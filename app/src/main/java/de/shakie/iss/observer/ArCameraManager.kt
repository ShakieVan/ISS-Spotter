package de.shakie.iss.observer

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

class ArCameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onCameraState: (isCameraActive: Boolean) -> Unit
) {

    private var cameraProvider: ProcessCameraProvider? = null
    private var activeCamera: Camera? = null
    private var currentZoomRatio: Float = 1.0f

    var forcedZoomRatio: Float? = null
    var onProjectionDataChanged: ((CameraProjectionData) -> Unit)? = null
    var currentProjectionData: CameraProjectionData = CameraProjectionData()
        private set

    init {
        previewView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateProjectionData()
        }
    }

    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraPreview()
            } catch (e: Exception) {
                Log.w("ArCameraManager", "Could not initialize camera: ${e.message}")
                onCameraState(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraPreview() {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                CameraSelector.DEFAULT_BACK_CAMERA
            } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                null
            }

            if (cameraSelector != null) {
                val camera = provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                activeCamera = camera

                camera.cameraInfo.zoomState.observe(lifecycleOwner) { zoomState ->
                    if (zoomState != null) {
                        currentZoomRatio = forcedZoomRatio ?: zoomState.zoomRatio
                        updateProjectionData()
                    }
                }

                onCameraState(true)
                Log.i("ArCameraManager", "CameraX preview bound successfully")
                updateProjectionData()
            } else {
                Log.w("ArCameraManager", "No camera found on device")
                onCameraState(false)
            }
        } catch (e: Exception) {
            Log.e("ArCameraManager", "Camera binding failed: ${e.message}", e)
            onCameraState(false)
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    fun updateProjectionData() {
        val camera = activeCamera
        if (camera == null) {
            val fallback = CameraProjectionData(
                calibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY,
                viewWidth = previewView.width,
                viewHeight = previewView.height,
                displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            )
            currentProjectionData = fallback
            onProjectionDataChanged?.invoke(fallback)
            return
        }

        try {
            val cam2Info = Camera2CameraInfo.from(camera.cameraInfo)

            val activeArray = cam2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val intrinsics = cam2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
            val focalLengths = cam2Info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            val physSize = cam2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val sensorOrient = cam2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val lensFacing = cam2Info.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) ?: 1
            val distModes = cam2Info.getCameraCharacteristic(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
            val lensDist = cam2Info.getCameraCharacteristic(CameraCharacteristics.LENS_DISTORTION)

            // Select focal length: if multiple available, pick typical standard focal length or middle
            val chosenFocalLength = if (focalLengths != null && focalLengths.isNotEmpty()) {
                if (focalLengths.size == 1) {
                    focalLengths[0]
                } else {
                    // Filter for standard wide focal length (~4.0 - 6.0 mm), else first
                    focalLengths.firstOrNull { it in 3.5f..7.0f } ?: focalLengths[0]
                }
            } else {
                null
            }

            val accuracy = if (intrinsics != null && intrinsics.size >= 4) {
                CalibrationAccuracy.CALIBRATED_INTRINSICS
            } else if (chosenFocalLength != null && physSize != null) {
                CalibrationAccuracy.APPROXIMATE_FOCAL_LENGTH
            } else {
                CalibrationAccuracy.APPROXIMATE_FOCAL_LENGTH
            }

            val displayRot = previewView.display?.rotation ?: Surface.ROTATION_0
            val transformMatrix = previewView.sensorToViewTransform

            val projData = CameraProjectionData(
                calibrationAccuracy = accuracy,
                focalLengthMm = chosenFocalLength,
                sensorPhysicalSize = physSize,
                activeArraySize = activeArray,
                sensorOrientation = sensorOrient,
                lensFacing = lensFacing,
                intrinsicCalibration = intrinsics,
                distortionModes = distModes,
                lensDistortion = lensDist,
                currentZoomRatio = currentZoomRatio,
                sensorToViewTransform = transformMatrix,
                viewWidth = previewView.width,
                viewHeight = previewView.height,
                displayRotation = displayRot
            )

            currentProjectionData = projData
            onProjectionDataChanged?.invoke(projData)
            Log.d("ArCameraManager", "Updated CameraProjectionData: accuracy=$accuracy, f=${chosenFocalLength}mm, zoom=${currentZoomRatio}x, view=${previewView.width}x${previewView.height}")
        } catch (e: Exception) {
            Log.w("ArCameraManager", "Error extracting camera characteristics: ${e.message}")
        }
    }

    fun setZoomRatio(zoomRatio: Float) {
        forcedZoomRatio = zoomRatio
        currentZoomRatio = zoomRatio.coerceAtLeast(1.0f)
        try {
            activeCamera?.cameraControl?.setZoomRatio(currentZoomRatio)
        } catch (e: Exception) {
            Log.w("ArCameraManager", "Could not set zoom ratio on camera: ${e.message}")
        }
        updateProjectionData()
    }

    fun stopCamera() {
        try {
            activeCamera = null
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w("ArCameraManager", "Error stopping camera: ${e.message}")
        }
    }
}
