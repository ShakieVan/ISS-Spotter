package de.shakie.iss.orbit

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/** Geometric predictions, not a guarantee against cloud, terrain or stale orbital elements. */
data class PassWindow(val startMillis: Long, val endMillis: Long) {
    init { require(endMillis >= startMillis) }
}

data class IssPass(
    val riseTimeMillis: Long,
    val maxTimeMillis: Long,
    val setTimeMillis: Long,
    val maxElevationDeg: Double,
    val isVisibleOptically: Boolean,
    val sunlitWindows: List<PassWindow> = emptyList(),
    val visibleWindows: List<PassWindow> = emptyList(),
    val visibilityComputed: Boolean = false
) {
    fun formatDescription(nowMillis: Long = System.currentTimeMillis()): String {
        val at = localTime(riseTimeMillis, nowMillis)
        val minutes = ceil((riseTimeMillis - nowMillis) / 60000.0).toLong().coerceAtLeast(0L)
        return when {
            nowMillis > setTimeMillis -> "Überflug beendet – nächste Berechnung läuft"
            nowMillis >= riseTimeMillis -> "Jetzt bis ${localTime(setTimeMillis, nowMillis)} (Max: %.0f°)".format(Locale.GERMANY, maxElevationDeg)
            minutes < 60 -> "In $minutes Min. ab $at (Max: %.0f°)".format(Locale.GERMANY, maxElevationDeg)
            else -> "In ${minutes / 60}h ${minutes % 60}m ab $at (Max: %.0f°)".format(Locale.GERMANY, maxElevationDeg)
        }
    }

    fun visibilityDescription(nowMillis: Long = System.currentTimeMillis()): String = when {
        !visibilityComputed -> "SICHTFENSTER: wird berechnet …"
        visibleWindows.isNotEmpty() -> "SICHTBAR: ca. ${formatWindows(visibleWindows, nowMillis)}*"
        sunlitWindows.isEmpty() -> "NICHT SICHTBAR: ISS im Erdschatten."
        else -> "NICHT SICHTBAR: Himmel am Standort zu hell."
    }

    fun sunlightDescription(nowMillis: Long = System.currentTimeMillis()): String = when {
        !visibilityComputed -> "ISS-Beleuchtung beim Überflug: noch nicht berechnet"
        sunlitWindows.isEmpty() -> "ISS während des gesamten Überflugs im Erdschatten"
        else -> "ISS beleuchtet: ${formatWindows(sunlitWindows, nowMillis)}"
    }

    companion object {
        private fun localTime(time: Long, now: Long): String {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY)
            val pattern = if (day.format(Date(time)) == day.format(Date(now))) "HH:mm:ss" else "dd.MM. HH:mm:ss"
            return SimpleDateFormat(pattern, Locale.GERMANY).format(Date(time))
        }
        fun formatWindows(windows: List<PassWindow>, now: Long): String = windows.joinToString("; ") {
            "${localTime(it.startMillis, now)}–${localTime(it.endMillis, now)}"
        }
    }
}

/** Pure interval logic shared by production and regression tests. Edges refined to <= 1 s. */
object VisibilityWindows {
    fun find(start: Long, end: Long, stepMillis: Long = 5000L, activeAt: (Long) -> Boolean): List<PassWindow> {
        require(end >= start && stepMillis > 0)
        if (start == end) return emptyList()
        val result = ArrayList<PassWindow>()
        var t = start
        var active = activeAt(t)
        var opened = if (active) start else null
        while (t < end) {
            val next = minOf(t + stepMillis, end)
            val nextActive = activeAt(next)
            if (active != nextActive) {
                var lo = t; var hi = next
                while (hi - lo > 1000L) {
                    val mid = lo + (hi - lo) / 2
                    if (activeAt(mid) == active) lo = mid else hi = mid
                }
                val edge = lo + (hi - lo) / 2
                if (nextActive) opened = edge
                else { opened?.let { result.add(PassWindow(it, edge)) }; opened = null }
            }
            t = next; active = nextActive
        }
        opened?.let { if (end > it) result.add(PassWindow(it, end)) }
        return result
    }

