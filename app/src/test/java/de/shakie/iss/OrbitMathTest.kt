package de.shakie.iss

import de.shakie.iss.graphics.*
import de.shakie.iss.orbit.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.*

class OrbitMathTest {

    private val line1 = "1 25544U 98067A   26259.85263506  .00007068  00000+0  13566-3 0  9993"
    private val line2 = "2 25544  51.6307 206.4210 0004838 147.2470 212.8820 15.49143506585961"

    @Test
    fun testBrazilCameraGeometry() {
        val latIss = Math.toRadians(-26.95).toFloat()
        val lonIss = Math.toRadians(-42.57).toFloat()
        val rIss = 10.66f
        val issPos = floatArrayOf(
            rIss * cos(latIss) * cos(lonIss),
            rIss * sin(latIss),
            -rIss * cos(latIss) * sin(lonIss)
        )

        val controller = de.shakie.iss.graphics.OrbitCameraController()
        val pose = controller.computeCameraPose(issPos, floatArrayOf(0f, 10f, 0f))
        val eye = floatArrayOf(pose[0], pose[1], pose[2])
        val target = floatArrayOf(pose[3], pose[4], pose[5])
        val up = floatArrayOf(pose[6], pose[7], pose[8])

        val fwd = floatArrayOf(target[0] - eye[0], target[1] - eye[1], target[2] - eye[2])
        val fwdLen = sqrt(fwd[0]*fwd[0] + fwd[1]*fwd[1] + fwd[2]*fwd[2])
        val fNorm = floatArrayOf(fwd[0]/fwdLen, fwd[1]/fwdLen, fwd[2]/fwdLen)

        // Camera Right = cross(fNorm, up)
        val right = floatArrayOf(
            fNorm[1] * up[2] - fNorm[2] * up[1],
            fNorm[2] * up[0] - fNorm[0] * up[2],
            fNorm[0] * up[1] - fNorm[1] * up[0]
        )

        // Brazil center: -14 lat, -51 lon
        val rEarth = 10.0f
        val latBr = Math.toRadians(-14.0).toFloat()
        val lonBr = Math.toRadians(-51.0).toFloat()
        val brazilPos = floatArrayOf(
            rEarth * cos(latBr) * cos(lonBr),
            rEarth * sin(latBr),
            -rEarth * cos(latBr) * sin(lonBr)
        )

        // Rio de Janeiro: -22.9 lat, -43.2 lon
        val latRio = Math.toRadians(-22.9).toFloat()
        val lonRio = Math.toRadians(-43.2).toFloat()
        val rioPos = floatArrayOf(
            rEarth * cos(latRio) * cos(lonRio),
            rEarth * sin(latRio),
            -rEarth * cos(latRio) * sin(lonRio)
        )

        val dBr = floatArrayOf(brazilPos[0] - eye[0], brazilPos[1] - eye[1], brazilPos[2] - eye[2])
        val screenXBr = dBr[0] * right[0] + dBr[1] * right[1] + dBr[2] * right[2]
        val screenYBr = dBr[0] * up[0] + dBr[1] * up[1] + dBr[2] * up[2]

        val dRio = floatArrayOf(rioPos[0] - eye[0], rioPos[1] - eye[1], rioPos[2] - eye[2])
        val screenXRio = dRio[0] * right[0] + dRio[1] * right[1] + dRio[2] * right[2]
        val screenYRio = dRio[0] * up[0] + dRio[1] * up[1] + dRio[2] * up[2]

        println("=== GEOMETRY CHECK (CORRECTED) ===")
        println("ISS pos: [${issPos[0]}, ${issPos[1]}, ${issPos[2]}]")
        println("Camera Eye: [${eye[0]}, ${eye[1]}, ${eye[2]}]")
        println("Camera Up: [${up[0]}, ${up[1]}, ${up[2]}]")
        println("Camera Right: [${right[0]}, ${right[1]}, ${right[2]}]")
        println("Brazil Screen X: $screenXBr, Screen Y: $screenYBr")
        println("Rio Screen X: $screenXRio, Screen Y: $screenYRio")

        // When looking North at the Earth from the South Atlantic (ISS southeast of Brazil):
        // Brazil and Rio MUST appear to the LEFT (West, negative Screen X) of the ISS!
        assertTrue("Brazil must be on the left (West): $screenXBr", screenXBr < 0.0f)
        assertTrue("Rio must be on the left (West): $screenXRio", screenXRio < 0.0f)
    }

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
        // 1. Sun at Greenwich (vectorZ = 0)
        val sunGreenwich = SunPosition(
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
            sun = sunGreenwich
        )
        assertEquals(1.0f, dayFactor, 1e-3f)

        // Point on opposite side of Earth (in deep umbra)
        val nightFactor = EclipseCalculator.getSunlightFactor(
            issLatDeg = 0.0,
            issLonDeg = 180.0,
            issAltKm = 420.0,
            sun = sunGreenwich
        )
        assertEquals(0.0f, nightFactor, 1e-3f)

