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
        assertEquals(1, net.byId(first).rec.errands.size)
        val other = if (net.byId(first).label == "B") "C" else "B"
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
        val first = net.byId(e.helper!!).label
        val second = if (first == "B") "C" else "B"
        net.disconnect("A", first)                              // walks off mid-fetch, never answers
        net.advance(Router.WORK_LEASE_MS + 45_000)
        assertEquals(1, net.nodes[second]!!.rec.errands.size)
        assertEquals(net.id(second), e.helper)
        assertTrue(net.id(first) in e.tried)
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
        val (keeps, yields) = if (net.id("B") < net.id("C")) "B" to "C" else "C" to "B"
        assertEquals(1, net.nodes[yields]!!.rec.aborts.size)
        assertTrue(net.nodes[keeps]!!.rec.aborts.isEmpty())
        net.connect("A", keeps)
        net.nodes[keeps]!!.router.completeErrand(e.id, true, "Page", JSONObject().put("t", "one answer")); net.pump()
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
        val first = net.byId(e.helper!!).label; val second = if (first == "B") "C" else "B"
        net.nodes[first]!!.router.declineErrand(e.id, "budget"); net.pump()
        net.advance(40_000)
        assertEquals(1, net.nodes[second]!!.rec.errands.size)
        assertEquals(net.id(second), e.helper)
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
        // what 2.1 sent: directed, no "rv" (under an id of the asker's, as every request's is now)
        val eid = FakeNet.newId("A")
        val p = JSONObject().put("eid", eid).put("type", "read").put("args", url()).put("helper", net.id("B")).put("helperName", "B")
        val env = FakeNet.envelope("A", Envelope.ERRAND, p, net.now)
        b.onBytes("B>A", FakeNet.frame(env)); net.pump()
        net.advance(3_000)
        assertEquals(listOf(eid), net.nodes["B"]!!.rec.errands.map { it.id })
        b.completeErrand(eid, true, "Web page: example", JSONObject().put("t", "Sunny, 18°C")); net.pump()
        for (id in listOf("A", "B", "C")) assertTrue(net.texts(id).any { it.contains("Sunny, 18°C") })
    }

    @Test fun `a 2_x asker whose request can't be done hears so instead of waiting forever`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!.router   // no caps at all: no signal right now
        val eid = FakeNet.newId("A")
        val p = JSONObject().put("eid", eid).put("type", "weather").put("args", JSONObject().put("place", "Manali")).put("helper", net.id("B"))
        val env = FakeNet.envelope("A", Envelope.ERRAND, p, net.now)
        b.onBytes("B>A", FakeNet.frame(env)); net.pump()
        net.advance(3_000)
        // the app declines what it can't do; for a 2.x asker that becomes a plain public "couldn't"
        b.declineErrand(eid, "unsupported"); net.pump()
        assertTrue(net.texts("A").any { it.contains("can't do this kind of request") })
    }

    @Test fun `an old helper is asked by name only after the asker agrees to a public answer`() {
        val net = FakeNet(); net.line("A", "B")
        // B announces internet the 2.1 way: net=true, no "ev"
        val pres = FakeNet.envelope("B", Envelope.PRESENCE, JSONObject().put("n", "B").put("net", true).put("q", net.now + 1), net.now)
        net.nodes["A"]!!.router.onBytes("A>B", FakeNet.frame(pres))
        val a = net.nodes["A"]!!.router
        assertEquals(listOf(net.id("B")), a.legacyHelpers().map { it.id })
        val e = a.requestErrand(Errand.READ, url()); net.pump()
        net.advance(70_000)
        assertNotEquals(net.id("B"), e.helper)                    // not without asking
        a.allowPublicAnswer(e.id); net.pump()
        assertEquals(net.id("B"), e.helper)
        assertTrue(net.id("B") in e.legacyAsked)
    }

    @Test fun `a passing problem stays quiet for a 2_x asker so they can simply ask again`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!.router
        val eid = FakeNet.newId("A")
        val p = JSONObject().put("eid", eid).put("type", "read").put("args", url()).put("helper", net.id("B"))
        val env = FakeNet.envelope("A", Envelope.ERRAND, p, net.now)
        b.onBytes("B>A", FakeNet.frame(env)); net.pump()
        net.advance(3_000)
        b.declineErrand(eid, "no_signal"); net.pump()
        assertTrue(net.texts("A").isEmpty())
    }

    @Test fun `a text whose helper went quiet is not sent again without the asker saying so`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        for (h in listOf("B", "C")) net.nodes[h]!!.router.setCaps(Errand.CAP_SMS)
        net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.SEND, JSONObject().put("to", "+919800000000").put("text", "OK")); net.pump()
        net.advance(3_000)
        val first = net.byId(e.helper!!).label; val second = if (first == "B") "C" else "B"
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
        assertEquals(net.id("B"), e.helper); assertEquals(Errand.CLAIMED, e.status)
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
        val first = net.byId(e.helper!!).label; val second = if (first == "B") "C" else "B"
        net.advance(Router.HUMAN_MAX_MS + 60_000, step = 5_000)
        assertEquals(net.id(second), e.helper)
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
        assertEquals(net.id("B"), e.helper)
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
        assertEquals(net.id("A"), net.nodes["B"]!!.router.errands[e.id]!!.helper)   // the group knows it's taken
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
        assertEquals(net.id("B"), e.helper)
        assertFalse("a friend is on it", a.runOwnNow(e.id, Errand.CAP_READ, force = true))
    }
    @Test fun `read more goes to the phone that has the page first, and finished requests can be removed`() {
        val net = FakeNet(); net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        for (h in listOf("B", "C")) net.nodes[h]!!.router.setCaps(Errand.CAP_READ)
        net.pump()
        val a = net.nodes["A"]!!.router
        for (who in listOf("B", "C")) {
            val e = a.requestErrand(Errand.READ, url().put("part", 2), prefer = net.id(who)); net.pump()
            assertEquals(net.id(who), e.pick.first())
            net.advance(3_000)
            assertEquals(net.id(who), e.helper)
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
        assertEquals(net.id("B"), e.helper)
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
        assertEquals(net.id("B"), e.helper)
        // C restarts with signal while B's claim is live
        val snap = JSONObject(net.nodes["C"]!!.router.snapshot().toString())
        val c2 = Router(FakeNet.identity("C"), FakeNet.group(), net.nodes["C"]!!.transport, net.nodes["C"]!!.rec) { net.now }
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

    /** An answer body as a helper's phone packs it ([Gz.pack]), from any text at all. */
    private fun gz(text: String): String {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Crypto.b64(bos.toByteArray())
    }

    @Test fun `an answer whose body nests deeper than any answer is never kept`() {
        // a few hundred bytes on the air, and a parser-killer once inflated
        fun deep(levels: Int) = gz("{\"t\":" + "[".repeat(levels) + "]".repeat(levels) + "}")
        assertNull(Gz.unpackJson(deep(Crypto.MAX_DEPTH + 8)))
        assertNull(Gz.unpackJson(deep(200_000)))          // and nothing is thrown, however deep
        assertTrue(deep(200_000).length < 2_000)
        assertEquals("ok", Gz.unpackJson(gz("{\"t\":\"ok\",\"l\":[[1]]}"))!!.getString("t"))
        // sent as the answer to my request, by a member who saw it go by: my request stays open
        val net = FakeNet(); net.line("A", "M")
        val a = net.nodes["A"]!!
        val e = a.router.requestErrand(Errand.READ, url()); net.pump()
        val inner = JSONObject().put("eid", e.id).put("ok", true).put("ty", Errand.READ).put("title", "Page")
            .put("z", deep(Crypto.MAX_DEPTH + 8)).put("by", "M")
        val answer = FakeNet.envelope("M", Envelope.DM, JSONObject().put("text", "Page").put("er", inner), net.now,
            to = net.id("A"), er = JSONObject().put("eid", e.id).put("ok", true))
        a.router.onBytes("A>M", FakeNet.frame(answer)); net.pump()
        assertTrue(e.isOpen); assertEquals("", e.answerZ); assertNull(e.answer())
        assertTrue(a.rec.answers.isEmpty())
        // a state saved with such a body before this check reads back as no answer, not a crash
        assertNull(Errand.fromJson(JSONObject(e.toJson().toString()).put("z", deep(200_000))).answer())
    }

    @Test fun `someone's request carried from before the update is let go, and their new one is taken`() {
        val net = FakeNet(); val b = net.node("B")
        val t0 = net.now - 60_000
        fun request(id: String, from: String) = Errand(id, Errand.READ, url(), from, "Asha", t0).also {
            it.rv = Errand.EV; it.exp = net.now + 3_600_000L; it.status = Errand.ASKED
        }
        val old = request("oldrequest01", "abcdefgh")        // Asha's id before 2.4
        val keyless = request("keyless00001", net.id("Z"))   // a 2.4 phone this one has no key for
        b.router.restore(JSONObject().put("fmt", Router.FMT).put("me", b.id)
            .put("errands", JSONArray().put(old.toJson()).put(keyless.toJson()))
            .put("running", JSONArray().put(old.id).put(keyless.id)))
        assertNull(b.router.errands[old.id])
        // no answer could ever be sealed for either asker: nothing is fetched, then or when signal comes
        b.router.resumeErrands()
        b.router.setCaps(Errand.CAP_READ)
        net.advance(60_000)
        assertTrue(b.rec.errands.isEmpty())
        assertFalse(b.router.isRunning(keyless.id))
        // Asha's phone, updated, sends it again under her new id (and, as every request now, an id of hers): taken in, and done
        net.node("A"); net.connect("A", "B")
        val eid = FakeNet.newId("A")
        val again = FakeNet.envelope("A", Envelope.ERRAND, JSONObject().put("eid", eid).put("type", Errand.READ).put("args", url())
            .put("helper", "").put("rv", Errand.EV).put("exp", net.now + 3_600_000L).put("at", t0), net.now)
        b.router.onBytes("B>A", FakeNet.frame(again)); net.pump()
        assertEquals(net.id("A"), b.router.errands[eid]!!.from)
        net.advance(60_000)
        assertEquals(listOf(eid), b.rec.errands.map { it.id })
        assertTrue(b.router.completeErrand(eid, true, "Page", JSONObject().put("t", "the page"))); net.pump()
    }

    @Test fun `a request's id is its asker's, and nobody can send it first as their own`() {
        val net = FakeNet(); net.node("A"); net.node("H"); net.node("M")
        val h = net.nodes["H"]!!
        h.router.setCaps(Errand.CAP_READ)
        val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, url())
        assertTrue(e.id.startsWith("${net.id("A")}."))
        // M saw it go by and sends the same request as M's own, reaching H first
        net.connect("H", "M")
        val squat = FakeNet.envelope("M", Envelope.ERRAND, JSONObject().put("eid", e.id).put("type", Errand.READ).put("args", url("https://evil.example/"))
            .put("helper", "").put("rv", Errand.EV).put("exp", net.now + 3_600_000L).put("at", net.now), net.now)
        h.router.onBytes("H>M", FakeNet.frame(squat)); net.pump()
        assertNull(h.router.errands[e.id])
        assertTrue(h.router.carries(squat.id))                          // still passed round like any genuine envelope
        // A's own request still gets through, and is done
        net.connect("A", "H")
        assertEquals(net.id("A"), h.router.errands[e.id]!!.from)
        net.advance(40_000)
        assertEquals(listOf(e.id), h.rec.errands.map { it.id })
        assertEquals("https://example.org/page", h.rec.errands.single().args.getString("url"))
        assertTrue(h.router.completeErrand(e.id, true, "Page", JSONObject().put("t", "the page"))); net.pump()
        assertEquals(Errand.DONE, e.status)
    }

    @Test fun `a request of mine still open from before goes on under an id of mine`() {
        val net = FakeNet(); val a = net.node("A"); val b = net.node("B")
        val hour = 3_600_000L
        val old = Errand("oldrequest01", Errand.READ, url(), a.id, "A", net.now - 60_000).also {
            it.rv = Errand.EV; it.exp = net.now + hour; it.status = Errand.ASKED; it.lastDispatchAt = net.now - 60_000; it.dispatchTs = net.now - 60_000
        }
        val done = Errand("oldrequest02", Errand.FIND, JSONObject().put("q", "pass"), a.id, "A", net.now - hour).also {
            it.rv = Errand.EV; it.exp = net.now; it.status = Errand.DONE; it.result = "open till 6"
        }
        // the state an upgrade leaves: my requests under ids from before, and the list of what to send again
        a.router.restore(JSONObject().put("fmt", Router.FMT).put("me", a.id).put("errands", JSONArray().put(old.toJson()).put(done.toJson()))
            .put("doneAt", JSONObject().put(done.id, net.now - hour)).put("reissue", JSONArray()))
        assertNull(a.router.errands[old.id])
        val mine = a.router.errands.values.single { it.isOpen }
        assertTrue(mine.id.startsWith("${a.id}."))
        assertEquals("https://example.org/page", mine.args.getString("url")); assertEquals(Errand.ASKED, mine.status)
        assertEquals("finished ones keep their id", "open till 6", a.router.errands[done.id]!!.result)
        a.router.reissueQueued()
        b.router.setCaps(Errand.CAP_READ)
        net.connect("A", "B")
        net.advance(40_000)
        assertEquals(listOf(mine.id), b.rec.errands.map { it.id })
        assertTrue(b.router.completeErrand(mine.id, true, "Page", JSONObject().put("t", "the page"))); net.pump()
        assertEquals(Errand.DONE, mine.status)
    }
}
