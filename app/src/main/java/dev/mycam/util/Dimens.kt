package dev.mycam.util

import android.content.Context
import android.content.res.Resources
import android.util.TypedValue
import android.view.View

/** Density-independent pixels -> pixels. */
val Number.dp: Float
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, toFloat(), Resources.getSystem().displayMetrics
    )

/** Same as [dp], rounded to an integer pixel size. */
val Number.dpi: Int
    get() = Math.round(dp)

/** Scaled pixels (font size) -> pixels. */
val Number.sp: Float
    get() = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP, toFloat(), Resources.getSystem().displayMetrics
    )

fun Context.color(resId: Int): Int = getColor(resId)

fun View.setVisible(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}
