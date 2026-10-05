package app.hopline.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * All of Hopline's cryptography: the group key stretched from the code, sealing (AES-256-GCM), and each
 * phone's P-256 identity (ECDSA signatures, ECDH for private chats). Pure JVM — no Android imports — so it
 * is unit-testable, and only what both Android 8 and a desktop JDK ship (so no X25519 or Ed25519).
 */
object Crypto {
    private val rng = SecureRandom()

    /** Letters for ids: no 0/o/1/l look-alikes. Exactly 32 of them, so each letter is five bits. */
    const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

    /** How deep JSON from another phone may nest. Deeper is refused before it is parsed or canonicalised. */
    const val MAX_DEPTH = 32

    fun randomId(len: Int = 10): String {
        val sb = StringBuilder(len)
        repeat(len) { sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    /** True when [s] is exactly [len] letters of [ALPHABET]: the random part of an id. */
    fun isAlphabet(s: String, len: Int): Boolean = s.length == len && s.all { it in ALPHABET }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun sha256(s: String): ByteArray = sha256(s.toByteArray(Charsets.UTF_8))

    fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)

    /** Lowercase hex SHA-256 of [b]: a file's checksum. */
    fun sha256Hex(b: ByteArray): String = hex(sha256(b))

    private const val HEX = "0123456789abcdef"

    fun hex(b: ByteArray): String {
        val out = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xff
            out[2 * i] = HEX[v ushr 4]; out[2 * i + 1] = HEX[v and 15]
        }
        return String(out)
    }

    /** base64url without padding: how bytes travel inside Hopline's JSON. */
    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /** [b64] undone, or null for anything b64 would not have written (padding, stray bits, other letters). */
    fun unb64(s: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(s).takeIf { b64(it) == s }
    } catch (e: IllegalArgumentException) {
        null
    }

    // ------------------------------------------------------------------ the group code

    private val GROUP_SALT = "hopline-group-v5".toByteArray(Charsets.UTF_8)
    private const val STRETCH_ROUNDS = 600_000
    private const val MAX_STRETCHED = 16
    private val stretched = ConcurrentHashMap<String, ByteArray>()

    /**
     * A group's master key: PBKDF2-HMAC-SHA256 of the code, 600 000 rounds. Slow on purpose (up to a few
     * seconds on a phone), because anyone in radio range can try codes against what it protects, and
     * every guess has to pay this too. Never call it on the main thread or on Core's writer. Remembered
     * per code for the life of the app.
     */
    fun stretch(code: String): ByteArray {
        val norm = Words.normalise(code)
        stretched[norm]?.let { return it.copyOf() }
        val key = pbkdf2(norm, GROUP_SALT, STRETCH_ROUNDS, 32)
        if (stretched.size >= MAX_STRETCHED) stretched.clear()
        stretched[norm] = key
        return key.copyOf()
    }

