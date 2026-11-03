package gr.vassbak.standby

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager

/** Δοκιμή της οθόνης χωρίς φορτιστή. Διπλό πάτημα ή «πίσω» για έξοδο. */
class PreviewActivity : Activity() {

    private lateinit var dashboard: Dashboard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dashboard = Dashboard(this, onExit = { finish() })
        setContentView(dashboard.view)
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
