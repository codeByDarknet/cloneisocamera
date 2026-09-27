package dev.mycam.ui.camera

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import dev.mycam.R
import dev.mycam.camera.AspectRatio
import dev.mycam.camera.CaptureMode
import dev.mycam.camera.FlashMode
import dev.mycam.camera.TimerDelay
import dev.mycam.camera.VideoQuality
import dev.mycam.ui.glass.GlassView
import dev.mycam.ui.glass.Spring
import dev.mycam.util.Haptics
import dev.mycam.util.Prefs
import dev.mycam.util.dp
import dev.mycam.util.dpi
import dev.mycam.util.setVisible

/**
 * The "camera controls" sheet, opened from the dots button: a glass sheet over the bottom
 * controls with a grid of round buttons, each showing its current value and cycling through the
 * choices on tap. It writes straight into [Prefs] and then calls [onChanged].
 */
class QuickSettingsPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassView(context, attrs) {

    enum class Setting { FLASH, ASPECT, TIMER, GRID, VIDEO_QUALITY, SHUTTER_SOUND, FACES }

    var onChanged: ((Setting) -> Unit)? = null

    private lateinit var prefs: Prefs
    private var videoFlash = false
    private val grid = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(12.dpi, 22.dpi, 12.dpi, 18.dpi)
    }
    private val tiles = Setting.entries.associateWith { Tile(it) }
    private val yellow = context.getColor(R.color.ios_yellow)

    private val revealSpring: Spring = Spring(0f, stiffness = 460f, dampingRatio = 0.82f) { p ->
        alpha = p.coerceIn(0f, 1f)
        scaleX = 0.94f + 0.06f * p
        scaleY = 0.94f + 0.06f * p
        translationY = (1f - p) * 48.dp
        if (p <= 0.01f && revealSpring.target == 0f) setVisible(false)
    }

    val isOpen: Boolean get() = revealSpring.target == 1f

    init {
        style = Style.REGULAR
        cornerRadius = 30.dp
        isClickable = true // swallow touches so they don't reach what is below
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun bind(prefs: Prefs, mode: CaptureMode, hasFlash: Boolean, hasFaceDetection: Boolean) {
        this.prefs = prefs
        videoFlash = mode.isVideo
        val shown = buildList {
            if (hasFlash && mode != CaptureMode.PANO) add(Setting.FLASH)
            if (mode.isStill && mode != CaptureMode.PANO) add(Setting.TIMER)
            if (mode == CaptureMode.PHOTO) add(Setting.ASPECT)
            if (mode.isVideo) add(Setting.VIDEO_QUALITY)
            add(Setting.GRID)
            add(Setting.SHUTTER_SOUND)
            if (hasFaceDetection) add(Setting.FACES)
        }
        grid.removeAllViews()
        shown.chunked(COLUMNS).forEachIndexed { r, settings ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until COLUMNS) {
                // Space measures to nothing, where an empty View would take all the height offered.
                val tile = settings.getOrNull(i)?.let { tiles.getValue(it).view } ?: Space(context)
                (tile.parent as? LinearLayout)?.removeView(tile)
                row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { if (r > 0) topMargin = 18.dpi },
            )
        }
        tiles.values.forEach { it.refresh() }
    }

    fun open() {
        setVisible(true)
        post {
            pivotX = width / 2f
            pivotY = height.toFloat()
        }
        revealSpring.animateTo(1f)
    }

    fun close() {
        if (!isOpen) return
        revealSpring.animateTo(0f)
    }

    private fun cycle(setting: Setting) {
        when (setting) {
            Setting.FLASH -> prefs.flash = next(if (videoFlash) VIDEO_FLASH_ORDER else FLASH_ORDER, displayedFlash())
            Setting.ASPECT -> prefs.aspect = next(ASPECT_ORDER, prefs.aspect)
            Setting.TIMER -> prefs.timer = next(TIMER_ORDER, prefs.timer)
            Setting.GRID -> prefs.grid = !prefs.grid
            Setting.VIDEO_QUALITY -> prefs.videoQuality = next(QUALITY_ORDER, prefs.videoQuality)
            Setting.SHUTTER_SOUND -> prefs.shutterSound = !prefs.shutterSound
            Setting.FACES -> prefs.faceDetection = !prefs.faceDetection
        }
        tiles.getValue(setting).refresh()
        onChanged?.invoke(setting)
    }

    /** Video has no automatic flash: it shows (and cycles from) off. */
    private fun displayedFlash(): FlashMode =
        prefs.flash.let { if (videoFlash && it == FlashMode.AUTO) FlashMode.OFF else it }

    private fun <T> next(order: List<T>, current: T): T = order[(order.indexOf(current) + 1) % order.size]

    /** One round button with its caption. Shows either an icon or a short text value. */
    private inner class Tile(private val setting: Setting) {
        private val icon = ImageView(context)
        private val value = TextView(context).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        private val disc = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(DISC_COLOR)
            }
            addView(icon, FrameLayout.LayoutParams(24.dpi, 24.dpi, Gravity.CENTER))
            addView(value, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        private val caption = TextView(context).apply {
            textSize = 10.5f
            letterSpacing = 0.04f
            isAllCaps = true
            setTextColor(0xD9FFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setText(
                when (setting) {
                    Setting.FLASH -> R.string.ctl_flash
                    Setting.ASPECT -> R.string.ctl_format
                    Setting.TIMER -> R.string.ctl_timer
                    Setting.GRID -> R.string.ctl_grid
                    Setting.VIDEO_QUALITY -> R.string.ctl_quality
                    Setting.SHUTTER_SOUND -> R.string.ctl_sound
                    Setting.FACES -> R.string.ctl_faces
                },
            )
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(disc, LinearLayout.LayoutParams(DISC_SIZE.dpi, DISC_SIZE.dpi))
            addView(
                caption,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = 7.dpi },
            )
            setOnClickListener {
                Haptics.tick(this)
                disc.animate().scaleX(0.88f).scaleY(0.88f).setDuration(70).withEndAction {
                    disc.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }.start()
                cycle(setting)
            }
        }

        fun refresh() {
            if (!::prefs.isInitialized) return
            when (setting) {
                Setting.FLASH -> {
                    val flash = displayedFlash()
                    showIcon(
                        when (flash) {
                            FlashMode.AUTO -> R.drawable.ic_bolt_auto
                            FlashMode.ON -> R.drawable.ic_bolt
                            FlashMode.OFF -> R.drawable.ic_bolt_slash
                        },
                        active = flash == FlashMode.ON,
                    )
                }
                Setting.ASPECT -> showText(prefs.aspect.let { "${it.width}:${it.height}" }, active = false)
                Setting.TIMER -> when (prefs.timer) {
                    TimerDelay.OFF -> showIcon(R.drawable.ic_timer, active = false)
                    else -> showText("${prefs.timer.seconds}s", active = true)
                }
                Setting.GRID -> showIcon(R.drawable.ic_grid, active = prefs.grid)
                Setting.VIDEO_QUALITY -> showText(if (prefs.videoQuality == VideoQuality.HD) "HD" else "FHD", active = false)
                Setting.SHUTTER_SOUND -> showIcon(
                    if (prefs.shutterSound) R.drawable.ic_speaker else R.drawable.ic_speaker_slash,
                    active = false,
                )
                Setting.FACES -> showIcon(R.drawable.ic_face, active = prefs.faceDetection)
            }
        }

        private fun showIcon(res: Int, active: Boolean) {
            icon.setImageResource(res)
            icon.setColorFilter(if (active) yellow else 0xFFFFFFFF.toInt())
            icon.setVisible(true)
            value.setVisible(false)
        }

        private fun showText(text: String, active: Boolean) {
            value.text = text
            value.setTextColor(if (active) yellow else 0xFFFFFFFF.toInt())
            value.setVisible(true)
            icon.setVisible(false)
        }
    }

    private companion object {
        const val COLUMNS = 3
        const val DISC_SIZE = 54
        const val DISC_COLOR = 0x4D000000
        val FLASH_ORDER = listOf(FlashMode.AUTO, FlashMode.ON, FlashMode.OFF)
        val VIDEO_FLASH_ORDER = listOf(FlashMode.OFF, FlashMode.ON)
        val ASPECT_ORDER = listOf(AspectRatio.RATIO_4_3, AspectRatio.RATIO_16_9, AspectRatio.RATIO_1_1)
        val TIMER_ORDER = listOf(TimerDelay.OFF, TimerDelay.THREE, TimerDelay.TEN)
        val QUALITY_ORDER = listOf(VideoQuality.HD, VideoQuality.FULL_HD)
    }
}
