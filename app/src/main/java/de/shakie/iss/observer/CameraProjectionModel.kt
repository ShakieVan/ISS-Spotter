package de.shakie.iss.observer

import android.graphics.Matrix
import android.graphics.Rect
import android.util.SizeF
import android.view.Surface
import kotlin.math.*

enum class CalibrationAccuracy {
    CALIBRATED_INTRINSICS,
    APPROXIMATE_FOCAL_LENGTH,
    VIRTUAL_SKY
}

enum class SensorAccuracyLevel {
    GEOMAGNETIC_TRUE_NORTH,
    GAME_ROTATION_RELATIVE,
    SENSOR_UNAVAILABLE
}

data class CameraProjectionData(
    val calibrationAccuracy: CalibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY,
    val focalLengthMm: Float? = null,
    val sensorPhysicalSize: SizeF? = null,
    val activeArraySize: Rect? = null,
    val sensorOrientation: Int = 90,
    val lensFacing: Int = 1, // 1 = CameraMetadata.LENS_FACING_BACK
    val intrinsicCalibration: FloatArray? = null, // [fx, fy, cx, cy, s]
    val distortionModes: IntArray? = null,
    val lensDistortion: FloatArray? = null,
    val currentZoomRatio: Float = 1.0f,
    val sensorToViewTransform: Matrix? = null,
    val viewWidth: Int = 0,
    val viewHeight: Int = 0,
    val displayRotation: Int = Surface.ROTATION_0,
    val virtualHfovDeg: Float = 62.0f,
    val virtualVfovDeg: Float = 76.0f
)

data class ProjectedPoint(
    val screenX: Float,
    val screenY: Float,
    val isBehindCamera: Boolean,
    val isInViewBounds: Boolean,
    val rayCameraX: Float,
    val rayCameraY: Float,
    val rayCameraZ: Float,
    val offscreenBearingDeg: Float
)

data class HorizonLineData(
    val isVisible: Boolean,
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val rollDeg: Float
)

/**
 * Unified 3D perspective projector for AR observer view.
 * Translates topocentric celestial coordinates (azimuth, elevation)
 * through device orientation, display rotation, lens parameters and zoom
 * into screen pixel coordinates without artificial scaling hacks.
 */
object CameraProjector {

    /**
     * Converts horizontal coordinates (azimuth clockwise from True North, elevation above horizon)
     * into a unit vector in the East-North-Up (ENU) world frame.
     */
    fun horizontalToWorldEnu(azimuthDeg: Double, elevationDeg: Double): FloatArray {
        val azRad = Math.toRadians(azimuthDeg)
        val elRad = Math.toRadians(elevationDeg)
        val cosEl = cos(elRad).toFloat()
        val sinEl = sin(elRad).toFloat()
        val sinAz = sin(azRad).toFloat()
        val cosAz = cos(azRad).toFloat()

        return floatArrayOf(
            cosEl * sinAz, // +X: East
            cosEl * cosAz, // +Y: North
            sinEl          // +Z: Up (Zenith)
        )
    }

    /**
     * Transforms a world ENU vector into device coordinates (X: right, Y: up, Z: front)
     * using the 3D rotation matrix from SensorManager.
     * R maps device -> world, so R^T maps world -> device.
     */
    fun worldToDevice(worldVec: FloatArray, rotationMatrix: FloatArray): FloatArray {
        val vx = worldVec[0]
        val vy = worldVec[1]
        val vz = worldVec[2]

        val dx = rotationMatrix[0] * vx + rotationMatrix[4] * vy + rotationMatrix[8] * vz
        val dy = rotationMatrix[1] * vx + rotationMatrix[5] * vy + rotationMatrix[9] * vz
        val dz = rotationMatrix[2] * vx + rotationMatrix[6] * vy + rotationMatrix[10] * vz

        return floatArrayOf(dx, dy, dz)
    }

