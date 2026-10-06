package app.hopline.mesh

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Leaving and joining, as the group sees it. A goodbye takes the phone out of everyone's group at
 * once — not in range, not a member, nobody to write to, nobody to @ — and the chat says so; a
 * hello brings it back. Both are ordered by the phone's own counter (the one on its beacons), so
 * neither a goodbye carried in late nor a beacon recorded early can undo what is newer.
 */
class MembershipTest {

    private fun copy(j: JSONObject) = JSONObject(j.toString())

    /**
     * [who] leaves the group the way Core.leaveGroup does it: the requests it was on handed back,
     * its goodbye, the radio up until that has gone, then nothing — and its kept chat, as stripped.
     */
    private fun leave(net: FakeNet, who: String, decline: Boolean = true): JSONObject {
        val n = net.nodes[who]!!
        val declined = if (decline) n.router.declineForLeaving() else emptyList()
        n.router.sayGoodbye(declined)
        net.pump()
        for (peer in n.peers.values.map { it.first }.toList()) net.disconnect(who, peer)
        net.nodes.remove(who)
        return Archive.strip(n.router.snapshot(), n.id, n.name, net.now, net.now)
    }

    /** ...and comes back the way Core builds a rejoined group: its kept chat, "You rejoined", the hello owed. */
    private fun rejoin(net: FakeNet, who: String, archive: JSONObject, vararg to: String): FakeNet.Node {
        val n = net.node(who)
        n.router.restore(copy(archive))
        Archive.rejoined(n.router)
        n.router.helloIfDue(firstJoin = false, joinedAt = 0)
        for (peer in to) net.connect(who, peer)
        return n
    }

    private fun membership(r: Router, who: String): List<String> = r.messages.filter { it.from == who && it.isMembership }.map { it.kind }

    private fun bye(from: String, q: Long, ts: Long) =
        FakeNet.envelope(from, Envelope.CHAT, JSONObject().put("text", Router.BYE_TEXT).put("mb", Router.MB_LEFT).put("q", q), ts)

    private fun hello(from: String, q: Long, ts: Long) =
        FakeNet.envelope(from, Envelope.CHAT, JSONObject().put("text", Router.HELLO_TEXT).put("mb", Router.MB_JOINED).put("q", q), ts)

    private fun beacon(from: String, q: Long, ts: Long) =
        FakeNet.envelope(from, Envelope.PRESENCE, JSONObject().put("n", from).put("q", q), ts)

    // ---------------------------------------------------------------- leaving

    @Test fun `a goodbye takes the leaver out of everyone's group at once, and the chat says so`() {
        val net = FakeNet(); net.line("A", "B", "C")
        net.nodes["C"]!!.router.setCaps(Errand.CAP_READ); net.tickAll()
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router
        val c = net.id("C")
        val ask = Errand("x", Errand.READ, JSONObject(), a.me.id, "", net.now)
        assertTrue("a helper before", a.capableHelpers(ask).any { it.id == c } && a.helpers().any { it.id == c })
        // As on the screenshot: in range, a phone between them
        assertTrue(a.isInRange(a.people[c]!!)); assertFalse(a.people[c]!!.direct); assertEquals(2, a.peopleInRange())

        leave(net, "C")                                                // and no time passes
        for (r in listOf(a, b)) {
            val p = r.people[c]!!
            assertTrue(p.left)
            assertFalse("not in range a moment after the goodbye", r.isInRange(p))
            assertEquals(1, r.peopleInRange()); assertEquals(1, r.activePeople())
            assertEquals(listOf(r.people.keys.first { it != c }), r.activePeopleList().map { it.id })
            assertTrue("still told apart from a namesake", c in r.recentPeopleList().map { it.id })
            assertTrue(r.capableHelpers(Errand("x", Errand.READ, JSONObject(), r.me.id, "", net.now)).none { it.id == c })
            assertTrue(r.helpers().none { it.id == c })
            val line = r.messages.single { it.from == c }
            assertEquals(Message.MEMBER_LEFT, line.kind)
            assertTrue(line.isNotice); assertTrue(line.isMembership); assertFalse(line.isRename); assertFalse(line.isPersonal)
            assertTrue(line.isGroup); assertEquals("", line.text); assertEquals(Message.GROUP_CHAT, line.chatKey(r.me.id))
            // Nothing private reaches them any more — but what the group owes them (an answer) can still be sealed
            assertFalse(r.canMessage(c)); assertTrue(r.canWriteTo(c))
            assertNull(r.sendDm(c, "are you there?"))
            assertNull(r.sendLocation(Loc.of(12.97, 77.59)!!, to = c))
            // and nobody confirms the goodbye to a phone that is gone
            assertFalse(r.carries("r.${line.id}.${r.me.id}"))
        }
        assertTrue("never notified", net.nodes["A"]!!.rec.shown.isEmpty() && net.nodes["B"]!!.rec.shown.isEmpty())
    }

