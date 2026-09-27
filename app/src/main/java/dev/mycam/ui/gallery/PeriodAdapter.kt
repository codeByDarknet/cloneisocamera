package dev.mycam.ui.gallery

import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.mycam.media.ImageLoader
import dev.mycam.media.MediaItem
import dev.mycam.util.dp
import dev.mycam.util.dpi
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The Years and Months levels of the library: one rounded card per period, showing a key photo
 * (the latest favorite, or the latest item) and the period's name.
 */
class PeriodAdapter(private val context: Context) : BaseAdapter() {

    enum class Level { YEARS, MONTHS }

    /** A run of consecutive items of the same year or month; [first] indexes the full list. */
    data class Period(val key: Int, val first: Int, val cover: MediaItem, val start: Long)

    var level = Level.MONTHS
        private set
    var periods: List<Period> = emptyList()
        private set

    /** Decode size of the covers, set from the card width. */
    var coverSize: Int = 720

    private val loader = ImageLoader.get(context)
    private val monthFormat = SimpleDateFormat("LLLL yyyy", Locale.getDefault())

    /** Groups [items], sorted by date, into periods of [level]. */
    fun submit(items: List<MediaItem>, level: Level) {
        this.level = level
        val calendar = Calendar.getInstance()
        val result = mutableListOf<Period>()
        var start = 0
        while (start < items.size) {
            val key = keyOf(items[start], calendar)
            var end = start + 1
            while (end < items.size && keyOf(items[end], calendar) == key) end++
            val run = items.subList(start, end)
            val cover = run.lastOrNull { it.isFavorite } ?: run.last()
            result += Period(key, start, cover, items[start].date)
            start = end
        }
        periods = result
        notifyDataSetChanged()
    }

    private fun keyOf(item: MediaItem, calendar: Calendar): Int {
        calendar.timeInMillis = item.date
        val year = calendar.get(Calendar.YEAR)
        return if (level == Level.YEARS) year else year * 12 + calendar.get(Calendar.MONTH)
    }

    override fun getCount() = periods.size
    override fun getItem(position: Int) = periods[position]
    override fun getItemId(position: Int) = periods[position].key.toLong()
    override fun hasStableIds() = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val card = convertView as? Card ?: Card(context)
        val period = periods[position]
        card.aspect = if (level == Level.YEARS) YEAR_ASPECT else MONTH_ASPECT
        loader.bindThumbnail(card.image, period.cover, coverSize)
        if (level == Level.YEARS) {
            card.title.visibility = View.GONE
            card.chip.visibility = View.VISIBLE
            card.chip.text = period.key.toString()
        } else {
            card.chip.visibility = View.GONE
            card.title.visibility = View.VISIBLE
            card.title.text = monthFormat.format(period.start).replaceFirstChar { it.titlecase(Locale.getDefault()) }
        }
        return card
    }

    private class Card(context: Context) : FrameLayout(context) {
        /** Height over width. */
        var aspect = MONTH_ASPECT
            set(value) {
                if (field != value) {
                    field = value
                    requestLayout()
                }
            }

        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1C1C1E.toInt())
        }
        private val shade = View(context).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x73000000, 0x00000000, 0x00000000),
            )
        }
        val title = TextView(context).apply {
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setShadowLayer(8f, 0f, 1f, 0x80000000.toInt())
        }
        val chip = TextView(context).apply {
            textSize = 17f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(14.dpi, 6.dpi, 14.dpi, 6.dpi)
            background = GradientDrawable().apply {
                cornerRadius = 100.dp
                setColor(0x59000000)
                setStroke(1.dpi, 0x40FFFFFF)
            }
        }

        init {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, CORNER.dp)
                }
            }
            clipToOutline = true
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(shade, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(
                title,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                    .apply { setMargins(16.dpi, 12.dpi, 16.dpi, 0) },
            )
            addView(
                chip,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                    .apply { setMargins(14.dpi, 14.dpi, 0, 0) },
            )
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec((w * aspect).toInt(), MeasureSpec.EXACTLY))
        }
    }

    private companion object {
        const val CORNER = 14
        const val MONTH_ASPECT = 0.62f
        const val YEAR_ASPECT = 1.05f
    }
}
