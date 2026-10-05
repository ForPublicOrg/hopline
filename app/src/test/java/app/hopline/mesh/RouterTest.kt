package app.hopline.mesh

import app.hopline.core.Crypto
import app.hopline.core.IdentityKeys
import app.hopline.core.Words
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * A fake radio: phones are connected by explicit links, frames are delivered in order, and
 * delivery acks fire after each frame. Lets us simulate a whole trekking group on a laptop.
 *
 * Phones are named ("A", "B", …) for the test's sake; each name has one key pair for the whole
 * run, so a phone built again under the same name is the same phone, with the same node id. The
 * node id is the real one, made from the key: tests ask [id] for it, never assume the name.
 */
class FakeNet {
    var now = 1_700_000_000_000L
    var maxFrameBytes = 0
    /** Simulate a flaky/jammed radio: the next N "env" (live flood) frames are lost, link stays up. */
    var dropEnvFrames = 0
    /** If set, only drop env frames SENT BY this node (lets a test lose one specific relay hop). */
    var dropEnvFrom: String? = null
    /** When set, every frame any phone sends is kept here as text, for tests that look for what must never be on the air. */
    var frames: ArrayList<String>? = null
    val nodes = LinkedHashMap<String, Node>()
    private val pending = ArrayDeque<Delivery>()
    private var payloadSeq = 0L

    class Delivery(val from: String, val pid: Long, val to: String, val toLink: String, val bytes: ByteArray, val failed: Boolean = false)

    inner class Recorder : RouterListener {
        val shown = ArrayList<Message>(); val errands = ArrayList<Errand>(); val log = ArrayList<String>()
        val files = ArrayList<Message>(); val answers = ArrayList<Errand>(); val aborts = ArrayList<Errand>()
        val reactions = ArrayList<Triple<String, String, String>>()   // message id, by, emoji
        val groupNames = ArrayList<String>()
        override fun onChanged() {}
        override fun onMessage(m: Message) { shown.add(m) }
        override fun onErrandRequest(e: Errand) { errands.add(e) }
        override fun onErrandAbort(e: Errand) { aborts.add(e) }
        override fun onErrandAnswer(e: Errand) { answers.add(e) }
        override fun onFileReady(m: Message) { files.add(m) }
        override fun onGroupNamed(name: String, at: Long) { groupNames.add(name) }
        override fun onReaction(m: Message, by: String, emoji: String) { reactions.add(Triple(m.id, by, emoji)) }
        override fun onLog(text: String) { log.add(text) }
    }

