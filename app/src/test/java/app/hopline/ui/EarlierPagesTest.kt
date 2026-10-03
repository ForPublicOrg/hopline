package app.hopline.ui

import app.hopline.data.History
import app.hopline.mesh.Envelope
import app.hopline.mesh.FakeNet
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import app.hopline.service.HistoryRules
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * An open chat's earlier messages, read back from the history: every message on screen once, none
 * that was on screen dropped — whatever is filed, deleted or read again underneath.
 */
class EarlierPagesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L
    private val line = Router.MAX_MESSAGES + Router.SPILL_BATCH

    private fun msg(id: String, ts: Long, to: String? = null, from: String = "ravi"): Message =
        Message(id, if (to == null) Envelope.CHAT else Envelope.DM, from, from, to, "words of $id", ts)

    private fun history() = History(File(tmp.root, "history/ab12cd34"))

    /** Segment [s] of a history: sixty group messages, older in a lower segment. */
    private fun segment(s: Int): List<Message> = (1..60).map { msg("g$s.%02d".format(it), t0 + s * 100_000L + it) }
    private fun file(h: History, messages: List<Message>) = assertTrue(h.append(messages.map { it.toJson() }))

    /** One page as Core reads it for the chat screen: the messages, and where the page above starts. */
    private fun page(h: History, before: Int?, chat: String = Message.GROUP_CHAT): Pair<List<Message>, Int> {
        val walk = HistoryRules.Walk(h, chat, "A", before)
        while (!walk.step()) { /* a few segments at a time */ }
        return walk.messages() to walk.next
    }

    /** What the screen does when the history changed: read again until told to stop, then swap. Returns the pages read. */
    private fun readAgain(pages: EarlierPages, h: History, re: EarlierPages.Reload): Int {
        var read = 0
        do { val (messages, next) = page(h, re.next); read++ } while (pages.take(re, messages, next))
        pages.finish(re)
        return read
    }

    private fun phone(messages: List<Message> = emptyList(), now: Long = t0 + 50_000_000L): Router {
        val net = FakeNet().also { it.now = now }
        return net.node("A").router.also { it.restore(JSONObject().put("messages", JSONArray(messages.map { m -> m.toJson() }))) }
    }

    private fun ids(list: List<Message>) = list.map { it.id }

    /** One redraw of the chat screen: keep up with the live window, then put the chat together. */
    private fun EarlierPages.on(r: Router, peer: String? = null, keep: Boolean = true): List<Message> {
        val live = r.chatMessages(peer)
        follow(live, r) { keep }
        return shown(live, r)
    }

    // ---------------------------------------------------------------- pages above the live window

    @Test fun withNothingEarlierTheChatIsTheLiveWindowUntouched() {
        val r = phone((1..5).map { msg("m$it", t0 + it) })
        val pages = EarlierPages()
        val live = r.chatMessages(null)
        pages.follow(live, r)
        assertSame(live, pages.shown(live, r))
        assertNull(pages.next); assertNull(pages.depth); assertNull(pages.restart())
        assertFalse(pages.exhausted)
    }

    @Test fun pagesGoAboveTheLiveMessagesOldestFirst() {
        val h = history()
        for (s in 1..4) file(h, segment(s))
        val r = phone(segment(5))
        val pages = EarlierPages()
        val (first, next) = page(h, null)
        pages.add(first, next)
        assertEquals(ids(segment(3) + segment(4) + segment(5)), ids(pages.on(r)))
        assertEquals(3, pages.next)
        val (second, last) = page(h, pages.next)
        pages.add(second, last)
        assertEquals(ids((1..5).flatMap { segment(it) }), ids(pages.on(r)))
        assertTrue("nothing older", pages.exhausted)
        assertEquals("g1.07", pages.find("g1.07")?.id)
        assertNull("a live message is the router's to find", pages.find("g5.07"))
    }

    @Test fun aMessageThatIsInTwoPlacesIsShownOnce() {
        // Killed between filing a batch and saving the state: the batch is filed again, and its
        // messages are in two segments — and for a moment in the live window too.
        val h = history()
        file(h, segment(1)); file(h, segment(2)); file(h, segment(2)); file(h, segment(3))
        val r = phone(segment(3) + segment(4))
        val pages = EarlierPages()
        var before: Int? = null
        do { val (messages, next) = page(h, before); pages.add(messages, next); before = next } while (next != 0)
        val shown = ids(pages.on(r))
        assertEquals(ids((1..4).flatMap { segment(it) }), shown)
        assertEquals(shown.size, shown.toSet().size)
    }

    @Test fun aLateFiledMessageSortsIntoItsPlaceAmongTheLiveOnes() {
        // A message of mine still trying to go out stays in the live window while newer ones are filed.
        val waiting = msg("mine", t0 + 5, from = "A")
        val r = phone(listOf(waiting, msg("new1", t0 + 100), msg("new2", t0 + 200)))
        val pages = EarlierPages()
        pages.add(listOf(msg("old1", t0 + 1), msg("old2", t0 + 10), msg("old3", t0 + 50)), 0)
        assertEquals(listOf("old1", "mine", "old2", "old3", "new1", "new2"), ids(pages.on(r)))
    }

    @Test fun aPrivateChatReadsOnlyItsOwnEarlierMessages() {
        val h = history()
        file(h, listOf(msg("g1", t0 + 1), msg("p1", t0 + 2, to = "A"), msg("q1", t0 + 3, to = "A", from = "meera"), msg("p2", t0 + 4, to = "ravi", from = "A")))
        val r = phone(listOf(msg("p3", t0 + 10, to = "A"), msg("g2", t0 + 11)))
        val pages = EarlierPages()
        val (messages, next) = page(h, null, chat = "ravi")
        pages.add(messages, next)
        assertEquals(listOf("p1", "p2", "p3"), ids(pages.on(r, "ravi")))
    }

    // ---------------------------------------------------------------- deleting

    @Test fun anEarlierMessageDeletedHereGoesAtOnceAndAPageReadBeforeCannotBringItBack() {
        val h = history()
        file(h, segment(1)); file(h, segment(2))
        val r = phone(segment(3))
        val pages = EarlierPages()
        val (newest, next) = page(h, null)          // both segments: sixty is under a page
        pages.remove(listOf("g2.05"))               // deleted while that page was still on its way
        pages.add(newest, next)
        assertFalse("g2.05" in ids(pages.on(r)))
        pages.remove(listOf("g1.01", "g2.60"))
        val shown = ids(pages.on(r))
        assertEquals(ids(segment(1) + segment(2) + segment(3)).filter { it != "g2.05" && it != "g1.01" && it != "g2.60" }, shown)
        assertNull(pages.find("g1.01"))
        // …nor can reading the pages again, from a history the delete has not reached yet
        val re = pages.restart()!!
        readAgain(pages, h, re)
        assertEquals(shown, ids(pages.on(r)))
    }

    @Test fun aLiveMessageDeletedIsNotKeptAsIfItHadBeenFiled() {
        val r = phone(segment(1))
        val pages = EarlierPages()
        pages.on(r)
        r.hideMessages(listOf("g1.03", "g1.04"))
        val shown = ids(pages.on(r))
        assertEquals(ids(segment(1)).filter { it != "g1.03" && it != "g1.04" }, shown)
        assertNull(pages.depth)
    }

    @Test fun aMessageDeletedFromBothPlacesStaysGone() {
        // In the live window and (filed twice over) in the history: deleting it hides the live one,
        // and the history's copy must not step into its place.
        val h = history()
        file(h, segment(1) + segment(2).take(3))
        val r = phone(segment(2))
        val pages = EarlierPages()
        val (messages, next) = page(h, null)
        pages.add(messages, next)
        r.hideMessages(listOf("g2.02"))
        assertEquals(ids(segment(1) + segment(2)).filter { it != "g2.02" }, ids(pages.on(r)))
    }

    @Test fun clearingTheChatForgetsEveryPage() {
        val h = history()
        file(h, segment(1))
        val r = phone(segment(2))
        val pages = EarlierPages()
        val (messages, next) = page(h, null)
        pages.add(messages, next)
        pages.remove(listOf("g1.01"))
        pages.clear()
        val live = r.chatMessages(null)
        pages.follow(live, r)
        assertSame(live, pages.shown(live, r))
        assertNull(pages.next); assertNull(pages.depth); assertTrue(pages.isEmpty)
    }

    @Test fun pagesLetGoAreReadAgainOnTheWayUpWithoutWhatWasDeletedHere() {
        val h = history()
        for (s in 1..3) file(h, segment(s))
        val r = phone(segment(4))
        val pages = EarlierPages()
        val (messages, next) = page(h, null)
        pages.add(messages, next)
        pages.remove(listOf("g3.10"))
        val stale = page(h, null)                     // read before the delete reached the history
        pages.unload()
        val live = r.chatMessages(null)
        pages.follow(live, r)
        assertSame(live, pages.shown(live, r))
        assertNull(pages.next); assertNull(pages.depth); assertNull(pages.restart())
        pages.add(stale.first, stale.second)
        assertEquals(ids(segment(2) + segment(3) + segment(4)).filter { it != "g3.10" }, ids(pages.on(r)))
        // …and the live window is still followed: what leaves it next is still noticed
        r.hideMessages(listOf("g4.01"))
        assertFalse("g4.01" in ids(pages.on(r)))
    }

    // ---------------------------------------------------------------- the history changes under an open chat

    @Test fun aBatchFiledWhileTheChatIsOpenNeverLeavesTheScreen() {
        val all = (0 until line).map { msg("g%05d".format(it), t0 + it * 1000L) }
        val r = phone(all, now = t0 + line * 1000L + 60_000)
        val h = history()
        val pages = EarlierPages()
        assertEquals(ids(all), ids(pages.on(r)))
        // the live window is over its size: the oldest two hundred move out of it…
        r.tick()
        assertEquals(Router.MAX_MESSAGES, r.chatMessages(null).size)
        assertEquals("…and stay on screen while they are on their way to the history", ids(all), ids(pages.on(r)))
        assertEquals(EarlierPages.NEWEST_ONLY, pages.depth)
        // the segment is written, the router lets go of the batch, and the screen reads its pages again
        val batch = HistoryRules.offer(r)
        file(h, batch)
        HistoryRules.settle(r, batch)
        assertEquals(ids(all), ids(pages.on(r)))
        val re = pages.restart()
        assertNotNull(re)
        assertEquals("one page: the new segment holds everything that was being kept", 1, readAgain(pages, h, re!!))
        val shown = ids(pages.on(r))
        assertEquals(ids(all), shown)
        assertEquals(shown.size, shown.toSet().size)
        assertTrue(pages.exhausted)
        // the screen is re-created: it reads the same messages back, and its list lines up row for row
        val again = EarlierPages()
        readAgain(again, h, again.reread(pages.depth!!))
        assertEquals(ids(all), ids(again.on(r)))
    }

    @Test fun aBatchFiledFarAboveWhatIsBeingReadJustGoesOffTheTopOfTheList() {
        val all = (0 until line).map { msg("g%05d".format(it), t0 + it * 1000L) }
        val r = phone(all, now = t0 + line * 1000L + 60_000)
        val h = history()
        val pages = EarlierPages()
        pages.on(r)
        r.tick()
        // the person is at the newest messages: the screen says the oldest need not be kept
        var asked = 0
        val live = r.chatMessages(null)
        pages.follow(live, r) { asked++; false }
        assertEquals("asked once, not once per message", 1, asked)
        assertSame(live, pages.shown(live, r))
        assertNull(pages.depth); assertNull(pages.restart())
        // nothing left the window since: nobody is asked anything
        pages.follow(live, r) { asked++; true }
        assertEquals(1, asked)
        assertSame(live, pages.shown(live, r))
        // they are in the history like any earlier message, and come back when the chat is scrolled up
        val batch = HistoryRules.offer(r)
        file(h, batch)
        HistoryRules.settle(r, batch)
        val (messages, next) = page(h, null)
        pages.add(messages, next)
        assertEquals(ids(all), ids(pages.on(r)))
    }

    @Test fun readingAgainGoesAsFarBackAsThePagesOnScreenDidAndNoFurther() {
        val h = history()
        for (s in 1..8) file(h, segment(s))
        val r = phone(segment(10))
        val pages = EarlierPages()
        repeat(2) { val (messages, next) = page(h, pages.next); pages.add(messages, next) }
        assertEquals(5, pages.next)
        val before = ids(pages.on(r))
        assertEquals(ids((5..8).flatMap { segment(it) } + segment(10)), before)
        // a batch is filed on top of them
        file(h, segment(9))
        val re = pages.restart()!!
        assertEquals(5, re.downTo)
        assertEquals("until then the screen shows what it showed", before, ids(pages.on(r)))
        assertEquals(3, readAgain(pages, h, re))
        val after = ids(pages.on(r))
        assertTrue("nothing that was on screen is gone", after.containsAll(before))
        assertTrue("the new segment is there", after.containsAll(ids(segment(9))))
        assertEquals(ids((4..10).flatMap { segment(it) }), after)
        assertEquals("paging carries on from where the reading stopped", 4, pages.next)
        assertFalse("the bottom of the history was not read for it", "g1.01" in after)
    }

    @Test fun theHistoryChangingAgainMidReadStartsTheSameReadingOver() {
        val h = history()
        for (s in 1..6) file(h, segment(s))
        val r = phone(segment(8))
        val pages = EarlierPages()
        val (messages, next) = page(h, null)
        pages.add(messages, next)                      // segments 6 and 5
        val first = pages.restart()!!
        val (p1, n1) = page(h, first.next)
        assertFalse(pages.take(first, p1, n1))         // deep enough — but before it is swapped in:
        file(h, segment(7))
        val second = first.again()
        assertEquals(first.downTo, second.downTo)
        assertNull("from the newest segment again", second.next)
        readAgain(pages, h, second)
        assertEquals(ids((4..8).flatMap { segment(it) }), ids(pages.on(r)))
    }

    @Test fun whatWasDeletedFromTheHistoryElsewhereGoesWhenThePagesAreReadAgain() {
        val h = history()
        for (s in 1..3) file(h, segment(s))
        val r = phone(segment(4))
        val pages = EarlierPages()
        var before: Int? = null
        do { val (messages, next) = page(h, before); pages.add(messages, next); before = next } while (next != 0)
        // "Clear chat" from the group's info screen, say: this screen deleted nothing itself
        h.removeWhere { it.optString("id").startsWith("g2.") }
        readAgain(pages, h, pages.restart()!!)
        assertEquals(ids(segment(1) + segment(3) + segment(4)), ids(pages.on(r)))
        // everything gone: the chat is its live window again, and a re-created screen has nothing to read back
        h.deleteAll()
        readAgain(pages, h, pages.restart()!!)
        val live = r.chatMessages(null)
        pages.follow(live, r)
        assertSame(live, pages.shown(live, r))
        assertNull(pages.depth)
        assertTrue(pages.exhausted)
    }

    @Test fun aRecreatedScreenReadsBackDownToWhereItWas() {
        val h = history()
        for (s in 1..8) file(h, segment(s))
        val r = phone(segment(9))
        val pages = EarlierPages()
        repeat(2) { val (messages, next) = page(h, pages.next); pages.add(messages, next) }
        val depth = pages.depth!!
        val again = EarlierPages()
        assertEquals(2, readAgain(again, h, again.reread(depth)))
        assertEquals(ids(pages.on(r)), ids(again.on(r)))
        assertEquals(pages.next, again.next)
        // read to the very top before: read to the very top again
        while (!pages.exhausted) { val (messages, next) = page(h, pages.next); pages.add(messages, next) }
        assertEquals(0, pages.depth)
        val whole = EarlierPages()
        readAgain(whole, h, whole.reread(0))
        assertEquals(ids((1..9).flatMap { segment(it) }), ids(whole.on(r)))
    }
}
