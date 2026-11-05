package gr.vassbak.standby

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CalendarContract
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.exp
import kotlin.random.Random

/** Ελατήριο τύπου iPhone: ξεπερνά λίγο τον στόχο και ταλαντώνεται μέχρι να ηρεμήσει. */
class SpringInterpolator(private val damping: Float = 6.5f, private val freq: Float = 13f) : Interpolator {
    override fun getInterpolation(t: Float): Float = (1 - exp(-damping * t) * cos(freq * t)).toFloat()
}

/**
 * Η οθόνη StandBy: δυναμική αρχική με πλακίδια. Πατάς ένα πλακίδιο και ανοίγει σε όλη την οθόνη
 * με ελατηριωτή κίνηση· πατάς ξανά και επιστρέφει. Διπλό πάτημα στο κενό της αρχικής = έξοδος.
 */
@SuppressLint("ClickableViewAccessibility")
class Dashboard(
    private val ctx: Context,
    private val onExit: () -> Unit = {},
    private val onNightChanged: (Boolean) -> Unit = {}
) {
    private enum class Section { NAV, WEATHER, BATTERY, COROS, MUSIC, CAL, NOTIF }

    val view: FrameLayout = object : FrameLayout(ctx) {
        private var startX = 0f
        private var startY = 0f
        private var pulling = false
        private var blocked = false
        private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) lastTouch = System.currentTimeMillis()
            return super.dispatchTouchEvent(ev)
        }

        // Τράβηγμα προς τα κάτω οπουδήποτε: «παίρνουμε» την κίνηση μόνο όταν είναι καθαρά προς τα κάτω
        // και η λίστα κάτω από το δάχτυλο (αν υπάρχει) είναι ήδη στην κορυφή της.
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = ev.rawX; startY = ev.rawY; pulling = false
                    blocked = animating || childCount > 2 || scrollsUpAt(this, ev.rawX, ev.rawY)
                }
                MotionEvent.ACTION_MOVE -> if (!blocked && !pulling) {
                    val dy = ev.rawY - startY
                    if (dy > slop && dy > kotlin.math.abs(ev.rawX - startX) * 1.2f) {
                        pulling = true
                        startY = ev.rawY      // η παραμόρφωση ξεκινά ομαλά από το μηδέν
                        pullBegin()
                    }
                }
            }
            return pulling
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { startX = ev.rawX; startY = ev.rawY; pulling = false; blocked = animating }
                MotionEvent.ACTION_MOVE -> {
                    if (!pulling && !blocked && ev.rawY - startY > slop) { pulling = true; startY = ev.rawY; pullBegin() }
                    if (pulling) pullTo(ev.rawY - startY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pulling) pullRelease()
                    pulling = false
                }
            }
            return true
        }
    }.apply { setBackgroundColor(Color.BLACK) }

    // ---------- Ελαστικό τράβηγμα προς τα κάτω ----------

    private var pullTarget: View? = null
    private var pullBaseY = 0f
    private var pullStep = 0   // πόσα «σκαλοπάτια» δόνησης έχουν περάσει στο τρέχον τράβηγμα

    /** Υπάρχει κάτι κάτω από το δάχτυλο που μπορεί ακόμα να κυλήσει προς τα πάνω; */
    private fun scrollsUpAt(v: View, rx: Float, ry: Float): Boolean {
        if (v.visibility != View.VISIBLE) return false
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        if (rx < loc[0] || ry < loc[1] || rx > loc[0] + v.width * v.scaleX || ry > loc[1] + v.height * v.scaleY) return false
        if (v.canScrollVertically(-1)) return true
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) if (scrollsUpAt(v.getChildAt(i), rx, ry)) return true
        return false
    }

    private fun pullBegin() {
        val t = openSection?.let { pages[it] } ?: homeLayer
        t.animate().cancel()
        pullTarget = t
        pullStep = 0
        buzz(Haptic.TICK)   // «έπιασε» το λάστιχο
        pullBaseY = if (t === homeLayer) homeLayer.translationY else 0f
        t.pivotX = t.width / 2f
        t.pivotY = 0f
    }

    private fun pullTo(dy: Float) {
        val t = pullTarget ?: return
        val h = view.height.coerceAtLeast(1).toFloat()
        val max = h * 0.4f
        val d = max * (1f - exp(-dy.coerceAtLeast(0f) / max).toFloat())   // όσο τραβάς, τόσο «σκληραίνει»
        t.translationY = pullBaseY + d * 0.45f
        t.scaleY = 1f + d / h * 0.35f     // τεντώνει προς τα κάτω
        t.scaleX = 1f - d / h * 0.06f     // και λεπταίνει λίγο
        // Ελαφριά «τικ» σε κάθε 20% της διαδρομής: νιώθεις το λάστιχο να τεντώνει
        val step = (d / max * 5f).toInt()
        if (step > pullStep) { pullStep = step; buzz(Haptic.TICK) }
    }

    private fun pullRelease() {
        val t = pullTarget ?: return
        pullTarget = null
        if (pullStep > 0) buzz(Haptic.HEAVY)   // «χτύπημα» του ελατηρίου που επιστρέφει
        t.animate().translationY(pullBaseY).scaleX(1f).scaleY(1f)
            .setDuration(750).setInterpolator(SpringInterpolator(5.5f, 15f)).setListener(null)
            .withEndAction { t.pivotY = t.height / 2f }   // τα άλλα εφέ (άνοιγμα/κλείσιμο) θέλουν κέντρο
            .start()
    }

    private val homeLayer = FrameLayout(ctx)
    private val detailLayer = FrameLayout(ctx)

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
    private val cMusic = Color.parseColor("#7D7AFF")

    private val white = Color.WHITE
    private val soft = Color.parseColor("#DDDDDD")
    private val grey = Color.parseColor("#9A9A9A")
    private val dim = Color.parseColor("#666666")
    private val amber = Color.parseColor("#FFB340")

    // Αρχική
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var clockBlock: View
    private val tiles = mutableMapOf<Section, LinearLayout>()      // το πλαίσιο
    private val tileBodies = mutableMapOf<Section, LinearLayout>() // το περιεχόμενο

    // Πλήρεις οθόνες
    private val pages = mutableMapOf<Section, View>()
    private val bodies = mutableMapOf<Section, LinearLayout>()
    private val smallClocks = mutableListOf<TextView>()
    private var openSection: Section? = null
    private var animating = false

    // Πλοήγηση
    private lateinit var navArrowBig: ImageView
    private lateinit var navDistBig: TextView
    private lateinit var navInstrBig: TextView
    private lateinit var navEtaBig: TextView
    private lateinit var navStopBtn: TextView
    private var navExit: android.app.PendingIntent? = null
    private var navActive = false

    private var weatherReady = false
    private var charging = true
    private lateinit var aodPill: View
    private var lastMinute = -1
    private var night: Boolean? = null
    private var lastTouch = System.currentTimeMillis()
    private val portrait = Prefs.portrait(ctx)

    // Πλακίδια αρχικής: η σειρά τους αλλάζει με παρατεταμένο πάτημα και σύρσιμο, και αποθηκεύεται
    private val homeSecs = listOf(Section.WEATHER, Section.BATTERY, Section.COROS, Section.MUSIC, Section.CAL, Section.NOTIF)
    private val order: MutableList<Section> = loadOrder()
    private val gridRows = mutableListOf<LinearLayout>()
    private val rowWeights = floatArrayOf(1f, 0.95f, 1.25f)
    private val colWeights = arrayOf(floatArrayOf(1.4f, 1f), floatArrayOf(1f, 1.15f), floatArrayOf(1.4f, 1f))

    private fun loadOrder(): MutableList<Section> {
        val saved = Prefs.get(ctx).getString(Prefs.KEY_TILE_ORDER, null)
            ?.split(",")?.mapNotNull { n -> homeSecs.firstOrNull { it.name == n } }.orEmpty()
        return if (saved.size == homeSecs.size && saved.toSet() == homeSecs.toSet()) saved.toMutableList()
               else homeSecs.toMutableList()
    }

    init {
        buildHome()
        buildPages()
        view.addView(homeLayer, FrameLayout.LayoutParams(-1, -1))
        view.addView(detailLayer, FrameLayout.LayoutParams(-1, -1))
        // INVISIBLE (όχι GONE): μετριέται από την αρχή, ώστε το πρώτο άνοιγμα να έχει σωστό μέγεθος
        detailLayer.visibility = View.INVISIBLE

        // Διπλό πάτημα στο κενό της αρχικής → έξοδος
        val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { onExit(); return true }
        })
        homeLayer.setOnTouchListener { _, e -> gd.onTouchEvent(e) }

        setBody(tileBodies.getValue(Section.WEATHER), tv("…", 24f, white, light = true))
        tiles.getValue(Section.MUSIC).visibility = View.GONE
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
        override fun run() { loadCoros(); handler.postDelayed(this, 10 * 60_000L) }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = showBattery(i)
    }

    // Αισθητήρας εγγύτητας: σβήνει την οθόνη στην τσέπη και την ανάβει όταν βγει
    private var pocketLock: android.os.PowerManager.WakeLock? = null

    private fun startPocket() {
        if (!Prefs.pocket(ctx) || pocketLock != null) return
        try {
            val pm = ctx.getSystemService(android.os.PowerManager::class.java)
            if (pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                pocketLock = pm.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "standby:pocket").apply {
                    setReferenceCounted(false)
                    acquire()
                }
            }
        } catch (_: Exception) {}
    }

    private fun stopPocket() {
        try { pocketLock?.release() } catch (_: Exception) {}
        pocketLock = null
    }

    fun start() {
        State.showing = true
        State.timedOut = false
        startPocket()
        ctx.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { showBattery(it) }
        NotifListener.onChange = { handler.post { loadNotifications() } }
        handler.post(tick)
        handler.post(weatherLoop)
        handler.post(corosLoop)
        loadEvents()
        loadNotifications()
        startMusic()
    }

    fun stop() {
        if (State.showing) State.lastStop = System.currentTimeMillis()
        State.showing = false
        handler.removeCallbacksAndMessages(null)
        NotifListener.onChange = null
        stopMusic()
        stopPocket()
        try { ctx.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    // ---------- Αρχική οθόνη με πλακίδια ----------

    private fun buildHome() {
        // Οριζόντια: ρολόι αριστερά, πλακίδια δεξιά. Κάθετη: ρολόι πάνω, πλακίδια κάτω.
        val root = LinearLayout(ctx).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            if (portrait) setPadding(dp(16), dp(24), dp(16), 0)
            else setPadding(dp(28), dp(16), dp(28), dp(16))
        }

        // Αριστερά: ρολόι, ή μεγάλο πλακίδιο πλοήγησης όσο τρέχει το Maps
        val left = FrameLayout(ctx)
        val clockCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (portrait) Gravity.CENTER else Gravity.CENTER_VERTICAL
        }
        clock = tv("00:00", if (portrait) 96f else 108f, white, thin = true).apply {
            includeFontPadding = false
            if (portrait) gravity = Gravity.CENTER_HORIZONTAL   // κάθετη: ώρα και ημερομηνία στο κέντρο
        }
        date = tv("", 21f, amber, light = true).apply {
            if (portrait) gravity = Gravity.CENTER_HORIZONTAL else setPadding(dp(6), 0, 0, 0)
        }
        clockCol.addView(clock)
        clockCol.addView(date)
        clockBlock = clockCol
        left.addView(clockCol, FrameLayout.LayoutParams(-1, -1))
        val navTile = tile(Section.NAV, cNav, "ΠΛΟΗΓΗΣΗ")
        navTile.visibility = View.GONE
        left.addView(navTile, FrameLayout.LayoutParams(-1, -1).apply {
            if (portrait) bottomMargin = dp(10) else marginEnd = dp(10)
        })
        root.addView(left, if (portrait) LinearLayout.LayoutParams(-1, dp(200)) else LinearLayout.LayoutParams(0, -1, 1.15f))

        if (portrait) {
            // Κάθετη: δύο πλακίδια ανά σειρά, πιο φαρδιά απ' ό,τι ψηλά, με κύλιση αν δεν χωράνε
            val list = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, dp(56))   // χώρος για το κουμπάκι Always On
            }
            list.addView(buildGrid(), LinearLayout.LayoutParams(-1, -2))
            val scroll = android.widget.ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                addView(list)
            }
            // Διπλό πάτημα στο κενό ανάμεσα/κάτω από τα πλακίδια → έξοδος, όπως και στο ρολόι
            taps(scroll, onDouble = { onExit() })
            root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
            homeLayer.addView(root, FrameLayout.LayoutParams(-1, -1))
            addAodButton()
            return
        }

        // Δεξιά: πλέγμα πλακιδίων
        root.addView(buildGrid(), LinearLayout.LayoutParams(0, -1, 1f))

        homeLayer.addView(root, FrameLayout.LayoutParams(-1, -1))
        addAodButton()
    }

    private fun addAodButton() {

        // Κουμπάκι Always On (φαίνεται μόνο εκτός φόρτισης): πράσινο = δεν κλείνει, γκρι = κλείνει σε 1′
        aodPill = View(ctx).apply {
            isClickable = true
            visibility = View.GONE
            setOnClickListener {
                buzz()
                Prefs.get(ctx).edit().putBoolean(Prefs.KEY_AOD, !Prefs.alwaysOn(ctx)).apply()
                styleAod()
                scaleX = 0.85f; scaleY = 0.85f
                animate().scaleX(1f).scaleY(1f).setDuration(500).setInterpolator(SpringInterpolator()).start()
            }
        }
        styleAod()
        homeLayer.addView(aodPill, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dp(34); bottomMargin = dp(14)
        })
    }

    /** Πλέγμα 3 σειρών × 2 πλακιδίων, γεμισμένο με τη σειρά [order]. */
    private fun buildGrid(): LinearLayout {
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (sec in homeSecs) {
            val (color, title) = when (sec) {
                Section.WEATHER -> cWeather to "ΚΑΙΡΟΣ"
                Section.BATTERY -> cBattery to "ΜΠΑΤΑΡΙΑ"
                Section.CAL -> cCal to "ΕΠΟΜΕΝΑ"
                Section.NOTIF -> cNotif to "ΕΙΔΟΠΟΙΗΣΕΙΣ"
                Section.COROS -> cCoros to "COROS ΣΗΜΕΡΑ"
                Section.MUSIC -> cMusic to "ΜΟΥΣΙΚΗ"
                else -> grey to ""
            }
            tile(sec, color, title)
        }
        for (r in 0..2) {
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            gridRows.add(row)
            val lp = if (portrait) LinearLayout.LayoutParams(-1, dp(118)) else LinearLayout.LayoutParams(-1, 0, rowWeights[r])
            if (r > 0) lp.topMargin = dp(8)
            grid.addView(row, lp)
        }
        layoutTiles()
        return grid
    }

    private fun layoutTiles() {
        gridRows.forEach { it.removeAllViews() }
        order.forEachIndexed { i, sec ->
            val r = i / 2; val c = i % 2
            val w = if (portrait) 1f else colWeights[r][c]
            gridRows[r].addView(tiles.getValue(sec), LinearLayout.LayoutParams(0, -1, w).apply {
                if (c > 0) marginStart = dp(8)
            })
        }
    }

    // ---------- Αναδιάταξη με παρατεταμένο πάτημα ----------

    private var ghost: ImageView? = null
    private var dragSec: Section? = null
    private var dropSec: Section? = null
    private var grabDx = 0f
    private var grabDy = 0f

    /**
     * Σημείο από τις τοπικές συντεταγμένες του [v] σε συντεταγμένες της αρχικής. Λογαριάζει
     * μετατοπίσεις/κλίμακες/περιστροφές, ώστε να δουλεύει και όταν η προφύλαξη οθόνης γυρίζει το περιεχόμενο.
     */
    private fun toHome(v: View, x: Float, y: Float): android.graphics.PointF {
        val pts = floatArrayOf(x, y)
        var cur: View = v
        while (cur !== homeLayer) {
            if (!cur.matrix.isIdentity) cur.matrix.mapPoints(pts)
            val parent = cur.parent as? View ?: break
            pts[0] += cur.left - parent.scrollX
            pts[1] += cur.top - parent.scrollY
            cur = parent
        }
        return android.graphics.PointF(pts[0], pts[1])
    }

    private fun homeRect(v: View): android.graphics.RectF {
        val a = toHome(v, 0f, 0f); val b = toHome(v, v.width.toFloat(), v.height.toFloat())
        return android.graphics.RectF(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
    }

    /** Σηκώνει το πλακίδιο: ένα «αντίγραφο» του ακολουθεί το δάχτυλο πάνω από όλα τα άλλα. */
    private fun dragStart(sec: Section, v: View, x: Float, y: Float) {
        v.parent?.requestDisallowInterceptTouchEvent(true)   // να μη κυλήσει/τεντώσει η οθόνη από κάτω
        v.animate().cancel(); v.scaleX = 1f; v.scaleY = 1f
        val bmp = android.graphics.Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1),
            android.graphics.Bitmap.Config.ARGB_8888)
        v.draw(android.graphics.Canvas(bmp))
        val r = homeRect(v)
        val p = toHome(v, x, y)
        val g = ImageView(ctx).apply {
            setImageBitmap(bmp)
            this.x = r.left; this.y = r.top
            elevation = dp(16).toFloat()
        }
        homeLayer.addView(g, FrameLayout.LayoutParams(v.width, v.height))
        g.animate().scaleX(1.08f).scaleY(1.08f).alpha(0.92f).setDuration(260).setInterpolator(SpringInterpolator()).start()
        grabDx = p.x - r.left; grabDy = p.y - r.top
        ghost = g; dragSec = sec; dropSec = null
        v.animate().alpha(0.25f).setDuration(150).start()
        buzz(Haptic.HEAVY)
    }

    private fun dragMove(v: View, x: Float, y: Float) {
        val g = ghost ?: return
        val p = toHome(v, x, y)
        g.x = p.x - grabDx
        g.y = p.y - grabDy
        // Ποιο πλακίδιο είναι κάτω από το δάχτυλο;
        val over = order.firstOrNull { s ->
            s != dragSec && tiles.getValue(s).visibility == View.VISIBLE && homeRect(tiles.getValue(s)).contains(p.x, p.y)
        }
        if (over != dropSec) {
            dropSec?.let { tiles.getValue(it).animate().scaleX(1f).scaleY(1f).setDuration(200).start() }
            over?.let { tiles.getValue(it).animate().scaleX(0.9f).scaleY(0.9f).setDuration(200).start() }
            dropSec = over
            if (over != null) buzz(Haptic.TICK)
        }
    }

    private fun dragEnd() {
        val g = ghost ?: return
        val from = dragSec ?: return
        val to = dropSec
        val src = tiles.getValue(from)
        ghost = null; dragSec = null; dropSec = null
        if (to != null) {
            tiles.getValue(to).animate().scaleX(1f).scaleY(1f).setDuration(200).start()
            val a = order.indexOf(from); val b = order.indexOf(to)
            order[a] = to; order[b] = from
            Prefs.get(ctx).edit().putString(Prefs.KEY_TILE_ORDER, order.joinToString(",") { it.name }).apply()
            layoutTiles()
            buzz(Haptic.CLICK)
        }
        // Το αντίγραφο «προσγειώνεται» στη (νέα) θέση του πλακιδίου και εξαφανίζεται
        src.post {
            val r = homeRect(src)
            g.animate().x(r.left).y(r.top).scaleX(1f).scaleY(1f).alpha(1f)
                .setDuration(420).setInterpolator(SpringInterpolator(6f, 14f))
                .withEndAction {
                    src.alpha = 1f
                    homeLayer.removeView(g)
                }.start()
        }
    }

    /** Πλακίδιο: χρωματιστό πλαίσιο που «βουλιάζει» στο πάτημα και ανοίγει σε όλη την οθόνη. */
    private fun tile(sec: Section, color: Int, title: String): LinearLayout {
        val (outer, body) = card(color, title)
        tiles[sec] = outer
        tileBodies[sec] = body
        outer.isClickable = true
        // Παρατεταμένο πάτημα (μόνο στα πλακίδια της αρχικής) → σηκώνεται και σέρνεται σε άλλη θέση
        val canDrag = sec in homeSecs
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var lastX = 0f; var lastY = 0f   // τοπικές συντεταγμένες στο πλακίδιο
        var dragging = false
        val longPress = Runnable {
            if (!animating && openSection == null && ghost == null) {
                dragging = true
                dragStart(sec, outer, lastX, lastY)
            }
        }
        outer.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; lastX = e.x; lastY = e.y; dragging = false
                    v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(110)
                        .setInterpolator(DecelerateInterpolator()).start()
                    if (canDrag) handler.postDelayed(longPress, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = e.x; lastY = e.y
                    if (dragging) dragMove(v, e.x, e.y)
                    else if (kotlin.math.abs(e.rawX - downX) > slop || kotlin.math.abs(e.rawY - downY) > slop)
                        handler.removeCallbacks(longPress)
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (dragging) { dragging = false; dragEnd() }
                    else {
                        springBack(v)
                        val inside = e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height
                        if (inside) { buzz(); open(sec) }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (dragging) { dragging = false; dragEnd() } else springBack(v)
                }
            }
            true
        }
        return outer
    }

    private fun styleAod() {
        val on = Prefs.alwaysOn(ctx)
        aodPill.contentDescription = if (on) "Always On: ενεργό" else "Always On: ανενεργό"
        aodPill.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (on) cNav else Color.parseColor("#5A5A5A"))
            setStroke(dp(2), if (on) Color.parseColor("#6634C759") else Color.parseColor("#33FFFFFF"))
        }
    }

    /** Μικρή δόνηση στο πάτημα κουμπιού. */
    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31)
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
    }

    /** Ένταση δόνησης: TICK = πολύ ελαφρύ «τικ», CLICK = κλικ κουμπιού, HEAVY = πιο γεμάτο «τοκ». */
    private enum class Haptic { TICK, CLICK, HEAVY }

    private fun buzz(kind: Haptic = Haptic.CLICK) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        try {
            if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(when (kind) {
                Haptic.TICK -> VibrationEffect.EFFECT_TICK
                Haptic.CLICK -> VibrationEffect.EFFECT_CLICK
                Haptic.HEAVY -> VibrationEffect.EFFECT_HEAVY_CLICK
            }))
            else v.vibrate(VibrationEffect.createOneShot(when (kind) {
                Haptic.TICK -> 8L; Haptic.CLICK -> 20L; Haptic.HEAVY -> 35L
            }, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {}
    }

    /**
     * Πιάνει ταπ πάνω σε μια λίστα με κύλιση χωρίς να χαλάει την κύλιση: η ScrollView αλλιώς «τρώει»
     * το πάτημα και δεν φτάνει ποτέ στη σελίδα/αρχική από πίσω. Πατήματα σε κουμπιά/κάρτες μέσα
     * στη λίστα τα χειρίζονται αυτά πρώτα, οπότε εδώ φτάνουν μόνο τα πατήματα στο κενό.
     */
    private fun taps(v: View, onSingle: (() -> Unit)? = null, onDouble: (() -> Unit)? = null) {
        val g = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (onDouble == null) onSingle?.invoke()
                return onSingle != null
            }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (onDouble != null) onSingle?.invoke()
                return onSingle != null
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                onDouble?.invoke(); return onDouble != null
            }
        })
        v.setOnTouchListener { _, e -> g.onTouchEvent(e); false }
    }

    private fun springBack(v: View) {
        v.animate().scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(SpringInterpolator(7f, 16f)).start()
    }

    // ---------- Άνοιγμα / κλείσιμο με ελατήριο ----------

    private fun open(sec: Section) {
        if (animating || openSection != null) return
        val tileView = tiles[sec] ?: return
        val page = pages[sec] ?: return
        openSection = sec
        animating = true
        if (sec == Section.COROS) loadCoros()   // φρέσκο πρόγραμμα κάθε φορά που ανοίγει
        pages.values.forEach { it.visibility = View.INVISIBLE }
        page.visibility = View.VISIBLE
        detailLayer.visibility = View.VISIBLE

        val (tx, ty, sx, sy) = tileGeometry(tileView)
        page.pivotX = 0f; page.pivotY = 0f
        page.translationX = tx; page.translationY = ty
        page.scaleX = sx; page.scaleY = sy
        page.alpha = 0.4f

        page.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f).alpha(1f)
            .setDuration(650).setInterpolator(SpringInterpolator())
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) { animating = false }
            }).start()
        homeLayer.animate().scaleX(0.9f).scaleY(0.9f).alpha(0f).setDuration(300)
            .setInterpolator(DecelerateInterpolator()).setListener(null).start()
    }

    private fun close() {
        val sec = openSection ?: return
        if (animating) return
        val tileView = tiles[sec] ?: return
        val page = pages[sec] ?: return
        animating = true
        buzz(Haptic.TICK)
        homeLayer.animate().cancel()
        homeLayer.scaleX = 1f; homeLayer.scaleY = 1f
        val (tx, ty, sx, sy) = tileGeometry(tileView)
        page.animate().translationX(tx).translationY(ty).scaleX(sx).scaleY(sy).alpha(0f)
            .setDuration(340).setInterpolator(DecelerateInterpolator(1.6f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    page.visibility = View.INVISIBLE
                    detailLayer.visibility = View.INVISIBLE
                    openSection = null
                    animating = false
                }
            }).start()
        homeLayer.scaleX = 0.95f; homeLayer.scaleY = 0.95f
        homeLayer.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(560)
            .setInterpolator(SpringInterpolator(7f, 14f)).setListener(null).start()
    }

    private data class Geo(val tx: Float, val ty: Float, val sx: Float, val sy: Float)

    /** Θέση και κλίμακα του πλακιδίου ως προς όλη την οθόνη. */
    private fun tileGeometry(t: View): Geo {
        val a = IntArray(2); val b = IntArray(2)
        t.getLocationInWindow(a); detailLayer.getLocationInWindow(b)
        val w = (if (detailLayer.width > 0) detailLayer.width else view.width).coerceAtLeast(1).toFloat()
        val h = (if (detailLayer.height > 0) detailLayer.height else view.height).coerceAtLeast(1).toFloat()
        // Η αρχική μπορεί να είναι σε σμίκρυνση· διορθώνουμε με την τρέχουσα κλίμακά της
        val s = homeLayer.scaleX
        return Geo((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat(), t.width * s / w, t.height * s / h)
    }

    // ---------- Πλήρεις οθόνες ----------

    private fun buildPages() {
        addPage(Section.NAV, cNav, "ΠΛΟΗΓΗΣΗ")
        addPage(Section.WEATHER, cWeather, "ΚΑΙΡΟΣ")
        addPage(Section.BATTERY, cBattery, "ΜΠΑΤΑΡΙΑ")
        addPage(Section.COROS, cCoros, "COROS · ΠΡΟΓΡΑΜΜΑ")
        addPage(Section.CAL, cCal, "ΗΜΕΡΟΛΟΓΙΟ · 7 ΗΜΕΡΕΣ")
        addPage(Section.NOTIF, cNotif, "ΕΙΔΟΠΟΙΗΣΕΙΣ")
        addPage(Section.MUSIC, cMusic, "ΜΟΥΣΙΚΗ")
        buildMusicPage()

        // Πλοήγηση σε όλη την οθόνη
        val nb = bodies.getValue(Section.NAV)
        nb.gravity = Gravity.CENTER_VERTICAL
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        navArrowBig = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        row.addView(navArrowBig, LinearLayout.LayoutParams(dp(190), dp(190)).apply { marginEnd = dp(28) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        navDistBig = tv("", 76f, white, light = true).apply { includeFontPadding = false; maxLines = 1 }
        navInstrBig = tv("", 32f, soft).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END }
        navEtaBig = tv("", 20f, cNav).apply { setPadding(0, dp(14), 0, 0); maxLines = 1 }
        col.addView(navDistBig); col.addView(navInstrBig); col.addView(navEtaBig)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        nb.addView(row)

        // Κουμπί τερματισμού: παρατεταμένο πάτημα → επιβεβαίωση
        navStopBtn = tv("⏹  Κράτα πατημένο για τερματισμό", 15f, white).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(12), dp(20), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(withAlpha(Color.parseColor("#FF453A"), 0x40))
                setStroke(dp(2), Color.parseColor("#FF453A"))
            }
            isClickable = true
            isLongClickable = true
            setOnClickListener { v ->
                buzz()
                // απλό πάτημα: μικρό «κούνημα» για να θυμίσει ότι θέλει κράτημα
                v.translationX = dp(10).toFloat()
                v.animate().translationX(0f).setDuration(500).setInterpolator(SpringInterpolator(6f, 30f)).start()
            }
            setOnLongClickListener { v ->
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                showStopConfirm(); true
            }
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(400)
                        .setInterpolator(DecelerateInterpolator()).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springBack(v)
                }
                false
            }
        }
        nb.addView(navStopBtn, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.END; topMargin = dp(12)
        })
    }

    /** Παράθυρο επιβεβαίωσης μέσα στην οθόνη (λειτουργεί και στην προφύλαξη οθόνης). */
    private fun showStopConfirm() {
        val red = Color.parseColor("#FF453A")
        val overlay = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(0xB0, 0, 0, 0))
            isClickable = true
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(22), dp(28), dp(20))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(Color.parseColor("#1C1C1E"))
                setStroke(dp(2), withAlpha(red, 0xAA))
            }
        }
        box.addView(tv("Τερματισμός πλοήγησης;", 24f, white).apply { gravity = Gravity.CENTER })
        box.addView(tv("Η διαδρομή στο Google Maps θα σταματήσει.", 15f, grey).apply {
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(16))
        })
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        fun dismiss() {
            box.animate().scaleX(0.8f).scaleY(0.8f).alpha(0f).setDuration(180).start()
            overlay.animate().alpha(0f).setDuration(200).withEndAction { view.removeView(overlay) }.start()
        }
        fun pill(label: String, color: Int, filled: Boolean, onTap: () -> Unit) = tv(label, 18f, white).apply {
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(12), dp(26), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(if (filled) color else withAlpha(color, 0x30))
            }
            isClickable = true
            setOnClickListener { buzz(); onTap() }
        }
        btns.addView(pill("Άκυρο", grey, false) { dismiss() })
        btns.addView(pill("Τερματισμός", red, true) {
            val ok = try { navExit?.send(); navExit != null } catch (_: Exception) { false }
            if (!ok) {
                box.removeAllViews()
                box.addView(tv("Δεν βρέθηκε η επιλογή «Έξοδος» στο Maps.\nΚλείσ' το από την ειδοποίηση.", 16f, soft).apply {
                    gravity = Gravity.CENTER
                })
                handler.postDelayed({ dismiss() }, 2500)
            } else {
                dismiss()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(14) })
        box.addView(btns)
        overlay.setOnClickListener { dismiss() }
        overlay.addView(box, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        view.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(180).start()
        box.scaleX = 0.6f; box.scaleY = 0.6f
        box.animate().scaleX(1f).scaleY(1f).setDuration(600).setInterpolator(SpringInterpolator()).start()
    }

    /** Πλήρης οθόνη για μία κατηγορία: χρωματιστό πλαίσιο σε όλη την οθόνη, πάτημα = επιστροφή. */
    private fun addPage(sec: Section, color: Int, title: String) {
        val page = FrameLayout(ctx).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            visibility = View.INVISIBLE
            isClickable = true
            setOnClickListener { close() }
        }
        val frame = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(14), dp(22), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(withAlpha(color, 0x22))
                setStroke(dp(2), withAlpha(color, 0x99))
            }
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(tv(title, 14f, color).apply {
            letterSpacing = 0.15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val small = tv("", 18f, grey, light = true)
        smallClocks += small
        header.addView(small)
        frame.addView(header)
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(body, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(10) })
        page.addView(frame, FrameLayout.LayoutParams(-1, -1))
        detailLayer.addView(page, FrameLayout.LayoutParams(-1, -1))
        pages[sec] = page
        bodies[sec] = body
    }

    // ---------- Ρολόι, νύχτα, προστασία οθόνης ----------

    private fun updateClock() {
        val now = Date()
        val t = timeFmt.format(now)
        clock.text = t
        for (c in smallClocks) c.text = t
        if (openSection == Section.MUSIC) refreshMusicProgress()

        // Επιστροφή στην αρχική μετά από 90" (εκτός από την πλοήγηση)
        if (openSection != null && openSection != Section.NAV &&
            System.currentTimeMillis() - lastTouch > 90_000) close()

        // Εκτός φόρτισης, χωρίς Always On και χωρίς πλοήγηση: κλείνει μετά από 1 λεπτό αδράνειας
        if (!charging && !Prefs.alwaysOn(ctx) && !navActive &&
            System.currentTimeMillis() - lastTouch > 60_000) {
            lastTouch = System.currentTimeMillis()
            State.timedOut = true
            onExit()
        }

        val cal = Calendar.getInstance()
        val minute = cal.get(Calendar.MINUTE)
        if (minute == lastMinute) return
        lastMinute = minute

        date.text = dateFmt.format(now).replaceFirstChar { it.uppercase(el) }

        // Μικρή μετατόπιση κάθε λεπτό για να μη «καίγεται» η οθόνη
        val px = dp(6).toFloat()
        val dx = Random.nextFloat() * 2 * px - px
        val dy = Random.nextFloat() * 2 * px - px
        homeLayer.translationX = dx; homeLayer.translationY = dy
        detailLayer.translationX = dx; detailLayer.translationY = dy

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
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val pct = if (level >= 0) level * 100 / scale else 0
        charging = plugged != 0
        aodPill.visibility = if (charging) View.GONE else View.VISIBLE
        val state = when (status) {
            BatteryManager.BATTERY_STATUS_FULL -> "Πλήρης"
            BatteryManager.BATTERY_STATUS_CHARGING -> "Φορτίζει"
            else -> "Σε μπαταρία"
        }
        val icon = if (status == BatteryManager.BATTERY_STATUS_CHARGING) "⚡" else "🔋"
        setBody(tileBodies.getValue(Section.BATTERY),
            tv("$icon $pct%", 22f, white, light = true),
            tv(state, 12f, cBattery))

        val source = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Πρίζα"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Ασύρματη φόρτιση"
            else -> "—"
        }
        val body = bodies.getValue(Section.BATTERY)
        body.gravity = Gravity.CENTER
        setBody(body,
            tv("$icon $pct%", 96f, white, thin = true).apply { gravity = Gravity.CENTER },
            tv("$state · $source · ${"%.1f".format(temp)}°C", 20f, cBattery).apply { gravity = Gravity.CENTER })
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
        val tileBody = tileBodies.getValue(Section.CAL)
        val calBody = bodies.getValue(Section.CAL)

        if (list == null) {
            setBody(tileBody, tv("Χωρίς άδεια", 13f, grey))
            setBody(calBody, tv("Δώσε άδεια ημερολογίου από την εφαρμογή StandBy.", 18f, grey))
            return
        }

        // Πλακίδιο: τα 2 επόμενα
        val near = list.filter { dayOffset(it) <= 1 }.take(2)
        if (near.isEmpty()) setBody(tileBody, tv("Τίποτα σήμερα/αύριο", 13f, grey))
        else setBody(tileBody, *near.map { e ->
            val off = dayOffset(e)
            val whenTxt = when {
                e.allDay -> if (off == 0) "Σήμ." else "Αύρ."
                off == 0 -> timeFmt.format(Date(e.begin))
                else -> "Αύρ."
            }
            eventLine(whenTxt, e.title, e.color, 13f)
        }.toTypedArray())

        // Πλήρης οθόνη: πλέγμα 4 + 3 ημερών
        calBody.removeAllViews()
        val byDay = list.groupBy { dayOffset(it) }
        if (portrait) {
            // Κάθετη: μία φαρδιά κάρτα ανά μέρα, με όλα τα γεγονότα της, σε λίστα με κύλιση
            val days = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            for (d in 0..6) {
                val (c, body) = card(if (d == 0) cCal else Color.parseColor("#8E6BB0"), dayName(d).uppercase(el))
                val evs = byDay[d].orEmpty()
                if (evs.isEmpty()) body.addView(tv("Τίποτα", 15f, dim))
                for (e in evs.take(8)) {
                    val t = if (e.allDay) "Ολοήμ." else timeFmt.format(Date(e.begin))
                    body.addView(eventLine(t, e.title, e.color, 16f).apply { setPadding(0, dp(2), 0, dp(2)) })
                }
                if (evs.size > 8) body.addView(tv("+${evs.size - 8} ακόμα", 13f, grey))
                days.addView(c, LinearLayout.LayoutParams(-1, -2).apply { if (d > 0) topMargin = dp(8) })
            }
            calBody.addView(android.widget.ScrollView(ctx).apply {
                taps(this, onSingle = { close() })   // ένα ταπ στη λίστα → επιστροφή
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(days)
            }, LinearLayout.LayoutParams(-1, -1))
            return
        }
        val perRow = if (portrait) 2 else 4
        val maxEv = if (portrait) 2 else 3
        for (r in 0..6 / perRow) {
            val rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val range = (r * perRow) until minOf(7, (r + 1) * perRow)
            for (d in range) {
                val (c, body) = card(if (d == 0) cCal else Color.parseColor("#8E6BB0"), dayName(d).uppercase(el))
                val evs = byDay[d].orEmpty()
                if (evs.isEmpty()) body.addView(tv("—", 14f, dim))
                for (e in evs.take(maxEv)) {
                    val t = if (e.allDay) "Ολοήμ." else timeFmt.format(Date(e.begin))
                    body.addView(eventLine(t, e.title, e.color, 14f))
                }
                if (evs.size > maxEv) body.addView(tv("+${evs.size - maxEv} ακόμα", 12f, grey))
                rowView.addView(c, LinearLayout.LayoutParams(0, -1, 1f).apply {
                    if (d != range.first) marginStart = dp(8)
                })
            }
            if (range.count() < perRow) rowView.addView(View(ctx), LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(8) })
            calBody.addView(rowView, (if (portrait) LinearLayout.LayoutParams(-1, dp(118))
                else LinearLayout.LayoutParams(-1, 0, 1f)).apply { if (r > 0) topMargin = dp(8) })
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
        line.addView(tv(title, size, soft).apply { maxLines = if (portrait && size >= 16f) 2 else 1; ellipsize = TextUtils.TruncateAt.END },
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
        val tileBody = tileBodies.getValue(Section.COROS)
        val body = bodies.getValue(Section.COROS)
        body.removeAllViews()
        if (list == null) {
            setBody(tileBody, tv(if (Coros.configured(ctx)) "Σφάλμα σύνδεσης" else "Δεν έχει συνδεθεί", 14f, grey))
            body.addView(tv(error ?: "", 18f, grey))
            return
        }
        val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
        val todayKey = fmt.format(Date())
        val todayStart = fmt.parse(todayKey)!!.time
        val today = list.filter { it.day == todayKey }
        val next = list.filter { it.day > todayKey }

        val main = today.firstOrNull { !it.completed } ?: today.firstOrNull()
        val tileLine = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        tileLine.addView(
            (if (main == null) tv("😴 Ξεκούραση", 17f, white)
            else tv("${Coros.sportLabel(main.sport).first} ${main.name}" + (if (main.completed) " ✓" else ""), 17f, white))
                .apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (main != null && main.minutes > 0) tileLine.addView(tv("  ${main.minutes}′", 15f, cCoros))
        setBody(tileBody, tileLine)

        val cols = LinearLayout(ctx).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }

        val (todayCard, tBody) = card(cCoros, "ΣΗΜΕΡΑ")
        tBody.gravity = Gravity.CENTER_VERTICAL
        if (today.isEmpty()) tBody.addView(tv("😴 Ξεκούραση", 34f, white, light = true))
        for (w in today) {
            val (icon, sport) = Coros.sportLabel(w.sport)
            tBody.addView(tv("$icon ${w.name}", 32f, if (w.completed) cBattery else white, light = true).apply {
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
            tBody.addView(tv(buildString {
                append(sport)
                if (w.minutes > 0) append(" · ${w.minutes}′")
                if (w.completed) append(" · ✓ Ολοκληρώθηκε")
            }, 17f, if (w.completed) cBattery else cCoros))
        }
        cols.addView(todayCard, if (portrait) LinearLayout.LayoutParams(-1, dp(190)) else LinearLayout.LayoutParams(0, -1, 1.1f))

        val (nextCard, nBody) = card(cCoros, "ΕΠΟΜΕΝΕΣ ΜΕΡΕΣ")
        if (next.isEmpty()) nBody.addView(tv("Τίποτα προγραμματισμένο", 15f, grey))
        for (w in next.take(7)) {
            val d = try { fmt.parse(w.day)?.time } catch (_: Exception) { null }
            val label = if (d == null) w.day else dayName(Math.round((d - todayStart) / 86_400_000.0).toInt())
            val line = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(3), 0, dp(3))
            }
            line.addView(tv(label, 15f, cCoros), LinearLayout.LayoutParams(dp(if (portrait) 80 else 100), -2))
            line.addView(tv("${Coros.sportLabel(w.sport).first} ${w.name}" +
                (if (w.minutes > 0) " · ${w.minutes}′" else ""), 16f, soft).apply {
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f))
            nBody.addView(line)
        }
        cols.addView(nextCard, if (portrait) LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
            else LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = dp(10) })
        body.addView(cols, LinearLayout.LayoutParams(-1, -1))
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
                    if (!weatherReady) setBody(tileBodies.getValue(Section.WEATHER), tv("Χωρίς σύνδεση", 14f, grey))
                }
            }
        }.start()
    }

    private fun renderWeather(w: Weather.Data) {
        weatherReady = true
        val (icon, desc) = Weather.text(w.code)
        val today = w.days.firstOrNull()
        val now = "$icon ${w.temp}°"
        val range = if (today != null) "↑${today.max}° ↓${today.min}°" else ""

        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(tv(now, 24f, white, light = true))
        line.addView(tv("  $desc\n  $range", 12f, grey).apply { maxLines = 2 })
        setBody(tileBodies.getValue(Section.WEATHER), line)

        val body = bodies.getValue(Section.WEATHER)
        body.removeAllViews()
        val top = LinearLayout(ctx).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        }

        val (nowCard, nowBody) = card(cWeather, "ΤΩΡΑ")
        nowBody.gravity = Gravity.CENTER_VERTICAL
        nowBody.addView(tv(now, 44f, white, light = true))
        nowBody.addView(tv(desc, 16f, soft))
        if (today != null) nowBody.addView(tv("$range · 💧${today.rain}%", 15f, cWeather))
        top.addView(nowCard, if (portrait) LinearLayout.LayoutParams(-1, dp(150)) else LinearLayout.LayoutParams(0, -1, 1f))

        val (daysCard, daysBody) = card(cWeather, "ΕΠΟΜΕΝΕΣ ΜΕΡΕΣ")
        val dayFmt = SimpleDateFormat("EEE", el)
        daysBody.addView(columns(w.days.drop(1).take(5).map { d ->
            Triple(dayFmt.format(Date(d.date)).replaceFirstChar { it.uppercase(el) },
                "${Weather.text(d.code).first}\n${d.max}°/${d.min}°",
                if (d.rain >= 20) "💧${d.rain}%" else " ")
        }), LinearLayout.LayoutParams(-1, -1))
        top.addView(daysCard, if (portrait) LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(8) }
            else LinearLayout.LayoutParams(0, -1, 2f).apply { marginStart = dp(8) })
        body.addView(top, if (portrait) LinearLayout.LayoutParams(-1, -2) else LinearLayout.LayoutParams(-1, 0, 1f))

        val (hCard, hBody) = card(cWeather, "ΕΠΟΜΕΝΕΣ ΩΡΕΣ")
        hBody.addView(columns(w.hours.filterIndexed { i, _ -> i % 2 == 0 }.take(if (portrait) 6 else 8).map { h ->
            Triple(timeFmt.format(Date(h.time)), "${Weather.text(h.code).first}\n${h.temp}°",
                if (h.rain >= 20) "💧${h.rain}%" else " ")
        }), LinearLayout.LayoutParams(-1, -1))
        body.addView(hCard, (if (portrait) LinearLayout.LayoutParams(-1, dp(170)) else LinearLayout.LayoutParams(-1, 0, 1f))
            .apply { topMargin = dp(8) })
    }

    private fun columns(items: List<Triple<String, String, String>>): LinearLayout {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for ((a, b, c) in items) {
            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(tv(a, 13f, cWeather))
            col.addView(tv(b, 17f, white).apply { gravity = Gravity.CENTER })
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

    // ---------- Πλοήγηση Google Maps ----------

    private fun updateNav() {
        val n = NotifListener.nav(ctx)
        val navTile = tiles.getValue(Section.NAV)
        if (n == null) {
            if (navActive) {
                navActive = false
                if (openSection == Section.NAV) close()
                navTile.visibility = View.GONE
                clockBlock.visibility = View.VISIBLE
            }
            return
        }
        if (!navActive) {
            navActive = true
            navTile.visibility = View.VISIBLE
            clockBlock.visibility = View.GONE
            // «Σκάει» στην οθόνη με ελατήριο
            navTile.scaleX = 0.6f; navTile.scaleY = 0.6f; navTile.alpha = 0f
            navTile.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(700)
                .setInterpolator(SpringInterpolator()).start()
        }

        // Πλακίδιο αρχικής
        val tb = tileBodies.getValue(Section.NAV)
        tb.gravity = Gravity.CENTER_VERTICAL
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (n.arrow != null) row.addView(ImageView(ctx).apply {
            setImageDrawable(n.arrow?.constantState?.newDrawable()?.mutate() ?: n.arrow)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(96), dp(96)).apply { marginEnd = dp(12) })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(n.distance, 40f, white, light = true).apply { maxLines = 1; includeFontPadding = false })
        col.addView(tv(n.instruction, 19f, soft).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        setBody(tb, row, tv(n.eta, 15f, cNav).apply {
            setPadding(0, dp(8), 0, 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })

        // Πλήρης οθόνη
        navDistBig.text = n.distance
        navInstrBig.text = n.instruction
        navEtaBig.text = n.eta
        navExit = n.exit
        if (n.arrow != null) { navArrowBig.setImageDrawable(n.arrow); navArrowBig.visibility = View.VISIBLE }
        else navArrowBig.visibility = View.GONE
    }

    // ---------- Ειδοποιήσεις ----------

    private fun loadNotifications() {
        updateNav()
        val tileBody = tileBodies.getValue(Section.NOTIF)
        val body = bodies.getValue(Section.NOTIF)
        body.removeAllViews()
        if (!NotifListener.enabled(ctx)) {
            setBody(tileBody, tv("Χωρίς άδεια", 13f, grey))
            body.addView(tv("Δώσε πρόσβαση στις ειδοποιήσεις από την εφαρμογή StandBy.", 18f, grey))
            return
        }
        val all = NotifListener.current(ctx)
        if (all.isEmpty()) {
            setBody(tileBody, tv("0", 30f, white, light = true), tv("Καμία", 12f, grey))
            body.addView(tv("Καμία ειδοποίηση 🎉", 20f, grey))
            return
        }
        setBody(tileBody,
            tv("${all.size}", 30f, white, light = true).apply { includeFontPadding = false },
            tv(all.first().app, 12f, cNotif).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })

        // Κουμπί «Εκκαθάριση όλων»: πρώτο πάτημα ζητά επιβεβαίωση, δεύτερο μέσα σε 3″ τα σβήνει
        val clearBtn = tv("✕  Εκκαθάριση όλων", 14f, white).apply {
            setPadding(dp(16), dp(7), dp(16), dp(7))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(withAlpha(cNotif, 0x30)); setStroke(dp(1), withAlpha(cNotif, 0x90))
            }
            isClickable = true
            var armed = false
            setOnClickListener { v ->
                buzz()
                if (!armed) {
                    armed = true
                    text = "Σίγουρα; Πάτα ξανά"
                    (background as GradientDrawable).setColor(withAlpha(Color.parseColor("#FF453A"), 0x60))
                    handler.postDelayed({
                        armed = false
                        text = "✕  Εκκαθάριση όλων"
                        (background as GradientDrawable).setColor(withAlpha(cNotif, 0x30))
                    }, 3000)
                } else {
                    NotifListener.dismissAll(all.map { it.key })
                    v.animate().alpha(0f).setDuration(200).start()
                    handler.postDelayed({ loadNotifications() }, 300)
                }
            }
        }
        body.addView(clearBtn, LinearLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.END; bottomMargin = dp(8)
        })

        if (portrait) {
            // Κάθετη: μία φαρδιά κάρτα ανά σειρά, με περισσότερο κείμενο, σε λίστα με κύλιση
            val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            all.forEachIndexed { i, n ->
                val color = kindColor(n.kind)
                val (cardView, cb) = card(color, "${n.app} · ${timeFmt.format(Date(n.time))}".uppercase(el))
                if (n.title.isNotBlank()) cb.addView(tv(n.title, 17f, white).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                val txt = n.full.ifBlank { n.text }
                if (txt.isNotBlank()) cb.addView(tv(txt, 15f, soft).apply {
                    maxLines = 5; ellipsize = TextUtils.TruncateAt.END
                    setLineSpacing(0f, 1.1f)
                })
                swipeToDismiss(cardView, n.key) { showNotifDetail(n) }
                list.addView(cardView, LinearLayout.LayoutParams(-1, -2).apply {
                    if (i > 0) topMargin = dp(8)
                })
            }
            body.addView(android.widget.ScrollView(ctx).apply {
                taps(this, onSingle = { close() })   // ένα ταπ εκτός κάρτας → επιστροφή
                isVerticalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                addView(list)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            return
        }

        val items = all.take(6)
        val nRows = if (portrait) 3 else 2
        val nCols = if (portrait) 2 else 3
        for (r in 0 until nRows) {
            val rowView = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until nCols) {
                val n = items.getOrNull(r * nCols + c)
                val lpCard = LinearLayout.LayoutParams(0, -1, 1f).apply { if (c > 0) marginStart = dp(8) }
                if (n == null) { rowView.addView(View(ctx), lpCard); continue }
                val (cardView, cb) = card(kindColor(n.kind), "${n.app} · ${timeFmt.format(Date(n.time))}".uppercase(el))
                if (n.title.isNotBlank()) cb.addView(tv(n.title, 16f, white).apply {
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                })
                if (n.text.isNotBlank()) cb.addView(tv(n.text, 14f, grey).apply {
                    maxLines = if (portrait) 2 else 3; ellipsize = TextUtils.TruncateAt.END
                })
                swipeToDismiss(cardView, n.key) { showNotifDetail(n) }
                rowView.addView(cardView, lpCard)
            }
            body.addView(rowView, (if (portrait) LinearLayout.LayoutParams(-1, dp(120))
                else LinearLayout.LayoutParams(-1, 0, 1f)).apply { if (r > 0) topMargin = dp(8) })
        }
    }

    // ---------- Μουσική ----------

    private var musicCtl: android.media.session.MediaController? = null
    private lateinit var mArt: ImageView
    private lateinit var mApp: TextView
    private lateinit var mTitle: TextView
    private lateinit var mArtist: TextView
    private lateinit var mPlay: TextView
    private lateinit var mFill: View
    private lateinit var mTrack: FrameLayout
    private lateinit var mPos: TextView
    private lateinit var mDur: TextView

    private val musicCallback = object : android.media.session.MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) { handler.post { renderMusic() } }
        override fun onPlaybackStateChanged(state: android.media.session.PlaybackState?) { handler.post { renderMusic() } }
        override fun onSessionDestroyed() { handler.post { bindMusic() } }
    }
    private val sessionsListener = android.media.session.MediaSessionManager.OnActiveSessionsChangedListener {
        handler.post { bindMusic() }
    }

    private fun startMusic() {
        try {
            Music.manager(ctx).addOnActiveSessionsChangedListener(sessionsListener, Music.listenerComponent(ctx), handler)
        } catch (_: Exception) {}
        bindMusic()
    }

    private fun stopMusic() {
        try { Music.manager(ctx).removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        musicCtl?.unregisterCallback(musicCallback)
        musicCtl = null
    }

    private fun bindMusic() {
        val c = Music.controller(ctx)
        if (c?.sessionToken != musicCtl?.sessionToken) {
            musicCtl?.unregisterCallback(musicCallback)
            musicCtl = c
            c?.registerCallback(musicCallback, handler)
        }
        renderMusic()
    }

    private fun circleBtn(label: String, size: Int, filled: Boolean, onTap: () -> Unit) = tv(label, if (size > 60) 30f else 22f, white).apply {
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (filled) withAlpha(cMusic, 0xCC) else withAlpha(cMusic, 0x30))
        }
        isClickable = true
        setOnClickListener { buzz(); onTap() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> springBack(v)
            }
            false
        }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun buildMusicPage() {
        val body = bodies.getValue(Section.MUSIC)
        val row = LinearLayout(ctx).apply {
            orientation = if (portrait) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (portrait) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
        }
        mArt = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(withAlpha(cMusic, 0x40)) }
            clipToOutline = true
        }
        if (!portrait) row.addView(mArt, LinearLayout.LayoutParams(dp(210), dp(210)).apply { marginEnd = dp(28) })

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        mApp = tv("", 13f, cMusic)
        mTitle = tv("", 30f, white, light = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        mArtist = tv("", 19f, grey).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        col.addView(mApp); col.addView(mTitle); col.addView(mArtist)

        // Μπάρα προόδου
        mTrack = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(Color.parseColor("#33FFFFFF")) }
        }
        mFill = View(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(white) }
        }
        mTrack.addView(mFill, FrameLayout.LayoutParams(0, -1))
        col.addView(mTrack, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(18) })
        val times = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        mPos = tv("0:00", 12f, grey); mDur = tv("0:00", 12f, grey)
        times.addView(mPos, LinearLayout.LayoutParams(0, -2, 1f)); times.addView(mDur)
        col.addView(times)

        // Κουμπιά
        val ctl = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ctl.addView(circleBtn("⏮", 56, false) { musicCtl?.transportControls?.skipToPrevious() })
        mPlay = circleBtn("▶", 72, true) { Music.toggle(musicCtl) }
        ctl.addView(mPlay)
        (mPlay.layoutParams as LinearLayout.LayoutParams).apply { marginStart = dp(22); marginEnd = dp(22) }
        ctl.addView(circleBtn("⏭", 56, false) { musicCtl?.transportControls?.skipToNext() })
        col.addView(ctl, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(14); gravity = Gravity.CENTER_HORIZONTAL })

        if (portrait) {
            // Κάθετη: τίτλος και κουμπιά πάνω, εξώφυλλο από κάτω
            row.addView(col, LinearLayout.LayoutParams(-1, -2))
            row.addView(mArt, LinearLayout.LayoutParams(dp(240), dp(240)).apply { topMargin = dp(24) })
        } else {
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        body.gravity = Gravity.CENTER_VERTICAL
        body.addView(row)
    }

    private fun renderMusic() {
        val tileView = tiles.getValue(Section.MUSIC)
        val c = musicCtl
        val t = if (c != null) Music.track(ctx, c) else null
        if (t == null) {
            if (tileView.visibility == View.VISIBLE) tileView.visibility = View.GONE
            if (openSection == Section.MUSIC) close()
            return
        }
        if (tileView.visibility != View.VISIBLE) {
            tileView.visibility = View.VISIBLE
            tileView.scaleX = 0.7f; tileView.scaleY = 0.7f; tileView.alpha = 0f
            tileView.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(650).setInterpolator(SpringInterpolator()).start()
        }

        // Πλακίδιο
        val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (t.art != null) line.addView(ImageView(ctx).apply {
            setImageBitmap(t.art)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(Color.DKGRAY) }
            clipToOutline = true
        }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(10) })
        val txt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        txt.addView(tv(t.title, 15f, white).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        txt.addView(tv(t.artist, 12f, grey).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        line.addView(txt, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(circleBtn(if (t.playing) "⏸" else "▶", 38, true) { Music.toggle(musicCtl) }.apply {
            textSize = 16f
            (layoutParams as LinearLayout.LayoutParams).marginStart = dp(8)
        })
        setBody(tileBodies.getValue(Section.MUSIC), line)

        // Πλήρης οθόνη
        if (t.art != null) mArt.setImageBitmap(t.art) else mArt.setImageDrawable(null)
        mApp.text = t.app.uppercase(el)
        mTitle.text = t.title
        mArtist.text = t.artist
        mPlay.text = if (t.playing) "⏸" else "▶"
        refreshMusicProgress()
    }

    private fun refreshMusicProgress() {
        val c = musicCtl ?: return
        val t = Music.track(ctx, c) ?: return
        mPos.text = Music.fmt(t.position)
        mDur.text = Music.fmt(t.duration)
        val frac = if (t.duration > 0) (t.position.toFloat() / t.duration).coerceIn(0f, 1f) else 0f
        val w = (mTrack.width * frac).toInt()
        mFill.layoutParams = (mFill.layoutParams as FrameLayout.LayoutParams).apply { width = w }
        mFill.requestLayout()
    }

    /** Σύρσιμο αριστερά/δεξιά: η κάρτα φεύγει και η ειδοποίηση διαγράφεται· αλλιώς γυρίζει με ελατήριο. */
    private fun swipeToDismiss(card: View, key: String, onTap: () -> Unit) {
        var downX = 0f
        var downY = 0f
        var dragging = false
        var armed = false
        var tracker: android.view.VelocityTracker? = null
        val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        card.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; dragging = false; armed = false
                    tracker?.recycle(); tracker = android.view.VelocityTracker.obtain()
                    tracker?.addMovement(e)
                    v.animate().cancel()
                }
                MotionEvent.ACTION_MOVE -> {
                    tracker?.addMovement(e)
                    val dx = e.rawX - downX
                    if (!dragging && kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(e.rawY - downY)) {
                        dragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (dragging) {
                        val past = kotlin.math.abs(dx) > v.width * 0.35f
                        if (past != armed) {
                            armed = past
                            buzz(Haptic.TICK)   // πέρασες/γύρισες το όριο διαγραφής
                        }
                        v.translationX = dx
                        v.rotation = dx / v.width.coerceAtLeast(1) * 8f
                        v.alpha = (1f - kotlin.math.abs(dx) / (v.width * 1.2f)).coerceIn(0.15f, 1f)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    tracker?.addMovement(e)
                    tracker?.computeCurrentVelocity(1000)
                    val vx = tracker?.xVelocity ?: 0f
                    tracker?.recycle(); tracker = null
                    val dx = v.translationX
                    val fling = kotlin.math.abs(vx) > 1200 && (vx > 0) == (dx > 0) && kotlin.math.abs(dx) > slop
                    if (dragging && e.actionMasked == MotionEvent.ACTION_UP &&
                        (kotlin.math.abs(dx) > v.width * 0.35f || fling)) {
                        val dir = if (dx >= 0) 1 else -1
                        buzz(Haptic.CLICK)
                        v.animate().translationX(dir * view.width.toFloat()).rotation(dir * 14f).alpha(0f)
                            .setDuration(220).setInterpolator(DecelerateInterpolator())
                            .withEndAction { NotifListener.dismiss(key); handler.postDelayed({ loadNotifications() }, 150) }
                            .start()
                    } else {
                        v.animate().translationX(0f).rotation(0f).alpha(1f).setDuration(550)
                            .setInterpolator(SpringInterpolator(7f, 15f)).start()
                        if (!dragging && e.actionMasked == MotionEvent.ACTION_UP &&
                            kotlin.math.abs(e.rawX - downX) < slop && kotlin.math.abs(e.rawY - downY) < slop) onTap()
                    }
                    dragging = false
                }
            }
            true   // κρατάμε το άγγιγμα ώστε να μην κλείσει η σελίδα
        }
    }

    /** Όλη η ειδοποίηση σε παράθυρο που «σκάει» με ελατήριο. */
    private fun showNotifDetail(n: NotifListener.Item) {
        val overlay = FrameLayout(ctx).apply {
            setBackgroundColor(Color.argb(0xB8, 0, 0, 0))
            isClickable = true
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(26).toFloat()
                setColor(Color.parseColor("#1C1C1E"))
                setStroke(dp(2), withAlpha(kindColor(n.kind), 0xAA))
            }
            isClickable = true   // πάτημα μέσα στο παράθυρο δεν το κλείνει
        }
        box.addView(tv("${n.app} · ${timeFmt.format(Date(n.time))}".uppercase(el), 12f, kindColor(n.kind)).apply {
            letterSpacing = 0.12f
        })
        if (n.title.isNotBlank()) box.addView(tv(n.title, 22f, white).apply { setPadding(0, dp(4), 0, dp(6)) })
        val scroll = android.widget.ScrollView(ctx).apply { isVerticalScrollBarEnabled = true }
        scroll.addView(tv(n.full.ifBlank { n.text }, 17f, soft).apply { setLineSpacing(0f, 1.15f) })
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun dismissPopup() {
            box.animate().scaleX(0.85f).scaleY(0.85f).alpha(0f).setDuration(180).start()
            overlay.animate().alpha(0f).setDuration(200).withEndAction { view.removeView(overlay) }.start()
        }
        fun pill(label: String, color: Int, filled: Boolean, onTap: () -> Unit) = tv(label, 16f, white).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(if (filled) color else withAlpha(color, 0x30))
            }
            isClickable = true
            setOnClickListener { buzz(); onTap() }
        }
        val btns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        btns.addView(pill("Διαγραφή", Color.parseColor("#FF453A"), false) {
            NotifListener.dismiss(n.key); dismissPopup(); handler.postDelayed({ loadNotifications() }, 200)
        })
        if (n.open != null || n.pkg.isNotEmpty()) btns.addView(pill("Άνοιγμα", kindColor(n.kind), false) {
            dismissPopup()
            openNotifApp(n)   // κλείνει το StandBy ώστε να φανεί η εφαρμογή (μπορεί να ζητήσει ξεκλείδωμα)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        btns.addView(pill("Κλείσιμο", grey, true) { dismissPopup() },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        box.addView(btns, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        overlay.setOnClickListener { dismissPopup() }
        val w = if (portrait) (view.width * 0.92f).toInt()
                else (view.width * 0.72f).toInt().coerceAtLeast(dp(320)).coerceAtMost(view.width)
        val h = (view.height * if (portrait) 0.7f else 0.86f).toInt()
        overlay.addView(box, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        view.addView(overlay, FrameLayout.LayoutParams(-1, -1))

        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(180).start()
        box.scaleX = 0.6f; box.scaleY = 0.6f
        box.animate().scaleX(1f).scaleY(1f).setDuration(600).setInterpolator(SpringInterpolator()).start()
    }

    // ---------- Βοηθητικά ----------

    /** Χρωματιστό πλαίσιο: διάφανο φόντο στο χρώμα της κατηγορίας, λεπτό περίγραμμα και τίτλος. */
    private fun card(accent: Int, title: String): Pair<LinearLayout, LinearLayout> {
        val outer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
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

    /**
     * Ανοίγει την εφαρμογή της ειδοποίησης. Ξεκλειδώνει πρώτα αν χρειάζεται και επιτρέπει ρητά
     * το άνοιγμα από το παρασκήνιο (Android 14+), αλλιώς το σύστημα το μπλοκάρει σιωπηλά.
     * Αν η ειδοποίηση δεν έχει δικό της intent, ανοίγει απλώς την εφαρμογή.
     */
    private fun openNotifApp(n: NotifListener.Item) {
        val go = {
            var ok = false
            n.open?.let { pi ->
                try {
                    if (Build.VERSION.SDK_INT >= 34) {
                        val opts = android.app.ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(
                                android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        pi.send(ctx, 0, null, null, null, null, opts.toBundle())
                    } else pi.send()
                    ok = true
                } catch (_: Exception) {}
            }
            if (!ok && n.pkg.isNotEmpty()) {
                try {
                    ctx.packageManager.getLaunchIntentForPackage(n.pkg)?.let {
                        ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                } catch (_: Exception) {}
            }
            onExit()   // κλείνει το StandBy ώστε να φανεί η εφαρμογή
        }
        val act = ctx as? android.app.Activity
        val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
        if (act != null && km != null && km.isKeyguardLocked) {
            km.requestDismissKeyguard(act, object : android.app.KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { go() }
            })
        } else go()
    }

    /** Χρώμα κάρτας ειδοποίησης ανάλογα με την κατηγορία. */
    private fun kindColor(k: Kind) = when (k) {
        Kind.MESSAGE -> Color.parseColor("#30D158")   // πράσινο
        Kind.EMAIL -> Color.parseColor("#0A84FF")     // μπλε
        Kind.CALL -> Color.parseColor("#FF453A")      // κόκκινο
        Kind.CALENDAR -> Color.parseColor("#BF5AF2")  // μωβ
        Kind.SOCIAL -> Color.parseColor("#FF9F0A")    // πορτοκαλί
        Kind.PROMO -> Color.parseColor("#8E8E93")     // γκρι
        Kind.SYSTEM -> Color.parseColor("#64D2FF")    // γαλάζιο
        Kind.OTHER -> cNotif
    }

    private fun withAlpha(color: Int, a: Int) = Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    private fun setBody(container: LinearLayout, vararg views: View) {
        container.removeAllViews()
        for (v in views) container.addView(v)
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

    private fun dp(v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
}
