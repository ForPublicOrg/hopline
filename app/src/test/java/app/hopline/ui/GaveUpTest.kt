package app.hopline.ui

import app.hopline.data.Upgrade
import app.hopline.mesh.Archive
import app.hopline.mesh.Envelope
import app.hopline.mesh.FakeNet
import app.hopline.mesh.Message
import app.hopline.mesh.RoleChange
import app.hopline.mesh.Router
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Not sent" is said the moment it is true: when this phone has nothing left to send. */
class GaveUpTest {
    private val hour = 3_600_000L

    @Test fun aWaitingMessageStillCarriedKeepsTryingForItsTwoDays() {
        val net = FakeNet(); val a = net.node("A")
        val m = a.router.sendChat("anyone?")!!
        assertEquals(Message.QUEUED, m.status)
        assertFalse(Ui.gaveUp(a.router, m, net.now + hour))
        assertFalse(Ui.gaveUp(a.router, m, net.now + Router.CARRY_MS - hour))
        assertTrue(Ui.gaveUp(a.router, m, net.now + Router.CARRY_MS + 1))
        // once a friend has it, it is simply sent
        net.node("B"); net.connect("A", "B")
        assertEquals(Message.SENT, m.status)
        assertFalse(Ui.gaveUp(a.router, m, net.now + 400 * 24 * hour))
    }

    @Test fun aMessageThatWasWaitingWhenILeftReadsNotSentAtOnce() {
        val net = FakeNet(); val a = net.node("A")
        val m = a.router.sendChat("never left my phone")!!
        val whenILeft = net.now + hour
        val archive = Archive.strip(a.router.snapshot(), a.id, "A", whenILeft, whenILeft)
        // in the kept chat, a minute after leaving…
        val kept = FakeNet().node("A"); kept.router.restore(JSONObject(archive.toString()))
        val there = kept.router.message(m.id)!!
        assertEquals(Message.QUEUED, there.status)
        assertTrue(Ui.gaveUp(kept.router, there, whenILeft + 60_000))
        // …and after joining again, even with a friend in range: only "Send again" sends it
        val back = net.node("A"); back.router.restore(JSONObject(archive.toString()))
        net.node("B"); net.connect("A", "B")
        net.now += Router.SYNC_MS + 1_000; net.tickAll()
        assertTrue(Ui.gaveUp(back.router, back.router.message(m.id)!!, net.now))
        assertFalse(net.texts("B").contains("never left my phone"))
    }

    @Test fun whyAMessageWasNotSentIsToldApartLeavingFromTimingOut() {
        val net = FakeNet(); val a = net.node("A")
        val m = a.router.sendChat("still trying")!!
        // waiting and still carried: it hasn't stopped at all
        assertFalse(Ui.unsentByLeaving(a.router, m, net.now + hour))
        // its two days ran out with no phone in range: "not sent", but not because of any leaving
        net.now += Router.CARRY_MS + hour; a.router.tick()
        assertFalse(a.router.carries(m.id))
        assertTrue(Ui.gaveUp(a.router, m, net.now))
        assertFalse(Ui.unsentByLeaving(a.router, m, net.now))
        // one that was waiting when I left is let go at once — and says so, in the kept chat and after a rejoin
        val net2 = FakeNet(); val a2 = net2.node("A")
        val waiting = a2.router.sendChat("never left my phone")!!
        val whenILeft = net2.now + hour
        val kept = FakeNet().also { it.now = whenILeft }.node("A")
        kept.router.restore(JSONObject(Archive.strip(a2.router.snapshot(), a2.id, "A", whenILeft, whenILeft).toString()))
        val there = kept.router.message(waiting.id)!!
        assertTrue(Ui.unsentByLeaving(kept.router, there, whenILeft + 60_000))
        assertTrue(Ui.unsentByLeaving(kept.router, there, waiting.ts + Router.CARRY_MS))
        // past its two days the two can't be told apart, and "no phone came in range in 48 hours" is true of both
        assertFalse(Ui.unsentByLeaving(kept.router, there, waiting.ts + Router.CARRY_MS + 1))
        assertTrue(Ui.gaveUp(kept.router, there, waiting.ts + Router.CARRY_MS + 1))
    }

