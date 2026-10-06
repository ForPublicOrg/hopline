package app.hopline.mesh

import app.hopline.core.Crypto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.4's wire: sealed envelopes signed by the phone that wrote them, node ids made from keys, and
 * links that prove the code and the key. What a member, a stranger in range, or a recording of
 * the radio can and can't do.
 */
class WireTest {

    private fun spy(frames: ArrayList<String>) = object : Transport {
        override fun send(linkId: String, bytes: ByteArray): Long { frames.add(String(bytes, Charsets.UTF_8)); return frames.size.toLong() }
        override fun disconnect(linkId: String) { frames.add("disconnect $linkId") }
    }

    // ---------------------------------------------------------------- linking

    @Test fun `a proof with a wrong mac, someone else's key or a bad signature changes nothing`() {
        val net = FakeNet(); val b = net.node("B")
        fun attempt(mac: (ByteArray) -> String, pk: String, sig: (ByteArray) -> String): Pair<Router, ArrayList<String>> {
            val frames = ArrayList<String>()
            val r = Router(FakeNet.identity("B"), FakeNet.group(), spy(frames), b.rec) { net.now }
            r.onLinkUp("L", net.id("X"), "Stranger", "tok")
            r.onBytes("L", JSONObject().put("t", "hello").put("id", net.id("X")).put("nonce", Crypto.randomId(16)).put("v", Router.VERSION).toString().toByteArray())
            val t = Crypto.lp("hopline/v5/proof", "tok", r.links["L"]!!.myNonce, net.id("X"), r.me.id)
            frames.clear()
            r.onBytes("L", JSONObject().put("t", "proof").put("mac", mac(t)).put("pk", pk).put("sig", sig(t)).toString().toByteArray())
            return r to frames
        }
        val goodMac = { t: ByteArray -> Crypto.b64(Crypto.hmac(FakeNet.group().keys.link, t)) }
        val goodSig = { t: ByteArray -> Crypto.sign(FakeNet.keysOf("X").priv, t) }
        for ((why, tried) in listOf(
            "a wrong code" to attempt({ t -> Crypto.b64(Crypto.hmac(FakeNet.group("wrong wrong wrong wrong").keys.link, t)) }, FakeNet.keysOf("X").pubB64, goodSig),
            "someone else's key" to attempt(goodMac, FakeNet.keysOf("Y").pubB64, { t -> Crypto.sign(FakeNet.keysOf("Y").priv, t) }),
            "a signature by another key" to attempt(goodMac, FakeNet.keysOf("X").pubB64, { t -> Crypto.sign(FakeNet.keysOf("Y").priv, t) }),
        )) {
            val (r, frames) = tried
            assertNull(why, r.links["L"])
            assertEquals(why, listOf("disconnect L"), frames)          // nothing sent: no inventory, no presence
            assertTrue(why, r.people.isEmpty())
            assertTrue(why, r.authedLinks().isEmpty())
        }
        // the very same, done right, links
        val (r, frames) = attempt(goodMac, FakeNet.keysOf("X").pubB64, goodSig)
        assertTrue(r.links["L"]!!.authed)
        assertEquals(FakeNet.keysOf("X").pubB64, r.keyOf(net.id("X")))
        assertTrue(frames.any { it.contains("\"inv\"") })
    }

