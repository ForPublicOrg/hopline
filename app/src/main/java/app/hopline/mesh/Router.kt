package app.hopline.mesh

import app.hopline.core.Crypto
import app.hopline.core.Names
import org.json.JSONArray
import org.json.JSONObject

/** How the router talks to the radio layer (Nearby Connections on a phone, a fake in tests). */
interface Transport {
    /** Send bytes over one link. Returns a payload id (so the router can learn when it was delivered), or -1. */
    fun send(linkId: String, bytes: ByteArray): Long
    fun disconnect(linkId: String)
}

interface RouterListener {
    fun onChanged()
    fun onMessage(m: Message)        // a new message this phone should show / notify about
    /** Run this request now: this phone is the helper (or it is my own and I have signal). */
    fun onErrandRequest(e: Errand)
    /** Stop working on [e]: someone else answered, the asker cancelled, or it expired. */
    fun onErrandAbort(e: Errand) {}
    /** One of MY requests got its answer — or failed, or expired. */
    fun onErrandAnswer(e: Errand) {}
    fun onFileReady(m: Message) {}   // every piece of m's attachment is on this phone — assemble it
    /** The group's name changed: a rename reached us, or we joined by typed code and learned it. */
    fun onGroupNamed(name: String, at: Long) {}
    /** Someone reacted to one of MY messages, live (never on restore). Empty emoji = taken back. */
    fun onReaction(m: Message, by: String, emoji: String) {}
    fun onLog(text: String) {}
}

/**
 * The mesh brain. Every phone runs one of these. It is deliberately simple:
 *
 *  - Every message is a signed envelope that is FLOODED to every link, with an id-based dedupe.
 *    With 5–40 phones sending text, flooding is cheaper than any routing protocol and has no
 *    routing tables to get stale while people walk around.
 *  - Every phone CARRIES every message for 48 h. When two phones link up they swap inventories
 *    and fill each other's gaps. That is what makes a chain that keeps breaking and re-forming
 *    still deliver everything — people walking between groups literally carry the backlog.
 *  - Photos and files ride the same flood as numbered chunks (~19 KB each, under the radio's
 *    32 KB payload cap). The chunks live in a ChunkStore (disk in the app) and are carried and
 *    gap-filled exactly like text, so an image can hop through phones whose owners never open it.
 *  - Receipts flow back the same way, so a sender sees "reached 7 of 9" truthfully — but only in
 *    small groups. In a crowd, per-phone receipts would be N² traffic, so phones stop sending
 *    them once the group outgrows RECEIPT_GROUP_LIMIT, and presence slows down as the crowd grows.
 *  - Requests for the internet travel the same way: carried with the group until a phone with
 *    signal picks one up, claimed live so only one phone spends its data, handed on when that
 *    phone goes quiet, and answered privately to the asker.
 *
 * Not thread-safe: call everything from one thread (the app uses the main thread).
 */