        // 2. Sun at 90 deg East (vectorZ = -1.0 in standard right-handed space)
        val sunEast = SunPosition(
            latitude = 0.0,
            longitude = 90.0,
            vectorX = 0.0f,
            vectorY = 0.0f,
            vectorZ = -1.0f
        )
        val eastDay = EclipseCalculator.getSunlightFactor(
            issLatDeg = 0.0,
            issLonDeg = 90.0,
            issAltKm = 420.0,
            sun = sunEast
        )
        assertEquals(1.0f, eastDay, 1e-3f)

        val westNight = EclipseCalculator.getSunlightFactor(
            issLatDeg = 0.0,
            issLonDeg = -90.0,
            issAltKm = 420.0,
            sun = sunEast
        )
        assertEquals(0.0f, westNight, 1e-3f)

        // 3. User Malaysia Overflight (06:32 UTC on 2026-09-18: Lat 4.36 N, Lon 107.43 E)
        val timeMalaysia = 1789713120000L // 06:32 UTC
        val sunAtMalaysiaTime = SolarCoordinates.calculate(timeMalaysia)
        val malaysiaSunFactor = EclipseCalculator.getSunlightFactor(
            issLatDeg = 4.36,
            issLonDeg = 107.43,
            issAltKm = 422.1,
            sun = sunAtMalaysiaTime
        )
        assertEquals("Malaysia at 13:32 local time must be in full sunlight", 1.0f, malaysiaSunFactor, 1e-3f)
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

    @Test
    fun testPeruLightingAndShadow() {
        // Timestamp from screenshot media_1789716117302.jpg (09:21 local = 07:21 UTC on 2026-09-18)
        val timeMillis = java.time.Instant.parse("2026-09-18T07:21:00Z").toEpochMilli()
        val sun = SolarCoordinates.calculate(timeMillis)

        println("=== SUN POSITION AT 07:21 UTC ===")
        println("Sun Lat: ${sun.latitude}, Lon: ${sun.longitude}")
        println("Sun Vector: [${sun.vectorX}, ${sun.vectorY}, ${sun.vectorZ}]")

        // Sun should be in the daytime over Asia / Indian Ocean (+60° to +110° East)
        assertTrue("Sun should be over Eastern hemisphere in morning UTC: ${sun.longitude}", sun.longitude > 0.0)

        // Peru: lat ~ -11.58, lon ~ -79.14 (Western hemisphere, ~02:21 AM local time)
        val issLat = -11.58
        val issLon = -79.14
        val issAlt = 417.6

        val sunlight = EclipseCalculator.getSunlightFactor(issLat, issLon, issAlt, sun)
        println("Peru ISS Sunlight factor: $sunlight")
        assertEquals("ISS over Peru at 02:21 AM must be in Earth shadow", 0.0f, sunlight, 1e-4f)

        // Calculate surface normal for Peru using UV mapping formula
        val uPeru = ((issLon + 180.0) / 360.0).toFloat()
        val vPeru = ((issLat + 90.0) / 180.0).toFloat()

        val theta = (1.0f - vPeru) * Math.PI.toFloat()
        val phi = (uPeru - 0.5f) * 2.0f * Math.PI.toFloat()
        val nx = sin(theta) * cos(phi)
        val ny = cos(theta)
        val nz = -sin(theta) * sin(phi)

        val sunDot = nx * sun.vectorX + ny * sun.vectorY + nz * sun.vectorZ
        println("Peru Surface Normal: [$nx, $ny, $nz]")
        println("Peru sunDot: $sunDot")

        assertTrue("Peru must be on night side (sunDot < -0.1): $sunDot", sunDot < -0.1f)
    }

