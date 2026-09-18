package de.shakie.iss.weather

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

data class SatelliteInfo(
    val file: File,
    val dateUtc: String,
    val layerName: String,
    val provider: String,
    val resolution: String = "2048x1024"
)

class GibsSatelliteDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _satelliteInfoFlow = MutableStateFlow<SatelliteInfo?>(null)
    val satelliteInfoFlow: StateFlow<SatelliteInfo?> = _satelliteInfoFlow

    private val _isDownloadingFlow = MutableStateFlow<Boolean>(false)
    val isDownloadingFlow: StateFlow<Boolean> = _isDownloadingFlow

    private val satelliteCacheFile = File(context.cacheDir, "gibs_satellite_viirs.jpg")
    private val metaFile = File(context.cacheDir, "gibs_satellite_meta.json")

    init {
        // 1. Immediately emit cached satellite image if available
        loadCachedInfo()

        // 2. Fetch fresh satellite image in the background
        scope.launch {
            fetchLatestSatelliteImage()
            // Check once every 6 hours
            while (isActive) {
                delay(6 * 3600 * 1000L)
                fetchLatestSatelliteImage()
            }
        }
    }

    private fun loadCachedInfo() {
        if (satelliteCacheFile.exists() && satelliteCacheFile.length() > 50_000) {
            val dateUtc = if (metaFile.exists()) {
                try {
                    JSONObject(metaFile.readText()).optString("dateUtc", "Archiv")
                } catch (e: Exception) {
                    "Archiv"
                }
            } else "Archiv"

            _satelliteInfoFlow.value = SatelliteInfo(
                file = satelliteCacheFile,
                dateUtc = dateUtc,
                layerName = "VIIRS_NOAA20_CorrectedReflectance_TrueColor",
                provider = "NASA GIBS / EOSDIS"
            )
            Log.i("GibsSatelliteDownloader", "Loaded cached NASA GIBS satellite mosaic from ${satelliteCacheFile.absolutePath} ($dateUtc)")
        }
    }

    suspend fun fetchLatestSatelliteImage(forceDownload: Boolean = false) = withContext(Dispatchers.IO) {
        if (_isDownloadingFlow.value) return@withContext
        _isDownloadingFlow.value = true

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

        // For a full, 100% complete global mosaic without orbital scan gaps,
        // the previous UTC day is the most reliable complete composite.
        cal.add(Calendar.DAY_OF_MONTH, -1)
        val targetDateStr = dateFormat.format(cal.time)

        // Check if cached file is already up to date for this target date
        if (!forceDownload && _satelliteInfoFlow.value?.dateUtc == targetDateStr && satelliteCacheFile.exists()) {
            Log.i("GibsSatelliteDownloader", "Cached satellite image already matches target date $targetDateStr")
            _isDownloadingFlow.value = false
            return@withContext
        }

        val layerName = "VIIRS_NOAA20_CorrectedReflectance_TrueColor"
        val wmsUrl = "https://gibs.earthdata.nasa.gov/wms/epsg4326/best/wms.cgi" +
                "?SERVICE=WMS&REQUEST=GetMap&VERSION=1.1.1" +
                "&LAYERS=$layerName" +
                "&SRS=EPSG:4326&BBOX=-180,-90,180,90" +
                "&WIDTH=2048&HEIGHT=1024" +
                "&FORMAT=image/jpeg" +
                "&TIME=$targetDateStr"

        Log.i("GibsSatelliteDownloader", "Requesting NASA GIBS satellite mosaic: $wmsUrl")

        try {
            val request = Request.Builder().url(wmsUrl).build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bytes = response.body?.bytes()
                    if (bytes != null && bytes.size > 50_000) {
                        val tempFile = File(context.cacheDir, "gibs_satellite_tmp.jpg")
                        FileOutputStream(tempFile).use { it.write(bytes) }

                        // Verify JPEG integrity
                        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(tempFile.absolutePath, opts)
                        if (opts.outWidth > 0 && opts.outHeight > 0) {
                            tempFile.renameTo(satelliteCacheFile)

                            // Save metadata
                            val metaJson = JSONObject().apply {
                                put("dateUtc", targetDateStr)
                                put("layerName", layerName)
                                put("provider", "NASA GIBS / EOSDIS")
                                put("width", opts.outWidth)
                                put("height", opts.outHeight)
                                put("downloadedAt", System.currentTimeMillis())
                            }
                            metaFile.writeText(metaJson.toString())

                            val info = SatelliteInfo(
                                file = satelliteCacheFile,
                                dateUtc = targetDateStr,
                                layerName = layerName,
                                provider = "NASA GIBS / EOSDIS",
                                resolution = "${opts.outWidth}x${opts.outHeight}"
                            )
                            _satelliteInfoFlow.value = info
                            Log.i("GibsSatelliteDownloader", "Successfully downloaded NASA GIBS mosaic ($targetDateStr, ${opts.outWidth}x${opts.outHeight}, ${bytes.size / 1024} KB)")
                        } else {
                            tempFile.delete()
                        }
                    }
                } else {
                    Log.w("GibsSatelliteDownloader", "HTTP Error ${response.code} from NASA GIBS")
                }
            }
        } catch (e: Exception) {
            Log.w("GibsSatelliteDownloader", "Failed to download NASA GIBS satellite mosaic: ${e.message}")
        } finally {
            _isDownloadingFlow.value = false
        }
    }

    fun getActiveSatelliteFile(): File? {
        return if (satelliteCacheFile.exists() && satelliteCacheFile.length() > 50_000) {
            satelliteCacheFile
        } else null
    }

    fun destroy() {
        scope.cancel()
    }
}
