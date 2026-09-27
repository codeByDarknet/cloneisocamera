package dev.mycam.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import dev.mycam.R
import dev.mycam.imaging.PanoramaStitcher
import dev.mycam.util.dp
import dev.mycam.util.sp
import kotlin.math.abs

/**
 * The panorama guide drawn over the preview: a horizontal band across the middle in which
 * the panorama grows, the arrow at its leading edge riding up or down with the vertical drift,
 * the yellow line to keep it on, and a hint above.
 */
class PanoramaOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var active = false
    private var progress: PanoramaStitcher.Progress? = null

    private val band = RectF()
    private val imageRect = RectF()
    private val source = Rect()
    private val arrow = Path()

    private val bandFill = Paint().apply { color = 0x33000000 }
    private val bandStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f.dp
        color = 0x99FFFFFF.toInt()
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f.dp
        color = context.getColor(R.color.ios_yellow)
    }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(4f.dp, 0f, 0f, 0x66000000)
    }
    private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 15.sp
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setShadowLayer(6f.dp, 0f, 0f, 0x99000000.toInt())
    }

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // shadow layers
    }

    /** Shows the guide at rest (before recording, or cleared with false). */
    fun setActive(value: Boolean) {
        active = value
        progress = null
        invalidate()
    }

    fun update(value: PanoramaStitcher.Progress) {
        progress = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!active) return
        val margin = 20f.dp
        val bandHeight = BAND_HEIGHT.dp
        band.set(margin, height / 2f - bandHeight / 2, width - margin, height / 2f + bandHeight / 2)
        canvas.drawRect(band, bandFill)

        val state = progress
        val direction = state?.direction?.takeIf { it != 0 } ?: 1
        val preview = state?.preview

        // The panorama so far, filling the band from the starting side.
        var leading = if (direction > 0) band.left else band.right
        if (preview != null) {
            val scale = band.height() / preview.height
            val drawnWidth = minOf(preview.width * scale, band.width())
            // Once wider than the band, the newest part is shown.
            source.set(0, 0, (drawnWidth / scale).toInt().coerceAtMost(preview.width), preview.height)
            if (direction > 0) {
                source.offset(preview.width - source.width(), 0)
                imageRect.set(band.left, band.top, band.left + drawnWidth, band.bottom)
            } else {
                imageRect.set(band.right - drawnWidth, band.top, band.right, band.bottom)
            }
            canvas.drawBitmap(preview, source, imageRect, imagePaint)
            leading = if (direction > 0) imageRect.right else imageRect.left
        }
        canvas.drawRect(band, bandStroke)
        canvas.drawLine(band.left, band.centerY(), band.right, band.centerY(), guide)

        // Arrow at the leading edge, pointing the way to go.
        val drift = (state?.drift ?: 0f).coerceIn(-1f, 1f)
        val arrowY = band.centerY() + drift * band.height() / 2
        drawArrow(canvas, leading, arrowY, direction)

        val hint = when {
            state == null || state.direction == 0 -> R.string.pano_hint_idle
            state.tooFast -> R.string.pano_slow_down
            abs(drift) > 0.5f -> R.string.pano_keep_line
            else -> R.string.pano_hint_move
        }
        canvas.drawText(context.getString(hint), width / 2f, band.top - 18f.dp, hintPaint)
    }

    private fun drawArrow(canvas: Canvas, x: Float, y: Float, direction: Int) {
        val length = 22f.dp
        val half = 11f.dp
        val tip = x + direction * length / 2
        val back = x - direction * length / 2
        arrow.reset()
        arrow.moveTo(tip, y)
        arrow.lineTo(back, y - half)
        arrow.lineTo(back + direction * 5f.dp, y)
        arrow.lineTo(back, y + half)
        arrow.close()
        canvas.drawPath(arrow, arrowFill)
    }

    private companion object {
        const val BAND_HEIGHT = 84
    }
}
