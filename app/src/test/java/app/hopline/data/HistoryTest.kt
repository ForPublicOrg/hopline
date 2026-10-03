package app.hopline.data

import app.hopline.mesh.Envelope
import app.hopline.mesh.Message
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A group's older messages on disk: append-only segments that are never half-written and never quietly rewritten. */
class HistoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_700_000_000_000L
    private val longAgo = 1_600_000_000_000L

    private fun all() = File(tmp.root, "history")
    private fun dir() = File(all(), "ab12cd34")
    private fun seg(n: Int) = File(dir(), "%06d.json".format(n))

    private fun msg(id: String, to: String? = null, text: String = "words of $id"): JSONObject =
        Message(id, if (to == null) Envelope.CHAT else Envelope.DM, "asha", "Asha", to, text, t0).toJson()

    private fun ids(list: List<JSONObject>) = list.map { it.getString("id") }

    /** Three segments: a1 a2 a3 | b1 b2 | c1 c2 c3. */
    private fun three(): History = History(dir()).also {
        assertTrue(it.append(listOf(msg("a1"), msg("a2"), msg("a3"))))
        assertTrue(it.append(listOf(msg("b1"), msg("b2"))))
        assertTrue(it.append(listOf(msg("c1"), msg("c2"), msg("c3"))))
    }

    @Test fun nothingIsCreatedUntilSomethingIsFiled() {
        val h = History(dir())
        assertTrue(h.isEmpty())
        assertTrue(h.segments().isEmpty())
        assertTrue(h.read(1).isEmpty())
        assertTrue(h.remove(setOf("a1")).isEmpty())
        assertTrue(h.removeWhere { true }.isEmpty())
        val page = h.earlier(null, 80) { true }
        assertTrue(page.records.isEmpty()); assertEquals(0, page.next)
        assertTrue("an empty batch is no segment", h.append(emptyList()))
        h.deleteAll()
        assertFalse("looking must never create the directory", dir().exists())
        assertFalse(all().exists())
    }

    @Test fun eachBatchBecomesTheNextNumberedSegment() {
        val h = three()
        assertFalse(h.isEmpty())
        assertEquals(listOf(1, 2, 3), h.segments())
        assertEquals(setOf("000001.json", "000002.json", "000003.json"), dir().list()!!.toSet())   // and no temp file stays behind
        assertEquals(listOf("a1", "a2", "a3"), ids(h.read(1)))
        assertEquals(listOf("b1", "b2"), ids(h.read(2)))
        assertEquals(listOf("c1", "c2", "c3"), ids(h.read(3)))
        assertTrue(h.read(4).isEmpty())
    }

    @Test fun aMessageComesBackExactlyAsItWasFiled() {
        val m = Message("m1", Envelope.DM, "asha", "Asha", "ravi", "meet at the bridge 🌉", t0).also {
            it.status = Message.DELIVERED; it.arrivedAt = t0 + 5; it.reached.add("ravi"); it.applyReaction("ravi", "👍", t0 + 9)
        }
        val h = History(dir())
        assertTrue(h.append(listOf(m.toJson())))
        val back = h.read(1).single()
        assertTrue(back.similar(m.toJson()))
        val again = Message.fromJson(back)
        assertEquals("meet at the bridge 🌉", again.text); assertEquals("ravi", again.to); assertEquals(Message.DELIVERED, again.status)
        assertEquals(t0 + 5, again.arrivedAt); assertEquals(setOf("ravi"), again.reached); assertEquals("👍", again.reactions["ravi"])
    }

    @Test fun anAppendNeverRewritesASegmentThatIsAlreadyThere() {
        val h = three()
        val before = (1..3).map { seg(it).readBytes() }
        (1..3).forEach { assertTrue(seg(it).setLastModified(longAgo)) }
        assertTrue(h.append(listOf(msg("d1"))))
        assertEquals(listOf(1, 2, 3, 4), h.segments())
        for (i in 1..3) {
            assertArrayEquals(before[i - 1], seg(i).readBytes())
            assertEquals(longAgo, seg(i).lastModified())
        }
        // a gap is not filled in: a new batch always goes after the newest segment
        assertTrue(seg(2).delete())
        assertTrue(h.append(listOf(msg("e1"))))
        assertEquals(listOf(1, 3, 4, 5), h.segments())
        assertEquals(listOf("e1"), ids(h.read(5)))
    }

    @Test fun aHalfWrittenTempFileIsNeverPartOfTheHistory() {
        val h = History(dir())
        assertTrue(h.append(listOf(msg("a1"))))
        File(dir(), "~000002.tmp").writeText("""[{"id":"gho""")    // the phone died mid-write
        File(dir(), "notes.txt").writeText("not ours")
        assertEquals(listOf(1), h.segments())
        assertTrue(h.earlier(null, 80) { true }.records.size == 1)
        assertTrue(h.append(listOf(msg("b1"))))
        assertEquals(listOf(1, 2), h.segments())
        assertEquals(listOf("b1"), ids(h.read(2)))
        assertFalse(File(dir(), "~000002.tmp").exists())
        assertTrue("a file that isn't a segment is none of our business", File(dir(), "notes.txt").exists())
    }

    @Test fun badRecordsAreSkippedAndAnUnreadableSegmentIsLeftUntouched() {
        val h = three()
        dir().mkdirs()
        seg(4).writeText(JSONArray().put(msg("d1")).put(7).put("words").put(JSONObject.NULL)
            .put(JSONObject().put("text", "no id")).put(msg("d2")).toString())
        seg(5).writeText("""[{"id":"e1","kind":"chat" this is not json""")
        val broken = seg(5).readBytes()
        assertTrue(seg(5).setLastModified(longAgo))
        assertEquals(listOf(1, 2, 3, 4, 5), h.segments())
        assertEquals(listOf("d1", "d2"), ids(h.read(4)))
        assertTrue(h.read(5).isEmpty())
        // reading pages and deleting walk straight past it
        assertEquals(listOf("a1", "a2", "a3", "b1", "b2", "c1", "c2", "c3", "d1", "d2"), ids(h.earlier(null, 80) { true }.records))
        assertEquals(listOf("d1"), ids(h.remove(setOf("d1", "e1"))))
        assertTrue(h.removeWhere { it.optString("id") == "e1" }.isEmpty())
        assertArrayEquals(broken, seg(5).readBytes())
        assertEquals(longAgo, seg(5).lastModified())
        // the rewrite of segment 4 took out d1 and nothing else — not even what it can't read as a message
        assertEquals(listOf("d2"), ids(h.read(4)))
        assertEquals(5, JSONArray(seg(4).readText()).length())
    }

    @Test fun removeRewritesOnlyTheSegmentsThatChange() {
        val h = three()
        val first = seg(1).readBytes(); val third = seg(3).readBytes()
        (1..3).forEach { assertTrue(seg(it).setLastModified(longAgo)) }
        val gone = h.remove(setOf("b2", "not there"))
        assertEquals(listOf("b2"), ids(gone))
        assertEquals("words of b2", gone.single().getString("text"))       // the whole record, so its file can go too
        assertEquals(listOf("b1"), ids(h.read(2)))
        assertArrayEquals(first, seg(1).readBytes()); assertEquals(longAgo, seg(1).lastModified())
        assertArrayEquals(third, seg(3).readBytes()); assertEquals(longAgo, seg(3).lastModified())
        assertEquals(setOf("000001.json", "000002.json", "000003.json"), dir().list()!!.toSet())
        // nothing to remove: nothing is written at all
        assertTrue(seg(2).setLastModified(longAgo))
        assertTrue(h.remove(setOf("b2", "zz")).isEmpty())
        assertTrue(h.remove(emptySet()).isEmpty())
        assertEquals(longAgo, seg(2).lastModified())
    }

    @Test fun aSegmentLeftEmptyIsDeleted() {
        val h = three()
        assertEquals(listOf("b1", "b2"), ids(h.remove(setOf("b1", "b2"))))
        assertFalse(seg(2).exists())
        assertEquals(listOf(1, 3), h.segments())
        assertEquals(listOf("a1", "a2", "a3", "c1", "c2", "c3"), ids(h.earlier(null, 80) { true }.records))
        // emptying everything leaves no segments — and a later batch still files fine
        assertEquals(6, h.removeWhere { true }.size)
        assertTrue(h.isEmpty())
        assertTrue(h.append(listOf(msg("z1"))))
        assertEquals(listOf("z1"), ids(h.earlier(null, 80) { true }.records))
    }

    @Test fun removeWhereTakesOneChatOutOfEverySegment() {
        val h = History(dir())
        h.append(listOf(msg("g1"), msg("p1", to = "ravi"), msg("g2")))
        h.append(listOf(msg("p2", to = "ravi"), msg("q1", to = "meera")))
        h.append(listOf(msg("g3")))
        val third = seg(3).readBytes()
        val gone = h.removeWhere { it.optString("to") == "ravi" }
        assertEquals(listOf("p1", "p2"), ids(gone))
        assertEquals(listOf("g1", "g2"), ids(h.read(1)))
        assertEquals(listOf("q1"), ids(h.read(2)))
        assertArrayEquals(third, seg(3).readBytes())
    }

    // ---------------------------------------------------------------- deleting a few segments at a time

    /** Six segments of four: g = group chat, p = a private chat with ravi. */
    private fun six(): History = History(dir()).also { h ->
        for (s in 1..6) assertTrue(h.append(listOf(msg("g$s.1"), msg("p$s.1", to = "ravi"), msg("g$s.2"), msg("p$s.2", to = "ravi"))))
    }

    private fun files(): Map<String, String> = dir().list()!!.sorted().associateWith { File(dir(), it).readText() }

    @Test fun deletingInStepsRemovesExactlyWhatOneGoRemovesAndLeavesTheSameFiles() {
        val ravi: (JSONObject) -> Boolean = { it.optString("to") == "ravi" }
        val oneGo = six()
        val all = ids(oneGo.removeWhere(ravi))
        val whole = files()
        oneGo.deleteAll()

        val h = six()
        val upTo = h.segments().last()
        val got = ArrayList<String>()
        var after = 0; var steps = 0
        do {
            val went = h.removeWhere(after, upTo, 2, ravi)
            got += ids(went.records)
            after = went.next; steps++
        } while (after != 0)
        assertEquals(3, steps)                                        // six segments, two at a time
        assertEquals(all, got)
        assertEquals((1..6).flatMap { listOf("p$it.1", "p$it.2") }, got)
        assertEquals(whole, files())
        assertEquals(listOf("g1.1", "g1.2"), ids(h.read(1)))
    }

    @Test fun oneStepLooksAtNoMoreSegmentsThanItIsAllowedAndSaysWhereToCarryOn() {
        val h = six()
        val before = (1..6).map { seg(it).readBytes() }
        (1..6).forEach { assertTrue(seg(it).setLastModified(longAgo)) }
        val first = h.removeWhere(0, 6, 2) { it.optString("to") == "ravi" }
        assertEquals(listOf("p1.1", "p1.2", "p2.1", "p2.2"), ids(first.records))
        assertEquals(2, first.next)
        for (i in 3..6) { assertArrayEquals(before[i - 1], seg(i).readBytes()); assertEquals(longAgo, seg(i).lastModified()) }
        // a step that finds nothing to delete still moves on, and writes nothing
        val quiet = h.removeWhere(first.next, 6, 2) { it.optString("id") == "nobody" }
        assertTrue(quiet.records.isEmpty()); assertEquals(4, quiet.next)
        for (i in 3..6) assertEquals(longAgo, seg(i).lastModified())
        // the last step says there is nothing more
        val last = h.removeWhere(quiet.next, 6, 2) { it.optString("to") == "ravi" }
        assertEquals(listOf("p5.1", "p5.2", "p6.1", "p6.2"), ids(last.records))
        assertEquals(0, last.next)
        // with nothing to look at, it is done at once
        assertEquals(0, h.removeWhere(6, 6, 2) { true }.next)
        assertEquals(0, History(File(tmp.root, "history/none")).removeWhere(0, 0, 2) { true }.next)
    }

    @Test fun aSegmentFiledAfterTheDeleteWasAskedForIsNeverTouchedByIt() {
        // "Clear chat" names a whole chat. What reaches the history afterwards was not cleared.
        val h = six()
        val upTo = h.segments().last()
        val first = h.removeWhere(0, upTo, 4) { it.optString("to") == "ravi" }
        assertEquals(4, first.next)
        assertTrue(h.append(listOf(msg("p7.1", to = "ravi"), msg("g7.1"))))      // filed between two steps
        val late = seg(7).readBytes()
        val second = h.removeWhere(first.next, upTo, 4) { it.optString("to") == "ravi" }
        assertEquals(listOf("p5.1", "p5.2", "p6.1", "p6.2"), ids(second.records))
        assertEquals(0, second.next)
        assertArrayEquals(late, seg(7).readBytes())
        assertEquals(listOf("p7.1", "g7.1"), ids(h.read(7)))
    }

    @Test fun aSegmentThatCantBeReadIsSteppedOverAndLeftAsItIs() {
        val h = six()
        seg(3).writeText("""[{"id":"p3.1","to":"ravi" this is not json""")
        val broken = seg(3).readBytes()
        val got = ArrayList<String>()
        var after = 0
        do { val went = h.removeWhere(after, 6, 1) { it.optString("to") == "ravi" }; got += ids(went.records); after = went.next } while (after != 0)
        assertEquals(listOf(1, 2, 4, 5, 6).flatMap { listOf("p$it.1", "p$it.2") }, got)
        assertArrayEquals(broken, seg(3).readBytes())
    }

    // ---------------------------------------------------------------- a batch the disk refuses

    @Test fun aBatchThatCantBeFiledIsNotFiledAndCanBeFiledLater() {
        // Something that is not a folder sits where the group's history should be: nothing can be written.
        all().mkdirs()
        dir().writeText("in the way")
        val h = History(dir())
        val batch = listOf(msg("a1"), msg("a2"))
        assertFalse("false tells the caller to keep hold of the batch", h.append(batch))
        assertEquals("in the way", dir().readText())
        assertTrue(h.segments().isEmpty()); assertTrue(h.isEmpty())
        // once it is out of the way, the very same batch files as the first segment
        assertTrue(dir().delete())
        assertTrue(h.append(batch))
        assertEquals(listOf(1), h.segments())
        assertEquals(listOf("a1", "a2"), ids(h.read(1)))
    }

    @Test fun aSegmentThatCantBeWrittenLeavesTheHistoryExactlyAsItWas() {
        val h = History(dir())
        assertTrue(h.append(listOf(msg("a1"))))
        val first = seg(1).readBytes()
        assertTrue(seg(1).setLastModified(longAgo))
        // The temporary file the next segment is written through can't be created: a folder has its name.
        val blocker = File(dir(), "~000002.tmp")
        assertTrue(blocker.mkdirs()); File(blocker, "stuck").writeText("x")
        val batch = listOf(msg("b1"), msg("b2"))
        assertFalse(h.append(batch))
        assertEquals("no half-made segment", listOf(1), h.segments())
        assertFalse(seg(2).exists())
        assertArrayEquals(first, seg(1).readBytes()); assertEquals(longAgo, seg(1).lastModified())
        // the obstacle gone, the batch lands as the next segment and no temporary file stays behind
        assertTrue(blocker.deleteRecursively())
        assertTrue(h.append(batch))
        assertEquals(listOf(1, 2), h.segments())
        assertEquals(listOf("b1", "b2"), ids(h.read(2)))
        assertEquals(setOf("000001.json", "000002.json"), dir().list()!!.toSet())
    }

    @Test fun deleteAllRemovesTheWholeDirectory() {
        val h = three()
        File(dir(), "~000009.tmp").writeText("x")
        h.deleteAll()
        assertFalse(dir().exists())
        assertTrue(h.isEmpty())
        assertTrue("the other groups' histories are not touched", all().exists())
        // and the group can start a history again
        assertTrue(h.append(listOf(msg("n1"))))
        assertEquals(listOf(1), h.segments())
    }

    @Test fun aMessageFiledTwiceIsReadOnce() {
        // The phone died after filing a batch but before saving its state: the same messages were
        // filed again later — by then b had been reacted to.
        val h = History(dir())
        h.append(listOf(msg("a"), msg("b")))
        val later = msg("b").put("status", Message.DELIVERED)
        h.append(listOf(later, msg("c")))
        val page = h.earlier(null, 80) { true }
        assertEquals(listOf("a", "b", "c"), ids(page.records))
        assertEquals("the copy filed last is the one that kept up", Message.DELIVERED, page.records[1].getString("status"))
        assertEquals(0, page.next)
    }

    @Test fun earlierWalksDownFromTheNewestSegmentOneChatAtATime() {
        val h = History(dir())
        for (s in 1..5) h.append((1..10).flatMap { i -> listOf(msg("g$s.$i"), msg("p$s.$i", to = "ravi")) })
        val group: (JSONObject) -> Boolean = { !it.has("to") }
        val newest = h.earlier(null, 15, group)
        assertEquals((4..5).flatMap { s -> (1..10).map { "g$s.$it" } }, ids(newest.records))   // oldest first, only this chat
        assertEquals(4, newest.next)
        val middle = h.earlier(newest.next, 15, group)
        assertEquals((2..3).flatMap { s -> (1..10).map { "g$s.$it" } }, ids(middle.records))
        assertEquals(2, middle.next)
        val oldest = h.earlier(middle.next, 15, group)
        assertEquals((1..10).map { "g1.$it" }, ids(oldest.records))
        assertEquals("nothing older", 0, oldest.next)
        // a chat with nothing in the history runs out without finding anything
        val none = h.earlier(null, 15) { it.optString("to") == "meera" }
        assertTrue(none.records.isEmpty()); assertEquals(0, none.next)
        // a page that is satisfied exactly at the oldest segment knows there is nothing older
        assertEquals(0, h.earlier(2, 10, group).next)
    }

    @Test fun earlierReadsNoMoreSegmentsThanItIsAllowedInOneGo() {
        val h = History(dir())
        h.append(listOf(msg("p1", to = "ravi")))
        for (s in 2..6) h.append(listOf(msg("g$s")))
        val ravi: (JSONObject) -> Boolean = { it.optString("to") == "ravi" }
        // two segments at a time: nothing of this chat in 6 and 5, but there is more to look through
        val first = h.earlier(null, 80, maxSegments = 2, want = ravi)
        assertTrue(first.records.isEmpty()); assertEquals(5, first.next)
        val second = h.earlier(first.next, 80, maxSegments = 2, want = ravi)
        assertTrue(second.records.isEmpty()); assertEquals(3, second.next)
        val third = h.earlier(second.next, 80, maxSegments = 2, want = ravi)
        assertEquals(listOf("p1"), ids(third.records))
        assertEquals("the oldest segment was read: nothing older", 0, third.next)
        // enough found before the limit: the limit changes nothing
        assertEquals(6, h.earlier(null, 1, maxSegments = 2) { true }.next)
        assertEquals(listOf("g6"), ids(h.earlier(null, 1, maxSegments = 2) { true }.records))
    }
}
