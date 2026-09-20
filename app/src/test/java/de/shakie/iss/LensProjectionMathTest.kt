package de.shakie.iss

import de.shakie.iss.observer.*
import org.junit.Test
import kotlin.math.*

class LensProjectionMathTest {
    private val array=PixelRect(0.0,0.0,4000.0,3000.0)
    private fun calibration(focal:Double=3000.0,readout:PixelRect=array,orientation:Int=90)=
        LensRayCalibration(focal,focal,2000.0,1500.0,0.0,array,array,readout,orientation,90)
    private fun near(a:Double,b:Double){check(abs(a-b)<1e-6){"$a != $b"}}
    @Test fun boresightAndNativeAxesMatchTheRearCamera() {
        val c=calibration()
        val p=c.projectDeviceRay(0.0,0.0,-1.0)!!;near(p[0],2000.0);near(p[1],1500.0)
        val right=c.projectDeviceRay(.1,0.0,-1.0)!!;near(right[0],2000.0);near(right[1],1200.0)
        val up=c.projectDeviceRay(0.0,.1,-1.0)!!;near(up[0],1700.0);near(up[1],1500.0)
        check(c.projectDeviceRay(0.0,0.0,1.0)==null)
    }
    @Test fun cropAndZoomRatioHaveTheSameEffectiveFieldOfView() {
        val crop=PixelRect(1000.0,750.0,3000.0,2250.0)
        check(LensProjectionMath.effectiveReadout(array,array,2.0)==crop)
        check(LensProjectionMath.effectiveReadout(array,crop,1.0)==crop)
        val p1=calibration().projectDeviceRay(.1,0.0,-1.0)!!
        val p2=calibration(readout=crop).projectDeviceRay(.1,0.0,-1.0)!!
        near(p2[1]-1500.0,(p1[1]-1500.0)*2)
    }
    @Test fun postZoomAdditionalCropCombinesExactlyOnce() {
        val crop=PixelRect(1000.0,750.0,3000.0,2250.0)
        val effective=LensProjectionMath.effectiveReadout(array,crop,2.0)
        near(effective.width,1000.0);near(effective.height,750.0)
        near(calibration(readout=effective).projectDeviceRay(.1,0.0,-1.0)!![1],300.0)
    }
    @Test fun physicalTelephotoUsesOnlyTheResidualDigitalCrop() {
        val residual=LensProjectionMath.effectiveReadout(array,array,2.0)
        val tele=calibration(9000.0,residual).projectDeviceRay(.05,0.0,-1.0)!!
        val wide=calibration().projectDeviceRay(.05,0.0,-1.0)!!
        near(tele[1]-1500.0,6*(wide[1]-1500.0))
    }
    @Test fun aspectCropDoesNotCreateExtraZoom() {
        val cropped=calibration(readout=PixelRect(0.0,375.0,4000.0,2625.0))
        val p=cropped.projectDeviceRay(.1,0.0,-1.0)!!
        near(p[1],1200.0)
    }
    @Test fun brownConradyIsAppliedInRayCoordinatesOnlyWhenPresent() {
        val normal=calibration()
        val distorted=normal.copy(distortion=listOf(.2,0.0,0.0,0.0,0.0))
        near(distorted.projectDeviceRay(.5,0.0,-1.0)!![1],1500.0-1500.0*1.05)
        near(normal.projectDeviceRay(.5,0.0,-1.0)!![1],0.0)
    }
    @Test fun tangentTermsAndPrincipalPointArePreserved() {
        val c=calibration().copy(cx=2050.0,cy=1490.0,skew=20.0,distortion=listOf(0.0,0.0,0.0,.01,.02))
        val p=c.projectDeviceRay(.1,.2,-1.0)!!
        val x=-.2;val y=-.1;val r=.05
        val xd=x+2*.01*x*y+.02*(r+2*x*x)
        val yd=y+2*.02*x*y+.01*(r+2*y*y)
        near(p[0],2050+3000*xd+20*yd);near(p[1],1490+3000*yd)
    }
    @Test fun differentNativeOrientationsMapIntoTheSameLogicalFrame() {
        val expected=calibration().projectDeviceRay(.13,.07,-1.0)!!
        // Square arrays avoid physical aspect-ratio changes in this orientation-only test.
        val square=PixelRect(0.0,0.0,4000.0,4000.0)
        val base=calibration().copy(nativeArray=square,logicalArray=square,readout=square,cy=2000.0)
        val ref=base.projectDeviceRay(.13,.07,-1.0)!!
        for(rotation in listOf(0,90,180,270)) {
            val p=base.copy(sensorOrientation=rotation).projectDeviceRay(.13,.07,-1.0)!!
            near(p[0],ref[0]);near(p[1],ref[1])
        }
        check(expected.all{it.isFinite()})
    }
    @Test fun lensSelectionHasHysteresisAndSupportsWideAngle() {
        val lenses=listOf(AvailableLens("wide",.6,1.0,3.0),AvailableLens("main",1.0,1.0,10.0),AvailableLens("tele",3.0,1.0,10.0))
        check(LensSelection.select(.6,"main",lenses)?.id=="wide")
        check(LensSelection.select(3.01,"main",lenses)?.id=="main")
        check(LensSelection.select(3.3,"main",lenses)?.id=="tele")
        check(LensSelection.select(2.95,"tele",lenses)?.id=="tele")
        check(LensSelection.select(2.8,"tele",lenses)?.id=="main")
        check(LensSelection.select(1.0,null,emptyList())==null)
    }
    @Test fun invalidRaysAndZoomMetadataDoNotPropagateNan() {
        check(calibration().projectDeviceRay(Double.NaN,0.0,-1.0)==null)
        check(calibration().copy(readout=PixelRect(0.0,0.0,0.0,1.0)).projectDeviceRay(0.0,0.0,-1.0)==null)
        check(LensProjectionMath.effectiveReadout(array,null,Double.NaN)==array)
    }
}
