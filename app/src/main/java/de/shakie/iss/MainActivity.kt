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
import de.shakie.iss.graphics.*
import de.shakie.iss.observer.ArCameraManager
import de.shakie.iss.observer.CalibrationAccuracy
import de.shakie.iss.observer.CameraProjectionData
import de.shakie.iss.observer.CameraProjector
import de.shakie.iss.observer.DeviceOrientation
import de.shakie.iss.observer.OrientationSensorHelper
import de.shakie.iss.orbit.HorizontalCoordinates
import de.shakie.iss.orbit.IssSnapshot
import de.shakie.iss.orbit.IssTracker
import de.shakie.iss.weather.GibsSatelliteDownloader
import de.shakie.iss.weather.LiveCloudDownloader
import de.shakie.iss.weather.SatelliteInfo
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.*

class MainActivity : AppCompatActivity(), Choreographer.FrameCallback {

    private lateinit var binding: ActivityMainBinding

    private lateinit var issTracker: IssTracker
    private lateinit var cloudDownloader: LiveCloudDownloader
    private lateinit var gibsDownloader: GibsSatelliteDownloader
    private lateinit var orientationHelper: OrientationSensorHelper
    private var arCameraManager: ArCameraManager? = null
    private var currentSatelliteInfo: SatelliteInfo? = null

    private var currentOrientation = DeviceOrientation(0f, 0f, 0f, FloatArray(16))
    private var bordersVisible = true
    private var cloudsVisible = true
    private var normalAnalysisEnabled = false
    private var isObserverMode = false
    private var locationListener: LocationListener? = null

