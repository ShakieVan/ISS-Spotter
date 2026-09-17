package de.shakie.iss.orbit

import kotlin.math.*

data class HorizontalCoordinates(
    val azimuthDeg: Double,      // 0..360 (0 = North, 90 = East, 180 = South, 270 = West)
    val elevationDeg: Double,    // -90..+90 (> 0 = above horizon)
    val distanceKm: Double,      // Direct line-of-sight distance
    val isVisibleAboveHorizon: Boolean
)

object TopocentricPosition {
    private const val WGS84_A = 6378.137 // Earth semi-major axis (km)
    private const val WGS84_F = 1.0 / 298.257223563
    private const val WGS84_E2 = 2.0 * WGS84_F - WGS84_F * WGS84_F

    /**
     * Converts geodetic coordinates to WGS84 Earth-Centered Earth-Fixed (ECEF) Cartesian (X, Y, Z in km).
     */
    fun toEcef(latDeg: Double, lonDeg: Double, altKm: Double): DoubleArray {
        val latRad = Math.toRadians(latDeg)
        val lonRad = Math.toRadians(lonDeg)

        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val sinLon = sin(lonRad)
        val cosLon = cos(lonRad)

        val n = WGS84_A / sqrt(1.0 - WGS84_E2 * sinLat * sinLat)

        val x = (n + altKm) * cosLat * cosLon
        val y = (n + altKm) * cosLat * sinLon
        val z = (n * (1.0 - WGS84_E2) + altKm) * sinLat

        return doubleArrayOf(x, y, z)
    }

    /**
     * Calculates the topocentric azimuth, elevation, and slant range of the ISS
     * relative to an observer on the Earth surface.
     */
    fun calculate(
        obsLatDeg: Double,
        obsLonDeg: Double,
        obsAltKm: Double = 0.05,
        issLatDeg: Double,
        issLonDeg: Double,
        issAltKm: Double
    ): HorizontalCoordinates {
        val obsEcef = toEcef(obsLatDeg, obsLonDeg, obsAltKm)
        val issEcef = toEcef(issLatDeg, issLonDeg, issAltKm)

        val dx = issEcef[0] - obsEcef[0]
        val dy = issEcef[1] - obsEcef[1]
        val dz = issEcef[2] - obsEcef[2]

        val latRad = Math.toRadians(obsLatDeg)
        val lonRad = Math.toRadians(obsLonDeg)

        val sinLat = sin(latRad)
        val cosLat = cos(latRad)
        val sinLon = sin(lonRad)
        val cosLon = cos(lonRad)

        // Convert to East, North, Up (ENU) frame
        val east = -sinLon * dx + cosLon * dy
        val north = -sinLat * cosLon * dx - sinLat * sinLon * dy + cosLat * dz
        val up = cosLat * cosLon * dx + cosLat * sinLon * dy + sinLat * dz

        val range = sqrt(east * east + north * north + up * up)
        val elevationRad = asin((up / range).coerceIn(-1.0, 1.0))
        var azimuthRad = atan2(east, north)
        if (azimuthRad < 0) azimuthRad += 2.0 * Math.PI

        val azDeg = Math.toDegrees(azimuthRad)
        val elDeg = Math.toDegrees(elevationRad)

        return HorizontalCoordinates(
            azimuthDeg = azDeg,
            elevationDeg = elDeg,
            distanceKm = range,
            isVisibleAboveHorizon = elDeg > 0.0
        )
    }
}
