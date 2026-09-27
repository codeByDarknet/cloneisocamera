package dev.mycam.media

import android.net.Uri

/** One photo or video of the device library. */
data class MediaItem(
    val id: Long,
    val uri: Uri,
    val isVideo: Boolean,
    /** Capture time in epoch milliseconds. */
    val date: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val name: String,
    val album: String?,
    val mimeType: String?,
    val isFavorite: Boolean,
    /** MediaStore bucket (the folder) the item belongs to: its album. */
    val albumId: Long = 0L,
    /** Folder path relative to the storage root, e.g. "DCIM/Screenshots/". */
    val folder: String = "",
)

/**
 * Ways to narrow the library: the gallery's filter menu and the media types of Collections.
 * New entries go at the end: the ordinal is passed between screens.
 */
enum class MediaFilter {
    ALL, MYCAM, VIDEOS, FAVORITES, PANORAMAS, SCREENSHOTS, SCREEN_RECORDINGS;

    fun matches(item: MediaItem): Boolean = when (this) {
        ALL -> true
        MYCAM -> item.album == MediaSaver.ALBUM
        VIDEOS -> item.isVideo
        FAVORITES -> item.isFavorite
        // Taken by a camera (under DCIM) and at least twice as wide as tall. Shape alone is not
        // enough: banners and landscape screenshots from messaging apps are just as wide.
        PANORAMAS -> !item.isVideo && item.folder.contains("DCIM", ignoreCase = true) &&
            !SCREENSHOTS.matches(item) && item.height > 0 && item.width >= 2 * item.height
        SCREENSHOTS -> !item.isVideo && item.folder.contains("screenshot", ignoreCase = true)
        SCREEN_RECORDINGS -> item.isVideo && (
            item.folder.contains("screen record", ignoreCase = true) ||
                item.folder.contains("screenrecord", ignoreCase = true)
            )
    }
}

/** A folder of the device library, shown as an album in Collections. */
data class Album(val id: Long, val name: String, val count: Int, val cover: MediaItem)
