package dev.mycam.ui.viewer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.mycam.ui.glass.Spring

/**
 * A photo that can be pinched (1x to 5x), double-tapped (fit <-> 2.5x) and panned.
 * While zoomed it keeps every touch for itself so the pager does not steal the pan.
 */
class ZoomImageView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            resetZoom()
        }

    var onTap: (() -> Unit)? = null

    val isZoomed: Boolean get() = scale > 1.01f

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val drawMatrix = Matrix()
    private val bounds = RectF()

    /** Zoom relative to "fit", and the translation of the image center from the view center. */
    private var scale = 1f
    private var panX = 0f
    private var panY = 0f

    // Double-tap animation: interpolates scale and pan together.
    private var animFrom = FloatArray(3)
    private var animTo = FloatArray(3)
    private val zoomSpring = Spring(0f, stiffness = 300f, dampingRatio = 0.9f) { t ->
        scale = animFrom[0] + (animTo[0] - animFrom[0]) * t
        panX = animFrom[1] + (animTo[1] - animFrom[1]) * t
        panY = animFrom[2] + (animTo[2] - animFrom[2]) * t
        invalidate()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            zoomSpring.cancel()
            val newScale = (scale * detector.scaleFactor).coerceIn(MIN_PINCH, MAX_SCALE)
            val factor = newScale / scale
            // Zoom around the fingers.
            val fx = detector.focusX - width / 2f
            val fy = detector.focusY - height / 2f
            panX = fx - (fx - panX) * factor
            panY = fy - (fy - panY) * factor
            scale = newScale
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) = settle()
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            performClick()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) {
                animateTo(1f, 0f, 0f)
            } else {
                val fx = e.x - width / 2f
                val fy = e.y - height / 2f
                val (x, y) = clampPan(DOUBLE_TAP_SCALE, -fx * (DOUBLE_TAP_SCALE - 1f), -fy * (DOUBLE_TAP_SCALE - 1f))
                animateTo(DOUBLE_TAP_SCALE, x, y)
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!isZoomed || scaleDetector.isInProgress) return false
            val (x, y) = clampPan(scale, panX - dx, panY - dy)
            panX = x
            panY = y
            invalidate()
            return true
        }
    })

    fun resetZoom() {
        zoomSpring.cancel()
        scale = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    /** A single tap, from touch or from an accessibility service. */
    override fun performClick(): Boolean {
        super.performClick()
        onTap?.invoke()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestures.onTouchEvent(event)
        if (isZoomed || event.pointerCount > 1) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val fit = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val s = fit * scale
        drawMatrix.setScale(s, s)
        drawMatrix.postTranslate(
            (width - b.width * s) / 2f + panX,
            (height - b.height * s) / 2f + panY,
        )
        canvas.drawBitmap(b, drawMatrix, paint)
    }

    /** After a pinch: bounce back into 1…MAX and inside the edges. */
    private fun settle() {
        val target = scale.coerceIn(1f, MAX_SCALE)
        val (x, y) = if (target <= 1f) 0f to 0f else clampPan(target, panX * target / scale, panY * target / scale)
        animateTo(target, x, y)
    }

    private fun animateTo(targetScale: Float, x: Float, y: Float) {
        animFrom = floatArrayOf(scale, panX, panY)
        animTo = floatArrayOf(targetScale, x, y)
        zoomSpring.snapTo(0f)
        zoomSpring.animateTo(1f)
    }

    /** Keeps the image edges from leaving the screen while zoomed. */
    private fun clampPan(s: Float, x: Float, y: Float): Pair<Float, Float> {
        val b = bitmap ?: return 0f to 0f
        val fit = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        bounds.set(0f, 0f, b.width * fit * s, b.height * fit * s)
        val maxX = ((bounds.width() - width) / 2f).coerceAtLeast(0f)
        val maxY = ((bounds.height() - height) / 2f).coerceAtLeast(0f)
        return x.coerceIn(-maxX, maxX) to y.coerceIn(-maxY, maxY)
    }

    private companion object {
        const val MAX_SCALE = 5f
        const val MIN_PINCH = 0.7f
        const val DOUBLE_TAP_SCALE = 2.5f
    }
}
