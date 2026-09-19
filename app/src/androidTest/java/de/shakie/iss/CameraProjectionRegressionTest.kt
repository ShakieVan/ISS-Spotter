package de.shakie.iss

import android.graphics.Matrix
import android.graphics.Rect
import de.shakie.iss.observer.*
import org.junit.Test
import kotlin.math.*

/** Instrumented tests: exercise the real Android Matrix and the production projector. */
class CameraProjectionRegressionTest {
    private fun near(actual: Float, expected: Float, tolerance: Float = 0.05f) {
        check(abs(actual - expected) <= tolerance) { "Expected $expected, got $actual" }
    }

    private fun portraitData() = CameraProjectionData(
        calibrationAccuracy = CalibrationAccuracy.CALIBRATED_INTRINSICS,
        activeArraySize = Rect(0, 0, 4000, 3000),
        intrinsicCalibration = floatArrayOf(3000f, 3000f, 2000f, 1500f, 0f),
        sensorOrientation = 90, viewWidth = 1080, viewHeight = 1920,
        // Native sensor image rotated 90 degrees clockwise, fillCenter at scale 0.48.
        sensorToViewTransform = Matrix().apply {
            setValues(floatArrayOf(0f, -0.48f, 1260f, 0.48f, 0f, 0f, 0f, 0f, 1f))
        }
    )

    @Test fun sensorPathKeepsEastRightAndZenithUp() {
        val pose = CameraProjector.createRotationMatrix(0f, 0f, 0f)
        val data = portraitData()
        val east = CameraProjector.projectDirection(10.0, 0.0, pose, data)
        val up = CameraProjector.projectDirection(0.0, 10.0, pose, data)
        near(east.screenX, 540f + 1440f * tan(Math.toRadians(10.0)).toFloat())
        near(east.screenY, 960f)
        near(up.screenX, 540f)
        near(up.screenY, 960f - 1440f * tan(Math.toRadians(10.0)).toFloat())
    }

    @Test fun opticalCenterAndSkewAreNotDiscarded() {
        val pose = CameraProjector.createRotationMatrix(0f, 0f, 0f)
        val data = portraitData().copy(intrinsicCalibration = floatArrayOf(3000f, 2800f, 2100f, 1400f, 50f))
        val center = CameraProjector.projectDirection(0.0, 0.0, pose, data)
        near(center.screenX, 588f)
        near(center.screenY, 1008f)
        val east = CameraProjector.projectDirection(10.0, 0.0, pose, data)
        val tangent = tan(Math.toRadians(10.0)).toFloat()
        near(east.screenX, 588f + 0.48f * 2800f * tangent)
        near(east.screenY, 1008f - 0.48f * 50f * tangent)
    }

    @Test fun landscapeDisplayRotationHasTheCorrectSign() {
        val ray = floatArrayOf(1f, 2f, -3f)
        val at90 = CameraProjector.deviceToViewFrame(ray, 1)
        val at270 = CameraProjector.deviceToViewFrame(ray, 3)
        check(at90.contentEquals(floatArrayOf(-2f, -1f, 3f)))
        check(at270.contentEquals(floatArrayOf(2f, 1f, 3f)))
        val pose = CameraProjector.createRotationMatrix(0f, 0f, 0f)
        val data = portraitData().copy(
            displayRotation = 1, viewWidth = 1920, viewHeight = 1080,
            sensorToViewTransform = Matrix().apply {
                setValues(floatArrayOf(0.48f, 0f, 0f, 0f, 0.48f, -180f, 0f, 0f, 1f))
            }
        )
        val east = CameraProjector.projectDirection(10.0, 0.0, pose, data)
        near(east.screenX, 960f)
        near(east.screenY, 540f - 1440f * tan(Math.toRadians(10.0)).toFloat())
    }

    @Test fun horizonUsesTheSameCalibrationAsTargets() {
        val pose = CameraProjector.createRotationMatrix(0f, 12f, 28f)
        val data = portraitData().copy(intrinsicCalibration = floatArrayOf(3000f, 2800f, 2100f, 1400f, 50f))
        val horizon = CameraProjector.projectHorizonLine(pose, data)
        check(horizon.isVisible)
        val dx = horizon.endX - horizon.startX
        val dy = horizon.endY - horizon.startY
        for (azimuth in listOf(-10.0, 0.0, 10.0)) {
            val p = CameraProjector.projectDirection(azimuth, 0.0, pose, data)
            check(!p.isBehindCamera)
            val distance = abs(dy * (p.screenX - horizon.startX) - dx * (p.screenY - horizon.startY)) / hypot(dx, dy)
            near(distance, 0f)
        }
    }

    @Test fun behindCameraRemainsOffscreen() {
        val p = CameraProjector.projectDirection(180.0, 0.0,
            CameraProjector.createRotationMatrix(0f, 0f, 0f), portraitData())
        check(p.isBehindCamera && !p.isInViewBounds)
        check(p.screenX.isFinite() && p.screenY.isFinite())
    }

    @Test fun cameraTransformDoesNotReceiveZoomTwice() {
        val pose = CameraProjector.createRotationMatrix(0f, 0f, 0f)
        val data = portraitData()
        val first = CameraProjector.projectDirection(5.0, 3.0, pose, data)
        val second = CameraProjector.projectDirection(5.0, 3.0, pose, data.copy(currentZoomRatio = 2f))
        near(first.screenX, second.screenX)
        near(first.screenY, second.screenY)
    }

    @Test fun horizonOutsideTheImageIsNotReportedVisible() {
        val pose = CameraProjector.createRotationMatrix(0f, 80f, 0f)
        check(!CameraProjector.projectHorizonLine(pose, portraitData()).isVisible)
    }
}
