package de.shakie.iss.graphics

import android.content.Context
import android.graphics.*
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import de.shakie.iss.astronomy.CelestialCatalog
import kotlin.math.*

class IssGlobeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        isClickable = false
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    private var bordersVisible: Boolean = true
    private var zoomFactor: Float = 1.0f
    private var cameraPose: FloatArray? = null
    private var aspect: Float = 1.0f
    private var fovYDeg: Float = 42.0f

    // Reusable matrices & vectors to avoid GC allocations in render loop
    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val worldPos = FloatArray(4)
    private val clipPos = FloatArray(4)

    private val sortedCountries = CountryCatalog.countries.sortedBy { it.importance }
    private val drawnX = FloatArray(64)
    private val drawnY = FloatArray(64)

    private val density = resources.displayMetrics.density

    // Paints
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 8, 20)
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.06f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.06f
    }

    private val textPaintMinor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E1F5FE")
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 0f, Color.parseColor("#00B0FF"))
    }

    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 10, 25)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }

    private var sunDir: FloatArray? = null
    private var inSunlight: Boolean = true

    private val flareCorePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flareStreakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val flareGhostPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    var diagnosticMarkersVisible: Boolean = false
    var issSubPointLatLon: Pair<Double, Double>? = null

    data class DiagnosticPoint(
        val name: String,
        val latDeg: Double,
        val lonDeg: Double,
        val colorHex: String
    )

    private val diagnosticPoints = listOf(
        DiagnosticPoint("NORDPOL (+90°, 0°)", 90.0, 0.0, "#FF3366"),
        DiagnosticPoint("SÜDPOL (-90°, 0°)", -90.0, 0.0, "#FF3366"),
        DiagnosticPoint("ÄQUATOR / GREENWICH (0°, 0°)", 0.0, 0.0, "#00FFCC"),
        DiagnosticPoint("ÄQUATOR / 90° OST (0°, +90°)", 0.0, 90.0, "#FFCC00"),
        DiagnosticPoint("ÄQUATOR / 90° WEST (0°, -90°)", 0.0, -90.0, "#FF9900"),
        DiagnosticPoint("BERLIN (52.5° N, 13.4° O)", 52.52, 13.405, "#33CCFF"),
        DiagnosticPoint("RIO DE JANEIRO (-22.9° S, 43.2° W)", -22.9, -43.2, "#33CCFF")
    )

    private val diagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }
    private val diagFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val diagTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }

    data class ConstellationLabel(
        val name: String,
        val x: Float,
        val y: Float,
        val z: Float
    )

    private val constellationLabels: List<ConstellationLabel> by lazy {
        val radius = 67.5f
        val starCoords = mutableMapOf<String, FloatArray>()
        for (star in CelestialCatalog.stars) {
            val raRad = Math.toRadians(star.raHours * 15.0).toFloat()
            val decRad = Math.toRadians(star.decDeg).toFloat()
            val nx = cos(decRad) * cos(raRad)
            val ny = sin(decRad)
            val nz = cos(decRad) * sin(raRad)
            starCoords[star.name] = floatArrayOf(radius * nx, radius * ny, radius * nz)
        }

        CelestialCatalog.constellations.map { c ->
            val uniqueStars = mutableSetOf<String>()
            for ((s1, s2) in c.lines) {
                uniqueStars.add(s1)
                uniqueStars.add(s2)
            }
            if (uniqueStars.isEmpty()) uniqueStars.add(c.labelStar)
            var avgX = 0f
            var avgY = 0f
            var avgZ = 0f
            for (sName in uniqueStars) {
                val pt = starCoords[sName] ?: continue
                avgX += pt[0]
                avgY += pt[1]
                avgZ += pt[2]
            }
            val count = uniqueStars.size.coerceAtLeast(1)
            avgX /= count
            avgY /= count
            avgZ /= count
            val centerLen = sqrt(avgX * avgX + avgY * avgY + avgZ * avgZ).coerceAtLeast(0.001f)
            ConstellationLabel(
                name = c.name,
                x = (avgX / centerLen) * radius,
                y = (avgY / centerLen) * radius,
                z = (avgZ / centerLen) * radius
            )
        }
    }

    private val constellationTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BAE6FD")
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }

    private val constellationOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 5, 12, 28)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.08f
    }

    fun setLabelsVisible(visible: Boolean) {
        bordersVisible = visible
        postInvalidateOnAnimation()
    }

    fun updateCamera(
        camPose: FloatArray,
        viewportAspect: Float,
        fovY: Float,
        zoom: Float,
        isBordersVisible: Boolean,
        sunDirection: FloatArray? = null,
        isInSunlight: Boolean = true
    ) {
        cameraPose = camPose
        aspect = viewportAspect
        fovYDeg = fovY
        zoomFactor = zoom
        bordersVisible = isBordersVisible
        sunDir = sunDirection
        inSunlight = isInSunlight
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val pose = cameraPose ?: return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val eyeX = pose[0]
        val eyeY = pose[1]
        val eyeZ = pose[2]
        val targetX = pose[3]
        val targetY = pose[4]
        val targetZ = pose[5]
        val upX = pose[6]
        val upY = pose[7]
        val upZ = pose[8]

        // 1. Build View-Projection Matrix
        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, targetX, targetY, targetZ, upX, upY, upZ)
        Matrix.perspectiveM(projMatrix, 0, fovYDeg, aspect, 0.1f, 150.0f)
        Matrix.multiplyMM(vpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // 2. Optical Screen-Space Lens Flare (when Sun is in view and not eclipsed by Earth)
        drawOpticalLensFlare(canvas, eyeX, eyeY, eyeZ, w, h)

        // 3. Constellation Names (always in view when looking at the sky, crisp and upright)
        drawConstellationLabels(canvas, eyeX, eyeY, eyeZ, w, h)

        // 4. Diagnostic Coordinate Markers (Pole, Equator, ISS sub-satellite point)
        if (diagnosticMarkersVisible) {
            drawDiagnosticMarkers(canvas, eyeX, eyeY, eyeZ, w, h)
        }

        if (!bordersVisible) return

        val earthRadius = 10.0f
        val majorTextSize = (13f * density).coerceIn(24f, 42f)
        val minorTextSize = (10.5f * density).coerceIn(20f, 32f)

        outlinePaint.textSize = majorTextSize
        textPaint.textSize = majorTextSize
        textPaintMinor.textSize = minorTextSize

        val maxImportance = when {
            zoomFactor > 1.8f -> 1 // Zoomed out: Major nations only
            zoomFactor > 0.9f -> 2 // Normal orbit view: Major + Medium nations
            zoomFactor > 0.5f -> 3 // Closer: include smaller nations
            else -> 4              // Very close: all nations
        }

        var drawnCount = 0
        val minSpacing = 38f * density
        val minSpacingSq = minSpacing * minSpacing

        // 2. Iterate countries and project to Screen Space
        for (country in sortedCountries) {
            if (country.importance > maxImportance) continue

            val latRad = Math.toRadians(country.lat.toDouble()).toFloat()
            val lonRad = Math.toRadians(country.lon.toDouble()).toFloat()

            val x = earthRadius * cos(latRad) * cos(lonRad)
            val y = earthRadius * sin(latRad)
            val z = -earthRadius * cos(latRad) * sin(lonRad)

            // Horizon curvature culling
            val nx = x / earthRadius
            val ny = y / earthRadius
            val nz = z / earthRadius

            val toEyeX = eyeX - x
            val toEyeY = eyeY - y
            val toEyeZ = eyeZ - z
            val toEyeLen = sqrt(toEyeX * toEyeX + toEyeY * toEyeY + toEyeZ * toEyeZ).coerceAtLeast(0.001f)
            val dotFacing = (nx * toEyeX + ny * toEyeY + nz * toEyeZ) / toEyeLen

            // Visible if normal points towards camera (geometric horizon is dotFacing = 0)
            if (dotFacing < 0.02f) continue

            // 3. Project to Clip Space
            worldPos[0] = x
            worldPos[1] = y
            worldPos[2] = z
            worldPos[3] = 1.0f
            Matrix.multiplyMV(clipPos, 0, vpMatrix, 0, worldPos, 0)

            val clipW = clipPos[3]
            if (clipW <= 0.1f) continue

            val ndcX = clipPos[0] / clipW
            val ndcY = clipPos[1] / clipW

            // Viewport bounds culling
            if (ndcX < -0.98f || ndcX > 0.98f || ndcY < -0.98f || ndcY > 0.98f) continue

            val screenX = (ndcX * 0.5f + 0.5f) * w
            val screenY = (1.0f - (ndcY * 0.5f + 0.5f)) * h

            // Avoid overlapping labels on screen
            var collides = false
            for (k in 0 until drawnCount) {
                val dx = screenX - drawnX[k]
                val dy = screenY - drawnY[k]
                if (dx * dx + dy * dy < minSpacingSq) {
                    collides = true
                    break
                }
            }
            if (collides) continue

            if (drawnCount < drawnX.size) {
                drawnX[drawnCount] = screenX
                drawnY[drawnCount] = screenY
                drawnCount++
            }

            // 4. Draw native vector marker and sharp text
            val isMajor = country.importance == 1
            val paint = if (isMajor) textPaint else textPaintMinor
            val markerRadius = if (isMajor) 3.5f * density else 2.5f * density

            // Marker dot with dark outline
            canvas.drawCircle(screenX, screenY, markerRadius + 1f * density, dotBorderPaint)
            canvas.drawCircle(screenX, screenY, markerRadius, dotPaint)

            // Razor-sharp label text with dark outline stroke for contrast
            outlinePaint.textSize = if (isMajor) majorTextSize else minorTextSize
            outlinePaint.strokeWidth = if (isMajor) 3.5f * density else 2.8f * density
            canvas.drawText(country.nameDe, screenX, screenY - 6f * density, outlinePaint)
            canvas.drawText(country.nameDe, screenX, screenY - 6f * density, paint)
        }
    }

    private fun drawConstellationLabels(
        canvas: Canvas,
        eyeX: Float, eyeY: Float, eyeZ: Float,
        w: Float, h: Float
    ) {
        val labelTextSize = (14f * density).coerceIn(24f, 42f)
        constellationTextPaint.textSize = labelTextSize
        constellationOutlinePaint.textSize = labelTextSize
        constellationOutlinePaint.strokeWidth = 3.2f * density

        for (label in constellationLabels) {
            // Earth occultation test (Earth is sphere at (0,0,0) with radius 10.0f)
            val dx = label.x - eyeX
            val dy = label.y - eyeY
            val dz = label.z - eyeZ
            val dist = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.001f)
            val ux = dx / dist
            val uy = dy / dist
            val uz = dz / dist

            val tClosest = -(eyeX * ux + eyeY * uy + eyeZ * uz)
            if (tClosest > 0.0f && tClosest < dist) {
                val px = eyeX + ux * tClosest
                val py = eyeY + uy * tClosest
                val pz = eyeZ + uz * tClosest
                val distCenterSq = px * px + py * py + pz * pz
                if (distCenterSq < 10.05f * 10.05f) {
                    continue // Blocked by Earth globe
                }
            }

            worldPos[0] = label.x
            worldPos[1] = label.y
            worldPos[2] = label.z
            worldPos[3] = 1.0f
            Matrix.multiplyMV(clipPos, 0, vpMatrix, 0, worldPos, 0)
            val clipW = clipPos[3]
            if (clipW <= 0.1f) continue

            val ndcX = clipPos[0] / clipW
            val ndcY = clipPos[1] / clipW
            if (ndcX < -0.92f || ndcX > 0.92f || ndcY < -0.92f || ndcY > 0.92f) continue

            val sx = (ndcX * 0.5f + 0.5f) * w
            val sy = (1.0f - (ndcY * 0.5f + 0.5f)) * h

            val formattedText = "✦  ${label.name}"
            canvas.drawText(formattedText, sx, sy, constellationOutlinePaint)
            canvas.drawText(formattedText, sx, sy, constellationTextPaint)
        }
    }

    private fun drawDiagnosticMarkers(
        canvas: Canvas,
        eyeX: Float, eyeY: Float, eyeZ: Float,
        w: Float, h: Float
    ) {
        val earthRadius = 10.0f
        val textSize = (11f * density).coerceIn(22f, 34f)
        diagTextPaint.textSize = textSize

        // 1. Draw pre-defined geographic reference markers
        for (pt in diagnosticPoints) {
            val latRad = Math.toRadians(pt.latDeg)
            val lonRad = Math.toRadians(pt.lonDeg)
            val x = (earthRadius * cos(latRad) * cos(lonRad)).toFloat()
            val y = (earthRadius * sin(latRad)).toFloat()
            val z = (-earthRadius * cos(latRad) * sin(lonRad)).toFloat()

            val nx = x / earthRadius
            val ny = y / earthRadius
            val nz = z / earthRadius

            val toEyeX = eyeX - x
            val toEyeY = eyeY - y
            val toEyeZ = eyeZ - z
            val toEyeLen = sqrt(toEyeX * toEyeX + toEyeY * toEyeY + toEyeZ * toEyeZ).coerceAtLeast(0.001f)
            val dotFacing = (nx * toEyeX + ny * toEyeY + nz * toEyeZ) / toEyeLen
            if (dotFacing < 0.01f) continue

            worldPos[0] = x; worldPos[1] = y; worldPos[2] = z; worldPos[3] = 1.0f
            Matrix.multiplyMV(clipPos, 0, vpMatrix, 0, worldPos, 0)
            if (clipPos[3] <= 0.1f) continue

            val ndcX = clipPos[0] / clipPos[3]
            val ndcY = clipPos[1] / clipPos[3]
            if (ndcX < -0.96f || ndcX > 0.96f || ndcY < -0.96f || ndcY > 0.96f) continue

            val sx = (ndcX * 0.5f + 0.5f) * w
            val sy = (1.0f - (ndcY * 0.5f + 0.5f)) * h

            val color = Color.parseColor(pt.colorHex)
            diagPaint.color = color
            diagFillPaint.color = color
            diagTextPaint.color = color

            // Crosshair / target marker
            val mSize = 8f * density
            canvas.drawCircle(sx, sy, mSize, diagPaint)
            canvas.drawCircle(sx, sy, 2.5f * density, diagFillPaint)
            canvas.drawLine(sx - mSize * 1.5f, sy, sx + mSize * 1.5f, sy, diagPaint)
            canvas.drawLine(sx, sy - mSize * 1.5f, sx, sy + mSize * 1.5f, diagPaint)

            // Label
            outlinePaint.textSize = textSize
            outlinePaint.strokeWidth = 3.2f * density
            canvas.drawText(pt.name, sx, sy - mSize * 1.6f, outlinePaint)
            canvas.drawText(pt.name, sx, sy - mSize * 1.6f, diagTextPaint)
        }

        // 2. Draw ISS Sub-Satellite Ground Point
        issSubPointLatLon?.let { (lat, lon) ->
            val latRad = Math.toRadians(lat)
            val lonRad = Math.toRadians(lon)
            val x = (earthRadius * cos(latRad) * cos(lonRad)).toFloat()
            val y = (earthRadius * sin(latRad)).toFloat()
            val z = (-earthRadius * cos(latRad) * sin(lonRad)).toFloat()

            val nx = x / earthRadius
            val ny = y / earthRadius
            val nz = z / earthRadius

            val toEyeX = eyeX - x
            val toEyeY = eyeY - y
            val toEyeZ = eyeZ - z
            val toEyeLen = sqrt(toEyeX * toEyeX + toEyeY * toEyeY + toEyeZ * toEyeZ).coerceAtLeast(0.001f)
            val dotFacing = (nx * toEyeX + ny * toEyeY + nz * toEyeZ) / toEyeLen
            if (dotFacing >= 0.01f) {
                worldPos[0] = x; worldPos[1] = y; worldPos[2] = z; worldPos[3] = 1.0f
                Matrix.multiplyMV(clipPos, 0, vpMatrix, 0, worldPos, 0)
                if (clipPos[3] > 0.1f) {
                    val ndcX = clipPos[0] / clipPos[3]
                    val ndcY = clipPos[1] / clipPos[3]
                    if (ndcX in -0.96f..0.96f && ndcY in -0.96f..0.96f) {
                        val sx = (ndcX * 0.5f + 0.5f) * w
                        val sy = (1.0f - (ndcY * 0.5f + 0.5f)) * h

                        val issColor = Color.parseColor("#FFFF00") // Bright yellow
                        diagPaint.color = issColor
                        diagFillPaint.color = issColor
                        diagTextPaint.color = issColor

                        val mSize = 10f * density
                        canvas.drawCircle(sx, sy, mSize, diagPaint)
                        canvas.drawCircle(sx, sy, 3f * density, diagFillPaint)
                        canvas.drawLine(sx - mSize * 1.8f, sy, sx + mSize * 1.8f, sy, diagPaint)
                        canvas.drawLine(sx, sy - mSize * 1.8f, sx, sy + mSize * 1.8f, diagPaint)

                        val label = String.format("🛰️ ISS BODENPUNKT (%.2f°, %.2f°)", lat, lon)
                        outlinePaint.textSize = textSize
                        outlinePaint.strokeWidth = 3.5f * density
                        val textY = if (sy > h - 180f * density) sy - mSize * 2.2f else sy + mSize * 2.2f
                        canvas.drawText(label, sx, textY, outlinePaint)
                        canvas.drawText(label, sx, textY, diagTextPaint)
                    }
                }
            }
        }
    }

    private val flareRayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private fun drawOpticalLensFlare(
        canvas: Canvas,
        eyeX: Float, eyeY: Float, eyeZ: Float,
        w: Float, h: Float
    ) {
        val sDir = sunDir ?: return
        // Ray-sphere occultation below determines whether the Sun is visible from the camera's viewpoint

        // 1. Physical gradual occultation by Earth sphere
        val sunDist = 74.0f
        val sunWorldX = sDir[0] * sunDist
        val sunWorldY = sDir[1] * sunDist
        val sunWorldZ = sDir[2] * sunDist

        val toSunX = sunWorldX - eyeX
        val toSunY = sunWorldY - eyeY
        val toSunZ = sunWorldZ - eyeZ
        val toSunLen = sqrt(toSunX * toSunX + toSunY * toSunY + toSunZ * toSunZ).coerceAtLeast(0.001f)
        val dirX = toSunX / toSunLen
        val dirY = toSunY / toSunLen
        val dirZ = toSunLen.let { toSunZ / it }

        val tClosest = -(eyeX * dirX + eyeY * dirY + eyeZ * dirZ)
        val occlusionFactor = if (tClosest > 0.0f) {
            val pCloseX = eyeX + dirX * tClosest
            val pCloseY = eyeY + dirY * tClosest
            val pCloseZ = eyeZ + dirZ * tClosest
            val distCenter = sqrt(pCloseX * pCloseX + pCloseY * pCloseY + pCloseZ * pCloseZ)
            // Solar disc apparent radius at the limb is ~0.35 units
            // Smoothly transitions as sun dips behind Earth horizon
            ((distCenter - 9.65f) / 0.70f).coerceIn(0.0f, 1.0f)
        } else {
            1.0f
        }
        if (occlusionFactor <= 0.005f) return

        // 2. Project Sun world position to Clip Space
        val sunClip = FloatArray(4)
        val sunWorld4 = floatArrayOf(sunWorldX, sunWorldY, sunWorldZ, 1.0f)
        Matrix.multiplyMV(sunClip, 0, vpMatrix, 0, sunWorld4, 0)
        val clipW = sunClip[3]
        if (clipW <= 0.1f) return // Sun is behind camera plane

        val ndcX = sunClip[0] / clipW
        val ndcY = sunClip[1] / clipW

        val sunScreenX = (ndcX * 0.5f + 0.5f) * w
        val sunScreenY = (1.0f - (ndcY * 0.5f + 0.5f)) * h

        val centerX = w * 0.5f
        val centerY = h * 0.5f

        // Optical flare vector from Sun through viewport optical center
        val flareDx = centerX - sunScreenX
        val flareDy = centerY - sunScreenY

        // Distance from screen center in normalized screen radius
        val normDistX = flareDx / (w * 0.5f)
        val normDistY = flareDy / (h * 0.5f)
        val normDist = sqrt(normDistX * normDistX + normDistY * normDistY)

        // As the sun leaves the viewport, rays and flare smoothly fade to 0
        // (full intensity when looking directly at sun, fading towards screen border)
        val fovFactor = ((1.35f - normDist) / 0.85f).coerceIn(0.0f, 1.0f)
        val totalIntensity = (occlusionFactor * fovFactor).coerceIn(0.0f, 1.0f)
        if (totalIntensity <= 0.005f) return

        // Continuous 60fps animation while optical lens flare is active
        postInvalidateOnAnimation()

        val timeSec = android.os.SystemClock.uptimeMillis() / 1000.0f

        // A. Dynamic Soft Blooming Yellow Solar Corona ("von winzig aufblühend, verwaschen, kein harter Rand")
        val bloomGrowth = totalIntensity * totalIntensity
        val bloomRadius = (35f + 290f * bloomGrowth) * density * (0.95f + 0.05f * sin(timeSec * 2.4f))
        flareCorePaint.shader = RadialGradient(
            sunScreenX, sunScreenY, bloomRadius,
            intArrayOf(
                Color.argb((255 * totalIntensity).toInt(), 255, 255, 255),
                Color.argb((215 * totalIntensity).toInt(), 255, 240, 165),
                Color.argb((115 * totalIntensity).toInt(), 255, 185, 75),
                Color.argb((35 * totalIntensity).toInt(), 255, 140, 45),
                Color.argb(0, 255, 120, 30)
            ),
            floatArrayOf(0.0f, 0.14f, 0.40f, 0.72f, 1.0f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(sunScreenX, sunScreenY, bloomRadius, flareCorePaint)

        // B. Dynamic Shimmering / Dancing Rays ("tänzelnde Strahlen")
        val baseAngle = Math.atan2(flareDy.toDouble(), flareDx.toDouble()).toFloat() + timeSec * 0.12f
        val numSpikes = 12
        for (i in 0 until numSpikes) {
            val phase = timeSec * 3.0f + i * 1.618f
            val wobble = sin(phase) * 0.05f
            val spikeAngle = baseAngle + (i.toFloat() / numSpikes) * (Math.PI.toFloat() * 2.0f) + wobble
            val cosA = cos(spikeAngle)
            val sinA = sin(spikeAngle)

            val lengthPulse = 0.82f + 0.22f * sin(timeSec * 3.4f + i * 2.1f)
            val spikeLength = (35f + 185f * bloomGrowth) * density * lengthPulse

            val endX = sunScreenX + cosA * spikeLength
            val endY = sunScreenY + sinA * spikeLength

            flareRayPaint.shader = LinearGradient(
                sunScreenX, sunScreenY, endX, endY,
                intArrayOf(
                    Color.argb((220 * totalIntensity).toInt(), 255, 255, 240),
                    Color.argb((120 * totalIntensity).toInt(), 255, 220, 140),
                    Color.argb((40 * totalIntensity).toInt(), 140, 200, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0.0f, 0.25f, 0.65f, 1.0f),
                Shader.TileMode.CLAMP
            )
            flareRayPaint.strokeWidth = (if (i % 3 == 0) 3.5f else 2.0f) * density * totalIntensity
            canvas.drawLine(sunScreenX, sunScreenY, endX, endY, flareRayPaint)
        }

        // C. Anamorphic Horizontal Light Streak across camera viewport
        val streakHalfWidth = w * 0.88f * bloomGrowth
        val streakHeight = 2.6f * density * (0.88f + 0.12f * sin(timeSec * 4.0f))
        if (streakHalfWidth > 2f) {
            flareStreakPaint.shader = LinearGradient(
                sunScreenX - streakHalfWidth, sunScreenY,
                sunScreenX + streakHalfWidth, sunScreenY,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb((120 * totalIntensity).toInt(), 120, 200, 255),
                    Color.argb((245 * totalIntensity).toInt(), 255, 255, 255),
                    Color.argb((120 * totalIntensity).toInt(), 120, 200, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0.0f, 0.35f, 0.5f, 0.65f, 1.0f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(
                sunScreenX - streakHalfWidth, sunScreenY - streakHeight,
                sunScreenX + streakHalfWidth, sunScreenY + streakHeight,
                flareStreakPaint
            )
        }

        // D. Optical Lens Ghost Elements along the axis
        val ghosts = arrayOf(
            Triple(0.40f, 16f, Color.argb((90 * totalIntensity).toInt(), 80, 220, 255)),
            Triple(0.65f, 32f, Color.argb((60 * totalIntensity).toInt(), 255, 180, 90)),
            Triple(1.15f, 22f, Color.argb((70 * totalIntensity).toInt(), 220, 120, 255)),
            Triple(1.45f, 48f, Color.argb((45 * totalIntensity).toInt(), 100, 230, 210)),
            Triple(1.85f, 14f, Color.argb((85 * totalIntensity).toInt(), 255, 230, 110)),
            Triple(2.20f, 65f, Color.argb((35 * totalIntensity).toInt(), 140, 180, 255))
        )

        for (g in ghosts) {
            val gx = sunScreenX + flareDx * g.first
            val gy = sunScreenY + flareDy * g.second
            val gr = g.second * density
            flareGhostPaint.color = g.third
            flareGhostPaint.style = Paint.Style.FILL
            canvas.drawCircle(gx, gy, gr, flareGhostPaint)
        }
    }
}
