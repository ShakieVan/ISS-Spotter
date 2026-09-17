package de.shakie.iss.graphics

import android.opengl.Matrix
import kotlin.math.*

class OrbitCameraController {

    // User touch offsets (for free look-around)
    var yawOffsetDeg: Float = 0.0f
    var pitchOffsetDeg: Float = 0.0f
    var zoomFactor: Float = 1.0f

    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    /**
     * Computes camera position, look-at target, and camera up vector.
     * @param issPos 3D coordinate of ISS in world space (Earth at 0,0,0)
     * @param observerPos 3D coordinate of Observer on Earth surface
     * @return 9-element array: [eyeX, eyeY, eyeZ, targetX, targetY, targetZ, upX, upY, upZ]
     */
    fun computeCameraPose(
        issPos: FloatArray,
        observerPos: FloatArray
    ): FloatArray {
        val issDist = sqrt(issPos[0] * issPos[0] + issPos[1] * issPos[1] + issPos[2] * issPos[2])
        val upIss = floatArrayOf(issPos[0] / issDist, issPos[1] / issDist, issPos[2] / issDist)

        // Direction towards observer on Earth
        val toObs = floatArrayOf(
            observerPos[0] - issPos[0],
            observerPos[1] - issPos[1],
            observerPos[2] - issPos[2]
        )
        val obsDist = sqrt(toObs[0] * toObs[0] + toObs[1] * toObs[1] + toObs[2] * toObs[2]).coerceAtLeast(0.001f)
        val forward = floatArrayOf(toObs[0] / obsDist, toObs[1] / obsDist, toObs[2] / obsDist)

        // Earth center is at (0,0,0), so nadir vector (towards Earth center) is -upIss.
        // Project -upIss onto plane perpendicular to forward to find screen "Up" (towards Earth)
        val dotF = -upIss[0] * forward[0] - upIss[1] * forward[1] - upIss[2] * forward[2]
        var upX = -upIss[0] - forward[0] * dotF
        var upY = -upIss[1] - forward[1] * dotF
        var upZ = -upIss[2] - forward[2] * dotF
        val upLen = sqrt(upX * upX + upY * upY + upZ * upZ)

        val camUp = if (upLen > 0.01f) {
            floatArrayOf(upX / upLen, upY / upLen, upZ / upLen)
        } else {
            floatArrayOf(0f, 1f, 0f)
        }

        // Camera distance behind ISS along line of sight to Earth
        val camDist = 1.35f * zoomFactor
        // Vertical shift along camUp: Shifting camera towards Earth moves ISS down towards bottom of screen!
        val camShift = 0.28f * zoomFactor

        val eyeX = issPos[0] - forward[0] * camDist + camUp[0] * camShift
        val eyeY = issPos[1] - forward[1] * camDist + camUp[1] * camShift
        val eyeZ = issPos[2] - forward[2] * camDist + camUp[2] * camShift

        // Look-at target: Along forward direction towards Earth
        val targetX = eyeX + forward[0] * 3.0f
        val targetY = eyeY + forward[1] * 3.0f
        val targetZ = eyeZ + forward[2] * 3.0f

        return floatArrayOf(eyeX, eyeY, eyeZ, targetX, targetY, targetZ, camUp[0], camUp[1], camUp[2])
    }

    fun reset() {
        yawOffsetDeg = 0.0f
        pitchOffsetDeg = 0.0f
        zoomFactor = 1.0f
    }
}
