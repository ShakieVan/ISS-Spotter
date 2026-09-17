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

    // Sun Light
    private var sunEntity: Int = EntityManager.get().create()

    // Camera controller
    val cameraController = OrbitCameraController()

    // Animation & State
    private var currentSnapshot: IssSnapshot? = null
    private var startTimeNanos = System.nanoTime()
    private var isRendering = false
    private var showBorders = 1.0f
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

        uiHelper.renderCallback = this
        uiHelper.attachTo(this)

        initLighting()
        initEarth()
        initIssModel()
    }

    private fun initLighting() {
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.98f, 0.95f)
            .intensity(110_000.0f)
            .direction(1.0f, 0.0f, 0.0f)
            .castShadows(true)
            .build(engine, sunEntity)
        scene.addEntity(sunEntity)
    }

    private fun initEarth() {
        try {
            val mesh = EarthSphereBuilder.build(engine, radius = 10.0f, latSegments = 48, lonSegments = 96)
            earthMesh = mesh

            val bytes = context.assets.open("materials/earth.filamat").use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
            val mat = Material.Builder().payload(buffer, buffer.remaining()).build(engine)
            earthMaterial = mat

            val instance = mat.createInstance()
            earthMaterialInstance = instance

            dayTexture = loadTextureFromAsset("textures/earth_day.jpg", isSrgb = true)
            nightTexture = loadTextureFromAsset("textures/earth_night.png", isSrgb = true)
            cloudTexture = loadTextureFromAsset("textures/earth_clouds.png", isSrgb = false)
            borderTexture = loadTextureFromAsset("textures/earth_borders.png", isSrgb = true)

            val sampler = TextureSampler(
                TextureSampler.MinFilter.LINEAR_MIPMAP_LINEAR,
                TextureSampler.MagFilter.LINEAR,
                TextureSampler.WrapMode.REPEAT
            )

            dayTexture?.let { instance.setParameter("dayMap", it, sampler) }
            nightTexture?.let { instance.setParameter("nightMap", it, sampler) }
            cloudTexture?.let { instance.setParameter("cloudMap", it, sampler) }
            borderTexture?.let { instance.setParameter("borderMap", it, sampler) }

            instance.setParameter("sunDirection", 1.0f, 0.0f, 0.0f)
            instance.setParameter("time", 0.0f)
            instance.setParameter("cloudRelief", cloudRelief)
            instance.setParameter("showBorders", showBorders)

            RenderableManager.Builder(1)
                .boundingBox(Box(0f, 0f, 0f, 11f, 11f, 11f))
                .geometry(0, RenderableManager.PrimitiveType.TRIANGLES, mesh.vertexBuffer, mesh.indexBuffer)
                .material(0, instance)
                .castShadows(false)
                .receiveShadows(false)
                .build(engine, mesh.entity)

            scene.addEntity(mesh.entity)
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

    override fun doFrame(frameTimeNanos: Long) {
        if (!isRendering || swapChain == null) return
        Choreographer.getInstance().postFrameCallback(this)

        val snapshot = currentSnapshot ?: return
        val elapsedSec = (frameTimeNanos - startTimeNanos) / 1_000_000_000.0f

        // 1. Update Earth Material params (Sun, Time)
        val sun = snapshot.sun
        earthMaterialInstance?.apply {
            setParameter("sunDirection", sun.vectorX, sun.vectorY, sun.vectorZ)
            setParameter("time", elapsedSec)
        }

        // 2. Update Sun Directional Light & Shadow Intensity
        val sunlight = snapshot.sunlightFactor
        val lightManager = engine.lightManager
        val lightInstance = lightManager.getInstance(sunEntity)
        if (lightInstance != 0) {
            lightManager.setDirection(lightInstance, -sun.vectorX, -sun.vectorY, -sun.vectorZ)
            val effectiveIntensity = 1400.0f + sunlight * 108_600.0f
            lightManager.setIntensity(lightInstance, effectiveIntensity)
        }

        // 3. Compute 3D Positions for ISS and Observer
        val earthRadius = 10.0f
        val issAltitudeScale = earthRadius * (snapshot.altitudeKm.toFloat() / EclipseCalculator.EARTH_RADIUS_KM.toFloat())
        val issRadius = earthRadius + issAltitudeScale

        val issLatRad = Math.toRadians(snapshot.latitude).toFloat()
        val issLonRad = Math.toRadians(snapshot.longitude).toFloat()

        val issX = issRadius * cos(issLatRad) * cos(issLonRad)
        val issY = issRadius * sin(issLatRad)
        val issZ = issRadius * cos(issLatRad) * sin(issLonRad)
        val issPos = floatArrayOf(issX, issY, issZ)

        // Observer position on Earth surface
        val obsLat = snapshot.horizontal?.let { 52.5200 } ?: 52.5200
        val obsLon = snapshot.horizontal?.let { 13.4050 } ?: 13.4050
        val obsLatRad = Math.toRadians(obsLat).toFloat()
        val obsLonRad = Math.toRadians(obsLon).toFloat()

        val obsX = earthRadius * cos(obsLatRad) * cos(obsLonRad)
        val obsY = earthRadius * sin(obsLatRad)
        val obsZ = earthRadius * cos(obsLatRad) * sin(obsLonRad)
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
                Matrix.rotateM(transform, 0, Math.toDegrees(-issLonRad.toDouble()).toFloat(), 0f, 1f, 0f)
                Matrix.rotateM(transform, 0, Math.toDegrees(issLatRad.toDouble()).toFloat(), 0f, 0f, 1f)

                tm.setTransform(instance, transform)
            }
        }

        // 5. Update Camera Look-At
        val camPose = cameraController.computeCameraPose(issPos, obsPos)
        val aspect = viewWidth.toFloat() / viewHeight.coerceAtLeast(1).toFloat()
        camera.setProjection(42.0, aspect.toDouble(), 0.05, 50.0, Camera.Fov.VERTICAL)
        camera.lookAt(
            camPose[0].toDouble(), camPose[1].toDouble(), camPose[2].toDouble(),
            camPose[3].toDouble(), camPose[4].toDouble(), camPose[5].toDouble(),
            camPose[6].toDouble(), camPose[7].toDouble(), camPose[8].toDouble()
        )

        // 6. Render frame
        if (renderer.beginFrame(swapChain!!, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Erstmal nur automatischer Blickwinkel (Umsehen-Funktion für später)
        return super.onTouchEvent(event)
    }

    // === UiHelper.RendererCallback Implementation ===

    override fun onNativeWindowChanged(surface: Surface) {
        swapChain?.let { engine.destroySwapChain(it) }
        swapChain = engine.createSwapChain(surface)
        isRendering = true
        Choreographer.getInstance().postFrameCallback(this)
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

    override fun onResized(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height
        view.viewport = Viewport(0, 0, width, height)
    }

    private fun loadTextureFromAsset(path: String, isSrgb: Boolean): Texture? {
        return try {
            val bitmap = context.assets.open(path).use { BitmapFactory.decodeStream(it) } ?: return null
            val texture = Texture.Builder()
                .width(bitmap.width)
                .height(bitmap.height)
                .levels(1)
                .sampler(Texture.Sampler.SAMPLER_2D)
                .format(if (isSrgb) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
                .build(engine)
            TextureHelper.setBitmap(engine, texture, 0, bitmap)
            texture
        } catch (e: Exception) {
            Log.e("IssFilamentView", "Failed to load texture $path: ${e.message}")
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
