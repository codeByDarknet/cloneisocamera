package dev.mycam.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaRecorder
import android.util.Size
import kotlin.math.abs

/**
 * Picks stream resolutions that every camera HAL level (including LIMITED and LEGACY
 * ones) is guaranteed to accept together.
 */
object SizeSelector {

    private const val RATIO_TOLERANCE = 0.02f

    /** Highest resolution JPEG size with the requested aspect ratio. */
    fun photoSize(lens: Lens, aspect: AspectRatio): Size {
        val sizes = outputSizes(lens) { it.getOutputSizes(ImageFormat.JPEG) }
        return sizes.filter { matches(it, aspect) }.maxByOrNull { it.area }
            ?: sizes.maxByOrNull { it.area }
            ?: Size(1920, 1080)
    }

    /**
     * Preview size: same aspect as the capture, large enough for a sharp viewfinder but capped
     * at 1080p (anything above this is wasted on a phone screen and costs bandwidth).
     */
    fun previewSize(lens: Lens, aspect: AspectRatio, maxShortSide: Int = 1080): Size {
        val sizes = outputSizes(lens) { it.getOutputSizes(SurfaceTexture::class.java) }
        val candidates = sizes.filter { matches(it, aspect) && it.shortSide <= maxShortSide }
        return candidates.maxByOrNull { it.area }
            ?: sizes.filter { it.shortSide <= maxShortSide }.minByOrNull { abs(it.ratio - aspect.value) }
            ?: Size(1280, 720)
    }

    /** YUV frames for on-the-fly analysis (panorama), same aspect as the preview. */
    fun analysisSize(lens: Lens, aspect: AspectRatio, maxShortSide: Int = 960): Size {
        val sizes = outputSizes(lens) { it.getOutputSizes(ImageFormat.YUV_420_888) }
        return sizes.filter { matches(it, aspect) && it.shortSide <= maxShortSide }.maxByOrNull { it.area }
            ?: sizes.filter { it.shortSide <= maxShortSide }.maxByOrNull { it.area }
            ?: Size(640, 480)
    }

    /** Video size for the requested quality, always 16:9. */
    fun videoSize(lens: Lens, quality: VideoQuality): Size {
        val sizes = outputSizes(lens) { it.getOutputSizes(MediaRecorder::class.java) }
            .filter { matches(it, AspectRatio.RATIO_16_9) }
        return sizes.firstOrNull { it.shortSide == quality.height }
            ?: sizes.filter { it.shortSide <= quality.height }.maxByOrNull { it.area }
            ?: sizes.minByOrNull { it.area }
            ?: Size(1280, 720)
    }

    private fun outputSizes(
        lens: Lens,
        query: (android.hardware.camera2.params.StreamConfigurationMap) -> Array<Size>?,
    ): List<Size> {
        val map = lens.characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptyList()
        return query(map)?.toList().orEmpty()
    }

    private fun matches(size: Size, aspect: AspectRatio) =
        abs(size.ratio - aspect.value) < RATIO_TOLERANCE

    private val Size.area get() = width.toLong() * height
    private val Size.shortSide get() = minOf(width, height)
    private val Size.ratio get() = maxOf(width, height).toFloat() / minOf(width, height)
}