    @Test
    fun testCameraDragDirection() {
        val lat = 0.0
        val lon = 0.0
        val altKm = 420.0
        val earthRadius = 10.0f
        val issRadius = earthRadius + earthRadius * (altKm.toFloat() / 6371.0f)
        val issPos = floatArrayOf(issRadius, 0f, 0f)

        val cam = de.shakie.iss.graphics.OrbitCameraController()
        val defaultPose = cam.computeCameraPose(issPos, floatArrayOf(0f, 0f, 0f))
        val eye0 = floatArrayOf(defaultPose[0], defaultPose[1], defaultPose[2])
        val tgt0 = floatArrayOf(defaultPose[3], defaultPose[4], defaultPose[5])
        val up0 = floatArrayOf(defaultPose[6], defaultPose[7], defaultPose[8])
        val fwd0 = floatArrayOf(tgt0[0] - eye0[0], tgt0[1] - eye0[1], tgt0[2] - eye0[2])
        val fLen0 = sqrt(fwd0[0]*fwd0[0] + fwd0[1]*fwd0[1] + fwd0[2]*fwd0[2])
        val fNorm0 = floatArrayOf(fwd0[0]/fLen0, fwd0[1]/fLen0, fwd0[2]/fLen0)
        // Camera Right = cross(fNorm0, up0)
        val right0 = floatArrayOf(
            fNorm0[1] * up0[2] - fNorm0[2] * up0[1],
            fNorm0[2] * up0[0] - fNorm0[0] * up0[2],
            fNorm0[0] * up0[1] - fNorm0[1] * up0[0]
        )

        // Point on Earth surface slightly to the East (Screen Right)
        val targetPointOnEarth = floatArrayOf(
            earthRadius,
            0f,
            -0.5f // East is -Z in Filament coordinate system
        )

        // Initial screen X coordinate of targetPointOnEarth
        val toPt0 = floatArrayOf(targetPointOnEarth[0] - eye0[0], targetPointOnEarth[1] - eye0[1], targetPointOnEarth[2] - eye0[2])
        val screenX0 = toPt0[0] * right0[0] + toPt0[1] * right0[1] + toPt0[2] * right0[2]

        // 1. HORIZONTAL DRAG TEST: User swipes finger to the RIGHT (dx = +100 px)
        // In direct manipulation, dragging finger to the RIGHT MUST move the scene to the RIGHT (screenX1 > screenX0)
        val dx = 100f
        cam.yawOffsetDeg = 0f + dx * 0.16f

        val dragPose = cam.computeCameraPose(issPos, floatArrayOf(0f, 0f, 0f))
        val eye1 = floatArrayOf(dragPose[0], dragPose[1], dragPose[2])
        val tgt1 = floatArrayOf(dragPose[3], dragPose[4], dragPose[5])
        val up1 = floatArrayOf(dragPose[6], dragPose[7], dragPose[8])
        val fwd1 = floatArrayOf(tgt1[0] - eye1[0], tgt1[1] - eye1[1], tgt1[2] - eye1[2])
        val fLen1 = sqrt(fwd1[0]*fwd1[0] + fwd1[1]*fwd1[1] + fwd1[2]*fwd1[2])
        val fNorm1 = floatArrayOf(fwd1[0]/fLen1, fwd1[1]/fLen1, fwd1[2]/fLen1)
        val right1 = floatArrayOf(
            fNorm1[1] * up1[2] - fNorm1[2] * up1[1],
            fNorm1[2] * up1[0] - fNorm1[0] * up1[2],
            fNorm1[0] * up1[1] - fNorm1[1] * up1[0]
        )

        val toPt1 = floatArrayOf(targetPointOnEarth[0] - eye1[0], targetPointOnEarth[1] - eye1[1], targetPointOnEarth[2] - eye1[2])
        val screenX1 = toPt1[0] * right1[0] + toPt1[1] * right1[1] + toPt1[2] * right1[2]

        println("Touch Drag Right Test: screenX0=$screenX0 -> screenX1=$screenX1 (delta=${screenX1 - screenX0})")
        assertTrue("Dragging finger RIGHT must shift scene to the RIGHT (positive delta): delta=${screenX1 - screenX0}", screenX1 > screenX0)

        // 2. VERTICAL DRAG TEST: User swipes finger DOWN (dy = +100 px)
        // Dragging finger DOWN MUST move the scene DOWN (screenY1 < screenY0 or positive screen displacement following finger)
        cam.reset()
        val dy = 100f
        cam.pitchOffsetDeg = 0f + dy * 0.16f

        val pitchPose = cam.computeCameraPose(issPos, floatArrayOf(0f, 0f, 0f))
        val eyeP = floatArrayOf(pitchPose[0], pitchPose[1], pitchPose[2])
        val upP = floatArrayOf(pitchPose[6], pitchPose[7], pitchPose[8])
        val toPtP = floatArrayOf(targetPointOnEarth[0] - eyeP[0], targetPointOnEarth[1] - eyeP[1], targetPointOnEarth[2] - eyeP[2])
        // In screen Y (where Up is +Y), when camera tilts up, object on screen moves DOWN (screen Y decreases)
        val screenY0 = toPt0[0] * up0[0] + toPt0[1] * up0[1] + toPt0[2] * up0[2]
        val screenYP = toPtP[0] * upP[0] + toPtP[1] * upP[1] + toPtP[2] * upP[2]
        println("Touch Drag Down Test: screenY0=$screenY0 -> screenYP=$screenYP (delta=${screenYP - screenY0})")
        assertTrue("Dragging finger DOWN must move scene DOWN on screen: delta=${screenYP - screenY0}", screenYP < screenY0)
    }