    /**
     * Remaps device coordinates (X_d: right in portrait, Y_d: up in portrait, Z_d: out of screen)
     * to the Display View frame (X_v: right on screen, Y_v: down on screen, Z_v: forward along rear camera line of sight).
     */
    fun deviceToViewFrame(deviceVec: FloatArray, displayRotation: Int): FloatArray {
        val xd = deviceVec[0]
        val yd = deviceVec[1]
        val zd = deviceVec[2]

        // Account for display orientation
        val xDisp: Float
        val yDisp: Float
        when (displayRotation) {
            Surface.ROTATION_90 -> {
                xDisp = yd
                yDisp = -xd
            }
            Surface.ROTATION_180 -> {
                xDisp = -xd
                yDisp = -yd
            }
            Surface.ROTATION_270 -> {
                xDisp = -yd
                yDisp = xd
            }
            else -> { // ROTATION_0 (Portrait)
                xDisp = xd
                yDisp = yd
            }
        }

        // Display View coordinate system:
        // +Zv: Forward along rear camera line of sight into the sky (-zd)
        // +Xv: Right on the screen (+xDisp)
        // +Yv: Down on the screen (-yDisp, matching Canvas pixel Y)
        return floatArrayOf(
            xDisp,  // Xv (right)
            -yDisp, // Yv (down)
            -zd     // Zv (forward)
        )
    }

    /**
     * Calculates the effective focal lengths (in view pixels) along X and Y.
     */
    fun computeEffectiveFocalLengths(
        data: CameraProjectionData,
        viewWidth: Float,
        viewHeight: Float
    ): Pair<Float, Float> {
        val zoom = data.currentZoomRatio.coerceAtLeast(1.0f)

        if (data.calibrationAccuracy == CalibrationAccuracy.VIRTUAL_SKY ||
            data.focalLengthMm == null || data.sensorPhysicalSize == null
        ) {
            // Virtual Sky / fallback projection:
            val hfovRad = Math.toRadians(data.virtualHfovDeg.toDouble()).toFloat()
            val vfovRad = Math.toRadians(data.virtualVfovDeg.toDouble()).toFloat()
            val fx = (viewWidth / 2f) / tan(hfovRad / 2f) * zoom
            val fy = (viewHeight / 2f) / tan(vfovRad / 2f) * zoom
            return Pair(fx, fy)
        }

        val fMm = data.focalLengthMm
        val physW = data.sensorPhysicalSize.width
        val physH = data.sensorPhysicalSize.height

        // Camera native horizontal & vertical FOV in landscape orientation:
        val camFovW = 2f * atan(physW / (2f * fMm))
        val camFovH = 2f * atan(physH / (2f * fMm))

        // When device is in portrait (sensorOrientation = 90 or 270):
        val isPortrait = (data.displayRotation == Surface.ROTATION_0 || data.displayRotation == Surface.ROTATION_180)
        val sensorFovX = if (isPortrait) camFovH else camFovW
        val sensorFovY = if (isPortrait) camFovW else camFovH

        // PreviewView with fillCenter uniform scale:
        val sensorAspect = tan(sensorFovX / 2f) / tan(sensorFovY / 2f)
        val viewAspect = viewWidth / viewHeight

        val fx: Float
        val fy: Float
        if (viewAspect > sensorAspect) {
            // View is wider than sensor: horizontal FOV is preserved, vertical is cropped
            fx = (viewWidth / 2f) / tan(sensorFovX / 2f) * zoom
            fy = fx // Uniform scale in fillCenter
        } else {
            // View is taller than sensor: vertical FOV is preserved, horizontal is cropped
            fy = (viewHeight / 2f) / tan(sensorFovY / 2f) * zoom
            fx = fy // Uniform scale in fillCenter
        }

        return Pair(fx, fy)
    }

