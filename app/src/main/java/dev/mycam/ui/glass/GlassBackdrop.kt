package dev.mycam.ui.glass

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.TextureView
import android.view.View
import dev.mycam.R

/**
 * The shared "what is behind the glass" image.
 *
 * Android views have no backdrop filter, so it is emulated: the content view (camera preview or
 * gallery grid) is sampled at 1/[DOWNSCALE] resolution, blurred on the CPU (a few thousand
 * pixels, well under a millisecond) and every [GlassView] paints the part that lies behind it.
 * Upscaling the tiny blurred frame with bilinear filtering gives the soft, frosted look.
 *
 * Install it once per screen with [install]; glass views find it through their root view.
 */
class GlassBackdrop private constructor(private val source: View) {

    var bitmap: Bitmap? = null
        private set

    /** Top-left of [source] in window coordinates when [bitmap] was captured. */
    val sourceOrigin = IntArray(2)

    /** Scale from source pixels to [bitmap] pixels. */
    var scaleX = 1f
        private set
    var scaleY = 1f
        private set

    private var work: Bitmap? = null
    private var pixels = IntArray(0)
    private var scratch = IntArray(0)
    private var lastCapture = 0L
    private var frameRequested = false
    private val clients = LinkedHashSet<View>()

    fun register(view: View) {
        clients += view
    }

    fun unregister(view: View) {
        clients -= view
    }

    /** Coalesced, throttled capture on the next frame. Safe to call on every scroll/frame. */
    fun requestCapture() {
        if (frameRequested) return
        frameRequested = true
        Choreographer.getInstance().postFrameCallback {
            frameRequested = false
            val now = SystemClock.uptimeMillis()
            if (now - lastCapture >= MIN_INTERVAL_MS) {
                lastCapture = now
                capture()
            } else {
                requestCapture()
            }
        }
    }

    private fun capture() {
        val w = source.width / DOWNSCALE
        val h = source.height / DOWNSCALE
        if (w <= 0 || h <= 0 || !source.isAttachedToWindow) return

        var target = work
        if (target == null || target.width != w || target.height != h) {
            target = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            work = target
        }
        // Checked on its own: the double buffer may hand back a bitmap of an earlier size
        // (4:3 -> 16:9 -> 4:3) while the arrays were sized for the latest one.
        if (pixels.size != w * h) {
            pixels = IntArray(w * h)
            scratch = IntArray(w * h)
        }

        val ok = try {
            if (source is TextureView) {
                if (!source.isAvailable) return
                source.getBitmap(target)
            } else {
                val canvas = Canvas(target)
                canvas.drawColor(0xFF000000.toInt())
                canvas.scale(w.toFloat() / source.width, h.toFloat() / source.height)
                source.draw(canvas)
            }
            true
        } catch (e: RuntimeException) {
            // e.g. hardware bitmaps inside a software canvas: glass falls back to its tint.
            Log.w(TAG, "Backdrop capture failed: ${e.message}")
            false
        }
        if (!ok) return

        target.getPixels(pixels, 0, w, 0, 0, w, h)
        BoxBlur.blur(pixels, scratch, w, h, BLUR_RADIUS)
        target.setPixels(pixels, 0, w, 0, 0, w, h)

        // Double buffering: glass views keep drawing the previous frame while the next is written.
        work = bitmap
        bitmap = target
        source.getLocationInWindow(sourceOrigin)
        scaleX = w.toFloat() / source.width
        scaleY = h.toFloat() / source.height
        clients.forEach { it.invalidate() }
    }

    companion object {
        private const val TAG = "GlassBackdrop"
        private const val DOWNSCALE = 8
        private const val BLUR_RADIUS = 2
        private const val MIN_INTERVAL_MS = 40L

        /** Makes [source] the backdrop of every glass view below [root]. */
        fun install(root: View, source: View): GlassBackdrop {
            val backdrop = GlassBackdrop(source)
            root.setTag(R.id.tag_glass_backdrop, backdrop)
            return backdrop
        }

        fun find(view: View): GlassBackdrop? =
            view.rootView?.getTag(R.id.tag_glass_backdrop) as? GlassBackdrop
                ?: generateSequence(view.parent as? View) { it.parent as? View }
                    .mapNotNull { it.getTag(R.id.tag_glass_backdrop) as? GlassBackdrop }
                    .firstOrNull()
    }
}

/** Three-pass separable box blur, a close approximation of a gaussian. */
private object BoxBlur {

    fun blur(pixels: IntArray, scratch: IntArray, w: Int, h: Int, radius: Int) {
        repeat(3) {
            horizontal(pixels, scratch, w, h, radius)
            vertical(scratch, pixels, w, h, radius)
        }
    }

    private fun horizontal(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var a = 0; var red = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                a += p ushr 24; red += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = ((a / div) shl 24) or ((red / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inp = src[row + (x + r + 1).coerceIn(0, w - 1)]
                a += (inp ushr 24) - (out ushr 24)
                red += ((inp shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inp shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inp and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun vertical(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
        val div = 2 * r + 1
        for (x in 0 until w) {
            var a = 0; var red = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                a += p ushr 24; red += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = ((a / div) shl 24) or ((red / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inp = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                a += (inp ushr 24) - (out ushr 24)
                red += ((inp shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                g += ((inp shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                b += (inp and 0xFF) - (out and 0xFF)
            }
        }
    }
}
