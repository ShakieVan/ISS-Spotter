package de.shakie.iss

import de.shakie.iss.graphics.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Fixed-north navigation: no history-dependent roll. Replaces free-trackball expectations. */
class OrbitRotationRegressionTest {
    private val iss = OrbitVector.from(OrbitVector.geographic(-50.0, 56.0, 10.66).floats())
    private fun pose(c: OrbitCameraController, p: OrbitVector = iss) = c.computeCameraPose(p.floats(), FloatArray(3))
    private fun forward(p: FloatArray) = (OrbitVector.from(p, 3) - OrbitVector.from(p)).unit()
    private fun up(p: FloatArray) = OrbitVector.from(p, 6)
    private fun right(p: FloatArray) = forward(p).cross(up(p)).unit()
    private fun near(a: Double, b: Double, tolerance: Double = 3e-5) { check(abs(a-b) < tolerance) { "$a != $b" } }
    private fun sameOrientation(a: FloatArray, b: FloatArray) {
        check((forward(a)-forward(b)).length()<4e-6)
        check((up(a)-up(b)).length()<4e-6)
    }
    private fun assertNorth(p: FloatArray, position: OrbitVector = iss) {
        val radial = position.unit()
        val north = (OrbitVector.Y - radial * radial.y).unit()
        val f = forward(p); val u = up(p)
        check(u.dot(north) > 0.008) { "North is no longer up" }
        near(right(p).dot(north), 0.0, 3e-5)
        near(f.dot(u), 0.0)
        near(u.length(), 1.0)
    }

    @Test fun resetHasLocalNorthUpEastRightAndEarthBelowOnBothHemispheres() {
        for (lat in listOf(-51.6,-40.0,-32.01,-32.0,-31.99,0.0,32.0,51.6))
            for (lon in listOf(-179.0,-56.0,0.0,140.0)) for (zoom in listOf(.35f,1f,6f,12f)) {
                val p = OrbitVector.from(OrbitVector.geographic(lat,lon,10.66).floats())
                val radial=p.unit(); val n=(OrbitVector.Y-radial*radial.y).unit(); val e=n.cross(radial).unit()
                val c=OrbitCameraController().apply { zoomFactor=zoom }
                val cam=pose(c,p)
                check(up(cam).dot(n)>0.52)
                near(right(cam).dot(e),1.0)
                check(OrbitScreenProjection(cam,1080.0,1920.0).project(OrbitVector.ZERO)!![1]>960.0)
            }
    }

    @Test fun globalYIsNotSubstitutedForLocalNorthAgain() {
        val c=OrbitCameraController(); val p=pose(c); val f=forward(p)
        val n=(OrbitVector.Y-iss.unit()*iss.unit().y).unit()
        val oldUp=(OrbitVector.Y-f*f.y).unit()
        check(oldUp.dot(n)<-0.5 && oldUp.y>0.0)
        check(up(p).dot(n)>0.5)
    }

    @Test fun crossingSouthernLatitudeDoesNotFlip() {
        val c=OrbitCameraController()
        var previous: OrbitVector?=null
        for (i in -100..100) {
            val position=OrbitVector.geographic(-32.0+i*0.001,56.0,10.66)
            val p=pose(c,position)
            check(p.all { it.isFinite() })
            previous?.let { check(up(p).dot(it)>0.999999) }
            previous=up(p)
            assertNorth(p,position)
        }
    }

    @Test fun swipeUpReachesNadirAndContinuesWithoutFlipping() {
        val c=OrbitCameraController(); val radial=iss.unit()
        c.orbitByPixels(0f,(-58.0/OrbitCameraController.DRAG_DEGREES_PER_PIXEL).toFloat())
        check(forward(pose(c)).dot(radial * -1.0)>0.999999)
        val before=pose(c)
        c.orbitByPixels(0f,-10f)
        val after=pose(c)
        check(up(before).dot(up(after))>0.999)
        check((forward(before)-forward(after)).length()>0.02)
        near(right(before).dot(right(after)),1.0)
        assertNorth(after)
    }

    @Test fun northRemainsUpThroughoutLongMixedGestures() {
        var samples=0
        for (lat in listOf(-51.6,-32.0,0.0,32.0,51.6)) for (zoom in listOf(.35f,1f,6f,12f)) {
            val position=OrbitVector.from(OrbitVector.geographic(lat,-69.0,10.66).floats())
            val c=OrbitCameraController().apply { zoomFactor=zoom }
            repeat(3600) { i ->
                c.orbitByPixels((7.0*cos(i*.017)).toFloat(), (5.0*sin(i*.031)).toFloat())
                if(i%90==89) c.endOrbitGesture()
                assertNorth(pose(c,position),position)
                samples++
            }
        }
        println("Checked $samples mixed-gesture poses for north lock")
    }

    @Test fun repeatedHorizontalTurnsReturnWithoutRoll() {
        for (pitch in listOf(-140f,-58f,0f,25f)) {
            val c=OrbitCameraController().apply { pitchOffsetDeg=pitch }
            val initial=pose(c)
            repeat(1440) {
                c.orbitByPixels(-3.125f,0f)
                assertNorth(pose(c))
            }
            sameOrientation(initial,pose(c))
        }
    }

