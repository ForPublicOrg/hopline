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
        override fun onChanged() {}
        override fun onMessage(m: Message) { shown.add(m) }
        override fun onErrandRequest(e: Errand) { errands.add(e) }
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
