package dev.mycam.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.mycam.ui.glass.Spring
import dev.mycam.util.dp

/**
 * The yellow focus square with its exposure "sun" slider: it lands with a bounce, pulses once
 * focus is acquired, then dims.
 */
class FocusIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f.dp
        color = YELLOW
    }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = YELLOW
    }
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f.dp
        strokeCap = Paint.Cap.ROUND
        color = YELLOW
    }

    private val boxSize = 76.dp
    private val box = RectF()
    private var centerX = 0f
    private var centerY = 0f
    private var visible = false
    private var scale = 1f
    private var exposure = 0f
    private var showSun = true

    private val scaleSpring = Spring(1f, stiffness = 420f, dampingRatio = 0.55f) {
        scale = it
        invalidate()
    }
    private val dim = Runnable { animate().alpha(0.5f).setDuration(300).start() }
    private val hide = Runnable {
        hide()
        onAutoHide?.invoke()
    }

    /** The square timed out (not after a manual [hide]): focus can go back to automatic. */
    var onAutoHide: (() -> Unit)? = null

    val isShowing: Boolean get() = visible

    fun showAt(x: Float, y: Float, withExposure: Boolean, autoHide: Boolean) {
        removeCallbacks(dim)
        removeCallbacks(hide)
        animate().cancel()
        centerX = x.coerceIn(boxSize / 2, width - boxSize / 2 - 30.dp)
        centerY = y.coerceIn(boxSize / 2, height - boxSize / 2)
        exposure = 0f
        showSun = withExposure
        visible = true
        alpha = 1f
        scaleSpring.snapTo(1.45f)
        scaleSpring.animateTo(1f)
        postDelayed(dim, DIM_DELAY_MS)
        if (autoHide) postDelayed(hide, HIDE_DELAY_MS)
    }

    /** Short "pulse" when focus is acquired. */
    fun pulse() {
        if (!visible) return
        scaleSpring.snapTo(1.08f)
        scaleSpring.animateTo(1f)
    }

    /** @param ev exposure bias in stops; moves the sun along its track. */
    fun setExposure(ev: Float) {
        if (!visible) return
        removeCallbacks(dim)
        animate().cancel()
        alpha = 1f
        exposure = ev
        postDelayed(dim, DIM_DELAY_MS)
        invalidate()
    }

    fun hide() {
        removeCallbacks(dim)
        removeCallbacks(hide)
        visible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible) return
        val half = boxSize / 2 * scale
        box.set(centerX - half, centerY - half, centerX + half, centerY + half)
        canvas.drawRect(box, boxPaint)

        // Small ticks at the middle of each side.
        val tick = 6.dp * scale
        canvas.drawLine(box.centerX(), box.top, box.centerX(), box.top + tick, boxPaint)
        canvas.drawLine(box.centerX(), box.bottom, box.centerX(), box.bottom - tick, boxPaint)
        canvas.drawLine(box.left, box.centerY(), box.left + tick, box.centerY(), boxPaint)
        canvas.drawLine(box.right, box.centerY(), box.right - tick, box.centerY(), boxPaint)

        if (showSun) drawSun(canvas)
    }

    private fun drawSun(canvas: Canvas) {
        val x = box.right + 16.dp
        val travel = boxSize * 0.7f
        val y = centerY - exposure / MAX_EV * travel
        val gap = 12.dp

        // Track above and below the sun (only drawn once the user starts dragging).
        if (exposure != 0f) {
            canvas.drawLine(x, centerY - travel - 8.dp, x, y - gap, rayPaint)
            canvas.drawLine(x, y + gap, x, centerY + travel + 8.dp, rayPaint)
        }
        canvas.drawCircle(x, y, 4.dp, sunPaint)
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val cos = Math.cos(angle).toFloat()
            val sin = Math.sin(angle).toFloat()
            canvas.drawLine(x + cos * 6.5f.dp, y + sin * 6.5f.dp, x + cos * 8.5f.dp, y + sin * 8.5f.dp, rayPaint)
        }
    }

    companion object {
        const val MAX_EV = 2f
        private const val YELLOW = 0xFFFFD60A.toInt()
        private const val DIM_DELAY_MS = 1_500L
        private const val HIDE_DELAY_MS = 5_000L
    }
}
