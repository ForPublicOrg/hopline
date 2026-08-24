package app.hopline.mesh

import app.hopline.core.Crypto
import app.hopline.core.Words
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * A fake radio: phones are connected by explicit links, frames are delivered in order, and
 * delivery acks fire after each frame. Lets us simulate a whole trekking group on a laptop.
 */
class FakeNet {
    var now = 1_700_000_000_000L
    var maxFrameBytes = 0
    val nodes = LinkedHashMap<String, Node>()
    private val pending = ArrayDeque<Delivery>()
    private var payloadSeq = 0L

    class Delivery(val from: String, val pid: Long, val to: String, val toLink: String, val bytes: ByteArray)

    inner class Recorder : RouterListener {
        val shown = ArrayList<Message>(); val errands = ArrayList<Errand>(); val log = ArrayList<String>()
        val files = ArrayList<Message>()
        override fun onChanged() {}
        override fun onMessage(m: Message) { shown.add(m) }
        override fun onErrandRequest(e: Errand) { errands.add(e) }
        override fun onFileReady(m: Message) { files.add(m) }
        override fun onLog(text: String) { log.add(text) }
    }

    inner class Node(val id: String, val name: String, code: String) {
        val peers = HashMap<String, Pair<String, String>>()  // my linkId -> (peer node, peer's linkId)
        val rec = Recorder()
        val transport = object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long {
                val (peer, peerLink) = peers[linkId] ?: return -1
                val pid = ++payloadSeq
                if (bytes.size > maxFrameBytes) maxFrameBytes = bytes.size
                pending.addLast(Delivery(id, pid, peer, peerLink, bytes))
                return pid
            }
            override fun disconnect(linkId: String) { cut(id, linkId) }
        }
        val router = Router(Identity(id, name), Group(code, "Trek"), transport, rec) { now }
    }

    fun node(id: String, name: String = id, code: String = CODE): Node = Node(id, name, code).also { nodes[id] = it }

    fun connect(a: String, b: String) {
        val la = "$a>$b"; val lb = "$b>$a"
        nodes[a]!!.peers[la] = b to lb; nodes[b]!!.peers[lb] = a to la
        nodes[a]!!.router.onLinkUp(la, b, nodes[b]!!.name)
        nodes[b]!!.router.onLinkUp(lb, a, nodes[a]!!.name)
        pump()
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
            val to = nodes[d.to] ?: continue
            if (!to.peers.containsKey(d.toLink)) { nodes[d.from]?.router?.onPayloadFailed(d.pid); continue }
            to.router.onBytes(d.toLink, d.bytes)
            nodes[d.from]?.router?.onPayloadSent(d.pid)
        }
        assertTrue("network never went quiet", guard < 100_000)
    }

    fun tickAll() { for (n in nodes.values) n.router.tick(); pump() }
    fun line(vararg ids: String) { ids.forEach { node(it) }; for (i in 0 until ids.size - 1) connect(ids[i], ids[i + 1]) }
    fun texts(id: String) = nodes[id]!!.router.messages.map { it.text }

    companion object { const val CODE = "tiger river lamp" }
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
        assertEquals(setOf("B", "C", "D", "E"), m.reached)
        assertEquals(Message.SENT, m.status)
    }

    @Test fun `forged message is dropped`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val fake = JSONObject().put("id", "zzzzzzzzzzzz").put("k", "chat").put("o", "A").put("on", "A").put("ts", net.now).put("h", 0)
            .put("p", JSONObject().put("text", "fake")).put("s", "00")
        net.nodes["B"]!!.router.onBytes("B>A", JSONObject().put("t", "env").put("e", fake).toString().toByteArray())
        net.pump()
        assertTrue(net.texts("B").isEmpty()); assertTrue(net.texts("C").isEmpty())
        assertTrue(net.nodes["B"]!!.rec.log.any { it.contains("forged") })
    }

    @Test fun `phone with the wrong code cannot link and sees nothing`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["A"]!!.router.sendChat("secret plan"); net.pump()
        net.node("X", "Stranger", "wrong wrong wrong"); net.connect("B", "X")
        assertTrue(net.nodes["B"]!!.router.links.values.none { it.nodeId == "X" && it.authed })
        assertFalse(net.nodes["B"]!!.peers.containsKey("B>X"))
        assertTrue(net.texts("X").isEmpty())
    }

    @Test fun `late joiner gets the history`() {
        val net = FakeNet(); net.line("A", "B", "C")
        net.nodes["A"]!!.router.sendChat("first"); net.pump(); net.now += 1000
        net.nodes["B"]!!.router.sendChat("second"); net.pump()
        net.node("F"); net.connect("C", "F")
        assertEquals(listOf("first", "second"), net.texts("F"))
        assertEquals(setOf("B", "C", "F"), net.nodes["A"]!!.router.messages[0].reached)
    }

    @Test fun `private message is only shown to its recipient and gets a double tick`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        val m = net.nodes["A"]!!.router.sendDm("E", "meet at the bridge"); net.pump()
        assertEquals(listOf("meet at the bridge"), net.texts("E"))
        for (id in listOf("B", "C", "D")) assertTrue(net.texts(id).isEmpty())
        assertEquals(Message.DELIVERED, m.status)
    }

    @Test fun `chain breaks then heals - messages arrive exactly once`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        net.disconnect("C", "D")
        val m = net.nodes["A"]!!.router.sendChat("where are you?"); net.pump()
        assertEquals(listOf("where are you?"), net.texts("C")); assertTrue(net.texts("D").isEmpty())
        assertEquals(setOf("B", "C"), m.reached)
        net.connect("C", "D")
        assertEquals(listOf("where are you?"), net.texts("D")); assertEquals(listOf("where are you?"), net.texts("E"))
        assertEquals(setOf("B", "C", "D", "E"), m.reached)
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
        assertEquals(setOf("B", "C", "D", "E"), a.people.keys)
        assertEquals(1, a.people["B"]!!.hops); assertEquals(4, a.people["E"]!!.hops)
        assertTrue(a.people["B"]!!.direct); assertFalse(a.people["E"]!!.direct)
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
        val dm = net.nodes["A"]!!.router.sendDm("N7", "just you"); net.pump()
        assertEquals(Message.DELIVERED, dm.status)
    }

    @Test fun `presence slows down as the group grows`() {
        val net = FakeNet(); net.node("A")
        assertEquals(30_000L, net.nodes["A"]!!.router.presenceInterval())
        repeat(200) { net.nodes["A"]!!.router.people["p$it"] = Person("p$it") }
        assertEquals(180_000L, net.nodes["A"]!!.router.presenceInterval())
        repeat(400) { net.nodes["A"]!!.router.people["q$it"] = Person("q$it") }
        assertEquals(300_000L, net.nodes["A"]!!.router.presenceInterval())
    }

    @Test fun `errand goes to the phone with internet and the answer comes back to all`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        val a = net.nodes["A"]!!.router; val e = net.nodes["E"]!!
        val errand = a.requestErrand(Errand.READ, JSONObject().put("url", "http://weather.example")); net.pump()
        assertEquals(Errand.WAITING, errand.status)           // nobody has internet yet
        e.router.hasInternet = true
        net.tickAll()                                          // presence announces it; A dispatches
        assertEquals(Errand.ASKED, errand.status); assertEquals("E", errand.helper)
        assertEquals(1, e.rec.errands.size); assertEquals("http://weather.example", e.rec.errands[0].args.getString("url"))
        e.router.completeErrand(errand.id, true, "Web page: weather.example", "Sunny, 18°C"); net.pump()
        assertEquals(Errand.DONE, errand.status)
        for (id in listOf("A", "C", "E")) assertTrue(net.texts(id).any { it.contains("Sunny, 18°C") })
        assertEquals(1, e.rec.errands.size)                   // not asked twice
    }

    @Test fun `errand is done locally when I am the one with internet`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!; a.router.hasInternet = true
        val er = a.router.requestErrand(Errand.READ, JSONObject().put("url", "http://x")); net.pump()
        assertEquals("A", er.helper); assertEquals(1, a.rec.errands.size)
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

    // ---------------------------------------------------------------- photos & files

    private fun makeFile(bytes: ByteArray): Pair<Attachment, List<String>> {
        val pieces = ArrayList<String>()
        var i = 0
        while (i < bytes.size) {
            val end = minOf(bytes.size, i + Router.CHUNK_RAW)
            pieces.add(java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(i, end)))
            i = end
        }
        val att = Attachment.make(Crypto.randomId(12), "photo.jpg", "image/jpeg", bytes.size.toLong(), pieces.size, 100, 75, "tb")
        return att to pieces
    }

    private fun reassemble(r: Router, att: Attachment): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until att.chunks) {
            val env = r.chunks.get(Envelope.chunkId(att.fid, i))!!
            out.write(java.util.Base64.getDecoder().decode(env.payload.getString("d")))
        }
        return out.toByteArray()
    }

    @Test fun `photo hops down the line in pieces and arrives whole`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        val bytes = ByteArray(60_000) { (it % 251).toByte() }
        val (att, pieces) = makeFile(bytes)
        val m = net.nodes["A"]!!.router.sendFile(att, pieces, "sunset from the ridge"); net.pump()
        val e = net.nodes["E"]!!
        assertEquals(listOf("sunset from the ridge"), net.texts("E"))
        assertTrue(e.router.fileComplete(att))
        assertArrayEquals(bytes, reassemble(e.router, att))
        assertEquals(1, e.rec.files.size)                       // onFileReady fired exactly once
        assertEquals(setOf("B", "C", "D", "E"), m.reached)      // the ✓ waited for the last piece
        assertTrue("frame was ${net.maxFrameBytes} bytes", net.maxFrameBytes < 32_000)
    }

    @Test fun `late joiner gets the photo pieces through gap fill`() {
        val net = FakeNet(); net.line("A", "B")
        val bytes = ByteArray(40_000) { (it * 7 % 256).toByte() }
        val (att, pieces) = makeFile(bytes)
        net.nodes["A"]!!.router.sendFile(att, pieces, ""); net.pump()
        net.node("F"); net.connect("B", "F")
        val f = net.nodes["F"]!!
        assertTrue(f.router.fileComplete(att))
        assertArrayEquals(bytes, reassemble(f.router, att))
        assertEquals(1, f.rec.files.size)
    }

    @Test fun `private photo is carried by middlemen but shown only to its recipient`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val (att, pieces) = makeFile(ByteArray(20_000) { it.toByte() })
        val m = net.nodes["A"]!!.router.sendFile(att, pieces, "just for you", to = "C"); net.pump()
        assertTrue(net.texts("B").isEmpty())                    // B carries but never sees it
        assertEquals(listOf("just for you"), net.texts("C"))
        assertTrue(net.nodes["B"]!!.router.chunks.ids().isNotEmpty())
        assertEquals(Message.DELIVERED, m.status)               // ✓✓ once C has every piece
    }

    @Test fun `file receipt waits for the last piece`() {
        // Feed B the meta by hand, without the pieces: no receipt, no onFileReady yet.
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!
        val (att, pieces) = makeFile(ByteArray(30_000) { it.toByte() })
        val m = a.sendFile(att, pieces, "slow photo"); net.pump()
        // Everything arrives in one pump here, so instead check a fresh phone that has only the meta.
        assertEquals(setOf("B"), m.reached)
        val loner = FakeNet(); val x = loner.node("X")
        val metaOnly = JSONObject().put("messages", org.json.JSONArray(listOf(
            Message(m.id, Envelope.FILE, "A", "A", null, "slow photo", loner.now, att).toJson())))
        x.router.restore(metaOnly)
        assertFalse(x.router.fileComplete(att))
        assertEquals(0, x.rec.files.size)
        assertEquals(0, x.router.fileProgress(att))
    }

    @Test fun `unknown envelope kinds from newer versions are ignored without crashing`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val future = Envelope(JSONObject().put("id", Crypto.randomId(12)).put("k", "hologram").put("o", "A").put("on", "A")
            .put("ts", net.now).put("h", 0).put("p", JSONObject().put("x", 1)))
        future.sign(net.nodes["A"]!!.router.group.key)
        net.nodes["B"]!!.router.onBytes("B>A", JSONObject().put("t", "env").put("e", future.json).toString().toByteArray())
        net.pump()
        assertTrue(net.texts("B").isEmpty()); assertTrue(net.texts("C").isEmpty())
    }

    @Test fun `files switch off in a crowd`() {
        val net = FakeNet(); net.node("A")
        assertTrue(net.nodes["A"]!!.router.canSendFiles())
        repeat(Router.FILE_GROUP_LIMIT) { net.nodes["A"]!!.router.people["p$it"] = Person("p$it") }
        assertFalse(net.nodes["A"]!!.router.canSendFiles())
    }

    // ---------------------------------------------------------------- locations

    @Test fun `location hops down the line with a maps link for old clients`() {
        val net = FakeNet(); net.line("A", "B", "C", "D", "E")
        val loc = Loc.of(12.9716, 77.5946, 8, "Base camp")!!
        val m = net.nodes["A"]!!.router.sendLocation(loc); net.pump()
        val got = net.nodes["E"]!!.router.messages.single()
        assertNotNull(got.loc)
        assertEquals(12.9716, got.loc!!.lat, 1e-6); assertEquals(77.5946, got.loc!!.lng, 1e-6)
        assertEquals(8, got.loc!!.acc); assertEquals("Base camp", got.loc!!.label)
        // The visible text is the 1.x fallback: a link Google Maps opens.
        assertTrue(got.text.contains("maps.google.com/?q=12.971600,77.594600"))
        assertTrue(got.text.contains("Base camp"))
        assertEquals(setOf("B", "C", "D", "E"), m.reached)
    }

    @Test fun `private location is only shown to its recipient`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val m = net.nodes["A"]!!.router.sendLocation(Loc.of(1.0, 2.0)!!, to = "C"); net.pump()
        assertTrue(net.texts("B").isEmpty())
        assertNotNull(net.nodes["C"]!!.router.messages.single().loc)
        assertEquals(Message.DELIVERED, m.status)
    }

    @Test fun `absurd coordinates from a crafted client fall back to plain text`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        val forged = JSONObject().put("id", Crypto.randomId(12)).put("k", "chat").put("o", "A").put("on", "A")
            .put("ts", net.now).put("h", 0)
            .put("p", JSONObject().put("text", "meet here").put("loc", JSONObject().put("lat", 999_000_000L).put("lng", 0)))
        val env = Envelope(forged).also { it.sign(a.group.key) }
        net.nodes["B"]!!.router.onBytes("B>A", JSONObject().put("t", "env").put("e", env.json).toString().toByteArray())
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
        val bytes = ByteArray(20_000) { it.toByte() }
        val pieces = ArrayList<String>()
        var i = 0
        while (i < bytes.size) {
            val end = minOf(bytes.size, i + Router.CHUNK_RAW)
            pieces.add(java.util.Base64.getEncoder().encodeToString(bytes.copyOfRange(i, end)))
            i = end
        }
        val att = Attachment.make(Crypto.randomId(12), "p.jpg", "image/jpeg", bytes.size.toLong(), pieces.size, 10, 10, "tb")
        net.nodes["A"]!!.router.sendFile(att, pieces, "this one", quote = Quote.of(net.nodes["A"]!!.router.message(original.id)!!))
        net.pump()
        val got = net.nodes["B"]!!.router.messages.first { it.att != null }
        assertEquals(original.id, got.quote!!.id)
        assertEquals("which peak is that?", got.quote!!.text)
    }

    @Test fun `reactions add change and remove with last-write-wins`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val m = net.nodes["A"]!!.router.sendChat("sunset!"); net.pump()
        val onB = net.nodes["B"]!!.router.message(m.id)!!
        net.nodes["B"]!!.router.sendReaction(onB, "👍"); net.pump()
        assertEquals("👍", m.reactions["B"])
        assertEquals("👍", net.nodes["C"]!!.router.message(m.id)!!.reactions["B"])
        net.now += 1000
        net.nodes["B"]!!.router.sendReaction(onB, "❤️"); net.pump()   // changed their mind
        assertEquals("❤️", m.reactions["B"]); assertEquals(1, m.reactions.size)
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
        assertEquals("😂", net.nodes["C"]!!.router.message(m.id)!!.reactions["B"])
    }

    @Test fun `a reaction that arrives before its message waits for it`() {
        val net = FakeNet(); net.line("A", "B")
        val key = net.nodes["A"]!!.router.group.key
        val chat = Envelope(JSONObject().put("id", "zmsgzmsgzmsg").put("k", "chat").put("o", "Z").put("on", "Zoe")
            .put("ts", net.now).put("h", 0).put("p", JSONObject().put("text", "hello"))).also { it.sign(key) }
        val react = Envelope(JSONObject().put("id", "zreactzreact").put("k", "reac").put("o", "Y").put("on", "Yan")
            .put("ts", net.now + 1).put("h", 0).put("p", JSONObject().put("m", "zmsgzmsgzmsg").put("e", "🙏"))).also { it.sign(key) }
        val b = net.nodes["B"]!!.router
        b.onBytes("B>A", JSONObject().put("t", "env").put("e", react.json).toString().toByteArray())
        assertNull(b.message("zmsgzmsgzmsg"))
        b.onBytes("B>A", JSONObject().put("t", "env").put("e", chat.json).toString().toByteArray())
        assertEquals("🙏", b.message("zmsgzmsgzmsg")!!.reactions["Y"])
    }

    @Test fun `private chat reactions stay between its two people`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val dm = net.nodes["A"]!!.router.sendDm("C", "just us"); net.pump()
        val onC = net.nodes["C"]!!.router.message(dm.id)!!
        net.nodes["C"]!!.router.sendReaction(onC, "❤️"); net.pump()
        assertEquals("❤️", dm.reactions["C"])              // the sender sees it
        assertNull(net.nodes["B"]!!.router.message(dm.id)) // the middleman never had the message
        assertTrue(net.nodes["B"]!!.router.carrySize() > 0)
    }

    @Test fun `an absurdly long reaction is clipped and reactions survive restore`() {
        val net = FakeNet(); net.line("A", "B")
        val m = net.nodes["A"]!!.router.sendChat("hi"); net.pump()
        net.nodes["B"]!!.router.sendReaction(net.nodes["B"]!!.router.message(m.id)!!, "x".repeat(500)); net.pump()
        assertEquals(8, m.reactions["B"]!!.length)
        val fresh = FakeNet(); val a2 = fresh.node("A")
        a2.router.restore(JSONObject(net.nodes["A"]!!.router.snapshot().toString()))
        assertEquals(m.reactions["B"], a2.router.message(m.id)!!.reactions["B"])
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
        val key = net.nodes["A"]!!.router.group.key
        val react = Envelope(JSONObject().put("id", "rrrrrrrrrrrr").put("k", "reac").put("o", "Y").put("on", "Yan")
            .put("ts", net.now).put("h", 0).put("p", JSONObject().put("m", "mmmmmmmmmmmm").put("e", "👍"))).also { it.sign(key) }
        net.nodes["B"]!!.router.onBytes("B>A", JSONObject().put("t", "env").put("e", react.json).toString().toByteArray())
        // B restarts before the message itself ever arrives
        val fresh = FakeNet(); val b2 = fresh.node("B")
        b2.router.restore(JSONObject(net.nodes["B"]!!.router.snapshot().toString()))
        fresh.node("A"); fresh.connect("A", "B")
        val chat = Envelope(JSONObject().put("id", "mmmmmmmmmmmm").put("k", "chat").put("o", "Z").put("on", "Zoe")
            .put("ts", fresh.now).put("h", 0).put("p", JSONObject().put("text", "late"))).also { it.sign(key) }
        b2.router.onBytes("B>A", JSONObject().put("t", "env").put("e", chat.json).toString().toByteArray())
        assertEquals("👍", b2.router.message("mmmmmmmmmmmm")!!.reactions["Y"])
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

    @Test fun `reaction backlog is not re-sent to a v2 peer on link-up`() {
        val frames = ArrayList<JSONObject>()
        val silent = object : RouterListener {
            override fun onChanged() {}
            override fun onMessage(m: Message) {}
            override fun onErrandRequest(e: Errand) {}
        }
        var now = 1_700_000_000_000L
        val r = Router(Identity("aa", "Vet"), Group(FakeNet.CODE, "Trek"), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long {
                frames.add(JSONObject(String(bytes, Charsets.UTF_8))); return frames.size.toLong()
            }
            override fun disconnect(linkId: String) {}
        }, silent) { now }
        val m = r.sendChat("hello")
        r.sendReaction(m, "👍")
        frames.clear()
        r.onLinkUp("L", "zz", "OldV2Phone")
        val myNonce = r.links["L"]!!.myNonce
        r.onBytes("L", JSONObject().put("t", "hello").put("id", "zz").put("name", "OldV2Phone").put("nonce", "n1").put("v", 2).toString().toByteArray())
        r.onBytes("L", JSONObject().put("t", "proof").put("proof", Crypto.hmacHex(r.group.key, "$myNonce|zz")).toString().toByteArray())
        r.onBytes("L", JSONObject().put("t", "inv").put("n", 1).put("i", 0).put("ids", org.json.JSONArray()).toString().toByteArray())
        val all = frames.joinToString("\n") { it.toString() }
        assertTrue(all.contains("hello"))                 // the chat itself fills
        assertFalse(all.contains("\"reac\""))             // the reaction backlog does not
    }

    // ---------------------------------------------------------------- live location

    @Test fun `live location rides presence and clears when sharing stops`() {
        val net = FakeNet(); net.line("A", "B", "C")
        net.nodes["A"]!!.router.myLoc = Loc.of(12.9716, 77.5946, 10)
        net.tickAll()
        val seenByC = net.nodes["C"]!!.router.people["A"]!!
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

    @Test fun `a 1x peer is never flooded with the file backlog on link-up`() {
        // A v2 phone with a photo backlog links to an old client (its hello carries no "v").
        val frames = ArrayList<JSONObject>()
        val silent = object : RouterListener {
            override fun onChanged() {}
            override fun onMessage(m: Message) {}
            override fun onErrandRequest(e: Errand) {}
        }
        var now = 1_700_000_000_000L
        val r = Router(Identity("aa", "Vet"), Group(FakeNet.CODE, "Trek"), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long {
                frames.add(JSONObject(String(bytes, Charsets.UTF_8))); return frames.size.toLong()
            }
            override fun disconnect(linkId: String) {}
        }, silent) { now }
        val (att, pieces) = makeFile(ByteArray(40_000) { it.toByte() })
        r.sendFile(att, pieces, "old sunset")
        r.sendChat("plain text travels fine")
        frames.clear()

        r.onLinkUp("L", "zz", "OldPhone")
        assertEquals(Router.VERSION, frames.first { it.optString("t") == "hello" }.optInt("v"))
        val myNonce = r.links["L"]!!.myNonce
        r.onBytes("L", JSONObject().put("t", "hello").put("id", "zz").put("name", "OldPhone").put("nonce", "n1").toString().toByteArray())
        val proof = Crypto.hmacHex(r.group.key, "$myNonce|zz")
        r.onBytes("L", JSONObject().put("t", "proof").put("proof", proof).toString().toByteArray())
        r.onBytes("L", JSONObject().put("t", "inv").put("n", 1).put("i", 0).put("ids", org.json.JSONArray()).toString().toByteArray())

        // The old phone gets the text, but no chunk ids in inventory and no file envelopes at all.
        val all = frames.joinToString("\n") { it.toString() }
        assertTrue(all.contains("plain text travels fine"))
        assertFalse(all.contains("\"fchk\""))
        assertFalse(all.contains("\"f.${att.fid}"))
        assertFalse(all.contains("old sunset"))
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

class PayloadSizeTest {
    @Test fun `sync frames stay under the radio payload cap even with emoji`() {
        val net = FakeNet(); net.line("A", "B")
        val big = "🙂".repeat(1900)               // 1900 chars but 7600 bytes of UTF-8
        repeat(30) { net.nodes["A"]!!.router.sendChat(big) }
        net.pump()
        net.node("C"); net.connect("B", "C")       // B fills C's 30-message gap in chunks
        assertEquals(30, net.texts("C").size)
        assertTrue("largest frame was ${net.maxFrameBytes} bytes", net.maxFrameBytes < 32_000)
        assertTrue(net.maxFrameBytes > 8_000)      // and the chunks are not silly-small either
    }

    @Test fun `over-long text is trimmed so one envelope can never exceed the cap`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["A"]!!.router.sendChat("x".repeat(50_000)); net.pump()
        assertEquals(Router.MAX_TEXT, net.texts("B")[0].length)
    }
}
