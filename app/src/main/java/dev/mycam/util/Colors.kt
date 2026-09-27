package dev.mycam.util

import kotlin.math.roundToInt

object Colors {
    /** Opaque linear blend between two ARGB colors. */
    fun blend(from: Int, to: Int, t: Float): Int {
        fun ch(shift: Int) =
            (((from shr shift) and 0xFF) * (1 - t) + ((to shr shift) and 0xFF) * t).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** White with the given opacity (0..1). */
    fun white(alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or 0xFFFFFF
}
