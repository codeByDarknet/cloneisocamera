package dev.mycam.media

import android.content.ContentUris
import android.content.Context
import android.annotation.SuppressLint
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/** Read access to the device photo library through [MediaStore]. */
@SuppressLint("InlinedApi") // The columns exist in the files table long before their constants were public.
class MediaRepository(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    /**
     * Items matching [filter], and of the album [albumId] when given, oldest first by capture
     * date (the gallery shows the newest at the bottom).
     */
    fun query(filter: MediaFilter, albumId: Long? = null): List<MediaItem> {
        val selection = StringBuilder()
        val args = mutableListOf<String>()

        when (filter) {
            MediaFilter.VIDEOS, MediaFilter.SCREEN_RECORDINGS ->
                selection.append("${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}")
            MediaFilter.PANORAMAS, MediaFilter.SCREENSHOTS ->
                selection.append("${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}")
            else -> selection.append(
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (" +
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}," +
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO})"
            )
        }
        if (filter == MediaFilter.MYCAM) {
            selection.append(" AND ${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME}=?")
            args += MediaSaver.ALBUM
        }
        if (filter == MediaFilter.FAVORITES) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
            selection.append(" AND ${MediaStore.MediaColumns.IS_FAVORITE}=1")
        }
        if (albumId != null) {
            selection.append(" AND ${MediaStore.MediaColumns.BUCKET_ID}=?")
            args += albumId.toString()
        }

        val projection = buildList {
            add(MediaStore.Files.FileColumns._ID)
            add(MediaStore.Files.FileColumns.MEDIA_TYPE)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.MediaColumns.DATE_TAKEN)
            add(MediaStore.MediaColumns.DURATION)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.SIZE)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.MIME_TYPE)
            @Suppress("DEPRECATION")
            add(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.MediaColumns.IS_FAVORITE)
        }.toTypedArray()

        return try {
            resolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection.toString(),
                args.toTypedArray(),
                "${MediaStore.MediaColumns.DATE_ADDED} ASC",
            )?.use(::readAll).orEmpty()
                .filter(filter::matches)
                // Stable: items taken at the same time keep the order they were added in.
                .sortedBy { it.date }
        } catch (e: Exception) {
            // Typically a missing storage permission.
            Log.w(TAG, "Library query failed", e)
            emptyList()
        }
    }

    /**
     * The albums of [items]: MyCam first, then the largest. The cover is the latest item.
     */
    fun albums(items: List<MediaItem>): List<Album> =
        items.groupBy { it.albumId }
            .map { (id, list) -> Album(id, list.last().album ?: "", list.size, list.last()) }
            .sortedWith(compareBy<Album> { it.name != MediaSaver.ALBUM }.thenByDescending { it.count })

    /** Most recent item taken with MyCam, for the thumbnail next to the shutter. */
    fun latestCapture(): MediaItem? = query(MediaFilter.MYCAM).lastOrNull()

    private fun readAll(c: Cursor): List<MediaItem> {
        val id = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
        val type = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
        val added = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
        val taken = c.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
        val duration = c.getColumnIndex(MediaStore.MediaColumns.DURATION)
        val width = c.getColumnIndex(MediaStore.MediaColumns.WIDTH)
        val height = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT)
        val size = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
        val name = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
        val bucket = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        val bucketId = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
        val mime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
        @Suppress("DEPRECATION")
        val path = c.getColumnIndex(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA,
        )
        val favorite = c.getColumnIndex("is_favorite")

        val items = ArrayList<MediaItem>(c.count)
        while (c.moveToNext()) {
            val mediaId = c.getLong(id)
            val isVideo = c.getInt(type) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val takenMs = if (taken >= 0 && !c.isNull(taken)) c.getLong(taken) else 0L
            items += MediaItem(
                id = mediaId,
                uri = ContentUris.withAppendedId(base, mediaId),
                isVideo = isVideo,
                date = if (takenMs > 0) takenMs else c.getLong(added) * 1000,
                durationMs = c.longOrZero(duration),
                width = c.longOrZero(width).toInt(),
                height = c.longOrZero(height).toInt(),
                sizeBytes = c.longOrZero(size),
                name = if (name >= 0) c.getString(name).orEmpty() else "",
                album = if (bucket >= 0) c.getString(bucket) else null,
                mimeType = if (mime >= 0) c.getString(mime) else null,
                isFavorite = c.longOrZero(favorite) == 1L,
                albumId = c.longOrZero(bucketId),
                folder = if (path >= 0) c.getString(path).orEmpty() else "",
            )
        }
        return items
    }

    private fun Cursor.longOrZero(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    private companion object {
        const val TAG = "MediaRepository"
    }
}
