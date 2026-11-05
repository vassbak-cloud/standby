package gr.vassbak.standby

import android.service.dreams.DreamService

/** Η «προφύλαξη οθόνης» που ξεκινά μόνη της όταν το κινητό φορτίζει. */
class StandbyDream : DreamService() {

    private var dashboard: Dashboard? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true    // για swipe ανάμεσα στις σελίδες· έξοδος με διπλό πάτημα
        isFullscreen = true
        isScreenBright = true

        val d = Dashboard(this, onExit = { finish() }, onNightChanged = { night -> isScreenBright = !night })
        val wrapper = RotatedLayout(this, Prefs.rotation(this))
        wrapper.addView(d.view)
        setContentView(wrapper)
        dashboard = d
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
