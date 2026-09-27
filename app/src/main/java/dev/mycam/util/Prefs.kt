package dev.mycam.util

import android.content.Context
import android.content.SharedPreferences
import dev.mycam.camera.AspectRatio
import dev.mycam.camera.CaptureMode
import dev.mycam.camera.FlashMode
import dev.mycam.camera.TimerDelay
import dev.mycam.camera.VideoQuality

/** Persistent user choices. Kept deliberately tiny: one getter/setter per setting. */
class Prefs(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("mycam", Context.MODE_PRIVATE)

    var flash: FlashMode
        get() = enumValue(KEY_FLASH, FlashMode.OFF)
        set(value) = sp.edit().putString(KEY_FLASH, value.name).apply()

    var aspect: AspectRatio
        get() = enumValue(KEY_ASPECT, AspectRatio.RATIO_4_3)
        set(value) = sp.edit().putString(KEY_ASPECT, value.name).apply()

    var timer: TimerDelay
        get() = enumValue(KEY_TIMER, TimerDelay.OFF)
        set(value) = sp.edit().putString(KEY_TIMER, value.name).apply()

    var mode: CaptureMode
        get() = enumValue(KEY_MODE, CaptureMode.PHOTO)
        set(value) = sp.edit().putString(KEY_MODE, value.name).apply()

    var videoQuality: VideoQuality
        get() = enumValue(KEY_VIDEO_QUALITY, VideoQuality.FULL_HD)
        set(value) = sp.edit().putString(KEY_VIDEO_QUALITY, value.name).apply()

    var grid: Boolean
        get() = sp.getBoolean(KEY_GRID, false)
        set(value) = sp.edit().putBoolean(KEY_GRID, value).apply()

    var shutterSound: Boolean
        get() = sp.getBoolean(KEY_SOUND, true)
        set(value) = sp.edit().putBoolean(KEY_SOUND, value).apply()

    var faceDetection: Boolean
        get() = sp.getBoolean(KEY_FACES, true)
        set(value) = sp.edit().putBoolean(KEY_FACES, value).apply()

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T =
        sp.getString(key, null)
            ?.let { name -> enumValues<T>().firstOrNull { it.name == name } }
            ?: default

    private companion object {
        const val KEY_FLASH = "flash"
        const val KEY_ASPECT = "aspect"
        const val KEY_TIMER = "timer"
        const val KEY_MODE = "mode"
        const val KEY_VIDEO_QUALITY = "video_quality"
        const val KEY_GRID = "grid"
        const val KEY_SOUND = "shutter_sound"
        const val KEY_FACES = "face_detection"
    }
}
