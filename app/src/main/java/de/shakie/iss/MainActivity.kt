package de.shakie.iss

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.util.Log
import android.view.Choreographer
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import de.shakie.iss.databinding.ActivityMainBinding
import de.shakie.iss.observer.ArCameraManager
import de.shakie.iss.observer.DeviceOrientation
import de.shakie.iss.observer.OrientationSensorHelper
import de.shakie.iss.orbit.IssSnapshot
import de.shakie.iss.orbit.IssTracker
import de.shakie.iss.weather.LiveCloudDownloader
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {

    private lateinit var binding: ActivityMainBinding

    private lateinit var issTracker: IssTracker
    private lateinit var cloudDownloader: LiveCloudDownloader
    private lateinit var orientationHelper: OrientationSensorHelper
    private var arCameraManager: ArCameraManager? = null

    private var currentOrientation = DeviceOrientation(0f, 0f, 0f, FloatArray(16))
    private var bordersVisible = true
    private var cloudsVisible = true
    private var isObserverMode = false
    private var locationListener: LocationListener? = null

    // Debug control for automated testing & angle inspection via ADB
    private var debugTimeOverride: Long? = null
    private var debugFreeze: Boolean = false

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.hasExtra("reset") && intent.getBooleanExtra("reset", false)) {
                binding.filamentView.resetCameraView()
                debugTimeOverride = null
                debugFreeze = false
                Log.i("MainActivity", "Debug: reset camera and time override")
                return
            }
            if (intent.hasExtra("yaw")) {
                binding.filamentView.cameraController.yawOffsetDeg = intent.getFloatExtra("yaw", 0f)
            }
            if (intent.hasExtra("pitch")) {
                binding.filamentView.cameraController.pitchOffsetDeg = intent.getFloatExtra("pitch", 0f)
            }
            if (intent.hasExtra("zoom")) {
                binding.filamentView.cameraController.zoomFactor = intent.getFloatExtra("zoom", 1f)
            }
            if (intent.hasExtra("time")) {
                debugTimeOverride = intent.getLongExtra("time", System.currentTimeMillis())
            }
            if (intent.hasExtra("freeze")) {
                debugFreeze = intent.getBooleanExtra("freeze", false)
            }
            if (intent.hasExtra("clouds")) {
                cloudsVisible = intent.getBooleanExtra("clouds", true)
                binding.filamentView.setCloudVisibility(cloudsVisible)
                updateCloudButtonText()
            }
            if (intent.hasExtra("borders")) {
                bordersVisible = intent.getBooleanExtra("borders", true)
                binding.filamentView.setBorderVisibility(bordersVisible)
                updateBorderButtonText()
            }
            binding.filamentView.onCameraModified?.invoke(binding.filamentView.cameraController.isModified())
            Log.i("MainActivity", "Debug scene updated: yaw=${binding.filamentView.cameraController.yawOffsetDeg}, pitch=${binding.filamentView.cameraController.pitchOffsetDeg}, zoom=${binding.filamentView.cameraController.zoomFactor}, time=$debugTimeOverride, freeze=$debugFreeze")
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            setupLocationUpdates()
        }
        if (permissions[Manifest.permission.CAMERA] == true) {
            if (isObserverMode && !binding.calloutOverlayView.showVirtualSky) {
                startCameraPreview()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val sysBars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            )
            val density = resources.displayMetrics.density
            binding.telemetryCard.layoutParams = (binding.telemetryCard.layoutParams as android.view.ViewGroup.MarginLayoutParams).apply {
                topMargin = sysBars.top + (8 * density).toInt()
            }
            binding.bottomNavContainer.setPadding(
                (16 * density).toInt(),
                (10 * density).toInt(),
                (16 * density).toInt(),
                sysBars.bottom + (10 * density).toInt()
            )
            insets
        }

        issTracker = IssTracker(this)
        cloudDownloader = LiveCloudDownloader(this)
        orientationHelper = OrientationSensorHelper(this)

        setupUI()
        setupSensors()
        setupCloudSync()
        checkPermissions()

        val filter = IntentFilter("de.shakie.iss.DEBUG_SCENE")
        ContextCompat.registerReceiver(this, debugReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun setupUI() {
        // Mode switching: ISS View vs Observer View
        binding.btnModeIss.setOnClickListener {
            switchToIssMode()
        }

        binding.btnModeObserver.setOnClickListener {
            switchToObserverMode()
        }

        // Toggle live cloud layer
        updateCloudButtonText()
        binding.btnToggleClouds.setOnClickListener {
            cloudsVisible = !cloudsVisible
            binding.filamentView.setCloudVisibility(cloudsVisible)
            updateCloudButtonText()
        }

        // Toggle country borders & names
        binding.btnToggleBorders.setOnClickListener {
            bordersVisible = !bordersVisible
            binding.filamentView.setBorderVisibility(bordersVisible)
            binding.globeOverlayView.setLabelsVisible(bordersVisible)
            binding.btnToggleBorders.text = if (bordersVisible) "🌐 Grenzen: AN" else "🌐 Grenzen: AUS"
        }

        // Live camera pose sync to 2D vector country labels overlay & optical lens flare
        binding.filamentView.onCameraPoseUpdated = { camPose, aspect, fovY, zoom, bordersVis, sunDir, inSun ->
            runOnUiThread {
                binding.globeOverlayView.updateCamera(camPose, aspect, fovY, zoom, bordersVis, sunDir, inSun)
            }
        }

        // Camera look-around interaction & reset
        binding.filamentView.onCameraModified = { modified ->
            runOnUiThread {
                binding.btnResetOrbitView.visibility = if (modified) View.VISIBLE else View.GONE
            }
        }

        binding.btnResetOrbitView.setOnClickListener {
            binding.filamentView.resetCameraView()
            binding.btnResetOrbitView.visibility = View.GONE
        }

        // Toggle virtual sky vs AR camera in Observer Mode
        binding.btnToggleSkyMode.setOnClickListener {
            val toVirtualSky = !binding.calloutOverlayView.showVirtualSky
            binding.calloutOverlayView.showVirtualSky = toVirtualSky
            if (toVirtualSky) {
                // User switched to Virtual Sky (no camera, dark starry sky)
                arCameraManager?.stopCamera()
                binding.cameraPreview.visibility = View.GONE
                binding.btnToggleSkyMode.text = "✦ AR-Kamera"
            } else {
                // User switched to AR-Camera (live camera background)
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    binding.cameraPreview.visibility = View.VISIBLE
                    binding.btnToggleSkyMode.text = "✦ Sternenhimmel"
                    startCameraPreview()
                } else {
                    permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                }
            }
        }
    }

    private fun updateModeButtonsUi(isObserver: Boolean) {
        if (!isObserver) {
            binding.btnModeIss.setBackgroundResource(R.drawable.bg_hud_button_active)
            binding.btnModeIss.setTextColor(Color.WHITE)
            binding.btnModeObserver.setBackgroundResource(R.drawable.bg_hud_button_inactive)
            binding.btnModeObserver.setTextColor(Color.parseColor("#8FAEC8"))
        } else {
            binding.btnModeObserver.setBackgroundResource(R.drawable.bg_hud_button_active)
            binding.btnModeObserver.setTextColor(Color.WHITE)
            binding.btnModeIss.setBackgroundResource(R.drawable.bg_hud_button_inactive)
            binding.btnModeIss.setTextColor(Color.parseColor("#8FAEC8"))
        }
    }

    private fun switchToIssMode() {
        isObserverMode = false
        binding.issViewContainer.visibility = View.VISIBLE
        binding.observerViewContainer.visibility = View.GONE
        updateModeButtonsUi(isObserver = false)
        arCameraManager?.stopCamera()
    }

    private fun switchToObserverMode() {
        isObserverMode = true
        binding.issViewContainer.visibility = View.GONE
        binding.observerViewContainer.visibility = View.VISIBLE
        updateModeButtonsUi(isObserver = true)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            binding.calloutOverlayView.showVirtualSky = false
            binding.cameraPreview.visibility = View.VISIBLE
            binding.btnToggleSkyMode.text = "✦ Sternenhimmel"
            startCameraPreview()
        } else {
            binding.calloutOverlayView.showVirtualSky = true
            binding.cameraPreview.visibility = View.GONE
            binding.btnToggleSkyMode.text = "✦ AR-Kamera"
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun setupSensors() {
        orientationHelper.onOrientationChanged = { orient ->
            currentOrientation = orient
        }
    }

    private fun updateCloudButtonText() {
        if (!cloudsVisible) {
            binding.btnToggleClouds.text = "☁️ Wolken: AUS"
        } else {
            val isLive = cloudDownloader.isLive
            binding.btnToggleClouds.text = if (isLive) "☁️ Wolken: AN (🌐 Live)" else "☁️ Wolken: AN (💾 Archiv)"
        }
    }

    private fun updateBorderButtonText() {
        binding.btnToggleBorders.text = if (bordersVisible) "🌐 Grenzen: AN" else "🌐 Grenzen: AUS"
        binding.globeOverlayView.setLabelsVisible(bordersVisible)
    }

    private fun setupCloudSync() {
        lifecycleScope.launch {
            cloudDownloader.cloudUpdateFlow.collectLatest { file ->
                binding.filamentView.updateLiveClouds(file)
                updateCloudButtonText()
            }
        }
        lifecycleScope.launch {
            cloudDownloader.isLiveFlow.collectLatest {
                updateCloudButtonText()
            }
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            setupLocationUpdates()
        }
    }

    private fun setupLocationUpdates() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            lastLoc?.let { updateObserverLocation(it) }

            if (locationListener == null) {
                locationListener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        updateObserverLocation(location)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationListener?.let { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 50f, it) }
            }
        } catch (ignored: Exception) {}
    }

    private fun stopLocationUpdates() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
            locationListener?.let { lm.removeUpdates(it) }
        } catch (ignored: Exception) {}
    }

    private fun updateObserverLocation(loc: Location) {
        issTracker.observerLat = loc.latitude
        issTracker.observerLon = loc.longitude
        issTracker.observerAltKm = loc.altitude / 1000.0
    }

    private fun startCameraPreview() {
        if (arCameraManager == null) {
            arCameraManager = ArCameraManager(
                context = this,
                lifecycleOwner = this,
                previewView = binding.cameraPreview
            ) { isCameraActive ->
                runOnUiThread {
                    if (isCameraActive) {
                        binding.calloutOverlayView.showVirtualSky = false
                        binding.cameraPreview.visibility = View.VISIBLE
                        binding.btnToggleSkyMode.text = "✦ Sternenhimmel"
                    } else {
                        binding.calloutOverlayView.showVirtualSky = true
                        binding.cameraPreview.visibility = View.GONE
                        binding.btnToggleSkyMode.text = "✦ AR-Kamera"
                    }
                }
            }
        }
        arCameraManager?.startCamera()
    }

    override fun onResume() {
        super.onResume()
        binding.filamentView.resumeRendering()
        orientationHelper.start()
        if (isObserverMode && !binding.calloutOverlayView.showVirtualSky &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
        }
        Choreographer.getInstance().postFrameCallback(this)
        setupLocationUpdates()
    }

    override fun onPause() {
        super.onPause()
        binding.filamentView.pauseRendering()
        orientationHelper.stop()
        if (isObserverMode) {
            arCameraManager?.stopCamera()
        }
        Choreographer.getInstance().removeFrameCallback(this)
        stopLocationUpdates()
    }

    override fun doFrame(frameTimeNanos: Long) {
        Choreographer.getInstance().postFrameCallback(this)

        val frameTime = if (debugFreeze && debugTimeOverride != null) {
            debugTimeOverride!!
        } else if (debugTimeOverride != null) {
            debugTimeOverride = debugTimeOverride!! + 16
            debugTimeOverride!!
        } else {
            System.currentTimeMillis()
        }

        val snapshot = issTracker.updateFrame(frameTime)

        if (!isObserverMode) {
            binding.filamentView.setSnapshot(snapshot)
        } else {
            binding.calloutOverlayView.updateData(currentOrientation, snapshot)
        }

        updateTelemetryUI(snapshot)
    }

    private fun updateTelemetryUI(snapshot: IssSnapshot) {
        // Coordinates & Overflight Location
        val latDir = if (snapshot.latitude >= 0) "N" else "S"
        val lonDir = if (snapshot.longitude >= 0) "O" else "W"
        binding.tvOrbitCoordinates.text = String.format(
            "LAT: %.2f° %s  LON: %.2f° %s  ALT: %.1f km",
            kotlin.math.abs(snapshot.latitude), latDir,
            kotlin.math.abs(snapshot.longitude), lonDir,
            snapshot.altitudeKm
        )

        val overflightLoc = de.shakie.iss.graphics.CountryCatalog.findOverflightLocation(snapshot.latitude, snapshot.longitude)
        binding.tvOverflight.text = "ÜBERFLIEGT: $overflightLoc"

        // Sunlight / Penumbra / Umbra Eclipse Status
        val sunlight = snapshot.sunlightFactor
        when {
            sunlight <= 0.05f -> {
                binding.tvSunlightStatus.text = "● IM ERDSCHATTEN"
                binding.tvSunlightStatus.setTextColor(ContextCompat.getColor(this, R.color.red_eclipse))
                binding.tvSunlightStatus.setBackgroundColor(Color.parseColor("#33D63031"))
            }
            sunlight < 0.85f -> {
                binding.tvSunlightStatus.text = "● DÄMMERUNG"
                binding.tvSunlightStatus.setTextColor(Color.parseColor("#FFAA00"))
                binding.tvSunlightStatus.setBackgroundColor(Color.parseColor("#33FFAA00"))
            }
            else -> {
                binding.tvSunlightStatus.text = "● IM SONNENLICHT"
                binding.tvSunlightStatus.setTextColor(ContextCompat.getColor(this, R.color.green_sunlight))
                binding.tvSunlightStatus.setBackgroundColor(Color.parseColor("#3300B894"))
            }
        }

        // Relative to observer
        val horiz = snapshot.horizontal
        if (horiz != null) {
            val elSign = if (horiz.elevationDeg >= 0) "+" else ""
            val camInfo = if (isObserverMode) {
                val camElSign = if (currentOrientation.pitchDeg >= 0) "+" else ""
                String.format("  |  BLICK: %.0f°/%s%.0f°", currentOrientation.azimuthDeg, camElSign, currentOrientation.pitchDeg)
            } else ""
            binding.tvObserverRelative.text = String.format(
                "SPEED: %.0f km/h  DIST: %.0f km  ISS: %.0f°/%s%.0f°%s",
                snapshot.velocityKmh, horiz.distanceKm, horiz.azimuthDeg, elSign, horiz.elevationDeg, camInfo
            )
        } else {
            binding.tvObserverRelative.text = String.format(
                "SPEED: %.0f km/h  (GPS-Position wird ermittelt...)",
                snapshot.velocityKmh
            )
        }

        // Next Pass (Requirement 6)
        val pass = snapshot.nextPass
        if (pass != null) {
            binding.tvNextPass.text = "NÄCHSTER ÜBERFLUG: " + pass.formatDescription()
        } else {
            binding.tvNextPass.text = "NÄCHSTER ÜBERFLUG: Berechne nächste Pass-Kulmination..."
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(debugReceiver)
        } catch (ignored: Exception) {}
        stopLocationUpdates()
        issTracker.destroy()
        cloudDownloader.destroy()
        arCameraManager?.stopCamera()
        binding.filamentView.destroy()
    }
}