    /**
     * PBKDF2-HMAC-SHA256 of an ASCII [password] (so it can't matter how a platform turns chars into
     * bytes): the platform's own, or [pbkdf2ByHand] on a phone that doesn't have it.
     */
    internal fun pbkdf2(password: String, salt: ByteArray, rounds: Int, len: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, len * 8)
        try {
            val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            if (key != null && key.size == len) return key
        } catch (e: GeneralSecurityException) {
            // Not on this phone: the same thing by hand, below.
        } finally {
            spec.clearPassword()
        }
        return pbkdf2ByHand(password.toByteArray(Charsets.UTF_8), salt, rounds, len)
    }

    /** PBKDF2-HMAC-SHA256 written out (RFC 8018): the fallback, and what the tests hold the platform's to. */
    internal fun pbkdf2ByHand(password: ByteArray, salt: ByteArray, rounds: Int, len: Int): ByteArray {
        val mac = mac(password)
        val out = ByteArray(len)
        var block = 1
        var at = 0
        while (at < len) {
            mac.update(salt); mac.update(int32(block))
            val u = mac.doFinal()
            val t = u.copyOf()
            repeat(rounds - 1) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            val n = minOf(t.size, len - at)
            System.arraycopy(t, 0, out, at, n)
            at += n; block++
        }
        return out
    }

    private fun mac(key: ByteArray): Mac = Mac.getInstance("HmacSHA256").apply {
        // SecretKeySpec refuses an empty key. HMAC pads a short key with zeros, so a block of zeros is the same key.
        init(SecretKeySpec(if (key.isEmpty()) ByteArray(64) else key, "HmacSHA256"))
    }

    fun hmac(key: ByteArray, data: ByteArray): ByteArray = mac(key).doFinal(data)

    fun hmacHex(key: ByteArray, data: String): String = hex(hmac(key, data.toByteArray(Charsets.UTF_8)))

    /** HKDF-SHA256 (RFC 5869) with an all-zero salt: [len] bytes of key for the one purpose named by [info]. */
    fun hkdf(ikm: ByteArray, info: ByteArray, len: Int = 32): ByteArray = hkdfExpand(hkdfExtract(ByteArray(32), ikm), info, len)

    fun hkdf(ikm: ByteArray, info: String, len: Int = 32): ByteArray = hkdf(ikm, info.toByteArray(Charsets.UTF_8), len)

    internal fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray = hmac(salt, ikm)

    internal fun hkdfExpand(prk: ByteArray, info: ByteArray, len: Int): ByteArray {
        require(len in 1..255 * 32) { "HKDF-SHA256 makes 1 to 8160 bytes" }
        val mac = mac(prk)
        val out = ByteArray(len)
        var t = ByteArray(0)
        var at = 0
        var i = 1
        while (at < len) {
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - at)
            System.arraycopy(t, 0, out, at, n)
            at += n; i++
        }
        return out
    }

    /**
     * A group's id before 2.4: the first 8 hex of HMAC(sha256("hopline-group:" + code), "fingerprint").
     * Quick to work out from a guessed code, so it is never sent any more. It only names the files of a
     * group saved by an older version, and recognises an older phone of the same group nearby.
     */
    fun legacyFingerprint(code: String): String = hmacHex(sha256("hopline-group:" + Words.normalise(code)), "fingerprint").substring(0, 8)

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].toInt() xor b[i].toInt())
        return r == 0
    }

    // ------------------------------------------------------------------ phones: P-256 identities

    private const val PUB_B64_LEN = 87   // 65 bytes of base64url
    private const val PUB_CACHE = 128

    /** P-256 as this platform describes it: taken from a key it made, so its KeyFactory always recognises it. */
    private val p256: ECParameterSpec by lazy { (newKeyPair().public as ECPublicKey).params }

    internal fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"), rng)
        generateKeyPair()
    }

    /** The 65 bytes everyone sees of a P-256 public key: 0x04, then x and y, 32 bytes each. */
    fun pubRawOf(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        return byteArrayOf(4) + fixed(w.affineX, 32) + fixed(w.affineY, 32)
    }

    /** [n] as exactly [len] big-endian bytes (BigInteger adds a sign byte or drops leading zeros). */
    private fun fixed(n: BigInteger, len: Int): ByteArray {
        val b = n.toByteArray()
        return if (b.size >= len) b.copyOfRange(b.size - len, b.size) else ByteArray(len - b.size) + b
    }

    /**
     * A P-256 public key from its 65 raw bytes, or null unless it is exactly a point on the curve:
     * 0x04, both coordinates below p, and y² = x³ + ax + b. (The point at infinity has no 65-byte form,
     * and P-256 has no small subgroups, so that is all there is to check.)
     */
    fun pubOf(raw: ByteArray): PublicKey? {
        if (raw.size != 65 || raw[0] != 4.toByte()) return null
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        val curve = p256.curve
        val p = (curve.field as ECFieldFp).p
        if (x >= p || y >= p) return null
        if (y.multiply(y).mod(p) != x.multiply(x).add(curve.a).multiply(x).add(curve.b).mod(p)) return null
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), p256))
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    private val pubs = object : LinkedHashMap<String, PublicKey>(PUB_CACHE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PublicKey>?): Boolean = size > PUB_CACHE
    }

    /**
     * Someone's public key from its base64url form (an envelope's `pk`), or null unless it is a valid
     * key ([pubOf]). Remembered, since the same few people sign everything.
     */
    fun decodePub(b64: String): PublicKey? {
        synchronized(pubs) { pubs[b64] }?.let { return it }
        val key = decodeEphemeral(b64) ?: return null
        synchronized(pubs) { pubs[b64] = key }
        return key
    }

    /** [decodePub] without remembering: for a one-off key (an envelope's `e`), which must not push out the people. */
    fun decodeEphemeral(b64: String): PublicKey? {
        if (b64.length != PUB_B64_LEN) return null
        return pubOf(unb64(b64) ?: return null)
    }

    /**
     * A phone's node id, made from its public key so nobody can claim someone else's: 16 letters of
     * [ALPHABET], the first 80 bits of SHA-256("hopline/v5/id" ‖ key).
     */
    fun nodeIdOf(pubRaw: ByteArray): String {
        val h = sha256("hopline/v5/id".toByteArray(Charsets.UTF_8) + pubRaw)
        val sb = StringBuilder(16)
        var bits = 0
        var held = 0
        for (j in 0 until 10) {
            bits = ((bits shl 8) or (h[j].toInt() and 0xff)) and 0xfff
            held += 8
            while (held >= 5) { held -= 5; sb.append(ALPHABET[(bits ushr held) and 31]) }
        }
        return sb.toString()
    }

    /** A node id from 2.4 on (16 letters, see [nodeIdOf]). The 8-letter ids of older phones are not. */
    fun isNodeId(s: String): Boolean = isAlphabet(s, 16)

    /** ECDSA-SHA256 over [data]: the DER signature, base64url. */
    fun sign(priv: PrivateKey, data: ByteArray): String =
        b64(Signature.getInstance("SHA256withECDSA").run { initSign(priv); update(data); sign() })

    /** True only when [sig] is [pub]'s signature over exactly [data]. */
    fun verify(pub: PublicKey, data: ByteArray, sig: String): Boolean = try {
        val der = unb64(sig)
        der != null && Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(data); verify(der) }
    } catch (e: Exception) {
        false
    }

    /** ECDH: the shared x-coordinate, always 32 bytes. */
    private fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray {
        val s = KeyAgreement.getInstance("ECDH").run { init(priv); doPhase(pub, true); generateSecret() }
        return if (s.size >= 32) s else ByteArray(32 - s.size) + s
    }

    // ------------------------------------------------------------------ sealing

    private const val NONCE = 12
    private const val TAG_BITS = 128

    /** AES-256-GCM of [plain] under [key] with a fresh random nonce, bound to [aad]: base64url(nonce ‖ ciphertext ‖ tag). */
    fun seal(key: ByteArray, aad: ByteArray, plain: ByteArray): String {
        val nonce = randomBytes(NONCE)
        return b64(nonce + gcm(Cipher.ENCRYPT_MODE, key, nonce, aad).doFinal(plain))
    }

    /** What [seal] sealed, or null if anything is wrong with it: the key, the aad, a single bit. */
    fun open(key: ByteArray, aad: ByteArray, sealed: String): ByteArray? {
        val b = unb64(sealed) ?: return null
        if (b.size < NONCE + TAG_BITS / 8) return null
        return try {
            gcm(Cipher.DECRYPT_MODE, key, b.copyOfRange(0, NONCE), aad).doFinal(b, NONCE, b.size - NONCE)
        } catch (e: Exception) {
            null
        }
    }

    private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray): Cipher {
        require(key.size == 32) { "AES-256 takes a 32-byte key" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
    }

    /**
     * Seals [plain] so only the owner of [recipientPub] can open it, and they know it came from [me]:
     * AES-256-GCM under a key from two ECDHs (a fresh one-off key pair with theirs, and mine with theirs),
     * bound to all three public keys. Returns the sealed text (`c`) and the one-off public key (`e`).
     * Nothing to keep: every message stands alone. No forward secrecy (my key opens what I sent).
     */
    fun sealTo(recipientPub: PublicKey, me: IdentityKeys, aad: ByteArray, plain: ByteArray): Pair<String, String> =
        sealTo(recipientPub, me, plain) { aad }

    /**
     * [sealTo] for an aad that names the one-off key itself (an envelope's header holds its `e`):
     * [aadFor] is given `e` before anything is sealed.
     */
    fun sealTo(recipientPub: PublicKey, me: IdentityKeys, plain: ByteArray, aadFor: (e: String) -> ByteArray): Pair<String, String> {
        val eph = newKeyPair()
        val ephRaw = pubRawOf(eph.public)
        val e = b64(ephRaw)
        val key = pairKey(ecdh(eph.private, recipientPub), ecdh(me.priv, recipientPub), ephRaw, me.pubRaw, pubRawOf(recipientPub))
        return seal(key, aadFor(e), plain) to e
    }

    /** What [sealTo] sealed for [me] from [senderPub] with one-off key [e], or null if it doesn't open (or isn't theirs). */
    fun openFrom(me: IdentityKeys, senderPub: PublicKey, e: String, aad: ByteArray, sealed: String): ByteArray? = try {
        val eph = decodeEphemeral(e)
        if (eph == null) null
        else open(pairKey(ecdh(me.priv, eph), ecdh(me.priv, senderPub), pubRawOf(eph), pubRawOf(senderPub), me.pubRaw), aad, sealed)
    } catch (ex: Exception) {
        null
    }

    private fun pairKey(ss1: ByteArray, ss2: ByteArray, ephRaw: ByteArray, senderRaw: ByteArray, recipientRaw: ByteArray): ByteArray =
        hkdf(ss1 + ss2, "hopline/v5/dm".toByteArray(Charsets.UTF_8) + lp(ephRaw, senderRaw, recipientRaw))

    private val PIECE_AAD = "hopline/v5/piece".toByteArray(Charsets.UTF_8)

    /**
     * Piece [i] of a file, sealed under the file's own key [fk]: AES-256-GCM whose nonce is the piece
     * number, so a piece only opens in its own place. Base64url of ciphertext ‖ tag (the nonce isn't sent).
     * Each file has a fresh key, so a nonce is never used twice.
     */
    fun sealPiece(fk: ByteArray, i: Int, plain: ByteArray): String =
        b64(gcm(Cipher.ENCRYPT_MODE, fk, pieceNonce(i), PIECE_AAD).doFinal(plain))

    /** What [sealPiece] sealed as piece [i], or null if it is not exactly that. */
    fun openPiece(fk: ByteArray, i: Int, sealed: String): ByteArray? {
        val b = unb64(sealed) ?: return null
        return try {
            gcm(Cipher.DECRYPT_MODE, fk, pieceNonce(i), PIECE_AAD).doFinal(b)
        } catch (e: Exception) {
            null
        }
    }

    /** 8 zero bytes, then [i] as 4 big-endian bytes. */
    internal fun pieceNonce(i: Int): ByteArray = ByteArray(8) + int32(i)

    // ------------------------------------------------------------------ bytes to sign, JSON to trust

    /**
     * Each part as its 4-byte big-endian length, then its bytes (text as UTF-8), all run together. What
     * is signed or bound is built from these, so no choice of text can make two headers read the same.
     */
    fun lp(vararg parts: String): ByteArray = lp(*Array(parts.size) { parts[it].toByteArray(Charsets.UTF_8) })

    fun lp(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) { out.write(int32(p.size)); out.write(p) }
        return out.toByteArray()
    }

    private fun int32(i: Int): ByteArray = byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte())

    /**
     * True when the JSON in [text] nests no more than [max] deep, found without parsing it: a hostile
     * `[[[[…` must not run the parser out of stack. Brackets inside strings don't count, so this reads
     * strings the way org.json does — either quote opens one where a value or key can start (not in the
     * middle of a bare word), a backslash escapes the next character, and comments are skipped. A raw
     * control character outside a string (which org.json never writes) is refused, because Android's
     * org.json and the desktop one disagree on what it means.
     */
    fun depthOk(text: String, max: Int): Boolean {
        var depth = 0
        var valueNext = true   // what comes next (after spaces or comments) starts a value or a key
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' || c == '\t' || c == '\n' || c == '\r' -> {}
                c < ' ' -> return false
                c == '#' || c == '/' && text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
                    continue
                }
                c == '/' && text.startsWith("/*", i) -> {
                    val end = text.indexOf("*/", i + 2)
                    if (end < 0) return true   // never closed: the parser gives up here
                    i = end + 2
                    continue
                }
                (c == '"' || c == '\'') && valueNext -> {
                    i++
                    while (i < text.length && text[i] != c) i += if (text[i] == '\\') 2 else 1
                    valueNext = false
                }
                c == '[' || c == '{' -> { if (++depth > max) return false; valueNext = true }
                c == ']' || c == '}' -> { if (depth > 0) depth--; valueNext = false }
                c == ',' || c == ';' || c == ':' -> valueNext = true
                c == '=' -> { valueNext = true; if (text.startsWith(">", i + 1)) i++ }   // org.json also takes = and => after a key
                else -> valueNext = false   // a bare word (true, 12, null), quotes inside it included
            }
            i++
        }
        return true
    }

    /**
     * Canonical JSON (sorted keys, no whitespace). org.json on Android keeps insertion order while the
     * JVM version does not, so signing must never depend on toString() ordering. JSON nested deeper than
     * [MAX_DEPTH] is refused with an IllegalArgumentException, so it can't run out of stack either.
     */
    fun canonical(v: Any?): String = canonical(v, 1)

    private fun canonical(v: Any?, level: Int): String {
        if ((v is JSONObject || v is JSONArray) && level > MAX_DEPTH) throw IllegalArgumentException("JSON nested more than $MAX_DEPTH deep")
        return when (v) {
            null, JSONObject.NULL -> "null"
            is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k ->
                JSONObject.quote(k) + ":" + canonical(v.opt(k), level + 1)
            }
            is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it), level + 1) }
            is String -> JSONObject.quote(v)
            is Boolean -> v.toString()
            is Number -> {
                val d = v.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) v.toLong().toString() else v.toString()
            }
            else -> JSONObject.quote(v.toString())
        }
    }

    // ------------------------------------------------------------------ checking who you're talking to

    /**
     * The security code two people compare to be sure of each other's keys ([pkA], [pkB]: raw 65 bytes):
     * SHA-256("hopline/v5/safety" ‖ lower key ‖ higher key) shown as six groups of five digits, so both
     * phones show the same code.
     */
    fun safetyNumber(pkA: ByteArray, pkB: ByteArray): String {
        val (lo, hi) = if (compareBytes(pkA, pkB) <= 0) pkA to pkB else pkB to pkA
        val h = sha256("hopline/v5/safety".toByteArray(Charsets.UTF_8) + lo + hi)
        return (0 until 6).joinToString(" ") { g ->
            var v = 0L
            for (j in 5 * g until 5 * g + 5) v = (v shl 8) or (h[j].toLong() and 0xff)
            (v % 100_000).toString().padStart(5, '0')
        }
    }

    /** [safetyNumber] of two keys as they travel (base64url), or null if either isn't one. */
    fun safetyNumber(pkA: String, pkB: String): String? {
        val a = unb64(pkA) ?: return null
        val b = unb64(pkB) ?: return null
        return safetyNumber(a, b)
    }

    /** Unsigned, byte by byte, the shorter first on a tie. */
    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }
}

