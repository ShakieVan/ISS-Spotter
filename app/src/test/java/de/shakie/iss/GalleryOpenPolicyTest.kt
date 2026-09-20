package de.shakie.iss

import de.shakie.iss.observer.*
import org.junit.Test
import java.io.File

/** Pure routing/input tests, not proof that a particular One UI build honors album intents. */
class GalleryOpenPolicyTest {
    @Test fun albumIsRequestedBeforeSamsungHome() {
        check(GalleryOpenPolicy.destinations(true, true) == listOf(
            GalleryDestination.SAMSUNG_ALBUM, GalleryDestination.SAMSUNG_HOME,
            GalleryDestination.SYSTEM_ALBUM, GalleryDestination.SYSTEM_HOME, GalleryDestination.LAST_MEDIA))
    }
    @Test fun emptyLibraryStillOpensGallery() {
        check(GalleryOpenPolicy.destinations(false, false) == listOf(
            GalleryDestination.SAMSUNG_HOME, GalleryDestination.SYSTEM_HOME))
    }
    @Test fun unavailableBucketDoesNotBecomeAGuessedAlbum() {
        val plan = GalleryOpenPolicy.destinations(false, true)
        check(plan.none { it == GalleryDestination.SAMSUNG_ALBUM || it == GalleryDestination.SYSTEM_ALBUM })
        check(plan.last() == GalleryDestination.LAST_MEDIA)
    }
    @Test fun longPressBypassesEveryAlbumAndMediaIntent() {
        for (album in listOf(false, true)) for (media in listOf(false, true)) {
            check(GalleryOpenPolicy.destinations(album, media, true) == listOf(
                GalleryDestination.SAMSUNG_HOME, GalleryDestination.SYSTEM_HOME))
        }
    }
    @Test fun fallbackOrderDoesNotSkipSamsungWhenAFileViewerExists() {
        val plan = GalleryOpenPolicy.destinations(true, true)
        fun firstAvailable(available: Set<GalleryDestination>) = plan.firstOrNull { it in available }
        check(firstAvailable(setOf(GalleryDestination.SAMSUNG_HOME, GalleryDestination.LAST_MEDIA)) == GalleryDestination.SAMSUNG_HOME)
        check(firstAvailable(setOf(GalleryDestination.SYSTEM_HOME)) == GalleryDestination.SYSTEM_HOME)
        check(firstAvailable(emptySet()) == null)
    }
    @Test fun signedMediaStoreBucketIdsArePreserved() {
        for (id in listOf(Int.MIN_VALUE, -42, 0, 42, Int.MAX_VALUE)) {
            check(CaptureAlbumSpec.validBucketId(id.toString()) == id.toString())
        }
    }
    @Test fun badBucketIdsCannotInjectQueryParameters() {
        for (id in listOf(null, "", "x", "42&mediaTypes=1", "1/2", "99999999999999999999", " 5")) {
            check(CaptureAlbumSpec.validBucketId(id) == null)
        }
    }
    @Test fun photoAndVideoShareARealDirectoryAndLegacyPathsAreSeparate() {
        check(CaptureAlbumSpec.RELATIVE_PATH == "DCIM/ISS-Spotter/")
        check(CaptureAlbumSpec.LEGACY_PATHS == listOf("Pictures/ISS-Spotter/", "Movies/ISS-Spotter/"))
        CaptureAlbumSpec.requireFileName("ISS_20260920_113000_001.jpg", "image/jpeg")
        CaptureAlbumSpec.requireFileName("ISS_20260920_113000_002.mp4", "video/mp4")
    }
    @Test fun outputNamesCannotEscapeTheAlbum() {
        for (name in listOf("../x.jpg", "ISS_../x.jpg", "ISS_..\\x.jpg", "ISS_\u0000.jpg", "ISS_file.mp4", "")) {
            check(runCatching { CaptureAlbumSpec.requireFileName(name, "image/jpeg") }.isFailure)
        }
        check(runCatching { CaptureAlbumSpec.requireFileName("ISS_x.jpg", "audio/mpeg") }.isFailure)
    }
    @Test fun productionCaptureAndControlsUseTheSharedHelpers() {
        fun source(name: String) = listOf("app/src/main/java/de/shakie/iss/observer/$name", "src/main/java/de/shakie/iss/observer/$name", "src/$name")
            .map(::File).first { it.isFile }.readText()
        val capture = source("ArCaptureSession.kt")
        val controls = source("ObserverCameraControls.kt")
        val layout = source("CameraControlLayout.kt")
        check(capture.split("CaptureAlbum.mediaValues(").size == 3)
        check(capture.contains("overlay.draw(canvas)") && !capture.contains("panel.draw("))
        check(capture.contains(".withAudioEnabled().start(main)"))
        check(controls.contains("button(\"Galerie öffnen\")"))
        check(controls.contains("withContext(Dispatchers.IO)"))
        check(controls.contains("manager?.busy != true"))
        check(!controls.contains("gallery.isEnabled=lastUri"))
        check(controls.contains("CameraControlLayout::prepare") && controls.contains("modeRow.addView(v, weighted())"))
        check(layout.contains("WRAP_CONTENT") && layout.contains("isBaselineAligned = false"))
    }
}
