package app.hopline.mesh

import app.hopline.core.Crypto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leaving keeps the chat: what a group's saved state becomes when this phone leaves, what a
 * router opened on it can (not) do, and what a rejoin picks up — and never sends.
 */
class ArchiveTest {

    private fun url() = JSONObject().put("url", "https://example.org/page")
    private fun copy(j: JSONObject) = JSONObject(j.toString())
    private fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    private fun ids(a: JSONArray?): List<String> = objects(a).map { it.optString("id") }
    private fun kinds(carry: JSONArray?): Set<String> = objects(carry).map { it.optString("k") }.toSet()
    private fun count(hay: String, needle: String) = hay.split(needle).size - 1

    /**
     * A (this phone) in a line A—B—C, after a busy day: messages both ways, a private chat, a
     * reaction, a message deleted here, someone else's private message passing through, internet
     * requests in every state — and, once off the air, one more message that never left.
     */
    private class Day(val net: FakeNet) {
        lateinit var fromB: Message; lateinit var mine: Message; lateinit var dm: Message
        lateinit var oops: Message; lateinit var unsent: Message
        lateinit var answered: Errand; lateinit var open: Errand; lateinit var theirs: Errand
        /** A's full state the moment it left, and each of its messages as text — taken before anything strips it. */
        lateinit var before: JSONObject
        lateinit var beforeText: String
        lateinit var messagesBefore: List<String>
        var leftAt = 0L
        fun archive(): JSONObject = Archive.strip(before, "A", "A", leftAt, leftAt)
    }

    private fun day(): Day {
        val net = FakeNet(); net.line("A", "B", "C")
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router; val c = net.nodes["C"]!!.router
        val d = Day(net)
        a.setCaps(Errand.CAP_SMS); b.setCaps(Errand.CAP_WX); net.pump()
        d.fromB = b.sendChat("hello from B"); net.pump()
        net.now += 1_000
        d.mine = a.sendChat("hi all"); net.pump()
        net.now += 1_000
        c.sendDm("B", "private between C and B"); net.pump()            // A only carries this one
        d.dm = a.sendDm("B", "just you"); net.pump()
        b.sendReaction(b.message(d.mine.id)!!, "👍"); net.pump()
        net.now += 1_000
        d.oops = b.sendChat("oops"); net.pump()
        assertEquals(1, a.hideMessages(listOf(d.oops.id)).size)            // deleted for me
        // a request of mine that was answered, one of mine still open, and one of B's my phone is on
        d.answered = a.requestErrand(Errand.WX, JSONObject().put("lat", 1_000_000L).put("lng", 2_000_000L), selfCaps = 0); net.pump()
        net.advance(3_000)
        assertTrue(b.completeErrand(d.answered.id, true, "Weather near you", JSONObject().put("t", "Now: clear, 12°C"))); net.pump()
        assertEquals(Errand.DONE, d.answered.status)
        d.open = a.requestErrand(Errand.READ, url(), selfCaps = 0); net.pump()
        d.theirs = b.requestErrand(Errand.SEND, JSONObject().put("to", "+919800000000").put("text", "tell mum I am fine")); net.pump()
        net.advance(3_000)
        assertEquals(listOf(d.theirs.id), net.nodes["A"]!!.rec.errands.map { it.id })
        a.markSendOpened(d.theirs.id)
        // off the air — and one more message that never leaves this phone
        net.disconnect("A", "B")
        net.now += 1_000
        d.unsent = a.sendChat("never left my phone")
        assertEquals(Message.QUEUED, d.unsent.status)
        d.leftAt = net.now
        d.before = a.snapshot()
        d.beforeText = d.before.toString()
        val m = d.before.getJSONArray("messages")
        d.messagesBefore = (0 until m.length()).map { m.get(it).toString() }
        return d
    }

    // ---------------------------------------------------------------- what an archive keeps

    @Test fun anArchiveKeepsEveryMessageByteForByteAndNotesYouLeftOnce() {
        val d = day()
        assertEquals(listOf("hello from B", "hi all", "just you", "never left my phone"),
            objects(d.before.getJSONArray("messages")).map { it.getString("text") })
        val archive = d.archive()
        val kept = archive.getJSONArray("messages")
        assertEquals(d.messagesBefore.size + 1, kept.length())
        for (i in d.messagesBefore.indices) assertEquals(d.messagesBefore[i], kept.get(i).toString())
        // reactions, ticks and who it reached ride along untouched
        val mine = objects(kept).single { it.getString("id") == d.mine.id }
        assertEquals("👍", mine.getJSONObject("reac").getJSONObject("B").getString("e"))
        assertEquals(setOf("B", "C"), (0 until mine.getJSONArray("reached").length()).map { mine.getJSONArray("reached").getString(it) }.toSet())
        assertEquals(Message.QUEUED, objects(kept).single { it.getString("id") == d.unsent.id }.getString("status"))
        // …plus exactly one line saying this phone left
        val note = kept.getJSONObject(kept.length() - 1)
        assertEquals("local.left.${d.leftAt}", note.getString("id"))
        assertEquals(Message.LEFT, note.getString("kind"))
        assertEquals("A", note.getString("from")); assertEquals("A", note.getString("fromName"))
        assertEquals(d.leftAt, note.getLong("ts")); assertFalse(note.has("to")); assertEquals("", note.getString("text"))
        assertEquals(1, objects(kept).count { it.getString("kind") == Message.LEFT })
        // the state it was made from is not touched
        assertEquals(d.beforeText, d.before.toString())
    }

    @Test fun anArchiveKeepsPeopleDeletionsAndWhatWasAlreadyAnswered() {
        val d = day()
        val archive = d.archive()
        assertEquals(d.before.getJSONArray("people").toString(), archive.getJSONArray("people").toString())
        assertEquals(setOf("B", "C"), ids(archive.getJSONArray("people")).toSet())
        assertEquals(d.before.getJSONObject("hidden").toString(), archive.getJSONObject("hidden").toString())
        assertTrue(archive.getJSONObject("hidden").has(d.oops.id))
        assertEquals(d.before.getJSONObject("doneAt").toString(), archive.getJSONObject("doneAt").toString())
        assertTrue(archive.getJSONObject("doneAt").has(d.answered.id))
        // my own finished request stays, answer and all
        val mineDone = objects(archive.getJSONArray("errands")).single { it.getString("id") == d.answered.id }
        assertEquals(Errand.DONE, mineDone.getString("status"))
        assertEquals("Now: clear, 12°C", Errand.fromJson(mineDone).answer()!!.getString("t"))
    }

