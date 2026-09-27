package dev.mycam.imaging

import android.graphics.RectF
import android.os.Bundle

/** The light and color adjustments of the editor, each from -1 to 1 (0 = unchanged). */
enum class Adjustment {
    EXPOSURE, BRILLIANCE, HIGHLIGHTS, SHADOWS, CONTRAST, BRIGHTNESS, BLACK_POINT,
    SATURATION, VIBRANCE, WARMTH, TINT, SHARPNESS, VIGNETTE,
}

/** The editor filters. */
enum class PhotoFilter {
    ORIGINAL, VIVID, VIVID_WARM, VIVID_COOL, DRAMATIC, DRAMATIC_WARM, DRAMATIC_COOL, MONO, SILVERTONE, NOIR,
}

/**
 * Every change made in the editor. Nothing touches the pixels until [EditRenderer] renders it,
 * so each value can be changed again or reset at any time.
 */
class PhotoEdits {
    val values = FloatArray(Adjustment.entries.size)
    var filter = PhotoFilter.ORIGINAL
    /** Strength of [filter], 0..1. */
    var filterIntensity = 1f
    /** Whether Auto set the current values. */
    var auto = false

    /** Clockwise quarter turns applied first. */
    var quarterTurns = 0
    var flipped = false
    /** Straightening, in degrees (−45…45). */
    var straighten = 0f
    /** The kept part of the straightened frame, normalized to 0..1. */
    val crop = RectF(0f, 0f, 1f, 1f)

    operator fun get(a: Adjustment) = values[a.ordinal]
    operator fun set(a: Adjustment, v: Float) {
        values[a.ordinal] = v.coerceIn(-1f, 1f)
    }

    val hasColorChanges: Boolean get() = values.any { it != 0f } || filter != PhotoFilter.ORIGINAL
    val hasGeometryChanges: Boolean
        get() = quarterTurns % 4 != 0 || flipped || straighten != 0f || crop != FULL
    val hasChanges: Boolean get() = hasColorChanges || hasGeometryChanges

    fun resetColor() {
        values.fill(0f)
        filter = PhotoFilter.ORIGINAL
        filterIntensity = 1f
        auto = false
    }

    fun resetGeometry() {
        quarterTurns = 0
        flipped = false
        straighten = 0f
        crop.set(FULL)
    }

    fun copy(): PhotoEdits = PhotoEdits().also { it.restore(toBundle()) }

    fun toBundle() = Bundle().apply {
        putFloatArray("values", values)
        putInt("filter", filter.ordinal)
        putFloat("intensity", filterIntensity)
        putBoolean("auto", auto)
        putInt("turns", quarterTurns)
        putBoolean("flipped", flipped)
        putFloat("straighten", straighten)
        putFloatArray("crop", floatArrayOf(crop.left, crop.top, crop.right, crop.bottom))
    }

    fun restore(bundle: Bundle) {
        bundle.getFloatArray("values")?.copyInto(values)
        filter = PhotoFilter.entries[bundle.getInt("filter")]
        filterIntensity = bundle.getFloat("intensity", 1f)
        auto = bundle.getBoolean("auto")
        quarterTurns = bundle.getInt("turns")
        flipped = bundle.getBoolean("flipped")
        straighten = bundle.getFloat("straighten")
        bundle.getFloatArray("crop")?.let { crop.set(it[0], it[1], it[2], it[3]) }
    }

    private companion object {
        val FULL = RectF(0f, 0f, 1f, 1f)
    }
}
