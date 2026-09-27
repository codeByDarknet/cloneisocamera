package dev.mycam.ui.gallery

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.mycam.R
import dev.mycam.media.MediaActions
import dev.mycam.media.MediaFilter
import dev.mycam.media.MediaItem
import dev.mycam.media.MediaRepository
import dev.mycam.ui.glass.GlassBackdrop
import dev.mycam.ui.glass.GlassSegmentedControl
import dev.mycam.ui.glass.GlassTabBar
import dev.mycam.ui.glass.GlassView
import dev.mycam.ui.viewer.ViewerActivity
import dev.mycam.util.Background
import dev.mycam.util.Haptics
import dev.mycam.util.dpi
import dev.mycam.util.goEdgeToEdge
import dev.mycam.util.onSystemBars
import dev.mycam.util.setVisible
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * The library: every item in a tight grid with the newest at
 * the bottom, a large title over the date range on screen, Years / Months / All levels at the
 * bottom, a filter menu and a selection mode for sharing or deleting several items at once.
 * A tab bar switches to Collections (pinned items, albums, media types). Started with
 * [albumIntent], it shows a single album or media type instead, with a back button.
 */
class GalleryActivity : Activity() {

    private enum class Level { YEARS, MONTHS, ALL }

    private lateinit var grid: MediaGridView
    private lateinit var periodsView: MediaGridView
    private lateinit var adapter: MediaGridAdapter
    private lateinit var periodAdapter: PeriodAdapter
    private lateinit var subtitle: TextView
    private lateinit var empty: View
    private lateinit var permissionBanner: View
    private lateinit var filterButton: View
    private lateinit var selectLabel: TextView
    private lateinit var bottomBar: View
    private lateinit var levelsControl: GlassSegmentedControl
    private lateinit var selectionBar: View
    private lateinit var selectionCount: TextView
    private lateinit var filterMenu: GlassView
    private lateinit var menuDismiss: View
    private lateinit var tabBar: GlassTabBar
    private lateinit var collections: CollectionsView
    private lateinit var title: TextView

    private val repository by lazy { MediaRepository(this) }
    private val filters = buildList {
        add(MediaFilter.ALL)
        add(MediaFilter.MYCAM)
        add(MediaFilter.VIDEOS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaFilter.FAVORITES)
    }
    private var filter = MediaFilter.ALL
    private var tab = TAB_LIBRARY

    /** Set when showing a single album or media type rather than the library. */
    private var albumMode = false
    private var albumId: Long? = null
    private var level = Level.ALL
    private var columns = DEFAULT_COLUMNS
    private var loadedOnce = false
    private var loadToken = 0

    /** Ids of the selected items, or null outside selection mode. */
    private var selection: MutableSet<Long>? = null

    /** During a slide selection: what was selected before it, and whether it selects or unselects. */
    private var dragBaseline: Set<Long> = emptySet()
    private var dragSelects = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goEdgeToEdge()
        setContentView(R.layout.activity_gallery)

        val root = findViewById<FrameLayout>(R.id.root)
        grid = findViewById(R.id.grid)
        periodsView = findViewById(R.id.periods)
        subtitle = findViewById(R.id.subtitle)
        empty = findViewById(R.id.empty)
        permissionBanner = findViewById(R.id.permission_banner)
        filterButton = findViewById(R.id.filter_button)
        selectLabel = findViewById(R.id.select_label)
        bottomBar = findViewById(R.id.bottom_bar)
        levelsControl = findViewById(R.id.levels)
        selectionBar = findViewById(R.id.selection_bar)
        selectionCount = findViewById(R.id.selection_count)
        filterMenu = findViewById(R.id.filter_menu)
        menuDismiss = findViewById(R.id.menu_dismiss)
        tabBar = findViewById(R.id.tab_bar)
        collections = findViewById(R.id.collections)
        title = findViewById(R.id.title)
        GlassBackdrop.install(root, findViewById(R.id.content))

