package app.hopline.ui

import app.hopline.mesh.Archive
import app.hopline.mesh.Envelope
import app.hopline.mesh.FakeNet
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Not sent" is said the moment it is true: when this phone has nothing left to send. */
class GaveUpTest {
    private val hour = 3_600_000L

    @Test fun aWaitingMessageStillCarriedKeepsTryingForItsTwoDays() {
        val net = FakeNet(); val a = net.node("A")
        val m = a.router.sendChat("anyone?")
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
        val m = a.router.sendChat("never left my phone")
        val whenILeft = net.now + hour
        val archive = Archive.strip(a.router.snapshot(), "A", "A", whenILeft, whenILeft)
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
        val m = a.router.sendChat("still trying")
        // waiting and still carried: it hasn't stopped at all
        assertFalse(Ui.unsentByLeaving(a.router, m, net.now + hour))
        // its two days ran out with no phone in range: "not sent", but not because of any leaving
        net.now += Router.CARRY_MS + hour; a.router.tick()
        assertFalse(a.router.carries(m.id))
        assertTrue(Ui.gaveUp(a.router, m, net.now))
        assertFalse(Ui.unsentByLeaving(a.router, m, net.now))
        // one that was waiting when I left is let go at once — and says so, in the kept chat and after a rejoin
        val net2 = FakeNet(); val a2 = net2.node("A")
        val waiting = a2.router.sendChat("never left my phone")
        val whenILeft = net2.now + hour
        val kept = FakeNet().also { it.now = whenILeft }.node("A")
        kept.router.restore(JSONObject(Archive.strip(a2.router.snapshot(), "A", "A", whenILeft, whenILeft).toString()))
        val there = kept.router.message(waiting.id)!!
        assertTrue(Ui.unsentByLeaving(kept.router, there, whenILeft + 60_000))
        assertTrue(Ui.unsentByLeaving(kept.router, there, waiting.ts + Router.CARRY_MS))
        // past its two days the two can't be told apart, and "no phone came in range in 48 hours" is true of both
        assertFalse(Ui.unsentByLeaving(kept.router, there, waiting.ts + Router.CARRY_MS + 1))
        assertTrue(Ui.gaveUp(kept.router, there, waiting.ts + Router.CARRY_MS + 1))
    }

    @Test fun onlyMyOwnUnsentMessagesAreEverCalledUnsentByLeaving() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        net.nodes["B"]!!.router.sendChat("hello"); net.pump()
        val sent = a.sendChat("got through"); net.pump()
        assertEquals(Message.SENT, sent.status)
        val whenILeft = net.now + hour
        val kept = FakeNet().also { it.now = whenILeft }.node("A")
        kept.router.restore(JSONObject(Archive.strip(a.snapshot(), "A", "A", whenILeft, whenILeft).toString()))
        for (m in kept.router.messages) assertFalse(m.id, Ui.unsentByLeaving(kept.router, m, whenILeft + 60_000))
        assertTrue("…the \"You left\" line included", kept.router.messages.any { it.kind == Message.LEFT })
    }

    @Test fun aPrivateMessageNobodyConfirmedStillGetsItsHourOfGrace() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        val m = a.sendDm("Z", "are you there?"); net.pump()          // B took it; Z never confirmed
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
        val m = a.sendDm("Z", "are you there?"); net.pump()          // B took it; Z never confirmed
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
}
