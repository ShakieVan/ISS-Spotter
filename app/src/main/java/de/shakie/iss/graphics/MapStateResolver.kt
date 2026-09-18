package de.shakie.iss.graphics

enum class MapSourcePreference {
    BLUE_MARBLE,
    SATELLITE
}

enum class EffectiveTextureSource {
    BLUE_MARBLE,
    SATELLITE_VIIRS
}

enum class SatelliteDownloadState {
    NOT_STARTED,
    PENDING,
    SUCCEEDED,
    FAILED
}

data class MapStateInput(
    val requestedSource: MapSourcePreference = MapSourcePreference.BLUE_MARBLE,
    val hasSatelliteTexture: Boolean = false,
    val downloadState: SatelliteDownloadState = SatelliteDownloadState.NOT_STARTED,
    val userCloudPreference: Boolean = true,
    val isReferenceMode: Boolean = false
)

data class EffectiveMapConfiguration(
    val activeTextureSource: EffectiveTextureSource,
    val showClouds: Boolean,
    val isReferenceMode: Boolean,
    val statusLabel: String,
    val isSatellitePending: Boolean,
    val isSatelliteFailed: Boolean
)

/**
 * Pure, deterministic state resolver for map source, cloud visibility, and reference mode.
 * Decouples requested mode from effectively rendered mode and prevents delayed downloads
 * from silently overriding user preferences or displaying stacked cloud layers.
 */
object MapStateResolver {

    fun resolve(input: MapStateInput): EffectiveMapConfiguration {
        // 1. Determine effective texture source
        val canUseSatellite = input.requestedSource == MapSourcePreference.SATELLITE && input.hasSatelliteTexture
        val effectiveSource = if (canUseSatellite) {
            EffectiveTextureSource.SATELLITE_VIIRS
        } else {
            EffectiveTextureSource.BLUE_MARBLE
        }

        // 2. Cloud visibility rules:
        // - If VIIRS is actually displayed: additional cloud layer is strictly OFF (baked into satellite image).
        // - In reference mode: additional clouds, shadows, and simulated lighting are strictly OFF.
        // - In Blue Marble: outside reference mode, user's saved cloud preference is used.
        val effectiveClouds = when {
            input.isReferenceMode -> false
            effectiveSource == EffectiveTextureSource.SATELLITE_VIIRS -> false
            else -> input.userCloudPreference
        }

        // 3. Status label & download status
        val isPending = input.requestedSource == MapSourcePreference.SATELLITE &&
                !input.hasSatelliteTexture &&
                input.downloadState == SatelliteDownloadState.PENDING

        val isFailed = input.requestedSource == MapSourcePreference.SATELLITE &&
                !input.hasSatelliteTexture &&
                input.downloadState == SatelliteDownloadState.FAILED

        val label = when {
            input.isReferenceMode && effectiveSource == EffectiveTextureSource.SATELLITE_VIIRS -> "🛰️ Satellit: Referenz"
            input.isReferenceMode -> "🌍 Referenz (Blue Marble)"
            effectiveSource == EffectiveTextureSource.SATELLITE_VIIRS -> "🛰️ Satellit: VIIRS"
            isPending -> "🛰️ Satellit: Lädt..."
            isFailed -> "🛰️ Satellit: Fehler (Fallback)"
            else -> "🌍 Karte: Blue Marble"
        }

        return EffectiveMapConfiguration(
            activeTextureSource = effectiveSource,
            showClouds = effectiveClouds,
            isReferenceMode = input.isReferenceMode,
            statusLabel = label,
            isSatellitePending = isPending,
            isSatelliteFailed = isFailed
        )
    }
}
