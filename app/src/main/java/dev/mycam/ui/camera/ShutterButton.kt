package dev.mycam.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.mycam.camera.CaptureMode
import dev.mycam.ui.glass.Spring
import dev.mycam.util.Colors
import dev.mycam.util.dp

/**
 * The shutter: a white ring around a white disc (photo) or a red disc that morphs into
 * a rounded square while recording (video).
 */
class ShutterButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var mode: CaptureMode = CaptureMode.PHOTO
        set(value) {
            field = value
            colorSpring.animateTo(if (value.isVideo) 1f else 0f)
        }

    var recording: Boolean = false
        set(value) {
            field = value
            morphSpring.animateTo(if (value) 1f else 0f)
        }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFFFFFFF.toInt()
        strokeWidth = 4.2f.dp
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val discRect = RectF()

    /** 0 = white disc, 1 = red disc. */
    private var redness = 0f
    /** 0 = disc, 1 = recording square. */
    private var morph = 0f
    private var press = 0f

    private val colorSpring = Spring(0f, stiffness = 300f, dampingRatio = 1f) { redness = it; invalidate() }
    private val morphSpring = Spring(0f, stiffness = 340f, dampingRatio = 0.75f) { morph = it; invalidate() }
    private val pressSpring = Spring(0f, stiffness = 600f, dampingRatio = 0.7f) { press = it; invalidate() }

    init {
        isClickable = true
        isHapticFeedbackEnabled = true
    }

    override fun setPressed(pressed: Boolean) {
        super.setPressed(pressed)
        pressSpring.animateTo(if (pressed) 1f else 0f)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        alpha = if (enabled) 1f else 0.5f
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val outer = minOf(width, height) / 2f - ringPaint.strokeWidth / 2
        canvas.drawCircle(cx, cy, outer, ringPaint)

        val discRadius = outer - ringPaint.strokeWidth / 2 - 3.2f.dp
        // Pressing shrinks the disc a little; recording shrinks it into a square.
        val pressScale = 1f - 0.08f * press
        val half = lerp(discRadius, discRadius * 0.46f, morph) * pressScale
        val corner = lerp(half, 6.dp, morph)
        discRect.set(cx - half, cy - half, cx + half, cy + half)
        discPaint.color = Colors.blend(0xFFFFFFFF.toInt(), RED, redness)
        canvas.drawRoundRect(discRect, corner, corner, discPaint)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val RED = 0xFFFF3B30.toInt()
    }
}
