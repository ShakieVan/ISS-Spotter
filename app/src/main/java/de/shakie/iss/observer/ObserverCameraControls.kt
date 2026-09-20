package de.shakie.iss.observer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import de.shakie.iss.R
import java.util.Locale

/** Camera UI only: does not touch the Filament orbit camera or its gestures. */
class ObserverCameraControls(
    private val activity: AppCompatActivity,
    private val container: FrameLayout,
    private val preview: androidx.camera.view.PreviewView,
    private val overlay: IssCalloutOverlayView,
    private val skyButton: View,
    trajectoryButton: View
) {
    private var manager: ArCameraManager? = null
    private val prefs = activity.getSharedPreferences("capture_options", 0)
    private var includeOverlay = prefs.getBoolean("include_overlay", true)
    private var pendingStorageAction: (() -> Unit)? = null
    private var lastUri: Uri? = null
    private var lastMime: String? = null
    private val panel = LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(8),dp(6),dp(8),dp(6)); setBackgroundResource(R.drawable.bg_telemetry_card) }
    private val status = TextView(activity).apply { setTextColor(Color.WHITE); textSize=10f; gravity=Gravity.CENTER }
    private val photo = button("📷 Foto")
    private val video = button("● Video")
    private val overlayOption = button("")
    private val gallery = button("Aufnahme öffnen")
    private val resetZoom = button("1×")
    private val focusRing = FocusRing(activity)
    private val microphoneLevel = MicrophoneLevelView(activity)
    private var microphoneRequestInFlight = false
    private val microphonePermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        microphoneRequestInFlight = false
        update()
        if (allowed) {
            // Permission dialogs may pause/unbind the camera. Never queue a surprise recording
            // for a later resume or mode switch; use a fresh explicit tap after first consent.
            message("Mikrofon freigegeben. Zum Aufnehmen Video antippen.")
        } else {
            message("Ohne Mikrofonfreigabe startet kein Video mit Ton. Fotos bleiben verfügbar.")
        }
    }
    private val storagePermission = activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val action = pendingStorageAction; pendingStorageAction=null
        if (allowed && container.isShown && manager?.ready == true) action?.invoke()
        else if (!allowed) message("Ohne Speicherfreigabe kann Android 9 die Aufnahme nicht in der Galerie ablegen.")
    }

    init {
        // Reuse the existing buttons/listeners, in a row above the capture controls.
        val modeRow = row()
        for (v in listOf(trajectoryButton, skyButton)) {
            (v.parent as? ViewGroup)?.removeView(v)
            modeRow.addView(v, LinearLayout.LayoutParams(0,dp(40),1f).apply { setMargins(dp(2),0,dp(2),dp(3)) })
        }
        panel.addView(modeRow)
        val captureRow=row();listOf(photo,video,overlayOption).forEach { captureRow.addView(it, weighted()) };panel.addView(captureRow)
        val toolsRow=row();toolsRow.addView(resetZoom,LinearLayout.LayoutParams(dp(62),dp(36)))
        toolsRow.addView(status,LinearLayout.LayoutParams(0,-2,1f));toolsRow.addView(gallery,LinearLayout.LayoutParams(dp(110),dp(36)));panel.addView(toolsRow)
        // This is a sibling of the AR overlay, never a child of the captured overlay view.
        panel.addView(microphoneLevel, LinearLayout.LayoutParams(-1, dp(36)))
        container.addView(focusRing,FrameLayout.LayoutParams(-1,-1))
        container.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { setMargins(dp(10),0,dp(10),dp(8)) })

        photo.setOnClickListener { withStorage { manager?.capture?.takePhoto() } }
        video.setOnClickListener {
            val capture=manager?.capture ?: return@setOnClickListener
            if(capture.isRecording) capture.stopRecording() else requestVideoWithAudio()
        }
        overlayOption.setOnClickListener {
            if(manager?.capture?.isBusy == true) return@setOnClickListener
            includeOverlay=!includeOverlay;prefs.edit().putBoolean("include_overlay",includeOverlay).apply();update()
        }
        resetZoom.setOnClickListener { manager?.setZoomRatio(1f) }
        gallery.setOnClickListener {
            lastUri?.let { uri ->
                runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,lastMime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }.onFailure { message("Keine passende Galerie-App gefunden.") }
            }
        }
        val pinch=ScaleGestureDetector(activity,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
            override fun onScale(detector:ScaleGestureDetector):Boolean { manager?.zoomByScale(detector.scaleFactor);return true }
        })
        val taps=GestureDetector(activity,object:GestureDetector.SimpleOnGestureListener(){
            override fun onDown(e:MotionEvent)=true
            override fun onSingleTapUp(e:MotionEvent):Boolean {
                if(!pinch.isInProgress && manager?.ready == true) {
                    focusRing.show(e.x,e.y,Color.YELLOW)
                    manager?.focusAt(e.x,e.y) { success ->
                        focusRing.show(e.x,e.y,if(success) Color.GREEN else Color.LTGRAY)
                        if(!success) message("Fokus nicht bestätigt; ggf. Fixfokus-Objektiv.")
                    }
                }
                return true
            }
            override fun onDoubleTap(e:MotionEvent):Boolean { manager?.setZoomRatio(1f);return true }
        })
        var multiTouch = false
        overlay.setOnTouchListener { v,e ->
            if(overlay.showVirtualSky || manager?.ready != true) false else {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) multiTouch = false
                if (e.pointerCount > 1 && !multiTouch) {
                    multiTouch = true
                    val cancel = MotionEvent.obtain(e)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    taps.onTouchEvent(cancel)
                    cancel.recycle()
                }
                pinch.onTouchEvent(e)
                if(e.pointerCount==1 && !pinch.isInProgress && !multiTouch) taps.onTouchEvent(e)
                if(e.actionMasked==MotionEvent.ACTION_UP && !multiTouch) v.performClick()
                true
            }
        }
        update()
    }

    fun bind(value: ArCameraManager) {
        manager=value
        value.attachCaptureOverlay(overlay)
        value.onControlsChanged={update()}
        value.onMessage={message(it)}
        update()
    }
    fun update() {
        val m=manager;val capture=m?.capture
        capture?.saveOverlay=includeOverlay
        val active=m?.ready==true && !overlay.showVirtualSky && m.currentProjectionData.isProjectionReady
        val recording=capture?.isRecording==true
        val busy=capture?.isBusy==true
        photo.isEnabled=active && !busy && !microphoneRequestInFlight
        video.isEnabled=recording || (active && !busy && !microphoneRequestInFlight && m?.videoAvailable==true)
        overlayOption.isEnabled=!busy
        resetZoom.isEnabled=active && capture?.isTakingPhoto!=true
        skyButton.isEnabled=!recording
        video.text=if(recording) "■ Stopp" else "● Video"
        video.setTextColor(if(recording) Color.rgb(255,90,90) else Color.CYAN)
        overlayOption.text=if(includeOverlay) "Overlay: AN" else "Overlay: AUS"
        if(capture?.lastUri!=null) {lastUri=capture.lastUri;lastMime=capture.lastMime}
        gallery.isEnabled=lastUri!=null
        status.text=when {
            overlay.showVirtualSky -> "Aufnahme in AR-Kamera\nPinch: Zoom • Tippen: Fokus"
            !active -> "Kamera startet …"
            else -> "%.2f× • %s".format(Locale.GERMANY,m?.globalZoom ?: 1f,capture?.status ?: "Vorschau")
        }
        microphoneLevel.visibility = if (overlay.showVirtualSky) View.GONE else View.VISIBLE
        microphoneLevel.setReading(capture?.microphoneReading ?: MicrophoneReading())
        container.keepScreenOn=recording
    }
    private fun requestVideoWithAudio() {
        if (microphoneRequestInFlight) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            val requestedBefore = prefs.getBoolean("microphone_requested", false)
            val canExplain = activity.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
            if (requestedBefore && !canExplain) {
                AlertDialog.Builder(activity).setTitle("Mikrofonfreigabe fehlt")
                    .setMessage("Für Videos mit Ton bitte das Mikrofon in den App-Berechtigungen freigeben. Es wird kein stummes Ersatzvideo gestartet.")
                    .setPositiveButton("Einstellungen") { _, _ ->
                        runCatching { activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${activity.packageName}"))) }
                            .onFailure { message("App-Berechtigungen bitte in den Android-Einstellungen öffnen.") }
                    }.setNegativeButton("Abbrechen", null).show()
            } else if (canExplain) {
                AlertDialog.Builder(activity).setTitle("Video mit Ton")
                    .setMessage("Das Mikrofon wird nur während einer Videoaufnahme verwendet. Die Pegelanzeige bleibt außerhalb des gespeicherten Bildes.")
                    .setPositiveButton("Freigeben") { _, _ -> requestMicrophonePermission() }
                    .setNegativeButton("Abbrechen", null).show()
            } else requestMicrophonePermission()
            return
        }
        withStorage {
            val m = manager
            // A permission callback must never start a hidden or no-longer-requested recording.
            if (!activity.isDestroyed && !activity.isFinishing && container.isShown && !overlay.showVirtualSky &&
                activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                m?.ready == true && m.videoAvailable && m.currentProjectionData.isProjectionReady) {
                m.capture?.startRecording()
            } else message("Kamera noch nicht bereit. Zum Starten erneut Video antippen.")
        }
    }

    private fun requestMicrophonePermission() {
        if (microphoneRequestInFlight || activity.isDestroyed || !container.isShown || overlay.showVirtualSky) return
        prefs.edit().putBoolean("microphone_requested", true).apply()
        microphoneRequestInFlight = true
        update()
        try { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) }
        catch (e: IllegalStateException) {
            microphoneRequestInFlight = false
            update()
            message("Mikrofonfreigabe konnte nicht geöffnet werden. Bitte erneut versuchen.")
        }
    }

    private fun withStorage(action:()->Unit) {
        if(Build.VERSION.SDK_INT<=28 && ContextCompat.checkSelfPermission(activity,Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED) {
            pendingStorageAction=action;storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else action()
    }
    private fun message(text:String) { if(!activity.isDestroyed) Toast.makeText(activity,text,Toast.LENGTH_LONG).show() }
    private fun dp(v:Int)=(v*activity.resources.displayMetrics.density).toInt()
    private fun row()=LinearLayout(activity).apply {orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
    private fun weighted()=LinearLayout.LayoutParams(0,dp(44),1f).apply {setMargins(dp(2),dp(2),dp(2),dp(2))}
    private fun button(label:String)=AppCompatButton(activity).apply {
        text=label;isAllCaps=false;textSize=11f;minWidth=0;minHeight=0
        setPadding(dp(5),0,dp(5),0);setTextColor(Color.CYAN);setBackgroundResource(R.drawable.bg_hud_button)
    }
    private class FocusRing(context:android.content.Context):View(context) {
        private var xPos=0f;private var yPos=0f;private var shown=false
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {style=Paint.Style.STROKE;strokeWidth=3f}
        fun show(x:Float,y:Float,color:Int){xPos=x;yPos=y;paint.color=color;shown=true;invalidate();removeCallbacks(hide);postDelayed(hide,1200)}
        private val hide=Runnable{shown=false;invalidate()}
        override fun onDraw(canvas:Canvas){if(shown) canvas.drawCircle(xPos,yPos,24*resources.displayMetrics.density,paint)}
    }
}
