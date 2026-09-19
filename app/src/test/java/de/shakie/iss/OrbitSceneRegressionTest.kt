package de.shakie.iss

import de.shakie.iss.graphics.*
import de.shakie.iss.orbit.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import kotlin.math.*

class OrbitSceneRegressionTest {
    private fun near(a: Double, b: Double, eps: Double = 1e-5) { check(abs(a - b) <= eps) { "$a != $b" } }
    private val instant = 1789813800000L

    @Test fun issScaleIsPhysicalAndModelCentered() {
        near(OrbitScale.MODEL_SPAN * OrbitScale.ISS_MODEL_SCALE / OrbitScale.metersToWorld(1.0), 109.0, 1e-8)
        val centered = (OrbitScale.MODEL_MIN + OrbitScale.MODEL_MAX) * 0.5 - OrbitScale.MODEL_CENTER
        near(centered.length(), 0.0)
        val ratio = OrbitScale.ISS_WORLD_SPAN / (420.0 * 10.0 / 6371.0)
        near(ratio, 0.109 / 420.0, 1e-12)
    }

    @Test fun measuredBoundsBelongToBundledModel() {
        val paths = listOf("src/main/assets/models/iss_nasa.glb", "app/src/main/assets/models/iss_nasa.glb")
        val file = paths.map(::File).firstOrNull { it.isFile } ?: error("Bundled GLB not found")
        val bytes = file.readBytes()
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray(Charsets.UTF_8))
        val hash = digest.digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(hash == "884cdf03d96b28285c1cdfdd34fcfd4f5de0021d") { "GLB changed: remeasure the visible meshes before using scale constants" }
    }

    @Test fun defaultCameraFollowsTheSmallStation() {
        val c = OrbitCameraController().apply { viewportAspect = 0.54f }
        val distanceMeters = c.defaultDistance() / OrbitScale.metersToWorld(1.0)
        check(distanceMeters in 700.0..1500.0)
        val p = c.computeCameraPose(floatArrayOf(10.66f, 0f, 0f), floatArrayOf(0f,0f,0f))
        val d = (OrbitVector.from(p) - OrbitVector(10.66f.toDouble(),0.0,0.0)).length()
        check(d in c.defaultDistance()*0.98..c.defaultDistance()*1.04)
        check(OrbitScale.nearPlane(d) < d - OrbitScale.ISS_WORLD_SPAN)
    }

    @Test fun maximumZoomFitsHalfTheWidthForAllAspectRatios() {
        // Updated requirement: the station, not Earth's centre, remains the pivot at maximum zoom.
        for (aspect in listOf(0.3f, 0.54f, 0.7f, 1f, 1.8f, 3f)) {
            val c = OrbitCameraController().apply { viewportAspect = aspect; zoomFactor = OrbitCameraController.MAX_ZOOM }
            for (lat in listOf(-51.6,0.0,51.6)) for (yaw in listOf(0f,75f,180f,300f)) {
                c.yawOffsetDeg = yaw
                val iss = OrbitVector.from(OrbitVector.geographic(lat, 143.0,10.66).floats())
                val pose = c.computeCameraPose(iss.floats(), FloatArray(3))
                val projection = OrbitScreenProjection(pose,1080.0,1080.0/aspect)
                val station = projection.project(iss)!!
                near(station[0],540.0,0.03)
                near(station[1],540.0/aspect,0.03)
                val f = (OrbitVector.from(pose,3) - projection.eye).unit()
                val r = f.cross(OrbitVector.from(pose,6)).unit()
                val earth = projection.eye * -1.0
                val x = earth.dot(r)
                val z = earth.dot(f)
                check(z > OrbitScale.EARTH_RADIUS)
                val tanH = tan(Math.toRadians(21.0)) * aspect
                val root = 10.0 * sqrt(x*x + z*z - 100.0)
                val left = (x*z-root)/(z*z-100.0)
                val right = (x*z+root)/(z*z-100.0)
                check(left >= -tanH && right <= tanH)
                check((right-left)/(2.0*tanH) <= 0.48001)
            }
        }
    }

    @Test fun freeOrbitKeepsAnOrthonormalBasisAndTheStationPivot() {
        val c = OrbitCameraController()
        for (lat in listOf(-90.0,-51.6,0.0,51.6,90.0)) for (z in listOf(0.35f,1f,3f,5f,7f,9f,12f))
            for (yaw in 0..330 step 30) for (pitch in listOf(-80f,0f,80f)) {
                c.zoomFactor=z;c.yawOffsetDeg=yaw.toFloat();c.pitchOffsetDeg=pitch
                val pose = c.computeCameraPose(OrbitVector.geographic(lat,56.0,10.66).floats(),FloatArray(3))
                check(pose.all { it.isFinite() })
                val f=(OrbitVector.from(pose,3)-OrbitVector.from(pose)).unit()
                val u=OrbitVector.from(pose,6)
                near(f.dot(u),0.0,2e-5)
                near(u.length(),1.0,2e-5)
                // Surface collision and forced global-Y-up were explicitly removed.
                val iss=OrbitVector.from(OrbitVector.geographic(lat,56.0,10.66).floats())
                val delta=iss-OrbitVector.from(pose)
                check((delta-f*delta.dot(f)).length() < 3e-6+delta.length()*2e-5)
                near(delta.length(),c.cameraDistance(),3e-5)
            }
    }

    @Test fun zoomDistanceIsMonotonicAndResetRestoresCloseup() {
        val c=OrbitCameraController();var previous=0.0
        for (i in 35..1200) { c.zoomFactor=i/100f; val d=c.cameraDistance();check(d>=previous);previous=d }
        c.reset();check(!c.isModified());near(c.cameraDistance(),c.defaultDistance())
        c.zoomFactor=Float.NaN;near(c.zoomFactor.toDouble(),1.0)
        c.zoomFactor=Float.POSITIVE_INFINITY;near(c.zoomFactor.toDouble(),1.0)
    }

    @Test fun celestialEastIsLeftWithNorthUpNotMirrored() {
        val f=OrbitSky.direction(12.0,55.0,instant)
        val u=(OrbitVector.Y-f*f.y).unit()
        val right=f.cross(u).unit()
        val increasingRa=OrbitSky.direction(12.1,55.0,instant)
        check(right.dot(increasingRa)<0.0)
        // Handle end Alkaid must lie east (left in a celestial north-up view) of Dubhe.
        check(right.dot(OrbitSky.direction(13.792,49.31,instant)) < right.dot(OrbitSky.direction(11.062,61.75,instant)))
    }

    @Test fun starDirectionsAgreeWithIndependentHourAngleProjection() {
        val latitude=Math.toRadians(49.5);val longitude=12.0
        val enuEast=OrbitVector.geographic(0.0,longitude+90.0)
        val up=OrbitVector.geographic(latitude*180.0/Math.PI,longitude)
        val north=(OrbitVector.Y-up*up.y).unit()
        for (ra in listOf(0.2,6.0,12.5,23.8)) for(dec in listOf(-60.0,0.0,55.0,89.26)) {
            val v=OrbitSky.direction(ra,dec,instant)
            val ha=OrbitSky.gmstRadians(instant)+Math.toRadians(longitude-ra*15.0)
            val de=Math.toRadians(dec)
            near(v.dot(enuEast),-cos(de)*sin(ha))
            near(v.dot(north),sin(de)*cos(latitude)-cos(de)*sin(latitude)*cos(ha))
            near(v.dot(up),sin(latitude)*sin(de)+cos(latitude)*cos(de)*cos(ha))
        }
    }

    @Test fun skyMatrixMatchesLabelsAndHasNoTranslationParallax() {
        val catalog=OrbitVector(0.2,0.6,0.7).unit()*68.0
        val dir=OrbitSky.catalogToWorld(catalog,instant).unit()
        for(eye in listOf(OrbitVector(10.7,0.0,0.0),OrbitVector(150.0,-100.0,25.0))) {
            val m=OrbitSky.matrix(instant,eye,68.0,4000.0)
            val p=OrbitVector(m[0]*catalog.x+m[4]*catalog.y+m[8]*catalog.z+m[12],
                m[1]*catalog.x+m[5]*catalog.y+m[9]*catalog.z+m[13],m[2]*catalog.x+m[6]*catalog.y+m[10]*catalog.z+m[14])
            near(((p-eye).unit()-dir).length(),0.0,1e-6)
        }
    }

    @Test fun siderealRotationChangesDirectionButPreservesAngles() {
        val a=OrbitSky.direction(11.062,61.75,instant);val b=OrbitSky.direction(13.792,49.31,instant)
        val a6=OrbitSky.direction(11.062,61.75,instant+6*3600000L);val b6=OrbitSky.direction(13.792,49.31,instant+6*3600000L)
        check((a-a6).length()>0.1);near(a.dot(b),a6.dot(b6),1e-10)
    }

    @Test fun brazilStillLiesToTheLeftAndDraggingRightMovesItRight() {
        val iss=OrbitVector.geographic(-26.95,-42.57,10.66)
        val c=OrbitCameraController();val pose=c.computeCameraPose(iss.floats(),FloatArray(3))
        val f=(OrbitVector.from(pose,3)-OrbitVector.from(pose)).unit()
        val right=f.cross(OrbitVector.from(pose,6)).unit()
        check((OrbitVector.geographic(-14.0,-51.0,10.0)-OrbitVector.from(pose)).dot(right)<0.0)
        // A distant scene point, not the orbit pivot (the station stays framed).
        val target=iss+f*1.0+right*0.1
        val p0=OrbitScreenProjection(pose,1080.0,1920.0).project(target)!!
        c.yawOffsetDeg=10f
        val p1=OrbitScreenProjection(c.computeCameraPose(iss.floats(),FloatArray(3)),1080.0,1920.0).project(target)!!
        check(p1[0]>p0[0])
    }

    @Test fun trajectorySamplesTheSameModelAndObserver() {
        val data=TrajectorySampler.sample(instant,0.0,0.0,0.0) { t -> OrbitState(0.0,0.0,420.0,27500.0,t) }
        check(data.points.size>1000)
        check(data.points.zipWithNext().all { (a,b) -> b.timeMillis-a.timeMillis==TrajectorySampler.STEP_MILLIS })
        check(data.points.all { abs(it.elevationDeg-90.0)<1e-6 })
        check(data.isUsable(instant,0.0,0.0));check(!data.isUsable(instant+20000,0.0,0.0));check(!data.isUsable(instant,1.0,0.0))
    }

    @Test fun pastAndFutureMeetAtExactLivePosition() {
        val data=TrajectorySampler.sample(instant,49.5,12.0,0.4) { t -> OrbitState(0.0,(t%9000000)/25000.0-180.0,420.0,27500.0,t) }
        val now=TrajectoryPoint(instant+1234,22.0,56.0,424.0,111.0,-18.0)
        val (past,future)=data.halves(now,TrajectorySampler.SKY_WINDOW_MILLIS)
        check(past.first()===now && future.first()===now)
        check(past.zipWithNext().all { (a,b)-> a.timeMillis>b.timeMillis })
        check(future.zipWithNext().all { (a,b)-> a.timeMillis<b.timeMillis })
        check(past.last().timeMillis>=now.timeMillis-TrajectorySampler.SKY_WINDOW_MILLIS)
        check(future.last().timeMillis<=now.timeMillis+TrajectorySampler.SKY_WINDOW_MILLIS)
    }

    @Test fun bothTrailEndsFadeToZero() {
        val window=TrajectorySampler.GROUND_WINDOW_MILLIS
        near(TrajectorySampler.fade(0.0,window).toDouble(),1.0)
        near(TrajectorySampler.fade(window/2.0,window).toDouble(),0.5)
        near(TrajectorySampler.fade(window.toDouble(),window).toDouble(),0.0)
        for(i in 0..100) near(TrajectorySampler.fade(i*window/100.0,window).toDouble(),TrajectorySampler.fade(-i*window/100.0,window).toDouble())
    }

    @Test fun viewportClippingAndDatelineDoNotDrawFalseConnections() {
        val c=TrailClip.segment(-100.0,50.0,200.0,50.0,100.0,100.0)!!
        near(c[0],0.0);near(c[2],100.0)
        check(TrailClip.segment(-100.0,-100.0,-5.0,-5.0,100.0,100.0)==null)
        check(TrailClip.segment(Double.NaN,0.0,10.0,10.0,100.0,100.0)==null)
        check((OrbitVector.geographic(0.0,179.9)-OrbitVector.geographic(0.0,-179.9)).length()<0.004)
        val projection=OrbitScreenProjection(floatArrayOf(20f,0f,0f,0f,0f,0f,0f,1f,0f),100.0,100.0)
        check(projection.groundVisible(OrbitVector.X));check(!projection.groundVisible(OrbitVector.X * -1.0))
    }
}
