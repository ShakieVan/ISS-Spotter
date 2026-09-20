package de.shakie.iss

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import de.shakie.iss.observer.*
import org.junit.Test

/** Android rendering tests only. Actual MP4 audio and UI isolation still need a camera test. */
class MicrophoneLevelViewTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }

    private fun bars(view: MicrophoneLevelView): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val top = (19 * view.resources.displayMetrics.density).toInt()
        val strip = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top)
        bitmap.recycle()
        return strip
    }

    private fun meter(): MicrophoneLevelView {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val d = context.resources.displayMetrics.density
        return MicrophoneLevelView(context).apply { layout(0, 0, (340*d).toInt(), (36*d).toInt()) }
    }

    @Test fun realAndroidCanvasDisplaysRecorderLevels() = onMain {
        val view = meter()
        view.setReading(MicrophoneReading(MicrophoneState.ACTIVE, 0.0, SystemClock.elapsedRealtime()))
        val silent = bars(view)
        view.setReading(MicrophoneReading(MicrophoneState.ACTIVE, 0.5, SystemClock.elapsedRealtime()))
        val loud = bars(view)
        try { check(!silent.sameAs(loud)) } finally { silent.recycle(); loud.recycle() }
    }

    @Test fun systemMuteDoesNotDisplayResidualAmplitude() = onMain {
        val view = meter()
        view.setReading(MicrophoneReading(MicrophoneState.SILENCED, 0.0, SystemClock.elapsedRealtime()))
        val muted = bars(view)
        view.setReading(MicrophoneReading(MicrophoneState.SILENCED, 1.0, SystemClock.elapsedRealtime()))
        val staleValue = bars(view)
        try { check(muted.sameAs(staleValue)) } finally { muted.recycle(); staleValue.recycle() }
    }

    @Test fun stoppedMeterMatchesInitialIdleBars() = onMain {
        val view = meter(); val initial = bars(view)
        view.setReading(MicrophoneReading(MicrophoneState.ACTIVE, 1.0, SystemClock.elapsedRealtime()))
        view.setReading(MicrophoneReading())
        val stopped = bars(view)
        try { check(initial.sameAs(stopped)); check(!view.isClickable) }
        finally { initial.recycle(); stopped.recycle() }
    }
}
