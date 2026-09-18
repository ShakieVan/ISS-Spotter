package de.shakie.iss

import de.shakie.iss.orbit.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class OrbitMathTest {

    private val line1 = "1 25544U 98067A   26259.85263506  .00007068  00000+0  13566-3 0  9993"
    private val line2 = "2 25544  51.6307 206.4210 0004838 147.2470 212.8820 15.49143506585961"

    @Test
    fun testTleParsing() {
        val tle = Sgp4Propagator.parseTle(line1, line2)
        assertEquals(25544, tle.satNumber)
        assertEquals(26, tle.epochYear)
        assertEquals(51.6307, tle.inclinationDeg, 1e-4)
        assertEquals(15.49143506, tle.meanMotionRevsPerDay, 1e-6)
        assertEquals(0.0004838, tle.eccentricity, 1e-6)
    }

    @Test
    fun testOrbitPropagation() {
        val tle = Sgp4Propagator.parseTle(line1, line2)
        val propagator = Sgp4Propagator(tle)
        val state = propagator.propagate(System.currentTimeMillis())

        // ISS inclination is ~51.6 degrees, so latitude must be within [-52, +52]
        assertTrue("Latitude within bounds: ${state.latitudeDeg}", abs(state.latitudeDeg) <= 52.0)
        // Longitude must be within [-180, +180]
        assertTrue("Longitude within bounds: ${state.longitudeDeg}", abs(state.longitudeDeg) <= 180.0)
        // Altitude must be around 400..435 km
        assertTrue("Altitude within LEO bounds: ${state.altitudeKm}", state.altitudeKm in 390.0..440.0)
        // Test current ISS position relative to Germany
        val now = System.currentTimeMillis()
        val currentState = propagator.propagate(now)
        val currentHoriz = TopocentricPosition.calculate(
            obsLatDeg = 50.0,
            obsLonDeg = 10.0,
            obsAltKm = 0.2,
            issLatDeg = currentState.latitudeDeg,
            issLonDeg = currentState.longitudeDeg,
            issAltKm = currentState.altitudeKm
        )
        println("CURRENT ISS: lat=${currentState.latitudeDeg}, lon=${currentState.longitudeDeg}, alt=${currentState.altitudeKm}")
        println("FROM GERMANY (50, 10): az=${currentHoriz.azimuthDeg} deg, el=${currentHoriz.elevationDeg} deg, dist=${currentHoriz.distanceKm} km")
    }

    @Test
    fun testSolarCoordinates() {
        val sun = SolarCoordinates.calculate(System.currentTimeMillis())
        // Declination / latitude must be within Earth axial tilt range [-23.5, +23.5]
        assertTrue("Solar latitude within ecliptic: ${sun.latitude}", abs(sun.latitude) <= 24.0)
        // Vector length must be 1.0 (unit vector)
        val len = sqrt(sun.vectorX * sun.vectorX + sun.vectorY * sun.vectorY + sun.vectorZ * sun.vectorZ)
        assertEquals(1.0f, len, 1e-4f)
    }

    @Test
    fun testEclipseCalculation() {
        val sun = SunPosition(
            latitude = 0.0,
            longitude = 0.0,
            vectorX = 1.0f,
            vectorY = 0.0f,
            vectorZ = 0.0f
        )

        // Point directly facing the Sun (subsolar)
        val dayFactor = EclipseCalculator.getSunlightFactor(
            issLatDeg = 0.0,
            issLonDeg = 0.0,
            issAltKm = 420.0,
            sun = sun
        )
        assertEquals(1.0f, dayFactor, 1e-3f)

        // Point on opposite side of Earth (in deep umbra)
        val nightFactor = EclipseCalculator.getSunlightFactor(
            issLatDeg = 0.0,
            issLonDeg = 180.0,
            issAltKm = 420.0,
            sun = sun
        )
        assertEquals(0.0f, nightFactor, 1e-3f)

        // Umbra boundary verification: grazing height <= 16 km must be completely dark (0.0)
        // With Earth radius 6371 km, umbra boundary is 6387 km.
        val rUmbra = 6371.0 + 15.0
        // When dot = -100 km, dPerp = sqrt(r^2 - dot^2)
        // If dPerp <= 6387 km -> 0.0f
        val factorAtExtinction = EclipseCalculator.getSunlightFactor(
            issLatDeg = 0.0,
            issLonDeg = 90.0 + Math.toDegrees(asin(15.0 / rUmbra)),
            issAltKm = 15.0,
            sun = sun
        )
        assertEquals(0.0f, factorAtExtinction, 1e-3f)
    }

    @Test
    fun testTopocentricCalculation() {
        // Observer in Munich (48.137, 11.576)
        // ISS directly overhead at 48.137, 11.576 at 420 km
        val horizOverhead = TopocentricPosition.calculate(
            obsLatDeg = 48.137,
            obsLonDeg = 11.576,
            obsAltKm = 0.5,
            issLatDeg = 48.137,
            issLonDeg = 11.576,
            issAltKm = 420.0
        )

        // Elevation should be very close to 90 degrees (zenith)
        assertTrue("Elevation overhead ~ 90 deg: ${horizOverhead.elevationDeg}", horizOverhead.elevationDeg > 88.0)
        assertTrue("Distance ~ 420 km: ${horizOverhead.distanceKm}", abs(horizOverhead.distanceKm - 420.0) < 5.0)
        assertTrue("Visible above horizon", horizOverhead.isVisibleAboveHorizon)

        // ISS on opposite side of Earth (antipode)
        val horizAntipode = TopocentricPosition.calculate(
            obsLatDeg = 48.137,
            obsLonDeg = 11.576,
            obsAltKm = 0.5,
            issLatDeg = -48.137,
            issLonDeg = -168.424,
            issAltKm = 420.0
        )
        assertTrue("Elevation on opposite side < 0: ${horizAntipode.elevationDeg}", horizAntipode.elevationDeg < -50.0)
        assertFalse("Not visible above horizon", horizAntipode.isVisibleAboveHorizon)
    }

    @Test
    fun testAndroidSensorRotationVector() {
        // Implementation of Android's getRotationMatrixFromVector
        fun getRotationMatrix(rv: FloatArray): FloatArray {
            val q1 = rv[0]
            val q2 = rv[1]
            val q3 = rv[2]
            val q0 = if (rv.size >= 4) rv[3] else sqrt(max(0f, 1f - q1*q1 - q2*q2 - q3*q3))

            val sq_q1 = 2f * q1 * q1
            val sq_q2 = 2f * q2 * q2
            val sq_q3 = 2f * q3 * q3
            val q1_q2 = 2f * q1 * q2
            val q3_q0 = 2f * q3 * q0
            val q1_q3 = 2f * q1 * q3
            val q2_q0 = 2f * q2 * q0
            val q2_q3 = 2f * q2 * q3
            val q1_q0 = 2f * q1 * q0

            val r = FloatArray(16)
            r[0] = 1f - sq_q2 - sq_q3
            r[1] = q1_q2 - q3_q0
            r[2] = q1_q3 + q2_q0

            r[4] = q1_q2 + q3_q0
            r[5] = 1f - sq_q1 - sq_q3
            r[6] = q2_q3 - q1_q0

            r[8] = q1_q3 - q2_q0
            r[9] = q2_q3 + q1_q0
            r[10] = 1f - sq_q1 - sq_q2
            r[15] = 1.0f
            return r
        }

        // Helper to construct quaternion from axis (x,y,z) and angle theta in degrees
        fun quatFromAxisAngle(ax: Float, ay: Float, az: Float, angleDeg: Float): FloatArray {
            val halfRad = Math.toRadians(angleDeg.toDouble() / 2.0).toFloat()
            val s = sin(halfRad)
            val c = cos(halfRad)
            return floatArrayOf(ax * s, ay * s, az * s, c)
        }

        // Case 1: Phone lying flat on table, top pointing North.
        // Identity: angle = 0
        val rvFlatNorth = quatFromAxisAngle(0f, 0f, 1f, 0f)
        val rFlat = getRotationMatrix(rvFlatNorth)
        println("Flat on table, top North:")
        println("R = " + rFlat.toList().chunked(4).joinToString("\n"))

        // Case 2: User picks up phone and holds it upright in portrait, pointing rear camera NORTH at horizon.
        // To go from flat (top pointing North, screen pointing Up (+Z)) to upright (top pointing Up, screen pointing South, rear camera pointing North):
        // The phone is tilted up around world X (East) by +90 degrees!
        val rvUprightNorth = quatFromAxisAngle(1f, 0f, 0f, 90f)
        val rUpNorth = getRotationMatrix(rvUprightNorth)
        println("\nUpright in portrait, camera North:")
        println("R = " + rUpNorth.toList().chunked(4).joinToString("\n"))

        // Let's check:
        // In this orientation, where does the rear camera point?
        // Rear camera is pointing NORTH (towards world Y: east=0, north=1, up=0).
        // Let's test our v1.0.2 formula:
        // camEast = -R[2]
        // camNorth = -R[6]
        // camUp = -R[10]
        println("v1.0.2 camera vector for Upright North: East=${-rUpNorth[2]}, North=${-rUpNorth[6]}, Up=${-rUpNorth[10]}")

        // What about Row 2 vs Column 2?
        // Column 2 of R: R[2], R[6], R[10]
        // Row 2 of R: R[8], R[9], R[10]
        println("Row 2: R[8]=${rUpNorth[8]}, R[9]=${rUpNorth[9]}, R[10]=${rUpNorth[10]}")
        println("Col 2: R[2]=${rUpNorth[2]}, R[6]=${rUpNorth[6]}, R[10]=${rUpNorth[10]}")

        // Now: User turns 90 degrees CLOCKWISE to face EAST.
        // Rotation around world Z: heading changes from North (0 deg) to East (+90 deg compass, which is -90 deg around +Z axis).
        // Combined with +90 deg tilt around X.
        // Let's check rotation around Z:
        val qTilt = quatFromAxisAngle(1f, 0f, 0f, 90f) // x tilt
        val qPan = quatFromAxisAngle(0f, 0f, 1f, -90f) // turn East (clockwise around +Z is negative angle)
        // Multiply quaternions qPan * qTilt:
        // (p0 + p1 i + p2 j + p3 k) * (t0 + t1 i + t2 j + t3 k)
        val p1 = qPan[0]; val p2 = qPan[1]; val p3 = qPan[2]; val p0 = qPan[3]
        val t1 = qTilt[0]; val t2 = qTilt[1]; val t3 = qTilt[2]; val t0 = qTilt[3]
        val c0 = p0*t0 - p1*t1 - p2*t2 - p3*t3
        val c1 = p0*t1 + p1*t0 + p2*t3 - p3*t2
        val c2 = p0*t2 - p1*t3 + p2*t0 + p3*t1
        val c3 = p0*t3 + p1*t2 - p2*t1 + p3*t0
        // Case 3: Tilting camera UP to zenith.
        // User starts upright North, then tilts phone backward so rear camera points UP to the sky.
        // Tilting backward by 90 degrees around East (X axis).
        // That is a total rotation of +180 degrees around X!
        val rvZenith = quatFromAxisAngle(1f, 0f, 0f, 180f)
        val rZenith = getRotationMatrix(rvZenith)
        println("\nCamera pointing UP to Zenith:")
        println("R = " + rZenith.toList().chunked(4).joinToString("\n"))
        println("v1.0.2 camera vector for Zenith: East=${-rZenith[2]}, North=${-rZenith[6]}, Up=${-rZenith[10]}")

        // Case 5: Pointing rear camera at ISS (Azimuth = 218 deg, Elevation = -46 deg)
        // Heading = 218 deg (clockwise from North, so rotation around Z is -218 deg)
        // Pitch = -46 deg (tilting down, so rotation around X is 90 + (-46) = 44 deg from flat, or upright tilt 90 then tilt down 46)
        // When upright: tilt was +90 around X. Tilting down by 46 deg means tilt around device X by -46 deg!
        val qPan218 = quatFromAxisAngle(0f, 0f, 1f, -218f)
        val qTiltDown46 = quatFromAxisAngle(1f, 0f, 0f, 90f - 46f) // 44 deg
        // Multiply:
        val p1_ = qPan218[0]; val p2_ = qPan218[1]; val p3_ = qPan218[2]; val p0_ = qPan218[3]
        val t1_ = qTiltDown46[0]; val t2_ = qTiltDown46[1]; val t3_ = qTiltDown46[2]; val t0_ = qTiltDown46[3]
        val c0_ = p0_*t0_ - p1_*t1_ - p2_*t2_ - p3_*t3_
        val c1_ = p0_*t1_ + p1_*t0_ + p2_*t3_ - p3_*t2_
        val c2_ = p0_*t2_ - p1_*t3_ + p2_*t0_ + p3_*t1_
        val c3_ = p0_*t3_ + p1_*t2_ - p2_*t1_ + p3_*t0_
        val rvIss = floatArrayOf(c1_, c2_, c3_, c0_)
        val rIss = getRotationMatrix(rvIss)
        val issCamE = -rIss[2].toDouble()
        val issCamN = -rIss[6].toDouble()
        val issCamU = -rIss[10].toDouble()
        val issPitch = Math.toDegrees(asin(issCamU)).toFloat()
        var issAz = Math.toDegrees(atan2(issCamE, issCamN)).toFloat()
        if (issAz < 0f) issAz += 360f
        println("\nPointing at ISS (Target: Az=218, Pitch=-46):")
        println("Calculated: Az=$issAz, Pitch=$issPitch")
        assertEquals(218f, issAz, 1.0f)
        assertEquals(-46f, issPitch, 1.0f)
    }

    @Test
    fun testOrbitCameraController() {
        val lat = 38.56
        val lon = -152.38
        val altKm = 424.4
        val earthRadius = 10.0f
        val issAltitudeScale = earthRadius * (altKm.toFloat() / 6371.0f)
        val issRadius = earthRadius + issAltitudeScale

        val issLatRad = Math.toRadians(lat).toFloat()
        val issLonRad = Math.toRadians(lon).toFloat()

        val issX = issRadius * cos(issLatRad) * cos(issLonRad)
        val issY = issRadius * sin(issLatRad)
        val issZ = issRadius * cos(issLatRad) * sin(issLonRad)

        val issPos = floatArrayOf(issX, issY, issZ)
        val cam = de.shakie.iss.graphics.OrbitCameraController()
        assertFalse(cam.isModified())

        val defaultPose = cam.computeCameraPose(issPos, floatArrayOf(0f, 0f, 0f))
        assertEquals(9, defaultPose.size)

        // Modify yaw and check isModified
        cam.yawOffsetDeg = 45f
        assertTrue(cam.isModified())

        val rotatedPose = cam.computeCameraPose(issPos, floatArrayOf(0f, 0f, 0f))
        assertEquals(9, rotatedPose.size)

        // Reset
        cam.reset()
        assertFalse(cam.isModified())
        assertEquals(0f, cam.yawOffsetDeg, 1e-4f)
        assertEquals(0f, cam.pitchOffsetDeg, 1e-4f)
        assertEquals(1.0f, cam.zoomFactor, 1e-4f)
    }
}
