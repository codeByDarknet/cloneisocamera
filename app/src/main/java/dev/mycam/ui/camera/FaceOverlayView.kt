package dev.mycam.ui.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import dev.mycam.util.dp
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Squares around the faces the camera detects: the main face (the one focus and
 * exposure follow) in yellow, the others in white. Boxes glide to their new position and fade
 * in and out instead of jumping from one detection to the next.
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Box(val rect: RectF, val target: RectF) {
        var alpha = 0f
        var visible = true
        var primary = false
        var scale = APPEAR_SCALE
    }

    private val boxes = mutableListOf<Box>()
    private val drawRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f.dp
    }

    /** @param faces normalized (0..1) rectangles in this view, main face first. */
    fun setFaces(faces: List<RectF>) {
        if (width == 0 || height == 0) return
        val targets = faces.map(::toSquare)
        val candidates = boxes.filter { it.visible }.toMutableList()
        boxes.forEach { it.visible = false }
        targets.forEachIndexed { index, target ->
            // Same face as before if it is the closest box, and not farther than its own size.
            val match = candidates.minByOrNull { distance(it.target, target) }
                ?.takeIf { distance(it.target, target) < maxOf(it.target.width(), target.width()) }
            val box = match?.also { candidates.remove(it) } ?: Box(RectF(target), RectF(target)).also { boxes += it }
            box.target.set(target)
            box.visible = true
            box.primary = index == 0
        }
        postInvalidateOnAnimation()
    }

    fun clear() {
        boxes.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        var animating = false
        val iterator = boxes.iterator()
        while (iterator.hasNext()) {
            val box = iterator.next()
            animating = step(box) || animating
            if (!box.visible && box.alpha <= 0f) {
                iterator.remove()
                continue
            }
            val half = box.rect.width() / 2 * box.scale
            drawRect.set(box.rect.centerX() - half, box.rect.centerY() - half, box.rect.centerX() + half, box.rect.centerY() + half)
            paint.color = if (box.primary) YELLOW else WHITE
            paint.alpha = (box.alpha * (if (box.primary) 255 else 200)).toInt()
            canvas.drawRect(drawRect, paint)
        }
        if (animating) postInvalidateOnAnimation()
    }

    /** Advances one box by a frame; returns whether it is still moving. */
    private fun step(box: Box): Boolean {
        val r = box.rect
        val t = box.target
        r.set(lerp(r.left, t.left), lerp(r.top, t.top), lerp(r.right, t.right), lerp(r.bottom, t.bottom))
        box.scale += (1f - box.scale) * 0.25f
        box.alpha = if (box.visible) minOf(1f, box.alpha + FADE_STEP) else maxOf(0f, box.alpha - FADE_STEP)
        val settled = abs(r.left - t.left) < 0.5f && abs(r.top - t.top) < 0.5f &&
            abs(r.width() - t.width()) < 0.5f && abs(box.scale - 1f) < 0.005f
        if (settled) {
            r.set(t)
            box.scale = 1f
        }
        return !settled || box.alpha != (if (box.visible) 1f else 0f)
    }

    private fun lerp(from: Float, to: Float) = from + (to - from) * FOLLOW

    /** Faces are shown as squares, a bit larger than the detected face. */
    private fun toSquare(face: RectF): RectF {
        val cx = face.centerX() * width
        val cy = face.centerY() * height
        val half = maxOf(face.width() * width, face.height() * height) * 0.55f
        return RectF(cx - half, cy - half, cx + half, cy + half)
    }

    private fun distance(a: RectF, b: RectF) = hypot(a.centerX() - b.centerX(), a.centerY() - b.centerY())

    private companion object {
        const val YELLOW = 0xFFFFD60A.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val APPEAR_SCALE = 1.25f
        /** Fraction of the remaining distance covered each frame. */
        const val FOLLOW = 0.3f
        const val FADE_STEP = 0.12f
    }
}
