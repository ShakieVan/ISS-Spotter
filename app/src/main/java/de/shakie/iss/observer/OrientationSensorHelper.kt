package de.shakie.iss.observer

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Surface
import android.view.WindowManager
import kotlin.math.*

data class DeviceOrientation(
    val azimuthDeg: Float,  // 0..360 (Compass bearing: 0 = North, 90 = East)
    val pitchDeg: Float,    // -90 (pointing down) to +90 (pointing up at sky)
    val rollDeg: Float,     // -180..+180
    val rotationMatrix: FloatArray
)

class OrientationSensorHelper(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)

    private val rawRotationMatrix = FloatArray(16)
    private val remappedMatrix = FloatArray(16)
    private val orientationAngles = FloatArray(3)

    private var smoothedAzimuth = 0f
    private var smoothedPitch = 0f
    private var smoothedRoll = 0f

    var onOrientationChanged: ((DeviceOrientation) -> Unit)? = null

    fun start() {
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)

            // Remap for portrait display orientation
            SensorManager.remapCoordinateSystem(
                rawRotationMatrix,
                SensorManager.AXIS_X,
                SensorManager.AXIS_Z,
                remappedMatrix
            )

            SensorManager.getOrientation(remappedMatrix, orientationAngles)

            // Convert to degrees
            var azimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
            if (azimuth < 0) azimuth += 360.0f
            // Pitch: when phone is pointing directly up to the zenith, pitch is ~ +90 degrees
            val pitch = Math.toDegrees(orientationAngles[1].toDouble()).toFloat()
            val roll = Math.toDegrees(orientationAngles[2].toDouble()).toFloat()

            // Smooth with low-pass filter (alpha = 0.25)
            smoothedAzimuth = smoothAngle(smoothedAzimuth, azimuth, 0.25f)
            smoothedPitch = smoothedPitch + 0.25f * (pitch - smoothedPitch)
            smoothedRoll = smoothedRoll + 0.25f * (roll - smoothedRoll)

            onOrientationChanged?.invoke(
                DeviceOrientation(
                    azimuthDeg = smoothedAzimuth,
                    pitchDeg = smoothedPitch,
                    rollDeg = smoothedRoll,
                    rotationMatrix = remappedMatrix.clone()
                )
            )
        }
    }

    private fun smoothAngle(current: Float, target: Float, alpha: Float): Float {
        var diff = (target - current) % 360.0f
        if (diff > 180.0f) diff -= 360.0f
        if (diff < -180.0f) diff += 360.0f
        return (current + alpha * diff + 360.0f) % 360.0f
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
