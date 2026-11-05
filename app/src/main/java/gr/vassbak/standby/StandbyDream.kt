package gr.vassbak.standby

import android.content.res.Configuration
import android.service.dreams.DreamService

/** Η «προφύλαξη οθόνης» που ξεκινά μόνη της όταν το κινητό φορτίζει. */
class StandbyDream : DreamService() {

    private var dashboard: Dashboard? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true    // για swipe ανάμεσα στις σελίδες· έξοδος με διπλό πάτημα
        isFullscreen = true
        isScreenBright = true

        build()
    }

    private fun build() {
        val d = Dashboard(this, onExit = { finish() }, onNightChanged = { night -> isScreenBright = !night })
        val wrapper = RotatedLayout(this, Prefs.rotation(this))
        wrapper.addView(d.view)
        setContentView(wrapper)
        dashboard = d
    }

    private var lastOrientation = 0

    /** Στο «αυτόματα»: όταν γυρίσει το κινητό, ξαναστήνεται με κάθετη ή οριζόντια διάταξη. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!Prefs.followsPhone(this) || newConfig.orientation == lastOrientation || dashboard == null) return
        lastOrientation = newConfig.orientation
        dashboard?.stop()
        build()
        dashboard?.start()
    }

    override fun onDreamingStarted() {
        lastOrientation = resources.configuration.orientation
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
