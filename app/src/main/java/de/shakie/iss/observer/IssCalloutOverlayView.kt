package de.shakie.iss.observer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import de.shakie.iss.astronomy.SkyRenderer
import de.shakie.iss.orbit.IssSnapshot
import de.shakie.iss.graphics.TrajectoryPainter
import kotlin.math.*

class IssCalloutOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val starfleetArrow = StarfleetArrowDrawable()
    private val skyRenderer = SkyRenderer()
    private val trajectoryPainter = TrajectoryPainter(resources.displayMetrics.density)
    var trajectoryVisible: Boolean = true
        set(value) { field = value; invalidate() }

    // Paints
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(14f, 0f, 0f, Color.WHITE)
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val calloutLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        pathEffect = DashPathEffect(floatArrayOf(12f, 4f), 0f)
    }

    private val hudCardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 10, 20, 35)
        style = Paint.Style.FILL
    }

    private val hudCardBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
    }

    private val titleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 34f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val subtitleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#81ECEC")
        textSize = 22f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    private val horizonLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 206, 201)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
    }

    private val compassTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00CEC9")
        textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 5, 15, 30)
        style = Paint.Style.FILL
    }

    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00CEC9")
        textSize = 19f
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    }

    // State
    private var orientation: DeviceOrientation? = null
    private var snapshot: IssSnapshot? = null
    var showVirtualSky: Boolean = false
    private var cameraProjectionData = CameraProjectionData()

    private val calloutPath = Path()

    fun updateData(newOrientation: DeviceOrientation, newSnapshot: IssSnapshot) {
        orientation = newOrientation
        snapshot = newSnapshot
        invalidate()
    }

    fun setCameraProjectionData(data: CameraProjectionData) {
        cameraProjectionData = data
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 1f || h <= 1f) return

        val centerX = w / 2f
        val centerY = h / 2f

        val orient = orientation ?: return
        val snap = snapshot ?: return
        val horiz = snap.horizontal ?: return

        // Effective projection data for this draw call
        val effectiveProjData = if (showVirtualSky) {
            cameraProjectionData.copy(
                calibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY,
                viewWidth = width,
                viewHeight = height
            )
        } else {
            cameraProjectionData.copy(
                viewWidth = width,
                viewHeight = height
            )
        }

        if (!showVirtualSky && !effectiveProjData.isProjectionReady) {
            canvas.drawText("Kameraprojektion wird aktualisiert …", 24f, h * 0.5f, badgeTextPaint)
            return
        }

        // 1. Draw Virtual Sky Background if enabled
        if (showVirtualSky) {
            val skyGradient = LinearGradient(
                0f, 0f, 0f, h,
                Color.parseColor("#040711"),
                Color.parseColor("#0A1428"),
                Shader.TileMode.CLAMP
            )
            canvas.drawPaint(Paint().apply { shader = skyGradient })
        }

        // 2. Draw Realistic Starry Sky with Seasonal Constellations using unified 3D projector
        val obsLat = snap.observerLat
        val obsLon = snap.observerLon
        skyRenderer.draw(
            canvas = canvas,
            w = w,
            h = h,
            centerX = centerX,
            centerY = centerY,
            rotationMatrix = orient.rotationMatrix,
            projectionData = effectiveProjData,
            obsLatDeg = obsLat,
            obsLonDeg = obsLon,
            timeMillis = snap.timestampMillis
        )

        // The same lens/crop/zoom model also bends the horizon in an uncorrected preview.
        CameraProjector.drawHorizon(canvas, orient.rotationMatrix, effectiveProjData, horizonLinePaint)
        val headings = listOf(0.0 to "N", 90.0 to "O", 180.0 to "S", 270.0 to "W")
        for ((bearing, label) in headings) {
            val p = CameraProjector.projectDirection(bearing, 0.0, orient.rotationMatrix, effectiveProjData)
            if (!p.isBehindCamera && p.isInViewBounds) {
                canvas.drawCircle(p.screenX, p.screenY, 4.5f, horizonLinePaint)
                canvas.drawText(label, p.screenX, p.screenY - 16f, compassTextPaint)
            }
        }

        // Same optical projection as the live marker; no screen-fixed or ground-projected AR path.
        if (trajectoryVisible) trajectoryPainter.drawSky(canvas, snap, orient.rotationMatrix, effectiveProjData)

        // 4. Project ISS using unified 3D perspective projector
        val projIss = CameraProjector.projectDirection(
            azimuthDeg = horiz.azimuthDeg,
            elevationDeg = horiz.elevationDeg,
            rotationMatrix = orient.rotationMatrix,
            data = effectiveProjData
        )

        if (!projIss.isBehindCamera && projIss.isInViewBounds) {
            // === ISS IS IN SIGHT (LOCKED ON) ===
            val isBelowHorizon = horiz.elevationDeg < 0.0
            val accentColor = if (isBelowHorizon) Color.parseColor("#FFAA00") else Color.parseColor("#00E5FF")
            val dotColor = if (isBelowHorizon) Color.parseColor("#FFEAA7") else Color.WHITE

            dotPaint.color = dotColor
            ringPaint.color = accentColor
            calloutLinePaint.color = accentColor
            hudCardBorder.color = accentColor

            val screenX = projIss.screenX
            val screenY = projIss.screenY

            // White/amber dot with targeting ring
            canvas.drawCircle(screenX, screenY, 6.5f, dotPaint)
            canvas.drawCircle(screenX, screenY, 18f, ringPaint)
            if (isBelowHorizon) {
                // Outer dashed aura indicating below horizon
                canvas.drawCircle(screenX, screenY, 28f, calloutLinePaint)
            }

            // Dynamically flip callout line & card near screen edges
            val cardWidth = 320f
            val cardHeight = 88f
            val flipX = (screenX + 45f + cardWidth > w - 16f)
            val cornerX = if (flipX) screenX - 45f else screenX + 45f
            val endX = if (flipX) cornerX - 160f else cornerX + 160f

            val flipY = (screenY - 45f - cardHeight < 90f)
            val cornerY = if (flipY) screenY + 45f else screenY - 45f
            val endY = cornerY

            calloutPath.reset()
            calloutPath.moveTo(screenX, screenY)
            calloutPath.lineTo(cornerX, cornerY)
            calloutPath.lineTo(endX, endY)
            canvas.drawPath(calloutPath, calloutLinePaint)

            // Technical HUD label card
            val cardRect = if (flipX) {
                RectF(cornerX - cardWidth, cornerY - cardHeight, cornerX - 10f, cornerY - 8f)
            } else {
                RectF(cornerX + 10f, cornerY - cardHeight, cornerX + cardWidth, cornerY - 8f)
            }
            canvas.drawRoundRect(cardRect, 10f, 10f, hudCardFill)
            canvas.drawRoundRect(cardRect, 10f, 10f, hudCardBorder)

            val titleText = if (isBelowHorizon) "ISS (UNTER DEM HORIZONT)" else "ISS"
            canvas.drawText(titleText, cardRect.left + 16f, cardRect.top + 32f, titleTextPaint)

            val distText = String.format("DIST: %.0f km  ALT: %.0f km", horiz.distanceKm, snap.altitudeKm)
            val statusText = if (isBelowHorizon) {
                "STATUS: DURCH DIE ERDE PEILEN"
            } else if (snap.isEclipsed) {
                "STATUS: IM ERDSCHATTEN"
            } else {
                "STATUS: SONNENLICHT"
            }
            canvas.drawText(distText, cardRect.left + 16f, cardRect.top + 55f, subtitleTextPaint)
            canvas.drawText(statusText, cardRect.left + 16f, cardRect.top + 73f, subtitleTextPaint)

        } else {
            // === ISS OUTSIDE SIGHT -> STARFLEET DELTA ARROW ===
            val angleRad = Math.toRadians(projIss.offscreenBearingDeg.toDouble()).toFloat()

            val margin = 85f
            val maxRadius = min(w / 2f - margin, h / 2f - margin)

            val edgeX = (centerX + cos(angleRad) * maxRadius).coerceIn(margin, w - margin)
            val edgeY = (centerY + sin(angleRad) * maxRadius).coerceIn(margin, h - margin)

            starfleetArrow.draw(
                canvas = canvas,
                x = edgeX,
                y = edgeY,
                rotationDeg = projIss.offscreenBearingDeg,
                elevationDeg = horiz.elevationDeg,
                isBelowHorizon = horiz.elevationDeg < 0.0
            )
        }

        // 5. Calibration & Sensor HUD status badge
        val calibText = (if (showVirtualSky) null else effectiveProjData.calibrationNote) ?: when (effectiveProjData.calibrationAccuracy) {
            CalibrationAccuracy.CALIBRATED_INTRINSICS -> "KAMERA: KALIBRIERT (INTRINSICS)"
            CalibrationAccuracy.APPROXIMATE_FOCAL_LENGTH -> {
                val fStr = effectiveProjData.focalLengthMm?.let { String.format("%.1f mm", it) } ?: "N/A"
                val zStr = if (effectiveProjData.currentZoomRatio > 1.05f) String.format(" (%.1fx Zoom)", effectiveProjData.currentZoomRatio) else ""
                "KAMERA: NÄHERUNG (f=$fStr$zStr)"
            }
            CalibrationAccuracy.VIRTUAL_SKY -> "MODUS: VIRTUELLER STERNENHIMMEL"
        }

        val compText = when (orient.sensorAccuracyLevel) {
            SensorAccuracyLevel.GEOMAGNETIC_TRUE_NORTH -> {
                val sign = if (orient.declinationDeg >= 0) "+" else ""
                String.format("KOMPASS: GEOGRAPHISCH NORD (DEKL. %s%.1f°)", sign, orient.declinationDeg)
            }
            SensorAccuracyLevel.GAME_ROTATION_RELATIVE -> "KOMPASS: RELATIVE DREHUNG (KEIN MAGNETOMETER)"
            SensorAccuracyLevel.SENSOR_UNAVAILABLE -> "KOMPASS: SENSOR NICHT VERFÜGBAR"
        }

        val badgeX = 24f
        val badgeY = h - 34f
        canvas.drawRect(badgeX - 6f, badgeY - 32f, badgeX + 460f, badgeY + 18f, badgePaint)
        canvas.drawText(calibText, badgeX, badgeY - 14f, badgeTextPaint)
        canvas.drawText(compText, badgeX, badgeY + 10f, badgeTextPaint)
    }
}
