package app.hopline.service

import app.hopline.core.SafeUrl
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.net.ssl.HttpsURLConnection

/**
 * The only way Hopline talks to the internet. Mostly for someone else — a helper's phone fetching
 * what a friend asked for ([get]): HTTPS first, public addresses only (re-checked on every
 * redirect), text-ish content only, hard caps on time and bytes, and an honest count of the bytes
 * that actually crossed the helper's data plan. And for Hopline itself, when it looks for a newer
 * version and fetches it ([get] with a host rule, [download]): HTTPS only, and only the hosts the
 * caller names, on every hop. Blocking — call it off the main thread.
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

    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) Hopline/2.3 (offline group chat, text only; +https://github.com/ForPublicOrg/hopline)"
    private val TEXT_TYPES = listOf("text/html", "application/xhtml+xml", "text/plain", "application/json", "text/xml", "application/xml")

    /**
     * GET (or POST a form body). [maxWire] bounds the bytes downloaded, [maxBody] the bytes after
     * decompression, [deadlineMs] the whole exchange including redirects.
     *
     * With [hostOk] the request is one Hopline makes for itself, to a service it names: every
     * address on the way — the first and each redirect — must pass it, plain http is never tried
     * (an answer that came in the clear could have been written by anyone on the way), and the
     * request says nothing about the phone, not even its language.
     */
    fun get(
        rawUrl: String,
        maxWire: Int = 400_000,
        maxBody: Int = 2_000_000,
        deadlineMs: Long = 30_000,
        accept: List<String> = TEXT_TYPES,
        form: String? = null,
        session: Session = Session(),
        hostOk: ((String) -> Boolean)? = null,
    ): Response = try {
        attempt(rawUrl, false, maxWire, maxBody, deadlineMs, accept, form, session, hostOk)
    } catch (e: IOException) {
        // Some small sites still only speak plain http. Try that once — the same private-address
        // checks apply to every hop — but only when https could not even connect.
        val httpOnly = hostOk == null && e !is Problem && (e is javax.net.ssl.SSLException || e is java.net.ConnectException || e is java.net.SocketTimeoutException)
        val plain = SafeUrl.check(rawUrl)?.replaceFirst("https://", "http://")
        if (httpOnly && plain != null && form == null && !session.cancelled) attempt(plain, true, maxWire, maxBody, deadlineMs, accept, null, session, null) else throw e
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
        hostOk: ((String) -> Boolean)?,
    ): Response {
        val start = System.currentTimeMillis()
        var url = SafeUrl.check(rawUrl, allowHttp = http) ?: throw Problem("That isn't a web address Hopline can open.", "bad_url", true)
        var wire = 0
        var postBody = form
        repeat(MAX_REDIRECTS + 1) {
            if (session.cancelled) throw Problem("Stopped.", "cancelled", true)
            if (hostOk != null && !hostOk(url)) throw Problem("That address isn't one Hopline asks.", "bad_host", true)
            val host = URI(url).host ?: throw Problem("That link has no address.", "bad_url", true)
            val addrs = try { InetAddress.getAllByName(host) } catch (e: Exception) { throw Problem("Couldn't find $host — is the address right?", "dns", false) }
            if (!SafeUrl.allPublic(addrs)) throw Problem("That address is on a private network, so a friend's phone won't open it.", "unsafe", true)
            // Looking a name up can take a while and can't be interrupted: a stop asked for meanwhile counts now.
            if (session.cancelled) throw Problem("Stopped.", "cancelled", true)
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
                if (hostOk == null) c.setRequestProperty("Accept-Language", java.util.Locale.getDefault().toLanguageTag() + ",en;q=0.8")
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

    // ------------------------------------------------------------------ a file to keep (Hopline's own update)

    /**
     * Download [rawUrl] into [dest] and return the SHA-256 of the bytes written, in hex. This is
     * how Hopline fetches a new version of itself, and it is stricter than [get] in every way:
     *  - HTTPS only. There is no plain-http second try, and a redirect to http is refused, never
     *    quietly "upgraded".
     *  - Every address — the first and each redirect — must pass [hostOk] as well as the usual
     *    checks. A download that is handed on to anywhere else stops there.
     *  - Never more than [maxBytes], and never longer than [DOWNLOAD_DEADLINE_MS] in all.
     *  - [dest] only ever holds a whole download: the bytes land in a temporary file beside it,
     *    which takes its name at the very end and is deleted on any failure.
     *
     * [onProgress] hears the bytes so far, on the calling thread, after every block. [session] can
     * stop it at once. The bytes are the phone's own use of its internet: they are not counted in
     * [Session.spent], which is what a helper spends on other people.
     *
     * Whatever goes wrong comes out as a [Problem] — also a connection that simply broke, which
     * is one to try again later ([Problem.permanent] false).
     */
    fun download(
        rawUrl: String,
        dest: File,
        maxBytes: Long,
        hostOk: (String) -> Boolean,
        session: Session = Session(),
        onProgress: (Long) -> Unit = {},
    ): String = try {
        fetchFile(rawUrl, dest, maxBytes, hostOk, session, onProgress)
    } catch (e: Problem) {
        throw e
    } catch (e: Exception) {
        // A socket closed under a read is how a cancel looks from here; anything else is the network.
        throw if (session.cancelled) Problem("Stopped.", "cancelled", true) else Problem("The download was interrupted.", "network", false)
    }

    private fun fetchFile(rawUrl: String, dest: File, maxBytes: Long, hostOk: (String) -> Boolean, session: Session, onProgress: (Long) -> Unit): String {
        val deadlineAt = System.currentTimeMillis() + DOWNLOAD_DEADLINE_MS
        var url = hop(rawUrl, hostOk)
        repeat(MAX_REDIRECTS + 1) {
            if (session.cancelled) throw Problem("Stopped.", "cancelled", true)
            val host = URI(url).host ?: throw Problem("That link has no address.", "bad_url", true)
            val addrs = try { InetAddress.getAllByName(host) } catch (e: Exception) { throw Problem("Couldn't find $host.", "dns", false) }
            // Not "for good": a WiFi that answers every name with its own sign-in page does this.
            if (!SafeUrl.allPublic(addrs)) throw Problem("$host isn't where it should be on this network.", "unsafe", false)
            val left = deadlineAt - System.currentTimeMillis()
            if (left <= 1000) throw Problem("The download took too long.", "timeout", false)
            val c = URL(url).openConnection() as? HttpsURLConnection ?: throw Problem("That download isn't over a secure connection.", "bad_url", true)
            session.conn = c
            try {
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.connectTimeout = minOf(15_000L, left).toInt()
                c.readTimeout = minOf(30_000L, left).toInt()
                c.setRequestProperty("User-Agent", USER_AGENT)
                c.setRequestProperty("Accept", "application/octet-stream,*/*;q=0.5")
                // The bytes exactly as stored: their size and SHA-256 are checked against what was published.
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                if (code in 300..399) { url = nextHop(url, c.getHeaderField("Location"), hostOk); return@repeat }
                if (code == 404 || code == 410) throw Problem("That file isn't there any more (error $code).", "http_$code", true)
                if (code != 200) throw Problem("The download was refused (error $code).", "http_$code", false)
                val length = c.contentLengthLong
                if (length > maxBytes) throw Problem("That file is too big.", "too_big", true)
                return c.inputStream.use { store(it, dest, maxBytes, length, session, deadlineAt, onProgress) }
            } finally {
                session.conn = null
                c.disconnect()
            }
        }
        throw Problem("Too many redirects.", "redirect", true)
    }

    /** One address a download may be asked from: https, public by its spelling, and a host the caller allows. */
    internal fun hop(url: String, hostOk: (String) -> Boolean): String =
        SafeUrl.check(url)?.takeIf(hostOk) ?: throw Problem("That download isn't at an address Hopline trusts.", "bad_host", true)

    /**
     * Where a redirect leads, or a [Problem] when a download may not follow it: nowhere, plain
     * http, a private address, or a host the caller doesn't allow. A relative Location is read
     * against the address that sent it.
     */
    internal fun nextHop(from: String, location: String?, hostOk: (String) -> Boolean): String {
        val loc = location?.trim()?.takeIf { it.isNotEmpty() } ?: throw Problem("The download was sent nowhere.", "redirect", true)
        val next = try { URI(from).resolve(loc).toString() } catch (e: Exception) { throw Problem("The download was sent somewhere unreadable.", "bad_url", true) }
        return hop(next, hostOk)
    }

    /**
     * Write [ins] to [dest] by way of a temporary file beside it, and return the SHA-256 of what
     * was written. Stops — leaving nothing behind — past [maxBytes], past [deadlineAt], when
     * [session] is cancelled, or when the stream ends before the [expected] length (-1 = not told).
     */
    internal fun store(ins: InputStream, dest: File, maxBytes: Long, expected: Long, session: Session, deadlineAt: Long, onProgress: (Long) -> Unit): String {
        // The phone's own storage letting the download down is not the network's fault, and is said apart.
        fun disk() = Problem("The phone couldn't keep the download — its storage may be full.", "disk", false)
        val dir = dest.parentFile ?: throw disk()
        dir.mkdirs()
        val tmp = File(dir, BlobRules.TEMP_PREFIX + dest.name)
        var kept = false
        try {
            val sha = MessageDigest.getInstance("SHA-256")
            var count = 0L
            (try { FileOutputStream(tmp) } catch (e: IOException) { throw disk() }).use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (session.cancelled) throw Problem("Stopped.", "cancelled", true)
                    if (System.currentTimeMillis() > deadlineAt) throw Problem("The download took too long.", "timeout", false)
                    // A blocked read only ends when its socket is closed under it: that is the cancel, not a fault.
                    val n = try { ins.read(buf) } catch (e: IOException) { if (session.cancelled) throw Problem("Stopped.", "cancelled", true) else throw e }
                    if (n < 0) break
                    count += n
                    if (count > maxBytes) throw Problem("That file is too big.", "too_big", true)
                    try { out.write(buf, 0, n) } catch (e: IOException) { throw disk() }
                    sha.update(buf, 0, n)
                    onProgress(count)
                }
                try { out.flush() } catch (e: IOException) { throw disk() }
                try { out.fd.sync() } catch (e: IOException) { }   // best effort: the size and SHA-256 are checked before the file is used
            }
            if (expected >= 0 && count != expected) throw Problem("The download was cut short.", "incomplete", false)
            if (dest.exists() && !dest.delete() || !tmp.renameTo(dest)) throw disk()
            kept = true
            return sha.digest().joinToString("") { "%02x".format(it) }
        } finally {
            if (!kept) tmp.delete()
        }
    }

    private const val MAX_REDIRECTS = 5
    /** Enough of a page to be worth reading when the clock runs out. */
    private const val MIN_USEFUL = 4_096
    /** A whole download, redirects included. An APK is a few MB; a link too slow for this is tried again later. */
    const val DOWNLOAD_DEADLINE_MS = 15 * 60_000L
}
