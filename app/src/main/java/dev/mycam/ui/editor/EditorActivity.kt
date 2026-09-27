package dev.mycam.ui.editor

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.mycam.R
import dev.mycam.imaging.Adjustment
import dev.mycam.imaging.EditRenderer
import dev.mycam.imaging.PhotoEdits
import dev.mycam.imaging.PhotoFilter
import dev.mycam.media.ImageLoader
import dev.mycam.media.MediaSaver
import dev.mycam.ui.glass.GlassSegmentedControl
import dev.mycam.ui.glass.GlassToast
import dev.mycam.util.Background
import dev.mycam.util.Haptics
import dev.mycam.util.dp
import dev.mycam.util.dpi
import dev.mycam.util.goEdgeToEdge
import dev.mycam.util.onSystemBars
import dev.mycam.util.setVisible
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * The photo editor: Adjust (Auto and thirteen light and color controls on a wheel with a
 * ruler), Filters, and Crop (straighten, rotate, flip, aspect). Every change is kept in
 * [PhotoEdits] and rendered live on a screen-sized copy; Done renders the full-resolution photo
 * and saves it as a new picture, so the original is never lost.
 */
class EditorActivity : Activity() {

    private enum class Tab { ADJUST, FILTERS, CROP }

    private lateinit var preview: EditPreviewView
    private lateinit var progress: ProgressBar
    private lateinit var toolLabel: TextView
    private lateinit var cancelLabel: TextView
    private lateinit var resetButton: View
    private lateinit var wheel: AdjustWheelView
    private lateinit var adjustRuler: RulerView
    private lateinit var filterScroll: HorizontalScrollView
    private lateinit var filterStrip: LinearLayout
    private lateinit var filterRuler: RulerView
    private lateinit var straightenRuler: RulerView
    private lateinit var aspectStrip: LinearLayout
    private lateinit var panels: Map<Tab, View>
    private lateinit var toast: GlassToast

    private val edits = PhotoEdits()
    private var tab = Tab.ADJUST
    private var source: Bitmap? = null
    /** Adjustments Auto sets, computed once from the picture. */
    private var autoValues: Map<Adjustment, Float>? = null
    private var aspectIndex = 0
    private var confirmingDiscard = false
    private var saving = false

    // Rendering: one frame at a time on its own thread; changes made meanwhile are rendered next.
    private val renderer = Executors.newSingleThreadExecutor { r -> Thread(r, "MyCam-preview") }
    private var rendering = false
    private var renderAgain = false
    // Owned by the render thread.
    private var base: Bitmap? = null
    private var baseKey: GeometryKey? = null
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var bufferIndex = 0
    private var filterThumbsKey: Bundle? = null

    @Suppress("DEPRECATION")
    private val uri: Uri by lazy { intent.getParcelableExtra<Uri>(EXTRA_URI)!! }
    private val itemDate: Long by lazy { intent.getLongExtra(EXTRA_DATE, System.currentTimeMillis()) }

    private data class GeometryKey(
        val turns: Int,
        val flipped: Boolean,
        val straighten: Float,
        val cropped: Boolean,
        val crop: RectF?,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goEdgeToEdge()
        setContentView(R.layout.activity_editor)
        savedInstanceState?.getBundle(STATE_EDITS)?.let { edits.restore(it) }

        preview = findViewById(R.id.preview)
        progress = findViewById(R.id.progress)
        toolLabel = findViewById(R.id.tool_label)
        cancelLabel = findViewById(R.id.cancel_label)
        resetButton = findViewById(R.id.reset_button)
        wheel = findViewById(R.id.wheel)
        adjustRuler = findViewById(R.id.adjust_ruler)
        filterScroll = findViewById(R.id.filter_scroll)
        filterStrip = findViewById(R.id.filter_strip)
        filterRuler = findViewById(R.id.filter_ruler)
        straightenRuler = findViewById(R.id.straighten_ruler)
        aspectStrip = findViewById(R.id.aspect_strip)
        panels = mapOf(
            Tab.ADJUST to findViewById(R.id.adjust_panel),
            Tab.FILTERS to findViewById(R.id.filter_panel),
            Tab.CROP to findViewById(R.id.crop_panel),
        )
        val root = findViewById<FrameLayout>(R.id.root)
        toast = GlassToast(root)

        layOut(root)
        setUpTopBar()
        setUpAdjust()
        setUpFilters()
        setUpCrop()

        val tabs = findViewById<GlassSegmentedControl>(R.id.tabs)
        tabs.items = listOf(getString(R.string.tab_adjust), getString(R.string.tab_filters), getString(R.string.tab_crop))
        tabs.onSelected = { showTab(Tab.entries[it]) }
        val startTab = Tab.entries[savedInstanceState?.getInt(STATE_TAB) ?: 0]
        tabs.setSelection(startTab.ordinal)
        showTab(startTab)

        preview.onCropChanged = {
            edits.crop.set(preview.crop)
            refreshTopBar()
        }
        load()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBundle(STATE_EDITS, edits.toBundle())
        outState.putInt(STATE_TAB, tab.ordinal)
    }

