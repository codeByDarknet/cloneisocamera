package dev.mycam.imaging

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Portrait mode without a depth sensor: the subject is cut out from the photo itself and the
 * background is blurred like a wide-open lens would.
 *
 * 1. Faces are found in the photo ([FaceDetector], part of the platform).
 * 2. A head-and-shoulders silhouette is drawn around each face: a rough prior.
 * 3. Color statistics of what is surely subject vs surely background decide the uncertain band
 *    between the two, so the cut follows the real outline of hair, shoulders and clothes.
 * 4. A guided filter snaps the mask to the image edges and gives it soft, natural borders.
 * 5. The background is blurred with the subject masked out (no halo) and highlights weighted
 *    up, so point lights bloom into bokeh discs.
 */
object PortraitRenderer {

    private const val TAG = "PortraitRenderer"
    /** Long side of the image the mask is computed on. */
    private const val MASK_SIDE = 640
    /** Long side of the image the background blur is computed on. */
    private const val BLUR_SIDE = 1000
    /** Blur radius as a fraction of the long side, roughly an f/2 look. */
    private const val BLUR_RADIUS = 0.012f
    private const val MAX_FACES = 4
    private const val MIN_CONFIDENCE = 0.4f
    private const val BINS = 16

    class Result(val bitmap: Bitmap)

    /** Upright portrait of [jpeg], or null when no face was found (or on any failure). */
    fun render(jpeg: ByteArray): Result? = try {
        renderInternal(jpeg)
    } catch (e: Throwable) {
        Log.e(TAG, "Portrait failed", e)
        null
    }

