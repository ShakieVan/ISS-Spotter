package de.shakie.iss.orbit

import kotlin.math.*

object EclipseCalculator {
    const val EARTH_RADIUS_KM = 6371.0
    // The dense lower atmosphere (troposphere & stratosphere) completely extinguishes grazing sun rays
    // at tangent altitudes below ~16 km. The Sun's ~0.53° angular disc subtends ~32 km at LEO orbital distances.
    const val UMBRA_RADIUS_KM = EARTH_RADIUS_KM + 16.0
    const val PENUMBRA_RADIUS_KM = UMBRA_RADIUS_KM + 32.0

    /**
     * Calculates the solar illumination factor on the ISS.
     * @return 1.0f for full sunlight, 0.0f for complete umbra (Earth shadow),
     *         and (0.0f..1.0f) during penumbral sunset/sunrise.
     */
    fun getSunlightFactor(
        issLatDeg: Double,
        issLonDeg: Double,
        issAltKm: Double,
        sun: SunPosition
    ): Float {
        val r = EARTH_RADIUS_KM + issAltKm
        val latRad = Math.toRadians(issLatDeg)
        val lonRad = Math.toRadians(issLonDeg)

        // ISS position vector in ECEF (matching Filament world frame: +Y=North, +X=Greenwich, -Z=East, +Z=West)
        val px = r * cos(latRad) * cos(lonRad)
        val py = r * sin(latRad)
        val pz = -r * cos(latRad) * sin(lonRad)

        // Dot product with sun unit vector
        val dot = px * sun.vectorX + py * sun.vectorY + pz * sun.vectorZ

        // If in front of Earth towards the Sun, full daylight
        if (dot >= 0.0) {
            return 1.0f
        }

        // Distance perpendicular to Earth-Sun axis
        val perpX = px - dot * sun.vectorX
        val perpY = py - dot * sun.vectorY
        val perpZ = pz - dot * sun.vectorZ
        val dPerp = sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ)

        // Compare against physical atmospheric extinction boundaries
        if (dPerp >= PENUMBRA_RADIUS_KM) {
            return 1.0f
        }
        if (dPerp <= UMBRA_RADIUS_KM) {
            return 0.0f
        }

        // Smooth cubic transition across penumbra
        val t = (dPerp - UMBRA_RADIUS_KM) / (PENUMBRA_RADIUS_KM - UMBRA_RADIUS_KM)
        return (t * t * (3.0 - 2.0 * t)).toFloat().coerceIn(0.0f, 1.0f)
    }
}