    @Test fun anArchiveDropsTheBacklogOtherPeoplesRequestsAndWorkInProgress() {
        val d = day()
        // what was there to drop
        assertTrue(kinds(d.before.getJSONArray("carry")).containsAll(setOf(Envelope.CHAT, Envelope.DM, Envelope.RECEIPT, Envelope.REACT, Envelope.ERRAND)))
        assertTrue(d.beforeText.contains("private between C and B"))
        assertEquals(listOf(d.theirs.id), (0 until d.before.getJSONArray("running").length()).map { d.before.getJSONArray("running").getString(it) })
        assertEquals(1, d.before.getJSONArray("sendOpened").length())
        assertEquals(setOf(d.answered.id, d.open.id, d.theirs.id), ids(d.before.getJSONArray("errands")).toSet())
        assertTrue(ids(d.before.getJSONArray("carry")).contains(d.unsent.id))

        val archive = d.archive()
        // carried on: only the envelopes of messages this chat shows that already left this phone
        assertEquals(setOf(d.fromB.id, d.mine.id, d.dm.id), ids(archive.getJSONArray("carry")).toSet())
        assertEquals(setOf(d.fromB.id, d.mine.id, d.dm.id), archive.getJSONObject("born").keySet())
        for (id in archive.getJSONObject("born").keySet()) assertEquals(d.before.getJSONObject("born").getLong(id), archive.getJSONObject("born").getLong(id))
        assertFalse("a message that never left is not sent later either", ids(archive.getJSONArray("carry")).contains(d.unsent.id))
        assertFalse("a deleted message's envelope goes too", ids(archive.getJSONArray("carry")).contains(d.oops.id))
        // other people's words and details are gone from the file
        val text = archive.toString()
        assertFalse(text.contains("private between C and B"))
        assertFalse(text.contains("+919800000000")); assertFalse(text.contains("tell mum"))
        assertFalse(text.contains(d.theirs.id))
        assertEquals(2, count(d.beforeText, "never left my phone")); assertEquals(1, count(text, "never left my phone"))
        // and nothing is left "in progress"
        assertFalse(archive.has("running")); assertFalse(archive.has("sendOpened"))
        // my own requests stay — the one still open too, exactly as it was: the group goes on
        // carrying it, and its answer must find it here after a rejoin
        assertEquals(listOf(d.answered.id, d.open.id), ids(archive.getJSONArray("errands")))
        val open = objects(archive.getJSONArray("errands")).single { it.getString("id") == d.open.id }
        assertEquals(objects(d.before.getJSONArray("errands")).single { it.getString("id") == d.open.id }.toString(), open.toString())
        assertEquals(Errand.WAITING, open.getString("status"))
    }

    @Test fun envelopesPastTheir48HoursAreNotKeptEither() {
        val d = day()
        val late = Archive.strip(d.before, "A", "A", d.leftAt, d.leftAt + Router.CARRY_MS + 3_600_000L)
        assertEquals(0, late.getJSONArray("carry").length())
        assertEquals(0, late.getJSONObject("born").length())
        assertEquals(d.messagesBefore.size + 1, late.getJSONArray("messages").length())
    }

    @Test fun strippingAnArchiveAgainChangesNothing() {
        val d = day()
        val once = d.archive()
        val text = once.toString()
        assertEquals(text, Archive.strip(once, "A", "A", d.leftAt, d.leftAt).toString())
        assertEquals(text, once.toString())
        // the same holds for the archive as it comes back from its file
        val fromFile = Archive.strip(JSONObject(text), "A", "A", d.leftAt, d.leftAt)
        assertTrue(fromFile.similar(once))
        assertEquals(text, fromFile.toString())
        // and for a paused group, whose state is stripped straight from its file
        assertTrue(Archive.strip(JSONObject(d.beforeText), "A", "A", d.leftAt, d.leftAt).similar(once))
    }

    @Test fun anArchiveOpenedAndSavedAgainIsStillTheSameArchive() {
        // Deleting a message in a left group's chat saves the open router's state through strip again.
        val d = day()
        val archive = d.archive()
        val net = FakeNet(); net.now = d.net.now + 30 * 86_400_000L
        val a = net.node("A"); a.router.restore(copy(archive))
        val again = Archive.strip(a.router.snapshot(), "A", "A", d.leftAt, net.now)
        assertEquals(ids(archive.getJSONArray("messages")), ids(again.getJSONArray("messages")))
        assertEquals(1, objects(again.getJSONArray("messages")).count { it.getString("kind") == Message.LEFT })
        for ((x, y) in objects(archive.getJSONArray("messages")).zip(objects(again.getJSONArray("messages")))) assertTrue(x.similar(y))
        assertEquals(ids(archive.getJSONArray("errands")), ids(again.getJSONArray("errands")))
        assertTrue(again.getJSONObject("hidden").has(d.oops.id))
        assertEquals(0, again.getJSONArray("carry").length())       // a month on, nothing is being passed round any more
    }

    @Test fun aGroupJoinedAgainSaysSoOnceAndAGroupNeverLeftSaysNothing() {
        val d = day()
        val net = FakeNet(); net.now = d.net.now + 3_600_000L
        val a = net.node("A"); a.router.restore(copy(d.archive()))
        val back = Archive.rejoined(a.router)
        assertNotNull(back)
        assertEquals(Message.REJOINED, back!!.kind)
        assertEquals(back.id, a.router.messages.last().id)
        // the radio comes up again, the app is killed and starts again: still the one line
        net.now += 60_000
        assertNull(Archive.rejoined(a.router))
        val restarted = FakeNet().also { it.now = net.now + 60_000 }.node("A")
        restarted.router.restore(copy(a.router.snapshot()))
        assertNull(Archive.rejoined(restarted.router))
        assertEquals(1, restarted.router.messages.count { it.kind == Message.REJOINED })
        // left once more and joined once more: every leaving gets its own answer, in order
        net.now += 86_400_000L
        val second = Archive.strip(a.router.snapshot(), "A", "A", net.now, net.now)
        val b = FakeNet().also { it.now = net.now + 5_000 }.node("A"); b.router.restore(copy(second))
        assertNotNull(Archive.rejoined(b.router))
        assertEquals(listOf(Message.LEFT, Message.REJOINED, Message.LEFT, Message.REJOINED),
            b.router.messages.filter { it.kind == Message.LEFT || it.kind == Message.REJOINED }.map { it.kind })
        // a group this phone never left has no "You left" to answer…
        val member = FakeNet().also { it.now = d.net.now }.node("A"); member.router.restore(copy(d.before))
        assertNull(Archive.rejoined(member.router))
        assertTrue(member.router.messages.none { it.kind == Message.REJOINED })
        // …and neither has one whose "You left" the person deleted
        val c = FakeNet().also { it.now = d.net.now + 3_600_000L }.node("A"); c.router.restore(copy(d.archive()))
        assertEquals(1, c.router.hideMessages(listOf("local.left.${d.leftAt}")).size)
        assertNull(Archive.rejoined(c.router))
    }

    @Test fun theYouLeftLineIsWrittenOncePerLeavingAndNeverAfterItWasDeleted() {
        val d = day()
        val first = d.archive()
        val net = FakeNet(); net.now = d.net.now + 3_600_000L
        val a = net.node("A"); a.router.restore(copy(first))
        // back in the group…
        val back = a.router.addLocalNotice(Message.REJOINED)
        assertNotNull(back)
        net.now += 86_400_000L
        // …and out again: the chat tells the whole story, in order
        val second = Archive.strip(a.router.snapshot(), "A", "A", net.now, net.now)
        val story = objects(second.getJSONArray("messages")).filter { it.getString("kind") != Envelope.CHAT && it.getString("kind") != Envelope.DM }
        assertEquals(listOf(Message.LEFT, Message.REJOINED, Message.LEFT), story.map { it.getString("kind") })
        assertEquals(listOf("local.left.${d.leftAt}", back!!.id, "local.left.${net.now}"), story.map { it.getString("id") })
        // a line the person deleted is not written back by a later tidy-up
        val b = FakeNet().also { it.now = net.now }.node("A"); b.router.restore(copy(second))
        assertEquals(1, b.router.hideMessages(listOf("local.left.${net.now}")).size)
        val tidy = Archive.strip(b.router.snapshot(), "A", "A", net.now, net.now)
        assertFalse(ids(tidy.getJSONArray("messages")).contains("local.left.${net.now}"))
        assertTrue(ids(tidy.getJSONArray("messages")).contains("local.left.${d.leftAt}"))
    }

