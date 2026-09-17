package de.shakie.iss

import de.shakie.iss.orbit.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

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
        // Velocity must be around 27,500..27,750 km/h
        assertTrue("Velocity within orbital bounds: ${state.velocityKmh}", state.velocityKmh in 27000.0..28000.0)
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
}