/**
 * One group's keys, all from its [master] key ([Crypto.stretch] of the code), each made when first
 * needed: [env] seals what the group sends, [link] proves the code when two phones link, and [tag]
 * (16 hex) is the only thing derived from the code that ever goes on air, in the name a phone advertises.
 */
class GroupKeys(val master: ByteArray) {
    val env: ByteArray by lazy { Crypto.hkdf(master, "hopline/v5/env") }
    val link: ByteArray by lazy { Crypto.hkdf(master, "hopline/v5/link") }
    val tag: String by lazy { Crypto.hex(Crypto.hkdf(master, "hopline/v5/tag", 8)) }
}

/**
 * One phone's identity: a P-256 key pair that signs everything the phone sends (ECDSA) and opens what
 * is written privately to it (ECDH). [pubRaw] is the 65-byte public point, [pubB64] the same as it
 * travels (87 chars), and [nodeId] the phone's id, which is made from it.
 */
class IdentityKeys(val priv: PrivateKey, val pub: PublicKey) {
    val pubRaw: ByteArray by lazy { Crypto.pubRawOf(pub) }
    val pubB64: String by lazy { Crypto.b64(pubRaw) }
    val nodeId: String by lazy { Crypto.nodeIdOf(pubRaw) }

    /** For identity.json: the private key (PKCS#8) and the raw public key, both base64url. */
    fun encode(): String = JSONObject().put("priv", Crypto.b64(priv.encoded)).put("pub", pubB64).toString()

    /** True when [priv] signs what [pub] verifies: the two halves belong together. */
    private fun matches(): Boolean = PROBE.let { Crypto.verify(pub, it, Crypto.sign(priv, it)) }

    companion object {
        private val PROBE = "hopline/v5/probe".toByteArray(Charsets.UTF_8)

        fun generate(): IdentityKeys = Crypto.newKeyPair().let { IdentityKeys(it.private, it.public) }

        /** What [encode] wrote, or null unless it reads back as a P-256 key pair whose halves belong together. */
        fun decode(text: String): IdentityKeys? = try {
            val j = JSONObject(text)
            val pub = Crypto.unb64(j.getString("pub"))?.let { Crypto.pubOf(it) }
            val der = Crypto.unb64(j.getString("priv"))
            if (pub == null || der == null) null
            else IdentityKeys(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der)), pub).takeIf { it.matches() }
        } catch (e: Exception) {
            null
        }
    }
}
