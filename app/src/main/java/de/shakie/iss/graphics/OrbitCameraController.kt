package de.shakie.iss.graphics

import kotlin.math.*

/** ISS-centred orbit camera. Pinching changes distance, never the pivot or viewing direction. */
class OrbitCameraController {
    var yawOffsetDeg: Float = 0f
        set(value) { if (value.isFinite()) field = value % 360f }
    var pitchOffsetDeg: Float = 0f
        set(value) { if (value.isFinite()) field = value.coerceIn(MIN_PITCH, MAX_PITCH) }
    // Retained as a bounded UI/debug parameter, not a physical distance multiplier.
    var zoomFactor: Float = 1f
        set(value) { if (value.isFinite()) field = value.coerceIn(MIN_ZOOM, MAX_ZOOM) }
    var viewportAspect: Float = 0.56f
        set(value) { if (value.isFinite() && value > 0f) field = value.coerceIn(0.2f, 4f) }

    fun isModified() = abs(yawOffsetDeg) > 0.5f || abs(pitchOffsetDeg) > 0.5f || abs(zoomFactor - 1f) > 0.02f

    fun defaultDistance(): Double {
        // Fit the physical model to the shorter viewport dimension, also in landscape.
        val tangent = tan(Math.toRadians(OrbitScale.FOV_Y_DEG * 0.5)) * min(viewportAspect.toDouble(), 1.0)
        return OrbitScale.ISS_WORLD_SPAN / (2.0 * tangent * 0.24)
    }

    fun maximumDistance(): Double {
        val tangent = tan(Math.toRadians(OrbitScale.FOV_Y_DEG * 0.5)) * min(viewportAspect.toDouble(), 1.0)
        val radius = OrbitScale.EARTH_RADIUS
        // Conservative LEO pivot bound. Earth need not be centred: ISS remains the pivot.
        val pivotRadius = radius + 2.0
        val k = 0.48 * tangent
        // Exact perspective sphere extent with a possibly off-axis Earth centre.
        val q = (radius * radius + sqrt(radius.pow(4) + 4.0 * k * k * radius * radius * pivotRadius * pivotRadius)) / (2.0 * k * k)
        val diameterDistance = sqrt(radius * radius + q)
        val edgeDistance = (pivotRadius + radius * sqrt(1.0 + tangent * tangent)) / tangent
        return max(diameterDistance, edgeDistance) * 1.03
    }

    fun cameraDistance(): Double {
        val close = defaultDistance()
        if (zoomFactor <= 1f) return close * zoomFactor
        val t = ((zoomFactor - 1f) / (MAX_ZOOM - 1f)).toDouble()
        return close * exp(ln(maximumDistance() / close) * t)
    }

    /** Equal pinch ratios give equal distance ratios at every zoom, including across reset. */
    fun zoomByScale(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        val close = defaultDistance()
        val end = maximumDistance()
        val distance = (cameraDistance() / scaleFactor.toDouble().pow(PINCH_GAIN)).coerceIn(close * MIN_ZOOM, end)
        zoomFactor = if (distance <= close) (distance / close).toFloat()
            else (1.0 + (MAX_ZOOM - 1.0) * ln(distance / close) / ln(end / close)).toFloat()
    }

    @Suppress("UNUSED_PARAMETER")
    fun computeCameraPose(issPos: FloatArray, observerPos: FloatArray): FloatArray {
        val iss = OrbitVector.from(issPos)
        val radial = iss.unit(OrbitVector.X)
        val north = (OrbitVector.Y - radial * radial.y).unit(OrbitVector.Z)
        val east = north.cross(radial).unit(OrbitVector.Z * -1.0)
        val yaw = Math.toRadians(yawOffsetDeg.toDouble())
        val elevation = Math.toRadians(DEFAULT_ELEVATION_DEG - pitchOffsetDeg)
        // Orbit on the spaceward hemisphere around the ISS, including a true side view (0 deg).
        // This direction is independent of zoom. For eye = ISS + direction*d with radial dot
        // direction >= 0, Earth clearance increases monotonically for ALL d >= 0.
        // No reanchoring inside Earth, no projection onto the surface and no far-side teleport.
        val horizontal = north * -cos(yaw) + east * sin(yaw)
        val away = (radial * sin(elevation) + horizontal * cos(elevation)).unit(radial)
        val forward = away * -1.0
        val projectedNorth = OrbitVector.Y - forward * forward.y
        val up = if (projectedNorth.length() > 1e-5) projectedNorth.unit()
            else (radial - forward * radial.dot(forward)).unit(north)
        val distance = cameraDistance()
        val eye = iss + away * distance
        // Close up, retain a unit look vector to avoid subtracting almost-identical float points.
        // Far away, using ISS itself avoids quantising a short unit vector at large world positions.
        // Both choices coincide at distance=1 and describe the same optical ray through the ISS.
        val target = if (distance >= 1.0) iss else eye + forward
        return eye.floats() + target.floats() + up.floats()
    }

    fun reset() { yawOffsetDeg = 0f; pitchOffsetDeg = 0f; zoomFactor = 1f }

    companion object {
        const val MIN_ZOOM = 0.35f
        const val MAX_ZOOM = 12f
        const val DEFAULT_ELEVATION_DEG = 32.0
        const val MIN_PITCH = -53f // 85 deg above the local tangent plane; avoids the orbit pole.
        const val MAX_PITCH = 32f  // Side view; never below the ISS tangent plane.
        private const val PINCH_GAIN = 2.0
    }
}
