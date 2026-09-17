package de.shakie.iss.observer

import android.graphics.*
import kotlin.math.*

class StarfleetArrowDrawable {

    private val arrowPath = Path()
    private val innerCutoutPath = Path()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.parseColor("#FFEAA7")
        setShadowLayer(14f, 0f, 0f, Color.parseColor("#FFD700"))
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.argb(90, 0, 229, 255)
        setShadowLayer(20f, 0f, 0f, Color.parseColor("#00E5FF"))
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 15, 25, 45)
        style = Paint.Style.FILL
    }

    private val badgeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 229, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    init {
        buildPaths()
    }

    private fun buildPaths() {
        // Starfleet Delta Insignia dimensions: width = 50, height = 75
        val w = 48f
        val h = 72f

        arrowPath.reset()
        // Start at top tip
        arrowPath.moveTo(0f, -h * 0.55f)
        // Right flank curve down
        arrowPath.cubicTo(w * 0.35f, -h * 0.15f, w * 0.55f, h * 0.25f, w * 0.5f, h * 0.45f)
        // Bottom inward sweep
        arrowPath.quadTo(0f, h * 0.18f, -w * 0.5f, h * 0.45f)
        // Left flank curve up to tip
        arrowPath.cubicTo(-w * 0.55f, h * 0.25f, -w * 0.35f, -h * 0.15f, 0f, -h * 0.55f)
        arrowPath.close()

        // Inner star / delta highlight
        innerCutoutPath.reset()
        innerCutoutPath.moveTo(0f, -h * 0.35f)
        innerCutoutPath.lineTo(w * 0.22f, h * 0.22f)
        innerCutoutPath.lineTo(0f, h * 0.08f)
        innerCutoutPath.lineTo(-w * 0.22f, h * 0.22f)
        innerCutoutPath.close()
    }

    /**
     * Draws the Starfleet arrow at the given screen coordinates, rotated towards targetAngleRad.
     */
    fun draw(
        canvas: Canvas,
        x: Float,
        y: Float,
        rotationDeg: Float,
        elevationDeg: Double,
        isBelowHorizon: Boolean
    ) {
        canvas.save()
        canvas.translate(x, y)

        // Rotate arrow towards target direction
        canvas.rotate(rotationDeg + 90f) // 0 rotation points right, arrow tip is up

        // Setup gradient for metallic Starfleet gold/bronze
        val gradient = if (isBelowHorizon) {
            LinearGradient(
                0f, -40f, 0f, 40f,
                Color.parseColor("#E17055"), // Amber/orange when below horizon
                Color.parseColor("#D63031"),
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, -40f, 0f, 40f,
                Color.parseColor("#FFEAA7"), // Brilliant gold/amber
                Color.parseColor("#E17055"),
                Shader.TileMode.CLAMP
            )
        }
        fillPaint.shader = gradient

        // Outer glow & stroke
        canvas.drawPath(arrowPath, glowPaint)
        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(arrowPath, strokePaint)

        // Inner delta
        canvas.drawPath(innerCutoutPath, badgeStroke)

        canvas.restore()

        // Draw elevation badge next to arrow (in upright orientation)
        canvas.save()
        canvas.translate(x, y)
        val badgeText = if (isBelowHorizon) {
            String.format("%.0f° (Unter)", elevationDeg)
        } else {
            String.format("+%.0f°", elevationDeg)
        }
        val textWidth = textPaint.measureText(badgeText)
        val badgeRect = RectF(-textWidth / 2f - 14f, 42f, textWidth / 2f + 14f, 78f)

        canvas.drawRoundRect(badgeRect, 8f, 8f, badgePaint)
        canvas.drawRoundRect(badgeRect, 8f, 8f, badgeStroke)
        canvas.drawText(badgeText, 0f, 68f, textPaint)
        canvas.restore()
    }
}
