package de.shakie.iss.graphics

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton
import de.shakie.iss.R
import de.shakie.iss.observer.IssCalloutOverlayView

/** Independent, persisted option for each scene. The XML tag is "orbit" or "observer". */
class TrajectoryToggleButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatButton(context, attrs) {
    private val preferences = context.getSharedPreferences("trajectory_options", Context.MODE_PRIVATE)
    private var enabledTrail = true
    private val key get() = if (tag == "observer") "observer" else "orbit"

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        enabledTrail = preferences.getBoolean(key, true)
        // All sibling views have been inflated when this runs.
        post { applyState() }
        setOnClickListener {
            enabledTrail = !enabledTrail
            preferences.edit().putBoolean(key, enabledTrail).apply()
            applyState()
        }
    }

    private fun applyState() {
        text = "〰 Flugbahn: ${if (enabledTrail) "AN" else "AUS"}"
        contentDescription = "Flugbahn ${if (enabledTrail) "eingeschaltet" else "ausgeschaltet"}. Vergangenheit durchgezogen, Zukunft gestrichelt."
        if (key == "orbit") {
            rootView.findViewById<IssGlobeOverlayView>(R.id.globeOverlayView)?.trajectoryVisible = enabledTrail
        } else {
            rootView.findViewById<IssCalloutOverlayView>(R.id.calloutOverlayView)?.trajectoryVisible = enabledTrail
        }
    }
}
