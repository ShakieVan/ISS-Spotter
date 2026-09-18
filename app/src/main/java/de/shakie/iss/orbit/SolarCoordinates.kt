package de.shakie.iss.orbit

import kotlin.math.*

data class SunPosition(
    val latitude: Double,   // Degrees [-90..90]
    val longitude: Double,  // Degrees [-180..180]
    val vectorX: Float,     // Unit vector in ECEF frame
    val vectorY: Float,
    val vectorZ: Float
)

object SolarCoordinates {
    /**
     * Calculates the subsolar geographic point and ECEF unit vector towards the Sun
     * for a given UTC timestamp in milliseconds.
     */
    fun calculate(timeMillis: Long = System.currentTimeMillis()): SunPosition {
        val jd = 2440587.5 + timeMillis / 86400000.0
        val n = jd - 2451545.0 // Days since J2000.0

        val l = (280.460 + 0.9856474 * n).mod(360.0)
        val g = Math.toRadians((357.528 + 0.9856003 * n).mod(360.0))

        val lambda = Math.toRadians(l + 1.915 * sin(g) + 0.020 * sin(2.0 * g))
        val epsilon = Math.toRadians(23.439 - 0.0000004 * n)

        val sinLambda = sin(lambda)
        val cosLambda = cos(lambda)
        val sinEps = sin(epsilon)
        val cosEps = cos(epsilon)

        val alpha = atan2(cosEps * sinLambda, cosLambda) // Right ascension (rad)
        val delta = asin(sinEps * sinLambda)             // Declination (rad)

        // Greenwich Mean Sidereal Time in degrees
        val gmst = (280.46061837 + 360.98564736629 * n).mod(360.0)
        val alphaDeg = Math.toDegrees(alpha).mod(360.0)

        var sunLon = (alphaDeg - gmst).mod(360.0)
        if (sunLon > 180.0) sunLon -= 360.0
        if (sunLon < -180.0) sunLon += 360.0

        val sunLat = Math.toDegrees(delta)

        // Unit direction vector
        val latRad = Math.toRadians(sunLat)
        val lonRad = Math.toRadians(sunLon)

        val vx = (cos(latRad) * cos(lonRad)).toFloat()
        val vy = sin(latRad).toFloat()
        val vz = (-cos(latRad) * sin(lonRad)).toFloat()

        return SunPosition(
            latitude = sunLat,
            longitude = sunLon,
            vectorX = vx,
            vectorY = vy,
            vectorZ = vz
        )
    }
}
