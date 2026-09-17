package de.shakie.iss.graphics

import android.content.Context
import android.graphics.*
import android.opengl.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
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

    fun setLabelsVisible(visible: Boolean) {
        bordersVisible = visible
        postInvalidateOnAnimation()
    }

    fun updateCamera(
        camPose: FloatArray,
        viewportAspect: Float,
        fovY: Float,
        zoom: Float,
        isBordersVisible: Boolean
    ) {
        cameraPose = camPose
        aspect = viewportAspect
        fovYDeg = fovY
        zoomFactor = zoom
        bordersVisible = isBordersVisible
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (!bordersVisible) return
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
            val z = earthRadius * cos(latRad) * sin(lonRad)

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
}
