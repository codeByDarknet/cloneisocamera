package dev.mycam.util

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager

/** System bar sizes in pixels, independent of the API level. */
data class SystemBars(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Draws the activity behind the status and navigation bars (the bars stay transparent). */
fun Activity.goEdgeToEdge() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.setDecorFitsSystemWindows(false)
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
}

fun WindowInsets.systemBars(): SystemBars =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val i = getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        SystemBars(i.left, i.top, i.right, i.bottom)
    } else {
        @Suppress("DEPRECATION")
        SystemBars(systemWindowInsetLeft, systemWindowInsetTop, systemWindowInsetRight, systemWindowInsetBottom)
    }

/** Calls [block] with the system bars now and whenever they change. */
fun View.onSystemBars(block: (SystemBars) -> Unit) {
    setOnApplyWindowInsetsListener { _, insets ->
        block(insets.systemBars())
        insets
    }
    requestApplyInsets()
}
