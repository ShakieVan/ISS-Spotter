package de.shakie.iss.orbit

import kotlin.math.*

data class Tle(
    val satNumber: Int,
    val epochYear: Int,
    val epochDay: Double,
    val bstar: Double,
    val inclinationDeg: Double,
    val raanDeg: Double,
    val eccentricity: Double,
    val argPerigeeDeg: Double,
    val meanAnomalyDeg: Double,
    val meanMotionRevsPerDay: Double,
    val revNumber: Int
)

data class OrbitState(
    val latitudeDeg: Double,
    val longitudeDeg: Double,
    val altitudeKm: Double,
    val velocityKmh: Double,
    val timeMillis: Long
)

class Sgp4Propagator(private val tle: Tle) {

    // Constants
    private val mu = 398600.4418      // Earth gravitational parameter (km^3/s^2)
    private val earthRadiusKm = 6378.137
    private val j2 = 0.00108262998905 // Earth J2 perturbation
    private val twoPi = 2.0 * Math.PI

    // Epoch timestamp in milliseconds UTC
    val epochMillis: Long

    init {
        val fullYear = if (tle.epochYear < 57) 2000 + tle.epochYear else 1900 + tle.epochYear
        // Calculate milliseconds for Jan 1 of fullYear + epochDay - 1
        val isLeap = (fullYear % 4 == 0 && fullYear % 100 != 0) || (fullYear % 400 == 0)
        var daysBeforeYear = 0L
        for (y in 1970 until fullYear) {
            val leap = (y % 4 == 0 && y % 100 != 0) || (y % 400 == 0)
            daysBeforeYear += if (leap) 366 else 365
        }
        val dayMillis = (tle.epochDay - 1.0) * 86400000.0
        epochMillis = (daysBeforeYear * 86400000.0 + dayMillis).toLong()
    }

    /**
     * Propagates orbit to the given UTC time in milliseconds.
     */
    fun propagate(timeMillis: Long = System.currentTimeMillis()): OrbitState {
        val deltaMin = (timeMillis - epochMillis) / 60000.0 // Minutes from epoch

        // Mean motion in rad/min
        val n0 = tle.meanMotionRevsPerDay * twoPi / 1440.0
        // Semi-major axis a in km (Kepler's 3rd law: a = (mu / n^2)^(1/3))
        // Convert n to rad/s for semi-major axis calculation
        val nSec = tle.meanMotionRevsPerDay * twoPi / 86400.0
        val a0 = (mu / (nSec * nSec)).pow(1.0 / 3.0)

        val e0 = tle.eccentricity
        val incRad = Math.toRadians(tle.inclinationDeg)
        val raan0Rad = Math.toRadians(tle.raanDeg)
        val argp0Rad = Math.toRadians(tle.argPerigeeDeg)
        val m0Rad = Math.toRadians(tle.meanAnomalyDeg)

        // J2 Secular rates of change
        val p = a0 * (1.0 - e0 * e0)
        val theta = cos(incRad)
        val factor = 1.5 * j2 * (earthRadiusKm / p).pow(2.0) * n0

        val raanDot = -factor * theta                     // rad/min
        val argpDot = factor * (2.0 - 2.5 * sin(incRad).pow(2.0)) // rad/min
        val nDot = 0.0 // Drag adjustment from BSTAR could be added here

        // Propagated orbital elements
        val raan = (raan0Rad + raanDot * deltaMin).mod(twoPi)
        val argp = (argp0Rad + argpDot * deltaMin).mod(twoPi)
        val m = (m0Rad + n0 * deltaMin).mod(twoPi)

        // Solve Kepler's equation for Eccentric Anomaly E: M = E - e*sin(E)
        var e = m
        for (iter in 0 until 12) {
            val deltaE = (m - (e - e0 * sin(e))) / (1.0 - e0 * cos(e))
            e += deltaE
            if (abs(deltaE) < 1e-9) break
        }

        // True anomaly nu
        val sinNu = (sqrt(1.0 - e0 * e0) * sin(e)) / (1.0 - e0 * cos(e))
        val cosNu = (cos(e) - e0) / (1.0 - e0 * cos(e))
        val nu = atan2(sinNu, cosNu)

        // Orbital radius r in km
        val r = a0 * (1.0 - e0 * cos(e))

        // Position in orbital plane
        val u = (argp + nu).mod(twoPi) // Argument of latitude
        val xOrb = r * cos(u)
        val yOrb = r * sin(u)

        // Transform to Earth-Centered Inertial (ECI) coordinates
        val cosRaan = cos(raan)
        val sinRaan = sin(raan)
        val cosInc = cos(incRad)
        val sinInc = sin(incRad)

        val xEci = xOrb * cosRaan - yOrb * cosInc * sinRaan
        val yEci = xOrb * sinRaan + yOrb * cosInc * cosRaan
        val zEci = yOrb * sinInc

        // Greenwich Mean Sidereal Time (GMST) for ECI to ECEF rotation
        val jd = 2440587.5 + timeMillis / 86400000.0
        val tJ2000 = jd - 2451545.0
        val gmstDeg = (280.46061837 + 360.98564736629 * tJ2000).mod(360.0)
        val gmstRad = Math.toRadians(gmstDeg)

        // Rotate ECI to ECEF around Z-axis by -GMST
        val cosGmst = cos(gmstRad)
        val sinGmst = sin(gmstRad)

        val xEcef = xEci * cosGmst + yEci * sinGmst
        val yEcef = -xEci * sinGmst + yEci * cosGmst
        val zEcef = zEci

        // Convert ECEF to geodetic Latitude, Longitude, Altitude
        val lonRad = atan2(yEcef, xEcef)
        val pDist = sqrt(xEcef * xEcef + yEcef * yEcef)
        var latRad = atan2(zEcef, pDist)

        // Iterative refinement for WGS84 flattening
        val e2 = 0.00669437999014
        for (i in 0 until 4) {
            val n = earthRadiusKm / sqrt(1.0 - e2 * sin(latRad) * sin(latRad))
            latRad = atan2(zEcef + e2 * n * sin(latRad), pDist)
        }

        val n = earthRadiusKm / sqrt(1.0 - e2 * sin(latRad) * sin(latRad))
        val altKm = (pDist / cos(latRad)) - n

        var lonDeg = Math.toDegrees(lonRad)
        if (lonDeg > 180.0) lonDeg -= 360.0
        if (lonDeg < -180.0) lonDeg += 360.0
        val latDeg = Math.toDegrees(latRad)

        // Orbital velocity: v = sqrt(mu * (2/r - 1/a))
        val vKms = sqrt(mu * (2.0 / r - 1.0 / a0))
        val vKmh = vKms * 3600.0

        return OrbitState(
            latitudeDeg = latDeg,
            longitudeDeg = lonDeg,
            altitudeKm = altKm,
            velocityKmh = vKmh,
            timeMillis = timeMillis
        )
    }

