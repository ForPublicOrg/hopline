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
 */
object WebText {

    class Link(val text: String, val url: String)

    class Page(
        val title: String,
        val text: String,          // paragraphs separated by blank lines; "# " marks a heading; "[n]" marks link n
        val links: List<Link>,     // 1-based in the text: [1] is links[0]
        val description: String,
        val thin: Boolean,         // too little readable text survived — probably a script-built page
    )

    const val MAX_LINKS = 40
    private val BLOCK_OPEN = Regex("(?i)<(h[1-6]|p|div|li)(?=[\\s/>])")
    private val BLOCK_CLOSE = Regex("(?i)</(h[1-6]|p|div|li)\\s*>")
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

    /** What sits between a tag's name and its closing '>': quoted values may themselves contain '>'. */
    private const val ATTRS = "(?:[^>\"']|\"[^\"]*\"|'[^']*')*"

    fun extract(html: String, baseUrl: String): Page {
        val title = clean(meta(html, "og:title") ?: tagText(html, "title") ?: tagText(html, "h1") ?: "").take(200)
        val description = clean(meta(html, "og:description") ?: meta(html, "description") ?: "").take(400)

        var body = html.replace(Regex("(?s)<!--.*?-->"), " ")
        body = body.replace(Regex("(?is)<(script|style|noscript|svg|template|iframe|object|canvas|select|button|form|head)(?=[\\s/>])$ATTRS>.*?</\\1\\s*>"), " ")
        // The biggest <article>, else <main>/role=main, else the whole body — the article wins over the chrome.
        body = mainPart(body)
        body = body.replace(Regex("(?is)<(nav|header|footer|aside|figure|menu|dialog)(?=[\\s/>])$ATTRS>.*?</\\1\\s*>"), " ")
        // Citation marks ("[12]") are noise without the footnotes they point to.
        body = Regex("(?is)<sup(?=[\\s/>])$ATTRS>(.*?)</sup\\s*>").replace(body) { m ->
            val t = stripTags(m.groupValues[1]).trim()
            if (t.startsWith("[") && t.length <= 12) " " else m.groupValues[1]
        }
        // A list whose every item is one short link is a menu (tabs, "share", language pickers).
        body = Regex("(?is)<(ul|ol)(?=[\\s/>])$ATTRS>(.*?)</\\1\\s*>").replace(body) { m ->
            val items = Regex("(?is)<li(?=[\\s/>])$ATTRS>(.*?)</li\\s*>").findAll(m.groupValues[2]).map { it.groupValues[1] }.toList()
            val menu = items.size >= 2 && items.all { Regex("(?is)<a\\s").containsMatchIn(it) && stripTags(it).trim().length <= 25 }
            if (menu) " " else m.value
        }

        val links = ArrayList<Link>()
        val seen = HashMap<String, Int>()
        body = Regex("(?is)<a\\s(?:[^>\"']|\"[^\"]*\"|'[^']*')*?href\\s*=\\s*([\"'])(.*?)\\1$ATTRS>(.*?)</a\\s*>").replace(body) { m ->
            val inner = m.groupValues[3]
            val label = clean(stripTags(inner))
            val url = absolute(decodeEntities(m.groupValues[2]).trim(), baseUrl)
            if (label.isEmpty() || url == null || links.size >= MAX_LINKS && url !in seen) return@replace " $inner "
            val n = seen.getOrPut(url) { links.add(Link(label.take(120), url)); links.size }
            // A link wrapping a whole news card (headline + summary): the number goes on the
            // headline, not on a line of its own after the card.
            val end = if (BLOCK_OPEN.containsMatchIn(inner)) BLOCK_CLOSE.find(inner) else null
            if (end != null) " ${inner.substring(0, end.range.first)} [$n]${inner.substring(end.range.first)} " else " $inner [$n] "
        }

        body = body.replace(Regex("(?is)<h([1-3])(?=[\\s/>])$ATTRS>(.*?)</h\\1\\s*>")) { m -> "\n\n\u0001${m.groupValues[2]}\n\n" }
        body = body.replace(Regex("(?i)<li(?=[\\s/>])$ATTRS>"), "\n• ")
        body = body.replace(Regex("(?i)<br\\s*/?>"), "\n")
        body = body.replace(Regex("(?i)</?(p|div|section|article|main|ul|ol|table|tr|blockquote|pre|h[4-6]|dl|dt|dd)(?=[\\s/>])$ATTRS>"), "\n\n")
        body = body.replace(Regex("(?i)</t[dh]\\s*>"), " · ")
        body = stripTags(body)
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
        return Page(title.ifEmpty { hostOf(baseUrl) }, text, links, description, thin)
    }