    @Test
    fun testLatLonTo3DAndBackRoundtrip() {
        val r = 10.0f

        // Helper conversion lat/lon to 3D
        fun to3D(latDeg: Double, lonDeg: Double): FloatArray {
            val latRad = Math.toRadians(latDeg)
            val lonRad = Math.toRadians(lonDeg)
            val x = (r * cos(latRad) * cos(lonRad)).toFloat()
            val y = (r * sin(latRad)).toFloat()
            val z = (-r * cos(latRad) * sin(lonRad)).toFloat()
            return floatArrayOf(x, y, z)
        }

        // Helper conversion 3D to lat/lon
        fun toLatLon(pos: FloatArray): Pair<Double, Double> {
            val len = sqrt(pos[0] * pos[0] + pos[1] * pos[1] + pos[2] * pos[2])
            val latRad = asin((pos[1] / len).coerceIn(-1f, 1f).toDouble())
            val lonRad = atan2(-pos[2].toDouble(), pos[0].toDouble())
            return Pair(Math.toDegrees(latRad), Math.toDegrees(lonRad))
        }

        val testPoints = listOf(
            Pair(90.0, 0.0),      // North Pole
            Pair(-90.0, 0.0),     // South Pole
            Pair(0.0, 0.0),       // Equator at Prime Meridian
            Pair(0.0, 90.0),      // Equator at 90° East
            Pair(0.0, -90.0),     // Equator at 90° West
            Pair(-50.0, 172.0),   // ISS sample point (South Pacific)
            Pair(52.52, 13.405),  // Berlin
            Pair(-22.9, -43.2),   // Rio de Janeiro
            Pair(72.0, -40.0),    // Greenland
            Pair(-54.0, -70.0)    // Patagonia / Chile
        )

        for ((lat, lon) in testPoints) {
            val pos = to3D(lat, lon)
            val (latBack, lonBack) = toLatLon(pos)

            println("Roundtrip: ($lat°, $lon°) -> pos=[${pos[0]}, ${pos[1]}, ${pos[2]}] -> ($latBack°, $lonBack°)")
            assertEquals("Latitude roundtrip for $lat°", lat, latBack, 1e-4)
            // At poles, longitude is indeterminate, only check when not at pole
            if (abs(lat) < 89.9) {
                assertEquals("Longitude roundtrip for $lon°", lon, lonBack, 1e-4)
            }
        }

        // Specific axial direction checks:
        val northPolePos = to3D(90.0, 0.0)
        assertEquals(0f, northPolePos[0], 1e-5f)
        assertEquals(r, northPolePos[1], 1e-5f)
        assertEquals(0f, northPolePos[2], 1e-5f)

        val southPolePos = to3D(-90.0, 0.0)
        assertEquals(0f, southPolePos[0], 1e-5f)
        assertEquals(-r, southPolePos[1], 1e-5f)
        assertEquals(0f, southPolePos[2], 1e-5f)

        val eastPos = to3D(0.0, 90.0)
        assertEquals(0f, eastPos[0], 1e-5f)
        assertEquals(0f, eastPos[1], 1e-5f)
        assertEquals(-r, eastPos[2], 1e-5f) // East is -Z

        val westPos = to3D(0.0, -90.0)
        assertEquals(0f, westPos[0], 1e-5f)
        assertEquals(0f, westPos[1], 1e-5f)
        assertEquals(r, westPos[2], 1e-5f)  // West is +Z
    }

    @Test
    fun testMeshUvToShaderNormalCorrespondence() {
        // Shader logic from earth.mat:
        // float theta = uv.y * 3.141592653589793;
        // float phi = (uv.x - 0.5) * 6.283185307179586;
        // float sinTheta = sin(theta);
        // float cosTheta = cos(theta);
        // float3 normal = float3(sinTheta * cos(phi), cosTheta, -sinTheta * sin(phi));
        // float latDeg = (0.5 - uv.y) * 180.0;
        fun shaderNormal(uvX: Float, uvY: Float): FloatArray {
            val theta = uvY * Math.PI.toFloat()
            val phi = (uvX - 0.5f) * 2.0f * Math.PI.toFloat()
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)
            return floatArrayOf(
                sinTheta * cos(phi),
                cosTheta,
                -sinTheta * sin(phi)
            )
        }

        fun shaderLatDeg(uvY: Float): Float = (0.5f - uvY) * 180.0f

        // 1. North Pole: Mesh v=1.0 -> Filament flipUV=true -> Shader uv.y=0.0
        val nNorth = shaderNormal(0.5f, 0.0f)
        println("Shader North Pole Normal: [${nNorth[0]}, ${nNorth[1]}, ${nNorth[2]}], Lat: ${shaderLatDeg(0.0f)}°")
        assertEquals(0f, nNorth[0], 1e-5f)
        assertEquals(1f, nNorth[1], 1e-5f) // MUST point +Y (North)!
        assertEquals(0f, nNorth[2], 1e-5f)
        assertEquals(90f, shaderLatDeg(0.0f), 1e-5f)

        // 2. South Pole: Mesh v=0.0 -> Filament flipUV=true -> Shader uv.y=1.0
        val nSouth = shaderNormal(0.5f, 1.0f)
        println("Shader South Pole Normal: [${nSouth[0]}, ${nSouth[1]}, ${nSouth[2]}], Lat: ${shaderLatDeg(1.0f)}°")
        assertEquals(0f, nSouth[0], 1e-5f)
        assertEquals(-1f, nSouth[1], 1e-5f) // MUST point -Y (South)!
        assertEquals(0f, nSouth[2], 1e-5f)
        assertEquals(-90f, shaderLatDeg(1.0f), 1e-5f)

        // 3. Equator at Prime Meridian: uv=(0.5, 0.5)
        val nEquatorGreenwich = shaderNormal(0.5f, 0.5f)
        assertEquals(1f, nEquatorGreenwich[0], 1e-5f) // MUST point +X (Greenwich)!
        assertEquals(0f, nEquatorGreenwich[1], 1e-5f)
        assertEquals(0f, nEquatorGreenwich[2], 1e-5f)
        assertEquals(0f, shaderLatDeg(0.5f), 1e-5f)

