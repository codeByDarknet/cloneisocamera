package dev.mycam.imaging

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders [PhotoEdits]: the geometry (quarter turns, flip, straighten, crop) with a matrix, then
 * the light and color adjustments, the filter, the vignette and the sharpness on the pixels,
 * split in bands across the CPU cores. The same code renders the screen preview and the saved
 * full-resolution photo, so what is seen is what is saved.
 */
object EditRenderer {

    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
    private val pool = Executors.newFixedThreadPool(threads) { r ->
        Thread(r, "MyCam-edit").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    // --- Geometry -------------------------------------------------------------------------------

    /** Size of the frame after the quarter turns. */
    fun frameSize(width: Int, height: Int, edits: PhotoEdits): Pair<Int, Int> =
        if (edits.quarterTurns % 2 == 0) width to height else height to width

    /**
     * Zoom that keeps the straightened image covering the whole frame, so no empty corner
     * ever shows.
     */
    fun straightenScale(frameW: Float, frameH: Float, degrees: Float): Float {
        val a = Math.toRadians(abs(degrees).toDouble())
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        return max((frameW * c + frameH * s) / frameW, (frameW * s + frameH * c) / frameH)
    }

    /**
     * Maps [width]x[height] source pixels into the frame, cropped when [cropped], and scaled by
     * [scale]. Returns the matrix and the output size.
     */
    fun geometry(width: Int, height: Int, edits: PhotoEdits, cropped: Boolean, scale: Float = 1f): Triple<Matrix, Int, Int> {
        val (fw, fh) = frameSize(width, height, edits)
        val m = Matrix()
        m.postTranslate(-width / 2f, -height / 2f)
        m.postRotate(90f * (edits.quarterTurns % 4))
        if (edits.flipped) m.postScale(-1f, 1f)
        if (edits.straighten != 0f) {
            m.postRotate(edits.straighten)
            val s = straightenScale(fw.toFloat(), fh.toFloat(), edits.straighten)
            m.postScale(s, s)
        }
        m.postTranslate(fw / 2f, fh / 2f)
        var outW = fw.toFloat()
        var outH = fh.toFloat()
        if (cropped) {
            m.postTranslate(-edits.crop.left * fw, -edits.crop.top * fh)
            outW = edits.crop.width() * fw
            outH = edits.crop.height() * fh
        }
        m.postScale(scale, scale)
        return Triple(m, (outW * scale).roundToInt().coerceAtLeast(1), (outH * scale).roundToInt().coerceAtLeast(1))
    }

    /** A new mutable bitmap with the geometry applied; [maxSide] bounds the output size. */
    fun applyGeometry(source: Bitmap, edits: PhotoEdits, cropped: Boolean, maxSide: Int = Int.MAX_VALUE): Bitmap {
        val (_, w, h) = geometry(source.width, source.height, edits, cropped)
        val scale = min(1f, maxSide.toFloat() / max(w, h))
        val (matrix, outW, outH) = geometry(source.width, source.height, edits, cropped, scale)
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }

    // --- Color ----------------------------------------------------------------------------------

    /** Applies the color edits to [bitmap] in place. */
    fun applyColor(bitmap: Bitmap, edits: PhotoEdits) {
        val program = Program(edits)
        if (program.isIdentity) return
        val w = bitmap.width
        val h = bitmap.height
        val bandHeight = ((h + threads * 2 - 1) / (threads * 2)).coerceAtLeast(16)
        val tasks = (0 until h step bandHeight).map { top ->
            Callable {
                val bottom = min(h, top + bandHeight)
                // One extra row above and below for the sharpening kernel.
                val readTop = max(0, top - 1)
                val readBottom = min(h, bottom + 1)
                val rows = readBottom - readTop
                val pixels = IntArray(w * rows)
                bitmap.getPixels(pixels, 0, w, 0, readTop, w, rows)
                for (y in 0 until rows) program.colorRow(pixels, y * w, w, readTop + y, h)
                val result = if (program.sharpness != 0f) program.sharpen(pixels, w, rows) else pixels
                bitmap.setPixels(result, (top - readTop) * w, w, 0, top, w, bottom - top)
            }
        }
        pool.invokeAll(tasks).forEach { it.get() }
    }

    /** Adjustment values for Auto, picked from the picture's histogram. */
    fun autoValues(bitmap: Bitmap): Map<Adjustment, Float> {
        val w = bitmap.width
        val h = bitmap.height
        val histogram = IntArray(256)
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                histogram[luma((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)]++
                x += 3
            }
            y += 3
        }
        val total = histogram.sum().coerceAtLeast(1)
        fun percentile(p: Float): Float {
            var sum = 0
            for (i in 0..255) {
                sum += histogram[i]
                if (sum >= total * p) return i / 255f
            }
            return 1f
        }
        var meanLinear = 0.0
        for (i in 0..255) meanLinear += histogram[i] * SRGB_TO_LINEAR[i]
        meanLinear /= total

        val low = percentile(0.005f)
        val p05 = percentile(0.05f)
        val p95 = percentile(0.95f)
        val high = percentile(0.995f)
        val result = mutableMapOf<Adjustment, Float>()
        // Bring the average toward a mid-gray (0.18 linear).
        val stops = (ln(0.18 / meanLinear.coerceAtLeast(0.005)) / ln(2.0)).toFloat()
        result[Adjustment.EXPOSURE] = (stops / EXPOSURE_STOPS * 0.6f).coerceIn(-0.5f, 0.6f)
        if (high > 0.95f) result[Adjustment.HIGHLIGHTS] = -0.35f
        if (p05 < 0.08f) result[Adjustment.SHADOWS] = 0.3f
        if (low > 0.04f) result[Adjustment.BLACK_POINT] = (low * 3f).coerceAtMost(0.4f)
        val spread = p95 - p05
        if (spread < 0.65f) result[Adjustment.CONTRAST] = ((0.65f - spread) * 0.9f).coerceAtMost(0.35f)
        result[Adjustment.BRILLIANCE] = 0.15f
        result[Adjustment.VIBRANCE] = 0.2f
        return result.filterValues { abs(it) >= 0.01f }
    }

