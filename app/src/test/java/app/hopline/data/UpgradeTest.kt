package app.hopline.data

import app.hopline.mesh.Attachment
import app.hopline.mesh.Envelope
import app.hopline.mesh.Errand
import app.hopline.mesh.FakeNet
import app.hopline.mesh.Loc
import app.hopline.mesh.Message
import app.hopline.mesh.Person
import app.hopline.mesh.Quote
import app.hopline.mesh.Router
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A chat saved by 2.3 or older, made fit for 2.4: my old id becomes my new one, the old carry goes, my unsent group messages go out again. */
class UpgradeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L
    private val old = "k7m2p9qa"                  // this phone before 2.4
    private val me = "abcdefghijkmnpqr"           // this phone now
    private val bea = "b3a4b3a4"                  // a friend, still under her 2.3 id here
    private val former = setOf(old)

    private fun msg(id: String, from: String = old, to: String? = null, text: String = "words of $id", ts: Long = now - hour,
                    kind: String = if (to == null) Envelope.CHAT else Envelope.DM, status: String = Message.SENT, att: Attachment? = null) =
        Message(id, kind, from, if (from == old) "Asha" else "Bea", to, text, ts, att).also { it.status = status }.toJson()

    /** An envelope as 2.3 carried it: signed with the group's old key, which no phone checks any more. */
    private fun legacy(id: String, from: String = old, ts: Long = now - hour, kind: String = Envelope.CHAT) =
        JSONObject().put("id", id).put("k", kind).put("o", from).put("on", "Asha").put("ts", ts).put("h", 0)
            .put("p", JSONObject().put("text", "x")).put("s", "abcdef")

    /** What 2.3 saved: no "fmt", no "me". */
    private fun state(messages: List<JSONObject>, carry: List<JSONObject> = emptyList(), born: JSONObject = JSONObject()) =
        JSONObject().put("messages", JSONArray(messages)).put("carry", JSONArray(carry)).put("born", born)

    private fun up(s: JSONObject, left: Boolean = false) = Upgrade.state(s, me, former, now, left)!!

    // ---------------------------------------------------------------- my id

    @Test fun myOldIdBecomesMyNewOneWhereverItStands() {
        val mine = msg("m1").put("reached", JSONArray(listOf(bea)))
        val theirs = msg("t1", from = bea, text = "hi").put("reached", JSONArray(listOf(old)))
            .put("mn", JSONArray(listOf(old, bea)))
            .put("re", Quote("m1", "Asha", "words of m1", old).toJson())
            .put("reac", JSONObject().put(old, JSONObject().put("e", "👍").put("ts", now)).put(bea, JSONObject().put("e", "❤").put("ts", now)))
        val dmToMe = msg("d1", from = bea, to = old, text = "just you")
        val dmFromMe = msg("d2", to = bea, text = "and you")
        val request = Errand("errand000001", Errand.READ, JSONObject().put("url", "https://example.org"), old, "Asha", now - hour)
            .also { it.helper = bea; it.tried.add(old); it.pick = listOf(old) }.toJson()
        val helping = Errand("errand000002", Errand.READ, JSONObject(), bea, "Bea", now - hour).also { it.helper = old }.toJson()
        val s = state(listOf(mine, theirs, dmToMe, dmFromMe))
            .put("people", JSONArray(listOf(Person(old).also { it.name = "Asha" }.toJson(), Person(bea).also { it.name = "Bea" }.toJson())))
            .put("errands", JSONArray(listOf(request, helping)))
            .put("doneAt", JSONObject().put("errand000003", now))
            .put("hidden", JSONObject().put("gone00000001", now))

        val u = up(s)
        val m = u.getJSONArray("messages")
        assertEquals(me, m.getJSONObject(0).getString("from"))
        assertEquals(listOf(bea), listOf(m.getJSONObject(0).getJSONArray("reached").getString(0)))
        val t = m.getJSONObject(1)
        assertEquals(bea, t.getString("from"))
        assertEquals(me, t.getJSONArray("reached").getString(0))
        assertEquals(listOf(me, bea), (0 until 2).map { t.getJSONArray("mn").getString(it) })
        assertEquals(me, t.getJSONObject("re").getString("o"))
        // a key that is my id is swapped too: my reaction is still mine
        assertEquals(setOf(me, bea), t.getJSONObject("reac").keySet())
        assertEquals("👍", t.getJSONObject("reac").getJSONObject(me).getString("e"))
        assertEquals(me, m.getJSONObject(2).getString("to")); assertEquals(bea, m.getJSONObject(2).getString("from"))
        assertEquals(me, m.getJSONObject(3).getString("from")); assertEquals(bea, m.getJSONObject(3).getString("to"))
        assertEquals(listOf(me, bea), (0 until 2).map { u.getJSONArray("people").getJSONObject(it).getString("id") })
        val e = u.getJSONArray("errands")
        assertEquals(me, e.getJSONObject(0).getString("from")); assertEquals(bea, e.getJSONObject(0).getString("helper"))
        assertEquals(me, e.getJSONObject(0).getJSONArray("tried").getString(0)); assertEquals(me, e.getJSONObject(0).getJSONArray("pick").getString(0))
        assertEquals(me, e.getJSONObject(1).getString("helper"))
        // ids of messages, requests and files are what they were
        assertEquals(listOf("m1", "t1", "d1", "d2"), (0 until 4).map { m.getJSONObject(it).getString("id") })
        assertTrue(u.getJSONObject("doneAt").has("errand000003")); assertTrue(u.getJSONObject("hidden").has("gone00000001"))
        // nothing of the old id is left anywhere but in words
        assertFalse(u.toString().contains(old))
        // the state it was given is not changed
        assertEquals(old, s.getJSONArray("messages").getJSONObject(0).getString("from"))
    }

    @Test fun wordsPeopleWroteAreNeverTouched() {
        // someone may well have typed something that looks just like my old id
        val m = msg("m1", from = bea, text = old).put("fromName", old)
            .put("re", Quote("q1", old, old, old).toJson())
            .put("loc", Loc(1_000_000, 2_000_000, 5, old).toJson())
        val e = Errand("errand000001", Errand.READ, JSONObject(), old, old, now).also { it.result = old; it.title = old }.toJson()
        val s = state(listOf(m)).put("errands", JSONArray().put(e))
            .put("people", JSONArray().put(Person(bea).also { it.name = old }.toJson()))
            .put("group", JSONObject().put("n", old).put("v", 2))
        val u = up(s)
        val back = u.getJSONArray("messages").getJSONObject(0)
        assertEquals(old, back.getString("text")); assertEquals(old, back.getString("fromName"))
        assertEquals(old, back.getJSONObject("re").getString("n")); assertEquals(old, back.getJSONObject("re").getString("t"))
        assertEquals(me, back.getJSONObject("re").getString("o"))           // who wrote it is an id, not words
        assertEquals(old, back.getJSONObject("loc").getString("lbl"))
        val req = u.getJSONArray("errands").getJSONObject(0)
        assertEquals(me, req.getString("from"))
        assertEquals(old, req.getString("fromName")); assertEquals(old, req.getString("result")); assertEquals(old, req.getString("title"))
        assertEquals(old, u.getJSONArray("people").getJSONObject(0).getString("name"))
        assertEquals(old, u.getJSONObject("group").getString("n"))
        // and an id inside someone's words is no id at all
        val words = up(state(listOf(msg("m2", from = bea, text = "my old id was $old")))).getJSONArray("messages").getJSONObject(0)
        assertEquals("my old id was $old", words.getString("text"))
    }

    @Test fun theOldCarryGoesAndTheStateSaysWhoseItIs() {
        val s = state(listOf(msg("m1", status = Message.QUEUED)), listOf(legacy("m1"), legacy("x1", from = bea)), JSONObject().put("m1", now - hour))
            .put("shareInternet", false)
        val u = up(s)
        assertFalse(u.has("carry")); assertFalse(u.has("born"))
        assertEquals(Router.FMT, u.getInt("fmt")); assertEquals(me, u.getString("me"))
        assertFalse(u.getBoolean("shareInternet"))   // everything else is handed through
        // a state saved by 2.4 for this very phone needs nothing
        assertNull(Upgrade.state(JSONObject().put("fmt", Router.FMT).put("me", me), me, former, now, left = false))
        // nor one that names a phone this one never was (it is restored as it is, as ever)
        assertNull(Upgrade.state(JSONObject().put("fmt", Router.FMT).put("me", "zzzzzzzzzzzzzzzz"), me, former, now, left = false))
    }

    // ---------------------------------------------------------------- what goes out again

    @Test fun onlyMyUnsentGroupMessagesStillInTheirTimeGoOutAgain() {
        val att = Attachment.make("oldfile00001", "p.jpg", "image/jpeg", 100, 1, 0, 0, "")
        val messages = listOf(
            msg("chat01", status = Message.QUEUED),                                          // yes
            msg("file01", status = Message.QUEUED, kind = Envelope.FILE, att = att),         // yes: Core sends it again from its copy
            msg("place1", status = Message.QUEUED).put("loc", Loc(1, 2, 0, "").toJson()),    // yes: a place is a group message too
            msg("nobrn1", status = Message.QUEUED, ts = now - 2 * hour),                     // yes: no saved start, the sender's stamp counts
            msg("dm0001", to = bea, status = Message.QUEUED),                                // no: private — Bea's old id is gone from the air
            msg("pfile1", to = bea, status = Message.QUEUED, kind = Envelope.FILE, att = att), // no: a private file
            msg("sent01"),                                                                   // no: it got out
            msg("old001", status = Message.QUEUED, ts = now - 72 * hour),                    // no: its 48 h are over
            msg("gone01", status = Message.QUEUED),                                          // no: not carried any more (expired, or evicted)
            msg("thrs01", from = bea, status = Message.QUEUED),                              // no: not mine
            msg("nofile", status = Message.QUEUED, kind = Envelope.FILE),                    // no: a file message with no file
        )
        val carried = listOf("chat01", "file01", "place1", "nobrn1", "dm0001", "pfile1", "sent01", "old001", "thrs01", "nofile")
        val born = JSONObject()
        for (id in carried) if (id != "nobrn1") born.put(id, if (id == "old001") now - 72 * hour else now - hour)
        val s = state(messages, carried.map { legacy(it, ts = if (it == "nobrn1") now - 2 * hour else now - hour) }, born)
        val u = up(s)
        val listed = u.getJSONArray("reissue").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertEquals(listOf("chat01", "file01", "place1", "nobrn1"), listed)
        // the edge of the 48 h: in at 48 h, out a moment after
        val edge = state(listOf(msg("e1", status = Message.QUEUED), msg("e2", status = Message.QUEUED)), listOf(legacy("e1"), legacy("e2")),
            JSONObject().put("e1", now - Router.CARRY_MS).put("e2", now - Router.CARRY_MS - 1))
        assertEquals(listOf("e1"), up(edge).getJSONArray("reissue").let { a -> (0 until a.length()).map { a.getString(it) } })
        // with nothing to send, the list is there and empty: my open requests are still asked again (Router.reissueQueued)
        assertEquals(0, up(state(listOf(msg("s1")))).getJSONArray("reissue").length())
    }

    @Test fun aGroupThatWasLeftSendsNothingAgain() {
        val s = state(listOf(msg("chat01", status = Message.QUEUED)), listOf(legacy("chat01")), JSONObject().put("chat01", now - hour))
        val u = up(s, left = true)
        assertFalse(u.has("reissue"))
        assertEquals(me, u.getJSONArray("messages").getJSONObject(0).getString("from"))
        assertFalse(u.has("carry"))
    }

    // ---------------------------------------------------------------- again, and again

    @Test fun upgradingTwiceChangesNothing() {
        val s = state(listOf(msg("chat01", status = Message.QUEUED), msg("t1", from = bea).put("reached", JSONArray().put(old))),
            listOf(legacy("chat01")), JSONObject().put("chat01", now - hour))
        val once = up(s)
        assertNull(Upgrade.state(once, me, former, now, left = false))
        // the same old state, upgraded again (the first save didn't reach the disk), gives the same result
        assertEquals(once.toString(), up(s).toString())
    }

    @Test fun aStateOfAnIdThisPhoneHadBeforeIsSwappedAndItsCarryKept() {
        // a 2.4 state saved under an id this phone had to give up (its key file couldn't be read)
        val before = "zyxwvutsrqpnmkjh"
        val carried = legacy("m1", from = before).put("pk", "key").put("c", "sealed")
        val s = JSONObject().put("fmt", Router.FMT).put("me", before)
            .put("messages", JSONArray().put(msg("m1", from = before, status = Message.QUEUED)))
            .put("carry", JSONArray().put(carried)).put("born", JSONObject().put("m1", now))
        val u = Upgrade.state(s, me, setOf(old, before), now, left = false)!!
        assertEquals(me, u.getString("me")); assertEquals(Router.FMT, u.getInt("fmt"))
        assertEquals(me, u.getJSONArray("messages").getJSONObject(0).getString("from"))
        // its envelopes are signed as they are: they stay exactly so
        assertEquals(carried.toString(), u.getJSONArray("carry").getJSONObject(0).toString())
        assertTrue(u.getJSONObject("born").has("m1"))
        assertFalse(u.has("reissue"))
        assertNull(Upgrade.state(u, me, setOf(old, before), now, left = false))
    }

    // ---------------------------------------------------------------- the history

    @Test fun historyPagesAreRewrittenOnceAndOnlyWhenTheyChange() {
        val dir = File(tmp.root, "history/ab12cd34")
        val h = History(dir)
        assertTrue(h.append(listOf(msg("a1"), msg("a2", from = bea, text = old))))
        assertTrue(h.append(listOf(msg("b1", from = bea), msg("b2", from = bea))))    // nothing of mine in here
        assertTrue(h.append(listOf(msg("c1", from = bea, to = old), msg("c2", to = bea))))
        val untouched = File(dir, "000002.json").let { it.readText() to it.lastModified() }
        File(dir, "000002.json").setLastModified(1_000_000_000_000L)

        assertTrue(h.rewrite { Upgrade.page(it, me, former) })
        val a = h.read(1); val c = h.read(3)
        assertEquals(me, a[0].getString("from")); assertEquals(old, a[1].getString("text"))   // words stay words
        assertEquals(me, c[0].getString("to")); assertEquals(me, c[1].getString("from"))
        assertEquals(untouched.first, File(dir, "000002.json").readText())
        assertEquals("a page with nothing to change is not written", 1_000_000_000_000L, File(dir, "000002.json").lastModified())
        // the same messages, in the same order, under the same ids
        assertEquals(listOf("a1", "a2", "b1", "b2", "c1", "c2"), (1..3).flatMap { h.read(it) }.map { it.getString("id") })
        // a second run finds nothing to do
        assertNull(Upgrade.page(JSONArray(h.read(1)), me, former))
        var asked = 0
        assertTrue(h.rewrite { asked++; Upgrade.page(it, me, former) })
        assertEquals(3, asked)
        // nothing to swap at all: no page is even looked at twice
        assertNull(Upgrade.page(JSONArray().put(msg("z1", from = bea)), me, emptySet()))
        assertNull(Upgrade.page(JSONArray().put(msg("z1")), me, setOf(me)))
    }

    // ---------------------------------------------------------------- end to end

    @Test fun anUpgradedChatRestoresAsMineAndMyUnsentMessageReachesTheGroup() {
        val net = FakeNet(); net.node("A"); net.node("B")
        val meNow = net.id("A")
        val t0 = net.now - hour
        val s = JSONObject()
            .put("messages", JSONArray(listOf(
                Message("oldchat00001", Envelope.CHAT, old, "A", null, "sent before the update", t0).also { it.status = Message.QUEUED }.toJson(),
                Message("oldchat00002", Envelope.CHAT, old, "A", null, "already out", t0 + 1).also { it.status = Message.SENT; it.reached.add(bea) }.toJson(),
                Message("theirs000001", Envelope.CHAT, bea, "Bea", null, "from Bea", t0 + 2).toJson())))
            .put("carry", JSONArray(listOf(legacy("oldchat00001", ts = t0), legacy("theirs000001", from = bea, ts = t0 + 2))))
            .put("born", JSONObject().put("oldchat00001", t0).put("theirs000001", t0 + 2))
            .put("people", JSONArray(listOf(Person(old).also { it.name = "A" }.toJson(), Person(bea).also { it.name = "Bea" }.toJson())))
        val u = Upgrade.state(s, meNow, former, net.now, left = false)!!
        val a = net.nodes["A"]!!.router
        a.restore(u)
        // my old words are mine, and I am not a stranger in my own people list
        assertEquals(listOf(meNow, meNow, bea), a.messages.map { it.from })
        assertNull(a.people[old]); assertNull(a.people[meNow]); assertNotNull(a.people[bea])
        assertEquals(0, a.carrySize())
        val files = a.reissueQueued()
        assertTrue(files.isEmpty())
        val fresh = a.messages.single { it.text == "sent before the update" }
        assertTrue(fresh.id.startsWith("$meNow."))
        assertEquals(t0, fresh.ts)
        assertEquals(listOf("sent before the update", "already out", "from Bea"), a.messages.map { it.text })
        net.connect("A", "B")
        assertTrue(net.texts("B").contains("sent before the update"))
        assertFalse(net.texts("B").contains("already out"))
        assertEquals(Message.SENT, fresh.status)
        // what is saved now is a 2.4 state: nothing to upgrade the next time
        assertNull(Upgrade.state(a.snapshot(), meNow, former, net.now, left = false))
    }
}
