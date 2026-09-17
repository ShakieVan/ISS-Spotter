package de.shakie.iss

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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
    private var isObserverMode = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            setupLocationUpdates()
        }
        if (permissions[Manifest.permission.CAMERA] == true) {
            setupCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        issTracker = IssTracker(this)
        cloudDownloader = LiveCloudDownloader(this)
        orientationHelper = OrientationSensorHelper(this)

        setupUI()
        setupSensors()
        setupCloudSync()
        checkPermissions()
    }

    private fun setupUI() {
        // Mode switching: ISS View vs Observer View
        binding.btnModeIss.setOnClickListener {
            switchToIssMode()
        }

        binding.btnModeObserver.setOnClickListener {
            switchToObserverMode()
        }

        // Toggle country borders & names
        binding.btnToggleBorders.setOnClickListener {
            bordersVisible = !bordersVisible
            binding.filamentView.setBorderVisibility(bordersVisible)
            binding.btnToggleBorders.text = if (bordersVisible) "🌐 Grenzen: AN" else "🌐 Grenzen: AUS"
        }

        // Toggle virtual sky vs AR camera in Observer Mode
        binding.btnToggleSkyMode.setOnClickListener {
            val currentVirtual = binding.calloutOverlayView.showVirtualSky
            binding.calloutOverlayView.showVirtualSky = !currentVirtual
            binding.cameraPreview.visibility = if (binding.calloutOverlayView.showVirtualSky) View.GONE else View.VISIBLE
            binding.btnToggleSkyMode.text = if (binding.calloutOverlayView.showVirtualSky) "✦ Modus: HIMMEL" else "✦ Modus: AR-KAMERA"
        }
    }

    private fun switchToIssMode() {
        isObserverMode = false
        binding.issViewContainer.visibility = View.VISIBLE
        binding.observerViewContainer.visibility = View.GONE
        binding.btnModeIss.setTextColor(Color.WHITE)
        binding.btnModeObserver.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))
    }

    private fun switchToObserverMode() {
        isObserverMode = true
        binding.issViewContainer.visibility = View.GONE
        binding.observerViewContainer.visibility = View.VISIBLE
        binding.btnModeObserver.setTextColor(Color.WHITE)
        binding.btnModeIss.setTextColor(ContextCompat.getColor(this, R.color.cyan_accent))

        if (arCameraManager == null) {
            setupCamera()
        }
    }

    private fun setupSensors() {
        orientationHelper.onOrientationChanged = { orient ->
            currentOrientation = orient
        }
    }

    private fun setupCloudSync() {
        lifecycleScope.launch {
            cloudDownloader.cloudUpdateFlow.collectLatest { file ->
                binding.filamentView.updateLiveClouds(file)
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
            setupCamera()
        }
    }

    private fun setupLocationUpdates() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val lastLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

            lastLoc?.let { updateObserverLocation(it) }

            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    updateObserverLocation(location)
                }
                @Deprecated("Deprecated in Java")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 10000L, 50f, listener)
            }
        } catch (ignored: Exception) {}
    }

    private fun updateObserverLocation(loc: Location) {
        issTracker.observerLat = loc.latitude
        issTracker.observerLon = loc.longitude
        issTracker.observerAltKm = loc.altitude / 1000.0
    }

    private fun setupCamera() {
        arCameraManager = ArCameraManager(
            context = this,
            lifecycleOwner = this,
            previewView = binding.cameraPreview
        ) { isCameraActive ->
            if (!isCameraActive) {
                binding.calloutOverlayView.showVirtualSky = true
                binding.cameraPreview.visibility = View.GONE
                binding.btnToggleSkyMode.text = "✦ Modus: HIMMEL"
            }
        }
        arCameraManager?.startCamera()
    }

    override fun onResume() {
        super.onResume()
        orientationHelper.start()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        super.onPause()
        orientationHelper.stop()
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        Choreographer.getInstance().postFrameCallback(this)

        val snapshot = issTracker.updateFrame()

        if (!isObserverMode) {
            binding.filamentView.setSnapshot(snapshot)
        } else {
            binding.calloutOverlayView.updateData(currentOrientation, snapshot)
        }

        updateTelemetryUI(snapshot)
    }

    private fun updateTelemetryUI(snapshot: IssSnapshot) {
        // Coordinates
        val latDir = if (snapshot.latitude >= 0) "N" else "S"
        val lonDir = if (snapshot.longitude >= 0) "O" else "W"
        binding.tvOrbitCoordinates.text = String.format(
            "LAT: %.2f° %s  LON: %.2f° %s  ALT: %.1f km",
            kotlin.math.abs(snapshot.latitude), latDir,
            kotlin.math.abs(snapshot.longitude), lonDir,
            snapshot.altitudeKm
        )

        // Sunlight / Eclipse Status
        if (snapshot.isEclipsed) {
            binding.tvSunlightStatus.text = "● IM ERDSCHATTEN"
            binding.tvSunlightStatus.setTextColor(ContextCompat.getColor(this, R.color.red_eclipse))
            binding.tvSunlightStatus.setBackgroundColor(Color.parseColor("#33D63031"))
        } else {
            binding.tvSunlightStatus.text = "● IM SONNENLICHT"
            binding.tvSunlightStatus.setTextColor(ContextCompat.getColor(this, R.color.green_sunlight))
            binding.tvSunlightStatus.setBackgroundColor(Color.parseColor("#3300B894"))
        }

        // Relative to observer
        val horiz = snapshot.horizontal
        if (horiz != null) {
            val elSign = if (horiz.elevationDeg >= 0) "+" else ""
            binding.tvObserverRelative.text = String.format(
                "SPEED: %.0f km/h  DIST: %.0f km  AZ/EL: %.0f° / %s%.0f°",
                snapshot.velocityKmh, horiz.distanceKm, horiz.azimuthDeg, elSign, horiz.elevationDeg
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
        issTracker.destroy()
        cloudDownloader.destroy()
        arCameraManager?.stopCamera()
        binding.filamentView.destroy()
    }
}
