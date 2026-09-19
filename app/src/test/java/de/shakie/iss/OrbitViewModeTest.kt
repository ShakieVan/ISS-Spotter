package de.shakie.iss

import de.shakie.iss.graphics.*
import org.junit.Test

class OrbitViewModeTest {
    @Test fun cyclesThroughFourViews() {
        var mode = OrbitViewMode.MAP
        for (expected in listOf(OrbitViewMode.CLOUDS_OFF, OrbitViewMode.SATELLITE,
            OrbitViewMode.REFERENCE, OrbitViewMode.MAP)) {
            mode = mode.next()
            check(mode == expected)
        }
    }

    @Test fun cloudsOffUsesTheBaseMapNotASatelliteImage() {
        val mode = OrbitViewMode.CLOUDS_OFF
        check(mode.source == MapSourcePreference.BLUE_MARBLE)
        check(!mode.clouds && !mode.reference)
    }

    @Test fun pendingSatelliteDoesNotChangeTheSelectedStep() {
        // The selected step uses the REQUESTED source, never a temporary fallback texture.
        check(OrbitViewMode.fromState(MapSourcePreference.SATELLITE, false, false).next() == OrbitViewMode.REFERENCE)
        check(OrbitViewMode.fromState(MapSourcePreference.SATELLITE, false, true).next() == OrbitViewMode.MAP)
    }

    @Test fun allModeConfigurationsRoundTrip() {
        for (mode in OrbitViewMode.values()) {
            check(OrbitViewMode.fromState(mode.source, mode.clouds, mode.reference) == mode)
        }
        check(OrbitViewMode.MAP.clouds)
        check(!OrbitViewMode.SATELLITE.clouds && !OrbitViewMode.REFERENCE.clouds)
    }
}
