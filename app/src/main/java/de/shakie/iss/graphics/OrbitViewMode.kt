package de.shakie.iss.graphics

/** User-facing view selection; independent of whether a satellite download has completed. */
enum class OrbitViewMode(
    val source: MapSourcePreference,
    val clouds: Boolean,
    val reference: Boolean
) {
    MAP(MapSourcePreference.BLUE_MARBLE, true, false),
    CLOUDS_OFF(MapSourcePreference.BLUE_MARBLE, false, false),
    SATELLITE(MapSourcePreference.SATELLITE, false, false),
    REFERENCE(MapSourcePreference.SATELLITE, false, true);

    fun next(): OrbitViewMode = when (this) {
        MAP -> CLOUDS_OFF
        CLOUDS_OFF -> SATELLITE
        SATELLITE -> REFERENCE
        REFERENCE -> MAP
    }

    companion object {
        fun fromState(source: MapSourcePreference, clouds: Boolean, reference: Boolean): OrbitViewMode = when {
            reference -> REFERENCE
            source == MapSourcePreference.SATELLITE -> SATELLITE
            clouds -> MAP
            else -> CLOUDS_OFF
        }
    }
}
