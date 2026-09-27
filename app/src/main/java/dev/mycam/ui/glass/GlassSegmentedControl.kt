package dev.mycam.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import dev.mycam.util.Haptics
import dev.mycam.util.Colors
import dev.mycam.util.dp
import dev.mycam.util.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Segmented control: a glass track with a glass "lens" thumb that can be tapped or
 * dragged; the thumb swells while held and springs into place on release.
 */
class GlassSegmentedControl @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassView(context, attrs) {

    var items: List<String> = emptyList()
        set(value) {
            field = value
            selectedIndex = selectedIndex.coerceIn(0, (value.size - 1).coerceAtLeast(0))
            requestLayout()
            invalidate()
        }

    var selectedIndex: Int = 0
        private set

    var onSelected: ((Int) -> Unit)? = null

    /** Label color of the selected segment (yellow for camera modes, white elsewhere). */
    var selectedTextColor: Int = 0xFFFFFFFF.toInt()
        set(value) {
            field = value
            invalidate()
        }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13.sp
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.04f
    }
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp
    }
    private val thumbRect = RectF()
    private val inset = 3f.dp

    /** Thumb position in segments (fractional while moving). */
    private var thumbPosition = 0f
    private var thumbSwell = 0f
    private val positionSpring = Spring(0f, stiffness = 420f, dampingRatio = 0.78f) {
        thumbPosition = it
        invalidate()
    }
    private val swellSpring = Spring(0f, stiffness = 500f, dampingRatio = 0.6f) {
        thumbSwell = it
        invalidate()
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var dragging = false
    private var dragOffset = 0f
    private var hoverIndex = 0

    init {
        style = Style.REGULAR
        isClickable = true
    }

    /** Programmatic selection; does not call [onSelected]. */
    fun setSelection(index: Int, animate: Boolean = false) {
        if (items.isEmpty()) return
        selectedIndex = index.coerceIn(0, items.lastIndex)
        hoverIndex = selectedIndex
        if (animate) positionSpring.animateTo(selectedIndex.toFloat())
        else positionSpring.snapTo(selectedIndex.toFloat())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val longest = items.maxOfOrNull { textPaint.measureText(it) } ?: 0f
        val desiredWidth = ((longest + 28.dp) * items.size + inset * 2).roundToInt()
        val desiredHeight = 36.dp.roundToInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        thumbRim.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x99FFFFFF.toInt(), 0x14FFFFFF, 0x55FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.isEmpty()) return
        val segment = (width - inset * 2) / items.size

        // Thumb: a smaller, brighter piece of glass that swells while dragged.
        val left = inset + thumbPosition * segment
        thumbRect.set(left, inset, left + segment, height - inset)
        val grow = thumbSwell * 4.dp
        thumbRect.inset(-grow, -grow)
        val r = thumbRect.height() / 2
        thumbFill.color = (((0.16f + thumbSwell * 0.1f) * 255).toInt() shl 24) or 0xFFFFFF
        canvas.drawRoundRect(thumbRect, r, r, thumbFill)
        canvas.drawRoundRect(thumbRect, r, r, thumbRim)

        val baseline = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2
        items.forEachIndexed { i, label ->
            val weight = (1f - abs(thumbPosition - i)).coerceIn(0f, 1f)
            textPaint.color = Colors.blend(0xFFFFFFFF.toInt(), selectedTextColor, weight)
            canvas.drawText(label, inset + segment * (i + 0.5f), baseline, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || items.isEmpty()) return false
        val segment = (width - inset * 2) / items.size
        fun indexAt(x: Float) = ((x - inset) / segment).toInt().coerceIn(0, items.lastIndex)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                dragging = false
                // Only a touch that starts on the thumb can drag it.
                dragOffset = if (indexAt(event.x) == selectedIndex) event.x - (inset + thumbPosition * segment) else -1f
                if (dragOffset >= 0f) swellSpring.animateTo(1f)
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && dragOffset >= 0f && abs(event.x - downX) > touchSlop) dragging = true
                if (dragging) {
                    val position = ((event.x - dragOffset - inset) / segment).coerceIn(0f, items.lastIndex.toFloat())
                    positionSpring.snapTo(position)
                    val nearest = position.roundToInt()
                    if (nearest != hoverIndex) {
                        hoverIndex = nearest
                        Haptics.tick(this)
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                swellSpring.animateTo(0f)
                val wasDragging = dragging
                val target = if (wasDragging) hoverIndex else indexAt(event.x)
                dragging = false
                // A drag already ticked while crossing segments.
                commit(target, withHaptic = !wasDragging)
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                swellSpring.animateTo(0f)
                dragging = false
                hoverIndex = selectedIndex
                positionSpring.animateTo(selectedIndex.toFloat())
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun commit(index: Int, withHaptic: Boolean) {
        positionSpring.animateTo(index.toFloat())
        hoverIndex = index
        if (index == selectedIndex) return
        selectedIndex = index
        if (withHaptic) Haptics.tick(this)
        onSelected?.invoke(index)
    }
}
