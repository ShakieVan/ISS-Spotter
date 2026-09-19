package de.shakie.iss.orbit

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class IssSnapshot(
    val latitude: Double,
    val longitude: Double,
    val altitudeKm: Double,
    val velocityKmh: Double,
    val sunlightFactor: Float,
    val isEclipsed: Boolean,
    val sun: SunPosition,
    val horizontal: HorizontalCoordinates?,
    val nextPass: IssPass?,
    val timestampMillis: Long,
    val observerLat: Double = 52.5200,
    val observerLon: Double = 13.4050,
    val trajectory: OrbitTrajectory? = null
)

class IssTracker(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private var currentTle = Tle(
        satNumber = 25544, epochYear = 26, epochDay = 259.85263506,
        bstar = 0.00013566, inclinationDeg = 51.6307, raanDeg = 206.4210,
        eccentricity = 0.0004838, argPerigeeDeg = 147.2470, meanAnomalyDeg = 212.8820,
        meanMotionRevsPerDay = 15.49143506, revNumber = 58596
    )
    @Volatile private var propagator = Sgp4Propagator(currentTle)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var cachedNextPass: IssPass? = null
    @Volatile private var lastPassCalcMillis = 0L

    var observerLat: Double? = 52.5200
        set(value) { field = value; recalculateNextPass() }
    var observerLon: Double? = 13.4050
        set(value) { field = value; recalculateNextPass() }
    var observerAltKm: Double = 0.05

    var trajectoryEnabled: Boolean = true
    private data class CachedTrajectory(val model: Sgp4Propagator, val data: OrbitTrajectory)
    @Volatile private var cachedTrajectory: CachedTrajectory? = null
    private var trajectoryJob: Job? = null

    private val _snapshotFlow = MutableStateFlow(computeSnapshot())
    val snapshotFlow: StateFlow<IssSnapshot> = _snapshotFlow.asStateFlow()

    init {
        loadCachedTle()
        recalculateNextPass()
        scope.launch {
            refreshTleFromCelestrak()
            while (isActive) { delay(2 * 3600 * 1000L); refreshTleFromCelestrak() }
        }
    }

    private fun recalculateNextPass() {
        val lat = observerLat ?: return
        val lon = observerLon ?: return
        val now = System.currentTimeMillis()
        if (cachedNextPass == null || now > (cachedNextPass?.setTimeMillis ?: 0L) || now - lastPassCalcMillis > 10 * 60000L) {
            val model = propagator
            val alt = observerAltKm
            scope.launch(Dispatchers.Default) {
                val pass = PassPredictor.findNextPass(model, lat, lon, alt, now)
                if (propagator === model) { cachedNextPass = pass; lastPassCalcMillis = now }
            }
        }
    }

    /** Cached on a worker, never propagate an entire orbit in the per-frame UI draw call. */
    private fun refreshTrajectory(time: Long) {
        if (!trajectoryEnabled) return
        val prefs = context.getSharedPreferences("trajectory_options", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("orbit", true) && !prefs.getBoolean("observer", true)) return
        val lat = observerLat ?: return
        val lon = observerLon ?: return
        val alt = observerAltKm
        val model = propagator
        val cached = cachedTrajectory
        val old = cached?.data
        if (cached?.model === model && old != null && old.isUsable(time, lat, lon) &&
            abs(time - old.centerTimeMillis) < 5000 && abs(alt - old.observerAltKm) < 0.001) return
        if (trajectoryJob?.isActive == true) return
        trajectoryJob = scope.launch(Dispatchers.Default) {
            val points = TrajectorySampler.sample(time, lat, lon, alt) { t ->
                ensureActive()
                model.propagate(t)
            }
            if (model === propagator) {
                // One immutable publication: an old orbit can never be paired with a new TLE.
                cachedTrajectory = CachedTrajectory(model, points)
            }
        }
    }

    fun updateFrame(timeMillis: Long = System.currentTimeMillis()): IssSnapshot {
        refreshTrajectory(timeMillis)
        val snapshot = computeSnapshot(timeMillis)
        _snapshotFlow.value = snapshot
        return snapshot
    }

    private fun computeSnapshot(timeMillis: Long = System.currentTimeMillis()): IssSnapshot {
        val model = propagator
        val state = model.propagate(timeMillis)
        val sun = SolarCoordinates.calculate(timeMillis)
        val sunlight = EclipseCalculator.getSunlightFactor(state.latitudeDeg, state.longitudeDeg, state.altitudeKm, sun)
        val lat = observerLat
        val lon = observerLon
        val horizontal = if (lat != null && lon != null) {
            TopocentricPosition.calculate(lat, lon, observerAltKm, state.latitudeDeg, state.longitudeDeg, state.altitudeKm)
        } else null
        val trail = cachedTrajectory?.takeIf {
            trajectoryEnabled && it.model === model && lat != null && lon != null &&
                it.data.isUsable(timeMillis, lat, lon) && abs(it.data.observerAltKm - observerAltKm) < 0.001
        }?.data
        return IssSnapshot(
            latitude = state.latitudeDeg, longitude = state.longitudeDeg, altitudeKm = state.altitudeKm,
            velocityKmh = state.velocityKmh, sunlightFactor = sunlight, isEclipsed = sunlight < 0.15f,
            sun = sun, horizontal = horizontal, nextPass = cachedNextPass, timestampMillis = timeMillis,
            observerLat = lat ?: 52.5200, observerLon = lon ?: 13.4050, trajectory = trail
        )
    }

    private suspend fun refreshTleFromCelestrak() = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE").build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val lines = resp.body?.string()?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return@use
                    if (lines.size >= 3) {
                        val newTle = Sgp4Propagator.parseTle(lines[1], lines[2])
                        currentTle = newTle
                        propagator = Sgp4Propagator(newTle)
                        cachedTrajectory = null
                        saveCachedTle(lines[1], lines[2])
                        recalculateNextPass()
                        Log.i("IssTracker", "Successfully updated ISS TLE from Celestrak")
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w("IssTracker", "Could not refresh TLE: ${e.message}")
        }
    }

    private fun saveCachedTle(l1: String, l2: String) {
        try { File(context.cacheDir, "iss_tle.txt").writeText("$l1\n$l2") } catch (ignored: Exception) {}
    }
    private fun loadCachedTle() {
        try {
            val file = File(context.cacheDir, "iss_tle.txt")
            if (file.exists()) {
                val lines = file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
                if (lines.size >= 2) { currentTle = Sgp4Propagator.parseTle(lines[0], lines[1]); propagator = Sgp4Propagator(currentTle) }
            }
        } catch (ignored: Exception) {}
    }
    fun destroy() { scope.cancel() }
}
