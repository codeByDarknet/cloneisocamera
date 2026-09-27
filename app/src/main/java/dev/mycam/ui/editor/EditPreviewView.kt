package dev.mycam.ui.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import dev.mycam.util.Haptics
import dev.mycam.util.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The picture being edited, fitted in the view. Holding a finger on it shows the original.
 * In crop mode it shows the whole frame with the crop box: drag a corner or an edge to resize
 * it, or inside to move it.
 */
class EditPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var image: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Shown while the picture is held. */
    var original: Bitmap? = null

    var cropMode = false
        set(value) {
            field = value
            invalidate()
        }

    /** The crop box, normalized to the frame. */
    val crop = RectF(0f, 0f, 1f, 1f)

    /** Width / height the crop box keeps, in frame pixels; null for a free crop. */
    var aspect: Float? = null

    var onCropChanged: (() -> Unit)? = null
    var onCompare: ((Boolean) -> Unit)? = null

    private val imageRect = RectF()
    private val box = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dim = Paint().apply { color = 0xA6000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.dp
        color = 0xE6FFFFFF.toInt()
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 0.8f.dp
        color = 0x80FFFFFF.toInt()
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f.dp
        strokeCap = Paint.Cap.SQUARE
        color = 0xFFFFFFFF.toInt()
    }

    private val padding = 18.dp
    private var comparing = false
    private val compareRunnable = Runnable {
        comparing = true
        Haptics.tick(this)
        onCompare?.invoke(true)
        invalidate()
    }

    private enum class Drag { NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }
    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    /** Fits the crop box to [ratio] (frame pixels), as large as possible and centered. */
    fun applyAspect(ratio: Float?) {
        aspect = ratio
        val bitmap = image ?: return
        if (ratio == null) return
        val frameRatio = bitmap.width.toFloat() / bitmap.height
        // Normalized width and height with (w * W) / (h * H) = ratio.
        var w = 1f
        var h = frameRatio / ratio
        if (h > 1f) {
            w = 1f / h
            h = 1f
        }
        crop.set(0.5f - w / 2, 0.5f - h / 2, 0.5f + w / 2, 0.5f + h / 2)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bitmap = if (comparing && !cropMode) original ?: image else image
        bitmap ?: return
        fit(bitmap)
        canvas.drawBitmap(bitmap, null, imageRect, bitmapPaint)
        if (!cropMode) return

        boxOnScreen()
        // Four bands around the box: an even-odd path is unreliable when both rects coincide.
        with(imageRect) {
            canvas.drawRect(left, top, right, box.top, dim)
            canvas.drawRect(left, box.bottom, right, bottom, dim)
            canvas.drawRect(left, box.top, box.left, box.bottom, dim)
            canvas.drawRect(box.right, box.top, right, box.bottom, dim)
        }
        canvas.drawRect(box, border)
        if (drag != Drag.NONE) {
            for (i in 1..2) {
                val x = box.left + box.width() * i / 3
                val y = box.top + box.height() * i / 3
                canvas.drawLine(x, box.top, x, box.bottom, grid)
                canvas.drawLine(box.left, y, box.right, y, grid)
            }
        }
        val l = 20.dp
        val o = 1.5f.dp
        with(box) {
            corner(canvas, left - o, top - o, l, l)
            corner(canvas, right + o, top - o, -l, l)
            corner(canvas, left - o, bottom + o, l, -l)
            corner(canvas, right + o, bottom + o, -l, -l)
        }
    }

    private fun corner(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        canvas.drawLine(x, y, x + dx, y, handle)
        canvas.drawLine(x, y, x, y + dy, handle)
    }

    private fun fit(bitmap: Bitmap) {
        val availW = width - padding * 2
        val availH = height - padding * 2
        val scale = min(availW / bitmap.width, availH / bitmap.height)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        imageRect.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    private fun boxOnScreen() {
        box.set(
            imageRect.left + crop.left * imageRect.width(),
            imageRect.top + crop.top * imageRect.height(),
            imageRect.left + crop.right * imageRect.width(),
            imageRect.top + crop.bottom * imageRect.height(),
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (image == null) return false
        return if (cropMode) cropTouch(event) else compareTouch(event)
    }

    private fun compareTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> postDelayed(compareRunnable, 220)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(compareRunnable)
                if (comparing) {
                    comparing = false
                    onCompare?.invoke(false)
                    invalidate()
                }
            }
        }
        return true
    }

    private fun cropTouch(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                boxOnScreen()
                drag = hit(x, y)
                if (drag == Drag.NONE) return false
                lastX = x
                lastY = y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE) return false
                dragBy(x - lastX, y - lastY)
                lastX = x
                lastY = y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != Drag.NONE) {
                    drag = Drag.NONE
                    invalidate()
                    onCropChanged?.invoke()
                }
            }
        }
        return true
    }

    private fun hit(x: Float, y: Float): Drag {
        val t = 30.dp
        val nearL = abs(x - box.left) < t
        val nearR = abs(x - box.right) < t
        val nearT = abs(y - box.top) < t
        val nearB = abs(y - box.bottom) < t
        val insideX = x > box.left - t && x < box.right + t
        val insideY = y > box.top - t && y < box.bottom + t
        return when {
            nearL && nearT -> Drag.TOP_LEFT
            nearR && nearT -> Drag.TOP_RIGHT
            nearL && nearB -> Drag.BOTTOM_LEFT
            nearR && nearB -> Drag.BOTTOM_RIGHT
            aspect != null && insideX && insideY -> Drag.MOVE
            nearL && insideY -> Drag.LEFT
            nearR && insideY -> Drag.RIGHT
            nearT && insideX -> Drag.TOP
            nearB && insideX -> Drag.BOTTOM
            box.contains(x, y) -> Drag.MOVE
            else -> Drag.NONE
        }
    }

    private fun dragBy(dx: Float, dy: Float) {
        boxOnScreen()
        val minSize = 56.dp
        val r = RectF(box)
        when (drag) {
            Drag.MOVE -> {
                val mx = dx.coerceIn(imageRect.left - r.left, imageRect.right - r.right)
                val my = dy.coerceIn(imageRect.top - r.top, imageRect.bottom - r.bottom)
                r.offset(mx, my)
            }
            Drag.LEFT -> r.left = (r.left + dx).coerceIn(imageRect.left, r.right - minSize)
            Drag.RIGHT -> r.right = (r.right + dx).coerceIn(r.left + minSize, imageRect.right)
            Drag.TOP -> r.top = (r.top + dy).coerceIn(imageRect.top, r.bottom - minSize)
            Drag.BOTTOM -> r.bottom = (r.bottom + dy).coerceIn(r.top + minSize, imageRect.bottom)
            else -> dragCorner(r, dx, dy, minSize)
        }
        crop.set(
            ((r.left - imageRect.left) / imageRect.width()).coerceIn(0f, 1f),
            ((r.top - imageRect.top) / imageRect.height()).coerceIn(0f, 1f),
            ((r.right - imageRect.left) / imageRect.width()).coerceIn(0f, 1f),
            ((r.bottom - imageRect.top) / imageRect.height()).coerceIn(0f, 1f),
        )
    }

    /** Moves a corner, the opposite one staying put; keeps [aspect] when set. */
    private fun dragCorner(r: RectF, dx: Float, dy: Float, minSize: Float) {
        val left = drag == Drag.TOP_LEFT || drag == Drag.BOTTOM_LEFT
        val top = drag == Drag.TOP_LEFT || drag == Drag.TOP_RIGHT
        val anchorX = if (left) r.right else r.left
        val anchorY = if (top) r.bottom else r.top
        var w = (if (left) anchorX - (r.left + dx) else r.right + dx - anchorX)
        var h = (if (top) anchorY - (r.top + dy) else r.bottom + dy - anchorY)
        val maxW = if (left) anchorX - imageRect.left else imageRect.right - anchorX
        val maxH = if (top) anchorY - imageRect.top else imageRect.bottom - anchorY
        w = w.coerceIn(minSize, maxW)
        h = h.coerceIn(minSize, maxH)
        aspect?.let { ratio ->
            if (w / h > ratio) w = h * ratio else h = w / ratio
            if (w < minSize) {
                w = minSize
                h = w / ratio
            }
            if (h < minSize) {
                h = minSize
                w = h * ratio
            }
            val fit = min(1f, min(maxW / w, maxH / h))
            w *= fit
            h *= fit
        }
        r.set(
            if (left) anchorX - w else anchorX,
            if (top) anchorY - h else anchorY,
            if (left) anchorX else anchorX + w,
            if (top) anchorY else anchorY + h,
        )
        r.sort()
        r.left = max(r.left, imageRect.left)
        r.top = max(r.top, imageRect.top)
    }
}