class Router(
    val me: Identity,
    val group: Group,
    private val transport: Transport,
    private val listener: RouterListener,
    val chunks: ChunkStore = MemoryChunkStore(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * One radio link. [token] is the Nearby connection's authentication token: identical on both
     * ends of THIS connection and different on any other, so a proof bound to it can't be relayed.
     */
    class Link(val id: String, val nodeId: String, var name: String, val since: Long, val token: String = "") {
        var authed = false
        var version = 1                  // from their hello; 1 = a 1.x client that doesn't carry files
        val myNonce: String = Crypto.randomId(16)
        var helloSeen = false
        var theirNonce = ""
        /** A proof that arrived before its hello — checked once the hello lands. */
        var pendingProof: String? = null
        /** A legacy (2.x) peer gets our proof only after we've verified theirs. */
        var owesLegacyProof = false
        /** Inventory reassembly: the round's part count, the parts seen, and the ids so far. */
        var invN = 0
        val invGot = HashSet<Int>()
        var invIds = HashSet<String>()
        /** Last time we re-offered our inventory on this link (periodic anti-entropy). */
        var lastSyncAt = 0L
        /** Last time any frame arrived on it — a silent "connected" link is a dead one. */
        var lastHeardAt = since
        /** Chunk ids still owed to this link from the last inventory swap, streamed a few at a time. */
        val fillQueue = ArrayDeque<String>()
        var fillInFlight = 0
    }

    val links = LinkedHashMap<String, Link>()
    val messages = ArrayList<Message>()
    private val messageById = HashMap<String, Message>()
    val people = LinkedHashMap<String, Person>()
    val errands = LinkedHashMap<String, Errand>()
    /** Requests some phone has answered or the asker cancelled: eid -> when we learned it. */
    private val doneErrands = LinkedHashMap<String, Long>()
    /** Requests this phone is working on right now (persisted, so a restart resumes them). */
    private val running = LinkedHashSet<String>()
    /** When this phone will claim an open request if nobody beats it to it: eid -> due time. */
    private val claims = HashMap<String, Long>()
    private val lastBeat = HashMap<String, Long>()
    private val runningSince = HashMap<String, Long>()
    /** Texts whose helper already opened their messaging app — never handed on automatically after that. */
    private val sendOpened = HashSet<String>()
    /** After saying "no" / giving one up, don't grab it straight back. */
    private val backoff = HashMap<String, Long>()

    /** File messages by attachment id, so an arriving chunk can find its meta. */
    private val filesByFid = HashMap<String, Message>()
    /** Files whose onFileReady already fired (or that this phone originated). */
    private val fileReadyFired = HashSet<String>()

    /** Store-and-forward memory: id -> envelope. */
    private val carry = LinkedHashMap<String, Envelope>()
    /** When each carried envelope's 48 h started, as far as THIS phone can tell (see [birth]). */
    private val carryBorn = HashMap<String, Long>()
    private val seen = object : LinkedHashMap<String, Boolean>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 40_000
    }
    /** Presence and acks are never carried, so they get their own short memory and don't push
     *  real messages out of [seen]. */
    private val liveSeen = object : LinkedHashMap<String, Boolean>(256, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 4_000
    }
    private val pendingPayloads = HashMap<Long, List<String>>()
    /** Payloads that belong to a link's chunk-fill window: payload id -> link id. */
    private val fillPayloads = HashMap<Long, String>()
    /** Messages deleted on this phone ("Delete for me"): id -> when. Never shown again. */
    private val hidden = LinkedHashMap<String, Long>()
    /**
     * Messages that have just left the live window (see [spill]), oldest first, waiting for the app
     * to file them in the group's history. Until [takeOverflow] hands them over they are still part
     * of every [snapshot], so a router nobody drains loses nothing.
     */
    private val overflow = ArrayList<Message>()
    private val overflowIds = HashSet<String>()
    /**
     * Messages handed to the history ([takeOverflow]): id -> when. Like [hidden], an id in here is
     * never taken in again — a friend still carrying the message must not make it pop up as new.
     */
    private val spilled = LinkedHashMap<String, Long>()

    var hasInternet = false
    var shareInternet = true
    var battery = -1
    /** What this phone can do for the group right now (Errand.CAP_*), worked out by the app. */
    var myCaps = 0
        private set
    /** Set while I share live location; rides presence beacons so it costs no extra envelopes. */
    var myLoc: Loc? = null

    /** True when something worth saving changed since the last [takeDirty]. Presence churn isn't. */
    private var dirty = false
    fun takeDirty(): Boolean = dirty.also { dirty = false }
    private fun touched() { dirty = true }

    /** Reactions that arrived before the message they belong to (links deliver in any order). */
    private val pendingReactions = LinkedHashMap<String, ArrayList<Triple<String, String, Long>>>()

    // ---------------------------------------------------------------- transport events

    fun onLinkUp(linkId: String, nodeId: String, name: String, token: String = "") {
        if (nodeId == me.id) { transport.disconnect(linkId); return }
        links.remove(linkId)
        val link = Link(linkId, nodeId, Names.clean(name), clock(), token)
        links[linkId] = link
        // "v" tells peers what we speak; 1.x clients ignore unknown fields.
        // No name here: anyone who copies the group's (public) fingerprint can open a link, and the
        // name only travels once the link has proved it knows the code (signed presence).
        sendFrame(link, JSONObject().put("t", "hello").put("id", me.id).put("nonce", link.myNonce).put("v", VERSION))
        listener.onLog("link up $linkId")
    }

    fun onLinkDown(linkId: String) {
        val link = links.remove(linkId) ?: return
        // Payloads still in flight on a dead link will never be acked — forget them.
        fillPayloads.entries.removeAll { it.value == link.id }
        refreshDirect(); listener.onLog("link down $linkId"); listener.onChanged()
    }

    fun onPayloadSent(payloadId: Long) {
        fillPayloads.remove(payloadId)?.let { linkId ->
            links[linkId]?.let { it.fillInFlight = maxOf(0, it.fillInFlight - 1); pumpFill(it) }
        }
        val ids = pendingPayloads.remove(payloadId) ?: return
        var changed = false
        for (id in ids) {
            val m = messageById[id] ?: continue
            if (m.from == me.id && m.status == Message.QUEUED) { m.status = Message.SENT; changed = true }
        }
        if (changed) { touched(); listener.onChanged() }
    }

    fun onPayloadFailed(payloadId: Long) {
        pendingPayloads.remove(payloadId)
        fillPayloads.remove(payloadId)?.let { linkId ->
            links[linkId]?.let { it.fillInFlight = maxOf(0, it.fillInFlight - 1); pumpFill(it) }
        }
    }

    fun onBytes(linkId: String, bytes: ByteArray) {
        val link = links[linkId] ?: return
        // A malformed frame from a buggy (or hostile) peer must never take this phone down.
        try { onFrame(link, JSONObject(String(bytes, Charsets.UTF_8))) }
        catch (e: Exception) { listener.onLog("bad frame dropped: ${e.message}") }
        // Requests that landed in this frame start only now, after the whole frame — a gap-fill
        // batch that carries both a request and its answer must not start the request.
        if (claims.isNotEmpty()) pollErrands()
    }

    private fun onFrame(link: Link, frame: JSONObject) {
        link.lastHeardAt = clock()
        if (link.authed) people[link.nodeId]?.let { it.lastSeen = maxOf(it.lastSeen, clock()) }
        when (frame.optString("t")) {
            "hello" -> onHello(link, frame)
            "proof" -> {
                val proof = frame.optString("proof")
                if (!link.helloSeen) { link.pendingProof = proof; return }
                checkProof(link, proof)
            }
            "inv" -> {
                if (!link.authed) return
                val n = frame.optInt("n", 1).coerceIn(1, MAX_INV_PARTS)
                val i = frame.optInt("i", 0)
                if (i !in 0 until n) return
                // A new round starts at part 0; a lost part must not let two rounds mix forever.
                if (i == 0 || link.invN != n) { link.invN = n; link.invGot.clear(); link.invIds = HashSet() }
                val ids = frame.optJSONArray("ids") ?: JSONArray()
                for (k in 0 until ids.length()) {
                    if (link.invIds.size >= MAX_INV_IDS) break
                    ids.optString(k, "").takeIf { it.isNotEmpty() && it.length <= 64 }?.let { link.invIds.add(it) }
                }
                link.invGot.add(i)
                if (link.invGot.size >= n) {
                    val have = link.invIds
                    link.invN = 0; link.invGot.clear(); link.invIds = HashSet()
                    fillGaps(link, have)
                }
            }
            "fill" -> {
                if (!link.authed) return
                val envs = frame.optJSONArray("envs") ?: return
                for (i in 0 until envs.length()) {
                    val e = envs.optJSONObject(i) ?: continue
                    // 2.0/2.1 still spend a hop on every hand-off; backlog must not die at the cap.
                    if (e.optInt("h", 0) >= Envelope.MAX_HOPS) e.put("h", Envelope.MAX_HOPS - 1)
                    receive(link, Envelope(e), fill = true)
                }
            }
            "env" -> {
                if (!link.authed) return
                val e = frame.optJSONObject("e") ?: return
                receive(link, Envelope(e), fill = false)
            }
        }
    }

    // ---------------------------------------------------------------- the link handshake

    /**
     * Each side proves it knows the group key by signing the other's nonce. From version 4 the
     * proof is also bound to this exact connection, so a stranger in range can't sit between two
     * members and pass one member's proof off as their own. A 2.x peer can't do that, so it gets
     * the classic proof — but only after it has proven itself first, so we are never the oracle.
     */
    private fun onHello(link: Link, frame: JSONObject) {
        if (frame.optString("id") != link.nodeId) { drop(link, "hello id mismatch"); return }
        if (link.helloSeen) return
        val nonce = frame.optString("nonce", "")
        if (nonce.length !in 8..64 || nonce == link.myNonce) { drop(link, "bad nonce"); return }
        link.helloSeen = true
        link.theirNonce = nonce
        link.name = Names.clean(frame.optString("name", "")).ifEmpty { link.name }
        link.version = frame.optInt("v", 1).coerceIn(1, 99)
        // A phone that once spoke 4+ never gets to fall back to the relayable proof.
        if (link.version < BOUND_PROOF_VERSION && (people[link.nodeId]?.ver ?: 0) >= BOUND_PROOF_VERSION) {
            drop(link, "version downgrade"); return
        }
        when {
            bound(link) -> sendFrame(link, JSONObject().put("t", "proof").put("proof", boundProof(link.theirNonce, me.id, link.nodeId, link.token)))
            link.version >= BOUND_PROOF_VERSION && me.id < link.nodeId -> sendLegacyProof(link)   // no token: lower id goes first
            link.authed -> sendLegacyProof(link)
            else -> link.owesLegacyProof = true
        }
        link.pendingProof?.let { link.pendingProof = null; checkProof(link, it) }
    }

    private fun bound(link: Link): Boolean = link.version >= BOUND_PROOF_VERSION && link.token.isNotEmpty()

    private fun boundProof(verifierNonce: String, prover: String, verifier: String, token: String): String =
        Crypto.hmacHex(group.key, "p4|$token|$verifierNonce|$prover|$verifier")

    private fun sendLegacyProof(link: Link) {
        sendFrame(link, JSONObject().put("t", "proof").put("proof", Crypto.hmacHex(group.key, link.theirNonce + "|" + me.id)))
    }

    private fun checkProof(link: Link, proof: String) {
        val expect = if (bound(link)) boundProof(link.myNonce, link.nodeId, me.id, link.token)
                     else Crypto.hmacHex(group.key, link.myNonce + "|" + link.nodeId)
        if (!Crypto.constantTimeEquals(expect, proof)) { drop(link, "bad proof"); return }
        if (link.authed) return
        link.authed = true
        if (link.owesLegacyProof) { link.owesLegacyProof = false; sendLegacyProof(link) }
        // One phone, one link: an older link claiming the same phone is stale (or an impostor).
        for (other in links.values.toList()) if (other !== link && other.nodeId == link.nodeId) drop(other, "replaced by a newer link")
        // The hello's name is only a stand-in until a signed envelope from them says otherwise —
        // stamping it with OUR clock would let it beat a genuine rename.
        val who = people.getOrPut(link.nodeId) { Person(link.nodeId) }
        if (who.name.isEmpty()) who.name = link.name
        who.lastSeen = maxOf(who.lastSeen, clock())
        if (link.version > who.ver) { who.ver = link.version; touched() }
        refreshDirect()
        sendInventory(link)
        link.lastSyncAt = clock()
        sendPresence()
        listener.onLog("link authed ${link.id} v${link.version}")
        listener.onChanged()
    }

    private fun drop(link: Link, why: String) {
        listener.onLog("dropping link ${link.id}: $why")
        links.remove(link.id)
        fillPayloads.entries.removeAll { it.value == link.id }
        transport.disconnect(link.id)
        refreshDirect()
    }

    // ---------------------------------------------------------------- sync on link-up

    private fun sendInventory(link: Link) {
        // 1.x peers don't carry chunks, so telling them about ours only wastes their parser.
        val ids = if (link.version >= 2) carry.keys.toList() + chunks.advertised() else carry.keys.toList()
        val chunk = 700
        val parts = maxOf(1, (ids.size + chunk - 1) / chunk)
        for (p in 0 until parts) {
            val slice = ids.subList(p * chunk, minOf(ids.size, (p + 1) * chunk))
            sendFrame(link, JSONObject().put("t", "inv").put("n", parts).put("i", p).put("ids", JSONArray(slice)))
        }
    }

    private fun fillGaps(link: Link, theyHave: Set<String>) {
        // Text-sized envelopes go now, batched. Never more than one batch is in memory.
        var sent = 0
        var batch = JSONArray(); var size = 0; val ids = ArrayList<String>()
        fun flush() {
            if (batch.length() == 0) return
            val pid = sendFrame(link, JSONObject().put("t", "fill").put("envs", batch))
            if (pid >= 0) pendingPayloads[pid] = ids.toList()
            batch = JSONArray(); size = 0; ids.clear()
        }
        for (env in carry.values) {
            if (env.id in theyHave) continue
            // A 1.x client can't show or carry file messages — don't re-send them on every link-up.
            if (link.version < 2 && env.kind == Envelope.FILE) continue
            // Pre-2.1 clients don't carry reactions, so their inventory never lists them: sending
            // the reaction backlog would repeat on every link-up. They still relay live ones.
            if (link.version < 3 && env.kind == Envelope.REACT) continue
            // Backlog is deduped by id, so a hand-off is not a routing hop: send it at its stored
            // hop count. Spending the flood's hop budget here would let a message that has been
            // carried far (a courier walking back and forth, gap-fills chained through late joiners)
            // silently die at MAX_HOPS mid-group — the very thing the 48 h carry exists to prevent.
            val copy = env.copy()
            val bytes = copy.json.toString().toByteArray(Charsets.UTF_8).size
            if (size + bytes > 24000) flush()                   // Nearby caps a bytes payload at 32 KB
            batch.put(copy.json); size += bytes; ids.add(env.id)
            sent++
        }
        flush()
        // File chunks are big (~19 KB each), so they are queued and streamed a few at a time as
        // the radio confirms delivery — a phone with a 48h photo backlog must not dump it all
        // into one link-up. 1.x peers don't carry chunks at all; they still relay live traffic.
        if (link.version >= 2) {
            val queued = HashSet(link.fillQueue)
            for (id in chunks.ids()) if (id !in theyHave && id !in queued) link.fillQueue.addLast(id)
            pumpFill(link)
            if (link.fillQueue.isNotEmpty() || link.fillInFlight > 0) sent += link.fillQueue.size + link.fillInFlight
        }
        if (sent > 0) listener.onLog("filling $sent for ${link.id}")
    }

    /** Keep a small window of chunk envelopes in flight to one link; the ack pulls the next one. */
    private fun pumpFill(link: Link) {
        while (link.fillInFlight < FILL_WINDOW && link.fillQueue.isNotEmpty()) {
            val id = link.fillQueue.removeFirst()
            val env = chunks.get(id) ?: continue
            val copy = env.copy()   // backlog hand-off, not a routing hop — don't spend the hop budget
            val pid = sendFrame(link, JSONObject().put("t", "env").put("e", copy.json))
            if (pid < 0) { link.fillQueue.clear(); return }     // link is gone
            link.fillInFlight++
            fillPayloads[pid] = link.id
        }
    }

    // ---------------------------------------------------------------- receiving

    private fun receive(from: Link?, env: Envelope, fill: Boolean) {
        try {
            val id = env.id
            if (seen.containsKey(id) || liveSeen.containsKey(id)) {
                // Nothing new — but if it is one this phone knows and has stopped carrying, the
                // phone handing it over will go on doing so until it is carried here again.
                if (!carry.containsKey(id) && (env.origin == me.id || id in hidden || id in spilled)) carryAgain(env)
                return
            }
            // Ids become map keys, inventory entries and (for chunks) file names on disk.
            if (!ENVELOPE_ID.matches(id) || id.contains("..")) { listener.onLog("bad envelope id dropped"); return }
            if (env.origin == me.id) { carryAgain(env); return }
            // The live flood has a hop ceiling; backlog and file pieces are deduped by id instead.
            if (!fill && env.kind != Envelope.CHUNK && env.hops >= Envelope.MAX_HOPS) return
            if (!env.verify(group.key)) { listener.onLog("forged/garbled envelope dropped"); return }
            markSeen(env)
            if (env.kind == Envelope.CHUNK) {
                // A chunk's id names a file on disk: it must be exactly f.<fid>.<index> for a sane
                // fid and index, or a crafted envelope could write (and later delete) anywhere.
                if (!validChunk(env)) { listener.onLog("malformed chunk dropped"); return }
                // If the disk write failed (storage full), forget we saw it so a peer can refill later.
                if (!chunks.put(env)) seen.remove(id)
            } else if (env.kind in Envelope.CARRIED) addCarry(env)
            val live = env.kind == Envelope.PRESENCE || env.kind == Envelope.ERRAND_ACK
            touchPerson(env.origin, env.originName, env.ts, seenAt = if (live) clock() else minOf(env.ts, clock()))
            process(env)
            forward(env, from)
        } catch (e: Exception) {
            // A malformed envelope from a buggy client must never take the whole mesh down with it.
            listener.onLog("garbled envelope dropped: ${e.message}")
        }
    }

    private fun markSeen(env: Envelope) {
        if (env.kind == Envelope.PRESENCE || env.kind == Envelope.ERRAND_ACK) liveSeen[env.id] = true else seen[env.id] = true
    }

    /**
     * An envelope this phone has no use for — one of its own, or the envelope of a message it
     * deleted or filed away — handed over by a friend because this phone's inventory doesn't list
     * it. That is the state leaving a group and joining it again leaves behind: the backlog went
     * with the leaving, while the group still carries my receipts, my reactions, my requests, the
     * pieces of my photos. Ignoring such an envelope (as this phone always did with its own) makes
     * the friend offer it again at every sync, a minute apart, until its time runs out — every
     * receipt of a day, every piece of a photo, over and over.
     *
     * So it is carried again, exactly as it would have been had this phone never left — and that
     * is all. It is not read, so no message comes back that was deleted here, none is shown twice,
     * nothing is notified, answered or run; and it is not passed on, because whoever handed it
     * over got it from the flood the first time round.
     *
     * Only inside its time, by its own stamp: a receipt for a day, anything else for 48 h. Never
     * longer than it would have lived here anyway — and a stamp from the future says nothing about
     * age, so that one is left alone. File pieces go to the piece store, which keeps its own time.
     *
     * A message of mine still marked unsent whose envelope a friend hands back did get out — that
     * friend has it. It reads "sent" from here on, and the tick has nothing to send again.
     */
    private fun carryAgain(env: Envelope) {
        val id = env.id
        val mine = env.origin == me.id
        if (env.kind == Envelope.CHUNK) {
            if (!mine || chunks.has(id)) return
            if (!env.verify(group.key)) { listener.onLog("forged/garbled envelope dropped"); return }
            if (!validChunk(env)) { listener.onLog("malformed chunk dropped"); return }
            seen[id] = true
            if (!chunks.put(env)) seen.remove(id)
            return
        }
        // Presence and "I'm on it" are never carried: an echo of mine is only remembered, as before.
        if (env.kind !in Envelope.CARRIED) { if (mine) markSeen(env); return }
        if (carry.containsKey(id) || !ENVELOPE_ID.matches(id) || id.contains("..")) return
        val now = clock()
        val limit = if (env.kind == Envelope.RECEIPT) RECEIPT_MS else CARRY_MS
        if (env.ts > now + FUTURE_SLACK_MS || now - env.ts > limit) return
        if (!env.verify(group.key)) { listener.onLog("forged/garbled envelope dropped"); return }
        markSeen(env)
        carry[id] = env
        carryBorn[id] = minOf(env.ts, now)
        touched()
        if (!mine) return
        val m = messageById[id] ?: return
        if (m.from == me.id && m.status == Message.QUEUED) { m.status = Message.SENT; listener.onChanged() }
    }

    /**
     * When an envelope's 48 h began, by this phone's reckoning: the sender's stamp, but never later
     * than now (a fast clock must not make it immortal), and never more than a day before now (a
     * phone whose clock reset to 1970 must still get its messages carried).
     */
    private fun birth(env: Envelope): Long {
        val now = clock()
        return maxOf(minOf(env.ts, now), now - CARRY_MS / 2)
    }

    private fun addCarry(env: Envelope) {
        carry[env.id] = env
        carryBorn[env.id] = birth(env)
        touched()
    }

    private fun validChunk(env: Envelope): Boolean {
        val p = env.payload
        val fid = p.optString("fid", "")
        val i = p.optInt("i", -1)
        if (!Attachment.validFid(fid) || i !in 0 until MAX_CHUNKS) return false
        if (env.id != Envelope.chunkId(fid, i)) return false
        return p.optString("d", "").length <= CHUNK_RAW * 2
    }

    private fun process(env: Envelope) {
        val p = env.payload
        val fromName = Names.clean(env.originName)
        when (env.kind) {
            Envelope.CHAT -> {
                val renamed = p.optString("gn", "")
                if (renamed.isNotEmpty()) { applyRename(env, renamed, fromName); return }
                val m = addMessage(Message(env.id, Envelope.CHAT, env.origin, fromName, null, p.optString("text").take(MAX_TEXT), env.ts,
                    loc = Loc.fromJson(p.optJSONObject("loc")), quote = Quote.fromJson(p.optJSONObject("re")),
                    mentions = Message.mentionsFromJson(p.optJSONArray("mn"))).also { it.arrivedAt = clock() }) ?: return
                // In a small group every phone confirms receipt ("reached 7 of 9"). In a crowd of
                // hundreds that would be an N-squared flood, so big groups skip chat receipts.
                if (activePeople() < RECEIPT_GROUP_LIMIT) sendReceipt(env.id, env.origin)
                listener.onMessage(m)
            }
            Envelope.DM -> {
                // An answer to an internet request rides a private message. Every phone that sees
                // one learns the request is answered (so no other helper starts it); only the
                // asker opens it — and it never becomes a chat bubble.
                p.optJSONObject("er")?.let { er -> onAnswerEnvelope(env, er); return }
                if (env.to != me.id) return
                val m = addMessage(Message(env.id, Envelope.DM, env.origin, fromName, me.id, p.optString("text").take(MAX_TEXT), env.ts,
                    loc = Loc.fromJson(p.optJSONObject("loc")), quote = Quote.fromJson(p.optJSONObject("re")),
                    mentions = Message.mentionsFromJson(p.optJSONArray("mn"))).also { it.arrivedAt = clock() }) ?: return
                sendReceipt(env.id, env.origin); listener.onMessage(m)
            }
            Envelope.FILE -> {
                // A private copy: the envelope's own JSON is signed and carried on, never edited.
                val att = p.optJSONObject("att")?.let { Attachment(JSONObject(it.toString())) } ?: return
                // A signed-but-absurd attachment (a crafted client) must not wedge every phone.
                if (!Attachment.validFid(att.fid) || att.chunks !in 1..MAX_CHUNKS || att.size !in 1..MAX_FILE) {
                    listener.onLog("absurd attachment dropped"); return
                }
                // The inline thumbnail is decoded on every phone; a huge one is a decompression bomb.
                if (att.thumb.length > MAX_THUMB_B64) att.json.remove("tb")
                if (env.to != null && env.to != me.id) return   // someone else's private photo: carry, don't show
                val to = if (env.to != null) me.id else null
                val m = Message(env.id, Envelope.FILE, env.origin, fromName, to, p.optString("text").take(MAX_CAPTION), env.ts, att,
                    quote = Quote.fromJson(p.optJSONObject("re")), mentions = Message.mentionsFromJson(p.optJSONArray("mn")))
                m.arrivedAt = clock()
                if (addMessage(m) == null) return
                filesByFid[att.fid] = m
                checkFileReady(att.fid)
                listener.onMessage(m)
            }
            Envelope.CHUNK -> {
                val fid = p.optString("fid")
                if (fid.isNotEmpty() && filesByFid.containsKey(fid)) checkFileReady(fid)
                listener.onChanged()
            }
            Envelope.REACT -> applyReactionEnvelope(env, live = true)
            Envelope.RECEIPT -> {
                val m = messageById[p.optString("m")] ?: return
                if (m.from != me.id) return
                val by = p.optString("by"); if (by.isEmpty()) return
                // Only a phone can say its own phone has it.
                if (by != env.origin) return
                if (!m.reached.add(by) && m.status != Message.QUEUED) return
                if (m.to != null && by == m.to) m.status = Message.DELIVERED
                else if (m.status == Message.QUEUED) m.status = Message.SENT
                touched()
                listener.onChanged()
            }
            Envelope.PRESENCE -> {
                val person = people[env.origin] ?: return
                person.skew = env.ts - clock(); person.skewKnown = true
                person.hasInternet = p.optBoolean("net", false)
                person.hops = env.hops
                person.battery = p.optInt("bat", -1).coerceIn(-1, 100)
                person.ev = p.optInt("ev", 0).coerceIn(0, 99)
                person.cap = if (person.ev >= Errand.EV) p.optInt("cap", 0) and CAP_MASK else 0
                val v = p.optInt("v", 0)
                if (v > person.ver) { person.ver = v.coerceAtMost(99); touched() }
                // Live location rides presence: present while they share, gone the beacon after they stop.
                // Only ever move forward — floods can replay an older beacon after a newer one — but
                // clamp the clock so a forged far-future beacon can't pin a position for hours.
                val locTs = minOf(env.ts, clock() + FUTURE_SLACK_MS)
                if (locTs >= person.locAt) {
                    person.loc = Loc.fromJson(p.optJSONObject("loc")); person.locAt = locTs; person.locHeardAt = clock()
                }
                // The group's name rides presence too, so it keeps spreading after a rename's 48 h
                // carry ends. 2.1 phones send no "gt" (it reads 0): they can fill an empty name,
                // never undo a real rename with the stale name they keep beaconing.
                // When the name was set travels as an AGE ("ga": how long ago, by the sender's own
                // clock), so a phone whose clock runs hours ahead can't stamp its rename into the
                // future and undo every later one. Without an age, a stamp from the future is a
                // wrong clock, not a rename: it counts as unknown.
                val gn = Names.clean(p.optString("gn"), Names.MAX_GROUP)
                val ga = p.optLong("ga", -1)
                val gt = if (ga >= 0) (clock() - ga).coerceAtLeast(1L)
                         else p.optLong("gt", 0).let { if (it > clock() + FUTURE_SLACK_MS) 0L else it }
                if (gn.isNotEmpty()) adoptGroupName(gn, gt, p.optInt("gv", 0).coerceIn(0, MAX_NAME_V))
                // Someone who can help just came into range: point my waiting requests at them now
                // instead of letting them wait out the slow "anyone" claim window.
                if (person.cap != 0) for (e in errands.values.toList()) if (e.from == me.id && e.status == Errand.WAITING) askerTick(e, clock())
                listener.onChanged()
            }
            Envelope.ERRAND -> onErrandEnvelope(env, fromName)
            Envelope.ERRAND_ACK -> onAckEnvelope(env)
            Envelope.ERRAND_RESULT -> onLegacyResult(env, fromName)
        }
    }

    // ---------------------------------------------------------------- group name

    /**
     * Last-writer-wins on the renamer's clock (ties: the alphabetically larger name, so every phone
     * settles on the same one). An unknown time (0) only ever fills an empty name.
     */
    private fun adoptGroupName(name: String, at: Long, v: Int = 0): Boolean {
        // A later rename (more renames behind it) always wins; the clock only settles two renames
        // made without seeing each other, and the name itself a dead heat — so every phone agrees.
        // Times a beacon's travel apart (a relayed age arrives a little late) count as the same
        // moment, so relay delay never picks the winner.
        val later = when {
            at > group.nameAt + SAME_TIME_MS -> true
            at < group.nameAt - SAME_TIME_MS -> false
            else -> at > 0 && name > group.name
        }
        val newer = group.name.isEmpty() || v > group.nameV || (v == group.nameV && later)
        if (!newer || name == group.name) {
            // Same name: learn only what is new — a higher count, or a time where we had none (a
            // QR's name confirmed by the renamer). A relayed age is always a little later than the
            // truth; learning it every beacon would creep forward and rewrite the store forever.
            if (name == group.name && (v > group.nameV || (v == group.nameV && group.nameAt == 0L && at > 0L))) {
                group.nameV = maxOf(group.nameV, v)
                if (group.nameAt == 0L || v > group.nameV) group.nameAt = minOf(at, clock())
                touched(); listener.onGroupNamed(name, group.nameAt)
            }
            return false
        }
        group.name = name
        group.nameV = v
        group.nameAt = minOf(at, clock())
        touched()
        listener.onGroupNamed(name, group.nameAt)
        return true
    }

    /**
     * A name from an invite link or QR, for a group this phone has no name for yet. Unstamped (0),
     * so it never replaces a name we know and any real rename beats it.
     */
    fun adoptHintName(raw: String): Boolean {
        val name = Names.clean(raw, Names.MAX_GROUP)
        if (name.isEmpty() || group.name.isNotEmpty()) return false
        return adoptGroupName(name, 0)
    }

    /** A rename rides a normal, carried CHAT envelope: 2.1 phones show its text as a chat line. */
    private fun applyRename(env: Envelope, raw: String, fromName: String) {
        val name = Names.clean(raw, Names.MAX_GROUP)
        if (name.isEmpty()) return
        // On our clock: the renamer's stamp less how far their clock runs ahead (known from their
        // presence). Never later than now — a rename can't come from the future — so the next
        // honest rename always wins.
        val skew = people[env.origin]?.takeIf { it.skewKnown }?.skew ?: 0L
        adoptGroupName(name, minOf(env.ts - skew, clock()), env.payload.optInt("gv", 0).coerceIn(0, MAX_NAME_V))
        addMessage(Message(env.id, Message.NOTICE, env.origin, fromName, null, name, env.ts).also { it.arrivedAt = clock() })
        listener.onChanged()
    }

    /** Rename the group for everyone. Returns null if the name is empty or unchanged. */
    fun renameGroup(raw: String): Message? {
        val name = Names.clean(raw, Names.MAX_GROUP)
        if (name.isEmpty() || name == group.name) return null
        val v = (group.nameV + 1).coerceAtMost(MAX_NAME_V)
        val env = newEnvelope(Envelope.CHAT, JSONObject().put("text", "✏️ renamed the group to “$name”").put("gn", name).put("gv", v))
        group.name = name
        group.nameAt = env.ts
        group.nameV = v
        listener.onGroupNamed(name, env.ts)
        val m = Message(env.id, Message.NOTICE, me.id, me.name, null, name, env.ts)
        addMessage(m); originate(env); sendPresence(); listener.onChanged()
        return m
    }

    /** Change my display name: it rides every envelope from now on and my next presence. */
    fun rename(raw: String): Boolean {
        val name = Names.clean(raw)
        if (name.isEmpty() || name == me.name) return false
        me.name = name
        sendPresence()
        listener.onChanged()
        return true
    }

    // ---------------------------------------------------------------- reactions

    /** Shared by live receive and restore: point a reaction at its message, or hold it. */
    private fun applyReactionEnvelope(env: Envelope, live: Boolean) {
        if (env.to != null && env.to != me.id) return   // a private chat's reaction: carry, don't show
        val p = env.payload
        val target = p.optString("m"); if (target.isEmpty() || target.length > 40) return
        val emoji = p.optString("e", "").take(Message.MAX_EMOJI)
        val m = messageById[target]
        if (m == null) {
            // Deleted here, or filed in the history: that message is never coming (back) to wait for.
            if (target in hidden || target in spilled || target in overflowIds) return
            // The message may still be hopping toward us — hold the reaction for it, bounded.
            val list = pendingReactions.getOrPut(target) { ArrayList() }
            if (list.size < 40) list.add(Triple(env.origin, emoji, env.ts))
            while (pendingReactions.size > 500) pendingReactions.remove(pendingReactions.keys.first())
        } else if (m.applyReaction(env.origin, emoji, env.ts)) {
            touched()
            if (live && m.from == me.id && env.origin != me.id) listener.onReaction(m, env.origin, emoji)
            listener.onChanged()
        }
    }

    // ---------------------------------------------------------------- files

    /** How many of a file's pieces this phone has. */
    fun fileProgress(att: Attachment): Int {
        var got = 0
        for (i in 0 until att.chunks) if (chunks.has(Envelope.chunkId(att.fid, i))) got++
        return got
    }

    fun fileComplete(att: Attachment): Boolean {
        for (i in 0 until att.chunks) if (!chunks.has(Envelope.chunkId(att.fid, i))) return false
        return true
    }

    fun fileMessage(fid: String): Message? = filesByFid[fid]

    /** Mark a file as already on disk (my own sends, or assembled after restore). */
    fun markFileReady(fid: String) { fileReadyFired.add(fid) }

    /** Assembly failed after all — let the next chunk arrival (or restart) try again. */
    fun unmarkFileReady(fid: String) { fileReadyFired.remove(fid) }

    private fun checkFileReady(fid: String) {
        if (fid in fileReadyFired) return
        val m = filesByFid[fid] ?: return
        val att = m.att ?: return
        if (!fileComplete(att)) return
        fileReadyFired.add(fid)
        // The truthful moment for a file's ✓ is "the whole thing is on their phone", so the
        // receipt waits for the last piece, not the first.
        if (m.to == me.id || activePeople() < RECEIPT_GROUP_LIMIT) sendReceipt(m.id, m.from)
        listener.onFileReady(m)
    }

    /**
     * Photos and files would drown the radios a very large crowd shares, so they switch off
     * as the group grows — same self-discipline as receipts and presence.
     */
    fun canSendFiles(): Boolean = activePeople() < FILE_GROUP_LIMIT

    /**
     * Send a file that has already been shrunk and cut into base64 pieces (see Blobs on the app
     * side). The meta envelope is the visible message; the chunks flood behind it.
     */
    fun sendFile(att: Attachment, pieces: List<String>, caption: String, to: String? = null, quote: Quote? = null,
                 mentions: List<String> = emptyList()): Message {
        val mn = if (to == null) mentions.take(Message.MAX_MENTIONS) else emptyList()
        val text = caption.take(MAX_CAPTION)
        val p = JSONObject().put("text", text).put("att", att.json)
        quote?.let { p.put("re", it.toJson()) }
        if (mn.isNotEmpty()) p.put("mn", JSONArray(mn))
        val meta = newEnvelope(Envelope.FILE, p, to)
        val m = Message(meta.id, Envelope.FILE, me.id, me.name, to, text, meta.ts, att, quote = quote, mentions = mn)
            .also { it.status = Message.QUEUED }
        addMessage(m)
        filesByFid[att.fid] = m
        fileReadyFired.add(att.fid)   // the original is already on this phone
        originate(meta)
        for ((i, data) in pieces.withIndex()) {
            val c = JSONObject().put("id", Envelope.chunkId(att.fid, i)).put("k", Envelope.CHUNK)
                .put("o", me.id).put("on", me.name).put("ts", meta.ts).put("h", 0)
                .put("p", JSONObject().put("fid", att.fid).put("i", i).put("d", data))
            if (to != null) c.put("to", to)
            val env = Envelope(c).also { it.sign(group.key) }
            seen[env.id] = true
            chunks.put(env)
            forward(env, null)
        }
        listener.onChanged()
        return m
    }

    private fun forward(env: Envelope, except: Link?) {
        val out = env.copy(); out.hops = env.hops + 1
        // Every peer drops an envelope at the ceiling; sending it would only burn airtime.
        if (out.hops >= Envelope.MAX_HOPS && env.kind != Envelope.CHUNK) return
        val frame = JSONObject().put("t", "env").put("e", out.json)
        for (link in links.values) {
            if (!link.authed || link === except) continue
            val pid = sendFrame(link, frame)
            if (pid >= 0) pendingPayloads[pid] = listOf(env.id)
        }
    }

    private fun sendFrame(link: Link, frame: JSONObject): Long =
        transport.send(link.id, frame.toString().toByteArray(Charsets.UTF_8))

    // ---------------------------------------------------------------- my own actions

    private fun newEnvelope(kind: String, payload: JSONObject, to: String? = null): Envelope {
        val j = JSONObject().put("id", Crypto.randomId(12)).put("k", kind).put("o", me.id).put("on", me.name)
            .put("ts", clock()).put("h", 0).put("p", payload)
        if (to != null) j.put("to", to)
        return Envelope(j).also { it.sign(group.key) }
    }

    /** Inject one of my own envelopes: remember it, carry it, flood it. */
    private fun originate(env: Envelope) {
        markSeen(env)
        if (env.kind in Envelope.CARRIED) { carry[env.id] = env; carryBorn[env.id] = clock(); touched() }
        forward(env, null)
    }

    fun sendChat(text: String, quote: Quote? = null, mentions: List<String> = emptyList()): Message {
        val mn = mentions.take(Message.MAX_MENTIONS)
        val t = text.take(MAX_TEXT)
        val p = JSONObject().put("text", t)
        quote?.let { p.put("re", it.toJson()) }
        if (mn.isNotEmpty()) p.put("mn", JSONArray(mn))
        val env = newEnvelope(Envelope.CHAT, p)
        val m = Message(env.id, Envelope.CHAT, me.id, me.name, null, t, env.ts, quote = quote, mentions = mn)
            .also { it.status = Message.QUEUED }
        addMessage(m); originate(env); listener.onChanged()
        return m
    }

    fun sendDm(to: String, text: String, quote: Quote? = null): Message {
        val t = text.take(MAX_TEXT)
        val p = JSONObject().put("text", t)
        quote?.let { p.put("re", it.toJson()) }
        val env = newEnvelope(Envelope.DM, p, to)
        val m = Message(env.id, Envelope.DM, me.id, me.name, to, t, env.ts, quote = quote)
            .also { it.status = Message.QUEUED }
        addMessage(m); originate(env); listener.onChanged()
        return m
    }

    /**
     * React to a message; an empty emoji takes mine back. A reaction is its own tiny envelope —
     * carried and gap-filled like chat, so late joiners see it — and a private chat's reactions
     * stay between its two people via the same `to` rule as DMs.
     */
    fun sendReaction(target: Message, emoji: String) {
        val e = emoji.take(Message.MAX_EMOJI)
        val to = if (target.isGroup) null else (if (target.from == me.id) target.to else target.from)
        // Strictly after my previous one, even if this phone's clock was set back since.
        val ts = maxOf(clock(), (target.reactionTsOf(me.id) ?: 0L) + 1)
        val j = JSONObject().put("id", Crypto.randomId(12)).put("k", Envelope.REACT).put("o", me.id).put("on", me.name)
            .put("ts", ts).put("h", 0).put("p", JSONObject().put("m", target.id).put("e", e))
        if (to != null) j.put("to", to)
        val env = Envelope(j).also { it.sign(group.key) }
        target.applyReaction(me.id, e, ts)
        originate(env)
        listener.onChanged()
    }

    /**
     * Share a place. It rides a normal chat/DM envelope — tiny, so it works at any crowd size —
     * with a maps link in the text so old clients (and copies) still land on the right spot.
     */
    fun sendLocation(loc: Loc, to: String? = null): Message {
        val text = loc.fallbackText()
        val kind = if (to == null) Envelope.CHAT else Envelope.DM
        val env = newEnvelope(kind, JSONObject().put("text", text).put("loc", loc.toJson()), to)
        val m = Message(env.id, kind, me.id, me.name, to, text, env.ts, loc = loc).also { it.status = Message.QUEUED }
        addMessage(m); originate(env); listener.onChanged()
        return m
    }

    private fun sendReceipt(messageId: String, origin: String) {
        val r = JSONObject().put("id", "r.$messageId.${me.id}").put("k", Envelope.RECEIPT).put("o", me.id).put("on", me.name)
            .put("ts", clock()).put("h", 0).put("to", origin)
            .put("p", JSONObject().put("m", messageId).put("by", me.id))
        originate(Envelope(r).also { it.sign(group.key) })
    }

    fun sendPresence() {
        // "net" keeps its 2.x meaning — "I can fetch for you" — so older askers route around a
        // phone that is out of data budget or has sharing off.
        val p = JSONObject().put("n", me.name).put("net", myCaps and Errand.CAP_READ != 0).put("bat", battery)
            .put("gn", group.name).put("v", VERSION).put("ev", Errand.EV)
        if (myCaps != 0) p.put("cap", myCaps)
        if (group.nameAt > 0) { p.put("gt", group.nameAt); p.put("ga", (clock() - group.nameAt).coerceAtLeast(0L)) }
        if (group.nameV > 0) p.put("gv", group.nameV)
        myLoc?.let { p.put("loc", it.toJson()) }
        forward(newEnvelope(Envelope.PRESENCE, p).also { markSeen(it) }, null)
    }

    // ---------------------------------------------------------------- delete for me

    /**
     * "Delete for me": gone from this phone's chat for good — gap-fill can't bring it back. The
     * envelope is still carried for the others for its 48 h (the mesh depends on every phone
     * carrying), except a message of mine that never left this phone: that is simply unsent.
     */
    fun hideMessages(ids: Collection<String>): List<Message> {
        val gone = ArrayList<Message>()
        val now = clock()
        for (id in ids) {
            val m = messageById.remove(id) ?: continue
            messages.remove(m)
            hidden[id] = now
            gone.add(m)
            m.att?.let { filesByFid.remove(it.fid) }
            if (m.from == me.id && m.status == Message.QUEUED) { carry.remove(id); carryBorn.remove(id) }
        }
        if (gone.isNotEmpty()) { touched(); listener.onChanged() }
        return gone
    }

    /** Every message of one chat: the group chat (peer = null) or my private chat with [peer]. */
    fun chatMessages(peer: String?): List<Message> =
        if (peer == null) messages.filter { it.isGroup }
        else messages.filter { !it.isGroup && ((it.from == peer && it.to == me.id) || (it.from == me.id && it.to == peer)) }

    fun isHidden(id: String): Boolean = id in hidden

    /**
     * A line this phone writes into its own copy of the group chat: [Message.LEFT] or
     * [Message.REJOINED]. It has no envelope, so nothing can put it on the air — the inventory, the
     * gap-fill and the retry of unsent messages all work from carried envelopes — and it is neither
     * counted as unread nor notified. Null for any other kind, or when the very same line is
     * already there.
     *
     * [notBefore]: the line is stamped no earlier than this. A line that answers another ("You
     * rejoined", under "You left") has to sort after it whatever this phone's clock says now — a
     * clock that ran fast and was put right in between would otherwise file the answer above the
     * line it answers.
     */
    fun addLocalNotice(kind: String, notBefore: Long = 0): Message? {
        if (kind != Message.LEFT && kind != Message.REJOINED) return null
        val now = maxOf(clock(), notBefore)
        val m = addMessage(Message("local.$kind.$now", kind, me.id, me.name, null, "", now)) ?: return null
        listener.onChanged()
        return m
    }

    // ---------------------------------------------------------------- shared internet: helper side

    /** The app worked out what this phone can do for the group now (signal, sharing, budget). */
    fun setCaps(caps: Int) {
        val c = caps and CAP_MASK
        if (c == myCaps) return
        val gained = c and myCaps.inv()
        myCaps = c
        sendPresence()
        // Just got signal on the ridge? Last night's requests are waiting in the carry.
        if (gained != 0) for (e in errands.values) maybeScheduleClaim(e)
        listener.onChanged()
    }

    private fun onErrandEnvelope(env: Envelope, fromName: String) {
        val p = env.payload
        val eid = p.optString("eid", "")
        if (!ERRAND_ID.matches(eid)) return
        var e = errands[eid]
        // Only the asker can re-send or cancel their own request.
        if (e != null && e.from != env.origin) return
        val cancel = p.optBoolean("cancel", false)
        if (e == null) {
            if (cancel) { markDone(eid); return }
            if (eid in doneErrands) return
            val args = p.optJSONObject("args") ?: JSONObject()
            if (args.toString().length > MAX_ARGS) return
            val type = p.optString("type", "")
            val legacy = p.optInt("rv", 1) < Errand.EV
            // Other people's requests are kept only while this phone might help with them.
            val helper = p.optString("helper", "")
            if (legacy && helper != me.id) return
            e = Errand(eid, type, JSONObject(args.toString()), env.origin, fromName, minOf(p.optLong("at", env.ts), env.ts))
            e.rv = p.optInt("rv", 1)
            // A deadline on OUR clock: what the asker allowed, less however long it has already
            // travelled (by their clock, clamped so a wrong clock can't make it immortal).
            val ttl = (p.optLong("exp", 0) - env.ts).takeIf { it > 0 }?.coerceAtMost(MAX_ERRAND_TTL) ?: defaultTtl(type)
            val age = (clock() - minOf(p.optLong("at", env.ts), env.ts)).coerceIn(0, ttl)
            e.exp = clock() + ttl - age
            errands[eid] = e
            touched()
            pruneErrands()
        }
        if (env.ts < e.dispatchTs) return   // an older dispatch replayed by gap-fill: the newest wins
        e.dispatchTs = env.ts
        if (cancel) {
            if (e.isOpen) e.status = Errand.CANCELLED
            markDone(eid)
            stopWork(e)
            touched(); listener.onChanged()
            return
        }
        if (eid in doneErrands) { if (e.isOpen) e.status = Errand.DONE; return }
        val helper = p.optString("helper", "")
        p.optJSONArray("pick")?.let { a -> e.pick = (0 until minOf(a.length(), 3)).map { a.optString(it) }.filter { it.isNotEmpty() } }
        p.optJSONArray("ex")?.let { a -> for (i in 0 until minOf(a.length(), 20)) a.optString(i).takeIf { it.isNotEmpty() }?.let { e.tried.add(it) } }
        if (helper.isEmpty()) {
            // Re-opened by the asker: whoever had it went quiet.
            // (Or the asker's own phone tried it on its own signal and couldn't.)
            if (e.status != Errand.CLAIMED || e.leaseUntil <= clock() || e.helper == env.origin) { e.status = Errand.ASKED; e.helper = null; e.leaseUntil = 0 }
        } else {
            // A 2.x asker moved it to another phone: if it was ours, let it go (their old app
            // would hide the card the same way), so the same text isn't sent twice.
            if (helper != me.id && e.rv < Errand.EV && e.id in running) stopWork(e)
            e.helper = helper; e.helperName = Names.clean(p.optString("helperName")); e.status = Errand.ASKED
        }
        maybeScheduleClaim(e)
        listener.onChanged()
    }

    /**
     * Who does it when several phones could: the asker's first pick claims after ~1.5 s, the
     * second after ~9.5 s if the first said nothing, anyone else after 20–30 s. The short floor
     * lets a gap-fill that carries the answer land before anyone starts work on the request.
     */
    private fun maybeScheduleClaim(e: Errand) {
        if (e.from == me.id || !e.isOpen || e.id in doneErrands || e.id in running) return
        val now = clock()
        if (now >= e.exp) return
        val directed = e.helper == me.id
        if (e.helper != null && !directed && e.status != Errand.CLAIMED) return   // asked someone else by name
        if (e.status == Errand.CLAIMED && e.leaseUntil > now) return              // someone is on it
        // A text someone else took — even one whose phone went quiet — may already have been sent:
        // only the asker (or that phone saying "no") hands it on. Never a bystander on its own.
        if (e.type == Errand.SEND && e.status == Errand.CLAIMED && e.helper != null && !directed) return
        if (e.rv < Errand.EV && !directed) return
        if ((backoff[e.id] ?: 0) > now) return
        val wait = when {
            directed -> CLAIM_FIRST_MS
            e.pick.indexOf(me.id) >= 0 -> CLAIM_FIRST_MS + CLAIM_STEP_MS * e.pick.indexOf(me.id)
            me.id in e.tried -> return
            else -> CLAIM_OTHERS_MS + ((e.id + me.id).hashCode() and 0x7FFFFFFF) % 10_000L
        }
        // A legacy (2.x) asker directed it at us: we answer either way — with a polite "can't"
        // if this phone can't do it — because they will never re-route on their own.
        val need = Errand.capFor(e.type, e.args)
        if (!directed && (need == 0 || myCaps and need == 0)) return
        claims[e.id] = minOf(claims[e.id] ?: Long.MAX_VALUE, now + wait)
    }

    private fun canStillClaim(e: Errand): Boolean {
        if (e.from == me.id || !e.isOpen || e.id in doneErrands || e.id in running) return false
        val now = clock()
        if (now >= e.exp) return false
        if (e.status == Errand.CLAIMED && e.helper != me.id && e.leaseUntil > now) return false
        if (e.type == Errand.SEND && e.status == Errand.CLAIMED && e.helper != null && e.helper != me.id) return false
        if (e.helper != null && e.helper != me.id && e.status != Errand.CLAIMED) return false
        // Scheduled while this phone could do it; since then sharing went off, or signal went.
        val need = Errand.capFor(e.type, e.args)
        if (e.helper != me.id && (need == 0 || myCaps and need == 0)) return false
        return true
    }

    private fun startWork(e: Errand) {
        val now = clock()
        running.add(e.id); claims.remove(e.id); runningSince[e.id] = now
        e.status = Errand.CLAIMED; e.helper = me.id; e.helperName = me.name
        e.leaseUntil = now + leaseMs(e)
        lastBeat[e.id] = now
        if (announced(e)) sendAck(e, "claim")
        touched()
        listener.onErrandRequest(e)
    }

    /** Other phones know about it (someone else's request, or mine once it went out): they need claims and heartbeats. */
    private fun announced(e: Errand) = e.from != me.id || e.lastDispatchAt > 0

    private fun stopWork(e: Errand) {
        claims.remove(e.id); lastBeat.remove(e.id); runningSince.remove(e.id); sendOpened.remove(e.id)
        if (running.remove(e.id)) { touched(); listener.onErrandAbort(e) }
    }

    private fun leaseMs(e: Errand): Long = if (e.type == Errand.SEND) HUMAN_LEASE_MS else WORK_LEASE_MS
    private fun beatMs(e: Errand): Long = if (e.type == Errand.SEND) HUMAN_BEAT_MS else WORK_BEAT_MS

    private fun sendAck(e: Errand, st: String, why: String = "") {
        val p = JSONObject().put("eid", e.id).put("st", st).put("rq", e.from).put("lease", leaseMs(e) / 1000)
        if (why.isNotEmpty()) p.put("why", why.take(40))
        forward(newEnvelope(Envelope.ERRAND_ACK, p).also { markSeen(it) }, null)
    }

    private fun onAckEnvelope(env: Envelope) {
        val p = env.payload
        val e = errands[p.optString("eid", "")] ?: return
        val by = env.origin
        val now = clock()
        when (p.optString("st")) {
            "claim", "work" -> {
                if (!e.isOpen) return
                if (e.id in running && by != me.id) {
                    // Two phones grabbed it at once. The smaller id keeps it; the other lets go.
                    if (by < me.id) { running.remove(e.id); lastBeat.remove(e.id); sendAck(e, "yield", "dup"); listener.onErrandAbort(e) }
                    else return
                }
                val max = if (e.type == Errand.SEND) HUMAN_LEASE_MS else WORK_LEASE_MS
                e.status = Errand.CLAIMED; e.helper = by; e.helperName = people[by]?.name ?: ""
                e.leaseUntil = now + (p.optLong("lease", 90) * 1000).coerceIn(10_000, max)
                claims.remove(e.id)
                listener.onChanged()
            }
            "yield", "no" -> {
                if (e.helper == by) { e.helper = null; e.leaseUntil = 0; if (e.isOpen) e.status = Errand.ASKED }
                e.tried.add(by)
                touched()
                maybeScheduleClaim(e)
                listener.onChanged()
            }
        }
    }

    /**
     * The helper finished. [body] is what the asker's screen shows ("t" text, plus "l" links or
     * "r" search results); it travels gzip'd in ONE private envelope, trimmed to fit the radio.
     * Returns false when there is nobody to tell — someone else already answered, or it was
     * cancelled — so nothing is sent twice.
     */
    fun completeErrand(id: String, ok: Boolean, title: String, body: JSONObject, cost: Int = 0, why: String = ""): Boolean {
        val e = errands[id] ?: return false
        val wasRunning = running.remove(id)
        claims.remove(id); lastBeat.remove(id); runningSince.remove(id); sendOpened.remove(id)
        if (e.from == me.id) {
            // My own request, run on my own signal: nothing to send.
            if (!e.isOpen) return false
            applyAnswer(e, ok, title.take(200), Gz.pack(body), me.name, cost, why, body.optInt("part", 1), body.optInt("parts", 1),
                summary = body.optString("t").take(500))
            // It went out to the group earlier: the phones carrying it can let it go.
            if (e.lastDispatchAt > 0) dispatch(e, cancel = true)
            return true
        }
        if (id in doneErrands || !e.isOpen) { if (wasRunning) touched(); return false }
        markDone(id)
        e.status = if (ok) Errand.DONE else Errand.FAILED
        if (e.rv >= Errand.EV) sendPrivateAnswer(e, ok, title, body, cost, why)
        else sendLegacyResult(e, ok, title, body.optString("t"))
        listener.onChanged()
        return true
    }

    /** This phone can't do it after all (no signal any more, out of budget, a limit). Someone else may. */
    fun declineErrand(id: String, why: String) {
        val e = errands[id] ?: return
        running.remove(id); claims.remove(id); lastBeat.remove(id); runningSince.remove(id); sendOpened.remove(id)
        backoff[id] = clock() + BACKOFF_MS
        if (e.from == me.id) {
            // My own request and my own signal failed: hand it to the group instead.
            e.helper = null; e.leaseUntil = 0
            e.status = if (capableHelpers(e).isEmpty()) Errand.WAITING else Errand.ASKED
            dispatch(e)
        } else if (e.isOpen) {
            e.tried.add(me.id)
            if (e.helper == me.id) { e.helper = null; e.leaseUntil = 0; e.status = Errand.ASKED }
            if (e.rv < Errand.EV) {
                // A 2.x asker marks any answer "done" for good — so only say "can't" when it is
                // final (an unknown kind of request). A passing problem stays quiet; they can re-ask.
                if (why in PERMANENT) {
                    markDone(id); e.status = Errand.FAILED
                    sendLegacyResult(e, false, "Couldn't do it", "${me.name}'s phone can't do this kind of request. Ask someone with the latest Hopline.")
                } else if (why == "off" || why == "paused") {
                    // They asked this phone by name and would wait for it forever: say so, kindly.
                    markDone(id); e.status = Errand.FAILED
                    sendLegacyResult(e, false, "Couldn't do it", "${me.name}'s phone isn't sharing its internet right now. Ask someone else.")
                } else { errands.remove(id) }
            } else sendAck(e, "no", why)
        }
        touched(); listener.onChanged()
    }

    private fun sendPrivateAnswer(e: Errand, ok: Boolean, title: String, body: JSONObject, cost: Int, why: String) {
        val limit = if (activePeople() >= FILE_GROUP_LIMIT) MAX_ANSWER_Z_CROWD else MAX_ANSWER_Z
        var b = body
        var z = Gz.pack(b)
        // Too big for one radio frame: shorten the text until it fits (paragraph-cut, honestly marked).
        var guard = 0
        while (z.length > limit && guard++ < 12) {
            val t = b.optString("t")
            if (t.length < 200) { b = JSONObject(b.toString()).put("l", JSONArray()).put("r", JSONArray()) ; z = Gz.pack(b); if (z.length <= limit) break }
            val cut = (t.length * 0.75).toInt()
            val at = t.lastIndexOf("\n\n", cut).takeIf { it > cut / 2 } ?: cut
            b = JSONObject(b.toString()).put("t", t.substring(0, at).trimEnd() + "\n\n…")
            z = Gz.pack(b)
        }
        val summary = title.take(120) + if (b.optString("t").isNotEmpty()) "\n" + b.optString("t").take(280) else ""
        val er = JSONObject().put("eid", e.id).put("ok", ok).put("ty", e.type).put("title", title.take(200)).put("z", z).put("by", me.name)
        if (cost > 0) er.put("cost", cost)
        if (why.isNotEmpty()) er.put("why", why.take(40))
        if (b.optInt("parts", 1) > 1) { er.put("part", b.optInt("part", 1)); er.put("parts", b.optInt("parts", 1)) }
        originate(newEnvelope(Envelope.DM, JSONObject().put("text", summary).put("er", er), e.from))
    }

    private fun sendLegacyResult(e: Errand, ok: Boolean, title: String, text: String) {
        val env = newEnvelope(Envelope.ERRAND_RESULT, JSONObject().put("eid", e.id).put("ok", ok).put("title", title.take(200)).put("text", text.take(MAX_RESULT)))
        val m = Message(env.id, Message.SYSTEM, me.id, me.name, null, if (title.isEmpty()) text.take(MAX_RESULT) else "$title\n${text.take(MAX_RESULT)}", env.ts)
        m.errandId = e.id
        addMessage(m); originate(env)
    }

    private fun onAnswerEnvelope(env: Envelope, er: JSONObject) {
        val eid = er.optString("eid", "")
        if (!ERRAND_ID.matches(eid)) return
        val z = er.optString("z", "")
        if (z.length > MAX_ANSWER_Z + 1_000) return   // absurd: no honest helper sends that
        val e = errands[eid]
        if (e != null && e.from != me.id) {
            // Some other phone answered it: stand down.
            markDone(eid)
            if (e.isOpen) e.status = Errand.DONE
            stopWork(e)
            touched()
        } else if (e == null) markDone(eid)
        if (env.to != me.id || e == null || e.from != me.id) return
        val ok = er.optBoolean("ok", true)
        // Two helpers raced: the first answer stands. And a "couldn't" never closes a request that
        // is closed already. After leaving a group and joining it again, a friend hands the old
        // answers back (leaving let their envelopes go); the failure this phone was told about
        // then must not be announced, and dated, a second time. A real answer still gets through
        // to a request that had failed or run out.
        if (e.status == Errand.DONE || e.status == Errand.CANCELLED || (!e.isOpen && !ok)) { sendReceipt(env.id, env.origin); return }
        applyAnswer(e, ok, Names.clean(er.optString("title"), 200), z,
            Names.clean(er.optString("by")).ifEmpty { Names.clean(env.originName) }, er.optInt("cost", 0).coerceAtLeast(0),
            er.optString("why", "").take(40), er.optInt("part", 1), er.optInt("parts", 1),
            summary = env.payload.optString("text").take(500))
        sendReceipt(env.id, env.origin)
    }

    private fun applyAnswer(e: Errand, ok: Boolean, title: String, z: String, by: String, cost: Int, why: String,
                            part: Int, parts: Int, summary: String) {
        e.status = if (ok) Errand.DONE else Errand.FAILED
        e.title = title; e.answerZ = z; e.result = summary
        e.answeredAt = clock(); e.answeredBy = by; e.cost = cost; e.why = why
        e.part = part.coerceIn(1, 99); e.parts = parts.coerceIn(1, 99)
        e.leaseUntil = 0
        markDone(e.id)
        running.remove(e.id)
        touched()
        listener.onErrandAnswer(e)
        listener.onChanged()
    }

    /** A 2.x helper's public answer — or our own legacy answer coming back round. Shown in the group as before. */
    private fun onLegacyResult(env: Envelope, fromName: String) {
        val p = env.payload
        val eid = p.optString("eid")
        val title = Names.clean(p.optString("title"), 200); val text = p.optString("text").take(MAX_RESULT)
        val e = errands[eid]
        val firstAnswer = eid !in doneErrands
        markDone(eid)
        if (e != null) {
            if (e.from == me.id) {
                if (e.isOpen || firstAnswer) applyAnswer(e, p.optBoolean("ok", true), title, "", fromName, 0, "", 1, 1,
                    summary = if (title.isEmpty()) text else "$title\n$text")
            } else { if (e.isOpen) e.status = Errand.DONE; stopWork(e) }
        }
        // Two helpers raced on a 2.x request: one answer in the chat is enough.
        if (!firstAnswer && messages.any { it.errandId == eid }) return
        val m = addMessage(Message(env.id, Message.SYSTEM, env.origin, fromName, null,
            if (title.isEmpty()) text else "$title\n$text", env.ts).also { it.arrivedAt = clock() }) ?: return
        m.errandId = eid
        listener.onMessage(m)
    }

    private fun markDone(eid: String) {
        if (eid.isEmpty()) return
        claims.remove(eid)
        doneErrands[eid] = clock()
        touched()
    }

    // ---------------------------------------------------------------- shared internet: asker side

    /** Phones in range that can do this request for us right now, best first. */
    fun capableHelpers(e: Errand): List<Person> {
        val need = Errand.capFor(e.type, e.args)
        if (need == 0) return emptyList()
        return people.values.filter { it.ev >= Errand.EV && it.cap and need != 0 && it.id !in e.tried && isInRange(it) }
            .sortedWith(compareBy<Person> { it.hops }.thenByDescending { it.battery })
    }

    /** Phones with internet that run an older Hopline: they can read a page or send a text, publicly. */
    fun legacyHelpers(): List<Person> =
        people.values.filter { it.ev < Errand.EV && it.hasInternet && isInRange(it) }.sortedBy { it.hops }

    /** Everyone who can help with anything right now, nearest first — me first when I can. */
    fun helpers(): List<Person> {
        val now = clock()
        val list = people.values.filter { isInRange(it) && ((it.ev >= Errand.EV && it.cap != 0) || (it.ev < Errand.EV && it.hasInternet)) }
            .sortedBy { it.hops }.toMutableList()
        if (myCaps != 0) list.add(0, Person(me.id).also { it.name = me.name; it.hasInternet = hasInternet; it.cap = myCaps; it.ev = Errand.EV; it.hops = 0; it.lastSeen = now })
        return list
    }

    /**
     * Ask the group's internet for something. It goes out at once as an open request every phone
     * carries — so it can reach someone who only gets signal tomorrow — and the phone that picks
     * it up answers privately. If THIS phone has signal, it just runs here.
     */
    fun openRequestCount(): Int = errands.values.count { it.from == me.id && it.isOpen }

    fun requestErrand(type: String, args: JSONObject, ttlMs: Long = defaultTtl(type), selfCaps: Int = myCaps, prefer: String? = null): Errand {
        require(args.toString().length <= MAX_ARGS / 2) { "request too long" }
        val e = Errand(Crypto.randomId(10), type, JSONObject(args.toString()), me.id, me.name, clock())
        e.rv = Errand.EV
        e.exp = clock() + ttlMs.coerceIn(60_000L, MAX_ERRAND_TTL)
        errands[e.id] = e
        val need = Errand.capFor(type, args)
        if (type != Errand.SEND && need != 0 && selfCaps and need != 0) startWork(e)
        else {
            // [prefer]: the phone that answered part 1 has the page already — it goes first if it's here.
            val capable = capableHelpers(e).sortedBy { if (it.id == prefer) 0 else 1 }
            e.pick = capable.take(3).map { it.id }
            e.status = if (capable.isEmpty()) Errand.WAITING else Errand.ASKED
            dispatch(e)
        }
        pruneErrands()
        touched()
        listener.onChanged()
        return e
    }

    private fun dispatch(e: Errand, helper: Person? = null, cancel: Boolean = false) {
        val p = JSONObject().put("eid", e.id).put("type", e.type).put("args", e.args)
            .put("helper", helper?.id ?: "").put("helperName", helper?.name ?: "")
            .put("rv", Errand.EV).put("exp", e.exp).put("at", e.ts)
        if (e.pick.isNotEmpty()) p.put("pick", JSONArray(e.pick))
        if (e.tried.isNotEmpty()) p.put("ex", JSONArray(e.tried.toList().takeLast(20)))
        if (cancel) p.put("cancel", true)
        val env = newEnvelope(Envelope.ERRAND, p)
        e.dispatchTs = env.ts; e.lastDispatchAt = clock()
        originate(env)
    }

    /** Take a request back. Phones carrying it drop it; a helper mid-way stops. */
    fun cancelErrand(id: String) {
        val e = errands[id] ?: return
        if (e.from != me.id || !e.isOpen) return
        val local = e.helper == me.id && id in running
        e.status = Errand.CANCELLED
        markDone(id)
        if (local) stopWork(e)
        // Running here, but asked while offline: the phones carrying it must let it go too.
        if (!local || announced(e)) dispatch(e, cancel = true)
        listener.onChanged()
    }

    /** Take a finished request of mine off the list. A late answer for it is still ignored. */
    fun forgetErrand(id: String): Boolean {
        val e = errands[id] ?: return false
        if (e.from != me.id || e.isOpen) return false
        errands.remove(id)
        markDone(id)
        listener.onChanged()
        return true
    }

    /** "Ask again": a fresh request for the same thing (a finished one), or a nudge for a stuck one. */
    fun retryErrand(id: String): Errand? {
        val e = errands[id] ?: return null
        if (e.from != me.id) return null
        if (!e.isOpen) return requestErrand(e.type, e.args)
        // "Ask someone else": whoever had it goes to the back of the line.
        e.helper?.let { if (e.status == Errand.CLAIMED) e.tried.add(it) }
        e.helper = null; e.leaseUntil = 0; e.why = ""
        val capable = capableHelpers(e)
        e.pick = capable.take(3).map { it.id }
        e.status = if (capable.isEmpty()) Errand.WAITING else Errand.ASKED
        dispatch(e)
        listener.onChanged()
        return e
    }

    /**
     * I asked while offline and now this phone has the signal for it: run it here, and tell the
     * group it's taken (a claim like any helper's) so nobody else spends data on it. If it fails
     * here it goes back to the group by itself ([declineErrand]). [force] = the person tapped for
     * it, so a recent failure here doesn't stop it.
     */
    fun runOwnNow(id: String, selfCaps: Int, force: Boolean = false): Boolean {
        val e = errands[id] ?: return false
        if (e.from != me.id || !e.isOpen || id in running || e.type == Errand.SEND) return false
        val need = Errand.capFor(e.type, e.args)
        if (need == 0 || selfCaps and need == 0) return false
        val now = clock()
        if (now >= e.exp) return false
        if (e.status == Errand.CLAIMED && e.helper != me.id && e.leaseUntil > now) return false   // a friend is on it already
        if (!force && (backoff[id] ?: 0) > now) return false
        startWork(e)
        listener.onChanged()
        return true
    }

    /** The asker agreed that a phone running an older Hopline may answer — in the group chat, for all. */
    fun allowPublicAnswer(id: String) {
        val e = errands[id] ?: return
        if (e.from != me.id || !e.isOpen) return
        e.allowPublic = true
        touched()
        askerTick(e, clock())
        listener.onChanged()
    }

    private fun askerTick(e: Errand, now: Long) {
        if (!e.isOpen || e.helper == me.id && e.id in running) return
        if (now >= e.exp) {
            // Every phone carrying it works out the same deadline on its own — no envelope needed.
            e.status = Errand.EXPIRED; e.leaseUntil = 0
            markDone(e.id)
            touched()
            listener.onErrandAnswer(e)
            return
        }
        if (e.status == Errand.CLAIMED && e.leaseUntil in 1..now && e.type == Errand.SEND) {
            // A person may already have sent that text before their phone went quiet. Sending it
            // twice would frighten a family, so this one waits for the asker to choose.
            if (e.why != "quiet") { e.why = "quiet"; touched(); listener.onErrandAnswer(e) }
        } else if (e.status == Errand.CLAIMED && e.leaseUntil in 1..now) {
            // The phone that had it went quiet: open it up again to everyone else.
            e.helper?.let { e.tried.add(it) }
            e.helper = null; e.leaseUntil = 0
            val capable = capableHelpers(e)
            e.pick = capable.take(3).map { it.id }
            e.status = if (capable.isEmpty()) Errand.WAITING else Errand.ASKED
            dispatch(e)
            touched()
        }
        if (e.status == Errand.WAITING) {
            val capable = capableHelpers(e)
            if (capable.isNotEmpty()) {
                e.status = Errand.ASKED
                val pick = capable.take(3).map { it.id }
                if (pick != e.pick) { e.pick = pick; dispatch(e); touched() }
            }
        }
        // An older Hopline only answers when asked by name, and answers in public.
        if (e.allowPublic && e.status != Errand.CLAIMED && (e.type == Errand.READ || e.type == Errand.SEND) &&
            now - e.lastDispatchAt >= LEGACY_WAIT_MS) {
            val old = legacyHelpers().firstOrNull { it.id !in e.legacyAsked }
            if (old != null && (capableHelpers(e).isEmpty() || now - e.ts > LEGACY_WAIT_MS)) {
                e.legacyAsked.add(old.id)
                e.helper = old.id; e.helperName = old.name; e.status = Errand.ASKED
                dispatch(e, helper = old)
                touched()
            }
        }
    }

    /** Timers for requests: claims coming due, heartbeats, leases that ran out, deadlines. */
    fun pollErrands() {
        val now = clock()
        for ((eid, due) in claims.entries.toList()) {
            if (due > now) continue
            claims.remove(eid)
            val e = errands[eid] ?: continue
            if (canStillClaim(e)) startWork(e)
        }
        for (eid in running.toList()) {
            val e = errands[eid] ?: run { running.remove(eid); null } ?: continue
            if (e.from == me.id) {
                if (e.lastDispatchAt > 0 && now - (lastBeat[eid] ?: 0) >= beatMs(e)) { lastBeat[eid] = now; e.leaseUntil = now + leaseMs(e); sendAck(e, "work") }
                continue
            }
            if (!e.isOpen || eid in doneErrands || now >= e.exp) { stopWork(e); continue }
            // A text waits on a person: if nobody has tapped Send in half an hour, let someone else try.
            if (e.type == Errand.SEND && eid !in sendOpened && now - (runningSince[eid] ?: now) > HUMAN_MAX_MS) {
                declineErrand(eid, "no_reply")
                listener.onErrandAbort(e)   // its "please text…" notification must go with it
                continue
            }
            if (now - (lastBeat[eid] ?: 0) >= beatMs(e)) { lastBeat[eid] = now; e.leaseUntil = now + leaseMs(e); sendAck(e, "work") }
        }
        for (e in errands.values.toList()) {
            if (e.from == me.id) { askerTick(e, now); continue }
            // Another helper's claim ran out without an answer: it's up for grabs again — except a
            // text, which that person may have sent before going quiet (the asker decides).
            if (e.status == Errand.CLAIMED && e.helper != me.id && e.leaseUntil in 1..now && e.type != Errand.SEND) {
                e.helper?.let { e.tried.add(it) }
                e.helper = null; e.leaseUntil = 0; e.status = Errand.ASKED
                maybeScheduleClaim(e)
            }
            if (e.isOpen && now >= e.exp) { e.status = Errand.EXPIRED; stopWork(e) }
        }
    }

    /** When [pollErrands] next has something to do (the app schedules a wake-up for it). */
    fun nextErrandWake(): Long {
        val now = clock()
        var t = Long.MAX_VALUE
        for (due in claims.values) t = minOf(t, due)
        for (eid in running) errands[eid]?.let { if (announced(it)) t = minOf(t, (lastBeat[eid] ?: 0) + beatMs(it)) }
        for (e in errands.values) {
            // A text whose helper went quiet keeps its lapsed lease on record (the asker decides
            // what happens next), so only leases still to run out and deadlines still ahead count —
            // a time in the past would wake the phone five times a second for a day.
            if (e.status == Errand.CLAIMED && e.leaseUntil > now) t = minOf(t, e.leaseUntil)
            if (e.isOpen && e.exp > now) t = minOf(t, e.exp)
        }
        return t
    }

    /** After a restart: carry on with requests this phone was working on, and re-arm the others. */
    fun resumeErrands() {
        for (eid in running.toList()) {
            val e = errands[eid]
            // Gone, answered, or past its deadline while this phone was off (or out of the group):
            // picking it up now would fetch a page nobody is waiting for, or ask its person to send
            // a text that is days late.
            if (e == null || !e.isOpen || eid in doneErrands || clock() >= e.exp) {
                running.remove(eid); sendOpened.remove(eid); touched(); continue
            }
            e.leaseUntil = clock() + leaseMs(e)
            lastBeat[eid] = clock(); runningSince[eid] = clock()
            if (announced(e)) sendAck(e, "claim")
            listener.onErrandRequest(e)
        }
        for (e in errands.values) maybeScheduleClaim(e)
    }

    fun isRunning(id: String): Boolean = id in running

    /** The helper's person opened their messaging app for this text: from now on only they decide. */
    fun markSendOpened(id: String) { if (id in running && sendOpened.add(id)) touched() }
    fun sendWasOpened(id: String): Boolean = id in sendOpened

    private fun pruneErrands() {
        val now = clock()
        val it = errands.entries.iterator()
        var others = 0
        while (it.hasNext()) {
            val e = it.next().value
            if (e.from == me.id) continue
            val stale = !e.isOpen || now >= e.exp + 3_600_000L || now - e.ts > CARRY_MS
            if (stale && e.id !in running) { it.remove(); claims.remove(e.id); continue }
            others++
        }
        if (others > MAX_OTHERS_ERRANDS) {
            val victims = errands.values.filter { it.from != me.id && it.id !in running }.sortedBy { it.ts }.take(others - MAX_OTHERS_ERRANDS)
            for (v in victims) { errands.remove(v.id); claims.remove(v.id) }
        }
        val mine = errands.values.filter { it.from == me.id && !it.isOpen }.sortedByDescending { it.ts }
        for (old in mine.drop(MAX_MY_ERRANDS)) errands.remove(old.id)
        val dit = doneErrands.entries.iterator()
        while (dit.hasNext()) if (now - dit.next().value > CARRY_MS) dit.remove()
        while (doneErrands.size > 5_000) doneErrands.remove(doneErrands.keys.first())
        backoff.entries.removeAll { it.value < now }
    }

    // ---------------------------------------------------------------- periodic

    /** Call every ~30 s. */
    fun tick() {
        val now = clock()
        // Presence scales down as the group scales up: 30 phones saying "I'm here" every 30 s is
        // nothing; a thousand doing it would drown the radios. Everyone slows down together.
        if (now - lastPresenceAt >= presenceInterval()) { lastPresenceAt = now; sendPresence() }
        for (l in links.values.toList()) {
            // links that never finished the handshake are dead weight
            if (!l.authed && now - l.since > HANDSHAKE_MS) drop(l, "handshake timeout")
            // and a "connected" link that has carried nothing for several beacon rounds is a dead one
            else if (l.authed && now - l.lastHeardAt > maxOf(LINK_SILENCE_MS, presenceInterval() * 3)) drop(l, "silent link")
        }
        // Periodic anti-entropy: re-offer our inventory on each live link so a message a flaky (or
        // jammed) radio dropped from the flood — or one that arrived after the one-shot link-up swap
        // — still gets reconciled, without waiting for the link to break and re-form. The peer fills
        // whatever we lack; every phone runs this, so gaps heal both ways. It costs nothing extra
        // when nothing is missing (the peer just sees it already has our ids). Slows with the crowd,
        // like presence, so a packed venue carries messages instead of endless roll-calls.
        val syncEvery = maxOf(SYNC_MS, presenceInterval())
        for (l in links.values) if (l.authed && now - l.lastSyncAt >= syncEvery) { sendInventory(l); l.lastSyncAt = now }
        // keep trying to get my unsent messages off this phone
        if (links.values.any { it.authed }) {
            for (m in messages) if (m.from == me.id && m.status == Message.QUEUED && now - m.ts < CARRY_MS) carry[m.id]?.let { forward(it, null) }
        }
        pollErrands()
        pruneErrands()
        expire(now)
        refreshDirect()
        listener.onChanged()
    }

    private fun expire(now: Long) {
        val it = carry.entries.iterator()
        while (it.hasNext()) {
            val (id, e) = it.next()
            val limit = if (e.kind == Envelope.RECEIPT) RECEIPT_MS else CARRY_MS
            if (now - (carryBorn[id] ?: minOf(e.ts, now)) > limit) { it.remove(); carryBorn.remove(id); dirty = true }
        }
        chunks.expire(now - CARRY_MS)
        // Pieces turned away for room (or as strays) can come now: forget we saw them.
        for (id in chunks.takeReleased()) seen.remove(id)
        // Over the cap, let receipts and reactions go first — never someone's words.
        if (carry.size > MAX_CARRY) {
            for (kind in listOf(Envelope.RECEIPT, Envelope.REACT, null)) {
                val victims = carry.values.asSequence().filter { kind == null || it.kind == kind }.map { it.id }.take(carry.size - MAX_CARRY).toList()
                for (id in victims) { carry.remove(id); carryBorn.remove(id) }
                if (carry.size <= MAX_CARRY) break
            }
        }
        spill(now)
        // People not heard from in a month are history; names live on in their messages.
        if (people.size > 50) {
            val pit = people.entries.iterator()
            while (pit.hasNext()) { val p = pit.next().value; if (now - p.lastSeen > FORGET_PEOPLE_MS && !p.direct) pit.remove() }
        }
        if (people.size > MAX_PEOPLE) {
            for (p in people.values.filter { !it.direct }.sortedBy { it.lastSeen }.take(people.size - MAX_PEOPLE)) people.remove(p.id)
        }
        val hit = hidden.entries.iterator()
        while (hit.hasNext()) if (now - hit.next().value > CARRY_MS + 86_400_000L) hit.remove()
        // Same rule for messages filed in the history: once no phone can still be carrying them,
        // nobody can hand them back, and the id need not be remembered.
        val sit = spilled.entries.iterator()
        while (sit.hasNext()) if (now - sit.next().value > CARRY_MS + 86_400_000L) sit.remove()
    }

    /**
     * Keep the live window a size the phone can redraw and save in one go. Nothing is thrown away:
     * once the window is a whole batch over, its oldest messages move to [overflow], and the app
     * files them in the group's history, where a chat finds them again when scrolled up.
     *
     * What stays, however old:
     *  - the newest [KEEP_PER_CHAT] messages of every chat, so a busy group chat can never push a
     *    quiet private chat off the chat list;
     *  - a message of mine that is still trying to leave this phone — the tick retries it from here;
     *  - a file whose pieces may still be arriving — it is put together through its live message.
     * If that leaves too few to move (thousands of tiny chats), the oldest go regardless of their
     * chat's size; the other two rules always hold.
     */
    private fun spill(now: Long) {
        val n = messages.size
        if (n < MAX_MESSAGES + SPILL_BATCH) return
        var excess = n - MAX_MESSAGES
        val live = HashMap<String, Int>()
        for (m in messages) { val k = m.chatKey(me.id); live[k] = (live[k] ?: 0) + 1 }
        fun pinned(m: Message): Boolean =
            (m.from == me.id && m.status == Message.QUEUED && carry.containsKey(m.id)) ||
            (m.att != null && m.att.fid !in fileReadyFired && now - m.arrivedAt < CARRY_MS)
        val go = BooleanArray(n)
        var i = 0
        while (i < n && excess > 0) {
            val m = messages[i]; val k = m.chatKey(me.id)
            val inChat = live[k] ?: 0
            if (inChat > KEEP_PER_CHAT && !pinned(m)) { go[i] = true; live[k] = inChat - 1; excess-- }
            i++
        }
        // Thousands of tiny chats: no chat has anything to spare, so the oldest go whatever their chat.
        i = 0
        while (i < n && excess > 0) {
            if (!go[i] && !pinned(messages[i])) { go[i] = true; excess-- }
            i++
        }
        val kept = ArrayList<Message>(n)
        var inOrder = true
        for (j in 0 until n) {
            val m = messages[j]
            if (!go[j]) { kept.add(m); continue }
            if (overflow.isNotEmpty() && overflow[overflow.size - 1].sortKey > m.sortKey) inOrder = false
            overflow.add(m); overflowIds.add(m.id)
            messageById.remove(m.id)
            // A file is found (and marked ready) through its live message; with that gone, neither holds.
            m.att?.let { if (filesByFid[it.fid] === m) { filesByFid.remove(it.fid); fileReadyFired.remove(it.fid) } }
        }
        if (kept.size == n) return
        messages.clear(); messages.addAll(kept)
        // A late arrival can be older than what an earlier, undrained batch already holds.
        if (!inOrder) overflow.sortBy { it.sortKey }
    }

    /**
     * Hand over the messages that left the live window, oldest first, for the app to file in the
     * group's history — and from here on refuse their ids, exactly like deleted ones, so a friend
     * still carrying one can't make it show up again as new.
     */
    fun takeOverflow(): List<Message> {
        if (overflow.isEmpty()) return emptyList()
        val out = ArrayList(overflow)
        val now = clock()
        for (m in out) spilled[m.id] = now
        overflow.clear(); overflowIds.clear()
        touched()
        return out
    }

    /**
     * The history could not take [back] after all (its write failed): they return to the overflow —
     * so the next [snapshot] holds them again — and the next [takeOverflow] offers them once more.
     * Only messages that really were handed over count; anything else is ignored.
     */
    fun returnOverflow(back: List<Message>) {
        var any = false
        for (m in back) {
            if (m.id !in spilled || messageById.containsKey(m.id) || !overflowIds.add(m.id)) continue
            spilled.remove(m.id)
            overflow.add(m); any = true
        }
        if (!any) return
        overflow.sortBy { it.sortKey }
        touched()
    }

    private fun refreshDirect() {
        val direct = links.values.filter { it.authed }.map { it.nodeId }.toSet()
        for (p in people.values) p.direct = p.id in direct
    }

    // ---------------------------------------------------------------- helpers

    private fun addMessage(m: Message): Message? {
        if (messageById.containsKey(m.id) || m.id in hidden) return null
        // Already filed in the history (or about to be): this phone has it, just not in the live window.
        if (m.id in spilled || m.id in overflowIds) return null
        messageById[m.id] = m
        // keep chronological order; new ones are almost always at the end
        val key = m.sortKey
        var i = messages.size
        while (i > 0 && messages[i - 1].sortKey > key) i--
        messages.add(i, m)
        // reactions that beat their message here have been waiting for it
        pendingReactions.remove(m.id)?.let { held ->
            for ((origin, emoji, ts) in held) m.applyReaction(origin, emoji, ts)
        }
        touched()
        return m
    }

    /**
     * Names are last-writer-wins on the sender's own clock: gap-fill hands over 48 h of someone's
     * old envelopes, each signed with the name they had THEN, and none may undo a later rename.
     * A far-future stamp is clamped so a phone with a wild clock can't pin a name for days.
     * [seenAt] is OUR idea of when they were last alive (their clock can be off by hours).
     */
    private fun touchPerson(id: String, rawName: String, at: Long, seenAt: Long): Person {
        val p = people.getOrPut(id) { Person(id).also { touched() } }
        val name = Names.clean(rawName)
        val t = minOf(at, clock() + FUTURE_SLACK_MS)
        if (name.isNotEmpty() && name != p.name && (t >= p.nameAt || p.name.isEmpty())) { p.name = name; touched() }
        if (name.isNotEmpty() && t > p.nameAt) p.nameAt = t
        if (seenAt > p.lastSeen) p.lastSeen = seenAt
        return p
    }

    fun message(id: String): Message? = messageById[id]
    fun isInRange(p: Person): Boolean = clock() - p.lastSeen < maxOf(IN_RANGE_MS, presenceInterval() * 2 + 30_000L)

    /** A person's live position — only while they're in range and the beacon is believably fresh. */
    fun liveLocOf(p: Person): Loc? {
        val loc = p.loc ?: return null
        if (!isInRange(p)) return null
        if (clock() - p.locHeardAt > presenceInterval() * 2 + 60_000L) return null
        return loc
    }
    fun peopleInRange(): Int = people.values.count { isInRange(it) }
    /** People heard from within the carry window — the group as it is now, not everyone ever. */
    fun activePeople(): Int { val now = clock(); return people.values.count { now - it.lastSeen < CARRY_MS } }
    fun activePeopleList(): List<Person> { val now = clock(); return people.values.filter { now - it.lastSeen < CARRY_MS } }
    fun authedLinks(): List<Link> = links.values.filter { it.authed }
    fun carrySize(): Int = carry.size
    /** Is this phone still holding [id]'s envelope to hand on? Once it isn't, an unsent message of
     *  mine has nothing left to send — it will not go out on its own. */
    fun carries(id: String): Boolean = carry.containsKey(id)

    // ---------------------------------------------------------------- persistence

    fun snapshot(): JSONObject = JSONObject().apply {
        // Messages on their way to the history are saved with the rest until the app has taken them.
        put("messages", JSONArray((overflow + messages).map { it.toJson() }))
        put("carry", JSONArray(carry.values.map { it.json }))
        put("born", JSONObject().also { b -> for ((id, t) in carryBorn) if (id in carry) b.put(id, t) })
        put("people", JSONArray(people.values.map { it.toJson() }))
        put("errands", JSONArray(errands.values.map { it.toJson() }))
        put("doneAt", JSONObject().also { d -> for ((id, t) in doneErrands) d.put(id, t) })
        put("running", JSONArray(running.toList()))
        put("sendOpened", JSONArray(sendOpened.filter { it in running }))
        put("hidden", JSONObject().also { h -> for ((id, t) in hidden) h.put(id, t) })
        if (spilled.isNotEmpty()) put("spilled", JSONObject().also { s -> for ((id, t) in spilled) s.put(id, t) })
        put("shareInternet", shareInternet)
        if (group.nameV > 0) put("group", JSONObject().put("n", group.name).put("v", group.nameV))
    }

    /** Every record is restored on its own: one bad entry must never cost the rest of the history. */
    fun restore(j: JSONObject) {
        val now = clock()
        // Deleted and filed-away ids first: the messages below must not bring one of them back.
        j.optJSONObject("hidden")?.let { h -> for (id in h.keys()) hidden[id] = h.optLong(id, now) }
        j.optJSONObject("spilled")?.let { s -> for (id in s.keys()) spilled[id] = s.optLong(id, now) }
        j.optJSONArray("messages")?.let { a ->
            for (i in 0 until a.length()) try {
                val m = Message.fromJson(a.getJSONObject(i))
                if (m.att != null && !Attachment.validFid(m.att.fid)) continue
                addMessage(m)
            } catch (e: Exception) { listener.onLog("bad saved message skipped") }
        }
        val born = j.optJSONObject("born")
        j.optJSONArray("carry")?.let { a ->
            for (i in 0 until a.length()) try {
                val e = Envelope(a.getJSONObject(i))
                if (!ENVELOPE_ID.matches(e.id)) continue
                val limit = if (e.kind == Envelope.RECEIPT) RECEIPT_MS else CARRY_MS
                val bornAt = born?.optLong(e.id, 0)?.takeIf { it > 0 } ?: minOf(e.ts, now)
                seen[e.id] = true
                // Its time ran out while this phone was off — or out of the group, for weeks. It must
                // never reach the inventory: a link can come up before the first tick expires it,
                // and a phone that took it would carry and show the ancient message as new.
                if (now - bornAt > limit) continue
                carry[e.id] = e
                carryBorn[e.id] = bornAt
            } catch (ex: Exception) { listener.onLog("bad saved envelope skipped") }
        }
        for (m in messages) {
            seen[m.id] = true
            m.att?.let { filesByFid[it.fid] = m }
        }
        for (id in hidden.keys) seen[id] = true
        for (id in spilled.keys) seen[id] = true
        // A reaction whose message hadn't arrived before the restart is still in carry — re-point
        // it so it lands the moment the message hops in (applyReaction dedupes ones already shown).
        for (e in carry.values) if (e.kind == Envelope.REACT) try { applyReactionEnvelope(e, live = false) } catch (ex: Exception) { }
        for (id in chunks.ids()) seen[id] = true
        j.optJSONArray("people")?.let { a ->
            for (i in 0 until a.length()) try { val p = Person.fromJson(a.getJSONObject(i)); if (p.id != me.id) people[p.id] = p }
            catch (e: Exception) { }
        }
        j.optJSONArray("errands")?.let { a ->
            for (i in 0 until a.length()) try { val e = Errand.fromJson(a.getJSONObject(i)); errands[e.id] = e } catch (ex: Exception) { }
        }
        j.optJSONObject("doneAt")?.let { d -> for (id in d.keys()) doneErrands[id] = d.optLong(id, now) }
        j.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotEmpty() }?.let { doneErrands.putIfAbsent(it, now) } }
        j.optJSONArray("running")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it in errands }?.let { running.add(it) } }
        j.optJSONArray("sendOpened")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it in running }?.let { sendOpened.add(it) } }
        // 2.1 kept every request ever seen from anyone, with its args (phone numbers, texts): let them go.
        for (e in errands.values.toList()) if (e.from != me.id && (e.exp == 0L || e.id in doneErrands)) errands.remove(e.id)
        for (e in errands.values) if (e.from == me.id && e.exp == 0L) {
            // A 2.1 request of mine: it can't be followed any more — close it honestly.
            if (e.isOpen) e.status = Errand.EXPIRED
        }
        // Saved before leases were kept: give a claim one fresh lease rather than treating it as lapsed.
        for (e in errands.values) if (e.status == Errand.CLAIMED && e.helper != me.id && e.leaseUntil == 0L && e.why != "quiet") e.leaseUntil = now + leaseMs(e)
        shareInternet = j.optBoolean("shareInternet", true)
        // The rename count goes with the name it belongs to (the store may know a newer name).
        j.optJSONObject("group")?.let { g -> if (g.optString("n") == group.name) group.nameV = g.optInt("v", 0).coerceIn(0, MAX_NAME_V) }
        dirty = false
    }

    private var lastPresenceAt = 0L

    /** ≤30 people: every 30 s. Grows with the crowd, capped at 5 min. */
    fun presenceInterval(): Long {
        val n = activePeople()
        return when {
            n <= 30 -> 30_000L
            n <= 150 -> 90_000L
            n <= 500 -> 180_000L
            else -> 300_000L
        }
    }

    companion object {
        const val IN_RANGE_MS = 120_000L
        const val RECEIPT_GROUP_LIMIT = 13   // people (excluding me) below this => chat receipts on
        const val FILE_GROUP_LIMIT = 30      // photos/files switch off in a crowd
        const val CARRY_MS = 48 * 3600_000L
        const val RECEIPT_MS = 24 * 3600_000L
        /** Floor for periodic anti-entropy; the real interval grows with the crowd via presenceInterval(). */
        const val SYNC_MS = 60_000L
        const val HANDSHAKE_MS = 25_000L
        const val LINK_SILENCE_MS = 4 * 60_000L
        const val MAX_TEXT = 2000        // chars; keeps any single envelope far under the 32 KB radio payload cap
        const val MAX_CAPTION = 500
        const val MAX_RESULT = 5000
        /** Base64 chars of an inline thumbnail; ours are ~1–3 KB. */
        const val MAX_THUMB_B64 = 12_000
        /** How far ahead of our clock a peer's timestamp may be before it is clamped. */
        const val FUTURE_SLACK_MS = 5 * 60_000L
        /** Two renames' times closer than this are a dead heat (settled by the name). */
        const val SAME_TIME_MS = 10_000L
        /** Renames counted, at most (a crafted huge count could otherwise freeze the name). */
        const val MAX_NAME_V = 1_000_000
        /** Raw bytes per file chunk; base64 puts the envelope at ~19 KB, under the 24 KB batch line. */
        const val CHUNK_RAW = 14 * 1024
        const val MAX_FILE = 2 * 1024 * 1024L
        val MAX_CHUNKS = ((MAX_FILE + CHUNK_RAW - 1) / CHUNK_RAW).toInt()
        /** Protocol version announced in hello: 2 = file chunks, 3 = reactions, 4 = connection-bound proofs. */
        const val VERSION = 4
        const val BOUND_PROOF_VERSION = 4
        /** Chunk envelopes in flight per link during a backlog fill (~19 KB each). */
        const val FILL_WINDOW = 4
        const val MAX_CARRY = 6000
        /**
         * The live window: how many messages a group keeps in memory and in its state file. Not a
         * cap on history — older messages move to the group's history segments, never away.
         */
        const val MAX_MESSAGES = 2000
        /** How far over the window the list may grow before its oldest move out, a batch at a time. */
        const val SPILL_BATCH = 200
        /** Every chat keeps at least this many of its newest messages in the live window. */
        const val KEEP_PER_CHAT = 30
        const val MAX_PEOPLE = 2000
        const val FORGET_PEOPLE_MS = 30 * 86_400_000L
        const val MAX_INV_PARTS = 64
        const val MAX_INV_IDS = 100_000

        // ---- shared internet
        const val CAP_MASK = Errand.CAP_READ or Errand.CAP_FIND or Errand.CAP_WX or Errand.CAP_SMS or Errand.CAP_MAIL
        /** Gzip'd answer body, base64 chars: keeps the whole envelope ~20 KB, under the 24 KB fill batch. */
        const val MAX_ANSWER_Z = 18_000
        const val MAX_ANSWER_Z_CROWD = 6_000
        const val MAX_ARGS = 4_000
        const val MAX_ERRAND_TTL = 24 * 3600_000L
        const val CLAIM_FIRST_MS = 1_500L
        const val CLAIM_STEP_MS = 8_000L
        const val CLAIM_OTHERS_MS = 20_000L
        const val WORK_LEASE_MS = 90_000L
        const val WORK_BEAT_MS = 30_000L
        /** A text needs a person to tap Send — they get longer before the request moves on. */
        const val HUMAN_LEASE_MS = 20 * 60_000L
        const val HUMAN_BEAT_MS = 5 * 60_000L
        const val HUMAN_MAX_MS = 30 * 60_000L
        const val BACKOFF_MS = 5 * 60_000L
        const val LEGACY_WAIT_MS = 60_000L
        const val MAX_OTHERS_ERRANDS = 300
        const val MAX_MY_ERRANDS = 60

        private val ENVELOPE_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
        /** Reasons a request can never be done by asking again. */
        private val PERMANENT = setOf("unsupported", "bad_url")
        /** One person can't have more than this many requests travelling at once. */
        const val MAX_OPEN_REQUESTS = 5
        private val ERRAND_ID = Regex("^[A-Za-z0-9]{6,24}$")

        fun defaultTtl(type: String): Long = when (type) {
            Errand.SEND -> 24 * 3600_000L
            Errand.WX -> 3 * 3600_000L
            else -> 6 * 3600_000L
        }
    }
}