    // Debug control for automated testing & angle inspection via ADB
    private var debugTimeOverride: Long? = null
    private var debugFreeze: Boolean = false
    private var debugSunOverride: FloatArray? = null
    private var debugDiagTargetAz: Float? = null
    private var debugDiagTargetEl: Float? = null
    private var debugObserverYaw: Float? = null
    private var debugObserverPitch: Float? = null
    private var debugObserverRoll: Float? = null
    private var debugCameraZoom: Float? = null

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            if (intent.hasExtra("reset") && intent.getBooleanExtra("reset", false)) {
                binding.filamentView.resetCameraView()
                debugTimeOverride = null
                debugFreeze = false
                debugSunOverride = null
                debugDiagTargetAz = null
                debugDiagTargetEl = null
                debugObserverYaw = null
                debugObserverPitch = null
                debugObserverRoll = null
                debugCameraZoom = null
                arCameraManager?.forcedZoomRatio = null
                binding.filamentView.setReferenceMode(false)
                binding.globeOverlayView.diagnosticMarkersVisible = false
                normalAnalysisEnabled = false
                binding.filamentView.setDebugVisualMode(0)
                binding.filamentView.setCloudDataSourceConfig(
                    CloudEncoding.GRAYSCALE_MASK,
                    CloudNoDataMode.NODATA_NONE
                )
                updateGroundMarkerButtonText()
                updateNormalAnalysisButtonText()
                Log.i("MainActivity", "Debug: reset camera, time, sun, reference mode, diagnostics, and clouds")
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
            if (intent.hasExtra("satellite")) {
                val sat = intent.getBooleanExtra("satellite", false)
                binding.filamentView.setMapMode(sat)
                updateMapSourceButtonText()
            }
            if (intent.hasExtra("reference_mode")) {
                val ref = intent.getBooleanExtra("reference_mode", false)
                binding.filamentView.setReferenceMode(ref)
                updateMapSourceButtonText()
            }
            if (intent.hasExtra("diagnostic_markers")) {
                val diag = intent.getBooleanExtra("diagnostic_markers", false)
                binding.globeOverlayView.diagnosticMarkersVisible = diag
                binding.globeOverlayView.postInvalidateOnAnimation()
                updateGroundMarkerButtonText()
            }
            if (intent.hasExtra("visual_mode")) {
                val mode = intent.getIntExtra("visual_mode", 0)
                normalAnalysisEnabled = (mode == 1)
                binding.filamentView.setDebugVisualMode(mode)
                updateNormalAnalysisButtonText()
            }
            if (intent.hasExtra("cloud_encoding")) {
                val encVal = intent.getFloatExtra("cloud_encoding", 0f)
                val enc = if (encVal > 0.5f) CloudEncoding.CLOUD_ALPHA_MASK else CloudEncoding.GRAYSCALE_MASK
                binding.filamentView.setCloudDataSourceConfig(enc, binding.filamentView.currentCloudNoDataMode)
            }
            if (intent.hasExtra("cloud_nodata")) {
                val ndVal = intent.getFloatExtra("cloud_nodata", 0f)
                val nd = when {
                    ndVal > 1.5f -> CloudNoDataMode.NODATA_SENTINEL_ZERO
                    ndVal > 0.5f -> CloudNoDataMode.NODATA_ALPHA_ZERO
                    else -> CloudNoDataMode.NODATA_NONE
                }
                binding.filamentView.setCloudDataSourceConfig(binding.filamentView.currentCloudEncoding, nd)
            }
            if (intent.hasExtra("sun_lat") && intent.hasExtra("sun_lon")) {
                val sLat = intent.getFloatExtra("sun_lat", 0f)
                val sLon = intent.getFloatExtra("sun_lon", 0f)
                val latRad = Math.toRadians(sLat.toDouble()).toFloat()
                val lonRad = Math.toRadians(sLon.toDouble()).toFloat()
                val vx = (cos(latRad) * cos(lonRad))
                val vy = sin(latRad)
                val vz = (-cos(latRad) * sin(lonRad))
                debugSunOverride = floatArrayOf(vx, vy, vz)
            }
            if (intent.hasExtra("mode")) {
                val mode = intent.getStringExtra("mode")
                if (mode.equals("observer", ignoreCase = true)) {
                    switchToObserverMode()
                } else if (mode.equals("iss", ignoreCase = true)) {
                    switchToIssMode()
                }
            }
            if (intent.hasExtra("diag_target_az")) {
                debugDiagTargetAz = intent.getFloatExtra("diag_target_az", 0f)
            }
            if (intent.hasExtra("diag_target_el")) {
                debugDiagTargetEl = intent.getFloatExtra("diag_target_el", 0f)
            }
            if (intent.hasExtra("observer_yaw")) {
                debugObserverYaw = intent.getFloatExtra("observer_yaw", 0f)
            }
            if (intent.hasExtra("observer_pitch")) {
                debugObserverPitch = intent.getFloatExtra("observer_pitch", 0f)
            }
            if (intent.hasExtra("observer_roll")) {
                debugObserverRoll = intent.getFloatExtra("observer_roll", 0f)
            }
            if (intent.hasExtra("camera_zoom")) {
                val zoom = intent.getFloatExtra("camera_zoom", 1f)
                debugCameraZoom = zoom
                arCameraManager?.setZoomRatio(zoom)
            }
            if (intent.hasExtra("sky_mode")) {
                val sky = intent.getBooleanExtra("sky_mode", false)
                binding.calloutOverlayView.showVirtualSky = sky
                if (sky) {
                    arCameraManager?.stopCamera()
                    binding.cameraPreview.visibility = View.GONE
                    binding.btnToggleSkyMode.text = "✦ AR-Kamera"
                    binding.calloutOverlayView.setCameraProjectionData(CameraProjectionData(calibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY))
                } else {
                    binding.cameraPreview.visibility = View.VISIBLE
                    binding.btnToggleSkyMode.text = "✦ Sternenhimmel"
                    startCameraPreview()
                }
            }
            binding.filamentView.onCameraModified?.invoke(binding.filamentView.cameraController.isModified())
            Log.i("MainActivity", "Debug scene updated: yaw=${binding.filamentView.cameraController.yawOffsetDeg}, pitch=${binding.filamentView.cameraController.pitchOffsetDeg}, zoom=${binding.filamentView.cameraController.zoomFactor}, time=$debugTimeOverride, freeze=$debugFreeze, observerYaw=$debugObserverYaw, observerPitch=$debugObserverPitch, diagAz=$debugDiagTargetAz, diagEl=$debugDiagTargetEl")
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
        gibsDownloader = GibsSatelliteDownloader(this)
        orientationHelper = OrientationSensorHelper(this)

        setupUI()
        setupSensors()
        setupCloudSync()
        setupSatelliteSync()
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

        // Toggle Map Source: Blue Marble -> Satellit (Simulation) -> Satellit (Referenz Unlit) -> Blue Marble
        updateMapSourceButtonText()
        binding.btnToggleMapSource.setOnClickListener {
            val req = binding.filamentView.requestedMapSource
            val isRef = binding.filamentView.isReferenceMode
            when {
                req == MapSourcePreference.BLUE_MARBLE -> {
                    // Switch from Blue Marble to Satellit (Simulation)
                    binding.filamentView.setReferenceMode(false)
                    binding.filamentView.setMapMode(true)
                }
                req == MapSourcePreference.SATELLITE && !isRef -> {
                    // Switch from Satellit (Simulation) to Satellit (Referenz Unlit)
                    binding.filamentView.setReferenceMode(true)
                }
                else -> {
                    // Switch from Satellit (Referenz) back to Blue Marble
                    binding.filamentView.setReferenceMode(false)
                    binding.filamentView.setMapMode(false)
                }
            }
            updateMapSourceButtonText()
        }

        // Toggle live cloud layer
        updateCloudButtonText()
        binding.btnToggleClouds.setOnClickListener {
            val next = !binding.filamentView.userCloudPreference
            binding.filamentView.setCloudVisibility(next)
            updateCloudButtonText()
        }

        // Toggle country borders & names (Short-click: toggle borders, Long-click: toggle diagnostic markers)
        binding.btnToggleBorders.setOnClickListener {
            bordersVisible = !bordersVisible
            binding.filamentView.setBorderVisibility(bordersVisible)
            binding.globeOverlayView.setLabelsVisible(bordersVisible)
            binding.btnToggleBorders.text = if (bordersVisible) "🌐 Grenzen: AN" else "🌐 Grenzen: AUS"
        }
        binding.btnToggleBorders.setOnLongClickListener {
            val next = !binding.globeOverlayView.diagnosticMarkersVisible
            binding.globeOverlayView.diagnosticMarkersVisible = next
            binding.globeOverlayView.postInvalidateOnAnimation()
            updateGroundMarkerButtonText()
            android.widget.Toast.makeText(this, if (next) "📍 Diagnose-Markierungen: AN" else "📍 Diagnose-Markierungen: AUS", android.widget.Toast.LENGTH_SHORT).show()
            true
        }

        // Aufklappmenü für Ebenen & Optionen
        binding.btnToggleViewOptions.setOnClickListener {
            val isVis = binding.layoutViewOptionsMenu.visibility == View.VISIBLE
            binding.layoutViewOptionsMenu.visibility = if (isVis) View.GONE else View.VISIBLE
            binding.btnToggleViewOptions.text = if (isVis) "🎛️ Optionen ▴" else "🎛️ Optionen ▾"
        }

        // Toggle ISS-Bodenpunkt & Markierungen
        updateGroundMarkerButtonText()
        binding.btnToggleGroundMarker.setOnClickListener {
            val next = !binding.globeOverlayView.diagnosticMarkersVisible
            binding.globeOverlayView.diagnosticMarkersVisible = next
            binding.globeOverlayView.postInvalidateOnAnimation()
            updateGroundMarkerButtonText()
        }

        // Toggle Shader-Normalenanalyse
        updateNormalAnalysisButtonText()
        binding.btnToggleNormalAnalysis.setOnClickListener {
            normalAnalysisEnabled = !normalAnalysisEnabled
            binding.filamentView.setDebugVisualMode(if (normalAnalysisEnabled) 1 else 0)
            updateNormalAnalysisButtonText()
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
                binding.calloutOverlayView.setCameraProjectionData(CameraProjectionData(calibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY))
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
        val config = binding.filamentView.currentConfiguration
        if (config.activeTextureSource == EffectiveTextureSource.SATELLITE_VIIRS) {
            binding.btnToggleClouds.isEnabled = false
            binding.btnToggleClouds.alpha = 0.5f
            binding.btnToggleClouds.text = "☁️ Wolken: Im Satellitenbild"
            return
        }
        if (config.isReferenceMode) {
            binding.btnToggleClouds.isEnabled = false
            binding.btnToggleClouds.alpha = 0.5f
            binding.btnToggleClouds.text = "☁️ Wolken: Unlit Referenz"
            return
        }

        binding.btnToggleClouds.isEnabled = true
        binding.btnToggleClouds.alpha = 1.0f
        if (!binding.filamentView.userCloudPreference) {
            binding.btnToggleClouds.text = "☁️ Wolken: AUS"
        } else {
            val isLive = cloudDownloader.isLive
            val meta = cloudDownloader.metadataFlow.value
            binding.btnToggleClouds.text = if (isLive) "☁️ Wolken: AN (${meta.provider})" else "☁️ Wolken: AN (💾 Archiv)"
        }
    }

    private fun updateMapSourceButtonText() {
        val config = binding.filamentView.currentConfiguration
        val date = currentSatelliteInfo?.dateUtc ?: "NASA VIIRS"
        binding.globeOverlayView.setReferenceMode(config.isReferenceMode)

        when {
            config.isReferenceMode -> {
                binding.btnToggleMapSource.text = if (config.activeTextureSource == EffectiveTextureSource.SATELLITE_VIIRS)
                    "🛰️ Satellit: Referenz ($date)" else "🌍 Referenz (Blue Marble)"
            }
            config.activeTextureSource == EffectiveTextureSource.SATELLITE_VIIRS -> {
                binding.btnToggleMapSource.text = "🛰️ Satellit: VIIRS ($date)"
            }
            config.isSatellitePending -> {
                binding.btnToggleMapSource.text = "🛰️ Satellit: Lädt..."
            }
            config.isSatelliteFailed -> {
                binding.btnToggleMapSource.text = "🛰️ Satellit: Fehler (Fallback)"
            }
            else -> {
                binding.btnToggleMapSource.text = "🌍 Karte: Blue Marble"
            }
        }
        updateCloudButtonText()
    }

    private fun updateBorderButtonText() {
        binding.btnToggleBorders.text = if (bordersVisible) "🌐 Grenzen: AN" else "🌐 Grenzen: AUS"
        binding.globeOverlayView.setLabelsVisible(bordersVisible)
    }

    private fun updateGroundMarkerButtonText() {
        val visible = binding.globeOverlayView.diagnosticMarkersVisible
        binding.btnToggleGroundMarker.text = if (visible) "🎯 Bodenpunkt: AN" else "🎯 Bodenpunkt: AUS"
    }

    private fun updateNormalAnalysisButtonText() {
        binding.btnToggleNormalAnalysis.text = if (normalAnalysisEnabled) "🔬 Normalenanalyse: AN" else "🔬 Normalenanalyse: AUS"
    }

    private fun setupCloudSync() {
        lifecycleScope.launch {
            cloudDownloader.cloudUpdateFlow.collectLatest { file ->
                binding.filamentView.updateLiveClouds(
                    file,
                    CloudEncoding.GRAYSCALE_MASK,
                    CloudNoDataMode.NODATA_NONE
                )
                updateCloudButtonText()
            }
        }
        lifecycleScope.launch {
            cloudDownloader.isLiveFlow.collectLatest {
                updateCloudButtonText()
            }
        }
        lifecycleScope.launch {
            cloudDownloader.metadataFlow.collectLatest { meta ->
                Log.i("MainActivity", "Live cloud metadata updated: provider=${meta.provider}, dims=${meta.dimensions}, cache=${meta.cacheFileName}, obs=${meta.observationTime}")
                updateCloudButtonText()
            }
        }
    }

    private fun setupSatelliteSync() {
        lifecycleScope.launch {
            gibsDownloader.satelliteInfoFlow.collectLatest { satInfo ->
                satInfo?.let {
                    currentSatelliteInfo = it
                    binding.filamentView.updateSatelliteTexture(it.file)
                    updateMapSourceButtonText()
                }
            }
        }
        lifecycleScope.launch {
            gibsDownloader.isDownloadingFlow.collectLatest { isDownloading ->
                val state = when {
                    isDownloading -> SatelliteDownloadState.PENDING
                    binding.filamentView.isSatelliteAvailable -> SatelliteDownloadState.SUCCEEDED
                    currentSatelliteInfo == null -> SatelliteDownloadState.FAILED
                    else -> SatelliteDownloadState.NOT_STARTED
                }
                binding.filamentView.setSatelliteDownloadState(state)
                updateMapSourceButtonText()
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
        orientationHelper.updateObserverLocation(loc.latitude, loc.longitude, loc.altitude / 1000.0)
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
                        if (!binding.calloutOverlayView.showVirtualSky) {
                            binding.cameraPreview.visibility = View.VISIBLE
                            binding.btnToggleSkyMode.text = "✦ Sternenhimmel"
                        }
                    } else {
                        binding.calloutOverlayView.showVirtualSky = true
                        binding.cameraPreview.visibility = View.GONE
                        binding.btnToggleSkyMode.text = "✦ AR-Kamera"
                        binding.calloutOverlayView.setCameraProjectionData(CameraProjectionData(calibrationAccuracy = CalibrationAccuracy.VIRTUAL_SKY))
                    }
                }
            }.apply {
                onProjectionDataChanged = { projData ->
                    runOnUiThread {
                        binding.calloutOverlayView.setCameraProjectionData(projData)
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

        val baseSnapshot = issTracker.updateFrame(frameTime)
        val snapshot = if (debugSunOverride != null) {
            val s = debugSunOverride!!
            val sunPos = de.shakie.iss.orbit.SunPosition(
                latitude = Math.toDegrees(asin(s[1].toDouble())),
                longitude = Math.toDegrees(atan2(-s[2].toDouble(), s[0].toDouble())),
                vectorX = s[0],
                vectorY = s[1],
                vectorZ = s[2]
            )
            val sunlight = de.shakie.iss.orbit.EclipseCalculator.getSunlightFactor(
                issLatDeg = baseSnapshot.latitude,
                issLonDeg = baseSnapshot.longitude,
                issAltKm = baseSnapshot.altitudeKm,
                sun = sunPos
            )
            baseSnapshot.copy(sun = sunPos, sunlightFactor = sunlight, isEclipsed = sunlight < 0.15f)
        } else {
            baseSnapshot
        }

        val orient = if (debugObserverYaw != null || debugObserverPitch != null || debugObserverRoll != null) {
            val yaw = debugObserverYaw ?: currentOrientation.azimuthDeg
            val pitch = debugObserverPitch ?: currentOrientation.pitchDeg
            val roll = debugObserverRoll ?: currentOrientation.rollDeg
            val rotMat = CameraProjector.createRotationMatrix(yaw, pitch, roll)
            DeviceOrientation(
                azimuthDeg = yaw,
                pitchDeg = pitch,
                rollDeg = roll,
                rotationMatrix = rotMat,
                declinationDeg = currentOrientation.declinationDeg,
                sensorAccuracyLevel = currentOrientation.sensorAccuracyLevel
            )
        } else {
            currentOrientation
        }

        val effSnapshot = if (debugDiagTargetAz != null && debugDiagTargetEl != null) {
            val diagAz = debugDiagTargetAz!!.toDouble()
            val diagEl = debugDiagTargetEl!!.toDouble()
            val horiz = HorizontalCoordinates(
                azimuthDeg = diagAz,
                elevationDeg = diagEl,
                distanceKm = 420.0,
                isVisibleAboveHorizon = diagEl > 0.0
            )
            snapshot.copy(horizontal = horiz)
        } else {
            snapshot
        }

        binding.globeOverlayView.issSubPointLatLon = Pair(effSnapshot.latitude, effSnapshot.longitude)

        if (!isObserverMode) {
            binding.filamentView.setSnapshot(effSnapshot)
        } else {
            binding.calloutOverlayView.updateData(orient, effSnapshot)
        }

        updateTelemetryUI(effSnapshot, orient)
    }

    private fun updateTelemetryUI(snapshot: IssSnapshot, orient: DeviceOrientation) {
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
                val camElSign = if (orient.pitchDeg >= 0) "+" else ""
                String.format("  |  BLICK: %.0f°/%s%.0f°", orient.azimuthDeg, camElSign, orient.pitchDeg)
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
        gibsDownloader.destroy()
        arCameraManager?.stopCamera()
        binding.filamentView.destroy()
    }
}
