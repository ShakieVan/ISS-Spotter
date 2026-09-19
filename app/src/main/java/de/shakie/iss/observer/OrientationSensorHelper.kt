package de.shakie.iss.observer

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.*

data class DeviceOrientation(
    val azimuthDeg: Float,  // 0..360 (True compass bearing: 0 = True North, 90 = East)
    val pitchDeg: Float,    // -90 (pointing down) to +90 (pointing up at sky)
    val rollDeg: Float,     // -180..+180
    val rotationMatrix: FloatArray, // 4x4 matrix transforming device -> True Geographic ENU
    val declinationDeg: Float = 0f,
    val sensorAccuracyLevel: SensorAccuracyLevel = SensorAccuracyLevel.GEOMAGNETIC_TRUE_NORTH
)

class OrientationSensorHelper(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val rawRotationMatrix = FloatArray(16)
    private val trueRotationMatrix = FloatArray(16)

    private var observerLat: Double? = null
    private var observerLon: Double? = null
    private var observerAltKm: Double = 0.0
    private var magneticDeclinationDeg: Float = 0f

    private var smoothedAzimuth = 0f
    private var smoothedPitch = 0f
    private var smoothedRoll = 0f
    private var isFirstEvent = true

    var onOrientationChanged: ((DeviceOrientation) -> Unit)? = null

    fun updateObserverLocation(lat: Double, lon: Double, altKm: Double = 0.0) {
        observerLat = lat
        observerLon = lon
        observerAltKm = altKm
        try {
            val geoField = GeomagneticField(
                lat.toFloat(),
                lon.toFloat(),
                (altKm * 1000.0).toFloat(),
                System.currentTimeMillis()
            )
            magneticDeclinationDeg = geoField.declination
        } catch (ignored: Exception) {}
    }

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
            val isGeomagnetic = (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR)
            val accuracyLevel = if (isGeomagnetic) {
                SensorAccuracyLevel.GEOMAGNETIC_TRUE_NORTH
            } else {
                SensorAccuracyLevel.GAME_ROTATION_RELATIVE
            }

            SensorManager.getRotationMatrixFromVector(rawRotationMatrix, event.values)

            // If geomagnetic sensor is active and location is known, correct for magnetic declination:
            // R_true = R_decl * R_raw transforms device coordinates directly into True Geographic ENU
            val declination = if (isGeomagnetic) magneticDeclinationDeg else 0f
            if (declination != 0f) {
                val declRad = Math.toRadians(declination.toDouble()).toFloat()
                val cosD = cos(declRad)
                val sinD = sin(declRad)

                // Row 0: cosD * R_raw[0..2] + sinD * R_raw[4..6]
                trueRotationMatrix[0] = cosD * rawRotationMatrix[0] + sinD * rawRotationMatrix[4]
                trueRotationMatrix[1] = cosD * rawRotationMatrix[1] + sinD * rawRotationMatrix[5]
                trueRotationMatrix[2] = cosD * rawRotationMatrix[2] + sinD * rawRotationMatrix[6]
                trueRotationMatrix[3] = 0f

                // Row 1: -sinD * R_raw[0..2] + cosD * R_raw[4..6]
                trueRotationMatrix[4] = -sinD * rawRotationMatrix[0] + cosD * rawRotationMatrix[4]
                trueRotationMatrix[5] = -sinD * rawRotationMatrix[1] + cosD * rawRotationMatrix[5]
                trueRotationMatrix[6] = -sinD * rawRotationMatrix[2] + cosD * rawRotationMatrix[6]
                trueRotationMatrix[7] = 0f

                // Row 2: unchanged Up axis
                trueRotationMatrix[8] = rawRotationMatrix[8]
                trueRotationMatrix[9] = rawRotationMatrix[9]
                trueRotationMatrix[10] = rawRotationMatrix[10]
                trueRotationMatrix[11] = 0f

                // Row 3
                trueRotationMatrix[12] = 0f
                trueRotationMatrix[13] = 0f
                trueRotationMatrix[14] = 0f
                trueRotationMatrix[15] = 1f
            } else {
                System.arraycopy(rawRotationMatrix, 0, trueRotationMatrix, 0, 16)
            }

            // The user looks through the rear camera (vector (0, 0, -1) in device coordinates).
            // Transforming (0, 0, -1) into world East-North-Up coordinates via trueRotationMatrix R:
            // V_world = R * [0, 0, -1]^T = [-R[2], -R[6], -R[10]]
            val camEast = -trueRotationMatrix[2].toDouble()
            val camNorth = -trueRotationMatrix[6].toDouble()
            val camUp = -trueRotationMatrix[10].toDouble().coerceIn(-1.0, 1.0)

            // Pitch: elevation angle above horizon (-90 deg looking down, 0 deg horizon, +90 deg zenith)
            val pitch = Math.toDegrees(asin(camUp)).toFloat()

            // Azimuth: true compass bearing of the rear camera (0 = True North, 90 = East, 180 = South, 270 = West)
            var azimuth = Math.toDegrees(atan2(camEast, camNorth)).toFloat()
            if (azimuth < 0f) azimuth += 360f

            // Roll: rotation of device's +X axis around line of sight
            val roll = Math.toDegrees(atan2(trueRotationMatrix[8].toDouble(), trueRotationMatrix[9].toDouble())).toFloat()

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
                    rotationMatrix = trueRotationMatrix.clone(),
                    declinationDeg = declination,
                    sensorAccuracyLevel = accuracyLevel
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
