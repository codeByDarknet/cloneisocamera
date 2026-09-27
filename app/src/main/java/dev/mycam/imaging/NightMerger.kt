package dev.mycam.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.os.Build
import android.util.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Night mode: merges a burst of identically exposed JPEGs into one cleaner, brighter photo.
 *
 * 1. The sharpest frame becomes the reference and every other frame is aligned on it
 *    (a translation found coarse-to-fine, which is what hand shake mostly is over one second).
 * 2. Frames are averaged pixel by pixel, which divides the noise by sqrt(n). A pixel that differs too
 *    much from the reference (something moved) is left out, so moving subjects do not ghost.
 * 3. The cleaner result can take a strong shadow lift without turning into grain.
 *
 * Works band by band with [BitmapRegionDecoder]: only one full-size bitmap (the output) is ever
 * held in memory, whatever the number of frames.
 */
object NightMerger {

    private const val TAG = "NightMerger"
    private const val BAND_HEIGHT = 256
    /** Coarse alignment runs at 1/16 of the resolution, ±COARSE_RANGE pixels there. */
    private const val COARSE_SAMPLE = 16
    private const val COARSE_RANGE = 6
    /** Full-resolution refinement on a central patch, ±FINE_RANGE pixels around the coarse shift. */
    private const val FINE_PATCH = 512
    private const val FINE_RANGE = 10
    /** Luma difference (0‥255) from which a pixel starts, then stops, being merged. */
    private const val GHOST_LOW = 18
    private const val GHOST_HIGH = 48
    /** Mean brightness (0‥1) the shadow lift aims for. */
    private const val TARGET_MEAN = 0.36f

    class Result(val bitmap: Bitmap, val reference: ByteArray)

    fun merge(frames: List<ByteArray>): Result? {
        if (frames.isEmpty()) return null
        return try {
            mergeInternal(frames)
        } catch (e: Throwable) {
            // OutOfMemoryError included: the caller falls back to a single frame.
            Log.e(TAG, "Night merge failed", e)
            null
        }
    }

    private fun mergeInternal(frames: List<ByteArray>): Result? {
        val decoders = frames.map(::regionDecoder)
        try {
            val width = decoders[0].width
            val height = decoders[0].height
            val usable = decoders.indices.filter { decoders[it].width == width && decoders[it].height == height }

            // Alignment
            val small = usable.associateWith { lumaSampled(frames[it], COARSE_SAMPLE) }
            val patchRect = centeredRect(width, height, FINE_PATCH + 2 * (FINE_RANGE + COARSE_SAMPLE * COARSE_RANGE))
            val patches = usable.associateWith { luma(decoders[it].decodeRegion(patchRect, null)) }
            val reference = usable.maxByOrNull { sharpness(patches.getValue(it), patchRect.width(), patchRect.height()) }!!
            val refSmall = small.getValue(reference)

            val shifts = HashMap<Int, IntArray>()
            for (k in usable) {
                if (k == reference) {
                    shifts[k] = intArrayOf(0, 0)
                    continue
                }
                val coarse = bestShift(
                    refSmall.pixels, small.getValue(k).pixels, refSmall.width, refSmall.height,
                    centerX = 0, centerY = 0, range = COARSE_RANGE, margin = COARSE_RANGE, step = 1,
                )
                val fine = bestShift(
                    patches.getValue(reference), patches.getValue(k), patchRect.width(), patchRect.height(),
                    centerX = coarse[0] * COARSE_SAMPLE, centerY = coarse[1] * COARSE_SAMPLE,
                    range = FINE_RANGE, margin = FINE_RANGE + COARSE_SAMPLE * COARSE_RANGE, step = 3,
                )
                shifts[k] = fine
            }

            // Tone curve from the reference's mean brightness
            val mean = refSmall.pixels.average().toFloat() / 255f
            val lift = if (mean >= TARGET_MEAN) 0f else mean * (1f - TARGET_MEAN) / (TARGET_MEAN - mean)
            val curve = IntArray(256) { v ->
                val x = v / 255f
                val y = if (lift <= 0f) x else x * (1f + lift) / (x + lift)
                (y * 255f).roundToInt().coerceIn(0, 255)
            }

            // Merge band by band
            val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val others = usable.filter { it != reference }
            var y0 = 0
            while (y0 < height) {
                val y1 = minOf(height, y0 + BAND_HEIGHT)
                mergeBand(decoders, reference, others, shifts, width, height, y0, y1, curve, output)
                y0 = y1
            }
            return Result(output, frames[reference])
        } finally {
            decoders.forEach { it.recycle() }
        }
    }