    // --- Pixel program --------------------------------------------------------------------------

    /** Tone and white balance, the parameters shared by the adjustments and the filters. */
    private class Tone(
        val exposure: Float = 0f,
        val brightness: Float = 0f,
        val contrast: Float = 0f,
        val highlights: Float = 0f,
        val shadows: Float = 0f,
        val blackPoint: Float = 0f,
        val warmth: Float = 0f,
        val tint: Float = 0f,
    ) {
        /** One lookup table per channel. */
        fun luts(): Array<IntArray> {
            val gain = 2f.pow(exposure * EXPOSURE_STOPS)
            val gains = floatArrayOf(1f + 0.22f * warmth + 0.06f * tint, 1f - 0.16f * tint, 1f - 0.22f * warmth + 0.06f * tint)
            return Array(3) { channel ->
                IntArray(256) { i ->
                    var linear = SRGB_TO_LINEAR[i] * gain * gains[channel]
                    // Soft shoulder instead of a hard clip when brightening.
                    if (linear > 0.8f) linear = 0.8f + 0.2f * (1f - exp(-(linear - 0.8f) / 0.2f))
                    (curve(linearToSrgb(linear)) * 255f).roundToInt().coerceIn(0, 255)
                }
            }
        }

        private fun curve(value: Float): Float {
            var x = value
            if (blackPoint > 0f) {
                val cut = 0.12f * blackPoint
                x = (x - cut) / (1f - cut)
            } else if (blackPoint < 0f) {
                val lift = -0.12f * blackPoint
                x = lift + x * (1f - lift)
            }
            x = x.coerceIn(0f, 1f)
            // Shadows move the dark tones and leave the bright ones.
            if (shadows != 0f) x += shadows * x * (1f - x) * (1f - x) * if (shadows > 0f) 1.6f else 0.9f
            // Highlights move the bright tones and leave the dark ones.
            if (highlights != 0f) x += highlights * x * x * (1f - x) * if (highlights > 0f) 0.9f else 1.6f
            x = x.coerceIn(0f, 1f)
            if (brightness != 0f) x = x.pow(2f.pow(-0.75f * brightness))
            if (contrast > 0f) {
                val s = x * x * (3f - 2f * x)
                x += (s - x) * contrast
            } else if (contrast < 0f) {
                x += (0.5f + (x - 0.5f) * 0.5f - x) * -contrast
            }
            return x.coerceIn(0f, 1f)
        }
    }

