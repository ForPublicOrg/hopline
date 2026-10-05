package app.hopline.mesh

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Store-and-forward kept honest: backlog is not sent to a phone that already has it, what a phone
 * let go of is not handed back to it at every sync, and an envelope's 48 h don't start over at
 * every phone that carries it late.
 */
class SyncTest {
    private val hour = 3_600_000L

    /** A phone whose every frame is kept, linked by hand to phones the test plays (each link proven). */
    private class Probe(val net: FakeNet, label: String) {
        val sent = ArrayList<Pair<String, JSONObject>>()
        val rec = net.Recorder()
        val router = Router(FakeNet.identity(label), FakeNet.group(), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long { sent.add(linkId to JSONObject(String(bytes, Charsets.UTF_8))); return sent.size.toLong() }
            override fun disconnect(linkId: String) {}
        }, rec) { net.now }

        fun link(peer: String) { router.onLinkUp(peer, FakeNet.idOf(peer), peer, "tok-$peer"); FakeNet.prove(router, peer, peer, "tok-$peer") }

        /** [peer]'s whole inventory, in one part. */
        fun inventory(peer: String, vararg ids: String) =
            router.onBytes(peer, JSONObject().put("t", "inv").put("n", 1).put("i", 0).put("ids", JSONArray(ids.toList())).toString().toByteArray())

        /** Ids of the envelopes this phone sent [peer], live or in a gap-fill. */
        fun handed(peer: String): List<String> = sent.filter { it.first == peer }.flatMap { (_, f) ->
            when (f.optString("t")) {
                "env" -> listOf(f.getJSONObject("e").getString("id"))
                "fill" -> f.getJSONArray("envs").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
                else -> emptyList()
            }
        }