    /**
     * Projects a 3D target direction into view screen coordinates.
     */
    fun projectDirection(
        azimuthDeg: Double,
        elevationDeg: Double,
        rotationMatrix: FloatArray,
        data: CameraProjectionData
    ): ProjectedPoint {
        val w = data.viewWidth.toFloat().coerceAtLeast(1f)
        val h = data.viewHeight.toFloat().coerceAtLeast(1f)
        val centerX = w / 2f
        val centerY = h / 2f

        // 1. World ENU vector
        val worldVec = horizontalToWorldEnu(azimuthDeg, elevationDeg)

        // 2. Device vector
        val devVec = worldToDevice(worldVec, rotationMatrix)

        // 3. View Frame vector
        val viewVec = deviceToViewFrame(devVec, data.displayRotation)
        val xv = viewVec[0]
        val yv = viewVec[1]
        val zv = viewVec[2]

        val isBehindCamera = zv <= 0.001f

        // 4. If CameraX sensorToViewTransform is available and calibrated:
        if (!isBehindCamera && data.sensorToViewTransform != null &&
            data.activeArraySize != null && data.calibrationAccuracy != CalibrationAccuracy.VIRTUAL_SKY
        ) {
            val activeW = data.activeArraySize.width().toFloat()
            val activeH = data.activeArraySize.height().toFloat()
            val cx = data.activeArraySize.left + activeW / 2f
            val cy = data.activeArraySize.top + activeH / 2f

            val fx: Float
            val fy: Float
            if (data.intrinsicCalibration != null && data.intrinsicCalibration.size >= 4) {
                fx = data.intrinsicCalibration[0]
                fy = data.intrinsicCalibration[1]
            } else if (data.focalLengthMm != null && data.sensorPhysicalSize != null) {
                fx = data.focalLengthMm * (activeW / data.sensorPhysicalSize.width)
                fy = data.focalLengthMm * (activeH / data.sensorPhysicalSize.height)
            } else {
                fx = activeW
                fy = activeH
            }

            // Transform view ray into sensor optical coordinates (accounting for sensor orientation)
            val xCam: Float
            val yCam: Float
            when ((data.sensorOrientation - (data.displayRotation * 90) + 360) % 360) {
                90 -> {
                    xCam = -yv
                    yCam = xv
                }
                270 -> {
                    xCam = yv
                    yCam = -xv
                }
                180 -> {
                    xCam = -xv
                    yCam = -yv
                }
                else -> {
                    xCam = xv
                    yCam = yv
                }
            }

            val sensorX = cx + fx * (xCam / zv)
            val sensorY = cy + fy * (yCam / zv)

            val srcPts = floatArrayOf(sensorX, sensorY)
            val dstPts = floatArrayOf(0f, 0f)
            data.sensorToViewTransform.mapPoints(dstPts, srcPts)

            val screenX = dstPts[0]
            val screenY = dstPts[1]
            val isInView = !isBehindCamera && (screenX in 0f..w) && (screenY in 0f..h)
            val offscreenAngle = Math.toDegrees(atan2(screenY - centerY, screenX - centerX).toDouble()).toFloat()

            return ProjectedPoint(
                screenX = screenX,
                screenY = screenY,
                isBehindCamera = isBehindCamera,
                isInViewBounds = isInView,
                rayCameraX = xv,
                rayCameraY = yv,
                rayCameraZ = zv,
                offscreenBearingDeg = offscreenAngle
            )
        }

        // 5. Perspective projection using effective view focal lengths (Virtual Sky or Direct Camera View)
        val (fxView, fyView) = computeEffectiveFocalLengths(data, w, h)

        val screenX: Float
        val screenY: Float
        if (!isBehindCamera) {
            screenX = centerX + (xv / zv) * fxView
            screenY = centerY + (yv / zv) * fyView
        } else {
            // Target behind camera: project along transverse direction (xv, yv) for edge pointing
            val r = sqrt(xv * xv + yv * yv)
            if (r > 1e-4f) {
                screenX = centerX + (xv / r) * max(w, h)
                screenY = centerY + (yv / r) * max(w, h)
            } else {
                // Directly behind camera (antipodal): indicate 180 deg turnaround (left edge)
                screenX = centerX - max(w, h)
                screenY = centerY
            }
        }

        val isInView = !isBehindCamera && (screenX in 0f..w) && (screenY in 0f..h)
        val offscreenAngle = Math.toDegrees(atan2((screenY - centerY).toDouble(), (screenX - centerX).toDouble())).toFloat()

        return ProjectedPoint(
            screenX = screenX,
            screenY = screenY,
            isBehindCamera = isBehindCamera,
            isInViewBounds = isInView,
            rayCameraX = xv,
            rayCameraY = yv,
            rayCameraZ = zv,
            offscreenBearingDeg = offscreenAngle
        )
    }