    /** What a filter does, on top of the adjustments. */
    private class FilterLook(val tone: Tone, val saturation: Float, val mono: Boolean = false)

    private fun look(filter: PhotoFilter): FilterLook? = when (filter) {
        PhotoFilter.ORIGINAL -> null
        PhotoFilter.VIVID -> FilterLook(Tone(contrast = 0.25f, brightness = 0.05f), 1.35f)
        PhotoFilter.VIVID_WARM -> FilterLook(Tone(contrast = 0.22f, brightness = 0.05f, warmth = 0.4f), 1.3f)
        PhotoFilter.VIVID_COOL -> FilterLook(Tone(contrast = 0.22f, brightness = 0.05f, warmth = -0.4f), 1.3f)
        PhotoFilter.DRAMATIC -> FilterLook(Tone(contrast = 0.55f, highlights = -0.5f, exposure = -0.08f), 0.8f)
        PhotoFilter.DRAMATIC_WARM -> FilterLook(Tone(contrast = 0.55f, highlights = -0.5f, exposure = -0.08f, warmth = 0.45f), 0.85f)
        PhotoFilter.DRAMATIC_COOL -> FilterLook(Tone(contrast = 0.55f, highlights = -0.5f, exposure = -0.08f, warmth = -0.45f), 0.85f)
        PhotoFilter.MONO -> FilterLook(Tone(contrast = 0.1f), 0f, mono = true)
        PhotoFilter.SILVERTONE -> FilterLook(Tone(contrast = 0.35f, brightness = 0.12f, shadows = 0.2f), 0f, mono = true)
        PhotoFilter.NOIR -> FilterLook(Tone(contrast = 0.8f, blackPoint = 0.45f, brightness = -0.12f), 0f, mono = true)
    }

    private class Program(edits: PhotoEdits) {
        private val brilliance = edits[Adjustment.BRILLIANCE]
        private val tone = Tone(
            exposure = edits[Adjustment.EXPOSURE],
            brightness = edits[Adjustment.BRIGHTNESS] + 0.1f * brilliance,
            contrast = (edits[Adjustment.CONTRAST] + 0.15f * brilliance).coerceIn(-1f, 1f),
            highlights = (edits[Adjustment.HIGHLIGHTS] - 0.5f * brilliance).coerceIn(-1f, 1f),
            shadows = (edits[Adjustment.SHADOWS] + 0.6f * brilliance).coerceIn(-1f, 1f),
            blackPoint = edits[Adjustment.BLACK_POINT],
            warmth = edits[Adjustment.WARMTH],
            tint = edits[Adjustment.TINT],
        )
        private val identityTone = edits.values.withIndex().all { (i, v) ->
            v == 0f || i == Adjustment.SATURATION.ordinal || i == Adjustment.VIBRANCE.ordinal ||
                i == Adjustment.SHARPNESS.ordinal || i == Adjustment.VIGNETTE.ordinal
        }
        private val lut = tone.luts()
        private val saturation = edits[Adjustment.SATURATION].let { 1f + if (it > 0f) it * 0.8f else it }
        private val vibrance = edits[Adjustment.VIBRANCE]
        private val look = look(edits.filter)
        private val lookLut = look?.tone?.luts()
        private val intensity = (edits.filterIntensity * 256).roundToInt()
        private val vignette = edits[Adjustment.VIGNETTE]
        val sharpness = edits[Adjustment.SHARPNESS]

        /** Vignette gain (scaled by 256) by squared normalized radius (scaled by 255). */
        private val vignetteGain = IntArray(256) { i ->
            val r = i / 255f
            val falloff = ((r - 0.12f) / 0.88f).coerceIn(0f, 1f).pow(1.4f)
            ((1f - vignette * 0.75f * falloff) * 256).roundToInt().coerceAtLeast(0)
        }

        val isIdentity = identityTone && saturation == 1f && vibrance == 0f && look == null &&
            vignette == 0f && sharpness == 0f

