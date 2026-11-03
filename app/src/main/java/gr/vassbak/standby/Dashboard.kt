package gr.vassbak.standby

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/** Όλη η λογική της οθόνης. Τη χρησιμοποιούν και η προφύλαξη οθόνης και η προεπισκόπηση. */
class Dashboard(
    private val ctx: Context,
    root: View,
    private val onNightChanged: (Boolean) -> Unit = {}
) {
    private val content: View = root.findViewById(R.id.content)
    private val clock: TextView = root.findViewById(R.id.clock)
    private val date: TextView = root.findViewById(R.id.date)
    private val weather: TextView = root.findViewById(R.id.weather)
    private val weatherDesc: TextView = root.findViewById(R.id.weatherDesc)
    private val battery: TextView = root.findViewById(R.id.battery)
    private val eventsTitle: TextView = root.findViewById(R.id.eventsTitle)
    private val events: LinearLayout = root.findViewById(R.id.events)

    private val handler = Handler(Looper.getMainLooper())
    private val el = Locale("el", "GR")
    private val timeFmt = SimpleDateFormat("HH:mm", el)
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", el)

    private var lastMinute = -1
    private var night: Boolean? = null

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    private val slowRefresh = object : Runnable {
        override fun run() {
            loadEvents()
            loadWeather()
            handler.postDelayed(this, 15 * 60 * 1000L)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = showBattery(i)
    }

    fun start() {
        ctx.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { showBattery(it) }
        handler.post(tick)
        handler.post(slowRefresh)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        try { ctx.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    // ---------- Ρολόι ----------

    private fun updateClock() {
        val now = Date()
        clock.text = timeFmt.format(now)
        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.MINUTE)
        if (minute == lastMinute) return
        lastMinute = minute

        date.text = dateFmt.format(now).replaceFirstChar { it.uppercase(el) }

        // Μικρή μετατόπιση κάθε λεπτό για να μη «καίγεται» η οθόνη
        val px = dp(10f)
        content.translationX = Random.nextFloat() * 2 * px - px
        content.translationY = Random.nextFloat() * 2 * px - px

        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isNight = Prefs.nightMode(ctx) && (hour >= 23 || hour < 7)
        if (isNight != night) {
            night = isNight
            applyTheme(isNight)
            onNightChanged(isNight)
        }

        if (minute % 5 == 0) loadEvents()
    }

    private fun applyTheme(isNight: Boolean) {
        val red = Color.parseColor("#FF4530")
        clock.setTextColor(if (isNight) red else Color.WHITE)
        date.setTextColor(if (isNight) red else Color.parseColor("#FFB340"))
        weather.setTextColor(if (isNight) red else Color.WHITE)
        battery.setTextColor(if (isNight) red else Color.parseColor("#7CD992"))
        content.alpha = if (isNight) 0.55f else 1f
        for (i in 0 until events.childCount) styleEvent(events.getChildAt(i) as TextView)
    }

    // ---------- Μπαταρία ----------

    private fun showBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0) level * 100 / scale else 0
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        battery.text = when {
            full -> "🔋 $pct% · Πλήρης"
            charging -> "⚡ $pct% · Φορτίζει"
            else -> "🔋 $pct%"
        }
    }

    // ---------- Ημερολόγιο ----------

    private fun loadEvents() {
        events.removeAllViews()
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            eventsTitle.visibility = View.GONE
            return
        }
        eventsTitle.visibility = View.VISIBLE
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 24 * 3600_000L)
            ContentUris.appendId(it, now + 48 * 3600_000L)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY
        )
        val lines = mutableListOf<String>()
        try {
            ctx.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext() && lines.size < 4) {
                    val title = c.getString(0) ?: "(χωρίς τίτλο)"
                    val begin = c.getLong(1)
                    val end = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    if (end <= now) continue
                    lines += "${whenLabel(begin, allDay)}  $title"
                }
            }
        } catch (_: Exception) {}

        if (lines.isEmpty()) lines += "Τίποτα για σήμερα και αύριο"
        for (line in lines) {
            val tv = TextView(ctx).apply {
                text = line
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(5f).toInt(), 0, 0)
            }
            styleEvent(tv)
            events.addView(tv)
        }
    }

    private fun styleEvent(tv: TextView) {
        tv.setTextColor(if (night == true) Color.parseColor("#FF4530") else Color.parseColor("#DDDDDD"))
    }

    private fun whenLabel(begin: Long, allDay: Boolean): String {
        // Τα ολοήμερα αποθηκεύονται σε UTC
        val c = Calendar.getInstance(if (allDay) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
        c.timeInMillis = begin
        val today = Calendar.getInstance()
        val dayDiff = daysBetween(today, c)
        val day = when (dayDiff) {
            in Int.MIN_VALUE..0 -> "Σήμερα"
            1 -> "Αύριο"
            else -> SimpleDateFormat("EEE", el).format(Date(begin))
        }
        return if (allDay) "$day ·" else if (dayDiff <= 0) timeFmt.format(Date(begin)) else "$day ${timeFmt.format(Date(begin))}"
    }

    private fun daysBetween(a: Calendar, b: Calendar): Int {
        val ya = a.get(Calendar.YEAR) * 400 + a.get(Calendar.DAY_OF_YEAR)
        val yb = b.get(Calendar.YEAR) * 400 + b.get(Calendar.DAY_OF_YEAR)
        return yb - ya
    }

    // ---------- Καιρός (Open-Meteo, χωρίς κλειδί) ----------

    private fun loadWeather() {
        val (lat, lon) = coords()
        Thread {
            try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                        "&current=temperature_2m,weather_code" +
                        "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
                )
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 10000
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val cur = json.getJSONObject("current")
                val temp = Math.round(cur.getDouble("temperature_2m"))
                val code = cur.getInt("weather_code")
                val daily = json.getJSONObject("daily")
                val max = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0))
                val min = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0))
                val (icon, desc) = weatherText(code)
                handler.post {
                    weather.text = "$icon $temp°"
                    weatherDesc.text = "$desc · ↑$max° ↓$min°"
                }
            } catch (_: Exception) {
                handler.post { if (weather.text.toString() == "…") weatherDesc.text = "Χωρίς σύνδεση για καιρό" }
            }
        }.start()
    }

    private fun coords(): Pair<Double, Double> {
        val p = Prefs.get(ctx)
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                var best: Location? = null
                for (prov in lm.getProviders(true)) {
                    val l = lm.getLastKnownLocation(prov) ?: continue
                    if (best == null || l.time > best.time) best = l
                }
                if (best != null) {
                    p.edit().putFloat(Prefs.KEY_LAT, best.latitude.toFloat())
                        .putFloat(Prefs.KEY_LON, best.longitude.toFloat()).apply()
                    return best.latitude to best.longitude
                }
            } catch (_: SecurityException) {}
        }
        // Τελευταία γνωστή θέση ή Αθήνα
        return p.getFloat(Prefs.KEY_LAT, 37.98f).toDouble() to p.getFloat(Prefs.KEY_LON, 23.73f).toDouble()
    }

    private fun weatherText(code: Int): Pair<String, String> = when (code) {
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

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
}
