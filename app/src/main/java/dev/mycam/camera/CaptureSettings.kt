package dev.mycam.camera

/** What the shutter button does, in the order of the mode picker. */
enum class CaptureMode {
    TIMELAPSE, VIDEO, PHOTO, PORTRAIT, PANO;

    /** Recorded to a video file through [VideoRecorder]. */
    val isVideo: Boolean get() = this == TIMELAPSE || this == VIDEO

    /** Takes JPEG stills through the still-capture pipeline. */
    val isStill: Boolean get() = this == PHOTO || this == PORTRAIT
}

enum class FlashMode { AUTO, ON, OFF }

enum class AspectRatio(val width: Int, val height: Int) {
    RATIO_4_3(4, 3),
    RATIO_16_9(16, 9),
    RATIO_1_1(1, 1);

    /** Long side / short side. */
    val value: Float get() = width.toFloat() / height

    /** Square photos are cropped from a 4:3 capture. */
    val sensorRatio: AspectRatio get() = if (this == RATIO_1_1) RATIO_4_3 else this
}

enum class TimerDelay(val seconds: Int) { OFF(0), THREE(3), TEN(10) }

enum class VideoQuality(val height: Int) { HD(720), FULL_HD(1080) }
