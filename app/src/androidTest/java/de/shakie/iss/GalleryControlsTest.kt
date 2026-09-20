package de.shakie.iss

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.provider.MediaStore
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatButton
import androidx.test.platform.app.InstrumentationRegistry
import de.shakie.iss.observer.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual Android intent/layout tests. Samsung album navigation still needs a Galaxy device. */
class GalleryControlsTest {
    @Test fun samsungAlbumRequestIsMixedMediaAndPackageScoped() {
        val intent = CaptureGalleryLauncher.albumIntent("-123", CaptureGalleryLauncher.SAMSUNG_PACKAGE)
        check(intent.action == Intent.ACTION_VIEW)
        check(intent.`package` == "com.sec.android.gallery3d" && intent.component == null)
        check(intent.type == "vnd.android.cursor.dir/image")
        check(intent.data!!.getQueryParameter("bucketId") == "-123")
        check(intent.data!!.getQueryParameter("mediaTypes") == "5")
    }
    @Test fun albumRequestDoesNotGrantAccessToTheMediaLibrary() {
        val intent = CaptureGalleryLauncher.albumIntent("42")
        check(intent.`package` == null && intent.clipData == null)
        check(intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0)
    }
    @Test fun photosAndVideosGetTheSameRelativePath() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val photo = CaptureAlbum.mediaValues("ISS_test.jpg", "image/jpeg")
        val video = CaptureAlbum.mediaValues("ISS_test.mp4", "video/mp4")
        check(photo.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) == "DCIM/ISS-Spotter/")
        check(photo.getAsString(MediaStore.MediaColumns.RELATIVE_PATH) == video.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        check(!photo.containsKey("_data") && !video.containsKey("_data"))
    }
    @Test fun modeButtonsFitRealAndroidFontMetricsAndPanelBounds() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            for (fontScale in listOf(1f, 1.3f, 2f)) {
                val config = Configuration(instrumentation.targetContext.resources.configuration).apply { this.fontScale = fontScale }
                val context = ContextThemeWrapper(instrumentation.targetContext.createConfigurationContext(config), R.style.Theme_IssSpotter)
                fun dp(n: Int) = CameraControlLayout.dp(context, n)
                val panel = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(10), dp(12), dp(10), dp(10))
                    setBackgroundResource(R.drawable.bg_telemetry_card)
                }
                val row = CameraControlLayout.row(context)
                val buttons = listOf("〰 Flugbahn: AN", "✦ Sternenhimmel").map { label ->
                    AppCompatButton(context).apply {
                        text = label; textSize = 12f; isAllCaps = false
                        CameraControlLayout.prepare(this)
                    }
                }
                buttons.forEach { row.addView(it, CameraControlLayout.weighted(context)) }
                panel.addView(row)
                panel.measure(View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                check(!row.isBaselineAligned && row.top >= panel.paddingTop)
                for (button in buttons) {
                    check(button.height >= dp(48))
                    check(button.layout.height <= button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                    check(button.top >= 0 && button.bottom <= row.height)
                    check(button.includeFontPadding)
                }
            }
        }
    }
    @Test fun replacingLayoutDoesNotReplaceModeClickListeners() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().targetContext, R.style.Theme_IssSpotter)
            var clicks = 0
            val button = AppCompatButton(context).apply { setOnClickListener { clicks++ } }
            CameraControlLayout.prepare(button)
            button.performClick()
            check(clicks == 1)
        }
    }
}
