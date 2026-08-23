package app.hopline.core

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Group key, fingerprints and message signing. Pure JVM — no Android imports — so it is unit-testable. */
object Crypto {
    private val rng = SecureRandom()
    private const val ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789" // no 0/o/1/l look-alikes

    fun randomId(len: Int = 10): String {
        val sb = StringBuilder(len)
        repeat(len) { sb.append(ALPHABET[rng.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }

    fun sha256(s: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))

    /** The 3-word group code is the shared secret. Everything else derives from it. */
    fun groupKey(code: String): ByteArray = sha256("hopline-group:" + Words.normalise(code))

    fun hmacHex(key: ByteArray, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** Short public tag that lets phones recognise "same group" over the air without revealing the code. */
    fun fingerprint(key: ByteArray): String = hmacHex(key, "fingerprint").substring(0, 8)

    fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }

    /**
     * Canonical JSON (sorted keys, no whitespace). org.json on Android keeps insertion order while the
     * JVM version does not, so signing must never depend on toString() ordering.
     */
    fun canonical(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k ->
            JSONObject.quote(k) + ":" + canonical(v.opt(k))
        }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canonical(v.opt(it)) }
        is String -> JSONObject.quote(v)
        is Boolean -> v.toString()
        is Number -> {
            val d = v.toDouble()
            if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) v.toLong().toString() else v.toString()
        }
        else -> JSONObject.quote(v.toString())
    }
}
