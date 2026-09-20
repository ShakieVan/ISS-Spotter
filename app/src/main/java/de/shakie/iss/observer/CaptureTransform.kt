package de.shakie.iss.observer

import android.graphics.Matrix

/** View pixels -> sensor domain -> unrotated capture buffer. Crop/rotation follow afterwards. */
object CaptureTransform {
    fun viewToBuffer(sensorToView: Matrix, sensorToBuffer: Matrix): Matrix? {
        val inverse = Matrix()
        if (!sensorToView.invert(inverse)) return null
        return Matrix().apply { setConcat(sensorToBuffer, inverse) }
    }
}
