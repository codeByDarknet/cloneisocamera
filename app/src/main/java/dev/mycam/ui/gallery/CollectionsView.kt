package dev.mycam.ui.gallery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.mycam.R
import dev.mycam.media.Album
import dev.mycam.media.ImageLoader
import dev.mycam.media.MediaFilter
import dev.mycam.media.MediaItem
import dev.mycam.ui.glass.GlassBackdrop
import dev.mycam.util.Haptics
import dev.mycam.util.dp
import dev.mycam.util.dpi

/**
 * The Collections tab: pinned collections and albums as rows of cards that scroll
 * sideways, then the media types as a list. Tapping one calls [onOpen].
 */
class CollectionsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ScrollView(context, attrs) {

    /** What a card or a row opens: items of [filter], restricted to the album [albumId] if set. */
    class Entry(val title: String, val filter: MediaFilter, val albumId: Long? = null)

    var onOpen: ((Entry) -> Unit)? = null

    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val loader = ImageLoader.get(context)
    private val secondary = context.getColor(R.color.ios_label_secondary)
    private val accent = context.getColor(R.color.ios_blue)

    init {
        isVerticalScrollBarEnabled = false
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * Room kept clear under the header and above the tab bar. It pads the content rather than
     * the scroll view, whose own padding makes it misjudge whether it can scroll at all.
     */
    fun setInsets(top: Int, bottom: Int) {
        content.setPadding(0, top, 0, bottom)
    }

    fun bind(items: List<MediaItem>, albums: List<Album>) {
        content.removeAllViews()

        val pinned = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaFilter.FAVORITES)
            add(MediaFilter.VIDEOS)
            add(MediaFilter.SCREENSHOTS)
        }.map { it to items.filter(it::matches) }
            .filter { (filter, list) -> filter == MediaFilter.FAVORITES || list.isNotEmpty() }
        if (pinned.isNotEmpty()) {
            section(R.string.pinned)
            cardRow(
                pinned.map { (filter, list) ->
                    Card(context.getString(filter.label), list.size, list.lastOrNull(), filter.icon, Entry(context.getString(filter.label), filter))
                },
            )
        }

        section(R.string.albums)
        if (albums.isEmpty()) {
            content.addView(
                TextView(context).apply {
                    setText(R.string.no_albums)
                    textSize = 15f
                    setTextColor(secondary)
                    setPadding(SIDE.dpi, 0, SIDE.dpi, 0)
                },
            )
        } else {
            cardRow(
                albums.map { album ->
                    Card(album.name, album.count, album.cover, R.drawable.ic_album, Entry(album.name, MediaFilter.ALL, album.id))
                },
            )
        }

        val types = TYPES.map { it to items.count(it::matches) }.filter { it.second > 0 }
        if (types.isNotEmpty()) {
            section(R.string.media_types)
            typeList(types)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (canvas.isHardwareAccelerated) GlassBackdrop.find(this)?.requestCapture()
    }

    // --- Building blocks ------------------------------------------------------------------------

    private class Card(val name: String, val count: Int, val cover: MediaItem?, val icon: Int, val entry: Entry)

    private fun section(title: Int) {
        content.addView(
            TextView(context).apply {
                setText(title)
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(SIDE.dpi, 22.dpi, SIDE.dpi, 10.dpi)
            },
        )
    }

