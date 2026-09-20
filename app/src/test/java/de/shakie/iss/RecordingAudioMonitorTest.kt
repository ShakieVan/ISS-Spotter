package de.shakie.iss

import de.shakie.iss.observer.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class RecordingAudioMonitorTest {
    private fun near(actual: Double, expected: Double, tolerance: Double = 1e-6) {
        check(abs(actual - expected) <= tolerance) { "$actual != $expected" }
    }
    private fun reading(a: Double) = MicrophoneReading(MicrophoneState.ACTIVE, a, 1000L)

    @Test fun amplitudeIsLogarithmicAndBounded() {
        near(reading(1.0).peakDbfs!!, 0.0)
        near(reading(0.1).peakDbfs!!, -20.0)
        near(reading(0.01).peakDbfs!!, -40.0)
        near(reading(0.001).peakDbfs!!, -60.0)
        near(reading(0.1).barFraction.toDouble(), 2.0/3.0)
        near(reading(0.00001).barFraction.toDouble(), 0.0)
        near(reading(2.0).barFraction.toDouble(), 1.0)
    }

    @Test fun validSilenceIsDifferentFromMissingData() {
        check(reading(0.0).peakDbfs == Double.NEGATIVE_INFINITY)
        check(reading(0.0).barFraction == 0f)
        for (a in listOf(-0.1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            check(reading(a).peakDbfs == null)
            check(reading(a).barFraction == 0f)
        }
        check(MicrophoneReading(MicrophoneState.ACTIVE).peakDbfs == null)
    }

    @Test fun allInactiveStatesIgnoreAnAmplitude() {
        for (state in MicrophoneState.values().filter { it != MicrophoneState.ACTIVE }) {
            val r = MicrophoneReading(state, 1.0)
            check(r.peakDbfs == null && r.barFraction == 0f)
        }
    }

    @Test fun idleDoesNotAcceptMicrophoneData() {
        val m = RecordingAudioMonitor()
        m.sample(MicrophoneState.ACTIVE, 1.0, 1000L, 1000L)
        check(m.reading.state == MicrophoneState.IDLE)
        check(!m.hasActiveAudio)
    }

    @Test fun startHasNoFabricatedLevel() {
        val m = RecordingAudioMonitor()
        m.start()
        check(m.reading.state == MicrophoneState.STARTING)
        check(m.reading.amplitude == null && m.reading.barFraction == 0f)
        check(!m.hasActiveAudio && !m.hadAudioProblem)
    }

    @Test fun recorderZeroAmplitudeStillCountsAsActiveAudio() {
        val m = RecordingAudioMonitor(); m.start()
        m.sample(MicrophoneState.ACTIVE, 0.0, 1000L, 1_000_000_000L)
        check(m.hasActiveAudio && !m.hadAudioProblem)
        check(m.savedWarning().isEmpty())
        check(m.reading.peakDbfs == Double.NEGATIVE_INFINITY)
    }

    @Test fun startEventAloneIsNotConfirmationOfAnAudioTrack() {
        val m = RecordingAudioMonitor(); m.start()
        m.sample(MicrophoneState.ACTIVE, 0.0, 1000L, 0L)
        check(!m.hasActiveAudio)
        check(m.savedWarning().contains("nicht bestätigt"))
    }

    @Test fun systemMuteClearsMeterAndPreservesWarningAfterRecovery() {
        val m = RecordingAudioMonitor(); m.start()
        m.sample(MicrophoneState.ACTIVE, 0.9, 1000L, 1L)
        m.sample(MicrophoneState.SILENCED, 0.9, 1100L, 2L)
        check(m.hadAudioProblem && m.reading.barFraction == 0f)
        m.sample(MicrophoneState.ACTIVE, 0.1, 1200L, 3L)
        check(m.reading.state == MicrophoneState.ACTIVE)
        check(m.savedWarning().contains("unterbrochen"))
    }

    @Test fun disabledMutedAndErrorStatesNeverLookLikeSilence() {
        for (state in MicrophoneState.values().filter { it.warning }) {
            val m = RecordingAudioMonitor(); m.start()
            m.sample(state, 0.0, 1000L, 1L)
            check(m.hadAudioProblem && m.reading.peakDbfs == null)
            check(m.savedWarning().isNotEmpty())
        }
    }

    @Test fun finalizationClearsMeterAndLateEventsDoNotReviveIt() {
        val m = RecordingAudioMonitor(); m.start()
        m.sample(MicrophoneState.ACTIVE, 0.8, 1000L, 1L)
        m.stopping()
        m.sample(MicrophoneState.ACTIVE, 1.0, 1100L, 2L)
        check(m.reading.state == MicrophoneState.FINALIZING && m.reading.barFraction == 0f)
        m.sample(MicrophoneState.SOURCE_ERROR, 0.8, 1200L, 3L)
        check(m.reading.state == MicrophoneState.FINALIZING && m.hadAudioProblem)
    }

    @Test fun finishClearsStateAndRejectsLateSamples() {
        val m = RecordingAudioMonitor(); m.start()
        m.sample(MicrophoneState.ACTIVE, 1.0, 1000L, 1L)
        m.finish()
        m.sample(MicrophoneState.ACTIVE, 1.0, 1100L, 2L)
        check(m.reading == MicrophoneReading())
    }

    @Test fun nextRecordingResetsWarningsAndReadings() {
        val m = RecordingAudioMonitor(); m.start()
        m.sample(MicrophoneState.SOURCE_ERROR, 1.0, 1000L, 1L)
        m.finish(); m.start()
        check(!m.hadAudioProblem && !m.hasActiveAudio)
        check(m.reading == MicrophoneReading(MicrophoneState.STARTING))
    }

    @Test fun staleAndInvalidTimestampsAreNotFreshReadings() {
        val r = reading(0.1)
        check(!r.isStale(3000L))
        check(r.isStale(3001L))
        check(r.isStale(999L))
        check(!MicrophoneReading().isStale(999999L))
    }

    @Test fun invalidAndTooLargeSamplesAreSanitized() {
        val m = RecordingAudioMonitor(); m.start()
        m.sample(MicrophoneState.ACTIVE, Double.NaN, 0L, 1L)
        check(m.reading.amplitude == null)
        m.sample(MicrophoneState.ACTIVE, 2.0, 1L, 2L)
        check(m.reading.amplitude == 1.0)
    }

    @Test fun amplitudeSweepNeverExceedsTheScaleOrRunsBackward() {
        var previous = 0f
        for (i in 0..10000) {
            val r = reading(i/10000.0)
            check(r.barFraction in 0f..1f)
            check(r.barFraction >= previous)
            previous = r.barFraction
        }
    }

    /** Source integration guard, not a substitute for a real recording/device permission test. */
    @Test fun microphoneMeterIsNotInTheRecordedOverlayAndPermissionIsRequired() {
        fun source(path: String): String = listOf("src/main/$path", "app/src/main/$path")
            .map(::File).first { it.isFile }.readText()
        val capture = source("java/de/shakie/iss/observer/ArCaptureSession.kt")
        val controls = source("java/de/shakie/iss/observer/ObserverCameraControls.kt")
        val manifest = source("AndroidManifest.xml")
        val start = capture.substringAfter("fun startRecording()")
        check(start.indexOf("Manifest.permission.RECORD_AUDIO") < start.indexOf(".withAudioEnabled().start(main)"))
        check(start.contains(".withAudioEnabled().start(main)"))
        check(capture.contains("audio.audioAmplitude"))
        check(capture.contains("overlay.draw(canvas)"))
        check(!capture.contains("container.draw(") && !capture.contains("panel.draw("))
        check(!capture.contains("AudioRecord(") && !capture.contains("MediaRecorder("))
        check(controls.contains("panel.addView(microphoneLevel"))
        check(controls.contains("value.attachCaptureOverlay(overlay)"))
        check(!controls.contains("attachCaptureOverlay(panel)"))
        check(controls.contains("photo.setOnClickListener { withStorage { manager?.capture?.takePhoto() } }"))
        check(manifest.contains("android.permission.RECORD_AUDIO"))
        check(manifest.contains("android.hardware.microphone\" android:required=\"false\""))
    }
}