    private fun mainPart(html: String): String {
        val articles = Regex("(?is)<article(?=[\\s/>])$ATTRS>(.*?)</article\\s*>").findAll(html).map { it.groupValues[1] }.toList()
        val best = articles.maxByOrNull { stripTags(it).length }
        if (best != null && stripTags(best).trim().length > 400) return best
        Regex("(?is)<main(?=[\\s/>])$ATTRS>(.*?)</main\\s*>").find(html)?.let { if (stripTags(it.groupValues[1]).trim().length > 200) return it.groupValues[1] }
        Regex("(?is)<([a-z0-9]+)(?=[\\s/>])[^>]*?\\brole\\s*=\\s*[\"']main[\"']$ATTRS>(.*?)</\\1\\s*>").find(html)?.let {
            if (stripTags(it.groupValues[2]).trim().length > 200) return it.groupValues[2]
        }
        return Regex("(?is)<body(?=[\\s/>])$ATTRS>(.*)</body\\s*>").find(html)?.groupValues?.get(1) ?: html
    }

    private val BOILER = Regex("(?i)^(accept( all)?( cookies)?|cookie (settings|policy|preferences)|we use cookies.*|subscribe( now)?|sign (in|up)|log ?in|register|share( this)?( on .*)?|follow us.*|advertisement|sponsored|skip to (main )?content|back to top|read more|menu|close|search)\\.?$")
    private val EDIT = Regex("(?i)^\\[?\\s*edit\\s*]?(\\s*\\[\\d+])?$")
    private val INVISIBLE = Regex("[\\uFEFF\\u200B\\u00AD\\u2060]")
    private fun boilerplate(p: String): Boolean {
        if (p.length >= 80) return false
        val flat = p.replace('\n', ' ').trim()
        return BOILER.matches(flat) || EDIT.matches(flat) || flat == "•"
    }

    private fun meta(html: String, name: String): String? {
        val n = Regex.escape(name)
        val a = Regex("(?is)<meta[^>]+(?:property|name)\\s*=\\s*[\"']$n[\"'][^>]*content\\s*=\\s*([\"'])(.*?)\\1").find(html)?.groupValues?.get(2)
        val b = a ?: Regex("(?is)<meta[^>]+content\\s*=\\s*([\"'])(.*?)\\1[^>]*(?:property|name)\\s*=\\s*[\"']$n[\"']").find(html)?.groupValues?.get(2)
        return b?.let { decodeEntities(it) }?.takeIf { it.isNotBlank() }
    }

    private fun tagText(html: String, tag: String): String? =
        Regex("(?is)<$tag(?=[\\s/>])$ATTRS>(.*?)</$tag\\s*>").find(html)?.groupValues?.get(1)?.let { decodeEntities(stripTags(it)) }?.takeIf { it.isNotBlank() }

    private fun stripTags(s: String): String = s.replace(TAG, " ")
    private val TAG = Regex("(?s)</?[A-Za-z!][^>\"']*(?:(?:\"[^\"]*\"|'[^']*')[^>\"']*)*>")

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

    // ------------------------------------------------------------------ entities

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "sbquo" to "‚", "bdquo" to "„",
        "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·", "bull" to "•", "deg" to "°",
        "copy" to "©", "reg" to "®", "trade" to "™", "euro" to "€", "pound" to "£", "yen" to "¥", "cent" to "¢",
        "rupee" to "₹", "times" to "×", "divide" to "÷", "plusmn" to "±", "frac12" to "½", "frac14" to "¼",
        "frac34" to "¾", "laquo" to "«", "raquo" to "»", "lsaquo" to "‹", "rsaquo" to "›", "prime" to "′",
        "Prime" to "″", "sect" to "§", "para" to "¶", "shy" to "", "zwj" to "\u200D", "zwnj" to "\u200C",
        "thinsp" to " ", "ensp" to " ", "emsp" to " ", "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓",
        "eacute" to "é", "egrave" to "è", "aacute" to "á", "agrave" to "à", "ouml" to "ö", "uuml" to "ü", "auml" to "ä",
        "szlig" to "ß", "ccedil" to "ç", "ntilde" to "ñ", "iacute" to "í", "oacute" to "ó", "uacute" to "ú",
    )

    fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        return Regex("&(#[xX][0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,10});").replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> codePoint(e.substring(2).toIntOrNull(16)) ?: m.value
                e.startsWith("#") -> codePoint(e.substring(1).toIntOrNull()) ?: m.value
                else -> NAMED[e] ?: m.value
            }
        }
    }

    private fun codePoint(cp: Int?): String? {
        if (cp == null || cp <= 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return null
        if (cp < 0x20 && cp != 0x09 && cp != 0x0A) return " "
        return String(Character.toChars(cp))
    }

    // ------------------------------------------------------------------ parts

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