    /**
     * Projects the artificial horizon line (great circle at Elevation = 0).
     */
    fun projectHorizonLine(
        rotationMatrix: FloatArray,
        data: CameraProjectionData
    ): HorizonLineData {
        val w = data.viewWidth.toFloat().coerceAtLeast(1f)
        val h = data.viewHeight.toFloat().coerceAtLeast(1f)
        val centerX = w / 2f
        val centerY = h / 2f

        // Up vector (Zenith: 0, 0, 1) in world ENU:
        val zenithWorld = floatArrayOf(0f, 0f, 1f)
        val zenithDev = worldToDevice(zenithWorld, rotationMatrix)
        val zenithView = deviceToViewFrame(zenithDev, data.displayRotation)

        val nx = zenithView[0]
        val ny = zenithView[1]
        val nz = zenithView[2]

        val (fx, fy) = computeEffectiveFocalLengths(data, w, h)

        val a = nx / fx
        val b = ny / fy
        val c = nz

        val rollDeg = Math.toDegrees(atan2(nx.toDouble(), -ny.toDouble())).toFloat()

        if (abs(a) < 1e-6f && abs(b) < 1e-6f) {
            return HorizonLineData(isVisible = false, 0f, 0f, 0f, 0f, rollDeg)
        }

        val (u1, v1, u2, v2) = if (abs(b) > 1e-6f) {
            val startU = -w * 0.5f
            val endU = w * 1.5f
            val startV = centerY - (a * (startU - centerX) + c) / b
            val endV = centerY - (a * (endU - centerX) + c) / b
            floatArrayOf(startU, startV, endU, endV)
        } else {
            // Near-vertical horizon line
            val u = centerX - c / a
            floatArrayOf(u, -h * 0.5f, u, h * 1.5f)
        }

        val isVisible = (v1 in -h..2f * h || v2 in -h..2f * h || u1 in -w..2f * w)

        return HorizonLineData(
            isVisible = isVisible,
            startX = u1,
            startY = v1,
            endX = u2,
            endY = v2,
            rollDeg = rollDeg
        )
    }

    /**
     * Constructs a 4x4 rotation matrix representing a device oriented at
     * the given azimuth (compass bearing of rear camera in degrees),
     * pitch (elevation angle of rear camera in degrees),
     * and roll (tilt around rear camera line of sight in degrees).
     * Useful for diagnostic simulation, unit testing, and headless verification.
     */
    fun createRotationMatrix(azimuthDeg: Float, pitchDeg: Float, rollDeg: Float): FloatArray {
        val azRad = Math.toRadians(azimuthDeg.toDouble())
        val elRad = Math.toRadians(pitchDeg.toDouble())
        val rollRad = Math.toRadians(rollDeg.toDouble())

        val cosEl = cos(elRad).toFloat()
        val sinEl = sin(elRad).toFloat()
        val cosAz = cos(azRad).toFloat()
        val sinAz = sin(azRad).toFloat()

        // Line of sight unit vector in ENU:
        // L = (cosEl * sinAz, cosEl * cosAz, sinEl)
        val lx = cosEl * sinAz
        val ly = cosEl * cosAz
        val lz = sinEl

        // Device +Z points towards user face = -L
        val zx = -lx
        val zy = -ly
        val zz = -lz

        // Reference "up" in world = (0, 0, 1)
        val upX = 0f
        val upY = if (abs(sinEl) > 0.999f) 1f else 0f
        val upZ = if (abs(sinEl) > 0.999f) 0f else 1f

        // X0 = L x Up
        var x0x = ly * upZ - lz * upY
        var x0y = lz * upX - lx * upZ
        var x0z = lx * upY - ly * upX
        val x0Len = sqrt(x0x * x0x + x0y * x0y + x0z * x0z).coerceAtLeast(1e-6f)
        x0x /= x0Len
        x0y /= x0Len
        x0z /= x0Len

        // Y0 = Z_d x X0
        val y0x = zy * x0z - zz * x0y
        val y0y = zz * x0x - zx * x0z
        val y0z = zx * x0y - zy * x0x

        // Apply roll around line of sight (-Z_d)
        val cosR = cos(rollRad).toFloat()
        val sinR = sin(rollRad).toFloat()

        val xdx = cosR * x0x + sinR * y0x
        val xdy = cosR * x0y + sinR * y0y
        val xdz = cosR * x0z + sinR * y0z

        val ydx = -sinR * x0x + cosR * y0x
        val ydy = -sinR * x0y + cosR * y0y
        val ydz = -sinR * x0z + cosR * y0z

        val matrix = FloatArray(16)
        matrix[0] = xdx; matrix[4] = xdy; matrix[8] = xdz;  matrix[12] = 0f
        matrix[1] = ydx; matrix[5] = ydy; matrix[9] = ydz;  matrix[13] = 0f
        matrix[2] = zx;  matrix[6] = zy;  matrix[10] = zz;  matrix[14] = 0f
        matrix[3] = 0f;  matrix[7] = 0f;  matrix[11] = 0f;  matrix[15] = 1f

        return matrix
    }
}
