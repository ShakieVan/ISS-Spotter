package de.shakie.iss

import android.graphics.Matrix
import android.graphics.Rect
import de.shakie.iss.observer.*
import org.junit.Test
import kotlin.math.*

/** Execute on real Android Matrix, not a mock; shared production mappings only. */
class CaptureTransformTest {
    private fun matrix(vararg values:Float)=Matrix().apply { setValues(values) }
    private fun near(a:Float,b:Float){check(abs(a-b)<.1f){"$a != $b"}}
    private val sensorToPortrait:Matrix get()=matrix(0f,-.48f,1260f,.48f,0f,0f,0f,0f,1f)
    @Test fun portraitPreviewMapsBackToNativePhotoPixels() {
        val photo=Matrix()
        val bridge=CaptureTransform.viewToBuffer(sensorToPortrait,photo)!!
        val point=floatArrayOf(540f,960f,684f,960f)
        bridge.mapPoints(point)
        near(point[0],2000f);near(point[1],1500f)
        near(point[2],2000f);near(point[3],1200f)
    }
    @Test fun videoAndPreviewHaveTheSameOverlayAfterTheirTransforms() {
        val video=matrix(.5f,0f,0f,0f,.5f,-187.5f,0f,0f,1f)
        val bridge=CaptureTransform.viewToBuffer(sensorToPortrait,video)!!
        val sensor=floatArrayOf(1000f,800f,2200f,1400f,3000f,2100f)
        val view=sensor.clone();sensorToPortrait.mapPoints(view)
        val expected=sensor.clone();video.mapPoints(expected)
        bridge.mapPoints(view)
        view.indices.forEach{near(view[it],expected[it])}
    }
    @Test fun inverseFailureIsNotSilentlyTreatedAsIdentity() {
        val invalid=matrix(0f,0f,0f,0f,0f,0f,0f,0f,1f)
        check(CaptureTransform.viewToBuffer(invalid,Matrix())==null)
    }
    @Test fun liveTwoTimesZoomDoesNotFallBackToTheOneTimesStaticMatrix() {
        val grid=PixelRect(0.0,0.0,4000.0,3000.0)
        val cal=LensRayCalibration(3000.0,3000.0,2000.0,1500.0,0.0,grid,grid,
            LensProjectionMath.effectiveReadout(grid,grid,2.0),90,90)
        val data=CameraProjectionData(calibrationAccuracy=CalibrationAccuracy.CALIBRATED_INTRINSICS,
            viewWidth=1080,viewHeight=1920,activeArraySize=Rect(0,0,4000,3000),
            sensorToViewTransform=sensorToPortrait,lensRayCalibration=cal,currentZoomRatio=2f)
        val p=CameraProjector.projectDirection(5.0,0.0,CameraProjector.createRotationMatrix(0f,0f,0f),data)
        near(p.screenX,540f+2880f*tan(Math.toRadians(5.0)).toFloat())
        near(p.screenY,960f)
    }
    @Test fun nativeAndLogicalLensSizesDoNotShiftTheBoresight() {
        val native=PixelRect(0.0,0.0,8000.0,6000.0);val logical=PixelRect(0.0,0.0,4000.0,3000.0)
        val c=LensRayCalibration(12000.0,12000.0,4000.0,3000.0,0.0,native,logical,native,90,90)
        val data=CameraProjectionData(calibrationAccuracy=CalibrationAccuracy.CALIBRATED_INTRINSICS,
            viewWidth=1080,viewHeight=1920,sensorToViewTransform=sensorToPortrait,lensRayCalibration=c)
        val p=CameraProjector.projectDirection(0.0,0.0,CameraProjector.createRotationMatrix(0f,0f,0f),data)
        near(p.screenX,540f);near(p.screenY,960f)
    }
}