    companion object {
        /**
         * Parses two standard TLE lines.
         */
        fun parseTle(line1: String, line2: String): Tle {
            val clean1 = line1.trim()
            val clean2 = line2.trim()

            val satNumber = clean1.substring(2, 7).trim().toInt()
            val epochYear = clean1.substring(18, 20).trim().toInt()
            val epochDay = clean1.substring(20, 32).trim().toDouble()

            // BSTAR drag term: e.g. 13566-3 -> 0.13566e-3
            val bstarStr = clean1.substring(53, 61).trim()
            val bstar = parseExponential(bstarStr)

            val inclinationDeg = clean2.substring(8, 16).trim().toDouble()
            val raanDeg = clean2.substring(17, 25).trim().toDouble()
            val eccStr = "0." + clean2.substring(26, 33).trim()
            val eccentricity = eccStr.toDouble()
            val argPerigeeDeg = clean2.substring(34, 42).trim().toDouble()
            val meanAnomalyDeg = clean2.substring(43, 51).trim().toDouble()
            val meanMotion = clean2.substring(52, 63).trim().toDouble()
            val revNumber = clean2.substring(63, 68).trim().toInt()

            return Tle(
                satNumber = satNumber,
                epochYear = epochYear,
                epochDay = epochDay,
                bstar = bstar,
                inclinationDeg = inclinationDeg,
                raanDeg = raanDeg,
                eccentricity = eccentricity,
                argPerigeeDeg = argPerigeeDeg,
                meanAnomalyDeg = meanAnomalyDeg,
                meanMotionRevsPerDay = meanMotion,
                revNumber = revNumber
            )
        }

        private fun parseExponential(str: String): Double {
            val clean = str.replace(" ", "")
            if (clean.isEmpty() || clean == "00000-0" || clean == "0") return 0.0
            val mantissaSign = if (clean.startsWith("-")) -1.0 else 1.0
            val unsigned = clean.removePrefix("+").removePrefix("-")
            val expIndex = unsigned.indexOfAny(charArrayOf('-', '+'))
            if (expIndex == -1) return unsigned.toDoubleOrNull() ?: 0.0

            val mantissaPart = unsigned.substring(0, expIndex)
            val expPart = unsigned.substring(expIndex)
            val mantissa = ("0." + mantissaPart).toDoubleOrNull() ?: 0.0
            val exp = expPart.toIntOrNull() ?: 0
            return mantissaSign * mantissa * 10.0.pow(exp.toDouble())
        }
    }
}
