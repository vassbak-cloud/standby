package gr.vassbak.standby

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Ανεπίσημο API του COROS Training Hub (το ίδιο που χρησιμοποιεί το t.coros.com).
 * Μπορεί να αλλάξει από την COROS χωρίς προειδοποίηση.
 */
object Coros {

    data class Workout(
        val day: String,          // yyyyMMdd
        val name: String,
        val sport: Int,
        val minutes: Int,
        val completed: Boolean
    )

    private const val EU = "https://teameuapi.coros.com"
    private const val US = "https://teamapi.coros.com"

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun configured(ctx: Context): Boolean {
        val p = Prefs.get(ctx)
        return !p.getString(Prefs.COROS_EMAIL, "").isNullOrBlank() &&
            !p.getString(Prefs.COROS_PWD_MD5, "").isNullOrBlank()
    }

    /** Επιστρέφει null αν πέτυχε, αλλιώς το μήνυμα λάθους. Τρέχει σε background thread. */
    fun login(ctx: Context): String? {
        val p = Prefs.get(ctx)
        val email = p.getString(Prefs.COROS_EMAIL, "") ?: ""
        val pwd = p.getString(Prefs.COROS_PWD_MD5, "") ?: ""
        if (email.isBlank() || pwd.isBlank()) return "Δεν έχουν οριστεί στοιχεία COROS"
        val first = if (p.getString(Prefs.COROS_REGION, "eu") == "us") US else EU
        val hosts = listOf(first, if (first == EU) US else EU)
        var lastError = "Άγνωστο σφάλμα"
        for (host in hosts) {
            try {
                val body = JSONObject()
                    .put("account", email)
                    .put("accountType", 2)
                    .put("pwd", pwd)
                val json = http("$host/account/login", body.toString(), emptyMap())
                if (json.optString("result") == "0000") {
                    val data = json.getJSONObject("data")
                    p.edit()
                        .putString(Prefs.COROS_TOKEN, data.optString("accessToken"))
                        .putString(Prefs.COROS_UID, data.opt("userId")?.toString() ?: "")
                        .putString(Prefs.COROS_HOST, host)
                        .apply()
                    return null
                }
                lastError = json.optString("message", "Λάθος στοιχεία")
            } catch (e: Exception) {
                lastError = "Σφάλμα σύνδεσης: ${e.message}"
            }
        }
        return lastError
    }

    /** Προπονήσεις από σήμερα και για [days] μέρες. Τρέχει σε background thread. */
    fun schedule(ctx: Context, days: Int = 7): List<Workout> {
        if (!configured(ctx)) return emptyList()
        val p = Prefs.get(ctx)
        if (p.getString(Prefs.COROS_TOKEN, "").isNullOrBlank()) {
            login(ctx)?.let { throw Exception(it) }
        }
        var json = fetchSchedule(ctx, days)
        if (json.optString("result") != "0000") {
            // Το token έληξε: νέα σύνδεση και ξανά
            login(ctx)?.let { throw Exception(it) }
            json = fetchSchedule(ctx, days)
            if (json.optString("result") != "0000") throw Exception(json.optString("message", "Σφάλμα COROS"))
        }
        val list = mutableListOf<Workout>()
        val entities = json.optJSONObject("data")?.optJSONArray("entities") ?: return list
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            list += Workout(
                day = e.opt("happenDay")?.toString() ?: "",
                name = e.optString("name", "").ifBlank { "Ξεκούραση" },
                sport = e.optInt("sportType", 0),
                minutes = (e.optDouble("estimatedTime", 0.0) / 60).toInt(),
                completed = e.optBoolean("completed", false)
            )
        }
        return list.sortedBy { it.day }
    }

    private fun fetchSchedule(ctx: Context, days: Int): JSONObject {
        val p = Prefs.get(ctx)
        val host = p.getString(Prefs.COROS_HOST, EU) ?: EU
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val cal = Calendar.getInstance()
        val start = fmt.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, days)
        val end = fmt.format(cal.time)
        val headers = mapOf(
            "accessToken" to (p.getString(Prefs.COROS_TOKEN, "") ?: ""),
            "yfheader" to "{\"userId\":\"${p.getString(Prefs.COROS_UID, "")}\"}"
        )
        return http("$host/training/schedule/query?startDate=$start&endDate=$end&supportRestExercise=1", null, headers)
    }

    private fun http(url: String, postBody: String?, headers: Map<String, String>): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (postBody != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(postBody.toByteArray()) }
        }
        val stream = if (c.responseCode in 200..299) c.inputStream else c.errorStream
        return JSONObject(stream.bufferedReader().readText())
    }

    fun sportLabel(code: Int): Pair<String, String> = when (code) {
        100 -> "🏃" to "Τρέξιμο"
        101 -> "🏃" to "Διάδρομος"
        102 -> "⛰️" to "Ορεινό τρέξιμο"
        103 -> "🏟️" to "Στίβος"
        104 -> "🥾" to "Πεζοπορία"
        200, 203, 204 -> "🚴" to "Ποδήλατο"
        201 -> "🚴" to "Ποδήλατο εσωτερικού"
        300 -> "🏊" to "Κολύμβηση"
        301 -> "🌊" to "Ανοιχτή θάλασσα"
        400 -> "❤️" to "Cardio"
        402 -> "🏋️" to "Ενδυνάμωση"
        900 -> "🚶" to "Περπάτημα"
        0 -> "😴" to "Ξεκούραση"
        else -> "⏱️" to "Προπόνηση"
    }
}
