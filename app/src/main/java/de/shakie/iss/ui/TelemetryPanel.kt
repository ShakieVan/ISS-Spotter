package de.shakie.iss.ui

import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import de.shakie.iss.R
import de.shakie.iss.orbit.IssSnapshot

/** Compact on a fresh launch. Extra telemetry remains available without covering the scene. */
class TelemetryPanel(private val card: LinearLayout) {
    private val coordinates = card.findViewById<View>(R.id.tvOrbitCoordinates)
    private val relative = card.findViewById<View>(R.id.tvObserverRelative)
    private val nextPass = card.findViewById<TextView>(R.id.tvNextPass)
    private val visibility = text(11f, Color.rgb(129,236,236))
    private val details = text(10f, Color.rgb(160,180,196))
    private val toggle = text(11f, Color.rgb(0,229,255))
    private var expanded = false

    init {
        card.addView(visibility)
        card.addView(details)
        card.addView(toggle)
        toggle.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        toggle.minHeight = (36 * card.resources.displayMetrics.density).toInt()
        toggle.isClickable = true; toggle.isFocusable = true
        toggle.setOnClickListener { expanded = !expanded; applyExpanded() }
        applyExpanded()
    }
    private fun text(size: Float, color: Int) = TextView(card.context).apply {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = (3 * resources.displayMetrics.density).toInt() }
        textSize = size; setTextColor(color)
    }
    private fun applyExpanded() {
        val value = if (expanded) View.VISIBLE else View.GONE
        coordinates.visibility = value; relative.visibility = value; details.visibility = value
        toggle.text = if (expanded) "Weniger Details ▴" else "Details ▾"
        toggle.contentDescription = if (expanded) "Zusätzliche Informationen ausblenden" else "Zusätzliche Informationen anzeigen"
    }
    fun update(snapshot: IssSnapshot) {
        val pass = snapshot.nextPass
        when {
            !snapshot.observerPositionKnown -> {
                nextPass.text = "NÄCHSTER ÜBERFLUG: Standort wird benötigt"
                visibility.text = "SICHTFENSTER: Standortfreigabe und GPS prüfen."
                details.text = "Keine Sichtbarkeitsvorhersage ohne deinen Standort."
            }
            pass != null -> {
                nextPass.text = "NÄCHSTER ÜBERFLUG: ${pass.formatDescription(snapshot.timestampMillis)}"
                visibility.text = pass.visibilityDescription(snapshot.timestampMillis)
                details.text = pass.sunlightDescription(snapshot.timestampMillis) +
                    "\n* Voraussichtlich bei freiem, klarem Himmel. Sonnenhöhe am Standort ≤ −6°. " +
                    "Wolken, Berge und Gebäude sind nicht berücksichtigt. Zeiten lokal; Bahndaten können abweichen."
            }
            snapshot.passPredictionError -> {
                nextPass.text = "NÄCHSTER ÜBERFLUG: Berechnung fehlgeschlagen"
                visibility.text = "SICHTFENSTER: noch nicht verfügbar."
                details.text = "Die Berechnung wird erneut versucht; keine Aussage zur Sichtbarkeit möglich."
            }
            snapshot.passPredictionPending -> {
                nextPass.text = "NÄCHSTER ÜBERFLUG: Berechnung läuft …"
                visibility.text = "SICHTFENSTER: wird berechnet …"
                details.text = "Beleuchtungs- und Dämmerungszeiten werden berechnet."
            }
            else -> {
                nextPass.text = "Kein Überflug ≥ 12° in den nächsten 48 Stunden gefunden."
                visibility.text = "SICHTFENSTER: keines ermittelt."
                details.text = "Berechnung anhand der verfügbaren Bahnelemente."
            }
        }
    }
}
