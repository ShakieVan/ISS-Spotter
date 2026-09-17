package de.shakie.iss.orbit

import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

data class IssPass(
    val riseTimeMillis: Long,
    val maxTimeMillis: Long,
    val setTimeMillis: Long,
    val maxElevationDeg: Double,
    val isVisibleOptically: Boolean // In twilight with ISS in sunlight
) {
    fun formatDescription(nowMillis: Long = System.currentTimeMillis()): String {
        val diffMin = (riseTimeMillis - nowMillis) / 60000L
        val sdf = SimpleDateFormat("HH:mm", Locale.GERMANY)
        val timeStr = sdf.format(Date(maxTimeMillis))

        return if (diffMin <= 0) {
            String.format("Überflug JETZT! (Max: %.0f°)", maxElevationDeg)
        } else if (diffMin < 60) {
            String.format("In %d Min. um %s Uhr (Max: %.0f°)", diffMin, timeStr, maxElevationDeg)
        } else {
            val hours = diffMin / 60
            val mins = diffMin % 60
            String.format("In %dh %02dm um %s Uhr (Max: %.0f°)", hours, mins, timeStr, maxElevationDeg)
        }
    }
}

object PassPredictor {

    /**
     * Calculates the next visible or highest pass within the next 48 hours.
     */
    fun findNextPass(
        propagator: Sgp4Propagator,
        obsLatDeg: Double,
        obsLonDeg: Double,
        obsAltKm: Double = 0.05,
        startMillis: Long = System.currentTimeMillis()
    ): IssPass? {
        val stepMillis = 45 * 1000L // 45-second scan step
        val maxScanMillis = 48 * 3600 * 1000L // 48 hours

        var t = startMillis
        val endScan = startMillis + maxScanMillis

        var inPass = false
        var riseTime = 0L
        var maxEl = -90.0
        var maxTime = 0L

        while (t < endScan) {
            val state = propagator.propagate(t)
            val horiz = TopocentricPosition.calculate(
                obsLatDeg = obsLatDeg,
                obsLonDeg = obsLonDeg,
                obsAltKm = obsAltKm,
                issLatDeg = state.latitudeDeg,
                issLonDeg = state.longitudeDeg,
                issAltKm = state.altitudeKm
            )

            if (horiz.elevationDeg > 0.0) {
                if (!inPass) {
                    inPass = true
                    riseTime = t
                    maxEl = horiz.elevationDeg
                    maxTime = t
                } else {
                    if (horiz.elevationDeg > maxEl) {
                        maxEl = horiz.elevationDeg
                        maxTime = t
                    }
                }
            } else {
                if (inPass) {
                    // Pass ended! If culmination was significant (> 12 degrees), return it!
                    if (maxEl >= 12.0) {
                        return IssPass(
                            riseTimeMillis = riseTime,
                            maxTimeMillis = maxTime,
                            setTimeMillis = t,
                            maxElevationDeg = maxEl,
                            isVisibleOptically = true
                        )
                    }
                    inPass = false
                }
            }

            t += stepMillis
        }

        return null
    }
}
