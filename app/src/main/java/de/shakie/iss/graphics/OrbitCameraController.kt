package de.shakie.iss.graphics

import kotlin.math.*

/**
 * Fixed ISS pivot and physical zoom. Orientation is carried in the ISS-local frame
 * (east, north, outward), not rebuilt from a possibly singular global north vector.
 * Gestures turn about the CURRENT screen axes, including after looking past nadir.
 */
class OrbitCameraController {
    private val tilt = Math.toRadians(90.0 - DEFAULT_ELEVATION_DEG)
    private val initialForward = OrbitVector(0.0, sin(tilt), -cos(tilt))
    private val initialUp = OrbitVector(0.0, cos(tilt), sin(tilt))
    private var localForward = initialForward
    private var localUp = initialUp
    private var debugYaw = 0f
    private var debugPitch = 0f

    // Absolute legacy/ADB settings. Touch input uses orbitByPixels instead; it must
    // never reconstruct an accumulated free orientation from two Euler angles.
    var yawOffsetDeg: Float
        get() = debugYaw
        set(value) { if (value.isFinite()) { debugYaw = wrap(value); setDebugOrientation() } }
    var pitchOffsetDeg: Float
        get() = debugPitch
        set(value) { if (value.isFinite()) { debugPitch = wrap(value); setDebugOrientation() } }

    var zoomFactor: Float = 1f
        set(value) { if (value.isFinite()) field = value.coerceIn(MIN_ZOOM, MAX_ZOOM) }
    var viewportAspect: Float = 0.56f
        set(value) { if (value.isFinite() && value > 0f) field = value.coerceIn(0.2f, 4f) }

    fun isModified(): Boolean = localForward.dot(initialForward) < cos(Math.toRadians(0.5)) ||
        localUp.dot(initialUp) < cos(Math.toRadians(0.5)) || abs(zoomFactor - 1f) > 0.02f

    fun defaultDistance(): Double {
        val tangent = tan(Math.toRadians(OrbitScale.FOV_Y_DEG * 0.5)) * min(viewportAspect.toDouble(), 1.0)
        return OrbitScale.ISS_WORLD_SPAN / (2.0 * tangent * 0.24)
    }

    fun maximumDistance(): Double {
        val tangent = tan(Math.toRadians(OrbitScale.FOV_Y_DEG * 0.5)) * min(viewportAspect.toDouble(), 1.0)
        val radius = OrbitScale.EARTH_RADIUS
        val pivotRadius = radius + 2.0
        val k = 0.48 * tangent
        val q = (radius * radius + sqrt(radius.pow(4) + 4.0 * k * k * radius * radius * pivotRadius * pivotRadius)) / (2.0 * k * k)
        val diameterDistance = sqrt(radius * radius + q)
        val edgeDistance = (pivotRadius + radius * sqrt(1.0 + tangent * tangent)) / tangent
        // Free orbit also allows the Earth between the camera and ISS. Include the
        // maximum forward offset of the Earth, rather than assuming a spaceward eye.
        return max(diameterDistance, edgeDistance) * 1.03 + pivotRadius
    }

    fun cameraDistance(): Double {
        val close = defaultDistance()
        if (zoomFactor <= 1f) return close * zoomFactor
        val t = ((zoomFactor - 1f) / (MAX_ZOOM - 1f)).toDouble()
        return close * exp(ln(maximumDistance() / close) * t)
    }

    /** Equal pinch ratios give equal distance ratios, without changing orientation. */
    fun zoomByScale(scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return
        val close = defaultDistance()
        val end = maximumDistance()
        val distance = (cameraDistance() / scaleFactor.toDouble().pow(PINCH_GAIN)).coerceIn(close * MIN_ZOOM, end)
        zoomFactor = if (distance <= close) (distance / close).toFloat()
            else (1.0 + (MAX_ZOOM - 1.0) * ln(distance / close) / ln(end / close)).toFloat()
    }

    /** Direct manipulation: right/down drags move the background right/down. */
    fun orbitByPixels(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        rotateScreen(dx.toDouble() * DRAG_DEGREES_PER_PIXEL, dy.toDouble() * DRAG_DEGREES_PER_PIXEL)
    }

    private fun rotateScreen(yawDegrees: Double, pitchDegrees: Double) {
        val yaw = Math.toRadians(yawDegrees % 360.0)
        val pitch = Math.toRadians(pitchDegrees % 360.0)
        // Rotation vector in the current screen plane. A single rotation keeps
        // diagonal drags independent of event subdivision and exactly reversible.
        val right = localForward.cross(localUp).unit(OrbitVector.X)
        val axisAngle = localUp * yaw + right * pitch
        val angle = axisAngle.length()
        if (angle <= 1e-14) return
        val axis = axisAngle * (1.0 / angle)
        localForward = rotate(localForward, axis, angle).unit()
        localUp = rotate(localUp, axis, angle)
        // Carry roll continuously through the poles; do not snap it to +/- global Y.
        val orthogonalRight = localForward.cross(localUp).unit(right)
        localUp = orthogonalRight.cross(localForward).unit()
    }

    private fun setDebugOrientation() {
        localForward = initialForward
        localUp = initialUp
        rotateScreen(debugYaw.toDouble(), 0.0)
        rotateScreen(0.0, debugPitch.toDouble())
    }

    @Suppress("UNUSED_PARAMETER")
    fun computeCameraPose(issPos: FloatArray, observerPos: FloatArray): FloatArray {
        val iss = OrbitVector.from(issPos)
        val radial = iss.unit(OrbitVector.X)
        val north = (OrbitVector.Y - radial * radial.y).unit(OrbitVector.Z)
        val east = north.cross(radial).unit(OrbitVector.Z * -1.0)
        fun toWorld(v: OrbitVector) = east * v.x + north * v.y + radial * v.z
        val forward = toWorld(localForward).unit()
        val up = toWorld(localUp).unit()
        val distance = cameraDistance()
        // No moving pivot, collision clamp, pole clamp or camera-position correction.
        val eye = iss - forward * distance
        val target = if (distance >= 1.0) iss else eye + forward
        return eye.floats() + target.floats() + up.floats()
    }

    fun reset() {
        debugYaw = 0f
        debugPitch = 0f
        localForward = initialForward
        localUp = initialUp
        zoomFactor = 1f
    }

    private fun rotate(v: OrbitVector, axis: OrbitVector, angle: Double): OrbitVector =
        v * cos(angle) + axis.cross(v) * sin(angle) + axis * (axis.dot(v) * (1.0 - cos(angle)))
    private fun wrap(value: Float): Float = ((value.toDouble() + 180.0) % 360.0 + 360.0).rem(360.0).minus(180.0).toFloat()

    companion object {
        const val MIN_ZOOM = 0.35f
        const val MAX_ZOOM = 12f
        const val DEFAULT_ELEVATION_DEG = 32.0
        const val DRAG_DEGREES_PER_PIXEL = 0.16
        private const val PINCH_GAIN = 2.0
    }
}