    @Test fun `nothing is sent on the link of a phone that said goodbye while its radio finishes`() {
        val net = FakeNet(); net.line("A", "B"); net.tickAll()
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router
        assertTrue(a.sayGoodbye()); net.pump()                        // A's radio is still up for a moment
        assertTrue(b.people[net.id("A")]!!.left)
        assertTrue("not a link to the group any more", b.authedLinks().isEmpty())
        val m = b.sendChat("anyone still here?"); net.pump()
        assertEquals("nobody in the group has it", Message.QUEUED, m.status)
        assertNull(a.message(m.id))
    }

    @Test fun `a phone that was away hears the goodbye from whoever carries it`() {
        val net = FakeNet(); net.line("A", "B", "C"); net.tickAll()
        leave(net, "C")
        net.now += 3 * 3_600_000L
        val d = net.node("D")
        net.connect("B", "D")
        val p = d.router.people[net.id("C")]!!
        assertTrue(p.left)
        assertEquals(listOf(Message.MEMBER_LEFT), membership(d.router, net.id("C")))
        assertTrue(d.router.activePeopleList().none { it.id == net.id("C") })
        assertTrue(d.rec.shown.isEmpty())
    }

    @Test fun `with nobody linked nothing is said, and a rejoin owes no hello`() {
        val net = FakeNet(); net.line("A", "B"); net.tickAll()
        net.disconnect("A", "B")
        val a = net.nodes["A"]!!.router
        assertFalse(a.sayGoodbye())
        assertEquals("", a.said)
        val archive = leave(net, "A")
        val a2 = rejoin(net, "A", archive, "B")
        assertEquals("", a2.router.said)
        assertTrue(net.nodes["B"]!!.router.messages.none { it.isMembership })
        assertFalse(net.nodes["B"]!!.router.people[net.id("A")]!!.left)
    }

    // ---------------------------------------------------------------- coming back

    @Test fun `back in the group, a hello undoes the goodbye everywhere and is said only once`() {
        val net = FakeNet(); net.line("A", "B", "C"); net.tickAll()
        val c = net.id("C")
        val archive = leave(net, "C")
        assertEquals(Router.MB_LEFT, archive.getJSONObject("said").getString("k"))
        val byeId = net.nodes["A"]!!.router.messages.single { it.kind == Message.MEMBER_LEFT }.id
        net.now += 3_600_000L
        val c2 = rejoin(net, "C", archive, "B")
        assertEquals(Router.MB_JOINED, c2.router.said)
        net.tickAll()
        for (label in listOf("A", "B")) {
            val r = net.nodes[label]!!.router
            val p = r.people[c]!!
            assertFalse(p.left); assertTrue(r.isInRange(p)); assertTrue(r.canMessage(c))
            assertEquals(listOf(Message.MEMBER_LEFT, Message.MEMBER_JOINED), membership(r, c))
            assertTrue(net.nodes[label]!!.rec.shown.isEmpty())
            assertNotNull(r.sendDm(c, "welcome back"))
        }
        // The rejoiner's own chat: "You left", "You rejoined". Its goodbye, which B handed back, is
        // carried again — and is neither a bubble nor a second line.
        net.pump()
        assertTrue(c2.router.carries(byeId))
        assertEquals(listOf(Message.LEFT, Message.REJOINED), c2.router.messages.filter { it.from == c && it.kind != Envelope.DM }.map { it.kind })
        assertTrue(c2.router.messages.none { it.text == Router.BYE_TEXT || it.text == Router.HELLO_TEXT })
        repeat(2) { net.now += Router.SYNC_MS + 1_000; net.tickAll() }
        assertEquals(1, net.nodes["A"]!!.router.messages.count { it.kind == Message.MEMBER_JOINED })

        // Killed before the hello was saved: the next start says the very same hello, which nobody shows twice
        val helloId = net.nodes["A"]!!.router.messages.single { it.kind == Message.MEMBER_JOINED }.id
        net.disconnect("B", "C"); net.nodes.remove("C")
        val c3 = rejoin(net, "C", archive, "B")
        assertTrue(c3.router.carries(helloId))
        net.tickAll()
        assertEquals(1, net.nodes["A"]!!.router.messages.count { it.kind == Message.MEMBER_JOINED })
        assertFalse(net.nodes["A"]!!.router.people[c]!!.left)
    }

