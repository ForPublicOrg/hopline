package app.hopline.mesh

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live window: a group keeps its newest messages in memory, and the oldest move out to the
 * history a batch at a time — handed over whole, never dropped, never shown twice.
 */
class SpillTest {
    private val window = Router.MAX_MESSAGES
    private val line = Router.MAX_MESSAGES + Router.SPILL_BATCH     // where the oldest start to move out
    /** This phone ("A") as the mesh knows it. */
    private val me = FakeNet.idOf("A")

    /** A group message from B, [i] seconds into the story — so a higher [i] is a newer message. */
    private fun group(net: FakeNet, i: Int, from: String = "B"): JSONObject =
        Message("g%05d".format(i), Envelope.CHAT, from, from, null, "group $i", net.now - 10_000_000L + i * 1000L).toJson()

    /** A private message between me (A) and [peer]. */
    private fun private(net: FakeNet, i: Int, peer: String, mine: Boolean = false): JSONObject =
        Message("p%05d.$peer".format(i), Envelope.DM, if (mine) me else peer, if (mine) "A" else peer, if (mine) peer else me,
            "private $i", net.now - 10_000_000L + i * 1000L).toJson()

    private fun state(messages: List<JSONObject>) = JSONObject().put("messages", JSONArray(messages))

    private fun phone(net: FakeNet, state: JSONObject): FakeNet.Node = net.node("A").also { it.router.restore(JSONObject(state.toString())) }

    private fun ids(a: JSONArray): List<String> = (0 until a.length()).map { a.getJSONObject(it).getString("id") }
    private fun ids(list: List<JSONObject>): List<String> = list.map { it.getString("id") }

    @Test fun belowTheLineALiveGroupIsLeftExactlyAsItWas() {
        val net = FakeNet()
        val all = (0 until line - 1).map { group(net, it) }
        val a = phone(net, state(all))
        a.router.tick(); a.router.tick()
        assertEquals(line - 1, a.router.messages.size)
        assertTrue(a.router.takeOverflow().isEmpty())
        assertEquals(ids(all), a.router.messages.map { it.id })
        val snap = a.router.snapshot()
        assertEquals(ids(all), ids(snap.getJSONArray("messages")))
        assertFalse("a group that never filed anything saves no list of filed messages", snap.has("spilled"))
    }

    @Test fun atTheLineTheOldestMoveOutAndNothingIsLost() {
        val net = FakeNet()
        val all = (0 until line).map { group(net, it) }
        val a = phone(net, state(all))
        assertEquals("opening the chat moves nothing", line, a.router.messages.size)
        a.router.tick()
        assertEquals(window, a.router.messages.size)
        val out = a.router.takeOverflow()
        assertEquals(Router.SPILL_BATCH, out.size)
        // the oldest, oldest first — and together with the live window, everything, once
        assertEquals(ids(all).take(Router.SPILL_BATCH), out.map { it.id })
        assertEquals(ids(all), out.map { it.id } + a.router.messages.map { it.id })
        assertEquals("group 0", out.first().text)
        // they are out of the live window for good…
        assertNull(a.router.message(out.first().id))
        assertNotNull(a.router.message(a.router.messages.first().id))
        assertTrue(a.router.chatMessages(null).none { it.id == out.first().id })
        // …handed over exactly once, and worth a save
        assertTrue(a.router.takeDirty())
        assertTrue(a.router.takeOverflow().isEmpty())
        a.router.tick()
        assertEquals(window, a.router.messages.size)
        assertTrue(a.router.takeOverflow().isEmpty())
    }

    @Test fun anOverflowNobodyTookIsSavedWithTheRestExactlyOnce() {
        val net = FakeNet()
        val all = (0 until line + 50).map { group(net, it) }
        val a = phone(net, state(all))
        a.router.tick()                                           // moved out — and nobody comes for them
        assertEquals(window, a.router.messages.size)
        val saved = a.router.snapshot()
        assertEquals(ids(all), ids(saved.getJSONArray("messages")))            // all of them, in order, none twice
        assertFalse(saved.has("spilled"))
        // more ticks and more messages don't change that
        a.router.tick()
        assertEquals(ids(all), ids(a.router.snapshot().getJSONArray("messages")))
        // a restart brings every one of them back into the chat, and the next tick moves them out again
        val again = FakeNet().also { it.now = net.now + 60_000 }
        val a2 = phone(again, saved)
        assertEquals(ids(all), a2.router.messages.map { it.id })
        a2.router.tick()
        val out = a2.router.takeOverflow()
        assertEquals(ids(all), out.map { it.id } + a2.router.messages.map { it.id })
        assertEquals(window, a2.router.messages.size)
        // once taken, they are no longer in the saved state — only remembered as filed
        val after = a2.router.snapshot()
        assertEquals(a2.router.messages.map { it.id }, ids(after.getJSONArray("messages")))
        assertEquals(out.map { it.id }.toSet(), after.getJSONObject("spilled").keySet())
    }

