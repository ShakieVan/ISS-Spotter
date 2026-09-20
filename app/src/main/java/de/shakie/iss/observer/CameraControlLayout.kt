package de.shakie.iss.observer

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil

/** Content-sized controls: inherited button font/padding may exceed a fixed 36/40 dp row. */
object CameraControlLayout {
    fun dp(context: Context, value: Int) = ceil(value * context.resources.displayMetrics.density.toDouble()).toInt()

    fun prepare(button: TextView) {
        button.gravity = Gravity.CENTER
        button.setSingleLine(false)
        button.setHorizontallyScrolling(false)
        button.maxLines = Int.MAX_VALUE
        button.includeFontPadding = true
        button.minHeight = dp(button.context, 48)
        button.minimumHeight = dp(button.context, 48)
        button.setPadding(dp(button.context, 8), dp(button.context, 8), dp(button.context, 8), dp(button.context, 8))
    }

    fun row(context: Context) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Mixed emoji/fonts and inherited button styles must not shift siblings by baseline.
        isBaselineAligned = false
    }

    fun weighted(context: Context) = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        val margin = dp(context, 2)
        setMargins(margin, margin, margin, margin)
    }
}
