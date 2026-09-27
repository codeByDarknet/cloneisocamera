package dev.mycam.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Writes photos and videos into the shared "DCIM/MyCam" album. */
class MediaSaver(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    /** A video entry being written; call [finishVideo] when recording ends. */
    class PendingVideo(val uri: Uri, val descriptor: ParcelFileDescriptor)

    /**
     * Saves a JPEG. When [square] is true the center square is cut out of the 4:3 capture
     * (the EXIF orientation is preserved, so the result displays upright everywhere).
     * [dateTaken] places an edited copy next to its original in the library.
     */
    fun savePhoto(jpeg: ByteArray, square: Boolean, dateTaken: Long = System.currentTimeMillis()): Uri? {
        val data = if (square) cropToSquare(jpeg) ?: jpeg else jpeg
        val name = "IMG_${timestamp()}.jpg"
        val values = baseValues(name, "image/jpeg", Environment.DIRECTORY_DCIM, dateTaken)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return insertAndWrite(collection, values) { it.write(data) }
    }

    /**
     * JPEG of a processed image (night merge, portrait, panorama) carrying the camera EXIF of
     * [source]. When [upright] the pixels are already rotated, so the orientation tag is reset.
     */
    fun encode(bitmap: Bitmap, source: ByteArray?, upright: Boolean): ByteArray {
        val jpeg = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            out.toByteArray()
        }
        if (source == null) return jpeg
        return try {
            copyExif(source, jpeg, bitmap.width, bitmap.height, resetOrientation = upright)
        } catch (e: Exception) {
            Log.w(TAG, "EXIF copy failed", e)
            jpeg
        }
    }

    fun createVideo(): PendingVideo? {
        val name = "VID_${timestamp()}.mp4"
        val values = baseValues(name, "video/mp4", Environment.DIRECTORY_DCIM)
        val uri = try {
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create video entry", e)
            null
        } ?: return null
        val pfd = try {
            resolver.openFileDescriptor(uri, "rw")
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open video file", e)
            null
        }
        if (pfd == null) {
            resolver.delete(uri, null, null)
            return null
        }
        return PendingVideo(uri, pfd)
    }

    /** Publishes the video, or deletes it when the recording failed. */
    fun finishVideo(video: PendingVideo, success: Boolean): Uri? {
        runCatching { video.descriptor.close() }
        if (!success) {
            runCatching { resolver.delete(video.uri, null, null) }
            return null
        }
        publish(video.uri)
        return video.uri
    }

    private fun baseValues(
        name: String,
        mime: String,
        directory: String,
        dateTaken: Long = System.currentTimeMillis(),
    ) = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, mime)
        val now = System.currentTimeMillis()
        put(MediaStore.MediaColumns.DATE_ADDED, now / 1000)
        put(MediaStore.MediaColumns.DATE_MODIFIED, now / 1000)
        put("datetaken", dateTaken)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$ALBUM")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(directory), ALBUM).apply { mkdirs() }
            @Suppress("DEPRECATION")
            put(MediaStore.MediaColumns.DATA, File(dir, name).absolutePath)
        }
    }

    private fun insertAndWrite(collection: Uri, values: ContentValues, write: (OutputStream) -> Unit): Uri? {
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.e(TAG, "Insert failed", e)
            null
        } ?: return null
        return try {
            resolver.openOutputStream(uri)?.use(write) ?: error("No output stream")
            publish(uri)
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Write failed", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, values, null, null)
        }
    }

    /** Center square of a 4:3 JPEG, EXIF preserved; null when the image cannot be decoded. */
    fun cropToSquare(jpeg: ByteArray): ByteArray? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val side = minOf(bounds.outWidth, bounds.outHeight)
        val left = (bounds.outWidth - side) / 2
        val top = (bounds.outHeight - side) / 2

        val decoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
        }
        val square = decoder.decodeRegion(Rect(left, top, left + side, top + side), null)
        decoder.recycle()

        val encoded = ByteArrayOutputStream().use { out ->
            square.compress(Bitmap.CompressFormat.JPEG, 95, out)
            square.recycle()
            out.toByteArray()
        }
        copyExif(from = jpeg, to = encoded, width = side, height = side)
    } catch (e: Exception) {
        Log.w(TAG, "Square crop failed, keeping full frame", e)
        null
    }

    /** Re-applies the original EXIF block (orientation, camera settings…) to the cropped JPEG. */
    private fun copyExif(from: ByteArray, to: ByteArray, width: Int, height: Int, resetOrientation: Boolean = false): ByteArray {
        val source = ExifInterface(ByteArrayInputStream(from))
        val temp = File.createTempFile("crop", ".jpg", appContext.cacheDir)
        try {
            temp.writeBytes(to)
            val target = ExifInterface(temp.absolutePath)
            EXIF_TAGS.forEach { tag -> source.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
            // Without a time zone, MediaStore guesses one from the file time (to 15 minutes), which
            // shifts processed or edited photos saved minutes after the shot.
            OFFSET_TAGS.forEach { tag ->
                target.setAttribute(tag, source.getAttribute(tag) ?: localOffset())
            }
            target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            if (resetOrientation) target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            target.saveAttributes()
            return temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    private fun localOffset(): String {
        val minutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (minutes < 0) '-' else '+'
        return String.format(Locale.US, "%c%02d:%02d", sign, abs(minutes) / 60, abs(minutes) % 60)
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    companion object {
        const val ALBUM = "MyCam"
        private const val TAG = "MediaSaver"

        private val OFFSET_TAGS = arrayOf("OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized")

        private val EXIF_TAGS = arrayOf(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
        )
    }
}