    @Test fun whatTheUpdateLetGoOfIsNotBlamedOnLeaving() {
        val net = FakeNet(); val a = net.node("A")
        val old = "k7m2p9qa"; val bea = "b3a4b3a4"           // this phone, and a friend, before 2.4
        fun queued(id: String, to: String?) = Message(id, if (to == null) Envelope.CHAT else Envelope.DM, old, "A", to, "written on 2.3", net.now - 3 * hour)
            .also { it.status = Message.QUEUED }.toJson()
        // 2.3 saved a private message and a group message, both still waiting (the group one's envelope already gone)
        val saved = JSONObject().put("messages", JSONArray(listOf(queued("dm0000000001", bea), queued("gm0000000001", null))))
        a.router.restore(JSONObject(Upgrade.state(saved, a.id, setOf(old), net.now, left = false)!!.toString()))
        a.router.reissueQueued()
        val dm = a.router.message("dm0000000001")!!; val gm = a.router.message("gm0000000001")!!
        for (m in listOf(dm, gm)) {
            assertTrue(m.id, Ui.gaveUp(a.router, m, net.now))
            assertFalse("I never left: ${m.id}", Ui.unsentByLeaving(a.router, m, net.now))
            assertTrue(m.id, Ui.unsentByUpdate(a.router, m, net.now))
        }
        assertEquals(Ui.NotSent.UPDATE_PRIVATE, Ui.notSent(a.router, dm, net.now))
        assertEquals(Ui.NotSent.UPDATE, Ui.notSent(a.router, gm, net.now))
        // one written since, waiting when I left: that one is the leaving's doing, and so are the old ones now
        val m = a.router.sendChat("written on 2.4")!!
        assertFalse(Ui.unsentByUpdate(a.router, m, net.now))
        val whenILeft = net.now + hour
        val back = FakeNet().also { it.now = whenILeft }.node("A")
        back.router.restore(JSONObject(Archive.strip(a.router.snapshot(), a.id, "A", whenILeft, whenILeft).toString()))
        for (id in listOf(m.id, dm.id, gm.id)) {
            val there = back.router.message(id)!!
            assertEquals(id, Ui.NotSent.LEFT, Ui.notSent(back.router, there, whenILeft + 60_000))
            assertFalse(id, Ui.unsentByUpdate(back.router, there, whenILeft + 60_000))
        }
        // past its two days, "no phone came in range within 48 hours" is true of the one written since…
        assertEquals(Ui.NotSent.TIMED_OUT, Ui.notSent(back.router, back.router.message(m.id)!!, m.ts + Router.CARRY_MS + 1))
        // …while the old ones still hadn't gone out when Hopline was updated
        assertEquals(Ui.NotSent.UPDATE, Ui.notSent(back.router, back.router.message(gm.id)!!, gm.ts + Router.CARRY_MS + 1))
        assertEquals(Ui.NotSent.UPDATE_PRIVATE, Ui.notSent(back.router, back.router.message(dm.id)!!, dm.ts + Router.CARRY_MS + 1))
    }

