package dev.mycam.media

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import dev.mycam.R

/**
 * User actions on library items. On Android 11+ deletions go to the system trash; the system
 * asks for confirmation when needed.
 */
object MediaActions {

    const val REQUEST_DELETE = 4101
    const val REQUEST_FAVORITE = 4102

    fun share(activity: Activity, item: MediaItem) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = item.mimeType ?: if (item.isVideo) "video/*" else "image/*"
            putExtra(Intent.EXTRA_STREAM, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share)))
    }

    /** Shares several items at once (the library's selection). */
    fun share(activity: Activity, items: List<MediaItem>) {
        if (items.size == 1) return share(activity, items[0])
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = when {
                items.all { it.isVideo } -> "video/*"
                items.none { it.isVideo } -> "image/*"
                else -> "*/*"
            }
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.share)))
    }

    /**
     * Deletes several items with a single system confirmation where the system asks for one.
     * @return true when they were removed immediately; false when a confirmation was launched
     * (the result arrives in onActivityResult with [REQUEST_DELETE]).
     */
    fun delete(activity: Activity, items: List<MediaItem>): Boolean {
        if (items.size == 1) return delete(activity, items[0])
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = MediaStore.createTrashRequest(activity.contentResolver, items.map { it.uri }, true)
            return launch(activity, request.intentSender, REQUEST_DELETE)
        }
        // Android 10 and older: one by one; items owned by other apps may ask each in turn.
        return items.all { delete(activity, it) }
    }

    /**
     * @return true when the item was removed immediately; false when a system confirmation
     * was launched (the result arrives in onActivityResult with [REQUEST_DELETE]).
     */
    fun delete(activity: Activity, item: MediaItem): Boolean {
        val resolver = activity.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val request = MediaStore.createTrashRequest(resolver, listOf(item.uri), true)
            return launch(activity, request.intentSender, REQUEST_DELETE)
        }
        return try {
            resolver.delete(item.uri, null, null) > 0
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) {
                launch(activity, e.userAction.actionIntent.intentSender, REQUEST_DELETE)
            } else {
                Log.e(TAG, "Delete refused", e)
                false
            }
        }
    }

    /** @return true when the favorite flag was written directly. */
    fun setFavorite(activity: Activity, item: MediaItem, favorite: Boolean): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val resolver = activity.contentResolver
        return try {
            // Works without prompt for files created by MyCam.
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_FAVORITE, if (favorite) 1 else 0) }
            resolver.update(item.uri, values, null, null) > 0
        } catch (e: SecurityException) {
            val request = MediaStore.createFavoriteRequest(resolver, listOf(item.uri), favorite)
            launch(activity, request.intentSender, REQUEST_FAVORITE)
        }
    }

    private fun launch(activity: Activity, sender: IntentSender, requestCode: Int): Boolean {
        try {
            @Suppress("DEPRECATION")
            activity.startIntentSenderForResult(sender, requestCode, null, 0, 0, 0)
        } catch (e: IntentSender.SendIntentException) {
            Log.e(TAG, "Unable to launch system request", e)
        }
        return false
    }

    private const val TAG = "MediaActions"
}
