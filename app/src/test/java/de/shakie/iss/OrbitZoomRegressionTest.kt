package de.shakie.iss

import de.shakie.iss.graphics.*
import org.junit.Test
import java.io.File
import kotlin.math.*

/** Zoom remains fixed-pivot; a straight free orbit may now legitimately cross Earth. */
class OrbitZoomRegressionTest {
    private val aspects = listOf(0.2f, 0.3f, 0.54f, 1f, 1.8f, 4f)
    private val latitudes = listOf(-90.0, -51.6, 0.0, 51.6, 90.0)
    private val pitches = listOf(-150f, -53f, 0f, 32f, 100f)
    private fun near(a: Double, b: Double, eps: Double = 1e-5) {
        check(abs(a - b) <= eps) { "$a != $b (tolerance $eps)" }
    }
    private fun forward(pose: FloatArray) = (OrbitVector.from(pose, 3) - OrbitVector.from(pose)).unit()

    @Test fun actualZoomPathIsStraightAndIssDistanceNeverReverses() {
        var samples = 0
        for (aspect in aspects) for (lat in latitudes) for (yaw in 0..315 step 45) for (pitch in pitches) {
            val c = OrbitCameraController().apply { viewportAspect = aspect; yawOffsetDeg = yaw.toFloat(); pitchOffsetDeg = pitch }
            val iss = OrbitVector.from(OrbitVector.geographic(lat, 56.17, 10.666).floats())
            var previousDistance = 0.0
            var firstForward: OrbitVector? = null
            for (i in 0..233) {
                c.zoomFactor = 0.35f + i * 0.05f
                val p = c.computeCameraPose(iss.floats(), FloatArray(3))
                val eye = OrbitVector.from(p)
                val d = (eye - iss).length()
                check(d + 2e-6 >= previousDistance)
                near(d, c.cameraDistance(), max(2e-6, d * 1e-6))
                val f = forward(p)
                if (firstForward == null) firstForward = f
                check(f.dot(firstForward) > 0.99999999)
                // Unlike the superseded spaceward-only camera, Earth clearance need
                // not be monotonic. The EXACT straight line must be preserved instead.
                val expected = iss - firstForward * c.cameraDistance()
                check((eye - expected).length() <= 3e-6 + d * 2e-5)
                previousDistance = d
                samples++
            }
        }
        println("Checked $samples straight, fixed-pivot zoom poses")
    }

    @Test fun stationIsThePivotAtEveryZoomAndEarthIsNotRecentered() {
        for (aspect in aspects) for (lat in latitudes) for (yaw in 0..315 step 45)
            for (pitch in pitches) for (zoom in listOf(.35f, 1f, 4f, 6f, 8f, 12f)) {
                val c = OrbitCameraController().apply { viewportAspect = aspect; yawOffsetDeg = yaw.toFloat(); pitchOffsetDeg = pitch; zoomFactor = zoom }
                val iss = OrbitVector.from(OrbitVector.geographic(lat, 143.0, 10.66).floats())
                val p = c.computeCameraPose(iss.floats(), FloatArray(3))
                val f = forward(p); val u = OrbitVector.from(p, 6)
                val toIss = iss - OrbitVector.from(p)
                check((toIss - f * toIss.dot(f)).length() <= 2e-6 + toIss.length() * 2e-5)
                check(toIss.dot(f) > OrbitScale.ISS_BOUND_RADIUS)
                near(f.dot(u), 0.0, 2e-5)
                near(u.length(), 1.0, 2e-5)
            }
        val c = OrbitCameraController().apply { zoomFactor = OrbitCameraController.MAX_ZOOM }
        val p = c.computeCameraPose(floatArrayOf(10.66f, 0f, 0f), FloatArray(3))
        val projection = OrbitScreenProjection(p, 1080.0, 1920.0)
        val station = projection.project(OrbitVector(10.66f.toDouble(), 0.0, 0.0))!!
        near(station[0], 540.0, 0.03); near(station[1], 960.0, 0.03)
        val earth = projection.project(OrbitVector.ZERO)!!
        check(hypot(earth[0] - station[0], earth[1] - station[1]) > 10.0)
    }