    private fun cardRow(cards: List<Card>) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(SIDE.dpi, 0, (SIDE - CARD_GAP).dpi, 0)
        }
        cards.forEach { row.addView(cardView(it), LinearLayout.LayoutParams(CARD.dpi, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = CARD_GAP.dpi }) }
        val scroller = object : HorizontalScrollView(context) {
            override fun dispatchDraw(canvas: Canvas) {
                super.dispatchDraw(canvas)
                if (canvas.isHardwareAccelerated) GlassBackdrop.find(this)?.requestCapture()
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
        content.addView(scroller)
    }

    private fun cardView(card: Card): View {
        val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val placeholder = ImageView(context).apply {
            setImageResource(card.icon)
            setColorFilter(0x66FFFFFF)
        }
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = 12.dp
                setColor(0xFF1C1C1E.toInt())
            }
            clipToOutline = true
            addView(placeholder, FrameLayout.LayoutParams(34.dpi, 34.dpi, Gravity.CENTER))
            addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        card.cover?.let { loader.bindThumbnail(image, it, (CARD * 2).dpi) }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(frame, LinearLayout.LayoutParams(CARD.dpi, CARD.dpi))
            addView(
                TextView(context).apply {
                    text = card.name
                    textSize = 15f
                    typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                    setTextColor(0xFFFFFFFF.toInt())
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = 7.dpi },
            )
            addView(
                TextView(context).apply {
                    text = card.count.toString()
                    textSize = 13f
                    setTextColor(secondary)
                },
            )
            setOnClickListener { open(card.entry) }
        }
    }

    private fun typeList(types: List<Pair<MediaFilter, Int>>) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = 14.dp
                setColor(0xFF1C1C1E.toInt())
            }
            clipToOutline = true
        }
        types.forEachIndexed { i, (filter, count) ->
            if (i > 0) {
                list.addView(
                    View(context).apply { setBackgroundColor(0x26FFFFFF) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1).apply { marginStart = 52.dpi },
                )
            }
            list.addView(typeRow(filter, count), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 48.dpi))
        }
        content.addView(
            list,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(SIDE.dpi, 0, SIDE.dpi, 0) },
        )
    }

    private fun typeRow(filter: MediaFilter, count: Int): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(14.dpi, 0, 12.dpi, 0)
        setBackgroundResource(android.R.drawable.list_selector_background)
        addView(
            ImageView(context).apply {
                setImageResource(filter.icon)
                setColorFilter(accent)
            },
            LinearLayout.LayoutParams(24.dpi, 24.dpi).apply { marginEnd = 14.dpi },
        )
        addView(
            TextView(context).apply {
                setText(filter.label)
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        addView(
            TextView(context).apply {
                text = count.toString()
                textSize = 15f
                setTextColor(secondary)
            },
        )
        addView(
            ImageView(context).apply {
                setImageResource(R.drawable.ic_chevron_right)
                setColorFilter(secondary)
            },
            LinearLayout.LayoutParams(16.dpi, 16.dpi).apply { marginStart = 8.dpi },
        )
        setOnClickListener { open(Entry(context.getString(filter.label), filter)) }
    }

    private fun open(entry: Entry) {
        Haptics.tick(this)
        onOpen?.invoke(entry)
    }

    private companion object {
        const val SIDE = 18
        const val CARD = 150
        const val CARD_GAP = 12
        val TYPES = listOf(MediaFilter.VIDEOS, MediaFilter.PANORAMAS, MediaFilter.SCREENSHOTS, MediaFilter.SCREEN_RECORDINGS)
    }
}

/** Display name of a filter, in menus and in Collections. */
val MediaFilter.label: Int
    get() = when (this) {
        MediaFilter.ALL -> R.string.filter_all
        MediaFilter.MYCAM -> R.string.filter_mycam
        MediaFilter.VIDEOS -> R.string.filter_videos
        MediaFilter.FAVORITES -> R.string.filter_favorites
        MediaFilter.PANORAMAS -> R.string.filter_panoramas
        MediaFilter.SCREENSHOTS -> R.string.filter_screenshots
        MediaFilter.SCREEN_RECORDINGS -> R.string.filter_screen_recordings
    }

val MediaFilter.icon: Int
    get() = when (this) {
        MediaFilter.FAVORITES -> R.drawable.ic_heart
        MediaFilter.VIDEOS, MediaFilter.SCREEN_RECORDINGS -> if (this == MediaFilter.VIDEOS) R.drawable.ic_video else R.drawable.ic_screen_record
        MediaFilter.PANORAMAS -> R.drawable.ic_panorama
        MediaFilter.SCREENSHOTS -> R.drawable.ic_screenshot
        else -> R.drawable.ic_album
    }
