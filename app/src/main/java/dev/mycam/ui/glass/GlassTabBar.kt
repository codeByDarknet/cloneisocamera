package dev.mycam.ui.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import dev.mycam.util.Colors
import dev.mycam.util.Haptics
import dev.mycam.util.dp
import dev.mycam.util.sp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A tab bar: a glass capsule of tabs, each an icon over its name, with a lighter lens
 * behind the current one that springs to the tab tapped. The current tab takes [tint].
 */
class GlassTabBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassView(context, attrs) {

    class Tab(val icon: Drawable, val label: String)

    var tabs: List<Tab> = emptyList()
        set(value) {
            field = value
            value.forEach { it.icon.mutate() }
            requestLayout()
            invalidate()
        }

    var selectedIndex = 0
        private set

    var onSelected: ((Int) -> Unit)? = null

    var tint: Int = 0xFF0A84FF.toInt()

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 10.5f.sp
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val lensFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val lensRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp
    }
    private val lens = RectF()
    private val inset = 4f.dp
    private val tabWidth = 86f.dp
    private val iconSize = 24.dp.roundToInt()

    private var position = 0f
    private var press = 0f
    private val positionSpring = Spring(0f, stiffness = 420f, dampingRatio = 0.78f) {
        position = it
        invalidate()
    }
    private val pressSpring = Spring(0f, stiffness = 500f, dampingRatio = 0.6f) {
        press = it
        invalidate()
    }

    init {
        style = Style.REGULAR
        isClickable = true
    }

    /** Programmatic selection; does not call [onSelected]. */
    fun setSelection(index: Int, animate: Boolean = false) {
        if (tabs.isEmpty()) return
        selectedIndex = index.coerceIn(0, tabs.lastIndex)
        if (animate) positionSpring.animateTo(selectedIndex.toFloat()) else positionSpring.snapTo(selectedIndex.toFloat())
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize((tabWidth * tabs.size + inset * 2).roundToInt(), widthMeasureSpec),
            resolveSize(58.dp.roundToInt(), heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        lensRim.shader = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(0x99FFFFFF.toInt(), 0x14FFFFFF, 0x55FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (tabs.isEmpty()) return
        val segment = (width - inset * 2) / tabs.size
        val left = inset + position * segment
        lens.set(left, inset, left + segment, height - inset)
        val grow = press * 3.dp
        lens.inset(-grow, -grow)
        val r = lens.height() / 2
        canvas.drawRoundRect(lens, r, r, lensFill)
        canvas.drawRoundRect(lens, r, r, lensRim)

        val iconTop = (height / 2f - 16.dp).roundToInt()
        val baseline = height / 2f + 17.dp
        tabs.forEachIndexed { i, tab ->
            val color = Colors.blend(0xFFFFFFFF.toInt(), tint, (1f - abs(position - i)).coerceIn(0f, 1f))
            val cx = (inset + segment * (i + 0.5f)).roundToInt()
            tab.icon.setTint(color)
            tab.icon.setBounds(cx - iconSize / 2, iconTop, cx + iconSize / 2, iconTop + iconSize)
            tab.icon.draw(canvas)
            labelPaint.color = color
            canvas.drawText(tab.label, cx.toFloat(), baseline, labelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || tabs.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressSpring.animateTo(1f)
            MotionEvent.ACTION_UP -> {
                pressSpring.animateTo(0f)
                val segment = (width - inset * 2) / tabs.size
                val index = ((event.x - inset) / segment).toInt().coerceIn(0, tabs.lastIndex)
                if (index != selectedIndex) {
                    setSelection(index, animate = true)
                    Haptics.tick(this)
                    onSelected?.invoke(index)
                }
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> pressSpring.animateTo(0f)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