    @Test fun entireGlobeFitsAtMaximumZoomWithoutChangingThePivot() {
        val radius = OrbitScale.EARTH_RADIUS
        val tanV = tan(Math.toRadians(OrbitScale.FOV_Y_DEG / 2.0))
        for (aspect in aspects) for (lat in latitudes) for (yaw in 0..315 step 45) for (pitch in pitches) {
            val c = OrbitCameraController().apply { viewportAspect = aspect; yawOffsetDeg = yaw.toFloat(); pitchOffsetDeg = pitch; zoomFactor = OrbitCameraController.MAX_ZOOM }
            val p = c.computeCameraPose(OrbitVector.geographic(lat, 12.0, 10.7).floats(), FloatArray(3))
            val f = forward(p)
            val right = f.cross(OrbitVector.from(p, 6)).unit()
            val up = right.cross(f).unit()
            val earth = OrbitVector.from(p) * -1.0
            val z = earth.dot(f)
            check(z > radius)
            for ((axis, tangent) in listOf(right to tanV * aspect, up to tanV)) {
                val x = earth.dot(axis)
                val root = radius * sqrt(x * x + z * z - radius * radius)
                val lo = (x * z - root) / (z * z - radius * radius)
                val hi = (x * z + root) / (z * z - radius * radius)
                check(lo >= -tangent && hi <= tangent)
                check((hi - lo) / (2.0 * tangent) <= 0.48001)
            }
        }
    }

    @Test fun pinchResponseDoesNotDependOnZoomAndReversesWithoutHysteresis() {
        for (aspect in aspects) for (zoom in listOf(.8f, 1f, 1.2f, 4f, 8f, 11f)) {
            val c = OrbitCameraController().apply { viewportAspect = aspect; zoomFactor = zoom }
            val d = c.cameraDistance()
            c.zoomByScale(1.05f)
            near(c.cameraDistance() / d, 1.0 / 1.05f.toDouble().pow(2), 2e-6)
            c.zoomByScale(1f / 1.05f)
            near(c.cameraDistance() / d, 1.0, 3e-6)
        }
        val c = OrbitCameraController()
        for (invalid in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) c.zoomByScale(invalid)
        near(c.zoomFactor.toDouble(), 1.0)
        c.zoomByScale(.0001f); near(c.zoomFactor.toDouble(), OrbitCameraController.MAX_ZOOM.toDouble())
        c.zoomByScale(100000f); near(c.zoomFactor.toDouble(), OrbitCameraController.MIN_ZOOM.toDouble())
        c.reset(); check(!c.isModified())
    }

    /** Mathematical reference for Filament's float frustum extraction, not a GPU test. */
    private fun cullingPlanes(p: FloatArray, aspect: Double, near: Double, far: Double): List<DoubleArray> {
        val e = OrbitVector.from(p); val f = forward(p)
        val r = f.cross(OrbitVector.from(p, 6)).unit(); val u = r.cross(f).unit()
        val v = arrayOf(
            doubleArrayOf(r.x, r.y, r.z, -r.dot(e)),
            doubleArrayOf(u.x, u.y, u.z, -u.dot(e)),
            doubleArrayOf(-f.x, -f.y, -f.z, f.dot(e)),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        )
        val tanV = tan(Math.toRadians(OrbitScale.FOV_Y_DEG * 0.5))
        val projection = arrayOf(
            doubleArrayOf(1.0 / (tanV * aspect), 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 1.0 / tanV, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, (far + near) / (near - far), 2 * far * near / (near - far)),
            doubleArrayOf(0.0, 0.0, -1.0, 0.0)
        )
        val pv = Array(4) { i -> FloatArray(4) { j -> (0..3).sumOf { k -> projection[i][k] * v[k][j] }.toFloat() } }
        return (0..2).flatMap { axis -> listOf(-1f, 1f).map { sign ->
            val plane = DoubleArray(4) { j -> (-pv[3][j] + sign * pv[axis][j]).toDouble() }
            val length = sqrt(plane[0] * plane[0] + plane[1] * plane[1] + plane[2] * plane[2])
            DoubleArray(4) { plane[it] / length }
        } }
    }

    @Test fun closeAndFarViewsHaveFiniteCullingPlanesAndKeepTheStation() {
        for (aspect in aspects) for (lat in latitudes) for (yaw in 0..315 step 45)
            for (pitch in pitches) for (zoom in listOf(.35f, 1f, 4f, 6f, 8f, 12f)) {
                val c = OrbitCameraController().apply { viewportAspect = aspect; yawOffsetDeg = yaw.toFloat(); pitchOffsetDeg = pitch; zoomFactor = zoom }
                val iss = OrbitVector.from(OrbitVector.geographic(lat, 56.17, 10.666).floats())
                val p = c.computeCameraPose(iss.floats(), FloatArray(3)); val eye = OrbitVector.from(p)
                val d = (iss - eye).length(); val near = OrbitScale.nearPlane(d); val far = OrbitScale.cullingFarPlane(eye)
                check(near > 0 && near < d - OrbitScale.ISS_BOUND_RADIUS)
                // Near the surface a deliberate crossing may clip Earth; inside it is hidden.
                check(far > eye.length() + OrbitScale.EARTH_RADIUS)
                val planes = cullingPlanes(p, aspect.toDouble(), near, far)
                check(planes.all { plane -> plane.all { it.isFinite() } })
                for (plane in planes) {
                    val signedDistance = plane[0] * iss.x + plane[1] * iss.y + plane[2] * iss.z + plane[3]
                    check(signedDistance <= OrbitScale.ISS_BOUND_RADIUS)
                }
            }
    }

