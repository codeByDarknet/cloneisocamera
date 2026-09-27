package dev.mycam.ui.gallery

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.mycam.R
import dev.mycam.media.ImageLoader
import dev.mycam.media.MediaItem
import dev.mycam.util.Formatters
import dev.mycam.util.dpi
import dev.mycam.util.setVisible

/**
 * Square cells with a duration badge for videos and a heart for favorites; in selection mode,
 * selected cells are veiled and show the blue check.
 */
class MediaGridAdapter(private val context: Context) : BaseAdapter() {

    var items: List<MediaItem> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Ids of the selected items, or null outside selection mode. */
    var selection: Set<Long>? = null
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Decode size of the thumbnails, set from the column width. */
    var thumbnailSize: Int = 256

    private val loader = ImageLoader.get(context)

    override fun getCount() = items.size
    override fun getItem(position: Int) = items[position]
    override fun getItemId(position: Int) = items[position].id
    override fun hasStableIds() = true

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val cell = convertView as? Cell ?: Cell(context)
        val item = items[position]
        loader.bindThumbnail(cell.image, item, thumbnailSize)
        cell.duration.setVisible(item.isVideo)
        if (item.isVideo) cell.duration.text = Formatters.duration(item.durationMs)
        cell.favorite.setVisible(item.isFavorite)
        val selected = selection?.contains(item.id) == true
        cell.check.setVisible(selected)
        cell.veil.setVisible(selected)
        return cell
    }

    private class Cell(context: Context) : FrameLayout(context) {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1C1C1E.toInt())
        }
        val duration = TextView(context).apply {
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(4f, 0f, 1f, 0x99000000.toInt())
        }
        val favorite = ImageView(context).apply {
            setImageResource(R.drawable.ic_heart_fill)
        }
        val veil = View(context).apply { setBackgroundColor(0x4DFFFFFF) }
        val check = ImageView(context).apply {
            setImageResource(R.drawable.ic_check_circle)
            elevation = 1f
        }

        init {
            addView(image, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(
                duration,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                    .apply { setMargins(0, 0, 5.dpi, 3.dpi) },
            )
            addView(
                favorite,
                LayoutParams(14.dpi, 14.dpi, Gravity.BOTTOM or Gravity.START).apply { setMargins(5.dpi, 0, 0, 5.dpi) },
            )
            addView(veil, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(
                check,
                LayoutParams(20.dpi, 20.dpi, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 4.dpi, 4.dpi) },
            )
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Square cells.
            super.onMeasure(widthMeasureSpec, widthMeasureSpec)
        }
    }
}
