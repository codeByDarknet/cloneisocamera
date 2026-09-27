package dev.mycam.camera

import android.content.Context
import android.view.OrientationEventListener

/**
 * Tracks how the phone is physically held while the UI itself stays locked in portrait: icons
 * rotate, photos get the right orientation.
 *
 * [degrees] is one of 0, 90, 180, 270 (clockwise rotation of the device from natural portrait).
 */
class DeviceOrientation(context: Context, private val onChanged: (Int) -> Unit) {

    var degrees: Int = 0
        private set

    private val listener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            val snapped = snap(orientation, degrees)
            if (snapped != degrees) {
                degrees = snapped
                onChanged(snapped)
            }
        }
    }

    fun enable() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun disable() = listener.disable()

    /** Rotation to write into a photo/video taken with [lens] while the device is at [degrees]. */
    fun outputRotation(lens: Lens): Int {
        val sign = if (lens.facing == Lens.Facing.FRONT) -1 else 1
        return (lens.sensorOrientation + sign * degrees + 360) % 360
    }

    private companion object {
        /** Hysteresis so the icons don't flicker when the phone is held around 45°. */
        const val HYSTERESIS = 20

        fun snap(orientation: Int, current: Int): Int {
            val distance = Math.abs(orientation - current).let { minOf(it, 360 - it) }
            if (distance < 45 + HYSTERESIS) return current
            return ((orientation + 45) / 90 * 90) % 360
        }
    }
}
