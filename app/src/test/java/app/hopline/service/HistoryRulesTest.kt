package app.hopline.service

import app.hopline.data.History
import app.hopline.mesh.Envelope
import app.hopline.mesh.FakeNet
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Between the live window and the history on disk: a batch of old messages is never in neither
 * place, whatever happens in between, and a chat reads its older messages back a page at a time.
 */
class HistoryRulesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val window = Router.MAX_MESSAGES
    private val line = Router.MAX_MESSAGES + Router.SPILL_BATCH
    private val t0 = 1_700_000_000_000L

    /** A group message from B; a higher [i] is a newer message. */
    private fun group(net: FakeNet, i: Int): JSONObject =
        Message("g%05d".format(i), Envelope.CHAT, "B", "B", null, "group $i", net.now - 10_000_000L + i * 1000L).toJson()

    private fun state(messages: List<JSONObject>) = JSONObject().put("messages", JSONArray(messages))
    private fun phone(net: FakeNet, state: JSONObject): FakeNet.Node = net.node("A").also { it.router.restore(JSONObject(state.toString())) }
    private fun ids(a: JSONArray): List<String> = (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    private fun ids(list: List<JSONObject>): List<String> = list.map { it.getString("id") }

    // ---------------------------------------------------------------- filing a batch, in two steps

    @Test fun aBatchOnItsWayToTheHistoryIsStillInEverySavedState() {
        val net = FakeNet()
        val all = (0 until line).map { group(net, it) }
        val a = phone(net, state(all))
        a.router.tick()
        val batch = HistoryRules.offer(a.router)
        assertEquals(ids(all).take(Router.SPILL_BATCH), batch.map { it.id })
        assertEquals(window, a.router.messages.size)
        // the segment is being written; a save taken now (or the last one before a kill) holds every message
        val saved = a.router.snapshot()
        assertEquals(ids(all), ids(saved.getJSONArray("messages")))
        assertFalse(saved.has("spilled"))
        // …so a phone killed here comes back with the whole chat, and files the batch again
        val a2 = phone(FakeNet().also { it.now = net.now + 60_000 }, saved)
        assertEquals(ids(all), a2.router.messages.map { it.id })
        a2.router.tick()
        assertEquals(batch.map { it.id }, HistoryRules.offer(a2.router).map { it.id })
        // nothing to file: nothing offered, nothing touched
        val quiet = phone(FakeNet(), state(all.take(10)))
        quiet.router.tick(); quiet.router.takeDirty()
        assertTrue(HistoryRules.offer(quiet.router).isEmpty())
        assertFalse(quiet.router.takeDirty())
    }

    @Test fun onceTheSegmentIsWrittenTheRouterLetsGoOfExactlyThatBatch() {
        val net = FakeNet()
        val all = (0 until line).map { group(net, it) }
        val a = phone(net, state(all))
        a.router.tick()
        val batch = HistoryRules.offer(a.router)
        HistoryRules.settle(a.router, batch)
        val saved = a.router.snapshot()
        assertEquals(ids(all).drop(Router.SPILL_BATCH), ids(saved.getJSONArray("messages")))
        assertEquals(batch.map { it.id }.toSet(), saved.getJSONObject("spilled").keySet())
        assertTrue(HistoryRules.offer(a.router).isEmpty())
        // settling twice, or settling what was never offered, changes nothing
        HistoryRules.settle(a.router, batch)
        HistoryRules.settle(a.router, listOf(Message.fromJson(group(net, 99_999))))
        assertEquals(ids(all).drop(Router.SPILL_BATCH), ids(a.router.snapshot().getJSONArray("messages")))
    }

    @Test fun whatLeftTheWindowWhileTheSegmentWasWrittenWaitsForTheNextRound() {
        val net = FakeNet()
        val all = (0 until line + Router.SPILL_BATCH).map { group(net, it) }
        val a = phone(net, state(all.take(line)))
        a.router.tick()
        val first = HistoryRules.offer(a.router)
        // more arrive, and the window spills again before the first segment is confirmed
        a.router.restore(state(all.drop(line)))
        a.router.tick()
        assertEquals(window, a.router.messages.size)
        HistoryRules.settle(a.router, first)
        val saved = a.router.snapshot()
        assertEquals("the second batch is not let go: it was never written", ids(all).drop(Router.SPILL_BATCH), ids(saved.getJSONArray("messages")))
        assertEquals(first.map { it.id }.toSet(), saved.getJSONObject("spilled").keySet())
        val second = HistoryRules.offer(a.router)
        assertEquals(ids(all).subList(Router.SPILL_BATCH, 2 * Router.SPILL_BATCH), second.map { it.id })
        HistoryRules.settle(a.router, second)
        assertEquals(a.router.messages.map { it.id }, ids(a.router.snapshot().getJSONArray("messages")))
        assertEquals(ids(all), (first + second).map { it.id } + a.router.messages.map { it.id })   // everything, once
    }

    @Test fun aBatchTheDiskRefusedIsOfferedAgainUnchanged() {
        val net = FakeNet()
        val all = (0 until line).map { group(net, it) }
        val a = phone(net, state(all))
        a.router.tick()
        val batch = HistoryRules.offer(a.router)
        // the write failed: no settle. Every tick after that offers the same messages, and no save lacks them.
        repeat(3) {
            a.router.tick()
            assertEquals(batch.map { it.id }, HistoryRules.offer(a.router).map { it.id })
            assertEquals(ids(all), ids(a.router.snapshot().getJSONArray("messages")))
        }
    }

    @Test fun aMessageDeletedWhileItWaitsToBeFiledIsGoneForGood() {
        val net = FakeNet()
        val all = (0 until line).map { group(net, it) }
        val a = phone(net, state(all))
        a.router.tick()
        val batch = HistoryRules.offer(a.router)
        val doomed = setOf(batch[3].id, batch[7].id)
        // the router's own delete can't see them — they are no longer in the live window
        assertTrue(a.router.hideMessages(doomed).isEmpty())
        assertEquals(doomed, HistoryRules.dropWaiting(a.router) { it.id in doomed }.map { it.id }.toSet())
        assertEquals(ids(all).filter { it !in doomed }, ids(a.router.snapshot().getJSONArray("messages")))
        // the rest of the batch still changes hands as before, and the two never come back
        assertEquals(batch.map { it.id }.filter { it !in doomed }, HistoryRules.offer(a.router).map { it.id })
        HistoryRules.settle(a.router, batch)
        val saved = a.router.snapshot()
        assertEquals(ids(all).drop(Router.SPILL_BATCH), ids(saved.getJSONArray("messages")))
        assertTrue(saved.getJSONObject("spilled").keySet().containsAll(doomed))
        val again = phone(FakeNet().also { it.now = net.now + 60_000 }, saved.put("messages", JSONArray(all)))
        assertTrue("an id that was filed or deleted is never taken in again", again.router.messages.none { it.id in doomed })
        // nothing waiting: nothing dropped
        assertTrue(HistoryRules.dropWaiting(a.router) { true }.isEmpty())
    }

    // ---------------------------------------------------------------- which chat a filed message is in

    @Test fun aFiledMessageBelongsToTheChatItsMessageSays() {
        val mine = "A"
        val cases = listOf(
            Message("m1", Envelope.CHAT, "ravi", "Ravi", null, "to everyone", t0),
            Message("m2", Envelope.CHAT, mine, "Me", null, "from me to everyone", t0),
            Message("m3", Envelope.DM, "ravi", "Ravi", mine, "to me", t0),
            Message("m4", Envelope.DM, mine, "Me", "ravi", "from me", t0),
            Message("m5", Envelope.FILE, mine, "Me", "meera", "a photo", t0),
            Message("m6", Message.NOTICE, "ravi", "Ravi", null, "Trek", t0),
            Message("m7", Message.SYSTEM, "ravi", "Ravi", null, "an answer", t0),
            Message("local.left.$t0", Message.LEFT, mine, "Me", null, "", t0),
        )
        for (m in cases) assertEquals(m.id, m.chatKey(mine), HistoryRules.chatOf(m.toJson(), mine))
        assertEquals(Message.GROUP_CHAT, HistoryRules.chatOf(cases[0].toJson(), mine))
        assertEquals("ravi", HistoryRules.chatOf(cases[2].toJson(), mine))
        assertEquals("ravi", HistoryRules.chatOf(cases[3].toJson(), mine))
    }

    // ---------------------------------------------------------------- reading a chat's older messages back

    private fun history() = History(File(tmp.root, "history/ab12cd34"))
    private fun msg(id: String, ts: Long, to: String? = null, from: String = "ravi"): JSONObject =
        Message(id, if (to == null) Envelope.CHAT else Envelope.DM, from, from, to, "words of $id", ts).toJson()

    /** Step until the page is ready; returns how many steps it took. */
    private fun run(walk: HistoryRules.Walk): Int { var steps = 1; while (!walk.step()) steps++; return steps }

    @Test fun aFullPageStopsAndSaysWhereToCarryOn() {
        val h = history()
        for (s in 1..3) assertTrue(h.append((1..60).map { msg("g$s.%02d".format(it), t0 + s * 100_000L + it) }))
        val newest = HistoryRules.Walk(h, Message.GROUP_CHAT, "A", null)
        assertEquals(1, run(newest))
        assertEquals((2..3).flatMap { s -> (1..60).map { "g$s.%02d".format(it) } }, newest.messages().map { it.id })
        assertEquals(2, newest.next)
        val older = HistoryRules.Walk(h, Message.GROUP_CHAT, "A", newest.next)
        assertEquals(1, run(older))
        assertEquals((1..60).map { "g1.%02d".format(it) }, older.messages().map { it.id })
        assertEquals("nothing older", 0, older.next)
        // a chat that ran out stays run out
        val none = HistoryRules.Walk(h, Message.GROUP_CHAT, "A", 0)
        assertTrue(none.step()); assertTrue(none.messages().isEmpty()); assertEquals(0, none.next)
        // and a group with no history at all has an empty first page
        val empty = HistoryRules.Walk(History(File(tmp.root, "history/none")), Message.GROUP_CHAT, "A", null)
        assertTrue(empty.step()); assertTrue(empty.messages().isEmpty()); assertEquals(0, empty.next)
        assertFalse("looking never creates the folder", File(tmp.root, "history/none").exists())
    }

    @Test fun aQuietPrivateChatIsReadAFewSegmentsAtATime() {
        // One private message per segment among the group's chatter: a page of it needs many segments.
        val h = history()
        val segments = HistoryRules.STEP * 2 + 5
        for (s in 1..segments) assertTrue(h.append(listOf(msg("g$s.1", t0 + s * 1000L), msg("p$s", t0 + s * 1000L + 1, to = "A"), msg("g$s.2", t0 + s * 1000L + 2),
            msg("q$s", t0 + s * 1000L + 3, to = "A", from = "meera"))))
        val walk = HistoryRules.Walk(h, "ravi", "A", null)
        assertFalse("one step reads only so many segments", walk.step())
        assertEquals(HistoryRules.STEP, walk.messages().size)
        assertEquals(segments - HistoryRules.STEP + 1, walk.next)
        assertFalse(walk.step())
        assertTrue("the history ran out", walk.step())
        assertEquals((1..segments).map { "p$it" }, walk.messages().map { it.id })   // this chat only, oldest first
        assertEquals(0, walk.next)
    }

    @Test fun aMessageFiledTwiceIsReadOnceEvenAcrossSteps() {
        val h = history()
        assertTrue(h.append(listOf(msg("twice", t0 + 5), msg("old", t0 + 1))))
        for (s in 1..HistoryRules.STEP + 2) assertTrue(h.append(listOf(msg("pad$s", t0 + 100L * s, to = "A"))))   // other chats' segments in between
        assertTrue(h.append(listOf(msg("twice", t0 + 5).put("status", Message.DELIVERED), msg("new", t0 + 9))))
        val walk = HistoryRules.Walk(h, Message.GROUP_CHAT, "A", null)
        assertTrue(run(walk) > 1)
        assertEquals(listOf("old", "twice", "new"), walk.messages().map { it.id })
        assertEquals("the copy filed last had the longest to collect its ticks", Message.DELIVERED, walk.messages()[1].status)
    }

    // ---------------------------------------------------------------- deleting from the history

    /** Step until the delete is done; returns the ids it took out, and how many steps it took. */
    private fun run(forget: HistoryRules.Forget): Pair<List<String>, Int> {
        val gone = ArrayList<String>(); var steps = 0
        while (!forget.done) { gone += forget.step().map { it.getString("id") }; steps++ }
        return gone to steps
    }

    @Test fun aDeleteWorksThroughALongHistoryAFewSegmentsAtATime() {
        val h = history()
        val segments = HistoryRules.STEP * 2 + 3
        for (s in 1..segments) assertTrue(h.append(listOf(msg("g$s", t0 + s * 1000L), msg("p$s", t0 + s * 1000L + 1, to = "A"))))
        val forget = HistoryRules.Forget(h) { HistoryRules.chatOf(it, "A") == "ravi" }
        assertFalse(forget.done)
        val first = forget.step()
        assertEquals("one step sifts only so many segments", (1..HistoryRules.STEP).map { "p$it" }, first.map { it.getString("id") })
        assertFalse(forget.done)
        // a save, or a page of another chat, gets the thread here — and a batch may be filed meanwhile
        assertTrue(h.append(listOf(msg("pLate", t0 + 9_000_000L, to = "A"))))
        val (rest, steps) = run(forget)
        assertEquals(2, steps)
        assertEquals((HistoryRules.STEP + 1..segments).map { "p$it" }, rest)
        assertTrue(forget.done); assertTrue(forget.step().isEmpty())
        // the whole chat is gone from every segment that was there, the group chat untouched…
        val group = HistoryRules.Walk(h, Message.GROUP_CHAT, "A", null); while (!group.step()) { }
        assertEquals((1..segments).map { "g$it" }, group.messages().map { it.id })
        // …and what was filed after the delete was asked for is not the delete's to take
        val ravi = HistoryRules.Walk(h, "ravi", "A", null); while (!ravi.step()) { }
        assertEquals(listOf("pLate"), ravi.messages().map { it.id })
        // a group with no history is done at once, and nothing is created for it
        val none = HistoryRules.Forget(History(File(tmp.root, "history/none"))) { true }
        assertTrue(none.step().isEmpty()); assertTrue(none.done)
        assertFalse(File(tmp.root, "history/none").exists())
    }

    @Test fun aMessageDeletedFromTheLiveChatIsDeletedFromTheHistoryTooOrItWouldComeBack() {
        // A batch was filed, and the phone died before a state without it was saved: after the
        // restart those messages are in the live chat again AND in the history.
        val net = FakeNet()
        val all = (0 until line).map { group(net, it) }
        val a = phone(net, state(all))
        a.router.tick()
        val batch = HistoryRules.offer(a.router)
        val h = history()
        assertTrue(h.append(batch.map { it.toJson() }))                       // …and no settle: the kill came first
        val restarted = FakeNet().also { it.now = net.now + 60_000 }
        val a2 = phone(restarted, state(all.dropLast(1)))                      // the last state that reached the disk: one short of a spill
        a2.router.tick()
        assertTrue("under the line nothing is filed again, so the copies stay in both places", HistoryRules.offer(a2.router).isEmpty())
        val doomed = batch[5].id
        assertEquals(doomed, a2.router.message(doomed)!!.id)

        // "Delete for me": it goes from the live chat, and the router remembers that for three days.
        assertEquals(listOf(doomed), a2.router.hideMessages(listOf(doomed)).map { it.id })
        assertTrue(a2.router.isHidden(doomed))
        restarted.now += 73 * 3_600_000L
        a2.router.tick()
        assertFalse("after three days the router no longer knows it was deleted", a2.router.isHidden(doomed))
        // Deleted from the live chat alone, the copy in the history would now be read back into the chat:
        val before = HistoryRules.Walk(h, Message.GROUP_CHAT, "A", null); while (!before.step()) { }
        assertTrue(before.messages().any { it.id == doomed })

        // So the delete looks through the history for every id it was given — also the ones it found live.
        val want = setOf(doomed)
        val (gone, _) = run(HistoryRules.Forget(h) { it.optString("id") in want })
        assertEquals(listOf(doomed), gone)
        val after = HistoryRules.Walk(h, Message.GROUP_CHAT, "A", null); while (!after.step()) { }
        assertTrue(after.messages().none { it.id == doomed })
        assertEquals(Router.SPILL_BATCH - 1, History(File(tmp.root, "history/ab12cd34")).read(1).size)
    }

    @Test fun messagesFiledLateStillComeInChatOrderAndBadRecordsAreSkipped() {
        val h = history()
        // "late" was still being sent when its neighbours were filed, so it sits in a newer segment
        assertTrue(h.append(listOf(msg("a", t0 + 1), msg("c", t0 + 3))))
        assertTrue(h.append(listOf(msg("d", t0 + 4), msg("late", t0 + 2), JSONObject().put("id", "broken").put("kind", "chat"))))
        val walk = HistoryRules.Walk(h, Message.GROUP_CHAT, "A", null)
        assertEquals(1, run(walk))
        assertEquals(listOf("a", "late", "c", "d"), walk.messages().map { it.id })
    }
}
