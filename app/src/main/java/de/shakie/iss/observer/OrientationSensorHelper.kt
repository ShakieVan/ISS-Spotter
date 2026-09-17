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
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val rawRotationMatrix = FloatArray(16)

    private var smoothedAzimuth = 0f
    private var smoothedPitch = 0f
    private var smoothedRoll = 0f
    private var isFirstEvent = true

    var onOrientationChanged: ((DeviceOrientation) -> Unit)? = null

    fun start() {
        isFirstEvent = true
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR ||
            event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR
        ) {
            SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)

            // The user looks through the rear camera (vector (0, 0, -1) in device coordinates).
            // Transforming (0, 0, -1) into world East-North-Up coordinates via rawRotationMatrix R:
            // V_world = R * [0, 0, -1]^T = [-R[2], -R[6], -R[10]]
            val camEast = -rawRotationMatrix[2].toDouble()
            val camNorth = -rawRotationMatrix[6].toDouble()
            val camUp = -rawRotationMatrix[10].toDouble().coerceIn(-1.0, 1.0)

            // Pitch: elevation angle above horizon (-90 deg looking down, 0 deg horizon, +90 deg zenith)
            val pitch = Math.toDegrees(asin(camUp)).toFloat()

            // Azimuth: compass bearing of the rear camera (0 = North, 90 = East, 180 = South, 270 = West)
            var azimuth = Math.toDegrees(atan2(camEast, camNorth)).toFloat()
            if (azimuth < 0f) azimuth += 360f

            // Roll: rotation of device's +X axis around line of sight
            val roll = Math.toDegrees(atan2(rawRotationMatrix[8].toDouble(), rawRotationMatrix[9].toDouble())).toFloat()

            // Instant initialization on first reading to eliminate start-up drift
            if (isFirstEvent) {
                smoothedAzimuth = azimuth
                smoothedPitch = pitch
                smoothedRoll = roll
                isFirstEvent = false
            } else {
                smoothedAzimuth = smoothAngle(smoothedAzimuth, azimuth, 0.25f)
                smoothedPitch = smoothedPitch + 0.25f * (pitch - smoothedPitch)
                smoothedRoll = smoothedRoll + 0.25f * (roll - smoothedRoll)
            }

            onOrientationChanged?.invoke(
                DeviceOrientation(
                    azimuthDeg = smoothedAzimuth,
                    pitchDeg = smoothedPitch,
                    rollDeg = smoothedRoll,
                    rotationMatrix = rawRotationMatrix.clone()
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
