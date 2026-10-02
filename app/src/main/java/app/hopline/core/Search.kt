package app.hopline.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Look it up": nobody in a dead zone knows the URL they need, they know the question. The helper
 * asks DuckDuckGo's lite page (real web results), then DuckDuckGo's Instant Answer API and
 * Wikipedia's search API as keyless fallbacks. Parsers are pure and tested on saved responses —
 * when a provider changes its HTML the tests say so, not a confused hiker.
 */
object Search {
    class Result(val title: String, val url: String, val snippet: String)

    class Answer(val summary: String, val results: List<Result>, val source: String)

    const val MAX_RESULTS = 8

    fun ddgLiteUrl(): String = "https://lite.duckduckgo.com/lite/"
    fun ddgLiteBody(q: String): String = "q=" + java.net.URLEncoder.encode(q, "UTF-8")
    fun ddgInstantUrl(q: String): String =
        "https://api.duckduckgo.com/?q=${java.net.URLEncoder.encode(q, "UTF-8")}&format=json&no_html=1&skip_disambig=1&no_redirect=1"
    fun wikiUrl(q: String, lang: String): String {
        val l = lang.lowercase().takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        return "https://$l.wikipedia.org/w/rest.php/v1/search/page?q=${java.net.URLEncoder.encode(q, "UTF-8")}&limit=5"
    }

    /** DuckDuckGo lite: `a.result-link` then a `td.result-snippet`. Sponsored rows are skipped. */
    fun parseDdgLite(html: String): List<Result> {
        if (html.contains("anomaly-modal") || html.contains("challenge-form")) return emptyList()
        val out = ArrayList<Result>()
        val link = Regex("(?is)<a[^>]+href\\s*=\\s*\"([^\"]+)\"[^>]*class\\s*=\\s*['\"]result-link['\"][^>]*>(.*?)</a>")
        val snippet = Regex("(?is)<td[^>]*class\\s*=\\s*['\"]result-snippet['\"][^>]*>(.*?)</td>")
        val links = link.findAll(html).toList()
        for ((i, m) in links.withIndex()) {
            val url = unwrap(WebText.decodeEntities(m.groupValues[1])) ?: continue
            if (url.contains("duckduckgo.com/y.js") || url.contains("/aclick")) continue   // ads
            val title = text(m.groupValues[2])
            val end = if (i + 1 < links.size) links[i + 1].range.first else html.length
            val sn = snippet.find(html.substring(m.range.last, end))?.groupValues?.get(1)?.let { text(it) } ?: ""
            if (title.isNotEmpty()) out.add(Result(title.take(160), url, sn.take(300)))
            if (out.size >= MAX_RESULTS) break
        }
        return out
    }

    /** DDG Instant Answer: an abstract (usually Wikipedia) plus related topics. */
    fun parseDdgInstant(json: String): Answer? {
        val j = try { JSONObject(json) } catch (e: Exception) { return null }
        val answer = j.optString("Answer", "").let { text(it) }
        val abstract = j.optString("AbstractText", "").trim()
        val summary = listOf(answer, abstract).filter { it.isNotEmpty() }.joinToString("\n\n")
        val results = ArrayList<Result>()
        j.optString("AbstractURL", "").takeIf { it.startsWith("https://") }?.let {
            results.add(Result(j.optString("Heading", "").ifEmpty { WebText.hostOf(it) }, it, j.optString("AbstractSource", "")))
        }
        fun topics(a: JSONArray?) {
            if (a == null) return
            for (i in 0 until a.length()) {
                val t = a.optJSONObject(i) ?: continue
                if (t.has("Topics")) { topics(t.optJSONArray("Topics")); continue }
                val u = t.optString("FirstURL", ""); val tx = t.optString("Text", "")
                if (u.startsWith("https://") && tx.isNotEmpty() && results.size < MAX_RESULTS) results.add(Result(tx.take(120), u, ""))
            }
        }
        topics(j.optJSONArray("Results")); topics(j.optJSONArray("RelatedTopics"))
        if (summary.isEmpty() && results.isEmpty()) return null
        return Answer(summary, results, "DuckDuckGo")
    }

    /** Wikipedia REST search: title, key, excerpt with <span class="searchmatch"> marks. */
    fun parseWiki(json: String, lang: String): List<Result> {
        val j = try { JSONObject(json) } catch (e: Exception) { return emptyList() }
        val pages = j.optJSONArray("pages") ?: return emptyList()
        val l = lang.lowercase().takeIf { it.matches(Regex("[a-z]{2,3}")) } ?: "en"
        val out = ArrayList<Result>()
        for (i in 0 until pages.length()) {
            val p = pages.optJSONObject(i) ?: continue
            val key = p.optString("key", ""); if (key.isEmpty()) continue
            val desc = p.optString("description", "").let { if (it == "null") "" else it }
            val ex = text(p.optString("excerpt", ""))
            out.add(Result(p.optString("title", key), "https://$l.wikipedia.org/wiki/" + java.net.URLEncoder.encode(key, "UTF-8").replace("+", "_"),
                listOf(desc, ex).filter { it.isNotEmpty() }.joinToString(" — ").take(300)))
        }
        return out
    }

    /** One plain-text answer: the summary, then numbered results someone can tap to read. */
    fun format(q: String, summary: String, results: List<Result>): String = buildString {
        if (summary.isNotEmpty()) append(summary.take(1200)).append("\n\n")
        for ((i, r) in results.withIndex()) {
            append(i + 1).append(". ").append(r.title).append(" — ").append(WebText.hostOf(r.url)).append('\n')
            if (r.snippet.isNotEmpty()) append("   ").append(r.snippet).append('\n')
        }
        if (summary.isEmpty() && results.isEmpty()) append("Nothing found for “").append(q).append("”.")
    }.trim()

    /** DuckDuckGo sometimes wraps results in its own redirect (…/l/?uddg=<encoded>). */
    private fun unwrap(href: String): String? {
        var u = href.trim()
        if (u.startsWith("//")) u = "https:$u"
        if (u.contains("duckduckgo.com/l/") && u.contains("uddg=")) {
            u = java.net.URLDecoder.decode(u.substringAfter("uddg=").substringBefore('&'), "UTF-8")
        }
        return u.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    }

    private fun text(html: String): String = WebText.decodeEntities(html.replace(Regex("(?s)<[^>]*>"), "")).replace(Regex("\\s+"), " ").trim()
}
