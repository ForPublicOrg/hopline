package app.hopline.mesh

import app.hopline.core.Crypto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** 2.2: names, group renames, link security, crafted input, clock skew, delete-for-me, reactions. */
class ProtocolTest {

    private fun env(net: FakeNet, from: String, kind: String, p: JSONObject, id: String = Crypto.randomId(12), ts: Long = net.now,
                    to: String? = null, name: String = from): Envelope {
        val j = JSONObject().put("id", id).put("k", kind).put("o", from).put("on", name).put("ts", ts).put("h", 0).put("p", p)
        if (to != null) j.put("to", to)
        return Envelope(j).also { it.sign(net.nodes.values.first().router.group.key) }
    }

    private fun inject(net: FakeNet, at: String, link: String, e: Envelope, fill: Boolean = false) {
        val frame = if (fill) JSONObject().put("t", "fill").put("envs", JSONArray().put(e.json))
                    else JSONObject().put("t", "env").put("e", e.json)
        net.nodes[at]!!.router.onBytes(link, frame.toString().toByteArray())
        net.pump()
    }

    // ---------------------------------------------------------------- names

    @Test fun `a rename sticks even when old messages hop in afterwards`() {
        val net = FakeNet(); net.line("A", "B", "C")
        net.nodes["A"]!!.router.sendChat("hi from the old name"); net.pump()
        val oldEnvelopeTs = net.now
        net.now += 60_000
        net.nodes["A"]!!.router.rename("Asha K"); net.pump()
        assertEquals("Asha K", net.nodes["C"]!!.router.people["A"]!!.name)
        // a phone that missed everything gets the backlog — signed with the OLD name — after the rename
        val late = env(net, "A", Envelope.CHAT, JSONObject().put("text", "an old one"), ts = oldEnvelopeTs - 1000, name = "A")
        inject(net, "C", "C>B", late, fill = true)
        assertEquals("Asha K", net.nodes["C"]!!.router.people["A"]!!.name)
        assertEquals("Asha K", net.nodes["B"]!!.router.people["A"]!!.name)
    }

    @Test fun `names from the air are cleaned`() {
        val net = FakeNet(); net.line("A", "B")
        val evil = env(net, "Z", Envelope.CHAT, JSONObject().put("text", "hi"), name = "‮ecila\n\n\nBob" + "x".repeat(500))
        inject(net, "B", "B>A", evil)
        val n = net.nodes["B"]!!.router.people["Z"]!!.name
        assertFalse(n.contains('‮')); assertFalse(n.contains('\n'))
        assertTrue(n.codePointCount(0, n.length) <= app.hopline.core.Names.MAX_PERSON)
        assertEquals(n, net.nodes["B"]!!.router.messages.single().fromName)
    }

    @Test fun `a group rename reaches phones that already had a name, and a late joiner`() {
        val net = FakeNet(); net.line("A", "B", "C")
        net.now += 1000
        val m = net.nodes["B"]!!.router.renameGroup("Kedarkantha 2026"); net.pump()
        assertNotNull(m); assertTrue(m!!.isNotice)
        for (id in listOf("A", "B", "C")) assertEquals("Kedarkantha 2026", net.nodes[id]!!.router.group.name)
        assertEquals("Kedarkantha 2026", net.nodes["A"]!!.rec.groupNames.last())
        // everyone sees who renamed it, as a notice — not a chat bubble that notifies
        val onA = net.nodes["A"]!!.router.messages.single { it.isNotice }
        assertEquals("B", onA.from); assertEquals("Kedarkantha 2026", onA.text)
        assertTrue(net.nodes["A"]!!.rec.shown.none { it.isNotice })
        // a phone that was away gets it through gap-fill, and its own stale name loses
        val d = net.node("D"); d.router.group.name = "Trek"
        net.connect("C", "D")
        assertEquals("Kedarkantha 2026", d.router.group.name)
        assertTrue(d.router.messages.any { it.isNotice })
    }

