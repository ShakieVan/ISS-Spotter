package de.shakie.iss.graphics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.opengl.Matrix
import android.util.AttributeSet
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceView
import com.google.android.filament.*
import com.google.android.filament.android.TextureHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import de.shakie.iss.orbit.EclipseCalculator
import de.shakie.iss.orbit.IssSnapshot
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.*

class IssFilamentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs), Choreographer.FrameCallback, UiHelper.RendererCallback {

    private val engine: Engine = Engine.create()
    private val renderer: Renderer = engine.createRenderer()
    private val scene: Scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity: Int = EntityManager.get().create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val uiHelper: UiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)

    private var swapChain: SwapChain? = null

    // Mesh & Materials
    private var earthMesh: EarthMesh? = null
    private var earthMaterial: Material? = null
    private var earthMaterialInstance: MaterialInstance? = null

    // Milky Way & Celestial Sky
    private var milkyWayMesh: EarthMesh? = null
    private var milkyWayMaterial: Material? = null
    private var milkyWayMaterialInstance: MaterialInstance? = null
    private var milkyWayTexture: Texture? = null

    // Starfield & Constellations
    private var starfieldMesh: EarthMesh? = null
    private var starfieldMaterial: Material? = null
    private var constellationLinesMesh: EarthMesh? = null
    private var constellationLinesMaterial: Material? = null
    private var constellationLabelsMesh: EarthMesh? = null
    private var constellationLabelsMaterial: Material? = null
    private var constellationLabelsInstance: MaterialInstance? = null
    private var constellationLabelsTexture: Texture? = null
    private var skybox: Skybox? = null

    // Sun Visual Billboard & Light
    private var sunBillboardMesh: EarthMesh? = null
    private var sunBillboardMaterial: Material? = null
    private var sunBillboardInstance: MaterialInstance? = null
    private var sunEntity: Int = EntityManager.get().create()

    // Textures
    private var dayTexture: Texture? = null
    private var nightTexture: Texture? = null
    private var cloudTexture: Texture? = null
    private var borderTexture: Texture? = null

    // ISS Model
    private var ubershaderProvider: UbershaderProvider? = null
    private var assetLoader: AssetLoader? = null
    private var resourceLoader: ResourceLoader? = null
    private var issAsset: FilamentAsset? = null

    // Camera controller
    val cameraController = OrbitCameraController()

    // Animation & State
    private var currentSnapshot: IssSnapshot? = null
    private var startTimeNanos = System.nanoTime()
    private var isRendering = false
    private var isPaused = false
    private var showBorders = 1.0f
    private var showClouds = 1.0f
    private var cloudRelief = 1.0f

    // Touch interaction
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private var viewWidth = 1
    private var viewHeight = 1

    init {
        view.scene = scene
        view.camera = camera

        // Post-processing: Tone mapping, Bloom, FXAA for cinematic realism
        view.isPostProcessingEnabled = true
        view.antiAliasing = View.AntiAliasing.FXAA
        val options = view.bloomOptions
        options.enabled = true
        options.strength = 0.25f
        view.bloomOptions = options

        // Ensure clear options and skybox are active to prevent trailing/smearing
        val clearOptions = Renderer.ClearOptions()
        clearOptions.clear = true
        clearOptions.clearColor = doubleArrayOf(0.002, 0.003, 0.006, 1.0)
        renderer.clearOptions = clearOptions

        uiHelper.renderCallback = this
        uiHelper.attachTo(this)

        initLighting()
        initStarfield()
        initEarth()
        initIssModel()
    }

    private var indirectLight: IndirectLight? = null

    private fun initLighting() {
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.98f, 0.95f)
            .intensity(110_000.0f)
            .direction(1.0f, 0.0f, 0.0f)
            .castShadows(true)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)

        try {
            val ibl = IndirectLight.Builder()
                .irradiance(1, floatArrayOf(0.75f, 0.88f, 1.05f))
                .intensity(10_000.0f)
                .build(engine)
            indirectLight = ibl
            scene.indirectLight = ibl
        } catch (e: Exception) {
            Log.w("IssFilamentView", "Failed to init IndirectLight: ${e.message}")
        }
    }

    private fun initStarfield() {
        try {
            // 1. Milky Way Deep Space Celestial Sphere
            val milkyBytes = context.assets.open("materials/milkyway.filamat").use { it.readBytes() }
            val milkyBuffer = ByteBuffer.allocateDirect(milkyBytes.size).apply { put(milkyBytes); flip() }
            val milkyMat = Material.Builder().payload(milkyBuffer, milkyBuffer.remaining()).build(engine)
            milkyWayMaterial = milkyMat
            val milkyInstance = milkyMat.createInstance().apply {
                setParameter("exposure", 1.25f)
            }
            milkyWayMaterialInstance = milkyInstance

            milkyWayTexture = loadTextureFromAsset("textures/milky_way.jpg", isSrgb = true, generateMips = false)
            val milkySampler = TextureSampler(
                TextureSampler.MinFilter.LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.REPEAT
            )
            milkyWayTexture?.let { milkyInstance.setParameter("skyMap", it, milkySampler) }

            val milkyMesh = CelestialSphereBuilder.buildMilkyWaySphere(engine, radius = 85.0f, latSegments = 64, lonSegments = 128)
            milkyWayMesh = milkyMesh

            RenderableManager.Builder(1)
                .boundingBox(Box(0f, 0f, 0f, 130f, 130f, 130f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, milkyMesh.vertexBuffer, milkyMesh.indexBuffer)
                .material(0, milkyInstance)
                .castShadows(false)
                .receiveShadows(false)
                .culling(false)
                .priority(0)
                .build(engine, milkyMesh.entity)
            scene.addEntity(milkyMesh.entity)

            // 2. Stars Material & Mesh (Billboards)
            val starBytes = context.assets.open("materials/stars.filamat").use { it.readBytes() }
            val starBuffer = ByteBuffer.allocateDirect(starBytes.size).apply { put(starBytes); flip() }
            val starMat = Material.Builder().payload(starBuffer, starBuffer.remaining()).build(engine)
            starfieldMaterial = starMat
            val starInstance = starMat.createInstance()

            val starMesh = StarfieldSphereBuilder.buildStarBillboards(engine, radius = 68.0f)
            starfieldMesh = starMesh

            RenderableManager.Builder(1)
                .boundingBox(Box(0f, 0f, 0f, 75f, 75f, 75f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, starMesh.vertexBuffer, starMesh.indexBuffer)
                .material(0, starInstance)
                .castShadows(false)
                .receiveShadows(false)
                .culling(false)
                .priority(1)
                .build(engine, starMesh.entity)
            scene.addEntity(starMesh.entity)

            // 3. Constellation Lines Material & Mesh
            val lineBytes = context.assets.open("materials/lines.filamat").use { it.readBytes() }
            val lineBuffer = ByteBuffer.allocateDirect(lineBytes.size).apply { put(lineBytes); flip() }
            val lineMat = Material.Builder().payload(lineBuffer, lineBuffer.remaining()).build(engine)
            constellationLinesMaterial = lineMat
            val lineInstance = lineMat.createInstance()

            val lineMesh = StarfieldSphereBuilder.buildConstellationLines(engine, radius = 67.8f)
            constellationLinesMesh = lineMesh

            RenderableManager.Builder(1)
                .boundingBox(Box(0f, 0f, 0f, 75f, 75f, 75f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, lineMesh.vertexBuffer, lineMesh.indexBuffer)
                .material(0, lineInstance)
                .castShadows(false)
                .receiveShadows(false)
                .culling(false)
                .priority(2)
                .build(engine, lineMesh.entity)
            scene.addEntity(lineMesh.entity)

            // 4. Constellation Labels are rendered crisp & upright in IssGlobeOverlayView
            val sunBytes = context.assets.open("materials/sun.filamat").use { it.readBytes() }
            val sunBuffer = ByteBuffer.allocateDirect(sunBytes.size).apply { put(sunBytes); flip() }
            val sunMat = Material.Builder().payload(sunBuffer, sunBuffer.remaining()).build(engine)
            sunBillboardMaterial = sunMat
            val sunInstance = sunMat.createInstance().apply {
                setParameter("intensity", 1.0f)
            }
            sunBillboardInstance = sunInstance

            val sunMesh = CelestialSphereBuilder.buildSunBillboard(engine, halfSize = 24.0f)
            sunBillboardMesh = sunMesh

            // Enable Transform on Sun Entity
            engine.transformManager.create(sunMesh.entity)

            RenderableManager.Builder(1)
                .boundingBox(Box(0f, 0f, 0f, 130f, 130f, 130f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, sunMesh.vertexBuffer, sunMesh.indexBuffer)
                .material(0, sunInstance)
                .castShadows(false)
                .receiveShadows(false)
                .culling(false)
                .priority(4)
                .build(engine, sunMesh.entity)
            scene.addEntity(sunMesh.entity)

            Log.i("IssFilamentView", "Initialized Milky Way sky, 3D Stars, Constellation Lines & Labels, and Sun billboard")
        } catch (e: Exception) {
            Log.e("IssFilamentView", "Error initializing Celestial Environment: ${e.message}", e)
        }
    }

    private fun initEarth() {
        try {
            val mesh = EarthSphereBuilder.build(engine, radius = 10.0f, latSegments = 96, lonSegments = 192)
            earthMesh = mesh

            val bytes = context.assets.open("materials/earth.filamat").use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
            val mat = Material.Builder().payload(buffer, buffer.remaining()).build(engine)
            earthMaterial = mat

            val instance = mat.createInstance()
            earthMaterialInstance = instance

            dayTexture = loadTextureFromAsset("textures/earth_day.jpg", isSrgb = true, generateMips = true)
            nightTexture = loadTextureFromAsset("textures/earth_night.jpg", isSrgb = true, generateMips = true)
            cloudTexture = loadTextureFromAsset("textures/earth_clouds.jpg", isSrgb = false, generateMips = true)
            borderTexture = loadTextureFromAsset("textures/earth_borders.png", isSrgb = true, generateMips = true)

            val sampler = TextureSampler(
                TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.REPEAT
            ).apply {
                anisotropy = 8.0f
            }

            dayTexture?.let { instance.setParameter("dayMap", it, sampler) }
            nightTexture?.let { instance.setParameter("nightMap", it, sampler) }
            cloudTexture?.let { instance.setParameter("cloudMap", it, sampler) }
            borderTexture?.let { instance.setParameter("borderMap", it, sampler) }

            instance.setParameter("sunDirection", 1.0f, 0.0f, 0.0f)
            instance.setParameter("time", 0.0f)
            instance.setParameter("cloudRelief", cloudRelief)
            instance.setParameter("showBorders", showBorders)
            instance.setParameter("showClouds", showClouds)

            RenderableManager.Builder(1)
                .boundingBox(Box(0f, 0f, 0f, 11f, 11f, 11f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vertexBuffer, mesh.indexBuffer)
                .material(0, instance)
                .castShadows(false)
                .receiveShadows(false)
                .priority(5)
                .build(engine, mesh.entity)

            scene.addEntity(mesh.entity)
            Log.i("IssFilamentView", "Initialized Earth 3D Mesh")
        } catch (e: Exception) {
            Log.e("IssFilamentView", "Error initializing Earth: ${e.message}", e)
        }
    }

    private fun initIssModel() {
        try {
            val provider = UbershaderProvider(engine)
            ubershaderProvider = provider
            val loader = AssetLoader(engine, provider, EntityManager.get())
            assetLoader = loader
            val resLoader = ResourceLoader(engine)
            resourceLoader = resLoader

            val glbBytes = context.assets.open("models/iss_nasa.glb").use { it.readBytes() }
            val glbBuffer = ByteBuffer.allocateDirect(glbBytes.size).apply { put(glbBytes); flip() }
            val asset = loader.createAsset(glbBuffer)
            issAsset = asset

            if (asset != null) {
                resLoader.loadResources(asset)
                for (entity in asset.entities) {
                    val name = asset.getName(entity)
                    if (name != null && (name.startsWith("bended") || name.startsWith("pCyl"))) {
                        continue
                    }
                    scene.addEntity(entity)
                }
                Log.i("IssFilamentView", "Loaded NASA ISS 3D model (filtered debris entities)")
            }
        } catch (e: Exception) {
            Log.e("IssFilamentView", "Error loading ISS 3D model: ${e.message}", e)
        }
    }

    fun updateLiveClouds(cloudFile: File) {
        try {
            if (!cloudFile.exists()) return
            val bitmap = BitmapFactory.decodeFile(cloudFile.absolutePath) ?: return
            val oldTexture = cloudTexture
            val newTexture = Texture.Builder()
                .width(bitmap.width)
                .height(bitmap.height)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(Texture.InternalFormat.RGBA8)
                .build(engine)
            TextureHelper.setBitmap(engine, newTexture, 0, bitmap)

            val sampler = TextureSampler(
                TextureSampler.MinFilter.LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.REPEAT
            )
            earthMaterialInstance?.setParameter("cloudMap", newTexture, sampler)
            cloudTexture = newTexture
            oldTexture?.let { engine.destroyTexture(it) }
            Log.i("IssFilamentView", "Updated Filament cloud texture from ${cloudFile.name}")
        } catch (e: Exception) {
            Log.w("IssFilamentView", "Failed to update cloud texture: ${e.message}")
        }
    }

    fun setSnapshot(snapshot: IssSnapshot) {
        currentSnapshot = snapshot
    }

    fun setBorderVisibility(visible: Boolean) {
        showBorders = if (visible) 1.0f else 0.0f
        earthMaterialInstance?.setParameter("showBorders", showBorders)
    }

    fun setCloudVisibility(visible: Boolean) {
        showClouds = if (visible) 1.0f else 0.0f
        earthMaterialInstance?.setParameter("showClouds", showClouds)
    }

    fun pauseRendering() {
        isPaused = true
        isRendering = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    fun resumeRendering() {
        isPaused = false
        if (!isRendering && swapChain != null) {
            isRendering = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRendering || isPaused || swapChain == null) {
            Log.d("IssFilamentView", "doFrame skipped: isRendering=$isRendering, isPaused=$isPaused, swapChain=$swapChain")
            return
        }
        Choreographer.getInstance().postFrameCallback(this)

        if (viewWidth <= 1 || viewHeight <= 1) {
            val w = width
            val h = height
            if (w > 1 && h > 1) {
                viewWidth = w
                viewHeight = h
                view.viewport = Viewport(0, 0, w, h)
            }
        }

        val snapshot = currentSnapshot ?: return
        val elapsedSec = (frameTimeNanos - startTimeNanos) / 1_000_000_000.0f

        // 1. Update Earth & Atmosphere Material params (Sun, Time)
        val sun = snapshot.sun
        earthMaterialInstance?.apply {
            setParameter("sunDirection", sun.vectorX, sun.vectorY, sun.vectorZ)
            setParameter("time", elapsedSec)
        }
        sunBillboardInstance?.setParameter("time", elapsedSec)

        // 2. Update Sun Directional Light & Shadow Intensity
        val sunlight = snapshot.sunlightFactor
        val lightManager = engine.lightManager
        val lightInstance = lightManager.getInstance(sunEntity)
        if (lightInstance != 0) {
            lightManager.setDirection(lightInstance, -sun.vectorX, -sun.vectorY, -sun.vectorZ)
            // Fully dark directional sunlight when in Earth's shadow (0 lux), full sun (110,000 lux) in daylight
            val effectiveIntensity = sunlight * 110_000.0f
            lightManager.setIntensity(lightInstance, effectiveIntensity)
        }

        // Ambient starlight / Earthshine on ISS:
        // In shadow: deep dark starlight (~250 lux) so ISS is dark like real space
        // In sunlight: ~10,000 lux diffuse earthshine on the bottom of the station
        val ambientIntensity = 250.0f + sunlight * 9750.0f
        indirectLight?.intensity = ambientIntensity

        // 3. Compute 3D Positions for ISS and Observer
        val earthRadius = 10.0f
        val issAltitudeScale = earthRadius * (snapshot.altitudeKm.toFloat() / EclipseCalculator.EARTH_RADIUS_KM.toFloat())
        val issRadius = earthRadius + issAltitudeScale

        val issLatRad = Math.toRadians(snapshot.latitude).toFloat()
        val issLonRad = Math.toRadians(snapshot.longitude).toFloat()

        val issX = issRadius * cos(issLatRad) * cos(issLonRad)
        val issY = issRadius * sin(issLatRad)
        val issZ = -issRadius * cos(issLatRad) * sin(issLonRad)
        val issPos = floatArrayOf(issX, issY, issZ)

        // Observer position on Earth surface
        val obsLat = snapshot.horizontal?.let { 52.5200 } ?: 52.5200
        val obsLon = snapshot.horizontal?.let { 13.4050 } ?: 13.4050
        val obsLatRad = Math.toRadians(obsLat).toFloat()
        val obsLonRad = Math.toRadians(obsLon).toFloat()

        val obsX = earthRadius * cos(obsLatRad) * cos(obsLonRad)
        val obsY = earthRadius * sin(obsLatRad)
        val obsZ = -earthRadius * cos(obsLatRad) * sin(obsLonRad)
        val obsPos = floatArrayOf(obsX, obsY, obsZ)

        // 4. Update ISS Entity Position & Orientation in Space
        issAsset?.let { asset ->
            val rootEntity = asset.root
            val tm = engine.transformManager
            val instance = tm.getInstance(rootEntity)
            if (instance != 0) {
                val transform = FloatArray(16)
                Matrix.setIdentityM(transform, 0)
                Matrix.translateM(transform, 0, issX, issY, issZ)

                Matrix.scaleM(transform, 0, 0.009f, 0.009f, 0.009f)
                Matrix.rotateM(transform, 0, Math.toDegrees(issLonRad.toDouble()).toFloat(), 0f, 1f, 0f)
                Matrix.rotateM(transform, 0, Math.toDegrees(issLatRad.toDouble()).toFloat(), 0f, 0f, 1f)

                tm.setTransform(instance, transform)
            }
        }

        // 5. Update Camera Look-At
        val camPose = cameraController.computeCameraPose(issPos, obsPos)
        val aspect = viewWidth.toFloat() / viewHeight.coerceAtLeast(1).toFloat()
        camera.setProjection(42.0, aspect.toDouble(), 0.1, 150.0, Camera.Fov.VERTICAL)
        camera.lookAt(
            camPose[0].toDouble(), camPose[1].toDouble(), camPose[2].toDouble(),
            camPose[3].toDouble(), camPose[4].toDouble(), camPose[5].toDouble(),
            camPose[6].toDouble(), camPose[7].toDouble(), camPose[8].toDouble()
        )
        val sunDir = floatArrayOf(sun.vectorX, sun.vectorY, sun.vectorZ)
        onCameraPoseUpdated?.invoke(camPose, aspect, 42.0f, cameraController.zoomFactor, showBorders > 0.5f, sunDir, snapshot.sunlightFactor > 0.05f)

        // 6. Update Sun Visual Billboard Position & Camera-Facing Orientation
        val sunDist = 74.0f
        val sunX = sun.vectorX * sunDist
        val sunY = sun.vectorY * sunDist
        val sunZ = sun.vectorZ * sunDist

        sunBillboardMesh?.let { mesh ->
            val tm = engine.transformManager
            val instance = tm.getInstance(mesh.entity)
            if (instance != 0) {
                // Vector from Sun towards Camera
                val toCamX = camPose[0] - sunX
                val toCamY = camPose[1] - sunY
                val toCamZ = camPose[2] - sunZ
                val toCamLen = sqrt(toCamX * toCamX + toCamY * toCamY + toCamZ * toCamZ).coerceAtLeast(0.001f)
                val fwdX = toCamX / toCamLen
                val fwdY = toCamY / toCamLen
                val fwdZ = toCamZ / toCamLen

                // Reference Up (switch to X if forward vector is nearly vertical along Y)
                val (refUpX, refUpY, refUpZ) = if (abs(fwdY) < 0.95f) {
                    Triple(0f, 1f, 0f)
                } else {
                    Triple(1f, 0f, 0f)
                }

                // Right = cross(refUp, fwd)
                var rightX = refUpY * fwdZ - refUpZ * fwdY
                var rightY = refUpZ * fwdX - refUpX * fwdZ
                var rightZ = refUpX * fwdY - refUpY * fwdX
                val rightLen = sqrt(rightX * rightX + rightY * rightY + rightZ * rightZ).coerceAtLeast(0.001f)
                rightX /= rightLen
                rightY /= rightLen
                rightZ /= rightLen

                // True Up = cross(fwd, right)
                val trueUpX = fwdY * rightZ - fwdZ * rightY
                val trueUpY = fwdZ * rightX - fwdX * rightZ
                val trueUpZ = fwdX * rightY - fwdY * rightX

                val transform = floatArrayOf(
                    rightX, rightY, rightZ, 0f,
                    trueUpX, trueUpY, trueUpZ, 0f,
                    fwdX, fwdY, fwdZ, 0f,
                    sunX, sunY, sunZ, 1f
                )
                tm.setTransform(instance, transform)
            }
        }

        // 7. Render frame
        if (renderer.beginFrame(swapChain!!, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    var onCameraModified: ((Boolean) -> Unit)? = null
    var onCameraPoseUpdated: ((camPose: FloatArray, aspect: Float, fovY: Float, zoom: Float, bordersVisible: Boolean, sunDir: FloatArray, inSunlight: Boolean) -> Unit)? = null

    private val scaleDetector = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            if (factor > 0.01f) {
                cameraController.zoomFactor = (cameraController.zoomFactor / factor).coerceIn(0.35f, 3.5f)
                onCameraModified?.invoke(cameraController.isModified())
            }
            return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (scaleDetector.isInProgress) {
            isDragging = false
            return true
        }

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && event.pointerCount == 1) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    cameraController.yawOffsetDeg = (cameraController.yawOffsetDeg - dx * 0.16f).mod(360f)
                    cameraController.pitchOffsetDeg = (cameraController.pitchOffsetDeg + dy * 0.16f).coerceIn(-85f, 85f)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    onCameraModified?.invoke(cameraController.isModified())
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    fun resetCameraView() {
        cameraController.reset()
        onCameraModified?.invoke(false)
    }

    // === UiHelper.RendererCallback Implementation ===

    override fun onNativeWindowChanged(surface: Surface) {
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = engine.createSwapChain(surface)
        if (!isPaused) {
            isRendering = true
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onDetachedFromSurface() {
        isRendering = false
        Choreographer.getInstance().removeFrameCallback(this)
        swapChain?.let {
            engine.destroySwapChain(it)
            swapChain = null
        }
        engine.flushAndWait()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            viewWidth = w
            viewHeight = h
            view.viewport = Viewport(0, 0, w, h)
        }
    }

    override fun onResized(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height
        view.viewport = Viewport(0, 0, width, height)
    }

    private fun loadTextureFromAsset(path: String, isSrgb: Boolean, generateMips: Boolean = true): Texture? {
        return try {
            val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it) } ?: return null
            val maxDim = max(bitmap.width, bitmap.height)
            val numLevels = if (generateMips) (1 + floor(log2(maxDim.toDouble()))).toInt() else 1
            val usageFlags = if (generateMips && numLevels > 1) {
                Texture.Usage.DEFAULT or Texture.Usage.GEN_MIPMAPPABLE
            } else {
                Texture.Usage.DEFAULT
            }
            val texture = Texture.Builder()
                .width(bitmap.width)
                .height(bitmap.height)
                .levels(numLevels)
                .usage(usageFlags)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(if (isSrgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
                .build(engine)
            Log.i("IssFilamentView", "Texture.Builder built texture for $path: ${bitmap.width}x${bitmap.height}, levels=$numLevels")
            TextureHelper.setBitmap(engine, texture, 0, bitmap)
            Log.i("IssFilamentView", "TextureHelper.setBitmap finished for $path")
            bitmap.recycle()
            if (generateMips && numLevels > 1) {
                texture.generateMipmaps(engine)
                Log.i("IssFilamentView", "generateMipmaps finished for $path")
            }
            texture
        } catch (e: Throwable) {
            Log.e("IssFilamentView", "CRITICAL ERROR loading texture $path: ${e.message}", e)
            null
        }
    }

    fun destroy() {
        uiHelper.detach()
        isRendering = false
        Choreographer.getInstance().removeFrameCallback(this)

        earthMesh?.destroy(engine)
        earthMaterial?.let { engine.destroyMaterial(it) }
        dayTexture?.let { engine.destroyTexture(it) }
        nightTexture?.let { engine.destroyTexture(it) }
        cloudTexture?.let { engine.destroyTexture(it) }
        borderTexture?.let { engine.destroyTexture(it) }

        milkyWayMesh?.destroy(engine)
        milkyWayMaterial?.let { engine.destroyMaterial(it) }
        milkyWayTexture?.let { engine.destroyTexture(it) }

        sunBillboardMesh?.destroy(engine)
        sunBillboardMaterial?.let { engine.destroyMaterial(it) }

        starfieldMesh?.destroy(engine)
        starfieldMaterial?.let { engine.destroyMaterial(it) }
        constellationLinesMesh?.destroy(engine)
        constellationLinesMaterial?.let { engine.destroyMaterial(it) }
        skybox?.let { engine.destroySkybox(it) }
        indirectLight?.let { engine.destroyIndirectLight(it) }

        issAsset?.let { assetLoader?.destroyAsset(it) }
        assetLoader?.destroy()
        ubershaderProvider?.destroy()

        engine.destroyEntity(sunEntity)
        engine.destroyEntity(cameraEntity)
        engine.destroyView(view)
        engine.destroyRenderer(renderer)
        engine.destroy()
    }

    companion object {
        init {
            com.google.android.filament.Filament.init()
            com.google.android.filament.gltfio.Gltfio.init()
        }
    }
}
