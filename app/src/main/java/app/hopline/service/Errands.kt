package app.hopline.service

import android.content.Context
import android.util.Log
import app.hopline.mesh.Errand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The things one phone with signal can do for the whole group. Deliberately tiny: a stripped
 * web page is capped at a few KB of plain text. One bar of signal in a dead zone is precious
 * and must not be spent on anything bigger than that.
 */
object Errands {
    private const val TAG = "Hopline/Errands"
    private val scope = CoroutineScope(Dispatchers.Main)

    fun run(ctx: Context, e: Errand, done: (ok: Boolean, title: String, text: String) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    when (e.type) {
                        Errand.READ -> read(e.args.optString("url"))
                        else -> Triple(false, "", "Unknown request")
                    }
                } catch (ex: Exception) {
                    Log.w(TAG, "errand failed", ex)
                    Triple(false, titleFor(e), "Couldn't do it (${ex.message ?: "no connection"}). Ask again later.")
                }
            }
            done(result.first, result.second, result.third)
        }
    }

    fun titleFor(e: Errand): String = when (e.type) {
        Errand.READ -> "Web page: ${e.args.optString("url").take(60)}"
        Errand.SEND -> "Message to ${e.args.optString("to")}"
        else -> "Request"
    }

    // ------------------------------------------------------------------ read a page (text only)

    private fun read(url: String): Triple<Boolean, String, String> {
        var u = url.trim(); if (!u.startsWith("http")) u = "https://$u"
        val html = get(u, maxBytes = 600_000)
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { clean(it) } ?: u
        var body = html
        body = body.replace(Regex("(?is)<(script|style|noscript|svg|head|nav|footer)[^>]*>.*?</\\1>"), " ")
        body = body.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>|</tr>"), "\n")
        body = body.replace(Regex("<[^>]+>"), " ")
        body = clean(body)
        body = body.lines().map { it.trim() }.filter { it.length > 2 }.joinToString("\n")
        if (body.length > 4800) body = body.take(4800) + "\n…(cut here to save data)"
        return Triple(true, "Web page: $title", body)
    }

    private fun clean(s: String): String = s
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n").trim()

    // ------------------------------------------------------------------ http

    private fun get(url: String, maxBytes: Int = 200_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        c.setRequestProperty("User-Agent", "Hopline/1.0 (offline group chat; text only)")
        c.setRequestProperty("Accept", "text/html,application/json,text/plain")
        try {
            if (c.responseCode >= 400) throw RuntimeException("HTTP ${c.responseCode}")
            val buf = ByteArray(8192); val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { ins ->
                while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > maxBytes) break }
            }
            return out.toString("UTF-8")
        } finally { c.disconnect() }
    }
}
