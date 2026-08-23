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
    fun onSos(m: Message)
    fun onErrandRequest(e: Errand)   // this phone has internet and was asked to do something
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
 *  - Receipts flow back the same way, so a sender sees "reached 7 of 9" truthfully.
 *
 * Not thread-safe: call everything from one thread (the app uses the main thread).
 */
class Router(
    val me: Identity,
    val group: Group,
    private val transport: Transport,
    private val listener: RouterListener,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Link(val id: String, val nodeId: String, var name: String, val since: Long) {
        var authed = false
        val myNonce: String = Crypto.randomId(16)
        var invExpected = -1
        var invIds = HashSet<String>()
        var invParts = 0
    }

    val links = LinkedHashMap<String, Link>()
    val messages = ArrayList<Message>()
    private val messageById = HashMap<String, Message>()
    val people = LinkedHashMap<String, Person>()
    val errands = LinkedHashMap<String, Errand>()
    private val doneErrands = HashSet<String>()
    private val runningErrands = HashSet<String>()

    /** Store-and-forward memory: id -> envelope. */
    private val carry = LinkedHashMap<String, Envelope>()
    private val seen = object : LinkedHashMap<String, Boolean>(1024, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 20000
    }
    private val pendingPayloads = HashMap<Long, List<String>>()

    var hasInternet = false
    var shareInternet = true
    var battery = -1

    private var seq = 0L

    // ---------------------------------------------------------------- transport events

    fun onLinkUp(linkId: String, nodeId: String, name: String) {
        if (nodeId == me.id) { transport.disconnect(linkId); return }
        links.remove(linkId)
        val link = Link(linkId, nodeId, name, clock())
        links[linkId] = link
        sendFrame(link, JSONObject().put("t", "hello").put("id", me.id).put("name", me.name).put("nonce", link.myNonce))
        listener.onLog("link up $linkId ($name)")
    }

    fun onLinkDown(linkId: String) {
        if (links.remove(linkId) != null) {
            refreshDirect(); listener.onLog("link down $linkId"); listener.onChanged()
        }
    }

    fun onPayloadSent(payloadId: Long) {
        val ids = pendingPayloads.remove(payloadId) ?: return
        var changed = false
        for (id in ids) {
            val m = messageById[id] ?: continue
            if (m.from == me.id && m.status == Message.QUEUED) { m.status = Message.SENT; changed = true }
        }
        if (changed) listener.onChanged()
    }

    fun onPayloadFailed(payloadId: Long) { pendingPayloads.remove(payloadId) }

    fun onBytes(linkId: String, bytes: ByteArray) {
        val link = links[linkId] ?: return
        val frame = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { return }
        when (frame.optString("t")) {
            "hello" -> {
                if (frame.optString("id") != link.nodeId) { drop(link, "hello id mismatch"); return }
                link.name = frame.optString("name", link.name)
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
        val ids = carry.keys.toList()
        val chunk = 700
        val parts = maxOf(1, (ids.size + chunk - 1) / chunk)
        for (p in 0 until parts) {
            val slice = ids.subList(p * chunk, minOf(ids.size, (p + 1) * chunk))
            sendFrame(link, JSONObject().put("t", "inv").put("n", parts).put("i", p).put("ids", JSONArray(slice)))
        }
    }

    private fun fillGaps(link: Link, theyHave: Set<String>) {
        val missing = carry.values.filter { it.id !in theyHave }
        if (missing.isEmpty()) return
        var batch = JSONArray(); var size = 0; val ids = ArrayList<String>()
        fun flush() {
            if (batch.length() == 0) return
            val pid = sendFrame(link, JSONObject().put("t", "fill").put("envs", batch))
            if (pid >= 0) pendingPayloads[pid] = ids.toList()
            batch = JSONArray(); size = 0; ids.clear()
        }
        for (env in missing) {
            val copy = env.copy(); copy.hops = env.hops + 1
            val s = copy.json.toString()
            if (size + s.length > 24000) flush()
            batch.put(copy.json); size += s.length; ids.add(env.id)
        }
        flush()
        listener.onLog("filled ${missing.size} for ${link.name}")
    }

    // ---------------------------------------------------------------- receiving

    private fun receive(from: Link?, env: Envelope) {
        val id = try { env.id } catch (e: Exception) { return }
        if (seen.containsKey(id)) return
        if (env.origin == me.id) { seen[id] = true; return }
        if (env.hops >= Envelope.MAX_HOPS) return
        if (!env.verify(group.key)) { listener.onLog("forged/garbled envelope dropped"); return }
        seen[id] = true
        if (env.kind in Envelope.CARRIED) carry[id] = env
        touchPerson(env.origin, env.originName, env.ts)
        process(env)
        forward(env, from)
    }

    private fun process(env: Envelope) {
        val p = env.payload
        when (env.kind) {
            Envelope.CHAT -> {
                val m = addMessage(Message(env.id, Envelope.CHAT, env.origin, env.originName, null, p.optString("text"), env.ts)) ?: return
                sendReceipt(env); listener.onMessage(m)
            }
            Envelope.DM -> {
                if (env.to != me.id) return
                val m = addMessage(Message(env.id, Envelope.DM, env.origin, env.originName, me.id, p.optString("text"), env.ts)) ?: return
                sendReceipt(env); listener.onMessage(m)
            }
            Envelope.RECEIPT -> {
                val m = messageById[p.optString("m")] ?: return
                if (m.from != me.id) return
                val by = p.optString("by"); if (by.isEmpty()) return
                m.reached.add(by)
                if (m.kind == Envelope.DM && by == m.to) m.status = Message.DELIVERED
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
            Envelope.SOS -> {
                val m = addMessage(Message(env.id, Envelope.SOS, env.origin, env.originName, null, p.optString("text"), env.ts)) ?: return
                sendReceipt(env); listener.onSos(m)
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
                    if (title.isEmpty()) text else "$title\n$text", env.ts)) ?: return
                m.errandId = eid
                listener.onMessage(m)
            }
        }
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
        val env = newEnvelope(Envelope.CHAT, JSONObject().put("text", text))
        val m = Message(env.id, Envelope.CHAT, me.id, me.name, null, text, env.ts).also { it.status = Message.QUEUED }
        addMessage(m); originate(env); listener.onChanged()
        return m
    }

    fun sendDm(to: String, text: String): Message {
        val env = newEnvelope(Envelope.DM, JSONObject().put("text", text), to)
        val m = Message(env.id, Envelope.DM, me.id, me.name, to, text, env.ts).also { it.status = Message.QUEUED }
        addMessage(m); originate(env); listener.onChanged()
        return m
    }

    fun sendSos(): Message {
        val near = links.values.filter { it.authed }.map { it.name }.distinct()
        val text = buildString {
            append(me.name).append(" needs help!")
            if (near.isNotEmpty()) append(" Their phone is near: ").append(near.joinToString(", ")).append(".")
        }
        val env = newEnvelope(Envelope.SOS, JSONObject().put("text", text).put("near", JSONArray(near)))
        val m = Message(env.id, Envelope.SOS, me.id, me.name, null, text, env.ts).also { it.status = Message.QUEUED }
        addMessage(m); originate(env); listener.onChanged()
        return m
    }

    private fun sendReceipt(env: Envelope) {
        val r = JSONObject().put("id", "r.${env.id}.${me.id}").put("k", Envelope.RECEIPT).put("o", me.id).put("on", me.name)
            .put("ts", clock()).put("h", 0).put("to", env.origin)
            .put("p", JSONObject().put("m", env.id).put("by", me.id))
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
        val list = people.values.filter { it.hasInternet && now - it.lastSeen < IN_RANGE_MS }.sortedBy { it.hops }.toMutableList()
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
        val env = newEnvelope(Envelope.ERRAND_RESULT, JSONObject().put("eid", id).put("ok", ok).put("title", title).put("text", text))
        val m = Message(env.id, Message.SYSTEM, me.id, me.name, null, if (title.isEmpty()) text else "$title\n$text", env.ts)
        m.errandId = id
        addMessage(m); originate(env); listener.onChanged()
    }

    // ---------------------------------------------------------------- periodic

    /** Call every ~30 s. */
    fun tick() {
        val now = clock()
        sendPresence()
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
    fun isInRange(p: Person): Boolean = clock() - p.lastSeen < IN_RANGE_MS
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
        for (m in messages) seen[m.id] = true
        j.optJSONArray("people")?.let { a -> for (i in 0 until a.length()) { val p = Person.fromJson(a.getJSONObject(i)); people[p.id] = p } }
        j.optJSONArray("errands")?.let { a -> for (i in 0 until a.length()) { val e = Errand.fromJson(a.getJSONObject(i)); errands[e.id] = e } }
        j.optJSONArray("done")?.let { a -> for (i in 0 until a.length()) doneErrands.add(a.getString(i)) }
        shareInternet = j.optBoolean("shareInternet", true)
    }

    companion object {
        const val IN_RANGE_MS = 120_000L
        const val CARRY_MS = 48 * 3600_000L
        const val RECEIPT_MS = 24 * 3600_000L
    }
}
