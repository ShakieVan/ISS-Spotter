package de.shakie.iss.observer

import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import java.util.concurrent.TimeUnit
import kotlin.math.*

@OptIn(ExperimentalCamera2Interop::class)
class ArCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onCameraState: (Boolean) -> Unit
) {
    private val main = ContextCompat.getMainExecutor(context)
    private val handler = Handler(Looper.getMainLooper())
    private val platform = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val characteristics = mutableMapOf<String, CameraCharacteristics>()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewRequested = false
    private var generation = 0
    private var boundId: String? = null
    private var defaultId: String? = null
    private var lensInfos: List<Pair<CameraInfo, AvailableLens>> = emptyList()
    private var selectedLens: AvailableLens? = null
    private var pendingLens: AvailableLens? = null
    private var latestResult: TotalCaptureResult? = null
    private var metadataPending = false
    private var zoomObserver: Observer<ZoomState>? = null
    private var desiredGlobalZoom = 1f
    private var lastTransform: FloatArray? = null
    private var captureOverlay: android.view.View? = null
    private var deferredClose = false
    private var zoomLimitNotified = false

    var forcedZoomRatio: Float? = null
    var onProjectionDataChanged: ((CameraProjectionData) -> Unit)? = null
    var onControlsChanged: (() -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null
    var capture: ArCaptureSession? = null
        private set
    var ready = false
        private set
    var videoAvailable = false
        private set
    var currentProjectionData = CameraProjectionData()
        private set
    val busy get() = capture?.isBusy == true
    val globalZoom: Float get() = (camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f) * (selectedLens?.nativeRatio ?: 1.0).toFloat()
    val globalZoomRange: Pair<Float, Float> get() {
        val list = lensInfos.map { it.second }
        if (list.isEmpty()) return 1f to 1f
        return list.minOf { it.nativeRatio * it.minZoom }.toFloat() to list.maxOf { it.nativeRatio * it.maxZoom }.toFloat()
    }

    init {
        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (camera != null) refreshPreviewTransform()
            else if (previewRequested && previewView.width > 0 && previewView.height > 0) pendingLens?.let(::bind)
        }
        previewView.previewStreamState.observe(lifecycleOwner) { state ->
            ready = previewRequested && camera != null && state == PreviewView.StreamState.STREAMING
            if (ready) { updateProjectionData(); refreshPreviewTransform() }
            onControlsChanged?.invoke()
        }
    }

    fun attachCaptureOverlay(overlay: android.view.View) { captureOverlay = overlay }

    fun startCamera() {
        if (deferredClose) return
        if (capture?.isRecording == true && !previewRequested) {
            previewRequested = true
            val intent = ++generation
            capture?.stopRecording {
                if (previewRequested && generation == intent && !deferredClose) { unbind(); startCamera() }
            }
            return
        }
        if (previewRequested && camera != null) { refreshPreviewTransform(); return }
        previewRequested = true
        val request = ++generation
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!previewRequested || generation != request) return@addListener
            try {
                provider = future.get()
                discoverLenses()
                val chosen = lensInfos.firstOrNull { it.second.id == boundId }?.second
                    ?: lensInfos.firstOrNull { it.second.id == defaultId }?.second
                    ?: error("Keine Rückkamera verfügbar")
                bind(chosen)
            } catch (e: Exception) { fail("Kamera nicht verfügbar", e) }
        }, main)
    }

    private fun chars(id: String): CameraCharacteristics = characteristics.getOrPut(id) { platform.getCameraCharacteristics(id) }
    private fun isLogical(id: String): Boolean = chars(id).get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true

    private fun discoverLenses() {
        val p = provider ?: return
        val infos = CameraSelector.DEFAULT_BACK_CAMERA.filter(p.availableCameraInfos)
        defaultId = infos.firstOrNull()?.let { Camera2CameraInfo.from(it).cameraId }
        lensInfos = infos.mapNotNull { info ->
            val id = Camera2CameraInfo.from(info).cameraId
            val range = info.zoomState.value
            val nativeRatio = info.intrinsicZoomRatio.toDouble().takeIf { it > 0 && it.isFinite() }
                ?: if (id == defaultId) 1.0 else return@mapNotNull null
            info to AvailableLens(id, nativeRatio,
                range?.minZoomRatio?.toDouble() ?: 1.0, range?.maxZoomRatio?.toDouble() ?: 1.0)
        }
    }

    private fun clearZoomObserver() {
        zoomObserver?.let { camera?.cameraInfo?.zoomState?.removeObserver(it) }
        zoomObserver = null
    }

    private fun bind(lens: AvailableLens) {
        try { bindInternal(lens) } catch (e: Exception) { fail("Kamera konnte nicht eingerichtet werden", e) }
    }

    private fun bindInternal(lens: AvailableLens) {
        if (!previewRequested) return
        val p = provider ?: return
        if (previewView.width < 1 || previewView.height < 1 || previewView.viewPort == null) {
            pendingLens = lens
            return
        }
        pendingLens = null
        val oldCapture = capture
        if (oldCapture?.isRecording == true) return // Logical multi-camera switches need no rebind.
        clearZoomObserver()
        oldCapture?.close(); capture = null
        p.unbindAll(); camera = null; ready = false; latestResult = null
        boundId = lens.id; selectedLens = lens
        val request = ++generation
        currentProjectionData = CameraProjectionData(calibrationAccuracy = CalibrationAccuracy.APPROXIMATE_FOCAL_LENGTH,
            viewWidth = previewView.width, viewHeight = previewView.height, isProjectionReady = false,
            displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0)
        onProjectionDataChanged?.invoke(currentProjectionData)
        val builder = Preview.Builder().setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
        val modes = chars(lens.id).get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
        val correction = when {
            modes?.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_FAST) == true -> CameraMetadata.DISTORTION_CORRECTION_MODE_FAST
            modes?.contains(CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY) == true -> CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
            else -> null
        }
        val interop = Camera2Interop.Extender(builder)
        correction?.let { interop.setCaptureRequestOption(CaptureRequest.DISTORTION_CORRECTION_MODE, it) }
        // Electronic stabilization adds an unreported moving crop. Keep the AR viewport geometric.
        interop.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        interop.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, requestInfo: CaptureRequest, result: TotalCaptureResult) {
                main.execute {
                    if (generation != request || !previewRequested) return@execute
                    latestResult = result
                    if (!metadataPending) {
                        metadataPending = true
                        handler.postDelayed({ metadataPending = false; if (generation == request) updateProjectionData() }, 40)
                    }
                }
            }
        })
        val preview = builder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val selector = CameraSelector.Builder().addCameraFilter { list -> list.filter { Camera2CameraInfo.from(it).cameraId == lens.id } }.build()
        val overlay = captureOverlay
        val media = overlay?.let { ArCaptureSession(context, previewView, it, previewView.display?.rotation ?: Surface.ROTATION_0,
            { onControlsChanged?.invoke() }, { text -> onMessage?.invoke(text) }) }
        capture = media
        fun group(withVideo: Boolean, withEffect: Boolean): UseCaseGroup {
            val g = UseCaseGroup.Builder().addUseCase(preview)
            previewView.viewPort?.let(g::setViewPort)
            media?.let {
                g.addUseCase(it.imageCapture)
                if (withVideo) { g.addUseCase(it.videoCapture); if (withEffect) g.addEffect(it.effect) }
            }
            return g.build()
        }
        try {
            var supportsVideo = media != null
            val bound = try { p.bindToLifecycle(lifecycleOwner, selector, group(supportsVideo, supportsVideo)) }
            catch (first: Exception) {
                p.unbindAll(); media?.disableVideoEffect()
                try { p.bindToLifecycle(lifecycleOwner, selector, group(supportsVideo, false)) }
                catch (second: Exception) {
                    p.unbindAll(); supportsVideo = false
                    p.bindToLifecycle(lifecycleOwner, selector, group(false, false))
                }
            }
            camera = bound; videoAvailable = supportsVideo
            zoomObserver = Observer<ZoomState> { if (camera === bound) { updateProjectionData(); onControlsChanged?.invoke() } }
                .also { bound.cameraInfo.zoomState.observe(lifecycleOwner, it) }
            onCameraState(true)
            setZoomOnBound(forcedZoomRatio ?: desiredGlobalZoom)
            updateProjectionData(); onControlsChanged?.invoke()
        } catch (e: Exception) {
            media?.close(); capture = null
            if (lens.id != defaultId && defaultId != null) {
                lensInfos = lensInfos.filter { it.second.id != lens.id }
                onMessage?.invoke("Objektiv nicht gemeinsam mit Aufnahme nutzbar; Standardkamera wird verwendet.")
                lensInfos.firstOrNull { it.second.id == defaultId }?.let { bind(it.second); return }
            }
            fail("Kamerastart fehlgeschlagen", e)
        }
    }

    fun setZoomRatio(zoomRatio: Float) {
        if (!zoomRatio.isFinite() || zoomRatio <= 0f || capture?.isTakingPhoto == true) return
        val range = globalZoomRange
        desiredGlobalZoom = zoomRatio.coerceIn(range.first, range.second)
        forcedZoomRatio = desiredGlobalZoom
        val current = selectedLens ?: return
        val cameraRange = camera?.cameraInfo?.zoomState?.value ?: return
        // Prefer the system logical camera: physical switching then works DURING video too.
        val logicalCovers = current.id == defaultId && isLogical(current.id) && desiredGlobalZoom >= current.nativeRatio*cameraRange.minZoomRatio &&
            desiredGlobalZoom <= current.nativeRatio*cameraRange.maxZoomRatio
        if (capture?.isRecording == true) {
            // Rebinding a separately exposed camera would interrupt the encoder.
            val requested = desiredGlobalZoom
            desiredGlobalZoom = desiredGlobalZoom.coerceIn(
                (current.nativeRatio*cameraRange.minZoomRatio).toFloat(),
                (current.nativeRatio*cameraRange.maxZoomRatio).toFloat())
            forcedZoomRatio = desiredGlobalZoom
            if (requested != desiredGlobalZoom && !zoomLimitNotified) {
                onMessage?.invoke("Während dieses Videos ist nur der Zoombereich der gebundenen Kamera verfügbar.")
                zoomLimitNotified = true
            } else if (requested == desiredGlobalZoom) zoomLimitNotified = false
        }
        if (!logicalCovers && capture?.isRecording != true) {
            val defaultLens = lensInfos.firstOrNull { it.second.id == defaultId }?.second
            val selected = if (defaultLens != null && current.id != defaultId && isLogical(defaultLens.id) &&
                desiredGlobalZoom >= defaultLens.nativeRatio*defaultLens.minZoom*1.08 &&
                desiredGlobalZoom <= defaultLens.nativeRatio*defaultLens.maxZoom) defaultLens
                else LensSelection.select(desiredGlobalZoom.toDouble(), current.id, lensInfos.map { it.second })
            if (selected != null && selected.id != current.id) { bind(selected); return }
        }
        setZoomOnBound(desiredGlobalZoom)
    }
    fun zoomByScale(scale: Float) {
        if (scale.isFinite() && scale > 0) setZoomRatio(desiredGlobalZoom * scale)
    }
    private fun setZoomOnBound(global: Float) {
        val c = camera ?: return
        val range = c.cameraInfo.zoomState.value ?: return
        val value = (global/(selectedLens?.nativeRatio ?: 1.0)).toFloat().coerceIn(range.minZoomRatio, range.maxZoomRatio)
        val future = c.cameraControl.setZoomRatio(value)
        future.addListener({
            runCatching { future.get() }.onFailure { Log.w("ArCameraManager", "Zoom not applied", it) }
            onControlsChanged?.invoke()
        }, main)
    }

    fun focusAt(x: Float, y: Float, finished: (Boolean) -> Unit) {
        val c = camera ?: return
        if (!ready || capture?.isTakingPhoto == true) return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        if (!c.cameraInfo.isFocusMeteringSupported(action)) { finished(false); return }
        val future = c.cameraControl.startFocusAndMetering(action)
        future.addListener({ finished(runCatching { future.get().isFocusSuccessful }.getOrDefault(false)) }, main)
    }

    fun updateProjectionData() {
        val c = camera ?: return
        val id = boundId ?: return
        if (!previewRequested) return
        try {
            val logical = chars(id)
            val result = latestResult
            val physicalId = if (Build.VERSION.SDK_INT >= 29) result?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
            val actualId = physicalId ?: id
            val native = runCatching { chars(actualId) }.getOrDefault(logical)
            val physicalResult = physicalId?.let { result?.physicalCameraResults?.get(it) }
            val logicalActive = logical.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val active = native.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val pre = native.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) ?: active
            val mode = result?.get(CaptureResult.DISTORTION_CORRECTION_MODE)
            val corrected = mode != null && mode != CameraMetadata.DISTORTION_CORRECTION_MODE_OFF
            val nativeArray = if (mode == CameraMetadata.DISTORTION_CORRECTION_MODE_OFF) pre else active
            val rawK = physicalResult?.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
                ?: if (actualId == id) result?.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
                    ?: native.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                else native.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val physicalSize = native.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val focal = physicalResult?.get(CaptureResult.LENS_FOCAL_LENGTH) ?: result?.get(CaptureResult.LENS_FOCAL_LENGTH)
                ?: native.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.singleOrNull()
            val hasK = rawK != null && rawK.size >= 5 && rawK.all { it.isFinite() } && rawK[0] > 0 && rawK[1] > 0
            val sx = nativeArray.width().toDouble()/pre.width()
            val sy = nativeArray.height().toDouble()/pre.height()
            val k = if (hasK) doubleArrayOf(rawK!![0]*sx, rawK[1]*sy, rawK[2]*sx, rawK[3]*sy, rawK[4]*sx)
                else if (focal != null && focal > 0f && physicalSize != null && physicalSize.width > 0 && physicalSize.height > 0)
                    doubleArrayOf(focal/physicalSize.width*nativeArray.width().toDouble(), focal/physicalSize.height*nativeArray.height().toDouble(),
                        nativeArray.width()/2.0, nativeArray.height()/2.0, 0.0) else null
            val applied = c.cameraInfo.zoomState.value?.zoomRatio?.toDouble() ?: 1.0
            val resultZoom = if (Build.VERSION.SDK_INT >= 30) result?.get(CaptureResult.CONTROL_ZOOM_RATIO)?.toDouble() else null
            val reportedPhysicalCrop = if (Build.VERSION.SDK_INT >= 35)
                result?.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_SENSOR_CROP_REGION) else null
            val physicalCrop = reportedPhysicalCrop ?: physicalResult?.get(CaptureResult.SCALER_CROP_REGION)
            var approximateSwitch = false
            val readout = when {
                physicalId != null && physicalCrop != null -> physicalCrop.pixels()
                physicalId == null || physicalId == id -> LensProjectionMath.effectiveReadout(nativeArray.pixels(),
                    result?.get(CaptureResult.SCALER_CROP_REGION)?.pixels(), resultZoom ?: if (result == null) applied else 1.0)
                else -> {
                    // API <35 / vendor without physical crop: estimate residual digital zoom,
                    // never combine the old lens focal length with the new sensor dimensions.
                    approximateSwitch = true
                    val baseSize = logical.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val baseK = logical.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                    val baseFocal = logical.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.singleOrNull()
                    val baseNormalizedFocal = when {
                        baseK != null && baseK.size >= 2 && baseK[0] > 0f -> baseK[0] / logicalActive.width().toDouble()
                        baseSize != null && baseFocal != null && baseFocal > 0 -> (baseFocal/baseSize.width).toDouble()
                        else -> null
                    }
                    val nativeRatio = if (baseNormalizedFocal != null && k != null)
                        (k[0]/nativeArray.width())/baseNormalizedFocal else 1.0
                    LensProjectionMath.effectiveReadout(nativeArray.pixels(), null, (resultZoom ?: applied)/nativeRatio)
                }
            }
            val distortion = physicalResult?.get(CaptureResult.LENS_DISTORTION) ?: result?.get(CaptureResult.LENS_DISTORTION)
                ?: native.get(CameraCharacteristics.LENS_DISTORTION)
            val rawDistortion = if (mode == CameraMetadata.DISTORTION_CORRECTION_MODE_OFF && hasK)
                distortion?.map { it.toDouble() } ?: emptyList() else emptyList()
            val logicalOrientation = logical.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val calibration = k?.let {
                LensRayCalibration(it[0],it[1],it[2],it[3],it[4], nativeArray.pixels(), logicalActive.pixels(), readout,
                    native.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: logicalOrientation, logicalOrientation, rawDistortion)
            }
            val transform = previewView.sensorToViewTransform?.let(::Matrix)
            // Corrected streams may still include vendor-specific warps/optical-axis alignment.
            val exactIntrinsics = hasK && !approximateSwitch && (pre == active) && mode != null
            val correctionLabel = when {
                corrected -> "entzerrte Vorschau"
                rawDistortion.size == 5 -> "Verzeichnung berücksichtigt"
                else -> "Verzeichnung nicht kalibriert"
            }
            currentProjectionData = CameraProjectionData(
                calibrationAccuracy = if (exactIntrinsics) CalibrationAccuracy.CALIBRATED_INTRINSICS else CalibrationAccuracy.APPROXIMATE_FOCAL_LENGTH,
                focalLengthMm = focal, sensorPhysicalSize = physicalSize, activeArraySize = logicalActive,
                sensorOrientation = logicalOrientation, lensFacing = CameraCharacteristics.LENS_FACING_BACK,
                currentZoomRatio = applied.toFloat(), sensorToViewTransform = transform,
                viewWidth = previewView.width, viewHeight = previewView.height,
                displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0,
                lensRayCalibration = calibration, isProjectionReady = transform != null && result != null,
                calibrationNote = "Objektiv $actualId • $correctionLabel${if (approximateSwitch || !hasK) " • Näherung" else ""}"
            )
            lastTransform = transform?.let { FloatArray(9).also(it::getValues) }
            onProjectionDataChanged?.invoke(currentProjectionData)
            onControlsChanged?.invoke()
        } catch (e: Exception) { Log.w("ArCameraManager", "Camera metadata unavailable", e) }
    }

    fun refreshPreviewTransform() {
        if (!previewRequested || camera == null) return
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        if (rotation != currentProjectionData.displayRotation) {
            if (capture?.isRecording == true) { capture?.stopRecording { selectedLens?.let(::bind) }; return }
            selectedLens?.let(::bind); return
        }
        val transform = previewView.sensorToViewTransform?.let(::Matrix)
        val values = transform?.let { FloatArray(9).also(it::getValues) }
        if (values?.contentEquals(lastTransform ?: FloatArray(0)) == true &&
            previewView.width == currentProjectionData.viewWidth && previewView.height == currentProjectionData.viewHeight) return
        lastTransform = values
        currentProjectionData = currentProjectionData.copy(sensorToViewTransform = transform,
            viewWidth = previewView.width, viewHeight = previewView.height,
            isProjectionReady = transform != null && latestResult != null)
        onProjectionDataChanged?.invoke(currentProjectionData)
    }

    fun stopCamera() {
        previewRequested = false; ++generation; ready = false; pendingLens = null
        clearZoomObserver()
        capture?.stopRecording { if (!previewRequested) unbind() } ?: unbind()
        onControlsChanged?.invoke()
    }
    private fun unbind() {
        clearZoomObserver(); camera = null; lastTransform = null
        runCatching { provider?.unbindAll() }
        capture?.close(); capture = null
        if (deferredClose) handler.removeCallbacksAndMessages(null)
    }
    fun destroy() { deferredClose = true; stopCamera() }
    private fun fail(text: String, e: Exception) {
        ready = false; previewRequested = false; ++generation
        unbind()
        Log.w("ArCameraManager", text, e)
        onMessage?.invoke("$text: ${e.message}"); onCameraState(false); onControlsChanged?.invoke()
    }
    private fun Rect.pixels() = PixelRect(left.toDouble(), top.toDouble(), right.toDouble(), bottom.toDouble())
}
