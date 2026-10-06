package app.hopline.mesh

import app.hopline.core.Crypto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Admins on the air: how a group's role ops spread (in the carrier of each change, and by the role set's digest and
 * roles frames), what a phone refuses, what keeping in step costs, and the one line each change gets in the chat.
 */
class RolesMeshTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val day = 24 * hour

    /** The air tag of the group every phone here is in: what their ops are signed for. */
    private val tag = FakeNet.group().airTag

    private fun r(net: FakeNet, label: String): Router = net.nodes[label]!!.router
    private fun roleFrames(net: FakeNet) = net.frames!!.count { JSONObject(it).optString("t") == "roles" }
    private fun lines(r: Router) = r.messages.filter { it.isRole }
    private fun copy(j: JSONObject) = JSONObject(j.toString())

    /** A group envelope's payload, opened as any phone in the group can. */
    private fun opened(env: JSONObject): JSONObject {
        val e = Envelope(env)
        return JSONObject(String(Crypto.open(FakeNet.group().keys.env, e.header(tag), e.sealed)!!, Charsets.UTF_8))
    }

    /** The carriers [r] holds, each with its opened payload. */
    private fun carriers(r: Router): List<Pair<JSONObject, JSONObject>> {
        val carry = r.snapshot().getJSONArray("carry")
        return (0 until carry.length()).map { carry.getJSONObject(it) }.filter { it.getString("k") == Envelope.CHAT }
            .map { it to opened(it) }.filter { it.second.has("ro") }
    }

    /** A phone of this group on a net of its own, linked by hand (link "L", proven) to [peer]. */
    private fun linkedByHand(net: FakeNet, label: String, peer: String): Router {
        val r = FakeNet().also { it.now = net.now }.node(label).router
        r.onLinkUp("L", net.id(peer), peer, "tok"); FakeNet.prove(r, "L", peer, "tok")
        return r
    }

    /**
     * C's state as 2.4 kept it after the carrier [env] went by: a bubble of its [words] (or, [deleted], not even that),
     * a word from B after it, and the carrier itself while it is still [carried].
     */
    private fun keptBy24(env: JSONObject, words: String, carried: Boolean, deleted: Boolean = false): Pair<JSONObject, Message> {
        val id = env.getString("id"); val ts = env.getLong("ts")
        val bubble = Message(id, Envelope.CHAT, env.getString("o"), "A", null, words, ts).also { it.arrivedAt = ts + 20_000 }
        val after = Message(FakeNet.newId("B"), Envelope.CHAT, FakeNet.idOf("B"), "B", null, "and then", ts + 60_000)
        val s = JSONObject().put("fmt", Router.FMT).put("me", FakeNet.idOf("C"))
            .put("messages", JSONArray((if (deleted) listOf(after) else listOf(bubble, after)).map { it.toJson() }))
        if (carried) s.put("carry", JSONArray().put(copy(env))).put("born", JSONObject().put(id, ts))
        if (deleted) s.put("hidden", JSONObject().put(id, ts + 30_000))
        return s to bubble
    }

    // ---------------------------------------------------------------- spreading

    @Test fun `the phone that started the group is its admin on every phone it reaches`() {
        val net = FakeNet.groupOf("A", "B", "C", "D")
        val a = net.id("A")
        for (label in listOf("A", "B", "C", "D")) {
            val r = r(net, label)
            assertEquals(label, a, r.founder())
            assertEquals(label, listOf(a), r.admins())
            assertTrue(label, r.isAdmin(a))
            assertFalse(label, r.isAdmin(net.id("B")))
            assertFalse(label, r.onlyAdminsSend())
            assertEquals(label, r(net, "A").roles.digest, r.roles.digest)
        }
        // and on one that comes along later, from whichever phone it meets
        net.node("E"); net.connect("D", "E")
        assertEquals(a, r(net, "E").founder())
    }

    @Test fun `making someone an admin reaches everyone, with one line each and none twice`() {
        val net = FakeNet.groupOf("A", "B", "C", "D")
        val c = net.id("C")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(c)); net.pump()
        val op = r(net, "A").roles.effective.last()
        fun check(why: String) {
            for (label in listOf("A", "B", "C", "D")) {
                val r = r(net, label)
                assertEquals("$label $why", listOf(net.id("A"), c), r.admins())
                val made = lines(r).filter { it.kind != Message.ROLE_STARTED }
                assertEquals("$label $why", listOf(Message.ROLE_ADMIN), made.map { it.kind })
                assertEquals("$label $why", "role.${op.id}", made[0].id)
                assertEquals(net.id("A"), made[0].from); assertEquals(c, made[0].text); assertEquals(op.ts, made[0].ts)
                assertTrue("$label $why: never a bubble", r.messages.none { it.kind == Envelope.CHAT })
                assertTrue("$label $why: never notified", net.nodes[label]!!.rec.shown.isEmpty())
            }
        }
        check("at once")
        // the same change comes round again and again: syncs, links that break and form again, a new way round
        net.syncs()
        net.disconnect("B", "C"); net.connect("B", "C"); net.connect("A", "D")
        net.syncs()
        check("after the syncs")
    }

    @Test fun `a change reaches a phone beyond a 2_4 phone in the message that carries it`() {
        val net = FakeNet(); net.frames = ArrayList(); net.legacy.add("L")
        for (l in listOf("A", "L", "C")) net.node(l)
        net.found("A")
        net.connect("A", "L"); net.connect("L", "C")
        val c = r(net, "C")
        assertNull("nothing reaches it by frame: the 2.4 phone between says nothing of roles", c.founder())
        assertEquals(false, r(net, "A").links["A>L"]!!.rolesAware)
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(c.me.id)); net.pump()
        assertEquals(net.id("A"), c.founder())
        assertEquals(listOf(net.id("A"), c.me.id), c.admins())
        assertEquals(listOf(Message.ROLE_ADMIN), lines(c).map { it.kind })
        assertEquals(r(net, "A").roles.digest, c.roles.digest)
    }

    @Test fun `a big set rides as chains, and the phone beyond a 2_4 phone can still check the change and every admin's right to post`() {
        val net = FakeNet(); net.legacy.add("L")
        for (l in listOf("A", "L", "C")) net.node(l)
        net.found("A")
        val a = r(net, "A")
        val b = net.id("B"); val e = net.id("E"); val x = net.id("X")
        // B and E are admins made away from here, and B let only admins send: their ops reach A, and A alone
        val grantB = a.roles.grant(b, net.now)!!
        val atB = Roles(tag, FakeNet.keysOf("B")).also { it.accept(a.roles.wire().map { op -> FakeNet.heard(op) }) }
        net.now += min
        val grantE = atB.grant(e, net.now)!!
        val only = atB.setOnlyAdmins(true, net.now + 1)!!
        a.roles.accept(listOf(FakeNet.heard(grantE), FakeNet.heard(only)))
        // then A makes X an admin and dismisses X again, over and over: a set far bigger than a carrier takes whole
        repeat(30) { net.now += 1_000; a.roles.grant(x, net.now)!!; net.now += 1_000; a.roles.revoke(x, net.now)!! }
        assertTrue(a.roles.wire().sumOf { it.toJson().toString().length + 1 } > Router.CARRIER_SET_BYTES)
        net.connect("A", "L"); net.connect("L", "C")
        net.now += 5 * min
        val c = r(net, "C")
        assertEquals(RoleChange.DONE, a.grantAdmin(c.me.id)); net.pump()
        val grantC = a.roles.effective.last()
        val (env, p) = carriers(a).single()
        assertTrue(env.toString().toByteArray().size <= Router.MAX_ENVELOPE_OUT)
        // the founder, the change, and the chains behind the setting and every admin: nothing of the back and forth
        val ro = p.getJSONArray("ro")
        val carried = (0 until ro.length()).map { RoleOp.parse(ro.getJSONObject(it))!!.id }.toSet()
        assertEquals(setOf(a.roles.pin!!.id, grantB.id, grantE.id, only.id, grantC.id), carried)
        assertEquals(net.id("A"), c.founder())
        assertEquals(setOf(net.id("A"), b, e, c.me.id), c.admins().toSet())
        assertTrue(c.onlyAdminsSend())
        // every admin may post there and a member may not, as on the phone holding the whole set
        val m = net.id("M")
        for (r in listOf(a, c)) {
            for (admin in listOf(net.id("A"), b, e, c.me.id)) assertTrue(r.roles.maySend(admin, net.now))
            assertFalse(r.roles.maySend(m, net.now))
        }
    }

    @Test fun `a 2_4 phone is never sent a roles frame and its inventory asks for nothing`() {
        val net = FakeNet(); net.frames = ArrayList(); net.legacy.add("L")
        net.node("A"); net.found("A"); net.node("L")
        net.connect("A", "L")
        net.syncs(5)
        val link = r(net, "A").links["A>L"]!!
        assertEquals(false, link.rolesAware); assertNull(link.peerRd)
        assertEquals(0, roleFrames(net))
        assertTrue("they did swap inventories", net.frames!!.count { JSONObject(it).optString("t") == "inv" } >= 10)
        // the same phone, updated, is handed the set at its first inventory
        net.legacy.clear()
        net.disconnect("A", "L"); net.connect("A", "L")
        assertEquals(1, roleFrames(net))
        assertEquals(net.id("A"), r(net, "L").founder())
    }

    @Test fun `every carrier's words start with the crown and pass 2_4's envelope checks`() {
        val net = FakeNet.groupOf("A", "B", "C")
        val a = r(net, "A"); val b = r(net, "B")
        val bid = net.id("B"); val cid = net.id("C")
        for (change in listOf<() -> RoleChange>({ a.grantAdmin(bid) }, { b.setOnlyAdmins(true) }, { b.setOnlyAdmins(false) },
                { a.grantAdmin(cid) }, { a.dismissAdmin(cid) }, { b.dismissAdmin(bid) })) {
            net.now += 1_000
            assertEquals(RoleChange.DONE, change()); net.pump()
        }
        val held = carriers(a)
        val mark = Router.ROLE_MARK
        assertEquals(listOf("made B a group admin", Router.ADMINS_ONLY_WORDS, "allowed everyone to send messages", "made C a group admin",
            "dismissed C as group admin", "stepped down as group admin").map { mark + it }.toSet(), held.map { it.second.getString("text") }.toSet())
        assertEquals(6, held.size)
        // 2.4 takes a group message with these fields, an id in its sender's space and no more than this size, and shows its words
        val wire = setOf("id", "k", "o", "on", "ts", "to", "h", "pk", "s", "c", "e", "er", "age")
        val x = linkedByHand(net, "X", "P")
        for ((env, p) in held) {
            val text = p.getString("text")
            assertTrue(text, text.startsWith(mark))
            assertEquals(text, setOf("text", "ro"), p.keySet())
            assertTrue(text, env.keySet().all { it in wire })
            assertEquals(Envelope.CHAT, env.getString("k"))
            assertTrue(text, env.toString().toByteArray().size <= Router.MAX_ENVELOPE_OUT)
            val origin = env.getString("o")
            assertTrue(text, env.getString("id").matches(Regex("$origin\\.[${Crypto.ALPHABET}]{10}")))
            val ro = p.getJSONArray("ro")
            assertTrue("stamped as its change", (0 until ro.length()).map { RoleOp.parse(ro.getJSONObject(it))!! }
                .any { it.author == origin && it.ts == env.getLong("ts") })
            x.onBytes("L", FakeNet.fill(Envelope(copy(env))))
            assertTrue("$text: taken and carried like any message", x.carries(env.getString("id")))
        }
    }

    @Test fun `a late joiner learns the admins and the setting from any updated phone, with no lines for what came before it joined`() {
        val net = FakeNet.groupOf("A", "B")
        val a = r(net, "A")
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("B"))); assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)); net.pump()
        net.now += 10 * min
        // within the changes' 48 h: their carriers are handed over too
        net.node("F", joinedAt = net.now); net.connect("B", "F")
        assertEquals(2, carriers(r(net, "F")).size)
        // days later, when no phone carries them any more: only the role set tells
        net.now += 3 * day; net.tickAll()
        assertTrue(carriers(r(net, "F")).isEmpty())
        net.node("G", joinedAt = net.now); net.connect("F", "G")
        for (label in listOf("F", "G")) {
            val r = r(net, label)
            assertEquals(label, listOf(net.id("A"), net.id("B")), r.admins())
            assertTrue(label, r.onlyAdminsSend())
            assertTrue(label, lines(r).isEmpty())
        }
    }

    @Test fun `every beacon says the phone knows about admins, and a phone two hops away remembers it after a restart`() {
        val net = FakeNet(); net.frames = ArrayList(); net.line("A", "B", "C")
        net.now += 31_000; net.tickAll()
        val beacons = net.frames!!.map { JSONObject(it) }.filter { it.optString("t") == "env" }.map { it.getJSONObject("e") }
            .filter { it.getString("k") == Envelope.PRESENCE }
        assertEquals(net.ids("A", "B", "C"), beacons.map { it.getString("o") }.toSet())
        for (b in beacons) assertEquals(1, opened(b).getInt("ra"))
        val a = r(net, "A")
        val c = a.people[net.id("C")]!!
        assertFalse("two hops away", c.direct)
        assertTrue(c.rolesAware)
        // saved with the person, and read back after a restart
        fun saved(state: JSONObject, id: String) = state.getJSONArray("people").let { p -> (0 until p.length()).map { p.getJSONObject(it) } }
            .single { it.getString("id") == id }
        val state = copy(a.snapshot())
        assertTrue(saved(state, net.id("C")).getBoolean("ra"))
        val a2 = FakeNet().also { it.now = net.now }.node("A")
        a2.router.restore(state)
        assertTrue(a2.router.people[net.id("C")]!!.rolesAware)
        // a 2.4 phone heard over hops says nothing of the kind, and is never taken to know
        val old = FakeNet.envelope("O", Envelope.PRESENCE, JSONObject().put("n", "O").put("q", net.now + 1), net.now)
        a.onBytes("A>B", FakeNet.frame(old))
        assertFalse(a.people[net.id("O")]!!.rolesAware)
        assertFalse(saved(a.snapshot(), net.id("O")).has("ra"))
    }

    @Test fun `a member back after days away gets a line for each change made while away`() {
        val net = FakeNet()
        net.node("A"); net.found("A")
        net.node("B", joinedAt = net.now); net.node("C")
        net.connect("A", "B"); net.connect("A", "C")
        val a = r(net, "A"); val b = r(net, "B")
        net.disconnect("A", "B")
        net.now += 2 * day
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("C"))); net.pump()
        val grant = a.roles.effective.last()
        net.now += hour
        assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)); net.pump()
        val only = a.roles.effective.last()
        // three days on, no phone carries the changes any more: only the role set brings them
        net.now += 3 * day; net.tickAll()
        assertTrue(carriers(a).isEmpty())
        net.connect("A", "B")
        assertEquals(listOf(Message.ROLE_ADMIN to net.id("C"), Message.ROLE_SEND to RoleOp.ADMINS), lines(b).map { it.kind to it.text })
        assertEquals("each at its own time", listOf(grant.ts, only.ts), lines(b).map { it.ts })
        assertTrue(b.onlyAdminsSend())
    }

    @Test fun `two partitions that changed admins apart settle the same once they meet`() {
        val net = FakeNet.groupOf("A", "B", "C", "D")
        val a = r(net, "A"); val d = r(net, "D")
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("D"))); net.pump()
        net.disconnect("B", "C")
        net.now += min
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("B"))); assertEquals(RoleChange.DONE, a.setOnlyAdmins(true))
        assertEquals(RoleChange.DONE, d.grantAdmin(net.id("C"))); assertEquals(RoleChange.DONE, d.dismissAdmin(net.id("D")))
        net.pump()
        assertNotEquals(a.roles.digest, d.roles.digest)
        net.connect("B", "C")
        val told = lines(a).filter { it.kind != Message.ROLE_STARTED }.map { it.id }.toSet()
        for (label in listOf("A", "B", "C", "D")) {
            val r = r(net, label)
            assertEquals(label, a.roles.digest, r.roles.digest)
            assertEquals(label, a.admins(), r.admins())
            assertEquals(label, a.onlyAdminsSend(), r.onlyAdminsSend())
            assertEquals(label, a.roles.told.map { it.id }, r.roles.told.map { it.id })
            assertEquals(label, told, lines(r).filter { it.kind != Message.ROLE_STARTED }.map { it.id }.toSet())
        }
        // what both sides did counts
        assertEquals(setOf(net.id("A"), net.id("B"), net.id("C")), a.admins().toSet())
        assertTrue(a.onlyAdminsSend())
    }

    @Test fun `concurrent dismissals in two partitions settle the same on every phone`() {
        val net = FakeNet()
        for (l in listOf("A", "B", "C", "X", "Y")) net.node(l)
        net.found("A")
        net.connect("A", "B"); net.connect("A", "C"); net.connect("B", "X"); net.connect("C", "Y")
        val a = r(net, "A"); val b = r(net, "B"); val c = r(net, "C")
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("B"))); net.pump()
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("C"))); net.pump()
        // B and C, each out of the other's reach, dismiss each other
        net.disconnect("A", "B"); net.disconnect("A", "C")
        net.now += min
        assertEquals(RoleChange.DONE, b.dismissAdmin(net.id("C"))); assertEquals(RoleChange.DONE, c.dismissAdmin(net.id("B"))); net.pump()
        net.connect("A", "B"); net.connect("A", "C")
        val settled = a.admins()
        for (label in listOf("A", "B", "C", "X", "Y")) {
            val r = r(net, label)
            assertEquals(label, settled, r.admins())
            assertEquals(label, a.roles.digest, r.roles.digest)
            assertEquals(label, a.roles.told.map { it.id }, r.roles.told.map { it.id })
        }
        assertEquals(net.id("A"), settled.first())
        assertEquals("one dismissal counts; the other was made by someone already dismissed", 2, settled.size)
    }

    @Test fun `two groups started apart under the same words keep their own founders`() {
        val net = FakeNet()
        for (l in listOf("A", "B", "Z", "Y")) net.node(l)
        net.found("A"); net.found("Z")
        net.connect("A", "B"); net.connect("Z", "Y")
        net.connect("B", "Y")
        net.syncs()
        for (label in listOf("A", "B")) assertEquals(label, net.id("A"), r(net, label).founder())
        for (label in listOf("Z", "Y")) assertEquals(label, net.id("Z"), r(net, label).founder())
        // and a change counts only where its author's founder is the founder
        assertEquals(RoleChange.DONE, r(net, "Z").grantAdmin(net.id("B"))); net.pump()
        assertTrue(r(net, "Y").isAdmin(net.id("B")))
        for (label in listOf("A", "B")) assertFalse(label, r(net, label).isAdmin(net.id("B")))
    }

    // ---------------------------------------------------------------- trust on the air

    @Test fun `a forged op in a roles frame is refused and nothing after it in that frame is kept`() {
        val net = FakeNet.groupOf("A", "B")
        val b = r(net, "B")
        // A's own changes, made where no phone has heard them yet
        val atA = Roles(tag, FakeNet.keysOf("A")).also { it.accept(listOf(FakeNet.heard(r(net, "A").roles.pin!!))) }
        val c = net.id("C"); val d = net.id("D"); val e = net.id("E")
        val grantC = atA.grant(c, net.now)!!
        val grantD = atA.grant(d, net.now + 1)!!
        val grantE = atA.grant(e, net.now + 2)!!
        val forged = RoleOp.parse(grantD.toJson().put("ts", grantD.ts + 1_000))!!     // A's signature on what A never signed
        b.onBytes("B>A", FakeNet.roles(listOf(grantC, forged, grantE), rd = atA.digest)); net.pump()
        assertTrue(b.isAdmin(c))
        assertFalse(b.isAdmin(d)); assertFalse(b.roles.knows(forged.id))
        assertFalse("after the forgery nothing in that frame is kept", b.isAdmin(e))
        assertFalse(b.roles.knows(grantE.id))
        // the same op, sent again by itself, is taken
        b.onBytes("B>A", FakeNet.roles(listOf(grantE), rd = atA.digest)); net.pump()
        assertTrue(b.isAdmin(e))
    }

    @Test fun `a roles frame before the handshake is ignored`() {
        val net = FakeNet(); val b = net.node("B").router
        val atA = Roles(tag, FakeNet.keysOf("A")).also { it.found(true, net.now) }
        val frame = FakeNet.roles(atA.wire(), rd = atA.digest)
        assertTrue(frame.size < Router.MAX_FRAME_BEFORE_AUTH)
        b.onLinkUp("L", net.id("A"), "A", "tok")
        b.onBytes("L", frame)
        assertTrue(b.roles.isEmpty)
        assertNotNull("small enough not to cost the link", b.links["L"])
        // the very same frame once the link has proved itself
        FakeNet.prove(b, "L", "A", "tok")
        b.onBytes("L", frame)
        assertEquals(net.id("A"), b.founder())
    }

    @Test fun `an op signed under another group's code is refused`() {
        val net = FakeNet.groupOf("A", "B")
        val b = r(net, "B")
        val there = FakeNet.group("wrong wrong wrong wrong").airTag
        val c = net.id("C")
        val grant = RoleOp.make(FakeNet.keysOf("A"), there, 2, net.now, RoleOp.GRANT, mapOf("x" to c))
        // in a roles frame, and in the carrier A's phone would send
        b.onBytes("B>A", FakeNet.roles(listOf(grant), rd = b.roles.digest))
        val env = FakeNet.envelope("A", Envelope.CHAT, JSONObject().put("text", Router.ROLE_MARK + "made C a group admin")
            .put("ro", JSONArray().put(grant.toJson())), net.now)
        b.onBytes("B>A", FakeNet.frame(env)); net.pump()
        assertFalse(b.isAdmin(c)); assertFalse(b.roles.knows(grant.id))
        assertTrue("the envelope itself is genuine: carried like any other", b.carries(env.id))
        assertNull(b.message(env.id))
        // nor does a founding signed there found anything here
        val elsewhere = RoleOp.make(FakeNet.keysOf("Z"), there, 1, net.now, RoleOp.FOUND, mapOf("sure" to 1L))
        val fresh = linkedByHand(net, "F", "Z")
        fresh.onBytes("L", FakeNet.roles(listOf(elsewhere), rd = b.roles.digest))
        assertTrue(fresh.roles.isEmpty)
        assertNull(fresh.founder())
    }

    @Test fun `a member's carrier with ops they had no right to make changes nothing and shows no bubble`() {
        val net = FakeNet.groupOf("A", "B", "M")
        val b = r(net, "B")
        val checked = b.roles.signaturesChecked
        val m = net.id("M")
        val ops = listOf(RoleOp.make(FakeNet.keysOf("M"), tag, 2, net.now, RoleOp.GRANT, mapOf("x" to m)),
            RoleOp.make(FakeNet.keysOf("M"), tag, 3, net.now, RoleOp.SET, mapOf("k" to RoleOp.SEND, "w" to RoleOp.ADMINS)))
        val env = FakeNet.envelope("M", Envelope.CHAT, JSONObject().put("text", Router.ROLE_MARK + "made M a group admin")
            .put("ro", JSONArray(ops.map { it.toJson() })), net.now)
        b.onBytes("B>M", FakeNet.frame(env)); net.pump()
        for (label in listOf("A", "B")) {
            val r = r(net, label)
            assertFalse(label, r.isAdmin(m)); assertFalse(label, r.onlyAdminsSend())
            assertEquals(label, listOf(net.id("A")), r.admins())
            assertNull(label, r.message(env.id))
            assertTrue(label, net.nodes[label]!!.rec.shown.isEmpty())
            assertTrue("$label: carried and passed on like any genuine envelope", r.carries(env.id))
        }
        assertEquals("never checked: nobody made M an admin", checked, b.roles.signaturesChecked)
    }

    @Test fun `a crafted chat with ro and text never shows as a bubble`() {
        val net = FakeNet.groupOf("A", "B", "M")
        val b = r(net, "B")
        val shapes = listOf<Any>("not a list", JSONArray(), JSONArray().put(1).put(JSONObject().put("bad", true)), JSONObject().put("t", "grant"), 7)
        val sent = shapes.map { ro -> FakeNet.envelope("M", Envelope.CHAT, JSONObject().put("text", "read me").put("ro", ro), net.now) }
        for (env in sent) b.onBytes("B>M", FakeNet.frame(env))
        net.pump()
        for (label in listOf("A", "B")) {
            val r = r(net, label)
            assertTrue(label, r.messages.none { it.text == "read me" })
            assertTrue(label, net.nodes[label]!!.rec.shown.isEmpty())
            for (env in sent) assertTrue(label, r.carries(env.id))
        }
        assertTrue(net.nodes["B"]!!.rec.log.any { it.contains("malformed role op dropped") })
    }

    @Test fun `a forged copy of a real op that came first is dropped, and the real one comes with the next sync`() {
        val net = FakeNet.groupOf("A", "B")
        val a = r(net, "A"); val b = r(net, "B")
        assertEquals(RoleChange.DONE, a.grantAdmin(b.me.id)); net.pump()
        net.know("B", "C")
        assertEquals(RoleChange.DONE, b.grantAdmin(net.id("C"))); net.pump()
        val real = b.roles.effective.last()
        // D knows only who started the group when M hands it B's change under a signature that isn't B's: the same id
        net.node("D"); net.node("M")
        val d = r(net, "D")
        d.roles.accept(listOf(FakeNet.heard(a.roles.pin!!)))
        net.connect("D", "M")
        val copyOf = RoleOp.parse(real.toJson().put("s", Crypto.sign(FakeNet.keysOf("M").priv, "something else".toByteArray())))!!
        assertEquals(real.id, copyOf.id)
        d.onBytes("D>M", FakeNet.roles(listOf(copyOf), rd = r(net, "M").roles.digest)); net.pump()
        assertTrue("waiting, unchecked: B isn't an admin here yet", d.roles.knows(real.id))
        assertFalse(d.isAdmin(net.id("C")))
        net.connect("D", "B")
        assertTrue(d.isAdmin(net.id("C")))
        assertEquals(b.roles.digest, d.roles.digest)
        assertEquals("the copy kept is the real one", real.sig, d.roles.wire().single { it.id == real.id }.sig)
    }

    // ---------------------------------------------------------------- the cost of keeping in step

    @Test fun `phones that agree send no roles frames at link-up or at any sync`() {
        val net = FakeNet.groupOf("A", "B", "C")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(net.id("C"))); net.pump()
        net.frames!!.clear()
        net.syncs(5)
        net.disconnect("A", "B"); net.connect("A", "B"); net.connect("A", "C")
        net.syncs(2)
        assertEquals(0, roleFrames(net))
        assertTrue("they did sync", net.frames!!.count { JSONObject(it).optString("t") == "inv" } > 20)
        for (label in listOf("B", "C")) assertEquals(label, r(net, "A").roles.digest, r(net, label).roles.digest)
    }

    @Test fun `a lasting mismatch sends the set once per change of either side, not at every link-up, and again only after ten minutes`() {
        val net = FakeNet(); net.frames = ArrayList()
        net.node("A"); net.node("Z")
        net.found("A"); net.found("Z")              // two founders: these two phones never agree
        val first = net.now
        net.connect("A", "Z")
        assertEquals("each hands the other its set once", 2, roleFrames(net))
        assertNotEquals(r(net, "A").roles.digest, r(net, "Z").roles.digest)
        net.frames!!.clear()
        repeat(5) {
            net.disconnect("A", "Z"); net.connect("A", "Z")
            net.now += Router.SYNC_MS + 1_000; net.tickAll()
        }
        assertEquals("not at every link-up or sync inside ten minutes", 0, roleFrames(net))
        while (net.now - first < Router.ROLES_AGAIN_MS + 4 * Router.SYNC_MS) { net.now += Router.SYNC_MS + 1_000; net.tickAll() }
        assertEquals("once more, each way, after ten minutes", 2, roleFrames(net))
        // a change on either side is a new pair: sent once each way, at the next sync
        net.frames!!.clear()
        assertEquals(RoleChange.DONE, r(net, "Z").setOnlyAdmins(true)); net.pump()
        net.syncs()
        assertEquals(2, roleFrames(net))
    }

    @Test fun `a phone that comes back without its roles is handed them again by a phone that already told it`() {
        val net = FakeNet.groupOf("A", "B")         // A told B its set at link-up
        net.now += Router.SYNC_MS + 1_000; net.tickAll()     // and B's next inventory says they agree
        assertEquals(r(net, "A").roles.digest, r(net, "B").roles.digest)
        // the group deleted on B and joined again: the same phone, nothing saved, no carrier to hand back (founding has none)
        net.disconnect("A", "B")
        val b2 = net.node("B").router
        net.connect("A", "B")
        assertEquals(net.id("A"), b2.founder())
        // again, before its next inventory could say it agreed: a phone that holds nothing is handed the set all the same
        net.disconnect("A", "B")
        val b3 = net.node("B").router
        net.connect("A", "B")
        assertEquals(net.id("A"), b3.founder())
    }

    @Test fun `a phone back with nothing before it said it agreed is handed the set at once, and shows nothing the set holds back`() {
        val net = FakeNet.groupOf("A")
        val a = r(net, "A")
        assertEquals(RoleChange.DONE, a.setOnlyAdmins(true))
        net.now += 49 * hour; net.tickAll()                        // the change's carrier is gone: only A can tell anyone
        val post = FakeNet.envelope("M", Envelope.CHAT, JSONObject().put("text", "from a member"), net.now)
        FakeNet.hand(a, post)
        assertNull("held on A", a.message(post.id))
        net.node("B"); net.connect("A", "B")
        val b = r(net, "B")
        assertTrue(b.onlyAdminsSend()); assertNull(b.message(post.id))
        // gone before its next inventory could say it agreed, and back a minute later with nothing (deleted and joined again)
        net.disconnect("A", "B")
        net.now += min
        net.frames!!.clear()
        val b2 = net.node("B").router
        net.connect("A", "B")
        assertEquals("handed the set at once", 1, roleFrames(net))
        assertTrue(b2.onlyAdminsSend()); assertFalse(b2.mayPost())
        assertNull("held, not shown", b2.message(post.id))
        assertNull(b2.sendChat("can I say something?"))
        net.syncs()
        assertNull(b2.message(post.id))
    }

    @Test fun `a set the radio refused on the spot was never told, and the next link hands it over`() {
        val net = FakeNet()
        val radio = FakeNet.Quiet()                                 // every frame refused: the link went from under it
        val a = Router(FakeNet.identity("A"), FakeNet.group(), radio, net.Recorder()) { net.now }
        a.foundIfDue(sure = true, ts = net.now)
        val theirs = Roles(tag, FakeNet.keysOf("B")).also { it.found(true, net.now) }.digest   // B holds a set of its own
        fun linkUp(link: String) {
            a.onLinkUp(link, net.id("B"), "B", "tok"); FakeNet.prove(a, link, "B", "tok")
            a.onBytes(link, JSONObject().put("t", "inv").put("n", 1).put("i", 0).put("ids", JSONArray()).put("rd", theirs).toString().toByteArray())
        }
        fun tried() = radio.frames.count { JSONObject(it).optString("t") == "roles" }
        linkUp("L1")
        assertEquals(1, tried())
        a.onLinkDown("L1")
        net.now += min
        linkUp("L2")
        assertEquals("handed over again", 2, tried())
    }

    @Test fun `a set too big for one frame is passed on once after its last frame, and never echoed back`() {
        val net = FakeNet(); net.frames = ArrayList()
        net.node("A"); net.found("A")
        val a = r(net, "A")
        val x = net.id("X")
        repeat(100) { net.now += 1_000; a.roles.grant(x, net.now)!!; net.now += 1_000; a.roles.revoke(x, net.now)!! }
        net.node("B"); net.node("C"); net.connect("B", "C")
        net.frames!!.clear()
        net.connect("A", "B")
        val frames = net.frames!!.map { JSONObject(it) }.filter { it.optString("t") == "roles" }
        val sets = frames.count { it.has("rd") }
        assertEquals("A to B, then B to C: never back to A, and nothing from C", 2, sets)
        val perSet = frames.size / sets
        assertTrue("$perSet frames a set", perSet >= 2)
        assertEquals(2 * perSet, frames.size)
        assertEquals(a.roles.digest, r(net, "C").roles.digest)
        net.frames!!.clear()
        net.syncs()
        assertEquals(0, roleFrames(net))
    }

    @Test fun `a set that comes in several frames is taken in whole after its last one, with no line for a state in between`() {
        val net = FakeNet(); net.frames = ArrayList()
        net.node("A"); net.found("A"); net.node("B")
        val a = r(net, "A")
        val x = net.id("X"); val y = net.id("Y")
        net.now += min
        val early = a.roles.grant(x, net.now)!!
        repeat(50) { net.now += 1_000; a.roles.grant(y, net.now)!!; net.now += 1_000; a.roles.revoke(y, net.now)!! }
        // made last by a clock that ran behind: X's dismissal comes after the grant in fold order but is stamped before it
        val late = a.roles.revoke(x, early.ts - 30_000)!!
        assertFalse(a.roles.told.any { it.id == early.id })
        net.connect("A", "B")
        val frames = net.frames!!.filter { JSONObject(it).optString("t") == "roles" }
        assertTrue("${frames.size} frames", frames.size >= 2)
        assertTrue("the grant is in the first", JSONObject(frames[0]).toString().contains(early.sig))
        // a phone that gets them one at a time
        val c = linkedByHand(net, "C", "A")
        for ((i, f) in frames.withIndex()) {
            c.onBytes("L", f.toByteArray())
            if (i == frames.lastIndex) break
            assertTrue("after frame $i", c.roles.isEmpty)
            assertTrue("after frame $i", lines(c).isEmpty())
        }
        assertEquals(a.roles.digest, c.roles.digest)
        assertNull("the grant was never the group's state: no line", c.message("role.${early.id}"))
        assertNotNull(c.message("role.${late.id}"))
    }

    @Test fun `a lost roles frame is sent again at the next sync`() {
        val net = FakeNet()
        val sent = ArrayList<Pair<Long, JSONObject>>()
        val a = Router(FakeNet.identity("A"), FakeNet.group(), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long { sent.add(sent.size + 1L to JSONObject(String(bytes, Charsets.UTF_8))); return sent.size.toLong() }
            override fun disconnect(linkId: String) {}
        }, net.Recorder()) { net.now }
        a.foundIfDue(sure = true, ts = net.now)
        a.onLinkUp("L", net.id("B"), "B", "tok"); FakeNet.prove(a, "L", "B", "tok")
        val theirs = Roles(tag, FakeNet.keysOf("B")).also { it.found(true, net.now) }.digest   // B holds a set of its own, and says so at every sync
        fun syncFromB() {
            net.now += Router.SYNC_MS
            a.onBytes("L", JSONObject().put("t", "inv").put("n", 1).put("i", 0).put("ids", JSONArray()).put("rd", theirs).toString().toByteArray())
        }
        fun told() = sent.filter { it.second.optString("t") == "roles" }
        syncFromB()
        assertEquals(1, told().size)
        a.onPayloadFailed(told().last().first)                  // the radio lost it
        syncFromB()
        assertEquals("sent again", 2, told().size)
        a.onPayloadSent(told().last().first)                    // this one got there
        syncFromB()
        assertEquals("not again so soon", 2, told().size)
    }

    @Test fun `a change learned by frame is passed on to the phone's other links at once`() {
        val net = FakeNet(); net.frames = ArrayList()
        net.node("B"); net.node("C"); net.connect("B", "C")
        net.node("A"); net.found("A")
        net.connect("A", "B")
        // no sync, no inventory from C since: B handed it on the moment it took it in
        assertEquals(net.id("A"), r(net, "C").founder())
        assertEquals(2, roleFrames(net))
    }

    // ---------------------------------------------------------------- lines

    @Test fun `a change that stops being told takes its line back, and no message is touched`() {
        val net = FakeNet.groupOf("A", "B", "E")
        val a = r(net, "A"); val e = r(net, "E")
        assertEquals(RoleChange.DONE, a.grantAdmin(e.me.id)); net.pump()
        r(net, "B").sendChat("before all this"); net.pump()
        net.now += hour
        net.know("A", "X")
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("X"))); net.pump()
        val grant = a.roles.effective.last()
        val labels = listOf("A", "B", "E")
        for (l in labels) assertNotNull(l, r(net, l).message("role.${grant.id}"))
        val others = labels.associateWith { l -> r(net, l).messages.filter { it.id != "role.${grant.id}" }.toList() }
        // E's clock runs ten minutes behind: its dismissal of X comes after A's grant in fold order, but is stamped before it
        net.nodes["E"]!!.skew = -10 * min
        assertEquals(RoleChange.DONE, e.dismissAdmin(net.id("X"))); net.pump()
        val dismissal = e.roles.effective.last()
        assertTrue(dismissal.ts < grant.ts)
        for (l in labels) {
            val r = r(net, l)
            assertNull("$l: taken back", r.message("role.${grant.id}"))
            assertNotNull(l, r.message("role.${dismissal.id}"))
            assertFalse(l, r.isAdmin(net.id("X")))
            // every other message is the very same, in the same order
            val now = r.messages.filter { it.id != "role.${dismissal.id}" }
            assertEquals(l, others.getValue(l).size, now.size)
            for ((was, is_) in others.getValue(l).zip(now)) assertSame(l, was, is_)
        }
    }

    @Test fun `after concurrent changes the newest line says what the group is now`() {
        // The founder and the stale admin are picked by id at run time: of the two changes made at once below,
        // the stale admin's folds first, so it counts, yet is overruled by the founder's newer one.
        val (f, s) = listOf("P", "Q").sortedByDescending { FakeNet.idOf(it) }
        val net = FakeNet()
        for (l in listOf(f, s, "C")) net.node(l)
        net.found(f)
        net.connect(f, s); net.connect(f, "C")
        val founder = r(net, f); val stale = r(net, s)
        assertEquals(RoleChange.DONE, founder.grantAdmin(stale.me.id)); net.pump()
        net.now += min; assertEquals(RoleChange.DONE, founder.setOnlyAdmins(true)); net.pump()
        net.disconnect(f, s)
        net.now += min; assertEquals(RoleChange.DONE, founder.setOnlyAdmins(false)); net.pump()
        net.now += min; assertEquals(RoleChange.DONE, founder.setOnlyAdmins(true)); net.pump()
        // the stale phone still has only admins, and lets everyone send — stamped after all of the founder's changes
        net.now += min; assertEquals(RoleChange.DONE, stale.setOnlyAdmins(false))
        net.connect(f, s)
        for (label in listOf(f, s, "C")) {
            val r = r(net, label)
            assertTrue(label, r.onlyAdminsSend())
            assertTrue("$label: the stale change counted", r.roles.effective.any { it.author == stale.me.id && it.type == RoleOp.SET })
            val newest = lines(r).filter { it.kind == Message.ROLE_SEND }.maxByOrNull { it.ts }!!
            assertEquals(label, RoleOp.ADMINS, newest.text)
            assertEquals(label, founder.me.id, newest.from)
        }
    }

    @Test fun `a line already filed in the history is never made again, even weeks later`() {
        val net = FakeNet.groupOf("A", "B")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(net.id("B"))); net.pump()
        val id = "role.${r(net, "A").roles.effective.last().id}"
        assertNotNull(r(net, "B").message(id))
        // B's chat fills up, and the line goes to the history with the oldest
        val state = copy(r(net, "B").snapshot())
        val more = state.getJSONArray("messages")
        for (i in 1..Router.MAX_MESSAGES + Router.SPILL_BATCH)
            more.put(Message("g%05d".format(i), Envelope.CHAT, net.id("A"), "A", null, "message $i", net.now + i * 1_000L).toJson())
        val later = FakeNet().also { it.now = net.now + 3 * hour }
        val b = later.node("B").router
        b.restore(state); b.tick()
        assertTrue(b.takeOverflow().any { it.id == id })
        assertNull(b.message(id))
        // three weeks on this phone has forgotten the history's ids; it restarts, and A's set comes round with a change
        later.now += 21 * day; b.tick()
        assertFalse(b.snapshot().optJSONObject("spilled")?.has(id) ?: false)
        val b2 = FakeNet().also { it.now = later.now }.node("B").router
        b2.restore(copy(b.snapshot()))
        assertNull(b2.message(id))
        b2.onLinkUp("L", net.id("A"), "A", "tok"); FakeNet.prove(b2, "L", "A", "tok")
        val atA = Roles(tag, FakeNet.keysOf("A")).also { it.restore(r(net, "A").roles.toJson()) }
        val only = atA.setOnlyAdmins(true, later.now)!!
        b2.onBytes("L", FakeNet.roles(atA.wire(), rd = atA.digest))
        assertTrue(b2.onlyAdminsSend())
        assertNotNull(b2.message("role.${only.id}"))
        assertNull("never made again", b2.message(id))
    }

    @Test fun `my own change gets its line at once, and no bubble when my carrier comes back`() {
        val net = FakeNet.groupOf("A", "B")
        val a = r(net, "A")
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("B")))          // before anything is delivered
        val op = a.roles.effective.last()
        val line = a.message("role.${op.id}")!!
        assertEquals(Message.ROLE_ADMIN, line.kind); assertEquals(a.me.id, line.from); assertEquals(net.id("B"), line.text)
        assertEquals(op.ts, line.ts); assertEquals(a.me.name, line.fromName)
        net.pump()
        // killed before it saved the carrier: a friend hands it back
        val cid = carriers(a).single().first.getString("id")
        val state = copy(a.snapshot())
        state.put("carry", JSONArray()).put("born", JSONObject())
        net.disconnect("A", "B")
        val a2 = net.node("A")
        a2.router.restore(state)
        net.connect("A", "B")
        assertTrue("carried again", a2.router.carries(cid))
        assertNull("never a bubble", a2.router.message(cid))
        assertEquals(1, lines(a2.router).count { it.kind == Message.ROLE_ADMIN })
        assertTrue(a2.rec.shown.isEmpty())
    }

    @Test fun `starting a group says You started this group on the starter's phone only, and never on the air`() {
        val net = FakeNet(); net.frames = ArrayList()
        net.node("A"); net.node("B")
        net.found("A")
        val a = r(net, "A")
        val pin = a.roles.pin!!
        val line = lines(a).single()
        assertEquals(Message.ROLE_STARTED, line.kind); assertEquals("role.${pin.id}", line.id)
        assertEquals(a.me.id, line.from); assertEquals("", line.text); assertEquals(pin.ts, line.ts)
        assertTrue(line.isNotice); assertFalse(line.isPersonal)
        net.found("A")
        assertEquals("said once", 1, lines(a).size)
        assertEquals("nothing to carry", 0, a.carrySize())
        net.connect("A", "B"); net.syncs()
        assertEquals(net.id("A"), r(net, "B").founder())
        assertTrue(lines(r(net, "B")).isEmpty())
        assertTrue(net.frames!!.none { it.contains("role.") || it.contains(Message.ROLE_STARTED) })
    }

    @Test fun `a group claimed on upgrade gets no started line`() {
        val net = FakeNet(); val a = net.node("A").router
        a.foundIfDue(sure = false, ts = net.now)
        assertEquals(a.me.id, a.founder())
        assertFalse(a.roles.founderSure)
        assertTrue(lines(a).isEmpty())
    }

    @Test fun `a grant of someone already an admin makes no line`() {
        val net = FakeNet.groupOf("A", "B", "C")
        val a = r(net, "A"); val c = r(net, "C")
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("B"))); net.pump()
        val before = lines(c).map { it.id }
        assertEquals(1, before.size)
        assertEquals(RoleChange.UNCHANGED, a.grantAdmin(net.id("B")))
        // one that does get made — by a phone of A's that didn't know — is kept, changes nothing, and has no line
        val again = RoleOp.make(FakeNet.keysOf("A"), tag, a.roles.nextN()!!, net.now, RoleOp.GRANT, mapOf("x" to net.id("B")))
        val env = FakeNet.envelope("A", Envelope.CHAT, JSONObject().put("text", Router.ROLE_MARK + "made B a group admin")
            .put("ro", JSONArray().put(again.toJson())), net.now)
        c.onBytes("C>B", FakeNet.frame(env)); net.pump()
        assertTrue(c.roles.knows(again.id))
        assertFalse(c.roles.effective.any { it.id == again.id })
        assertEquals(before, lines(c).map { it.id })
    }

    @Test fun `a 2_4 bubble of a change becomes its line when the change arrives, even after its carrier expired`() {
        val net = FakeNet.groupOf("A", "B")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(net.id("B"))); net.pump()
        val op = r(net, "A").roles.effective.last()
        val (env, p) = carriers(r(net, "A")).single()
        net.now += 3 * day; net.tickAll()
        val (state, bubble) = keptBy24(env, p.getString("text"), carried = false)
        val c = net.node("C").router
        c.restore(state)
        assertEquals("nothing here says what it is yet", Envelope.CHAT, c.message(bubble.id)!!.kind)
        net.connect("B", "C")
        assertNull(c.message(bubble.id))
        assertEquals(listOf(Message.ROLE_ADMIN, Envelope.CHAT), c.messages.map { it.kind })
        val line = c.messages[0]
        assertEquals("role.${op.id}", line.id); assertEquals(net.id("A"), line.from); assertEquals(net.id("B"), line.text)
        assertEquals("in the bubble's place", bubble.ts, line.ts); assertEquals(bubble.arrivedAt, line.arrivedAt)
    }

    @Test fun `a 2_4 bubble the person deleted gets no line`() {
        val net = FakeNet.groupOf("A", "B")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(net.id("B"))); net.pump()
        val (env, p) = carriers(r(net, "A")).single()
        net.now += 3 * day; net.tickAll()
        val (state, _) = keptBy24(env, p.getString("text"), carried = false, deleted = true)
        val c = net.node("C").router
        c.restore(state)
        net.connect("B", "C")
        assertTrue(c.isAdmin(net.id("B")))
        assertTrue(lines(c).isEmpty())
        assertEquals(listOf("and then"), c.messages.map { it.text })
    }

    // ---------------------------------------------------------------- restarts, leaving, upgrading

    @Test fun `after a restart nothing is sent again and nothing is shown twice`() {
        val net = FakeNet.groupOf("A", "B", "C")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(net.id("B"))); assertEquals(RoleChange.DONE, r(net, "A").setOnlyAdmins(true))
        net.pump()
        val labels = listOf("A", "B", "C")
        val linesBefore = labels.associateWith { l -> lines(r(net, l)).map { it.id } }
        val carried = labels.associateWith { l -> carriers(r(net, l)).map { it.first.getString("id") }.toSet() }
        val saved = labels.associateWith { l -> copy(r(net, l).snapshot()) }
        net.disconnect("A", "B"); net.disconnect("B", "C")
        for (l in labels) net.node(l).router.restore(saved.getValue(l))
        net.frames!!.clear()
        net.connect("A", "B"); net.connect("B", "C")
        net.syncs()
        assertEquals(0, roleFrames(net))
        for (l in labels) {
            assertEquals(l, linesBefore[l], lines(r(net, l)).map { it.id })
            assertEquals(l, carried[l], carriers(r(net, l)).map { it.first.getString("id") }.toSet())
            assertTrue(l, net.nodes[l]!!.rec.shown.isEmpty())
        }
    }

    @Test fun `restoring a state with roles is not a change worth saving`() {
        val net = FakeNet.groupOf("A", "B")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(net.id("B"))); assertEquals(RoleChange.DONE, r(net, "A").setOnlyAdmins(true))
        net.pump()
        for (label in listOf("A", "B")) {
            val was = r(net, label)
            val back = FakeNet().also { it.now = net.now }.node(label).router
            back.restore(copy(was.snapshot()))
            assertFalse(label, back.takeDirty())
            assertEquals(label, was.roles.digest, back.roles.digest)
            assertEquals(label, lines(was).map { it.id }, lines(back).map { it.id })
            assertTrue(label, back.onlyAdminsSend())
        }
    }

    @Test fun `rebuilding my own lost carrier from a friend puts my op back, not a bubble`() {
        val net = FakeNet(); net.legacy.add("L")
        net.node("A"); net.found("A"); net.node("L"); net.connect("A", "L")
        net.know("A", "C")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(net.id("C"))); net.pump()
        val cid = carriers(r(net, "A")).single().first.getString("id")
        // the group was deleted on A's phone and joined again: nothing saved, and its only friend is a 2.4 phone,
        // which hands back what it carries but never a role set
        net.disconnect("A", "L")
        val a2 = net.node("A")
        net.connect("A", "L")
        val a = a2.router
        assertTrue(a.carries(cid))
        assertNull("never a bubble", a.message(cid))
        assertTrue(a.messages.none { it.kind == Envelope.CHAT })
        assertEquals(net.id("A"), a.founder()); assertTrue(a.isAdmin(net.id("C")))
        assertTrue(lines(a).any { it.kind == Message.ROLE_ADMIN && it.text == net.id("C") })
        assertTrue("not news to me", a2.rec.shown.isEmpty())
    }

    @Test fun `an admin who leaves and rejoins is still an admin and learns what changed while away`() {
        val net = FakeNet.groupOf("A", "B", "C")
        val a = r(net, "A")
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("B"))); net.pump()
        val archive = net.leave("B").archive
        assertTrue("an admin who left is still one", a.isAdmin(net.id("B")))
        net.connect("A", "C")
        net.now += hour
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("C"))); net.pump()
        val b = net.rejoin("B", archive, "A").router
        assertTrue(b.isAdmin(b.me.id))
        assertTrue(b.isAdmin(net.id("C")))
        assertEquals(a.roles.digest, b.roles.digest)
        assertEquals(1, lines(b).count { it.kind == Message.ROLE_ADMIN && it.text == net.id("C") })
        assertEquals("the line from before is not made again", 1, lines(b).count { it.kind == Message.ROLE_ADMIN && it.text == b.me.id })
    }

    @Test fun `a left group's kept chat shows the admins as they were`() {
        val net = FakeNet.groupOf("A", "B", "C")
        val a = r(net, "A")
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("B"))); assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)); net.pump()
        val had = lines(r(net, "B")).map { it.id }
        val archive = net.leave("B").archive
        net.connect("A", "C")
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("C"))); assertEquals(RoleChange.DONE, a.setOnlyAdmins(false)); net.pump()
        val kept = Router(FakeNet.identity("B"), FakeNet.group(), FakeNet.Quiet(), net.Recorder()) { net.now }
        kept.restore(copy(archive))
        assertEquals(listOf(net.id("A"), net.id("B")), kept.admins())
        assertTrue(kept.onlyAdminsSend())
        assertEquals(had, lines(kept).map { it.id })
        assertFalse(kept.takeDirty())
    }

    @Test fun `a 2_4 bubble of a carrier still carried becomes the line on upgrade and its ops count`() {
        val net = FakeNet.groupOf("A", "B")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(net.id("B"))); net.pump()
        val op = r(net, "A").roles.effective.last()
        val (env, p) = carriers(r(net, "A")).single()
        // C ran 2.4 when the change went by: it kept the carrier, and showed it as a bubble of its words
        val (state, bubble) = keptBy24(env, p.getString("text"), carried = true)
        val c = FakeNet().also { it.now = net.now + hour }.node("C").router
        c.restore(state)
        assertEquals(net.id("A"), c.founder()); assertTrue(c.isAdmin(net.id("B")))
        assertNull(c.message(bubble.id))
        assertEquals(listOf(Message.ROLE_ADMIN, Envelope.CHAT), c.messages.map { it.kind })
        val line = c.messages[0]
        assertEquals("role.${op.id}", line.id); assertEquals(net.id("A"), line.from); assertEquals(net.id("B"), line.text)
        assertEquals(bubble.ts, line.ts); assertEquals(bubble.arrivedAt, line.arrivedAt)
        assertTrue("still carried for the others", c.carries(bubble.id))
        assertFalse(c.takeDirty())
    }

    @Test fun `the founder's state lost and rebuilt founds the same op, and nobody sees a second founder`() {
        val net = FakeNet()
        net.node("A")
        val savedAt = net.now
        r(net, "A").foundIfDue(sure = true, ts = savedAt)
        val pin = r(net, "A").roles.pin!!
        net.node("B"); net.connect("A", "B")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(net.id("B"))); net.pump()
        // A's state is set aside: the same phone, and the group still saved at the same moment
        net.now += hour
        net.disconnect("A", "B")
        val a2 = net.node("A").router
        a2.foundIfDue(sure = true, ts = savedAt)
        assertEquals(pin.id, a2.roles.pin!!.id)
        net.connect("A", "B")
        val b = r(net, "B")
        assertEquals(pin.id, b.roles.pin!!.id)
        assertEquals(1, b.roles.wire().count { it.type == RoleOp.FOUND })
        assertEquals(b.roles.digest, a2.roles.digest)
        assertTrue(a2.isAdmin(net.id("B")))
        assertTrue(lines(b).none { it.kind == Message.ROLE_STARTED })
        assertEquals(1, lines(a2).count { it.kind == Message.ROLE_STARTED })
    }

    @Test fun `saved roles of the wrong shape cost nothing else in the chat`() {
        val net = FakeNet.groupOf("A", "B")
        assertEquals(RoleChange.DONE, r(net, "A").grantAdmin(net.id("B"))); net.pump()
        r(net, "B").sendChat("hello"); net.pump()
        val good = copy(r(net, "B").snapshot())
        for (bad in listOf<Any>("x", JSONObject().put("pin", 5), JSONObject().put("ops", JSONArray().put(1).put(JSONObject())),
                JSONObject().put("noticed", "x"), JSONArray().put(JSONObject()))) {
            val b = FakeNet().also { it.now = net.now }.node("B").router
            b.restore(copy(good).put("roles", bad))
            assertTrue("$bad", b.roles.isEmpty)
            assertEquals("$bad", r(net, "B").messages.map { it.id }, b.messages.map { it.id })
            assertEquals("$bad", r(net, "B").people.keys, b.people.keys)
            assertTrue("$bad", b.carries(r(net, "B").messages.single { it.text == "hello" }.id))
            assertFalse("$bad", b.takeDirty())
        }
    }

    // ---------------------------------------------------------------- asking for changes

    @Test fun `only an admin can make admins, dismiss them or change who can send, and each refusal says why`() {
        val net = FakeNet.groupOf("A", "B", "C")
        val a = r(net, "A"); val b = r(net, "B")
        val aid = net.id("A"); val bid = net.id("B"); val cid = net.id("C")
        fun refused(r: Router, why: String, expected: RoleChange, change: () -> RoleChange) {
            val digest = r.roles.digest; val carried = r.carrySize(); val made = lines(r).size
            assertEquals(why, expected, change())
            assertEquals(why, digest, r.roles.digest); assertEquals(why, carried, r.carrySize()); assertEquals(why, made, lines(r).size)
        }
        refused(b, "a member makes an admin", RoleChange.NOT_ADMIN) { b.grantAdmin(cid) }
        refused(b, "a member dismisses", RoleChange.NOT_ADMIN) { b.dismissAdmin(aid) }
        refused(b, "a member restricts", RoleChange.NOT_ADMIN) { b.setOnlyAdmins(true) }
        refused(a, "an admin already", RoleChange.UNCHANGED) { a.grantAdmin(aid) }
        refused(a, "the one who started the group", RoleChange.CREATOR) { a.dismissAdmin(aid) }
        refused(a, "not an admin", RoleChange.UNCHANGED) { a.dismissAdmin(bid) }
        refused(a, "already everyone", RoleChange.UNCHANGED) { a.setOnlyAdmins(false) }
        refused(a, "nobody this phone knows", RoleChange.NOT_MEMBER) { a.grantAdmin(net.id("Q")) }
        refused(a, "not a node id", RoleChange.NOT_MEMBER) { a.grantAdmin("not-a-node-id") }
        a.people[cid]!!.leftQ = 1
        refused(a, "left the group", RoleChange.NOT_MEMBER) { a.grantAdmin(cid) }
        a.people[cid]!!.leftQ = 0
        assertEquals(RoleChange.DONE, a.grantAdmin(bid)); net.pump()
        refused(a, "made already", RoleChange.UNCHANGED) { a.grantAdmin(bid) }
        assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)); net.pump()
        refused(a, "already only admins", RoleChange.UNCHANGED) { a.setOnlyAdmins(true) }
        refused(b, "an admin can't dismiss the one who started the group either", RoleChange.CREATOR) { b.dismissAdmin(aid) }
        // the set at its cap: nobody but the founder may add to it, and the founder only up to 512 of its own
        while (!a.roles.full(aid)) { net.now += 1; a.roles.grant(cid, net.now) ?: a.roles.revoke(cid, net.now)!! }
        net.syncs(1)
        assertEquals(a.roles.digest, b.roles.digest)
        assertTrue(b.roles.full(bid))
        refused(b, "the set is full", RoleChange.FULL) { b.setOnlyAdmins(false) }
        refused(a, "the founder's 512 are made", RoleChange.FULL) { a.setOnlyAdmins(false) }
    }

    @Test fun `a dismissal or a restriction is stamped after the newest post this phone shows, at most five minutes ahead`() {
        val net = FakeNet.groupOf("A", "B")
        val a = r(net, "A"); val b = r(net, "B")
        net.nodes["A"]!!.skew = -3 * min                       // A's clock runs three minutes behind B's
        net.know("A", "X")
        fun last() = a.roles.effective.last().ts
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("X"))); assertEquals("nothing shown yet: now", net.now - 3 * min, last())
        val post = b.sendChat("said before the change")!!; net.pump()
        assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)); assertEquals("after what this phone shows", post.ts + 1, last())
        assertEquals(RoleChange.DONE, a.dismissAdmin(net.id("X"))); assertEquals(post.ts + 1, last())
        assertEquals(RoleChange.DONE, a.setOnlyAdmins(false)); assertEquals("a lift is stamped now", net.now - 3 * min, last())
        assertEquals(RoleChange.DONE, a.grantAdmin(net.id("X"))); assertEquals("so is a grant", net.now - 3 * min, last())
        // a post from a clock an hour ahead: never more than five minutes past this phone's own
        net.nodes["B"]!!.skew = hour
        b.sendChat("from the future"); net.pump()
        assertEquals(RoleChange.DONE, a.setOnlyAdmins(true)); assertEquals(net.now - 3 * min + Router.FUTURE_SLACK_MS, last())
    }
}