    private fun renderInternal(jpeg: ByteArray): Result? {
        val photo = Jpeg.decodeUpright(jpeg) ?: return null
        val width = photo.width
        val height = photo.height

        // Mask, at low resolution
        val maskScale = MASK_SIDE.toFloat() / max(width, height)
        // FaceDetector wants an RGB_565 bitmap of even width.
        val mw = ((width * maskScale).roundToInt() and 1.inv()).coerceAtLeast(2)
        val mh = (height * maskScale).roundToInt().coerceAtLeast(2)
        val small = Bitmap.createScaledBitmap(photo, mw, mh, true)
        val faces = findFaces(small)
        if (faces.isEmpty()) {
            small.recycle()
            photo.recycle()
            return null
        }
        val pixels = IntArray(mw * mh)
        small.getPixels(pixels, 0, mw, 0, 0, mw, mh)
        small.recycle()
        val mask = buildMask(pixels, mw, mh, faces)

        // Blurred background
        val blurScale = BLUR_SIDE.toFloat() / max(width, height)
        val bw = (width * blurScale).roundToInt().coerceAtLeast(1)
        val bh = (height * blurScale).roundToInt().coerceAtLeast(1)
        val blurred = blurBackground(photo, bw, bh, mask, mw, mh)

        // Composite at full resolution, row by row
        val row = IntArray(width)
        for (y in 0 until height) {
            photo.getPixels(row, 0, width, 0, y, width, 1)
            val my = (y + 0.5f) * mh / height - 0.5f
            val by = (y + 0.5f) * bh / height - 0.5f
            for (x in 0 until width) {
                val m = sample(mask, mw, mh, (x + 0.5f) * mw / width - 0.5f, my)
                if (m >= 0.995f) continue
                val bg = sampleColor(blurred, bw, bh, (x + 0.5f) * bw / width - 0.5f, by)
                val c = row[x]
                val inv = 1f - m
                val r = ((c shr 16 and 0xFF) * m + (bg shr 16 and 0xFF) * inv).roundToInt()
                val g = ((c shr 8 and 0xFF) * m + (bg shr 8 and 0xFF) * inv).roundToInt()
                val b = ((c and 0xFF) * m + (bg and 0xFF) * inv).roundToInt()
                row[x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            photo.setPixels(row, 0, width, 0, y, width, 1)
        }
        return Result(photo)
    }


    // --- Faces ----------------------------------------------------------------------------------

    private class Face(val x: Float, val y: Float, val eyes: Float)

    private fun findFaces(bitmap: Bitmap): List<Face> {
        val rgb565 = bitmap.copy(Bitmap.Config.RGB_565, false)
        val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val count = FaceDetector(rgb565.width, rgb565.height, MAX_FACES).findFaces(rgb565, found)
        rgb565.recycle()
        val point = PointF()
        return found.take(count).filterNotNull()
            .filter { it.confidence() >= MIN_CONFIDENCE }
            .map { face ->
                face.getMidPoint(point)
                Face(point.x, point.y, face.eyesDistance())
            }
    }

    // --- Mask -----------------------------------------------------------------------------------

    private fun buildMask(pixels: IntArray, w: Int, h: Int, faces: List<Face>): FloatArray {
        // 1. Head-and-shoulders silhouette, softened into a prior probability.
        val silhouette = FloatArray(w * h)
        for (face in faces) drawSilhouette(silhouette, w, h, face)
        val eyes = faces.maxOf { it.eyes }
        val prior = boxBlur(silhouette, w, h, (eyes * 0.6f).roundToInt().coerceAtLeast(2), passes = 2)

        // 2. Color models of sure subject / sure background.
        val fg = FloatArray(BINS * BINS * BINS)
        val bg = FloatArray(BINS * BINS * BINS)
        for (i in pixels.indices) {
            when {
                prior[i] > 0.97f -> fg[bin(pixels[i])] += 1f
                prior[i] < 0.03f -> bg[bin(pixels[i])] += 1f
            }
        }
        normalize(fg)
        normalize(bg)

        // 3. Posterior in the uncertain band; far background can never become subject.
        val posterior = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = prior[i]
            posterior[i] = when {
                p > 0.97f -> 1f
                p < 0.03f -> 0f
                else -> {
                    val b = bin(pixels[i])
                    val lf = p * fg[b]
                    val lb = (1f - p) * bg[b]
                    lf / (lf + lb)
                }
            }
        }

        // 4. Edge-aware smoothing, then a gentle contrast so edges are soft but not mushy.
        val guide = FloatArray(w * h) { i ->
            val c = pixels[i]
            ((c shr 16 and 0xFF) * 0.299f + (c shr 8 and 0xFF) * 0.587f + (c and 0xFF) * 0.114f) / 255f
        }
        val refined = guidedFilter(guide, posterior, w, h, (eyes * 0.12f).roundToInt().coerceAtLeast(3), 0.004f)
        for (i in refined.indices) refined[i] = smoothstep(0.3f, 0.7f, refined[i])
        return refined
    }

    /** Head ellipse, neck and widening shoulders below it, down to the bottom of the frame. */
    private fun drawSilhouette(mask: FloatArray, w: Int, h: Int, face: Face) {
        val e = face.eyes
        val headX = face.x
        // Generous upwards: hair can stand well above the forehead.
        val headY = face.y + 0.1f * e
        val rx = 1.35f * e
        val ry = 2.0f * e
        val neckTop = face.y + 1.5f * e
        val shoulders = face.y + 2.7f * e
        for (y in 0 until h) {
            val row = y * w
            val fy = y.toFloat()
            for (x in 0 until w) {
                val fx = x.toFloat()
                val dx = (fx - headX) / rx
                val dy = (fy - headY) / ry
                val inHead = dx * dx + dy * dy <= 1f
                val inNeck = fy in neckTop..shoulders && kotlin.math.abs(fx - headX) <= 0.75f * e
                val inBody = fy >= shoulders && run {
                    // Shoulders round off at the top, then the torso widens a little.
                    val t = (fy - shoulders) / e
                    val halfWidth = (0.9f + sqrt(t.coerceAtMost(1.2f)) * 2.1f + t * 0.1f) * e
                    kotlin.math.abs(fx - headX) <= halfWidth.coerceAtMost(3.4f * e)
                }
                if (inHead || inNeck || inBody) mask[row + x] = 1f
            }
        }
    }

    private fun bin(c: Int): Int {
        val r = (c shr 16 and 0xFF) * BINS shr 8
        val g = (c shr 8 and 0xFF) * BINS shr 8
        val b = (c and 0xFF) * BINS shr 8
        return (r * BINS + g) * BINS + b
    }

    /** Turns counts into a smoothed probability distribution (empty bins keep a small chance). */
    private fun normalize(hist: FloatArray) {
        val total = hist.sum() + hist.size * 0.5f
        for (i in hist.indices) hist[i] = (hist[i] + 0.5f) / total
    }

    /** He et al.'s guided filter: [input] smoothed while keeping the edges of [guide]. */
    private fun guidedFilter(guide: FloatArray, input: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val meanI = boxBlur(guide, w, h, r)
        val meanP = boxBlur(input, w, h, r)
        val corrIp = boxBlur(FloatArray(n) { guide[it] * input[it] }, w, h, r)
        val corrII = boxBlur(FloatArray(n) { guide[it] * guide[it] }, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = corrII[i] - meanI[i] * meanI[i]
            val covIp = corrIp[i] - meanI[i] * meanP[i]
            a[i] = covIp / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = boxBlur(a, w, h, r)
        val meanB = boxBlur(b, w, h, r)
        return FloatArray(n) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    // --- Background blur ------------------------------------------------------------------------

    /**
     * The photo at low resolution with the subject removed before blurring (normalized
     * convolution), so the subject never bleeds into the background around it.
     */
    private fun blurBackground(photo: Bitmap, bw: Int, bh: Int, mask: FloatArray, mw: Int, mh: Int): IntArray {
        val scaled = Bitmap.createScaledBitmap(photo, bw, bh, true)
        val pixels = IntArray(bw * bh)
        scaled.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        scaled.recycle()

        val n = bw * bh
        val r = FloatArray(n)
        val g = FloatArray(n)
        val b = FloatArray(n)
        val weight = FloatArray(n)
        for (y in 0 until bh) {
            for (x in 0 until bw) {
                val i = y * bw + x
                val m = sample(mask, mw, mh, (x + 0.5f) * mw / bw - 0.5f, (y + 0.5f) * mh / bh - 0.5f)
                val bgWeight = 1f - m
                val c = pixels[i]
                // Squaring favors highlights, which is how real bokeh looks.
                val cr = (c shr 16 and 0xFF) / 255f
                val cg = (c shr 8 and 0xFF) / 255f
                val cb = (c and 0xFF) / 255f
                r[i] = cr * cr * bgWeight
                g[i] = cg * cg * bgWeight
                b[i] = cb * cb * bgWeight
                weight[i] = bgWeight
            }
        }
        val radius = (max(bw, bh) * BLUR_RADIUS).roundToInt().coerceAtLeast(2)
        val br = boxBlur(r, bw, bh, radius, passes = 3)
        val bg = boxBlur(g, bw, bh, radius, passes = 3)
        val bb = boxBlur(b, bw, bh, radius, passes = 3)
        val bwt = boxBlur(weight, bw, bh, radius, passes = 3)
        for (i in 0 until n) {
            val wt = bwt[i]
            pixels[i] = if (wt < 1e-3f) {
                pixels[i]
            } else {
                // The running sums of the box blur drift slightly below zero in empty areas.
                val rr = (sqrt((br[i] / wt).coerceAtLeast(0f)) * 255f).roundToInt().coerceIn(0, 255)
                val gg = (sqrt((bg[i] / wt).coerceAtLeast(0f)) * 255f).roundToInt().coerceIn(0, 255)
                val bbb = (sqrt((bb[i] / wt).coerceAtLeast(0f)) * 255f).roundToInt().coerceIn(0, 255)
                (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bbb
            }
        }
        return pixels
    }

    // --- Small numeric helpers ------------------------------------------------------------------

    /** Separable box blur with clamped edges; several passes approach a gaussian. */
    fun boxBlur(src: FloatArray, w: Int, h: Int, radius: Int, passes: Int = 1): FloatArray {
        var a = src.copyOf()
        var b = FloatArray(src.size)
        repeat(passes) {
            blurLine(a, b, w, h, radius, horizontal = true)
            blurLine(b, a, w, h, radius, horizontal = false)
        }
        return a
    }

    private fun blurLine(src: FloatArray, dst: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val stride = if (horizontal) 1 else w
        val norm = 1f / (2 * r + 1)
        for (line in 0 until lines) {
            val start = if (horizontal) line * w else line
            var sum = 0f
            for (k in -r..r) sum += src[start + k.coerceIn(0, length - 1) * stride]
            for (i in 0 until length) {
                dst[start + i * stride] = sum * norm
                val add = (i + r + 1).coerceAtMost(length - 1)
                val remove = (i - r).coerceAtLeast(0)
                sum += src[start + add * stride] - src[start + remove * stride]
            }
        }
    }

    /** Bilinear sample of a float plane. */
    private fun sample(plane: FloatArray, w: Int, h: Int, x: Float, y: Float): Float {
        val cx = x.coerceIn(0f, (w - 1).toFloat())
        val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val tx = cx - x0
        val ty = cy - y0
        val top = plane[y0 * w + x0] * (1 - tx) + plane[y0 * w + x1] * tx
        val bottom = plane[y1 * w + x0] * (1 - tx) + plane[y1 * w + x1] * tx
        return top * (1 - ty) + bottom * ty
    }

    /** Bilinear sample of an ARGB plane. */
    private fun sampleColor(plane: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val cx = x.coerceIn(0f, (w - 1).toFloat())
        val cy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = cx.toInt()
        val y0 = cy.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val tx = cx - x0
        val ty = cy - y0
        val c00 = plane[y0 * w + x0]
        val c10 = plane[y0 * w + x1]
        val c01 = plane[y1 * w + x0]
        val c11 = plane[y1 * w + x1]
        fun channel(shift: Int): Int {
            val top = (c00 shr shift and 0xFF) * (1 - tx) + (c10 shr shift and 0xFF) * tx
            val bottom = (c01 shr shift and 0xFF) * (1 - tx) + (c11 shr shift and 0xFF) * tx
            return (top * (1 - ty) + bottom * ty).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
}
