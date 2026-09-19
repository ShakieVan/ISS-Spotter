package de.shakie.iss

import de.shakie.iss.graphics.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Exercises real gesture input, the geographic north tangent and full rotations. */
class OrbitRotationRegressionTest {
    private val iss = OrbitVector.from(OrbitVector.geographic(-50.0, 56.0, 10.66).floats())
    private fun pose(c: OrbitCameraController, p: OrbitVector = iss) = c.computeCameraPose(p.floats(), FloatArray(3))
    private fun forward(p: FloatArray) = (OrbitVector.from(p, 3) - OrbitVector.from(p)).unit()
    private fun up(p: FloatArray) = OrbitVector.from(p, 6)
    private fun right(p: FloatArray) = forward(p).cross(up(p)).unit()
    private fun near(a: Double, b: Double, tolerance: Double = 3e-5) { check(abs(a-b) < tolerance) { "$a != $b" } }

    @Test fun resetHasLocalNorthUpEastRightAndEarthBelowOnBothHemispheres() {
        for (lat in listOf(-51.6,-40.0,-32.01,-32.0,-31.99,0.0,32.0,51.6))
            for (lon in listOf(-179.0,-56.0,0.0,140.0)) for (zoom in listOf(.35f,1f,6f,12f)) {
                val p = OrbitVector.from(OrbitVector.geographic(lat,lon,10.66).floats())
                val radial=p.unit(); val n=(OrbitVector.Y-radial*radial.y).unit(); val e=n.cross(radial).unit()
                val c=OrbitCameraController().apply { zoomFactor=zoom }
                val cam=pose(c,p); val u=up(cam); val r=right(cam)
                check(u.dot(n)>0.52) { "Local north inverted at latitude $lat" }
                near(r.dot(e),1.0)
                val proj=OrbitScreenProjection(cam,1080.0,1920.0)
                check(proj.project(OrbitVector.ZERO)!![1]>960.0) { "Earth is above the station after reset" }
            }
    }

    @Test fun negativeControlReproducesTheOldSouthernHemisphereInversion() {
        val c=OrbitCameraController(); val p=pose(c); val f=forward(p)
        val n=(OrbitVector.Y-iss.unit()*iss.unit().y).unit()
        val oldUp=(OrbitVector.Y-f*f.y).unit()
        check(oldUp.dot(n)<-0.5) // The previous north-up check would accept this wrong roll.
        check(oldUp.y>0.0)
        check(up(p).dot(n)>0.5)
    }

    @Test fun snapshotCrossingTheOldPoleSingularityDoesNotFlipTheCamera() {
        val c=OrbitCameraController()
        var previous: OrbitVector?=null
        for (i in -100..100) {
            val p=pose(c,OrbitVector.geographic(-32.0+i*0.001,56.0,10.66))
            check(p.all { it.isFinite() })
            previous?.let { check(up(p).dot(it)>0.999999) }
            previous=up(p)
        }
    }

    @Test fun swipeUpReachesNadirAndContinuesWithoutAPoleClamp() {
        val c=OrbitCameraController(); val radial=iss.unit()
        c.orbitByPixels(0f,(-58.0/OrbitCameraController.DRAG_DEGREES_PER_PIXEL).toFloat())
        check(forward(pose(c)).dot(radial * -1.0)>0.999999)
        val before=pose(c)
        c.orbitByPixels(0f,-10f)
        val after=pose(c)
        check(up(before).dot(up(after))>0.999)
        check((forward(before)-forward(after)).length()>0.02)
        near(right(before).dot(right(after)),1.0)
    }

    @Test fun swipesUseCurrentScreenAxesAfterArbitraryPreviousRotations() {
        for (yaw in 0..300 step 60) for (pitch in listOf(-150f,-90f,-58f,0f,90f,150f)) {
            val c=OrbitCameraController().apply { yawOffsetDeg=yaw.toFloat(); pitchOffsetDeg=pitch }
            val before=pose(c)
            c.orbitByPixels(-5f,0f)
            val after=pose(c)
            near(up(before).dot(up(after)),1.0)
            check(forward(after).dot(right(before))>0.01) // Left swipe looks right.
            val beforeVertical=after
            c.orbitByPixels(0f,-5f)
            val afterVertical=pose(c)
            near(right(beforeVertical).dot(right(afterVertical)),1.0)
            check(forward(afterVertical).dot(up(beforeVertical)) < -0.01)
        }
    }

