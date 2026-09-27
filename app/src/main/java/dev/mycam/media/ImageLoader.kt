package dev.mycam.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import dev.mycam.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Minimal image pipeline (no Glide/Coil): memory-cached thumbnails for the grid and
 * screen-sized decodes for the viewer. Bitmaps are always decoded upright.
 */
class ImageLoader private constructor(context: Context) {

    private val resolver = context.applicationContext.contentResolver
    private val main = Handler(Looper.getMainLooper())
    private val thumbExecutor: ExecutorService = Executors.newFixedThreadPool(3)
    private val fullExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    private val thumbnails = object : LruCache<Long, Bitmap>(cacheSize(1f / 8)) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    private val fullImages = object : LruCache<Long, Bitmap>(cacheSize(1f / 5)) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }

    /** Loads a square-ish thumbnail into [view], cancelling the previous request bound to it. */
    fun bindThumbnail(view: ImageView, item: MediaItem, sizePx: Int) {
        (view.getTag(TAG_KEY) as? Future<*>)?.cancel(true)
        // Set first: a load already posted for the view's previous item must see it's stale.
        view.setTag(ID_KEY, item.id)
        val cached = cachedThumbnail(item, sizePx)
        if (cached != null) {
            view.setImageBitmap(cached)
            view.setTag(TAG_KEY, null)
            return
        }
        view.setImageDrawable(null)
        val future = thumbExecutor.submit {
            val bitmap = loadThumbnail(item, sizePx) ?: return@submit
            thumbnails.put(item.id, bitmap)
            main.post {
                if (view.getTag(ID_KEY) == item.id) view.setImageBitmap(bitmap)
            }
        }
        view.setTag(TAG_KEY, future)
    }

    fun thumbnail(item: MediaItem, sizePx: Int, callback: (Bitmap?) -> Unit) {
        cachedThumbnail(item, sizePx)?.let { return callback(it) }
        thumbExecutor.execute {
            val bitmap = loadThumbnail(item, sizePx)
            if (bitmap != null) thumbnails.put(item.id, bitmap)
            main.post { callback(bitmap) }
        }
    }

    /** Screen-sized decode for the viewer. The callback runs on the main thread. */
    fun full(item: MediaItem, maxSide: Int, callback: (Bitmap?) -> Unit): Future<*>? {
        fullImages.get(item.id)?.let {
            callback(it)
            return null
        }
        return fullExecutor.submit {
            val bitmap = decodeSampled(item.uri, maxSide)
            if (bitmap != null) fullImages.put(item.id, bitmap)
            if (!Thread.currentThread().isInterrupted) main.post { callback(bitmap) }
        }
    }

    /** A cached thumbnail, unless it is too small for [sizePx] (grid cells vs. month cards). */
    private fun cachedThumbnail(item: MediaItem, sizePx: Int): Bitmap? =
        thumbnails.get(item.id)?.takeIf { maxOf(it.width, it.height) * 4 >= sizePx * 3 }

    fun peekFull(item: MediaItem): Bitmap? = fullImages.get(item.id)

    fun evict(item: MediaItem) {
        thumbnails.remove(item.id)
        fullImages.remove(item.id)
    }

    private fun loadThumbnail(item: MediaItem, sizePx: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(item.uri, Size(sizePx, sizePx), CancellationSignal())
        } else if (item.isVideo) {
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(resolver, item.id, MediaStore.Video.Thumbnails.MINI_KIND, null)
        } else {
            decodeSampled(item.uri, sizePx * 2)?.let {
                ThumbnailUtils.extractThumbnail(it, sizePx, sizePx, ThumbnailUtils.OPTIONS_RECYCLE_INPUT)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Thumbnail failed for ${item.uri}: ${e.message}")
        null
    }

    /** Decodes [uri] upright with its longest side at most [maxSide], bypassing the caches. */
    fun decodeSampled(uri: Uri, maxSide: Int): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF orientation itself.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest
                    decoder.setTargetSize(
                        (info.size.width * scale).toInt().coerceAtLeast(1),
                        (info.size.height * scale).toInt().coerceAtLeast(1),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacy(uri, maxSide)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Decode failed for $uri: ${e.message}")
        null
    }

    private fun decodeLegacy(uri: Uri, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        val rotation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val degrees = when (rotation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    companion object {
        private const val TAG = "ImageLoader"
        private val TAG_KEY = R.id.tag_image_request
        private val ID_KEY = R.id.tag_media_id

        @Volatile
        private var instance: ImageLoader? = null

        fun get(context: Context): ImageLoader =
            instance ?: synchronized(this) {
                instance ?: ImageLoader(context).also { instance = it }
            }

        private fun cacheSize(fraction: Float): Int =
            (Runtime.getRuntime().maxMemory() * fraction).toInt()
    }
}
