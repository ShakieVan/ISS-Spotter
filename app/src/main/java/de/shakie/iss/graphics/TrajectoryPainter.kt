package de.shakie.iss.graphics

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import de.shakie.iss.orbit.*
import de.shakie.iss.observer.CameraProjectionData
import de.shakie.iss.observer.CameraProjector
import kotlin.math.*

/** Same past/solid, future/dashed and time-fade semantics in the globe and AR views. */
class TrajectoryPainter(private val density: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        strokeCap = Paint.Cap.ROUND
    }

    fun drawGround(canvas: Canvas, pose: FloatArray, snapshot: IssSnapshot) {
        val data = snapshot.trajectory ?: return
        if (!data.isUsable(snapshot.timestampMillis, snapshot.observerLat, snapshot.observerLon)) return
        val h = snapshot.horizontal
        val now = TrajectoryPoint(snapshot.timestampMillis, snapshot.latitude, snapshot.longitude,
            snapshot.altitudeKm, h?.azimuthDeg ?: 0.0, h?.elevationDeg ?: 0.0)
        val projection = OrbitScreenProjection(pose, canvas.width.toDouble(), canvas.height.toDouble())
        val halves = data.halves(now, TrajectorySampler.GROUND_WINDOW_MILLIS)
        for ((points, future) in listOf(halves.first to false, halves.second to true)) {
            var accumulated = 0.0
            for (i in 1 until points.size) {
                var a = OrbitVector.geographic(points[i - 1].latitude, points[i - 1].longitude)
                var b = OrbitVector.geographic(points[i].latitude, points[i].longitude)
                val va = projection.groundVisible(a)
                val vb = projection.groundVisible(b)
                if (!va && !vb) { accumulated = 0.0; continue }
                if (va != vb) {
                    // End exactly at the visible limb; never join across the back of the planet.
                    var lo = a
                    var hi = b
                    repeat(18) {
                        val mid = (lo + hi).unit(a)
                        if (projection.groundVisible(mid) == va) lo = mid else hi = mid
                    }
                    val limb = (lo + hi).unit(a)
                    if (va) b = limb else a = limb
                }
                val p = projection.project(a * OrbitScale.EARTH_RADIUS)
                val q = projection.project(b * OrbitScale.EARTH_RADIUS)
                if (p == null || q == null) { accumulated = 0.0; continue }
                val age = ((points[i - 1].timeMillis - now.timeMillis).toDouble() + (points[i].timeMillis - now.timeMillis)) * 0.5
                accumulated = drawSegment(canvas, p, q, accumulated, future,
                    TrajectorySampler.fade(age, TrajectorySampler.GROUND_WINDOW_MILLIS), false)
            }
        }
    }

    fun drawSky(canvas: Canvas, snapshot: IssSnapshot, rotation: FloatArray, projection: CameraProjectionData) {
        val data = snapshot.trajectory ?: return
        val h = snapshot.horizontal ?: return
        if (!data.isUsable(snapshot.timestampMillis, snapshot.observerLat, snapshot.observerLon)) return
        val now = TrajectoryPoint(snapshot.timestampMillis, snapshot.latitude, snapshot.longitude,
            snapshot.altitudeKm, h.azimuthDeg, h.elevationDeg)
        val halves = data.halves(now, TrajectorySampler.SKY_WINDOW_MILLIS)
        for ((points, future) in listOf(halves.first to false, halves.second to true)) {
            var accumulated = 0.0
            for (i in 1 until points.size) {
                val a = points[i - 1]
                val b = points[i]
                val p = CameraProjector.projectDirection(a.azimuthDeg, a.elevationDeg, rotation, projection)
                val q = CameraProjector.projectDirection(b.azimuthDeg, b.elevationDeg, rotation, projection)
                // No false line through the viewport when a ray crosses behind the camera.
                if (p.isBehindCamera || q.isBehindCamera) { accumulated = 0.0; continue }
                val age = ((a.timeMillis - now.timeMillis).toDouble() + (b.timeMillis - now.timeMillis)) * 0.5
                accumulated = drawSegment(canvas, doubleArrayOf(p.screenX.toDouble(), p.screenY.toDouble()),
                    doubleArrayOf(q.screenX.toDouble(), q.screenY.toDouble()), accumulated, future,
                    TrajectorySampler.fade(age, TrajectorySampler.SKY_WINDOW_MILLIS), (a.elevationDeg + b.elevationDeg) < 0)
            }
        }
    }

    private fun drawSegment(canvas: Canvas, p: DoubleArray, q: DoubleArray, phase: Double,
                            dashed: Boolean, opacity: Float, belowHorizon: Boolean): Double {
        val length = hypot(q[0] - p[0], q[1] - p[1])
        val period = 16.0 * density
        if (!length.isFinite() || length < 1e-8) return phase
        val nextPhase = (phase + length) % period
        val clip = TrailClip.segment(p[0], p[1], q[0], q[1], canvas.width.toDouble(), canvas.height.toDouble()) ?: return nextPhase
        paint.color = if (belowHorizon) Color.rgb(255, 180, 70) else Color.rgb(60, 220, 255)
        paint.alpha = (220 * opacity).roundToInt().coerceIn(0, 220)
        if (paint.alpha == 0) return nextPhase
        if (!dashed) {
            canvas.drawLine(clip[0].toFloat(), clip[1].toFloat(), clip[2].toFloat(), clip[3].toFloat(), paint)
        } else {
            // Dash phase continues across sample boundaries, anchored at the ISS, not per segment.
            val visibleLength = hypot(clip[2] - clip[0], clip[3] - clip[1])
            if (visibleLength < 1e-8) return nextPhase
            val startPhase = (phase + clip[4] * length) % period
            var d = -startPhase
            val dash = 10.0 * density
            while (d < visibleLength) {
                val a = max(0.0, d)
                val b = min(visibleLength, d + dash)
                if (b > a) {
                    fun x(t: Double) = (clip[0] + (clip[2] - clip[0]) * t / visibleLength).toFloat()
                    fun y(t: Double) = (clip[1] + (clip[3] - clip[1]) * t / visibleLength).toFloat()
                    canvas.drawLine(x(a), y(a), x(b), y(b), paint)
                }
                d += period
            }
        }
        return nextPhase
    }
}
