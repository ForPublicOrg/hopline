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
 * The things one phone with signal can do for the whole group. Deliberately tiny: a weather
 * forecast is ~2 KB, a stripped web page is capped at a few KB. One bar of signal two days from
 * a road is precious and must not be spent on anything bigger than text.
 */
object Errands {
    private const val TAG = "Hopline/Errands"
    private val scope = CoroutineScope(Dispatchers.Main)

    fun run(ctx: Context, e: Errand, done: (ok: Boolean, title: String, text: String) -> Unit) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    when (e.type) {
                        Errand.WEATHER -> weather(e.args.optString("place"))
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
        Errand.WEATHER -> "Weather for ${e.args.optString("place")}"
        Errand.READ -> "Web page: ${e.args.optString("url").take(60)}"
        Errand.SEND -> "Message to ${e.args.optString("to")}"
        else -> "Request"
    }

    // ------------------------------------------------------------------ weather (open-meteo, no key)

    private fun weather(place: String): Triple<Boolean, String, String> {
        val q = URLEncoder.encode(place.trim(), "UTF-8")
        val geo = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=1&language=en&format=json"))
        val hit = geo.optJSONArray("results")?.optJSONObject(0)
            ?: return Triple(false, "Weather for $place", "Couldn't find a place called \"$place\". Try a bigger town nearby.")
        val lat = hit.getDouble("latitude"); val lon = hit.getDouble("longitude")
        val where = listOfNotNull(hit.optString("name"), hit.optString("admin1").ifEmpty { null }, hit.optString("country").ifEmpty { null }).joinToString(", ")
        val elev = hit.optDouble("elevation", Double.NaN)
        val f = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,sunrise,sunset" +
            "&timezone=auto&forecast_days=4&wind_speed_unit=kmh"))
        val cur = f.getJSONObject("current"); val d = f.getJSONObject("daily")
        val sb = StringBuilder()
        sb.append("Now: ").append(words(cur.optInt("weather_code"))).append(", ")
            .append(Math.round(cur.optDouble("temperature_2m"))).append("°C (feels ").append(Math.round(cur.optDouble("apparent_temperature"))).append("°), wind ")
            .append(Math.round(cur.optDouble("wind_speed_10m"))).append(" km/h\n")
        val dates = d.getJSONArray("time")
        for (i in 0 until dates.length()) {
            val day = when (i) { 0 -> "Today"; 1 -> "Tomorrow"; else -> dayName(dates.getString(i)) }
            sb.append(day).append(": ").append(words(d.getJSONArray("weather_code").getInt(i)))
                .append(", ").append(Math.round(d.getJSONArray("temperature_2m_min").getDouble(i))).append("° to ")
                .append(Math.round(d.getJSONArray("temperature_2m_max").getDouble(i))).append("°")
            val pp = d.getJSONArray("precipitation_probability_max").optInt(i, -1)
            if (pp >= 0) sb.append(", ").append(pp).append("% chance of rain")
            val wind = d.getJSONArray("wind_speed_10m_max").optDouble(i, 0.0)
            if (wind >= 30) sb.append(", windy (").append(Math.round(wind)).append(" km/h)")
            if (i == 0) sb.append(". Sunrise ").append(d.getJSONArray("sunrise").getString(i).takeLast(5)).append(", sunset ").append(d.getJSONArray("sunset").getString(i).takeLast(5))
            sb.append("\n")
        }
        val title = "Weather for $where" + if (!elev.isNaN()) " (${Math.round(elev)} m)" else ""
        return Triple(true, title, sb.toString().trim())
    }

    private fun dayName(iso: String): String = try {
        val p = iso.split("-"); val cal = java.util.Calendar.getInstance()
        cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
        java.text.SimpleDateFormat("EEEE", java.util.Locale.ENGLISH).format(cal.time)
    } catch (e: Exception) { iso }

    private fun words(code: Int): String = when (code) {
        0 -> "clear"; 1 -> "mostly clear"; 2 -> "partly cloudy"; 3 -> "overcast"
        45, 48 -> "fog"; 51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"
        61 -> "light rain"; 63 -> "rain"; 65 -> "heavy rain"; 66, 67 -> "freezing rain"
        71 -> "light snow"; 73 -> "snow"; 75 -> "heavy snow"; 77 -> "snow grains"
        80 -> "light showers"; 81 -> "showers"; 82 -> "violent showers"; 85 -> "snow showers"; 86 -> "heavy snow showers"
        95 -> "thunderstorm"; 96, 99 -> "thunderstorm with hail"
        else -> "unknown"
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
