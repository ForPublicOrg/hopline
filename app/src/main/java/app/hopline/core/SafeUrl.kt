package app.hopline.core

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * The helper's phone fetches what OTHER people type, from inside whatever network it is on. So a
 * request may only reach the public internet over HTTPS: never the helper's home router, a
 * printer, localhost, or a carrier-internal address — and every redirect is re-checked.
 */
object SafeUrl {
    const val MAX_LEN = 2048

    private val URL_IN_TEXT = Regex("""(?i)\b((?:https?://)?(?:[a-z0-9-]+\.)+[a-z]{2,}(?::\d{1,5})?(?:[/?#][^\s<>"']*)?)""")

    /**
     * What a person typed or pasted, as a URL to ask for: pulls the link out of shared text
     * ("Title https://…"), lowercases the scheme, adds https:// when there is none, and upgrades
     * http to https (cleartext is blocked on the helper anyway). Null when there is no link.
     */
    fun fromInput(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty() || t.length > MAX_LEN) return null
        val m = URL_IN_TEXT.find(t) ?: return null
        var u = m.groupValues[1].trimEnd('.', ',', ')', ']', ';', ':', '!', '?')
        val scheme = Regex("^(?i)(https?)://").find(u)
        u = if (scheme != null) "https://" + u.substring(scheme.value.length) else "https://$u"
        return check(u)
    }

    /** True when the text looks like a link rather than a question. */
    fun looksLikeUrl(raw: String): Boolean {
        val t = raw.trim()
        if (t.contains(' ') && !t.contains("://")) return false
        return fromInput(t) != null && (t.contains("://") || t.contains('/') || Regex("""(?i)^[a-z0-9-]+(\.[a-z0-9-]+)+$""").matches(t))
    }

    /**
     * Syntax checks that need no network: https (or, for an http-only site the helper falls back
     * to, plain http on port 80), no credentials, default port, no local names.
     */
    fun check(url: String, allowHttp: Boolean = false): String? {
        if (url.length > MAX_LEN) return null
        val u = try { URI(url) } catch (e: Exception) { return null }
        val scheme = u.scheme?.lowercase()
        if (scheme != "https" && !(allowHttp && scheme == "http")) return null
        if (u.rawUserInfo != null) return null
        if (u.port != -1 && u.port != (if (scheme == "http") 80 else 443)) return null
        val host = u.host?.lowercase()?.trimEnd('.') ?: return null
        if (host.isEmpty() || !host.contains('.') && !host.startsWith("[")) return null
        if (LOCAL_SUFFIXES.any { host == it.removePrefix(".") || host.endsWith(it) }) return null
        return u.toString()
    }

    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".internal", ".intranet", ".home", ".home.arpa", ".corp", ".onion", ".test", ".invalid")

    /** Every address a host resolves to must be public — one private answer and the fetch is refused. */
    fun allPublic(addrs: Array<InetAddress>): Boolean = addrs.isNotEmpty() && addrs.all { isPublic(it) }

    fun isPublic(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        if (a is Inet4Address) return publicV4(b)
        if (a is Inet6Address) {
            // IPv4-mapped / -compatible: judge the embedded IPv4.
            val mapped = (0 until 10).all { b[it].toInt() == 0 } && ((b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF || b[10].toInt() == 0 && b[11].toInt() == 0)
            if (mapped) return publicV4(b.copyOfRange(12, 16))
            val first = b[0].toInt() and 0xFF
            if (first and 0xFE == 0xFC) return false                               // fc00::/7 unique local
            if (first == 0xFE && (b[1].toInt() and 0xC0) == 0x80) return false     // fe80::/10
            if (first == 0x20 && (b[1].toInt() and 0xFF) == 0x01 && b[2].toInt() == 0x0D && (b[3].toInt() and 0xFF) == 0xB8) return false // documentation
            if (first == 0x00) return false
            if (first == 0x20 && b[1].toInt() == 0x02) return publicV4(b.copyOfRange(2, 6))   // 6to4 wraps an IPv4
            return true
        }
        return false
    }

    private fun publicV4(b: ByteArray): Boolean {
        val o0 = b[0].toInt() and 0xFF; val o1 = b[1].toInt() and 0xFF; val o2 = b[2].toInt() and 0xFF
        return when {
            o0 == 0 || o0 == 10 || o0 == 127 -> false
            o0 == 100 && o1 in 64..127 -> false            // carrier-grade NAT
            o0 == 169 && o1 == 254 -> false
            o0 == 172 && o1 in 16..31 -> false
            o0 == 192 && o1 == 168 -> false
            o0 == 192 && o1 == 0 && (o2 == 0 || o2 == 2) -> false
            o0 == 198 && (o1 == 18 || o1 == 19) -> false   // benchmarking
            o0 == 198 && o1 == 51 && o2 == 100 -> false
            o0 == 203 && o1 == 0 && o2 == 113 -> false
            o0 >= 224 -> false                              // multicast + reserved + broadcast
            else -> true
        }
    }
}