    @Test fun `a 2_1 phone beaconing a stale group name never undoes a rename`() {
        val net = FakeNet(); net.line("A", "B")
        net.now += 1000
        net.nodes["A"]!!.router.renameGroup("New name"); net.pump()
        // presence without "gt", the way 2.1 sends it
        val old = env(net, "Q", Envelope.PRESENCE, JSONObject().put("n", "Old phone").put("net", false).put("gn", "Trek"))
        inject(net, "B", "B>A", old)
        assertEquals("New name", net.nodes["B"]!!.router.group.name)
        // but it can still fill in a name for a phone that has none
        val fresh = FakeNet(); val x = fresh.node("X"); x.router.group.name = ""
        val p = Envelope(JSONObject().put("id", Crypto.randomId(12)).put("k", Envelope.PRESENCE).put("o", "Q").put("on", "Q")
            .put("ts", fresh.now).put("h", 0).put("p", JSONObject().put("n", "Q").put("gn", "Trek"))).also { it.sign(x.router.group.key) }
        fresh.node("Y"); fresh.connect("X", "Y")
        x.router.onBytes("X>Y", JSONObject().put("t", "env").put("e", p.json).toString().toByteArray())
        assertEquals("Trek", x.router.group.name)
    }

    @Test fun `a phone with a fast clock cannot lock the group name`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!.router
        // Q's clock is two hours ahead: its rename still lands, as of when B heard it…
        val fast = net.now + 2 * 3600_000L
        inject(net, "B", "B>A", env(net, "Q", Envelope.CHAT, JSONObject().put("text", "renamed").put("gn", "Fast name"), ts = fast))
        assertEquals("Fast name", b.group.name)
        // …and its beacons, stamped far ahead, can't keep pushing that name back over later renames.
        net.now += 60_000
        net.nodes["A"]!!.router.renameGroup("Honest name"); net.pump()
        assertEquals("Honest name", b.group.name)
        repeat(3) {
            net.now += 30_000
            inject(net, "B", "B>A", env(net, "Q", Envelope.PRESENCE, JSONObject().put("n", "Q").put("gn", "Fast name").put("gt", fast)))
        }
        assertEquals("Honest name", b.group.name)
        assertTrue(b.group.nameAt <= net.now)
    }

    @Test fun `renames made after a fast-clock phone's rename win everywhere, even once real time passes its stamp`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("F")
        net.nodes["F"]!!.skew = 3 * 3600_000L                         // F's clock is three hours fast
        net.connect("A", "B"); net.connect("B", "F")                    // presence: everyone learns F's lead
        net.nodes["F"]!!.router.renameGroup("X"); net.pump()
        assertEquals("X", net.nodes["A"]!!.router.group.name)
        net.now += 60_000
        net.nodes["A"]!!.router.renameGroup("Trek"); net.pump()
        // past the moment F's own clock stamped its rename, beacons keep flowing both ways
        for (step in 0 until 14) { net.now += 15 * 60_000L; for (n in net.nodes.values) n.router.tick(); net.pump() }
        for (id in listOf("A", "B", "F")) assertEquals(id, "Trek", net.nodes[id]!!.router.group.name)
        // a late joiner gap-filling F's old rename still ends up on the latest name
        net.node("L"); net.connect("L", "B"); net.connect("L", "F")
        for (step in 0 until 3) { net.now += 30_000; for (n in net.nodes.values) n.router.tick(); net.pump() }
        assertEquals("Trek", net.nodes["L"]!!.router.group.name)
    }

    @Test fun `two renames made apart settle on one name everywhere, and the count survives a restart`() {
        val net = FakeNet(); net.node("A"); net.node("B")
        net.nodes["A"]!!.router.renameGroup("Ridge camp")
        net.now += 1_000
        net.nodes["B"]!!.router.renameGroup("Lake camp")             // made without seeing A's
        net.connect("A", "B")
        for (step in 0 until 3) { net.now += 30_000; for (n in net.nodes.values) n.router.tick(); net.pump() }
        val settled = net.nodes["A"]!!.router.group.name
        assertEquals(settled, net.nodes["B"]!!.router.group.name)
        // A restarts, then renames: one more than the newest it saw, so it wins outright
        val snap = JSONObject(net.nodes["A"]!!.router.snapshot().toString())
        val a2 = Router(Identity("A", "A"), Group(FakeNet.CODE, settled, net.nodes["A"]!!.router.group.nameAt), net.nodes["A"]!!.transport, net.nodes["A"]!!.rec) { net.now }
        a2.restore(snap)
        assertEquals(net.nodes["A"]!!.router.group.nameV, a2.group.nameV)
        assertTrue(a2.renameGroup("Base camp")!!.text == "Base camp")
        assertTrue(a2.group.nameV > net.nodes["B"]!!.router.group.nameV)
    }

    @Test fun `beacons about a name everyone already has change nothing`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("B", "C")
        net.nodes["A"]!!.router.renameGroup("Base"); net.pump()
        val c = net.nodes["C"]!!.router
        val at = c.group.nameAt; val named = net.nodes["C"]!!.rec.groupNames.size
        // relayed ages always arrive a little late: none of that may creep the time forward
        for (step in 0 until 10) { net.now += 30_000 + 7; for (n in net.nodes.values) n.router.tick(); net.pump() }
        assertEquals(at, c.group.nameAt)
        assertEquals(named, net.nodes["C"]!!.rec.groupNames.size)
    }

    @Test fun `the hello frame carries no name before the link proves the code`() {
        val net = FakeNet(); net.node("A", "Asha"); net.node("B", "Ravi")
        val frames = ArrayList<String>()
        val spy = object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long { frames.add(String(bytes, Charsets.UTF_8)); return 1 }
            override fun disconnect(linkId: String) {}
        }
        val r = Router(Identity("A", "Asha"), Group(FakeNet.CODE, "Trek"), spy, net.nodes["A"]!!.rec) { net.now }
        r.onLinkUp("A>X", "X", "", "tok")
        val hello = frames.map { JSONObject(it) }.first { it.optString("t") == "hello" }
        assertFalse(hello.has("name"))
        assertFalse(frames.any { it.contains("Asha") })
    }

    @Test fun `quotes remember who wrote the original`() {
        val net = FakeNet(); net.line("A", "B")
        val original = net.nodes["A"]!!.router.sendChat("meet at 5"); net.pump()
        net.nodes["B"]!!.router.sendChat("ok", Quote.of(net.nodes["B"]!!.router.message(original.id)!!)); net.pump()
        val reply = net.nodes["A"]!!.router.messages.first { it.text == "ok" }
        assertEquals("A", reply.quote!!.origin)
    }

    // ---------------------------------------------------------------- link security

    @Test fun `a stranger relaying proofs between two members cannot join`() {
        // V and M are members; Eve (no code) dials V claiming to be M, and dials M as herself,
        // passing V's challenge to M and M's answer back to V. Each Nearby connection has its
        // own auth token, so M's proof (bound to the Eve–M connection) is worthless to V.
        val frames = ArrayList<Pair<String, JSONObject>>()
        val net = FakeNet()
        val v = net.node("V"); val m = net.node("M")
        val toV = object : Transport { override fun send(linkId: String, bytes: ByteArray): Long { frames.add("toEve" to JSONObject(String(bytes))); return 1 }; override fun disconnect(linkId: String) {} }
        // V's view of the fake M link
        val vr = Router(Identity("V", "V"), Group(FakeNet.CODE, "Trek"), toV, v.rec) { net.now }
        vr.onLinkUp("eve1", "M", "", "token-eve-v")
        val vHello = frames.first { it.second.optString("t") == "hello" }.second
        // Eve opens a separate connection to the real M (different token) and replays V's nonce
        val mFrames = ArrayList<JSONObject>()
        val mr = Router(Identity("M", "M"), Group(FakeNet.CODE, "Trek"), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long { mFrames.add(JSONObject(String(bytes))); return 1 }
            override fun disconnect(linkId: String) {}
        }, m.rec) { net.now }
        mr.onLinkUp("eve2", "E", "", "token-eve-m")
        mr.onBytes("eve2", JSONObject().put("t", "hello").put("id", "E").put("nonce", vHello.getString("nonce")).put("v", 4).toString().toByteArray())
        val mProof = mFrames.firstOrNull { it.optString("t") == "proof" }?.getString("proof")
        assertNotNull("M answers a v4 hello with a bound proof", mProof)
        // Eve hands that proof to V as "M"
        vr.onBytes("eve1", JSONObject().put("t", "hello").put("id", "M").put("nonce", "eveeveeveeveeve1").put("v", 4).toString().toByteArray())
        vr.onBytes("eve1", JSONObject().put("t", "proof").put("proof", mProof).toString().toByteArray())
        assertTrue("V must not accept a relayed proof", vr.links["eve1"]?.authed != true)
        assertTrue(vr.authedLinks().isEmpty())
    }

    @Test fun `a phone that spoke v4 can never fall back to the old proof`() {
        val net = FakeNet(); net.line("A", "B")
        assertEquals(4, net.nodes["A"]!!.router.people["B"]!!.ver)
        net.disconnect("A", "B")
        // someone claiming to be B with a v3 hello (the relayable proof) is refused
        val a = net.nodes["A"]!!.router
        a.onLinkUp("X", "B", "", "")
        a.onBytes("X", JSONObject().put("t", "hello").put("id", "B").put("nonce", "abcdefghijklmnop").put("v", 3).toString().toByteArray())
        assertNull(a.links["X"])
    }

    @Test fun `a legacy 2_x peer still links and gets the backlog`() {
        val frames = ArrayList<JSONObject>()
        var now = 1_700_000_000_000L
        val net = FakeNet()
        val r = Router(Identity("aa", "New"), Group(FakeNet.CODE, "Trek"), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long { frames.add(JSONObject(String(bytes))); return frames.size.toLong() }
            override fun disconnect(linkId: String) {}
        }, net.Recorder()) { now }
        r.sendChat("for the old phone")
        frames.clear()
        r.onLinkUp("L", "zz", "", "sometoken")
        val myNonce = r.links["L"]!!.myNonce
        r.onBytes("L", JSONObject().put("t", "hello").put("id", "zz").put("nonce", "oldphoneoldphone").put("v", 3).toString().toByteArray())
        // we don't prove ourselves to a legacy phone until it has proven itself
        assertTrue(frames.none { it.optString("t") == "proof" })
        r.onBytes("L", JSONObject().put("t", "proof").put("proof", Crypto.hmacHex(r.group.key, "$myNonce|zz")).toString().toByteArray())
        assertTrue(r.links["L"]!!.authed)
        assertEquals(Crypto.hmacHex(r.group.key, "oldphoneoldphone|aa"), frames.first { it.optString("t") == "proof" }.getString("proof"))
        r.onBytes("L", JSONObject().put("t", "inv").put("n", 1).put("i", 0).put("ids", JSONArray()).toString().toByteArray())
        assertTrue(frames.joinToString { it.toString() }.contains("for the old phone"))
    }

    @Test fun `a proof that arrives before its hello still completes the handshake`() {
        val net = FakeNet()
        val a = net.node("A"); val b = net.node("B")
        val frames = ArrayList<JSONObject>()
        val r = Router(Identity("A", "A"), Group(FakeNet.CODE, "Trek"), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long { frames.add(JSONObject(String(bytes))); return 1 }
            override fun disconnect(linkId: String) {}
        }, a.rec) { net.now }
        r.onLinkUp("L", "B", "", "tok")
        val myNonce = r.links["L"]!!.myNonce
        val proof = Crypto.hmacHex(r.group.key, "p4|tok|$myNonce|B|A")
        r.onBytes("L", JSONObject().put("t", "proof").put("proof", proof).toString().toByteArray())
        assertFalse(r.links["L"]!!.authed)
        r.onBytes("L", JSONObject().put("t", "hello").put("id", "B").put("nonce", "bbbbbbbbbbbbbbbb").put("v", 4).toString().toByteArray())
        assertTrue(r.links["L"]!!.authed)
        b.router.toString()
    }

    // ---------------------------------------------------------------- crafted input

    @Test fun `a malformed fill frame cannot crash a phone`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!.router
        b.onBytes("B>A", """{"t":"fill","envs":[1,null,"x",{"id":"no"}]}""".toByteArray())
        b.onBytes("B>A", "not json at all".toByteArray())
        b.onBytes("B>A", """{"t":"inv","n":1000000000,"i":5,"ids":[1,2,3]}""".toByteArray())
        net.nodes["A"]!!.router.sendChat("still alive"); net.pump()
        assertEquals(listOf("still alive"), net.texts("B"))
    }

    @Test fun `chunks whose ids would escape the file store are refused`() {
        val net = FakeNet(); net.line("A", "B")
        val bad = env(net, "A", Envelope.CHUNK, JSONObject().put("fid", "abcdefgh").put("i", 0).put("d", "AAAA"), id = "../../mesh-state-x")
        inject(net, "B", "B>A", bad)
        val lying = env(net, "A", Envelope.CHUNK, JSONObject().put("fid", "abcdefgh").put("i", 0).put("d", "AAAA"), id = "f.zzzzzzzz.3")
        inject(net, "B", "B>A", lying)
        assertTrue(net.nodes["B"]!!.router.chunks.ids().isEmpty())
    }

    @Test fun `a file without an id is dropped before it can break anything`() {
        val net = FakeNet(); net.line("A", "B")
        val noFid = env(net, "A", Envelope.FILE, JSONObject().put("text", "boom").put("att", JSONObject().put("n", 1).put("size", 10)))
        inject(net, "B", "B>A", noFid)
        val huge = env(net, "A", Envelope.FILE, JSONObject().put("text", "big thumb")
            .put("att", Attachment.make("abcdefghij", "p.jpg", "image/jpeg", 10, 1, 1, 1, "x".repeat(50_000)).json))
        inject(net, "B", "B>A", huge)
        val b = net.nodes["B"]!!.router
        assertEquals(listOf("big thumb"), net.texts("B"))
        assertEquals("", b.messages.single().att!!.thumb)       // the bomb was defused, the message kept
        // and the carried copy is untouched, so its signature still holds for the next phone
        net.node("C"); net.connect("B", "C")
        assertEquals(listOf("big thumb"), net.texts("C"))
    }

    @Test fun `nobody can claim someone else's phone has a message`() {
        val net = FakeNet(); net.line("A", "B")
        val m = net.nodes["A"]!!.router.sendDm("Z", "to someone away"); net.pump()
        val forged = env(net, "B", Envelope.RECEIPT, JSONObject().put("m", m.id).put("by", "Z"), id = "r.${m.id}.Z", to = "A")
        inject(net, "A", "A>B", forged)
        assertNotEquals(Message.DELIVERED, m.status)
        assertFalse("Z" in m.reached)
    }

    @Test fun `backlog from a 2_x phone at the hop ceiling still gets through`() {
        val net = FakeNet(); net.line("A", "B")
        val old = env(net, "Q", Envelope.CHAT, JSONObject().put("text", "carried a long way"))
        old.hops = Envelope.MAX_HOPS
        inject(net, "B", "B>A", old, fill = true)
        assertEquals(listOf("carried a long way"), net.texts("B"))
    }

    // ---------------------------------------------------------------- clocks

    @Test fun `a linked phone with a slow clock stays in range`() {
        val net = FakeNet(); net.line("A", "B")
        val slow = env(net, "B", Envelope.PRESENCE, JSONObject().put("n", "B"), ts = net.now - 3 * 3600_000L)
        net.now += 110_000
        inject(net, "A", "A>B", slow)
        net.now += 60_000
        val p = net.nodes["A"]!!.router.people["B"]!!
        assertTrue(net.nodes["A"]!!.router.isInRange(p))
    }

    @Test fun `a message stamped far in the future does not pin itself to the bottom`() {
        val net = FakeNet(); net.line("A", "B")
        val future = env(net, "Q", Envelope.CHAT, JSONObject().put("text", "from 2100"), ts = net.now + 1000L * 3600_000L)
        inject(net, "B", "B>A", future)
        net.now += 10 * 60_000
        net.nodes["A"]!!.router.sendChat("later, but real"); net.pump()
        assertEquals("later, but real", net.texts("B").last())
    }

    @Test fun `a phone whose clock reset to the past still gets its messages carried`() {
        val net = FakeNet(); net.line("A", "B")
        val ancient = env(net, "Q", Envelope.CHAT, JSONObject().put("text", "my clock says 1970"), ts = 1000L)
        inject(net, "B", "B>A", ancient)
        net.nodes["B"]!!.router.tick()
        val carried = net.nodes["B"]!!.router.snapshot().getJSONArray("carry")
        assertTrue((0 until carried.length()).any { carried.getJSONObject(it).getString("id") == ancient.id })
        net.node("C"); net.connect("B", "C")
        assertEquals(listOf("my clock says 1970"), net.texts("C"))
    }

    // ---------------------------------------------------------------- delete for me, carry

    @Test fun `delete for me sticks through gap-fill but the phone still carries it for others`() {
        val net = FakeNet(); net.line("A", "B")
        val m = net.nodes["A"]!!.router.sendChat("oops"); net.pump()
        val b = net.nodes["B"]!!.router
        assertEquals(1, b.hideMessages(listOf(m.id)).size)
        assertTrue(net.texts("B").isEmpty())
        net.disconnect("A", "B"); net.connect("A", "B")               // A re-offers everything
        assertTrue(net.texts("B").isEmpty())
        net.node("C"); net.connect("B", "C")                          // ...and B still relays it
        assertEquals(listOf("oops"), net.texts("C"))
        val fresh = FakeNet(); val b2 = fresh.node("B"); b2.router.restore(JSONObject(b.snapshot().toString()))
        assertTrue(b2.router.isHidden(m.id))
        assertTrue(fresh.texts("B").isEmpty())
    }

    @Test fun `an unsent message deleted before it left is never sent`() {
        val net = FakeNet(); val a = net.node("A")
        val m = a.router.sendChat("never mind")
        a.router.hideMessages(listOf(m.id))
        net.node("B"); net.connect("A", "B")
        assertTrue(net.texts("B").isEmpty())
    }

    @Test fun `over the carry cap receipts go before anyone's words`() {
        val net = FakeNet(); net.line("A", "Q")
        val r = net.nodes["A"]!!.router
        val n = Router.MAX_CARRY - 10
        repeat(n) { r.sendChat("m$it") }          // Q confirms every one: as many receipts again
        net.pump()
        assertTrue(r.carrySize() > Router.MAX_CARRY)
        r.tick()
        assertTrue(r.carrySize() <= Router.MAX_CARRY)
        val carried = r.snapshot().getJSONArray("carry")
        assertEquals(n, (0 until carried.length()).count { carried.getJSONObject(it).getString("k") == Envelope.CHAT })
    }

    @Test fun `one bad record in a saved state does not lose the rest`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["B"]!!.router.sendChat("keep me"); net.pump()
        val snap = JSONObject(net.nodes["A"]!!.router.snapshot().toString())
        snap.getJSONArray("messages").put(JSONObject().put("garbage", true))
        snap.getJSONArray("people").put("not an object")
        val fresh = FakeNet(); val a2 = fresh.node("A"); a2.router.restore(snap)
        assertEquals(listOf("keep me"), fresh.texts("A"))
        assertNotNull(a2.router.people["B"])
    }

    // ---------------------------------------------------------------- reactions

    @Test fun `someone reacting to my message tells me, a restore does not`() {
        val net = FakeNet(); net.line("A", "B")
        val m = net.nodes["A"]!!.router.sendChat("summit!"); net.pump()
        net.nodes["B"]!!.router.sendReaction(net.nodes["B"]!!.router.message(m.id)!!, "🔥"); net.pump()
        assertEquals(listOf(Triple(m.id, "B", "🔥")), net.nodes["A"]!!.rec.reactions)
        val fresh = FakeNet(); val a2 = fresh.node("A"); a2.router.restore(JSONObject(net.nodes["A"]!!.router.snapshot().toString()))
        assertTrue(a2.rec.reactions.isEmpty())
        assertEquals("🔥", a2.router.message(m.id)!!.reactions["B"])
    }

    @Test fun `my next reaction wins even if my clock went backwards`() {
        val net = FakeNet(); net.line("A", "B")
        val m = net.nodes["A"]!!.router.sendChat("x"); net.pump()
        val onB = net.nodes["B"]!!.router.message(m.id)!!
        net.nodes["B"]!!.router.sendReaction(onB, "👍"); net.pump()
        net.now -= 3600_000L                                          // clock corrected backwards
        net.nodes["B"]!!.router.sendReaction(onB, "❤️"); net.pump()
        assertEquals("❤️", m.reactions["B"])
    }

    @Test fun `the pill shows the top three and the total`() {
        val m = Message("x", Envelope.CHAT, "A", "A", null, "hi", 1L)
        m.applyReaction("a", "👍", 1); m.applyReaction("b", "👍", 1); m.applyReaction("c", "❤️", 1)
        m.applyReaction("d", "😂", 1); m.applyReaction("e", "😮", 1)
        assertEquals("👍❤️😂 5", m.reactionPill())
        val one = Message("y", Envelope.CHAT, "A", "A", null, "hi", 1L).also { it.applyReaction("a", "🙏", 1) }
        assertEquals("🙏", one.reactionPill())
    }
}