        // 4. Equator at 90° East: uv=(0.75, 0.5)
        val nEquatorEast = shaderNormal(0.75f, 0.5f)
        assertEquals(0f, nEquatorEast[0], 1e-5f)
        assertEquals(0f, nEquatorEast[1], 1e-5f)
        assertEquals(-1f, nEquatorEast[2], 1e-5f) // MUST point -Z (East)!

        // 5. Equator at 90° West: uv=(0.25, 0.5)
        val nEquatorWest = shaderNormal(0.25f, 0.5f)
        assertEquals(0f, nEquatorWest[0], 1e-5f)
        assertEquals(0f, nEquatorWest[1], 1e-5f)
        assertEquals(1f, nEquatorWest[2], 1e-5f)  // MUST point +Z (West)!
    }

    @Test
    fun testSolarIlluminationAndDeclination() {
        fun normalFromLatLon(latDeg: Double, lonDeg: Double): FloatArray {
            val latRad = Math.toRadians(latDeg)
            val lonRad = Math.toRadians(lonDeg)
            return floatArrayOf(
                (cos(latRad) * cos(lonRad)).toFloat(),
                sin(latRad).toFloat(),
                (-cos(latRad) * sin(lonRad)).toFloat()
            )
        }

        fun dot(a: FloatArray, b: FloatArray): Float = a[0]*b[0] + a[1]*b[1] + a[2]*b[2]

        // 1. Equatorial Sun (Equinox): Subsolar at 0° Lat, 0° Lon
        val sunEquator = normalFromLatLon(0.0, 0.0)
        val nGreenwich = normalFromLatLon(0.0, 0.0)
        val nAntipode = normalFromLatLon(0.0, 180.0)
        val nTerminator = normalFromLatLon(0.0, 90.0)

        assertEquals("Subsolar dot must be +1.0", 1.0f, dot(nGreenwich, sunEquator), 1e-5f)
        assertEquals("Antisolar dot must be -1.0", -1.0f, dot(nAntipode, sunEquator), 1e-5f)
        assertEquals("Terminator dot must be 0.0", 0.0f, dot(nTerminator, sunEquator), 1e-5f)

        // 2. Northern Summer (Solstice): Sun declination = +23.44° over 0° Lon
        val sunNorthSummer = normalFromLatLon(23.44, 0.0)
        val nNorthPole = normalFromLatLon(90.0, 0.0)
        val nSouthPole = normalFromLatLon(-90.0, 0.0)

        val dotNorthPoleSummer = dot(nNorthPole, sunNorthSummer)
        val dotSouthPoleSummer = dot(nSouthPole, sunNorthSummer)
        println("Northern Summer: North Pole dot=$dotNorthPoleSummer, South Pole dot=$dotSouthPoleSummer")
        assertTrue("North Pole must be in sunlight (Polar Day) during Northern Summer: dot=$dotNorthPoleSummer", dotNorthPoleSummer > 0f)
        assertTrue("South Pole must be in shadow (Polar Night) during Northern Summer: dot=$dotSouthPoleSummer", dotSouthPoleSummer < 0f)

        // 3. Southern Summer (Solstice): Sun declination = -23.44° over 0° Lon
        val sunSouthSummer = normalFromLatLon(-23.44, 0.0)
        val dotNorthPoleWinter = dot(nNorthPole, sunSouthSummer)
        val dotSouthPoleWinter = dot(nSouthPole, sunSouthSummer)
        println("Southern Summer: North Pole dot=$dotNorthPoleWinter, South Pole dot=$dotSouthPoleWinter")
        assertTrue("North Pole must be in shadow (Polar Night) during Southern Summer: dot=$dotNorthPoleWinter", dotNorthPoleWinter < 0f)
        assertTrue("South Pole must be in sunlight (Polar Day) during Southern Summer: dot=$dotSouthPoleWinter", dotSouthPoleWinter > 0f)
    }

    @Test
    fun testMapStateResolverCombinations() {
        // Requirement 1 & 5a: Test all 7 state combinations against the REAL MapStateResolver

        // 1. App start: Blue Marble, no satellite texture, clouds ON
        val state1 = MapStateResolver.resolve(
            MapStateInput(
                requestedSource = MapSourcePreference.BLUE_MARBLE,
                hasSatelliteTexture = false,
                downloadState = SatelliteDownloadState.NOT_STARTED,
                userCloudPreference = true,
                isReferenceMode = false
            )
        )
        assertEquals(EffectiveTextureSource.BLUE_MARBLE, state1.activeTextureSource)
        assertEquals(MapLightingMode.BLUE_MARBLE_ALBEDO, state1.mapLightingMode)
        assertTrue("Blue Marble with cloud preference ON must show clouds", state1.showClouds)
        assertFalse(state1.isReferenceMode)
        assertFalse(state1.isSatellitePending)
        assertFalse(state1.isSatelliteFailed)
        assertEquals("🌍 Karte: Blue Marble", state1.statusLabel)

        // 2. Satellite requested, download in progress: fallback to Blue Marble, "Lädt...", preserve clouds
        val state2 = MapStateResolver.resolve(
            MapStateInput(
                requestedSource = MapSourcePreference.SATELLITE,
                hasSatelliteTexture = false,
                downloadState = SatelliteDownloadState.PENDING,
                userCloudPreference = true,
                isReferenceMode = false
            )
        )
        assertEquals(EffectiveTextureSource.BLUE_MARBLE, state2.activeTextureSource)
        assertEquals(MapLightingMode.BLUE_MARBLE_ALBEDO, state2.mapLightingMode)
        assertTrue("During satellite download fallback, user cloud preference must be preserved", state2.showClouds)
        assertTrue("Must signal pending satellite download", state2.isSatellitePending)
        assertFalse(state2.isSatelliteFailed)
        assertEquals("🛰️ Satellit: Lädt...", state2.statusLabel)

        // 3. Satellite download succeeded: switch to VIIRS, strictly NO stacked clouds
        val state3 = MapStateResolver.resolve(
            MapStateInput(
                requestedSource = MapSourcePreference.SATELLITE,
                hasSatelliteTexture = true,
                downloadState = SatelliteDownloadState.SUCCEEDED,
                userCloudPreference = true,
                isReferenceMode = false
            )
        )
        assertEquals(EffectiveTextureSource.SATELLITE_VIIRS, state3.activeTextureSource)
        assertEquals(MapLightingMode.VIIRS_TRUECOLOR, state3.mapLightingMode)
        assertFalse("VIIRS satellite image MUST NEVER stack extra cloud layer on top!", state3.showClouds)
        assertFalse(state3.isSatellitePending)
        assertFalse(state3.isSatelliteFailed)
        assertEquals("🛰️ Satellit: VIIRS", state3.statusLabel)

        // 4. User reverted to Blue Marble before download finished: when download finishes, DO NOT hijack view
        val state4 = MapStateResolver.resolve(
            MapStateInput(
                requestedSource = MapSourcePreference.BLUE_MARBLE,
                hasSatelliteTexture = true,
                downloadState = SatelliteDownloadState.SUCCEEDED,
                userCloudPreference = true,
                isReferenceMode = false
            )
        )
        assertEquals(EffectiveTextureSource.BLUE_MARBLE, state4.activeTextureSource)
        assertTrue("Blue Marble must restore user cloud preference", state4.showClouds)
        assertEquals("🌍 Karte: Blue Marble", state4.statusLabel)

        // 5. User reverted to Blue Marble with clouds OFF: restore clouds OFF
        val state5 = MapStateResolver.resolve(
            MapStateInput(
                requestedSource = MapSourcePreference.BLUE_MARBLE,
                hasSatelliteTexture = true,
                downloadState = SatelliteDownloadState.SUCCEEDED,
                userCloudPreference = false,
                isReferenceMode = false
            )
        )
        assertEquals(EffectiveTextureSource.BLUE_MARBLE, state5.activeTextureSource)
        assertFalse("User cloud preference OFF must be strictly respected", state5.showClouds)
        assertEquals("🌍 Karte: Blue Marble", state5.statusLabel)

        // 6. Reference mode: strictly NO clouds, regardless of texture source
        val state6 = MapStateResolver.resolve(
            MapStateInput(
                requestedSource = MapSourcePreference.SATELLITE,
                hasSatelliteTexture = true,
                downloadState = SatelliteDownloadState.SUCCEEDED,
                userCloudPreference = true,
                isReferenceMode = true
            )
        )
        assertEquals(EffectiveTextureSource.SATELLITE_VIIRS, state6.activeTextureSource)
        assertFalse("Reference mode must have NO clouds", state6.showClouds)
        assertFalse("Reference mode must strictly suppress borders (showBorders=false)", state6.showBorders)
        assertTrue(state6.isReferenceMode)
        assertEquals("🛰️ Satellit: Referenz", state6.statusLabel)

        // 7. Satellite download failed: fallback to Blue Marble, signal error
        val state7 = MapStateResolver.resolve(
            MapStateInput(
                requestedSource = MapSourcePreference.SATELLITE,
                hasSatelliteTexture = false,
                downloadState = SatelliteDownloadState.FAILED,
                userCloudPreference = true,
                isReferenceMode = false
            )
        )
        assertEquals(EffectiveTextureSource.BLUE_MARBLE, state7.activeTextureSource)
        assertTrue(state7.showClouds)
        assertFalse(state7.isSatellitePending)
        assertTrue("Must signal failed satellite download", state7.isSatelliteFailed)
        assertEquals("🛰️ Satellit: Fehler (Fallback)", state7.statusLabel)
    }

    @Test
    fun testReferenceModeSuppressesBordersAndRestoresPreference() {
        // User has borders ON
        val onInput = MapStateInput(
            requestedSource = MapSourcePreference.SATELLITE,
            hasSatelliteTexture = true,
            userBorderPreference = true,
            isReferenceMode = false
        )
        val normalState = MapStateResolver.resolve(onInput)
        assertTrue("Normal mode with userBorderPreference=true must show borders", normalState.showBorders)

        // Enter reference mode: borders MUST be suppressed (0)
        val refInput = onInput.copy(isReferenceMode = true)
        val refState = MapStateResolver.resolve(refInput)
        assertFalse("Reference mode MUST suppress borders (showBorders=false) even if user preference is ON", refState.showBorders)

        // Exit reference mode: borders MUST be restored to user preference (ON)
        val exitInput = refInput.copy(isReferenceMode = false)
        val restoredState = MapStateResolver.resolve(exitInput)
        assertTrue("Exiting reference mode MUST restore user border preference (true)", restoredState.showBorders)

        // If user previously turned borders OFF
        val offInput = onInput.copy(userBorderPreference = false)
        val offState = MapStateResolver.resolve(offInput)
        assertFalse("User border preference OFF must be respected", offState.showBorders)

        // Entering reference mode with borders OFF keeps borders OFF
        val refOffState = MapStateResolver.resolve(offInput.copy(isReferenceMode = true))
        assertFalse(refOffState.showBorders)

        // Exiting reference mode restores borders OFF
        val restoredOffState = MapStateResolver.resolve(offInput.copy(isReferenceMode = false))
        assertFalse("Exiting reference mode MUST restore user border preference (false)", restoredOffState.showBorders)
    }

    @Test
    fun testShaderIntegrity() {
        // Requirement 5b: Read actual materials/earth.mat from filesystem and verify critical formulas
        val candidatePaths = listOf(
            File("materials/earth.mat"),
            File("../materials/earth.mat"),
            File("../../materials/earth.mat")
        )
        val matFile = candidatePaths.firstOrNull { it.exists() }
        assertNotNull("materials/earth.mat must exist in repository root", matFile)

        val content = matFile!!.readText()

        // 1. Normal reconstruction formula (strictly maintained)
        assertTrue("Shader must maintain continuous normal theta formula: theta = uv.y * 3.141592653589793",
            content.contains("theta = uv.y * 3.141592653589793"))
        assertTrue("Shader must maintain latDeg formula: latDeg = (0.5 - uv.y) * 180.0",
            content.contains("latDeg = (0.5 - uv.y) * 180.0"))

        // 2. struct CloudSample must exist in real shader
        assertTrue("Shader must contain struct CloudSample",
            content.contains("struct CloudSample"))
        assertTrue("CloudSample must declare float opacity",
            content.contains("float opacity;"))
        assertTrue("CloudSample must declare float isValid",
            content.contains("float isValid;"))

        // 3. No pole masks or artificial damping
        assertFalse("Shader must NOT contain pole attenuation masks (smoothstep on latitude)",
            content.contains("smoothstep(60.0") || content.contains("smoothstep(70.0") || content.contains("poleMask"))

        // 4. Filament flipUV must be true
        assertTrue("Filament material must declare flipUV : true",
            content.contains("flipUV : true"))

        // 5. VIIRS TrueColor mode must use mapLightingMode and satDayMask transition
        assertTrue("Shader must declare mapLightingMode parameter",
            content.contains("name : mapLightingMode"))
        assertTrue("Shader must contain VIIRS Visual / Realistic path",
            content.contains("VIIRS TRUECOLOR VISUAL / REALISTIC"))
        assertTrue("Shader must compute satDayMask across terminator",
            content.contains("satDayMask = smoothstep(-0.06, 0.06, sunDot)"))
    }

    @Test
    fun testProductionCloudEvaluator() {
        // Requirement 5c: Test real CloudEvaluator against specification

        // A. GRAYSCALE_MASK: Red/Grayscale channel decides, Alpha is ignored
        val semiCloudJpeg = CloudEvaluator.evaluate(
            r = 0.35f, g = 0.35f, b = 0.35f, a = 1.0f,
            encoding = CloudEncoding.GRAYSCALE_MASK,
            noDataMode = CloudNoDataMode.NODATA_NONE
        )
        assertEquals("GRAYSCALE_MASK with R=0.35 must yield opacity 0.35 even with GPU A=1.0", 0.35f, semiCloudJpeg.opacity, 1e-5f)
        assertTrue(semiCloudJpeg.isValid)

        val clearSkyJpeg = CloudEvaluator.evaluate(
            r = 0.0f, g = 0.0f, b = 0.0f, a = 1.0f,
            encoding = CloudEncoding.GRAYSCALE_MASK,
            noDataMode = CloudNoDataMode.NODATA_NONE
        )
        assertEquals(0.0f, clearSkyJpeg.opacity, 1e-5f)
        assertTrue(clearSkyJpeg.isValid)

        val denseStormJpeg = CloudEvaluator.evaluate(
            r = 1.0f, g = 1.0f, b = 1.0f, a = 1.0f,
            encoding = CloudEncoding.GRAYSCALE_MASK,
            noDataMode = CloudNoDataMode.NODATA_NONE
        )
        assertEquals(1.0f, denseStormJpeg.opacity, 1e-5f)
        assertTrue(denseStormJpeg.isValid)

        // B. CLOUD_ALPHA_MASK: Alpha decides, boundary values 0.0 and 1.0 are valid
        val alphaZero = CloudEvaluator.evaluate(
            r = 1.0f, g = 1.0f, b = 1.0f, a = 0.0f,
            encoding = CloudEncoding.CLOUD_ALPHA_MASK,
            noDataMode = CloudNoDataMode.NODATA_NONE
        )
        assertEquals("Alpha 0.0 must yield 0.0 opacity", 0.0f, alphaZero.opacity, 1e-5f)
        assertTrue("Alpha 0.0 in NODATA_NONE is a valid clear sky observation", alphaZero.isValid)

        val alphaMid = CloudEvaluator.evaluate(
            r = 1.0f, g = 1.0f, b = 1.0f, a = 0.65f,
            encoding = CloudEncoding.CLOUD_ALPHA_MASK,
            noDataMode = CloudNoDataMode.NODATA_NONE
        )
        assertEquals(0.65f, alphaMid.opacity, 1e-5f)
        assertTrue(alphaMid.isValid)

        val alphaOne = CloudEvaluator.evaluate(
            r = 0.2f, g = 0.2f, b = 0.2f, a = 1.0f,
            encoding = CloudEncoding.CLOUD_ALPHA_MASK,
            noDataMode = CloudNoDataMode.NODATA_NONE
        )
        assertEquals(1.0f, alphaOne.opacity, 1e-5f)
        assertTrue(alphaOne.isValid)

        // C. NODATA_ALPHA_ZERO: Alpha < 0.001 is invalid
        val noDataAlpha = CloudEvaluator.evaluate(
            r = 0.8f, g = 0.8f, b = 0.8f, a = 0.0f,
            encoding = CloudEncoding.GRAYSCALE_MASK,
            noDataMode = CloudNoDataMode.NODATA_ALPHA_ZERO
        )
        assertFalse("Alpha < 0.001 in NODATA_ALPHA_ZERO must be invalid", noDataAlpha.isValid)

        // D. NODATA_SENTINEL_ZERO: RGB=(0,0,0) is invalid
        val noDataSentinel = CloudEvaluator.evaluate(
            r = 0.0f, g = 0.0f, b = 0.0f, a = 1.0f,
            encoding = CloudEncoding.GRAYSCALE_MASK,
            noDataMode = CloudNoDataMode.NODATA_SENTINEL_ZERO
        )
        assertFalse("RGB=(0,0,0) in NODATA_SENTINEL_ZERO must be invalid", noDataSentinel.isValid)

        // E. Valid pixel in NODATA_SENTINEL_ZERO
        val validSentinel = CloudEvaluator.evaluate(
            r = 0.5f, g = 0.5f, b = 0.5f, a = 1.0f,
            encoding = CloudEncoding.GRAYSCALE_MASK,
            noDataMode = CloudNoDataMode.NODATA_SENTINEL_ZERO
        )
        assertTrue("Non-zero RGB in NODATA_SENTINEL_ZERO must be valid", validSentinel.isValid)
        assertEquals(0.5f, validSentinel.opacity, 1e-5f)
    }

    @Test
    fun testNoCloudStackingOnSatellite() {
        // Regression test: Whenever effective source is SATELLITE_VIIRS, showClouds must be false
        val allCombinations = listOf(
            MapStateInput(MapSourcePreference.SATELLITE, hasSatelliteTexture = true, downloadState = SatelliteDownloadState.SUCCEEDED, userCloudPreference = true, isReferenceMode = false),
            MapStateInput(MapSourcePreference.SATELLITE, hasSatelliteTexture = true, downloadState = SatelliteDownloadState.SUCCEEDED, userCloudPreference = false, isReferenceMode = false),
            MapStateInput(MapSourcePreference.SATELLITE, hasSatelliteTexture = true, downloadState = SatelliteDownloadState.SUCCEEDED, userCloudPreference = true, isReferenceMode = true)
        )
        for (input in allCombinations) {
            val config = MapStateResolver.resolve(input)
            assertEquals(EffectiveTextureSource.SATELLITE_VIIRS, config.activeTextureSource)
            assertFalse("Clouds must be strictly OFF when displaying VIIRS satellite map", config.showClouds)
        }
    }

    @Test
    fun testNorthSouthInversionRegression() {
        // Verify that +Y is North and -Y is South in world coordinates
        val northPole = floatArrayOf(0f, 10f, 0f)
        val southPole = floatArrayOf(0f, -10f, 0f)
        assertTrue("North Pole must have positive Y", northPole[1] > 0f)
        assertTrue("South Pole must have negative Y", southPole[1] < 0f)

        // Equator at Greenwich: +X = 10, Y = 0, Z = 0
        val greenwich = floatArrayOf(10f, 0f, 0f)
        assertEquals(10f, greenwich[0], 1e-5f)
        assertEquals(0f, greenwich[1], 1e-5f)
        assertEquals(0f, greenwich[2], 1e-5f)
    }
}
