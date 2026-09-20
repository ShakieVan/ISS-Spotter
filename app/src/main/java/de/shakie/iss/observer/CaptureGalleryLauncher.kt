package de.shakie.iss.observer

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.provider.MediaStore
import android.util.Log

/** Album navigation is a compatibility request, not a guaranteed Samsung public API. */
class CaptureGalleryLauncher(private val activity: Activity, private val message: (String) -> Unit) {
    fun open(item: CaptureAlbum.Item?, homeOnly: Boolean = false) {
        if (activity.isFinishing || activity.isDestroyed) return
        val bucket = CaptureAlbumSpec.validBucketId(item?.bucketId)
        for (destination in GalleryOpenPolicy.destinations(bucket != null, item != null, homeOnly)) {
            val intent = when (destination) {
                GalleryDestination.SAMSUNG_ALBUM -> albumIntent(bucket!!, SAMSUNG_PACKAGE)
                GalleryDestination.SAMSUNG_HOME -> try {
                    activity.packageManager.getLaunchIntentForPackage(SAMSUNG_PACKAGE)
                } catch (_: RuntimeException) { null }
                GalleryDestination.SYSTEM_ALBUM -> albumIntent(bucket!!)
                GalleryDestination.SYSTEM_HOME -> Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_GALLERY)
                GalleryDestination.LAST_MEDIA -> Intent(Intent.ACTION_VIEW).setDataAndType(item!!.uri, item.mime).apply {
                    clipData = ClipData.newRawUri("ISS-Aufnahme", item.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } ?: continue
            if (tryStart(intent)) {
                if (destination == GalleryDestination.SAMSUNG_HOME || destination == GalleryDestination.SYSTEM_HOME) {
                    message(if (item == null) "Neue Aufnahmen findest du im Album „ISS-Spotter“." else
                        "In der Galerie das Album „ISS-Spotter“ wählen.")
                } else if (destination == GalleryDestination.LAST_MEDIA) {
                    message("Keine Galerie-Übersicht verfügbar; letzte Aufnahme geöffnet.")
                }
                Log.i("CaptureGallery", "Gallery launch accepted: $destination; album display needs device verification")
                return
            }
        }
        message("Keine passende Galerie-App verfügbar. Aufnahmen liegen unter DCIM/ISS-Spotter.")
    }

    private fun tryStart(intent: Intent): Boolean = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) { false }
      catch (_: SecurityException) { false }
      catch (_: IllegalArgumentException) { false }

    companion object {
        const val SAMSUNG_PACKAGE = "com.sec.android.gallery3d"

        /**
         * AOSP Gallery2-compatible bucket request (LocalSource.KEY_BUCKET_ID / mediaTypes).
         * Not an invented Samsung component/deep-link. A One UI version may ignore it.
         * mediaTypes=5 asks for images AND videos in the same directory.
         * Never grant permission to the whole MediaStore collection: the gallery uses its own
         * media permissions. Only the last-item fallback grants a single content URI.
         */
        fun albumIntent(bucketId: String, targetPackage: String? = null): Intent {
            val id = requireNotNull(CaptureAlbumSpec.validBucketId(bucketId))
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                .appendQueryParameter("bucketId", id)
                .appendQueryParameter("mediaTypes", "5")
                .build()
            return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.dir/image").apply {
                if (targetPackage != null) setPackage(targetPackage)
            }
        }
    }
}
