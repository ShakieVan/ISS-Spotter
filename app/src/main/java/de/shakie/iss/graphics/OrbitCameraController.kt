package de.shakie.iss.graphics
import kotlin.math.*

class OrbitCameraController {

    // User touch offsets (for free look-around)
    var yawOffsetDeg: Float = 0.0f
    var pitchOffsetDeg: Float = 0.0f
    var zoomFactor: Float = 1.0f

    /**
     * Checks whether the camera view has been modified by user gestures from default.
     */
    fun isModified(): Boolean {
        return abs(yawOffsetDeg) > 0.5f || abs(pitchOffsetDeg) > 0.5f || abs(zoomFactor - 1.0f) > 0.05f
    }

    /**
     * Computes camera position, look-at target, and camera up vector.
     * In default state (yaw=0, pitch=0), camera is placed above/behind the ISS looking down
     * at the Earth directly below the ISS with the ISS positioned in the lower half of the screen.
     * When user rotates or zooms, camera freely pivots around the ISS.
     *
     * @param issPos 3D coordinate of ISS in world space (Earth at 0,0,0)
     * @param observerPos 3D coordinate of Observer on Earth surface
     * @return 9-element array: [eyeX, eyeY, eyeZ, targetX, targetY, targetZ, upX, upY, upZ]
     */
    fun computeCameraPose(
        issPos: FloatArray,
        observerPos: FloatArray
    ): FloatArray {
        val issDist = sqrt(issPos[0] * issPos[0] + issPos[1] * issPos[1] + issPos[2] * issPos[2]).coerceAtLeast(0.001f)
        val upIss = floatArrayOf(issPos[0] / issDist, issPos[1] / issDist, issPos[2] / issDist)
        val nadir = floatArrayOf(-upIss[0], -upIss[1], -upIss[2])

        // 1. Compute North tangent on Earth sphere at ISS position
        val dotN = upIss[1] // Projection of North pole (0, 1, 0)
        val northX = -upIss[0] * dotN
        val northY = 1.0f - upIss[1] * dotN
        val northZ = -upIss[2] * dotN
        val northLen = sqrt(northX * northX + northY * northY + northZ * northZ)
        val northTangent = if (northLen > 0.01f) {
            floatArrayOf(northX / northLen, northY / northLen, northZ / northLen)
        } else {
            floatArrayOf(0f, 0f, 1f)
        }

        // East tangent = cross(upIss, northTangent)
        val eastTangent = floatArrayOf(
            upIss[1] * northTangent[2] - upIss[2] * northTangent[1],
            upIss[2] * northTangent[0] - upIss[0] * northTangent[2],
            upIss[0] * northTangent[1] - upIss[1] * northTangent[0]
        )

        // 2. Base Forward & Up (astronaut view looking down towards Earth, tilted slightly forward)
        val defaultTiltRad = Math.toRadians(12.0).toFloat()
        val baseForward = floatArrayOf(
            nadir[0] * cos(defaultTiltRad) + northTangent[0] * sin(defaultTiltRad),
            nadir[1] * cos(defaultTiltRad) + northTangent[1] * sin(defaultTiltRad),
            nadir[2] * cos(defaultTiltRad) + northTangent[2] * sin(defaultTiltRad)
        )
        val baseUp = floatArrayOf(
            northTangent[0] * cos(defaultTiltRad) - nadir[0] * sin(defaultTiltRad),
            northTangent[1] * cos(defaultTiltRad) - nadir[1] * sin(defaultTiltRad),
            northTangent[2] * cos(defaultTiltRad) - nadir[2] * sin(defaultTiltRad)
        )
        val baseRight = eastTangent

        // 3. Apply user look-around rotations (Yaw around baseUp, Pitch around rotated right)
        val yawRad = Math.toRadians(yawOffsetDeg.toDouble()).toFloat()
        val cosY = cos(yawRad)
        val sinY = sin(yawRad)

        // Rotate baseForward and baseRight around baseUp
        val fYaw = floatArrayOf(
            baseForward[0] * cosY - baseRight[0] * sinY,
            baseForward[1] * cosY - baseRight[1] * sinY,
            baseForward[2] * cosY - baseRight[2] * sinY
        )
        val rYaw = floatArrayOf(
            baseRight[0] * cosY + baseForward[0] * sinY,
            baseRight[1] * cosY + baseForward[1] * sinY,
            baseRight[2] * cosY + baseForward[2] * sinY
        )
        val uYaw = baseUp

        // Pitch around rYaw
        val pitchRad = Math.toRadians(pitchOffsetDeg.toDouble()).toFloat()
        val cosP = cos(pitchRad)
        val sinP = sin(pitchRad)

        val forward = floatArrayOf(
            fYaw[0] * cosP + uYaw[0] * sinP,
            fYaw[1] * cosP + uYaw[1] * sinP,
            fYaw[2] * cosP + uYaw[2] * sinP
        )
        val camUp = floatArrayOf(
            uYaw[0] * cosP - fYaw[0] * sinP,
            uYaw[1] * cosP - fYaw[1] * sinP,
            uYaw[2] * cosP - fYaw[2] * sinP
        )

        // 4. Camera placement behind the ISS
        val camDist = 1.35f * zoomFactor
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
