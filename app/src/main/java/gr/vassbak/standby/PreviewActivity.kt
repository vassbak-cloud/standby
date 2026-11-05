package gr.vassbak.standby

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * Το StandBy ως οθόνη: ανοίγει όταν σβήνει η οθόνη (από την υπηρεσία) ή από το «Δοκιμή τώρα».
 * Φαίνεται πάνω από την οθόνη κλειδώματος. Διπλό πάτημα ή «πίσω» για έξοδο.
 */
class PreviewActivity : Activity() {

    private lateinit var dashboard: Dashboard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        // Αυτόματα: και τις 4 φορές (όρθιο, ανάποδα, πλάγια αριστερά/δεξιά), ακόμα κι αν είναι κλειδωμένη
        // η περιστροφή. Σε κάθε γύρισμα η οθόνη ξαναστήνεται με την κατάλληλη διάταξη.
        requestedOrientation = when {
            Prefs.followsPhone(this) -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            Prefs.portrait(this) -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dashboard = Dashboard(this, onExit = { finish() }, onNightChanged = { night ->
            window.attributes = window.attributes.apply { screenBrightness = if (night) 0.05f else -1f }
        })
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

    override fun onResume() {
        super.onResume()
        dashboard.start()
    }

    override fun onPause() {
        dashboard.stop()
        super.onPause()
    }
}