    /** [label] is the test's name for the phone (and its links: "A>B"); [name] its display name. */
    inner class Node(val label: String, val name: String, code: String) {
        val id: String = idOf(label)
        val peers = HashMap<String, Pair<String, String>>()  // my linkId -> (peer node, peer's linkId)
        val rec = Recorder()
        val transport = object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long {
                val (peer, peerLink) = peers[linkId] ?: return -1
                val pid = ++payloadSeq
                if (bytes.size > maxFrameBytes) maxFrameBytes = bytes.size
                frames?.add(String(bytes, Charsets.UTF_8))
                val isEnv = dropEnvFrames > 0 && (dropEnvFrom == null || dropEnvFrom == label) &&
                    (try { JSONObject(String(bytes, Charsets.UTF_8)).optString("t") } catch (e: Exception) { "" }) == "env"
                if (isEnv) { dropEnvFrames--; pending.addLast(Delivery(label, pid, peer, peerLink, bytes, failed = true)); return pid }
                pending.addLast(Delivery(label, pid, peer, peerLink, bytes))
                return pid
            }
            override fun disconnect(linkId: String) { cut(label, linkId) }
        }
        /** How far this phone's clock runs ahead of the others'. */
        var skew = 0L
        val router = Router(identity(label, name), group(code), transport, rec) { now + skew }
    }

    fun node(label: String, name: String = label, code: String = CODE): Node = Node(label, name, code).also { nodes[label] = it }

    /** The node id of the phone this test calls [label]. */
    fun id(label: String): String = idOf(label)
    fun ids(vararg labels: String): Set<String> = labels.map { idOf(it) }.toSet()
    /** The phone whose node id is [id]. */
    fun byId(id: String): Node = nodes.values.first { it.id == id }

    /** [at] has seen [who] before (and so has their key), without a link between them now. */
    fun know(at: String, vararg who: String) {
        for (w in who) nodes[at]!!.router.people.getOrPut(idOf(w)) { Person(idOf(w)).also { it.name = w } }.pk = keysOf(w).pubB64
    }

    /** Like a Nearby connection: both ends get the same authentication token, unique to this link. */
    fun connect(a: String, b: String, token: String = Crypto.randomId(32)) {
        val la = "$a>$b"; val lb = "$b>$a"
        nodes[a]!!.peers[la] = b to lb; nodes[b]!!.peers[lb] = a to la
        nodes[a]!!.router.onLinkUp(la, nodes[b]!!.id, nodes[b]!!.name, token)
        nodes[b]!!.router.onLinkUp(lb, nodes[a]!!.id, nodes[a]!!.name, token)
        pump()
    }

    /** Let [ms] pass, firing every phone's request timers along the way. */
    fun advance(ms: Long, step: Long = 500) {
        var left = ms
        while (left > 0) { val d = minOf(step, left); now += d; left -= d; for (n in nodes.values) n.router.pollErrands(); pump() }
    }

    fun disconnect(a: String, b: String) { cut(a, "$a>$b"); cut(b, "$b>$a") }

    private fun cut(node: String, linkId: String) {
        val n = nodes[node] ?: return
        val peer = n.peers.remove(linkId) ?: return
        n.router.onLinkDown(linkId)
        nodes[peer.first]?.let { p -> if (p.peers.remove(peer.second) != null) p.router.onLinkDown(peer.second) }
    }

    fun pump() {
        var guard = 0
        while (pending.isNotEmpty() && guard++ < 100_000) {
            val d = pending.removeFirst()
            if (d.failed) { nodes[d.from]?.router?.onPayloadFailed(d.pid); continue }
            val to = nodes[d.to] ?: continue
            if (!to.peers.containsKey(d.toLink)) { nodes[d.from]?.router?.onPayloadFailed(d.pid); continue }
            to.router.onBytes(d.toLink, d.bytes)
            nodes[d.from]?.router?.onPayloadSent(d.pid)
        }
        assertTrue("network never went quiet", guard < 100_000)
    }

    fun tickAll() { for (n in nodes.values) n.router.tick(); pump() }
    fun line(vararg labels: String) { labels.forEach { node(it) }; for (i in 0 until labels.size - 1) connect(labels[i], labels[i + 1]) }
    fun texts(label: String) = nodes[label]!!.router.messages.map { it.text }

    companion object {
        const val CODE = "tiger river lamp hat"
        /** A code from before 2.4: three words, and groups started with one still work. */
        const val OLD_CODE = "tiger river lamp"

        private val ring = HashMap<String, IdentityKeys>()
        private val byNodeId = HashMap<String, IdentityKeys>()

        /** One key pair per name for the whole test run: the same name is always the same phone. */
        fun keysOf(label: String): IdentityKeys = synchronized(ring) {
            ring.getOrPut(label) { IdentityKeys.generate().also { byNodeId[it.nodeId] = it } }
        }

        fun idOf(label: String): String = keysOf(label).nodeId
        fun identity(label: String, name: String = label): Identity = Identity(idOf(label), name, keysOf(label))
        /** The group as a phone in it has it (the slow stretch is remembered per code, so this is quick after the first). */
        fun group(code: String = CODE, name: String = "Trek", nameAt: Long = 0): Group = Group.derive(code, name, nameAt)

        /** A new message id of [label]'s, as its router would make it. */
        fun newId(label: String): String = "${idOf(label)}.${Crypto.randomId(10)}"

        /**
         * An envelope exactly as [from]'s phone would seal and sign it in the group of [code] —
         * for tests that hand a phone something crafted. A private kind is sealed for [to] (a
         * node id), whose key must be one of the test's phones.
         */
        fun envelope(from: String, kind: String, p: JSONObject, ts: Long, id: String = newId(from), to: String? = null,
                     name: String = from, h: Int = 0, code: String = CODE, er: JSONObject? = null, piece: String? = null): Envelope {
            val toKey = if (to != null && Envelope.privateKind(kind, to)) synchronized(ring) { byNodeId[to] }?.pub else null
            return Envelope.seal(group(code), identity(from, name), kind, p, id, ts, to, toKey, er, piece)!!.also {
                it.payload = null
                if (h != 0) it.hops = h
            }
        }

        /** Sign [env] again as [as] (after a test changed its fields), with that phone's key in it. */
        fun resign(env: Envelope, `as`: String, code: String = CODE): Envelope {
            env.json.put("pk", keysOf(`as`).pubB64)
            env.json.put("s", Crypto.sign(keysOf(`as`).priv, env.signed(group(code).airTag)))
            return env
        }

        /** A file of [r]'s, cut and sealed the way the app does it: its attachment, and the sealed pieces. */
        fun makeFile(r: Router, bytes: ByteArray, name: String = "photo.jpg", mime: String = "image/jpeg", thumb: String = "tb"): Pair<Attachment, List<String>> {
            val key = Crypto.randomBytes(32)
            val pieces = ArrayList<String>()
            var i = 0
            while (i < bytes.size) {
                val end = minOf(bytes.size, i + Router.CHUNK_RAW)
                pieces.add(Crypto.sealPiece(key, pieces.size, bytes.copyOfRange(i, end)))
                i = end
            }
            val att = Attachment.make(r.newFid(), name, mime, bytes.size.toLong(), pieces.size, 100, 75, thumb, key = key, sha = Crypto.sha256Hex(bytes))
            return att to pieces
        }

        /** The file of [att] put together from [r]'s pieces with the key its message brought: only what really opens. */
        fun reassemble(r: Router, att: Attachment): ByteArray {
            val mine = r.fileMessage(att.fid)?.att ?: att
            val out = java.io.ByteArrayOutputStream()
            for (i in 0 until mine.chunks) {
                val env = r.chunks.get(Envelope.chunkId(mine.fid, i))!!
                out.write(Crypto.openPiece(mine.key!!, i, env.sealed)!!)
            }
            return out.toByteArray()
        }

        /**
         * Complete the link handshake on [r]'s link [linkId] as phone [peer] would: hello, then a
         * proof of the group [code] and of [peer]'s key, bound to [token] (the link's token).
         */
        fun prove(r: Router, linkId: String, peer: String, token: String, code: String = CODE) {
            r.onBytes(linkId, JSONObject().put("t", "hello").put("id", idOf(peer)).put("nonce", Crypto.randomId(16)).put("v", Router.VERSION).toString().toByteArray())
            val t = Crypto.lp("hopline/v5/proof", token, r.links[linkId]!!.myNonce, idOf(peer), r.me.id)
            r.onBytes(linkId, JSONObject().put("t", "proof").put("mac", Crypto.b64(Crypto.hmac(group(code).keys.link, t)))
                .put("pk", keysOf(peer).pubB64).put("sig", Crypto.sign(keysOf(peer).priv, t)).toString().toByteArray())
        }

        fun frame(env: Envelope): ByteArray = JSONObject().put("t", "env").put("e", JSONObject(env.json.toString())).toString().toByteArray()
        fun fill(vararg envs: Envelope): ByteArray =
            JSONObject().put("t", "fill").put("envs", JSONArray(envs.map { JSONObject(it.json.toString()) })).toString().toByteArray()
    }
}