        /** Every id this phone listed to [peer] in its inventories. */
        fun listed(peer: String): Set<String> = sent.filter { it.first == peer && it.second.optString("t") == "inv" }
            .flatMap { (_, f) -> f.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } } }.toSet()
    }

    /** Ids inside the gap-fill and live frames among [frames] (inventories only name ids; these carry them). */
    private fun carriedIn(frames: List<String>): Set<String> = frames.map { JSONObject(it) }.flatMap { f ->
        when (f.optString("t")) {
            "env" -> listOf(f.getJSONObject("e").getString("id"))
            "fill" -> f.getJSONArray("envs").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }
            else -> emptyList()
        }
    }.toSet()

    // ---------------------------------------------------------------- backlog both friends hold

    @Test fun `backlog is not passed on to a phone whose inventory has it, and still is at once to one whose does not`() {
        val net = FakeNet()
        val x = Probe(net, "X")
        x.link("A"); x.link("B"); x.link("C")
        val old = FakeNet.envelope("O", Envelope.CHAT, JSONObject().put("text", "from this morning"), net.now - 5 * hour)
        x.inventory("B", old.id)                     // B has it already; C hasn't said
        x.sent.clear()
        x.router.onBytes("A", FakeNet.fill(old))     // A fills the gap X had
        assertTrue(x.router.carries(old.id))
        assertEquals(listOf("from this morning"), x.router.messages.map { it.text })
        assertFalse("B listed it: not sent there again", old.id in x.handed("B"))
        assertEquals("C didn't: it goes there at once", listOf(old.id), x.handed("C").filter { it == old.id })
        assertFalse(old.id in x.handed("A"))
        // only what B listed is held back from it: anything new reaches it at once
        val fresh = FakeNet.envelope("O", Envelope.CHAT, JSONObject().put("text", "just now"), net.now)
        x.router.onBytes("A", FakeNet.frame(fresh))
        assertTrue(fresh.id in x.handed("B")); assertTrue(fresh.id in x.handed("C"))
    }

    @Test fun `a message of mine a friend already lists has left my phone`() {
        val net = FakeNet()
        val x = Probe(net, "X")
        val m = x.router.sendChat("did this get out?")
        x.link("A")                                  // no word back about the frames on the way
        assertEquals(Message.QUEUED, m.status)
        x.inventory("A", m.id)
        assertEquals(Message.SENT, m.status)
        // and it is not sent to them again
        x.sent.clear()
        net.now += Router.SYNC_MS; x.router.tick()
        assertFalse(m.id in x.handed("A"))
    }

    // ---------------------------------------------------------------- what was let go of

    @Test fun `what a phone let go of is listed, so a friend still carrying it stops handing it over`() {
        val net = FakeNet(); net.frames = ArrayList()
        net.line("A", "F"); net.line("B", "G")         // F and G only feed A and B what the test hands them
        val a = net.nodes["A"]!!; val b = net.nodes["B"]!!
        val t0 = net.now
        val env = FakeNet.envelope("C", Envelope.REACT, JSONObject().put("m", FakeNet.newId("C")).put("e", "👍"), t0)
        a.router.onBytes("A>F", FakeNet.fill(env)); net.pump()     // A carries it from its stamp
        net.disconnect("A", "F")
        net.now += 30 * hour
        b.router.onBytes("B>G", FakeNet.fill(env)); net.pump()     // B gets it a day late from a phone that says nothing of its age
        net.disconnect("B", "G")
        assertTrue(a.router.carries(env.id)); assertTrue(b.router.carries(env.id))
        net.now = t0 + Router.CARRY_MS + 60_000
        net.tickAll()
        assertFalse("A's 48 h are up", a.router.carries(env.id))
        assertTrue("B's day-late copy is not", b.router.carries(env.id))
        // they meet, and sync again and again: B never hands it over
        net.frames!!.clear()
        net.connect("A", "B")
        repeat(3) { net.now += Router.SYNC_MS + 1_000; net.tickAll() }
        assertFalse(env.id in carriedIn(net.frames!!))
        // the list survives a restart, and what is on it is still not taken in
        val saved = JSONObject(a.router.snapshot().toString())
        assertTrue(saved.getJSONObject("tombs").has(env.id))
        val again = Probe(net, "A"); again.router.restore(saved)
        assertFalse(again.router.carries(env.id))
        again.link("B")
        assertTrue(env.id in again.listed("B"))
        again.router.onBytes("B", FakeNet.fill(env))
        assertFalse(again.router.carries(env.id))
        // once no friend can still be carrying it, it is listed no more
        net.now = t0 + 30 * hour + Router.CARRY_MS + Router.CARRY_MS / 2 + 60_000
        again.router.tick()
        assertFalse(again.router.snapshot().has("tombs"))
    }

    @Test fun `the list of what was let go of is bounded, oldest out`() {
        val net = FakeNet()
        val many = JSONObject()
        val ids = (0 until Router.MAX_TOMBS + 500).map { FakeNet.newId("C") }
        for ((k, id) in ids.withIndex()) many.put(id, net.now + hour + k)
        val a = net.node("A")
        a.router.restore(JSONObject().put("fmt", Router.FMT).put("me", a.id).put("tombs", many))
        val kept = a.router.snapshot().getJSONObject("tombs").keySet()
        assertEquals(Router.MAX_TOMBS, kept.size)
        assertEquals(ids.takeLast(Router.MAX_TOMBS).toSet(), kept)
    }

    // ---------------------------------------------------------------- 48 h that don't start over

    @Test fun `an envelope's 48 hours go on from where the phone before had them, and don't start over`() {
        val net = FakeNet(); net.line("A", "C")
        val t0 = net.now
        val m = net.nodes["C"]!!.router.sendChat("left at the pass"); net.pump()
        net.disconnect("A", "C")
        net.now = t0 + 47 * hour
        net.node("W", "Late walker"); net.connect("A", "W")
        val w = net.nodes["W"]!!.router
        assertTrue(w.carries(m.id))
        assertEquals(listOf("left at the pass"), net.texts("W"))   // inside its time: delivered
        net.disconnect("A", "W")
        net.now = t0 + Router.CARRY_MS + 60_000
        w.tick()
        assertFalse("W carries it no longer than A did", w.carries(m.id))
    }

    @Test fun `backlog handed over after its time ran out is known, but not carried, shown or passed on`() {
        val net = FakeNet()
        val x = Probe(net, "X")
        x.link("A"); x.link("B")
        // written more than two days ago (an age can't take an envelope back past its own stamp)
        val env = FakeNet.envelope("O", Envelope.CHAT, JSONObject().put("text", "two days old"), net.now - 49 * hour)
        x.sent.clear()
        x.router.onBytes("A", FakeNet.fill(Envelope(JSONObject(env.json.toString()).put("age", Router.CARRY_MS + 1))))
        assertFalse(x.router.carries(env.id))
        assertTrue(x.router.messages.isEmpty()); assertTrue(x.rec.shown.isEmpty())
        assertFalse(env.id in x.handed("B"))
        // a copy that says nothing of its age is not taken in either: this phone knows it now
        x.router.onBytes("A", FakeNet.fill(env))
        assertFalse(x.router.carries(env.id)); assertTrue(x.router.messages.isEmpty())
        // and lists it, so A stops offering it
        net.now += Router.SYNC_MS; x.router.tick()
        assertTrue(env.id in x.listed("A"))
        // an age that isn't a whole number of milliseconds is no envelope at all
        val odd = FakeNet.envelope("O", Envelope.CHAT, JSONObject().put("text", "odd"), net.now)
        x.router.onBytes("A", FakeNet.fill(Envelope(JSONObject(odd.json.toString()).put("age", -5))))
        x.router.onBytes("A", FakeNet.fill(Envelope(JSONObject(odd.json.toString()).put("age", "5"))))
        assertFalse(x.router.carries(odd.id))
        x.router.onBytes("A", FakeNet.fill(odd))
        assertTrue(x.router.carries(odd.id))
    }

    @Test fun `a made-up age can't have a fresh message turned away`() {
        val net = FakeNet()
        val x = Probe(net, "X")
        x.link("A"); x.link("B")
        val env = FakeNet.envelope("O", Envelope.CHAT, JSONObject().put("text", "just written"), net.now - 60_000)
        x.sent.clear()
        // A member hands it over as if it had been carried for more than two days: the age isn't signed
        x.router.onBytes("A", FakeNet.fill(Envelope(JSONObject(env.json.toString()).put("age", Router.CARRY_MS + 1))))
        assertTrue(x.router.carries(env.id))
        assertEquals(listOf("just written"), x.router.messages.map { it.text })
        assertEquals(1, x.rec.shown.size)
        assertTrue("passed on like any message", env.id in x.handed("B"))
        // ...and carried for the 48 h its own stamp gives it, not a minute more than a few of clock slack
        net.now = env.ts + Router.CARRY_MS - 10 * 60_000; x.router.tick()
        assertTrue(x.router.carries(env.id))
        net.now = env.ts + Router.CARRY_MS + Router.FUTURE_SLACK_MS; x.router.tick()
        assertFalse(x.router.carries(env.id))
    }

    @Test fun `a stamp from the future doesn't give an envelope a new 48 hours at every carrier`() {
        val net = FakeNet()
        val x = Probe(net, "X")
        x.link("A"); x.link("B")
        // Written on a phone whose date is a month ahead, and carried 47 hours by the phone handing it over
        val month = 30 * 24 * hour
        val env = FakeNet.envelope("O", Envelope.CHAT, JSONObject().put("text", "from next month"), net.now + month)
        x.sent.clear()
        x.router.onBytes("A", FakeNet.fill(Envelope(JSONObject(env.json.toString()).put("age", 47 * hour))))
        assertTrue(x.router.carries(env.id))
        assertTrue(env.id in x.handed("B"))
        // passed on with those 47 hours, so B's 48 h don't start over either
        val toB = x.sent.filter { it.first == "B" }.map { it.second }.first { it.optString("t") == "env" && it.getJSONObject("e").optString("id") == env.id }.getJSONObject("e")
        assertTrue(toB.toString(), toB.optLong("age") >= 47 * hour)
        // the hour it has left here, and not a minute more
        net.now += hour - 60_000; x.router.tick()
        assertTrue(x.router.carries(env.id))
        net.now += 2 * 60_000; x.router.tick()
        assertFalse(x.router.carries(env.id))
        // A made-up age still can't have one turned away: it is read, passed on, and kept a few minutes
        val y = Probe(net, "Y")
        y.link("A"); y.link("B")
        val other = FakeNet.envelope("O", Envelope.CHAT, JSONObject().put("text", "also from next month"), net.now + month)
        y.router.onBytes("A", FakeNet.fill(Envelope(JSONObject(other.json.toString()).put("age", 400 * 24 * hour))))
        assertTrue(y.router.carries(other.id))
        assertTrue(y.router.messages.any { it.id == other.id })
        assertTrue(other.id in y.handed("B"))
        // a stamp just a little ahead is still read as the stamp it is: a made-up age can't cut a fresh message short
        val fresh = FakeNet.envelope("O", Envelope.CHAT, JSONObject().put("text", "just written"), net.now + 60_000)
        y.router.onBytes("A", FakeNet.fill(Envelope(JSONObject(fresh.json.toString()).put("age", 47 * hour))))
        assertTrue(y.router.carries(fresh.id))
        net.now += Router.FUTURE_SLACK_MS + 1; y.router.tick()
        assertFalse(y.router.carries(other.id))
        net.now += Router.CARRY_MS - 20 * 60_000; y.router.tick()
        assertTrue(y.router.carries(fresh.id))
    }

    @Test fun `a receipt from a phone whose clock is days behind still gets back through a relay`() {
        val net = FakeNet()
        net.node("S"); net.node("A"); net.node("P").skew = -2 * 24 * hour
        net.connect("S", "A"); net.connect("A", "P")
        net.know("S", "P")
        val m = net.nodes["S"]!!.router.sendDm(net.id("P"), "are you at the hut?")!!; net.pump()
        assertEquals(listOf("are you at the hut?"), net.texts("P"))
        // P's receipt is stamped two days back: A carries it for half a receipt's day and passes it on
        // with that much age, which leaves S time to take it
        assertEquals(Message.DELIVERED, m.status)
        val rid = "r.${m.id}.${net.id("P")}"
        assertTrue(net.nodes["A"]!!.router.carries(rid)); assertTrue(net.nodes["S"]!!.router.carries(rid))
    }

    @Test fun `a deleted message is remembered for two weeks, so a late carrier can't bring it back`() {
        val net = FakeNet(); net.line("A", "B")
        val m = net.nodes["B"]!!.router.sendChat("delete me"); net.pump()
        val a = net.nodes["A"]!!.router
        a.hideMessages(listOf(m.id))
        net.now += 4 * 24 * hour; a.tick()
        assertTrue(a.isHidden(m.id))
        net.now += 10 * 24 * hour + 60_000; a.tick()
        assertFalse(a.isHidden(m.id))
    }
}
