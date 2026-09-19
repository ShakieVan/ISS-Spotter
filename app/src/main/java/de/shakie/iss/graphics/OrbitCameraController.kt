package de.shakie.iss.graphics

import kotlin.math.*

class OrbitCameraController {
    var yawOffsetDeg: Float = 0f
    var pitchOffsetDeg: Float = 0f
    // 1 = scale-correct close-up, MAX_ZOOM = complete globe at 48% viewport width.
    // A logarithmic distance curve covers metres to tens of thousands of kilometres.
    var zoomFactor: Float = 1f
        set(value) { if (value.isFinite()) field = value.coerceIn(MIN_ZOOM, MAX_ZOOM) }
    var viewportAspect: Float = 0.56f
        set(value) { if (value.isFinite() && value > 0f) field = value.coerceIn(0.2f, 4f) }

    fun isModified() = abs(yawOffsetDeg % 360f) > 0.5f || abs(pitchOffsetDeg) > 0.5f || abs(zoomFactor - 1f) > 0.02f

    fun defaultDistance(): Double {
        val tanH = tan(Math.toRadians(OrbitScale.FOV_Y_DEG / 2.0)) * viewportAspect
        return OrbitScale.ISS_WORLD_SPAN / (2.0 * tanH * 0.24)
    }

    fun cameraDistance(): Double {
        val close = defaultDistance()
        if (zoomFactor <= 1f) return close * zoomFactor
        val end = OrbitScale.earthOverviewDistance(viewportAspect.toDouble())
        val t = ((zoomFactor - 1f) / (MAX_ZOOM - 1f)).toDouble()
        return close * exp(ln(end / close) * t)
    }

    @Suppress("UNUSED_PARAMETER")
    fun computeCameraPose(issPos: FloatArray, observerPos: FloatArray): FloatArray {
        val iss = OrbitVector.from(issPos)
        val radial = iss.unit(OrbitVector.X)
        val nadir = radial * -1.0
        val north = (OrbitVector.Y - radial * radial.y).unit(OrbitVector.Z)
        val east = north.cross(radial).unit(OrbitVector.Z * -1.0)
        // At real ISS altitude the limb is about 70 degrees from nadir.
        val tilt = Math.toRadians(58.0)
        val baseForward = nadir * cos(tilt) + north * sin(tilt)
        val baseUp = north * cos(tilt) - nadir * sin(tilt)
        val yaw = Math.toRadians(yawOffsetDeg.toDouble())
        val pitch = Math.toRadians(pitchOffsetDeg.coerceIn(-85f, 85f).toDouble())
        val fYaw = baseForward * cos(yaw) - east * sin(yaw)
        var forward = (fYaw * cos(pitch) + baseUp * sin(pitch)).unit()
        var up = northUp(forward, baseUp)
        val distance = cameraDistance()
        // Recenter on Earth before distant viewpoints; a fixed ISS framing would lose the globe.
        val transition = ((ln(distance / 0.03) / ln(20.0 / 0.03))).coerceIn(0.0, 1.0)
        val blend = transition * transition * (3.0 - 2.0 * transition)
        val anchor = iss * (1.0 - blend)
        var eye = anchor - forward * distance + up * (distance * 0.16 * (1.0 - blend))
        val minimumRadius = OrbitScale.EARTH_RADIUS + OrbitScale.metersToWorld(150.0)
        if (eye.length() < minimumRadius) {
            // Do not let free-orbit gestures move the camera through the Earth.
            eye = eye.unit(radial) * minimumRadius
            forward = (anchor - eye).unit(forward)
            up = northUp(forward, baseUp)
        }
        // A unit-length look vector avoids loss of precision at the scale-correct near distance.
        val target = eye + forward
        return eye.floats() + target.floats() + up.floats()
    }

    private fun northUp(forward: OrbitVector, fallback: OrbitVector): OrbitVector {
        val projected = OrbitVector.Y - forward * forward.y
        return if (projected.length() > 1e-5) projected.unit()
            else (fallback - forward * fallback.dot(forward)).unit(OrbitVector.Z)
    }

    fun reset() { yawOffsetDeg = 0f; pitchOffsetDeg = 0f; zoomFactor = 1f }

    companion object {
        const val MIN_ZOOM = 0.35f
        const val MAX_ZOOM = 12f
    }
}
