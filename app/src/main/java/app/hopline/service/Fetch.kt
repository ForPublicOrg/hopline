package app.hopline.service

import app.hopline.core.SafeUrl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * The only way a helper's phone talks to the internet for someone else. HTTPS only, public
 * addresses only (re-checked on every redirect), text-ish content only, hard caps on time and
 * bytes, and an honest count of the bytes that actually crossed the helper's data plan.
 * Blocking — call it off the main thread.
 */
object Fetch {
    class Response(val url: String, val contentType: String, val body: ByteArray, val wireBytes: Int)

    /** A failure the asker should hear about in plain words. [permanent] = asking again won't help. */
    class Problem(message: String, val why: String, val permanent: Boolean) : IOException(message)

    /**
     * One request's fetches: how to stop them at once, and every byte they cost the helper's data
     * plan — failed downloads included, so the daily allowance stays honest.
     */
    class Session {
        @Volatile var cancelled = false; private set
        @Volatile internal var conn: HttpURLConnection? = null
        val spent = java.util.concurrent.atomic.AtomicInteger()

        /** Stop now: a blocked read only ends when its socket closes (off the main thread — it can touch the network). */
        fun abort() {
            cancelled = true
            conn?.let { c -> Thread { try { c.disconnect() } catch (e: Exception) { } }.start() }
        }
    }

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) Hopline/2.2 (offline group chat, text only; +https://github.com/ForPublicOrg/hopline)"
    private val TEXT_TYPES = listOf("text/html", "application/xhtml+xml", "text/plain", "application/json", "text/xml", "application/xml")

    /**
     * GET (or POST a form body). [maxWire] bounds the bytes downloaded, [maxBody] the bytes after
     * decompression, [deadlineMs] the whole exchange including redirects.
     */
    fun get(
        rawUrl: String,
        maxWire: Int = 400_000,
        maxBody: Int = 2_000_000,
        deadlineMs: Long = 30_000,
        accept: List<String> = TEXT_TYPES,
        form: String? = null,
        session: Session = Session(),
    ): Response = try {
        attempt(rawUrl, false, maxWire, maxBody, deadlineMs, accept, form, session)
    } catch (e: IOException) {
        // Some small sites still only speak plain http. Try that once — the same private-address
        // checks apply to every hop — but only when https could not even connect.
        val httpOnly = e !is Problem && (e is javax.net.ssl.SSLException || e is java.net.ConnectException || e is java.net.SocketTimeoutException)
        val plain = SafeUrl.check(rawUrl)?.replaceFirst("https://", "http://")
        if (httpOnly && plain != null && form == null && !session.cancelled) attempt(plain, true, maxWire, maxBody, deadlineMs, accept, null, session) else throw e
    }

    private fun attempt(
        rawUrl: String,
        http: Boolean,
        maxWire: Int,
        maxBody: Int,
        deadlineMs: Long,
        accept: List<String>,
        form: String?,
        session: Session,
    ): Response {
        val start = System.currentTimeMillis()
        var url = SafeUrl.check(rawUrl, allowHttp = http) ?: throw Problem("That isn't a web address Hopline can open.", "bad_url", true)
        var wire = 0
        var postBody = form
        repeat(MAX_REDIRECTS + 1) {
            if (session.cancelled) throw Problem("Stopped.", "cancelled", true)
            val host = URI(url).host ?: throw Problem("That link has no address.", "bad_url", true)
            val addrs = try { InetAddress.getAllByName(host) } catch (e: Exception) { throw Problem("Couldn't find $host — is the address right?", "dns", false) }
            if (!SafeUrl.allPublic(addrs)) throw Problem("That address is on a private network, so a friend's phone won't open it.", "unsafe", true)
            val left = deadlineMs - (System.currentTimeMillis() - start)
            if (left <= 1000) throw Problem("The site took too long to answer.", "timeout", false)
            val c = URL(url).openConnection() as HttpURLConnection
            session.conn = c
            var hop = 0
            var counted: CountingStream? = null
            try {
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.connectTimeout = minOf(12_000L, left).toInt()
                c.readTimeout = minOf(15_000L, left).toInt()
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", accept.joinToString(",") + ";q=0.9,*/*;q=0.1")
                c.setRequestProperty("Accept-Encoding", "gzip")
                c.setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8")
                if (postBody != null) {
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    val b = postBody!!.toByteArray(Charsets.UTF_8)
                    c.setFixedLengthStreamingMode(b.size)
                    c.outputStream.use { it.write(b) }
                    hop += b.size; session.spent.addAndGet(b.size)
                }
                val code = c.responseCode
                hop += 400; session.spent.addAndGet(400)   // headers, roughly
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw Problem("The site sent us nowhere.", "redirect", true)
                    val next = URI(url).resolve(loc.trim()).toString()
                    url = SafeUrl.check(if (!http && next.startsWith("http://")) "https://" + next.removePrefix("http://") else next, allowHttp = http)
                        ?: throw Problem("The site redirects to a link Hopline can't open.", "bad_url", true)
                    postBody = null
                    return@repeat
                }
                if (code == 404 || code == 410) throw Problem("That page doesn't exist (error $code).", "http_$code", true)
                if (code in 400..499) throw Problem("The site refused (error $code).", "http_$code", code != 408 && code != 429)
                if (code >= 500) throw Problem("The site is having trouble (error $code).", "http_$code", false)
                val type = (c.contentType ?: "").lowercase()
                if (accept.none { type.startsWith(it) } && type.isNotEmpty()) {
                    val len = c.contentLengthLong
                    val kind = when {
                        type.startsWith("application/pdf") -> "a PDF"
                        type.startsWith("image/") -> "an image"
                        type.startsWith("video/") -> "a video"
                        type.startsWith("audio/") -> "audio"
                        else -> "not a text page"
                    }
                    throw Problem("That link is $kind${if (len > 0) " (${len / 1024} KB)" else ""} — Hopline only reads text pages.", "bad_type", true)
                }
                if (c.contentLengthLong > maxWire * 4L) throw Problem("That page is too big to carry over the mesh.", "too_big", true)
                val gz = (c.contentEncoding ?: "").contains("gzip", ignoreCase = true)
                val cs = CountingStream(c.inputStream, maxWire, session).also { counted = it }
                val body = readCapped(if (gz) GZIPInputStream(cs) else cs, cs, maxBody, session, start + deadlineMs)
                return Response(url, type, body, wire + hop + cs.count)
            } finally {
                // (The session already counted these bytes as they crossed — a cancel reads the
                // total mid-download, before this hop finishes.)
                hop += counted?.count ?: 0
                wire += hop
                session.conn = null
                c.disconnect()
            }
        }
        throw Problem("Too many redirects.", "redirect", true)
    }

    /**
     * Read the body, never past [max] bytes, [wire]'s data budget or [deadlineAt]. The beginning
     * of a huge (or very slow) page is still useful; a site that trickles out almost nothing is
     * a timeout, so another phone can try instead of this one waiting for hours.
     */
    internal fun readCapped(ins: InputStream, wire: CountingStream, max: Int, session: Session, deadlineAt: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        ins.use {
            while (true) {
                if (session.cancelled) throw Problem("Stopped.", "cancelled", true)
                if (System.currentTimeMillis() > deadlineAt) {
                    if (out.size() >= MIN_USEFUL) break
                    throw Problem("The site took too long to answer.", "timeout", false)
                }
                // A gzip body cut off by the data budget ends mid-stream: that is the cap, not damage.
                val n = try { it.read(buf) } catch (x: java.io.EOFException) { if (wire.atLimit) -1 else throw x }
                if (n < 0) break
                out.write(buf, 0, minOf(n, max - out.size()))
                if (out.size() >= max) break   // the beginning of a huge page is still useful
            }
        }
        return out.toByteArray()
    }

    /** Stops reading past the data budget for one request, and tells us how much was really used. */
    internal class CountingStream(private val inner: InputStream, private val limit: Int, private val session: Session? = null) : InputStream() {
        var count = 0; private set
        val atLimit: Boolean get() = count >= limit
        override fun read(): Int {
            if (count >= limit) return -1
            val b = inner.read()
            if (b >= 0) { count++; session?.spent?.incrementAndGet() }
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (count >= limit) return -1
            val n = inner.read(b, off, minOf(len, limit - count))
            if (n > 0) { count += n; session?.spent?.addAndGet(n) }
            return n
        }
        override fun close() = inner.close()
    }

    private const val MAX_REDIRECTS = 5
    /** Enough of a page to be worth reading when the clock runs out. */
    private const val MIN_USEFUL = 4_096
}