class RouterTest {

    @Test fun `word list is clean`() {
        assertTrue(Words.LIST.size > 250)
        assertEquals(Words.LIST.size, Words.LIST.toSet().size)
        assertTrue(Words.LIST.all { it.matches(Regex("[a-z]{2,9}")) })
        assertEquals("tiger-river-lamp", Words.normalise("  Tiger, RIVER   lamp "))
        assertTrue(Words.looksValid(Words.randomCode()))
    }

    @Test fun `canonical json is order independent`() {
        val a = JSONObject().put("b", 1).put("a", JSONObject().put("y", true).put("x", "s"))
        val b = JSONObject().put("a", JSONObject().put("x", "s").put("y", true)).put("b", 1)
        assertEquals(Crypto.canonical(a), Crypto.canonical(b))
    }

    @Test fun `message crosses a line of five phones exactly once and receipts come back`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        val m = net.nodes["A"]!!.router.sendChat("hi everyone"); net.pump()
        for (id in listOf("B", "C", "D", "E")) assertEquals(listOf("hi everyone"), net.texts(id))
        assertEquals(1, net.nodes["E"]!!.rec.shown.size)
        assertEquals(net.ids("B", "C", "D", "E"), m.reached)
        assertEquals(Message.SENT, m.status)
    }

    @Test fun `forged message is dropped`() {
        val net = FakeNet(); net.line("A", "B", "C")
        // A's own envelope, but with a signature that is over something else
        val fake = FakeNet.envelope("A", Envelope.CHAT, JSONObject().put("text", "fake"), net.now)
        fake.json.put("s", Crypto.sign(FakeNet.keysOf("A").priv, "something else".toByteArray()))
        net.nodes["B"]!!.router.onBytes("B>A", FakeNet.frame(fake))
        net.pump()
        assertTrue(net.texts("B").isEmpty()); assertTrue(net.texts("C").isEmpty())
        assertTrue(net.nodes["B"]!!.rec.log.any { it.contains("forged") })
    }

    @Test fun `phone with the wrong code cannot link and sees nothing`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["A"]!!.router.sendChat("secret plan"); net.pump()
        net.node("X", "Stranger", "wrong wrong wrong wrong"); net.connect("B", "X")
        assertTrue(net.nodes["B"]!!.router.links.values.none { it.nodeId == net.id("X") && it.authed })
        assertFalse(net.nodes["B"]!!.peers.containsKey("B>X"))
        assertTrue(net.texts("X").isEmpty())
    }

    @Test fun `late joiner gets the history`() {
        val net = FakeNet(); net.line("A", "B", "C")
        net.nodes["A"]!!.router.sendChat("first"); net.pump(); net.now += 1000
        net.nodes["B"]!!.router.sendChat("second"); net.pump()
        net.node("F"); net.connect("C", "F")
        assertEquals(listOf("first", "second"), net.texts("F"))
        assertEquals(net.ids("B", "C", "F"), net.nodes["A"]!!.router.messages[0].reached)
    }

    @Test fun `private message is only shown to its recipient and gets a double tick`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        val m = net.nodes["A"]!!.router.sendDm(net.id("E"), "meet at the bridge")!!; net.pump()
        assertEquals(listOf("meet at the bridge"), net.texts("E"))
        for (id in listOf("B", "C", "D")) assertTrue(net.texts(id).isEmpty())
        assertEquals(Message.DELIVERED, m.status)
    }

    @Test fun `chain breaks then heals - messages arrive exactly once`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        net.disconnect("C", "D")
        val m = net.nodes["A"]!!.router.sendChat("where are you?"); net.pump()
        assertEquals(listOf("where are you?"), net.texts("C")); assertTrue(net.texts("D").isEmpty())
        assertEquals(net.ids("B", "C"), m.reached)
        net.connect("C", "D")
        assertEquals(listOf("where are you?"), net.texts("D")); assertEquals(listOf("where are you?"), net.texts("E"))
        assertEquals(net.ids("B", "C", "D", "E"), m.reached)
        assertEquals(1, net.nodes["E"]!!.rec.shown.size)
    }

    @Test fun `message queued with nobody around goes out when someone appears`() {
        val net = FakeNet(); net.node("A"); net.node("B")
        val m = net.nodes["A"]!!.router.sendChat("anyone?"); net.pump()
        assertEquals(Message.QUEUED, m.status)
        net.connect("A", "B")
        assertEquals(listOf("anyone?"), net.texts("B")); assertEquals(Message.SENT, m.status)
    }

    @Test fun `a person walking between two separated groups carries the messages`() {
        val net = FakeNet(); net.line("A", "B"); net.line("D", "E"); net.node("C", "Courier")
        net.nodes["B"]!!.router.sendChat("dinner at 7"); net.pump()
        net.connect("B", "C"); net.disconnect("B", "C")       // courier meets group 1
        assertTrue(net.texts("E").isEmpty())
        net.connect("C", "D")                                 // courier walks to group 2
        assertEquals(listOf("dinner at 7"), net.texts("E"))
    }

    @Test fun `presence lists everyone with distance in hops`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        net.tickAll()
        val a = net.nodes["A"]!!.router
        assertEquals(net.ids("B", "C", "D", "E"), a.people.keys)
        assertEquals(1, a.people[net.id("B")]!!.hops); assertEquals(4, a.people[net.id("E")]!!.hops)
        assertTrue(a.people[net.id("B")]!!.direct); assertFalse(a.people[net.id("E")]!!.direct)
        assertEquals(4, a.peopleInRange())
        net.now += 10 * 60_000
        assertEquals(0, a.peopleInRange())
    }

    @Test fun `big groups skip chat receipts so a crowd cannot melt the radios`() {
        val net = FakeNet()
        // hub-and-spoke crowd: 20 phones all linked to A (people.size crosses the receipt limit)
        net.node("A"); (1..20).forEach { net.node("N$it") }
        (1..20).forEach { net.connect("A", "N$it") }
        net.tickAll()
        assertTrue(net.nodes["A"]!!.router.people.size >= Router.RECEIPT_GROUP_LIMIT)
        val m = net.nodes["A"]!!.router.sendChat("crowd hello"); net.pump()
        for (i in 1..20) assertEquals(listOf("crowd hello"), net.texts("N$i"))
        assertTrue("no receipts expected in a crowd", m.reached.isEmpty())
        // private messages still confirm person-to-person even in a crowd
        val dm = net.nodes["A"]!!.router.sendDm(net.id("N7"), "just you")!!; net.pump()
        assertEquals(Message.DELIVERED, dm.status)
    }

    @Test fun `presence slows down as the group grows`() {
        val net = FakeNet(); net.node("A")
        assertEquals(30_000L, net.nodes["A"]!!.router.presenceInterval())
        repeat(200) { Crypto.randomId(16).let { id -> net.nodes["A"]!!.router.people[id] = Person(id).also { p -> p.lastSeen = net.now } } }
        assertEquals(180_000L, net.nodes["A"]!!.router.presenceInterval())
        repeat(400) { Crypto.randomId(16).let { id -> net.nodes["A"]!!.router.people[id] = Person(id).also { p -> p.lastSeen = net.now } } }
        assertEquals(300_000L, net.nodes["A"]!!.router.presenceInterval())
        // people last heard from days ago are not "the group" any more
        net.now += Router.CARRY_MS + 1
        assertEquals(30_000L, net.nodes["A"]!!.router.presenceInterval())
    }

    @Test fun `a request made with nobody online is carried until a phone with signal answers it privately`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        val a = net.nodes["A"]!!.router; val e = net.nodes["E"]!!
        val errand = a.requestErrand(Errand.READ, JSONObject().put("url", "https://weather.example")); net.pump()
        assertEquals(Errand.WAITING, errand.status)           // nobody has signal yet — but it is on its way
        e.router.setCaps(Errand.CAP_READ); net.pump()           // E reaches the ridge
        net.advance(3_000)
        assertEquals(1, e.rec.errands.size); assertEquals("https://weather.example", e.rec.errands[0].args.getString("url"))
        assertEquals(Errand.CLAIMED, errand.status); assertEquals(net.id("E"), errand.helper)
        assertTrue(e.router.completeErrand(errand.id, true, "Web page", JSONObject().put("t", "Sunny, 18°C"))); net.pump()
        assertEquals(Errand.DONE, errand.status)
        assertEquals("Sunny, 18°C", errand.answer()!!.getString("t"))
        assertEquals(listOf(errand.id), net.nodes["A"]!!.rec.answers.map { it.id })
        // private: nobody's chat shows it, nobody else is notified
        for (id in listOf("A", "B", "C", "D", "E")) assertTrue(net.texts(id).none { it.contains("Sunny") })
        for (id in listOf("B", "C", "D")) assertTrue(net.nodes[id]!!.rec.shown.isEmpty())
        net.advance(60_000)
        assertEquals(1, e.rec.errands.size)                   // not run twice
    }

    @Test fun `my own request runs on my own signal`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!
        val er = a.router.requestErrand(Errand.READ, JSONObject().put("url", "https://x.example"), selfCaps = Errand.CAP_READ); net.pump()
        assertEquals(net.id("A"), er.helper); assertEquals(1, a.rec.errands.size)
        assertTrue(net.nodes["B"]!!.router.errands.isEmpty())   // nothing went on the air
        a.router.completeErrand(er.id, true, "x", JSONObject().put("t", "page"))
        assertEquals(Errand.DONE, er.status); assertEquals(1, a.rec.answers.size)
    }

    @Test fun `snapshot and restore keep messages and do not re-accept old envelopes`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["B"]!!.router.sendChat("remember me"); net.pump()
        val snap = net.nodes["A"]!!.router.snapshot()
        val fresh = FakeNet(); val a2 = fresh.node("A"); a2.router.restore(JSONObject(snap.toString()))
        assertEquals(listOf("remember me"), fresh.texts("A"))
        val b2 = fresh.node("B"); b2.router.restore(JSONObject(net.nodes["B"]!!.router.snapshot().toString()))
        fresh.connect("A", "B")
        assertEquals(1, fresh.texts("A").size)                 // sync did not duplicate it
        assertEquals(0, a2.rec.shown.size)
    }

    @Test fun `big backlog syncs in chunks`() {
        val net = FakeNet(); net.line("A", "B")
        repeat(900) { net.nodes["A"]!!.router.sendChat("msg $it") }
        net.pump()
        net.node("C"); net.connect("B", "C")
        assertEquals(900, net.texts("C").size)
    }

    @Test fun `a message survives far more than MAX_HOPS store-and-forward handoffs`() {
        // A bucket brigade: each phone carries the one message a single link further, then the link
        // breaks before the next forms. This is a courier chain far longer than the live-flood
        // ceiling — the 48 h carry must still deliver it, not let it die at MAX_HOPS mid-chain.
        val net = FakeNet()
        val n = Envelope.MAX_HOPS + 8
        for (i in 0..n) net.node("N$i")
        net.nodes["N0"]!!.router.sendChat("hop me far"); net.pump()
        for (i in 0 until n) { net.connect("N$i", "N${i + 1}"); net.disconnect("N$i", "N${i + 1}") }
        assertEquals(listOf("hop me far"), net.texts("N$n"))
    }

    @Test fun `a relayed message a flaky radio drops is healed by periodic sync without a relink`() {
        // The subtle gap: A's DM reaches B, so A sees it off its phone and never retries. B only
        // RELAYS it (not the origin, and — as a DM it isn't the recipient of — it sends no receipt),
        // so B's single relay frame to C is the only thing carrying it onward. When the flaky radio
        // loses that one frame, nothing but a link-up re-sync would ever fix C — yet the links stay
        // up the whole time. Periodic anti-entropy is what closes it.
        val net = FakeNet(); net.line("A", "B", "C")
        net.dropEnvFrom = "B"; net.dropEnvFrames = 1               // lose B's one relay hop to C
        net.nodes["A"]!!.router.sendDm(net.id("C"), "did you get this?")!!; net.pump()
        assertTrue("C's relay frame was dropped, links still up", net.texts("C").isEmpty())
        assertTrue("B is carrying it, it just couldn't relay", net.nodes["B"]!!.router.carrySize() > 0)
        net.now += Router.SYNC_MS + 1_000; net.tickAll()           // anti-entropy reconciles the gap
        assertEquals(listOf("did you get this?"), net.texts("C"))
    }

    // ---------------------------------------------------------------- photos & files

    @Test fun `photo hops down the line in pieces and arrives whole`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        val bytes = ByteArray(60_000) { (it % 251).toByte() }
        val (att, pieces) = FakeNet.makeFile(net.nodes["A"]!!.router, bytes)
        val m = net.nodes["A"]!!.router.sendFile(att, pieces, "sunset from the ridge")!!; net.pump()
        val e = net.nodes["E"]!!
        assertEquals(listOf("sunset from the ridge"), net.texts("E"))
        assertTrue(e.router.fileComplete(att))
        assertArrayEquals(bytes, FakeNet.reassemble(e.router, att))
        assertEquals(1, e.rec.files.size)                       // onFileReady fired exactly once
        assertEquals(net.ids("B", "C", "D", "E"), m.reached)    // the ✓ waited for the last piece
        assertTrue("frame was ${net.maxFrameBytes} bytes", net.maxFrameBytes < 32_000)
    }

    @Test fun `late joiner gets the photo pieces through gap fill`() {
        val net = FakeNet(); net.line("A", "B")
        val bytes = ByteArray(40_000) { (it * 7 % 256).toByte() }
        val (att, pieces) = FakeNet.makeFile(net.nodes["A"]!!.router, bytes)
        net.nodes["A"]!!.router.sendFile(att, pieces, "")!!; net.pump()
        net.node("F"); net.connect("B", "F")
        val f = net.nodes["F"]!!
        assertTrue(f.router.fileComplete(att))
        assertArrayEquals(bytes, FakeNet.reassemble(f.router, att))
        assertEquals(1, f.rec.files.size)
    }

    @Test fun `private photo is carried by middlemen but shown only to its recipient`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val bytes = ByteArray(20_000) { it.toByte() }
        val (att, pieces) = FakeNet.makeFile(net.nodes["A"]!!.router, bytes)
        val m = net.nodes["A"]!!.router.sendFile(att, pieces, "just for you", to = net.id("C"))!!; net.pump()
        assertTrue(net.texts("B").isEmpty())                    // B carries but never sees it
        assertEquals(listOf("just for you"), net.texts("C"))
        assertTrue(net.nodes["B"]!!.router.chunks.ids().isNotEmpty())
        assertArrayEquals(bytes, FakeNet.reassemble(net.nodes["C"]!!.router, att))
        assertEquals(Message.DELIVERED, m.status)               // ✓✓ once C has every piece
    }

    @Test fun `file receipt waits for the last piece`() {
        // Feed B the meta by hand, without the pieces: no receipt, no onFileReady yet.
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        val (att, pieces) = FakeNet.makeFile(a, ByteArray(30_000) { it.toByte() })
        val m = a.sendFile(att, pieces, "slow photo")!!; net.pump()
        // Everything arrives in one pump here, so instead check a fresh phone that has only the meta.
        assertEquals(net.ids("B"), m.reached)
        val loner = FakeNet(); val x = loner.node("X")
        val metaOnly = JSONObject().put("messages", JSONArray(listOf(
            Message(m.id, Envelope.FILE, net.id("A"), "A", null, "slow photo", loner.now, att).toJson())))
        x.router.restore(metaOnly)
        assertFalse(x.router.fileComplete(att))
        assertEquals(0, x.rec.files.size)
        assertEquals(0, x.router.fileProgress(att))
    }

    @Test fun `unknown envelope kinds from newer versions are ignored without crashing`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val future = FakeNet.envelope("A", "hologram", JSONObject().put("x", 1), net.now)
        net.nodes["B"]!!.router.onBytes("B>A", FakeNet.frame(future))
        net.pump()
        assertTrue(net.texts("B").isEmpty()); assertTrue(net.texts("C").isEmpty())
    }

    @Test fun `files switch off in a crowd`() {
        val net = FakeNet(); net.node("A")
        assertTrue(net.nodes["A"]!!.router.canSendFiles())
        repeat(Router.FILE_GROUP_LIMIT) { Crypto.randomId(16).let { id -> net.nodes["A"]!!.router.people[id] = Person(id).also { p -> p.lastSeen = net.now } } }
        assertFalse(net.nodes["A"]!!.router.canSendFiles())
    }

    // ---------------------------------------------------------------- locations

    @Test fun `location hops down the line with a maps link for old clients`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        val loc = Loc.of(12.9716, 77.5946, 8, "Base camp")!!
        val m = net.nodes["A"]!!.router.sendLocation(loc)!!; net.pump()
        val got = net.nodes["E"]!!.router.messages.single()
        assertNotNull(got.loc)
        assertEquals(12.9716, got.loc!!.lat, 1e-6); assertEquals(77.5946, got.loc!!.lng, 1e-6)
        assertEquals(8, got.loc!!.acc); assertEquals("Base camp", got.loc!!.label)
        // The visible text is the 1.x fallback: a link Google Maps opens.
        assertTrue(got.text.contains("maps.google.com/?q=12.971600,77.594600"))
        assertTrue(got.text.contains("Base camp"))
        assertEquals(net.ids("B", "C", "D", "E"), m.reached)
    }

    @Test fun `private location is only shown to its recipient`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val m = net.nodes["A"]!!.router.sendLocation(Loc.of(1.0, 2.0)!!, to = net.id("C"))!!; net.pump()
        assertTrue(net.texts("B").isEmpty())
        assertNotNull(net.nodes["C"]!!.router.messages.single().loc)
        assertEquals(Message.DELIVERED, m.status)
    }

    @Test fun `absurd coordinates from a crafted client fall back to plain text`() {
        val net = FakeNet(); net.line("A", "B")
        val env = FakeNet.envelope("A", Envelope.CHAT,
            JSONObject().put("text", "meet here").put("loc", JSONObject().put("lat", 999_000_000L).put("lng", 0)), net.now)
        net.nodes["B"]!!.router.onBytes("B>A", FakeNet.frame(env))
        net.pump()
        val got = net.nodes["B"]!!.router.messages.single()
        assertNull(got.loc); assertEquals("meet here", got.text)
    }

    // ---------------------------------------------------------------- replies, reactions, mentions

    @Test fun `a reply carries its quote down the line`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val original = net.nodes["A"]!!.router.sendChat("meet at the bridge"); net.pump()
        net.nodes["C"]!!.router.sendChat("on my way", Quote.of(original)); net.pump()
        val got = net.nodes["A"]!!.router.messages.first { it.text == "on my way" }
        assertEquals(original.id, got.quote!!.id)
        assertEquals("A", got.quote!!.name)
        assertEquals("meet at the bridge", got.quote!!.text)
    }

    @Test fun `a photo sent as a reply carries the quote too`() {
        val net = FakeNet(); net.line("A", "B")
        val original = net.nodes["B"]!!.router.sendChat("which peak is that?"); net.pump()
        val a = net.nodes["A"]!!.router
        val (att, pieces) = FakeNet.makeFile(a, ByteArray(20_000) { it.toByte() }, name = "p.jpg")
        a.sendFile(att, pieces, "this one", quote = Quote.of(a.message(original.id)!!))!!
        net.pump()
        val got = net.nodes["B"]!!.router.messages.first { it.att != null }
        assertEquals(original.id, got.quote!!.id)
        assertEquals("which peak is that?", got.quote!!.text)
    }

    @Test fun `reactions add change and remove with last-write-wins`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val m = net.nodes["A"]!!.router.sendChat("sunset!"); net.pump()
        val onB = net.nodes["B"]!!.router.message(m.id)!!
        val b = net.id("B")
        net.nodes["B"]!!.router.sendReaction(onB, "👍"); net.pump()
        assertEquals("👍", m.reactions[b])
        assertEquals("👍", net.nodes["C"]!!.router.message(m.id)!!.reactions[b])
        net.now += 1000
        net.nodes["B"]!!.router.sendReaction(onB, "❤️"); net.pump()   // changed their mind
        assertEquals("❤️", m.reactions[b]); assertEquals(1, m.reactions.size)
        net.now += 1000
        net.nodes["B"]!!.router.sendReaction(onB, ""); net.pump()     // took it back
        assertTrue(m.reactions.isEmpty())
        assertTrue(net.nodes["C"]!!.router.message(m.id)!!.reactions.isEmpty())
    }

    @Test fun `late joiner sees reactions through gap fill`() {
        val net = FakeNet(); net.line("A", "B")
        val m = net.nodes["A"]!!.router.sendChat("group photo"); net.pump()
        net.nodes["B"]!!.router.sendReaction(net.nodes["B"]!!.router.message(m.id)!!, "😂"); net.pump()
        net.node("C"); net.connect("B", "C")
        assertEquals("😂", net.nodes["C"]!!.router.message(m.id)!!.reactions[net.id("B")])
    }

    @Test fun `a reaction that arrives before its message waits for it`() {
        val net = FakeNet(); net.line("A", "B")
        val chatId = FakeNet.newId("Z")
        val chat = FakeNet.envelope("Z", Envelope.CHAT, JSONObject().put("text", "hello"), net.now, id = chatId, name = "Zoe")
        val react = FakeNet.envelope("Y", Envelope.REACT, JSONObject().put("m", chatId).put("e", "🙏"), net.now + 1, name = "Yan")
        val b = net.nodes["B"]!!.router
        b.onBytes("B>A", FakeNet.frame(react))
        assertNull(b.message(chatId))
        b.onBytes("B>A", FakeNet.frame(chat))
        assertEquals("🙏", b.message(chatId)!!.reactions[net.id("Y")])
    }

    @Test fun `private chat reactions stay between its two people`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val dm = net.nodes["A"]!!.router.sendDm(net.id("C"), "just us")!!; net.pump()
        val onC = net.nodes["C"]!!.router.message(dm.id)!!
        assertTrue(net.nodes["C"]!!.router.sendReaction(onC, "❤️")); net.pump()
        assertEquals("❤️", dm.reactions[net.id("C")])      // the sender sees it
        assertNull(net.nodes["B"]!!.router.message(dm.id)) // the middleman never had the message
        assertTrue(net.nodes["B"]!!.router.carrySize() > 0)
    }

    @Test fun `an absurdly long reaction is clipped and reactions survive restore`() {
        val net = FakeNet(); net.line("A", "B")
        val m = net.nodes["A"]!!.router.sendChat("hi"); net.pump()
        net.nodes["B"]!!.router.sendReaction(net.nodes["B"]!!.router.message(m.id)!!, "x".repeat(500)); net.pump()
        assertEquals(Message.MAX_EMOJI, m.reactions[net.id("B")]!!.length)
        val fresh = FakeNet(); val a2 = fresh.node("A")
        a2.router.restore(JSONObject(net.nodes["A"]!!.router.snapshot().toString()))
        assertEquals(m.reactions[net.id("B")], a2.router.message(m.id)!!.reactions[net.id("B")])
    }

    @Test fun `mentions travel and are capped`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["A"]!!.router.sendChat("@Bea @Cal wake up", mentions = (1..30).map { "id$it" }); net.pump()
        val got = net.nodes["B"]!!.router.messages.single()
        assertEquals(Message.MAX_MENTIONS, got.mentions.size)
        assertEquals("id1", got.mentions.first())
    }

    @Test fun `a stashed reaction survives a restart through the carry`() {
        val net = FakeNet(); net.line("A", "B")
        val chatId = FakeNet.newId("Z")
        val react = FakeNet.envelope("Y", Envelope.REACT, JSONObject().put("m", chatId).put("e", "👍"), net.now, name = "Yan")
        net.nodes["B"]!!.router.onBytes("B>A", FakeNet.frame(react))
        // B restarts before the message itself ever arrives
        val fresh = FakeNet(); val b2 = fresh.node("B")
        b2.router.restore(JSONObject(net.nodes["B"]!!.router.snapshot().toString()))
        fresh.node("A"); fresh.connect("A", "B")
        val chat = FakeNet.envelope("Z", Envelope.CHAT, JSONObject().put("text", "late"), fresh.now, id = chatId, name = "Zoe")
        b2.router.onBytes("B>A", FakeNet.frame(chat))
        assertEquals("👍", b2.router.message(chatId)!!.reactions[net.id("Y")])
    }

    @Test fun `one message cannot be ballooned by invented reactors`() {
        val m = Message("x", Envelope.CHAT, "A", "A", null, "hi", 1L)
        for (i in 0 until Message.MAX_REACTORS + 100) m.applyReaction("fake$i", "👍", i.toLong())
        assertEquals(Message.MAX_REACTORS, m.reactions.size)
        // existing reactors can still change or remove theirs at the cap
        assertTrue(m.applyReaction("fake0", "❤️", 999_999L))
        assertTrue(m.applyReaction("fake1", "", 999_999L))
        assertEquals(Message.MAX_REACTORS - 1, m.reactions.size)
    }

    // ---------------------------------------------------------------- live location

    @Test fun `live location rides presence and clears when sharing stops`() {
        val net = FakeNet(); net.line("A", "B", "C")
        net.nodes["A"]!!.router.myLoc = Loc.of(12.9716, 77.5946, 10)
        net.tickAll()
        val seenByC = net.nodes["C"]!!.router.people[net.id("A")]!!
        assertEquals(12.9716, seenByC.loc!!.lat, 1e-6)
        assertNotNull(net.nodes["C"]!!.router.liveLocOf(seenByC))
        net.nodes["A"]!!.router.myLoc = null                  // stopped sharing
        net.now += 31_000; net.tickAll()                       // next beacon carries no loc
        assertNull(seenByC.loc)
        // and a beacon that stops coming goes stale instead of lying forever
        net.nodes["A"]!!.router.myLoc = Loc.of(1.0, 2.0)
        net.now += 31_000; net.tickAll()
        assertNotNull(net.nodes["C"]!!.router.liveLocOf(seenByC))
        net.now += 10 * 60_000
        assertNull(net.nodes["C"]!!.router.liveLocOf(seenByC))
    }

    @Test fun `voice note length rides the attachment`() {
        val att = Attachment.make(Crypto.randomId(12), "voice-1.m4a", "audio/mp4", 90_000, 7, 0, 0, "", 23)
        val back = Attachment(JSONObject(att.json.toString()))
        assertTrue(back.isAudio); assertEquals(23, back.dur)
        val old = Attachment.make(Crypto.randomId(12), "photo.jpg", "image/jpeg", 100, 1, 10, 10, "tb")
        assertEquals(0, old.dur); assertFalse(old.isAudio)
    }

    @Test fun `location survives snapshot and restore`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["A"]!!.router.sendLocation(Loc.of(-33.856789, 151.215256, 12, "Opera House")!!); net.pump()
        val fresh = FakeNet(); val b2 = fresh.node("B")
        b2.router.restore(JSONObject(net.nodes["B"]!!.router.snapshot().toString()))
        val got = b2.router.messages.single().loc!!
        assertEquals(-33.856789, got.lat, 1e-6); assertEquals(151.215256, got.lng, 1e-6)
        assertEquals(12, got.acc); assertEquals("Opera House", got.label)
    }
}

