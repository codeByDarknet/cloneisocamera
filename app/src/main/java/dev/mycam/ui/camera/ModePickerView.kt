package dev.mycam.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import dev.mycam.ui.glass.GlassView
import dev.mycam.ui.glass.Spring
import dev.mycam.util.Colors
import dev.mycam.util.Haptics
import dev.mycam.util.dp
import dev.mycam.util.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The camera mode picker: a compact glass capsule showing two neighbouring modes, the
 * current one in yellow on a lighter thumb. The pair slides and the capsule resizes with a
 * spring when the mode changes. Tap the other mode, or swipe the capsule, to switch.
 */
class ModePickerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassView(context, attrs) {

    var items: List<String> = emptyList()
        set(value) {
            field = value
            selectedIndex = selectedIndex.coerceIn(0, (value.size - 1).coerceAtLeast(0))
            measureLabels()
            snap()
        }

    /**
     * The mode shown on the right of the pair when it is selected (Photo): modes
     * before it pair with their right neighbour, the others with their left one.
     */
    var anchorIndex: Int = 0

    var selectedIndex: Int = 0
        private set

    var onSelected: ((Int) -> Unit)? = null

    var selectedTextColor: Int = 0xFFFFD60A.toInt()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13.sp
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val thumbRect = RectF()
    private val clip = Path()
    private val inset = 4f.dp
    private val labelPadding = 14f.dp

    /** Left edge and width of each label's slot along the strip. */
    private var lefts = FloatArray(0)
    private var slots = FloatArray(0)

    /** First mode of the visible pair, and the selected mode (fractional while animating). */
    private var window = 0f
    private var selection = 0f
    private val windowSpring = Spring(0f, stiffness = 420f, dampingRatio = 0.86f) {
        window = it
        requestLayout()
        invalidate()
    }
    private val selectionSpring = Spring(0f, stiffness = 420f, dampingRatio = 0.8f) {
        selection = it
        invalidate()
    }

    private var downX = 0f
    private var swiped = false

    init {
        style = Style.DARK
        isClickable = true
    }

    /** Programmatic selection; does not call [onSelected]. */
    fun setSelection(index: Int, animate: Boolean = false) {
        if (items.isEmpty()) return
        selectedIndex = index.coerceIn(0, items.lastIndex)
        if (animate) {
            windowSpring.animateTo(windowStart(selectedIndex).toFloat())
            selectionSpring.animateTo(selectedIndex.toFloat())
        } else {
            snap()
        }
    }

    private fun snap() {
        windowSpring.snapTo(windowStart(selectedIndex).toFloat())
        selectionSpring.snapTo(selectedIndex.toFloat())
        requestLayout()
        invalidate()
    }

    private fun windowStart(index: Int): Int {
        val start = if (index < anchorIndex) index else index - 1
        return start.coerceIn(0, (items.size - 2).coerceAtLeast(0))
    }

    private fun measureLabels() {
        slots = FloatArray(items.size) { textPaint.measureText(items[it]) + labelPadding * 2 }
        lefts = FloatArray(items.size)
        var x = 0f
        for (i in items.indices) {
            lefts[i] = x
            x += slots[i]
        }
    }

    /** Linear interpolation of a per-item value at a fractional index. */
    private fun at(values: FloatArray, p: Float): Float {
        if (values.isEmpty()) return 0f
        val i = p.toInt().coerceIn(0, values.lastIndex)
        val j = (i + 1).coerceAtMost(values.lastIndex)
        val t = (p - i).coerceIn(0f, 1f)
        return values[i] + (values[j] - values[i]) * t
    }

    /** Width of the pair starting at fractional index [start]. */
    private fun pairWidth(start: Float): Float {
        if (items.size < 2) return slots.firstOrNull() ?: 0f
        val pairs = FloatArray(items.size - 1) { slots[it] + slots[it + 1] }
        return at(pairs, start)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (pairWidth(window) + inset * 2).roundToInt()
        setMeasuredDimension(w, resolveSize(40.dp.roundToInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return
        val shift = inset - at(lefts, window)
        val r = height / 2f
        canvas.save()
        clip.reset()
        clip.addRoundRect(inset / 2, 0f, width - inset / 2, height.toFloat(), r, r, Path.Direction.CW)
        canvas.clipPath(clip)

        val thumbLeft = at(lefts, selection) + shift
        thumbRect.set(thumbLeft, inset, thumbLeft + at(slots, selection), height - inset)
        val tr = thumbRect.height() / 2
        canvas.drawRoundRect(thumbRect, tr, tr, thumbFill)

        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        items.forEachIndexed { i, label ->
            val x = lefts[i] + slots[i] / 2 + shift
            // Labels leaving the pair fade out as they slide under the capsule's edge.
            val visible = (1f - abs(i - (window + 0.5f)).minus(0.5f).coerceAtLeast(0f) * 1.6f).coerceIn(0f, 1f)
            if (visible <= 0f) return@forEachIndexed
            val weight = (1f - abs(selection - i)).coerceIn(0f, 1f)
            textPaint.color = Colors.blend(0xFFFFFFFF.toInt(), selectedTextColor, weight)
            textPaint.alpha = (visible * 255).roundToInt()
            canvas.drawText(label, x, baseline, textPaint)
        }
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || items.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                swiped = false
                isPressed = true
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (!swiped && abs(dx) > SWIPE_DISTANCE.dp) {
                    swiped = true
                    // Swiping left brings the next mode.
                    commit((selectedIndex + if (dx < 0) 1 else -1).coerceIn(0, items.lastIndex))
                }
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                if (!swiped) commit(indexAt(event.x))
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> isPressed = false
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun indexAt(x: Float): Int {
        val stripX = x - inset + at(lefts, window)
        return lefts.indices.firstOrNull { stripX < lefts[it] + slots[it] } ?: items.lastIndex
    }

    private fun commit(index: Int) {
        if (index == selectedIndex) return
        setSelection(index, animate = true)
        Haptics.tick(this)
        onSelected?.invoke(index)
    }

    private companion object {
        const val SWIPE_DISTANCE = 28
    }
}