    @Test fun repeatedHorizontalAndVerticalCirclesDoNotWobbleOrFlip() {
        for (horizontal in listOf(true,false)) {
            val c=OrbitCameraController()
            c.orbitByPixels(130f,-210f)
            val initial=pose(c)
            val axis=if(horizontal) up(initial) else right(initial)
            var previous=initial
            repeat(1440) {
                c.orbitByPixels(if(horizontal) -3.125f else 0f,if(horizontal) 0f else -3.125f)
                val current=pose(c)
                check(up(current).dot(up(previous))>0.9999)
                check(forward(current).dot(forward(previous))>0.9999)
                near((if(horizontal) up(current) else right(current)).dot(axis),1.0)
                previous=current
            }
            near(forward(initial).dot(forward(previous)),1.0)
            near(up(initial).dot(up(previous)),1.0)
        }
    }

    @Test fun diagonalDragIsReversibleAndDoesNotDependOnEventSubdivision() {
        val c=OrbitCameraController(); val start=pose(c)
        c.orbitByPixels(143f,-81f); c.orbitByPixels(-143f,81f)
        near(forward(pose(c)).dot(forward(start)),1.0)
        near(up(pose(c)).dot(up(start)),1.0)
        val one=OrbitCameraController(); one.orbitByPixels(100f,200f)
        val many=OrbitCameraController(); repeat(100){many.orbitByPixels(1f,2f)}
        near(forward(pose(one)).dot(forward(pose(many))),1.0)
        near(up(pose(one)).dot(up(pose(many))),1.0)
    }

    @Test fun changingZoomDoesNotChangeFreeOrientationOrPivot() {
        val c=OrbitCameraController(); c.orbitByPixels(1100f,-830f)
        val before=pose(c)
        for (z in listOf(.35f,1f,4f,8f,12f,8f,1f,.35f)) {
            c.zoomFactor=z
            val p=pose(c); val eye=OrbitVector.from(p); val f=forward(p)
            near(f.dot(forward(before)),1.0)
            near(up(p).dot(up(before)),1.0)
            val line=iss-eye
            check(line.dot(f)>0)
            check((line-f*line.dot(f)).length()<2e-6+line.length()*2e-5)
        }
    }

    @Test fun cameraMayPassThroughEarthWithoutTeleportationAndSkyStaysAvailable() {
        val c=OrbitCameraController().apply { pitchOffsetDeg=122f }
        // At this setting the forward ray points radially out; retreating enters Earth.
        val reference=pose(c); val f=forward(reference)
        check(f.dot(iss.unit())>0.999999)
        var insideCount=0; var afterExit=false
        for(i in 0..1165) {
            c.zoomFactor=.35f+i*.01f
            val p=pose(c); val eye=OrbitVector.from(p)
            check((eye-(iss-f*c.cameraDistance())).length()<3e-6+c.cameraDistance()*2e-5)
            if(OrbitScale.cameraInsideEarth(eye)) {
                insideCount++
                for(dir in listOf(OrbitVector.X,OrbitVector.Y,OrbitVector.Z,OrbitVector.X * -1.0))
                    check(!OrbitSky.rayBlockedByEarth(eye,dir))
            } else if(insideCount>0) afterExit=true
        }
        check(insideCount>20 && afterExit)
        // Outside the globe normal sky occultation still applies.
        check(OrbitSky.rayBlockedByEarth(OrbitVector(20.0,0.0,0.0),OrbitVector.X * -1.0))
        check(!OrbitSky.rayBlockedByEarth(OrbitVector(20.0,0.0,0.0),OrbitVector.X))
    }

    @Test fun resetRestoresOrientationAndRejectsInvalidInputs() {
        val c=OrbitCameraController(); val initial=pose(c)
        c.orbitByPixels(333f,-999f);check(c.isModified())
        c.reset();check(!c.isModified())
        for(v in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            c.orbitByPixels(v,1f);c.orbitByPixels(1f,v);c.yawOffsetDeg=v;c.pitchOffsetDeg=v
        }
        near(forward(pose(c)).dot(forward(initial)),1.0)
        near(up(pose(c)).dot(up(initial)),1.0)
    }

    @Test fun rendererUsesFreeDragAndInsideVisibilityWithoutTouchingShaders() {
        val f=listOf("src/main/java/de/shakie/iss/graphics/IssFilamentView.kt","app/src/main/java/de/shakie/iss/graphics/IssFilamentView.kt","IssFilamentView.kt").map(::File).first{it.isFile}.readText()
        check(f.contains("cameraController.orbitByPixels("))
        check(!f.contains("OrbitCameraController.MIN_PITCH"))
        check(f.contains("val showEarth = !cameraInsideGlobe"))
        check(f.contains("val showAtmosphere = showEarth && !isReferenceMode"))
        check(f.contains("MotionEvent.ACTION_POINTER_UP"))
        check(f.contains("event.findPointerIndex(activePointerId)"))
    }
}
