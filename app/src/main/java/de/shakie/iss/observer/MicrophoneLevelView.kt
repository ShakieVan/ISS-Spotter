package de.shakie.iss.observer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import java.util.Locale
import kotlin.math.ceil

/** UI-only view inside the button panel. Never add this view to the recorded AR overlay. */
class MicrophoneLevelView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10f * resources.displayMetrics.scaledDensity
    }
    private var reading = MicrophoneReading()
    private val quiet = Color.rgb(65, 83, 96)
    private val normal = Color.rgb(66, 210, 145)
    private val amber = Color.rgb(255, 193, 70)
    private val red = Color.rgb(255, 90, 90)

    init {
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Mikrofonpegel: aktiv während der Videoaufnahme; nicht im Video eingeblendet."
    }

    fun setReading(value: MicrophoneReading) {
        if (reading == value) return
        reading = value
        contentDescription = description(SystemClock.elapsedRealtime())
        invalidate()
    }

    private fun description(now: Long): String = when {
        reading.isStale(now) -> "Mikrofon: keine aktuellen Pegeldaten"
        else -> when (reading.state) {
            MicrophoneState.IDLE -> "Mikrofon · Pegel während Videoaufnahme"
            MicrophoneState.STARTING -> "Mikrofon startet …"
            MicrophoneState.FINALIZING -> "Mikrofon beendet · Video wird gespeichert"
            MicrophoneState.DISABLED -> "Kein Ton · Audio nicht aktiviert"
            MicrophoneState.SILENCED -> "Kein Ton · Mikrofon vom System stummgeschaltet"
            MicrophoneState.MUTED -> "Kein Ton · Mikrofon stumm"
            MicrophoneState.SOURCE_ERROR -> "Tonfehler · Mikrofon nicht verfügbar"
            MicrophoneState.ENCODER_ERROR -> "Tonfehler · Audiokodierung fehlgeschlagen"
            MicrophoneState.UNKNOWN -> "Mikrofonstatus unbekannt"
            MicrophoneState.ACTIVE -> {
                val db = reading.peakDbfs
                when {
                    db == null -> "Ton aktiv · Pegel nicht verfügbar"
                    db == Double.NEGATIVE_INFINITY -> "Mikrofon: −∞ dBFS"
                    db <= MicrophoneReading.FLOOR_DBFS -> "Mikrofon: ≤ −60 dBFS"
                    db >= -1.0 -> "Mikrofon: %.0f dBFS · Pegel sehr hoch".format(Locale.GERMANY, db)
                    else -> "Mikrofon: %.0f dBFS".format(Locale.GERMANY, db)
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.elapsedRealtime()
        val stale = reading.isStale(now)
        val active = reading.state == MicrophoneState.ACTIVE && !stale
        val label = description(now)
        textPaint.color = if (reading.state.warning || stale) amber else Color.WHITE
        val x = 4f * density
        canvas.drawText(label, x, 12f * density, textPaint)
        val top = 19f * density
        val bottom = (height - 3f * density).coerceAtLeast(top + 2f * density)
        val available = (width - 8f * density).coerceAtLeast(1f)
        val count = 24
        val gap = density
        val segment = (available / count - gap).coerceAtLeast(0.5f)
        val lit = if (active) ceil(reading.barFraction * count).toInt().coerceIn(0, count) else 0
        for (i in 0 until count) {
            paint.color = if (i >= lit) quiet else when {
                i >= 22 -> red
                i >= 19 -> amber
                else -> normal
            }
            val left = x + i * (segment + gap)
            canvas.drawRect(left, top, left + segment, bottom, paint)
        }
        // A stalled recorder must not leave an old, plausible-looking level on screen.
        // This only redraws cached stats; it never reads or opens the microphone.
        if (active && isShown) postInvalidateDelayed(250L)
    }
}
