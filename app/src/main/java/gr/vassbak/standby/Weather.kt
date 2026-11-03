package gr.vassbak.standby

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale

/** Καιρός από το Open-Meteo (δωρεάν, χωρίς κλειδί). */
object Weather {

    data class Hour(val time: Long, val temp: Int, val code: Int, val rain: Int)
    data class Day(val date: Long, val max: Int, val min: Int, val code: Int, val rain: Int)
    data class Data(val temp: Int, val code: Int, val hours: List<Hour>, val days: List<Day>)

    fun fetch(lat: Double, lon: Double): Data {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&current=temperature_2m,weather_code" +
                "&hourly=temperature_2m,weather_code,precipitation_probability" +
                "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max" +
                "&timezone=auto&forecast_days=6"
        )
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        val json = JSONObject(c.inputStream.bufferedReader().readText())

        val cur = json.getJSONObject("current")
        val hourFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

        val h = json.getJSONObject("hourly")
        val ht = h.getJSONArray("time")
        val now = System.currentTimeMillis() - 3600_000L
        val hours = mutableListOf<Hour>()
        for (i in 0 until ht.length()) {
            val t = hourFmt.parse(ht.getString(i))?.time ?: continue
            if (t < now) continue
            hours += Hour(
                t,
                Math.round(h.getJSONArray("temperature_2m").optDouble(i, 0.0)).toInt(),
                h.getJSONArray("weather_code").optInt(i, 0),
                h.getJSONArray("precipitation_probability").optInt(i, 0)
            )
            if (hours.size >= 24) break
        }

        val d = json.getJSONObject("daily")
        val dt = d.getJSONArray("time")
        val days = mutableListOf<Day>()
        for (i in 0 until dt.length()) {
            days += Day(
                dayFmt.parse(dt.getString(i))?.time ?: 0L,
                Math.round(d.getJSONArray("temperature_2m_max").optDouble(i, 0.0)).toInt(),
                Math.round(d.getJSONArray("temperature_2m_min").optDouble(i, 0.0)).toInt(),
                d.getJSONArray("weather_code").optInt(i, 0),
                d.getJSONArray("precipitation_probability_max").optInt(i, 0)
            )
        }
        return Data(
            Math.round(cur.getDouble("temperature_2m")).toInt(),
            cur.getInt("weather_code"),
            hours,
            days
        )
    }

    fun text(code: Int): Pair<String, String> = when (code) {
        0 -> "☀️" to "Αίθριος"
        1, 2 -> "🌤️" to "Λίγα σύννεφα"
        3 -> "☁️" to "Συννεφιά"
        45, 48 -> "🌫️" to "Ομίχλη"
        51, 53, 55, 56, 57 -> "🌦️" to "Ψιχάλες"
        61, 63, 65, 66, 67 -> "🌧️" to "Βροχή"
        71, 73, 75, 77 -> "❄️" to "Χιόνι"
        80, 81, 82 -> "🌧️" to "Μπόρες"
        85, 86 -> "🌨️" to "Χιονόπτωση"
        95, 96, 99 -> "⛈️" to "Καταιγίδα"
        else -> "🌡️" to ""
    }
}
