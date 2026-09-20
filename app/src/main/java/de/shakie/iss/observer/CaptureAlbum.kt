package de.shakie.iss.observer

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/** One real directory for NEW photos and videos; old media are never moved/deleted. */
object CaptureAlbum {
    data class Item(val uri: Uri, val mime: String, val bucketId: String?, val addedSeconds: Long)

    @Suppress("DEPRECATION")
    fun mediaValues(name: String, mime: String): ContentValues {
        CaptureAlbumSpec.requireFileName(name, mime)
        return ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, CaptureAlbumSpec.RELATIVE_PATH)
            } else {
                // Android 9: called only after the existing runtime storage permission check.
                val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), CaptureAlbumSpec.NAME)
                check(folder.isDirectory || folder.mkdirs()) { "Aufnahmeordner konnte nicht angelegt werden" }
                put(MediaStore.MediaColumns.DATA, File(folder, name).absolutePath)
            }
        }
    }

    /** Run on a worker. Query owned, completed media only, not the user's entire library. */
    fun findLatest(context: Context): Item? {
        val collections = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        // Once present, the common album is the primary destination, even across app restarts.
        collections.mapNotNull { latestIn(context, it, CaptureAlbumSpec.RELATIVE_PATH) }
            .maxByOrNull { it.addedSeconds }?.let { return it }
        return CaptureAlbumSpec.LEGACY_PATHS.flatMap { path ->
            collections.mapNotNull { latestIn(context, it, path) }
        }.maxByOrNull { it.addedSeconds }
    }

    @Suppress("DEPRECATION")
    private fun latestIn(context: Context, collection: Uri, relativePath: String): Item? {
        val modern = Build.VERSION.SDK_INT >= 29
        val folder = if (!modern) File(Environment.getExternalStorageDirectory(), relativePath) else null
        val locationColumn = if (modern) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.Images.ImageColumns.BUCKET_ID, MediaStore.MediaColumns.DATE_ADDED, locationColumn)
        val selection = buildString {
            append(if (modern) "$locationColumn = ?" else "$locationColumn LIKE ?")
            if (modern) append(" AND ${MediaStore.MediaColumns.IS_PENDING} = 0")
            if (Build.VERSION.SDK_INT >= 30) append(" AND ${MediaStore.MediaColumns.IS_TRASHED} = 0")
            if (modern) append(" AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
        }
        val args = if (modern) arrayOf(relativePath, context.packageName) else arrayOf("${folder!!.absolutePath}/%")
        try {
            context.contentResolver.query(collection, projection, selection, args,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val bucketCol = cursor.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val locationCol = cursor.getColumnIndexOrThrow(locationColumn)
                while (cursor.moveToNext()) {
                    // A legacy LIKE query is not proof of a matching directory.
                    if (!modern && File(cursor.getString(locationCol) ?: "").parentFile != folder) continue
                    val mime = cursor.getString(mimeCol) ?: continue
                    if (mime != "image/jpeg" && mime != "video/mp4") continue
                    val id = cursor.getLong(idCol)
                    if (id < 0) continue
                    val bucket = if (bucketCol >= 0 && !cursor.isNull(bucketCol))
                        CaptureAlbumSpec.validBucketId(cursor.getString(bucketCol)) else null
                    return Item(ContentUris.withAppendedId(collection, id), mime, bucket, cursor.getLong(dateCol))
                }
            }
        } catch (e: RuntimeException) {
            // No broad READ_MEDIA / storage permission prompt just to launch another gallery.
            // Revoked access, legacy providers or missing optional columns -> gallery home.
            Log.w("CaptureAlbum", "Album metadata unavailable (${e.javaClass.simpleName})")
        }
        return null
    }
}
