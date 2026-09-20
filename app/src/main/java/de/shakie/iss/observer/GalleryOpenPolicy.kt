package de.shakie.iss.observer

/** Pure policy shared by capture storage and gallery navigation. No filesystem migration. */
object CaptureAlbumSpec {
    const val NAME = "ISS-Spotter"
    const val RELATIVE_PATH = "DCIM/ISS-Spotter/"
    val LEGACY_PATHS = listOf("Pictures/ISS-Spotter/", "Movies/ISS-Spotter/")

    fun validBucketId(value: String?): String? = value?.toIntOrNull()?.toString()

    fun requireFileName(name: String, mime: String) {
        val suffix = when (mime) {
            "image/jpeg" -> ".jpg"
            "video/mp4" -> ".mp4"
            else -> throw IllegalArgumentException("Unsupported capture type")
        }
        require(name.startsWith("ISS_") && name.endsWith(suffix) &&
            name.none { it == '/' || it == '\\' || it.code < 32 }) { "Invalid capture filename" }
    }
}

enum class GalleryDestination { SAMSUNG_ALBUM, SAMSUNG_HOME, SYSTEM_ALBUM, SYSTEM_HOME, LAST_MEDIA }

object GalleryOpenPolicy {
    fun destinations(hasAlbum: Boolean, hasMedia: Boolean, homeOnly: Boolean = false): List<GalleryDestination> = buildList {
        if (hasAlbum && !homeOnly) add(GalleryDestination.SAMSUNG_ALBUM)
        add(GalleryDestination.SAMSUNG_HOME)
        if (hasAlbum && !homeOnly) add(GalleryDestination.SYSTEM_ALBUM)
        add(GalleryDestination.SYSTEM_HOME)
        if (hasMedia && !homeOnly) add(GalleryDestination.LAST_MEDIA)
    }
}
