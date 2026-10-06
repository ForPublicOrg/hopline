package app.hopline.mesh

import app.hopline.core.Crypto
import app.hopline.core.Names
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every envelope this app builds, and every frame it sends, fits the radio — however the text is made. */
class PayloadSizeTest {
    @Test fun `sync frames stay under the radio payload cap even with emoji`() {
        val net = FakeNet(); net.line("A", "B")
        val big = "🙂".repeat(1900)               // 1900 chars but 7600 bytes of UTF-8
        repeat(30) { net.nodes["A"]!!.router.sendChat(big) }
        net.pump()
        net.node("C"); net.connect("B", "C")       // B fills C's 30-message gap in chunks
        assertEquals(30, net.texts("C").size)
        assertTrue("largest frame was ${net.maxFrameBytes} bytes", net.maxFrameBytes < 32_000)
        assertTrue(net.maxFrameBytes > 8_000)      // and the chunks are not silly-small either
    }

    @Test fun `over-long text is trimmed so one envelope can never exceed the cap`() {
        val net = FakeNet(); net.line("A", "B")
        net.nodes["A"]!!.router.sendChat("x".repeat(50_000)); net.pump()
        assertEquals(Router.MAX_TEXT, net.texts("B")[0].length)
    }

    /** The envelope of [id] as [r] carries it, as sent. */
    private fun sizeOf(r: Router, id: String): Int {
        val carry = r.snapshot().getJSONArray("carry")
        for (i in 0 until carry.length()) if (carry.getJSONObject(i).getString("id") == id) return carry.getJSONObject(i).toString().toByteArray().size
        throw AssertionError("$id is not carried")
    }

