package dev.mycam.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import dev.mycam.R
import dev.mycam.media.ImageLoader
import dev.mycam.media.MediaActions
import dev.mycam.media.MediaFilter
import dev.mycam.media.MediaItem
import dev.mycam.media.MediaRepository
import dev.mycam.ui.editor.EditorActivity
import dev.mycam.ui.gallery.GalleryActivity
import dev.mycam.ui.glass.GlassBackdrop
import dev.mycam.util.Background
import dev.mycam.util.Formatters
import dev.mycam.util.Haptics
import dev.mycam.util.dpi
import dev.mycam.util.goEdgeToEdge
import dev.mycam.util.onSystemBars
import dev.mycam.util.setVisible

/** Full-screen photo/video viewer with its toolbar (share, favorite, info, edit, delete). */
class ViewerActivity : Activity() {

    private lateinit var pager: MediaPager
    private lateinit var background: View
    private lateinit var chrome: View
    private lateinit var titleDate: TextView
    private lateinit var titleTime: TextView
    private lateinit var videoControls: View
    private lateinit var playPause: ImageView
    private lateinit var scrubber: SeekBar
    private lateinit var videoTime: TextView
    private lateinit var favoriteButton: ImageView
    private lateinit var editButton: View
    private lateinit var infoSheet: InfoSheet

    private val repository by lazy { MediaRepository(this) }
    private lateinit var filter: MediaFilter
    private var albumId: Long? = null
    private var chromeVisible = true
    private var scrubbing = false
    /** Item waiting for the system trash/favorite confirmation. */
    private var pendingItem: MediaItem? = null
    private var pendingFavorite = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goEdgeToEdge()
        setContentView(R.layout.activity_viewer)

        val root = findViewById<FrameLayout>(R.id.root)
        background = findViewById(R.id.background)
        chrome = findViewById(R.id.chrome)
        titleDate = findViewById(R.id.title_date)
        titleTime = findViewById(R.id.title_time)
        videoControls = findViewById(R.id.video_controls)
        playPause = findViewById(R.id.play_pause)
        scrubber = findViewById(R.id.scrubber)
        videoTime = findViewById(R.id.video_time)
        favoriteButton = findViewById(R.id.favorite_button)
        editButton = findViewById(R.id.edit_button)
        infoSheet = findViewById(R.id.info_sheet)

        pager = MediaPager(this)
        root.addView(pager, 1, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        GlassBackdrop.install(root, pager)

        filter = MediaFilter.entries[intent.getIntExtra(EXTRA_FILTER, 0)]
        albumId = intent.getLongExtra(EXTRA_ALBUM, NO_ALBUM).takeIf { it != NO_ALBUM }
        val startId = savedInstanceState?.getLong(EXTRA_ITEM_ID) ?: intent.getLongExtra(EXTRA_ITEM_ID, -1L)

        pager.onPageChanged = { onPageChanged() }
        pager.onPageTap = { toggleChrome() }
        pager.onPlaybackChanged = { updateVideoControls() }
        pager.onDismissProgress = { progress ->
            background.alpha = 1f - progress
            chrome.alpha = if (chromeVisible) 1f - progress * 3f else 0f
        }
        pager.onDismiss = { dismiss() }

        setUpChrome(root)
        load(startId)
    }

    override fun onPause() {
        pager.pauseAll()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        pager.resumeCurrent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        currentItem()?.let { outState.putLong(EXTRA_ITEM_ID, it.id) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (infoSheet.isOpen) infoSheet.close() else dismiss()
    }

    private fun load(focusId: Long) {
        Background.run({ repository.query(filter, albumId) }) { items ->
            if (items.isEmpty()) {
                finish()
                return@run
            }
            val index = items.indexOfFirst { it.id == focusId }.takeIf { it >= 0 } ?: items.lastIndex
            pager.setItems(items, index)
        }
    }

    private fun currentItem(): MediaItem? = pager.items.getOrNull(pager.currentIndex)

    // --- Chrome ---------------------------------------------------------------------------------

    private fun setUpChrome(root: View) {
        val topBar = findViewById<View>(R.id.top_bar)
        val toolbar = findViewById<View>(R.id.toolbar)
        val scrim = findViewById<View>(R.id.top_scrim)
        root.onSystemBars { bars ->
            (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top
            scrim.layoutParams.height = bars.top + 120.dpi
            (toolbar.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + 12.dpi
            (videoControls.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + 12.dpi + 52.dpi + 12.dpi
            (infoSheet.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + 10.dpi
            topBar.requestLayout()
            toolbar.requestLayout()
            videoControls.requestLayout()
        }

        findViewById<View>(R.id.back_button).setOnClickListener { dismiss() }
        findViewById<View>(R.id.library_button).setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
            finish()
        }
        findViewById<View>(R.id.share_button).setOnClickListener {
            currentItem()?.let { MediaActions.share(this, it) }
        }
        findViewById<View>(R.id.delete_button).setOnClickListener { deleteCurrent() }
        findViewById<View>(R.id.info_button).setOnClickListener {
            val item = currentItem() ?: return@setOnClickListener
            if (infoSheet.isOpen) infoSheet.close() else infoSheet.show(item)
        }
        favoriteButton.setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        favoriteButton.setOnClickListener { toggleFavorite() }
        editButton.setOnClickListener {
            val item = currentItem()?.takeUnless { it.isVideo } ?: return@setOnClickListener
            pager.pauseAll()
            @Suppress("DEPRECATION")
            startActivityForResult(EditorActivity.intent(this, item.uri, item.date), REQUEST_EDIT)
        }

        playPause.setOnClickListener { pager.currentPage.togglePlay() }
        scrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) pager.currentPage.seekTo(progress / bar.max.toFloat())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                scrubbing = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                scrubbing = false
            }
        })
    }

