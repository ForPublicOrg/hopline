package app.hopline.mesh

import app.hopline.core.Crypto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * "Only admins may send" as the phones keep it: what a member can't post (and what still goes), what a phone shows,
 * holds back and lets in late, what becomes of my own posts when the rules turn against them, founding, and leaving
 * as the only admin.
 */
class AdminSendTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    private fun r(net: FakeNet, label: String): Router = net.nodes[label]!!.router
    private fun rec(net: FakeNet, label: String): FakeNet.Recorder = net.nodes[label]!!.rec
    private fun copy(j: JSONObject) = JSONObject(j.toString())

    /** [label], an admin, lets only admins send, and every phone linked hears it. */
    private fun restrict(net: FakeNet, label: String) { assertEquals(RoleChange.DONE, r(net, label).setOnlyAdmins(true)); net.pump() }

    /** A group post by [from], as a 2.4 phone sends one: it has no idea whether it may. */
    private fun post(from: String, text: String, ts: Long): Envelope = FakeNet.envelope(from, Envelope.CHAT, JSONObject().put("text", text), ts)

    /** The posts [r] holds back ("held" in its saved state). */
    private fun held(r: Router): Set<String> = r.snapshot().optJSONObject("held")?.keySet()?.toSet() ?: emptySet()

    /** What [r] carries, by id. */
    private fun carried(r: Router): Map<String, JSONObject> =
        r.snapshot().getJSONArray("carry").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.associateBy { it.getString("id") } }

    /** The envelope of [id] as [r] carries it, ready to hand to another phone. */
    private fun envelopeOf(r: Router, id: String): Envelope = Envelope(copy(carried(r).getValue(id)))

    /** Frames that put an envelope on the air. */
    private fun onAir(net: FakeNet): List<String> = net.frames!!.filter { JSONObject(it).optString("t") in setOf("env", "fill") }

    /** [envs] handed to [label]'s phone by a friend's ([FakeNet.hand]), and whatever that sets off on the net. */
    private fun handTo(net: FakeNet, label: String, vararg envs: Envelope) { FakeNet.hand(r(net, label), *envs); net.pump() }

    /** A photo someone sent: its message, its pieces (each signed by them), and its file id. */
    private class Photo(val meta: Envelope, val pieces: List<Envelope>, val fid: String)

    private fun photo(from: String, ts: Long, size: Int = 30_000): Photo {
        val bytes = ByteArray(size) { (it % 251).toByte() }
        val key = Crypto.randomBytes(32)
        val fid = FakeNet.idOf(from) + Crypto.randomId(8)
        val pieces = ArrayList<String>()
        var i = 0
        while (i < bytes.size) {
            val end = minOf(bytes.size, i + Router.CHUNK_RAW)
            pieces.add(Crypto.sealPiece(key, pieces.size, bytes.copyOfRange(i, end))); i = end
        }
        val att = Attachment.make(fid, "photo.jpg", "image/jpeg", bytes.size.toLong(), pieces.size, 100, 75, "tb", key = key, sha = Crypto.sha256Hex(bytes))
        val meta = FakeNet.envelope(from, Envelope.FILE, JSONObject().put("text", "the view").put("att", att.json), ts)
        return Photo(meta, pieces.mapIndexed { n, c -> FakeNet.envelope(from, Envelope.CHUNK, JSONObject(), ts, id = Envelope.chunkId(fid, n), piece = c) }, fid)
    }

    // ---------------------------------------------------------------- sending

    @Test fun `a member can't post once only admins may send, and nothing goes on the air`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        val b = r(net, "B")
        assertFalse(b.mayPost())
        val carry = b.carrySize(); val pieces = b.chunks.ids().size; val messages = b.messages.map { it.id }
        net.frames!!.clear()
        assertNull(b.sendChat("can I still say something?"))
        assertNull(b.sendLocation(Loc.of(12.97, 77.59)!!))
        val (att, sealed) = FakeNet.makeFile(b, ByteArray(30_000) { 7 })
        assertNull(b.sendFile(att, sealed, "the view"))
        net.pump()
        assertEquals(carry, b.carrySize()); assertEquals(pieces, b.chunks.ids().size)
        assertEquals(messages, b.messages.map { it.id })
        assertTrue(net.frames!!.isEmpty())
        assertTrue(r(net, "A").messages.none { it.from == b.me.id })
        assertTrue(rec(net, "B").log.any { it == "only admins may send in this group: not sent" })
        // the admin can, as ever
        assertTrue(r(net, "A").mayPost())
        val said = r(net, "A").sendChat("only me now")!!; net.pump()
        assertNotNull(b.message(said.id))
    }

    @Test fun `a member's photo, file and place are refused the same way, with no piece kept`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        val b = r(net, "B")
        val photo = FakeNet.makeFile(b, ByteArray(40_000) { 3 })
        val doc = FakeNet.makeFile(b, ByteArray(20_000) { 5 }, name = "plan.pdf", mime = "application/pdf", thumb = "")
        for ((att, pieces) in listOf(photo, doc)) {
            assertNull(att.name, b.sendFile(att, pieces, "caption"))
            for (i in 0 until att.chunks) assertFalse(att.name, b.chunks.has(Envelope.chunkId(att.fid, i)))
            assertNull(att.name, b.fileMessage(att.fid))
            assertFalse(att.name, b.isMine(att.fid))
        }
        assertNull(b.sendLocation(Loc.of(30.1, 78.2, 15, "camp")!!))
        assertTrue(b.messages.none { it.from == b.me.id })
        // the same photo to the admin alone goes
        val (att, pieces) = FakeNet.makeFile(b, ByteArray(40_000) { 3 })
        assertNotNull(b.sendFile(att, pieces, "just for you", to = net.id("A"))); net.pump()
        assertNotNull(r(net, "A").fileMessage(att.fid))
        assertNotNull(b.sendLocation(Loc.of(30.1, 78.2)!!, to = net.id("A")))
    }

    @Test fun `a member can still message privately, react, confirm, say goodbye and hello, and rename`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        val a = r(net, "A"); val b = r(net, "B")
        val from = a.sendChat("only admins now")!!; net.pump()
        assertTrue("confirmed", b.me.id in from.reached)
        val dm = b.sendDm(a.me.id, "just you")!!; net.pump()
        assertEquals(Message.DELIVERED, dm.status)
        assertEquals("just you", a.message(dm.id)!!.text)
        assertTrue(b.sendReaction(b.message(from.id)!!, "👍")); net.pump()
        assertEquals("👍", a.message(from.id)!!.reactions[b.me.id])
        val asked = b.requestErrand(Errand.READ, JSONObject().put("url", "https://example.org/page"), selfCaps = 0); net.pump()
        assertTrue(asked.id in a.errands)
        assertNotNull(b.renameGroup("Ridge")); net.pump()
        assertEquals("Ridge", a.group.name)
        assertTrue(b.sayGoodbye()); net.pump()
        assertTrue(a.people[b.me.id]!!.left)
        net.now += min
        assertTrue(b.helloIfDue(firstJoin = false, joinedAt = 0)); net.pump()
        assertFalse(a.people[b.me.id]!!.left)
        assertEquals(listOf(Message.NOTICE, Message.MEMBER_LEFT, Message.MEMBER_JOINED),
            a.messages.filter { it.from == b.me.id && it.isNotice }.map { it.kind })
        assertTrue(held(a).isEmpty())
    }

    @Test fun `in a group nobody restricted, posts are stamped by the clock exactly as before`() {
        for (started in listOf(false, true)) {
            val net = FakeNet(); net.node("A"); net.node("B")
            if (started) net.found("A")
            net.connect("A", "B")
            val b = r(net, "B")
            assertTrue(b.mayPost())
            net.now += 12_345
            val m = b.sendChat("hello")!!
            assertEquals("$started", net.now, m.ts); assertEquals(net.now, m.arrivedAt)
            assertEquals(net.now, b.sendLocation(Loc.of(12.97, 77.59)!!)!!.ts)
            val (att, pieces) = FakeNet.makeFile(b, ByteArray(20_000) { 1 })
            assertEquals(net.now, b.sendFile(att, pieces, "")!!.ts)
            // a clock that runs behind stamps by itself, as it always did
            net.nodes["B"]!!.skew = -hour
            assertEquals(net.now - hour, b.sendChat("from the past")!!.ts)
            net.pump()
            assertEquals("$started", 4, r(net, "A").messages.count { it.from == b.me.id })
        }
    }

    @Test fun `a post is stamped by the clock whenever the rules allow it then`() {
        val net = FakeNet.groupOf("A", "B", "C")
        val a = r(net, "A"); val b = r(net, "B")
        net.nodes["A"]!!.skew = 3 * hour                 // the admin's clock runs three hours fast
        restrict(net, "A")
        net.now += 10 * min
        assertEquals(RoleChange.DONE, a.setOnlyAdmins(false)); net.pump()
        val lift = a.roles.effective.last()
        net.now += min
        // members are barred three hours from now for ten minutes; lifted for good after that
        assertEquals(lift.ts, b.roles.allowedFrom(b.me.id))
        assertTrue(lift.ts > net.now + 2 * hour)
        val m = b.sendChat("all good here")!!
        assertEquals("the clock: allowed at it", net.now, m.ts)
        net.pump()
        for (l in listOf("A", "C")) assertEquals(l, m.ts, r(net, l).message(m.id)!!.ts)
    }

    @Test fun `a post that would fall before the change that lets me post is stamped just after it, in the order written, and sorts by when I wrote it on my own phone`() {
        val net = FakeNet.groupOf("A", "B")
        val a = r(net, "A"); val b = r(net, "B")
        restrict(net, "A")
        // the admin makes B an admin with its clock a year fast: B may post only from then on, everywhere
        net.nodes["A"]!!.skew = 365 * day
        assertEquals(RoleChange.DONE, a.grantAdmin(b.me.id)); net.pump()
        net.nodes["A"]!!.skew = 0
        val grant = a.roles.effective.last()
        assertTrue(b.mayPost())
        net.now += 5 * min
        val p1 = b.sendChat("first")!!; net.pump()
        net.now += 10 * min
        val f = a.sendChat("from the admin")!!; net.pump()
        net.now += 10 * min
        val p2 = b.sendChat("second")!!; net.pump()
        net.now += 10 * min
        val p3 = b.sendChat("third")!!; net.pump()
        assertEquals(listOf(grant.ts + 1, grant.ts + 2, grant.ts + 3), listOf(p1, p2, p3).map { it.ts })
        assertEquals(net.now - 30 * min, p1.arrivedAt)
        // on my own phone: in the order I wrote them, the admin's in between
        assertEquals(listOf(p1.id, f.id, p2.id, p3.id), b.messages.filter { it.isPersonal }.map { it.id })
        // and every phone with the grant shows them
        for (id in listOf(p1.id, p2.id, p3.id)) assertNotNull(a.message(id))
        assertTrue(held(a).isEmpty())
    }

    @Test fun `a group with no founder known lets everyone post and shows everything`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val b = r(net, "B"); val c = r(net, "C")
        // a member's change of settings, with nobody known to have started the group: it counts for nothing
        val only = RoleOp.make(FakeNet.keysOf("M"), FakeNet.group().airTag, 2, net.now, RoleOp.SET, mapOf("k" to RoleOp.SEND, "w" to RoleOp.ADMINS))
        val claim = FakeNet.envelope("M", Envelope.CHAT, JSONObject().put("text", Router.ROLE_MARK + Router.ADMINS_ONLY_WORDS)
            .put("ro", JSONArray().put(only.toJson())), net.now)
        handTo(net, "B", claim)
        net.now += 10 * min
        assertTrue(b.mayPost()); assertTrue(c.mayPost())
        val m = b.sendChat("anyone")!!; net.pump()
        assertEquals(net.now, m.ts)
        val p = post("M", "me too", net.now)
        handTo(net, "B", p)
        for (l in listOf("A", "B", "C")) {
            val r = r(net, l)
            assertNull(l, r.founder()); assertFalse(l, r.onlyAdminsSend())
            assertNotNull(l, r.message(m.id))
            assertNotNull(l, r.message(p.id))
            assertTrue(l, held(r).isEmpty())
        }
    }

    // ---------------------------------------------------------------- receiving

    @Test fun `a member's post is carried and passed on but never shown, notified or confirmed`() {
        val net = FakeNet.groupOf("A", "B", "C")
        restrict(net, "A")
        net.now += 10 * min
        val p = post("M", "let me in", net.now)
        net.frames!!.clear()
        handTo(net, "B", p)
        for (l in listOf("A", "B", "C")) {
            val r = r(net, l)
            assertTrue("$l: carried", r.carries(p.id))
            assertNull(l, r.message(p.id))
            assertTrue("$l: never notified", rec(net, l).shown.isEmpty())
            assertEquals(l, setOf(p.id), held(r))
            assertTrue(l, r.people[net.id("M")] != null)
        }
        assertTrue("passed on", onAir(net).any { it.contains(p.id) })
        assertTrue("never confirmed", net.frames!!.none { it.contains("r.${p.id}.") })
        // nor after the next syncs
        net.syncs()
        for (l in listOf("A", "B", "C")) { assertNull(l, r(net, l).message(p.id)); assertTrue(l, rec(net, l).shown.isEmpty()) }
    }

    @Test fun `a post stamped just after the restriction still shows, within the slack`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        val at = r(net, "A").roles.effective.last().ts
        val before = post("M", "just before", at - 1)
        val within = post("M", "a moment after", at + Roles.SLACK_MS - 1)
        handTo(net, "B", before, within)
        for (l in listOf("A", "B")) {
            assertNotNull(l, r(net, l).message(before.id)); assertNotNull(l, r(net, l).message(within.id))
            assertTrue(l, held(r(net, l)).isEmpty())
        }
        assertEquals(listOf(before.id, within.id), rec(net, "B").shown.map { it.id })
        assertTrue("confirmed like any post", r(net, "B").carries("r.${within.id}.${net.id("B")}"))
    }

    @Test fun `a post stamped well after the restriction is held`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        val at = r(net, "A").roles.effective.last().ts
        val edge = post("M", "two minutes on", at + Roles.SLACK_MS)
        val later = post("M", "ten minutes on", at + 10 * min)
        net.now += 10 * min
        handTo(net, "B", edge, later)
        for (l in listOf("A", "B")) {
            val r = r(net, l)
            assertNull(l, r.message(edge.id)); assertNull(l, r.message(later.id))
            assertEquals(l, setOf(edge.id, later.id), held(r))
            assertTrue(l, r.carries(edge.id) && r.carries(later.id))
        }
        assertTrue(rec(net, "B").log.any { it == "a post only admins may send was kept for the others, not shown" })
    }

    @Test fun `a post that beat the grant here shows when the grant arrives, notified and confirmed like any post`() {
        val net = FakeNet.groupOf("A", "B", "C")
        restrict(net, "A")
        net.disconnect("A", "B")
        net.now += 10 * min
        net.know("A", "M")
        val a = r(net, "A"); val b = r(net, "B")
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("M")))
        net.now += 30_000
        val p = post("M", "made admin, and saying so", net.now)
        handTo(net, "B", p)
        assertNull(b.message(p.id)); assertEquals(setOf(p.id), held(b)); assertEquals(setOf(p.id), held(r(net, "C")))
        net.now += 30_000
        net.connect("A", "B")
        for (l in listOf("B", "C")) {
            val r = r(net, l)
            assertNotNull(l, r.message(p.id))
            assertTrue(l, held(r).isEmpty())
            assertEquals("$l: notified", listOf(p.id), rec(net, l).shown.map { it.id })
            assertTrue("$l: confirmed", r.carries("r.${p.id}.${r.me.id}"))
        }
        assertNotNull(a.message(p.id))
    }

    @Test fun `a post stamped after the lift that got here before it shows when the lift arrives, quietly once it is two minutes old`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        net.disconnect("A", "B")
        net.now += 10 * min
        assertEquals(RoleChange.DONE, r(net, "A").setOnlyAdmins(false))
        net.now += min
        val p = post("M", "free again", net.now)
        val b = r(net, "B")
        handTo(net, "B", p)
        assertEquals(setOf(p.id), held(b))
        net.now += 5 * min
        net.frames!!.clear()
        net.connect("A", "B")
        val m = b.message(p.id)!!
        assertEquals("arrived now, as far as this phone is concerned", net.now, m.arrivedAt)
        assertEquals(p.ts, m.ts)
        assertTrue("not notified", rec(net, "B").shown.isEmpty())
        assertFalse("not confirmed", b.carries("r.${p.id}.${b.me.id}"))
        assertTrue(net.frames!!.none { it.contains("r.${p.id}.${b.me.id}") })
        assertTrue(held(b).isEmpty())
    }

    @Test fun `a post stamped while only admins could send stays hidden after the lift, whichever reached the phone first`() {
        for (postFirst in listOf(true, false)) {
            val net = FakeNet.groupOf("A", "B")
            restrict(net, "A")
            net.disconnect("A", "B")
            net.now += 10 * min
            val p = post("M", "while it was closed", net.now)
            net.now += 10 * min
            assertEquals(RoleChange.DONE, r(net, "A").setOnlyAdmins(false))
            net.now += min
            if (postFirst) { handTo(net, "B", p); net.connect("A", "B") } else { net.connect("A", "B"); handTo(net, "B", p) }
            for (l in listOf("A", "B")) {
                val r = r(net, l)
                assertFalse("$postFirst $l", r.onlyAdminsSend())
                assertNull("$postFirst $l", r.message(p.id))
                assertEquals("$postFirst $l", setOf(p.id), held(r))
                assertTrue("$postFirst $l", rec(net, l).shown.none { it.id == p.id })
            }
        }
    }

    @Test fun `a big backlog of held posts is shown a slice at a time`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        net.disconnect("A", "B")
        val a = r(net, "A"); val b = r(net, "B")
        net.now += 10 * min
        val lift = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(false)) }
        net.now += min
        val posts = (1..450).map { post("M", "post $it", net.now + it) }
        for (some in posts.chunked(50)) handTo(net, "B", *some.toTypedArray())
        assertEquals(450, held(b).size)
        net.now += 5 * min
        fun shown() = posts.count { b.message(it.id) != null }
        handTo(net, "B", lift)
        assertEquals(200, shown())
        assertEquals(250, held(b).size)
        b.tick()
        assertEquals(400, shown())
        b.tick()
        assertEquals(450, shown())
        assertTrue(held(b).isEmpty())
        // each in its place, and none of them notified or confirmed: they were five minutes old
        assertEquals(posts.map { it.id }, b.messages.filter { it.from == net.id("M") }.map { it.id })
        assertTrue(rec(net, "B").shown.isEmpty())
        assertTrue(posts.none { b.carries("r.${it.id}.${b.me.id}") })
    }

    @Test fun `a post already shown stays when the restriction reaches this phone late`() {
        val net = FakeNet.groupOf("A", "B")
        net.disconnect("A", "B")
        restrict(net, "A")
        net.now += 10 * min
        val p = post("M", "before I knew", net.now)
        val b = r(net, "B")
        handTo(net, "B", p)
        assertNotNull(b.message(p.id))
        net.connect("A", "B")
        assertTrue(b.onlyAdminsSend())
        assertNotNull("never taken back", b.message(p.id))
        assertTrue(held(b).isEmpty())
        assertEquals("the phone that knew holds it", setOf(p.id), held(r(net, "A")))
        // nor after a restart
        val again = FakeNet().also { it.now = net.now }.node("B").router
        again.restore(copy(b.snapshot()))
        assertNotNull(again.message(p.id))
    }

    @Test fun `a reaction to a held post isn't held, and lands when the post is shown`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        net.disconnect("A", "B")
        val a = r(net, "A"); val b = r(net, "B")
        net.now += 10 * min
        val lift = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(false)) }
        net.now += min
        val p = post("M", "pick me", net.now)
        val fire = FakeNet.envelope("C", Envelope.REACT, JSONObject().put("m", p.id).put("e", "🔥"), net.now + 1)
        // the reaction comes first and waits for its post; the post comes, and is held
        handTo(net, "B", fire)
        handTo(net, "B", p)
        assertEquals(setOf(p.id), held(b))
        // a restart in between: the reaction is still in the carry, and only there
        val b2 = Router(FakeNet.identity("B"), FakeNet.group(), FakeNet.Quiet(), net.Recorder(), b.chunks) { net.now }
        b2.restore(copy(b.snapshot()))
        assertNull(b2.message(p.id))
        assertTrue(b2.carries(fire.id))
        FakeNet.hand(b2, lift)
        assertEquals("🔥", b2.message(p.id)!!.reactions[net.id("C")])
        // and the same without the restart
        FakeNet.hand(b, lift)
        assertEquals("🔥", b.message(p.id)!!.reactions[net.id("C")])
    }

    @Test fun `a reply quoting a held post keeps its snippet`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        net.now += 10 * min
        val p = post("M", "can anyone see this?", net.now)
        handTo(net, "B", p)
        assertEquals(setOf(p.id), held(r(net, "B")))
        // the admin answers it from a phone that shows it (a 2.4 one): the reply quotes it
        val quote = Quote(p.id, "M", "can anyone see this?", net.id("M"))
        val reply = r(net, "A").sendChat("I can", quote = quote)!!; net.pump()
        val got = r(net, "B").message(reply.id)!!
        assertEquals("can anyone see this?", got.quote!!.text)
        assertEquals(p.id, got.quote!!.id); assertEquals("M", got.quote!!.name)
        assertNull(r(net, "B").message(p.id))
    }

    @Test fun `a held post past its 48 h is never shown, whatever changes later`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        net.disconnect("A", "B")
        val b = r(net, "B")
        net.now += 10 * min
        assertEquals(RoleChange.DONE, r(net, "A").setOnlyAdmins(false))
        net.now += min
        val p = post("M", "is it open?", net.now)
        val view = photo("M", net.now + 1)
        handTo(net, "B", p, view.meta)
        assertEquals(setOf(p.id, view.meta.id), held(b)); assertTrue(b.holdsFile(view.fid))
        net.now += 49 * hour
        b.tick()
        assertFalse(b.carries(p.id)); assertTrue(held(b).isEmpty())
        assertFalse("its pieces are a stranger's again", b.holdsFile(view.fid))
        // the lift arrives at last
        net.connect("A", "B")
        assertFalse(b.onlyAdminsSend())
        assertNull(b.message(p.id)); assertNull(b.fileMessage(view.fid))
        // and a friend that still has the post hands it over
        handTo(net, "B", p)
        assertNull(b.message(p.id))
        assertTrue(rec(net, "B").shown.none { it.id == p.id })
    }

    @Test fun `a legacy public answer from a member who can't post makes no line, and its request is still answered`() {
        val net = FakeNet.groupOf("A", "B", "C")
        restrict(net, "A")
        net.now += 10 * min
        val b = r(net, "B")
        val url = JSONObject().put("url", "https://example.org/page")
        val asked = b.requestErrand(Errand.READ, url, selfCaps = 0); net.pump()
        net.now += min
        fun answer(from: String, eid: String) = FakeNet.envelope(from, Envelope.ERRAND_RESULT,
            JSONObject().put("eid", eid).put("ok", true).put("title", "Page").put("text", "it says hello"), net.now)
        handTo(net, "B", answer("M", asked.id))
        assertEquals(Errand.DONE, asked.status)
        assertTrue(rec(net, "B").answers.any { it.id == asked.id })
        for (l in listOf("A", "B", "C")) assertTrue(l, r(net, l).messages.none { it.kind == Message.SYSTEM })
        // one from an admin's phone gets its line as ever
        net.know("A", "H")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(net.id("H"))); net.pump()
        val again = b.requestErrand(Errand.READ, url, selfCaps = 0); net.pump()
        handTo(net, "B", answer("H", again.id))
        for (l in listOf("A", "B", "C")) assertEquals(l, listOf(again.id), r(net, l).messages.filter { it.kind == Message.SYSTEM }.map { it.errandId })
    }

    @Test fun `goodbyes, hellos and renames from a member who can't post still land`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        net.now += 10 * min
        val q = net.now
        val bye = FakeNet.envelope("M", Envelope.CHAT, JSONObject().put("text", Router.BYE_TEXT).put("mb", Router.MB_LEFT).put("q", q), net.now)
        val hello = FakeNet.envelope("M", Envelope.CHAT, JSONObject().put("text", Router.HELLO_TEXT).put("mb", Router.MB_JOINED).put("q", q + 1), net.now + min)
        val rename = FakeNet.envelope("M", Envelope.CHAT, JSONObject().put("text", "✏️ renamed the group to “Ridge”").put("gn", "Ridge").put("gv", 1),
            net.now + 2 * min)
        handTo(net, "B", bye, hello, rename)
        for (l in listOf("A", "B")) {
            val r = r(net, l)
            assertEquals(l, listOf(Message.MEMBER_LEFT, Message.MEMBER_JOINED, Message.NOTICE),
                r.messages.filter { it.from == net.id("M") }.map { it.kind })
            assertEquals(l, "Ridge", r.group.name)
            assertFalse(l, r.people[net.id("M")]!!.left)
            assertTrue(l, held(r).isEmpty())
        }
    }

    @Test fun `every phone settles on the same chat whichever order the post and the changes arrive in`() {
        val tag = FakeNet.group().airTag
        val t0 = 1_700_000_000_000L
        val m = FakeNet.idOf("M")
        val atF = Roles(tag, FakeNet.keysOf("F"))
        val pin = atF.found(true, t0)!!
        val only = atF.setOnlyAdmins(true, t0 + 10 * min)!!
        val grant = atF.grant(m, t0 + 20 * min)!!
        val revoke = atF.revoke(m, t0 + 40 * min)!!
        val said = post("M", "said while an admin", t0 + 30 * min)
        val rd = atF.digest
        val items = listOf<Any>(only, grant, said, revoke)
        val outcomes = HashSet<String>()
        for (order in FakeNet.permutations(items)) {
            val net = FakeNet().also { it.now = t0 + hour }
            val x = net.node("X").router
            FakeNet.hand(x, FakeNet.roles(listOf(pin), rd))
            for (item in order) FakeNet.hand(x, if (item is RoleOp) FakeNet.roles(listOf(item), rd) else FakeNet.fill(item as Envelope))
            val names = order.map { if (it is RoleOp) it.type else "post" }
            assertNotNull("$names", x.message(said.id))
            assertEquals("$names", listOf(FakeNet.idOf("F")), x.admins())
            assertTrue("$names", x.onlyAdminsSend())
            assertTrue("$names", held(x).isEmpty())
            outcomes.add(x.messages.map { it.id }.toSortedSet().joinToString(",") + "|" + x.roles.digest)
        }
        assertEquals("all 24 orders, one chat", 1, outcomes.size)
    }

    @Test fun `a restriction by a phone whose clock runs behind doesn't hide posts it had already seen`() {
        val net = FakeNet.groupOf("A", "B")
        net.node("C")
        val a = r(net, "A"); val b = r(net, "B"); val c = r(net, "C")
        net.nodes["A"]!!.skew = -5 * min
        val p = b.sendChat("said before the change")!!; net.pump()
        assertNotNull(a.message(p.id))
        val change = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }; net.pump()
        assertEquals("never before what this phone shows", p.ts, a.roles.effective.last().ts)
        // a third phone gets the change, then the post
        handTo(net, "C", change, envelopeOf(b, p.id))
        assertTrue(c.onlyAdminsSend())
        assertNotNull(c.message(p.id))
        assertTrue(held(c).isEmpty())
        // the poster's phone: nothing to say about it
        assertTrue(b.onlyAdminsSend())
        assertTrue(b.messages.none { it.kind == Message.ROLE_UNSEEN })
        assertFalse(b.postBarred(p))
    }

    @Test fun `a restriction by a phone more than seven minutes behind does reach back, and the poster is told`() {
        val net = FakeNet.groupOf("A", "B")
        net.node("C")
        val a = r(net, "A"); val b = r(net, "B"); val c = r(net, "C")
        net.nodes["A"]!!.skew = -10 * min
        val p = b.sendChat("said before the change")!!; net.pump()
        val change = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }; net.pump()
        val only = a.roles.effective.last()
        assertEquals("no more than five minutes ahead of its own clock", net.now - 5 * min, only.ts)
        handTo(net, "C", change, envelopeOf(b, p.id))
        assertNull(c.message(p.id)); assertEquals(setOf(p.id), held(c))
        assertNotNull("shown before it knew: never taken back", a.message(p.id))
        // the poster is told phones that knew won't show it
        assertTrue(b.postBarred(p))
        val line = b.messages.single { it.kind == Message.ROLE_UNSEEN }
        assertEquals("role.unseen.${only.ts}", line.id)
        assertEquals(p.ts + 1, line.ts); assertEquals(p.arrivedAt, line.arrivedAt); assertEquals(b.me.id, line.from)
    }

    // ---------------------------------------------------------------- restart and restore

    @Test fun `held posts survive a restart and show when allowed`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        net.disconnect("A", "B")
        val a = r(net, "A"); val b = r(net, "B")
        net.now += 10 * min
        val lift = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(false)) }
        net.now += min
        val p = post("M", "is it open yet?", net.now)
        val view = photo("M", net.now + 1)
        val meta = view.meta; val fid = view.fid
        handTo(net, "B", p, meta)
        for (piece in view.pieces) handTo(net, "B", piece)
        assertTrue(b.holdsFile(fid))
        val saved = copy(b.snapshot())
        assertEquals(mapOf(p.id to "", meta.id to fid), saved.getJSONObject("held").let { h -> h.keySet().associateWith { h.getString(it) } })
        val rec2 = net.Recorder()
        val b2 = Router(FakeNet.identity("B"), FakeNet.group(), FakeNet.Quiet(), rec2, b.chunks) { net.now }
        b2.restore(saved)
        assertNull(b2.message(p.id)); assertNull(b2.fileMessage(fid))
        assertEquals(setOf(p.id, meta.id), held(b2))
        assertTrue(b2.holdsFile(fid))
        FakeNet.hand(b2, lift)
        assertNotNull(b2.message(p.id))
        assertNotNull(b2.fileMessage(fid))
        assertEquals("its pieces were all here: put together", listOf(meta.id), rec2.files.map { it.id })
        assertTrue(held(b2).isEmpty()); assertFalse(b2.holdsFile(fid))
    }

    @Test fun `restoring shows nothing and sends nothing on its own`() {
        val net = FakeNet.groupOf("A", "B")
        restrict(net, "A")
        net.disconnect("A", "B")
        val a = r(net, "A"); val b = r(net, "B")
        net.now += 10 * min
        assertEquals(RoleChange.DONE, a.setOnlyAdmins(false))
        net.now += min
        val p = post("M", "waiting", net.now)
        handTo(net, "B", p)
        // saved with the lift already taken in, the post not yet let in (a pass that had no room for it)
        val state = copy(b.snapshot()).put("roles", copy(a.snapshot().getJSONObject("roles")))
        assertTrue(state.getJSONObject("held").has(p.id))
        val radio = FakeNet.Quiet(); val rec2 = net.Recorder()
        val b2 = Router(FakeNet.identity("B"), FakeNet.group(), radio, rec2, MemoryChunkStore()) { net.now }
        b2.restore(state)
        assertNotNull("let in, quietly", b2.message(p.id))
        assertTrue(held(b2).isEmpty())
        assertTrue(rec2.shown.isEmpty())
        assertTrue(radio.frames.isEmpty())
        assertFalse(b2.carries("r.${p.id}.${b2.me.id}"))
        assertFalse(b2.takeDirty())
    }

    @Test fun `a photo let in late or at restore isn't confirmed when its last pieces land afterwards`() {
        for (restart in listOf(false, true)) {
            val l = if (restart) "at restore" else "late"
            val net = FakeNet.groupOf("A", "B")
            restrict(net, "A")
            net.disconnect("A", "B")
            val a = r(net, "A"); val b = r(net, "B")
            net.now += 10 * min
            val lift = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(false)) }
            net.now += min
            val view = photo("M", net.now)
            handTo(net, "B", view.meta, view.pieces.first())
            assertTrue(l, b.holdsFile(view.fid))
            net.now += 5 * min
            // the lift comes before the rest of the photo: taken in live, or saved with it and the photo not yet let in
            val rec2 = net.Recorder()
            val shows = if (!restart) b.also { FakeNet.hand(it, lift) } else {
                val saved = copy(b.snapshot()).put("roles", copy(a.snapshot().getJSONObject("roles")))
                Router(FakeNet.identity("B"), FakeNet.group(), FakeNet.Quiet(), rec2, b.chunks) { net.now }.also { it.restore(saved) }
            }
            val files = if (restart) rec2.files else rec(net, "B").files
            assertNotNull(l, shows.fileMessage(view.fid)); assertTrue(l, files.isEmpty())
            FakeNet.hand(shows, *view.pieces.drop(1).toTypedArray())
            assertEquals("$l: put together", listOf(view.meta.id), files.map { it.id })
            assertFalse("$l: not confirmed", shows.carries("r.${view.meta.id}.${shows.me.id}"))
        }
    }

    // ---------------------------------------------------------------- my own posts

    @Test fun `my waiting post is withdrawn when I learn I can't post, and its pieces are no longer offered`() {
        val net = FakeNet.groupOf("A", "B", "C")
        net.disconnect("A", "B"); net.disconnect("B", "C")
        val a = r(net, "A"); val b = r(net, "B"); val c = r(net, "C")
        val change = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }
        net.now += 10 * min
        val q = b.sendChat("anyone around?")!!
        val (att, pieces) = FakeNet.makeFile(b, ByteArray(40_000) { 9 })
        val photo = b.sendFile(att, pieces, "the view")!!
        assertTrue(b.carries(q.id) && b.carries(photo.id))
        handTo(net, "B", change)
        assertFalse(b.mayPost())
        for (m in listOf(q, photo)) { assertEquals(Message.QUEUED, m.status); assertFalse(b.carries(m.id)); assertTrue(b.postBarred(m)) }
        assertTrue(rec(net, "B").log.any { it == "2 of my posts not sent: only admins may send" })
        for (i in 0 until att.chunks) assertTrue("the copy here stays, for Send again", b.chunks.has(Envelope.chunkId(att.fid, i)))
        net.frames!!.clear()
        net.connect("B", "C")
        net.syncs()
        assertNull(c.message(q.id)); assertNull(c.fileMessage(att.fid))
        for (i in 0 until att.chunks) assertFalse(c.chunks.has(Envelope.chunkId(att.fid, i)))
        assertTrue(onAir(net).none { it.contains(att.fid) || it.contains(q.id) || it.contains(photo.id) })
        assertTrue("C learned it from B", c.onlyAdminsSend())
        assertEquals(Message.QUEUED, q.status)
    }

    @Test fun `my post written before the restriction took effect still goes out`() {
        val net = FakeNet.groupOf("A", "B")
        net.disconnect("A", "B")
        val a = r(net, "A"); val b = r(net, "B")
        val early = b.sendChat("before the change")!!
        net.now += 10 * min
        val change = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }
        handTo(net, "B", change)
        assertTrue(b.onlyAdminsSend())
        assertTrue(b.carries(early.id)); assertEquals(Message.QUEUED, early.status); assertFalse(b.postBarred(early))
        assertNull(b.sendChat("after it"))
        net.connect("A", "B")
        assertNotNull(a.message(early.id))
        assertEquals(Message.SENT, early.status)
        assertTrue(held(a).isEmpty())
    }

    @Test fun `my posts that already left get one line saying phones that knew won't show them`() {
        val net = FakeNet.groupOf("A", "B", "C")
        net.disconnect("A", "B")
        val a = r(net, "A"); val b = r(net, "B"); val c = r(net, "C")
        assertEquals(RoleChange.DONE, a.setOnlyAdmins(true))
        val only = a.roles.effective.last()
        net.now += 10 * min
        val p1 = b.sendChat("first")!!; net.pump()
        net.now += 5 * min
        val p2 = b.sendChat("second")!!; net.pump()
        assertEquals(Message.SENT, p1.status); assertEquals(Message.SENT, p2.status)
        assertTrue(b.messages.none { it.kind == Message.ROLE_UNSEEN })
        net.connect("A", "B")
        val line = b.messages.single { it.kind == Message.ROLE_UNSEEN }
        assertEquals("role.unseen.${only.ts}", line.id)
        assertEquals(p2.ts + 1, line.ts); assertEquals(p2.arrivedAt, line.arrivedAt)
        assertEquals(b.me.id, line.from); assertEquals(b.me.name, line.fromName)
        assertTrue(line.isNotice); assertFalse(line.isPersonal)
        assertEquals("right under the newer one", b.messages.indexOf(b.message(p2.id)) + 1, b.messages.indexOf(line))
        assertEquals(setOf(p1.id, p2.id), held(a))
        assertNotNull(c.message(p1.id)); assertNotNull(c.message(p2.id))
        // once, whatever comes round again, and after a restart
        net.syncs()
        assertEquals(1, b.messages.count { it.kind == Message.ROLE_UNSEEN })
        val again = FakeNet().also { it.now = net.now }.node("B").router
        again.restore(copy(b.snapshot()))
        assertEquals(listOf(line.id), again.messages.filter { it.kind == Message.ROLE_UNSEEN }.map { it.id })
        assertFalse(again.takeDirty())
        // a line deleted here isn't written back, whatever changes next
        assertEquals(1, b.hideMessages(listOf(line.id)).size)
        assertEquals(RoleChange.DONE, a.grantAdmin(c.me.id)); net.pump()
        assertTrue(b.isAdmin(c.me.id))
        assertTrue(b.messages.none { it.kind == Message.ROLE_UNSEEN })
    }

    @Test fun `that line moves under a newer such post, and goes if a later change lets them in`() {
        val net = FakeNet.groupOf("A", "B", "C")
        net.disconnect("A", "B")
        val a = r(net, "A"); val b = r(net, "B")
        val restriction = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }
        val only = a.roles.effective.last()
        val id = "role.unseen.${only.ts}"
        net.now += 10 * min
        val p0 = b.sendChat("first")!!; net.pump()
        net.now += 5 * min
        // on its way to C when B learns: withdrawn here, though it had already left
        val p1 = b.sendChat("second")!!
        FakeNet.hand(b, restriction)
        assertEquals(Message.QUEUED, p1.status); assertFalse(b.carries(p1.id))
        assertEquals(p0.ts + 1, b.message(id)!!.ts)
        net.pump()
        assertEquals("it got out after all", Message.SENT, p1.status)
        assertEquals("the line moves under it at once", p1.ts + 1, b.message(id)!!.ts)
        // a lift stamped after both lets neither in; the line stays under the newer one
        net.now += 5 * min
        val lift = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(false)) }
        FakeNet.hand(b, lift)
        assertTrue(b.mayPost())
        assertEquals(p1.ts + 1, b.message(id)!!.ts)
        assertEquals(1, b.messages.count { it.kind == Message.ROLE_UNSEEN })
        // B made an admin by a phone whose clock runs behind, stamped before them: they were allowed all along
        net.nodes["A"]!!.skew = only.ts + min - net.now
        val grant = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.grantAdmin(b.me.id)) }
        assertTrue(a.roles.effective.last().ts < p0.ts)
        FakeNet.hand(b, grant)
        assertNull(b.message(id))
        assertTrue(b.messages.none { it.kind == Message.ROLE_UNSEEN })
        assertFalse(b.postBarred(p0)); assertFalse(b.postBarred(p1))
    }

    @Test fun `deleting the post that line sits under moves it under the one before, and deleting the last takes it away`() {
        for (way in listOf("one by one", "with the line")) {
            val net = FakeNet.groupOf("A", "B", "C")
            net.disconnect("A", "B")
            val a = r(net, "A"); val b = r(net, "B")
            assertEquals(RoleChange.DONE, a.setOnlyAdmins(true))
            val id = "role.unseen.${a.roles.effective.last().ts}"
            net.now += 10 * min
            val p1 = b.sendChat("first")!!; net.pump()
            net.now += 5 * min
            val p2 = b.sendChat("second")!!; net.pump()
            net.connect("A", "B")
            assertEquals(way, p2.ts + 1, b.message(id)!!.ts)
            if (way == "one by one") {
                assertEquals(1, b.hideMessages(listOf(p2.id)).size)
                val line = b.message(id)!!
                assertEquals(p1.ts + 1, line.ts); assertEquals(p1.arrivedAt, line.arrivedAt)
                assertEquals("right under the one left", b.messages.indexOf(b.message(p1.id)) + 1, b.messages.indexOf(line))
                net.syncs()
                assertEquals(1, b.messages.count { it.kind == Message.ROLE_UNSEEN })
                assertEquals(1, b.hideMessages(listOf(p1.id)).size)
            } else {
                // deleted together, the line isn't written back under the post that is left
                assertEquals(2, b.hideMessages(listOf(p2.id, id)).size)
                assertNotNull(b.message(p1.id))
            }
            assertTrue(way, b.messages.none { it.kind == Message.ROLE_UNSEEN })
            net.syncs()
            assertTrue(way, b.messages.none { it.kind == Message.ROLE_UNSEEN })
        }
    }

    @Test fun `my post that got out while being withdrawn gets its line as soon as it reads sent, however this phone learns it`() {
        for (way in listOf("the radio", "an inventory", "a receipt", "handed back")) {
            val net = FakeNet.groupOf("A", "B", "C")
            net.disconnect("A", "B")
            if (way != "the radio") net.disconnect("B", "C")
            val a = r(net, "A"); val b = r(net, "B"); val c = r(net, "C")
            val restriction = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }
            val only = a.roles.effective.last()
            net.now += 10 * min
            val p = b.sendChat("on its way")!!            // the radio has it, for C, or it waits here
            val env = envelopeOf(b, p.id)
            if (way != "the radio") handTo(net, "C", env)  // ...and it got to C some other way
            FakeNet.hand(b, restriction)                           // withdrawn here before this phone hears it went
            assertEquals(way, Message.QUEUED, p.status); assertFalse(way, b.carries(p.id))
            assertTrue(way, b.messages.none { it.kind == Message.ROLE_UNSEEN })
            when (way) {
                "the radio" -> net.pump()
                "an inventory" -> b.onBytes(FakeNet.HAND, JSONObject().put("t", "inv").put("n", 1).put("i", 0).put("ids", JSONArray().put(p.id)).toString().toByteArray())
                "a receipt" -> FakeNet.hand(b, envelopeOf(c, "r.${p.id}.${c.me.id}"))
                else -> FakeNet.hand(b, env)
            }
            assertEquals(way, Message.SENT, p.status)
            assertNotNull(way, c.message(p.id))
            val line = b.messages.singleOrNull { it.kind == Message.ROLE_UNSEEN }
            assertNotNull("$way: its line", line)
            assertEquals(way, "role.unseen.${only.ts}", line!!.id)
            assertEquals(way, p.ts + 1, line.ts)
        }
    }

    @Test fun `my post a friend puts back in the chat, after the app lost it, gets its line too`() {
        val net = FakeNet.groupOf("A", "B", "C")
        net.disconnect("A", "B")
        val a = r(net, "A"); val c = r(net, "C")
        val restriction = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }
        val only = a.roles.effective.last()
        net.now += 10 * min
        val lost = copy(r(net, "B").snapshot())        // the app is killed before it saves the send
        val p = r(net, "B").sendChat("before I knew")!!; net.pump()
        assertNotNull(c.message(p.id))
        net.disconnect("B", "C")
        val b2 = net.node("B").router
        b2.restore(lost)
        FakeNet.hand(b2, restriction)
        assertNull(b2.message(p.id))
        net.connect("B", "C")
        assertEquals(Message.SENT, b2.message(p.id)!!.status)
        assertEquals(p.ts + 1, b2.message("role.unseen.${only.ts}")!!.ts)
    }

    @Test fun `my withdrawn post handed back by a friend is carried again and reads sent`() {
        val net = FakeNet.groupOf("A", "B", "C")
        net.disconnect("A", "B")
        val a = r(net, "A"); val b = r(net, "B")
        val restriction = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)) }
        net.now += 10 * min
        val p = b.sendChat("on its way")!!            // the radio has it, for C
        FakeNet.hand(b, restriction)                           // ...and B learns before it hears it went
        assertEquals(Message.QUEUED, p.status); assertFalse(b.carries(p.id))
        val killed = copy(b.snapshot())                // the app is killed right then
        net.pump()
        assertNotNull(r(net, "C").message(p.id))
        net.disconnect("B", "C")
        val b2 = net.node("B").router
        b2.restore(killed)
        assertEquals(Message.QUEUED, b2.message(p.id)!!.status); assertFalse(b2.carries(p.id))
        net.connect("B", "C")
        assertTrue(b2.carries(p.id))
        assertEquals(Message.SENT, b2.message(p.id)!!.status)
        assertTrue(rec(net, "B").shown.isEmpty())
    }

    // ---------------------------------------------------------------- founding

    @Test fun `founding is one op however often it is asked for`() {
        val net = FakeNet(); val a = net.node("A").router
        val savedAt = net.now
        repeat(3) { a.foundIfDue(sure = true, ts = savedAt) }
        val pin = a.roles.pin!!
        assertEquals(listOf(pin.id), a.roles.wire().map { it.id })
        val digest = a.roles.digest
        // asked again later — the saved moment never changes, but even another stamp makes nothing new
        net.now += day
        a.foundIfDue(sure = true, ts = savedAt)
        a.foundIfDue(sure = true, ts = net.now)
        a.foundIfDue(sure = false, ts = savedAt)
        assertEquals(digest, a.roles.digest)
        assertEquals(1, a.messages.count { it.kind == Message.ROLE_STARTED })
        // the state set aside: the same phone founds the very same op
        val again = FakeNet().also { it.now = net.now }.node("A").router
        again.foundIfDue(sure = true, ts = savedAt)
        assertEquals(pin.id, again.roles.pin!!.id)
        assertEquals(digest, again.roles.digest)
    }

    @Test fun `a claim never takes over from a sure founder, and a sure founding replaces a claim`() {
        val net = FakeNet()
        for (l in listOf("A", "B", "C", "D")) net.node(l)
        // C claimed the group, long ago by its saved times, and made D an admin under that claim
        r(net, "C").foundIfDue(sure = false, ts = net.now - day)
        net.connect("C", "D")
        assertEquals(RoleChange.DONE, r(net, "C").grantAdmin(net.id("D"))); net.pump()
        assertTrue(r(net, "D").isAdmin(net.id("D")))
        // A really started it, and B knows A
        net.found("A")
        net.connect("A", "B")
        net.connect("B", "C")
        for (l in listOf("A", "B", "C", "D")) {
            val r = r(net, l)
            assertEquals(l, net.id("A"), r.founder())
            assertEquals(l, listOf(net.id("A")), r.admins())
            assertTrue(l, r.roles.founderSure)
        }
        // a claim made earlier still, heard later, changes nothing for a phone that knows the founder
        net.node("E"); r(net, "E").foundIfDue(sure = false, ts = net.now - 2 * day)
        net.connect("A", "E")
        assertEquals(net.id("A"), r(net, "A").founder())
        assertEquals(net.id("A"), r(net, "E").founder())
    }

    @Test fun `founding puts nothing on the air`() {
        val net = FakeNet(); net.frames = ArrayList()
        net.node("A"); net.node("B"); net.connect("A", "B")
        val a = r(net, "A")
        val carry = a.carrySize()
        net.frames!!.clear()
        net.found("A"); net.pump()
        assertTrue(net.frames!!.isEmpty())
        assertEquals(carry, a.carrySize())
        assertEquals(listOf(Message.ROLE_STARTED), a.messages.map { it.kind })
        // it reaches B with the role set at the next sync, never in an envelope
        net.syncs(1)
        assertEquals(net.id("A"), r(net, "B").founder())
        assertTrue(onAir(net).map { JSONObject(it) }.filter { it.optString("t") == "env" }
            .all { it.getJSONObject("e").getString("k") == Envelope.PRESENCE })
        assertTrue(onAir(net).none { JSONObject(it).optString("t") == "fill" })
        assertTrue(r(net, "B").messages.isEmpty())
    }

    // ---------------------------------------------------------------- leaving

    @Test fun `the only admin leaving hands admin to the phone linked longest, before the goodbye`() {
        val net = FakeNet(); net.frames = ArrayList()
        for (l in listOf("A", "B", "C")) net.node(l)
        net.found("A")
        net.connect("A", "B"); net.now += 1_000; net.connect("A", "C")
        val a = r(net, "A")
        assertTrue(a.soleAdmin()); assertEquals(net.id("B"), a.handoverPick())
        net.frames!!.clear()
        val declined = a.declineForLeaving()
        val before = carried(a).keys
        assertEquals(net.id("B"), a.handOver(null))
        val carrier = (carried(a).keys - before).single()
        val handedAt = carried(a).keys
        assertTrue(a.sayGoodbye(declined))
        val goodbye = (carried(a).keys - handedAt).single()
        net.pump()
        val first = net.frames!!.indexOfFirst { it.contains(carrier) }
        val last = net.frames!!.indexOfFirst { it.contains(goodbye) }
        assertTrue("both went out", first >= 0 && last >= 0)
        assertTrue("the grant's carrier goes first", first < last)
        for (l in listOf("B", "C")) assertEquals(l, setOf(net.id("A"), net.id("B")), r(net, l).admins().toSet())
        // the heir's chat: made an admin, then the leaver left
        val b = r(net, "B")
        assertEquals(listOf(Message.ROLE_ADMIN, Message.MEMBER_LEFT), b.messages.filter { it.from == a.me.id }.map { it.kind })
        assertEquals(b.me.id, b.messages.first { it.kind == Message.ROLE_ADMIN }.text)
        assertTrue(b.mayPost()); assertTrue(b.soleAdmin())
    }

    @Test fun `the heir named in the question is the one made admin while it is still linked, else the next pick`() {
        fun scene(): FakeNet {
            val net = FakeNet()
            for (l in listOf("A", "B", "C", "D")) net.node(l)
            net.found("A")
            net.connect("A", "B"); net.now += 1_000; net.connect("A", "C"); net.now += 1_000; net.connect("A", "D")
            return net
        }
        val here = scene()
        val handed = here.leave("A", heir = here.id("C")).handed
        assertEquals(here.id("C"), handed)
        for (l in listOf("B", "C", "D")) assertEquals(l, setOf(here.id("A"), here.id("C")), r(here, l).admins().toSet())
        // C went out of range before the leave: the pick now is whoever has been linked longest
        val gone = scene()
        gone.disconnect("A", "C")
        val instead = gone.leave("A", heir = gone.id("C")).handed
        assertEquals(gone.id("B"), instead)
        assertEquals(setOf(gone.id("A"), gone.id("B")), r(gone, "D").admins().toSet())
        assertFalse(r(gone, "C").isAdmin(gone.id("C")))
    }

    @Test fun `no handover when another admin was heard from lately`() {
        val net = FakeNet()
        for (l in listOf("A", "B", "C")) net.node(l)
        net.found("A")
        net.connect("A", "B"); net.connect("A", "C")
        val a = r(net, "A")
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("B"))); net.pump()
        assertTrue(a.people[net.id("B")]!!.rolesAware)
        net.disconnect("A", "B")
        net.now += hour
        assertEquals(setOf(net.id("A"), net.id("B")), a.presentAdmins().toSet())
        assertFalse(a.soleAdmin())
        assertNull(a.handoverPick())
        assertNull(a.handOver(null))
        assertFalse(a.isAdmin(net.id("C")))
        // two days without a word from B: nobody else can run the group, and C gets it
        net.now += 2 * day
        assertTrue(a.soleAdmin())
        assertEquals(net.id("C"), a.handOver(null))
    }

    @Test fun `no handover with nobody linked`() {
        val net = FakeNet(); net.node("A"); net.node("B")
        net.found("A")
        net.connect("A", "B"); net.disconnect("A", "B")
        val a = r(net, "A")
        val carry = a.carrySize()
        assertTrue(a.soleAdmin())
        assertNull(a.handoverPick())
        assertNull(a.handOver(null)); assertNull(a.handOver(net.id("B")))
        assertEquals(listOf(a.me.id), a.admins())
        assertEquals(carry, a.carrySize())
    }

    @Test fun `no handover to a phone that never showed it knows about admins, and such an admin doesn't count as around, linked or two hops away`() {
        val net = FakeNet(); net.legacy.addAll(listOf("L", "K"))
        for (l in listOf("A", "L", "K", "B")) net.node(l)
        net.found("A")
        net.connect("A", "L"); net.now += 1_000; net.connect("A", "K"); net.now += 1_000
        val a = r(net, "A")
        // X and Y run older versions, two hops away: their beacons, passed on by L, say nothing of admins (Y's not even "ev")
        for ((who, ev) in listOf("X" to Errand.EV, "Y" to 0)) {
            val p = JSONObject().put("n", who).put("q", net.now + 1).also { if (ev > 0) it.put("ev", ev) }
            a.onBytes("A>L", FakeNet.frame(FakeNet.envelope(who, Envelope.PRESENCE, p, net.now))); net.pump()
        }
        // A makes K (linked), X and Y admins: none of them can run anything
        for (who in listOf("K", "X", "Y")) assertEquals(who, RoleChange.DONE, a.grantAdmin(net.id(who)))
        net.pump()
        assertEquals(setOf(a.me.id, net.id("K"), net.id("X"), net.id("Y")), a.admins().toSet())
        assertTrue(a.olderPhone(net.id("K"))); assertTrue(a.olderPhone(net.id("X"))); assertTrue(a.olderPhone(net.id("L")))
        assertFalse("nothing says so, nor otherwise", a.people[net.id("Y")]!!.rolesAware)
        assertEquals(listOf(a.me.id), a.presentAdmins())
        assertTrue(a.soleAdmin())
        // L has been linked longest, but has never shown it knows about admins: not even when named
        assertNull(a.handoverPick())
        assertNull(a.handOver(net.id("L")))
        assertFalse(a.isAdmin(net.id("L")))
        // an updated phone comes along: it is the one
        net.connect("A", "B")
        assertEquals(net.id("B"), a.handoverPick())
        assertEquals(net.id("B"), a.handOver(null))
    }

    @Test fun `an admin who left is still an admin, and one again on rejoin`() {
        val net = FakeNet.groupOf("A", "B", "C")
        val a = r(net, "A")
        val bid = net.id("B")
        assertEquals(RoleChange.DONE, a.grantAdmin(bid)); net.pump()
        restrict(net, "A")
        net.now += 10 * min
        val said = r(net, "B").sendChat("from an admin")!!; net.pump()
        val fromMember = post("M", "from a member", net.now)
        handTo(net, "C", fromMember)
        val (archive, handed) = net.leave("B")
        assertNull("another admin is around: nothing handed on", handed)
        assertTrue(a.isAdmin(bid)); assertTrue(bid in a.admins())
        assertFalse("but not around to run the group", bid in a.presentAdmins())
        assertTrue(a.soleAdmin())
        // a newcomer gets the posts from the carry: the one from an admin who has left shows, the member's doesn't
        net.node("D"); net.connect("C", "D")
        val d = r(net, "D")
        assertTrue(d.onlyAdminsSend())
        assertNotNull(d.message(said.id))
        assertNull(d.message(fromMember.id)); assertEquals(setOf(fromMember.id), held(d))
        // back again: an admin on its own phone, and around again on A's
        val b = net.rejoin("B", archive, "A").router
        assertTrue(b.isAdmin(bid)); assertTrue(b.mayPost())
        assertFalse(a.people[bid]!!.left)
        assertTrue(bid in a.presentAdmins())
        assertFalse(a.soleAdmin())
    }

    @Test fun `a dismissed admin's later posts are held once the dismissal reaches them, and their waiting ones withdrawn`() {
        val net = FakeNet.groupOf("A", "B", "C")
        val a = r(net, "A"); val b = r(net, "B"); val c = r(net, "C")
        assertEquals(RoleChange.DONE, a.grantAdmin(b.me.id)); net.pump()
        restrict(net, "A")
        net.disconnect("A", "B")
        val dismissal = FakeNet.newlyCarried(a) { assertEquals(RoleChange.DONE, a.dismissAdmin(b.me.id)) }
        val revoke = a.roles.effective.last()
        net.now += 10 * min
        // B doesn't know yet, and neither does C, which shows what B says
        val p1 = b.sendChat("still in charge?")!!; net.pump()
        assertNotNull(c.message(p1.id))
        net.disconnect("B", "C")
        net.now += min
        val p2 = b.sendChat("hello?")!!                 // nobody linked: waiting
        // the dismissal reaches C, and A gets p1: held there, kept on C
        net.connect("A", "C")
        assertNotNull("shown before C knew: stays", c.message(p1.id))
        assertEquals(setOf(p1.id), held(a))
        // B's later post, now that C knows: held
        handTo(net, "C", envelopeOf(b, p2.id))
        assertNull(c.message(p2.id)); assertEquals(setOf(p2.id), held(c))
        // the dismissal reaches B: its waiting post is withdrawn, and it is told about the one that left
        FakeNet.hand(b, dismissal)
        assertFalse(b.isAdmin(b.me.id)); assertFalse(b.mayPost())
        assertEquals(Message.QUEUED, p2.status); assertFalse(b.carries(p2.id))
        assertEquals(p1.ts + 1, b.message("role.unseen.${revoke.ts}")!!.ts)
    }
}