    @Test fun messagesTheHistoryCouldNotTakeGoBackIntoTheSavedState() {
        val net = FakeNet()
        val all = (0 until line).map { group(net, it) }
        val a = phone(net, state(all))
        a.router.tick()
        val out = a.router.takeOverflow()
        assertEquals(window, a.router.snapshot().getJSONArray("messages").length())
        a.router.takeDirty()
        // the disk refused the batch: nothing may be lost while it waits for another try
        a.router.returnOverflow(out)
        assertTrue(a.router.takeDirty())
        val saved = a.router.snapshot()
        assertEquals(ids(all), ids(saved.getJSONArray("messages")))
        assertFalse(saved.has("spilled"))
        assertEquals(window, a.router.messages.size)                // still out of the live window
        // handing back twice, or handing back strangers, changes nothing
        a.router.returnOverflow(out)
        a.router.returnOverflow(listOf(Message.fromJson(group(net, 99_999))))
        assertEquals(ids(all), ids(a.router.snapshot().getJSONArray("messages")))
        // and the next round offers exactly the same batch again
        a.router.tick()
        assertEquals(out.map { it.id }, a.router.takeOverflow().map { it.id })
        assertEquals(out.map { it.id }.toSet(), a.router.snapshot().getJSONObject("spilled").keySet())
    }

    @Test fun aQuietPrivateChatIsNeverPushedOutByGroupChatter() {
        val net = FakeNet()
        // the two private chats are older than every group message
        val ravi = (0 until 20).map { private(net, it, "ravi", mine = it % 2 == 0) }
        val meera = (20 until 20 + Router.KEEP_PER_CHAT + 5).map { private(net, it, "meera") }
        val chatter = (100 until 100 + line + 300).map { group(net, it) }
        val a = phone(net, state(ravi + meera + chatter))
        a.router.tick()
        assertEquals(window, a.router.messages.size)
        val out = a.router.takeOverflow().map { it.id }
        // a chat at or under the floor keeps every message
        assertEquals(ids(ravi), a.router.chatMessages("ravi").map { it.id })
        assertTrue(out.none { it.endsWith(".ravi") })
        // a chat just over it gives up only its oldest, down to the floor
        assertEquals(ids(meera).takeLast(Router.KEEP_PER_CHAT), a.router.chatMessages("meera").map { it.id })
        assertEquals(ids(meera).take(5), out.filter { it.endsWith(".meera") })
        // the busy group chat pays for the rest, oldest first — and keeps the newest
        assertEquals(window - 20 - Router.KEEP_PER_CHAT, a.router.chatMessages(null).size)
        assertEquals(ids(chatter).last(), a.router.messages.last().id)
        assertEquals(ids(ravi + meera + chatter).toSet(), (out + a.router.messages.map { it.id }).toSet())
        assertEquals(ids(ravi + meera + chatter).size, out.size + a.router.messages.size)
    }

    @Test fun thousandsOfTinyChatsStillComeDownToTheWindow() {
        val net = FakeNet()
        val all = (0 until line + 10).map { private(net, it, "peer$it") }     // one message per chat: none has any to spare
        val a = phone(net, state(all))
        a.router.tick()
        assertEquals(window, a.router.messages.size)
        val out = a.router.takeOverflow()
        assertEquals(ids(all).take(Router.SPILL_BATCH + 10), out.map { it.id })     // plain oldest-first
        assertEquals(ids(all), out.map { it.id } + a.router.messages.map { it.id })
    }

