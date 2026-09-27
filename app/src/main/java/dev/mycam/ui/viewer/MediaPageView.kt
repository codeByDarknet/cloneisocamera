package dev.mycam.ui.viewer

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import dev.mycam.R
import dev.mycam.media.ImageLoader
import dev.mycam.media.MediaItem
import dev.mycam.ui.glass.GlassView
import dev.mycam.util.dpi
import dev.mycam.util.setVisible
import java.util.concurrent.Future

/**
 * One page of the viewer. Photos go in a [ZoomImageView]; videos show their poster frame in it
 * and play in a [TextureView] on top once the page becomes the current one.
 */
class MediaPageView(context: Context) : FrameLayout(context) {

    var item: MediaItem? = null
        private set

    /** Single tap on the photo or video (the activity toggles its chrome). */
    var onTap: (() -> Unit)? = null

    /** Playback state changes and progress ticks, for the activity's video controls. */
    var onPlaybackChanged: (() -> Unit)? = null

    val image = ZoomImageView(context)
    private val videoView = TextureView(context)
    private val playBadge = GlassView(context).apply {
        interactive = true
        addView(
            ImageView(context).apply { setImageResource(R.drawable.ic_play) },
            LayoutParams(28.dpi, 28.dpi, Gravity.CENTER),
        )
    }

    private val loader = ImageLoader.get(context)
    private var request: Future<*>? = null
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var active = false

    val isVideo: Boolean get() = item?.isVideo == true
    val isPlaying: Boolean get() = prepared && player?.isPlaying == true
    val durationMs: Int get() = if (prepared) player?.duration ?: 0 else item?.durationMs?.toInt() ?: 0
    val positionMs: Int get() = if (prepared) player?.currentPosition ?: 0 else 0

    init {
        setBackgroundColor(0)
        addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(videoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(playBadge, LayoutParams(72.dpi, 72.dpi, Gravity.CENTER))
        videoView.alpha = 0f
        videoView.setVisible(false)
        playBadge.setVisible(false)
        image.onTap = { onTap?.invoke() }
        videoView.setOnClickListener { onTap?.invoke() }
        playBadge.setOnClickListener { togglePlay() }
        videoView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                surface = Surface(st)
                if (active) preparePlayer()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    fun bind(newItem: MediaItem?) {
        if (newItem == item && newItem != null) return
        releasePlayer()
        request?.cancel(true)
        item = newItem
        image.bitmap = null
        videoView.setVisible(false)
        playBadge.setVisible(false)
        if (newItem == null) return

        // Thumbnail first for an instant page, then the screen-sized image.
        val side = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val full = loader.peekFull(newItem)
        if (full != null) {
            image.bitmap = full
        } else {
            loader.thumbnail(newItem, 512) { thumb ->
                if (item == newItem && image.bitmap == null) image.bitmap = thumb
            }
            if (!newItem.isVideo) {
                request = loader.full(newItem, side) { bitmap ->
                    if (item == newItem && bitmap != null) image.bitmap = bitmap
                }
            }
        }
        if (newItem.isVideo) {
            videoView.setVisible(true)
            playBadge.setVisible(true)
        }
        if (active) preparePlayer()
    }

    /** Only the current page plays; the neighbors keep just their poster. */
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) preparePlayer() else {
            releasePlayer()
            image.resetZoom()
        }
    }

    fun togglePlay() {
        val p = player
        if (p == null || !prepared) {
            preparePlayer()
            return
        }
        if (p.isPlaying) p.pause() else p.start()
        updatePlayState()
    }

    fun pause() {
        if (isPlaying) {
            player?.pause()
            updatePlayState()
        }
    }

    fun seekTo(fraction: Float) {
        val p = player ?: return
        if (!prepared) return
        p.seekTo((fraction.coerceIn(0f, 1f) * p.duration).toInt())
        onPlaybackChanged?.invoke()
    }

    private fun preparePlayer() {
        val video = item ?: return
        if (!video.isVideo || player != null) return
        val target = surface ?: return
        prepared = false
        player = MediaPlayer().apply {
            try {
                setDataSource(context, video.uri)
                setSurface(target)
                setOnPreparedListener { mp ->
                    prepared = true
                    fitVideo(mp.videoWidth, mp.videoHeight)
                    mp.start()
                    updatePlayState()
                }
                setOnInfoListener { _, what, _ ->
                    // Reveal the video only once a frame is on screen: no black flash.
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) videoView.animate().alpha(1f).setDuration(120).start()
                    false
                }
                setOnCompletionListener { updatePlayState() }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "Playback error $what/$extra for ${video.uri}")
                    releasePlayer()
                    true
                }
                prepareAsync()
            } catch (e: Exception) {
                Log.e(TAG, "Unable to play ${video.uri}", e)
                releasePlayer()
            }
        }
    }

    private fun releasePlayer() {
        removeCallbacks(progressTick)
        player?.let { runCatching { it.release() } }
        player = null
        prepared = false
        videoView.alpha = 0f
        updatePlayState()
    }

    /** Letterboxes the TextureView to the video's aspect ratio. */
    private fun fitVideo(videoWidth: Int, videoHeight: Int) {
        if (videoWidth <= 0 || videoHeight <= 0 || width == 0 || height == 0) return
        val scale = minOf(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        videoView.layoutParams = (videoView.layoutParams as LayoutParams).apply {
            width = (videoWidth * scale).toInt()
            height = (videoHeight * scale).toInt()
        }
    }

    private val progressTick = object : Runnable {
        override fun run() {
            onPlaybackChanged?.invoke()
            if (isPlaying) postDelayed(this, 200L)
        }
    }

    private fun updatePlayState() {
        playBadge.animate().alpha(if (isPlaying) 0f else 1f).setDuration(150).start()
        playBadge.isClickable = !isPlaying
        removeCallbacks(progressTick)
        progressTick.run()
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        request?.cancel(true)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG = "MediaPageView"
    }
}
