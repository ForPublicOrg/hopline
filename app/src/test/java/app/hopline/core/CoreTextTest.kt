package app.hopline.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

/** The pure engines behind shared internet and names, tested on real saved responses. */
class CoreTextTest {
    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)

    // ---------------------------------------------------------------- reading pages

    @Test fun `a real article comes out as readable text with numbered links`() {
        val page = WebText.extract(fixture("wikipedia_rohtang.html"), "https://en.wikipedia.org/wiki/Rohtang_Pass")
        assertTrue(page.title, page.title.contains("Rohtang"))
        assertFalse("thin", page.thin)
        assertTrue(page.text.contains("Pir Panjal"))
        assertFalse("scripts never leak into the text", page.text.contains("function("))
        val lt = page.text.indexOf('<')
        assertTrue("markup leaked: " + (if (lt >= 0) page.text.substring(maxOf(0, lt - 80), minOf(page.text.length, lt + 80)) else ""), lt < 0)
        assertTrue(page.links.isNotEmpty()); assertTrue(page.links.size <= WebText.MAX_LINKS)
        assertTrue(page.links.all { it.url.startsWith("https://") })
        assertTrue(Regex("\\[\\d+]").containsMatchIn(page.text))
        assertFalse("citation marks are dropped", Regex("\\[ \\d+ ]").containsMatchIn(page.text))
        assertFalse("section edit links are dropped", page.text.lines().any { it.trim().startsWith("edit") })
        assertFalse(page.text.contains("﻿"))
        assertFalse("tab menus are dropped", page.text.contains("Talk ["))
    }

    @Test fun `the article wins over the page chrome and entities are decoded`() {
        val html = """<html><head><title>Fallback</title><meta property="og:title" content="Road status &amp; weather"></head>
            <body><nav>Home | About | Login</nav><header>Site header</header>
            <article><h2>Is the pass open?</h2><p>Yes &mdash; the pass opened at 9&nbsp;am. It&#8217;s busy, so leave early &hellip;</p>
            <p>Permits are checked at the gate. See <a href="/permits">the permit page</a> and <a href="javascript:alert(1)">this</a>.</p>
            <p>""" + "Plenty of real words about the road conditions and snow clearing work done this week. ".repeat(10) + """</p></article>
            <aside>Advertisement</aside><footer>© 2026</footer><script>var x = "<p>nope</p>";</script></body></html>"""
        val page = WebText.extract(html, "https://example.org/news/pass")
        assertEquals("Road status & weather", page.title)
        assertTrue(page.text.contains("# Is the pass open?"))
        assertTrue(page.text.contains("Yes — the pass opened at 9 am. It’s busy, so leave early …"))
        assertFalse(page.text.contains("Home | About")); assertFalse(page.text.contains("Advertisement")); assertFalse(page.text.contains("nope"))
        assertEquals(listOf("https://example.org/permits"), page.links.map { it.url })
        assertTrue(page.text.contains("the permit page [1]"))
    }

    @Test fun `a link around a whole news card numbers its headline`() {
        val html = "<html><body><main>" + "<p>${"Some words about the day. ".repeat(20)}</p>" +
            "<a href=\"/news/g7\"><div><h3>G7 to release oil reserves</h3><p>A statement says reserves would be released.</p></div></a></main></body></html>"
        val page = WebText.extract(html, "https://www.example.com/")
        assertTrue(page.text, page.text.contains("G7 to release oil reserves [1]"))
        assertFalse(page.text, page.text.lines().any { it.trim() == "[1]" })
    }

    @Test fun `a script-built page is reported as one, not as an empty success`() {
        val html = """<html><head><title>App</title><meta name="description" content="Live train status for all of India."></head>
            <body><div id="root"></div><script src="app.js"></script></body></html>"""
        val page = WebText.extract(html, "https://trains.example")
        assertTrue(page.thin)
        assertTrue(page.text.contains("Live train status"))
    }

    @Test fun `the charset comes from the header, then the page, then a sensible guess`() {
        val cyr = "Привет".toByteArray(charset("windows-1251"))
        assertEquals("Привет", WebText.decode(cyr, "text/html; charset=windows-1251"))
        val meta = "<meta charset=\"windows-1251\">".toByteArray() + cyr
        assertTrue(WebText.decode(meta, "text/html").endsWith("Привет"))
        assertEquals("café", WebText.decode("café".toByteArray(charset("windows-1252")), "text/html"))
        assertEquals("ok ✓", WebText.decode("ok ✓".toByteArray(), null))
    }

    @Test fun `long text is split at paragraph boundaries the same way every time`() {
        val text = (1..50).joinToString("\n\n") { "Paragraph $it " + "word ".repeat(40) }
        val a = WebText.parts(text, 2_000); val b = WebText.parts(text, 2_000)
        assertEquals(a, b)
        assertTrue(a.size > 1); assertTrue(a.all { it.length <= 2_000 })
        assertEquals(text.replace("\n\n", " ").replace(" ", ""), a.joinToString("").replace("\n\n", " ").replace(" ", ""))
    }

    // ---------------------------------------------------------------- weather

    @Test fun `weather reads like a forecast and credits its source`() {
        val (title, body) = Weather.format(fixture("weather.json"), "near you")
        assertTrue(title, title.startsWith("Weather near you · 1891 m"))
        assertTrue(body, body.startsWith("Now: "))
        assertTrue(body.contains("Today: ")); assertTrue(body.contains("Tomorrow: "))
        assertTrue(body.contains("Sunrise "))
        assertTrue(body.endsWith(Weather.ATTRIBUTION))
        assertTrue(Weather.url(32.2432, 77.1892).startsWith("https://api.open-meteo.com/v1/forecast?latitude=32.243&longitude=77.189"))
    }

    // ---------------------------------------------------------------- search

    @Test fun `web results are read from DuckDuckGo lite`() {
        val r = Search.parseDdgLite(fixture("ddg_lite.html"))
        assertTrue(r.size >= 5)
        assertEquals("https://manali.fyi/", r[0].url)
        assertTrue(r[0].title.contains("Rohtang"))
        assertTrue(r[0].snippet.contains("open/closed status"))
        assertTrue(r.all { it.url.startsWith("http") && !it.title.contains("<") })
        assertTrue(Search.parseDdgLite("<html>anomaly-modal</html>").isEmpty())
    }

    @Test fun `instant answers and Wikipedia are keyless fallbacks`() {
        val ia = Search.parseDdgInstant(fixture("ddg_ia.json"))!!
        assertTrue(ia.summary.contains("Pir Panjal"))
        val wiki = Search.parseWiki(fixture("wiki.json"), "en")
        assertTrue(wiki.isNotEmpty())
        assertEquals("https://en.wikipedia.org/wiki/Rohtang_Pass", wiki[0].url)
        assertFalse(wiki[0].snippet.contains("searchmatch"))
        val text = Search.format("rohtang pass", ia.summary, wiki)
        assertTrue(text.contains("1. Rohtang Pass — en.wikipedia.org"))
    }

    // ---------------------------------------------------------------- what a helper will fetch

    @Test fun `links are pulled out of whatever people paste`() {
        assertEquals("https://example.org/a?b=1", SafeUrl.fromInput("example.org/a?b=1"))
        assertEquals("https://example.org/x", SafeUrl.fromInput("HTTP://example.org/x"))
        assertEquals("https://news.example.com/story", SafeUrl.fromInput("Look at this https://news.example.com/story."))
        assertEquals("https://httpbin.org/get", SafeUrl.fromInput("httpbin.org/get"))
        assertNull(SafeUrl.fromInput("is the pass open today"))
        assertNull(SafeUrl.fromInput("file:///etc/passwd"))
        assertTrue(SafeUrl.looksLikeUrl("bbc.com")); assertFalse(SafeUrl.looksLikeUrl("weather in manali"))
    }

    @Test fun `a helper never fetches from a private network`() {
        assertNull(SafeUrl.check("https://user:pw@example.org/"))
        assertNull(SafeUrl.check("https://example.org:8443/"))
        assertNull(SafeUrl.check("https://printer.local/"))
        assertNull(SafeUrl.check("https://localhost/"))
        assertNull(SafeUrl.check("http://example.org/"))
        assertNotNull(SafeUrl.check("http://example.org/", allowHttp = true))
        fun ip(s: String) = InetAddress.getByName(s)
        for (bad in listOf("127.0.0.1", "10.1.2.3", "192.168.1.1", "172.16.0.9", "169.254.1.1", "100.64.0.1", "0.0.0.0", "224.0.0.1",
                "::1", "fe80::1", "fd00::1", "::ffff:192.168.0.1")) assertFalse(bad, SafeUrl.isPublic(ip(bad)))
        for (good in listOf("8.8.8.8", "142.250.183.14", "2606:4700:4700::1111")) assertTrue(good, SafeUrl.isPublic(ip(good)))
        assertFalse(SafeUrl.allPublic(arrayOf(ip("8.8.8.8"), ip("10.0.0.1"))))
    }

    // ---------------------------------------------------------------- texts home

    @Test fun `texts are folded to plain SMS characters and counted honestly`() {
        val t = SmsText.toGsm("I’m OK — reached camp… “all fine”")
        assertEquals("I'm OK - reached camp... \"all fine\"", t)
        assertTrue(SmsText.isGsm(t)); assertEquals(1, SmsText.segments(t))
        assertEquals(2, SmsText.segments("x".repeat(161)))
        assertEquals(2, SmsText.segments("मैं ठीक हूँ ".repeat(7)))
        assertEquals(17, SmsText.units("Price: 5€ [ok]"))       // €, [ and ] cost two each
        assertEquals(160, SmsText.capacity("hi")); assertEquals(306, SmsText.capacity("x".repeat(161)))
        assertEquals(70, SmsText.capacity("ठीक"))
        assertEquals("+919812345678", SmsText.normaliseNumber("+91 98123-45678"))
        assertEquals("+919812345678", SmsText.normaliseNumber("0091 9812345678"))
        assertEquals("9812345678", SmsText.normaliseNumber("98123 45678"))
        assertNull(SmsText.normaliseNumber("56767"))            // short codes cost money
        assertNull(SmsText.normaliseNumber("Mum"))
        assertTrue(SmsText.looksLikeEmail("asha@example.com")); assertFalse(SmsText.looksLikeEmail("asha@"))
    }

    // ---------------------------------------------------------------- names

    @Test fun `names are cleaned without breaking emoji or scripts`() {
        assertEquals("Asha K", Names.clean("  Asha \n\t K  "))
        assertEquals("alice", Names.clean("‮alice‬"))
        assertEquals("👨‍👩‍👧 Trek", Names.clean("👨‍👩‍👧 Trek"))
        assertEquals("विकास", Names.clean("विकास"))
        val long = Names.clean("😀".repeat(100), 10)
        assertEquals(10, long.codePointCount(0, long.length))
        assertEquals("", Names.clean("\u0000\u0007"))
    }

    // ---------------------------------------------------------------- pacing

    @Test fun `one asker can't run a helper's phone ragged`() {
        var now = 0L
        val l = HelperLimits { now }
        repeat(HelperLimits.FETCHES_PER_10_MIN) { assertNull(l.check("asha", false)); l.started("asha", false); l.finished(false) }
        assertEquals("limit", l.check("asha", false))
        assertNull(l.check("ravi", false))                      // someone else is fine
        now += 11 * 60_000L
        assertNull(l.check("asha", false))
        l.started("x", false); l.started("y", false)
        assertEquals("busy", l.check("z", false))
        repeat(HelperLimits.TEXTS_PER_HOUR) { l.started("asha", true) }
        assertEquals("limit", l.check("asha", true))
    }
}
