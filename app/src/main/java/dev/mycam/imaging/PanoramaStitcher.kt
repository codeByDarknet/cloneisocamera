package dev.mycam.imaging

import android.graphics.Bitmap
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.mycam.camera.GyroTracker
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Builds a panorama from preview frames while the phone is turned sideways in portrait.
 *
 * Each frame is positioned from the gyroscope, then the position is refined by matching it
 * against an earlier frame at quarter resolution. The panorama is made of the central vertical
 * strip of successive frames (where lens distortion is lowest), joined with a short feathered
 * seam. The direction (left or right) is set by the first move; until then the first frame is
 * kept aside so its outer half can open the panorama. The outer half of a recent frame is kept
 * beyond the strips to close it, and the result is cropped to the rows every frame covered.
 *
 * [onFrame] runs on the camera analysis thread, [finish] on any worker thread; [listener] is
 * called on the main thread.
 *
 * @param pixelsPerRadian how far the image moves, in portrait frame pixels, per radian of turn.
 * @param sensorOrientation 90 or 270: how the sensor buffers map to the portrait frame.
 * @param timestampsMatchGyro whether camera timestamps share the gyroscope's clock.
 */
class PanoramaStitcher(
    private val gyro: GyroTracker,
    private val pixelsPerRadian: Float,
    private val sensorOrientation: Int,
    private val timestampsMatchGyro: Boolean,
    private val listener: Listener,
) {

    class Progress(
        /** 0‥1 of the maximum width. */
        val progress: Float,
        /** -1 (left), 0 (not decided yet) or 1 (right). */
        val direction: Int,
        /** Vertical drift from the first frame, -1‥1 of the tolerated drift (positive: too low). */
        val drift: Float,
        val tooFast: Boolean,
        /** Small copy of the panorama so far; null before the direction is known. */
        val preview: Bitmap?,
    )

    interface Listener {
        fun onProgress(progress: Progress)

        /** The panorama cannot grow any further (maximum width or too much drift). */
        fun onLimitReached()
    }

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    // Portrait frame geometry, known from the first frame.
    private var frameWidth = 0
    private var frameHeight = 0

    // --- Canvas ---------------------------------------------------------------------------------
    private var canvas = IntArray(0)
    private var canvasWidth = 0
    /** Canvas column of panorama x = 0 (the first frame's left edge). */
    private var originX = 0
    /** Columns made of central strips are [writtenStart, writtenEnd). */
    private var writtenStart = 0
    private var writtenEnd = 0
    /** Written columns including the outer half of a recent frame, [extentStart, extentEnd). */
    private var extentStart = 0
    private var extentEnd = 0
    private var frameCount = 0
    /** Rows covered by every written frame, [coveredTop, coveredBottom). */
    private var coveredTop = 0
    private var coveredBottom = 0
    private var direction = 0
    private var first: IntArray? = null
    private var finished = false

    // --- Tracking -------------------------------------------------------------------------------
    private var reference: Tracked? = null
    private var last: Tracked? = null
    private var lastTimeMs = 0L
    private var speed = 0f

    // --- Preview --------------------------------------------------------------------------------
    private var preview = IntArray(0)
    private var previewWidth = 0
    private var previewHeight = 0
    private var lastPreviewMs = 0L

    // --- Reused buffers -------------------------------------------------------------------------
    private var column = IntArray(0)
    private var yRow = ByteArray(0)
    private var uvRow = ByteArray(0)
    private var vRow = ByteArray(0)

    /** A frame's quarter-resolution luma and its position in the panorama (frame pixels). */
    private class Tracked(val luma: IntArray, val x: Float, val y: Float, val yaw: Float, val pitch: Float)

    fun onFrame(image: Image) {
        synchronized(lock) {
            if (finished) return
            try {
                process(image)
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory", e)
                finished = true
                main.post { listener.onLimitReached() }
            }
        }
    }

    private fun process(image: Image) {
        val portraitWidth = image.height
        val portraitHeight = image.width
        if (frameWidth == 0) setUp(portraitWidth, portraitHeight)
        if (portraitWidth != frameWidth || portraitHeight != frameHeight) return

        val angles = gyro.anglesAt(if (timestampsMatchGyro) image.timestamp else null)
        val luma = quarterLuma(image)
        val tracked = track(luma, angles[0], angles[1])
        updateSpeed(tracked)
        last = tracked

        if (first == null && direction == 0) {
            // Very first frame: keep it until the direction of the panorama is known.
            first = IntArray(frameWidth * frameHeight).also { pixels ->
                for (px in 0 until frameWidth) {
                    readColumn(image, px)
                    for (py in 0 until frameHeight) pixels[py * frameWidth + px] = column[py]
                }
            }
            publish()
            return
        }
        if (direction == 0) {
            if (abs(tracked.x) < pixelsPerRadian * START_ANGLE) {
                publish()
                return
            }
            startDirection(sign(tracked.x).toInt())
        }

        val drift = tracked.y / (frameHeight * MAX_DRIFT)
        if (abs(drift) > 1f) {
            stop()
            return
        }

        // The central strip, up to this frame's middle.
        val left = (originX + tracked.x).roundToInt()
        val center = left + frameWidth / 2
        val top = tracked.y.roundToInt()
        if (direction > 0 && center > writtenEnd) {
            val end = minOf(center, canvasWidth)
            writeColumns(left, top, writtenEnd, end, feather = true) { readColumn(image, it) }
            writtenEnd = end
            extentEnd = maxOf(extentEnd, end)
        } else if (direction < 0 && center < writtenStart) {
            val start = maxOf(center, 0)
            writeColumns(left, top, start, writtenStart, feather = true) { readColumn(image, it) }
            writtenStart = start
            extentStart = minOf(extentStart, start)
        }
        if (++frameCount % TAIL_INTERVAL == 0) writeTail(image, left, top)
        if (writtenEnd - writtenStart >= canvasWidth - frameWidth / 2) {
            writeTail(image, left, top)
            stop()
            return
        }
        publish()
    }

    /**
     * Writes this frame's outer half beyond the strips, where the next strips will overwrite it:
     * whenever the panorama stops, it ends on real content rather than on a frame's middle.
     */
    private fun writeTail(image: Image, left: Int, top: Int) {
        if (direction > 0) {
            val end = minOf(left + frameWidth, canvasWidth)
            if (end <= writtenEnd) return
            writeColumns(left, top, writtenEnd, end, feather = false) { readColumn(image, it) }
            extentStart = writtenStart
            extentEnd = end
        } else {
            val start = maxOf(left, 0)
            if (start >= writtenStart) return
            writeColumns(left, top, start, writtenStart, feather = false) { readColumn(image, it) }
            extentStart = start
            extentEnd = writtenEnd
        }
    }

    private fun setUp(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
        canvasWidth = MAX_WIDTH.coerceAtLeast(width * 2)
        canvas = IntArray(canvasWidth * height)
        coveredTop = 0
        coveredBottom = height
        previewWidth = canvasWidth / PREVIEW_SCALE
        previewHeight = height / PREVIEW_SCALE
        preview = IntArray(previewWidth * previewHeight)
        column = IntArray(height)
    }

    private fun startDirection(dir: Int) {
        direction = dir
        // Right: the first frame's left edge opens the canvas. Left: its right edge closes it.
        originX = if (dir > 0) 0 else canvasWidth - frameWidth
        val pixels = first ?: return
        first = null
        val half = frameWidth / 2
        val (from, to) = if (dir > 0) originX to originX + half else originX + half to originX + frameWidth
        writeColumns(originX, 0, from, to, feather = false) { px ->
            for (py in 0 until frameHeight) column[py] = pixels[py * frameWidth + px]
        }
        writtenStart = from
        writtenEnd = to
        extentStart = from
        extentEnd = to
    }

    /**
     * Copies canvas columns [from, to) from a frame whose left edge is canvas column [left] and
     * top edge canvas row [top]. [load] fills [column] with the frame's portrait column px.
     * With [feather], the columns just before the new ones (on the already written side) are
     * cross-faded instead of left as a hard seam.
     */
    private inline fun writeColumns(left: Int, top: Int, from: Int, to: Int, feather: Boolean, load: (Int) -> Unit) {
        val blendFrom: Int
        val blendTo: Int
        if (!feather) {
            blendFrom = from
            blendTo = to
        } else if (direction > 0) {
            blendFrom = maxOf(from - FEATHER, writtenStart)
            blendTo = to
        } else {
            blendFrom = from
            blendTo = minOf(to + FEATHER, writtenEnd)
        }
        val rowFrom = maxOf(0, top)
        val rowTo = minOf(frameHeight, top + frameHeight)
        for (c in blendFrom until blendTo) {
            val px = c - left
            if (px !in 0 until frameWidth || c !in 0 until canvasWidth) continue
            load(px)
            // Weight of the new frame, 0..256: ramps up across the seam.
            val weight = when {
                c in from until to -> 256
                direction > 0 -> 256 * (c - blendFrom + 1) / (FEATHER + 1)
                else -> 256 * (blendTo - c) / (FEATHER + 1)
            }
            for (r in rowFrom until rowTo) {
                val src = column[r - top]
                val i = r * canvasWidth + c
                canvas[i] = if (weight >= 256) src else mix(canvas[i], src, weight)
            }
            if (c % PREVIEW_SCALE == 0 && c / PREVIEW_SCALE < previewWidth) {
                val pc = c / PREVIEW_SCALE
                for (pr in 0 until previewHeight) {
                    preview[pr * previewWidth + pc] = canvas[(pr * PREVIEW_SCALE) * canvasWidth + c]
                }
            }
        }
        coveredTop = maxOf(coveredTop, top)
        coveredBottom = minOf(coveredBottom, top + frameHeight)
    }

    // --- Tracking -------------------------------------------------------------------------------

    private fun track(luma: IntArray, yaw: Float, pitch: Float): Tracked {
        val ref = reference ?: return Tracked(luma, 0f, 0f, yaw, pitch).also { reference = it }
        // Gyroscope prediction of this frame's shift from the reference, in quarter pixels.
        val predictX = (yaw - ref.yaw) * pixelsPerRadian / 4f
        val predictY = -(pitch - ref.pitch) * pixelsPerRadian / 4f
        val (sx, sy) = match(ref.luma, luma, predictX.roundToInt(), predictY.roundToInt())
        val tracked = Tracked(luma, ref.x + sx * 4f, ref.y + sy * 4f, yaw, pitch)
        // A fresh reference once the overlap shrinks, so matching keeps working; not more often,
        // since every change of reference adds a little error.
        if (abs(sx) >= quarterWidth / REFERENCE_SPAN || abs(sy) >= quarterHeight / REFERENCE_SPAN) reference = tracked
        return tracked
    }

    private val quarterWidth get() = frameWidth / 4
    private val quarterHeight get() = frameHeight / 4

    /**
     * Shift (sx, sy) such that frame(x, y) ~= ref(x + sx, y + sy), searched around the
     * prediction. Falls back to the prediction on scenes without enough texture to tell.
     */
    private fun match(ref: IntArray, frame: IntArray, predictX: Int, predictY: Int): IntArray {
        val w = quarterWidth
        val h = quarterHeight
        var best = Float.MAX_VALUE
        var bestX = predictX
        var bestY = predictY
        var atPrediction = Float.MAX_VALUE
        for (dy in predictY - SEARCH..predictY + SEARCH) {
            for (dx in predictX - SEARCH..predictX + SEARCH) {
                val sad = sad(ref, frame, w, h, dx, dy)
                if (dx == predictX && dy == predictY) atPrediction = sad
                if (sad < best) {
                    best = sad
                    bestX = dx
                    bestY = dy
                }
            }
        }
        return if (atPrediction - best < atPrediction * MIN_MATCH_GAIN) intArrayOf(predictX, predictY)
        else intArrayOf(bestX, bestY)
    }

    /** Mean absolute difference over the overlap, every other pixel. */
    private fun sad(ref: IntArray, frame: IntArray, w: Int, h: Int, dx: Int, dy: Int): Float {
        val x0 = maxOf(0, -dx)
        val x1 = minOf(w, w - dx)
        val y0 = maxOf(0, -dy)
        val y1 = minOf(h, h - dy)
        if (x1 - x0 < w / 3 || y1 - y0 < h / 3) return Float.MAX_VALUE
        var total = 0L
        var count = 0
        var y = y0
        while (y < y1) {
            val rowFrame = y * w
            val rowRef = (y + dy) * w + dx
            var x = x0
            while (x < x1) {
                total += abs(frame[rowFrame + x] - ref[rowRef + x])
                count++
                x += 2
            }
            y += 2
        }
        return if (count == 0) Float.MAX_VALUE else total.toFloat() / count
    }

    private fun updateSpeed(tracked: Tracked) {
        val now = SystemClock.uptimeMillis()
        val previous = last
        if (previous != null && now > lastTimeMs) {
            val instant = abs(tracked.x - previous.x) * 1000f / (now - lastTimeMs)
            speed = 0.7f * speed + 0.3f * instant
        }
        lastTimeMs = now
    }

    // --- Frame access ---------------------------------------------------------------------------

    /** Quarter-resolution luma of the portrait frame. */
    private fun quarterLuma(image: Image): IntArray {
        val w = frameWidth / 4
        val h = frameHeight / 4
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val bufferWidth = image.width
        val bufferHeight = image.height
        val out = IntArray(w * h)
        for (qx in 0 until w) {
            val by = bufferRowOf(qx * 4, bufferHeight)
            for (qy in 0 until h) {
                val bx = bufferColumnOf(qy * 4, bufferWidth)
                out[qy * w + qx] = buffer.get(by * rowStride + bx * pixelStride).toInt() and 0xFF
            }
        }
        return out
    }

    // Portrait (px, py) <-> buffer (bx, by). Orientation 90: bx = py, by = H - 1 - px.
    private fun bufferRowOf(px: Int, bufferHeight: Int) =
        if (sensorOrientation == 270) px else bufferHeight - 1 - px

    private fun bufferColumnOf(py: Int, bufferWidth: Int) =
        if (sensorOrientation == 270) bufferWidth - 1 - py else py

    /** Fills [column] with portrait column [px] as ARGB (one sensor buffer row). */
    private fun readColumn(image: Image, px: Int) {
        val bufferWidth = image.width
        val by = bufferRowOf(px, image.height)
        val (yPlane, uPlane, vPlane) = image.planes
        if (yRow.size < bufferWidth) yRow = ByteArray(yPlane.rowStride)
        val uvLength = (bufferWidth / 2 - 1) * uPlane.pixelStride + 1
        if (uvRow.size < uvLength) {
            uvRow = ByteArray(uvLength)
            vRow = ByteArray(uvLength)
        }
        readRow(yPlane, by, (bufferWidth - 1) * yPlane.pixelStride + 1, yRow)
        readRow(uPlane, by / 2, uvLength, uvRow)
        readRow(vPlane, by / 2, uvLength, vRow)
        val yStride = yPlane.pixelStride
        val uStride = uPlane.pixelStride
        val vStride = vPlane.pixelStride
        for (py in 0 until frameHeight) {
            val bx = bufferColumnOf(py, bufferWidth)
            val y = (yRow[bx * yStride].toInt() and 0xFF) - 16
            val u = (uvRow[(bx / 2) * uStride].toInt() and 0xFF) - 128
            val v = (vRow[(bx / 2) * vStride].toInt() and 0xFF) - 128
            // BT.601 limited range, fixed point.
            val c = 298 * y.coerceAtLeast(0)
            val r = (c + 409 * v + 128) shr 8
            val g = (c - 100 * u - 208 * v + 128) shr 8
            val b = (c + 516 * u + 128) shr 8
            column[py] = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
    }

    private fun readRow(plane: Image.Plane, row: Int, length: Int, out: ByteArray) {
        val buffer = plane.buffer.duplicate()
        val start = row * plane.rowStride
        buffer.position(start)
        buffer.get(out, 0, minOf(length, buffer.remaining()))
    }

    // --- Output ---------------------------------------------------------------------------------

    private fun publish() {
        val now = SystemClock.uptimeMillis()
        if (now - lastPreviewMs < PREVIEW_INTERVAL_MS) return
        lastPreviewMs = now
        val bitmap = if (direction == 0 || extentEnd <= extentStart) null else previewBitmap()
        val progress = Progress(
            progress = if (direction == 0) 0f else (writtenEnd - writtenStart).toFloat() / canvasWidth,
            direction = direction,
            drift = (last?.y ?: 0f) / (frameHeight * MAX_DRIFT),
            tooFast = speed > frameWidth * MAX_SPEED,
            preview = bitmap,
        )
        main.post { listener.onProgress(progress) }
    }

    private fun previewBitmap(): Bitmap {
        val from = extentStart / PREVIEW_SCALE
        val to = (extentEnd / PREVIEW_SCALE).coerceAtMost(previewWidth)
        val width = (to - from).coerceAtLeast(1)
        return Bitmap.createBitmap(preview, from, previewWidth, width, previewHeight, Bitmap.Config.ARGB_8888)
    }

    private fun stop() {
        if (finished) return
        finished = true
        main.post { listener.onLimitReached() }
    }

    /** Ends the panorama and returns the cropped result, or null when the phone barely moved. */
    fun finish(): Bitmap? = synchronized(lock) {
        finished = true
        try {
            if (direction == 0 || frameWidth == 0) return null
            val width = extentEnd - extentStart
            if (width < frameWidth * MIN_LENGTH) return null
            val top = coveredTop.coerceAtLeast(0)
            val bottom = coveredBottom.coerceAtMost(frameHeight)
            if (bottom - top < frameHeight / 2) return null
            Bitmap.createBitmap(canvas, top * canvasWidth + extentStart, canvasWidth, width, bottom - top, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            Log.e(TAG, "Panorama failed", e)
            null
        } finally {
            canvas = IntArray(0)
            first = null
        }
    }

    private companion object {
        const val TAG = "Panorama"
        /** Canvas width in frame pixels (about 5.5 portrait frames at 1080 px). */
        const val MAX_WIDTH = 6000
        /** Turn, in radians, that decides the direction (about 2°). */
        const val START_ANGLE = 0.035f
        /** Tolerated vertical drift, as a fraction of the frame height. */
        const val MAX_DRIFT = 0.12f
        /** Faster than this (frame widths per second) is shown as "too fast". */
        const val MAX_SPEED = 0.7f
        /** Shorter than this (frame widths) is not worth a panorama. */
        const val MIN_LENGTH = 1.2f
        const val FEATHER = 8
        /** Search window around the gyroscope prediction, in quarter pixels. */
        const val SEARCH = 6
        /** The reference frame is renewed after moving by 1/REFERENCE_SPAN of the frame. */
        const val REFERENCE_SPAN = 5
        /** How much better than the prediction a match must be to be trusted. */
        const val MIN_MATCH_GAIN = 0.03f
        /** Every how many frames a frame's outer half is written beyond the strips. */
        const val TAIL_INTERVAL = 6
        const val PREVIEW_SCALE = 8
        const val PREVIEW_INTERVAL_MS = 80L

        fun mix(old: Int, new: Int, weight: Int): Int {
            val inv = 256 - weight
            val r = ((old shr 16 and 0xFF) * inv + (new shr 16 and 0xFF) * weight) shr 8
            val g = ((old shr 8 and 0xFF) * inv + (new shr 8 and 0xFF) * weight) shr 8
            val b = ((old and 0xFF) * inv + (new and 0xFF) * weight) shr 8
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
