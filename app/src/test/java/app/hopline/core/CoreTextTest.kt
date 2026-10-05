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

    @Test fun `a page built to stall the reader is read in one walk`() {
        // A few KB gzipped: a tag opened 300 000 times and never closed. The patterns this replaced
        // searched the rest of the page again for every one of them — hours of a helper's phone.
        fun quick(what: String, html: String) {
            val t0 = System.nanoTime()
            val page = WebText.extract(html, "https://example.org/")
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("$what took $ms ms", ms < 1_000)
            assertTrue(what, page.links.size <= WebText.MAX_LINKS)
        }
        quick("forms", "<form>".repeat(300_000))
        // Under the size cap too, every other shape that made the patterns search again:
        val n = WebText.MAX_HTML / 12
        quick("tags that never end", "<a <li <p ".repeat(n / 3))
        quick("quotes that never close", "<a href=\"x <form a='".repeat(n / 2))
        quick("links that never close", "<a href=\"/x\">".repeat(n / 2) + "</a>")
        quick("comments that never close", "<!--".repeat(n * 3))
        quick("titles, articles, bodies", "<title><article><body><h1><sup><ul>".repeat(n / 3))
        quick("meta tags", "<meta content=\"".repeat(n / 2) + "<meta name=\"description\" ".repeat(n / 4))
        quick("role=main", "<div role=\"main\" ".repeat(n / 2) + "</div>")
        quick("entities", "&amp;&#8217;&nbsp;".repeat(n / 2))
    }

    @Test fun `a page that still takes too long is given up on, as one that can't be read`() {
        val page = "<form>".repeat(300_000)
        try { WebText.extract(page, "https://example.org/", timeLimitMs = 0); fail("read anyway") } catch (e: WebText.TooComplex) { }
        // and when the request is called off (its thread interrupted), the reading stops too
        Thread.currentThread().interrupt()
        try { WebText.extract(page, "https://example.org/"); fail("read anyway") } catch (e: WebText.TooComplex) {
        } finally { Thread.interrupted() }
        // an ordinary page is not affected
        assertTrue(WebText.extract("<p>${"Plain words. ".repeat(40)}</p>", "https://example.org/").text.startsWith("Plain words."))
    }

    @Test fun `only the start of a huge page is read`() {
        val html = "<html><body><p>The start of the page, which is read.</p>" + " ".repeat(WebText.MAX_HTML) +
            "<p>The end of the page, which is never read.</p></body></html>"
        val page = WebText.extract(html, "https://example.org/")
        assertTrue(page.text.contains("The start of the page"))
        assertFalse(page.text.contains("never read"))
        // …and says so where its text stops: at the end of its last part
        assertTrue(page.cut)
        val parts = WebText.parts(page, 2_000)
        assertTrue(parts.last().endsWith(WebText.CUT_NOTE))
        assertEquals(1, parts.count { it.contains(WebText.CUT_NOTE) })
        // a page read whole says nothing of the kind
        val small = WebText.extract("<p>${"Plain words. ".repeat(40)}</p>", "https://example.org/")
        assertFalse(small.cut)
        assertFalse(WebText.parts(small, 2_000).last().contains(WebText.CUT_NOTE))
    }

    @Test fun `a script or a comment open where a huge page is cut is not read out as words`() {
        val words = "<p>${"Words of the article, read as they should be. ".repeat(40)}</p>"
        // a script whose end is past the cut
        val script = "<html><body>$words" + " ".repeat(WebText.MAX_HTML - 30_000) +
            "<script>var trackingCode = \"" + "x".repeat(60_000) + "\";</script><p>Past the cut.</p></body></html>"
        WebText.extract(script, "https://example.org/").let { p ->
            assertTrue(p.text.contains("Words of the article"))
            assertFalse("the script's code", p.text.contains("trackingCode") || p.text.contains("xxxx"))
        }
        // the same with a style, and with a comment
        val style = "<html><body>$words" + " ".repeat(WebText.MAX_HTML - 30_000) + "<style>.ad{color:red}" + ".b{}".repeat(20_000) + "</style></body></html>"
        assertFalse(WebText.extract(style, "https://example.org/").text.contains("color:red"))
        val comment = "<html><body>$words" + " ".repeat(WebText.MAX_HTML - 30_000) + "<!-- old menu, hidden" + " x".repeat(30_000) + " --></body></html>"
        assertFalse(WebText.extract(comment, "https://example.org/").text.contains("old menu"))
        // and a tag cut in half is not read as words either
        val half = "<html><body>$words" + " ".repeat(WebText.MAX_HTML - words.length - 30) + "<a href=\"https://example.org/a-long-way-off\">far</a>"
        assertFalse(WebText.extract(half, "https://example.org/").text.contains("href"))
        // a page that fits is read as it always was: a script left open there is that page's own doing
        val whole = "<p>${"Plain words. ".repeat(40)}</p><script>never closed"
        assertTrue(WebText.extract(whole, "https://example.org/").text.contains("never closed"))
    }

    @Test fun `a page whose download stopped short is read as the start it is, however short`() {
        val words = "<p>${"Words of the article, read as they should be. ".repeat(40)}</p>"
        // A friend's data budget ran out inside a script: far under MAX_HTML, and the script's end never came
        val script = "<html><body>$words" + " ".repeat(300_000) + "<script>var trackingCode = \"" + "x".repeat(20_000)
        assertTrue(script.length < WebText.MAX_HTML)
        WebText.extract(script, "https://example.org/", cutShort = true).let { p ->
            assertTrue(p.text.contains("Words of the article"))
            assertFalse("the script's code", p.text.contains("trackingCode") || p.text.contains("xxxx"))
            assertTrue(p.cut)
            assertTrue(WebText.parts(p, 2_000).last().endsWith(WebText.CUT_NOTE))
        }
        // stopped mid-sentence: the words up to there, and then the note that the page goes on
        WebText.extract("<html><body>$words<p>The pass is open until", "https://example.org/", cutShort = true).let { p ->
            assertTrue(p.text, p.text.endsWith("The pass is open until"))
            assertTrue(WebText.parts(p, 2_000).last().endsWith("The pass is open until\n\n" + WebText.CUT_NOTE))
        }
        // stopped inside a tag, or inside a comment: neither is read as words
        assertFalse(WebText.extract("<html><body>$words<a href=\"https://example.org/a-long", "https://example.org/", cutShort = true).text.contains("href"))
        assertFalse(WebText.extract("<html><body>$words<!-- old menu, hidden", "https://example.org/", cutShort = true).text.contains("old menu"))
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

    @Test fun `a phone on an IPv6-only mobile network can still reach the public internet, and only that`() {
        fun ip(s: String) = InetAddress.getByName(s)
        // What such a network's DNS answers for an IPv4-only site: the site's address behind the carrier's 64:ff9b:: gateway.
        for (good in listOf("64:ff9b::14cf:4955", "64:ff9b::808:808")) assertTrue(good, SafeUrl.isPublic(ip(good)))
        assertTrue(SafeUrl.allPublic(arrayOf(ip("64:ff9b::14cf:4955"), ip("20.207.73.85"))))
        // The same gateway must not become a way into a private network.
        for (bad in listOf("64:ff9b::a00:1", "64:ff9b::c0a8:101", "64:ff9b::7f00:1", "64:ff9b::6440:1", "64:ff9b::a9fe:101", "64:ff9b::",
                "64:ff9b:1::808:808", "64:ff9b:0:1::808:808", "64::808:808")) assertFalse(bad, SafeUrl.isPublic(ip(bad)))
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