    @Test fun `a hello said with nobody around is owed until it gets off the phone`() {
        val net = FakeNet(); net.line("A", "B", "C"); net.tickAll()
        val c = net.id("C")
        val first = leave(net, "C")                                    // heard: A and B count C out
        val alone = rejoin(net, "C", first)                            // back with nobody in range: the hello waits
        assertEquals(Router.MB_JOINED, alone.router.said)
        net.now += 3_600_000L
        val second = leave(net, "C")                                   // and gone again, still alone: nothing said
        assertEquals(Router.MB_JOINED, second.getJSONObject("said").getString("k"))
        assertTrue("still owed", second.getJSONObject("said").has("e"))
        net.now += 3_600_000L
        val back = rejoin(net, "C", second, "B")                       // among the group at last: the same hello goes out
        net.tickAll()
        for (label in listOf("A", "B")) {
            val r = net.nodes[label]!!.router
            assertFalse(r.people[c]!!.left)
            assertEquals(listOf(Message.MEMBER_LEFT, Message.MEMBER_JOINED), membership(r, c))
        }
        // and once it is out, it is said for good
        assertFalse(copy(back.router.snapshot()).getJSONObject("said").has("e"))
        assertFalse(back.router.helloIfDue(firstJoin = false, joinedAt = 0))
    }

    @Test fun `joined never sorts above left, whatever the clock did in between`() {
        val net = FakeNet(); net.line("A", "B", "C"); net.tickAll()
        val c = net.id("C")
        net.nodes["C"]!!.skew = 20 * 60_000L                          // C's clock ran 20 minutes fast when it left...
        val archive = leave(net, "C")
        net.now += 2 * 60_000L
        rejoin(net, "C", archive, "B")                                 // ...and was put right before it came back
        net.tickAll()
        assertEquals(listOf(Message.MEMBER_LEFT, Message.MEMBER_JOINED), membership(net.nodes["A"]!!.router, c))
        assertFalse(net.nodes["A"]!!.router.people[c]!!.left)
    }

    @Test fun `a hello on first joining, never from the group's starter, said once`() {
        val net = FakeNet(); net.line("A", "B")
        val c = net.node("C")
        val joinedAt = net.now
        assertTrue(c.router.helloIfDue(firstJoin = true, joinedAt = joinedAt))
        assertFalse("said once", c.router.helloIfDue(firstJoin = true, joinedAt = joinedAt))
        assertTrue("no line of its own: the empty chat stays empty", c.router.messages.isEmpty())
        net.connect("B", "C")
        val a = net.nodes["A"]!!.router
        assertEquals(listOf(Message.MEMBER_JOINED), membership(a, net.id("C")))
        assertTrue(a.activePeopleList().any { it.id == net.id("C") })
        assertTrue(net.nodes["A"]!!.rec.shown.isEmpty())

        // A start killed before its first save: the very same hello again, shown once
        net.disconnect("B", "C")
        val again = net.node("C")
        assertTrue(again.router.helloIfDue(firstJoin = true, joinedAt = joinedAt))
        net.connect("B", "C")
        assertEquals(1, a.messages.count { it.kind == Message.MEMBER_JOINED })

        // The phone that started a group, or one in it since before hellos existed, owes none
        val d = net.node("D")
        assertFalse(d.router.helloIfDue(firstJoin = false, joinedAt = net.now))
        assertEquals("", d.router.said)
    }

