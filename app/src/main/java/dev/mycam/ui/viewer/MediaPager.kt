package dev.mycam.ui.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import dev.mycam.media.MediaItem
import dev.mycam.ui.glass.GlassBackdrop
import dev.mycam.ui.glass.Spring
import kotlin.math.abs

/**
 * Horizontal photo pager with three recycled pages (previous, current, next), spring snapping,
 * and "drag down to dismiss".
 */
class MediaPager(context: Context) : ViewGroup(context) {

    var items: List<MediaItem> = emptyList()
        private set

    var currentIndex: Int = 0
        private set

    var onPageChanged: ((Int) -> Unit)? = null
    var onPageTap: (() -> Unit)? = null
    var onPlaybackChanged: (() -> Unit)? = null

    /** 0 = in place, 1 = dragged far enough to close. Drives the background fade. */
    var onDismissProgress: ((Float) -> Unit)? = null
    var onDismiss: (() -> Unit)? = null

    /** pages[0] = previous, pages[1] = current, pages[2] = next. */
    private val pages = Array(3) { MediaPageView(context) }
    val currentPage: MediaPageView get() = pages[1]

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var mode = Drag.NONE

    private enum class Drag { NONE, HORIZONTAL, DISMISS }

    /** Horizontal offset of the strip, in px (negative = moving to the next page). */
    private var offset = 0f
    private val offsetSpring = Spring(0f, stiffness = 420f, dampingRatio = 0.9f) {
        offset = it
        positionPages()
    }

    private var dismissX = 0f
    private var dismissY = 0f
    private val dismissSpring = Spring(0f, stiffness = 380f, dampingRatio = 0.8f) { t ->
        applyDismiss(dismissX * t, dismissY * t)
    }

    init {
        pages.forEach { page ->
            addView(page)
            page.onTap = { onPageTap?.invoke() }
            page.onPlaybackChanged = { if (page === currentPage) onPlaybackChanged?.invoke() }
        }
    }

    fun setItems(newItems: List<MediaItem>, index: Int) {
        items = newItems
        currentIndex = index.coerceIn(0, (newItems.size - 1).coerceAtLeast(0))
        bindPages()
        onPageChanged?.invoke(currentIndex)
    }

    fun pauseAll() = pages.forEach { it.setActive(false) }

    fun resumeCurrent() {
        pages.forEachIndexed { i, page -> page.setActive(i == 1) }
    }

    private fun bindPages() {
        pages[0].bind(items.getOrNull(currentIndex - 1))
        pages[1].bind(items.getOrNull(currentIndex))
        pages[2].bind(items.getOrNull(currentIndex + 1))
        resumeCurrent()
        positionPages()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        pages.forEach {
            it.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        pages.forEach { it.layout(0, 0, r - l, b - t) }
        positionPages()
    }

    private fun positionPages() {
        val step = width + GAP
        pages.forEachIndexed { i, page -> page.translationX = (i - 1) * step + offset }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // The glass toolbar refracts the photo: refresh it whenever the pages move or change.
        // The capture itself draws on a software canvas, hence the guard against a loop.
        if (canvas.isHardwareAccelerated) GlassBackdrop.find(this)?.requestCapture()
    }

    // --- Touch ----------------------------------------------------------------------------------

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                mode = Drag.NONE
                // Catch a page that is still settling: the finger grabs it where it is.
                if (offsetSpring.value != offsetSpring.target) {
                    offsetSpring.cancel()
                    mode = Drag.HORIZONTAL
                    downX = ev.x - offset
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (ev.pointerCount > 1 || currentPage.image.isZoomed) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    mode = Drag.HORIZONTAL
                    downX = ev.x - offset
                    return true
                }
                if (dy > touchSlop && dy > abs(dx)) {
                    mode = Drag.DISMISS
                    downX = ev.x
                    downY = ev.y
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val tracker = velocity ?: VelocityTracker.obtain().also { velocity = it }
        tracker.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> when (mode) {
                Drag.HORIZONTAL -> {
                    var dx = ev.x - downX
                    // Rubber band at both ends.
                    if ((dx > 0 && currentIndex == 0) || (dx < 0 && currentIndex == items.lastIndex)) dx *= 0.35f
                    offsetSpring.snapTo(dx)
                }
                Drag.DISMISS -> {
                    dismissSpring.cancel()
                    applyDismiss(ev.x - downX, (ev.y - downY).coerceAtLeast(-40f))
                }
                Drag.NONE -> Unit
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                tracker.computeCurrentVelocity(1000)
                when (mode) {
                    Drag.HORIZONTAL -> settleHorizontal(tracker.xVelocity)
                    Drag.DISMISS -> settleDismiss(tracker.yVelocity)
                    Drag.NONE -> Unit
                }
                mode = Drag.NONE
                tracker.recycle()
                velocity = null
            }
        }
        return true
    }

    private fun settleHorizontal(vx: Float) {
        val step = width + GAP
        val direction = when {
            vx < -minFling || offset < -width / 3f -> 1
            vx > minFling || offset > width / 3f -> -1
            else -> 0
        }
        val target = currentIndex + direction
        if (direction == 0 || target !in items.indices) {
            offsetSpring.animateTo(0f)
            return
        }
        // Rotate the pages now and compensate the offset, so the spring just returns to 0.
        shift(direction)
        offsetSpring.snapTo(offset + direction * step)
        offsetSpring.animateTo(0f)
    }

    private fun shift(direction: Int) {
        currentIndex += direction
        if (direction > 0) {
            val recycled = pages[0]
            pages[0] = pages[1]
            pages[1] = pages[2]
            pages[2] = recycled
            recycled.bind(items.getOrNull(currentIndex + 1))
        } else {
            val recycled = pages[2]
            pages[2] = pages[1]
            pages[1] = pages[0]
            pages[0] = recycled
            recycled.bind(items.getOrNull(currentIndex - 1))
        }
        resumeCurrent()
        onPageChanged?.invoke(currentIndex)
    }

    private fun applyDismiss(x: Float, y: Float) {
        val progress = (y / DISMISS_DISTANCE).coerceIn(0f, 1f)
        val scale = 1f - 0.25f * progress
        currentPage.translationX = x
        currentPage.translationY = y
        currentPage.scaleX = scale
        currentPage.scaleY = scale
        pages[0].alpha = 1f - progress
        pages[2].alpha = 1f - progress
        onDismissProgress?.invoke(progress)
    }

    private fun settleDismiss(vy: Float) {
        if (currentPage.translationY > DISMISS_DISTANCE * 0.5f || vy > minFling) {
            onDismiss?.invoke()
            return
        }
        dismissX = currentPage.translationX
        dismissY = currentPage.translationY
        dismissSpring.snapTo(1f)
        dismissSpring.animateTo(0f)
    }

    private companion object {
        /** Black gap between photos while swiping. */
        const val GAP = 40
        const val DISMISS_DISTANCE = 600f
    }
}