    @Test fun closedCirclesAndRectanglesReturnToTheOriginalPose() {
        var paths=0
        for (direction in listOf(-1,1)) for (radius in listOf(20f,100f,250f,800f))
            for (steps in listOf(36,120,360)) {
                val c=OrbitCameraController().apply { yawOffsetDeg=177f; pitchOffsetDeg=-20f; zoomFactor=12f }
                val initial=pose(c)
                repeat(20) {
                    var x=radius; var y=0f
                    for (i in 1..steps) {
                        val angle=direction*2.0*PI*i/steps
                        val nx=if(i==steps) radius else (radius*cos(angle)).toFloat()
                        val ny=if(i==steps) 0f else (radius*sin(angle)).toFloat()
                        c.orbitByPixels(nx-x,ny-y)
                        assertNorth(pose(c))
                        x=nx;y=ny
                    }
                    c.endOrbitGesture()
                    sameOrientation(initial,pose(c))
                    paths++
                }
            }
        val c=OrbitCameraController();val initial=pose(c)
        repeat(100) {
            c.orbitByPixels(400f,0f);c.orbitByPixels(0f,400f)
            c.orbitByPixels(-400f,0f);c.orbitByPixels(0f,-400f)
            c.endOrbitGesture();sameOrientation(initial,pose(c))
        }
        println("Checked $paths closed circles plus 100 rectangles")
    }

    @Test fun diagonalPathsAreReversibleAndEventOrderIndependent() {
        val c=OrbitCameraController();val initial=pose(c)
        c.orbitByPixels(143f,-81f);c.orbitByPixels(-143f,81f);c.endOrbitGesture()
        sameOrientation(initial,pose(c))
        val one=OrbitCameraController();one.orbitByPixels(100f,200f)
        val many=OrbitCameraController();repeat(100){many.orbitByPixels(1f,2f)}
        sameOrientation(pose(one),pose(many))
        val a=OrbitCameraController();a.orbitByPixels(0f,350f);a.orbitByPixels(230f,-300f)
        val b=OrbitCameraController();b.orbitByPixels(230f,-300f);b.orbitByPixels(0f,350f)
        sameOrientation(pose(a),pose(b))
    }

    @Test fun poleLimitsStayFiniteAndNextGestureRespondsImmediately() {
        for (sign in listOf(-1f,1f)) {
            val c=OrbitCameraController()
            c.orbitByPixels(0f,sign*10000f)
            val before=pose(c);assertNorth(before)
            c.endOrbitGesture();sameOrientation(before,pose(c))
            val oldPitch=c.pitchOffsetDeg
            c.orbitByPixels(0f,-sign)
            check(sign*(c.pitchOffsetDeg-oldPitch)<-0.15f)
            assertNorth(pose(c))
        }
    }

    @Test fun tiltLimitsDoNotPreventTheRearViewOrEarthCrossing() {
        val c=OrbitCameraController().apply { yawOffsetDeg=180f;pitchOffsetDeg=-58f }
        val f=forward(pose(c))
        check(f.dot(iss.unit())>0.999999)
        var insideCount=0;var afterExit=false
        for(i in 0..1165) {
            c.zoomFactor=.35f+i*.01f
            val p=pose(c);val eye=OrbitVector.from(p)
            check((eye-(iss-f*c.cameraDistance())).length()<3e-6+c.cameraDistance()*2e-5)
            if(OrbitScale.cameraInsideEarth(eye)) {
                insideCount++
                check(!OrbitSky.rayBlockedByEarth(eye,OrbitVector.X))
            } else if(insideCount>0) afterExit=true
        }
        check(insideCount>20 && afterExit)
    }

    @Test fun zoomPreservesOrientationAndFixedIssPivot() {
        val c=OrbitCameraController();c.orbitByPixels(1100f,-830f);c.endOrbitGesture()
        val before=pose(c)
        for (z in listOf(.35f,1f,4f,8f,12f,8f,1f,.35f)) {
            c.zoomFactor=z
            val p=pose(c);val eye=OrbitVector.from(p);val f=forward(p)
            sameOrientation(before,p)
            val line=iss-eye
            check(line.dot(f)>0)
            check((line-f*line.dot(f)).length()<2e-6+line.length()*2e-5)
            near(line.length(),c.cameraDistance(),3e-6+c.cameraDistance()*2e-5)
            assertNorth(p)
        }
    }

    @Test fun resetAndInvalidInputsAreSafe() {
        val c=OrbitCameraController();val initial=pose(c)
        c.orbitByPixels(333f,-999f);check(c.isModified())
        c.reset();check(!c.isModified())
        for(v in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            c.orbitByPixels(v,1f);c.orbitByPixels(1f,v);c.yawOffsetDeg=v;c.pitchOffsetDeg=v
        }
        sameOrientation(initial,pose(c))
        near(OrbitScale.ISS_SPAN_METERS,109.0)
    }

    @Test fun rendererCommitsGestureBoundariesAndRetainsZoomAndInsideVisibility() {
        val f=listOf("src/main/java/de/shakie/iss/graphics/IssFilamentView.kt","app/src/main/java/de/shakie/iss/graphics/IssFilamentView.kt","src/IssFilamentView.kt").map(::File).first{it.isFile}.readText()
        check(f.contains("cameraController.orbitByPixels("))
        check(f.contains("cameraController.zoomByScale(factor)"))
        for (event in listOf("ACTION_DOWN", "ACTION_POINTER_DOWN", "ACTION_POINTER_UP", "ACTION_CANCEL")) {
            val body=f.substringAfter("MotionEvent.$event -> {").substringBefore("return true")
            check(body.contains("cameraController.endOrbitGesture()")) { "Missing boundary $event" }
        }
        check(f.contains("val showEarth = !cameraInsideGlobe"))
        check(f.contains("val showAtmosphere = showEarth && !isReferenceMode"))
        check(f.contains("event.findPointerIndex(activePointerId)"))
    }
}