    @Test fun whatTheUpdateLetGoOfIsTheUpdatesDoingAtAnyAge() {
        val net = FakeNet(); val a = net.node("A")
        val old = "k7m2p9qa"; val bea = "b3a4b3a4"
        fun queued(id: String, to: String?, ago: Long) = Message(id, if (to == null) Envelope.CHAT else Envelope.DM, old, "A", to, "written on 2.3", net.now - ago)
            .also { it.status = Message.QUEUED }.toJson()
        // Written an hour before the update with nobody in range, and one whose two days had already run out by then
        val saved = JSONObject().put("messages", JSONArray(listOf(queued("dm0000000003", bea, hour), queued("gm0000000003", null, hour),
            queued("gm0000000004", null, 3 * 24 * hour))))
        a.router.restore(JSONObject(Upgrade.state(saved, a.id, setOf(old), net.now, left = false)!!.toString()))
        a.router.reissueQueued()
        val dm = a.router.message("dm0000000003")!!; val gm = a.router.message("gm0000000003")!!; val older = a.router.message("gm0000000004")!!
        // the update's doing at once, and still once 48 hours have passed since it was written, and a week on:
        // "no phone came in range within 48 hours" would not be true of the first two
        for (later in listOf(0L, Router.CARRY_MS - hour + 60_000, 7 * 24 * hour)) {
            for (m in listOf(dm, gm, older)) assertTrue("${m.id} +$later", Ui.gaveUp(a.router, m, net.now + later))
            assertEquals("+$later", Ui.NotSent.UPDATE_PRIVATE, Ui.notSent(a.router, dm, net.now + later))
            assertEquals("+$later", Ui.NotSent.UPDATE, Ui.notSent(a.router, gm, net.now + later))
            assertEquals("+$later", Ui.NotSent.UPDATE, Ui.notSent(a.router, older, net.now + later))
        }
    }

    @Test fun aMessageOfMineLetGoOfForNoReasonAboveIsSaidToBeJustThat() {
        val net = FakeNet(); val a = net.node("A")
        val m = a.router.sendChat("still trying")!!
        // no "You left" after it, written on this version, and no envelope any more (a phone too full to carry it)
        val state = a.router.snapshot().put("carry", JSONArray()).put("born", JSONObject())
        val again = FakeNet().also { it.now = net.now }.node("A"); again.router.restore(JSONObject(state.toString()))
        val there = again.router.message(m.id)!!
        assertFalse(Ui.unsentByLeaving(again.router, there, net.now + hour))
        assertFalse(Ui.unsentByUpdate(again.router, there, net.now + hour))
        assertEquals(Ui.NotSent.LET_GO, Ui.notSent(again.router, there, net.now + hour))
    }

    @Test fun aGroupMessageFromBeforeTheUpdateIsNotMeasuredAgainstTodaysGroup() {
        val net = FakeNet(); val a = net.node("A")
        val old = "k7m2p9qa"
        val before = Message("gm0000000002", Envelope.CHAT, old, "A", null, "made it to camp", net.now - 3 * hour)
        val saved = JSONObject().put("messages", JSONArray(listOf(before.toJson())))
        a.router.restore(JSONObject(Upgrade.state(saved, a.id, setOf(old), net.now, left = false)!!.toString()))
        assertTrue(Ui.beforeUpdate(a.router, a.router.message(before.id)!!))
        assertFalse(Ui.beforeUpdate(a.router, a.router.sendChat("made it again")!!))
        // someone else's message is never "mine from before"
        net.node("B"); net.connect("A", "B"); net.nodes["B"]!!.router.sendChat("hi"); net.pump()
        assertFalse(Ui.beforeUpdate(a.router, a.router.messages.first { it.from == net.id("B") }))
    }