    @Test fun aStateFromAnOlderVersionOrWithBadRecordsStillArchives() {
        // nothing at all: the group never had a message
        val empty = Archive.strip(JSONObject(), "A", "A", 5_000, 6_000)
        assertEquals(listOf("local.left.5000"), ids(empty.getJSONArray("messages")))
        assertEquals(0, empty.getJSONArray("carry").length()); assertEquals(0, empty.getJSONArray("errands").length())
        // a 2.1-era file: no born, no doneAt, no hidden — a "done" list instead — with rubbish mixed in
        val now = 1_700_000_000_000L
        val good = Message("m1", Envelope.CHAT, "B", "Bea", null, "keep me", now - 1_000).toJson()
        val env = JSONObject().put("id", "m1").put("k", Envelope.CHAT).put("o", "B").put("ts", now - 1_000).put("p", JSONObject().put("text", "keep me"))
        val old = JSONObject()
            .put("messages", JSONArray().put(good).put(JSONObject().put("garbage", true)).put("words").put(7))
            .put("carry", JSONArray().put(env).put("not an envelope").put(JSONObject().put("id", "x")))
            .put("born", "not a map")
            .put("errands", JSONArray().put("x").put(JSONObject()).put(JSONObject().put("from", "A")).put(JSONObject().put("from", "A").put("id", "e9").put("status", Errand.EXPIRED)))
            .put("people", JSONArray().put(Person("B").also { it.name = "Bea" }.toJson()).put("nobody"))
            .put("done", JSONArray().put("e1"))
            .put("hidden", 5)
            .put("shareInternet", false)
            .put("somethingNewer", JSONObject().put("kept", true))
        val text = old.toString()
        val out = Archive.strip(old, "A", "Asha", now, now)
        assertEquals(text, old.toString())
        val kept = out.getJSONArray("messages")
        assertEquals(5, kept.length())                                  // every entry handed through, plus the note
        assertEquals(good.toString(), kept.get(0).toString())
        assertEquals(listOf("m1"), ids(out.getJSONArray("carry")))      // the sender's stamp stands in for a missing "born"
        assertEquals(0, out.getJSONObject("born").length())
        assertEquals(listOf("e9"), ids(out.getJSONArray("errands")))    // mine, with an id; other people's and the broken ones go
        assertEquals("e1", out.getJSONArray("done").getString(0))
        assertFalse(out.getBoolean("shareInternet"))
        assertTrue("what this version doesn't know is not thrown away", out.getJSONObject("somethingNewer").getBoolean("kept"))
        // and a router opens on it without losing the good message
        val net = FakeNet(); net.now = now + 1_000
        val a = net.node("A"); a.router.restore(copy(out))
        assertEquals(listOf("keep me", ""), net.texts("A"))
        assertEquals(listOf(Envelope.CHAT, Message.LEFT), a.router.messages.map { it.kind })
        assertEquals("Bea", a.router.people["B"]!!.name)
    }

    // ---------------------------------------------------------------- a router opened on an archive

    @Test fun aRouterOpenedOnAnArchiveSendsNothing() {
        val d = day()
        val frames = ArrayList<String>(); var disconnects = 0
        val spy = object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long { frames.add(String(bytes, Charsets.UTF_8)); return frames.size.toLong() }
            override fun disconnect(linkId: String) { disconnects++ }
        }
        val rec = d.net.Recorder()
        val r = Router(Identity("A", "A"), Group(FakeNet.CODE, "Trek"), spy, rec) { d.net.now + 86_400_000L }
        r.restore(copy(d.archive()))
        assertEquals(listOf("hello from B", "hi all", "just you", "never left my phone", ""), r.messages.map { it.text })
        assertEquals("👍", r.message(d.mine.id)!!.reactions["B"])
        assertTrue(r.isHidden(d.oops.id))
        // my request that was still open sits there with the finished one — a day on, and nothing has ticked it
        assertEquals(setOf(d.answered.id, d.open.id), r.errands.keys)
        assertEquals(Errand.WAITING, r.errands[d.open.id]!!.status)
        assertFalse(r.isRunning(d.theirs.id)); assertFalse(r.sendWasOpened(d.theirs.id))
        // reading it, deleting from it and saving it put nothing on the air and wake nothing up
        assertEquals(1, r.hideMessages(listOf(d.fromB.id)).size)
        assertEquals(1, r.hideMessages(listOf(d.unsent.id)).size)
        r.snapshot()
        assertTrue(frames.isEmpty()); assertEquals(0, disconnects); assertTrue(r.links.isEmpty())
        assertTrue(rec.shown.isEmpty()); assertTrue(rec.errands.isEmpty()); assertTrue(rec.answers.isEmpty())
        assertTrue(rec.aborts.isEmpty()); assertTrue(rec.reactions.isEmpty()); assertTrue(rec.files.isEmpty())
        assertFalse("restoring is not a change worth saving", Router(Identity("A", "A"), Group(FakeNet.CODE, "Trek"), spy, rec) { d.net.now }
            .also { it.restore(copy(d.archive())) }.takeDirty())

