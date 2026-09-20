package de.shakie.iss.observer

import kotlin.math.log10

/** Recorder status, not a second microphone consumer. Values come only from CameraX AudioStats. */
enum class MicrophoneState(val warning: Boolean = false) {
    IDLE, STARTING, ACTIVE, FINALIZING,
    DISABLED(true), SILENCED(true), MUTED(true), SOURCE_ERROR(true), ENCODER_ERROR(true), UNKNOWN(true)
}

data class MicrophoneReading(
    val state: MicrophoneState = MicrophoneState.IDLE,
    val amplitude: Double? = null,
    val sampledAtMillis: Long = 0L
) {
    /** Relative digital peak level; not calibrated sound pressure (dB SPL), nor a stereo meter. */
    val peakDbfs: Double? get() {
        val a = amplitude ?: return null
        if (state != MicrophoneState.ACTIVE || !a.isFinite() || a < 0.0) return null
        return if (a == 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(a.coerceAtMost(1.0))
    }
    val barFraction: Float get() {
        val db = peakDbfs ?: return 0f
        return ((db.coerceIn(FLOOR_DBFS, 0.0) - FLOOR_DBFS) / -FLOOR_DBFS).toFloat()
    }
    fun isStale(nowMillis: Long): Boolean = state == MicrophoneState.ACTIVE &&
        (nowMillis < sampledAtMillis || nowMillis - sampledAtMillis > STALE_AFTER_MILLIS)

    companion object {
        const val FLOOR_DBFS = -60.0
        const val STALE_AFTER_MILLIS = 2000L
    }
}

/** Pure state machine shared by recording and tests; no polling or background audio capture. */
class RecordingAudioMonitor {
    var reading = MicrophoneReading()
        private set
    var hadAudioProblem = false
        private set
    var hasActiveAudio = false
        private set
    private var accepting = false
    private var finalizing = false

    fun start() {
        accepting = true
        finalizing = false
        hadAudioProblem = false
        hasActiveAudio = false
        reading = MicrophoneReading(MicrophoneState.STARTING)
    }

    fun sample(state: MicrophoneState, amplitude: Double, nowMillis: Long, recordedDurationNanos: Long) {
        if (!accepting) return
        hadAudioProblem = hadAudioProblem || state.warning
        if (state == MicrophoneState.ACTIVE && recordedDurationNanos > 0L) hasActiveAudio = true
        // Late Status/Start events must not revive a stopped meter during MP4 finalization.
        if (finalizing) return
        reading = MicrophoneReading(state,
            amplitude.takeIf { state == MicrophoneState.ACTIVE && it.isFinite() && it >= 0.0 }?.coerceAtMost(1.0),
            nowMillis)
    }

    fun stopping() {
        if (!accepting) return
        finalizing = true
        reading = MicrophoneReading(MicrophoneState.FINALIZING)
    }

    /** Empty means no reported audio problem; this does not replace playing the saved file. */
    fun savedWarning(): String = when {
        !hasActiveAudio -> "Ton nicht bestätigt – bitte prüfen"
        hadAudioProblem -> "Ton war unterbrochen – bitte prüfen"
        else -> ""
    }

    fun finish() {
        accepting = false
        finalizing = false
        reading = MicrophoneReading()
    }
}
