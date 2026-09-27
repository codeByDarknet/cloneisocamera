package dev.mycam.ui.gallery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.widget.GridView
import dev.mycam.ui.glass.GlassBackdrop
import dev.mycam.util.dp
import kotlin.math.abs

/**
 * The library grid. Refreshes the glass backdrop whenever it draws something new, and turns a
 * pinch into a column change.
 *
 * It also selects by sliding: after [startDragSelect] (called on a long press) or, when
 * [swipeSelects] is on, after a sideways slide, the finger sweeps a range of items instead of
 * scrolling, and the grid scrolls by itself when the finger nears its top or bottom.
 */
class MediaGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GridView(context, attrs) {

    /** Called with +1 (pinch in: more, smaller cells) or −1 (pinch out: fewer, bigger cells). */
    var onPinchColumns: ((Int) -> Unit)? = null

    /** A slide selection started from [anchor]; the activity notes what was selected before. */
    var onDragSelectStart: ((anchor: Int) -> Unit)? = null

    /** The slide now covers [anchor]‥[current], in either order. */
    var onDragSelect: ((anchor: Int, current: Int) -> Unit)? = null

    /** In selection mode, a sideways slide selects without waiting for a long press. */
    var swipeSelects = false

    private var dragAnchor = INVALID_POSITION
    private var dragCurrent = INVALID_POSITION
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downPosition = INVALID_POSITION
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val edge = 90.dp
    private var autoScrollSpeed = 0

    private val autoScroll = object : Runnable {
        override fun run() {
            if (dragAnchor == INVALID_POSITION || autoScrollSpeed == 0) return
            scrollListBy(autoScrollSpeed)
            updateDrag(lastX, lastY)
            postOnAnimation(this)
        }
    }

    private var pinchScale = 1f
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            pinchScale = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchScale *= detector.scaleFactor
            val step = when {
                pinchScale > PINCH_THRESHOLD -> -1
                pinchScale < 1f / PINCH_THRESHOLD -> 1
                else -> 0
            }
            if (step != 0) {
                pinchScale = 1f
                onPinchColumns?.invoke(step)
            }
            return true
        }
    })

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // The backdrop capture itself draws us into a software canvas: don't loop on it.
        if (canvas.isHardwareAccelerated) GlassBackdrop.find(this)?.requestCapture()
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        return scaleDetector.isInProgress || super.onInterceptTouchEvent(ev)
    }

    /** Starts a slide selection at [position], for the rest of the current touch. */
    fun startDragSelect(position: Int) {
        if (position == INVALID_POSITION) return
        dragAnchor = position
        dragCurrent = position
        parent?.requestDisallowInterceptTouchEvent(true)
        onDragSelectStart?.invoke(position)
        onDragSelect?.invoke(position, position)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress || ev.pointerCount > 1) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                downPosition = pointToPosition(ev.x.toInt(), ev.y.toInt())
            }
            MotionEvent.ACTION_MOVE -> if (dragAnchor == INVALID_POSITION && swipeSelects) {
                val dx = abs(ev.x - downX)
                val dy = abs(ev.y - downY)
                // Sideways selects; up or down still scrolls.
                if (dx > touchSlop && dx > dy * 1.5f && downPosition != INVALID_POSITION) {
                    cancelListTouch(ev)
                    startDragSelect(downPosition)
                }
            }
        }
        if (dragAnchor != INVALID_POSITION) {
            dragTouch(ev)
            return true
        }
        return super.onTouchEvent(ev)
    }

    private fun dragTouch(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = ev.x
                lastY = ev.y
                updateDrag(ev.x, ev.y)
                val speed = when {
                    ev.y < paddingTop + edge -> -((paddingTop + edge - ev.y) / edge * MAX_SCROLL.dp).toInt().coerceAtLeast(2)
                    ev.y > height - paddingBottom - edge -> ((ev.y - (height - paddingBottom - edge)) / edge * MAX_SCROLL.dp).toInt().coerceAtLeast(2)
                    else -> 0
                }
                val wasScrolling = autoScrollSpeed != 0
                autoScrollSpeed = speed.coerceIn(-MAX_SCROLL.dp.toInt(), MAX_SCROLL.dp.toInt())
                if (autoScrollSpeed != 0 && !wasScrolling) postOnAnimation(autoScroll)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragAnchor = INVALID_POSITION
                dragCurrent = INVALID_POSITION
                autoScrollSpeed = 0
                removeCallbacks(autoScroll)
                // Let the list settle: it may have seen the start of this touch.
                cancelListTouch(ev)
            }
        }
    }

    private fun updateDrag(x: Float, y: Float) {
        val count = adapter?.count ?: return
        if (count == 0) return
        var position = pointToPosition(x.toInt(), y.toInt())
        if (position == INVALID_POSITION) {
            // Between cells keeps the last item; past the end of the items means the last one.
            val lastChild = getChildAt(childCount - 1) ?: return
            position = if (lastVisiblePosition == count - 1 && y > lastChild.bottom) count - 1 else return
        }
        if (position != dragCurrent) {
            dragCurrent = position
            onDragSelect?.invoke(dragAnchor, position)
        }
    }

    private fun cancelListTouch(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        super.onTouchEvent(cancel)
        cancel.recycle()
    }

    private companion object {
        const val PINCH_THRESHOLD = 1.35f

        /** Auto scroll per frame at the very edge, in dp. */
        const val MAX_SCROLL = 18
    }
}