    @Test fun `the biggest message, private message and photo this app can build stay within an envelope's size`() {
        val net = FakeNet(); net.frames = ArrayList(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        // the worst text there is: every character one that JSON writes as six
        val worst = "\u0001".repeat(Router.MAX_TEXT)
        val quote = Quote(FakeNet.newId("B"), "B".repeat(40), "\u0001".repeat(Quote.MAX_SNIPPET), net.id("B"))
        val mentions = (1..Message.MAX_MENTIONS).map { "m".repeat(40) }
        val chat = a.sendChat(worst, quote, mentions)!!
        val dm = a.sendDm(net.id("B"), worst, quote)!!
        val place = a.sendLocation(Loc.of(1.0, 2.0, 5, "\u0001".repeat(Loc.MAX_LABEL))!!, to = net.id("B"))!!
        val (att, pieces) = FakeNet.makeFile(a, ByteArray(3 * Router.CHUNK_RAW) { it.toByte() }, name = "n".repeat(200), thumb = "t".repeat(Router.MAX_THUMB_B64))
        val photo = a.sendFile(att, pieces, "\u0001".repeat(Router.MAX_CAPTION), quote = quote, mentions = mentions)!!
        net.pump()
        for (m in listOf(chat, dm, place, photo)) {
            val size = sizeOf(a, m.id)
            assertTrue("${m.kind} is $size bytes", size <= Router.MAX_ENVELOPE_OUT)
        }
        assertTrue(sizeOf(a, chat.id) > 15_000)                      // the worst case really was built
        for (i in 0 until att.chunks) assertTrue(a.chunks.get(Envelope.chunkId(att.fid, i))!!.bytes().size <= Router.MAX_ENVELOPE_OUT)
        assertEquals(Router.MAX_TEXT, net.texts("B").first().length)
        assertTrue("largest frame was ${net.maxFrameBytes} bytes", net.maxFrameBytes <= 32_768)
        // a preview too big for the radio goes, and the photo with it still does
        val (big, bigPieces) = FakeNet.makeFile(a, ByteArray(1_000), thumb = "t".repeat(Router.MAX_ENVELOPE_OUT))
        val sent = a.sendFile(big, bigPieces, "")!!
        assertEquals("", sent.att!!.thumb)
        assertTrue(sizeOf(a, sent.id) <= Router.MAX_ENVELOPE_OUT)
    }

    @Test fun `a private answer is cut to fit by its real sealed size, smaller still in a crowd`() {
        for (crowd in listOf(false, true)) {
            val net = FakeNet(); net.line("A", "B")
            val b = net.nodes["B"]!!.router
            b.setCaps(Errand.CAP_READ); net.pump()
            val e = net.nodes["A"]!!.router.requestErrand(Errand.READ, JSONObject().put("url", "https://example.org")); net.pump()
            net.advance(3_000)
            if (crowd) repeat(Router.FILE_GROUP_LIMIT) { Crypto.randomId(16).let { id -> b.people[id] = Person(id).also { p -> p.lastSeen = net.now } } }
            val rnd = java.util.Random(11)
            val text = (1..600).joinToString("\n\n") { (1..60).joinToString(" ") { Crypto.randomId(4 + rnd.nextInt(6)) } }
            assertTrue(b.completeErrand(e.id, true, "A huge page", JSONObject().put("t", text))); net.pump()
            val carry = b.snapshot().getJSONArray("carry")
            val answer = (0 until carry.length()).map { carry.getJSONObject(it) }.single { it.getString("k") == Envelope.DM && it.has("er") }
            val size = answer.toString().toByteArray().size
            val limit = if (crowd) Router.MAX_ANSWER_BYTES_CROWD else Router.MAX_ENVELOPE_OUT
            assertTrue("crowd=$crowd: $size bytes", size <= limit)
            assertTrue("crowd=$crowd: not cut much more than needed ($size)", size > limit * 2 / 3)
            assertEquals(Errand.DONE, e.status)
            assertTrue(e.answer()!!.getString("t").endsWith("…"))
        }
    }

    @Test fun `the inventory is sliced by size, never past a radio payload or the parts a peer reads`() {
        val net = FakeNet()
        val many = (0 until 150_000).map { "f.${FakeNet.idOf("A")}${Crypto.randomId(8)}.$it".take(48) }
        val pieces = object : ChunkStore {
            override fun put(env: Envelope): Boolean = true
            override fun has(id: String): Boolean = false
            override fun ids(): List<String> = emptyList()
            override fun advertised(): List<String> = many
            override fun get(id: String): Envelope? = null
            override fun expire(before: Long) {}
        }
        val frames = ArrayList<JSONObject>()
        val r = Router(FakeNet.identity("A"), FakeNet.group(), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long {
                assertTrue("a frame of ${bytes.size} bytes", bytes.size <= 32_768)
                frames.add(JSONObject(String(bytes, Charsets.UTF_8))); return frames.size.toLong()
            }
            override fun disconnect(linkId: String) {}
        }, net.Recorder(), pieces) { net.now }
        repeat(50) { r.sendChat("m$it") }
        r.onLinkUp("L", net.id("B"), "", "tok"); FakeNet.prove(r, "L", "B", "tok")
        val parts = frames.filter { it.optString("t") == "inv" }
        assertEquals(Router.MAX_INV_PARTS, parts.size)
        for ((i, part) in parts.withIndex()) {
            assertEquals(Router.MAX_INV_PARTS, part.getInt("n")); assertEquals(i, part.getInt("i"))
            assertTrue(part.getJSONArray("ids").toString().toByteArray().size <= Router.INV_PART_BYTES)
        }
        // my own carry goes first, so it is always listed
        val listed = parts.flatMap { p -> (0 until p.getJSONArray("ids").length()).map { p.getJSONArray("ids").getString(it) } }.toSet()
        assertTrue(r.messages.all { it.id in listed })
        // a small one is one part
        frames.clear()
        val small = Router(FakeNet.identity("A"), FakeNet.group(), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long { frames.add(JSONObject(String(bytes, Charsets.UTF_8))); return 1 }
            override fun disconnect(linkId: String) {}
        }, net.Recorder()) { net.now }
        small.onLinkUp("L", net.id("B"), "", "tok"); FakeNet.prove(small, "L", "B", "tok")
        assertEquals(listOf(1), frames.filter { it.optString("t") == "inv" }.map { it.getInt("n") })
    }

    @Test fun `a full phone's inventory - carried, let go of, and pieces - is read whole by a friend`() {
        val net = FakeNet()
        val node = FakeNet.idOf("A")
        // the longest ids a phone carries (a receipt for a message id of 40) and lists after letting them go
        fun longest(k: Int) = "r.${(k.toString() + "x".repeat(40)).take(40)}.$node"
        val carry = org.json.JSONArray(); val born = JSONObject(); val tombs = JSONObject()
        for (k in 0 until Router.MAX_CARRY) {
            val id = longest(k)
            carry.put(JSONObject().put("id", id).put("k", Envelope.RECEIPT).put("o", node).put("ts", net.now).put("h", 0).put("pk", "k").put("s", "s").put("c", "c"))
            born.put(id, net.now)
        }
        for (k in Router.MAX_CARRY until Router.MAX_CARRY + Router.MAX_TOMBS) tombs.put(longest(k), net.now + 3_600_000L)
        // a phone's piece store at its fullest: ~16,500 pieces held and 20,000 let go of (see Blobs.DiskChunkStore)
        val pieces = (0 until 36_500).map { "f.$node${Crypto.randomId(8)}.${it % Router.MAX_CHUNKS}" }
        val store = object : ChunkStore {
            override fun put(env: Envelope): Boolean = true
            override fun has(id: String): Boolean = false
            override fun ids(): List<String> = emptyList()
            override fun advertised(): List<String> = pieces
            override fun get(id: String): Envelope? = null
            override fun expire(before: Long) {}
        }
        val frames = ArrayList<JSONObject>()
        val r = Router(FakeNet.identity("A"), FakeNet.group(), object : Transport {
            override fun send(linkId: String, bytes: ByteArray): Long {
                assertTrue("a frame of ${bytes.size} bytes", bytes.size <= 32_768)
                frames.add(JSONObject(String(bytes, Charsets.UTF_8))); return frames.size.toLong()
            }
            override fun disconnect(linkId: String) {}
        }, net.Recorder(), store) { net.now }
        r.restore(JSONObject().put("fmt", Router.FMT).put("me", node).put("carry", carry).put("born", born).put("tombs", tombs))
        assertEquals(Router.MAX_CARRY, r.carrySize())
        r.onLinkUp("L", net.id("B"), "", "tok"); FakeNet.prove(r, "L", "B", "tok")
        val parts = frames.filter { it.optString("t") == "inv" }
        assertTrue("${parts.size} parts", parts.size <= Router.MAX_INV_PARTS)
        for (part in parts) assertTrue(part.getJSONArray("ids").toString().toByteArray().size <= Router.INV_PART_BYTES)
        val listed = parts.flatMap { p -> (0 until p.getJSONArray("ids").length()).map { p.getJSONArray("ids").getString(it) } }
        assertTrue("${listed.size} ids", listed.size <= Router.MAX_INV_IDS)
        // nothing left out: every id is there for the friend to see
        assertEquals((0 until Router.MAX_CARRY + Router.MAX_TOMBS).map { longest(it) }.toSet() + pieces, listed.toSet())
    }

    @Test fun `an envelope too big for any honest phone is refused, and not remembered`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!
        val id = FakeNet.newId("A")
        val huge = FakeNet.envelope("A", Envelope.CHAT, JSONObject().put("text", "x".repeat(22_000)), net.now, id = id)
        assertTrue(huge.bytes().size > Router.MAX_ENVELOPE_IN)
        b.router.onBytes("B>A", FakeNet.fill(huge)); net.pump()
        assertFalse(b.router.carries(id))
        assertTrue(net.texts("B").isEmpty())
        val fine = FakeNet.envelope("A", Envelope.CHAT, JSONObject().put("text", "the same id, honest size"), net.now, id = id)
        b.router.onBytes("B>A", FakeNet.fill(fine)); net.pump()
        assertEquals(listOf("the same id, honest size"), net.texts("B"))
    }

    @Test fun `the carry keeps to its byte budget, and the phone sending the most is the one cut back`() {
        val net = FakeNet(); net.line("A", "B")
        val b = net.nodes["B"]!!.router
        val words = (1..5).map { net.nodes["A"]!!.router.sendChat("honest $it")!! }
        net.pump()
        // M floods envelopes of almost the biggest size a phone takes, far past the budget
        val big = "y".repeat(19_500)
        var sent = 0L
        val flood = ArrayList<String>()
        while (sent < Router.MAX_CARRY_BYTES + 2_000_000L) {
            val env = FakeNet.envelope("M", Envelope.CHAT, JSONObject().put("text", big), net.now)
            sent += env.bytes().size; flood.add(env.id)
            b.onBytes("B>A", FakeNet.fill(env))
        }
        net.pump()
        assertTrue(b.carriedBytes() > Router.MAX_CARRY_BYTES)
        b.tick(); net.pump()
        assertTrue(b.carriedBytes() <= Router.MAX_CARRY_BYTES)
        for (m in words) assertTrue("honest words stay", b.carries(m.id))
        assertFalse("the flood's oldest went first", b.carries(flood.first()))
        assertTrue(b.carries(flood.last()))
        assertNotNull(b.message(words.last().id))
    }

    @Test fun `the biggest carrier and the biggest roles frame fit the radio`() {
        val net = FakeNet(); net.frames = ArrayList(); net.line("A", "B")
        val a = net.nodes["A"]!!.router
        a.foundIfDue(sure = true, ts = net.now)
        // someone whose name is as long as a name can be, every character four bytes
        val t = net.id("T")
        net.know("A", "T"); a.people[t]!!.name = "𝔸".repeat(Names.MAX_PERSON)
        // a set just short of what a carrier takes whole: X made an admin and dismissed, over and over
        val x = net.id("X")
        fun setBytes() = a.roles.wire().sumOf { it.toJson().toString().length + 1 }
        while (setBytes() < Router.CARRIER_SET_BYTES - 600) { net.now += 1_000; a.roles.grant(x, net.now) ?: a.roles.revoke(x, net.now)!! }
        assertEquals(RoleChange.DONE, a.grantAdmin(t)); net.pump()
        assertTrue("${setBytes()} bytes of ops", setBytes() in Router.CARRIER_SET_BYTES - 600..Router.CARRIER_SET_BYTES)
        val tag = FakeNet.group().airTag
        val carried = a.snapshot().getJSONArray("carry").let { c -> (0 until c.length()).map { c.getJSONObject(it) } }.single { it.getString("k") == Envelope.CHAT }
        val p = JSONObject(String(Crypto.open(FakeNet.group().keys.env, Envelope(carried).header(tag), Envelope(carried).sealed)!!, Charsets.UTF_8))
        assertEquals("the whole set rode it", a.roles.wire().size, p.getJSONArray("ro").length())
        assertTrue(p.getString("text").contains(a.people[t]!!.name))
        val size = carried.toString().toByteArray().size
        assertTrue("the carrier is $size bytes", size <= Router.MAX_ENVELOPE_OUT)

        // the biggest roles frame: the founder's ops as big as an op may be (a later version's type, every field full), sent whole
        val full = (1..6).associate { "${'a' + it}${'a' + it}" to "z".repeat(100) as Any }
        repeat(60) { a.roles.accept(listOf(RoleOp.make(FakeNet.keysOf("A"), tag, a.roles.nextN()!!, net.now, "later", full))) }
        val biggest = a.roles.wire().maxOf { it.toJson().toString().length }
        assertTrue("an op of $biggest bytes", biggest > 900)
        net.frames!!.clear()
        net.node("C"); net.connect("A", "C")
        val frames = net.frames!!.filter { JSONObject(it).optString("t") == "roles" }.map { it.toByteArray().size }
        assertTrue("${frames.size} frames", frames.size >= 3)
        for (n in frames) assertTrue("a roles frame of $n bytes", n <= Router.ROLES_FRAME_BYTES + 1_200 + 60 && n <= 32_768)
        assertEquals(a.roles.digest, net.nodes["C"]!!.router.roles.digest)
    }
}
