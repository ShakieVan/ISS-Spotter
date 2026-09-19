package de.shakie.iss.orbit

import kotlin.math.*

/** Reconstructed/predicted positions from the same orbit model as the live ISS, not a GPS log. */
data class TrajectoryPoint(
    val timeMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeKm: Double,
    val azimuthDeg: Double,
    val elevationDeg: Double
)

data class OrbitTrajectory(
    val centerTimeMillis: Long,
    val observerLat: Double,
    val observerLon: Double,
    val observerAltKm: Double,
    val points: List<TrajectoryPoint>
) {
    fun isUsable(time: Long, latitude: Double, longitude: Double): Boolean =
        abs(time - centerTimeMillis) <= 15000L &&
            abs(latitude - observerLat) < 1e-5 && abs(longitude - observerLon) < 1e-5

    /** Both lists start at the exact CURRENT ISS point; there is no join gap or stale anchor. */
    fun halves(now: TrajectoryPoint, windowMillis: Long): Pair<List<TrajectoryPoint>, List<TrajectoryPoint>> {
        val past = ArrayList<TrajectoryPoint>()
        val future = ArrayList<TrajectoryPoint>()
        past.add(now)
        future.add(now)
        for (i in points.indices.reversed()) {
            val p = points[i]
            if (p.timeMillis < now.timeMillis && p.timeMillis >= now.timeMillis - windowMillis) past.add(p)
        }
        for (p in points) {
            if (p.timeMillis > now.timeMillis && p.timeMillis <= now.timeMillis + windowMillis) future.add(p)
        }
        return past to future
    }
}

object TrajectorySampler {
    const val GROUND_WINDOW_MILLIS = 45 * 60 * 1000L
    const val SKY_WINDOW_MILLIS = 10 * 60 * 1000L
    const val STEP_MILLIS = 5000L

    fun sample(center: Long, obsLat: Double, obsLon: Double, obsAlt: Double,
               stateAt: (Long) -> OrbitState): OrbitTrajectory {
        val anchor = Math.floorDiv(center, STEP_MILLIS) * STEP_MILLIS
        val margin = 20000L
        val points = ArrayList<TrajectoryPoint>()
        var t = anchor - GROUND_WINDOW_MILLIS - margin
        val end = anchor + GROUND_WINDOW_MILLIS + margin
        while (t <= end) {
            val s = stateAt(t)
            val h = TopocentricPosition.calculate(obsLat, obsLon, obsAlt, s.latitudeDeg, s.longitudeDeg, s.altitudeKm)
            if (s.latitudeDeg.isFinite() && s.longitudeDeg.isFinite() && h.azimuthDeg.isFinite() && h.elevationDeg.isFinite()) {
                points.add(TrajectoryPoint(t, s.latitudeDeg, s.longitudeDeg, s.altitudeKm, h.azimuthDeg, h.elevationDeg))
            }
            t += STEP_MILLIS
        }
        return OrbitTrajectory(center, obsLat, obsLon, obsAlt, points.toList())
    }

    fun fade(ageMillis: Double, windowMillis: Long): Float {
        val x = (abs(ageMillis) / windowMillis).coerceIn(0.0, 1.0)
        return ((1.0 - x) * (1.0 - x) * (1.0 + 2.0 * x)).toFloat()
    }
}

/** Viewport clipping before Canvas rendering avoids giant segments near the camera plane. */
object TrailClip {
    fun segment(x0: Double, y0: Double, x1: Double, y1: Double, w: Double, h: Double): DoubleArray? {
        if (!listOf(x0, y0, x1, y1, w, h).all { it.isFinite() } || w <= 0.0 || h <= 0.0) return null
        val dx = x1 - x0
        val dy = y1 - y0
        var lo = 0.0
        var hi = 1.0
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(x0, w - x0, y0, h - y0)
        for (i in 0..3) {
            if (abs(p[i]) < 1e-12) { if (q[i] < 0.0) return null }
            else {
                val t = q[i] / p[i]
                if (p[i] < 0) lo = max(lo, t) else hi = min(hi, t)
                if (lo > hi) return null
            }
        }
        return doubleArrayOf(x0 + lo * dx, y0 + lo * dy, x0 + hi * dx, y0 + hi * dy, lo, hi)
    }
}
