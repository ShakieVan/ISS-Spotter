package de.shakie.iss.observer

import kotlin.math.*

/** Camera2 pixel domains kept explicit; no dependency on Android, reusable in tests. */
data class PixelRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) * 0.5
    val cy get() = (top + bottom) * 0.5
    val valid get() = listOf(left, top, right, bottom).all { it.isFinite() } && width > 0 && height > 0
}

/**
 * A calibrated native sensor ray, converted to the bound logical sensor's pixel domain.
 * CameraX then supplies rotation/stream crop/fillCenter. Zoom is applied HERE exactly once.
 */
data class LensRayCalibration(
    val fx: Double, val fy: Double, val cx: Double, val cy: Double, val skew: Double,
    val nativeArray: PixelRect,
    val logicalArray: PixelRect,
    val readout: PixelRect,
    val sensorOrientation: Int,
    val logicalSensorOrientation: Int,
    val distortion: List<Double> = emptyList()
) {
    fun projectDeviceRay(x: Double, y: Double, z: Double): DoubleArray? {
        val depth = -z // Rear camera looks away from the screen.
        if (depth <= 1e-5 || !nativeArray.valid || !logicalArray.valid || !readout.valid) return null
        val a = Math.toRadians(sensorOrientation.toDouble())
        val sx = (cos(a) * x - sin(a) * y) / depth
        val sy = (-sin(a) * x - cos(a) * y) / depth
        var ux = sx; var uy = sy
        // Camera2 LENS_DISTORTION: undistorted normalized ray -> raw sample coordinate.
        // Do not use the deprecated LENS_RADIAL_DISTORTION edge-normalized convention.
        if (distortion.size == 5 && distortion.all { it.isFinite() }) {
            val r2 = sx * sx + sy * sy
            val gain = 1.0 + distortion[0]*r2 + distortion[1]*r2*r2 + distortion[2]*r2*r2*r2
            ux = sx*gain + 2.0*distortion[3]*sx*sy + distortion[4]*(r2+2.0*sx*sx)
            uy = sy*gain + 2.0*distortion[4]*sx*sy + distortion[3]*(r2+2.0*sy*sy)
        }
        val px = nativeArray.left + cx + fx*ux + skew*uy
        val py = nativeArray.top + cy + fy*uy
        // Isotropic zoom: aspect-only letterboxing must NOT become extra magnification.
        val scale = min(nativeArray.width/readout.width, nativeArray.height/readout.height)
        var nx = (px-readout.cx)*scale/nativeArray.width
        var ny = (py-readout.cy)*scale/nativeArray.height
        when ((logicalSensorOrientation - sensorOrientation + 360) % 360) {
            90 -> { val old=nx; nx=ny; ny=-old }
            180 -> { nx=-nx; ny=-ny }
            270 -> { val old=nx; nx=-ny; ny=old }
        }
        val out = doubleArrayOf(logicalArray.cx+nx*logicalArray.width, logicalArray.cy+ny*logicalArray.height)
        return out.takeIf { it.all(Double::isFinite) }
    }
}

object LensProjectionMath {
    /** CONTROL_ZOOM_RATIO changes the domain of cropRegion to the post-zoom active array. */
    fun effectiveReadout(active: PixelRect, reportedCrop: PixelRect?, zoomRatio: Double): PixelRect {
        require(active.valid)
        val crop = reportedCrop?.takeIf { it.valid } ?: active
        val z = zoomRatio.takeIf { it.isFinite() && it > 0.0 } ?: 1.0
        return PixelRect(active.cx+(crop.left-active.cx)/z, active.cy+(crop.top-active.cy)/z,
            active.cx+(crop.right-active.cx)/z, active.cy+(crop.bottom-active.cy)/z)
    }
}

data class AvailableLens(val id: String, val nativeRatio: Double, val minZoom: Double, val maxZoom: Double)

object LensSelection {
    /** Largest native lens no stronger than requested; hysteresis avoids switching at every pixel. */
    fun select(requested: Double, currentId: String?, lenses: List<AvailableLens>): AvailableLens? {
        val valid = lenses.filter { it.nativeRatio.isFinite() && it.minZoom.isFinite() && it.maxZoom.isFinite() &&
            it.nativeRatio > 0 && it.minZoom > 0 && it.maxZoom >= it.minZoom }
        if (valid.isEmpty()) return null
        if (!requested.isFinite() || requested <= 0.0) return valid.firstOrNull { it.id == currentId } ?: valid.first()
        val current = valid.firstOrNull { it.id == currentId }
        val candidate = valid.filter { requested >= it.nativeRatio*it.minZoom }.maxByOrNull { it.nativeRatio }
            ?: valid.minByOrNull { it.nativeRatio*it.minZoom }!!
        if (current != null && candidate.id != current.id) {
            if (candidate.nativeRatio > current.nativeRatio && requested < candidate.nativeRatio*candidate.minZoom*1.08) return current
            if (candidate.nativeRatio < current.nativeRatio && requested >= current.nativeRatio*current.minZoom*0.96) return current
        }
        return candidate
    }
}
