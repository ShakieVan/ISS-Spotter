package de.shakie.iss.graphics

import kotlin.math.*

/**
 * ISS-centred, roll-locked orbit camera. The up reference is the geographic NORTH
 * TANGENT at the ISS, not global +Y and not the camera's previous up vector.
 * Orientation is reconstructed from two independent angles. Closed touch paths
 * therefore cannot accumulate the trackball roll of successive screen-axis rotations.
 */
class OrbitCameraController {
    private var yawDegrees = 0.0
    private var pitchDegrees = 0.0
    private val initialNorthElevation = 90.0 - DEFAULT_ELEVATION_DEG
    private val initialForward = OrbitVector(0.0, sin(Math.toRadians(initialNorthElevation)), -cos(Math.toRadians(initialNorthElevation)))
    private val initialUp = OrbitVector(0.0, cos(Math.toRadians(initialNorthElevation)), sin(Math.toRadians(initialNorthElevation)))

    // Effective angles are also used by ADB. Touch keeps unclipped displacement
    // during one gesture so a loop that briefly reaches a limit still closes.
    var yawOffsetDeg: Float
        get() = yawDegrees.toFloat()
        set(value) { if (value.isFinite()) yawDegrees = wrap(value.toDouble()) }
    var pitchOffsetDeg: Float
        get() = effectivePitch().toFloat()
        set(value) { if (value.isFinite()) pitchDegrees = boundedPitch(value.toDouble()) }

    var zoomFactor: Float = 1f
        set(value) { if (value.isFinite()) field = value.coerceIn(MIN_ZOOM, MAX_ZOOM) }
    var viewportAspect: Float = 0.56f
        set(value) { if (value.isFinite() && value > 0f) field = value.coerceIn(0.2f, 4f) }

    fun isModified(): Boolean {
        val (forward, up) = localOrientation()
        return forward.dot(initialForward) < cos(Math.toRadians(0.5)) ||
            up.dot(initialUp) < cos(Math.toRadians(0.5)) || abs(zoomFactor - 1f) > 0.02f
    }

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
        // Keep the existing wide zoom range, also with Earth between eye and ISS.
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

    /** Right/down drags move the background right/down. No accumulated roll. */
    fun orbitByPixels(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        yawDegrees = wrap(yawDegrees + dx.toDouble() * DRAG_DEGREES_PER_PIXEL)
        pitchDegrees += dy.toDouble() * DRAG_DEGREES_PER_PIXEL
    }

    /**
     * Called at touch/pointer/lifecycle boundaries, never during a one-finger loop.
     * Discard excess beyond a pole limit so the NEXT gesture responds immediately.
     * This changes neither the displayed pose nor zoom.
     */
    fun endOrbitGesture() {
        pitchDegrees = effectivePitch()
    }

    private fun boundedPitch(value: Double) = value.coerceIn(
        -MAX_NORTH_ELEVATION_DEG - initialNorthElevation,
        MAX_NORTH_ELEVATION_DEG - initialNorthElevation
    )
    private fun effectivePitch() = boundedPitch(pitchDegrees)

    private fun localOrientation(): Pair<OrbitVector, OrbitVector> {
        val yaw = Math.toRadians(yawDegrees)
        val elevation = Math.toRadians(initialNorthElevation + effectivePitch())
        val sy = sin(yaw); val cy = cos(yaw)
        val se = sin(elevation); val ce = cos(elevation)
        // Local frame: X=east, Y=north tangent, Z=radially outward.
        // Analytical orthonormal basis; no normalization of a nearly zero north projection.
        val forward = OrbitVector(-sy * ce, se, -cy * ce)
        val up = OrbitVector(sy * se, ce, cy * se)
        return forward to up
    }

    @Suppress("UNUSED_PARAMETER")
    fun computeCameraPose(issPos: FloatArray, observerPos: FloatArray): FloatArray {
        val iss = OrbitVector.from(issPos)
        val radial = iss.unit(OrbitVector.X)
        val north = (OrbitVector.Y - radial * radial.y).unit(OrbitVector.Z)
        val east = north.cross(radial).unit(OrbitVector.Z * -1.0)
        fun toWorld(v: OrbitVector) = east * v.x + north * v.y + radial * v.z
        val (localForward, localUp) = localOrientation()
        val forward = toWorld(localForward).unit()
        val up = toWorld(localUp).unit()
        val distance = cameraDistance()
        // Earth crossing remains permitted. Do not relocate the camera or pivot.
        val eye = iss - forward * distance
        val target = if (distance >= 1.0) iss else eye + forward
        return eye.floats() + target.floats() + up.floats()
    }

    fun reset() {
        yawDegrees = 0.0
        pitchDegrees = 0.0
        zoomFactor = 1f
    }

    private fun wrap(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0

    companion object {
        const val MIN_ZOOM = 0.35f
        const val MAX_ZOOM = 12f
        const val DEFAULT_ELEVATION_DEG = 32.0
        const val DRAG_DEGREES_PER_PIXEL = 0.16
        // A north-locked camera has no defined roll looking exactly along +/-north.
        // Stop 0.5 degrees short; nadir (elevation=0) is NOT a singularity or a limit.
        const val MAX_NORTH_ELEVATION_DEG = 89.5
        private const val PINCH_GAIN = 2.0
    }
}