    @Test fun onlyMyOwnUnsentMessagesAreEverCalledUnsentByLeaving() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        net.nodes["B"]!!.router.sendChat("hello"); net.pump()
        val sent = a.sendChat("got through")!!; net.pump()
        assertEquals(Message.SENT, sent.status)
        val whenILeft = net.now + hour
        val kept = FakeNet().also { it.now = whenILeft }.node("A")
        kept.router.restore(JSONObject(Archive.strip(a.snapshot(), a.me.id, "A", whenILeft, whenILeft).toString()))
        for (m in kept.router.messages) assertFalse(m.id, Ui.unsentByLeaving(kept.router, m, whenILeft + 60_000))
        assertTrue("…the \"You left\" line included", kept.router.messages.any { it.kind == Message.LEFT })
    }

    @Test fun aPrivateMessageNobodyConfirmedStillGetsItsHourOfGrace() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        net.know("A", "Z")
        val m = a.sendDm(net.id("Z"), "are you there?")!!; net.pump()          // B took it; Z never confirmed
        assertEquals(Message.SENT, m.status)
        assertFalse(Ui.gaveUp(a, m, net.now + Router.CARRY_MS + hour - 1))
        assertTrue(Ui.gaveUp(a, m, net.now + Router.CARRY_MS + hour + 1))
    }

    @Test fun leavingIsBlamedOnlyForWhatLeavingCutShort() {
        val leftAt = 1_700_000_000_000L
        fun group(sentBefore: Long) = Message("g", Envelope.CHAT, "A", "A", null, "to everyone", leftAt - sentBefore)
        fun private(sentBefore: Long) = Message("p", Envelope.DM, "A", "A", "B", "just you", leftAt - sentBefore)
        // Sent an hour before I left: who got it was still being found out. Leaving cut that short.
        assertFalse(Ui.settledBeforeLeaving(group(hour), leftAt))
        assertFalse(Ui.settledBeforeLeaving(group(Router.CARRY_MS), leftAt))
        // Sent more than 48 hours before: nothing was carrying it any more. What was known then is all there ever was.
        assertTrue(Ui.settledBeforeLeaving(group(Router.CARRY_MS + 1), leftAt))
        assertTrue(Ui.settledBeforeLeaving(group(7 * 24 * hour), leftAt))
        // A private message gets the same hour's grace as in a live chat: it turned "Not delivered" at 49 hours.
        assertFalse(Ui.settledBeforeLeaving(private(Router.CARRY_MS + 1), leftAt))
        assertFalse(Ui.settledBeforeLeaving(private(Router.CARRY_MS + hour), leftAt))
        assertTrue(Ui.settledBeforeLeaving(private(Router.CARRY_MS + hour + 1), leftAt))
    }

    @Test fun aPrivateMessageAlreadyNotDeliveredBeforeILeftIsTheSameMessagesLiveChatCalledNotDelivered() {
        // The kept chat must not turn a "Not delivered" back into a tick: both go by the same 49 hours.
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        net.know("A", "Z")
        val m = a.sendDm(net.id("Z"), "are you there?")!!; net.pump()          // B took it; Z never confirmed
        for (after in listOf(Router.CARRY_MS + hour - 1, Router.CARRY_MS + hour + 1, 7 * 24 * hour)) {
            val leftAt = m.ts + after
            assertEquals("left $after ms after sending", Ui.gaveUp(a, m, leftAt), Ui.settledBeforeLeaving(m, leftAt))
        }
    }

    @Test fun otherPeoplesMessagesAndNoticesNeverGiveUp() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        net.nodes["B"]!!.router.sendChat("hello"); net.pump()
        val theirs = a.messages.single { it.kind == Envelope.CHAT }
        val left = a.addLocalNotice(Message.LEFT)!!
        val far = net.now + 400 * 24 * hour
        assertFalse(Ui.gaveUp(a, theirs, far))
        assertFalse(Ui.gaveUp(a, left, far))
    }

    // ---------------------------------------------------------------- only admins may send

    /** A started the group and B joined it: they met, so B knows who started it, and aren't linked now. */
    private fun group(): FakeNet {
        val net = FakeNet(); val a = net.node("A"); net.node("B")
        a.router.foundIfDue(sure = true, ts = net.now)
        net.connect("A", "B"); net.disconnect("A", "B")
        return net
    }

    @Test fun aQueuedGroupMessageWithdrawnWhenOnlyAdminsCouldSendSaysSoNotLetGo() {
        val net = group(); val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router
        val restriction = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }
        net.now += 10 * 60_000
        val m = b.sendChat("anyone around?")!!          // nobody linked: waiting
        FakeNet.hand(b, restriction)
        // withdrawn the moment B learns: still ◷, and nothing of it is carried any more
        assertEquals(Message.QUEUED, m.status)
        assertFalse(b.carries(m.id))
        assertTrue(Ui.gaveUp(b, m, net.now))
        // "this phone let it go" is true, and not why: only admins could send by the time it would have gone out
        assertEquals(Ui.NotSent.ADMINS_ONLY, Ui.notSent(b, m, net.now))
        assertEquals(Ui.NotSent.ADMINS_ONLY, Ui.notSent(b, m, m.ts + Router.CARRY_MS))
        // and it isn't offered again while that holds
        assertFalse(ChatRules.sendAgain(Ui.gaveUp(b, m, net.now), m.to, canWrite = true, canPost = b.mayPost()))
    }

    @Test fun onlyAdminsIsToldBeforeLeavingWhenItCameFirst() {
        val net = group(); val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router
        val early = b.sendChat("before the change")!!   // allowed: it waits on, as any post does
        net.now += 10 * 60_000
        val restriction = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }
        net.now += 10 * 60_000
        val late = b.sendChat("after it")!!
        FakeNet.hand(b, restriction)
        assertTrue(b.carries(early.id)); assertFalse(b.carries(late.id))
        // then I leave, both still ◷: the leaving lets go of the one that was still waiting
        val whenILeft = net.now + hour
        val kept = FakeNet().also { it.now = whenILeft }.node("B")
        kept.router.restore(JSONObject(Archive.strip(b.snapshot(), b.me.id, "B", whenILeft, whenILeft).toString()))
        val keptLate = kept.router.message(late.id)!!
        val keptEarly = kept.router.message(early.id)!!
        assertTrue("…and there is a \"You left\" after the withdrawn one too", Ui.unsentByLeaving(kept.router, keptLate, whenILeft + 60_000))
        assertEquals(Ui.NotSent.ADMINS_ONLY, Ui.notSent(kept.router, keptLate, whenILeft + 60_000))
        assertEquals(Ui.NotSent.LEFT, Ui.notSent(kept.router, keptEarly, whenILeft + 60_000))
    }

    @Test fun aMessageThatLeftThePhoneBeforeTheChangeIsNeverCalledWithdrawn() {
        val net = group(); val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router
        net.node("C"); net.connect("B", "C")
        val restriction = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }
        net.now += 10 * 60_000
        val m = b.sendChat("on its way")!!; net.pump()
        assertEquals(Message.SENT, m.status)
        FakeNet.hand(b, restriction)
        // only admins could send when it was written, as B knows now — but it had already gone
        assertTrue(b.postBarred(m))
        assertEquals(Message.SENT, m.status)
        assertTrue(b.carries(m.id))
        for (later in listOf(0L, Router.CARRY_MS + hour, 30 * 24 * hour)) {
            assertFalse("+$later", Ui.gaveUp(b, m, net.now + later))
            assertNotEquals("+$later", Ui.NotSent.ADMINS_ONLY, Ui.notSent(b, m, net.now + later))
        }
    }

    @Test fun aPrivateMessageIsNeverWithdrawnForTheGroupsSetting() {
        val net = group(); val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router
        net.know("B", "C")
        val restriction = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }
        net.now += 10 * 60_000
        val dm = b.sendDm(net.id("C"), "just you")!!          // nobody linked: waiting
        FakeNet.hand(b, restriction)
        assertFalse(b.mayPost())
        assertEquals(Message.QUEUED, dm.status)
        assertTrue("still on its way to C", b.carries(dm.id))
        assertFalse(b.postBarred(dm))
        assertFalse(Ui.gaveUp(b, dm, net.now))
        assertTrue("one written now goes too", b.carries(b.sendDm(net.id("C"), "still here")!!.id))
        // let go early for another reason (a phone too full to carry it): that, and not the group's setting, is why
        val state = b.snapshot().put("carry", JSONArray()).put("born", JSONObject())
        val again = FakeNet().also { it.now = net.now }.node("B"); again.router.restore(JSONObject(state.toString()))
        assertFalse(again.router.mayPost())
        assertEquals(Ui.NotSent.LET_GO, Ui.notSent(again.router, again.router.message(dm.id)!!, net.now + hour))
    }
}
