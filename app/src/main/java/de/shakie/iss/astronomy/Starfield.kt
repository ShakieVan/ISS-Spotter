package de.shakie.iss.astronomy

import android.graphics.*
import kotlin.math.*

data class Star(
    val name: String,
    val raHours: Double,    // Right ascension in hours (0..24)
    val decDeg: Double,     // Declination in degrees (-90..+90)
    val magnitude: Double   // Visual magnitude (e.g. Sirius = -1.46, Vega = 0.03)
)

data class Constellation(
    val name: String,
    val lines: List<Pair<String, String>>, // Star name pairs
    val labelStar: String                  // Star near which to show label
)

object CelestialCatalog {

    val stars = listOf(
        // Ursa Major (Großer Wagen / Bär)
        Star("Dubhe", 11.062, 61.75, 1.79),
        Star("Merak", 11.031, 56.38, 2.37),
        Star("Phecda", 11.897, 53.69, 2.44),
        Star("Megrez", 12.257, 57.03, 3.31),
        Star("Alioth", 12.900, 55.96, 1.77),
        Star("Mizar", 13.399, 54.92, 2.23),
        Star("Alkaid", 13.792, 49.31, 1.86),

        // Ursa Minor (Kleiner Wagen mit Polarstern)
        Star("Polaris", 2.530, 89.26, 1.98),
        Star("Kochab", 14.845, 74.16, 2.08),
        Star("Pherkad", 15.346, 71.83, 3.05),

        // Cassiopeia (Kassiopeia - Himmels-W)
        Star("Schedar", 0.675, 56.54, 2.24),
        Star("Caph", 0.153, 59.15, 2.27),
        Star("Gamma Cas", 0.945, 60.72, 2.47),
        Star("Ruchbah", 1.428, 60.23, 2.68),
        Star("Segin", 1.907, 63.67, 3.37),

        // Orion (Winterhimmel / Äquator)
        Star("Beteigeuze", 5.919, 7.41, 0.50),
        Star("Rigel", 5.242, -8.20, 0.13),
        Star("Bellatrix", 5.419, 6.35, 1.64),
        Star("Saiph", 5.796, -9.67, 2.07),
        Star("Alnitak", 5.679, -1.94, 1.77),
        Star("Alnilam", 5.604, -1.20, 1.69),
        Star("Mintaka", 5.533, -0.30, 2.23),

        // Cygnus (Schwan / Sommerdreieck)
        Star("Deneb", 20.690, 45.28, 1.25),
        Star("Albireo", 19.512, 27.96, 3.05),
        Star("Sadr", 20.373, 40.26, 2.23),
        Star("Gienah", 20.770, 33.97, 2.46),
        Star("Rukh", 19.749, 45.13, 2.87),

        // Lyra (Leier)
        Star("Wega", 18.616, 38.78, 0.03),
        Star("Sheliak", 18.835, 33.36, 3.52),
        Star("Sulafat", 18.982, 32.69, 3.25),

        // Aquila (Adler)
        Star("Atair", 19.846, 8.87, 0.77),
        Star("Tarazed", 19.771, 10.61, 2.72),
        Star("Alshain", 19.921, 6.41, 3.71),

        // Taurus (Stier)
        Star("Aldebaran", 4.599, 16.51, 0.85),
        Star("Elnath", 5.438, 28.61, 1.65),

        // Gemini (Zwillinge)
        Star("Pollux", 7.755, 28.03, 1.14),
        Star("Castor", 7.577, 31.89, 1.58),
        Star("Alhena", 6.629, 16.40, 1.93),

        // Leo (Löwe)
        Star("Regulus", 10.139, 11.97, 1.35),
        Star("Algieba", 10.333, 19.84, 2.08),
        Star("Denebola", 11.818, 14.57, 2.14),
        Star("Zosma", 11.235, 20.52, 2.56),

        // Major Landmark Stars
        Star("Sirius", 6.752, -16.72, -1.46),
        Star("Prokyon", 7.653, 5.22, 0.38),
        Star("Arktur", 14.261, 19.18, -0.05),
        Star("Spica", 13.420, -11.16, 0.98),
        Star("Capella", 5.278, 45.99, 0.08),
        Star("Antares", 16.490, -26.43, 1.09)
    )

