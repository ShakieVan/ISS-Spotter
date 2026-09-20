package de.shakie.iss.observer

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size
import android.view.Surface
import android.view.View
import androidx.camera.core.CameraEffect
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.effects.OverlayEffect
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Native camera capture. Never records system UI or microphone audio. Main-thread API. */
class ArCaptureSession(
    private val context: Context,
    private val preview: PreviewView,
    private val overlay: View,
    rotation: Int = Surface.ROTATION_0,
    private val changed: () -> Unit,
    private val message: (String) -> Unit
) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor()
    private val main = ContextCompat.getMainExecutor(context)
    private var closed = false
    private var takingPhoto = false
    private var recording: Recording? = null
    private var finalizing = false
    private val afterRecording = mutableListOf<() -> Unit>()
    private var recordedOverlay = false
    var saveOverlay: Boolean = true
    var videoOverlayAvailable: Boolean = true
        private set
    var lastUri: Uri? = null
        private set
    var lastMime: String? = null
        private set
    var status: String = "Video ohne Ton"
        private set
    val isRecording get() = recording != null || finalizing
    val isBusy get() = takingPhoto || isRecording
    val isTakingPhoto get() = takingPhoto

    val imageCapture: ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setFlashMode(ImageCapture.FLASH_MODE_OFF)
        .setTargetRotation(rotation)
        .setResolutionSelector(ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(2560, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
        .build()
    private val recorder = Recorder.Builder()
        .setQualitySelector(QualitySelector.fromOrderedList(listOf(Quality.FHD, Quality.HD, Quality.SD),
            FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
        .build()
    val videoCapture: VideoCapture<Recorder> = VideoCapture.withOutput(recorder).also { it.targetRotation = rotation }
    val effect = OverlayEffect(CameraEffect.VIDEO_CAPTURE, 0, Handler(Looper.getMainLooper())) { error ->
        videoOverlayAvailable = false
        message("Video-Overlay nicht verfügbar: ${error.javaClass.simpleName}")
        stopRecording()
        changed()
    }.apply {
        setOnDrawListener { frame ->
            val canvas = frame.overlayCanvas
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            if (recordedOverlay && isRecording && !drawOverlay(canvas, frame.sensorToBufferTransform)) {
                message("Overlay-Ausrichtung verloren; Aufnahme wird beendet.")
                stopRecording()
                false
            } else true
        }
    }

    fun disableVideoEffect() { videoOverlayAvailable = false; changed() }

    /** Sensor pixel domains bridge PHOTO/VIDEO to PreviewView, not arbitrary bitmap scaling. */
    private fun drawOverlay(canvas: Canvas, sensorToBuffer: Matrix): Boolean {
        val sensorToView = preview.sensorToViewTransform ?: return false
        if (overlay.width < 1 || overlay.height < 1) return false
        val viewToBuffer = CaptureTransform.viewToBuffer(sensorToView, sensorToBuffer) ?: return false
        val save = canvas.save()
        try {
            canvas.concat(viewToBuffer)
            // Overlay and PreviewView have identical bounds; no buttons or telemetry card included.
            overlay.draw(canvas)
        } finally { canvas.restoreToCount(save) }
        return true
    }

    fun takePhoto() {
        if (closed || isBusy) return
        val withOverlay = saveOverlay
        if (withOverlay && preview.sensorToViewTransform == null) { message("Kamera wird noch vorbereitet."); return }
        takingPhoto = true; status = "Foto wird aufgenommen …"; changed()
        try {
            imageCapture.takePicture(main, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    var bitmap: Bitmap? = null
                    try {
                        val rotation = image.imageInfo.rotationDegrees
                        val crop = Rect(image.cropRect)
                        if (closed) throw IllegalStateException("Kamera wurde geschlossen")
                        val decoded = image.toBitmap()
                        try { bitmap = decoded.copy(Bitmap.Config.ARGB_8888, true) }
                        finally { decoded.recycle() }
                        if (withOverlay && !drawOverlay(Canvas(bitmap!!), image.imageInfo.sensorToBufferTransformMatrix)) {
                            throw IllegalStateException("Overlay-Projektion noch nicht verfügbar")
                        }
                        val source = bitmap!!
                        val bounded = Rect(0, 0, source.width, source.height)
                        if (!bounded.intersect(crop)) throw IllegalStateException("Ungültiger Fotoausschnitt")
                        val cropped = Bitmap.createBitmap(source, bounded.left, bounded.top, bounded.width(), bounded.height())
                        if (cropped !== source) source.recycle()
                        bitmap = cropped
                        val oriented = if (rotation == 0) cropped else Bitmap.createBitmap(cropped, 0, 0, cropped.width, cropped.height,
                            Matrix().apply { postRotate(rotation.toFloat()) }, true).also { if (it !== cropped) cropped.recycle() }
                        bitmap = oriented
                        executor.execute {
                            val outcome = runCatching { saveJpeg(oriented) }
                            oriented.recycle()
                            main.execute {
                                finishPhoto()
                                outcome.onSuccess { uri -> lastUri = uri; lastMime = "image/jpeg"; status = "Foto gespeichert" }
                                    .onFailure { status = "Foto fehlgeschlagen"; message("Foto nicht gespeichert: ${it.message}") }
                                changed()
                            }
                        }
                        bitmap = null // Ownership passed to the save worker after successful submission.
                    } catch (e: Exception) {
                        bitmap?.let { if (!it.isRecycled) it.recycle() }; finishPhoto(); status = "Foto fehlgeschlagen"
                        message(e.message ?: "Foto konnte nicht gespeichert werden."); changed()
                    } finally { image.close() }
                }
                override fun onError(exception: ImageCaptureException) {
                    finishPhoto(); status = "Foto fehlgeschlagen"
                    message("Foto fehlgeschlagen: ${exception.message}"); changed()
                }
            })
        } catch (e: Exception) {
            finishPhoto(); status = "Foto fehlgeschlagen"; message(e.message ?: status); changed()
        }
    }

    fun startRecording() {
        if (closed || isBusy) return
        if (saveOverlay && (!videoOverlayAvailable || preview.sensorToViewTransform == null)) {
            message("Video mit Overlay ist noch nicht bereit. Ohne Overlay nur nach Ausschalten der Option."); return
        }
        recordedOverlay = saveOverlay
        val values = mediaValues("ISS_${stamp()}.mp4", "video/mp4", "Movies/ISS-Spotter")
        try {
            val output = MediaStoreOutputOptions.Builder(context.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
                .setContentValues(values).build()
            // Intentionally no withAudioEnabled(): no microphone permission or hidden audio.
            recording = recorder.prepareRecording(context, output).start(main) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> { status = "● 00:00 • ohne Ton"; changed() }
                    is VideoRecordEvent.Status -> {
                        val seconds = event.recordingStats.recordedDurationNanos / 1_000_000_000L
                        status = "● %02d:%02d • ohne Ton".format(Locale.GERMANY, seconds/60, seconds%60)
                        changed()
                    }
                    is VideoRecordEvent.Finalize -> {
                        recording = null; finalizing = false
                        val usablePartial = event.error in setOf(
                            VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE,
                            VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED,
                            VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED,
                            VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE
                        ) && event.recordingStats.recordedDurationNanos > 0L
                        if ((!event.hasError() || usablePartial) && event.outputResults.outputUri != Uri.EMPTY) {
                            lastUri = event.outputResults.outputUri; lastMime = "video/mp4"
                            status = if (event.hasError()) "Video gespeichert (vorzeitig beendet)" else "Video gespeichert"
                            if (event.hasError()) message("$status • Grund ${event.error}")
                        } else {
                            status = "Video fehlgeschlagen (${event.error})"
                            if (event.outputResults.outputUri != Uri.EMPTY) runCatching {
                                context.contentResolver.delete(event.outputResults.outputUri, null, null)
                            }
                            message(status)
                        }
                        changed()
                        val pending = afterRecording.toList(); afterRecording.clear()
                        pending.forEach { it() }
                    }
                }
            }
            status = "Video startet …"; changed()
        } catch (e: Exception) {
            recording = null; finalizing = false; status = "Video konnte nicht starten"
            message("$status: ${e.message}"); changed()
        }
    }

    /** Unbind only AFTER finalization, so mode changes / pause do not truncate the MP4. */
    fun stopRecording(after: (() -> Unit)? = null) {
        if (after != null) afterRecording.add(after)
        val current = recording
        if (current != null && !finalizing) {
            finalizing = true; status = "Video wird gespeichert …"; changed(); current.stop()
        } else if (!finalizing) {
            val pending = afterRecording.toList(); afterRecording.clear(); pending.forEach { it() }
        }
    }

    private fun saveJpeg(bitmap: Bitmap): Uri {
        val resolver = context.contentResolver
        val values = mediaValues("ISS_${stamp()}.jpg", "image/jpeg", "Pictures/ISS-Spotter")
        if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Images.Media.IS_PENDING, 1)
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Medienablage nicht verfügbar")
        try {
            resolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                ?: throw IllegalStateException("Datei konnte nicht geöffnet werden")
            if (Build.VERSION.SDK_INT >= 29) resolver.update(uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) { resolver.delete(uri, null, null); throw e }
    }

    private fun mediaValues(name: String, mime: String, folder: String) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
    }
    private fun stamp() = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())

    private fun finishPhoto() {
        takingPhoto = false
        if (closed) executor.shutdown()
    }

    override fun close() {
        if (closed) return
        closed = true
        stopRecording { effect.close(); if (!takingPhoto) executor.shutdown() }
    }
}
