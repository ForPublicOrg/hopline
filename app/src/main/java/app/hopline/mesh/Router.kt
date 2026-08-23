package app.hopline.mesh

import app.hopline.core.Crypto
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
    fun onErrandRequest(e: Errand)   // this phone has internet and was asked to do something
    fun onFileReady(m: Message) {}   // every piece of m's attachment is on this phone — assemble it
    fun onGroupNamed(name: String) {} // joined by typed code; learned the group's name from a friend
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
    class Link(val id: String, val nodeId: String, var name: String, val since: Long) {
        var authed = false
        var version = 1                  // from their hello; 1 = a 1.x client that doesn't carry files
        val myNonce: String = Crypto.randomId(16)
        var invExpected = -1
        var invIds = HashSet<String>()
        var invParts = 0
        /** Chunk ids still owed to this link from the last inventory swap, streamed a few at a time. */
        val fillQueue = ArrayDeque<String>()
        var fillInFlight = 0
    }

    val links = LinkedHashMap<String, Link>()
    val messages = ArrayList<Message>()
    private val messageById = HashMap<String, Message>()
    val people = LinkedHashMap<String, Person>()
    val errands = LinkedHashMap<String, Errand>()
    private val doneErrands = HashSet<String>()
    private val runningErrands = HashSet<String>()

    /** File messages by attachment id, so an arriving chunk can find its meta. */
    private val filesByFid = HashMap<String, Message>()
    /** Files whose onFileReady already fired (or that this phone originated). */
    private val fileReadyFired = HashSet<String>()

    /** Store-and-forward memory: id -> envelope. */
    private val carry = LinkedHashMap<String, Envelope>()
    private val seen = object : LinkedHashMap<String, Boolean>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 20000
    }
    private val pendingPayloads = HashMap<Long, List<String>>()
    /** Payloads that belong to a link's chunk-fill window: payload id -> link id. */
    private val fillPayloads = HashMap<Long, String>()

    var hasInternet = false
    var shareInternet = true
    var battery = -1

    // ---------------------------------------------------------------- transport events

    fun onLinkUp(linkId: String, nodeId: String, name: String) {
        if (nodeId == me.id) { transport.disconnect(linkId); return }
        links.remove(linkId)
        val link = Link(linkId, nodeId, name, clock())
        links[linkId] = link
        // "v" tells peers we carry file chunks; 1.x clients ignore unknown fields.
        sendFrame(link, JSONObject().put("t", "hello").put("id", me.id).put("name", me.name).put("nonce", link.myNonce).put("v", VERSION))
        listener.onLog("link up $linkId ($name)")
    }

    fun onLinkDown(linkId: String) {
        if (links.remove(linkId) != null) {
            refreshDirect(); listener.onLog("link down $linkId"); listener.onChanged()
        }
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
        if (changed) listener.onChanged()
    }

    fun onPayloadFailed(payloadId: Long) {
        pendingPayloads.remove(payloadId)
        fillPayloads.remove(payloadId)?.let { linkId ->
            links[linkId]?.let { it.fillInFlight = maxOf(0, it.fillInFlight - 1); pumpFill(it) }
        }
    }

    fun onBytes(linkId: String, bytes: ByteArray) {
        val link = links[linkId] ?: return
        val frame = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { return }
        when (frame.optString("t")) {
            "hello" -> {
                if (frame.optString("id") != link.nodeId) { drop(link, "hello id mismatch"); return }
                link.name = frame.optString("name", link.name)
                link.version = frame.optInt("v", 1)
                val proof = Crypto.hmacHex(group.key, frame.optString("nonce") + "|" + me.id)
                sendFrame(link, JSONObject().put("t", "proof").put("proof", proof))
            }
            "proof" -> {
                val expect = Crypto.hmacHex(group.key, link.myNonce + "|" + link.nodeId)
                if (!Crypto.constantTimeEquals(expect, frame.optString("proof"))) { drop(link, "bad proof"); return }
                if (!link.authed) {
                    link.authed = true
                    touchPerson(link.nodeId, link.name, clock())
                    refreshDirect()
                    sendInventory(link)
                    sendPresence()
                    listener.onLog("link authed ${link.name}")
                    listener.onChanged()
                }
            }
            "inv" -> {
                if (!link.authed) return
                if (link.invExpected < 0) { link.invExpected = frame.optInt("n", 1); link.invIds = HashSet(); link.invParts = 0 }
                val ids = frame.optJSONArray("ids") ?: JSONArray()
                for (i in 0 until ids.length()) link.invIds.add(ids.getString(i))
                link.invParts++
                if (link.invParts >= link.invExpected) {
                    fillGaps(link, link.invIds)
                    link.invExpected = -1; link.invIds = HashSet()
                }
            }
            "fill" -> {
                if (!link.authed) return
                val envs = frame.optJSONArray("envs") ?: return
                for (i in 0 until envs.length()) receive(link, Envelope(envs.getJSONObject(i)))
            }
            "env" -> {
                if (!link.authed) return
                val e = frame.optJSONObject("e") ?: return
                receive(link, Envelope(e))
            }
        }
    }

    private fun drop(link: Link, why: String) {
        listener.onLog("dropping link ${link.id}: $why")
        links.remove(link.id)
        transport.disconnect(link.id)
        refreshDirect()
    }

    // ---------------------------------------------------------------- sync on link-up

    private fun sendInventory(link: Link) {
        // 1.x peers don't carry chunks, so telling them about ours only wastes their parser.
        val ids = if (link.version >= 2) carry.keys.toList() + chunks.ids() else carry.keys.toList()
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
            val copy = env.copy(); copy.hops = env.hops + 1
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
            for (id in chunks.ids()) if (id !in theyHave && id !in link.fillQueue) link.fillQueue.addLast(id)
            pumpFill(link)
            if (link.fillQueue.isNotEmpty() || link.fillInFlight > 0) sent += link.fillQueue.size + link.fillInFlight
        }
        if (sent > 0) listener.onLog("filling $sent for ${link.name}")
    }

    /** Keep a small window of chunk envelopes in flight to one link; the ack pulls the next one. */
    private fun pumpFill(link: Link) {
        while (link.fillInFlight < FILL_WINDOW && link.fillQueue.isNotEmpty()) {
            val id = link.fillQueue.removeFirst()
            val env = chunks.get(id) ?: continue
            val copy = env.copy(); copy.hops = env.hops + 1
            val pid = sendFrame(link, JSONObject().put("t", "env").put("e", copy.json))
            if (pid < 0) { link.fillQueue.clear(); return }     // link is gone
            link.fillInFlight++
            fillPayloads[pid] = link.id
        }
    }

    // ---------------------------------------------------------------- receiving

    private fun receive(from: Link?, env: Envelope) = try {
        val id = env.id
        if (seen.containsKey(id)) Unit
        else if (env.origin == me.id) { seen[id] = true; Unit }
        else if (env.hops >= Envelope.MAX_HOPS) Unit
        else if (!env.verify(group.key)) { listener.onLog("forged/garbled envelope dropped") }
        else {
            seen[id] = true
            if (env.kind == Envelope.CHUNK) {
                // If the disk write failed (storage full), forget we saw it so a peer can refill later.
                if (!chunks.put(env)) seen.remove(id)
            } else if (env.kind in Envelope.CARRIED) carry[id] = env
            touchPerson(env.origin, env.originName, env.ts)
            process(env)
            forward(env, from)
        }
    } catch (e: Exception) {
        // A malformed envelope from a buggy client must never take the whole mesh down with it.
        listener.onLog("garbled envelope dropped: ${e.message}")
    }

    private fun process(env: Envelope) {
        val p = env.payload
        when (env.kind) {
            Envelope.CHAT -> {
                val m = addMessage(Message(env.id, Envelope.CHAT, env.origin, env.originName, null, p.optString("text"), env.ts).also { it.arrivedAt = clock() }) ?: return
                // In a small group every phone confirms receipt ("reached 7 of 9"). In a crowd of
                // hundreds that would be an N-squared flood, so big groups skip chat receipts.
                if (people.size < RECEIPT_GROUP_LIMIT) sendReceipt(env.id, env.origin)
                listener.onMessage(m)
            }
            Envelope.DM -> {
                if (env.to != me.id) return
                val m = addMessage(Message(env.id, Envelope.DM, env.origin, env.originName, me.id, p.optString("text"), env.ts).also { it.arrivedAt = clock() }) ?: return
                sendReceipt(env.id, env.origin); listener.onMessage(m)
            }
            Envelope.FILE -> {
                val att = p.optJSONObject("att")?.let { Attachment(it) } ?: return
                // A signed-but-absurd attachment (a crafted client) must not wedge every phone.
                if (att.chunks !in 1..MAX_CHUNKS || att.size !in 1..MAX_FILE) { listener.onLog("absurd attachment dropped"); return }
                if (env.to != null && env.to != me.id) return   // someone else's private photo: carry, don't show
                val to = if (env.to != null) me.id else null
                val m = Message(env.id, Envelope.FILE, env.origin, env.originName, to, p.optString("text"), env.ts, att)
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
            Envelope.RECEIPT -> {
                val m = messageById[p.optString("m")] ?: return
                if (m.from != me.id) return
                val by = p.optString("by"); if (by.isEmpty()) return
                m.reached.add(by)
                if (m.to != null && by == m.to) m.status = Message.DELIVERED
                else if (m.status == Message.QUEUED) m.status = Message.SENT
                listener.onChanged()
            }
            Envelope.PRESENCE -> {
                val person = touchPerson(env.origin, p.optString("n"), env.ts)
                person.hasInternet = p.optBoolean("net", false)
                person.hops = env.hops
                person.battery = p.optInt("bat", -1)
                val gn = p.optString("gn")
                if (group.name.isEmpty() && gn.isNotEmpty()) { group.name = gn; listener.onGroupNamed(gn) }
                assignWaitingErrands()
                listener.onChanged()
            }
            Envelope.ERRAND -> {
                val eid = p.optString("eid"); if (eid.isEmpty()) return
                val e = errands.getOrPut(eid) {
                    Errand(eid, p.optString("type"), p.optJSONObject("args") ?: JSONObject(), env.origin, env.originName, env.ts)
                }
                if (e.status != Errand.DONE) {
                    e.helper = p.optString("helper").ifEmpty { null }; e.helperName = p.optString("helperName"); e.status = Errand.ASKED
                }
                if (e.helper == me.id && eid !in doneErrands && eid !in runningErrands && e.status != Errand.DONE) {
                    runningErrands.add(eid); listener.onErrandRequest(e)
                }
                listener.onChanged()
            }
            Envelope.ERRAND_RESULT -> {
                val eid = p.optString("eid")
                val e = errands[eid]
                if (e != null) { e.status = Errand.DONE; e.result = p.optString("text") }
                doneErrands.add(eid)
                val title = p.optString("title"); val text = p.optString("text")
                val m = addMessage(Message(env.id, Message.SYSTEM, env.origin, env.originName, null,
                    if (title.isEmpty()) text else "$title\n$text", env.ts).also { it.arrivedAt = clock() }) ?: return
                m.errandId = eid
                listener.onMessage(m)
            }
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
        if (m.to == me.id || people.size < RECEIPT_GROUP_LIMIT) sendReceipt(m.id, m.from)
        listener.onFileReady(m)
    }

    /**
     * Photos and files would drown the radios a very large crowd shares, so they switch off
     * as the group grows — same self-discipline as receipts and presence.
     */
    fun canSendFiles(): Boolean = people.size < FILE_GROUP_LIMIT

    /**
     * Send a file that has already been shrunk and cut into base64 pieces (see Blobs on the app
     * side). The meta envelope is the visible message; the chunks flood behind it.
     */
    fun sendFile(att: Attachment, pieces: List<String>, caption: String, to: String? = null): Message {
        val meta = newEnvelope(Envelope.FILE, JSONObject().put("text", caption.take(MAX_CAPTION)).put("att", att.json), to)
        val m = Message(meta.id, Envelope.FILE, me.id, me.name, to, caption, meta.ts, att).also { it.status = Message.QUEUED }
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
        seen[env.id] = true
        if (env.kind in Envelope.CARRIED) carry[env.id] = env
        forward(env, null)
    }

    fun sendChat(text: String): Message {
        val env = newEnvelope(Envelope.CHAT, JSONObject().put("text", text.take(MAX_TEXT)))
        val m = Message(env.id, Envelope.CHAT, me.id, me.name, null, text, env.ts).also { it.status = Message.QUEUED }
        addMessage(m); originate(env); listener.onChanged()
        return m
    }

    fun sendDm(to: String, text: String): Message {
        val env = newEnvelope(Envelope.DM, JSONObject().put("text", text.take(MAX_TEXT)), to)
        val m = Message(env.id, Envelope.DM, me.id, me.name, to, text, env.ts).also { it.status = Message.QUEUED }
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
        val p = JSONObject().put("n", me.name).put("net", hasInternet && shareInternet).put("bat", battery).put("gn", group.name)
        forward(newEnvelope(Envelope.PRESENCE, p).also { seen[it.id] = true }, null)
    }

    // ---------------------------------------------------------------- errands (internet sharing)

    /** Who can do internet things for us right now, nearest first. Includes me. */
    fun helpers(): List<Person> {
        val now = clock()
        val list = people.values.filter { it.hasInternet && isInRange(it) }.sortedBy { it.hops }.toMutableList()
        if (hasInternet && shareInternet) list.add(0, Person(me.id).also { it.name = me.name; it.hasInternet = true; it.hops = 0; it.lastSeen = now })
        return list
    }

    fun requestErrand(type: String, args: JSONObject): Errand {
        val e = Errand(Crypto.randomId(10), type, args, me.id, me.name, clock())
        errands[e.id] = e
        dispatchErrand(e)
        listener.onChanged()
        return e
    }

    private fun dispatchErrand(e: Errand) {
        val helper = helpers().firstOrNull()
        if (helper == null) { e.status = Errand.WAITING; e.helper = null; return }
        e.helper = helper.id; e.helperName = helper.name; e.status = Errand.ASKED
        val p = JSONObject().put("eid", e.id).put("type", e.type).put("args", e.args).put("helper", helper.id).put("helperName", helper.name)
        val env = newEnvelope(Envelope.ERRAND, p)
        originate(env)
        if (helper.id == me.id && e.id !in doneErrands && e.id !in runningErrands) { runningErrands.add(e.id); listener.onErrandRequest(e) }
    }

    /** Ask again (e.g. the helper walked away). */
    fun retryErrand(id: String) { errands[id]?.let { if (it.status != Errand.DONE) { dispatchErrand(it); listener.onChanged() } } }

    private fun assignWaitingErrands() {
        for (e in errands.values) if (e.status == Errand.WAITING && e.from == me.id) dispatchErrand(e)
    }

    /** Called by the phone that ran the errand. */
    fun completeErrand(id: String, ok: Boolean, title: String, text: String) {
        runningErrands.remove(id); doneErrands.add(id)
        errands[id]?.let { it.status = Errand.DONE; it.result = text }
        val env = newEnvelope(Envelope.ERRAND_RESULT, JSONObject().put("eid", id).put("ok", ok).put("title", title.take(200)).put("text", text.take(MAX_RESULT)))
        val m = Message(env.id, Message.SYSTEM, me.id, me.name, null, if (title.isEmpty()) text else "$title\n$text", env.ts)
        m.errandId = id
        addMessage(m); originate(env); listener.onChanged()
    }

    // ---------------------------------------------------------------- periodic

    /** Call every ~30 s. */
    fun tick() {
        val now = clock()
        // Presence scales down as the group scales up: 30 phones saying "I'm here" every 30 s is
        // nothing; a thousand doing it would drown the radios. Everyone slows down together.
        if (now - lastPresenceAt >= presenceInterval()) { lastPresenceAt = now; sendPresence() }
        // links that never finished the handshake are dead weight
        for (l in links.values.toList()) if (!l.authed && now - l.since > 25_000) drop(l, "handshake timeout")
        // keep trying to get my unsent messages off this phone
        if (links.values.any { it.authed }) {
            for (m in messages) if (m.from == me.id && m.status == Message.QUEUED && now - m.ts < CARRY_MS) carry[m.id]?.let { forward(it, null) }
        }
        assignWaitingErrands()
        expire(now)
        refreshDirect()
        listener.onChanged()
    }

    private fun expire(now: Long) {
        val it = carry.entries.iterator()
        while (it.hasNext()) {
            val e = it.next().value
            val limit = if (e.kind == Envelope.RECEIPT) RECEIPT_MS else CARRY_MS
            if (now - e.ts > limit) it.remove()
        }
        chunks.expire(now - CARRY_MS)
        while (carry.size > 4000) carry.remove(carry.keys.first())
        while (messages.size > 2000) messageById.remove(messages.removeAt(0).id)
    }

    private fun refreshDirect() {
        val direct = links.values.filter { it.authed }.map { it.nodeId }.toSet()
        for (p in people.values) p.direct = p.id in direct
    }

    // ---------------------------------------------------------------- helpers

    private fun addMessage(m: Message): Message? {
        if (messageById.containsKey(m.id)) return null
        messageById[m.id] = m
        // keep chronological order; new ones are almost always at the end
        var i = messages.size
        while (i > 0 && messages[i - 1].ts > m.ts) i--
        messages.add(i, m)
        return m
    }

    private fun touchPerson(id: String, name: String, at: Long): Person {
        val p = people.getOrPut(id) { Person(id) }
        if (name.isNotEmpty()) p.name = name
        if (at > p.lastSeen) p.lastSeen = at
        return p
    }

    fun message(id: String): Message? = messageById[id]
    fun isInRange(p: Person): Boolean = clock() - p.lastSeen < maxOf(IN_RANGE_MS, presenceInterval() * 2 + 30_000L)
    fun peopleInRange(): Int = people.values.count { isInRange(it) }
    fun authedLinks(): List<Link> = links.values.filter { it.authed }
    fun carrySize(): Int = carry.size

    // ---------------------------------------------------------------- persistence

    fun snapshot(): JSONObject = JSONObject().apply {
        put("messages", JSONArray(messages.map { it.toJson() }))
        put("carry", JSONArray(carry.values.map { it.json }))
        put("people", JSONArray(people.values.map { it.toJson() }))
        put("errands", JSONArray(errands.values.map { it.toJson() }))
        put("done", JSONArray(doneErrands.toList()))
        put("shareInternet", shareInternet)
    }

    fun restore(j: JSONObject) {
        j.optJSONArray("messages")?.let { a -> for (i in 0 until a.length()) addMessage(Message.fromJson(a.getJSONObject(i))) }
        j.optJSONArray("carry")?.let { a -> for (i in 0 until a.length()) { val e = Envelope(a.getJSONObject(i)); carry[e.id] = e; seen[e.id] = true } }
        for (m in messages) {
            seen[m.id] = true
            m.att?.let { filesByFid[it.fid] = m }
        }
        for (id in chunks.ids()) seen[id] = true
        j.optJSONArray("people")?.let { a -> for (i in 0 until a.length()) { val p = Person.fromJson(a.getJSONObject(i)); people[p.id] = p } }
        j.optJSONArray("errands")?.let { a -> for (i in 0 until a.length()) { val e = Errand.fromJson(a.getJSONObject(i)); errands[e.id] = e } }
        j.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) doneErrands.add(a.getString(i)) }
        shareInternet = j.optBoolean("shareInternet", true)
    }

    private var lastPresenceAt = 0L

    /** ≤30 people: every 30 s. Grows with the crowd, capped at 5 min. */
    fun presenceInterval(): Long {
        val n = people.size
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
        const val MAX_TEXT = 2000        // chars; keeps any single envelope far under the 32 KB radio payload cap
        const val MAX_CAPTION = 500
        const val MAX_RESULT = 5000
        /** Raw bytes per file chunk; base64 puts the envelope at ~19 KB, under the 24 KB batch line. */
        const val CHUNK_RAW = 14 * 1024
        const val MAX_FILE = 2 * 1024 * 1024L
        val MAX_CHUNKS = ((MAX_FILE + CHUNK_RAW - 1) / CHUNK_RAW).toInt()
        /** Protocol version announced in hello; 2 = carries file chunks. */
        const val VERSION = 2
        /** Chunk envelopes in flight per link during a backlog fill (~19 KB each). */
        const val FILL_WINDOW = 4
    }
}
