package de.shakie.iss.observer

import android.content.Context
import android.util.Log
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

    private fun bindCameraPreview() {
        val provider = cameraProvider ?: return
        try {
            provider.unbindAll()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            if (provider.hasCamera(cameraSelector)) {
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                onCameraState(true)
                Log.i("ArCameraManager", "CameraX preview bound successfully")
            } else {
                Log.w("ArCameraManager", "No back camera found (e.g. running in basic emulator)")
                onCameraState(false)
            }
        } catch (e: Exception) {
            Log.w("ArCameraManager", "Camera binding failed: ${e.message}")
            onCameraState(false)
        }
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
    }
}
