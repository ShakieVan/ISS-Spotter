package de.shakie.iss.graphics

enum class CloudEncoding(val value: Float) {
    GRAYSCALE_MASK(0.0f),
    CLOUD_ALPHA_MASK(1.0f)
}

enum class CloudNoDataMode(val value: Float) {
    NODATA_NONE(0.0f),
    NODATA_ALPHA_ZERO(1.0f),
    NODATA_SENTINEL_ZERO(2.0f)
}

data class CloudSample(
    val opacity: Float,
    val isValid: Boolean
)

data class CloudMetadata(
    val provider: String = "Matteason (Testquelle)",
    val cacheFileName: String = "downloaded_live_clouds.jpg",
    val dimensions: String = "Unknown",
    val downloadTimeMillis: Long = 0L,
    val observationTime: String = "Aufnahmezeit: Unbekannt"
)

/**
 * Evaluates cloud opacity and observation validity according to the exact same logic
 * executed in materials/earth.mat shader.
 */
object CloudEvaluator {
    fun evaluate(
        r: Float,
        g: Float,
        b: Float,
        a: Float,
        encoding: CloudEncoding,
        noDataMode: CloudNoDataMode
    ): CloudSample {
        var isValid = true
        when (noDataMode) {
            CloudNoDataMode.NODATA_ALPHA_ZERO -> {
                if (a < 0.001f) {
                    isValid = false
                }
            }
            CloudNoDataMode.NODATA_SENTINEL_ZERO -> {
                if ((r * r + g * g + b * b) < 0.0001f) {
                    isValid = false
                }
            }
            CloudNoDataMode.NODATA_NONE -> {
                isValid = true
            }
        }

        val raw = if (encoding == CloudEncoding.CLOUD_ALPHA_MASK) a else r
        val opacity = raw.coerceIn(0.0f, 1.0f)

        return CloudSample(
            opacity = opacity,
            isValid = isValid
        )
    }
}