    @Test fun `the transport hears when a link has proved itself`() {
        val net = FakeNet(); net.node("A")
        val authed = ArrayList<String>()
        val r = Router(FakeNet.identity("A"), FakeNet.group(), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long = 1
            override fun disconnect(linkId: String) {}
            override fun authed(linkId: String) { authed.add(linkId) }
        }, net.nodes["A"]!!.rec) { net.now }
        r.onLinkUp("L", net.id("B"), "", "tok")
        assertTrue(authed.isEmpty())
        FakeNet.prove(r, "L", "B", "tok")
        assertEquals(listOf("L"), authed)
    }

    // ---------------------------------------------------------------- nobody speaks for anyone else

    /** What member M can craft in A's name: signed with M's own key, or carrying A's key but signed by M. */
    private fun forgeries(net: FakeNet, kind: String, p: JSONObject, id: String, to: String? = null, piece: String? = null): List<Envelope> {
        val a = net.id("A")
        val withMyKey = FakeNet.envelope("M", kind, p, net.now, id = id, to = to, name = "A", piece = piece)
        withMyKey.json.put("o", a)
        FakeNet.resign(withMyKey, "M")
        val withTheirKey = FakeNet.envelope("M", kind, p, net.now, id = id, to = to, name = "A", piece = piece)
        withTheirKey.json.put("o", a).put("pk", FakeNet.keysOf("A").pubB64)
        withTheirKey.json.put("s", Crypto.sign(FakeNet.keysOf("M").priv, withTheirKey.signed(FakeNet.group().airTag)))
        return listOf(withMyKey, withTheirKey)
    }

    @Test fun `a member cannot forge another member's chat, receipt, reaction, presence, piece or file`() {
        val net = FakeNet(); net.line("A", "B", "M")
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!
        val mine = net.nodes["B"]!!.router.sendChat("did everyone get this?")!!; net.pump()
        val toM = net.nodes["B"]!!.router.sendDm(net.id("M"), "just for M")!!; net.pump()
        val shownBefore = b.rec.shown.size
        val (att, pieces) = FakeNet.makeFile(a, ByteArray(5_000) { it.toByte() })
        val crafted = listOf(
            Envelope.CHAT to (JSONObject().put("text", "A never said this") to FakeNet.newId("A")),
            Envelope.RECEIPT to (JSONObject().put("m", toM.id).put("by", net.id("A")) to "r.${toM.id}.${net.id("A")}"),
            Envelope.REACT to (JSONObject().put("m", mine.id).put("e", "💩") to FakeNet.newId("A")),
            Envelope.PRESENCE to (JSONObject().put("n", "A").put("loc", Loc.of(1.0, 2.0)!!.toJson()).put("q", Long.MAX_VALUE / 2) to FakeNet.newId("A")),
            Envelope.FILE to (JSONObject().put("text", "not A's photo").put("att", att.json) to FakeNet.newId("A")),
        )
        for ((kind, pi) in crafted) for (env in forgeries(net, kind, pi.first, pi.second)) {
            b.router.onBytes("B>M", FakeNet.frame(env)); net.pump()
        }
        for (env in forgeries(net, Envelope.CHUNK, JSONObject(), Envelope.chunkId(att.fid, 0), piece = pieces[0])) {
            b.router.onBytes("B>M", FakeNet.frame(env)); net.pump()
        }
        assertEquals(shownBefore, b.rec.shown.size)
        assertTrue(net.texts("B").none { it.contains("A never said") || it.contains("not A's photo") })
        assertTrue(mine.reactions.isEmpty() && b.router.message(mine.id)!!.reactions.isEmpty())
        assertFalse(net.id("A") in toM.reached)
        assertNull(b.router.people[net.id("A")]!!.loc)
        assertFalse(b.router.chunks.has(Envelope.chunkId(att.fid, 0)))
        assertNull(b.router.fileMessage(att.fid))
        assertTrue(b.rec.log.count { it.contains("forged") } >= 10)
        // none of it was even remembered: the real file, under the very ids M tried, still gets through
        assertTrue(a.sendFile(att, pieces, "the real one") != null); net.pump()
        assertEquals("the real one", b.router.fileMessage(att.fid)!!.text)
        assertTrue(b.router.fileComplete(att))
        assertArrayEquals(ByteArray(5_000) { it.toByte() }, FakeNet.reassemble(b.router, att))
    }

    @Test fun `ids in someone else's space are refused, and the real envelope still gets through`() {
        // A isn't in range yet: M gets there first
        val net = FakeNet(); net.node("A"); net.line("B", "M")
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router
        val asked = b.sendChat("who has it?")!!; net.pump()
        // M signs with its own key, honestly as itself — but takes ids that are A's to make
        val chatId = FakeNet.newId("A")
        val receiptId = "r.${asked.id}.${net.id("A")}"
        val (att, pieces) = FakeNet.makeFile(a, ByteArray(3_000) { 7 })
        val pieceId = Envelope.chunkId(att.fid, 0)
        for (env in listOf(
            FakeNet.envelope("M", Envelope.CHAT, JSONObject().put("text", "squatted"), net.now, id = chatId),
            FakeNet.envelope("M", Envelope.RECEIPT, JSONObject().put("m", asked.id).put("by", net.id("M")), net.now, id = receiptId, to = net.id("B")),
            FakeNet.envelope("M", Envelope.CHUNK, JSONObject(), net.now, id = pieceId, piece = pieces[0]),
        )) { b.onBytes("B>M", FakeNet.frame(env)); net.pump() }
        assertTrue(net.texts("B").none { it == "squatted" })
        assertFalse(b.chunks.has(pieceId))
        assertFalse(net.id("A") in asked.reached)
        // A's own envelopes with exactly those ids arrive later — and are taken, not turned away as old
        net.connect("A", "B")
        b.onBytes("B>A", FakeNet.fill(FakeNet.envelope("A", Envelope.CHAT, JSONObject().put("text", "the real one"), net.now, id = chatId)))
        net.pump()
        assertTrue(net.texts("B").contains("the real one"))
        assertTrue(net.id("A") in asked.reached)
        assertTrue(a.sendFile(att, pieces, "") != null); net.pump()
        assertTrue(b.chunks.has(pieceId))
    }

    @Test fun `nothing under someone's signature can be changed - words, id, kind, name, time, recipient, one-off key, answer, piece`() {
        // A is not in range. M (a member: it holds the group key) took A's envelopes off the air
        // before B had them, and hands B each with one thing changed — A's own signature kept.
        val net = FakeNet(); net.node("A"); net.line("M", "B", "C")
        val b = net.nodes["B"]!!; val c = net.nodes["C"]!!.router
        val tag = FakeNet.group().airTag
        val a = net.id("A"); val bId = net.id("B")
        val asked = b.router.requestErrand(Errand.READ, JSONObject().put("url", "https://example.org")); net.pump()
        val mine = b.router.sendChat("did everyone get this?")!!; net.pump()
        val (att, pieces) = FakeNet.makeFile(net.nodes["A"]!!.router, ByteArray(5_000) { it.toByte() })
        val chat = FakeNet.envelope("A", Envelope.CHAT, JSONObject().put("text", "A's own words"), net.now)
        val receipt = FakeNet.envelope("A", Envelope.RECEIPT, JSONObject().put("m", mine.id).put("by", a), net.now, id = "r.${mine.id}.$a", to = bId)
        val dm = FakeNet.envelope("A", Envelope.DM, JSONObject().put("text", "for B alone"), net.now, to = bId)
        val inner = JSONObject().put("eid", asked.id).put("ok", true).put("ty", Errand.READ).put("title", "Page")
            .put("z", Gz.pack(JSONObject().put("t", "the page"))).put("by", "A")
        val answer = FakeNet.envelope("A", Envelope.DM, JSONObject().put("text", "Page").put("er", inner), net.now, to = bId,
            er = JSONObject().put("eid", asked.id).put("ok", true))
        val piece = FakeNet.envelope("A", Envelope.CHUNK, JSONObject(), net.now, id = Envelope.chunkId(att.fid, 0), piece = pieces[0])
        fun changed(env: Envelope, change: (JSONObject) -> Unit) = Envelope(JSONObject(env.json.toString())).also { change(it.json) }
        val otherId = FakeNet.newId("A")
        val variants = listOf(
            // other words, sealed so they open: the group key is M's too
            changed(chat) { it.put("c", Crypto.seal(FakeNet.group().keys.env, chat.header(tag), "{\"text\":\"A never said this\"}".toByteArray())) },
            changed(chat) { it.put("id", otherId) },
            changed(chat) { it.put("k", Envelope.ERRAND_RESULT) },
            changed(chat) { it.put("on", "Mallory") },
            changed(chat) { it.put("ts", net.now - 60_000) },
            changed(receipt) { it.put("to", net.id("C")) },
            changed(dm) { it.put("to", net.id("C")) },
            changed(dm) { it.put("e", FakeNet.keysOf("E1").pubB64) },
            changed(answer) { it.getJSONObject("er").put("eid", Crypto.randomId(10)) },
            changed(answer) { it.getJSONObject("er").put("ok", false) },
            changed(piece) { it.put("c", Crypto.sealPiece(att.key!!, 0, ByteArray(5_000))) },
        )
        val shown = b.rec.shown.size
        for (env in variants) { b.router.onBytes("B>M", FakeNet.frame(env)); net.pump() }
        assertEquals(variants.size, b.rec.log.count { it.contains("forged") })
        assertEquals(shown, b.rec.shown.size)
        assertTrue(net.texts("B").none { it.contains("never said") })
        for (id in listOf(chat.id, otherId, receipt.id, dm.id, answer.id)) { assertFalse(b.router.carries(id)); assertFalse(c.carries(id)) }
        assertFalse(b.router.chunks.has(piece.id))
        assertFalse(a in mine.reached)
        assertTrue(asked.isOpen)
        // none of it was remembered: the untouched envelopes, under the very same ids, all get through
        b.router.onBytes("B>M", FakeNet.fill(chat, receipt, dm, answer)); b.router.onBytes("B>M", FakeNet.frame(piece)); net.pump()
        assertTrue(net.texts("B").containsAll(listOf("A's own words", "for B alone")))
        assertTrue(a in mine.reached)
        assertEquals(Errand.DONE, asked.status); assertEquals("the page", asked.answer()!!.getString("t"))
        assertTrue(b.router.chunks.has(piece.id))
        for (id in listOf(chat.id, receipt.id, dm.id, answer.id)) assertTrue(c.carries(id))
    }

    @Test fun `the one-off key, the answered marker and the list of fields are checked before anything is remembered`() {
        // A is not in range; what it signed reaches B through M
        val net = FakeNet(); net.node("A"); net.line("M", "B", "C")
        val b = net.nodes["B"]!!
        val a = net.id("A"); val bId = net.id("B")
        // C asked the group for something: B carries the request, open
        val theirs = net.nodes["C"]!!.router.requestErrand(Errand.READ, JSONObject().put("url", "https://example.org")); net.pump()
        assertTrue(b.router.errands[theirs.id]!!.isOpen)
        val mine = b.router.sendChat("hello")!!; net.pump()
        val (att, pieces) = FakeNet.makeFile(net.nodes["A"]!!.router, ByteArray(2_000) { 1 })
        // A's envelopes as A's phone makes them, not sent yet
        val chat = FakeNet.envelope("A", Envelope.CHAT, JSONObject().put("text", "plain words"), net.now)
        val receipt = FakeNet.envelope("A", Envelope.RECEIPT, JSONObject().put("m", mine.id).put("by", a), net.now, id = "r.${mine.id}.$a", to = bId)
        val dm = FakeNet.envelope("A", Envelope.DM, JSONObject().put("text", "just for B"), net.now, to = bId)
        val file = FakeNet.envelope("A", Envelope.FILE, JSONObject().put("text", "a photo for B").put("att", att.json), net.now, to = bId)
        val piece = FakeNet.envelope("A", Envelope.CHUNK, JSONObject(), net.now, id = Envelope.chunkId(att.fid, 0), to = bId, piece = pieces[0])
        // each bent out of shape one way, then signed again by A: every signature below is genuine
        val marker = JSONObject().put("eid", theirs.id).put("ok", true)
        val oneOff = FakeNet.keysOf("E2").pubB64
        fun bent(env: Envelope, change: (JSONObject) -> Unit) = Envelope(JSONObject(env.json.toString())).also { change(it.json); FakeNet.resign(it, "A") }
        val malformed = listOf(
            bent(chat) { it.put("er", marker) },
            bent(receipt) { it.put("er", marker) },
            bent(dm) { it.put("er", JSONObject().put("eid", "not/an/id").put("ok", true)) },
            bent(dm) { it.put("er", JSONObject(marker.toString()).put("by", "A")) },
            bent(dm) { it.remove("e") },
            bent(file) { it.remove("e") },
            bent(chat) { it.put("e", oneOff) },
            bent(receipt) { it.put("e", oneOff) },
            bent(piece) { it.put("e", oneOff) },
            bent(chat) { it.put("hops", 5) },
            bent(chat) { it.put("age", "5") },
            bent(chat) { it.put("age", -5) },
            bent(chat) { it.put("k", "hologram") },
        )
        for (env in malformed) { b.router.onBytes("B>M", FakeNet.frame(env)); net.pump() }
        assertEquals(malformed.size, b.rec.log.count { it.contains("malformed") })
        for (id in listOf(chat.id, receipt.id, dm.id, file.id)) assertFalse(b.router.carries(id))
        assertFalse(b.router.chunks.has(piece.id))
        assertTrue(b.rec.shown.none { it.from == a })
        assertTrue("an answered marker on anything but a private answer stands nobody down", b.router.errands[theirs.id]!!.isOpen)
        // the same ids, well formed, are taken: nothing of the bent ones was remembered
        b.router.onBytes("B>M", FakeNet.fill(chat, receipt, dm, file)); b.router.onBytes("B>M", FakeNet.frame(piece)); net.pump()
        assertTrue(net.texts("B").containsAll(listOf("plain words", "just for B", "a photo for B")))
        assertTrue(a in mine.reached)
        assertTrue(b.router.chunks.has(piece.id))
    }

    @Test fun `nobody but its two people can put a reaction into a private chat`() {
        val net = FakeNet(); net.line("A", "M", "B")
        val a = net.nodes["A"]!!; val b = net.nodes["B"]!!
        val dm = a.router.sendDm(net.id("B"), "just us")!!; net.pump()
        // M carried it, so M knows its id: a reaction to it for the group, and one sealed for each of them, all in M's own name
        fun reaction(target: String, to: String? = null) =
            FakeNet.envelope("M", Envelope.REACT, JSONObject().put("m", target).put("e", "💩"), net.now, to = to)
        val forAll = reaction(dm.id)
        for (env in listOf(forAll, reaction(dm.id, to = net.id("B")))) { b.router.onBytes("B>M", FakeNet.frame(env)); net.pump() }
        for (env in listOf(forAll, reaction(dm.id, to = net.id("A")))) { a.router.onBytes("A>M", FakeNet.frame(env)); net.pump() }
        assertTrue(dm.reactions.isEmpty()); assertTrue(b.router.message(dm.id)!!.reactions.isEmpty())
        assertTrue(a.rec.reactions.isEmpty())
        // a reaction sealed for one person is no more a group message's than a group one is a private chat's
        val chat = a.router.sendChat("for everyone")!!; net.pump()
        b.router.onBytes("B>M", FakeNet.frame(reaction(chat.id, to = net.id("B")))); net.pump()
        assertTrue(b.router.message(chat.id)!!.reactions.isEmpty())
        // one that comes before its message is held — and checked the same way when the message lands
        val laterId = FakeNet.newId("A")
        b.router.onBytes("B>M", FakeNet.frame(reaction(laterId))); net.pump()
        b.router.onBytes("B>M", FakeNet.frame(FakeNet.envelope("A", Envelope.DM, JSONObject().put("text", "later"), net.now, id = laterId, to = net.id("B"))))
        net.pump()
        assertTrue(b.router.message(laterId)!!.reactions.isEmpty())
        // the chat's own two people still can, of course
        assertTrue(b.router.sendReaction(b.router.message(dm.id)!!, "❤️")); net.pump()
        assertEquals(mapOf(net.id("B") to "❤️"), dm.reactions.toMap())
        assertEquals(listOf(Triple(dm.id, net.id("B"), "❤️")), a.rec.reactions)
    }

    @Test fun `an envelope claiming to be mine but not signed by me is not carried as mine`() {
        val net = FakeNet(); net.line("A", "M")
        val a = net.nodes["A"]!!.router
        val carried = a.carrySize()
        for (env in forgeries(net, Envelope.CHAT, JSONObject().put("text", "you said this"), FakeNet.newId("A"))) {
            a.onBytes("A>M", FakeNet.fill(env)); net.pump()
        }
        assertEquals(carried, a.carrySize())
        assertTrue(a.messages.isEmpty())
    }

    // ---------------------------------------------------------------- what the air and the carry hold

    @Test fun `nothing a carrier or a listener holds shows a private message, a private file or group text`() {
        val net = FakeNet(); net.frames = ArrayList(); net.line("A", "B", "C")
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router; val c = net.nodes["C"]!!.router
        a.sendChat("GROUPWORDS for the whole group"); net.pump()
        a.sendDm(net.id("C"), "DMWORDS just for C")!!; net.pump()
        val bytes = "FILEBYTES private photo bytes ".repeat(1_000).toByteArray()
        val (att, pieces) = FakeNet.makeFile(a, bytes, name = "FILENAME.jpg", thumb = "THUMBDATA")
        a.sendFile(att, pieces, "FILECAPTION for C", to = net.id("C"))!!; net.pump()
        c.sendReaction(c.messages.first { it.text.startsWith("DMWORDS") }, "🫣"); net.pump()
        net.now += Router.SYNC_MS + 1_000; net.tickAll()
        // C can read all of it, B (in the middle) only the group's words
        assertTrue(net.texts("C").containsAll(listOf("GROUPWORDS for the whole group", "DMWORDS just for C", "FILECAPTION for C")))
        assertArrayEquals(bytes, FakeNet.reassemble(c, att))
        assertEquals(listOf("GROUPWORDS for the whole group"), net.texts("B"))
        val firstPiece = bytes.copyOfRange(0, Router.CHUNK_RAW)
        val secrets = listOf("GROUPWORDS", "DMWORDS", "FILECAPTION", "FILENAME", "THUMBDATA", "FILEBYTES", "🫣",
            java.util.Base64.getEncoder().encodeToString(firstPiece).take(40), Crypto.b64(firstPiece).take(40), Crypto.b64(att.key!!))
        val air = net.frames!!
        assertTrue(air.size > 20)
        for (s in secrets) assertTrue("\"$s\" went on the air", air.none { it.contains(s) })
        // and what B carries — its saved backlog and the pieces it keeps — shows none of it either
        val carried = b.snapshot().getJSONArray("carry").toString() + b.chunks.ids().joinToString { b.chunks.get(it)!!.json.toString() }
        for (s in secrets) assertFalse("\"$s\" in B's carry", carried.contains(s))
        assertTrue(b.chunks.ids().isNotEmpty())
        // every piece names the person its file is for, as the file message does
        for (id in b.chunks.ids()) assertEquals(net.id("C"), b.chunks.get(id)!!.to)
        // …and it is the seal that keeps them so, not B's manners: the group's key, which every member
        // holds, opens the group's words and none of the private ones; B's own key none either; each
        // opens only for the one person it is for
        val tag = FakeNet.group().airTag
        val envs = b.snapshot().getJSONArray("carry").let { arr -> (0 until arr.length()).map { Envelope(arr.getJSONObject(it)) } }
        val chat = envs.single { it.kind == Envelope.CHAT }
        assertEquals("GROUPWORDS for the whole group",
            JSONObject(String(Crypto.open(FakeNet.group().keys.env, chat.header(tag), chat.sealed)!!)).getString("text"))
        val forOne = envs.filter { it.isPrivate }
        assertEquals(listOf(Envelope.DM, Envelope.FILE, Envelope.REACT), forOne.map { it.kind }.sorted())
        for (env in forOne) {
            val h = env.header(tag)
            val from = Crypto.decodePub(env.pk)!!
            assertNull(env.kind, Crypto.open(FakeNet.group().keys.env, h, env.sealed))
            assertNull(env.kind, Crypto.openFrom(FakeNet.keysOf("B"), from, env.eph!!, h, env.sealed))
            val forWhom = if (env.to == net.id("C")) "C" else "A"
            val p = JSONObject(String(Crypto.openFrom(FakeNet.keysOf(forWhom), from, env.eph!!, h, env.sealed)!!))
            when (env.kind) {
                Envelope.DM -> assertEquals("DMWORDS just for C", p.getString("text"))
                Envelope.FILE -> assertEquals("FILECAPTION for C", p.getString("text"))
                else -> { assertEquals(net.id("A"), env.to); assertEquals("🫣", p.getString("e")) }
            }
        }
    }

    @Test fun `an envelope from another group is refused, even signed by a member`() {
        val other = FakeNet(); other.node("P", code = "otter ember quilt maple"); other.node("Q", code = "otter ember quilt maple")
        other.connect("P", "Q")
        val p = other.nodes["P"]!!.router
        p.sendChat("from the other camp"); other.pump()
        val theirs = p.snapshot().getJSONArray("carry").getJSONObject(0)
        // Same phone, same key, same id — only the code differs
        val net = FakeNet(); net.line("P", "B", "C")
        val b = net.nodes["B"]!!
        b.router.onBytes("B>P", JSONObject().put("t", "fill").put("envs", JSONArray().put(theirs)).toString().toByteArray()); net.pump()
        assertTrue(net.texts("B").isEmpty()); assertTrue(net.texts("C").isEmpty())
        assertFalse(b.router.carries(theirs.getString("id")))
        assertTrue(b.rec.log.any { it.contains("forged") })
    }

    // ---------------------------------------------------------------- replays and keys

    @Test fun `a presence played again after a restart changes nothing`() {
        val net = FakeNet(); net.frames = ArrayList(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        a.myLoc = Loc.of(12.97, 77.59)
        net.frames!!.clear()
        net.now += 31_000; net.tickAll()
        val beacon = net.frames!!.map { JSONObject(it) }.filter { it.optString("t") == "env" }.map { it.getJSONObject("e") }
            .first { it.getString("k") == Envelope.PRESENCE && it.getString("o") == net.id("A") }
        assertNotNull(net.nodes["B"]!!.router.people[net.id("A")]!!.loc)
        a.myLoc = null
        net.now += 31_000; net.tickAll()
        val b = net.nodes["B"]!!.router
        assertNull(b.people[net.id("A")]!!.loc)
        // B restarts — its memory of what it saw goes — and someone plays the old beacon to it
        net.now += 10 * 60_000
        val b2 = FakeNet().also { it.now = net.now }.node("B")
        b2.router.restore(JSONObject(b.snapshot().toString()))
        val before = b2.router.people[net.id("A")]!!
        val lastSeen = before.lastSeen; val q = before.liveQ
        assertTrue(q > 0)
        b2.router.onLinkUp("L", net.id("X"), "", "tok"); FakeNet.prove(b2.router, "L", "X", "tok")
        b2.router.onBytes("L", JSONObject().put("t", "env").put("e", beacon).toString().toByteArray())
        val after = b2.router.people[net.id("A")]!!
        assertNull(after.loc)
        assertEquals(lastSeen, after.lastSeen)
        assertFalse(b2.router.isInRange(after))
        assertEquals(q, after.liveQ)
    }

    @Test fun `a phone that died between saves and came back with its clock set back is still heard`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!.router
        val saved = JSONObject(net.nodes["A"]!!.router.snapshot().toString())   // its last save
        // a minute and a half of beacons after that, every one heard
        repeat(3) { net.now += 31_000; net.tickAll() }
        val heard = b.people[net.id("A")]!!.liveQ
        // the battery dies; the phone comes back with its clock ten minutes behind
        net.disconnect("A", "B")
        val a2 = net.node("A"); a2.skew = -10 * 60_000L
        a2.router.restore(saved)
        a2.router.battery = 42
        net.connect("A", "B")
        val p = b.people[net.id("A")]!!
        assertTrue("its next beacon counts (${p.liveQ} after $heard)", p.liveQ > heard)
        assertEquals(42, p.battery)
    }

    @Test fun `a group deleted and joined again is heard at once, its counters start above the phone's mark`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!.router
        // A few restarts: each one starts above the mark its state held, so friends know a counter ahead of the clock
        repeat(3) {
            val saved = JSONObject(net.nodes["A"]!!.router.snapshot().toString())
            net.disconnect("A", "B")
            net.node("A").router.restore(saved)
            net.connect("A", "B")
            net.now += 31_000; net.tickAll()
        }
        val heard = b.people[net.id("A")]!!.liveQ
        assertTrue(heard > net.now + 10 * 60_000)
        val mark = net.nodes["A"]!!.router.snapshot().getLong("lastQ")
        // A leaves, deletes the group and joins it again: no state at all. Started from the clock, B ignores it
        net.disconnect("A", "B")
        net.node("A").router.battery = 7
        net.connect("A", "B")
        assertEquals(heard, b.people[net.id("A")]!!.liveQ)
        assertTrue(b.people[net.id("A")]!!.battery != 7)
        // started above the phone's mark, it is heard from its first beacon
        net.disconnect("A", "B")
        val again = net.node("A"); again.router.liveQAbove(mark); again.router.battery = 42
        net.connect("A", "B")
        val p = b.people[net.id("A")]!!
        assertTrue(p.liveQ > heard)
        assertEquals(42, p.battery)
    }

    @Test fun `nothing private is sent to a phone whose key is not known here`() {
        val net = FakeNet(); net.frames = ArrayList(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        val z = net.id("Z")
        // an 8-letter id from before 2.4 has a chat here, and nobody has a key for it
        a.restore(JSONObject().put("messages", JSONArray().put(Message("old1", Envelope.DM, "abcdefgh", "Old friend", a.me.id, "hi", net.now - 1_000).toJson())))
        net.frames!!.clear()
        val carried = a.carrySize(); val shown = a.messages.size
        assertFalse(a.canWriteTo(z)); assertFalse(a.canWriteTo("abcdefgh")); assertFalse(a.canWriteTo(a.me.id))
        assertNull(a.sendDm(z, "hello?"))
        assertNull(a.sendDm("abcdefgh", "hello?"))
        assertNull(a.sendLocation(Loc.of(1.0, 2.0)!!, to = z))
        val (att, pieces) = FakeNet.makeFile(a, ByteArray(2_000))
        assertNull(a.sendFile(att, pieces, "for Z", to = z))
        assertFalse(a.sendReaction(a.message("old1")!!, "👍"))
        net.pump()
        assertTrue(net.frames!!.isEmpty())
        assertEquals(carried, a.carrySize()); assertEquals(shown, a.messages.size)
        assertTrue(a.message("old1")!!.reactions.isEmpty())
        assertFalse(a.isMine(att.fid)); assertTrue(a.chunks.ids().isEmpty())
        // once Z has been in range, its key is known — and kept through a restart
        net.node("Z"); net.connect("A", "Z")
        assertTrue(a.canWriteTo(z))
        val a2 = FakeNet().node("A"); a2.router.restore(JSONObject(a.snapshot().toString()))
        assertEquals(FakeNet.keysOf("Z").pubB64, a2.router.keyOf(z))
        assertNotNull(a2.router.sendDm(z, "now it works"))
    }

    @Test fun `someone I have a private chat with is never forgotten, however long ago they were here`() {
        val net = FakeNet(); net.line("A", "Z")
        val a = net.nodes["A"]!!.router
        a.sendDm(net.id("Z"), "see you next year")!!; net.pump()
        net.disconnect("A", "Z")
        val others = (0 until 60).map { Crypto.randomId(16) }
        for (id in others) a.people[id] = Person(id).also { p -> p.lastSeen = net.now }
        net.now += Router.FORGET_PEOPLE_MS + 86_400_000L
        a.tick()
        assertTrue(others.none { it in a.people })
        assertTrue(a.canWriteTo(net.id("Z")))
    }

    // ---------------------------------------------------------------- what is read, and what isn't

    @Test fun `a genuine envelope whose inside is bad is passed on like any other, but never shown`() {
        val net = FakeNet(); net.line("A", "B", "C")
        val b = net.nodes["B"]!!.router
        var deep: Any = JSONObject().put("x", 1)
        repeat(40) { deep = JSONArray().put(deep) }
        val crafted = listOf(
            FakeNet.envelope("A", Envelope.CHAT, JSONObject().put("text", "too deep").put("re", deep), net.now),
            // sealed under some other key: signed by A, opens for nobody
            FakeNet.envelope("A", Envelope.CHAT, JSONObject().put("text", "wrong key"), net.now).also { e ->
                e.json.put("c", Crypto.seal(Crypto.randomBytes(32), e.header(FakeNet.group().airTag), "{\"text\":\"wrong key\"}".toByteArray()))
                FakeNet.resign(e, "A")
            },
            // a file whose id isn't A's
            FakeNet.envelope("A", Envelope.FILE, JSONObject().put("text", "stolen file").put("att", FakeNet.makeFile(net.nodes["B"]!!.router, ByteArray(10)).first.json), net.now),
        )
        for (env in crafted) { b.onBytes("B>A", FakeNet.frame(env)); net.pump() }
        assertTrue(net.texts("B").isEmpty()); assertTrue(net.texts("C").isEmpty())
        assertTrue(net.nodes["B"]!!.rec.shown.isEmpty() && net.nodes["C"]!!.rec.shown.isEmpty())
        // …but every phone carries them alike, so none is offered again and again
        for (env in crafted) { assertTrue(b.carries(env.id)); assertTrue(net.nodes["C"]!!.router.carries(env.id)) }
        net.node("D"); net.connect("C", "D")
        for (env in crafted) assertTrue(net.nodes["D"]!!.router.carries(env.id))
    }

    @Test fun `a second message for the same file is not shown`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router
        val (att, pieces) = FakeNet.makeFile(a, ByteArray(4_000) { 3 })
        val first = a.sendFile(att, pieces, "the photo")!!; net.pump()
        val again = FakeNet.envelope("A", Envelope.FILE, JSONObject().put("text", "the same file again").put("att", att.json), net.now)
        b.onBytes("B>A", FakeNet.frame(again)); net.pump()
        assertEquals(first.id, b.fileMessage(att.fid)!!.id)
        assertEquals(listOf("the photo"), net.texts("B"))
    }

    @Test fun `a file whose pieces can't be kept is not sent at all`() {
        val net = FakeNet(); net.frames = ArrayList()
        val refusing = object : ChunkStore {
            override fun put(env: Envelope): Boolean = true          // "accepted", and dropped
            override fun has(id: String): Boolean = false
            override fun ids(): List<String> = emptyList()
            override fun get(id: String): Envelope? = null
            override fun expire(before: Long) {}
        }
        val frames = ArrayList<String>()
        val r = Router(FakeNet.identity("A"), FakeNet.group(), spy(frames), net.Recorder(), refusing) { net.now }
        r.onLinkUp("L", net.id("B"), "", "tok"); FakeNet.prove(r, "L", "B", "tok")
        frames.clear()
        val (att, pieces) = FakeNet.makeFile(r, ByteArray(30_000))
        assertNull(r.sendFile(att, pieces, "too big for this phone"))
        assertTrue(frames.isEmpty())
        assertTrue(r.messages.isEmpty()); assertEquals(0, r.carrySize())
        assertFalse(r.isMine(att.fid)); assertNull(r.fileMessage(att.fid))
        // and a file that isn't this phone's, or has no key, is refused before anything happens
        val (theirs, theirPieces) = FakeNet.makeFile(FakeNet().node("B").router, ByteArray(100))
        assertNull(r.sendFile(theirs, theirPieces, ""))
        assertNull(r.sendFile(Attachment.make(r.newFid(), "x", "image/jpeg", 100, 1, 0, 0, ""), listOf("AAAA"), ""))
    }

    @Test fun `my files are mine by what this phone knows, through a restart too`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!.router; val b = net.nodes["B"]!!.router
        val (att, pieces) = FakeNet.makeFile(a, ByteArray(20_000))
        a.sendFile(att, pieces, "")!!; net.pump()
        assertTrue(a.isMine(att.fid)); assertFalse(b.isMine(att.fid))
        val a2 = FakeNet().node("A"); a2.router.restore(JSONObject(a.snapshot().toString()))
        assertTrue(a2.router.isMine(att.fid))
        assertTrue(a2.router.newFid().startsWith(a.me.id))
        assertTrue(Attachment.ownedBy(a.newFid(), a.me.id)); assertFalse(Attachment.ownedBy(a.newFid(), b.me.id))
    }

    @Test fun `a message of mine my phone lost is put back, as sent, when a friend hands it back`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!
        val said = a.router.sendChat("said just before the crash")!!; net.pump()
        a.router.renameGroup("New camp"); net.pump()
        net.know("A", "C"); a.router.sendDm(net.id("C"), "private, also lost")!!; net.pump()
        // the app died before it saved: its state has none of it
        val state = JSONObject(a.router.snapshot().toString()).put("messages", JSONArray()).put("carry", JSONArray()).put("born", JSONObject())
        net.disconnect("A", "B")
        val a2 = net.node("A"); a2.router.restore(state)
        net.connect("A", "B")
        val back = a2.router.message(said.id)
        assertNotNull(back)
        assertEquals("said just before the crash", back!!.text); assertEquals(Message.SENT, back.status)
        assertEquals(said.ts, back.ts); assertEquals(a2.router.me.id, back.from)
        assertTrue("not news to me", a2.rec.shown.isEmpty())
        assertEquals("a rename and a private message stay as they are", 1, a2.router.messages.size)
    }

    @Test fun `a lost own role carrier is rebuilt as ops, not a message`() {
        val net = FakeNet(); net.line("A", "B")
        val a = net.nodes["A"]!!
        a.router.foundIfDue(sure = true, ts = net.now)
        assertEquals(RoleChange.DONE, a.router.grantAdmin(net.id("B"))); net.pump()
        // the app died before it saved any of it: no line, no carrier, no roles
        val state = JSONObject(a.router.snapshot().toString()).put("messages", JSONArray()).put("carry", JSONArray()).put("born", JSONObject())
        state.remove("roles")
        net.disconnect("A", "B")
        val a2 = net.node("A"); a2.router.restore(state)
        assertNull(a2.router.founder())
        net.connect("A", "B")
        assertEquals(a2.id, a2.router.founder())
        assertTrue(a2.router.isAdmin(net.id("B")))
        assertTrue("its carrier is carried again", a2.router.snapshot().getJSONArray("carry").length() == 1)
        assertTrue("never a bubble", a2.router.messages.none { it.kind == Envelope.CHAT })
        assertTrue("not news to me", a2.rec.shown.isEmpty())
    }

    @Test fun `a photo of mine my phone lost is put together again once a friend hands its pieces back`() {
        val net = FakeNet(); net.line("A", "B")
        val bytes = ByteArray(3 * Router.CHUNK_RAW) { (it % 241).toByte() }
        val (att, pieces) = FakeNet.makeFile(net.nodes["A"]!!.router, bytes)
        val sent = net.nodes["A"]!!.router.sendFile(att, pieces, "from the summit")!!; net.pump()
        assertTrue(net.nodes["B"]!!.router.fileComplete(att))
        // the group was deleted on A's phone and joined again: no state, no pieces, no file
        net.disconnect("A", "B")
        val a2 = net.node("A")
        net.connect("A", "B")
        val back = a2.router.message(sent.id)!!
        assertEquals("from the summit", back.text); assertEquals(Message.SENT, back.status)
        assertTrue(a2.router.fileComplete(att))
        assertEquals("put together once, when its last piece is back", listOf(sent.id), a2.rec.files.map { it.id })
        assertArrayEquals(bytes, FakeNet.reassemble(a2.router, att))
        assertTrue("not news to me", a2.rec.shown.isEmpty())
    }

    // ---------------------------------------------------------------- after an upgrade

    @Test fun `people kept under their ids from before the update don't make the group look bigger`() {
        val net = FakeNet(); val a = net.node("A")
        // thirty people seen a minute before the update, under their old ids: names in old chats now, nothing more
        val old = JSONArray()
        repeat(Router.FILE_GROUP_LIMIT) {
            old.put(Person(Crypto.randomId(8)).also { p -> p.name = "P$it"; p.lastSeen = net.now - 60_000; p.hasInternet = true }.toJson())
        }
        a.router.restore(JSONObject().put("fmt", Router.FMT).put("me", a.id).put("people", old))
        assertEquals(Router.FILE_GROUP_LIMIT, a.router.people.size)
        net.node("B"); net.connect("A", "B")
        assertEquals(1, a.router.activePeople()); assertEquals(listOf(net.id("B")), a.router.activePeopleList().map { it.id })
        assertEquals(1, a.router.peopleInRange())
        assertTrue(a.router.canSendFiles())
        assertTrue(a.router.legacyHelpers().isEmpty()); assertTrue(a.router.helpers().isEmpty())
        // and B's words get A's receipt, as in any small group
        val said = net.nodes["B"]!!.router.sendChat("hello")!!; net.pump()
        assertEquals(setOf(net.id("A")), said.reached)
    }

    @Test fun `a state from before 2_4 keeps its chat but none of its carry`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["B"]!!.router.sendChat("hello"); net.pump()
        val saved = JSONObject(net.nodes["A"]!!.router.snapshot().toString())
        assertEquals(Router.FMT, saved.getInt("fmt")); assertEquals(net.id("A"), saved.getString("me"))
        assertTrue(saved.getJSONArray("carry").length() > 0)
        saved.remove("fmt")
        val a2 = FakeNet().node("A"); a2.router.restore(saved)
        assertEquals(listOf("hello"), a2.router.messages.map { it.text })
        assertEquals(0, a2.router.carrySize())
    }

    @Test fun `after an upgrade my unsent group messages go out again, in their place, and my requests are asked again`() {
        val net = FakeNet(); net.node("A"); net.node("B")
        val me = net.id("A")
        val t0 = net.now - 3_600_000L
        val chat = Message("oldchat00001", Envelope.CHAT, me, "A", null, "sent before the update", t0, loc = Loc.of(1.0, 2.0),
            quote = Quote("q1", "Bea", "where?"), mentions = listOf(net.id("B"))).also { it.status = Message.QUEUED }
        val older = Message("oldchat00002", Envelope.CHAT, me, "A", null, "three days ago", net.now - 3 * 86_400_000L).also { it.status = Message.QUEUED }
        val dm = Message("olddm0000001", Envelope.DM, me, "A", "abcdefgh", "to an old id", t0).also { it.status = Message.QUEUED }
        val att = Attachment.make("oldfile0001x", "p.jpg", "image/jpeg", 100, 1, 0, 0, "")
        val file = Message("oldfile00001", Envelope.FILE, me, "A", null, "a photo", t0 + 1, att).also { it.status = Message.QUEUED }
        val after = Message("theirs000001", Envelope.CHAT, "someoneelse1", "Bea", null, "later", t0 + 2)
        val request = Errand("errandmine01", Errand.READ, JSONObject().put("url", "https://example.org"), me, "A", t0).also {
            it.rv = Errand.EV; it.exp = net.now + 3_600_000L; it.lastDispatchAt = t0; it.status = Errand.WAITING
        }
        val state = JSONObject().put("fmt", Router.FMT).put("me", me)
            .put("messages", JSONArray(listOf(chat, older, dm, file, after).map { it.toJson() }))
            .put("errands", JSONArray().put(request.toJson()))
            .put("reissue", JSONArray(listOf(chat.id, older.id, dm.id, file.id)))
        val a = net.nodes["A"]!!.router
        a.restore(state)
        // the request goes on under an id of mine: every phone takes a request only under its asker's id
        val asked = a.errands.values.single()
        assertTrue(asked.id.startsWith("$me.")); assertEquals("https://example.org", asked.args.getString("url"))
        val files = a.reissueQueued()
        assertEquals(listOf(file.id), files.map { it.id })
        assertTrue("once only", a.reissueQueued().isEmpty())
        assertFalse(a.snapshot().has("reissue"))
        // the message is the same — words, time, place, quote, mentions, place in the chat — under a new id
        assertNull(a.message(chat.id))
        val fresh = a.messages.single { it.text == "sent before the update" }
        assertTrue(fresh.id.startsWith("$me."))
        assertEquals(t0, fresh.ts); assertEquals(Message.QUEUED, fresh.status)
        assertEquals(chat.loc!!.latE6, fresh.loc!!.latE6); assertEquals("q1", fresh.quote!!.id); assertEquals(chat.mentions, fresh.mentions)
        assertEquals(listOf("three days ago", "sent before the update", "to an old id", "a photo", "later"), a.messages.map { it.text })
        assertTrue(a.carries(fresh.id))
        // too old, private, or not a message at all: left as they were
        assertTrue(a.message(older.id)!!.status == Message.QUEUED && !a.carries(older.id))
        assertFalse(a.carries(dm.id))
        // and on the air: the friend gets the message and the request
        net.connect("A", "B")
        assertTrue(net.texts("B").contains("sent before the update"))
        assertEquals(Message.SENT, fresh.status)
        assertNotNull(net.nodes["B"]!!.router.errands[asked.id])
        assertTrue(net.texts("B").none { it == "three days ago" || it == "to an old id" })
    }

    // ---------------------------------------------------------------- frames

    @Test fun `frame guards - oversized, deeply nested and pre-handshake frames`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!
        // an authed link: a frame over 64 KB is ignored, the link stays
        b.router.onBytes("B>A", ("{\"t\":\"fill\",\"envs\":[\"" + "x".repeat(70_000) + "\"]}").toByteArray())
        assertTrue(b.router.links["B>A"]!!.authed)
        assertTrue(b.rec.log.any { it.contains("oversized frame ignored") })
        // a frame nested deeper than any honest one is ignored unread, without running out of stack
        val deep = "{\"t\":\"fill\",\"envs\":" + "[".repeat(20_000) + "]".repeat(20_000) + "}"
        b.router.onBytes("B>A", deep.toByteArray())
        assertTrue(b.rec.log.any { it.contains("deeply nested frame ignored") })
        assertTrue(b.router.links["B>A"]!!.authed)
        // nothing from a bad frame is ever written to the log
        b.router.onBytes("B>A", "{\"t\":\"fill\",\"envs\":[{\"id\": SECRETINPUT".toByteArray())
        assertTrue(b.rec.log.none { it.contains("SECRETINPUT") })
        // before the handshake, anything bigger than a hello or a proof drops the link
        val frames = ArrayList<String>()
        val r = Router(FakeNet.identity("B"), FakeNet.group(), spy(frames), b.rec) { net.now }
        r.onLinkUp("L", net.id("X"), "", "tok")
        r.onBytes("L", JSONObject().put("t", "hello").put("id", net.id("X")).put("nonce", "n".repeat(16)).put("pad", "p".repeat(3_000)).put("v", 5).toString().toByteArray())
        assertNull(r.links["L"])
        assertTrue(frames.contains("disconnect L"))
        // and everything still works
        net.nodes["A"]!!.router.sendChat("still alive"); net.pump()
        assertEquals(listOf("still alive"), net.texts("B"))
    }
}