    @Test fun whenNoChatHasAnyToSpareAMessageStillWaitingAndAFileStillArrivingStayAllTheSame() {
        // A private message of mine, typed with nobody around, long before: the oldest message of all.
        val lonely = FakeNet(); lonely.now -= 20_000_000L
        lonely.node("A"); lonely.know("A", "zed")
        val waiting = lonely.nodes["A"]!!.router.sendDm(lonely.id("zed"), "still trying to reach you")!!
        val saved = lonely.nodes["A"]!!.router.snapshot()
        assertEquals(Message.QUEUED, waiting.status)

        val net = FakeNet()
        // A private photo whose message came an hour ago; its pieces are still on their way.
        val att = Attachment.make("abcdefghij", "photo.jpg", "image/jpeg", 30_000, 3, 10, 10, "tb")
        val file = Message("file-0", Envelope.FILE, "yan", "yan", me, "sunset", net.now - 15_000_000L, att).also { it.arrivedAt = net.now - 3_600_000L }.toJson()
        // …and thousands of chats of one message each: none has any to spare, so the oldest go whatever their chat.
        val tiny = (0 until line + 10).map { private(net, it, "peer$it") }
        val all = JSONObject(saved.toString())
        all.getJSONArray("messages").put(file)
        for (m in tiny) all.getJSONArray("messages").put(m)
        val a = phone(net, all)
        assertTrue(a.router.carries(waiting.id))
        assertEquals(listOf(waiting.id, "file-0"), a.router.messages.take(2).map { it.id })       // the two oldest of all
        a.router.tick()
        val out = a.router.takeOverflow().map { it.id }
        assertEquals(window, a.router.messages.size)
        // Those two rules hold in this pass too: neither is moved out…
        assertFalse(out.contains(waiting.id)); assertFalse(out.contains("file-0"))
        assertEquals(listOf(waiting.id, "file-0"), a.router.messages.take(2).map { it.id })
        assertNotNull(a.router.message(waiting.id)); assertNotNull(a.router.fileMessage("abcdefghij"))
        // …and everything else went plain oldest-first.
        assertEquals(Router.SPILL_BATCH + 12, out.size)
        assertEquals(ids(tiny).take(out.size), out)
        // The waiting message is still being tried from the live window: the friend it is for gets it.
        net.node("zed"); net.connect("A", "zed")
        assertEquals(listOf("still trying to reach you"), net.texts("zed"))
        assertEquals(Message.DELIVERED, a.router.message(waiting.id)!!.status)
    }

    @Test fun aMessageOfMineStillWaitingToLeaveIsNotMovedOut() {
        // Typed with nobody around, long before the group got busy: it is the oldest message of all.
        val lonely = FakeNet(); lonely.now -= 20_000_000L
        val waiting = lonely.node("A").router.sendChat("still trying to reach you")
        val saved = lonely.nodes["A"]!!.router.snapshot()
        assertEquals(Message.QUEUED, waiting.status)
        // the same words, but with no envelope left to send (as after leaving and rejoining)
        val given = Message("given-up", Envelope.CHAT, me, "A", null, "gave up", lonely.now + 1).also { it.status = Message.QUEUED }.toJson()

        val net = FakeNet()
        val chatter = (0 until line + 5).map { group(net, it) }
        val all = JSONObject(saved.toString())
        all.getJSONArray("messages").put(given)
        for (m in chatter) all.getJSONArray("messages").put(m)
        val a = phone(net, all)
        assertTrue(a.router.carries(waiting.id)); assertFalse(a.router.carries("given-up"))
        a.router.tick()
        val out = a.router.takeOverflow().map { it.id }
        assertEquals(window, a.router.messages.size)
        assertFalse(out.contains(waiting.id))
        assertEquals(waiting.id, a.router.messages.first().id)              // still first in the live chat
        assertTrue("a message that will never be sent moves out like any other", out.contains("given-up"))
        // and it really is still being tried: the first friend in range gets it
        net.node("B"); net.connect("A", "B")
        assertTrue(net.texts("B").contains("still trying to reach you"))
        assertFalse(net.texts("B").contains("gave up"))
    }

    @Test fun aFileStillArrivingStaysUntilItCanNoLongerArrive() {
        fun withFile(net: FakeNet, arrived: Long): FakeNet.Node {
            val att = Attachment.make("abcdefghij", "photo.jpg", "image/jpeg", 30_000, 3, 10, 10, "tb")
            val file = Message("file-0", Envelope.FILE, "B", "B", null, "sunset", net.now - 20_000_000L, att).also { it.arrivedAt = arrived }.toJson()
            return phone(net, state(listOf(file) + (0 until line).map { group(net, it) }))
        }
        // its message arrived an hour ago and the pieces are still coming: it stays, so it can be put together
        val net = FakeNet()
        val a = withFile(net, arrived = net.now - 3_600_000L)
        a.router.tick()
        assertEquals(window, a.router.messages.size)
        assertNotNull(a.router.message("file-0")); assertNotNull(a.router.fileMessage("abcdefghij"))
        assertTrue(a.router.takeOverflow().none { it.id == "file-0" })
        // three days on, no phone carries its pieces any more: it moves out with the rest
        val late = FakeNet()
        val b = withFile(late, arrived = late.now - 3 * 86_400_000L)
        b.router.tick()
        assertEquals("file-0", b.router.takeOverflow().first().id)
        assertNull(b.router.message("file-0")); assertNull(b.router.fileMessage("abcdefghij"))
    }

