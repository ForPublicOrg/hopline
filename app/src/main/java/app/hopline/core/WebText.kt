package app.hopline.core

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A web page, boiled down to what a person on one bar of signal actually wants: the title, the
 * article's words, and its links as numbered references they can ask for next. Pure Kotlin with
 * no Android imports, so it is tested against saved pages on a laptop.
 *
 * Deliberately not a browser: no scripts, no images, no layout. A page that only exists after
 * JavaScript runs comes back as an honest "needs a full browser" instead of a menu dump.
 *
 * The page comes from a stranger's server, on a friend's request, and is read on a helper's
 * phone: it may be built to stall the reader. So it is read in one walk ([Scan]) with nothing
 * that searches the rest of the page again for every tag; only the first [MAX_HTML] characters
 * are read; and a page still not read after [MAX_MS] is given up on as [TooComplex].
 */
object WebText {

    class Link(val text: String, val url: String)

    class Page(
        val title: String,
        val text: String,          // paragraphs separated by blank lines; "# " marks a heading; "[n]" marks link n
        val links: List<Link>,     // 1-based in the text: [1] is links[0]
        val description: String,
        val thin: Boolean,         // too little readable text survived — probably a script-built page
        val cut: Boolean = false,  // the page goes on past what is read ([MAX_HTML], or where the download stopped): the text is its start only
    )

    /** A page this phone can't read in time — built to stall it, or just too tangled. Asking again won't change that. */
    class TooComplex : Exception("This page is too big or too tangled to read here.")

    const val MAX_LINKS = 40
    /** Characters of a page that are read; any article worth reading on one bar of signal starts well before. */
    const val MAX_HTML = 400_000
    /** How long reading one page may take before it counts as [TooComplex]. */
    const val MAX_MS = 3_000L
    private val BLOCK_OPEN = Regex("(?i)<(h[1-6]|p|div|li)(?=[\\s/>])")
    private val BLOCK_CLOSE = Regex("(?i)</(h[1-6]|p|div|li)\\s*>")
    private val LINK_OPEN = Regex("(?is)<a\\s")
    private const val MIN_USEFUL = 200

    // ------------------------------------------------------------------ charset

