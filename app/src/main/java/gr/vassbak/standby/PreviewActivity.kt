package gr.vassbak.standby

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.OrientationEventListener
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Το StandBy ως οθόνη: ανοίγει όταν σβήνει η οθόνη (από την υπηρεσία) ή από το «Δοκιμή τώρα».
 * Φαίνεται πάνω από την οθόνη κλειδώματος. Διπλό πάτημα ή «πίσω» για έξοδο.
 */
class PreviewActivity : Activity() {

    companion object {
        /** Η τελευταία φορά στο «αυτόματα», για να μη γυρίσει πίσω όταν ξαναστηθεί η οθόνη. */
        private var lastFollow = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private lateinit var dashboard: Dashboard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Αυτόματα: όρθιο ή πλάγιο αριστερά/δεξιά (όχι ανάποδα), ακόμα κι αν είναι κλειδωμένη η περιστροφή.
        // Σε κάθε γύρισμα η οθόνη ξαναστήνεται με την κατάλληλη διάταξη.
        applyOrientation()
        if (Prefs.followsPhone(this)) follow = object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN || Prefs.orientLocked(this@PreviewActivity)) return
                // Περιθώριο ±30° γύρω από κάθε θέση, για να μη «χορεύει» στις ενδιάμεσες γωνίες.
                // Το ανάποδο όρθιο (≈180°) αγνοείται: η οθόνη μένει όπως ήταν.
                val target = when {
                    deg >= 330 || deg <= 30 -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    deg in 60..120 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                    deg in 240..300 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    else -> return
                }
                if (target != requestedOrientation) {
                    lastFollow = target
                    requestedOrientation = target
                }
            }
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dashboard = Dashboard(this, onExit = { finish() }, onNightChanged = { night ->
            window.attributes = window.attributes.apply { screenBrightness = if (night) 0.05f else -1f }
        }, onOrientLock = { applyOrientation() })
        setContentView(dashboard.view)
        hideBars()
    }

    private fun hideBars() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private var follow: OrientationEventListener? = null

    /** Κλειδωμένη (από το κουμπάκι): μένει όπως είναι τώρα. Αλλιώς ανάλογα με τη «Φορά οθόνης». */
    private fun applyOrientation() {
        requestedOrientation = when {
            Prefs.orientLocked(this) -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            Prefs.followsPhone(this) -> lastFollow
            Prefs.portrait(this) -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT   // όρθιο μόνο, ποτέ ανάποδα
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    override fun onResume() {
        super.onResume()
        follow?.let { if (it.canDetectOrientation()) it.enable() }
        dashboard.start()
    }

    override fun onPause() {
        follow?.disable()
        dashboard.stop()
        super.onPause()
    }
}
