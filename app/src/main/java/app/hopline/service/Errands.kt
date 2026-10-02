package app.hopline.service

import android.content.Context
import android.util.Log
import android.util.LruCache
import app.hopline.core.Search
import app.hopline.core.SafeUrl
import app.hopline.core.Weather
import app.hopline.core.WebText
import app.hopline.mesh.Errand
import app.hopline.mesh.Loc
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * What one phone with signal does for the group: read a page, look something up, get the
 * weather. Everything comes back as plain text a person can read on one bar of signal — never
 * bigger than one radio frame once compressed. (Texts home are sent by a person, not here.)
 */
object Errands {
    private const val TAG = "Hopline/Errands"

    sealed class Outcome {
        /** The answer: [body] has "t" (text) plus "l" (links) or "r" (search results). */
        class Ok(val title: String, val body: JSONObject, val cost: Int) : Outcome()
        /** It can't be done — asking again won't change that. The asker is told why. */
        class Fail(val title: String, val text: String, val why: String, val cost: Int = 0) : Outcome()
        /** It didn't work from here right now (signal dropped, site slow): let another phone try. */
        class Retry(val why: String, val cost: Int = 0) : Outcome()
    }

    private class Job {
        val session = Fetch.Session()
        val cancelled: Boolean get() = session.cancelled
        var future: Future<*>? = null
    }

    private val pool = Executors.newFixedThreadPool(2)
    private val jobs = ConcurrentHashMap<String, Job>()
    /** A page read once is served again (Read more, a second asker) without spending data. */
    private val pages = LruCache<String, Pair<Long, WebText.Page>>(10)
    private val weather = LruCache<String, Pair<Long, String>>(20)
    private const val CACHE_MS = 30 * 60_000L

    /** Run [e] on a worker; [done] is called on the main thread unless it was cancelled. */
    fun run(ctx: Context, e: Errand, partChars: Int, maxWire: Int, done: (Outcome) -> Unit) {
        val job = Job()
        jobs[e.id]?.session?.abort()
        jobs[e.id] = job
        job.future = pool.submit {
            val out = try {
                when (e.type) {
                    Errand.READ -> read(e, partChars, maxWire, job.session)
                    Errand.FIND -> find(e, maxWire, job.session)
                    Errand.WX -> weather(ctx, e, job.session)
                    else -> Outcome.Fail(titleFor(e), "This phone's Hopline can't do that kind of request.", "unsupported")
                }
            } catch (p: Fetch.Problem) {
                if (p.permanent) Outcome.Fail(titleFor(e), p.message ?: "Couldn't do it.", p.why) else Outcome.Retry(p.why)
            } catch (x: java.io.IOException) {
                Log.w(TAG, "errand network failure", x); Outcome.Retry("net")
            } catch (x: Exception) {
                Log.w(TAG, "errand failed", x)
                Outcome.Fail(titleFor(e), "Something went wrong reading that. Try a different link or search.", "error")
            }
            // Every byte this request cost, failed downloads too: the allowance must see them.
            val spent = job.session.spent.get()
            val billed = when (out) {
                is Outcome.Ok -> if (spent > out.cost) Outcome.Ok(out.title, out.body, spent) else out
                is Outcome.Fail -> if (spent > out.cost) Outcome.Fail(out.title, out.text, out.why, spent) else out
                is Outcome.Retry -> if (spent > out.cost) Outcome.Retry(out.why, spent) else out
            }
            Core.handler.post {
                if (jobs[e.id] === job) jobs.remove(e.id)
                if (!job.cancelled) done(billed)
            }
        }
    }

    /** Stop [eid]'s job. Returns the bytes it had already cost (0 if nothing was running). */
    fun cancel(eid: String): Int {
        val job = jobs.remove(eid) ?: return 0
        job.session.abort(); job.future?.cancel(true)
        return job.session.spent.get()
    }

    fun isRunning(eid: String): Boolean = jobs.containsKey(eid)

    // ------------------------------------------------------------------ words for screens

    fun titleFor(e: Errand): String = when (e.type) {
        Errand.READ -> {
            val url = e.args.optString("url")
            val host = SafeUrl.fromInput(url)?.let { WebText.hostOf(it) } ?: url.take(40)
            val part = e.args.optInt("part", 1)
            "Web page · $host" + if (part > 1) " (part $part)" else ""
        }
        Errand.FIND -> "Search: ${e.args.optString("q").take(60)}"
        Errand.WX -> "Weather " + (e.args.optString("lbl").takeIf { it.isNotEmpty() }?.let { "at $it" } ?: "where you are")
        Errand.SEND -> {
            val to = e.args.optString("name").ifEmpty { e.args.optString("to") }
            if (Errand.isEmailTarget(e.args)) "Email to $to" else "Text to $to"
        }
        else -> "Request"
    }

    // ------------------------------------------------------------------ read a page

