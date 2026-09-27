package dev.mycam.ui.editor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import dev.mycam.util.Haptics
import dev.mycam.util.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The editor slider: a ruler of ticks dragged under a fixed yellow needle. It clicks at
 * every long tick and sticks a little at the neutral value; a double tap goes back to it.
 */
class RulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var min = -100f
    var max = 100f
    /** Value of the needle when nothing is changed. */
    var neutral = 0f
    /** Units between two ticks. */
    var tickStep = 2f

    var value = 0f
        private set

    /** Called while dragging, with fromUser = true. */
    var onValueChanged: ((Float, Boolean) -> Unit)? = null
    var onTrackingChanged: ((Boolean) -> Unit)? = null

    private val tickSpacing = 7.dp
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.2f.dp
        strokeCap = Paint.Cap.ROUND
    }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD60A.toInt()
        strokeWidth = 2.dp
        strokeCap = Paint.Cap.ROUND
    }
    private val neutralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    private var lastX = 0f
    private var dragging = false
    private var stickiness = 0f
    private var animator: ValueAnimator? = null

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            animateTo(neutral)
            return true
        }
    })

    private val unitsPerPx get() = tickStep / tickSpacing

    fun setValue(v: Float, notify: Boolean = false) {
        animator?.cancel()
        val clamped = v.coerceIn(min, max)
        if (clamped == value) return
        value = clamped
        invalidate()
        if (notify) onValueChanged?.invoke(value, true)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(value, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                value = it.animatedValue as Float
                invalidate()
                onValueChanged?.invoke(value, true)
            }
            start()
        }
        Haptics.tick(this)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(34.dp.roundToInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val first = (min / tickStep).toInt()
        val last = (max / tickStep).toInt()
        for (i in first..last) {
            val tickValue = i * tickStep
            val x = cx + (tickValue - value) / unitsPerPx
            if (x < -tickSpacing || x > width + tickSpacing) continue
            // Fade toward the edges.
            val edge = (1f - abs(x - cx) / cx).coerceIn(0f, 1f)
            val major = i % 5 == 0
            val half = if (major) 7.dp else 4.dp
            tickPaint.color = 0xFFFFFFFF.toInt()
            tickPaint.alpha = ((if (major) 200 else 110) * edge).roundToInt()
            canvas.drawLine(x, cy - half, x, cy + half, tickPaint)
            if (tickValue == neutral) {
                neutralPaint.alpha = (230 * edge).roundToInt()
                canvas.drawCircle(x, cy - half - 5.dp, 2.dp, neutralPaint)
            }
        }
        canvas.drawLine(cx, cy - 12.dp, cx, cy + 12.dp, needlePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                lastX = event.x
                dragging = true
                stickiness = 0f
                parent?.requestDisallowInterceptTouchEvent(true)
                onTrackingChanged?.invoke(true)
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                val dx = event.x - lastX
                lastX = event.x
                move(-dx * unitsPerPx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                onTrackingChanged?.invoke(false)
            }
        }
        return true
    }

    private fun move(delta: Float) {
        val old = value
        var next = (value + delta).coerceIn(min, max)
        // Stick at the neutral value for a few pixels, like a detent.
        if (old == neutral) {
            stickiness += delta
            if (abs(stickiness) < tickStep * 1.5f) return
            next = (neutral + stickiness).coerceIn(min, max)
            stickiness = 0f
        } else if ((old - neutral) * (next - neutral) < 0f) {
            next = neutral
        }
        if (next == old) return
        val major = tickStep * 5
        if ((old / major).toInt() != (next / major).toInt() || next == neutral || next == min || next == max) Haptics.tick(this)
        value = next
        invalidate()
        onValueChanged?.invoke(value, true)
    }
}
