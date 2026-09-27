package dev.mycam.ui.viewer

import android.content.Context
import android.graphics.Typeface
import android.media.ExifInterface
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import dev.mycam.R
import dev.mycam.media.MediaItem
import dev.mycam.ui.glass.GlassView
import dev.mycam.ui.glass.Spring
import dev.mycam.util.Background
import dev.mycam.util.Formatters
import dev.mycam.util.dp
import dev.mycam.util.dpi
import dev.mycam.util.setVisible
import kotlin.math.roundToInt

/** The "i" sheet: date, file details and, for photos, the camera settings read from EXIF. */
class InfoSheet @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GlassView(context, attrs) {

    private val column = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dpi, 18.dpi, 20.dpi, 18.dpi)
    }
    private var shownItem: MediaItem? = null
    private val slide = Spring(0f, stiffness = 380f, dampingRatio = 0.85f) { p ->
        translationY = (1f - p) * (height + 40.dp)
        alpha = p.coerceIn(0f, 1f)
        if (p <= 0.01f && !isOpen) setVisible(false)
    }

    var isOpen = false
        private set

    init {
        style = Style.DARK
        cornerRadius = 30.dp
        isClickable = true
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnClickListener { close() }
    }

    fun show(item: MediaItem) {
        shownItem = item
        populate(item, exif = emptyList())
        if (!item.isVideo) {
            Background.run({ readExif(item) }) { rows ->
                if (shownItem == item) populate(item, rows)
            }
        }
        if (!isOpen) {
            isOpen = true
            setVisible(true)
            // Wait for the first layout so the slide starts below the screen edge.
            post {
                slide.snapTo(0f)
                slide.animateTo(1f)
            }
        }
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        shownItem = null
        slide.animateTo(0f)
    }

    private fun populate(item: MediaItem, exif: List<Pair<Int, String>>) {
        column.removeAllViews()
        column.addView(
            text(Formatters.dateTime(item.date), 17f, bold = true),
        )
        val rows = buildList {
            add(R.string.info_name to item.name)
            item.album?.let { add(R.string.info_album to it) }
            if (item.width > 0 && item.height > 0) {
                val mp = item.width.toLong() * item.height / 1_000_000.0
                val detail = if (item.isVideo) "${item.width} × ${item.height}"
                else "${item.width} × ${item.height} · ${"%.1f".format(mp)} MP"
                add(R.string.info_resolution to detail)
            }
            add(R.string.info_size to Formatters.fileSize(item.sizeBytes))
            if (item.isVideo) add(R.string.info_duration to Formatters.duration(item.durationMs))
            addAll(exif)
        }
        rows.forEach { (label, value) -> column.addView(row(label, value)) }
    }

    private fun row(labelRes: Int, value: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, 10.dpi, 0, 0)
        addView(
            text(context.getString(labelRes), 14f, secondary = true),
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.4f),
        )
        addView(
            text(value, 14f).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.6f),
        )
    }

    private fun text(value: String, size: Float, bold: Boolean = false, secondary: Boolean = false) =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(if (secondary) 0x99EBEBF5.toInt() else 0xFFFFFFFF.toInt())
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        }

    /** Runs on the disk worker. */
    private fun readExif(item: MediaItem): List<Pair<Int, String>> = try {
        context.contentResolver.openInputStream(item.uri)?.use { stream ->
            val exif = ExifInterface(stream)
            buildList {
                val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()
                val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()
                val camera = listOfNotNull(make, model).filter { it.isNotEmpty() }.distinct().joinToString(" ")
                if (camera.isNotEmpty()) add(R.string.info_camera to camera)

                exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
                    add(R.string.info_exposure to if (it >= 1) "${"%.1f".format(it)} s" else "1/${(1 / it).roundToInt()} s")
                }
                @Suppress("DEPRECATION")
                exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0).takeIf { it > 0 }?.let {
                    add(R.string.info_iso to it.toString())
                }
                exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let {
                    add(R.string.info_focal to "${"%.1f".format(it)} mm")
                }
                exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let {
                    add(R.string.info_aperture to "ƒ/${"%.1f".format(it)}")
                }
            }
        } ?: emptyList()
    } catch (e: Exception) {
        Log.w("InfoSheet", "EXIF unavailable for ${item.uri}: ${e.message}")
        emptyList()
    }
}