        savedInstanceState?.let {
            filter = MediaFilter.entries[it.getInt(STATE_FILTER, 0)]
            columns = it.getInt(STATE_COLUMNS, DEFAULT_COLUMNS)
            level = Level.entries[it.getInt(STATE_LEVEL, Level.ALL.ordinal)]
            tab = it.getInt(STATE_TAB, TAB_LIBRARY)
        }
        intent.getStringExtra(EXTRA_TITLE)?.let { albumTitle ->
            albumMode = true
            title.text = albumTitle
            filter = MediaFilter.entries[intent.getIntExtra(EXTRA_FILTER, 0)]
            albumId = intent.getLongExtra(EXTRA_ALBUM, NO_ALBUM).takeIf { it != NO_ALBUM }
            level = Level.ALL
            tab = TAB_LIBRARY
            filterButton.setVisible(false)
            bottomBar.setVisible(false)
            levelsControl.setVisible(false)
            findViewById<View>(R.id.back_button).apply {
                setVisible(true)
                setOnClickListener { finish() }
            }
        }

        adapter = MediaGridAdapter(this)
        grid.adapter = adapter
        grid.numColumns = columns
        grid.setOnItemClickListener { _, view, position, _ ->
            val item = adapter.getItem(position)
            val selected = selection
            if (selected != null) {
                if (!selected.remove(item.id)) selected.add(item.id)
                Haptics.tick(view)
                adapter.selection = selected.toSet()
                updateSelectionCount()
            } else {
                startActivity(ViewerActivity.intent(this, filter, item.id, albumId))
            }
        }
        grid.setOnItemLongClickListener { _, view, position, _ ->
            // A long press selects the item, and the finger can then slide on to select more.
            if (selection == null) enterSelection()
            Haptics.tap(view)
            grid.startDragSelect(position)
            true
        }
        grid.onDragSelectStart = { anchor ->
            val selected = selection.orEmpty()
            dragBaseline = selected.toSet()
            dragSelects = adapter.getItem(anchor).id !in selected
        }
        grid.onDragSelect = ::applyDragSelection
        grid.onPinchColumns = ::changeColumns
        grid.viewTreeObserver.addOnGlobalLayoutListener { updateThumbnailSize() }
        grid.setOnScrollListener(rangeListener)

        periodAdapter = PeriodAdapter(this)
        periodsView.adapter = periodAdapter
        periodsView.setOnItemClickListener { _, _, position, _ ->
            // A card opens the level below, at that period.
            val period = periodAdapter.getItem(position)
            setLevel(Level.ALL, animate = true)
            levelsControl.setSelection(Level.ALL.ordinal, animate = true)
            grid.post { grid.setSelection(period.first) }
        }
        periodsView.setOnScrollListener(rangeListener)
        periodsView.viewTreeObserver.addOnGlobalLayoutListener {
            if (periodsView.width > 0) periodAdapter.coverSize = periodsView.width
        }

        levelsControl.items = listOf(getString(R.string.level_years), getString(R.string.level_months), getString(R.string.level_all))
        levelsControl.setSelection(level.ordinal)
        levelsControl.onSelected = { setLevel(Level.entries[it], animate = true) }

        tabBar.tabs = listOf(
            GlassTabBar.Tab(getDrawable(R.drawable.ic_tab_library)!!, getString(R.string.library)),
            GlassTabBar.Tab(getDrawable(R.drawable.ic_tab_collections)!!, getString(R.string.collections)),
        )
        tabBar.onSelected = { setTab(it) }
        collections.onOpen = { entry ->
            startActivity(albumIntent(this, entry.title, entry.filter, entry.albumId))
        }

        findViewById<View>(R.id.camera_button).setOnClickListener { finish() }
        filterButton.setOnClickListener { openFilterMenu() }
        menuDismiss.setOnClickListener { closeFilterMenu() }
        findViewById<View>(R.id.select_button).setOnClickListener {
            if (selection == null) enterSelection() else exitSelection()
        }
        findViewById<View>(R.id.share_selection).setOnClickListener {
            selectedItems().takeIf { it.isNotEmpty() }?.let { MediaActions.share(this, it) }
        }
        findViewById<View>(R.id.delete_selection).setOnClickListener { deleteSelection() }
        permissionBanner.setOnClickListener { requestPermissions(storagePermissions(), REQUEST_STORAGE) }

