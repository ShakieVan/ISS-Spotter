package de.shakie.iss.observer

import android.content.Context
import android.graphics.Matrix
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer

class ArCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onCameraState: (isCameraActive: Boolean) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var currentZoomRatio: Float = 1.0f
    private var previewRequested = false
    private var requestGeneration = 0
    private var zoomObserver: Observer<ZoomState>? = null
    private var lastTransformValues: FloatArray? = null

    var forcedZoomRatio: Float? = null
    var onProjectionDataChanged: ((CameraProjectionData) -> Unit)? = null
    var currentProjectionData: CameraProjectionData = CameraProjectionData()
        private set

    init {
        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (activeCamera != null) updateProjectionData()
        }
        previewView.previewStreamState.observe(lifecycleOwner) { state ->
            if (state == PreviewView.StreamState.STREAMING) refreshPreviewTransform()
        }
    }

    fun startCamera() {
        if (previewRequested && activeCamera != null) {
            refreshPreviewTransform()
            return
        }
        previewRequested = true
        val generation = ++requestGeneration
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            // Do not reopen the camera after the user already selected sky/orbit or paused.
            if (!previewRequested || generation != requestGeneration) return@addListener
            try {
                cameraProvider = future.get()
                bindCameraPreview()
            } catch (e: Exception) {
                Log.w("ArCameraManager", "Could not initialize camera: ${e.message}")
                onCameraState(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun removeZoomObserver() {
        zoomObserver?.let { activeCamera?.cameraInfo?.zoomState?.removeObserver(it) }
        zoomObserver = null
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraPreview() {
        val provider = cameraProvider ?: return
        try {
            removeZoomObserver()
            activeCamera = null
            provider.unbindAll()
            // The orientation model describes the rear optical axis. Never silently use a selfie camera.
            if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                Log.w("ArCameraManager", "Rear camera unavailable; using virtual sky")
                onCameraState(false)
                return
            }
            val preview = Preview.Builder()
                .setTargetRotation(previewView.display?.rotation ?: Surface.ROTATION_0)
                .build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            activeCamera = camera
            currentZoomRatio = camera.cameraInfo.zoomState.value?.zoomRatio ?: 1f
            zoomObserver = Observer<ZoomState> { state ->
                if (state != null && activeCamera === camera && previewRequested) {
                    // Use the applied zoom, not an unconfirmed request.
                    currentZoomRatio = state.zoomRatio
                    updateProjectionData()
                }
            }.also { camera.cameraInfo.zoomState.observe(lifecycleOwner, it) }
            onCameraState(true)
            updateProjectionData()
            forcedZoomRatio?.let { setZoomRatio(it) }
            Log.i("ArCameraManager", "CameraX rear preview bound successfully")
        } catch (e: Exception) {
            removeZoomObserver()
            activeCamera = null
            Log.e("ArCameraManager", "Camera binding failed: ${e.message}", e)
            onCameraState(false)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun updateProjectionData() {
        val camera = activeCamera ?: return
        if (!previewRequested) return
        try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val active = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val preCorrection = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
            val rawIntrinsics = info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            // Camera2 intrinsics use the pre-correction pixel grid. Do not mix different grids.
            // Full device-specific distortion/pose calibration remains outside this approximation.
            val intrinsics = rawIntrinsics?.takeIf {
                it.size >= 4 && it.take(4).all { value -> value.isFinite() } && it[0] > 0f && it[1] > 0f &&
                    active != null && preCorrection != null && active == preCorrection
            }?.clone()
            val focalLengths = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val size = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            // An available-lens list does not identify the currently active physical lens.
            val focal = focalLengths?.filter { it.isFinite() && it > 0f }?.distinct()?.singleOrNull()
            val accuracy = if (intrinsics != null) CalibrationAccuracy.CALIBRATED_INTRINSICS
                else CalibrationAccuracy.APPROXIMATE_FOCAL_LENGTH
            val transform = previewView.sensorToViewTransform?.let { Matrix(it) }
            val data = CameraProjectionData(
                calibrationAccuracy = accuracy,
                focalLengthMm = focal,
                sensorPhysicalSize = size,
                activeArraySize = active,
                sensorOrientation = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                lensFacing = info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ?: 1,
                intrinsicCalibration = intrinsics,
                distortionModes = info.getCameraCharacteristic(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES),
                lensDistortion = info.getCameraCharacteristic(CameraCharacteristics.LENS_DISTORTION),
                currentZoomRatio = currentZoomRatio,
                sensorToViewTransform = transform,
                viewWidth = previewView.width,
                viewHeight = previewView.height,
                displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            )
            currentProjectionData = data
            lastTransformValues = transform?.let { FloatArray(9).also(it::getValues) }
            onProjectionDataChanged?.invoke(data)
            Log.d("ArCameraManager", "Camera ${info.cameraId}: $accuracy, focal=$focal, zoom=$currentZoomRatio, preview=${data.viewWidth}x${data.viewHeight}; distortion/pose not fully calibrated")
        } catch (e: Exception) {
            Log.w("ArCameraManager", "Error extracting camera characteristics: ${e.message}")
        }
    }

    /** UI-thread update immediately before projection; CameraX transforms become ready asynchronously. */
    fun refreshPreviewTransform() {
        if (!previewRequested || activeCamera == null) return
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        if (rotation != currentProjectionData.displayRotation) {
            bindCameraPreview()
            return
        }
        val transform = previewView.sensorToViewTransform?.let { Matrix(it) }
        val values = transform?.let { FloatArray(9).also(it::getValues) }
        val changed = when {
            values == null -> lastTransformValues != null
            lastTransformValues == null -> true
            else -> !values.contentEquals(lastTransformValues!!)
        }
        if (!changed && previewView.width == currentProjectionData.viewWidth &&
            previewView.height == currentProjectionData.viewHeight) return
        lastTransformValues = values
        currentProjectionData = currentProjectionData.copy(
            sensorToViewTransform = transform,
            viewWidth = previewView.width,
            viewHeight = previewView.height,
            displayRotation = rotation
        )
        onProjectionDataChanged?.invoke(currentProjectionData)
    }

    fun setZoomRatio(zoomRatio: Float) {
        if (!zoomRatio.isFinite() || zoomRatio <= 0f) return
        forcedZoomRatio = zoomRatio
        val camera = activeCamera ?: return
        val zoomState = camera.cameraInfo.zoomState.value ?: return
        val requested = zoomRatio.coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
        try {
            val future = camera.cameraControl.setZoomRatio(requested)
            future.addListener({
                try { future.get() } catch (e: Exception) {
                    Log.w("ArCameraManager", "Zoom request not applied: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            Log.w("ArCameraManager", "Could not set zoom ratio: ${e.message}")
        }
    }

    fun stopCamera() {
        previewRequested = false
        requestGeneration++
        removeZoomObserver()
        activeCamera = null
        lastTransformValues = null
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w("ArCameraManager", "Error stopping camera: ${e.message}")
        }
    }
}