    private fun read(e: Errand, partChars: Int, maxWire: Int, s: Fetch.Session): Outcome {
        val url = SafeUrl.fromInput(e.args.optString("url"))
            ?: return Outcome.Fail(titleFor(e), "That isn't a web address Hopline can open.", "bad_url")
        val part = e.args.optInt("part", 1).coerceIn(1, 50)
        var cost = 0
        val now = System.currentTimeMillis()
        val page = pages.get(url)?.takeIf { now - it.first < CACHE_MS }?.second ?: run {
            val r = Fetch.get(url, maxWire = maxWire, session = s)
            cost = r.wireBytes
            val html = WebText.decode(r.body, r.contentType)
            val p = if (r.contentType.startsWith("text/plain")) WebText.Page(WebText.hostOf(r.url), html.trim(), emptyList(), "", html.isBlank())
                    else WebText.extract(html, r.url)
            pages.put(url, now to p); if (r.url != url) pages.put(r.url, now to p)
            p
        }
        if (page.thin && page.text.length < 60) {
            return Outcome.Fail(page.title, "This page only works in a full web browser (it builds itself with scripts). " +
                "Try a search instead${if (page.title.isNotEmpty()) " for “${page.title.take(60)}”" else ""}.", "needs_browser", cost)
        }
        val parts = WebText.parts(page.text, partChars)
        val idx = (part - 1).coerceIn(0, parts.size - 1)
        val text = parts[idx]
        val links = JSONArray()
        for (l in page.links) links.put(JSONArray().put(l.text).put(l.url))
        val body = JSONObject().put("t", text).put("l", links).put("src", url).put("part", idx + 1).put("parts", parts.size)
        if (page.description.isNotEmpty()) body.put("d", page.description)
        return Outcome.Ok(page.title.ifEmpty { WebText.hostOf(url) }, body, cost)
    }

    // ------------------------------------------------------------------ look it up

    private fun find(e: Errand, maxWire: Int, s: Fetch.Session): Outcome {
        val q = e.args.optString("q").trim().replace(Regex("\\s+"), " ").take(200)
        if (q.isEmpty()) return Outcome.Fail(titleFor(e), "There was nothing to search for.", "empty")
        val lang = e.args.optString("lang").ifEmpty { "en" }
        var cost = 0
        var results: List<Search.Result> = emptyList()
        var summary = ""
        var source = "DuckDuckGo"
        try {
            val r = Fetch.get(Search.ddgLiteUrl(), maxWire = minOf(maxWire, 200_000), form = Search.ddgLiteBody(q), session = s)
            cost += r.wireBytes
            results = Search.parseDdgLite(String(r.body, Charsets.UTF_8))
        } catch (p: Fetch.Problem) { if (p.why == "cancelled") throw p; Log.w(TAG, "web results unavailable: ${p.why}") }
        try {
            val r = Fetch.get(Search.ddgInstantUrl(q), maxWire = 60_000, accept = listOf("application/json", "application/x-javascript", "text/javascript"), session = s)
            cost += r.wireBytes
            Search.parseDdgInstant(String(r.body, Charsets.UTF_8))?.let { a ->
                summary = a.summary
                if (results.isEmpty()) results = a.results
            }
        } catch (p: Fetch.Problem) { if (p.why == "cancelled") throw p }
        if (results.size < 3) {
            try {
                val r = Fetch.get(Search.wikiUrl(q, lang), maxWire = 60_000, accept = listOf("application/json"), session = s)
                cost += r.wireBytes
                val wiki = Search.parseWiki(String(r.body, Charsets.UTF_8), lang)
                if (results.isEmpty()) source = "Wikipedia"
                results = (results + wiki).distinctBy { it.url }.take(Search.MAX_RESULTS)
            } catch (p: Fetch.Problem) { if (p.why == "cancelled") throw p }
        }
        if (results.isEmpty() && summary.isEmpty()) {
            if (s.spent.get() == 0) return Outcome.Retry("net")
            return Outcome.Fail(titleFor(e), "Nothing found for “$q”. Try fewer or different words.", "no_results", cost)
        }
        val r = JSONArray()
        for (x in results) r.put(JSONArray().put(x.title).put(x.url).put(x.snippet))
        val body = JSONObject().put("t", Search.format(q, summary, results)).put("r", r).put("s", summary).put("src", source)
        return Outcome.Ok("Search: $q", body, cost)
    }

    // ------------------------------------------------------------------ weather

    private fun weather(ctx: Context, e: Errand, s: Fetch.Session): Outcome {
        var lat = e.args.optLong("lat", Long.MIN_VALUE)
        var lng = e.args.optLong("lng", Long.MIN_VALUE)
        var label = e.args.optString("lbl").take(Loc.MAX_LABEL)
        var where = if (label.isNotEmpty()) "at $label" else "near ${e.fromName.ifEmpty { "you" }}"
        if (Loc.fromJson(JSONObject().put("lat", lat).put("lng", lng)) == null) {
            // The asker had no fix: the helper's own position is the next best thing.
            val mine = Locations.lastKnown(ctx)
                ?: return Outcome.Fail(titleFor(e), "Neither phone knew where it was. Try again once your GPS has a fix (it works without signal).", "no_location")
            lat = Math.round(mine.latitude * 1e6); lng = Math.round(mine.longitude * 1e6)
            where = "near ${Core.store.name.ifEmpty { "the helper" }}'s phone"; label = ""
        }
        val la = lat / 1e6; val ln = lng / 1e6
        val key = String.format(Locale.US, "%.2f,%.2f", Math.round(la * 20) / 20.0, Math.round(ln * 20) / 20.0)
        val now = System.currentTimeMillis()
        var cost = 0
        val json = weather.get(key)?.takeIf { now - it.first < CACHE_MS }?.second ?: run {
            val r = Fetch.get(Weather.url(la, ln), maxWire = 80_000, accept = listOf("application/json"), session = s)
            cost = r.wireBytes
            String(r.body, Charsets.UTF_8).also { weather.put(key, now to it) }
        }
        val (title, text) = Weather.format(json, where)
        return Outcome.Ok(title, JSONObject().put("t", text), cost)
    }
}
