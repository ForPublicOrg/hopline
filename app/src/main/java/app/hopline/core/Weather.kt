package app.hopline.core

import org.json.JSONObject
import java.util.Locale

/**
 * "Will it rain on the pass tomorrow?" for exactly where the asker stands — their GPS works with
 * no signal, the helper's phone asks Open-Meteo (free, no key, ~2 KB). Pure Kotlin: the URL and
 * the plain-English forecast are tested on a saved response.
 */
object Weather {
    const val ATTRIBUTION = "Weather data: Open-Meteo.com (CC BY 4.0)"

    fun url(lat: Double, lng: Double): String = String.format(Locale.US,
        "https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f" +
            "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m,wind_gusts_10m,precipitation" +
            "&hourly=weather_code,precipitation_probability,temperature_2m&forecast_hours=12" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum," +
            "snowfall_sum,wind_gusts_10m_max,sunrise,sunset&forecast_days=3&timezone=auto&wind_speed_unit=kmh",
        lat, lng)

    /** Title + body. [placeLabel] names the spot ("near you", "at Base camp"). */
    fun format(json: String, placeLabel: String): Pair<String, String> {
        val f = JSONObject(json)
        val elev = f.optDouble("elevation", Double.NaN)
        val title = "Weather $placeLabel" + if (!elev.isNaN()) " · ${Math.round(elev)} m" else ""
        val sb = StringBuilder()
        f.optJSONObject("current")?.let { c ->
            sb.append("Now: ").append(words(c.optInt("weather_code", -1))).append(", ")
                .append(Math.round(c.optDouble("temperature_2m"))).append("°C (feels ")
                .append(Math.round(c.optDouble("apparent_temperature"))).append("°), wind ")
                .append(Math.round(c.optDouble("wind_speed_10m"))).append(" km/h")
            val gust = c.optDouble("wind_gusts_10m", 0.0)
            if (gust >= 40) sb.append(", gusts ").append(Math.round(gust))
            sb.append('\n')
        }
        warnings(f)?.let { sb.append(it).append('\n') }
        val d = f.optJSONObject("daily")
        val dates = d?.optJSONArray("time")
        if (d != null && dates != null) {
            for (i in 0 until dates.length()) {
                val day = when (i) { 0 -> "Today"; 1 -> "Tomorrow"; else -> dayName(dates.optString(i)) }
                sb.append(day).append(": ").append(words(d.optJSONArray("weather_code")?.optInt(i, -1) ?: -1))
                    .append(", ").append(Math.round(d.optJSONArray("temperature_2m_min")?.optDouble(i) ?: 0.0)).append("° to ")
                    .append(Math.round(d.optJSONArray("temperature_2m_max")?.optDouble(i) ?: 0.0)).append("°")
                val pp = d.optJSONArray("precipitation_probability_max")?.optInt(i, -1) ?: -1
                val snow = d.optJSONArray("snowfall_sum")?.optDouble(i, 0.0) ?: 0.0
                val rain = d.optJSONArray("precipitation_sum")?.optDouble(i, 0.0) ?: 0.0
                if (snow >= 0.5) sb.append(", ").append(String.format(Locale.US, "%.0f cm snow", snow))
                else if (pp >= 20) sb.append(", ").append(pp).append("% chance of rain")
                if (rain >= 10 && snow < 0.5) sb.append(" (").append(Math.round(rain)).append(" mm)")
                val gust = d.optJSONArray("wind_gusts_10m_max")?.optDouble(i, 0.0) ?: 0.0
                if (gust >= 45) sb.append(", gusts ").append(Math.round(gust)).append(" km/h")
                if (i == 0) {
                    val rise = d.optJSONArray("sunrise")?.optString(i).orEmpty().takeLast(5)
                    val set = d.optJSONArray("sunset")?.optString(i).orEmpty().takeLast(5)
                    if (rise.isNotEmpty() && set.isNotEmpty()) sb.append(". Sunrise ").append(rise).append(", sunset ").append(set)
                }
                sb.append('\n')
            }
        }
        sb.append(ATTRIBUTION)
        return title to sb.toString().trim()
    }

    /** The next 12 hours, scanned for what changes plans: storms, heavy rain, snow. */
    private fun warnings(f: JSONObject): String? {
        val h = f.optJSONObject("hourly") ?: return null
        val times = h.optJSONArray("time") ?: return null
        val codes = h.optJSONArray("weather_code") ?: return null
        val probs = h.optJSONArray("precipitation_probability")
        fun window(pred: (Int) -> Boolean): Pair<String, String>? {
            var first = -1; var last = -1
            for (i in 0 until times.length()) if (pred(i)) { if (first < 0) first = i; last = i }
            if (first < 0) return null
            return times.optString(first).takeLast(5) to times.optString(last).takeLast(5)
        }
        val storm = window { codes.optInt(it) in 95..99 }
        if (storm != null) return "⚠ Thunderstorm likely ${storm.first}–${storm.second}"
        val snow = window { codes.optInt(it) in setOf(71, 73, 75, 77, 85, 86) }
        if (snow != null) return "⚠ Snow ${snow.first}–${snow.second}"
        val heavy = window { codes.optInt(it) in setOf(63, 65, 81, 82) || (probs?.optInt(it, 0) ?: 0) >= 70 }
        if (heavy != null) return "⚠ Rain likely ${heavy.first}–${heavy.second}"
        return null
    }

    private fun dayName(iso: String): String = try {
        val p = iso.split("-"); val cal = java.util.Calendar.getInstance()
        cal.set(p[0].toInt(), p[1].toInt() - 1, p[2].toInt())
        java.text.SimpleDateFormat("EEEE", Locale.ENGLISH).format(cal.time)
    } catch (e: Exception) { iso }

    fun words(code: Int): String = when (code) {
        0 -> "clear"; 1 -> "mostly clear"; 2 -> "partly cloudy"; 3 -> "overcast"
        45, 48 -> "fog"; 51, 53, 55 -> "drizzle"; 56, 57 -> "freezing drizzle"
        61 -> "light rain"; 63 -> "rain"; 65 -> "heavy rain"; 66, 67 -> "freezing rain"
        71 -> "light snow"; 73 -> "snow"; 75 -> "heavy snow"; 77 -> "snow grains"
        80 -> "light showers"; 81 -> "showers"; 82 -> "violent showers"; 85 -> "snow showers"; 86 -> "heavy snow showers"
        95 -> "thunderstorm"; 96, 99 -> "thunderstorm with hail"
        else -> "mixed"
    }
}