    override fun onDestroy() {
        renderer.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!saving) cancel()
    }

    private fun layOut(root: View) {
        val topBar = findViewById<View>(R.id.top_bar)
        val panel = findViewById<View>(R.id.panel)
        var top = 0
        fun fitPreview() {
            val lp = preview.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = top + topBar.layoutParams.height
            lp.bottomMargin = root.height - panel.top
            preview.requestLayout()
        }
        root.onSystemBars { bars ->
            top = bars.top
            toast.topOffset = bars.top + 64.dpi
            (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top
            (panel.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + 12.dpi
            topBar.requestLayout()
            panel.requestLayout()
        }
        panel.addOnLayoutChangeListener { _, _, t, _, _, _, oldTop, _, _ ->
            if (t != oldTop) panel.post { fitPreview() }
        }
    }

    private fun load() {
        Background.run({ ImageLoader.get(this).decodeSampled(uri, PREVIEW_SIDE) }) { bitmap ->
            if (isDestroyed) return@run
            if (bitmap == null) {
                toast.show(getString(R.string.edit_failed))
                preview.postDelayed({ finish() }, 1_200)
                return@run
            }
            source = bitmap
            preview.original = bitmap
            preview.crop.set(edits.crop)
            refreshControls()
            render()
        }
    }

    // --- Top bar --------------------------------------------------------------------------------

    private fun setUpTopBar() {
        findViewById<View>(R.id.cancel_button).setOnClickListener { cancel() }
        resetButton.setOnClickListener {
            Haptics.tap(it)
            edits.resetColor()
            edits.resetGeometry()
            aspectIndex = 0
            preview.aspect = null
            preview.crop.set(edits.crop)
            refreshControls()
            render()
        }
        findViewById<View>(R.id.done_button).setOnClickListener { done() }
    }

    private val endDiscard = Runnable {
        confirmingDiscard = false
        cancelLabel.setText(R.string.cancel)
        cancelLabel.setTextColor(getColor(R.color.white))
    }

    private fun cancel() {
        if (!edits.hasChanges || confirmingDiscard) {
            finish()
            return
        }
        // Edits are only thrown away after a second tap on the red label.
        Haptics.tick(cancelLabel)
        confirmingDiscard = true
        cancelLabel.setText(R.string.discard)
        cancelLabel.setTextColor(DISCARD_RED)
        cancelLabel.removeCallbacks(endDiscard)
        cancelLabel.postDelayed(endDiscard, 3_000)
    }

    private fun refreshTopBar() {
        resetButton.visibility = if (edits.hasChanges) View.VISIBLE else View.INVISIBLE
        if (!edits.hasChanges && confirmingDiscard) endDiscard.run()
    }

    // --- Adjust ---------------------------------------------------------------------------------

    private fun setUpAdjust() {
        wheel.items = buildList {
            add(AdjustWheelView.Item(getDrawable(R.drawable.ic_auto)!!))
            ADJUSTMENTS.forEach { (_, icon, _) -> add(AdjustWheelView.Item(getDrawable(icon)!!)) }
        }
        wheel.onSelected = { refreshAdjustTool() }
        wheel.onSelectedTapped = { index -> if (index == 0) toggleAuto() }
        adjustRuler.onValueChanged = { value, fromUser ->
            val adjustment = selectedAdjustment()
            if (fromUser && adjustment != null) {
                edits[adjustment] = value / 100f
                wheel.items[wheel.selectedIndex].value = edits[adjustment]
                wheel.items[wheel.selectedIndex].active = edits[adjustment] != 0f
                wheel.invalidate()
                refreshTopBar()
                render()
            }
        }
    }

    private fun selectedAdjustment(): Adjustment? = ADJUSTMENTS.getOrNull(wheel.selectedIndex - 1)?.first

    private fun toggleAuto() {
        val bitmap = source ?: return
        Haptics.tap(wheel)
        val values = autoValues ?: EditRenderer.autoValues(bitmap).also { autoValues = it }
        edits.auto = !edits.auto
        values.forEach { (adjustment, value) -> edits[adjustment] = if (edits.auto) value else 0f }
        refreshControls()
        render()
    }

    private fun refreshAdjustTool() {
        if (tab != Tab.ADJUST) return
        val adjustment = selectedAdjustment()
        if (adjustment == null) {
            toolLabel.setText(R.string.adj_auto)
            adjustRuler.visibility = View.INVISIBLE
            return
        }
        toolLabel.setText(ADJUSTMENTS[wheel.selectedIndex - 1].third)
        adjustRuler.visibility = View.VISIBLE
        adjustRuler.min = if (adjustment == Adjustment.SHARPNESS) 0f else -100f
        adjustRuler.setValue(edits[adjustment] * 100f)
        adjustRuler.invalidate()
    }

    // --- Filters --------------------------------------------------------------------------------

    private fun setUpFilters() {
        filterRuler.min = 0f
        filterRuler.max = 100f
        filterRuler.neutral = 100f
        filterRuler.onValueChanged = { value, fromUser ->
            if (fromUser) {
                edits.filterIntensity = value / 100f
                render()
            }
        }
        PhotoFilter.entries.forEach { filter ->
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = 8.dp
                    setColor(0xFF1C1C1E.toInt())
                }
                clipToOutline = true
                setOnClickListener { selectFilter(filter) }
            }
            val frame = FrameLayout(this).apply {
                setPadding(2.dpi, 2.dpi, 2.dpi, 2.dpi)
                addView(thumb, FrameLayout.LayoutParams(FILTER_THUMB.dpi, FILTER_THUMB.dpi))
            }
            filterStrip.addView(
                frame,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = 6.dpi },
            )
        }
    }

    private fun selectFilter(filter: PhotoFilter) {
        if (filter == edits.filter) return
        Haptics.tick(filterStrip)
        edits.filter = filter
        edits.filterIntensity = 1f
        refreshFilterTool()
        refreshTopBar()
        render()
    }

    private fun refreshFilterTool() {
        filterStrip.children().forEachIndexed { i, frame ->
            val selected = PhotoFilter.entries[i] == edits.filter
            frame.background = if (selected) {
                GradientDrawable().apply {
                    cornerRadius = 10.dp
                    setStroke(2.dpi, 0xFFFFFFFF.toInt())
                }
            } else {
                null
            }
        }
        if (tab != Tab.FILTERS) return
        toolLabel.setText(FILTER_NAMES[edits.filter.ordinal])
        filterRuler.visibility = if (edits.filter == PhotoFilter.ORIGINAL) View.INVISIBLE else View.VISIBLE
        filterRuler.setValue(edits.filterIntensity * 100f)
        // Bring the selected filter to the middle of the strip.
        filterStrip.post {
            val frame = filterStrip.getChildAt(edits.filter.ordinal) ?: return@post
            filterScroll.smoothScrollTo(frame.left + frame.width / 2 - filterScroll.width / 2, 0)
        }
    }

    /** Renders each filter on a small copy of the edited picture for the filter strip. */
    private fun renderFilterThumbnails() {
        val bitmap = source ?: return
        val snapshot = edits.copy().apply {
            filter = PhotoFilter.ORIGINAL
            filterIntensity = 1f
        }
        val key = snapshot.toBundle()
        if (filterThumbsKey?.let { sameEdits(it, key) } == true) return
        filterThumbsKey = key
        val size = (FILTER_THUMB * 2).dpi
        renderer.execute {
            val small = EditRenderer.applyGeometry(bitmap, snapshot, cropped = true, maxSide = size)
            EditRenderer.applyColor(small, snapshot)
            PhotoFilter.entries.forEachIndexed { i, filter ->
                val thumb = small.copy(Bitmap.Config.ARGB_8888, true)
                EditRenderer.applyColor(thumb, PhotoEdits().also { it.filter = filter })
                runOnUiThread {
                    val frame = filterStrip.getChildAt(i) as? FrameLayout ?: return@runOnUiThread
                    (frame.getChildAt(0) as ImageView).setImageBitmap(thumb)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun sameEdits(a: Bundle, b: Bundle): Boolean =
        a.keySet() == b.keySet() && a.keySet().all { k ->
            val x = a.get(k)
            val y = b.get(k)
            if (x is FloatArray && y is FloatArray) x.contentEquals(y) else x == y
        }

    // --- Crop -----------------------------------------------------------------------------------

    private fun setUpCrop() {
        straightenRuler.min = -45f
        straightenRuler.max = 45f
        straightenRuler.tickStep = 1f
        straightenRuler.onValueChanged = { value, fromUser ->
            if (fromUser) {
                edits.straighten = (value * 10f).roundToInt() / 10f
                refreshTopBar()
                render()
            }
        }
        findViewById<View>(R.id.rotate_button).setOnClickListener {
            Haptics.tap(it)
            // Quarter turns apply before the flip: a flipped picture turns the other way.
            edits.quarterTurns = (edits.quarterTurns + if (edits.flipped) 3 else 1) % 4
            val c = RectF(edits.crop)
            edits.crop.set(1f - c.bottom, c.left, 1f - c.top, c.right)
            preview.crop.set(edits.crop)
            preview.aspect = preview.aspect?.let { 1f / it }
            refreshTopBar()
            render()
        }
        findViewById<View>(R.id.flip_button).setOnClickListener {
            Haptics.tap(it)
            edits.flipped = !edits.flipped
            val c = RectF(edits.crop)
            edits.crop.set(1f - c.right, c.top, 1f - c.left, c.bottom)
            preview.crop.set(edits.crop)
            refreshTopBar()
            render()
        }
        ASPECTS.forEachIndexed { i, (label, _) ->
            val chip = TextView(this).apply {
                text = if (label != 0) getString(label) else ASPECT_TEXTS[i]
                textSize = 12f
                isAllCaps = true
                letterSpacing = 0.04f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER
                setPadding(12.dpi, 0, 12.dpi, 0)
                setBackgroundResource(R.drawable.chip)
                setOnClickListener { selectAspect(i) }
            }
            aspectStrip.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, 28.dpi))
        }
    }

    private fun selectAspect(index: Int) {
        val bitmap = source ?: return
        Haptics.tick(aspectStrip)
        aspectIndex = index
        val (fw, fh) = EditRenderer.frameSize(bitmap.width, bitmap.height, edits)
        val frameRatio = fw.toFloat() / fh
        val ratio = ASPECTS[index].second.let { r ->
            when {
                r == null -> null
                r == ORIGINAL -> frameRatio
                // Chips follow the picture's orientation.
                fw < fh -> 1f / r
                else -> r
            }
        }
        preview.aspect = ratio
        if (ratio != null) {
            var w = 1f
            var h = frameRatio / ratio
            if (h > 1f) {
                w = 1f / h
                h = 1f
            }
            edits.crop.set(0.5f - w / 2, 0.5f - h / 2, 0.5f + w / 2, 0.5f + h / 2)
            preview.crop.set(edits.crop)
            preview.invalidate()
        }
        refreshCropTool()
        refreshTopBar()
    }

    private fun refreshCropTool() {
        aspectStrip.children().forEachIndexed { i, chip ->
            chip.isSelected = i == aspectIndex
            (chip as TextView).setTextColor(if (i == aspectIndex) 0xFFFFFFFF.toInt() else 0x99FFFFFF.toInt())
        }
        straightenRuler.setValue(edits.straighten)
        if (tab == Tab.CROP) toolLabel.setText(R.string.straighten)
    }

    // --- Tabs and state -------------------------------------------------------------------------

    private fun showTab(next: Tab) {
        val wasCrop = tab == Tab.CROP
        tab = next
        panels.forEach { (t, panel) -> panel.setVisible(t == next) }
        preview.cropMode = next == Tab.CROP
        when (next) {
            Tab.ADJUST -> refreshAdjustTool()
            Tab.FILTERS -> {
                refreshFilterTool()
                renderFilterThumbnails()
            }
            Tab.CROP -> refreshCropTool()
        }
        if (wasCrop != (next == Tab.CROP)) render()
    }

    private fun refreshControls() {
        wheel.items.forEachIndexed { i, item ->
            if (i == 0) {
                item.active = edits.auto
            } else {
                val value = edits[ADJUSTMENTS[i - 1].first]
                item.value = value
                item.active = value != 0f
            }
        }
        wheel.invalidate()
        refreshAdjustTool()
        refreshFilterTool()
        refreshCropTool()
        refreshTopBar()
    }

    // --- Rendering ------------------------------------------------------------------------------

    private fun render() {
        val bitmap = source ?: return
        if (rendering) {
            renderAgain = true
            return
        }
        rendering = true
        val snapshot = edits.copy()
        val cropped = tab != Tab.CROP
        Background.run(renderer, { draw(bitmap, snapshot, cropped) }) { frame ->
            rendering = false
            if (isDestroyed) return@run
            progress.setVisible(false)
            preview.image = frame
            if (renderAgain) {
                renderAgain = false
                render()
            }
        }
    }

    /** Runs on [renderer]. Reuses the geometry while only colors change. */
    private fun draw(bitmap: Bitmap, snapshot: PhotoEdits, cropped: Boolean): Bitmap {
        val key = GeometryKey(
            snapshot.quarterTurns % 4,
            snapshot.flipped,
            snapshot.straighten,
            cropped,
            if (cropped) RectF(snapshot.crop) else null,
        )
        val geometry = base?.takeIf { key == baseKey }
            ?: EditRenderer.applyGeometry(bitmap, snapshot, cropped).also {
                base = it
                baseKey = key
            }
        // Two buffers: the one on screen is never the one being drawn.
        bufferIndex = 1 - bufferIndex
        val out = buffers[bufferIndex]
            ?.takeIf { it.width == geometry.width && it.height == geometry.height }
            ?: Bitmap.createBitmap(geometry.width, geometry.height, Bitmap.Config.ARGB_8888)
                .also { buffers[bufferIndex] = it }
        Canvas(out).drawBitmap(geometry, 0f, 0f, null)
        EditRenderer.applyColor(out, snapshot)
        return out
    }

    // --- Saving ---------------------------------------------------------------------------------

    private fun done() {
        if (saving) return
        if (!edits.hasChanges) {
            finish()
            return
        }
        saving = true
        Haptics.tap(preview)
        showSavingOverlay()
        val snapshot = edits.copy()
        Background.run(Background.compute, { save(snapshot) }) { savedId ->
            if (isDestroyed) return@run
            if (savedId == null) {
                saving = false
                hideSavingOverlay()
                toast.show(getString(R.string.edit_failed))
                return@run
            }
            setResult(RESULT_OK, Intent().putExtra(EXTRA_ITEM_ID, savedId))
            finish()
        }
    }

    /** Renders the full-resolution photo and saves it as a new picture. Returns its id. */
    private fun save(snapshot: PhotoEdits): Long? = try {
        val original = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        val full = ImageLoader.get(this).decodeSampled(uri, SAVE_SIDE) ?: error("Decode failed")
        val out = EditRenderer.applyGeometry(full, snapshot, cropped = true)
        full.recycle()
        EditRenderer.applyColor(out, snapshot)
        val saver = MediaSaver(this)
        val jpeg = saver.encode(out, original, upright = true)
        out.recycle()
        saver.savePhoto(jpeg, square = false, dateTaken = itemDate)?.let { ContentUris.parseId(it) }
    } catch (e: Throwable) {
        android.util.Log.e(TAG, "Save failed", e)
        null
    }

    private var savingOverlay: View? = null

    private fun showSavingOverlay() {
        val root = findViewById<FrameLayout>(R.id.root)
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xB3000000.toInt())
            isClickable = true
            addView(ProgressBar(this@EditorActivity).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(-1) })
            addView(
                TextView(this@EditorActivity).apply {
                    setText(R.string.saving)
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 15f
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = 12.dpi },
            )
            alpha = 0f
            animate().alpha(1f).setDuration(180).start()
        }
        root.addView(overlay, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        savingOverlay = overlay
    }

    private fun hideSavingOverlay() {
        savingOverlay?.let { (it.parent as? FrameLayout)?.removeView(it) }
        savingOverlay = null
    }

    private fun LinearLayout.children(): List<View> = (0 until childCount).map { getChildAt(it) }

    companion object {
        private const val TAG = "EditorActivity"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_DATE = "date"
        const val EXTRA_ITEM_ID = "item_id"
        private const val STATE_EDITS = "edits"
        private const val STATE_TAB = "tab"

        private const val PREVIEW_SIDE = 1400
        private const val SAVE_SIDE = 4096
        private const val FILTER_THUMB = 52
        private const val DISCARD_RED = 0xFFFF453A.toInt()
        private const val ORIGINAL = -1f

        private val ADJUSTMENTS = listOf(
            Triple(Adjustment.EXPOSURE, R.drawable.ic_exposure, R.string.adj_exposure),
            Triple(Adjustment.BRILLIANCE, R.drawable.ic_brilliance, R.string.adj_brilliance),
            Triple(Adjustment.HIGHLIGHTS, R.drawable.ic_highlights, R.string.adj_highlights),
            Triple(Adjustment.SHADOWS, R.drawable.ic_shadows, R.string.adj_shadows),
            Triple(Adjustment.CONTRAST, R.drawable.ic_contrast, R.string.adj_contrast),
            Triple(Adjustment.BRIGHTNESS, R.drawable.ic_brightness, R.string.adj_brightness),
            Triple(Adjustment.BLACK_POINT, R.drawable.ic_black_point, R.string.adj_black_point),
            Triple(Adjustment.SATURATION, R.drawable.ic_saturation, R.string.adj_saturation),
            Triple(Adjustment.VIBRANCE, R.drawable.ic_vibrance, R.string.adj_vibrance),
            Triple(Adjustment.WARMTH, R.drawable.ic_warmth, R.string.adj_warmth),
            Triple(Adjustment.TINT, R.drawable.ic_tint, R.string.adj_tint),
            Triple(Adjustment.SHARPNESS, R.drawable.ic_sharpness, R.string.adj_sharpness),
            Triple(Adjustment.VIGNETTE, R.drawable.ic_vignette, R.string.adj_vignette),
        )

        private val FILTER_NAMES = intArrayOf(
            R.string.flt_original, R.string.flt_vivid, R.string.flt_vivid_warm, R.string.flt_vivid_cool,
            R.string.flt_dramatic, R.string.flt_dramatic_warm, R.string.flt_dramatic_cool,
            R.string.flt_mono, R.string.flt_silvertone, R.string.flt_noir,
        )

        /** Label (0 = use [ASPECT_TEXTS]) and landscape ratio; null ratio = free. */
        private val ASPECTS = listOf(
            R.string.aspect_free to null,
            R.string.aspect_original to ORIGINAL,
            R.string.aspect_square to 1f,
            0 to 4f / 3f,
            0 to 16f / 9f,
            0 to 3f / 2f,
        )
        private val ASPECT_TEXTS = arrayOf("", "", "", "4:3", "16:9", "3:2")

        fun intent(context: Context, uri: Uri, date: Long): Intent =
            Intent(context, EditorActivity::class.java)
                .putExtra(EXTRA_URI, uri)
                .putExtra(EXTRA_DATE, date)
    }
}