class LocTest {
    @Test fun `parses what people actually paste`() {
        assertEquals(12.9716 to 77.5946, Loc.parse("12.9716, 77.5946")!!.let { it.lat to it.lng })
        assertEquals(12.9716 to 77.5946, Loc.parse("12.9716 77.5946")!!.let { it.lat to it.lng })
        assertEquals(-12.5 to -77.25, Loc.parse("12.5 S, 77.25 W")!!.let { it.lat to it.lng })
        assertEquals(48.8584 to 2.2945, Loc.parse("geo:48.8584,2.2945?z=17")!!.let { it.lat to it.lng })
        assertEquals(48.8584 to 2.2945, Loc.parse("https://maps.google.com/?q=48.8584,2.2945")!!.let { it.lat to it.lng })
        assertEquals(48.8584 to 2.2945, Loc.parse("https://www.google.com/maps/search/?api=1&query=48.8584%2C2.2945")!!.let { it.lat to it.lng })
        // place-page URL: the pair after @ is the pin, the trailing 17z is zoom, not a longitude
        assertEquals(27.9881 to 86.925, Loc.parse("https://www.google.com/maps/place/Everest/@27.9881,86.9250,17z/data=xyz")!!.let { it.lat to it.lng })
        assertNull(Loc.parse("see you at the bridge"))
        assertNull(Loc.parse("999, 12"))
        assertNull(Loc.parse(""))
    }

    @Test fun `microdegrees round-trip without float drift`() {
        val loc = Loc.of(12.123456789, -77.987654321, 5, "x")!!
        val back = Loc.fromJson(JSONObject(loc.toJson().toString()))!!
        assertEquals(loc.latE6, back.latE6); assertEquals(loc.lngE6, back.lngE6)
        assertEquals(12.123457, back.lat, 1e-6)
    }

    @Test fun `distance bearing and compass make sense`() {
        // ~111 km per degree of latitude, due north
        val d = Loc.distanceMeters(12.0, 77.0, 13.0, 77.0)
        assertTrue("was $d", d > 110_000 && d < 112_000)
        assertEquals("north", Loc.compass(Loc.bearingDeg(12.0, 77.0, 13.0, 77.0)))
        assertEquals("east", Loc.compass(Loc.bearingDeg(0.0, 77.0, 0.0, 78.0)))
        assertEquals("south-west", Loc.compass(225.0))
        assertEquals("north", Loc.compass(359.0))
        assertEquals("42 m", Loc.prettyDistance(42.4))
        assertEquals("1.2 km", Loc.prettyDistance(1234.0))
        assertEquals("57 km", Loc.prettyDistance(56_789.0))
    }
}
