package app.hopline.mesh

import app.hopline.core.Crypto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Shared internet: requests that travel with the group, claims, hand-offs, private answers, old phones. */
class ErrandTest {

    private fun url(u: String = "https://example.org/page") = JSONObject().put("url", u)

    @Test fun `the first pick takes it and a second helper stands down`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_READ); net.nodes["C"]!!.router.setCaps(Errand.CAP_READ); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, url()); net.pump()
        assertEquals(Errand.ASKED, e.status)
        val first = e.pick.first()
        net.advance(40_000)
        assertEquals(1, net.nodes[first]!!.rec.errands.size)
        val other = if (first == "B") "C" else "B"
        assertTrue("the other helper never started", net.nodes[other]!!.rec.errands.isEmpty())
        assertEquals(Errand.CLAIMED, e.status); assertEquals(first, e.helper)
    }

    @Test fun `when the helper goes quiet another one takes over and only one answer counts`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        for (h in listOf("B", "C")) net.nodes[h]!!.router.setCaps(Errand.CAP_READ)
        net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, url()); net.pump()
        net.advance(3_000)
        val first = e.helper!!
        val second = if (first == "B") "C" else "B"
        net.disconnect("A", first)                              // walks off mid-fetch, never answers
        net.advance(Router.WORK_LEASE_MS + 45_000)
        assertEquals(1, net.nodes[second]!!.rec.errands.size)
        assertEquals(second, e.helper)
        assertTrue(first in e.tried)
        net.nodes[second]!!.router.completeErrand(e.id, true, "Page", JSONObject().put("t", "from the second")); net.pump()
        assertEquals(Errand.DONE, e.status)
        // the first helper finishes late and comes back: its answer is not shown again
        net.connect("A", first)
        net.nodes[first]!!.router.completeErrand(e.id, true, "Page", JSONObject().put("t", "from the first")); net.pump()
        assertEquals("from the second", e.answer()!!.getString("t"))
        assertEquals(1, net.nodes["A"]!!.rec.answers.size)
    }

    @Test fun `two helpers that both started settle on one`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        for (h in listOf("B", "C")) net.nodes[h]!!.router.setCaps(Errand.CAP_READ)
        net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, url()); net.pump()
        // a partition: neither hears the other's claim
        net.disconnect("A", "B"); net.disconnect("A", "C")
        net.advance(60_000)
        assertEquals(1, net.nodes["B"]!!.rec.errands.size); assertEquals(1, net.nodes["C"]!!.rec.errands.size)
        net.connect("B", "C")
        net.advance(31_000)                                     // heartbeats cross: the larger id lets go
        assertEquals(1, net.nodes["C"]!!.rec.aborts.size)
        assertTrue(net.nodes["B"]!!.rec.aborts.isEmpty())
        net.connect("A", "B")
        net.nodes["B"]!!.router.completeErrand(e.id, true, "Page", JSONObject().put("t", "one answer")); net.pump()
        assertEquals(Errand.DONE, e.status)
    }

    @Test fun `cancelling stops the helper`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_FIND); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.FIND, JSONObject().put("q", "is the pass open")); net.pump()
        net.advance(3_000)
        assertEquals(1, net.nodes["B"]!!.rec.errands.size)
        net.nodes["A"]!!.router.cancelErrand(e.id); net.pump()
        assertEquals(Errand.CANCELLED, e.status)
        assertEquals(1, net.nodes["B"]!!.rec.aborts.size)
        assertFalse(net.nodes["B"]!!.router.completeErrand(e.id, true, "x", JSONObject().put("t", "too late")))
    }

    @Test fun `a request nobody can do expires honestly and is never run late`() {
        val net = FakeNet(); net.line("A", "B")
        val e = net.nodes["A"]!!.router.requestErrand(Errand.WX, JSONObject().put("lat", 32_240_000L).put("lng", 77_190_000L), ttlMs = 60_000L)
        net.pump()
        net.advance(61_000)
        net.nodes["A"]!!.router.tick(); net.pump()
        assertEquals(Errand.EXPIRED, e.status)
        assertEquals(listOf(Errand.EXPIRED), net.nodes["A"]!!.rec.answers.map { it.status })
        net.nodes["B"]!!.router.setCaps(Errand.CAP_WX)
        net.advance(40_000)
        assertTrue(net.nodes["B"]!!.rec.errands.isEmpty())
    }

    @Test fun `a helper that restarts mid-fetch picks it back up`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_READ); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, url()); net.pump()
        net.advance(3_000)
        val snap = JSONObject(net.nodes["B"]!!.router.snapshot().toString())
        val fresh = FakeNet(); fresh.now = net.now
        val b2 = fresh.node("B"); b2.router.restore(snap); b2.router.setCaps(Errand.CAP_READ)
        b2.router.resumeErrands()
        assertEquals(listOf(e.id), b2.rec.errands.map { it.id })
    }

    @Test fun `saying no hands it to the next phone`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        for (h in listOf("B", "C")) net.nodes[h]!!.router.setCaps(Errand.CAP_READ)
        net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, url()); net.pump()
        net.advance(3_000)
        val first = e.helper!!; val second = if (first == "B") "C" else "B"
        net.nodes[first]!!.router.declineErrand(e.id, "budget"); net.pump()
        net.advance(40_000)
        assertEquals(1, net.nodes[second]!!.rec.errands.size)
        assertEquals(second, e.helper)
    }

    @Test fun `an old dispatch replayed by gap-fill does not restart a finished request`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_READ); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, url()); net.pump()
        net.advance(3_000)
        net.nodes["B"]!!.router.completeErrand(e.id, true, "x", JSONObject().put("t", "done")); net.pump()
        // a third phone that had the request but not the answer links to a fresh helper
        val c = net.node("C"); c.router.setCaps(Errand.CAP_READ)
        net.connect("B", "C")                     // gets request AND answer in the same fill
        net.advance(40_000)
        assertTrue(c.rec.errands.isEmpty())
    }

    @Test fun `answers fit one radio frame however long the page`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_READ); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, url()); net.pump()
        net.advance(3_000)
        val rnd = java.util.Random(7)
        val text = (1..400).joinToString("\n\n") { (1..60).joinToString(" ") { Crypto.randomId(4 + rnd.nextInt(6)) } }
        val links = JSONArray(); repeat(40) { links.put(JSONArray().put("link $it").put("https://example.org/" + Crypto.randomId(30))) }
        net.nodes["B"]!!.router.completeErrand(e.id, true, "Huge page", JSONObject().put("t", text).put("l", links)); net.pump()
        assertEquals(Errand.DONE, e.status)
        assertTrue("frame was ${net.maxFrameBytes}", net.maxFrameBytes < 32_000)
        assertTrue(e.answer()!!.getString("t").endsWith("…"))
    }

    @Test fun `a 2_x asker still gets a public answer from an updated helper`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val b = net.nodes["B"]!!.router
        b.setCaps(Errand.CAP_READ)
        // exactly what 2.1 sends: directed, no "rv"
        val p = JSONObject().put("eid", "legacyeid1").put("type", "read").put("args", url()).put("helper", "B").put("helperName", "B")
        val env = Envelope(JSONObject().put("id", Crypto.randomId(12)).put("k", Envelope.ERRAND).put("o", "A").put("on", "A")
            .put("ts", net.now).put("h", 0).put("p", p)).also { it.sign(b.group.key) }
        b.onBytes("B>A", JSONObject().put("t", "env").put("e", env.json).toString().toByteArray()); net.pump()
        net.advance(3_000)
        assertEquals(listOf("legacyeid1"), net.nodes["B"]!!.rec.errands.map { it.id })
        b.completeErrand("legacyeid1", true, "Web page: example", JSONObject().put("t", "Sunny, 18°C")); net.pump()
        for (id in listOf("A", "B", "C")) assertTrue(net.texts(id).any { it.contains("Sunny, 18°C") })
    }

    @Test fun `a 2_x asker whose request can't be done hears so instead of waiting forever`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!.router   // no caps at all: no signal right now
        val p = JSONObject().put("eid", "legacyeid2").put("type", "weather").put("args", JSONObject().put("place", "Manali")).put("helper", "B")
        val env = Envelope(JSONObject().put("id", Crypto.randomId(12)).put("k", Envelope.ERRAND).put("o", "A").put("on", "A")
            .put("ts", net.now).put("h", 0).put("p", p)).also { it.sign(b.group.key) }
        b.onBytes("B>A", JSONObject().put("t", "env").put("e", env.json).toString().toByteArray()); net.pump()
        net.advance(3_000)
        // the app declines what it can't do; for a 2.x asker that becomes a plain public "couldn't"
        b.declineErrand("legacyeid2", "unsupported"); net.pump()
        assertTrue(net.texts("A").any { it.contains("can't do this kind of request") })
    }

    @Test fun `an old helper is asked by name only after the asker agrees to a public answer`() {
        val net = FakeNet(); net.line("A", "B")
        // B announces internet the 2.1 way: net=true, no "ev"
        val pres = Envelope(JSONObject().put("id", Crypto.randomId(12)).put("k", Envelope.PRESENCE).put("o", "B").put("on", "B")
            .put("ts", net.now).put("h", 0).put("p", JSONObject().put("n", "B").put("net", true))).also { it.sign(net.nodes["A"]!!.router.group.key) }
        net.nodes["A"]!!.router.onBytes("A>B", JSONObject().put("t", "env").put("e", pres.json).toString().toByteArray())
        val a = net.nodes["A"]!!.router
        assertEquals(listOf("B"), a.legacyHelpers().map { it.id })
        val e = a.requestErrand(Errand.READ, url()); net.pump()
        net.advance(70_000)
        assertNotEquals("B", e.helper)                            // not without asking
        a.allowPublicAnswer(e.id); net.pump()
        assertEquals("B", e.helper)
        assertTrue("B" in e.legacyAsked)
    }

    @Test fun `a passing problem stays quiet for a 2_x asker so they can simply ask again`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!.router
        val p = JSONObject().put("eid", "legacyeid3").put("type", "read").put("args", url()).put("helper", "B")
        val env = Envelope(JSONObject().put("id", Crypto.randomId(12)).put("k", Envelope.ERRAND).put("o", "A").put("on", "A")
            .put("ts", net.now).put("h", 0).put("p", p)).also { it.sign(b.group.key) }
        b.onBytes("B>A", JSONObject().put("t", "env").put("e", env.json).toString().toByteArray()); net.pump()
        net.advance(3_000)
        b.declineErrand("legacyeid3", "no_signal"); net.pump()
        assertTrue(net.texts("A").isEmpty())
    }

    @Test fun `a text whose helper went quiet is not sent again without the asker saying so`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        for (h in listOf("B", "C")) net.nodes[h]!!.router.setCaps(Errand.CAP_SMS)
        net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.SEND, JSONObject().put("to", "+919800000000").put("text", "OK")); net.pump()
        net.advance(3_000)
        val first = e.helper!!; val second = if (first == "B") "C" else "B"
        net.nodes[first]!!.router.markSendOpened(e.id)
        net.disconnect("A", first)                                 // their phone dies after they opened Messages
        net.advance(Router.HUMAN_LEASE_MS + 60_000, step = 5_000)
        assertTrue(net.nodes[second]!!.rec.errands.isEmpty())        // nobody re-sends on their own
        assertEquals("quiet", e.why)
        net.nodes["A"]!!.router.retryErrand(e.id); net.pump()       // the asker chooses "Ask someone else"
        net.advance(40_000)
        assertEquals(1, net.nodes[second]!!.rec.errands.size)
    }

    @Test fun `phones that can't help never claim`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_SMS); net.pump()
        net.nodes["A"]!!.router.requestErrand(Errand.READ, url()); net.pump()
        net.advance(60_000)
        assertTrue(net.nodes["B"]!!.rec.errands.isEmpty())
    }

    @Test fun `a text home goes to a phone with mobile service and waits for a person`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_SMS); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.SEND, JSONObject().put("to", "+919800000000").put("text", "I'm OK")); net.pump()
        net.advance(3_000)
        assertEquals(1, net.nodes["B"]!!.rec.errands.size)
        // a person has twenty minutes: no hand-off after a normal fetch lease
        net.advance(Router.WORK_LEASE_MS * 3)
        assertEquals("B", e.helper); assertEquals(Errand.CLAIMED, e.status)
        net.nodes["B"]!!.router.completeErrand(e.id, true, "Text sent to +919800000000", JSONObject().put("t", "Sent at 14:02")); net.pump()
        assertEquals(Errand.DONE, e.status)
    }

    @Test fun `nobody tapping send for half an hour lets someone else try`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        for (h in listOf("B", "C")) net.nodes[h]!!.router.setCaps(Errand.CAP_SMS)
        net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.SEND, JSONObject().put("to", "+919800000000").put("text", "OK")); net.pump()
        net.advance(3_000)
        val first = e.helper!!; val second = if (first == "B") "C" else "B"
        net.advance(Router.HUMAN_MAX_MS + 60_000, step = 5_000)
        assertEquals(second, e.helper)
        assertEquals(1, net.nodes[second]!!.rec.errands.size)
    }

    @Test fun `my request history survives a restart, answers included`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_WX); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.WX, JSONObject().put("lat", 1_000_000L).put("lng", 2_000_000L)); net.pump()
        net.advance(3_000)
        net.nodes["B"]!!.router.completeErrand(e.id, true, "Weather near you", JSONObject().put("t", "Now: clear, 12°C")); net.pump()
        val fresh = FakeNet(); val a2 = fresh.node("A"); a2.router.restore(JSONObject(net.nodes["A"]!!.router.snapshot().toString()))
        val back = a2.router.errands[e.id]!!
        assertEquals(Errand.DONE, back.status); assertEquals("Now: clear, 12°C", back.answer()!!.getString("t"))
        // and other people's finished requests are not kept around with their details
        assertTrue(JSONObject(net.nodes["B"]!!.router.snapshot().toString()).getJSONArray("errands").length() <= 1)
    }
    @Test fun `a quiet text never makes the phone wake up for a time already past`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("B", "C")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_SMS); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.SEND, JSONObject().put("to", "+919800000000").put("text", "OK")); net.pump()
        net.advance(3_000)
        assertEquals("B", e.helper)
        net.disconnect("A", "B"); net.disconnect("B", "C")             // B's phone dies with the text claimed
        net.advance(Router.HUMAN_LEASE_MS + 60_000, step = 5_000)
        assertEquals("quiet", e.why)
        for (n in listOf("A", "C")) assertTrue(n, net.nodes[n]!!.router.nextErrandWake() > net.now)
    }

    @Test fun `a request asked offline runs on my own signal later and the group lets it go`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        val e = a.requestErrand(Errand.READ, url()); net.pump()
        assertEquals(Errand.WAITING, e.status)
        assertNotNull(net.nodes["B"]!!.router.errands[e.id])           // B carries it
        assertTrue(a.runOwnNow(e.id, Errand.CAP_READ))
        assertEquals(listOf(e.id), net.nodes["A"]!!.rec.errands.map { it.id })
        net.pump()
        assertEquals("A", net.nodes["B"]!!.router.errands[e.id]!!.helper)   // the group knows it's taken
        net.nodes["B"]!!.router.setCaps(Errand.CAP_READ)                 // B gets signal: nothing to do
        net.advance(60_000)
        assertTrue(net.nodes["B"]!!.rec.errands.isEmpty())
        assertTrue(a.completeErrand(e.id, true, "Page", JSONObject().put("t", "read here"))); net.pump()
        assertEquals(Errand.DONE, e.status)
        assertFalse(net.nodes["B"]!!.router.errands[e.id]?.isOpen ?: false)
        assertFalse("not run twice", a.runOwnNow(e.id, Errand.CAP_READ))
    }

    @Test fun `if my own signal fails the request goes back to the group`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        val e = a.requestErrand(Errand.READ, url()); net.pump()
        assertTrue(a.runOwnNow(e.id, Errand.CAP_READ)); net.pump()
        a.declineErrand(e.id, "no_signal"); net.pump()
        assertFalse("a failure here waits before trying here again", a.runOwnNow(e.id, Errand.CAP_READ))
        net.nodes["B"]!!.router.setCaps(Errand.CAP_READ); net.pump()
        net.advance(40_000)
        assertEquals(listOf(e.id), net.nodes["B"]!!.rec.errands.map { it.id })
        assertEquals("B", e.helper)
        assertFalse("a friend is on it", a.runOwnNow(e.id, Errand.CAP_READ, force = true))
    }
    @Test fun `read more goes to the phone that has the page first, and finished requests can be removed`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        for (h in listOf("B", "C")) net.nodes[h]!!.router.setCaps(Errand.CAP_READ)
        net.pump()
        val a = net.nodes["A"]!!.router
        for (who in listOf("B", "C")) {
            val e = a.requestErrand(Errand.READ, url().put("part", 2), prefer = who); net.pump()
            assertEquals(who, e.pick.first())
            net.advance(3_000)
            assertEquals(who, e.helper)
            net.nodes[who]!!.router.completeErrand(e.id, true, "Page", JSONObject().put("t", "part 2")); net.pump()
            assertFalse("an open one stays", a.forgetErrand("nope"))
            assertTrue(a.forgetErrand(e.id))
            assertNull(a.errands[e.id])
        }
        val open = a.requestErrand(Errand.READ, url())
        assertFalse("an open request is cancelled, not removed", a.forgetErrand(open.id))
    }
    @Test fun `a bystander never picks up a text another phone took, even after it went quiet`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C"); net.connect("B", "C")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_SMS); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.SEND, JSONObject().put("to", "+919800000000").put("text", "OK")); net.pump()
        net.advance(3_000)
        assertEquals("B", e.helper)
        net.nodes["B"]!!.router.markSendOpened(e.id)
        net.disconnect("A", "B"); net.disconnect("B", "C")             // B's phone dies after opening Messages
        net.advance(Router.HUMAN_LEASE_MS + 60_000, step = 5_000)
        val c = net.nodes["C"]!!.router
        c.setCaps(Errand.CAP_SMS); c.setCaps(0); c.setCaps(Errand.CAP_SMS)   // C's service flaps
        net.advance(120_000, step = 5_000)
        assertTrue("C never asks its person to send it again", net.nodes["C"]!!.rec.errands.isEmpty())
        net.nodes["A"]!!.router.retryErrand(e.id); net.pump()       // only the asker hands it on
        net.advance(40_000)
        assertEquals(1, net.nodes["C"]!!.rec.errands.size)
    }

    @Test fun `a helper that restarts while a friend is mid-fetch does not start the same fetch`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_READ); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, url()); net.pump()
        net.advance(3_000)
        assertEquals("B", e.helper)
        // C restarts with signal while B's claim is live
        val snap = JSONObject(net.nodes["C"]!!.router.snapshot().toString())
        val c2 = Router(Identity("C", "C"), Group(FakeNet.CODE, "Trek"), net.nodes["C"]!!.transport, net.nodes["C"]!!.rec) { net.now }
        c2.restore(snap)
        assertEquals(Errand.CLAIMED, c2.errands[e.id]!!.status)
        assertTrue("the live claim survived the restart", c2.errands[e.id]!!.leaseUntil > net.now)
        c2.setCaps(Errand.CAP_READ); c2.resumeErrands()
        repeat(10) { net.now += 2_000; c2.pollErrands() }
        assertTrue(net.nodes["C"]!!.rec.errands.isEmpty())
    }

    @Test fun `cancelling a request running on my own signal tells the phones carrying it`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        val e = a.requestErrand(Errand.READ, url()); net.pump()
        // A restarts before getting signal: the group still knows about the request
        val a2snap = JSONObject(a.snapshot().toString())
        assertTrue(a2snap.toString().contains("lastDispatchAt"))
        assertTrue(a.runOwnNow(e.id, Errand.CAP_READ)); net.pump()
        a.cancelErrand(e.id); net.pump()
        assertFalse(net.nodes["B"]!!.router.errands[e.id]?.isOpen ?: false)
        net.nodes["B"]!!.router.setCaps(Errand.CAP_READ)
        net.advance(Router.WORK_LEASE_MS + 60_000)
        assertTrue("B never fetches a cancelled request", net.nodes["B"]!!.rec.errands.isEmpty())
    }
}