    val constellations = listOf(
        Constellation(
            name = "Großer Wagen",
            lines = listOf(
                "Dubhe" to "Merak",
                "Merak" to "Phecda",
                "Phecda" to "Megrez",
                "Megrez" to "Dubhe",
                "Megrez" to "Alioth",
                "Alioth" to "Mizar",
                "Mizar" to "Alkaid"
            ),
            labelStar = "Dubhe"
        ),
        Constellation(
            name = "Kleiner Wagen",
            lines = listOf(
                "Polaris" to "Kochab",
                "Kochab" to "Pherkad"
            ),
            labelStar = "Polaris"
        ),
        Constellation(
            name = "Kassiopeia",
            lines = listOf(
                "Caph" to "Schedar",
                "Schedar" to "Gamma Cas",
                "Gamma Cas" to "Ruchbah",
                "Ruchbah" to "Segin"
            ),
            labelStar = "Schedar"
        ),
        Constellation(
            name = "Orion",
            lines = listOf(
                "Beteigeuze" to "Bellatrix",
                "Bellatrix" to "Rigel",
                "Rigel" to "Saiph",
                "Saiph" to "Beteigeuze",
                "Alnitak" to "Alnilam",
                "Alnilam" to "Mintaka"
            ),
            labelStar = "Beteigeuze"
        ),
        Constellation(
            name = "Schwan",
            lines = listOf(
                "Deneb" to "Sadr",
                "Sadr" to "Albireo",
                "Rukh" to "Sadr",
                "Sadr" to "Gienah"
            ),
            labelStar = "Deneb"
        ),
        Constellation(
            name = "Leier",
            lines = listOf(
                "Wega" to "Sheliak",
                "Sheliak" to "Sulafat",
                "Sulafat" to "Wega"
            ),
            labelStar = "Wega"
        ),
        Constellation(
            name = "Löwe",
            lines = listOf(
                "Regulus" to "Algieba",
                "Algieba" to "Zosma",
                "Zosma" to "Denebola",
                "Denebola" to "Regulus"
            ),
            labelStar = "Regulus"
        ),
        Constellation(
            name = "Zwillinge",
            lines = listOf(
                "Castor" to "Pollux",
                "Pollux" to "Alhena"
            ),
            labelStar = "Pollux"
        )
    )
}

class SkyRenderer {

    private val starMap = mutableMapOf<String, StarPoint>()

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    private val constellationLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f
        color = Color.argb(85, 120, 220, 255) // Subtle celestial blue
        pathEffect = DashPathEffect(floatArrayOf(8f, 4f), 0f)
    }

    private val constellationTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(175, 160, 230, 255)
        textSize = 21f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        letterSpacing = 0.05f
    }

    data class StarPoint(
        val x: Float,
        val y: Float,
        val radius: Float,
        val alpha: Int,
        val isVisible: Boolean
    )

    /**
     * Projects and renders the real seasonal starry sky and constellations.
     */
    fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        centerX: Float,
        centerY: Float,
        hfov: Float,
        vfov: Float,
        deviceAzimuth: Float,
        devicePitch: Float,
        obsLatDeg: Double,
        obsLonDeg: Double,
        timeMillis: Long = System.currentTimeMillis()
    ) {
        starMap.clear()

        // 1. Calculate Local Sidereal Time (LST)
        val jd = 2440587.5 + timeMillis / 86400000.0
        val d = jd - 2451545.0
        val gmstDeg = (280.46061837 + 360.98564736629 * d).mod(360.0)
        val lstDeg = (gmstDeg + obsLonDeg).mod(360.0)
        val obsLatRad = Math.toRadians(obsLatDeg)

        // 2. Compute Topocentric Horizontal Coordinates (Azimuth & Elevation) for each star
        for (star in CelestialCatalog.stars) {
            val raDeg = star.raHours * 15.0
            val hourAngleDeg = (lstDeg - raDeg).mod(360.0)
            val haRad = Math.toRadians(hourAngleDeg)
            val decRad = Math.toRadians(star.decDeg)

            // Elevation
            val sinAlt = sin(obsLatRad) * sin(decRad) + cos(obsLatRad) * cos(decRad) * cos(haRad)
            val altRad = asin(sinAlt.coerceIn(-1.0, 1.0))
            val altDeg = Math.toDegrees(altRad)

            // Azimuth
            var azRad = atan2(-cos(decRad) * sin(haRad), sin(decRad) * cos(obsLatRad) - cos(decRad) * sin(obsLatRad) * cos(haRad))
            if (azRad < 0) azRad += 2.0 * Math.PI
            val azDeg = Math.toDegrees(azRad)

            // Screen projection relative to device bearing & pitch
            var deltaAz = (azDeg.toFloat() - deviceAzimuth)
            while (deltaAz > 180f) deltaAz -= 360f
            while (deltaAz < -180f) deltaAz += 360f

            val deltaEl = (altDeg.toFloat() - devicePitch)

            val screenX = centerX + (deltaAz / (hfov / 2f)) * (w * 0.45f)
            val screenY = centerY - (deltaEl / (vfov / 2f)) * (h * 0.45f)

            // Magnitude to brightness & radius
            val baseRadius = (3.8 - star.magnitude * 0.7).coerceIn(1.2, 5.0).toFloat()
            val alpha = (255 - star.magnitude * 28.0).coerceIn(90.0, 255.0).toInt()

            val isInScreen = screenX in -50f..(w + 50f) && screenY in -50f..(h + 50f) && altDeg >= -5.0
            starMap[star.name] = StarPoint(screenX, screenY, baseRadius, alpha, isInScreen)
        }

        // 3. Draw Constellation Lines
        for (constellation in CelestialCatalog.constellations) {
            for ((s1, s2) in constellation.lines) {
                val p1 = starMap[s1] ?: continue
                val p2 = starMap[s2] ?: continue
                if (p1.isVisible || p2.isVisible) {
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, constellationLinePaint)
                }
            }

            // Draw Constellation Name Label
            val labelPoint = starMap[constellation.labelStar]
            if (labelPoint != null && labelPoint.isVisible) {
                canvas.drawText(constellation.name, labelPoint.x + 12f, labelPoint.y - 12f, constellationTextPaint)
            }
        }

        // 4. Draw Stars
        for (point in starMap.values) {
            if (point.isVisible) {
                starPaint.alpha = point.alpha
                canvas.drawCircle(point.x, point.y, point.radius, starPaint)
            }
        }
    }
}
