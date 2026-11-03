package gr.vassbak.standby

import android.service.dreams.DreamService
import android.view.LayoutInflater

/** Η «προφύλαξη οθόνης» που ξεκινά μόνη της όταν το κινητό φορτίζει. */
class StandbyDream : DreamService() {

    private var dashboard: Dashboard? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false   // ένα άγγιγμα κλείνει την οθόνη, όπως στο iPhone
        isFullscreen = true
        isScreenBright = true

        val root = LayoutInflater.from(this).inflate(R.layout.dashboard, null)
        val wrapper = RotatedLayout(this, Prefs.rotation(this))
        wrapper.addView(root)
        setContentView(wrapper)

        dashboard = Dashboard(this, root) { night -> isScreenBright = !night }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        dashboard?.start()
    }

    override fun onDreamingStopped() {
        dashboard?.stop()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        dashboard?.stop()
        dashboard = null
        super.onDetachedFromWindow()
    }
}
