package de.shakie.iss.orbit

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class IssSnapshot(
    val latitude: Double,
    val longitude: Double,
    val altitudeKm: Double,
    val velocityKmh: Double,
    val sunlightFactor: Float, // 1.0 = sunlight, 0.0 = total umbra (Earth shadow)
    val isEclipsed: Boolean,
    val sun: SunPosition,
    val horizontal: HorizontalCoordinates?,
    val nextPass: IssPass?,
    val timestampMillis: Long
)

class IssTracker(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Fresh default TLE (ISS ZARYA)
    private var currentTle = Tle(
        satNumber = 25544,
        epochYear = 26,
        epochDay = 259.85263506,
        bstar = 0.00013566,
        inclinationDeg = 51.6307,
        raanDeg = 206.4210,
        eccentricity = 0.0004838,
        argPerigeeDeg = 147.2470,
        meanAnomalyDeg = 212.8820,
        meanMotionRevsPerDay = 15.49143506,
        revNumber = 58596
    )

    private var propagator = Sgp4Propagator(currentTle)

    // Observer position on Earth (GPS)
    var observerLat: Double? = 52.5200 // Default Berlin/Central Europe
        set(value) {
            field = value
            recalculateNextPass()
        }
    var observerLon: Double? = 13.4050
        set(value) {
            field = value
            recalculateNextPass()
        }
    var observerAltKm: Double = 0.05

    private var cachedNextPass: IssPass? = null
    private var lastPassCalcMillis = 0L

    private val _snapshotFlow = MutableStateFlow(computeSnapshot())
    val snapshotFlow: StateFlow<IssSnapshot> = _snapshotFlow.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        loadCachedTle()
        recalculateNextPass()
        scope.launch {
            refreshTleFromCelestrak()
            while (isActive) {
                delay(2 * 3600 * 1000L)
                refreshTleFromCelestrak()
            }
        }
    }

    private fun recalculateNextPass() {
        val lat = observerLat ?: return
        val lon = observerLon ?: return
        val now = System.currentTimeMillis()
        if (cachedNextPass == null || now > (cachedNextPass?.setTimeMillis ?: 0L) || (now - lastPassCalcMillis) > 10 * 60000L) {
            scope.launch(Dispatchers.Default) {
                val pass = PassPredictor.findNextPass(propagator, lat, lon, observerAltKm, now)
                cachedNextPass = pass
                lastPassCalcMillis = now
            }
        }
    }

    fun updateFrame(timeMillis: Long = System.currentTimeMillis()): IssSnapshot {
        val snapshot = computeSnapshot(timeMillis)
        _snapshotFlow.value = snapshot
        return snapshot
    }

    private fun computeSnapshot(timeMillis: Long = System.currentTimeMillis()): IssSnapshot {
        val state = propagator.propagate(timeMillis)
        val sun = SolarCoordinates.calculate(timeMillis)
        val sunlight = EclipseCalculator.getSunlightFactor(
            issLatDeg = state.latitudeDeg,
            issLonDeg = state.longitudeDeg,
            issAltKm = state.altitudeKm,
            sun = sun
        )

        val horizontal = if (observerLat != null && observerLon != null) {
            TopocentricPosition.calculate(
                obsLatDeg = observerLat!!,
                obsLonDeg = observerLon!!,
                obsAltKm = observerAltKm,
                issLatDeg = state.latitudeDeg,
                issLonDeg = state.longitudeDeg,
                issAltKm = state.altitudeKm
            )
        } else null

        return IssSnapshot(
            latitude = state.latitudeDeg,
            longitude = state.longitudeDeg,
            altitudeKm = state.altitudeKm,
            velocityKmh = state.velocityKmh,
            sunlightFactor = sunlight,
            isEclipsed = sunlight < 0.15f,
            sun = sun,
            horizontal = horizontal,
            nextPass = cachedNextPass,
            timestampMillis = timeMillis
        )
    }

    private suspend fun refreshTleFromCelestrak() = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@use
                    val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (lines.size >= 3) {
                        val newTle = Sgp4Propagator.parseTle(lines[1], lines[2])
                        currentTle = newTle
                        propagator = Sgp4Propagator(newTle)
                        saveCachedTle(lines[1], lines[2])
                        recalculateNextPass()
                        Log.i("IssTracker", "Successfully updated ISS TLE from Celestrak")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("IssTracker", "Could not refresh TLE: ${e.message}")
        }
    }

    private fun saveCachedTle(l1: String, l2: String) {
        try {
            val file = File(context.cacheDir, "iss_tle.txt")
            file.writeText("$l1\n$l2")
        } catch (ignored: Exception) {}
    }

    private fun loadCachedTle() {
        try {
            val file = File(context.cacheDir, "iss_tle.txt")
            if (file.exists()) {
                val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.size >= 2) {
                    currentTle = Sgp4Propagator.parseTle(lines[0], lines[1])
                    propagator = Sgp4Propagator(currentTle)
                }
            }
        } catch (ignored: Exception) {}
    }

    fun destroy() {
        scope.cancel()
    }
}