    @Test fun oldInfiniteRangeReproducesTheNearViewFailure() {
        val c = OrbitCameraController().apply { viewportAspect = .54f; zoomFactor = .35f }
        val pose = floatArrayOf(10.661f, 0f, 0f, 9.661f, 0f, 0f, 0f, 1f, 0f)
        val oldNear = c.cameraDistance() * 0.08
        check(((5000.0 + oldNear) / (oldNear - 5000.0)).toFloat() == -1f)
        check(cullingPlanes(pose, .54, oldNear, 5000.0).any { plane -> plane.any { !it.isFinite() } })
    }

    @Test fun earthInTheDefaultCloseViewSurvivesFrustumCulling() {
        for (aspect in aspects) for (lat in latitudes) for (zoom in listOf(.35f, .5f, .8f, 1f, 1.1f, 2f, 3f, 4f, 5f, 6f, 8f, 12f)) {
            val c = OrbitCameraController().apply { viewportAspect = aspect; zoomFactor = zoom }
            val iss = OrbitVector.from(OrbitVector.geographic(lat, 56.17, 10.666).floats())
            val p = c.computeCameraPose(iss.floats(), FloatArray(3)); val eye = OrbitVector.from(p)
            val planes = cullingPlanes(p, aspect.toDouble(), OrbitScale.nearPlane((iss - eye).length()), OrbitScale.cullingFarPlane(eye))
            for (plane in planes) check(plane[3] - 11.0 * (abs(plane[0]) + abs(plane[1]) + abs(plane[2])) <= 0.0)
        }
    }

    @Test fun scaleAndResetRemainPhysical() {
        near(OrbitScale.MODEL_SPAN * OrbitScale.ISS_MODEL_SCALE / OrbitScale.metersToWorld(1.0), 109.0, 1e-8)
        val c = OrbitCameraController().apply { viewportAspect = .54f }
        check(c.defaultDistance() / OrbitScale.metersToWorld(1.0) in 700.0..1500.0)
        c.zoomFactor = 10f; c.yawOffsetDeg = 200f; c.pitchOffsetDeg = 25f
        c.reset(); check(!c.isModified()); near(c.cameraDistance(), c.defaultDistance())
        val p = c.computeCameraPose(floatArrayOf(10.66f,0f,0f),FloatArray(3))
        near(-forward(p).x, sin(Math.toRadians(32.0)), 1e-5)
        c.pitchOffsetDeg = Float.NaN; c.yawOffsetDeg = Float.POSITIVE_INFINITY
        check(c.computeCameraPose(floatArrayOf(10.66f,0f,0f),FloatArray(3)).all { it.isFinite() })
    }

    @Test fun integrationUsesTheTestedRangeAndPinchFunctions() {
        val paths = listOf("src/main/java/de/shakie/iss/graphics/IssFilamentView.kt", "app/src/main/java/de/shakie/iss/graphics/IssFilamentView.kt", "IssFilamentView.kt")
        val text = paths.map(::File).firstOrNull { it.isFile }?.readText() ?: error("IssFilamentView.kt not found")
        check(text.contains("val far = OrbitScale.cullingFarPlane(eye)"))
        check(text.contains("near, far, Camera.Fov.VERTICAL"))
        check(text.contains("cameraController.zoomByScale(factor)"))
        check(text.contains("cameraController.orbitByPixels("))
        check(!text.contains("OrbitCameraController.MIN_PITCH"))
        check(!text.contains("near, OrbitScale.FAR_PLANE, Camera.Fov.VERTICAL"))
        check(text.contains("cameraInsideGlobe = OrbitScale.cameraInsideEarth(eye)"))
        for (mesh in listOf("milkyMesh", "starMesh", "lineMesh", "sunMesh")) {
            val construction = text.substringBefore(".build(engine, $mesh.entity)").substringAfterLast("RenderableManager.Builder(1)")
            check(construction.contains(".culling(false)"))
        }
    }
}
