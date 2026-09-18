package de.shakie.iss.weather

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class LiveCloudDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _cloudUpdateFlow = MutableSharedFlow<File>(replay = 1)
    val cloudUpdateFlow: SharedFlow<File> = _cloudUpdateFlow

    private val _isLiveFlow = kotlinx.coroutines.flow.MutableStateFlow<Boolean>(false)
    val isLiveFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _isLiveFlow
    val isLive: Boolean get() = _isLiveFlow.value

    private val _metadataFlow = kotlinx.coroutines.flow.MutableStateFlow(
        de.shakie.iss.graphics.CloudMetadata()
    )
    val metadataFlow: kotlinx.coroutines.flow.StateFlow<de.shakie.iss.graphics.CloudMetadata> = _metadataFlow

    private val cloudCacheFile = File(context.cacheDir, "live_clouds.jpg")

    init {
        // If cache already exists from a previous session, activate it immediately
        if (cloudCacheFile.exists() && cloudCacheFile.length() > 10000) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(cloudCacheFile.absolutePath, opts)
            _metadataFlow.value = de.shakie.iss.graphics.CloudMetadata(
                provider = "Matteason (Testquelle)",
                cacheFileName = cloudCacheFile.name,
                dimensions = "${opts.outWidth}x${opts.outHeight}",
                downloadTimeMillis = cloudCacheFile.lastModified(),
                observationTime = "Aufnahmezeit: Unbekannt"
            )
            _isLiveFlow.value = true
            _cloudUpdateFlow.tryEmit(cloudCacheFile)
        }

        scope.launch {
            // Initial check/download
            fetchLiveClouds()
            // Check every 3 hours
            while (isActive) {
                delay(3 * 3600 * 1000L)
                fetchLiveClouds()
            }
        }
    }

    suspend fun fetchLiveClouds() = withContext(Dispatchers.IO) {
        val urls = listOf(
            "https://clouds.matteason.co.uk/images/4096x2048/clouds.jpg",
            "https://clouds.matteason.co.uk/images/2048x1024/clouds.jpg"
        )
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .build()

                val succeeded = client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty()) {
                            val tempFile = File(context.cacheDir, "live_clouds_tmp.jpg")
                            FileOutputStream(tempFile).use { it.write(bytes) }

                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(tempFile.absolutePath, opts)
                            if (opts.outWidth > 0 && opts.outHeight > 0) {
                                tempFile.renameTo(cloudCacheFile)
                                val meta = de.shakie.iss.graphics.CloudMetadata(
                                    provider = "Matteason (Testquelle)",
                                    cacheFileName = cloudCacheFile.name,
                                    dimensions = "${opts.outWidth}x${opts.outHeight}",
                                    downloadTimeMillis = System.currentTimeMillis(),
                                    observationTime = "Aufnahmezeit: Unbekannt"
                                )
                                _metadataFlow.value = meta
                                Log.i("LiveCloudDownloader", "Downloaded fresh satellite cloud map (${opts.outWidth}x${opts.outHeight}) from $url. Provider: ${meta.provider}, ${meta.observationTime}")
                                _isLiveFlow.value = true
                                _cloudUpdateFlow.emit(cloudCacheFile)
                                true
                            } else {
                                tempFile.delete()
                                false
                            }
                        } else false
                    } else false
                }
                if (succeeded) break
            } catch (e: Exception) {
                Log.w("LiveCloudDownloader", "Cloud map download from $url skipped: ${e.message}")
            }
        }
    }

    fun getActiveCloudFile(): File? {
        return if (cloudCacheFile.exists() && cloudCacheFile.length() > 10000) {
            cloudCacheFile
        } else null
    }

    fun destroy() {
        scope.cancel()
    }
}