    private fun onPageChanged() {
        val item = currentItem() ?: return
        titleDate.text = Formatters.date(item.date)
        titleTime.text = Formatters.time(item.date)
        favoriteButton.setImageResource(if (item.isFavorite) R.drawable.ic_heart_fill else R.drawable.ic_heart)
        videoControls.setVisible(item.isVideo)
        editButton.setVisible(!item.isVideo)
        updateVideoControls()
        if (infoSheet.isOpen) infoSheet.show(item)
    }

    private fun updateVideoControls() {
        val page = pager.currentPage
        if (!page.isVideo) return
        playPause.setImageResource(if (page.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        val duration = page.durationMs
        if (!scrubbing && duration > 0) scrubber.progress = (page.positionMs * scrubber.max.toLong() / duration).toInt()
        videoTime.text = Formatters.duration((duration - page.positionMs).toLong().coerceAtLeast(0))
    }

    private fun toggleChrome() {
        if (infoSheet.isOpen) {
            infoSheet.close()
            return
        }
        chromeVisible = !chromeVisible
        chrome.animate().alpha(if (chromeVisible) 1f else 0f).setDuration(200)
            .withStartAction { if (chromeVisible) chrome.setVisible(true) }
            .withEndAction { if (!chromeVisible) chrome.setVisible(false) }
            .start()
    }

    private fun dismiss() {
        pager.pauseAll()
        chrome.animate().alpha(0f).setDuration(150).start()
        background.animate().alpha(0f).setDuration(200).start()
        pager.animate().alpha(0f).setDuration(200).withEndAction {
            finish()
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }.start()
    }

    // --- Actions --------------------------------------------------------------------------------

    private fun deleteCurrent() {
        val item = currentItem() ?: return
        Haptics.tap(chrome)
        pendingItem = item
        if (MediaActions.delete(this, item)) onDeleted(item)
    }

    private fun onDeleted(item: MediaItem) {
        pendingItem = null
        ImageLoader.get(this).evict(item)
        val remaining = pager.items.filter { it.id != item.id }
        if (remaining.isEmpty()) {
            finish()
            return
        }
        // Stay at the same position: shows the next item, or the previous one at the end.
        pager.setItems(remaining, pager.currentIndex.coerceAtMost(remaining.lastIndex))
    }

    private fun toggleFavorite() {
        val item = currentItem() ?: return
        val favorite = !item.isFavorite
        Haptics.tick(favoriteButton)
        pendingItem = item
        pendingFavorite = favorite
        if (MediaActions.setFavorite(this, item, favorite)) onFavoriteChanged(item, favorite)
    }

    private fun onFavoriteChanged(item: MediaItem, favorite: Boolean) {
        pendingItem = null
        val updated = pager.items.map { if (it.id == item.id) it.copy(isFavorite = favorite) else it }
        pager.setItems(updated, pager.currentIndex)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_EDIT) {
            // The edited copy is a new photo: show it.
            if (resultCode == RESULT_OK) load(data?.getLongExtra(EditorActivity.EXTRA_ITEM_ID, -1L) ?: -1L)
            return
        }
        val item = pendingItem ?: return
        if (resultCode != RESULT_OK) {
            pendingItem = null
            return
        }
        when (requestCode) {
            MediaActions.REQUEST_DELETE -> onDeleted(item)
            MediaActions.REQUEST_FAVORITE -> onFavoriteChanged(item, pendingFavorite)
        }
    }

    companion object {
        private const val EXTRA_FILTER = "filter"
        private const val EXTRA_ITEM_ID = "item_id"
        private const val EXTRA_ALBUM = "album"
        private const val NO_ALBUM = Long.MIN_VALUE
        private const val REQUEST_EDIT = 4103

        /** Browses the items of [filter] (and of the album [albumId] when given), starting at [itemId]. */
        fun intent(context: Context, filter: MediaFilter, itemId: Long, albumId: Long? = null): Intent =
            Intent(context, ViewerActivity::class.java)
                .putExtra(EXTRA_FILTER, filter.ordinal)
                .putExtra(EXTRA_ITEM_ID, itemId)
                .putExtra(EXTRA_ALBUM, albumId ?: NO_ALBUM)
    }
}
