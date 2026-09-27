package dev.mycam.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.SizeF
import kotlin.math.hypot

/** Static description of one usable camera sensor. */
class Lens(
    val id: String,
    val facing: Facing,
    val characteristics: CameraCharacteristics,
    /** Horizontal field of view, expressed as a 35 mm equivalent focal length. */
    val equivalentFocal: Float,
) {
    enum class Facing { BACK, FRONT, EXTERNAL }

    val sensorOrientation: Int =
        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

    val hasFlash: Boolean =
        characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    val activeArray: Rect =
        characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)

    val afModes: IntArray =
        characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)

    val supportsContinuousAf: Boolean =
        CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in afModes

    val supportsAutoAf: Boolean = CameraMetadata.CONTROL_AF_MODE_AUTO in afModes

    val maxAfRegions: Int =
        characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0

    val maxAeRegions: Int =
        characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0

    val exposureRange: Range<Int> =
        characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)

    val exposureStep: Float =
        characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

    /** Digital zoom range of this sensor (1.0 = no crop). */
    val zoomRange: Range<Float> = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                ?.let { return@run Range(it.lower.coerceAtLeast(1f), it.upper) }
        }
        val max = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        Range(1f, max.coerceAtLeast(1f))
    }

    val isHardwareLevelLegacy: Boolean =
        characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ==
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY

    val supportsOis: Boolean =
        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            ?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true

    val supportsVideoStabilization: Boolean =
        characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true

    /** Best face detection the HAL offers (FULL, SIMPLE), or OFF when it has none. */
    val faceDetectMode: Int = characteristics.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
        ?.let { modes ->
            when {
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL in modes -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL
                CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE in modes -> CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE
                else -> null
            }
        } ?: CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF

    val supportsFaceDetection: Boolean get() = faceDetectMode != CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF

    override fun toString() = "Lens(id=$id, $facing, ${"%.1f".format(equivalentFocal)}mm)"
}

/**
 * Enumerates the cameras exposed by the device and sorts them into a main wide lens, an
 * optional ultra-wide ("0.5x") and a front camera.
 *
 * Every call to the camera service is guarded: some devices (for example with a failing
 * sensor) throw from [CameraManager.getCameraCharacteristics]; such cameras are skipped.
 */
class CameraInventory(manager: CameraManager) {

    val backMain: Lens?
    val backUltraWide: Lens?
    val front: Lens?

    /** Every front lens, [front] first. Some phones expose two. */
    val fronts: List<Lens>

    /** Zoom factor (as shown to the user) of the ultra-wide lens relative to the main one. */
    val ultraWideFactor: Float

    init {
        val lenses = readLenses(manager)
        val backs = lenses.filter { it.facing == Lens.Facing.BACK }

        // The first back camera reported by the HAL is, by convention, the main one.
        backMain = backs.firstOrNull() ?: lenses.firstOrNull { it.facing == Lens.Facing.EXTERNAL }
        backUltraWide = backMain?.let { main ->
            backs.filter { it !== main && it.equivalentFocal < main.equivalentFocal * 0.8f }
                .minByOrNull { it.equivalentFocal }
        }
        fronts = lenses.filter { it.facing == Lens.Facing.FRONT }
        front = fronts.firstOrNull()

        ultraWideFactor = if (backMain != null && backUltraWide != null) {
            (backUltraWide.equivalentFocal / backMain.equivalentFocal).coerceIn(0.3f, 0.8f)
        } else 1f

        Log.i(TAG, "main=$backMain ultraWide=$backUltraWide (×$ultraWideFactor) front=$front")
    }

    fun byId(id: String): Lens? = (listOfNotNull(backMain, backUltraWide) + fronts).firstOrNull { it.id == id }

    private fun readLenses(manager: CameraManager): List<Lens> {
        val ids = try {
            manager.cameraIdList.toList()
        } catch (e: Exception) {
            Log.e(TAG, "Unable to list cameras", e)
            emptyList()
        }
        return ids.mapNotNull { id ->
            try {
                val chars = manager.getCameraCharacteristics(id)
                val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
                val backwardCompatible =
                    CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE in caps
                if (!backwardCompatible) return@mapNotNull null

                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraMetadata.LENS_FACING_BACK -> Lens.Facing.BACK
                    CameraMetadata.LENS_FACING_FRONT -> Lens.Facing.FRONT
                    else -> Lens.Facing.EXTERNAL
                }
                Lens(id, facing, chars, equivalentFocal(chars))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping camera $id", e)
                null
            }
        }
    }

    private fun equivalentFocal(chars: CameraCharacteristics): Float {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            ?: return 26f
        val size: SizeF = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 26f
        val diagonal = hypot(size.width, size.height)
        if (diagonal <= 0f) return 26f
        // 43.27 mm is the diagonal of a full-frame (36 x 24 mm) sensor.
        return focal * 43.27f / diagonal
    }

    private companion object {
        const val TAG = "CameraInventory"
    }
}
