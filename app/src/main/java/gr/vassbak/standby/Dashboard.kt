package gr.vassbak.standby

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Όλη η οθόνη StandBy: σελίδες με swipe
 * (Αρχική · COROS · Ημερολόγιο · Καιρός · Ειδοποιήσεις).
 * Τη χρησιμοποιούν και η προφύλαξη οθόνης και η προεπισκόπηση.
 */
class Dashboard(
    private val ctx: Context,
    private val onExit: () -> Unit = {},
    private val onNightChanged: (Boolean) -> Unit = {}
) {
    val view = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK) }
    private val pager = Pager(ctx)
    private val dots = LinearLayout(ctx)

    private val handler = Handler(Looper.getMainLooper())
    private val el = Locale("el", "GR")
    private val timeFmt = SimpleDateFormat("HH:mm", el)
    private val dateFmt = SimpleDateFormat("EEEE d MMMM", el)
    private val shortDay = SimpleDateFormat("EEE d/M", el)

    private val white = Color.WHITE
    private val grey = Color.parseColor("#9A9A9A")
    private val dim = Color.parseColor("#666666")
    private val amber = Color.parseColor("#FFB340")
    private val blue = Color.parseColor("#5AC8FA")
    private val green = Color.parseColor("#7CD992")

    // Αρχική σελίδα (από το dashboard.xml)
    private val home: View = LayoutInflater.from(ctx).inflate(R.layout.dashboard, pager, false)
    private val clock: TextView = home.findViewById(R.id.clock)
    private val date: TextView = home.findViewById(R.id.date)
    private val weather: TextView = home.findViewById(R.id.weather)
    private val weatherDesc: TextView = home.findViewById(R.id.weatherDesc)
    private val battery: TextView = home.findViewById(R.id.battery)
    private val corosToday: TextView = home.findViewById(R.id.corosToday)
    private val eventsTitle: TextView = home.findViewById(R.id.eventsTitle)
    private val events: LinearLayout = home.findViewById(R.id.events)

    // Περιεχόμενο των υπόλοιπων σελίδων
    private val smallClocks = mutableListOf<TextView>()
    private val corosBody: LinearLayout
    private val calBody: LinearLayout
    private val weatherBody: LinearLayout
    private val notifBody: LinearLayout

    private var lastMinute = -1
    private var night: Boolean? = null
    private var lastTouch = System.currentTimeMillis()

    init {
        home.background = null
        pager.addView(home)
        corosBody = addPage("COROS · ΠΡΟΓΡΑΜΜΑ", scroll = false)
        calBody = addPage("ΗΜΕΡΟΛΟΓΙΟ · 7 ΗΜΕΡΕΣ", scroll = true)
        weatherBody = addPage("ΚΑΙΡΟΣ", scroll = false)
        notifBody = addPage("ΕΙΔΟΠΟΙΗΣΕΙΣ", scroll = true)

        view.addView(pager, FrameLayout.LayoutParams(-1, -1))
        dots.gravity = Gravity.CENTER
        view.addView(dots, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = dp(10f).toInt()
        })
        buildDots()

        pager.onPageChanged = { updateDots(it) }
        pager.onDoubleTap = { onExit() }
        pager.onInteraction = { lastTouch = System.currentTimeMillis() }
    }

    // ---------- Κύκλος ζωής ----------

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    private val weatherLoop = object : Runnable {
        override fun run() { loadWeather(); handler.postDelayed(this, 15 * 60_000L) }
    }

    private val corosLoop = object : Runnable {
        override fun run() { loadCoros(); handler.postDelayed(this, 30 * 60_000L) }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = showBattery(i)
    }

    fun start() {
        ctx.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { showBattery(it) }
        NotifListener.onChange = { handler.post { loadNotifications() } }
        handler.post(tick)
        handler.post(weatherLoop)
        handler.post(corosLoop)
        loadEvents()
        loadNotifications()
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        NotifListener.onChange = null
        try { ctx.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    // ---------- Ρολόι, νύχτα, προστασία οθόνης ----------

    private fun updateClock() {
        val now = Date()
        val t = timeFmt.format(now)
        clock.text = t
        for (c in smallClocks) c.text = t

        // Επιστροφή στην αρχική μετά από 90" χωρίς άγγιγμα
        if (pager.current != 0 && System.currentTimeMillis() - lastTouch > 90_000) pager.goTo(0)

        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.MINUTE)
        if (minute == lastMinute) return
        lastMinute = minute

        date.text = dateFmt.format(now).replaceFirstChar { it.uppercase(el) }

        // Μικρή μετατόπιση κάθε λεπτό για να μη «καίγεται» η οθόνη
        val px = dp(8f)
        pager.translationX = Random.nextFloat() * 2 * px - px
        pager.translationY = Random.nextFloat() * 2 * px - px

        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isNight = Prefs.nightMode(ctx) && (hour >= 23 || hour < 7)
        if (isNight != night) {
            night = isNight
            applyNight(isNight)
            onNightChanged(isNight)
        }

        if (minute % 5 == 0) loadEvents()
    }

    /** Τη νύχτα όλα γίνονται κόκκινα και χαμηλωμένα, σε όλες τις σελίδες. */
    private fun applyNight(isNight: Boolean) {
        if (isNight) {
            val m = ColorMatrix(floatArrayOf(
                0.30f, 0.59f, 0.11f, 0f, 0f,
                0.05f, 0.10f, 0.02f, 0f, 0f,
                0.03f, 0.06f, 0.01f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            view.setLayerType(View.LAYER_TYPE_HARDWARE, Paint().apply { colorFilter = ColorMatrixColorFilter(m) })
            view.alpha = 0.6f
        } else {
            view.setLayerType(View.LAYER_TYPE_NONE, null)
            view.alpha = 1f
        }
    }

    // ---------- Μπαταρία ----------

    private fun showBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0) level * 100 / scale else 0
        battery.text = when (status) {
            BatteryManager.BATTERY_STATUS_FULL -> "🔋 $pct% · Πλήρης"
            BatteryManager.BATTERY_STATUS_CHARGING -> "⚡ $pct% · Φορτίζει"
            else -> "🔋 $pct%"
        }
    }

    // ---------- Ημερολόγιο ----------

    private data class Ev(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int)

    private fun queryEvents(daysAhead: Int): List<Ev>? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val now = System.currentTimeMillis()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now - 24 * 3600_000L)
            ContentUris.appendId(it, now + daysAhead * 24 * 3600_000L)
        }.build()
        val proj = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR
        )
        val out = mutableListOf<Ev>()
        try {
            ctx.contentResolver.query(uri, proj, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val end = c.getLong(2)
                    if (end <= now) continue
                    out += Ev(c.getString(0) ?: "(χωρίς τίτλο)", c.getLong(1), end, c.getInt(3) == 1, c.getInt(4))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /** Πόσες μέρες από σήμερα (τα ολοήμερα είναι σε UTC). */
    private fun dayOffset(ev: Ev): Int {
        val c = Calendar.getInstance(if (ev.allDay) TimeZone.getTimeZone("UTC") else TimeZone.getDefault())
        c.timeInMillis = ev.begin
        val today = Calendar.getInstance()
        val diff = (c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)) -
            (today.get(Calendar.YEAR) * 400 + today.get(Calendar.DAY_OF_YEAR))
        return maxOf(0, diff)
    }

    private fun dayName(offset: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, offset)
        return when (offset) {
            0 -> "Σήμερα"
            1 -> "Αύριο"
            else -> shortDay.format(c.time).replaceFirstChar { it.uppercase(el) }
        }
    }

    private fun loadEvents() {
        val list = queryEvents(8)

        // Αρχική: τα 4 επόμενα (σήμερα/αύριο)
        events.removeAllViews()
        if (list == null) {
            eventsTitle.visibility = View.GONE
        } else {
            eventsTitle.visibility = View.VISIBLE
            val near = list.filter { dayOffset(it) <= 1 }.take(4)
            if (near.isEmpty()) events.addView(row("Τίποτα για σήμερα και αύριο", grey))
            for (e in near) {
                val off = dayOffset(e)
                val whenTxt = when {
                    e.allDay -> "${dayName(off)} ·"
                    off == 0 -> timeFmt.format(Date(e.begin))
                    else -> "Αύριο ${timeFmt.format(Date(e.begin))}"
                }
                events.addView(row("$whenTxt  ${e.title}", Color.parseColor("#DDDDDD")))
            }
        }

        // Σελίδα ημερολογίου: 7 μέρες
        calBody.removeAllViews()
        if (list == null) {
            calBody.addView(row("Δώσε άδεια ημερολογίου από την εφαρμογή StandBy.", grey, 18f))
            return
        }
        val byDay = list.groupBy { dayOffset(it) }.filterKeys { it <= 6 }
        for (d in 0..6) {
            val evs = byDay[d] ?: continue
            calBody.addView(tv(dayName(d), 15f, amber).apply { setPadding(0, dp(12f).toInt(), 0, dp(2f).toInt()) })
            for (e in evs) {
                val line = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(4f).toInt(), 0, dp(4f).toInt())
                }
                line.addView(View(ctx).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(e.color) }
                }, LinearLayout.LayoutParams(dp(10f).toInt(), dp(10f).toInt()).apply { marginEnd = dp(12f).toInt() })
                val t = if (e.allDay) "Ολοήμερο" else "${timeFmt.format(Date(e.begin))}–${timeFmt.format(Date(e.end))}"
                line.addView(tv(t, 17f, grey), LinearLayout.LayoutParams(dp(120f).toInt(), -2))
                line.addView(tv(e.title, 19f, white).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                    LinearLayout.LayoutParams(0, -2, 1f))
                calBody.addView(line)
            }
        }
        if (byDay.isEmpty()) calBody.addView(row("Καμία εκδήλωση την επόμενη εβδομάδα.", grey, 18f))
    }

    // ---------- COROS ----------

    private fun loadCoros() {
        if (!Coros.configured(ctx)) {
            renderCoros(null, "Σύνδεσε τον λογαριασμό COROS από την εφαρμογή StandBy.")
            return
        }
        Thread {
            try {
                val list = Coros.schedule(ctx, 7)
                handler.post { renderCoros(list, null) }
            } catch (e: Exception) {
                handler.post { renderCoros(null, "COROS: ${e.message ?: "σφάλμα"}") }
            }
        }.start()
    }

    private fun renderCoros(list: List<Coros.Workout>?, error: String?) {
        corosBody.removeAllViews()
        if (list == null) {
            corosToday.visibility = View.GONE
            corosBody.addView(row(error ?: "", grey, 18f))
            return
        }
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val todayKey = fmt.format(Date())
        val today = list.filter { it.day == todayKey }
        val next = list.filter { it.day > todayKey }

        // Αρχική σελίδα: μία γραμμή
        val main = today.firstOrNull { !it.completed } ?: today.firstOrNull()
        if (main != null) {
            val (icon, _) = Coros.sportLabel(main.sport)
            corosToday.text = "$icon ${main.name}" + (if (main.minutes > 0) " · ${main.minutes}′" else "") +
                (if (main.completed) " ✓" else "")
            corosToday.visibility = View.VISIBLE
        } else {
            corosToday.visibility = View.GONE
        }

        val cols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val left = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        left.addView(tv("ΣΗΜΕΡΑ", 13f, dim).apply { letterSpacing = 0.15f })
        if (today.isEmpty()) {
            left.addView(tv("😴 Ξεκούραση", 34f, white, "sans-serif-light"))
        }
        for (w in today) {
            val (icon, sport) = Coros.sportLabel(w.sport)
            left.addView(tv("$icon ${w.name}", 32f, if (w.completed) green else white, "sans-serif-light").apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(6f).toInt(), 0, 0)
            })
            val sub = buildString {
                append(sport)
                if (w.minutes > 0) append(" · ${w.minutes}′")
                if (w.completed) append(" · ✓ Ολοκληρώθηκε")
            }
            left.addView(tv(sub, 17f, if (w.completed) green else blue))
        }
        cols.addView(left, LinearLayout.LayoutParams(0, -1, 1.1f))

        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        right.addView(tv("ΕΠΟΜΕΝΕΣ ΜΕΡΕΣ", 13f, dim).apply { letterSpacing = 0.15f })
        val scroll = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
        val list2 = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (next.isEmpty()) list2.addView(row("Τίποτα προγραμματισμένο", grey))
        for (w in next) {
            val d = try { fmt.parse(w.day) } catch (_: Exception) { null }
            val label = if (d == null) w.day else {
                val diff = ((d.time - fmt.parse(todayKey)!!.time) / 86_400_000L).toInt()
                dayName(diff)
            }
            val (icon, _) = Coros.sportLabel(w.sport)
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6f).toInt(), 0, dp(6f).toInt())
            }
            line.addView(tv(label, 16f, amber), LinearLayout.LayoutParams(dp(110f).toInt(), -2))
            line.addView(tv("$icon ${w.name}" + (if (w.minutes > 0) " · ${w.minutes}′" else ""), 17f, white).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            list2.addView(line)
        }
        scroll.addView(list2)
        right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        cols.addView(right, LinearLayout.LayoutParams(0, -1, 1f))

        corosBody.addView(cols, LinearLayout.LayoutParams(-1, -1))
    }

    // ---------- Καιρός ----------

    private fun loadWeather() {
        val (lat, lon) = coords()
        Thread {
            try {
                val data = Weather.fetch(lat, lon)
                handler.post { renderWeather(data) }
            } catch (_: Exception) {
                handler.post {
                    if (weather.text.toString() == "…") weatherDesc.text = "Χωρίς σύνδεση για καιρό"
                }
            }
        }.start()
    }

    private fun renderWeather(w: Weather.Data) {
        val (icon, desc) = Weather.text(w.code)
        val today = w.days.firstOrNull()
        weather.text = "$icon ${w.temp}°"
        weatherDesc.text = if (today != null) "$desc · ↑${today.max}° ↓${today.min}°" else desc

        weatherBody.removeAllViews()
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(tv("$icon ${w.temp}°", 44f, white, "sans-serif-light"))
        top.addView(tv(
            if (today != null) "  $desc\n  ↑${today.max}° ↓${today.min}° · βροχή ${today.rain}%" else "  $desc",
            16f, grey
        ))
        weatherBody.addView(top)

        // Επόμενες ώρες (κάθε 2 ώρες, 8 στήλες)
        weatherBody.addView(columns(w.hours.filterIndexed { i, _ -> i % 2 == 0 }.take(8).map { h ->
            Triple(timeFmt.format(Date(h.time)), "${Weather.text(h.code).first}\n${h.temp}°",
                if (h.rain >= 20) "💧${h.rain}%" else " ")
        }), LinearLayout.LayoutParams(-1, 0, 1f))

        // Επόμενες 5 μέρες
        val dayFmt = SimpleDateFormat("EEE", el)
        weatherBody.addView(columns(w.days.drop(1).take(5).map { d ->
            Triple(dayFmt.format(Date(d.date)).replaceFirstChar { it.uppercase(el) },
                "${Weather.text(d.code).first}\n${d.max}° / ${d.min}°",
                if (d.rain >= 20) "💧${d.rain}%" else " ")
        }), LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun columns(items: List<Triple<String, String, String>>): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for ((a, b, c) in items) {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(tv(a, 14f, amber))
            col.addView(tv(b, 18f, white).apply { gravity = Gravity.CENTER })
            col.addView(tv(c, 12f, blue))
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return row
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
        return p.getFloat(Prefs.KEY_LAT, 37.98f).toDouble() to p.getFloat(Prefs.KEY_LON, 23.73f).toDouble()
    }

    // ---------- Ειδοποιήσεις ----------

    private fun loadNotifications() {
        notifBody.removeAllViews()
        if (!NotifListener.enabled(ctx)) {
            notifBody.addView(row("Δώσε πρόσβαση στις ειδοποιήσεις από την εφαρμογή StandBy.", grey, 18f))
            return
        }
        val items = NotifListener.current(ctx)
        if (items.isEmpty()) {
            notifBody.addView(row("Καμία ειδοποίηση 🎉", grey, 18f))
            return
        }
        for (n in items) {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8f).toInt(), 0, dp(8f).toInt())
            }
            box.addView(tv("${n.app} · ${timeFmt.format(Date(n.time))}", 13f, amber))
            if (n.title.isNotBlank()) box.addView(tv(n.title, 18f, white).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            if (n.text.isNotBlank()) box.addView(tv(n.text, 15f, grey).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            notifBody.addView(box)
        }
    }

    // ---------- Βοηθητικά για τις σελίδες ----------

    /** Φτιάχνει σελίδα με τίτλο και μικρό ρολόι, επιστρέφει το σώμα της. */
    private fun addPage(title: String, scroll: Boolean): LinearLayout {
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40f).toInt(), dp(18f).toInt(), dp(40f).toInt(), dp(26f).toInt())
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(tv(title, 13f, dim).apply { letterSpacing = 0.15f }, LinearLayout.LayoutParams(0, -2, 1f))
        val small = tv("", 18f, grey)
        smallClocks += small
        header.addView(small)
        page.addView(header)

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (scroll) {
            val sv = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false }
            sv.addView(body)
            page.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8f).toInt() })
        }
        pager.addView(page)
        return body
    }

    private fun buildDots() {
        dots.removeAllViews()
        for (i in 0 until pager.childCount) {
            dots.addView(View(ctx), LinearLayout.LayoutParams(dp(7f).toInt(), dp(7f).toInt()).apply {
                marginStart = dp(4f).toInt(); marginEnd = dp(4f).toInt()
            })
        }
        updateDots(0)
    }

    private fun updateDots(page: Int) {
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (i == page) Color.parseColor("#BBBBBB") else Color.parseColor("#444444"))
            }
        }
    }

    private fun tv(text: String, size: Float, color: Int, font: String = "sans-serif") = TextView(ctx).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        typeface = android.graphics.Typeface.create(font, android.graphics.Typeface.NORMAL)
    }

    private fun row(text: String, color: Int, size: Float = 16f) = tv(text, size, color).apply {
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(5f).toInt(), 0, 0)
        layoutParams = ViewGroup.LayoutParams(-1, -2)
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)
}
