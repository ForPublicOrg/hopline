package app.hopline.service

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** How a helper reads a page body: never past its data budget, never for hours. */
class FetchTest {
    private fun gzip(text: String): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    @Test fun `a gzip page bigger than the data budget gives its beginning instead of failing`() {
        val words = (1..60_000).joinToString(" ") { "w${(it * 7919) % 100_003}" }
        val zipped = gzip(words)
        val budget = zipped.size / 4
        val wire = Fetch.CountingStream(ByteArrayInputStream(zipped), budget)
        val body = Fetch.readCapped(GZIPInputStream(wire), wire, 2_000_000, Fetch.Session(), Long.MAX_VALUE)
        assertTrue(body.isNotEmpty())
        assertTrue(String(body).startsWith("w7919 "))
        assertEquals(budget, wire.count)
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
}
