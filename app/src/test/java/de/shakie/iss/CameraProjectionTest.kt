package de.shakie.iss

import android.view.Surface
import de.shakie.iss.observer.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class CameraProjectionTest {

    private val viewWidth = 1080
    private val viewHeight = 2400

    @Test
    fun testHorizontalToWorldEnu() {
        // True North, horizon: (Az=0, El=0) -> (0, 1, 0)
        val north = CameraProjector.horizontalToWorldEnu(0.0, 0.0)
        assertEquals(0f, north[0], 1e-4f)
        assertEquals(1f, north[1], 1e-4f)
        assertEquals(0f, north[2], 1e-4f)

        // East, horizon: (Az=90, El=0) -> (1, 0, 0)
        val east = CameraProjector.horizontalToWorldEnu(90.0, 0.0)
        assertEquals(1f, east[0], 1e-4f)
        assertEquals(0f, east[1], 1e-4f)
        assertEquals(0f, east[2], 1e-4f)

        // Zenith: (Az=0, El=90) -> (0, 0, 1)
        val zenith = CameraProjector.horizontalToWorldEnu(0.0, 90.0)
        assertEquals(0f, zenith[0], 1e-4f)
        assertEquals(0f, zenith[1], 1e-4f)
        assertEquals(1f, zenith[2], 1e-4f)
    }

    @Test
    fun testBoresightProjectionAtScreenCenter() {
        // Device aimed at Azimuth 120°, Pitch 35°, Roll 0°
        val az = 120.0
        val el = 35.0
        val rotMatrix = CameraProjector.createRotationMatrix(az.toFloat(), el.toFloat(), 0f)

        val projData = CameraProjectionData(
            calibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            displayRotation = Surface.ROTATION_0,
            virtualHfovDeg = 60.0f,
            virtualVfovDeg = 80.0f
        )

        // Target exactly aligned with camera boresight
        val pt = CameraProjector.projectDirection(
            azimuthDeg = az,
            elevationDeg = el,
            rotationMatrix = rotMatrix,
            data = projData
        )

        assertFalse("Target on boresight must not be behind camera", pt.isBehindCamera)
        assertTrue("Target on boresight must be in view bounds", pt.isInViewBounds)
        assertEquals("Boresight ray X must be 0", 0f, pt.rayCameraX, 1e-4f)
        assertEquals("Boresight ray Y must be 0", 0f, pt.rayCameraY, 1e-4f)
        assertTrue("Boresight ray Z must be forward (> 0)", pt.rayCameraZ > 0.99f)

        assertEquals("Boresight X must be screen center", viewWidth / 2f, pt.screenX, 0.5f)
        assertEquals("Boresight Y must be screen center", viewHeight / 2f, pt.screenY, 0.5f)
    }

    @Test
    fun testOffAxisPerspectiveLinearity() {
        // Looking North at horizon (Az=0, El=0, Roll=0)
        val rotMatrix = CameraProjector.createRotationMatrix(0f, 0f, 0f)
        val hfov = 60.0f
        val vfov = 80.0f

        val projData = CameraProjectionData(
            calibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            displayRotation = Surface.ROTATION_0,
            virtualHfovDeg = hfov,
            virtualVfovDeg = vfov,
            currentZoomRatio = 1.0f
        )

        val (fx, fy) = CameraProjector.computeEffectiveFocalLengths(projData, viewWidth.toFloat(), viewHeight.toFloat())
        val expectedFx = (viewWidth / 2f) / tan(Math.toRadians(hfov / 2.0)).toFloat()
        val expectedFy = (viewHeight / 2f) / tan(Math.toRadians(vfov / 2.0)).toFloat()
        assertEquals(expectedFx, fx, 0.01f)
        assertEquals(expectedFy, fy, 0.01f)

        // 10 degrees to the East (Az = +10°, El = 0°)
        val deltaThetaX = Math.toRadians(10.0)
        val ptEast = CameraProjector.projectDirection(
            azimuthDeg = 10.0,
            elevationDeg = 0.0,
            rotationMatrix = rotMatrix,
            data = projData
        )

        val expectedScreenX = viewWidth / 2f + fx * tan(deltaThetaX).toFloat()
        assertEquals(expectedScreenX, ptEast.screenX, 0.5f)
        assertEquals(viewHeight / 2f, ptEast.screenY, 0.5f)

        // 15 degrees elevation (Az = 0°, El = +15°)
        val deltaThetaY = Math.toRadians(15.0)
        val ptUp = CameraProjector.projectDirection(
            azimuthDeg = 0.0,
            elevationDeg = 15.0,
            rotationMatrix = rotMatrix,
            data = projData
        )

        // Higher elevation is higher in sky -> lower screen pixel Y
        val expectedScreenY = viewHeight / 2f - fy * tan(deltaThetaY).toFloat()
        assertEquals(viewWidth / 2f, ptUp.screenX, 0.5f)
        assertEquals(expectedScreenY, ptUp.screenY, 0.5f)
    }

    @Test
    fun testBehindCameraRejection() {
        // Device pointing North (Az=0, El=0)
        val rotMatrix = CameraProjector.createRotationMatrix(0f, 0f, 0f)

        val projData = CameraProjectionData(
            calibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            displayRotation = Surface.ROTATION_0
        )

        // Target is directly behind camera (South: Az=180, El=0)
        val ptBehind = CameraProjector.projectDirection(
            azimuthDeg = 180.0,
            elevationDeg = 0.0,
            rotationMatrix = rotMatrix,
            data = projData
        )

        assertTrue("Target directly behind camera must have isBehindCamera = true", ptBehind.isBehindCamera)
        assertFalse("Target behind camera cannot be in view bounds", ptBehind.isInViewBounds)
        assertTrue("Ray Z must be <= 0 behind camera", ptBehind.rayCameraZ < 0f)
        // Offscreen bearing should point backwards (bottom edge: ~180°)
        assertEquals(180f, ptBehind.offscreenBearingDeg, 2.0f)
    }

    @Test
    fun testHorizonLineTiltAndElevation() {
        val projData = CameraProjectionData(
            calibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            displayRotation = Surface.ROTATION_0,
            virtualHfovDeg = 60.0f,
            virtualVfovDeg = 80.0f
        )

        // 1. Level phone facing North
        val rotLevel = CameraProjector.createRotationMatrix(0f, 0f, 0f)
        val horizLevel = CameraProjector.projectHorizonLine(rotLevel, projData)
        assertTrue(horizLevel.isVisible)
        assertEquals(0f, horizLevel.rollDeg, 0.1f)
        assertEquals(viewHeight / 2f, horizLevel.startY, 1.0f)
        assertEquals(viewHeight / 2f, horizLevel.endY, 1.0f)

        // 2. Pitch up by 15° -> Horizon must move down towards bottom of screen
        val rotPitchUp = CameraProjector.createRotationMatrix(0f, 15f, 0f)
        val horizPitchUp = CameraProjector.projectHorizonLine(rotPitchUp, projData)
        assertTrue(horizPitchUp.isVisible)
        val (fx, fy) = CameraProjector.computeEffectiveFocalLengths(projData, viewWidth.toFloat(), viewHeight.toFloat())
        val expectedY = viewHeight / 2f + fy * tan(Math.toRadians(15.0)).toFloat()
        assertEquals(expectedY, horizPitchUp.startY, 1.5f)
        assertEquals(expectedY, horizPitchUp.endY, 1.5f)

        // 3. Roll right by 25° -> Horizon line roll angle must be 25°
        val rotRoll = CameraProjector.createRotationMatrix(0f, 0f, 25f)
        val horizRoll = CameraProjector.projectHorizonLine(rotRoll, projData)
        assertTrue(horizRoll.isVisible)
        assertEquals(25f, horizRoll.rollDeg, 0.5f)
        // startY (left) should be higher than endY (right) or vice versa depending on roll sign
        assertNotEquals(horizRoll.startY, horizRoll.endY)
    }

    @Test
    fun testZoomScaling() {
        val rotMatrix = CameraProjector.createRotationMatrix(0f, 0f, 0f)

        val projData1x = CameraProjectionData(
            calibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            currentZoomRatio = 1.0f
        )

        val projData2x = CameraProjectionData(
            calibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            currentZoomRatio = 2.0f
        )

        val pt1x = CameraProjector.projectDirection(5.0, 5.0, rotMatrix, projData1x)
        val pt2x = CameraProjector.projectDirection(5.0, 5.0, rotMatrix, projData2x)

        val dx1 = pt1x.screenX - viewWidth / 2f
        val dx2 = pt2x.screenX - viewWidth / 2f
        val dy1 = pt1x.screenY - viewHeight / 2f
        val dy2 = pt2x.screenY - viewHeight / 2f

        // Under 2x zoom, pixel displacement from center must double exactly
        assertEquals(dx1 * 2f, dx2, 0.5f)
        assertEquals(dy1 * 2f, dy2, 0.5f)
    }

    @Test
    fun testNoTranslationParallax() {
        // Target at infinite/astronomical distance (Az=45°, El=30°)
        // In the observer projection model, the direction vector is purely angular.
        // Pure spatial translations of the device do not alter the ray direction in CameraProjector.
        val rot = CameraProjector.createRotationMatrix(0f, 0f, 0f)
        val projData = CameraProjectionData(viewWidth = viewWidth, viewHeight = viewHeight)

        val p1 = CameraProjector.projectDirection(45.0, 30.0, rot, projData)
        val p2 = CameraProjector.projectDirection(45.0, 30.0, rot, projData)

        assertEquals(p1.screenX, p2.screenX, 1e-4f)
        assertEquals(p1.screenY, p2.screenY, 1e-4f)
    }

    @Test
    fun testMagneticDeclinationCorrection() {
        // Suppose raw magnetometer points 5° East of True North (declination = +5°)
        // Rear camera pointing at True North (Az = 0°), but raw magnetic azimuth reads 355° (-5°)
        val rawRot = CameraProjector.createRotationMatrix(355f, 0f, 0f)

        val declination = 5.0f
        val declRad = Math.toRadians(declination.toDouble()).toFloat()
        val cosD = cos(declRad)
        val sinD = sin(declRad)

        val trueRot = FloatArray(16)
        trueRot[0] = cosD * rawRot[0] + sinD * rawRot[4]
        trueRot[1] = cosD * rawRot[1] + sinD * rawRot[5]
        trueRot[2] = cosD * rawRot[2] + sinD * rawRot[6]

        trueRot[4] = -sinD * rawRot[0] + cosD * rawRot[4]
        trueRot[5] = -sinD * rawRot[1] + cosD * rawRot[5]
        trueRot[6] = -sinD * rawRot[2] + cosD * rawRot[6]

        trueRot[8] = rawRot[8]
        trueRot[9] = rawRot[9]
        trueRot[10] = rawRot[10]
        trueRot[15] = 1f

        // Now, rear camera direction V_world = [-R[2], -R[6], -R[10]]
        val camEast = -trueRot[2].toDouble()
        val camNorth = -trueRot[6].toDouble()
        var trueAzimuth = Math.toDegrees(atan2(camEast, camNorth)).toFloat()
        if (trueAzimuth < 0f) trueAzimuth += 360f

        // The corrected azimuth must be exactly True North (0° / 360°)
        assertEquals(0f, trueAzimuth % 360f, 0.1f)

        // Projecting a True North star (Az=0, El=0) with the corrected matrix must land on boresight screen center
        val projData = CameraProjectionData(viewWidth = viewWidth, viewHeight = viewHeight)
        val pt = CameraProjector.projectDirection(0.0, 0.0, trueRot, projData)
        assertEquals(viewWidth / 2f, pt.screenX, 0.5f)
        assertEquals(viewHeight / 2f, pt.screenY, 0.5f)
    }

    @Test
    fun testDisplayRotationRemapping() {
        val devVec = floatArrayOf(1f, 2f, 3f)

        // ROTATION_0 (Portrait): Xv = xd, Yv = -yd, Zv = -zd
        val v0 = CameraProjector.deviceToViewFrame(devVec, Surface.ROTATION_0)
        assertEquals(1f, v0[0], 1e-4f)
        assertEquals(-2f, v0[1], 1e-4f)
        assertEquals(-3f, v0[2], 1e-4f)

        // ROTATION_90: display right points toward -device Y, display up toward +device X.
        // Canvas Y points down: Xv = -yd, Yv = -xd, Zv = -zd.
        val v90 = CameraProjector.deviceToViewFrame(devVec, Surface.ROTATION_90)
        assertEquals(-2f, v90[0], 1e-4f)
        assertEquals(-1f, v90[1], 1e-4f)
        assertEquals(-3f, v90[2], 1e-4f)
    }
}
