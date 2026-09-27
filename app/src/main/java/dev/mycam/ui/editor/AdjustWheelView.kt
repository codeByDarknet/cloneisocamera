package dev.mycam.ui.editor

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import dev.mycam.util.Haptics
import dev.mycam.util.dp
import dev.mycam.util.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The row of round adjustment buttons of the editor. The one in the middle is the one
 * being edited; each ring shows its value in yellow, clockwise for more and counter-clockwise
 * for less. Swipe the row or tap a button to bring it to the middle.
 */
class AdjustWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    class Item(val icon: Drawable, var value: Float = 0f, var active: Boolean = false)

    var items: List<Item> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var selectedIndex = 0
        private set

    var onSelected: ((Int) -> Unit)? = null
    /** Tap on the button already in the middle. */
    var onSelectedTapped: ((Int) -> Unit)? = null

    private val spacing = 58.dp
    private val radius = 21.dp
    /** Scroll position, in items (fractional while moving). */
    private var position = 0f

    private val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.dp
        color = 0x40FFFFFF
    }
    private val ringValue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.dp
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFD60A.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        textSize = 13.sp
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val arc = RectF()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var lastX = 0f
    private var dragging = false
    private var velocity: VelocityTracker? = null
    private var animator: ValueAnimator? = null

    fun select(index: Int, animate: Boolean) {
        if (items.isEmpty()) return
        val target = index.coerceIn(0, items.lastIndex)
        animator?.cancel()
        if (!animate) {
            position = target.toFloat()
            invalidate()
        } else {
            animator = ValueAnimator.ofFloat(position, target.toFloat()).apply {
                duration = 260
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    position = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
        if (target != selectedIndex) {
            selectedIndex = target
            Haptics.tick(this)
            onSelected?.invoke(target)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(56.dp.roundToInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        items.forEachIndexed { i, item ->
            val x = cx + (i - position) * spacing
            if (x < -spacing || x > width + spacing) return@forEachIndexed
            val centered = (1f - abs(i - position)).coerceIn(0f, 1f)
            val r = radius * (0.86f + 0.14f * centered)
            val edge = (1f - abs(x - cx) / (cx + spacing / 2)).coerceIn(0.25f, 1f)
            val alpha = (255 * edge).roundToInt()

            fill.alpha = (0x1F * edge).roundToInt()
            canvas.drawCircle(x, cy, r, fill)
            ringTrack.alpha = (0x40 * edge).roundToInt()
            canvas.drawCircle(x, cy, r, ringTrack)
            arc.set(x - r, cy - r, x + r, cy + r)
            ringValue.alpha = alpha
            if (item.active && item.value == 0f) {
                canvas.drawCircle(x, cy, r, ringValue)
            } else if (item.value != 0f) {
                canvas.drawArc(arc, -90f, 360f * item.value, false, ringValue)
            }

            // The button being edited shows its value instead of its icon.
            if (i == selectedIndex && item.value != 0f && centered > 0.5f) {
                numberPaint.alpha = alpha
                val text = (item.value * 100).roundToInt().toString()
                canvas.drawText(text, x, cy - (numberPaint.descent() + numberPaint.ascent()) / 2, numberPaint)
            } else {
                val s = (r * 0.95f).roundToInt()
                item.icon.setBounds((x - s / 2).roundToInt(), (cy - s / 2).roundToInt(), (x + s / 2).roundToInt(), (cy + s / 2).roundToInt())
                item.icon.setTint(if (item.value != 0f || item.active) 0xFFFFD60A.toInt() else 0xFFFFFFFF.toInt())
                item.icon.alpha = alpha
                item.icon.draw(canvas)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (items.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                downX = event.x
                lastX = event.x
                dragging = false
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                if (!dragging && abs(event.x - downX) > touchSlop) dragging = true
                if (dragging) {
                    position = (position - (event.x - lastX) / spacing).coerceIn(-0.4f, items.lastIndex + 0.4f)
                    val nearest = position.roundToInt().coerceIn(0, items.lastIndex)
                    if (nearest != selectedIndex) {
                        selectedIndex = nearest
                        Haptics.tick(this)
                        onSelected?.invoke(nearest)
                    }
                    invalidate()
                }
                lastX = event.x
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val fling = -(velocity?.xVelocity ?: 0f) / spacing * 0.18f
                    select((position + fling).roundToInt(), animate = true)
                } else {
                    val tapped = (position + (event.x - width / 2f) / spacing).roundToInt()
                    if (tapped == selectedIndex) onSelectedTapped?.invoke(tapped)
                    select(tapped, animate = true)
                }
                velocity?.recycle()
                velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                select(position.roundToInt(), animate = true)
                velocity?.recycle()
                velocity = null
            }
        }
        return true
    }
}