    // ---------------------------------------------------------------- order

    @Test fun `an old beacon can't bring back someone who said goodbye, even after a restart`() {
        val net = FakeNet(); net.line("A", "B", "C"); net.tickAll()
        val c = net.id("C")
        val a = net.nodes["A"]!!.router
        val heard = a.people[c]!!.liveQ
        leave(net, "C")
        val p = a.people[c]!!
        val byeQ = p.leftQ
        assertTrue(byeQ > heard)
        // A beacon C sent before its goodbye that never reached A, played now
        a.onBytes("A>B", FakeNet.frame(beacon("C", byeQ - 1, net.now - 1_000))); net.pump()
        assertTrue(p.left); assertFalse(a.isInRange(p))
        // Still so after A restarts
        val fresh = FakeNet(); fresh.now = net.now
        val a2 = fresh.node("A"); a2.router.restore(copy(a.snapshot()))
        val p2 = a2.router.people[c]!!
        assertTrue(p2.left); assertEquals(byeQ, p2.leftQ)
        // A beacon counted after the goodbye: they are back on this group's radio
        fresh.node("B"); fresh.connect("A", "B")
        a2.router.onBytes("A>B", FakeNet.frame(beacon("C", byeQ + 1, fresh.now)))
        assertFalse(p2.left); assertTrue(a2.router.isInRange(p2))
    }

    @Test fun `a goodbye and a hello settle the same whichever arrives first`() {
        for (helloFirst in listOf(false, true)) {
            val net = FakeNet(); net.line("A", "B")
            val a = net.nodes["A"]!!.router
            val bye = bye("C", 100, net.now - 120_000); val hi = hello("C", 200, net.now - 60_000)
            a.onBytes("A>B", if (helloFirst) FakeNet.fill(hi, bye) else FakeNet.fill(bye, hi)); net.pump()
            val p = a.people[net.id("C")]!!
            assertFalse("hello first: $helloFirst", p.left); assertEquals(200L, p.liveQ)
            // Both lines, in the order they were said
            assertEquals(listOf(Message.MEMBER_LEFT, Message.MEMBER_JOINED), membership(a, net.id("C")))
            // A later goodbye still counts
            a.onBytes("A>B", FakeNet.frame(bye("C", 300, net.now))); net.pump()
            assertTrue(p.left)
        }
    }

