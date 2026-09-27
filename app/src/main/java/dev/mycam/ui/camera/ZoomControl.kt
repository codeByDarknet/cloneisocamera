package dev.mycam.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.mycam.ui.glass.Spring
import dev.mycam.util.Formatters
import dev.mycam.util.Haptics
import dev.mycam.util.dp
import dev.mycam.util.sp
import kotlin.math.roundToInt

/**
 * The zoom presets floating over the preview (".5", "1x", "2"): no capsule, the
 * active preset in a small dark disc showing the live zoom factor in yellow, the others as bare
 * white numbers.
 */
class ZoomControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Zoom presets as shown to the user, ascending. */
    var stops: List<Float> = listOf(1f, 2f)
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var onStopSelected: ((Float) -> Unit)? = null

    private var displayZoom = 1f
    private var activeIndex = 0
    private val bubbleScale = FloatArray(MAX_STOPS) { 0f }
    private val springs = Array(MAX_STOPS) { i ->
        Spring(0f, stiffness = 480f, dampingRatio = 0.65f) {
            bubbleScale[i] = it
            invalidate()
        }
    }

    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x73000000 }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setShadowLayer(3f.dp, 0f, 0f, 0x80000000.toInt())
    }
    private val slot = 42.dp
    private val padding = 2.dp

    init {
        isClickable = true
    }

    /** Updates the displayed value (pinch or preset). */
    fun setZoom(value: Float) {
        displayZoom = value
        val index = stops.indexOfLast { value >= it - 0.05f }.coerceAtLeast(0)
        if (index != activeIndex || springs[index].target != 1f) {
            activeIndex = index
            stops.indices.forEach { springs[it].animateTo(if (it == index) 1f else 0f) }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (stops.size * slot + padding * 2).roundToInt()
        val h = (slot + padding * 2).roundToInt()
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        stops.forEachIndexed { i, stop ->
            val cx = padding + slot * (i + 0.5f)
            val active = bubbleScale[i]
            if (active > 0.01f) {
                bubblePaint.alpha = (active.coerceIn(0f, 1f) * 0x73).roundToInt()
                canvas.drawCircle(cx, cy, (slot / 2 - 4.dp) * (0.7f + 0.3f * active), bubblePaint)
            }

            val isActive = i == activeIndex
            val label = if (isActive) "${Formatters.zoom(displayZoom)}×" else Formatters.zoom(stop)
            textPaint.textSize = (12 + active * 1f).sp
            textPaint.color = if (isActive) YELLOW else 0xFFFFFFFF.toInt()
            val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(label, cx, baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> Unit
            MotionEvent.ACTION_UP -> {
                val index = ((event.x - padding) / slot).toInt().coerceIn(0, stops.lastIndex)
                Haptics.tick(this)
                onStopSelected?.invoke(stops[index])
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val MAX_STOPS = 4
        const val YELLOW = 0xFFFFD60A.toInt()
    }
}
