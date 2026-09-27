package dev.mycam.ui.glass

import android.content.Context
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import dev.mycam.R
import dev.mycam.util.Colors
import dev.mycam.util.dp

/**
 * A glass surface: a container that paints, from back to front,
 *
 *  1. the blurred, saturated backdrop behind it, slightly magnified (the "lens"),
 *  2. a tint for legibility,
 *  3. a soft specular sheen from the top,
 *  4. a thin rim of light, brighter on the top-left and bottom-right edges.
 *
 * Interactive glass (buttons) springs up and glows under the finger.
 */
open class GlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class Style { REGULAR, DARK, CLEAR }

    /** Corner radius in px; negative means a capsule (radius = half the short side). */
    var cornerRadius: Float = -1f
        set(value) {
            field = value
            invalidateOutline()
            invalidate()
        }

    var style: Style = Style.REGULAR
        set(value) {
            field = value
            updateStylePaints()
            invalidate()
        }

    var interactive: Boolean = false

    /** Extra brightness (0..1), animated on press. */
    private var glow = 0f

    private var backdrop: GlassBackdrop? = null
    private var shader: BitmapShader? = null
    private var shaderSource: Any? = null
    private val shaderMatrix = Matrix()
    private val location = IntArray(2)
    private val rect = RectF()
    private val rimRect = RectF()

    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** What lies behind the glass outside the backdrop source: the screen's black background. */
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = RIM_WIDTH
    }

    private val pressSpring = Spring(0f, stiffness = 520f, dampingRatio = 0.6f) { p ->
        val scale = 1f + p * pressScale()
        scaleX = scale
        scaleY = scale
        glow = p.coerceIn(0f, 1f)
        updateSheen()
        invalidate()
    }

    init {
        setWillNotDraw(false)
        context.obtainStyledAttributes(attrs, R.styleable.GlassView).apply {
            cornerRadius = getDimension(R.styleable.GlassView_glassCornerRadius, -1f)
            style = Style.entries[getInt(R.styleable.GlassView_glassStyle, 0)]
            interactive = getBoolean(R.styleable.GlassView_glassInteractive, false)
            recycle()
        }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius())
            }
        }
        updateStylePaints()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backdrop = GlassBackdrop.find(this)?.also { it.register(this) }
    }

    override fun onDetachedFromWindow() {
        backdrop?.unregister(this)
        backdrop = null
        pressSpring.cancel()
        super.onDetachedFromWindow()
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        if (interactive && isEnabled) pressSpring.animateTo(if (pressed) 1f else 0f)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        rimRect.set(rect)
        rimRect.inset(RIM_WIDTH / 2, RIM_WIDTH / 2)
        rimPaint.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(Colors.white(0.32f), Colors.white(0.08f), Colors.white(0.03f), Colors.white(0.2f)),
            floatArrayOf(0f, 0.38f, 0.62f, 1f),
            Shader.TileMode.CLAMP,
        )
        updateSheen()
    }

    override fun onDraw(canvas: Canvas) {
        val r = radius()
        drawBackdrop(canvas, r)
        canvas.drawRoundRect(rect, r, r, tintPaint)
        canvas.drawRoundRect(rect, r, r, sheenPaint)
        canvas.drawRoundRect(rimRect, r, r, rimPaint)
    }

    private fun drawBackdrop(canvas: Canvas, r: Float) {
        val source = backdrop
        val bitmap = source?.bitmap
        if (bitmap == null) {
            canvas.drawRoundRect(rect, r, r, fallbackPaint)
            return
        }
        if (shaderSource !== bitmap) {
            shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shaderSource = bitmap
        }
        getLocationInWindow(location)
        shaderMatrix.setScale(1f / source.scaleX, 1f / source.scaleY)
        shaderMatrix.postTranslate(
            (source.sourceOrigin[0] - location[0]).toFloat(),
            (source.sourceOrigin[1] - location[1]).toFloat(),
        )
        // Lensing: what is seen through the glass is slightly magnified around its center.
        shaderMatrix.postScale(LENS_MAGNIFICATION, LENS_MAGNIFICATION, width / 2f, height / 2f)
        shader!!.setLocalMatrix(shaderMatrix)
        backdropPaint.shader = shader
        // Only the part of the glass over the source shows it; elsewhere (e.g. the black band
        // above the camera preview) the clamped edge pixels would smear across the glass.
        val left = (source.sourceOrigin[0] - location[0]).toFloat()
        val top = (source.sourceOrigin[1] - location[1]).toFloat()
        val right = left + bitmap.width / source.scaleX
        val bottom = top + bitmap.height / source.scaleY
        val covered = left <= 0f && top <= 0f && right >= width && bottom >= height
        if (!covered) canvas.drawRoundRect(rect, r, r, basePaint)
        canvas.save()
        if (!covered) canvas.clipRect(left, top, right, bottom)
        canvas.drawRoundRect(rect, r, r, backdropPaint)
        canvas.restore()
    }

    private fun updateStylePaints() {
        val (brightness, saturation) = when (style) {
            Style.REGULAR -> 0.74f to 1.5f
            Style.DARK -> 0.5f to 1.3f
            Style.CLEAR -> 1f to 1.3f
        }
        val matrix = ColorMatrix().apply { setSaturation(saturation) }
        matrix.postConcat(ColorMatrix().apply { setScale(brightness, brightness, brightness, 1f) })
        backdropPaint.colorFilter = ColorMatrixColorFilter(matrix)
        tintPaint.color = when (style) {
            // On black, both read as a dark gray (#1C to #26); over a picture, DARK
            // halves its brightness for legibility.
            Style.REGULAR -> Colors.white(0.12f)
            Style.DARK -> Colors.white(0.1f)
            Style.CLEAR -> Colors.white(0.03f)
        }
        fallbackPaint.color = when (style) {
            Style.CLEAR -> 0x33000000
            else -> 0xB31C1C1E.toInt()
        }
    }

    private fun updateSheen() {
        if (height == 0) return
        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, height.toFloat(),
            intArrayOf(Colors.white(0.07f + glow * 0.14f), Colors.white(0.01f + glow * 0.06f), Colors.white(0.02f + glow * 0.05f)),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    /** Small controls grow a lot, wide panels barely move. */
    private fun pressScale(): Float {
        val size = maxOf(width, height)
        return if (size <= 0) 0.1f else (12.dp / size).coerceIn(0.03f, 0.16f)
    }

    protected fun radius(): Float =
        if (cornerRadius < 0f) minOf(width, height) / 2f else cornerRadius

    private companion object {
        val RIM_WIDTH = 1.1f.dp
        const val LENS_MAGNIFICATION = 1.08f
    }
}