    @Test fun `anything they signed after their goodbye says they are back, hello or not`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        val c = net.id("C")
        a.onBytes("A>B", FakeNet.frame(bye("C", net.now, net.now))); net.pump()
        assertTrue(a.people[c]!!.left)
        // Something of theirs from before the goodbye, carried in late, changes nothing
        a.onBytes("A>B", FakeNet.fill(FakeNet.envelope("C", Envelope.CHAT, JSONObject().put("text", "on my way out"), net.now - 5_000))); net.pump()
        assertTrue(a.people[c]!!.left)
        // A message they wrote after it — their hello lost, say — and they are a member again
        net.now += 3_600_000L
        a.onBytes("A>B", FakeNet.fill(FakeNet.envelope("C", Envelope.CHAT, JSONObject().put("text", "back again"), net.now))); net.pump()
        assertFalse(a.people[c]!!.left)
        assertTrue(a.canMessage(c))
    }

    @Test fun `a goodbye a phone kept as a message before it was updated becomes its line`() {
        val net = FakeNet(); net.line("A", "B", "C"); net.tickAll()
        val c = net.id("C")
        leave(net, "C")
        val a = net.nodes["A"]!!.router
        val heard = a.people[c]!!.liveQ
        // How 2.4 kept it: an ordinary message with its words, and C still one of the group
        val snap = copy(a.snapshot())
        val msgs = snap.getJSONArray("messages")
        for (i in 0 until msgs.length()) msgs.getJSONObject(i).let { m ->
            if (m.getString("kind") == Message.MEMBER_LEFT) { m.put("kind", Envelope.CHAT); m.put("text", Router.BYE_TEXT) }
        }
        val ppl = snap.getJSONArray("people")
        for (i in 0 until ppl.length()) ppl.getJSONObject(i).let { if (it.getString("id") == c) { it.remove("leftQ"); it.put("liveQ", heard - 1) } }
        val fresh = FakeNet(); fresh.now = net.now
        val a2 = fresh.node("A"); a2.router.restore(snap)
        val line = a2.router.messages.single { it.from == c }
        assertEquals(Message.MEMBER_LEFT, line.kind); assertEquals("", line.text)
        assertTrue(a2.router.people[c]!!.left)
        assertFalse(a2.router.isInRange(a2.router.people[c]!!))
    }

    @Test fun `a goodbye is noted before the chat is saved, and only when there is someone to tell`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        a.aboutToLeave()
        assertEquals(Router.MB_LEFT, copy(a.snapshot()).getJSONObject("said").getString("k"))
        a.stayed()                                                     // the leave was refused: nothing was said
        assertFalse(copy(a.snapshot()).has("said"))
        // Noted, then nobody linked by the time it would go: not said, not owed a hello
        a.aboutToLeave()
        net.disconnect("A", "B")
        assertFalse(a.sayGoodbye())
        assertEquals("", a.said)
        assertFalse(a.helloIfDue(firstJoin = false, joinedAt = 0))
        // Alone: nothing is noted at all
        a.aboutToLeave()
        assertEquals("", a.said)
    }

    @Test fun `a goodbye only counts links it can go out on`() {
        val net = FakeNet(); net.line("A", "C")
        assertTrue(net.nodes["A"]!!.router.sayGoodbye()); net.pump()  // A's radio is finishing; its link still up
        val c = net.nodes["C"]!!.router
        assertFalse("C's only link is to a phone that left", c.sayGoodbye())
        assertEquals("", c.said)
    }

    @Test fun `what a person is saved with can't call them gone after something newer`() {
        val p = Person.fromJson(JSONObject().put("id", FakeNet.idOf("C")).put("liveQ", 500).put("leftQ", 500))
        assertTrue(p.left)
        val outdated = Person.fromJson(JSONObject().put("id", FakeNet.idOf("C")).put("liveQ", 500).put("leftQ", 900))
        assertFalse(outdated.left)
        assertEquals(500L, Person.fromJson(JSONObject(p.toJson().toString())).leftQ)
        assertFalse(Person.fromJson(JSONObject(Person(FakeNet.idOf("C")).toJson().toString())).left)
    }

    // ---------------------------------------------------------------- odd envelopes

    @Test fun `an announcement this version doesn't know shows nothing, and a reaction can't land on a line about the group`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        val later = FakeNet.envelope("C", Envelope.CHAT, JSONObject().put("text", "👋 did something new").put("mb", "moved").put("q", 50), net.now)
        a.onBytes("A>B", FakeNet.frame(later)); net.pump()
        assertTrue(a.messages.none { it.from == net.id("C") })
        assertTrue(net.nodes["A"]!!.rec.shown.isEmpty())
        // A 2.4 phone shows a goodbye as a bubble, and lets people react to it: nothing lands here
        val gone = bye("C", 60, net.now)
        a.onBytes("A>B", FakeNet.frame(gone)); net.pump()
        val line = a.message(gone.id)!!
        val react = FakeNet.envelope("B", Envelope.REACT, JSONObject().put("m", gone.id).put("e", "👍"), net.now)
        a.onBytes("A>B", FakeNet.frame(react)); net.pump()
        assertTrue(line.reactions.isEmpty())
        // ...nor one that arrived first and waited for its message
        val gone2 = bye("D", 70, net.now)
        a.onBytes("A>B", FakeNet.frame(FakeNet.envelope("B", Envelope.REACT, JSONObject().put("m", gone2.id).put("e", "❤️"), net.now))); net.pump()
        a.onBytes("A>B", FakeNet.frame(gone2)); net.pump()
        assertTrue(a.message(gone2.id)!!.reactions.isEmpty())
    }

    // ---------------------------------------------------------------- requests

    private fun url() = JSONObject().put("url", "https://example.org/page")

    /** A asks; B and C can both do it; the first pick takes it. Returns the request and (taker, the other). */
    private fun claimed(net: FakeNet, type: String, args: JSONObject, cap: Int): Triple<Errand, String, String> {
        net.node("A"); net.node("B"); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        for (h in listOf("B", "C")) net.nodes[h]!!.router.setCaps(cap)
        net.pump(); net.tickAll()
        val e = net.nodes["A"]!!.router.requestErrand(type, args); net.pump()
        net.advance(3_000)
        assertEquals(Errand.CLAIMED, e.status)
        val first = net.byId(e.helper!!).label
        return Triple(e, first, if (first == "B") "C" else "B")
    }

    @Test fun `a helper that leaves hands its request to the next phone at once`() {
        val net = FakeNet()
        val (e, first, second) = claimed(net, Errand.READ, url(), Errand.CAP_READ)
        leave(net, first)
        net.advance(30_000)                                            // far inside the 90 s a quiet helper keeps it
        assertEquals(net.id(second), e.helper)
        assertEquals(1, net.nodes[second]!!.rec.errands.size)
        assertTrue(net.id(first) in e.tried)
    }

    @Test fun `the goodbye alone hands it on, as the helper's no may never have arrived`() {
        val net = FakeNet()
        val (e, first, second) = claimed(net, Errand.READ, url(), Errand.CAP_READ)
        leave(net, first, decline = false)
        net.advance(30_000)
        assertEquals(net.id(second), e.helper)
        assertEquals(1, net.nodes[second]!!.rec.errands.size)
    }

    @Test fun `a text the leaver may already have sent is never sent again by someone else`() {
        val net = FakeNet()
        val text = JSONObject().put("to", "+919800000000").put("text", "reached camp")
        val (e, first, second) = claimed(net, Errand.SEND, text, Errand.CAP_SMS)
        net.nodes[first]!!.router.markSendOpened(e.id)                 // their messaging app is open on it
        leave(net, first)
        assertEquals("the asker decides, and is told at once", "quiet", e.why)
        assertEquals(Errand.CLAIMED, e.status)
        assertEquals(listOf(e.id), net.nodes["A"]!!.rec.answers.map { it.id })
        net.advance(Router.HUMAN_LEASE_MS)
        assertTrue(net.nodes[second]!!.rec.errands.isEmpty())
    }

    @Test fun `a text the leaver never opened goes to the next phone`() {
        val net = FakeNet()
        val text = JSONObject().put("to", "+919800000000").put("text", "reached camp")
        val (e, first, second) = claimed(net, Errand.SEND, text, Errand.CAP_SMS)
        leave(net, first)
        net.advance(40_000)
        assertEquals(net.id(second), e.helper)
        assertEquals(1, net.nodes[second]!!.rec.errands.size)
    }

    @Test fun `a request of someone who left is still done for them`() {
        val net = FakeNet()
        net.node("A"); net.node("B"); net.connect("A", "B")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_READ); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, url()); net.pump()
        // A asks, and leaves before anyone picks it up; the group still owes the answer (Archive)
        val archive = leave(net, "A")
        assertTrue(net.nodes["B"]!!.router.people[net.id("A")]!!.left)
        net.advance(40_000)
        val b = net.nodes["B"]!!
        assertEquals(listOf(e.id), b.rec.errands.map { it.id })
        assertTrue(b.router.completeErrand(e.id, true, "Page", JSONObject().put("t", "for when you are back"))); net.pump()
        // ...and it is there for A when A is back
        val a2 = rejoin(net, "A", archive, "B")
        val mine = a2.router.errands[e.id]!!
        assertEquals(Errand.DONE, mine.status)
        assertEquals("for when you are back", mine.answer()!!.getString("t"))
    }

    @Test fun `a text the leaver gave back goes on even when its no was lost - the goodbye says it again`() {
        val net = FakeNet()
        val text = JSONObject().put("to", "+919800000000").put("text", "reached camp")
        val (e, first, second) = claimed(net, Errand.SEND, text, Errand.CAP_SMS)
        net.dropEnvFrom = first; net.dropEnvFrames = 1                // the live "I can't" never arrives
        leave(net, first)
        assertNotEquals("not left to its asker as maybe sent", "quiet", e.why)
        net.advance(40_000)
        assertEquals(net.id(second), e.helper)
        assertEquals(1, net.nodes[second]!!.rec.errands.size)
    }

    @Test fun `back after leaving, a phone never takes up again a text it had open`() {
        val net = FakeNet()
        val text = JSONObject().put("to", "+919800000000").put("text", "reached camp")
        val (e, first, _) = claimed(net, Errand.SEND, text, Errand.CAP_SMS)
        net.nodes[first]!!.router.markSendOpened(e.id)
        val archive = leave(net, first)
        assertEquals("quiet", e.why)
        net.now += 3_600_000L
        // Back, able to text, and handed the request again by the group (as Core starts a router)
        val back = rejoin(net, first, archive, "A")
        back.router.setCaps(Errand.CAP_SMS); back.router.resumeErrands(); net.pump()
        net.advance(60_000)
        assertTrue("the family is not texted twice", back.rec.errands.isEmpty())
        // ...and it is what this phone noted on leaving that holds it back: without it, it would take it
        net.disconnect(first, "A"); net.nodes.remove(first)
        val forgot = copy(archive).also { it.remove("doneAt") }
        val again = rejoin(net, first, forgot, "A")
        again.router.setCaps(Errand.CAP_SMS); again.router.resumeErrands(); net.pump()
        net.advance(60_000)
        assertEquals(listOf(e.id), again.rec.errands.map { it.id })
    }

    @Test fun `a second goodbye sorts after the hello before it, whatever the clock did`() {
        val net = FakeNet(); net.line("A", "B", "C"); net.tickAll()
        val c = net.id("C")
        net.nodes["C"]!!.skew = 10 * 60_000L                          // fast when it leaves...
        val first = leave(net, "C")
        net.now += 2 * 60_000L
        val back = rejoin(net, "C", first, "B")                        // ...put right, back (its hello stamped after the goodbye)
        net.tickAll()
        net.now += 2 * 60_000L
        leave(net, "C")                                                // and gone again, two minutes later
        assertEquals(listOf(Message.MEMBER_LEFT, Message.MEMBER_JOINED, Message.MEMBER_LEFT), membership(net.nodes["A"]!!.router, c))
        assertTrue(net.nodes["A"]!!.router.people[c]!!.left)
        assertNotNull(back)
    }

    @Test fun `a goodbye is counted above anything of the leaver's still travelling, whatever their clock did`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!
        b.skew = 3_600_000L                                            // B's clock runs an hour fast...
        val photo = b.router.sendChat("sent while my clock ran fast"); net.pump()
        b.skew = 0L                                                    // ...and is put right before B leaves
        val q = run { b.router.sayGoodbye(); net.pump(); net.nodes["A"]!!.router.people[net.id("B")]!!.leftQ }
        assertTrue("above every stamp of B's", q > photo.ts)
        // So that message, handed on late, can't bring B back
        val fresh = FakeNet(); fresh.now = net.now; fresh.node("C"); fresh.node("D"); fresh.connect("C", "D")
        val c = fresh.nodes["C"]!!.router
        c.onBytes("C>D", FakeNet.fill(bye("B", q, net.now))); fresh.pump()
        c.onBytes("C>D", FakeNet.fill(FakeNet.envelope("B", Envelope.CHAT, JSONObject().put("text", "sent while my clock ran fast"), photo.ts))); fresh.pump()
        assertTrue(c.people[net.id("B")]!!.left)
    }
}