        // With no link there was nothing it could have sent on. So give it one — the app never
        // does, a left group has no radio — and see what such a router has to offer a friend who
        // has nothing: only the envelopes of messages the chat shows that had already left this
        // phone. Not the message that never went out, no request, no receipt, no reaction.
        r.onLinkUp("L", "zz", "Friend")
        val myNonce = r.links["L"]!!.myNonce
        r.onBytes("L", JSONObject().put("t", "hello").put("id", "zz").put("nonce", "n1n1n1n1n1n1n1n1").put("v", 3).toString().toByteArray())
        r.onBytes("L", JSONObject().put("t", "proof").put("proof", Crypto.hmacHex(r.group.key, "$myNonce|zz")).toString().toByteArray())
        assertTrue(r.links["L"]!!.authed)
        r.onBytes("L", JSONObject().put("t", "inv").put("n", 1).put("i", 0).put("ids", JSONArray()).toString().toByteArray())
        val sent = frames.map { JSONObject(it) }
        val filled = sent.filter { it.optString("t") == "fill" }.flatMap { objects(it.getJSONArray("envs")) }
        assertEquals(setOf(d.fromB.id, d.mine.id, d.dm.id), filled.map { it.getString("id") }.toSet())
        assertEquals(3, filled.size)
        val live = sent.filter { it.optString("t") == "env" }.map { it.getJSONObject("e") }
        assertTrue((filled + live).none { it.getString("k") in setOf(Envelope.ERRAND, Envelope.ERRAND_ACK, Envelope.RECEIPT, Envelope.REACT) })
        assertTrue(live.all { it.getString("k") == Envelope.PRESENCE })
        assertFalse(frames.any { it.contains("never left my phone") })
        assertFalse(frames.any { it.contains(d.open.id) || it.contains(d.answered.id) })
        assertTrue(rec.shown.isEmpty()); assertTrue(rec.errands.isEmpty()); assertTrue(rec.answers.isEmpty())
    }

    // ---------------------------------------------------------------- joining again

    @Test fun rejoiningWithin48HoursShowsEachOldMessageOnceNotifiesNothingAndFillsInWhatWasSaid() {
        val d = day(); val net = d.net
        val archive = d.archive()
        net.now += 2 * 3_600_000L
        net.nodes["B"]!!.router.sendChat("said while A was away"); net.pump()
        net.now += 60_000
        val a2 = net.node("A")                                          // the same phone, back on the group's code
        a2.router.restore(copy(archive))
        assertNotNull(a2.router.addLocalNotice(Message.REJOINED))
        net.connect("A", "B")
        repeat(3) { net.now += Router.SYNC_MS + 1_000; net.tickAll() }   // and a few rounds of sync on top
        val got = a2.router.messages
        assertEquals(got.map { it.id }.toSet().size, got.size)
        assertEquals(listOf("hello from B", "hi all", "just you", "never left my phone", "", "said while A was away", ""), got.map { it.text })
        assertEquals(listOf(Message.LEFT, Envelope.CHAT, Message.REJOINED), got.takeLast(3).map { it.kind })
        // only what is new is announced: no old message, no old reaction, no old answer, no deleted message
        assertEquals(listOf("said while A was away"), a2.rec.shown.map { it.text })
        assertTrue(a2.rec.reactions.isEmpty()); assertTrue(a2.rec.answers.isEmpty()); assertTrue(a2.rec.errands.isEmpty())
        assertNull(a2.router.message(d.oops.id))
        assertEquals("👍", a2.router.message(d.mine.id)!!.reactions["B"])
        // and the others don't see anything of A's twice either
        assertEquals(1, net.texts("B").count { it == "hi all" }); assertEquals(1, net.texts("C").count { it == "hi all" })
    }

    @Test fun aMessageStillWaitingWhenILeftIsNeverSentByARejoin() {
        // What would happen if the state were kept whole: the rejoin quietly sends it, hours late.
        val whole = day()
        val w = whole.net.node("A"); w.router.restore(copy(whole.before))
        assertTrue(w.router.carries(whole.unsent.id))
        whole.net.connect("A", "B")
        assertTrue(whole.net.texts("B").contains("never left my phone"))

        // What leaving does instead: the message stays in the chat, its envelope is gone.
        val d = day(); val net = d.net
        net.now += 3_600_000L
        val a2 = net.node("A"); a2.router.restore(copy(d.archive()))
        val m = a2.router.message(d.unsent.id)!!
        assertEquals(Message.QUEUED, m.status)
        assertFalse(a2.router.carries(m.id))
        assertTrue("a message that did go out is still carried for the others", a2.router.carries(d.mine.id))
        net.connect("A", "B")
        repeat(3) { net.now += Router.SYNC_MS + 1_000; net.tickAll() }   // ticks retry unsent messages; sync offers the backlog
        for (id in listOf("B", "C")) assertFalse(net.texts(id).contains("never left my phone"))
        assertEquals(Message.QUEUED, m.status)
        assertFalse(a2.router.carries(m.id))
    }

    @Test fun aRequestThisPhoneAlreadyFinishedIsNotRunAgainAfterARejoin() {
        fun leftAfterTexting(): Pair<FakeNet, JSONObject> {
            val net = FakeNet(); net.line("A", "B")
            val a = net.nodes["A"]!!.router
            a.setCaps(Errand.CAP_SMS); net.pump()
            val e = net.nodes["B"]!!.router.requestErrand(Errand.SEND, JSONObject().put("to", "+919800000000").put("text", "I am OK")); net.pump()
            net.advance(3_000)
            assertEquals(1, net.nodes["A"]!!.rec.errands.size)
            assertTrue(a.completeErrand(e.id, true, "Text sent to +919800000000", JSONObject().put("t", "Sent at 14:02"))); net.pump()
            assertEquals(Errand.DONE, e.status)
            net.disconnect("A", "B")
            val archive = Archive.strip(a.snapshot(), "A", "A", net.now, net.now)
            assertTrue(archive.getJSONObject("doneAt").has(e.id))
            assertEquals("their request — a phone number, a text — is not kept", 0, archive.getJSONArray("errands").length())
            assertFalse(archive.toString().contains("+919800000000"))
            net.now += 3_600_000L
            return net to archive
        }
        fun rejoin(net: FakeNet, archive: JSONObject): FakeNet.Node {
            val a2 = net.node("A"); a2.router.restore(archive)
            a2.router.setCaps(Errand.CAP_SMS); a2.router.resumeErrands()
            net.connect("A", "B")                                       // B still carries its request and hands it back
            net.advance(60_000)
            return a2
        }
        val (net, archive) = leftAfterTexting()
        assertTrue("the family is not texted twice", rejoin(net, copy(archive)).rec.errands.isEmpty())
        // It is the kept list of answered requests that prevents it: without it, the text goes out again.
        val (net2, archive2) = leftAfterTexting()
        val forgetful = copy(archive2).also { it.remove("doneAt") }
        assertEquals(1, rejoin(net2, forgetful).rec.errands.size)
    }

    // ---------------------------------------------------------------- a request of mine that was still open when I left

    @Test fun aRequestStillOpenWhenILeftIsAnsweredWhileIAmAwayAndTheAnswerIsThereAfterARejoin() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!
        // nobody has signal yet: the request travels with the group
        val e = a.requestErrand(Errand.READ, url(), selfCaps = 0); net.pump()
        assertEquals(Errand.WAITING, e.status)
        net.disconnect("A", "B")
        val archive = Archive.strip(a.snapshot(), "A", "A", net.now, net.now)
        assertEquals(listOf(e.id), ids(archive.getJSONArray("errands")))
        // Leaving says nothing on the radio: B still carries the request. An hour later B gets
        // signal, picks it up and answers — to a phone that is no longer there.
        net.now += 3_600_000L
        b.router.setCaps(Errand.CAP_READ); net.pump()
        net.advance(40_000)
        assertEquals(listOf(e.id), b.rec.errands.map { it.id })
        assertTrue(b.router.completeErrand(e.id, true, "Example page", JSONObject().put("t", "the page, as text"))); net.pump()

        // A rejoins a little later. Before any friend is in range, nothing has happened to the request…
        net.now += 600_000L
        val a2 = net.node("A"); a2.router.restore(copy(archive))
        assertNotNull(Archive.rejoined(a2.router))
        a2.router.resumeErrands(); a2.router.tick()
        assertEquals(Errand.WAITING, a2.router.errands[e.id]!!.status)
        assertTrue(a2.rec.answers.isEmpty()); assertTrue(a2.rec.errands.isEmpty())
        // …and when B hands its backlog over, the answer finds the request it belongs to.
        net.connect("A", "B")
        repeat(3) { net.now += Router.SYNC_MS + 1_000; net.tickAll() }
        assertEquals(listOf(Errand.DONE), a2.rec.answers.map { it.status })
        val mine = a2.router.errands[e.id]!!
        assertEquals(Errand.DONE, mine.status)
        assertEquals("Example page", mine.title)
        assertEquals("the page, as text", mine.answer()!!.getString("t"))
        // nobody does it a second time, and this phone doesn't run it itself
        assertEquals(1, b.rec.errands.size); assertTrue(a2.rec.errands.isEmpty())
        assertEquals(1, a2.router.errands.size)
    }

    @Test fun aRequestStillOpenWhenILeftIsSaidToHaveRunOutOnceIfItsTimePassedWhileIWasAway() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        val e = a.requestErrand(Errand.READ, url(), ttlMs = 10 * 60_000L, selfCaps = 0); net.pump()
        net.disconnect("A", "B")
        val archive = Archive.strip(a.snapshot(), "A", "A", net.now, net.now)
        // in the kept chat nothing ticks: it sits there as it was, however long, and nobody is told anything
        val kept = FakeNet().also { it.now = net.now + 30 * 86_400_000L }.node("A"); kept.router.restore(copy(archive))
        assertEquals(Errand.WAITING, kept.router.errands[e.id]!!.status)
        assertTrue(kept.rec.answers.isEmpty())
        // back in the group after its ten minutes are over: said once, honestly, and never run
        net.now += 11 * 60_000L
        val a2 = net.node("A"); a2.router.restore(copy(archive)); a2.router.resumeErrands()
        assertTrue(a2.rec.errands.isEmpty()); assertTrue(a2.rec.answers.isEmpty())
        a2.router.tick()
        assertEquals(listOf(Errand.EXPIRED), a2.rec.answers.map { it.status })
        net.connect("A", "B")
        repeat(3) { net.now += Router.SYNC_MS + 1_000; net.tickAll() }
        assertEquals("said once", 1, a2.rec.answers.size)
        assertEquals(Errand.EXPIRED, a2.router.errands[e.id]!!.status)
        assertTrue(a2.rec.errands.isEmpty()); assertTrue(net.nodes["B"]!!.rec.errands.isEmpty())
    }

    @Test fun aRequestThatHadFailedIsNotAnnouncedASecondTimeAfterARejoin() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!; val b = net.nodes["B"]!!
        b.router.setCaps(Errand.CAP_WX); net.pump()
        val e = a.router.requestErrand(Errand.WX, JSONObject().put("lat", 1_000_000L).put("lng", 2_000_000L), selfCaps = 0); net.pump()
        net.advance(3_000)
        assertTrue(b.router.completeErrand(e.id, false, "Couldn't get the weather", JSONObject().put("t", "The weather service didn't answer."), why = "upstream")); net.pump()
        assertEquals(listOf(Errand.FAILED), a.rec.answers.map { it.status })
        val answeredAt = e.answeredAt
        net.disconnect("A", "B")
        val archive = Archive.strip(a.router.snapshot(), "A", "A", net.now, net.now)
        assertEquals(listOf(e.id), ids(archive.getJSONArray("errands")))
        // Leaving let go of the envelope that brought the answer; B still carries it, and hands it back.
        net.now += 3 * 3_600_000L
        val a2 = net.node("A"); a2.router.restore(copy(archive))
        b.rec.log.clear()
        net.connect("A", "B")
        assertTrue(filled(b, "B>A") > 0)
        repeat(3) { net.now += Router.SYNC_MS + 1_000; net.tickAll() }
        val mine = a2.router.errands[e.id]!!
        assertTrue("an old \"couldn't\" is not news, and is not told twice", a2.rec.answers.isEmpty())
        assertEquals(Errand.FAILED, mine.status)
        assertEquals("…nor does it look as if it had just been answered", answeredAt, mine.answeredAt)
        assertEquals("The weather service didn't answer.", mine.answer()!!.getString("t"))
        // A real answer, though, still gets through to a request that had failed.
        val good = signed(a2.router, "lateanswer001", Envelope.DM, "B", net.now, JSONObject().put("text", "Weather near you")
            .put("er", JSONObject().put("eid", e.id).put("ok", true).put("ty", Errand.WX).put("title", "Weather near you")
                .put("z", Gz.pack(JSONObject().put("t", "Now: clear, 12°C"))).put("by", "B")), to = "A")
        handOver(a2, "A>B", good); net.pump()
        assertEquals(listOf(Errand.DONE), a2.rec.answers.map { it.status })
        assertEquals(Errand.DONE, mine.status)
        assertEquals("Now: clear, 12°C", mine.answer()!!.getString("t"))
    }

    // ---------------------------------------------------------------- "You left" / "You rejoined" and a clock that was put back

    private fun notes(r: Router): List<String> = r.messages.filter { it.kind == Message.LEFT || it.kind == Message.REJOINED }.map { it.kind }

    @Test fun youRejoinedIsWrittenOnceEvenWhenTheClockWasPutBackSinceILeft() {
        val d = day()
        // The clock ran twenty minutes fast when I left (days without a network); it has been put right since.
        val net = FakeNet(); net.now = d.leftAt - 20 * 60_000L
        val a = net.node("A"); a.router.restore(copy(d.archive()))
        val back = Archive.rejoined(a.router)
        assertNotNull(back)
        assertEquals("under the line it answers, whatever the clock says now", listOf(Message.LEFT, Message.REJOINED), a.router.messages.takeLast(2).map { it.kind })
        assertTrue(back!!.sortKey > d.leftAt)
        // Every start of the app, and every switch back to the group, asks again: still the one line.
        assertNull(Archive.rejoined(a.router))
        net.now += 60_000
        assertNull(Archive.rejoined(a.router))
        val restarted = FakeNet().also { it.now = net.now + 120_000 }.node("A")
        restarted.router.restore(copy(a.router.snapshot()))
        assertNull(Archive.rejoined(restarted.router))
        assertEquals(listOf(Message.LEFT, Message.REJOINED), notes(restarted.router))
    }

    @Test fun youLeftIsFiledUnderTheRejoinBeforeItEvenWhenTheClockWasPutBackInBetween() {
        val d = day()
        // Rejoined an hour later, by a clock that was running fast…
        val net = FakeNet(); net.now = d.leftAt + 3_600_000L
        val a = net.node("A"); a.router.restore(copy(d.archive()))
        val back = Archive.rejoined(a.router)!!
        // …which was then put back half an hour — and after that the group was left again.
        val leftAgain = net.now - 30 * 60_000L
        assertTrue(leftAgain < back.ts)
        val second = Archive.strip(a.router.snapshot(), "A", "A", leftAgain, leftAgain)
        assertTrue("the leaving keeps its own name, so it is written once", ids(second.getJSONArray("messages")).contains("local.left.$leftAgain"))
        assertEquals(second.toString(), Archive.strip(second, "A", "A", leftAgain, leftAgain).toString())
        val b = FakeNet().also { it.now = leftAgain + 60_000 }.node("A"); b.router.restore(copy(second))
        assertEquals("the story reads in the order it happened", listOf(Message.LEFT, Message.REJOINED, Message.LEFT), notes(b.router))
        // and the second leaving gets its answer, like the first
        assertNotNull(Archive.rejoined(b.router))
        assertEquals(listOf(Message.LEFT, Message.REJOINED, Message.LEFT, Message.REJOINED), notes(b.router))
        assertNull(Archive.rejoined(b.router))
    }

    // ---------------------------------------------------------------- what the group hands back after a rejoin

    /** How many envelopes [n] handed over its link [link] to fill gaps, going by its own log ("filling 12 for B>A"). */
    private fun filled(n: FakeNet.Node, link: String): Int =
        n.rec.log.filter { it.startsWith("filling ") && it.endsWith(" for $link") }.sumOf { it.removePrefix("filling ").substringBefore(' ').toInt() }

    /** An envelope as [origin] would have signed it at [ts] — handed to a phone the way a friend's backlog arrives. */
    private fun signed(r: Router, id: String, kind: String, origin: String, ts: Long, payload: JSONObject, to: String? = null): Envelope {
        val j = JSONObject().put("id", id).put("k", kind).put("o", origin).put("on", origin).put("ts", ts).put("h", 2).put("p", payload)
        if (to != null) j.put("to", to)
        return Envelope(j).also { it.sign(r.group.key) }
    }

    private fun handOver(n: FakeNet.Node, link: String, vararg envs: Envelope) {
        n.router.onBytes(link, JSONObject().put("t", "fill").put("envs", JSONArray(envs.map { copy(it.json) })).toString().toByteArray())
    }

    @Test fun afterARejoinWhatTheGroupHandsBackIsCarriedAgainAndTheNextSyncFillsNothing() {
        val d = day(); val net = d.net
        val b = net.nodes["B"]!!; val c = net.nodes["C"]!!
        val shownOnB = b.rec.shown.size; val shownOnC = c.rec.shown.size
        net.now += 2 * 3_600_000L
        val a2 = net.node("A")                                          // the same phone, back on the group's code
        a2.router.restore(copy(d.archive()))
        assertNotNull(Archive.rejoined(a2.router))
        val before = a2.router.messages.map { it.id }
        // leaving let go of my own receipts and requests; the group still carries them
        val myReceipt = "r.${d.fromB.id}.A"
        assertFalse(a2.router.carries(myReceipt))
        assertTrue(b.router.carries(myReceipt))
        assertEquals(3, a2.router.carrySize())

        net.connect("A", "B")
        assertTrue("B hands over what A's inventory doesn't list", filled(b, "B>A") > 0)
        // carried again — my receipts, my requests, other people's backlog, and the deleted message's envelope
        assertTrue(a2.router.carries(myReceipt))
        assertTrue(a2.router.carries("r.${d.oops.id}.A"))
        assertEquals(b.router.carrySize(), a2.router.carrySize())
        val carried = a2.router.snapshot().getJSONArray("carry")
        assertTrue(kinds(carried).containsAll(setOf(Envelope.CHAT, Envelope.DM, Envelope.RECEIPT, Envelope.REACT, Envelope.ERRAND)))
        assertEquals(2, objects(carried).count { it.getString("k") == Envelope.ERRAND && it.getString("o") == "A" })

        // from the second sync on there is nothing left to hand over, either way
        repeat(4) {
            b.rec.log.clear(); a2.rec.log.clear()
            net.now += Router.SYNC_MS + 1_000; net.tickAll()
            assertEquals(0, filled(b, "B>A")); assertEquals(0, filled(a2, "A>B"))
        }

        // nothing came back to life on the way: no message twice, none new, nothing announced, nothing run
        assertEquals(before, a2.router.messages.map { it.id })
        assertEquals(before.toSet().size, before.size)
        assertTrue(a2.rec.shown.isEmpty()); assertTrue(a2.rec.reactions.isEmpty()); assertTrue(a2.rec.answers.isEmpty())
        assertTrue(a2.rec.errands.isEmpty()); assertTrue(a2.rec.files.isEmpty()); assertTrue(a2.rec.aborts.isEmpty())
        assertEquals(setOf(d.answered.id, d.open.id), a2.router.errands.values.filter { it.from == "A" }.map { it.id }.toSet())
        assertEquals(Errand.DONE, a2.router.errands[d.answered.id]!!.status)
        // the request that was open is still open and still waiting: its own envelope came back as backlog, not as news
        assertEquals(Errand.WAITING, a2.router.errands[d.open.id]!!.status)
        assertEquals("👍", a2.router.message(d.mine.id)!!.reactions["B"])
        // the message deleted here is carried for the others, and stays deleted — through a restart too
        assertTrue(a2.router.carries(d.oops.id))
        assertNull(a2.router.message(d.oops.id)); assertTrue(a2.router.isHidden(d.oops.id))
        assertFalse(net.texts("A").contains("oops"))
        val restarted = FakeNet().also { it.now = net.now + 60_000 }.node("A")
        restarted.router.restore(copy(a2.router.snapshot()))
        assertTrue(restarted.router.carries(d.oops.id)); assertNull(restarted.router.message(d.oops.id))
        assertEquals(before, restarted.router.messages.map { it.id })
        // my message that never left is still unsent, and still not carried
        assertEquals(Message.QUEUED, a2.router.message(d.unsent.id)!!.status)
        assertFalse(a2.router.carries(d.unsent.id))
        // and the others were shown nothing a second time
        assertEquals(shownOnB, b.rec.shown.size); assertEquals(shownOnC, c.rec.shown.size)
        for (id in listOf("B", "C")) {
            assertEquals(1, net.texts(id).count { it == "hi all" })
            assertFalse(net.texts(id).contains("never left my phone"))
        }
    }

    @Test fun aMessageOfMineDeletedHereComesBackOnlyAsBacklogNeverAsAMessage() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!
        val regret = a.sendChat("said too much"); net.pump()
        assertEquals(Message.SENT, regret.status)
        assertEquals(1, a.hideMessages(listOf(regret.id)).size)
        assertTrue("deleted here, still carried for the others", a.carries(regret.id))
        net.disconnect("A", "B")
        val archive = Archive.strip(a.snapshot(), "A", "A", net.now, net.now)
        assertFalse(ids(archive.getJSONArray("carry")).contains(regret.id))
        net.now += 3_600_000L
        val a2 = net.node("A"); a2.router.restore(copy(archive))
        net.connect("A", "B")
        assertTrue(a2.router.carries(regret.id))
        assertNull(a2.router.message(regret.id)); assertTrue(a2.router.isHidden(regret.id))
        assertFalse(net.texts("A").contains("said too much"))
        assertTrue(a2.rec.shown.isEmpty())
        repeat(3) {
            b.rec.log.clear()
            net.now += Router.SYNC_MS + 1_000; net.tickAll()
            assertEquals(0, filled(b, "B>A"))
        }
        assertEquals(1, net.texts("B").count { it == "said too much" })
        assertEquals(1, b.rec.shown.size)
    }

    @Test fun aMessageMarkedUnsentThatAFriendHandsBackDidGetOutAndIsNotSentAgain() {
        // The radio delivered it, but the phone left before it heard so: saved as unsent, envelope dropped.
        val net = FakeNet(); net.line("A", "B", "C")
        val a = net.nodes["A"]!!.router
        val m = a.sendChat("made it out after all"); net.pump()
        val state = copy(a.snapshot())
        objects(state.getJSONArray("messages")).single { it.getString("id") == m.id }.put("status", Message.QUEUED).put("reached", JSONArray())
        val envelope = Envelope(objects(state.getJSONArray("carry")).single { it.getString("id") == m.id })
        net.disconnect("A", "B")
        val archive = Archive.strip(state, "A", "A", net.now, net.now)
        assertFalse(ids(archive.getJSONArray("carry")).contains(m.id))
        net.now += 3_600_000L
        val a2 = net.node("A"); a2.router.restore(copy(archive))
        val mine = a2.router.message(m.id)!!
        assertEquals(Message.QUEUED, mine.status); assertFalse(a2.router.carries(m.id))
        // a phone that has nothing links up: the unsent message stays unsent
        val n = net.node("N"); net.connect("A", "N")
        net.now += Router.SYNC_MS + 1_000; a2.router.tick(); n.router.tick(); net.pump()
        assertEquals(Message.QUEUED, mine.status); assertTrue(net.texts("N").isEmpty())
        // then a friend hands its envelope back: that friend has it, which proves it left this phone
        handOver(a2, "A>N", envelope); net.pump()
        assertEquals(Message.SENT, mine.status)
        assertTrue(a2.router.carries(m.id))
        assertTrue("carried, not flooded", net.texts("N").isEmpty())
        net.disconnect("A", "N")
        val shownOnB = net.nodes["B"]!!.rec.shown.size; val shownOnC = net.nodes["C"]!!.rec.shown.size
        net.connect("A", "B")
        repeat(3) { net.now += Router.SYNC_MS + 1_000; net.tickAll() }
        assertEquals(Message.SENT, mine.status)
        assertEquals(setOf("B", "C"), mine.reached)                     // their receipts, still carried by B, count again
        assertEquals(1, a2.router.messages.count { it.id == m.id })
        for (id in listOf("B", "C")) assertEquals(1, net.texts(id).count { it == "made it out after all" })
        assertEquals(shownOnB, net.nodes["B"]!!.rec.shown.size); assertEquals(shownOnC, net.nodes["C"]!!.rec.shown.size)
        assertTrue(a2.rec.shown.isEmpty())
    }

    @Test fun whatIsHandedBackIsCarriedOnlyInsideItsTimeOnlyWhenGenuineAndNeverPassedOn() {
        val net = FakeNet(); net.line("A", "B"); net.node("C"); net.connect("A", "C")
        val a = net.nodes["A"]!!; val c = net.nodes["C"]!!
        val r = a.router
        val hour = 3_600_000L
        fun receipt(id: String, age: Long) = signed(r, id, Envelope.RECEIPT, "A", net.now - age, JSONObject().put("m", "m$id").put("by", "A"), to = "B")
        fun reaction(id: String, age: Long) = signed(r, id, Envelope.REACT, "A", net.now - age, JSONObject().put("m", "nosuchmessage").put("e", "👍"))
        val freshReceipt = receipt("r.one.A", 23 * hour); val staleReceipt = receipt("r.two.A", 25 * hour)
        val freshReaction = reaction("reactfresh001", 47 * hour); val staleReaction = reaction("reactstale001", 49 * hour)
        val fromTomorrow = reaction("reacttomorrow", -hour)
        val forged = reaction("reactforged01", hour).also { it.json.put("s", "00") }
        val request = signed(r, "errandmine001", Envelope.ERRAND, "A", net.now - hour,
            JSONObject().put("eid", "abcdef123456").put("type", Errand.READ).put("args", url()).put("helper", "").put("rv", Errand.EV).put("exp", net.now + hour).put("at", net.now - hour))
        val beacon = signed(r, "presencemine1", Envelope.PRESENCE, "A", net.now - 1_000, JSONObject().put("n", "A"))
        handOver(a, "A>B", freshReceipt, staleReceipt, freshReaction, staleReaction, fromTomorrow, forged, request, beacon)
        net.pump()
        assertTrue(r.carries(freshReceipt.id)); assertFalse("a receipt is carried for a day", r.carries(staleReceipt.id))
        assertTrue(r.carries(freshReaction.id)); assertFalse("anything else for 48 hours", r.carries(staleReaction.id))
        assertFalse("a stamp from the future says nothing about age", r.carries(fromTomorrow.id))
        assertFalse(r.carries(forged.id))
        assertTrue(r.carries(request.id)); assertFalse(r.carries(beacon.id))
        assertEquals(3, r.carrySize())
        // carried from its own stamp: it lives no longer than it would have, had it never been let go
        val born = r.snapshot().getJSONObject("born")
        assertEquals(net.now - 23 * hour, born.getLong(freshReceipt.id)); assertEquals(net.now - 47 * hour, born.getLong(freshReaction.id))
        // not read: my own request is not opened again, nothing is shown, nothing is run
        assertTrue(r.errands.isEmpty()); assertTrue(r.messages.isEmpty())
        assertTrue(a.rec.shown.isEmpty()); assertTrue(a.rec.errands.isEmpty()); assertTrue(a.rec.reactions.isEmpty())
        // not passed on: the phone on my other link hears nothing of it until it asks for the backlog
        assertEquals(0, c.router.carrySize())
        // a garbled copy does not stand in the way of the real one
        handOver(a, "A>B", reaction("reactforged01", hour))
        assertTrue(r.carries("reactforged01"))
        // a second copy changes nothing
        handOver(a, "A>B", freshReceipt, freshReaction); net.pump()
        assertEquals(4, r.carrySize()); assertEquals(0, c.router.carrySize())
        // and each goes when its time is up, like the rest of the backlog
        net.now += 2 * hour; net.tickAll()
        assertFalse(r.carries(freshReceipt.id)); assertFalse(r.carries(freshReaction.id))
        assertTrue(r.carries(request.id))
    }

    @Test fun aMessageFiledInTheHistoryOrDeletedIsCarriedAgainButNeverShownAgain() {
        val net = FakeNet()
        val state = JSONObject()
            .put("spilled", JSONObject().put("filedmine0001", net.now - 1_000).put("filedtheirs01", net.now - 1_000))
            .put("hidden", JSONObject().put("deletedtheirs", net.now - 1_000))
        val a2 = net.node("A"); a2.router.restore(state)
        net.node("B"); net.connect("A", "B")
        val r = a2.router
        val mine = signed(r, "filedmine0001", Envelope.CHAT, "A", net.now - 5_000, JSONObject().put("text", "mine, filed away"))
        val theirs = signed(r, "filedtheirs01", Envelope.CHAT, "B", net.now - 5_000, JSONObject().put("text", "theirs, filed away"))
        val deleted = signed(r, "deletedtheirs", Envelope.DM, "B", net.now - 5_000, JSONObject().put("text", "theirs, deleted here"), to = "A")
        val tooOld = signed(r, "deletedtheirs", Envelope.DM, "B", net.now - Router.CARRY_MS - 5_000, JSONObject().put("text", "theirs, deleted here"), to = "A")
        handOver(a2, "A>B", tooOld)
        assertEquals(0, r.carrySize())
        handOver(a2, "A>B", mine, theirs, deleted); net.pump()
        assertTrue(r.carries(mine.id)); assertTrue(r.carries(theirs.id)); assertTrue(r.carries(deleted.id))
        assertTrue(r.messages.isEmpty()); assertTrue(a2.rec.shown.isEmpty())
        // not taken in again, so no receipt goes out for it — and nothing is passed on
        assertEquals(3, r.carrySize())
        assertEquals(0, net.nodes["B"]!!.router.carrySize())
        // a restart later they are still only backlog
        val restarted = FakeNet().also { it.now = net.now + 60_000 }.node("A")
        restarted.router.restore(copy(r.snapshot()))
        assertEquals(3, restarted.router.carrySize()); assertTrue(restarted.router.messages.isEmpty())
    }

    @Test fun thePiecesOfAPhotoISentAreKeptAgainInsteadOfBeingOfferedAtEverySync() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!
        val pieces = (0 until 3).map { i -> java.util.Base64.getEncoder().encodeToString(ByteArray(Router.CHUNK_RAW) { (it + i).toByte() }) }
        val att = Attachment.make(Crypto.randomId(12), "photo.jpg", "image/jpeg", 3L * Router.CHUNK_RAW, pieces.size, 100, 75, "tb")
        val m = a.sendFile(att, pieces, "the view"); net.pump()
        assertTrue(b.router.fileComplete(att)); assertEquals(1, b.rec.files.size)
        net.disconnect("A", "B")
        val archive = Archive.strip(a.snapshot(), "A", "A", net.now, net.now)
        net.now += 3_600_000L
        val a2 = net.node("A"); a2.router.restore(copy(archive))        // leaving dropped the pieces: a fresh, empty piece store
        assertEquals(0, a2.router.fileProgress(att))
        b.rec.log.clear()
        net.connect("A", "B")
        assertTrue("B hands the pieces back", filled(b, "B>A") >= 3)
        assertTrue("my own pieces are kept again, to hand on", a2.router.fileComplete(att))
        assertTrue("it is my own photo: nothing to put together, nothing to announce", a2.rec.files.isEmpty())
        assertTrue(a2.rec.shown.isEmpty())
        repeat(3) {
            b.rec.log.clear()
            net.now += Router.SYNC_MS + 1_000; net.tickAll()
            assertEquals(0, filled(b, "B>A"))
        }
        assertEquals(1, a2.router.messages.count { it.id == m.id })
        assertEquals(1, b.rec.files.size); assertEquals(1, net.texts("B").count { it == "the view" })
        // a piece that is not what its name says is not kept
        val a3 = FakeNet().also { it.now = net.now }
        a3.line("A", "B")
        val bad = signed(a3.nodes["A"]!!.router, Envelope.chunkId(att.fid, 0), Envelope.CHUNK, "A", a3.now, JSONObject().put("fid", att.fid).put("i", 1).put("d", pieces[1]))
        a3.nodes["A"]!!.router.onBytes("A>B", JSONObject().put("t", "env").put("e", bad.json).toString().toByteArray())
        assertFalse(a3.nodes["A"]!!.router.chunks.has(bad.id))
    }

    // ---------------------------------------------------------------- hardening for a rejoin

    @Test fun restoreDropsCarryPastItsTimeEvenBeforeTheFirstTick() {
        val net = FakeNet(); net.line("A", "B")
        val m = net.nodes["B"]!!.router.sendChat("old news"); net.pump()
        val full = net.nodes["A"]!!.router.snapshot().toString()         // saved whole: nothing stripped
        assertEquals(setOf(Envelope.CHAT, Envelope.RECEIPT), kinds(JSONObject(full).getJSONArray("carry")))

        // a day on: receipts have had their 24 h, words are still carried
        val day = FakeNet(); day.now = net.now + Router.RECEIPT_MS + 3_600_000L
        val a1 = day.node("A"); a1.router.restore(JSONObject(full))
        assertEquals(setOf(Envelope.CHAT), kinds(a1.router.snapshot().getJSONArray("carry")))
        assertTrue(a1.router.carries(m.id))

        // weeks on: nothing is carried — and a phone that never saw the message links up BEFORE any tick
        val late = FakeNet(); late.now = net.now + 20 * 86_400_000L
        val a2 = late.node("A"); a2.router.restore(JSONObject(full))
        assertEquals(0, a2.router.carrySize())
        assertFalse(a2.router.carries(m.id))
        assertEquals(listOf("old news"), late.texts("A"))                // the chat itself is all there
        val n = late.node("N"); late.connect("A", "N")
        assertTrue("an ancient message must not be handed on as new", late.texts("N").isEmpty())
        assertTrue(n.rec.shown.isEmpty())
        assertEquals(0, n.router.carrySize())
        // and it is still known here: handed back by a phone with a slow clock, it is not shown twice
        val again = Envelope(JSONObject(JSONObject(full).getJSONArray("carry").getJSONObject(0).toString()))
        a2.router.onBytes("A>N", JSONObject().put("t", "fill").put("envs", JSONArray().put(again.json)).toString().toByteArray())
        assertEquals(listOf("old news"), late.texts("A"))
        assertTrue(a2.rec.shown.isEmpty())
    }

    @Test fun resumeErrandsSkipsARequestThatRanOutWhileThePhoneWasAway() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["B"]!!.router.setCaps(Errand.CAP_READ); net.pump()
        val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, url(), ttlMs = 10 * 60_000L); net.pump()
        net.advance(3_000)
        assertEquals(1, net.nodes["B"]!!.rec.errands.size)
        val snap = net.nodes["B"]!!.router.snapshot().toString()
        assertEquals(e.id, JSONObject(snap).getJSONArray("running").getString(0))
        // back in time: carried on with (as before)
        val soon = FakeNet(); soon.now = net.now + 60_000
        val b1 = soon.node("B"); b1.router.restore(JSONObject(snap)); b1.router.setCaps(Errand.CAP_READ); b1.router.resumeErrands()
        assertEquals(listOf(e.id), b1.rec.errands.map { it.id })
        // back after its deadline: not fetched for nobody, now or later
        val late = FakeNet(); late.now = net.now + 11 * 60_000L
        val b2 = late.node("B"); b2.router.restore(JSONObject(snap)); b2.router.setCaps(Errand.CAP_READ); b2.router.resumeErrands()
        assertTrue(b2.rec.errands.isEmpty())
        assertFalse(b2.router.isRunning(e.id))
        repeat(10) { late.now += 2_000; b2.router.pollErrands() }
        assertTrue(b2.rec.errands.isEmpty())
        assertEquals(0, b2.router.snapshot().getJSONArray("running").length())
    }

    @Test fun myOwnRequestThatRanOutWhileAwayEndsHonestlyInsteadOfRunning() {
        val net = FakeNet(); val a = net.node("A")
        val e = a.router.requestErrand(Errand.READ, url(), ttlMs = 10 * 60_000L, selfCaps = Errand.CAP_READ)
        assertEquals(1, a.rec.errands.size)                              // running on my own signal
        val snap = a.router.snapshot().toString()
        val late = FakeNet(); late.now = net.now + 11 * 60_000L
        val a2 = late.node("A"); a2.router.restore(JSONObject(snap)); a2.router.resumeErrands()
        assertTrue(a2.rec.errands.isEmpty())
        a2.router.pollErrands()
        assertEquals(Errand.EXPIRED, a2.router.errands[e.id]!!.status)
        assertEquals(listOf(Errand.EXPIRED), a2.rec.answers.map { it.status })
    }

    // ---------------------------------------------------------------- "You left" / "You rejoined"

    @Test fun aLocalNoticeNeverReachesTheAir() {
        val frames = ArrayList<JSONObject>()
        var now = 1_700_000_000_000L
        val rec = FakeNet().Recorder()
        val r = Router(Identity("aa", "Asha"), Group(FakeNet.CODE, "Trek"), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long { frames.add(JSONObject(String(bytes, Charsets.UTF_8))); return frames.size.toLong() }
            override fun disconnect(linkId: String) {}
        }, rec) { now }
        r.sendChat("a real message")
        val left = r.addLocalNotice(Message.LEFT)!!
        now += 1_000
        val back = r.addLocalNotice(Message.REJOINED)!!
        assertEquals("local.left.${now - 1_000}", left.id); assertEquals("local.rejoined.$now", back.id)
        for (n in listOf(left, back)) {
            assertTrue(n.isNotice); assertFalse(n.isRename); assertFalse(n.isPersonal); assertTrue(n.isGroup)
            assertEquals("aa", n.from); assertEquals("", n.text); assertEquals(Message.GROUP_CHAT, n.chatKey("aa"))
            assertFalse(r.carries(n.id))
        }
        assertEquals(listOf(Envelope.CHAT, Message.LEFT, Message.REJOINED), r.messages.map { it.kind })
        assertTrue("worth saving", r.takeDirty())
        assertNull("the very same line twice", r.addLocalNotice(Message.REJOINED))
        assertNull("only these two kinds", r.addLocalNotice(Envelope.CHAT))
        assertNull(r.addLocalNotice(Message.NOTICE))
        assertEquals(3, r.messages.size)

        // a friend links up with nothing: everything this phone carries is offered and filled…
        r.onLinkUp("L", "zz", "Friend")
        val myNonce = r.links["L"]!!.myNonce
        r.onBytes("L", JSONObject().put("t", "hello").put("id", "zz").put("nonce", "n1n1n1n1n1n1n1n1").put("v", 3).toString().toByteArray())
        r.onBytes("L", JSONObject().put("t", "proof").put("proof", Crypto.hmacHex(r.group.key, "$myNonce|zz")).toString().toByteArray())
        assertTrue(r.links["L"]!!.authed)
        r.onBytes("L", JSONObject().put("t", "inv").put("n", 1).put("i", 0).put("ids", JSONArray()).toString().toByteArray())
        // …then ticks: presence, the inventory again, the retry of whatever is still unsent
        repeat(3) {
            now += Router.SYNC_MS + 1_000; r.tick()
            r.onBytes("L", JSONObject().put("t", "inv").put("n", 1).put("i", 0).put("ids", JSONArray()).toString().toByteArray())
        }
        val all = frames.joinToString("\n") { it.toString() }
        assertTrue(all.contains("a real message"))
        assertTrue(frames.any { it.optString("t") == "inv" } && frames.any { it.optString("t") == "fill" })
        assertFalse(all.contains("local."))
        assertFalse(all.contains(Message.REJOINED)); assertFalse(all.contains("\"${Message.LEFT}\""))
        val snap = r.snapshot()
        assertFalse(ids(snap.getJSONArray("carry")).any { it.startsWith("local.") })
        assertEquals(1, snap.getJSONArray("carry").length())
        assertEquals(3, snap.getJSONArray("messages").length())
        assertTrue("never notified, never counted", rec.shown.isEmpty())
    }

    // ---------------------------------------------------------------- history is for keeps

    @Test fun historyDoesNotExpireWithTime() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        net.nodes["B"]!!.router.sendChat("from B"); net.pump()
        a.sendChat("from A"); a.sendDm("B", "just between us"); net.pump()
        val before = a.messages.map { it.id }
        assertEquals(3, before.size)
        assertTrue(a.carrySize() > 0)
        net.now += 400L * 86_400_000L
        net.tickAll(); net.tickAll()
        assertEquals(0, a.carrySize())                                   // the backlog went after its 48 h…
        assertEquals(before, a.messages.map { it.id })                   // …the chat did not
        assertEquals(listOf("from B", "from A", "just between us"), net.texts("A"))
        // nor after a restart a year later
        val fresh = FakeNet(); fresh.now = net.now + 86_400_000L
        val a2 = fresh.node("A"); a2.router.restore(copy(a.snapshot())); a2.router.tick()
        assertEquals(listOf("from B", "from A", "just between us"), fresh.texts("A"))
        assertTrue(a2.router.takeOverflow().isEmpty())
    }
}