    /** Sun altitude at the observer. SolarCoordinates returns the subsolar lat/lon. */
    fun solarElevation(obsLat: Double, obsLon: Double, sunLat: Double, sunLon: Double): Double {
        val lat = Math.toRadians(obsLat)
        val dec = Math.toRadians(sunLat)
        val hour = Math.toRadians(obsLon - sunLon)
        return Math.toDegrees(asin((sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(hour)).coerceIn(-1.0, 1.0)))
    }
}

object PassPredictor {
    const val MIN_PASS_ELEVATION_DEG = 12.0 // Preserve the existing pass selection criterion.
    const val MAX_OBSERVER_SUN_ELEVATION_DEG = -6.0 // Civil twilight heuristic, not weather prediction.
    const val MIN_SUNLIGHT_FACTOR = 0.15f

    fun findNextPass(
        propagator: Sgp4Propagator,
        obsLatDeg: Double,
        obsLonDeg: Double,
        obsAltKm: Double = 0.05,
        startMillis: Long = System.currentTimeMillis()
    ): IssPass? = findNextPassFromSamples(startMillis, { t ->
        val s = propagator.propagate(t)
        TopocentricPosition.calculate(obsLatDeg, obsLonDeg, obsAltKm, s.latitudeDeg, s.longitudeDeg, s.altitudeKm).elevationDeg
    }, { t ->
        val s = propagator.propagate(t)
        val sun = SolarCoordinates.calculate(t)
        EclipseCalculator.getSunlightFactor(s.latitudeDeg, s.longitudeDeg, s.altitudeKm, sun) >= MIN_SUNLIGHT_FACTOR
    }, { t ->
        val sun = SolarCoordinates.calculate(t)
        VisibilityWindows.solarElevation(obsLatDeg, obsLonDeg, sun.latitude, sun.longitude) <= MAX_OBSERVER_SUN_ELEVATION_DEG
    })

    /** Injectable ephemeris functions make day-only, eclipsed and current passes testable. */
    fun findNextPassFromSamples(
        start: Long,
        elevationAt: (Long) -> Double,
        sunlitAt: (Long) -> Boolean,
        darkAt: (Long) -> Boolean
    ): IssPass? {
        val step = 30000L
        val end = start + 48 * 3600000L
        var t = start
        // Include the beginning and culmination of a pass already above the horizon.
        if (elevationAt(t) > 0.0) {
            val limit = start - 30 * 60000L
            while (t > limit && elevationAt(t) > 0.0) t -= step
        }
        var prevEl = elevationAt(t)
        var rise: Long? = if (prevEl > 0) t else null
        var maxEl = prevEl
        var maxTime = t
        fun horizonEdge(a: Long, b: Long, rising: Boolean): Long {
            var lo = a; var hi = b
            while (hi - lo > 500L) {
                val mid = lo + (hi - lo) / 2
                if ((elevationAt(mid) > 0.0) == rising) hi = mid else lo = mid
            }
            return lo + (hi - lo) / 2
        }
        while (t < end) {
            val next = t + step
            val el = elevationAt(next)
            if (rise == null && prevEl <= 0 && el > 0) {
                rise = horizonEdge(t, next, true)
                maxEl = el; maxTime = next
            }
            if (rise != null && el > maxEl) { maxEl = el; maxTime = next }
            if (rise != null && el <= 0 && prevEl > 0) {
                val set = horizonEdge(t, next, false)
                val began = rise
                if (set > start) {
                    // Refine the peak locally to avoid missing the 12-degree threshold.
                    var peakT = maxOf(began, maxTime - step)
                    val peakEnd = minOf(set, maxTime + step)
                    while (peakT <= peakEnd) {
                        val peakEl = elevationAt(peakT)
                        if (peakEl > maxEl) { maxEl = peakEl; maxTime = peakT }
                        peakT += 1000L
                    }
                    if (maxEl >= MIN_PASS_ELEVATION_DEG) {
                        val sunlit = VisibilityWindows.find(began, set) { time -> elevationAt(time) >= 0 && sunlitAt(time) }
                        val visible = VisibilityWindows.find(began, set) { time -> elevationAt(time) >= 0 && sunlitAt(time) && darkAt(time) }
                        return IssPass(began, maxTime, set, maxEl, visible.isNotEmpty(), sunlit, visible, true)
                    }
                }
                rise = null; maxEl = -90.0
            }
            t = next; prevEl = el
        }
        return null
    }
}
