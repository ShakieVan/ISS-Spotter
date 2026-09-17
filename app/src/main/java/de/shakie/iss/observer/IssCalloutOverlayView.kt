package de.shakie.iss.observer

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import de.shakie.iss.astronomy.SkyRenderer
import de.shakie.iss.orbit.IssSnapshot
import kotlin.math.*

class IssCalloutOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val starfleetArrow = StarfleetArrowDrawable()
    private val skyRenderer = SkyRenderer()

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
        color = Color.argb(120, 0, 206, 201)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val compassTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00CEC9")
        textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    // State
    private var orientation: DeviceOrientation? = null
    private var snapshot: IssSnapshot? = null
    var showVirtualSky: Boolean = false

    // Camera field of view (degrees)
    private val cameraHfov = 62.0f
    private val cameraVfov = 76.0f

    private val calloutPath = Path()

    fun updateData(newOrientation: DeviceOrientation, newSnapshot: IssSnapshot) {
        orientation = newOrientation
        snapshot = newSnapshot
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val centerX = w / 2f
        val centerY = h / 2f

        val orient = orientation ?: return
        val snap = snapshot ?: return
        val horiz = snap.horizontal ?: return

        // 1. Draw Virtual Sky Background if enabled (or dark backing for camera overlay)
        if (showVirtualSky) {
            val skyGradient = LinearGradient(
                0f, 0f, 0f, h,
                Color.parseColor("#040711"),
                Color.parseColor("#0A1428"),
                Shader.TileMode.CLAMP
            )
            canvas.drawPaint(Paint().apply { shader = skyGradient })
        }

        // 2. Draw Realistic Starry Sky with Seasonal Constellations!
        // (Visible in Virtual Sky mode or as celestial overlay above horizon)
        val obsLat = snap.observerLat
        val obsLon = snap.observerLon
        skyRenderer.draw(
            canvas = canvas,
            w = w,
            h = h,
            centerX = centerX,
            centerY = centerY,
            hfov = cameraHfov,
            vfov = cameraVfov,
            deviceAzimuth = orient.azimuthDeg,
            devicePitch = orient.pitchDeg,
            obsLatDeg = obsLat,
            obsLonDeg = obsLon,
            timeMillis = snap.timestampMillis
        )

        // 3. Draw Artificial Horizon & Compass Marks
        val horizonY = centerY + (orient.pitchDeg / (cameraVfov / 2f)) * (h / 2f)
        if (horizonY in -50f..(h + 50f)) {
            canvas.drawLine(0f, horizonY, w, horizonY, horizonLinePaint)
            val headings = listOf(0f to "N", 90f to "O", 180f to "S", 270f to "W")
            for ((bearing, label) in headings) {
                var dAz = (bearing - orient.azimuthDeg)
                while (dAz > 180f) dAz -= 360f
                while (dAz < -180f) dAz += 360f

                val markX = centerX + (dAz / (cameraHfov / 2f)) * (w / 2f)
                if (markX in 30f..(w - 30f)) {
                    canvas.drawLine(markX, horizonY - 15f, markX, horizonY + 15f, horizonLinePaint)
                    canvas.drawText(label, markX, horizonY - 24f, compassTextPaint)
                }
            }
        }

        // 4. Compute Angular Offset to ISS
        var deltaAz = (horiz.azimuthDeg.toFloat() - orient.azimuthDeg)
        while (deltaAz > 180f) deltaAz -= 360f
        while (deltaAz < -180f) deltaAz += 360f

        val deltaEl = (horiz.elevationDeg.toFloat() - orient.pitchDeg)

        val halfHfov = cameraHfov / 2f
        val halfVfov = cameraVfov / 2f

        // ISS is in sight when within camera FOV angles (regardless of elevation)
        val isInFov = abs(deltaAz) <= halfHfov && abs(deltaEl) <= halfVfov

        if (isInFov) {
            // === ISS IS IN SIGHT (LOCKED ON) ===
            val isBelowHorizon = horiz.elevationDeg < 0.0
            val accentColor = if (isBelowHorizon) Color.parseColor("#FFAA00") else Color.parseColor("#00E5FF")
            val dotColor = if (isBelowHorizon) Color.parseColor("#FFEAA7") else Color.WHITE

            dotPaint.color = dotColor
            ringPaint.color = accentColor
            calloutLinePaint.color = accentColor
            hudCardBorder.color = accentColor

            val screenX = centerX + (deltaAz / halfHfov) * (w * 0.45f)
            val screenY = centerY - (deltaEl / halfVfov) * (h * 0.45f)

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
            val angleRad = atan2(-deltaEl.toDouble(), deltaAz.toDouble()).toFloat()
            val angleDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

            val margin = 85f
            val maxRadius = min(w / 2f - margin, h / 2f - margin)

            val edgeX = (centerX + cos(angleRad) * maxRadius).coerceIn(margin, w - margin)
            val edgeY = (centerY + sin(angleRad) * maxRadius).coerceIn(margin, h - margin)

            starfleetArrow.draw(
                canvas = canvas,
                x = edgeX,
                y = edgeY,
                rotationDeg = angleDeg,
                elevationDeg = horiz.elevationDeg,
                isBelowHorizon = horiz.elevationDeg < 0.0
            )
        }
    }
}