        val header = findViewById<View>(R.id.header)
        val scrim = findViewById<View>(R.id.top_scrim)
        root.onSystemBars { bars ->
            header.setPadding(header.paddingLeft, bars.top + 8.dpi, header.paddingRight, header.paddingBottom)
            scrim.layoutParams.height = bars.top + 140.dpi
            scrim.requestLayout()
            (permissionBanner.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + 88.dpi
            (filterMenu.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top + 60.dpi
            (bottomBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + 14.dpi
            (levelsControl.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + 84.dpi
            levelsControl.requestLayout()
            (selectionBar.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + 14.dpi
            bottomBar.requestLayout()
            selectionBar.requestLayout()
            // The album view has no bars at the bottom.
            val bottom = bars.bottom + if (albumMode) 12.dpi else 134.dpi
            grid.setPadding(0, bars.top + 84.dpi, 0, bottom)
            periodsView.setPadding(periodsView.paddingLeft, bars.top + 84.dpi, periodsView.paddingRight, bottom)
            collections.setInsets(bars.top + 72.dpi, bars.bottom + 90.dpi)
        }

        setLevel(level, animate = false)
        tabBar.setSelection(tab)
        setTab(tab)
        if (!hasStoragePermission()) requestPermissions(storagePermissions(), REQUEST_STORAGE)
    }

    override fun onResume() {
        super.onResume()
        // Items may have been deleted or favorited in the viewer.
        reload()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_FILTER, filter.ordinal)
        outState.putInt(STATE_COLUMNS, columns)
        outState.putInt(STATE_LEVEL, level.ordinal)
        outState.putInt(STATE_TAB, tab)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            filterMenu.visibility == View.VISIBLE -> closeFilterMenu()
            selection != null -> exitSelection()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == MediaActions.REQUEST_DELETE && resultCode == RESULT_OK) {
            exitSelection()
            reload()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) reload()
    }

    private fun reload() {
        permissionBanner.setVisible(!hasStoragePermission())
        if (tab == TAB_COLLECTIONS) {
            reloadCollections()
            return
        }
        val token = ++loadToken
        val requested = filter
        val album = albumId
        Background.run({ repository.query(requested, album) }) { items ->
            if (token != loadToken) return@run
            val firstLoad = !loadedOnce
            adapter.items = items
            selection?.retainAll(items.mapTo(HashSet()) { it.id })
            selection?.let { adapter.selection = it.toSet() }
            updateSelectionCount()
            if (level != Level.ALL) periodAdapter.submit(items, level.periodLevel)
            empty.setVisible(items.isEmpty())
            // Open at the most recent items, at the bottom.
            if (firstLoad && items.isNotEmpty()) {
                grid.setSelection(items.lastIndex)
                if (periodAdapter.count > 0) periodsView.setSelection(periodAdapter.count - 1)
            }
            loadedOnce = true
            grid.post { updateRange() }
        }
    }

    private fun reloadCollections() {
        val token = ++loadToken
        Background.run({
            val items = repository.query(MediaFilter.ALL)
            items to repository.albums(items)
        }) { (items, albums) ->
            if (token != loadToken) return@run
            collections.bind(items, albums)
        }
    }

    // --- Tabs -----------------------------------------------------------------------------------

    private fun setTab(index: Int) {
        val changed = index != tab
        tab = index
        val library = index == TAB_LIBRARY
        if (!library && selection != null) exitSelection()
        if (!albumMode) title.setText(if (library) R.string.library else R.string.collections)
        subtitle.setVisible(library)
        filterButton.setVisible(library && !albumMode)
        levelsControl.setVisible(library && !albumMode)
        findViewById<View>(R.id.select_button).setVisible(library && level == Level.ALL)
        empty.setVisible(false)
        if (library) {
            collections.setVisible(false)
            setLevel(level, animate = false)
        } else {
            grid.setVisible(false)
            periodsView.setVisible(false)
            collections.setVisible(true)
        }
        if (changed) {
            val incoming = if (library) (if (level == Level.ALL) grid else periodsView) else collections
            incoming.alpha = 0f
            incoming.animate().alpha(1f).setDuration(200).start()
            loadedOnce = false
            reload()
        }
    }

    // --- Levels ---------------------------------------------------------------------------------

    private val Level.periodLevel
        get() = if (this == Level.YEARS) PeriodAdapter.Level.YEARS else PeriodAdapter.Level.MONTHS

    private fun setLevel(newLevel: Level, animate: Boolean) {
        val changed = newLevel != level
        level = newLevel
        if (newLevel != Level.ALL) {
            periodAdapter.submit(adapter.items, newLevel.periodLevel)
            if (periodAdapter.count > 0) periodsView.setSelection(periodAdapter.count - 1)
        }
        val showGrid = newLevel == Level.ALL
        val incoming = if (showGrid) grid else periodsView
        val outgoing = if (showGrid) periodsView else grid
        outgoing.setVisible(false)
        incoming.setVisible(true)
        if (animate && changed) {
            incoming.alpha = 0f
            incoming.scaleX = if (showGrid) 1.04f else 0.96f
            incoming.scaleY = incoming.scaleX
            incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start()
        }
        // Selecting only makes sense on the full grid.
        findViewById<View>(R.id.select_button).setVisible(showGrid)
        incoming.post { updateRange() }
    }

    // Subtitle: the dates on screen

    private val rangeListener = object : AbsListView.OnScrollListener {
        override fun onScrollStateChanged(view: AbsListView, scrollState: Int) = Unit
        override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) = updateRange()
    }

    private fun updateRange() {
        val items = adapter.items
        if (items.isEmpty()) {
            subtitle.text = resources.getQuantityString(R.plurals.items_count, 0, 0)
            return
        }
        val (start, end) = if (level == Level.ALL) {
            val first = grid.firstVisiblePosition.coerceIn(0, items.lastIndex)
            val last = grid.lastVisiblePosition.coerceIn(first, items.lastIndex)
            items[first].date to items[last].date
        } else {
            val periods = periodAdapter.periods
            if (periods.isEmpty()) return
            val first = periodsView.firstVisiblePosition.coerceIn(0, periods.lastIndex)
            val last = periodsView.lastVisiblePosition.coerceIn(first, periods.lastIndex)
            val lastItem = periods.getOrNull(last + 1)?.first?.minus(1) ?: items.lastIndex
            periods[first].start to items[lastItem].date
        }
        subtitle.text = if (level == Level.YEARS) yearRange(start, end) else dateRange(start, end)
    }

    private fun yearRange(start: Long, end: Long): String {
        val a = yearOf(start)
        val b = yearOf(end)
        return if (a == b) "$a" else "$a – $b"
    }

    /** "4–11 sept. 2026", "28 août – 3 sept. 2026" or "août 2025 – sept. 2026". */
    private fun dateRange(start: Long, end: Long): String {
        val locale = Locale.getDefault()
        fun format(skeleton: String, time: Long) =
            SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(time)
        val a = Calendar.getInstance().apply { timeInMillis = start }
        val b = Calendar.getInstance().apply { timeInMillis = end }
        val sameYear = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
        val sameMonth = sameYear && a.get(Calendar.MONTH) == b.get(Calendar.MONTH)
        return when {
            sameMonth && a.get(Calendar.DAY_OF_MONTH) == b.get(Calendar.DAY_OF_MONTH) -> format("dMMMyyyy", end)
            sameMonth -> "${a.get(Calendar.DAY_OF_MONTH)}–${format("dMMMyyyy", end)}"
            sameYear -> "${format("dMMM", start)} – ${format("dMMMyyyy", end)}"
            else -> "${format("MMMyyyy", start)} – ${format("MMMyyyy", end)}"
        }
    }

    private fun yearOf(time: Long) = Calendar.getInstance().apply { timeInMillis = time }.get(Calendar.YEAR)

    // Filter menu

    private fun openFilterMenu() {
        val list = findViewById<LinearLayout>(R.id.filter_items)
        list.removeAllViews()
        filters.forEach { option ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(14.dpi, 0, 18.dpi, 0)
                addView(
                    ImageView(context).apply {
                        setImageResource(R.drawable.ic_check)
                        visibility = if (option == filter) View.VISIBLE else View.INVISIBLE
                    },
                    LinearLayout.LayoutParams(18.dpi, 18.dpi).apply { marginEnd = 12.dpi },
                )
                addView(
                    TextView(context).apply {
                        setText(option.label)
                        textSize = 16f
                        setTextColor(0xFFFFFFFF.toInt())
                    },
                )
                setOnClickListener {
                    closeFilterMenu()
                    if (option != filter) {
                        filter = option
                        loadedOnce = false
                        reload()
                    }
                }
            }
            list.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 46.dpi))
        }
        menuDismiss.setVisible(true)
        filterMenu.setVisible(true)
        filterMenu.pivotX = filterMenu.width.toFloat().takeIf { it > 0 } ?: 230f.dpi.toFloat()
        filterMenu.pivotY = 0f
        filterMenu.alpha = 0f
        filterMenu.scaleX = 0.6f
        filterMenu.scaleY = 0.6f
        filterMenu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(260)
            .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        filterMenu.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun closeFilterMenu() {
        menuDismiss.setVisible(false)
        filterMenu.animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(150)
            .withEndAction { filterMenu.setVisible(false) }.start()
    }

    // --- Selection ------------------------------------------------------------------------------

    private fun enterSelection() {
        selection = mutableSetOf()
        adapter.selection = emptySet()
        grid.swipeSelects = true
        selectLabel.setText(R.string.cancel)
        filterButton.setVisible(false)
        bottomBar.setVisible(false)
        levelsControl.setVisible(false)
        selectionBar.setVisible(true)
        updateSelectionCount()
    }

    private fun exitSelection() {
        selection = null
        adapter.selection = null
        grid.swipeSelects = false
        selectLabel.setText(R.string.select)
        filterButton.setVisible(!albumMode)
        bottomBar.setVisible(!albumMode)
        levelsControl.setVisible(!albumMode)
        selectionBar.setVisible(false)
    }

    /** Items between [anchor] and [current] take the slide's state; the others go back to theirs. */
    private fun applyDragSelection(anchor: Int, current: Int) {
        val selected = selection ?: return
        val items = adapter.items
        selected.clear()
        selected.addAll(dragBaseline)
        for (i in minOf(anchor, current)..maxOf(anchor, current)) {
            val id = items.getOrNull(i)?.id ?: continue
            if (dragSelects) selected.add(id) else selected.remove(id)
        }
        if (anchor != current) Haptics.tick(grid)
        adapter.selection = selected.toSet()
        updateSelectionCount()
    }

    private fun updateSelectionCount() {
        val count = selection?.size ?: return
        selectionCount.text = if (count == 0) {
            getString(R.string.select_items)
        } else {
            resources.getQuantityString(R.plurals.selected_count, count, count)
        }
        val enabled = count > 0
        listOf(R.id.share_selection, R.id.delete_selection).forEach {
            findViewById<View>(it).apply {
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.4f
            }
        }
    }

    private fun selectedItems(): List<MediaItem> {
        val ids = selection ?: return emptyList()
        return adapter.items.filter { it.id in ids }
    }

    private fun deleteSelection() {
        val items = selectedItems()
        if (items.isEmpty()) return
        // Before Android 11 the items are deleted at once; from 11 the system asks first.
        if (!MediaActions.delete(this, items)) {
            exitSelection()
            reload()
        }
    }

    // --- Grid -----------------------------------------------------------------------------------

    private fun changeColumns(step: Int) {
        val index = (COLUMN_CHOICES.indexOf(columns) + step).coerceIn(0, COLUMN_CHOICES.lastIndex)
        val newColumns = COLUMN_CHOICES[index]
        if (newColumns == columns) return
        // Keep the item under the middle of the screen in place.
        val anchor = grid.pointToPosition(grid.width / 2, grid.height / 2).takeIf { it >= 0 }
            ?: grid.firstVisiblePosition
        columns = newColumns
        grid.alpha = 0.4f
        grid.numColumns = newColumns
        grid.setSelection(anchor)
        grid.animate().alpha(1f).setDuration(180).start()
        grid.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun updateThumbnailSize() {
        if (grid.width == 0) return
        adapter.thumbnailSize = (grid.width / columns).coerceAtLeast(64)
    }

    private fun hasStoragePermission() = storagePermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun storagePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_FILTER = "filter"
        private const val EXTRA_ALBUM = "album"
        private const val NO_ALBUM = Long.MIN_VALUE

        /** The items of one album, or of [filter] across the library when [albumId] is null. */
        fun albumIntent(context: Context, title: String, filter: MediaFilter, albumId: Long?): Intent =
            Intent(context, GalleryActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_FILTER, filter.ordinal)
                .putExtra(EXTRA_ALBUM, albumId ?: NO_ALBUM)

        private const val TAB_LIBRARY = 0
        private const val TAB_COLLECTIONS = 1
        private const val REQUEST_STORAGE = 2
        private const val DEFAULT_COLUMNS = 5
        private val COLUMN_CHOICES = listOf(1, 3, 5, 7)
        private const val STATE_FILTER = "filter"
        private const val STATE_COLUMNS = "columns"
        private const val STATE_LEVEL = "level"
        private const val STATE_TAB = "tab"
    }
}