    @Test fun aFiledMessageHandedBackByAFriendIsNotShownAgain() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!; val b = net.nodes["B"]!!.router
        repeat(line) { b.sendChat("message $it") }
        net.pump()
        assertEquals(line, a.rec.shown.size)
        a.router.tick(); net.pump()
        val out = a.router.takeOverflow()
        assertEquals(Router.SPILL_BATCH, out.size)
        assertEquals("message 0", out.first().text)
        val shownBefore = a.rec.shown.size

        // same run: B offers its whole backlog again, link after link, sync after sync
        net.disconnect("A", "B"); net.connect("A", "B")
        net.now += Router.SYNC_MS + 1_000; net.tickAll()
        assertEquals(window, a.router.messages.size)
        assertEquals(shownBefore, a.rec.shown.size)
        assertNull(a.router.message(out.first().id))

        // after a restart, with this phone's own backlog gone (so only the saved list of filed ids
        // knows them): B hands every one of its messages over, and none of the filed ones comes back
        val saved = JSONObject(a.router.snapshot().toString())
        assertEquals(out.map { it.id }.toSet(), saved.getJSONObject("spilled").keySet())
        saved.put("carry", JSONArray()).put("born", JSONObject())
        net.disconnect("A", "B")
        val a2 = net.node("A"); a2.router.restore(saved)
        net.connect("A", "B")
        net.now += Router.SYNC_MS + 1_000; net.tickAll()
        assertTrue(b.snapshot().getJSONArray("carry").length() >= line)        // B really did still have them to give
        assertEquals(window, a2.router.messages.size)
        assertTrue("nothing old is announced as new", a2.rec.shown.isEmpty())
        assertNull(a2.router.message(out.first().id))
        assertTrue(a2.router.takeOverflow().isEmpty())
    }

    @Test fun restoreNeverBringsBackAMessageThatWasDeletedOrFiled() {
        val net = FakeNet()
        val all = (0 until 5).map { group(net, it) }
        val saved = state(all)
            .put("hidden", JSONObject().put(ids(all)[1], net.now))
            .put("spilled", JSONObject().put(ids(all)[3], net.now))
        val a = phone(net, saved)
        assertEquals(listOf(ids(all)[0], ids(all)[2], ids(all)[4]), a.router.messages.map { it.id })
        assertTrue(a.router.isHidden(ids(all)[1]))
        // and both lists are saved again
        val snap = a.router.snapshot()
        assertTrue(snap.getJSONObject("hidden").has(ids(all)[1]))
        assertTrue(snap.getJSONObject("spilled").has(ids(all)[3]))
    }

    @Test fun theListOfFiledMessagesSurvivesARestartAndIsForgottenOnceNobodyCarriesThem() {
        val net = FakeNet()
        val a = phone(net, state((0 until line).map { group(net, it) }))
        a.router.tick()
        val filed = a.router.takeOverflow().map { it.id }.toSet()
        val saved = a.router.snapshot()
        assertEquals(filed, saved.getJSONObject("spilled").keySet())
        for (id in filed) assertEquals(net.now, saved.getJSONObject("spilled").getLong(id))

        val later = FakeNet().also { it.now = net.now + Router.CARRY_MS }
        val a2 = phone(later, saved)
        a2.router.tick()
        assertEquals("two days on, a friend may still carry them", filed, a2.router.snapshot().getJSONObject("spilled").keySet())
        later.now = net.now + Router.REMEMBER_MS - 60_000
        a2.router.tick()
        assertEquals("nor can a late one, nearly two weeks on, bring them back", filed, a2.router.snapshot().getJSONObject("spilled").keySet())
        later.now = net.now + Router.REMEMBER_MS + 1_000
        a2.router.tick()
        assertFalse("two weeks on, nobody does", a2.router.snapshot().has("spilled"))
        assertEquals(window, a2.router.messages.size)
    }

    @Test fun aReactionToAFiledMessageIsStillCarriedForTheOthers() {
        val net = FakeNet()
        val a = phone(net, state((0 until line).map { group(net, it, from = me) }))
        a.router.tick()
        val gone = a.router.takeOverflow().first()
        net.node("B"); net.node("C")
        net.connect("A", "B")
        // B reacts to a message of mine that this phone has filed away: nothing here to put it on…
        val react = FakeNet.envelope("B", Envelope.REACT, JSONObject().put("m", gone.id).put("e", "👍"), net.now)
        a.router.onBytes("A>B", FakeNet.frame(react)); net.pump()
        assertNull(a.router.message(gone.id))
        assertTrue(a.rec.reactions.isEmpty())
        // …but the mesh still depends on this phone passing it on
        assertTrue(a.router.carries(react.id))
        net.connect("A", "C")
        assertTrue(net.nodes["C"]!!.router.carries(react.id))
    }
}