    private fun mergeBand(
        decoders: List<BitmapRegionDecoder>,
        reference: Int,
        others: List<Int>,
        shifts: Map<Int, IntArray>,
        width: Int,
        height: Int,
        y0: Int,
        y1: Int,
        curve: IntArray,
        output: Bitmap,
    ) {
        val bandHeight = y1 - y0
        val count = width * bandHeight
        val ref = IntArray(count)
        decoders[reference].decodeRegion(Rect(0, y0, width, y1), null).also {
            it.getPixels(ref, 0, width, 0, 0, width, bandHeight)
            it.recycle()
        }
        // Weighted sums, weights in 1/256.
        val sumR = IntArray(count)
        val sumG = IntArray(count)
        val sumB = IntArray(count)
        val sumW = IntArray(count)
        for (i in 0 until count) {
            val c = ref[i]
            sumR[i] = (c shr 16 and 0xFF) shl 8
            sumG[i] = (c shr 8 and 0xFF) shl 8
            sumB[i] = (c and 0xFF) shl 8
            sumW[i] = 256
        }

        for (k in others) {
            val (dx, dy) = shifts.getValue(k).let { it[0] to it[1] }
            // Pixel (x, y) of the reference is pixel (x + dx, y + dy) of frame k.
            val src = Rect(dx, y0 + dy, width + dx, y1 + dy)
            if (!src.intersect(0, 0, width, height)) continue
            val frame = decoders[k].decodeRegion(src, null)
            val pixels = IntArray(src.width() * src.height())
            frame.getPixels(pixels, 0, src.width(), 0, 0, src.width(), src.height())
            frame.recycle()
            for (sy in 0 until src.height()) {
                val y = src.top + sy - dy - y0
                if (y !in 0 until bandHeight) continue
                val rowOut = y * width
                val rowIn = sy * src.width()
                for (sx in 0 until src.width()) {
                    val x = src.left + sx - dx
                    if (x !in 0 until width) continue
                    val c = pixels[rowIn + sx]
                    val r = c shr 16 and 0xFF
                    val g = c shr 8 and 0xFF
                    val b = c and 0xFF
                    val o = ref[rowOut + x]
                    val diff = abs((r + 2 * g + b) - ((o shr 16 and 0xFF) + 2 * (o shr 8 and 0xFF) + (o and 0xFF))) shr 2
                    val w = when {
                        diff <= GHOST_LOW -> 256
                        diff >= GHOST_HIGH -> continue
                        else -> 256 * (GHOST_HIGH - diff) / (GHOST_HIGH - GHOST_LOW)
                    }
                    val i = rowOut + x
                    sumR[i] += r * w
                    sumG[i] += g * w
                    sumB[i] += b * w
                    sumW[i] += w
                }
            }
        }

        // Average, then lift the shadows on luminance only so colors keep their saturation.
        for (i in 0 until count) {
            val w = sumW[i]
            val r = sumR[i] / w
            val g = sumG[i] / w
            val b = sumB[i] / w
            val luma = (r * 77 + g * 150 + b * 29) shr 8
            val out = if (luma == 0) {
                ref[i]
            } else {
                val gain = curve[luma] * 256 / luma
                val rr = (r * gain shr 8).coerceAtMost(255)
                val gg = (g * gain shr 8).coerceAtMost(255)
                val bb = (b * gain shr 8).coerceAtMost(255)
                (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
            ref[i] = out
        }
        output.setPixels(ref, 0, width, 0, y0, width, bandHeight)
    }

    // --- Alignment helpers ----------------------------------------------------------------------

    private class Plane(val pixels: IntArray, val width: Int, val height: Int)

    private fun regionDecoder(jpeg: ByteArray): BitmapRegionDecoder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
        }

    private fun lumaSampled(jpeg: ByteArray, sample: Int): Plane {
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        val plane = Plane(luma(bitmap), bitmap.width, bitmap.height)
        return plane
    }

    /** Luma plane of [bitmap] (recycled). */
    private fun luma(bitmap: Bitmap): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        for (i in pixels.indices) {
            val c = pixels[i]
            pixels[i] = ((c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        }
        return pixels
    }

    private fun centeredRect(width: Int, height: Int, size: Int): Rect {
        val w = minOf(size, width)
        val h = minOf(size, height)
        val left = (width - w) / 2
        val top = (height - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /** Sum of absolute gradients: blurrier frames (shake during exposure) score lower. */
    private fun sharpness(luma: IntArray, width: Int, height: Int): Long {
        var total = 0L
        for (y in 1 until height) {
            val row = y * width
            for (x in 1 until width) {
                val v = luma[row + x]
                total += abs(v - luma[row + x - 1]) + abs(v - luma[row - width + x])
            }
        }
        return total
    }

    /**
     * Translation (dx, dy) minimizing the mean absolute difference between ref(x, y) and
     * frame(x + dx, y + dy), searched in ±[range] around ([centerX], [centerY]).
     */
    private fun bestShift(
        ref: IntArray,
        frame: IntArray,
        width: Int,
        height: Int,
        centerX: Int,
        centerY: Int,
        range: Int,
        margin: Int,
        step: Int,
    ): IntArray {
        var best = Long.MAX_VALUE
        var bestX = centerX
        var bestY = centerY
        for (dy in centerY - range..centerY + range) {
            for (dx in centerX - range..centerX + range) {
                var sad = 0L
                var y = margin
                while (y < height - margin) {
                    val fy = y + dy
                    if (fy in 0 until height) {
                        val rowRef = y * width
                        val rowFrame = fy * width
                        var x = margin
                        while (x < width - margin) {
                            val fx = x + dx
                            if (fx in 0 until width) sad += abs(ref[rowRef + x] - frame[rowFrame + fx])
                            x += step
                        }
                    }
                    if (sad >= best) break
                    y += step
                }
                if (sad < best) {
                    best = sad
                    bestX = dx
                    bestY = dy
                }
            }
        }
        return intArrayOf(bestX, bestY)
    }
}