    /** Bytes to text: the header's charset, else a <meta charset>, else UTF-8 (falling back to windows-1252). */
    fun decode(bytes: ByteArray, contentType: String?): String {
        charsetOf(contentType)?.let { cs -> return String(bytes, cs) }
        val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1)
        val meta = Regex("""(?i)<meta[^>]+charset\s*=\s*["']?\s*([a-zA-Z0-9_\-:.]+)""").find(head)?.groupValues?.get(1)
        meta?.let { name -> runCatching { Charset.forName(name) }.getOrNull()?.let { return String(bytes, it) } }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: Exception) {
            String(bytes, runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1))
        }
    }

    fun charsetOf(contentType: String?): Charset? {
        val name = contentType?.let { Regex("""(?i)charset\s*=\s*["']?([a-zA-Z0-9_\-:.]+)""").find(it)?.groupValues?.get(1) } ?: return null
        return runCatching { Charset.forName(name) }.getOrNull()
    }

    // ------------------------------------------------------------------ extraction

    /** What is dropped with everything inside it: never words a person reads. */
    private val HIDDEN = listOf("script", "style", "noscript", "svg", "template", "iframe", "object", "canvas", "select", "button", "form", "head")
    /** Of those, the ones whose insides are code: one left open where a page is cut would be read out as text. */
    private val CODE = listOf("script", "style")
    /** The page's chrome around the article. */
    private val CHROME = listOf("nav", "header", "footer", "aside", "figure", "menu", "dialog")
    /** Tags that start a new paragraph, opening or closing. */
    private val BLOCKS = listOf("p", "div", "section", "article", "main", "ul", "ol", "table", "tr", "blockquote", "pre", "h4", "h5", "h6", "dl", "dt", "dd")

    /**
     * The page as text. [timeLimitMs]: how long it may take ([TooComplex] after that, or when the
     * thread is interrupted — the request was called off). [cutShort]: [page] is only the start of
     * the page — the download stopped at the helper's data budget, its size cap or its clock — and
     * is read like a page longer than [MAX_HTML], which it may well be.
     */
    fun extract(page: String, baseUrl: String, timeLimitMs: Long = MAX_MS, cutShort: Boolean = false): Page {
        val d = Deadline(timeLimitMs)
        val cut = cutShort || page.length > MAX_HTML
        val html = if (cut) start(page, d) else page
        val whole = Scan(html, d)
        val title = clean(meta(whole, "og:title") ?: tagText(whole, "title") ?: tagText(whole, "h1") ?: "").take(200)
        val description = clean(meta(whole, "og:description") ?: meta(whole, "description") ?: "").take(400)

        var body = dropComments(html, d)
        // Cut short: a script or a style may be open where the page was cut, its end in what isn't
        // read. Left in, its code would come out as words; on the whole page all of it is hidden.
        if (cut) body = Scan(body, d).firstUnclosed(CODE).let { if (it >= 0) body.substring(0, it) else body }
        body = Scan(body, d).replaceElements(HIDDEN) { " " }
        // The biggest <article>, else <main>/role=main, else the whole body — the article wins over the chrome.
        body = mainPart(body, d)
        body = Scan(body, d).replaceElements(CHROME) { " " }
        // Citation marks ("[12]") are noise without the footnotes they point to.
        body = Scan(body, d).let { sc ->
            sc.replaceElements(listOf("sup")) { el ->
                val inner = sc.inner(el)
                val t = stripTags(inner, d).trim()
                if (t.startsWith("[") && t.length <= 12) " " else inner
            }
        }
        // A list whose every item is one short link is a menu (tabs, "share", language pickers).
        body = Scan(body, d).let { sc ->
            sc.replaceElements(listOf("ul", "ol")) { el ->
                val list = Scan(sc.inner(el), d)
                val items = list.findElements(listOf("li")).map { list.inner(it) }
                val menu = items.size >= 2 && items.all { LINK_OPEN.containsMatchIn(it) && stripTags(it, d).trim().length <= 25 }
                if (menu) " " else sc.whole(el)
            }
        }

        val links = ArrayList<Link>()
        val seen = HashMap<String, Int>()
        body = Scan(body, d).replaceLinks { href, inner ->
            val label = clean(stripTags(inner, d))
            val url = absolute(decodeEntities(href).trim(), baseUrl)
            if (label.isEmpty() || url == null || links.size >= MAX_LINKS && url !in seen) return@replaceLinks " $inner "
            val n = seen.getOrPut(url) { links.add(Link(label.take(120), url)); links.size }
            // A link wrapping a whole news card (headline + summary): the number goes on the
            // headline, not on a line of its own after the card.
            val end = if (BLOCK_OPEN.containsMatchIn(inner)) BLOCK_CLOSE.find(inner) else null
            if (end != null) " ${inner.substring(0, end.range.first)} [$n]${inner.substring(end.range.first)} " else " $inner [$n] "
        }

        body = Scan(body, d).let { sc -> sc.replaceElements(listOf("h1", "h2", "h3")) { el -> "\n\n\u0001${sc.inner(el)}\n\n" } }
        body = Scan(body, d).replaceTags("\n• ") { sc, i -> sc.tagNamed(i, "li") }
        body = body.replace(Regex("(?i)<br\\s*/?>"), "\n")
        body = Scan(body, d).replaceTags("\n\n") { sc, i -> sc.block(i) }
        body = body.replace(Regex("(?i)</t[dh]\\s*>"), " · ")
        body = stripTags(body, d)
        body = decodeEntities(body).replace(INVISIBLE, "")

        val out = StringBuilder()
        var lastLine = ""
        for (raw in body.split(Regex("\\n\\s*\\n+"))) {
            var para = raw.replace(Regex("[ \\t\\x0B\\f\\r\\u00A0]+"), " ").lines().joinToString("\n") { it.trim() }.trim()
            para = para.replace(Regex("\\n{2,}"), "\n").replace(Regex("•\\s*\\n\\s*"), "• ").trim(' ', '·')
            para = para.replace(Regex(" +([.,;:!?)])"), "$1")
            if (para.isEmpty() || boilerplate(para)) continue
            val heading = para.startsWith("\u0001")
            para = para.removePrefix("\u0001").trim()
            if (para.isEmpty() || para == lastLine) continue
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(if (heading) "# $para" else para)
            lastLine = para
        }
        var text = out.toString()
        val useful = text.replace(Regex("\\[\\d+]"), "").count { it.isLetterOrDigit() }
        val thin = useful < MIN_USEFUL
        if (thin && description.isNotEmpty() && !text.contains(description)) text = if (text.isEmpty()) description else "$description\n\n$text"
        return Page(title.ifEmpty { hostOf(baseUrl) }, text, links, description, thin, cut)
    }

    /**
     * The part of a page cut short that is read: its first [MAX_HTML] characters (all there are,
     * when the download stopped sooner), but never ending inside a tag (half a tag would be read
     * as words), and ending before a comment the cut leaves open — on the whole page it hides
     * everything up to its end, which isn't read.
     */
    private fun start(page: String, d: Deadline): String {
        var end = minOf(page.length, MAX_HTML)
        if (end > 0 && Character.isHighSurrogate(page[end - 1])) end--
        val lt = page.lastIndexOf('<', end - 1)
        if (lt > page.lastIndexOf('>', end - 1)) end = lt
        // The comments as dropComments walks them: the first with no end after it is where reading stops.
        var at = page.indexOf("<!--")
        while (at in 0 until end) {
            d.tick()
            val close = page.indexOf("-->", at + 4).takeIf { it >= 0 && it + 3 <= end }
            if (close == null) { end = at; break }
            at = page.indexOf("<!--", close + 3)
        }
        return page.substring(0, end)
    }

    /** Every `<!-- … -->` as a space. A comment left open hides nothing after it. */
    private fun dropComments(s: String, d: Deadline): String {
        var at = s.indexOf("<!--")
        if (at < 0) return s
        val out = StringBuilder(s.length)
        var last = 0
        while (at >= 0) {
            d.tick()
            val end = s.indexOf("-->", at + 4)
            if (end < 0) break
            out.append(s, last, at).append(' ')
            last = end + 3
            at = s.indexOf("<!--", last)
        }
        return out.append(s, last, s.length).toString()
    }

    private fun mainPart(html: String, d: Deadline): String {
        val sc = Scan(html, d)
        val articles = sc.findElements(listOf("article")).map { sc.inner(it) }
        val best = articles.maxByOrNull { stripTags(it, d).length }
        if (best != null && stripTags(best, d).trim().length > 400) return best
        sc.firstElement(listOf("main"))?.let { val inner = sc.inner(it); if (stripTags(inner, d).trim().length > 200) return inner }
        sc.roleMain()?.let { if (stripTags(it, d).trim().length > 200) return it }
        return sc.body() ?: html
    }

    private val BOILER = Regex("(?i)^(accept( all)?( cookies)?|cookie (settings|policy|preferences)|we use cookies.*|subscribe( now)?|sign (in|up)|log ?in|register|share( this)?( on .*)?|follow us.*|advertisement|sponsored|skip to (main )?content|back to top|read more|menu|close|search)\\.?$")
    private val EDIT = Regex("(?i)^\\[?\\s*edit\\s*]?(\\s*\\[\\d+])?$")
    private val INVISIBLE = Regex("[\\uFEFF\\u200B\\u00AD\\u2060]")
    private fun boilerplate(p: String): Boolean {
        if (p.length >= 80) return false
        val flat = p.replace('\n', ' ').trim()
        return BOILER.matches(flat) || EDIT.matches(flat) || flat == "•"
    }

    /** A <meta> tag's content by its property or name, either order. */
    private fun meta(sc: Scan, name: String): String? =
        (sc.metaContentAfter(name) ?: sc.metaContentBefore(name))?.let { decodeEntities(it) }?.takeIf { it.isNotBlank() }

    /** The words of the first <[tag]>…</[tag]>. */
    private fun tagText(sc: Scan, tag: String): String? =
        sc.firstElement(listOf(tag))?.let { decodeEntities(stripTags(sc.inner(it), sc.d)) }?.takeIf { it.isNotBlank() }

    /** Every tag as a space. A tag that never ends (no '>', or a quote left open) is left as text. */
    private fun stripTags(s: String, d: Deadline): String = Scan(s, d).replaceTags(" ") { sc, i -> sc.anyTag(i) }

    private fun clean(s: String): String = decodeEntities(s).replace(Regex("\\s+"), " ").trim()

    /** Only links someone could ask a helper to read next: absolute http(s), no fragments-only. */
    fun absolute(href: String, base: String): String? {
        if (href.isEmpty() || href.startsWith("#")) return null
        val lower = href.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("data:")) return null
        return try {
            val u = java.net.URI(base).resolve(java.net.URI(href.replace(" ", "%20")))
            val s = u.toString().substringBefore('#')
            if (u.scheme?.lowercase() in setOf("http", "https") && !u.host.isNullOrEmpty() && s.length <= 2048) s else null
        } catch (e: Exception) { null }
    }

    fun hostOf(url: String): String = try { java.net.URI(url).host?.removePrefix("www.") ?: url } catch (e: Exception) { url }

    // ------------------------------------------------------------------ reading tags, in one walk

    /** The time a page may take, checked as the walk goes — and a request called off (the thread interrupted) stops it too. */
    private class Deadline(ms: Long) {
        private val end = System.nanoTime() + ms * 1_000_000L
        private var steps = 0

        fun tick() {
            if (++steps and 1023 == 0 && (Thread.interrupted() || System.nanoTime() - end > 0)) throw TooComplex()
        }
    }

    /** One element found by [Scan]: `<` at [start], its inside from [open] to [close], and [end] just past `</name>`. */
    private class El(val start: Int, val open: Int, val close: Int, val end: Int)

    /**
     * One walk over [s], finding tags and elements as HTML-ish text has them: a tag runs from '<'
     * to the first '>' outside a quoted value (a quote left open ends nothing), and an element
     * from its tag to the first `</name>` after it. Each answer is what the patterns this replaced
     * gave (names in either case of ASCII, whitespace as `\s`), found without searching the rest
     * of the page again for every tag: where a tag starting at a place ends is remembered for
     * that place ([tagEnd]), and per tag name, from where on no closing tag exists ([closer]).
     * So a page of 300 000 tags that never close costs one walk, not 300 000.
     */
    private class Scan(val s: String, val d: Deadline) {
        val n = s.length
        /** [tagEnd] per place reached outside quotes: 0 not known yet, 1 none, else the end + 2. */
        private var ends: IntArray? = null
        /** Per tag name: no closing tag of it starts at or after this place. */
        private val noCloser = HashMap<String, Int>()

        fun inner(el: El): String = s.substring(el.open, el.close)
        fun whole(el: El): String = s.substring(el.start, el.end)

        /** [name] (lower case) at [i], ASCII letters in either case. */
        fun nameAt(i: Int, name: String): Boolean {
            if (i < 0 || i + name.length > n) return false
            for (k in name.indices) if (lower(s[i + k]) != name[k]) return false
            return true
        }

        fun skipSpaces(from: Int): Int {
            var k = from
            while (k < n && isSpace(s[k])) k++
            return k
        }

        /** What may follow a tag's name: a space, '/' or '>'. */
        fun nameEndsAt(k: Int): Boolean = k < n && (isSpace(s[k]) || s[k] == '/' || s[k] == '>')

        /**
         * Where a tag whose attributes start at [k] ends — just past its '>' — or -1. Quoted values
         * may hold '>', and a quote left open ends nothing. Remembered for every place the walk
         * passes outside quotes: from any of them, the answer is the same.
         */
        fun tagEnd(k: Int): Int {
            val memo = ends ?: IntArray(n + 1).also { ends = it }
            var p = k
            val result: Int
            while (true) {
                d.tick()
                if (p >= n) { p = n; result = -1; break }
                if (memo[p] != 0) { result = memo[p] - 2; break }
                val c = s[p]
                if (c == '>') { result = p + 1; break }
                if (c == '"' || c == '\'') {
                    val q = s.indexOf(c, p + 1)
                    if (q < 0) { result = -1; break }
                    p = q + 1
                } else p++
            }
            note(memo, k, p, result)
            return result
        }

        /** The same walk again, from [from] to [stop], noting [result] at every place on it. */
        private fun note(memo: IntArray, from: Int, stop: Int, result: Int) {
            var p = from
            while (p < stop) {
                memo[p] = result + 2
                val c = s[p]
                p = if (c == '"' || c == '\'') s.indexOf(c, p + 1) + 1 else p + 1
            }
            memo[stop] = result + 2
        }

        /** Just past `</name\s*>` if it starts at [j], else -1. */
        fun closerAt(j: Int, name: String): Int {
            if (j < 0 || j + 1 >= n || s[j] != '<' || s[j + 1] != '/' || !nameAt(j + 2, name)) return -1
            val k = skipSpaces(j + 2 + name.length)
            return if (k < n && s[k] == '>') k + 1 else -1
        }

        /** Where the first `</name>` at or after [from] starts, or -1 — and once there is none, that is remembered. */
        fun closer(from: Int, name: String): Int {
            val none = noCloser[name] ?: Int.MAX_VALUE
            if (from >= none) return -1
            var j = s.indexOf("</", from)
            while (j in 0 until none) {
                d.tick()
                if (closerAt(j, name) >= 0) return j
                j = s.indexOf("</", j + 1)
            }
            noCloser[name] = from
            return -1
        }

        /** Where the last `</name>` starts, or -1. */
        fun lastCloser(name: String): Int {
            var j = s.lastIndexOf("</")
            while (j >= 0) {
                d.tick()
                if (closerAt(j, name) >= 0) return j
                j = s.lastIndexOf("</", j - 1)
            }
            return -1
        }

        /** `<name …>…</name>` at [i], for the first of [names] that fits there. */
        fun element(i: Int, names: List<String>): El? {
            if (i + 1 >= n || s[i] != '<') return null
            for (name in names) {
                if (!nameAt(i + 1, name) || !nameEndsAt(i + 1 + name.length)) continue
                val open = tagEnd(i + 1 + name.length)
                if (open < 0) continue
                val close = closer(open, name)
                if (close < 0) continue
                return El(i, open, close, closerAt(close, name))
            }
            return null
        }

        fun firstElement(names: List<String>): El? {
            var i = s.indexOf('<')
            while (i >= 0) {
                d.tick()
                element(i, names)?.let { return it }
                i = s.indexOf('<', i + 1)
            }
            return null
        }

        /** Every element of [names], left to right, none inside another. */
        fun findElements(names: List<String>): List<El> {
            val out = ArrayList<El>()
            var i = s.indexOf('<')
            while (i >= 0) {
                d.tick()
                val el = element(i, names)
                if (el != null) { out.add(el); i = s.indexOf('<', el.end) } else i = s.indexOf('<', i + 1)
            }
            return out
        }

        /** [s] with every element of [names] (left to right, none inside another) replaced by what [with] makes of it. */
        fun replaceElements(names: List<String>, with: (El) -> String): String {
            var i = s.indexOf('<')
            val out = StringBuilder(n)
            var last = 0
            while (i >= 0) {
                d.tick()
                val el = element(i, names)
                if (el == null) { i = s.indexOf('<', i + 1); continue }
                out.append(s, last, el.start).append(with(el))
                last = el.end
                i = s.indexOf('<', el.end)
            }
            if (last == 0) return s
            return out.append(s, last, n).toString()
        }

        /**
         * Where the walk [replaceElements] makes over [names] first meets an element's start with no
         * end after it — one it would leave in, as text — or -1. In a page cut short, that element
         * went on past the cut, and everything from here on was inside it.
         */
        fun firstUnclosed(names: List<String>): Int {
            var i = s.indexOf('<')
            while (i >= 0) {
                d.tick()
                val el = element(i, names)
                if (el != null) { i = s.indexOf('<', el.end); continue }
                if (names.any { nameAt(i + 1, it) && nameEndsAt(i + 1 + it.length) && tagEnd(i + 1 + it.length) >= 0 }) return i
                i = s.indexOf('<', i + 1)
            }
            return -1
        }

        /** [s] with every tag [end] finds — it says where the tag at a '<' ends, or -1 — replaced by [with]. */
        fun replaceTags(with: String, end: (Scan, Int) -> Int): String {
            var i = s.indexOf('<')
            val out = StringBuilder(n)
            var last = 0
            var any = false
            while (i >= 0) {
                d.tick()
                val e = end(this, i)
                if (e < 0) { i = s.indexOf('<', i + 1); continue }
                out.append(s, last, i).append(with)
                last = e; any = true
                i = s.indexOf('<', e)
            }
            if (!any) return s
            return out.append(s, last, n).toString()
        }

        /** The end of `<name …>` at [i], or -1. */
        fun tagNamed(i: Int, name: String): Int =
            if (nameAt(i + 1, name) && nameEndsAt(i + 1 + name.length)) tagEnd(i + 1 + name.length) else -1

        /** The end of a paragraph tag, opening or closing ([BLOCKS]), at [i], or -1. */
        fun block(i: Int): Int {
            val k = if (i + 1 < n && s[i + 1] == '/') i + 2 else i + 1
            for (name in BLOCKS) {
                if (!nameAt(k, name) || !nameEndsAt(k + name.length)) continue
                val e = tagEnd(k + name.length)
                if (e >= 0) return e
            }
            return -1
        }

        /** The end of any tag at [i] — `<x`, `</x` or `<!` — or -1. */
        fun anyTag(i: Int): Int {
            if (i + 1 >= n) return -1
            var f = i + 1
            if (s[f] == '/') f++
            if (f >= n || !(s[f] in 'a'..'z' || s[f] in 'A'..'Z' || s[f] == '!')) return -1
            return tagEnd(f + 1)
        }

        // ---- links

        /** Where the last `</a>` starts: no link can end after it. -2 = not looked for yet. */
        private var lastLinkEnd = -2
        /** Per quote character: the last place it can close a link's address at ([closesLink]). -2 = not looked for yet. */
        private val lastClosing = intArrayOf(-2, -2)
        /** [winner] per place reached outside quotes, noted like [ends]. */
        private var winners: IntArray? = null

        /**
         * [s] with every link — `<a` and a space, attributes with an `href="…"` among them, `>`,
         * its inside, `</a>` — replaced by what [with] makes of its address (as written) and inside.
         */
        fun replaceLinks(with: (href: String, inner: String) -> String): String {
            lastLinkEnd = lastCloser("a")
            if (lastLinkEnd < 0) return s
            var i = s.indexOf('<')
            val out = StringBuilder(n)
            var last = 0
            while (i >= 0) {
                d.tick()
                val h = if (i + 2 < n && lower(s[i + 1]) == 'a' && isSpace(s[i + 2])) winner(i + 3) else -1
                if (h < 0) { i = s.indexOf('<', i + 1); continue }
                val y = hrefQuote(h)
                var x = s.indexOf(s[y], y + 1)
                while (x >= 0 && !closesLink(x)) { d.tick(); x = s.indexOf(s[y], x + 1) }
                if (x < 0) { i = s.indexOf('<', i + 1); continue }   // can't be: [winner] saw one
                val open = tagEnd(x + 1)
                val close = closer(open, "a")
                out.append(s, last, i).append(with(s.substring(y + 1, x), s.substring(open, close)))
                last = closerAt(close, "a")
                i = s.indexOf('<', last)
            }
            if (last == 0) return s
            return out.append(s, last, n).toString()
        }

        /** The opening quote of `href\s*=\s*["']` at [h], or -1. */
        private fun hrefQuote(h: Int): Int {
            if (!nameAt(h, "href")) return -1
            var k = skipSpaces(h + 4)
            if (k >= n || s[k] != '=') return -1
            k = skipSpaces(k + 1)
            return if (k < n && (s[k] == '"' || s[k] == '\'')) k else -1
        }

        /** Can the quote at [x] close a link's address: the tag goes on to its '>', and a `</a>` comes after? */
        private fun closesLink(x: Int): Boolean {
            val open = tagEnd(x + 1)
            return open in 0..lastLinkEnd
        }

        /** The last place a [q] quote can close a link's address at, or -1. */
        private fun lastClosingOf(q: Char): Int {
            val slot = if (q == '"') 0 else 1
            if (lastClosing[slot] == -2) {
                var x = s.lastIndexOf(q, lastLinkEnd - 1)
                while (x >= 0 && !closesLink(x)) { d.tick(); x = s.lastIndexOf(q, x - 1) }
                lastClosing[slot] = x
            }
            return lastClosing[slot]
        }

        /**
         * The first `href="…"` from [k] on, among one tag's attributes, whose quote is closed by
         * one that can end the link ([closesLink]) — or -1. Walked attribute by attribute, past
         * quoted values, up to the tag's '>'. Remembered for every place the walk passes.
         */
        private fun winner(k: Int): Int {
            val memo = winners ?: IntArray(n + 1).also { winners = it }
            var p = k
            val result: Int
            while (true) {
                d.tick()
                if (p >= n) { p = n; result = -1; break }
                if (memo[p] != 0) { result = memo[p] - 2; break }
                val y = hrefQuote(p)
                if (y >= 0 && y < lastClosingOf(s[y])) { result = p; break }
                val c = s[p]
                if (c == '>') { result = -1; break }
                if (c == '"' || c == '\'') {
                    val q = s.indexOf(c, p + 1)
                    if (q < 0) { result = -1; break }
                    p = q + 1
                } else p++
            }
            note(memo, k, p, result)
            return result
        }

        // ---- role="main"

        /**
         * The inside of the first element — any tag name of letters and digits — with
         * `role="main"` among its attributes (before any quoted value holding a '>'), or null.
         */
        fun roleMain(): String? {
            var closers: HashMap<String, Int>? = null
            // The places role="main" can be read at within one stretch up to a '>', with where the tag would end from each.
            var stretch = -2
            var at = IntArray(0); var end = IntArray(0); var least = IntArray(0); var count = 0
            var i = s.indexOf('<')
            while (i >= 0) {
                d.tick()
                var k = i + 1
                while (k < n && isAlnum(s[k])) k++
                if (k > i + 1 && nameEndsAt(k)) {
                    val g = s.indexOf('>', k).let { if (it < 0) n else it }
                    if (g != stretch) {
                        stretch = g
                        val a = ArrayList<Int>(); val e = ArrayList<Int>()
                        for (r in k until g) { d.tick(); val after = roleEnd(r); if (after >= 0) { a.add(r); e.add(tagEnd(after)) } }
                        count = a.size; at = a.toIntArray(); end = e.toIntArray()
                        // the earliest tag end from each candidate on (none: past everything)
                        least = IntArray(count + 1).also { it[count] = Int.MAX_VALUE }
                        for (t in count - 1 downTo 0) least[t] = minOf(least[t + 1], if (end[t] < 0) Int.MAX_VALUE else end[t])
                    }
                    var t = at.binarySearch(k, 0, count).let { if (it < 0) -it - 1 else it }
                    if (t < count) {
                        val name = s.substring(i + 1, k).lowercase()
                        val lastClose = (closers ?: allClosers().also { closers = it })[name] ?: -1
                        if (least[t] <= lastClose) {
                            while (end[t] !in 0..lastClose) t++
                            return s.substring(end[t], closer(end[t], name))
                        }
                    }
                }
                i = s.indexOf('<', i + 1)
            }
            return null
        }

        /** Just past `role\s*=\s*["']main["']` at [r] — at the start of a word — or -1. */
        private fun roleEnd(r: Int): Int {
            if (!nameAt(r, "role") || wordBefore(r)) return -1
            var k = skipSpaces(r + 4)
            if (k >= n || s[k] != '=') return -1
            k = skipSpaces(k + 1)
            if (k >= n || (s[k] != '"' && s[k] != '\'') || !nameAt(k + 1, "main")) return -1
            k += 5
            return if (k < n && (s[k] == '"' || s[k] == '\'')) k + 1 else -1
        }

        /** Is the character before [i] part of a word, as `\b` sees it (a mark counts when a letter or digit carries it)? */
        private fun wordBefore(i: Int): Boolean {
            if (i <= 0) return false
            val cp = Character.codePointBefore(s, i)
            if (cp == '_'.code || Character.isLetterOrDigit(cp)) return true
            if (Character.getType(cp) != Character.NON_SPACING_MARK.toInt()) return false
            var x = i - 1
            while (x >= 0) {
                val c = Character.codePointAt(s, x)
                if (Character.isLetterOrDigit(c)) return true
                if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) return false
                x--
            }
            return false
        }

        /** Tag name (lower case) -> where its last `</name>` starts. */
        private fun allClosers(): HashMap<String, Int> {
            val out = HashMap<String, Int>()
            var j = s.indexOf("</")
            while (j >= 0) {
                d.tick()
                var k = j + 2
                while (k < n && isAlnum(s[k])) k++
                if (k > j + 2) {
                    val e = skipSpaces(k)
                    if (e < n && s[e] == '>') out[s.substring(j + 2, k).lowercase()] = j
                }
                j = s.indexOf("</", j + 1)
            }
            return out
        }

        // ---- the body

        /** Everything between the first `<body …>` that has a `</body>` after it and the last `</body>`, or null. */
        fun body(): String? {
            val last = lastCloser("body")
            if (last < 0) return null
            var i = s.indexOf('<')
            while (i in 0 until last) {
                d.tick()
                if (nameAt(i + 1, "body") && nameEndsAt(i + 5)) {
                    val open = tagEnd(i + 5)
                    if (open in 0..last) return s.substring(open, last)
                }
                i = s.indexOf('<', i + 1)
            }
            return null
        }

        // ---- <meta>

        /** `<meta` at or after [from], in either case. */
        private fun metaFrom(from: Int): Int {
            var i = s.indexOf('<', from)
            while (i >= 0 && !nameAt(i + 1, "meta")) { d.tick(); i = s.indexOf('<', i + 1) }
            return i
        }

        /** Just past `(?:property|name)\s*=\s*["']name["']` at [r], or -1. */
        private fun namedAs(r: Int, name: String): Int {
            var k = when {
                nameAt(r, "property") -> r + 8
                nameAt(r, "name") -> r + 4
                else -> return -1
            }
            k = skipSpaces(k)
            if (k >= n || s[k] != '=') return -1
            k = skipSpaces(k + 1)
            if (k >= n || (s[k] != '"' && s[k] != '\'') || !nameAt(k + 1, name)) return -1
            k += 1 + name.length
            return if (k < n && (s[k] == '"' || s[k] == '\'')) k + 1 else -1
        }

        /** The opening quote of `content\s*=\s*["']` at [c], or -1. */
        private fun contentQuote(c: Int): Int {
            if (!nameAt(c, "content")) return -1
            var k = skipSpaces(c + 7)
            if (k >= n || s[k] != '=') return -1
            k = skipSpaces(k + 1)
            return if (k < n && (s[k] == '"' || s[k] == '\'')) k else -1
        }

        /**
         * `<meta … property="name" … content="…"`: the content of the first such tag (the last
         * content="…" in it whose quote closes, after a property or name naming [name]). The
         * content may run past the tag's '>', to its closing quote.
         */
        fun metaContentAfter(name: String): String? {
            val lastDq = s.lastIndexOf('"'); val lastSq = s.lastIndexOf('\'')
            var i = metaFrom(0)
            while (i >= 0) {
                val g = s.indexOf('>', i + 5).let { if (it < 0) n else it }
                if (g > i + 5) {
                    var c = -1; var y = -1
                    for (p in i + 6 until g) {
                        d.tick()
                        val q = contentQuote(p)
                        if (q >= 0 && q < (if (s[q] == '"') lastDq else lastSq)) { c = p; y = q }
                    }
                    if (c >= 0) for (e in i + 6 until c) {
                        d.tick()
                        val after = namedAs(e, name)
                        if (after in 0..c) return s.substring(y + 1, s.indexOf(s[y], y + 1))
                    }
                }
                // Every other <meta before that '>' sees the same tag, less of it: none fits either.
                if (g >= n) return null
                i = metaFrom(g + 1)
            }
            return null
        }

        /**
         * `<meta … content="…" … property="name"`: the content of the first such tag (its last
         * content="…" that fits). The content runs to the first closing quote after which a
         * property or name naming [name] comes before the next '>' — possibly a later tag's.
         */
        fun metaContentBefore(name: String): String? {
            // every content=" on the page, with the quote it opens
            val cs = ArrayList<Int>(); val ys = ArrayList<Int>()
            for (p in 0 until n) { d.tick(); val q = contentQuote(p); if (q >= 0) { cs.add(p); ys.add(q) } }
            if (cs.isEmpty()) return null
            // for each, the first quote after it like its own that is followed — before any '>' — by the name
            val closes = IntArray(cs.size) { -1 }
            var nextNamed = Int.MAX_VALUE; var nextGt = Int.MAX_VALUE
            var firstDq = -1; var firstSq = -1
            var t = cs.size - 1
            for (k in n - 1 downTo 0) {
                d.tick()
                // what holds from k + 1 on is known: answer the content whose quote is at k
                while (t >= 0 && ys[t] == k) { closes[t] = if (s[k] == '"') firstDq else firstSq; t-- }
                val c = s[k]
                if (nextNamed < nextGt) { if (c == '"') firstDq = k else if (c == '\'') firstSq = k }
                if (namedAs(k, name) >= 0) nextNamed = k
                if (c == '>') nextGt = k
            }
            var i = metaFrom(0)
            while (i >= 0) {
                val g = s.indexOf('>', i + 5).let { if (it < 0) n else it }
                if (g > i + 5) {
                    var u = cs.binarySearch(g).let { if (it < 0) -it - 1 else it } - 1
                    while (u >= 0 && cs[u] >= i + 6) {
                        d.tick()
                        if (closes[u] >= 0) return s.substring(ys[u] + 1, closes[u])
                        u--
                    }
                }
                if (g >= n) return null
                i = metaFrom(g + 1)
            }
            return null
        }
    }

    private fun lower(c: Char): Char = if (c in 'A'..'Z') c + 32 else c
    /** Whitespace as `\s` has it. */
    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r'
    private fun isAlnum(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    // ------------------------------------------------------------------ entities

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "sbquo" to "‚", "bdquo" to "„",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·", "bull" to "•", "deg" to "°",
        "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "rupee" to "₹", "times" to "×", "divide" to "÷", "plusmn" to "±", "frac12" to "½", "frac14" to "¼",
        "frac34" to "¾", "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›", "prime" to "′",
        "Prime" to "″", "sect" to "§", "para" to "¶", "shy" to "", "zwj" to "‍", "zwnj" to "‌",
        "thinsp" to " ", "ensp" to " ", "emsp" to " ", "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓",
        "eacute" to "é", "egrave" to "è", "aacute" to "á", "agrave" to "à", "ouml" to "ö", "uuml" to "ü", "auml" to "ä",
        "szlig" to "ß", "ccedil" to "ç", "ntilde" to "ñ", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
    )

    /**
     * `&amp;`, `&#8217;`, `&#x2019;` and the like as the characters they stand for; anything else
     * with a '&' stays as written. One walk: a page of nothing but entities costs no more.
     */
    fun decodeEntities(s: String): String {
        var at = s.indexOf('&')
        if (at < 0) return s
        val out = StringBuilder(s.length)
        var last = 0
        while (at >= 0) {
            val end = entityEnd(s, at)
            if (end < 0) { at = s.indexOf('&', at + 1); continue }
            val e = s.substring(at + 1, end - 1)
            val v = when {
                e.startsWith("#x") || e.startsWith("#X") -> codePoint(e.substring(2).toIntOrNull(16))
                e.startsWith("#") -> codePoint(e.substring(1).toIntOrNull())
                else -> NAMED[e]
            }
            out.append(s, last, at).append(v ?: s.substring(at, end))
            last = end
            at = s.indexOf('&', end)
        }
        return out.append(s, last, s.length).toString()
    }

    /** Just past the ';' of an entity at [at] — `&#x` and 1–6 hex digits, `&#` and 1–7 digits, or a name of 2–11 letters and digits — or -1. */
    private fun entityEnd(s: String, at: Int): Int {
        val n = s.length
        var k = at + 1
        val max: Int
        val ok: (Char) -> Boolean
        when {
            k < n && s[k] == '#' && k + 1 < n && (s[k + 1] == 'x' || s[k + 1] == 'X') -> { k += 2; max = 6; ok = { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }
            k < n && s[k] == '#' -> { k += 1; max = 7; ok = { it in '0'..'9' } }
            k < n && (s[k] in 'a'..'z' || s[k] in 'A'..'Z') -> { k += 1; max = 10; ok = { isAlnum(it) } }
            else -> return -1
        }
        val from = k
        while (k < n && k - from < max && ok(s[k])) k++
        return if (k > from && k < n && s[k] == ';') k + 1 else -1
    }

    private fun codePoint(cp: Int?): String? {
        if (cp == null || cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return null
        if (cp < 0x20 && cp != 0x09 && cp != 0x0A) return " "
        return String(Character.toChars(cp))
    }

    // ------------------------------------------------------------------ parts

    /** The last line of a page that goes on past what is read ([Page.cut]). */
    const val CUT_NOTE = "(The page goes on, but this is as much of it as Hopline reads.)"

    /**
     * [page]'s text in parts ([parts]). A page cut short says so where its text stops — at the end
     * of its last part, not mid-sentence and silent.
     */
    fun parts(page: Page, maxChars: Int): List<String> = parts(if (page.cut) page.text + "\n\n" + CUT_NOTE else page.text, maxChars)

    /**
     * Cut long text into parts on paragraph boundaries, deterministically — every helper that
     * serves "part 2" of the same text must cut it at the same place.
     */
    fun parts(text: String, maxChars: Int): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val out = ArrayList<String>()
        val cur = StringBuilder()
        for (para in text.split("\n\n")) {
            var p = para
            while (p.length > maxChars) {             // one monster paragraph: cut at a sentence/space
                if (cur.isNotEmpty()) { out.add(cur.toString()); cur.setLength(0) }
                var cut = p.lastIndexOf(". ", maxChars).takeIf { it > maxChars / 2 }?.plus(1) ?: p.lastIndexOf(' ', maxChars)
                if (cut <= 0) cut = maxChars
                out.add(p.substring(0, cut).trim()); p = p.substring(cut).trim()
            }
            if (cur.isNotEmpty() && cur.length + 2 + p.length > maxChars) { out.add(cur.toString()); cur.setLength(0) }
            if (cur.isNotEmpty()) cur.append("\n\n")
            cur.append(p)
        }
        if (cur.isNotEmpty()) out.add(cur.toString())
        return out
    }
}
