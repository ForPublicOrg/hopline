package app.hopline.service

import app.hopline.core.Update
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * How a helper reads a page body: never past its data budget, never for hours. And how Hopline
 * fetches its own update: from GitHub only, over https only, and never leaving half a file behind.
 */
class FetchTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    @Test fun `a gzip page bigger than the data budget gives its beginning instead of failing`() {
        val words = (1..60_000).joinToString(" ") { "w${(it * 7919) % 100_003}" }
        val zipped = gzip(words)
        val budget = zipped.size / 4
        val wire = Fetch.CountingStream(ByteArrayInputStream(zipped), budget)
        val body = Fetch.readCapped(GZIPInputStream(wire), wire, 2_000_000, Fetch.Session(), Long.MAX_VALUE)
        assertTrue(body.bytes.isNotEmpty())
        assertTrue(String(body.bytes).startsWith("w7919 "))
        assertEquals(budget, wire.count)
        // …and says it is only the beginning
        assertTrue(body.cut)
    }

    @Test fun `a body stopped by the data budget, the size cap or the clock says it is cut, and a whole one does not`() {
        fun plain(size: Int, budget: Int, max: Int = 2_000_000): Fetch.Body {
            val wire = Fetch.CountingStream(ByteArrayInputStream(ByteArray(size) { 'x'.code.toByte() }), budget)
            return Fetch.readCapped(wire, wire, max, Fetch.Session(), Long.MAX_VALUE)
        }
        // read to its end: whole
        plain(10_000, 400_000).let { assertEquals(10_000, it.bytes.size); assertFalse(it.cut) }
        val words = (1..20_000).joinToString(" ") { "w$it" }
        val zipped = Fetch.CountingStream(ByteArrayInputStream(gzip(words)), 400_000)
        Fetch.readCapped(GZIPInputStream(zipped), zipped, 2_000_000, Fetch.Session(), Long.MAX_VALUE).let {
            assertEquals(words, String(it.bytes)); assertFalse(it.cut)
        }
        // a page that isn't gzipped, stopped at a friend's 400 000 bytes: the budget ends it without any error
        plain(680_000, 400_000).let { assertEquals(400_000, it.bytes.size); assertTrue(it.cut) }
        // one that comes out bigger than the size cap (as a gzipped page can)
        plain(50_000, 400_000, max = 20_000).let { assertEquals(20_000, it.bytes.size); assertTrue(it.cut) }
        // a slow site, stopped by the clock once enough of it is in
        val slow = object : InputStream() {
            var left = 100
            override fun read(): Int = throw IOException("not used")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left-- <= 0) return -1
                Thread.sleep(20); java.util.Arrays.fill(b, off, off + len, 'x'.code.toByte()); return len
            }
        }
        val wire = Fetch.CountingStream(slow, 10_000_000)
        Fetch.readCapped(wire, wire, 10_000_000, Fetch.Session(), System.currentTimeMillis() + 300).let {
            assertTrue(it.bytes.size in 4_096 until 100 * 8_192); assertTrue(it.cut)
        }
    }

    @Test fun `a site that trickles bytes times out so another phone can try`() {
        val trickle = object : InputStream() {
            override fun read(): Int = 'x'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int { b[off] = 'x'.code.toByte(); return 1 }
        }
        val wire = Fetch.CountingStream(trickle, 400_000)
        val p = try { Fetch.readCapped(wire, wire, 2_000_000, Fetch.Session(), System.currentTimeMillis() - 1); null }
                catch (x: Fetch.Problem) { x }
        assertEquals("timeout", p?.why); assertFalse(p!!.permanent)
    }

    @Test fun `bytes count against the allowance as they arrive, so a cancel mid-download still bills them`() {
        val s = Fetch.Session()
        val wire = Fetch.CountingStream(ByteArrayInputStream(ByteArray(50_000)), 400_000, s)
        val buf = ByteArray(8192)
        wire.read(buf); wire.read(buf)
        assertEquals(16_384, s.spent.get())
    }

    @Test fun `a cancelled request stops reading`() {
        val s = Fetch.Session(); s.abort()
        val wire = Fetch.CountingStream(ByteArrayInputStream(ByteArray(10_000)), 400_000)
        val p = try { Fetch.readCapped(wire, wire, 2_000_000, s, Long.MAX_VALUE); null } catch (x: Fetch.Problem) { x }
        assertEquals("cancelled", p?.why)
    }

    // ---------------------------------------------------------------- Hopline's own download: where it may come from

    private fun problem(block: () -> Unit): Fetch.Problem? = try { block(); null } catch (x: Fetch.Problem) { x }

    private val asset = "https://github.com/ForPublicOrg/hopline/releases/download/v2.3/Hopline.apk"

    @Test fun `a download starts only at an https address the caller allows`() {
        assertEquals(asset, Fetch.hop(asset, Update::allowedHost))
        for (bad in listOf(asset.replace("https://", "http://"), "https://example.com/Hopline.apk", "https://github.com.evil.io/Hopline.apk",
            "https://user@github.com/Hopline.apk", "https://github.com:8443/Hopline.apk", "ftp://github.com/Hopline.apk", "Hopline.apk", "")) {
            val p = problem { Fetch.hop(bad, Update::allowedHost) }
            assertEquals(bad, "bad_host", p?.why); assertTrue(bad, p!!.permanent)
        }
        // The caller's rule is not the only one: a name that can only be on this phone's own network is never asked.
        assertEquals("bad_host", problem { Fetch.hop("https://updates.local/Hopline.apk") { true } }?.why)
        assertEquals("bad_host", problem { Fetch.hop("https://localhost/Hopline.apk") { true } }?.why)
    }

    @Test fun `a redirect is followed to GitHub's own file hosts and nowhere else`() {
        val signed = "https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sp=r&sig=x%2Fy%3D"
        assertEquals(signed, Fetch.nextHop(asset, signed, Update::allowedHost))
        assertEquals("https://objects.githubusercontent.com/a/b", Fetch.nextHop(asset, "  https://objects.githubusercontent.com/a/b \n", Update::allowedHost))
        for (bad in listOf("https://evil.example/Hopline.apk", "https://evilgithubusercontent.com/x", "https://github.com.evil.io/x",
            "https://release-assets.githubusercontent.com:8443/x", "https://10.0.0.1/Hopline.apk", "//evil.example/Hopline.apk")) {
            assertEquals(bad, "bad_host", problem { Fetch.nextHop(asset, bad, Update::allowedHost) }?.why)
        }
    }

    @Test fun `a redirect to plain http is refused, never quietly upgraded`() {
        for (bad in listOf("http://release-assets.githubusercontent.com/x", "http://github.com/ForPublicOrg/hopline/releases/download/v2.3/Hopline.apk",
            "HTTP://objects.githubusercontent.com/x")) {
            val p = problem { Fetch.nextHop(asset, bad, Update::allowedHost) }
            assertEquals(bad, "bad_host", p?.why); assertTrue(p!!.permanent)
        }
        // And that is the download's own rule, whatever the caller's rule would let through.
        assertEquals("bad_host", problem { Fetch.hop("http://example.com/Hopline.apk") { true } }?.why)
        assertEquals("bad_host", problem { Fetch.nextHop(asset, "http://example.com/Hopline.apk") { true } }?.why)
        assertEquals("https://example.com/Hopline.apk", Fetch.nextHop(asset, "https://example.com/Hopline.apk") { true })
    }

    @Test fun `a relative redirect is read against the address that sent it`() {
        assertEquals("https://github.com/ForPublicOrg/hopline/releases/download/v2.3/other.apk", Fetch.nextHop(asset, "other.apk", Update::allowedHost))
        assertEquals("https://github.com/elsewhere", Fetch.nextHop(asset, "/elsewhere", Update::allowedHost))
        // "//host/…" keeps the scheme and changes the host: it still has to be an allowed one.
        assertEquals("https://objects.githubusercontent.com/x", Fetch.nextHop(asset, "//objects.githubusercontent.com/x", Update::allowedHost))
    }

    @Test fun `a redirect to nowhere, or to something unreadable, stops the download`() {
        assertEquals("redirect", problem { Fetch.nextHop(asset, null, Update::allowedHost) }?.why)
        assertEquals("redirect", problem { Fetch.nextHop(asset, "   ", Update::allowedHost) }?.why)
        assertEquals("bad_url", problem { Fetch.nextHop(asset, "https://exa mple.com/ x", Update::allowedHost) }?.why)
    }

    // ---------------------------------------------------------------- Hopline's own download: the file

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun bytes(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }
    private fun dest() = File(File(tmp.root, "update"), "Hopline-2.3.apk")
    private fun leftovers() = dest().parentFile!!.listFiles()?.map { it.name }.orEmpty()

    @Test fun `a download lands whole under its name, with its sha-256, by way of a temporary file`() {
        val data = bytes(200_000)
        val seen = ArrayList<Long>()
        var tempSeen = false
        val sha = Fetch.store(ByteArrayInputStream(data), dest(), 1_000_000, data.size.toLong(), Fetch.Session(), Long.MAX_VALUE) {
            seen += it
            // While it is on its way the bytes are under the temporary name, never the real one.
            tempSeen = tempSeen || (File(dest().parentFile, "~Hopline-2.3.apk").exists() && !dest().exists())
        }
        assertEquals(sha256(data), sha)
        assertArrayEquals(data, dest().readBytes())
        assertEquals(listOf("Hopline-2.3.apk"), leftovers())
        assertTrue(tempSeen)
        assertEquals(data.size.toLong(), seen.last())
        assertEquals("progress only ever goes up", seen.sorted(), seen)
        assertTrue(BlobRules.isTemp("~Hopline-2.3.apk"))
    }

    @Test fun `a new download replaces an old file of the same name`() {
        dest().parentFile!!.mkdirs(); dest().writeBytes(bytes(10))
        val data = bytes(5_000)
        assertEquals(sha256(data), Fetch.store(ByteArrayInputStream(data), dest(), 1_000_000, -1, Fetch.Session(), Long.MAX_VALUE) {})
        assertArrayEquals(data, dest().readBytes())
    }

    @Test fun `a file bigger than allowed stops, and leaves nothing behind`() {
        val p = problem { Fetch.store(ByteArrayInputStream(bytes(300_000)), dest(), 250_000, -1, Fetch.Session(), Long.MAX_VALUE) {} }
        assertEquals("too_big", p?.why); assertTrue(p!!.permanent)
        assertEquals(emptyList<String>(), leftovers())
        // Exactly the limit is still allowed.
        Fetch.store(ByteArrayInputStream(bytes(250_000)), dest(), 250_000, -1, Fetch.Session(), Long.MAX_VALUE) {}
        assertEquals(250_000L, dest().length())
    }

    @Test fun `a download cut short is not kept`() {
        val p = problem { Fetch.store(ByteArrayInputStream(bytes(4_000)), dest(), 1_000_000, 5_000, Fetch.Session(), Long.MAX_VALUE) {} }
        assertEquals("incomplete", p?.why); assertFalse(p!!.permanent)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun `a failed download does not take an earlier good file with it`() {
        dest().parentFile!!.mkdirs(); dest().writeBytes(bytes(10))
        assertNotNull(problem { Fetch.store(ByteArrayInputStream(bytes(4_000)), dest(), 1_000_000, 5_000, Fetch.Session(), Long.MAX_VALUE) {} })
        assertEquals(listOf("Hopline-2.3.apk"), leftovers())
        assertEquals(10L, dest().length())
    }

    @Test fun `a cancelled download stops at the next block and leaves nothing behind`() {
        val s = Fetch.Session()
        var blocks = 0
        val p = problem { Fetch.store(ByteArrayInputStream(bytes(500_000)), dest(), 1_000_000, 500_000, s, Long.MAX_VALUE) { if (++blocks == 2) s.abort() } }
        assertEquals("cancelled", p?.why)
        assertEquals(2, blocks)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun `a socket closed under a cancelled download is the cancel, not a network fault`() {
        val s = Fetch.Session()
        val closing = object : InputStream() {
            override fun read(): Int = throw IOException("Socket closed")
            override fun read(b: ByteArray, off: Int, len: Int): Int { s.abort(); throw IOException("Socket closed") }
        }
        assertEquals("cancelled", problem { Fetch.store(closing, dest(), 1_000_000, -1, s, Long.MAX_VALUE) {} }?.why)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun `a download that runs past its deadline stops and leaves nothing behind`() {
        val p = problem { Fetch.store(ByteArrayInputStream(bytes(4_000)), dest(), 1_000_000, 4_000, Fetch.Session(), System.currentTimeMillis() - 1) {} }
        assertEquals("timeout", p?.why); assertFalse(p!!.permanent)
        assertEquals(emptyList<String>(), leftovers())
    }

    @Test fun `a download from anywhere but an allowed https address never opens a connection`() {
        // Refused from the address alone: no name is looked up, no socket opened, nothing written.
        for (bad in listOf("http://github.com/ForPublicOrg/hopline/releases/download/v2.3/Hopline.apk", "https://example.com/Hopline.apk")) {
            assertEquals(bad, "bad_host", problem { Fetch.download(bad, dest(), 1_000_000, Update::allowedHost) }?.why)
        }
        assertFalse(dest().exists())
    }
}
