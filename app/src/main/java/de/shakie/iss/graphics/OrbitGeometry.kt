package de.shakie.iss.graphics

import kotlin.math.*

/** Shared, testable geometry. Render axes: +Y north, +X Greenwich, -Z east. */
data class OrbitVector(val x: Double, val y: Double, val z: Double) {
    operator fun plus(b: OrbitVector) = OrbitVector(x + b.x, y + b.y, z + b.z)
    operator fun minus(b: OrbitVector) = OrbitVector(x - b.x, y - b.y, z - b.z)
    operator fun times(s: Double) = OrbitVector(x * s, y * s, z * s)
    fun dot(b: OrbitVector) = x * b.x + y * b.y + z * b.z
    fun cross(b: OrbitVector) = OrbitVector(y * b.z - z * b.y, z * b.x - x * b.z, x * b.y - y * b.x)
    fun length() = sqrt(dot(this))
    fun unit(fallback: OrbitVector = Y): OrbitVector {
        val len = length()
        return if (len.isFinite() && len > 1e-12) this * (1.0 / len) else fallback
    }
    fun floats() = floatArrayOf(x.toFloat(), y.toFloat(), z.toFloat())
    companion object {
        val ZERO = OrbitVector(0.0, 0.0, 0.0)
        val X = OrbitVector(1.0, 0.0, 0.0)
        val Y = OrbitVector(0.0, 1.0, 0.0)
        val Z = OrbitVector(0.0, 0.0, 1.0)
        fun from(v: FloatArray, offset: Int = 0) = OrbitVector(v[offset].toDouble(), v[offset + 1].toDouble(), v[offset + 2].toDouble())
        fun geographic(lat: Double, lon: Double, radius: Double = 1.0): OrbitVector {
            val p = Math.toRadians(lat)
            val l = Math.toRadians(lon)
            return OrbitVector(cos(p) * cos(l), sin(p), -cos(p) * sin(l)) * radius
        }
    }
}

object OrbitScale {
    const val EARTH_RADIUS = 10.0
    const val EARTH_RADIUS_KM = 6371.0
    const val ISS_SPAN_METERS = 109.0
    const val FOV_Y_DEG = 42.0
    const val SKY_RADIUS = 4000.0
    const val FAR_PLANE = 5000.0
    // Measured POSITION bounds of the displayed polySurfa1/2/3 meshes in iss_nasa.glb.
    // Git blob 884cdf03d96b28285c1cdfdd34fcfd4f5de0021d; bended*/pCyl* are not displayed.
    val MODEL_MIN = OrbitVector(-2.830271005630493, -4.168513774871826, -22.86463165283203)
    val MODEL_MAX = OrbitVector(3.287945032119751, 25.77063751220703, 22.679731369018555)
    val MODEL_CENTER = (MODEL_MIN + MODEL_MAX) * 0.5
    val MODEL_SPAN = MODEL_MAX.z - MODEL_MIN.z
    fun metersToWorld(meters: Double) = meters * EARTH_RADIUS / (EARTH_RADIUS_KM * 1000.0)
    val ISS_WORLD_SPAN = metersToWorld(ISS_SPAN_METERS)
    val ISS_MODEL_SCALE = ISS_WORLD_SPAN / MODEL_SPAN
    val ISS_BOUND_RADIUS = (MODEL_MAX - MODEL_MIN).length() * ISS_MODEL_SCALE * 0.5
    fun nearPlane(cameraDistanceToIss: Double): Double {
        // Preserve the complete physical model, but do not sacrifice depth precision needlessly.
        val frontClearance = (cameraDistanceToIss - ISS_BOUND_RADIUS).coerceAtLeast(metersToWorld(1.0))
        return (frontClearance * 0.35).coerceIn(metersToWorld(0.1), 0.1)
    }
    fun cullingFarPlane(eye: OrbitVector): Double {
        // Filament 1.75.1 renders with infinite far, but extracts its culling planes in float.
        // A 5000-unit culling range with a sub-millimetre-world near plane rounds p22 to -1,
        // degenerating the far-plane normal. Bound culling to Earth/ISS, not the sky dome.
        // All sky renderables already have culling(false), and retain their infinite render range.
        return max(32.0, eye.length() + 2.0 * EARTH_RADIUS)
    }
    fun earthOverviewDistance(aspect: Double): Double {
        // Exact angular silhouette of a sphere, diameter <= 48% of viewport width.
        val tanHalfH = tan(Math.toRadians(FOV_Y_DEG * 0.5)) * aspect.coerceIn(0.2, 4.0)
        return EARTH_RADIUS * sqrt(1.0 + 1.0 / (0.48 * tanHalfH).pow(2))
    }
}