        fun colorRow(pixels: IntArray, offset: Int, width: Int, y: Int, height: Int) {
            val lr = lut[0]
            val lg = lut[1]
            val lb = lut[2]
            val dy = (y - height / 2f) / (height / 2f)
            val dy2 = dy * dy
            for (x in 0 until width) {
                val index = offset + x
                val c = pixels[index]
                var r = (c shr 16) and 0xFF
                var g = (c shr 8) and 0xFF
                var b = c and 0xFF
                if (!identityTone) {
                    r = lr[r]
                    g = lg[g]
                    b = lb[b]
                }
                if (saturation != 1f || vibrance != 0f) {
                    var k = saturation
                    if (vibrance != 0f) {
                        // Vibrance favors the muted colors, and spares skin a little.
                        val spread = (max(r, max(g, b)) - min(r, min(g, b))) / 255f
                        val skin = if (r > g && g > b) 0.6f else 1f
                        k *= 1f + vibrance * (1f - spread) * skin * if (vibrance > 0f) 1f else 0.9f
                    }
                    val l = luma(r, g, b)
                    r = clamp(l + ((r - l) * k).toInt())
                    g = clamp(l + ((g - l) * k).toInt())
                    b = clamp(l + ((b - l) * k).toInt())
                }
                if (look != null && lookLut != null) {
                    var fr: Int
                    var fg: Int
                    var fb: Int
                    if (look.mono) {
                        val l = luma(r, g, b)
                        fr = lookLut[0][l]
                        fg = lookLut[1][l]
                        fb = lookLut[2][l]
                    } else {
                        fr = lookLut[0][r]
                        fg = lookLut[1][g]
                        fb = lookLut[2][b]
                        val l = luma(fr, fg, fb)
                        fr = clamp(l + ((fr - l) * look.saturation).toInt())
                        fg = clamp(l + ((fg - l) * look.saturation).toInt())
                        fb = clamp(l + ((fb - l) * look.saturation).toInt())
                    }
                    r += ((fr - r) * intensity) shr 8
                    g += ((fg - g) * intensity) shr 8
                    b += ((fb - b) * intensity) shr 8
                }
                if (vignette != 0f) {
                    val dx = (x - width / 2f) / (width / 2f)
                    val radius = ((dx * dx + dy2) * 0.5f * 255f).toInt().coerceAtMost(255)
                    val gain = vignetteGain[radius]
                    r = clamp((r * gain) shr 8)
                    g = clamp((g * gain) shr 8)
                    b = clamp((b * gain) shr 8)
                }
                pixels[index] = (c and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }

        /** Unsharp mask on the 4 neighbors; negative amounts soften. */
        fun sharpen(pixels: IntArray, width: Int, rows: Int): IntArray {
            val amount = if (sharpness > 0f) sharpness * 1.6f else sharpness * 0.8f
            val k = (amount * 256).roundToInt()
            val out = pixels.copyOf()
            for (y in 1 until rows - 1) {
                val row = y * width
                for (x in 1 until width - 1) {
                    val i = row + x
                    val c = pixels[i]
                    val n = pixels[i - width]
                    val s = pixels[i + width]
                    val e = pixels[i + 1]
                    val wv = pixels[i - 1]
                    var result = c and 0xFF000000.toInt()
                    var shift = 16
                    while (shift >= 0) {
                        val v = (c shr shift) and 0xFF
                        val avg = (((n shr shift) and 0xFF) + ((s shr shift) and 0xFF) + ((e shr shift) and 0xFF) + ((wv shr shift) and 0xFF)) shr 2
                        result = result or (clamp(v + (((v - avg) * k) shr 8)) shl shift)
                        shift -= 8
                    }
                    out[i] = result
                }
            }
            return out
        }
    }

    private fun luma(r: Int, g: Int, b: Int) = (r * 77 + g * 150 + b * 29) shr 8

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    private fun linearToSrgb(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }

    private val SRGB_TO_LINEAR = FloatArray(256) { i ->
        val c = i / 255f
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }

    /** Exposure range at ±1, in stops. */
    private const val EXPOSURE_STOPS = 1.6f
}
