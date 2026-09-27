package dev.mycam.ui.glass

import android.graphics.Typeface
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import dev.mycam.util.dp
import dev.mycam.util.dpi

/**
 * Transient glass capsule shown at the top of the screen ("AE/AF LOCK", "Front camera
 * unavailable"...). A new message replaces the current one.
 */
class GlassToast(private val container: FrameLayout) {

    /** Distance from the top of [container]; set after window insets are known. */
    var topOffset: Int = 56.dpi

    private var current: GlassView? = null
    private val hide = Runnable { dismiss() }

    fun show(text: CharSequence, durationMs: Long = 2_400L, accent: Boolean = false) {
        container.removeCallbacks(hide)
        current?.let { container.removeView(it) }

        val label = TextView(container.context).apply {
            this.text = text
            textSize = 13f
            setTextColor(if (accent) 0xFFFFD60A.toInt() else 0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setPadding(18.dpi, 9.dpi, 18.dpi, 9.dpi)
        }
        val glass = GlassView(container.context).apply {
            style = GlassView.Style.DARK
            addView(label)
            alpha = 0f
        }
        container.addView(
            glass,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ).apply { topMargin = topOffset },
        )
        current = glass

        Spring(0f, stiffness = 360f, dampingRatio = 0.7f) { p ->
            glass.alpha = p.coerceIn(0f, 1f)
            glass.scaleX = 0.8f + 0.2f * p
            glass.scaleY = 0.8f + 0.2f * p
            glass.translationY = (1f - p) * -12.dp
        }.animateTo(1f)

        container.postDelayed(hide, durationMs)
    }

    fun dismiss() {
        val glass = current ?: return
        current = null
        glass.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(180)
            .withEndAction { container.removeView(glass) }
            .start()
    }
}
