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
import android.graphics.Typeface
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
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Η οθόνη StandBy: σελίδες με κάθετο swipe
 * (Αρχική · COROS · Ημερολόγιο · Καιρός · Ειδοποιήσεις), κάθε κατηγορία σε δικό της χρωματιστό πλαίσιο.
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

    // Χρώματα ανά κατηγορία
    private val cWeather = Color.parseColor("#5AC8FA")
    private val cBattery = Color.parseColor("#30D158")
    private val cCoros = Color.parseColor("#FF9F0A")
    private val cCal = Color.parseColor("#BF5AF2")
    private val cNotif = Color.parseColor("#FF375F")
    private val cNav = Color.parseColor("#34C759")

    private val white = Color.WHITE
    private val soft = Color.parseColor("#DDDDDD")
    private val grey = Color.parseColor("#9A9A9A")
    private val dim = Color.parseColor("#666666")
    private val amber = Color.parseColor("#FFB340")

    // Αρχική σελίδα
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var homeWeather: LinearLayout
    private lateinit var homeBattery: LinearLayout
    private lateinit var homeCorosCard: View
    private lateinit var homeCoros: LinearLayout
    private lateinit var homeEventsCard: View
    private lateinit var homeEvents: LinearLayout
    private lateinit var clockBlock: View
    private lateinit var navBlock: View
    private lateinit var navArrow: ImageView
    private lateinit var navDistance: TextView
    private lateinit var navInstruction: TextView
    private lateinit var navEta: TextView
    private lateinit var navClock: TextView
    private var navActive = false

    // Σώματα των άλλων σελίδων
    private val smallClocks = mutableListOf<TextView>()
    private val corosBody: LinearLayout
    private val calBody: LinearLayout
    private val weatherBody: LinearLayout
    private val notifBody: LinearLayout

    private var weatherText = "…"
    private var lastMinute = -1
    private var night: Boolean? = null
    private var lastTouch = System.currentTimeMillis()

    init {
        pager.addView(buildHome())
        corosBody = addPage("COROS · ΠΡΟΓΡΑΜΜΑ")
        calBody = addPage("ΗΜΕΡΟΛΟΓΙΟ · 7 ΗΜΕΡΕΣ")
        weatherBody = addPage("ΚΑΙΡΟΣ")
        notifBody = addPage("ΕΙΔΟΠΟΙΗΣΕΙΣ")

        view.addView(pager, FrameLayout.LayoutParams(-1, -1))
        dots.orientation = LinearLayout.VERTICAL
        dots.gravity = Gravity.CENTER
        view.addView(dots, FrameLayout.LayoutParams(-2, -1, Gravity.END).apply { marginEnd = dp(12) })
        buildDots()

        pager.onPageChanged = { updateDots(it) }
        pager.onDoubleTap = { onExit() }
        pager.onInteraction = { lastTouch = System.currentTimeMillis() }

        setText(homeWeather, listOf(tv("…", 26f, white, light = true)))
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
        State.showing = true
        ctx.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { showBattery(it) }
        NotifListener.onChange = { handler.post { loadNotifications() } }
        handler.post(tick)
        handler.post(weatherLoop)
        handler.post(corosLoop)
        loadEvents()
        loadNotifications()
    }

    fun stop() {
        if (State.showing) State.lastStop = System.currentTimeMillis()
        State.showing = false
        handler.removeCallbacksAndMessages(null)
        NotifListener.onChange = null
        try { ctx.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    // ---------- Αρχική σελίδα ----------

    private fun buildHome(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(32), dp(18), dp(40), dp(18))
        }

        val left = FrameLayout(ctx)
        val clockCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        clock = tv("00:00", 112f, white, thin = true).apply { includeFontPadding = false }
        date = tv("", 22f, amber, light = true).apply { setPadding(dp(6), 0, 0, 0) }
        clockCol.addView(clock)
        clockCol.addView(date)
        clockBlock = clockCol
        left.addView(clockCol, FrameLayout.LayoutParams(-1, -1))

        // Πλοήγηση Google Maps: εμφανίζεται στη θέση του ρολογιού όσο τρέχει
        val (navCard, navBody) = card(cNav, "ΠΛΟΗΓΗΣΗ")
        navClock = tv("", 16f, grey, light = true)
        val navRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        navArrow = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        navRow.addView(navArrow, LinearLayout.LayoutParams(dp(110), dp(110)).apply { marginEnd = dp(14) })
        val navText = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        navDistance = tv("", 46f, white, light = true).apply { maxLines = 1; includeFontPadding = false }
        navInstruction = tv("", 21f, soft).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        navText.addView(navDistance)
        navText.addView(navInstruction)
        navRow.addView(navText, LinearLayout.LayoutParams(0, -2, 1f))
        navBody.gravity = Gravity.CENTER_VERTICAL
        navBody.addView(navRow)
        navEta = tv("", 16f, cNav).apply { setPadding(0, dp(10), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        navBody.addView(navEta)
        navCard.addView(navClock, 0)
        navBlock = navCard
        navCard.visibility = View.GONE
        left.addView(navCard, FrameLayout.LayoutParams(-1, -1).apply { marginEnd = dp(10) })
        smallClocks += navClock

        root.addView(left, LinearLayout.LayoutParams(0, -1, 1.2f))

        val right = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        val (wCard, wBody) = card(cWeather, "ΚΑΙΡΟΣ")
        homeWeather = wBody
        right.addView(wCard, lp(-1, -2))

        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val (bCard, bBody) = card(cBattery, "ΜΠΑΤΑΡΙΑ")
        homeBattery = bBody
        row.addView(bCard, LinearLayout.LayoutParams(0, -1, 1f))
        val (cCard, cBody) = card(cCoros, "COROS ΣΗΜΕΡΑ")
        homeCorosCard = cCard
        homeCoros = cBody
        row.addView(cCard, LinearLayout.LayoutParams(0, -1, 1.6f).apply { marginStart = dp(8) })
        right.addView(row, lp(-1, -2).apply { topMargin = dp(8) })

        val (eCard, eBody) = card(cCal, "ΕΠΟΜΕΝΑ")
        homeEventsCard = eCard
        homeEvents = eBody
        right.addView(eCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })

        root.addView(right, LinearLayout.LayoutParams(0, -1, 1f))
        return root
    }

    // ---------- Ρολόι, νύχτα, προστασία οθόνης ----------

    private fun updateClock() {
        val now = Date()
        val t = timeFmt.format(now)
        clock.text = t
        for (c in smallClocks) c.text = t

        if (pager.current != 0 && System.currentTimeMillis() - lastTouch > 90_000) pager.goTo(0)

        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.MINUTE)
        if (minute == lastMinute) return
        lastMinute = minute

        date.text = dateFmt.format(now).replaceFirstChar { it.uppercase(el) }

        val px = dp(6).toFloat()
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
        val sub = when (status) {
            BatteryManager.BATTERY_STATUS_FULL -> "Πλήρης"
            BatteryManager.BATTERY_STATUS_CHARGING -> "Φορτίζει"
            else -> ""
        }
        val icon = if (status == BatteryManager.BATTERY_STATUS_CHARGING) "⚡" else "🔋"
        setText(homeBattery, listOfNotNull(
            tv("$icon $pct%", 22f, white, light = true),
            if (sub.isNotEmpty()) tv(sub, 13f, cBattery) else null
        ))
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

        // Αρχική
        if (list == null) {
            homeEventsCard.visibility = View.GONE
        } else {
            homeEventsCard.visibility = View.VISIBLE
            val near = list.filter { dayOffset(it) <= 1 }.take(3)
            val rows = near.map { e ->
                val off = dayOffset(e)
                val whenTxt = when {
                    e.allDay -> if (off == 0) "Σήμερα" else "Αύριο"
                    off == 0 -> timeFmt.format(Date(e.begin))
                    else -> "Αύρ. ${timeFmt.format(Date(e.begin))}"
                }
                eventLine(whenTxt, e.title, e.color, 15f)
            }
            setText(homeEvents, rows.ifEmpty { listOf(tv("Τίποτα για σήμερα και αύριο", 14f, grey)) })
        }

        // Σελίδα ημερολογίου: πλέγμα 4 + 3 ημερών
        calBody.removeAllViews()
        if (list == null) {
            calBody.addView(tv("Δώσε άδεια ημερολογίου από την εφαρμογή StandBy.", 18f, grey))
            return
        }
        val byDay = list.groupBy { dayOffset(it) }
        for (r in 0..1) {
            val rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val range = if (r == 0) 0..3 else 4..6
            for (d in range) {
                val (c, body) = card(if (d == 0) cCal else Color.parseColor("#8E6BB0"), dayName(d).uppercase(el))
                val evs = byDay[d].orEmpty()
                if (evs.isEmpty()) body.addView(tv("—", 14f, dim))
                for (e in evs.take(3)) {
                    val t = if (e.allDay) "Ολοήμ." else timeFmt.format(Date(e.begin))
                    body.addView(eventLine(t, e.title, e.color, 13f))
                }
                if (evs.size > 3) body.addView(tv("+${evs.size - 3} ακόμα", 12f, grey))
                rowView.addView(c, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (d != range.first) marginStart = dp(8)
                })
            }
            // η 2η σειρά έχει 3 κάρτες: κενό στο τέλος για ίδιο πλάτος
            if (r == 1) rowView.addView(View(ctx), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
            calBody.addView(rowView, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (r == 1) topMargin = dp(8) })
        }
    }

    private fun eventLine(whenTxt: String, title: String, color: Int, size: Float): View {
        val line = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(2), 0, dp(2))
        }
        line.addView(View(ctx).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        }, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(6) })
        line.addView(tv(whenTxt, size, grey).apply { setPadding(0, 0, dp(6), 0) })
        line.addView(tv(title, size, soft).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        return line
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
            homeCorosCard.visibility = View.GONE
            corosBody.addView(tv(error ?: "", 18f, grey))
            return
        }
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val todayKey = fmt.format(Date())
        val todayStart = fmt.parse(todayKey)!!.time
        val today = list.filter { it.day == todayKey }
        val next = list.filter { it.day > todayKey }

        // Αρχική
        homeCorosCard.visibility = View.VISIBLE
        val main = today.firstOrNull { !it.completed } ?: today.firstOrNull()
        setText(homeCoros, listOf(
            if (main == null) tv("😴 Ξεκούραση", 17f, white)
            else tv("${Coros.sportLabel(main.sport).first} ${main.name}" + (if (main.completed) " ✓" else ""),
                17f, white).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            tv(if (main != null && main.minutes > 0) "${main.minutes}′" else " ", 13f, cCoros)
        ))

        val cols = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        val (todayCard, tBody) = card(cCoros, "ΣΗΜΕΡΑ")
        tBody.gravity = Gravity.CENTER_VERTICAL
        if (today.isEmpty()) tBody.addView(tv("😴 Ξεκούραση", 32f, white, light = true))
        for (w in today) {
            val (icon, sport) = Coros.sportLabel(w.sport)
            tBody.addView(tv("$icon ${w.name}", 30f, if (w.completed) cBattery else white, light = true).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
            tBody.addView(tv(buildString {
                append(sport)
                if (w.minutes > 0) append(" · ${w.minutes}′")
                if (w.completed) append(" · ✓ Ολοκληρώθηκε")
            }, 16f, if (w.completed) cBattery else cCoros))
        }
        cols.addView(todayCard, LinearLayout.LayoutParams(0, -1, 1.1f))

        val (nextCard, nBody) = card(cCoros, "ΕΠΟΜΕΝΕΣ ΜΕΡΕΣ")
        if (next.isEmpty()) nBody.addView(tv("Τίποτα προγραμματισμένο", 15f, grey))
        for (w in next.take(7)) {
            val d = try { fmt.parse(w.day)?.time } catch (_: Exception) { null }
            val label = if (d == null) w.day else dayName(Math.round((d - todayStart) / 86_400_000.0).toInt())
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            line.addView(tv(label, 14f, cCoros), LinearLayout.LayoutParams(dp(92), -2))
            line.addView(tv("${Coros.sportLabel(w.sport).first} ${w.name}" +
                (if (w.minutes > 0) " · ${w.minutes}′" else ""), 15f, soft).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            nBody.addView(line)
        }
        cols.addView(nextCard, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(10) })

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
                    if (weatherText == "…") setText(homeWeather, listOf(tv("Χωρίς σύνδεση", 15f, grey)))
                }
            }
        }.start()
    }

    private fun renderWeather(w: Weather.Data) {
        val (icon, desc) = Weather.text(w.code)
        val today = w.days.firstOrNull()
        weatherText = "$icon ${w.temp}°"
        val range = if (today != null) "↑${today.max}° ↓${today.min}°" else ""

        // Αρχική
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(tv(weatherText, 26f, white, light = true))
        line.addView(tv("   $desc\n   $range", 13f, grey))
        setText(homeWeather, listOf(line))

        // Σελίδα καιρού
        weatherBody.removeAllViews()
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

        val (nowCard, nowBody) = card(cWeather, "ΤΩΡΑ")
        nowBody.gravity = Gravity.CENTER_VERTICAL
        nowBody.addView(tv(weatherText, 40f, white, light = true))
        nowBody.addView(tv(desc, 15f, soft))
        if (today != null) nowBody.addView(tv("$range · 💧${today.rain}%", 14f, cWeather))
        top.addView(nowCard, LinearLayout.LayoutParams(0, -1, 1f))

        val (daysCard, daysBody) = card(cWeather, "ΕΠΟΜΕΝΕΣ ΜΕΡΕΣ")
        val dayFmt = SimpleDateFormat("EEE", el)
        daysBody.addView(columns(w.days.drop(1).take(5).map { d ->
            Triple(dayFmt.format(Date(d.date)).replaceFirstChar { it.uppercase(el) },
                "${Weather.text(d.code).first}\n${d.max}°/${d.min}°",
                if (d.rain >= 20) "💧${d.rain}%" else " ")
        }), LinearLayout.LayoutParams(-1, -1))
        top.addView(daysCard, LinearLayout.LayoutParams(0, -1, 2f).apply { marginStart = dp(8) })

        weatherBody.addView(top, LinearLayout.LayoutParams(-1, 0, 1f))

        val (hCard, hBody) = card(cWeather, "ΕΠΟΜΕΝΕΣ ΩΡΕΣ")
        hBody.addView(columns(w.hours.filterIndexed { i, _ -> i % 2 == 0 }.take(8).map { h ->
            Triple(timeFmt.format(Date(h.time)), "${Weather.text(h.code).first}\n${h.temp}°",
                if (h.rain >= 20) "💧${h.rain}%" else " ")
        }), LinearLayout.LayoutParams(-1, -1))
        weatherBody.addView(hCard, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
    }

    private fun columns(items: List<Triple<String, String, String>>): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for ((a, b, c) in items) {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(tv(a, 13f, cWeather))
            col.addView(tv(b, 16f, white).apply { gravity = Gravity.CENTER })
            col.addView(tv(c, 11f, grey))
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

    // ---------- Ειδοποιήσεις: πλέγμα 3 x 2 ----------

    private fun updateNav() {
        val n = NotifListener.nav(ctx)
        if (n == null) {
            if (navActive) { navActive = false; navBlock.visibility = View.GONE; clockBlock.visibility = View.VISIBLE }
            return
        }
        if (!navActive) {
            navActive = true
            navBlock.visibility = View.VISIBLE
            clockBlock.visibility = View.GONE
            pager.goTo(0)
        }
        navDistance.text = n.distance
        navInstruction.text = n.instruction
        navEta.text = n.eta
        if (n.arrow != null) { navArrow.setImageDrawable(n.arrow); navArrow.visibility = View.VISIBLE }
        else navArrow.visibility = View.GONE
    }

    private fun loadNotifications() {
        updateNav()
        notifBody.removeAllViews()
        if (!NotifListener.enabled(ctx)) {
            notifBody.addView(tv("Δώσε πρόσβαση στις ειδοποιήσεις από την εφαρμογή StandBy.", 18f, grey))
            return
        }
        val items = NotifListener.current(ctx).take(6)
        if (items.isEmpty()) {
            notifBody.addView(tv("Καμία ειδοποίηση 🎉", 20f, grey))
            return
        }
        for (r in 0..1) {
            val rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0..2) {
                val n = items.getOrNull(r * 3 + c)
                val lpCard = LinearLayout.LayoutParams(0, -1, 1f).apply { if (c > 0) marginStart = dp(8) }
                if (n == null) { rowView.addView(View(ctx), lpCard); continue }
                val (cardView, body) = card(cNotif, "${n.app} · ${timeFmt.format(Date(n.time))}".uppercase(el))
                if (n.title.isNotBlank()) body.addView(tv(n.title, 16f, white).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                if (n.text.isNotBlank()) body.addView(tv(n.text, 14f, grey).apply {
                    maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                })
                rowView.addView(cardView, lpCard)
            }
            notifBody.addView(rowView, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (r == 1) topMargin = dp(8) })
        }
    }

    // ---------- Βοηθητικά ----------

    /** Χρωματιστό πλαίσιο: διάφανο φόντο στο χρώμα της κατηγορίας, λεπτό περίγραμμα και τίτλος. */
    private fun card(accent: Int, title: String): Pair<LinearLayout, LinearLayout> {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(withAlpha(accent, 0x26))
                setStroke(dp(1), withAlpha(accent, 0x80))
            }
        }
        outer.addView(tv(title, 11f, accent).apply {
            letterSpacing = 0.12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
        return outer to body
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    private fun setText(container: LinearLayout, views: List<View>) {
        container.removeAllViews()
        for (v in views) container.addView(v)
    }

    /** Σελίδα με τίτλο και μικρό ρολόι· επιστρέφει το σώμα της. */
    private fun addPage(title: String): LinearLayout {
        val page = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(14), dp(40), dp(18))
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(tv(title, 13f, dim).apply { letterSpacing = 0.15f }, LinearLayout.LayoutParams(0, -2, 1f))
        val small = tv("", 18f, grey, light = true)
        smallClocks += small
        header.addView(small)
        page.addView(header)

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        page.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
        pager.addView(page)
        return body
    }

    private fun buildDots() {
        dots.removeAllViews()
        for (i in 0 until pager.childCount) {
            dots.addView(View(ctx), LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                topMargin = dp(4); bottomMargin = dp(4)
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

    private fun tv(text: String, size: Float, color: Int, light: Boolean = false, thin: Boolean = false) =
        TextView(ctx).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            typeface = Typeface.create(
                when { thin -> "sans-serif-thin"; light -> "sans-serif-light"; else -> "sans-serif" },
                Typeface.NORMAL
            )
        }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
}
