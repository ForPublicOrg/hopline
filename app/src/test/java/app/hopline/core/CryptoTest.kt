package app.hopline.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.util.Random
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The primitives everything else trusts: published vectors (and values worked out independently, with
 * Python's hashlib), round trips, and every way of breaking them that must fail.
 */
class CryptoTest {
    private fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun utf8(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)

    /** [sealed] with one bit flipped in byte [at] (counted from the end when negative). */
    private fun flip(sealed: String, at: Int): String {
        val b = Crypto.unb64(sealed)!!
        val i = if (at < 0) b.size + at else at
        b[i] = (b[i].toInt() xor 1).toByte()
        return Crypto.b64(b)
    }

    private fun fixed32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return if (b.size >= 32) b.copyOfRange(b.size - 32, b.size) else ByteArray(32 - b.size) + b
    }

    // ---------------------------------------------------------------- the group code

    @Test fun `PBKDF2-HMAC-SHA256 matches the published vectors, the platform's and the one by hand`() {
        class Vector(val pw: String, val salt: String, val rounds: Int, val len: Int, val want: String)
        val vectors = listOf(
            Vector("password", "salt", 1, 32, "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b"),
            Vector("password", "salt", 2, 32, "ae4d0c95af6b46d32d0adff928f06dd02a303f8ef3c251dfd6e2d85a95474c43"),
            Vector("password", "salt", 4096, 32, "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a"),
            Vector("passwordPASSWORDpassword", "saltSALTsaltSALTsaltSALTsaltSALTsalt", 4096, 40,
                "348c89dbcbd32b2f32d814b8116e84cf2b17347ebc1800181c4e2a1fb8dd53e1c635518c7dac47e9"),
        )
        for (v in vectors) {
            assertEquals("${v.pw}/${v.rounds}", v.want, Crypto.hex(Crypto.pbkdf2(v.pw, utf8(v.salt), v.rounds, v.len)))
            assertEquals("by hand ${v.pw}/${v.rounds}", v.want, Crypto.hex(Crypto.pbkdf2ByHand(utf8(v.pw), utf8(v.salt), v.rounds, v.len)))
        }
    }

    @Test fun `the PBKDF2 fallback computes exactly what SecretKeyFactory does`() {
        val r = Random(7)
        for (n in 0 until 12) {
            val pw = List(3 + n % 2) { Words.LIST[r.nextInt(Words.LIST.size)] }.joinToString("-")
            val salt = ByteArray(r.nextInt(40) + 1).also { r.nextBytes(it) }
            val rounds = listOf(1, 2, 3, 1000)[n % 4]
            val len = listOf(32, 16, 33, 64)[n / 3 % 4]
            val platform = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(pw.toCharArray(), salt, rounds, len * 8)).encoded
            assertArrayEquals("$pw/$rounds/$len", platform, Crypto.pbkdf2ByHand(utf8(pw), salt, rounds, len))
        }
    }

    @Test fun `HKDF-SHA256 matches RFC 5869 test cases 1 and 3`() {
        val ikm = ByteArray(22) { 0x0b }
        // Test case 1.
        val prk1 = Crypto.hkdfExtract(unhex("000102030405060708090a0b0c"), ikm)
        assertEquals("077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5", Crypto.hex(prk1))
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Crypto.hex(Crypto.hkdfExpand(prk1, unhex("f0f1f2f3f4f5f6f7f8f9"), 42)))
        // Test case 3: no salt, no info. An empty salt is HashLen zeros, which is what hkdf always uses.
        val prk3 = Crypto.hkdfExtract(ByteArray(0), ikm)
        assertEquals("19ef24a32c717b167f33a91d6f648bdf96596776afdb6377ac434c1c293ccb04", Crypto.hex(prk3))
        val okm3 = "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"
        assertEquals(okm3, Crypto.hex(Crypto.hkdfExpand(prk3, ByteArray(0), 42)))
        assertEquals(okm3, Crypto.hex(Crypto.hkdf(ikm, ByteArray(0), 42)))
        assertEquals(okm3, Crypto.hex(Crypto.hkdf(ikm, "", 42)))
        assertArrayEquals(Crypto.hkdf(ikm, utf8("hopline/v5/env")), Crypto.hkdf(ikm, "hopline/v5/env"))
        assertEquals(32, Crypto.hkdf(ikm, "x").size)
    }

    @Test fun `the stretched key is PBKDF2 of the code, the same however the code is typed`() {
        val master = Crypto.stretch("tiger river lamp hat")
        // Worked out independently: hashlib.pbkdf2_hmac("sha256", b"tiger-river-lamp-hat", b"hopline-group-v5", 600000, 32).
        assertEquals("6dff93ba7bff87a3d7cd8cec94fda32f273f320fce509272154bd2a5612db1f8", Crypto.hex(master))
        assertArrayEquals(master, Crypto.stretch("Tiger, River LAMP hat"))
        assertArrayEquals(master, Crypto.stretch("tiger-river-lamp-hat"))
        assertFalse(master.contentEquals(Crypto.stretch("tiger river lamp moon")))
        val old = Crypto.stretch("tiger river lamp")
        assertEquals("three-word codes stretch too", 32, old.size)
        assertFalse(master.contentEquals(old))
        // What it hands out is a copy: scribbling on it changes nothing remembered.
        master.fill(0)
        assertEquals("6dff93ba7bff87a3d7cd8cec94fda32f273f320fce509272154bd2a5612db1f8", Crypto.hex(Crypto.stretch("tiger river lamp hat")))
    }

    @Test fun `a group's keys are all different and only the tag is short and public`() {
        val keys = GroupKeys(unhex("6dff93ba7bff87a3d7cd8cec94fda32f273f320fce509272154bd2a5612db1f8"))
        assertEquals("worked out independently", "f36d140c277280a5", keys.tag)
        assertTrue(keys.tag.matches(Regex("[0-9a-f]{16}")))
        assertEquals(32, keys.env.size); assertEquals(32, keys.link.size)
        val all = listOf(keys.master, keys.env, keys.link, unhex(keys.tag)).map { Crypto.hex(it) }
        for (a in all) for (b in all) if (a !== b) assertFalse("$a starts $b", b.startsWith(a))
        val other = GroupKeys(Crypto.randomBytes(32))
        assertNotEquals(keys.tag, other.tag)
        assertFalse(keys.env.contentEquals(other.env))
    }

    @Test fun `the legacy fingerprint is the value groups were saved under before 2_4`() {
        // Worked out independently: hmac(sha256("hopline-group:" + code), "fingerprint")[:8].
        assertEquals("ded795b8", Crypto.legacyFingerprint("tiger river lamp"))
        assertEquals("ded795b8", Crypto.legacyFingerprint("Tiger, RIVER lamp"))
        assertEquals("21ae90d7", Crypto.legacyFingerprint("wizard pirate robot"))
    }

    // ---------------------------------------------------------------- sealing

    @Test fun `a group-sealed payload opens with the same key and aad, and nothing else`() {
        val key = Crypto.randomBytes(32)
        val aad = utf8("header")
        val plain = utf8("""{"text":"meet at the bridge"}""")
        val sealed = Crypto.seal(key, aad, plain)
        assertArrayEquals(plain, Crypto.open(key, aad, sealed))
        assertEquals("nonce + ciphertext + tag", 12 + plain.size + 16, Crypto.unb64(sealed)!!.size)
        val again = Crypto.seal(key, aad, plain)
        assertNotEquals("a fresh nonce every time", sealed.substring(0, 16), again.substring(0, 16))
        assertArrayEquals(plain, Crypto.open(key, aad, again))
        assertArrayEquals(ByteArray(0), Crypto.open(key, aad, Crypto.seal(key, aad, ByteArray(0))))

        assertNull("nonce", Crypto.open(key, aad, flip(sealed, 0)))
        assertNull("ciphertext", Crypto.open(key, aad, flip(sealed, 12)))
        assertNull("tag", Crypto.open(key, aad, flip(sealed, -1)))
        assertNull("aad", Crypto.open(key, utf8("headex"), sealed))
        assertNull("no aad", Crypto.open(key, ByteArray(0), sealed))
        assertNull("key", Crypto.open(Crypto.randomBytes(32), aad, sealed))
        assertNull("short key", Crypto.open(key.copyOf(16), aad, sealed))
        assertNull("cut short", Crypto.open(key, aad, Crypto.b64(Crypto.unb64(sealed)!!.copyOf(27))))
        assertNull("padded", Crypto.open(key, aad, "$sealed="))
        assertNull("not base64", Crypto.open(key, aad, "!!!!"))
        assertNull(Crypto.open(key, aad, ""))
        assertThrows(IllegalArgumentException::class.java) { Crypto.seal(key.copyOf(16), aad, plain) }
    }

    @Test fun `base64url is the one strict form`() {
        val b = byteArrayOf(-1, 0, 62, 63, 1)
        assertArrayEquals(b, Crypto.unb64(Crypto.b64(b)))
        assertFalse(Crypto.b64(Crypto.randomBytes(300)).contains(Regex("[+/=]")))
        assertNull("padding", Crypto.unb64(Crypto.b64(byteArrayOf(0)) + "=="))
        assertNull("stray bits", Crypto.unb64("AB"))
        assertNull("standard alphabet", Crypto.unb64("ab+/"))
        assertNull("spaces", Crypto.unb64("AA AA"))
        assertArrayEquals(ByteArray(0), Crypto.unb64(""))
    }

    @Test fun `a private message opens only for its recipient, from its sender`() {
        val alice = IdentityKeys.generate(); val bob = IdentityKeys.generate(); val carol = IdentityKeys.generate()
        val aad = utf8("H")
        val plain = utf8("""{"text":"just for you"}""")
        val (c, e) = Crypto.sealTo(bob.pub, alice, aad, plain)
        assertArrayEquals(plain, Crypto.openFrom(bob, alice.pub, e, aad, c))
        assertEquals(87, e.length)
        assertNotNull(Crypto.decodeEphemeral(e))
        val (c2, e2) = Crypto.sealTo(bob.pub, alice, aad, plain)
        assertNotEquals("a one-off key for every message", e, e2)
        assertNotEquals(c, c2)

        assertNull("not the recipient", Crypto.openFrom(carol, alice.pub, e, aad, c))
        assertNull("not the sender", Crypto.openFrom(bob, carol.pub, e, aad, c))
        assertNull("the sender can't open its own", Crypto.openFrom(alice, bob.pub, e, aad, c))
        assertNull("aad", Crypto.openFrom(bob, alice.pub, e, utf8("X"), c))
        assertNull("ciphertext", Crypto.openFrom(bob, alice.pub, e, aad, flip(c, 20)))
        assertNull("another message's e", Crypto.openFrom(bob, alice.pub, e2, aad, c))
        assertNull("some other key as e", Crypto.openFrom(bob, alice.pub, carol.pubB64, aad, c))

        val raw = Crypto.unb64(e)!!
        val offCurve = raw.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        val bad = listOf(
            "off the curve" to Crypto.b64(offCurve),
            "too short" to Crypto.b64(raw.copyOf(64)),
            "too long" to Crypto.b64(raw + 0),
            "infinity" to Crypto.b64(byteArrayOf(0)),
            "zeros" to Crypto.b64(ByteArray(65)),
            "0x04 and zeros" to Crypto.b64(byteArrayOf(4) + ByteArray(64)),
            "compressed" to Crypto.b64(byteArrayOf(2) + raw.copyOfRange(1, 33)),
            "empty" to "",
        )
        for ((why, b) in bad) {
            assertNull(why, Crypto.decodeEphemeral(b))
            assertNull(why, Crypto.openFrom(bob, alice.pub, b, aad, c))
        }
    }

    // ---------------------------------------------------------------- identities

    @Test fun `public keys are accepted only when they are exactly a point on P-256`() {
        val k = IdentityKeys.generate()
        assertEquals(87, k.pubB64.length)
        assertEquals(65, k.pubRaw.size)
        val pub = Crypto.decodePub(k.pubB64)!!
        assertArrayEquals(k.pubRaw, Crypto.pubRawOf(pub))
        assertSame("remembered", pub, Crypto.decodePub(k.pubB64))
        assertNotSame("one-off keys are not", Crypto.decodeEphemeral(k.pubB64), Crypto.decodeEphemeral(k.pubB64))

        val raw = k.pubRaw
        assertNull("short", Crypto.decodePub(Crypto.b64(raw.copyOf(64))))
        assertNull("long", Crypto.decodePub(Crypto.b64(raw + 1)))
        assertNull("prefix 0x02", Crypto.decodePub(Crypto.b64(raw.copyOf().also { it[0] = 2 })))
        assertNull("prefix 0x00", Crypto.decodePub(Crypto.b64(raw.copyOf().also { it[0] = 0 })))
        assertNull("off the curve", Crypto.decodePub(Crypto.b64(raw.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() })))
        assertNull("padded", Crypto.decodePub(k.pubB64 + "="))
        assertNull("not base64", Crypto.decodePub("*".repeat(87)))

        // A point with a small x is on the curve; the same point written with x + p is too, modulo p,
        // and must still be refused: every key has exactly one form.
        val spec = (k.pub as ECPublicKey).params
        val p = (spec.curve.field as ECFieldFp).p
        var x = BigInteger.ZERO
        var y: BigInteger
        while (true) {
            val rhs = x.pow(3).add(spec.curve.a.multiply(x)).add(spec.curve.b).mod(p)
            y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
            if (y.multiply(y).mod(p) == rhs) break
            x = x.add(BigInteger.ONE)
        }
        assertNotNull("the point itself is fine", Crypto.pubOf(byteArrayOf(4) + fixed32(x) + fixed32(y)))
        assertNull("x >= p", Crypto.pubOf(byteArrayOf(4) + fixed32(x.add(p)) + fixed32(y)))
        assertNull("y >= p", Crypto.pubOf(byteArrayOf(4) + fixed32(x) + fixed32(p)))
        assertNull("x = p", Crypto.pubOf(byteArrayOf(4) + fixed32(p) + fixed32(y)))
    }

    @Test fun `an identity is stored and read back whole, and a broken one is refused`() {
        val k = IdentityKeys.generate()
        val back = IdentityKeys.decode(k.encode())!!
        assertEquals(k.pubB64, back.pubB64)
        assertEquals(k.nodeId, back.nodeId)
        assertArrayEquals(k.priv.encoded, back.priv.encoded)
        assertTrue("it still signs as itself", Crypto.verify(k.pub, utf8("x"), Crypto.sign(back.priv, utf8("x"))))

        val other = IdentityKeys.generate()
        assertNull("halves from two key pairs", IdentityKeys.decode(JSONObject(k.encode()).put("pub", other.pubB64).toString()))
        assertNull("broken private key", IdentityKeys.decode(JSONObject(k.encode()).put("priv", Crypto.b64(byteArrayOf(1, 2, 3))).toString()))
        assertNull("no private key", IdentityKeys.decode(JSONObject(k.encode()).apply { remove("priv") }.toString()))
        assertNull(IdentityKeys.decode("{}"))
        assertNull(IdentityKeys.decode("not json"))
        assertNull(IdentityKeys.decode(""))
    }

    @Test fun `a signature verifies only for its own key and its own bytes`() {
        val a = IdentityKeys.generate(); val b = IdentityKeys.generate()
        val data = Crypto.lp("hl5", "tag", "id")
        val sig = Crypto.sign(a.priv, data)
        assertTrue(Crypto.verify(a.pub, data, sig))
        assertFalse("another key", Crypto.verify(b.pub, data, sig))
        assertFalse("other bytes", Crypto.verify(a.pub, Crypto.lp("hl5", "tag", "iD"), sig))
        assertFalse("signed by someone else", Crypto.verify(a.pub, data, Crypto.sign(b.priv, data)))
        assertFalse("tampered", Crypto.verify(a.pub, data, flip(sig, -1)))
        assertFalse(Crypto.verify(a.pub, data, ""))
        assertFalse(Crypto.verify(a.pub, data, "garbage!"))
    }

    @Test fun `node ids come from the key and have one exact shape`() {
        assertEquals(32, Crypto.ALPHABET.toSet().size)
        assertFalse(Crypto.ALPHABET.any { it in "lo01" })
        // Worked out independently: base32 (this alphabet) of sha256("hopline/v5/id" + key)[:10].
        assertEquals("va89qsmqsyccasgq", Crypto.nodeIdOf(byteArrayOf(4) + ByteArray(64) { (it + 1).toByte() }))

        val k = IdentityKeys.generate()
        val id = Crypto.nodeIdOf(k.pubRaw)
        assertEquals(16, id.length)
        assertTrue(id.all { it in Crypto.ALPHABET })
        assertTrue(Crypto.isNodeId(id))
        assertEquals("stable", id, Crypto.nodeIdOf(Crypto.unb64(k.pubB64)!!))
        assertEquals(id, k.nodeId)
        assertNotEquals(id, IdentityKeys.generate().nodeId)

        assertFalse("a pre-2.4 id", Crypto.isNodeId(Crypto.randomId(8)))
        assertFalse(Crypto.isNodeId(id.dropLast(1)))
        assertFalse(Crypto.isNodeId(id + "a"))
        assertFalse(Crypto.isNodeId(id.uppercase()))
        for (c in "lo01.-_ A") assertFalse("'$c'", Crypto.isNodeId(id.dropLast(1) + c))
        assertFalse(Crypto.isNodeId(""))
        assertTrue(Crypto.isAlphabet(Crypto.randomId(), 10))
        assertFalse(Crypto.isAlphabet(Crypto.randomId(), 9))
    }

    // ---------------------------------------------------------------- files

    @Test fun `a file piece opens only as itself, in its own place, under its own key`() {
        val fk = Crypto.randomBytes(32)
        val data = Crypto.randomBytes(14_336)
        val s0 = Crypto.sealPiece(fk, 0, data)
        assertArrayEquals(data, Crypto.openPiece(fk, 0, s0))
        assertEquals("ciphertext + tag, no nonce", data.size + 16, Crypto.unb64(s0)!!.size)
        assertEquals("the same piece seals the same way", s0, Crypto.sealPiece(fk, 0, data))
        val s1 = Crypto.sealPiece(fk, 1, data)
        assertNotEquals("the nonce is the piece number", s0, s1)
        assertArrayEquals(data, Crypto.openPiece(fk, 1, s1))

        assertNull("wrong place", Crypto.openPiece(fk, 1, s0))
        assertNull("wrong place", Crypto.openPiece(fk, 0, s1))
        assertNull("wrong key", Crypto.openPiece(Crypto.randomBytes(32), 0, s0))
        assertNull("tampered", Crypto.openPiece(fk, 0, flip(s0, 100)))
        assertNull("tag", Crypto.openPiece(fk, 0, flip(s0, -1)))
        assertNull("not base64", Crypto.openPiece(fk, 0, "$s0="))

        assertArrayEquals(ByteArray(8) + byteArrayOf(0, 0, 1, 2), Crypto.pieceNonce(258))
        assertFalse(Crypto.pieceNonce(0).contentEquals(Crypto.pieceNonce(1)))
    }

    @Test fun `a file's checksum is lowercase hex SHA-256`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Crypto.sha256Hex(utf8("abc")))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Crypto.sha256Hex(ByteArray(0)))
    }

    // ---------------------------------------------------------------- signing bytes, trusting JSON

    @Test fun `length prefixes make every header unambiguous`() {
        assertArrayEquals(byteArrayOf(0, 0, 0, 2, 'a'.code.toByte(), 'b'.code.toByte(), 0, 0, 0, 0), Crypto.lp("ab", ""))
        assertFalse(Crypto.lp("a|b", "c").contentEquals(Crypto.lp("a", "b|c")))
        assertFalse(Crypto.lp("ab", "").contentEquals(Crypto.lp("a", "b")))
        assertArrayEquals("the length is in bytes", byteArrayOf(0, 0, 0, 2) + utf8("é"), Crypto.lp("é"))
        assertArrayEquals(Crypto.lp("x", "yz"), Crypto.lp(utf8("x"), utf8("yz")))
        assertArrayEquals(byteArrayOf(0, 1, 0, 0) + ByteArray(65536), Crypto.lp(ByteArray(65536)))
    }

    @Test fun `byte arrays compare in constant time`() {
        assertTrue(Crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(Crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
        assertFalse(Crypto.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertTrue(Crypto.constantTimeEquals(ByteArray(0), ByteArray(0)))
    }

    @Test fun `nesting is counted, brackets inside strings are not`() {
        assertTrue(Crypto.depthOk("""{"a":[1,{"b":2}]}""", 3))
        assertFalse(Crypto.depthOk("""{"a":[1,{"b":2}]}""", 2))
        assertTrue(Crypto.depthOk("12", 0))
        assertTrue(Crypto.depthOk("[".repeat(32) + "]".repeat(32), 32))
        assertFalse(Crypto.depthOk("[".repeat(33) + "]".repeat(33), 32))
        assertFalse("and quickly", Crypto.depthOk("[".repeat(1_000_000), Crypto.MAX_DEPTH))

        // A chat message full of brackets is one level deep.
        val chat = JSONObject().put("text", "[[[[{{{{ \"quoted [[\" and 'single [[' \\ ]]]]").toString()
        assertTrue(chat, Crypto.depthOk(chat, 1))
        // Either quote opens a string where a value can start, just as org.json reads it.
        val single = "{'t':'[[[[ \"[[[[\" '}"
        assertEquals("[[[[ \"[[[[\" ", JSONObject(single).getString("t"))
        assertTrue(Crypto.depthOk(single, 1))
        assertTrue(Crypto.depthOk("""["it's [[[[", 1]""", 1))
        assertTrue("an escaped quote doesn't end a string", Crypto.depthOk("""["a\"[[[[[[", 1]""", 1))
        assertTrue(Crypto.depthOk("""['it\'s [[[[', 1]""", 1))
        // ... and an escaped backslash does not hide the quote after it.
        assertFalse(Crypto.depthOk("""["\\", [[[[1]]]]]""", 4))
        assertTrue(Crypto.depthOk("""["\\", [[[[1]]]]]""", 5))
        // A quote inside a bare word starts no string (Android's org.json reads a"b as one word).
        assertFalse(Crypto.depthOk("""[a", [[[[[[1]]]]]], "]""", 6))
        assertTrue(Crypto.depthOk("""[a", [[[[[[1]]]]]], "]""", 7))
        // Comments are skipped, so a quote inside one can't hide real nesting.
        assertFalse(Crypto.depthOk("""[1, /* , " */ [[[[[1]]]]], "x"]""", 5))
        assertTrue(Crypto.depthOk("""[1, /* , " */ [[[[[1]]]]], "x"]""", 6))
        assertFalse(Crypto.depthOk("[1, # , \"\n [[[[[1]]]]], \"x\"]", 5))
        assertFalse(Crypto.depthOk("[1, // , \"\n [[[[[1]]]]], \"x\"]", 5))
        // Raw control characters: never written by org.json outside a string, so refused there.
        assertFalse(Crypto.depthOk("[\u000b\"x\", [[1]]]", 32))
        assertTrue(Crypto.depthOk(JSONObject().put("t", "a\u0001b").toString(), 1))
        assertTrue(Crypto.depthOk("{\"t\":\"a\u0001b\"}", 1))
    }

    @Test fun `nesting is counted exactly on anything org_json writes`() {
        val r = Random(42)
        val nasty = "[]{}\"'\\/#*,:;=> ab\n\t\u0001é"
        fun text(): String = String(CharArray(r.nextInt(12)) { nasty[r.nextInt(nasty.length)] })
        fun leaf(): Any = when (r.nextInt(4)) { 0 -> r.nextInt(); 1 -> true; 2 -> JSONObject.NULL; else -> text() }
        fun tree(depth: Int): Any {
            val deep = r.nextInt(3)
            val kids = List(3) { i -> (if (i == deep) depth - 1 else r.nextInt(depth)).let { d -> if (d == 0) leaf() else tree(d) } }
            return if (r.nextBoolean()) JSONObject().apply { kids.forEachIndexed { i, k -> put(text() + i, k) } } else JSONArray(kids)
        }
        for (n in 0 until 300) {
            val depth = 1 + n % 12
            val s = tree(depth).toString()
            assertTrue(s, Crypto.depthOk(s, depth))
            assertFalse(s, Crypto.depthOk(s, depth - 1))
        }
    }

    @Test fun `canonical refuses JSON nested too deep`() {
        fun nested(levels: Int): JSONArray = (1 until levels).fold(JSONArray()) { inner, _ -> JSONArray().put(inner) }
        assertEquals("[".repeat(32) + "]".repeat(32), Crypto.canonical(nested(32)))
        assertThrows(IllegalArgumentException::class.java) { Crypto.canonical(nested(33)) }
        assertThrows(IllegalArgumentException::class.java) { Crypto.canonical(JSONObject().put("a", nested(32))) }
        assertEquals("""{"a":[1,"x"],"b":null}""", Crypto.canonical(JSONObject().put("b", JSONObject.NULL).put("a", JSONArray().put(1).put("x"))))
    }

    // ---------------------------------------------------------------- checking who you're talking to

    @Test fun `the security code is the same on both phones and different for every pair`() {
        val one = byteArrayOf(4) + ByteArray(64) { 1 }
        val three = byteArrayOf(4) + ByteArray(64) { 3 }
        // Worked out independently, and one group needs its leading zero.
        assertEquals("57199 01060 03369 23778 58831 39408", Crypto.safetyNumber(three, one))
        assertEquals("57199 01060 03369 23778 58831 39408", Crypto.safetyNumber(one, three))

        val a = IdentityKeys.generate(); val b = IdentityKeys.generate(); val c = IdentityKeys.generate()
        val ab = Crypto.safetyNumber(a.pubRaw, b.pubRaw)
        assertTrue(ab, ab.matches(Regex("\\d{5}( \\d{5}){5}")))
        assertEquals(ab, Crypto.safetyNumber(b.pubRaw, a.pubRaw))
        assertEquals(ab, Crypto.safetyNumber(a.pubB64, b.pubB64))
        assertEquals(ab, Crypto.safetyNumber(b.pubB64, a.pubB64))
        assertNotEquals(ab, Crypto.safetyNumber(a.pubRaw, c.pubRaw))
        assertNotEquals(ab, Crypto.safetyNumber(c.pubRaw, b.pubRaw))
        assertNull(Crypto.safetyNumber(a.pubB64, "not a key!"))
    }
}