/** The old sky meshes use +Z for increasing RA. This maps them to the render axes. */
object OrbitSky {
    fun gmstRadians(timeMillis: Long): Double {
        val jd = 2440587.5 + timeMillis / 86400000.0
        val d = jd - 2451545.0
        val t = d / 36525.0
        val deg = 280.46061837 + 360.98564736629 * d + 0.000387933 * t * t - t * t * t / 38710000.0
        return Math.toRadians(((deg % 360.0) + 360.0) % 360.0)
    }
    fun catalogToWorld(v: OrbitVector, timeMillis: Long): OrbitVector {
        val g = gmstRadians(timeMillis)
        // RA in the earth-fixed frame is RA - GMST; east is -Z, NOT +Z.
        return OrbitVector(cos(g) * v.x + sin(g) * v.z, v.y, sin(g) * v.x - cos(g) * v.z)
    }
    fun direction(raHours: Double, decDeg: Double, timeMillis: Long): OrbitVector {
        val a = Math.toRadians(raHours * 15.0)
        val d = Math.toRadians(decDeg)
        return catalogToWorld(OrbitVector(cos(d) * cos(a), sin(d), cos(d) * sin(a)), timeMillis)
    }
    fun matrix(timeMillis: Long, eye: OrbitVector, originalRadius: Double, targetRadius: Double): FloatArray {
        val g = gmstRadians(timeMillis)
        val s = targetRadius / originalRadius
        val c = (cos(g) * s).toFloat()
        val q = (sin(g) * s).toFloat()
        // Column-major; intentional parity correction of the old +Z-RA mesh convention.
        return floatArrayOf(c, 0f, q, 0f, 0f, s.toFloat(), 0f, 0f, q, 0f, -c, 0f,
            eye.x.toFloat(), eye.y.toFloat(), eye.z.toFloat(), 1f)
    }
    fun rayBlockedByEarth(eye: OrbitVector, direction: OrbitVector): Boolean {
        val t = -eye.dot(direction)
        return t > 0.0 && (eye + direction * t).length() < OrbitScale.EARTH_RADIUS
    }
}

/** Double-precision view projection shared by the ground track and its visibility tests. */
class OrbitScreenProjection(pose: FloatArray, val width: Double, val height: Double) {
    val eye = OrbitVector.from(pose)
    private val f = (OrbitVector.from(pose, 3) - eye).unit()
    private val r = f.cross(OrbitVector.from(pose, 6)).unit(OrbitVector.X)
    private val u = r.cross(f).unit()
    private val focal = height * 0.5 / tan(Math.toRadians(OrbitScale.FOV_Y_DEG * 0.5))
    fun project(p: OrbitVector): DoubleArray? {
        val d = p - eye
        val z = d.dot(f)
        if (z <= 1e-7) return null
        val x = width * 0.5 + focal * d.dot(r) / z
        val y = height * 0.5 - focal * d.dot(u) / z
        return if (x.isFinite() && y.isFinite()) doubleArrayOf(x, y) else null
    }
    fun groundVisible(n: OrbitVector) = n.dot(eye) >= OrbitScale.EARTH_RADIUS
}
