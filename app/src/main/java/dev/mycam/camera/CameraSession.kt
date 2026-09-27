package dev.mycam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.ConditionVariable
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.FileDescriptor
import java.util.ArrayDeque
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns one [CameraDevice] and its capture session.
 *
 * All camera2 calls run on a private background thread; every [Listener] callback is delivered
 * on the main thread. Each [open] increments a generation counter so that late callbacks from a
 * previous device (very common with a failing sensor) can never corrupt the current state.
 *
 * A camera is considered healthy only once it delivered its first preview frame. If that does
 * not happen within [FIRST_FRAME_TIMEOUT_MS], or if the HAL reports an error, the listener is
 * told via [Listener.onCameraFailed] so the UI can fall back to another lens.
 */
class CameraSession(context: Context, private val listener: Listener) {

    interface Listener {
        fun onPreviewStarted(config: Config)
        fun onCameraFailed(config: Config, reason: Failure)
        fun onShutter()
        fun onFocusLocked(success: Boolean)

        /**
         * Detected faces, largest (the one focus follows) first, normalized to the portrait
         * preview (0..1). An empty list once faces are gone.
         */
        fun onFaces(config: Config, faces: List<RectF>)

        /** The scene became dark enough for night mode, or bright again (still modes only). */
        fun onLowLight(config: Config, lowLight: Boolean)
    }

    /**
     * Receives preview-rate YUV frames on a dedicated thread while set (panorama). The image is
     * closed as soon as [onFrame] returns.
     */
    fun interface FrameSink {
        fun onFrame(image: Image)
    }

    enum class Failure { OPEN_FAILED, DEVICE_ERROR, DISCONNECTED, IN_USE, NO_FRAMES, CONFIGURE_FAILED }

    data class Config(
        val lens: Lens,
        val mode: CaptureMode,
        val aspect: AspectRatio,
        val videoQuality: VideoQuality,
    ) {
        val previewSize: Size by lazy {
            if (mode.isVideo) SizeSelector.previewSize(lens, AspectRatio.RATIO_16_9)
            else SizeSelector.previewSize(lens, aspect.sensorRatio)
        }
    }

    /**
     * A still capture. With [frames] > 1 the frames are shot as one burst with identical
     * settings (night mode merges them) and [onJpegs] receives them all at once.
     */
    class PhotoRequest(
        val flash: FlashMode,
        val jpegOrientation: Int,
        val frames: Int = 1,
        val onJpegs: (List<ByteArray>) -> Unit,
        val onError: () -> Unit,
    ) {
        internal val received = ArrayList<ByteArray>(frames)
    }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("CameraSession").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executor { handler.post(it) }
    private val analysisThread = HandlerThread("CameraAnalysis").apply { start() }
    private val analysisHandler = Handler(analysisThread.looper)

    // --- State below is only touched on the camera thread ---------------------------------------
    private var generation = 0
    private var config: Config? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var jpegReader: ImageReader? = null
    private var analysisReader: ImageReader? = null
    @Volatile private var frameSink: FrameSink? = null
    private var recorder: VideoRecorder? = null
    private var firstFrameReceived = false

    private var zoom = 1f
    private var exposureIndex = 0
    private var meteringRegion: MeteringRectangle? = null
    private var aeAfLocked = false
    private var torch = false

    private var faceDetection = true
    /** Main face, used for focus and exposure when the user hasn't tapped somewhere. */
    private var faceRegion: MeteringRectangle? = null
    private var faceRegionUpdatedAt = 0L
    private var lastFaceSeenAt = 0L
    private var facesShown = false

    private var lowLight = false

    private var captureState = CaptureState.IDLE
    private var pendingPhoto: PhotoRequest? = null
    private val jpegCallbacks = ArrayDeque<PhotoRequest>()
    private var lastAfState: Int? = null
    private var focusWatchStarted = false

    private enum class CaptureState { IDLE, WAITING_FOCUS, WAITING_PRECAPTURE, WAITING_NON_PRECAPTURE, CAPTURING }

    // --- Public API (any thread) ----------------------------------------------------------------

    fun open(config: Config, texture: SurfaceTexture) = handler.post { openInternal(config, texture) }

    /** Closes the camera and waits (bounded) so another app can use it right after onPause. */
    fun close() {
        val done = ConditionVariable()
        handler.post {
            closeInternal()
            done.open()
        }
        done.block(CLOSE_TIMEOUT_MS)
    }

    fun release() {
        close()
        thread.quitSafely()
        analysisThread.quitSafely()
    }

    fun setZoom(ratio: Float) = handler.post {
        val lens = config?.lens ?: return@post
        zoom = ratio.coerceIn(lens.zoomRange.lower, lens.zoomRange.upper)
        updateRepeating()
    }

    /** @param ev exposure bias in stops, typically -2..+2. */
    fun setExposure(ev: Float) = handler.post {
        val lens = config?.lens ?: return@post
        if (lens.exposureStep <= 0f) return@post
        exposureIndex = (ev / lens.exposureStep).roundToInt()
            .coerceIn(lens.exposureRange.lower, lens.exposureRange.upper)
        updateRepeating()
    }

    fun setFaceDetection(enabled: Boolean) = handler.post {
        if (faceDetection == enabled) return@post
        faceDetection = enabled
        faceRegion = null
        facesShown = false
        updateRepeating()
    }

    fun setTorch(enabled: Boolean) = handler.post {
        torch = enabled
        updateRepeating()
    }

    /**
     * Tap to focus. Coordinates are normalized (0..1) in the portrait preview.
     * @param lock also lock exposure and focus ("AE/AF LOCK").
     */
    fun focusAt(nx: Float, ny: Float, lock: Boolean) = handler.post { focusInternal(nx, ny, lock) }

    /** Back to continuous auto-focus / auto-exposure. */
    fun resetFocus() = handler.post {
        if (meteringRegion == null && !aeAfLocked) return@post
        meteringRegion = null
        aeAfLocked = false
        sendAfTrigger(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        updateRepeating()
    }

    fun takePhoto(request: PhotoRequest) = handler.post { startPhotoSequence(request) }

    /**
     * Starts (or stops, with null) streaming YUV frames to [sink]. Exposure and white balance
     * stay locked meanwhile, so that the frames stitch without visible seams.
     */
    fun setFrameSink(sink: FrameSink?) = handler.post {
        frameSink = sink
        updateRepeating()
    }

    /** Starts recording to [output]. The file descriptor must stay open until [stopRecording]. */
    fun startRecording(
        output: FileDescriptor,
        orientation: Int,
        withAudio: Boolean,
        callback: (Boolean) -> Unit,
    ) = handler.post {
        val rec = recorder
        val ok = if (rec == null || session == null) false else try {
            rec.start(output, orientation, withAudio)
            updateRepeating()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start recording", e)
            false
        }
        main.post { callback(ok) }
    }

    fun stopRecording(callback: (Boolean) -> Unit) = handler.post {
        val ok = recorder?.stop() ?: false
        updateRepeating()
        main.post { callback(ok) }
    }

    // --- Opening / closing ----------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun openInternal(newConfig: Config, texture: SurfaceTexture) {
        closeInternal()
        val gen = ++generation
        config = newConfig
        firstFrameReceived = false
        zoom = 1f
        exposureIndex = 0
        meteringRegion = null
        aeAfLocked = false
        faceRegion = null
        facesShown = false
        lowLight = false
        frameSink = null

        val previewSize = newConfig.previewSize
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(texture)

        handler.postDelayed({
            if (gen == generation && !firstFrameReceived) fail(gen, Failure.NO_FRAMES)
        }, FIRST_FRAME_TIMEOUT_MS)

        try {
            manager.openCamera(newConfig.lens.id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (gen != generation) {
                        camera.close()
                        return
                    }
                    device = camera
                    createSession(gen, camera, newConfig)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (gen == generation) fail(gen, Failure.DISCONNECTED)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera ${newConfig.lens.id} error $error")
                    camera.close()
                    val reason = when (error) {
                        ERROR_CAMERA_IN_USE, ERROR_MAX_CAMERAS_IN_USE -> Failure.IN_USE
                        else -> Failure.DEVICE_ERROR
                    }
                    if (gen == generation) fail(gen, reason)
                }
            }, handler)
        } catch (e: Exception) {
            // CameraAccessException, SecurityException, IllegalArgumentException...
            Log.e(TAG, "openCamera(${newConfig.lens.id}) failed", e)
            fail(gen, Failure.OPEN_FAILED)
        }
    }

    private fun createSession(gen: Int, camera: CameraDevice, cfg: Config) {
        val preview = previewSurface ?: return
        val outputs = mutableListOf(preview)
        try {
            when {
                cfg.mode.isStill -> {
                    val size = SizeSelector.photoSize(cfg.lens, cfg.aspect.sensorRatio)
                    val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 3)
                    reader.setOnImageAvailableListener(::onJpegAvailable, handler)
                    jpegReader = reader
                    outputs += reader.surface
                }
                cfg.mode.isVideo -> {
                    val rate = if (cfg.mode == CaptureMode.TIMELAPSE) TIMELAPSE_CAPTURE_RATE else null
                    val rec = VideoRecorder(appContext, cfg.lens, SizeSelector.videoSize(cfg.lens, cfg.videoQuality), rate)
                    recorder = rec
                    outputs += rec.surface
                }
                else -> {
                    val size = SizeSelector.analysisSize(cfg.lens, cfg.aspect.sensorRatio)
                    val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 3)
                    reader.setOnImageAvailableListener(::onAnalysisFrame, analysisHandler)
                    analysisReader = reader
                    outputs += reader.surface
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to prepare outputs", e)
            fail(gen, Failure.CONFIGURE_FAILED)
            return
        }

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != generation) {
                    s.close()
                    return
                }
                session = s
                updateRepeating()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.e(TAG, "Session configuration failed")
                if (gen == generation) fail(gen, Failure.CONFIGURE_FAILED)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val sessionConfig = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputs.map { OutputConfiguration(it) },
                    executor,
                    callback,
                )
                camera.createCaptureSession(sessionConfig)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(outputs, callback, handler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "createCaptureSession failed", e)
            fail(gen, Failure.CONFIGURE_FAILED)
        }
    }

    private fun closeInternal() {
        generation++
        captureState = CaptureState.IDLE
        pendingPhoto = null
        jpegCallbacks.clear()
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        jpegReader?.close()
        jpegReader = null
        frameSink = null
        analysisReader?.close()
        analysisReader = null
        recorder?.release()
        recorder = null
        previewSurface?.release()
        previewSurface = null
        torch = false
    }

    private fun fail(gen: Int, reason: Failure) {
        if (gen != generation) return
        val cfg = config ?: return
        Log.w(TAG, "Camera ${cfg.lens.id} failed: $reason")
        closeInternal()
        main.post { listener.onCameraFailed(cfg, reason) }
    }

    // --- Repeating request ----------------------------------------------------------------------

    private fun updateRepeating() {
        val s = session ?: return
        val camera = device ?: return
        val cfg = config ?: return
        val preview = previewSurface ?: return
        val recording = recorder?.isRecording == true
        try {
            val template = if (recording) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
            val builder = camera.createCaptureRequest(template)
            builder.addTarget(preview)
            if (recording) builder.addTarget(recorder!!.surface)
            val analysis = analysisReader?.takeIf { frameSink != null }
            if (analysis != null) builder.addTarget(analysis.surface)
            applyCommon(builder, cfg, forStill = false)
            if (analysis != null) {
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true)
                builder.set(CaptureRequest.CONTROL_AWB_LOCK, true)
            }
            s.setRepeatingRequest(builder.build(), repeatingCallback, handler)
        } catch (e: CameraAccessException) {
            Log.e(TAG, "setRepeatingRequest failed", e)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Session already closed", e)
        }
    }

    private fun applyCommon(builder: CaptureRequest.Builder, cfg: Config, forStill: Boolean, flash: FlashMode? = null) {
        val lens = cfg.lens
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        // Focus: a tapped point wins over the main face, which wins over the whole frame.
        val region = meteringRegion
        val face = if (region == null) faceRegion else null
        when {
            region != null && lens.supportsAutoAf -> {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                if (lens.maxAfRegions > 0) builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            cfg.mode.isVideo && CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO in lens.afModes ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
            lens.supportsContinuousAf ->
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            else -> builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
        }
        // Continuous AF honors regions too: it keeps tracking the face.
        if (face != null && lens.maxAfRegions > 0 && lens.supportsContinuousAf) {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(face))
        }

        // Exposure
        val aeRegion = region ?: face
        if (aeRegion != null && lens.maxAeRegions > 0) {
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(aeRegion))
        }
        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureIndex)
        builder.set(CaptureRequest.CONTROL_AE_LOCK, aeAfLocked && !forStill)

        // Flash: the still request carries the user's flash mode; preview uses torch or nothing.
        val aeMode = when {
            !lens.hasFlash -> CameraMetadata.CONTROL_AE_MODE_ON
            flash == FlashMode.AUTO -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
            flash == FlashMode.ON -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
            else -> CameraMetadata.CONTROL_AE_MODE_ON
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        builder.set(
            CaptureRequest.FLASH_MODE,
            if (torch && lens.hasFlash && !forStill) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF,
        )

        // Frame rate: steady 30 fps for video, let AE go down to 15 fps in the dark for photos.
        pickFpsRange(lens, cfg.mode)?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }

        // Stabilization
        if (lens.supportsOis) {
            builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
        if (cfg.mode.isVideo && lens.supportsVideoStabilization) {
            builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        }

        if (faceDetection && lens.supportsFaceDetection) {
            builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, lens.faceDetectMode)
        }

        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion(lens))
    }

    private fun pickFpsRange(lens: Lens, mode: CaptureMode): Range<Int>? {
        val ranges = lens.characteristics.get(
            android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ) ?: return null
        return if (mode.isVideo || mode == CaptureMode.PANO) {
            ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
                ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
        } else {
            ranges.filter { it.upper == 30 }.minByOrNull { it.lower }
        }
    }

    private fun cropRegion(lens: Lens): Rect {
        val active = lens.activeArray
        val w = (active.width() / zoom).roundToInt()
        val h = (active.height() / zoom).roundToInt()
        val left = active.left + (active.width() - w) / 2
        val top = active.top + (active.height() - h) / 2
        return Rect(left, top, left + w, top + h)
    }

    /**
     * The part of the active array the preview actually shows: the zoom crop, further cut by the
     * HAL to the stream's aspect ratio (a 16:9 stream shows less height than the 4:3 sensor).
     */
    private fun visibleRegion(cfg: Config): Rect {
        val crop = cropRegion(cfg.lens)
        val size = cfg.previewSize
        val streamRatio = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height)
        val cropRatio = crop.width().toFloat() / crop.height()
        return if (cropRatio > streamRatio) {
            val w = (crop.height() * streamRatio).roundToInt()
            val left = crop.left + (crop.width() - w) / 2
            Rect(left, crop.top, left + w, crop.bottom)
        } else {
            val h = (crop.width() / streamRatio).roundToInt()
            val top = crop.top + (crop.height() - h) / 2
            Rect(crop.left, top, crop.right, top + h)
        }
    }

    /** Portrait preview (0..1) -> sensor (0..1): the sensor is mounted in landscape. */
    private fun viewToSensor(lens: Lens, nx: Float, ny: Float): Pair<Float, Float> {
        val x = if (lens.facing == Lens.Facing.FRONT) 1f - nx else nx
        return when (lens.sensorOrientation) {
            90 -> ny to 1f - x
            180 -> 1f - x to 1f - ny
            270 -> 1f - ny to x
            else -> x to ny
        }
    }

    /** Inverse of [viewToSensor]. */
    private fun sensorToView(lens: Lens, sx: Float, sy: Float): Pair<Float, Float> {
        val (x, ny) = when (lens.sensorOrientation) {
            90 -> 1f - sy to sx
            180 -> 1f - sx to 1f - sy
            270 -> sy to 1f - sx
            else -> sx to sy
        }
        return (if (lens.facing == Lens.Facing.FRONT) 1f - x else x) to ny
    }

    /** Active-array rectangle -> normalized portrait preview rectangle, or null if off screen. */
    private fun sensorRectToView(cfg: Config, rect: Rect): RectF? {
        val visible = visibleRegion(cfg)
        val w = visible.width().toFloat()
        val h = visible.height().toFloat()
        val (x1, y1) = sensorToView(cfg.lens, (rect.left - visible.left) / w, (rect.top - visible.top) / h)
        val (x2, y2) = sensorToView(cfg.lens, (rect.right - visible.left) / w, (rect.bottom - visible.top) / h)
        val out = RectF(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
        return out.takeIf { it.right > 0f && it.bottom > 0f && it.left < 1f && it.top < 1f }
    }

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (!firstFrameReceived) {
                firstFrameReceived = true
                val cfg = config ?: return
                main.post { listener.onPreviewStarted(cfg) }
            }
            lastAfState = result.get(CaptureResult.CONTROL_AF_STATE)
            processCaptureState(result)
            val cfg = config ?: return
            if (faceDetection && cfg.lens.supportsFaceDetection) processFaces(cfg, result)
            if (cfg.mode.isStill) processLight(cfg, result)
        }
    }

    // --- Light level ----------------------------------------------------------------------------

    /**
     * Exposure time x ISO grows as the scene gets darker: AE first lengthens the exposure, then
     * raises the gain. A hysteresis keeps the night-mode badge from flickering at the threshold.
     */
    private fun processLight(cfg: Config, result: CaptureResult) {
        val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val darkness = exposureNs / 1e9f * iso
        val dark = if (lowLight) darkness > NIGHT_EXIT_DARKNESS else darkness > NIGHT_ENTER_DARKNESS
        if (dark == lowLight) return
        lowLight = dark
        main.post { listener.onLowLight(cfg, dark) }
    }

    private fun onAnalysisFrame(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        image.use { frameSink?.onFrame(it) }
    }

    // --- Faces ----------------------------------------------------------------------------------

    private fun processFaces(cfg: Config, result: CaptureResult) {
        val now = SystemClock.uptimeMillis()
        val faces = result.get(CaptureResult.STATISTICS_FACES)
            ?.filter { it.score >= MIN_FACE_SCORE }
            ?.sortedByDescending { it.bounds.width() * it.bounds.height() }
            .orEmpty()
        if (faces.isNotEmpty()) {
            lastFaceSeenAt = now
        } else if (!facesShown || now - lastFaceSeenAt < FACE_LINGER_MS) {
            // Detection often misses a frame or two: keep the boxes instead of flickering.
            return
        }
        facesShown = faces.isNotEmpty()
        val rects = faces.mapNotNull { sensorRectToView(cfg, it.bounds) }
        main.post { listener.onFaces(cfg, rects) }
        updateFaceRegion(cfg.lens, faces.firstOrNull()?.bounds, now)
    }

    /** Points focus and exposure at the main face; rate-limited, as each change is a new request. */
    private fun updateFaceRegion(lens: Lens, bounds: Rect?, now: Long) {
        if (captureState != CaptureState.IDLE) return
        val current = faceRegion
        if (bounds == null) {
            if (current != null) {
                faceRegion = null
                if (meteringRegion == null) updateRepeating()
            }
            return
        }
        if (current != null && (now - faceRegionUpdatedAt < FACE_REGION_INTERVAL_MS || !movedEnough(current.rect, bounds, lens))) return
        val rect = Rect(bounds)
        if (!rect.intersect(lens.activeArray)) return
        faceRegion = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        faceRegionUpdatedAt = now
        if (meteringRegion == null) updateRepeating()
    }

    private fun movedEnough(old: Rect, new: Rect, lens: Lens): Boolean {
        val threshold = lens.activeArray.width() * 0.05f
        val sizeRatio = new.width().toFloat() / old.width().coerceAtLeast(1)
        return abs(new.centerX() - old.centerX()) > threshold ||
            abs(new.centerY() - old.centerY()) > threshold ||
            sizeRatio !in 0.8f..1.25f
    }

    // --- Focus ----------------------------------------------------------------------------------

    private fun focusInternal(nx: Float, ny: Float, lock: Boolean) {
        val cfg = config ?: return
        val lens = cfg.lens
        if (session == null) return

        val (sx, sy) = viewToSensor(lens, nx, ny)
        val visible = visibleRegion(cfg)
        val cx = visible.left + (sx * visible.width()).roundToInt()
        val cy = visible.top + (sy * visible.height()).roundToInt()
        val half = (visible.width() / 16).coerceAtLeast(40)
        val rect = Rect(cx - half, cy - half, cx + half, cy + half)
        if (!rect.intersect(lens.activeArray)) return
        meteringRegion = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
        aeAfLocked = lock

        sendAfTrigger(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        updateRepeating()
        if (lens.supportsAutoAf) {
            sendAfTrigger(CameraMetadata.CONTROL_AF_TRIGGER_START)
            focusWatchStarted = true
        } else {
            main.post { listener.onFocusLocked(true) }
        }
    }

    private fun sendAfTrigger(trigger: Int) {
        val s = session ?: return
        val camera = device ?: return
        val cfg = config ?: return
        val preview = previewSurface ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(preview)
            applyCommon(builder, cfg, forStill = false)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
            s.capture(builder.build(), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "AF trigger failed", e)
        }
    }

    private fun sendPrecaptureTrigger(trigger: Int) {
        val s = session ?: return
        val camera = device ?: return
        val cfg = config ?: return
        val preview = previewSurface ?: return
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(preview)
            applyCommon(builder, cfg, forStill = false, flash = pendingPhoto?.flash)
            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, trigger)
            s.capture(builder.build(), null, handler)
        } catch (e: Exception) {
            Log.w(TAG, "Precapture trigger failed", e)
        }
    }

    // --- Still capture --------------------------------------------------------------------------

    private fun startPhotoSequence(request: PhotoRequest) {
        val cfg = config
        if (cfg == null || session == null || jpegReader == null || captureState != CaptureState.IDLE) {
            main.post { request.onError() }
            return
        }
        pendingPhoto = request
        val lens = cfg.lens
        val af = lastAfState
        val alreadyFocused = !lens.supportsAutoAf || af == null ||
            af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
            af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
            af == CaptureResult.CONTROL_AF_STATE_INACTIVE && meteringRegion == null

        if (alreadyFocused) {
            startPrecaptureIfNeeded()
        } else {
            captureState = CaptureState.WAITING_FOCUS
            sendAfTrigger(CameraMetadata.CONTROL_AF_TRIGGER_START)
            scheduleTimeout(CaptureState.WAITING_FOCUS, FOCUS_TIMEOUT_MS) { startPrecaptureIfNeeded() }
        }
    }

    private fun startPrecaptureIfNeeded() {
        val lens = config?.lens ?: return
        val flash = pendingPhoto?.flash ?: FlashMode.OFF
        if (lens.hasFlash && flash != FlashMode.OFF) {
            captureState = CaptureState.WAITING_PRECAPTURE
            sendPrecaptureTrigger(CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            scheduleTimeout(CaptureState.WAITING_PRECAPTURE, PRECAPTURE_TIMEOUT_MS) { captureStill() }
        } else {
            captureStill()
        }
    }

    private fun scheduleTimeout(state: CaptureState, delay: Long, action: () -> Unit) {
        val gen = generation
        handler.postDelayed({
            if (gen == generation && captureState == state) {
                Log.w(TAG, "Timeout while $state, continuing")
                action()
            }
        }, delay)
    }

    private fun processCaptureState(result: CaptureResult) {
        when (captureState) {
            CaptureState.WAITING_FOCUS -> {
                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                if (af == null || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                ) startPrecaptureIfNeeded()
            }
            CaptureState.WAITING_PRECAPTURE -> {
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                    ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED ||
                    ae == CaptureResult.CONTROL_AE_STATE_CONVERGED
                ) {
                    captureState = CaptureState.WAITING_NON_PRECAPTURE
                    scheduleTimeout(CaptureState.WAITING_NON_PRECAPTURE, PRECAPTURE_TIMEOUT_MS) { captureStill() }
                }
            }
            CaptureState.WAITING_NON_PRECAPTURE -> {
                val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                if (ae == null || ae != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) captureStill()
            }
            else -> Unit
        }

        if (focusWatchStarted) {
            val af = result.get(CaptureResult.CONTROL_AF_STATE)
            if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
            ) {
                focusWatchStarted = false
                val ok = af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                main.post { listener.onFocusLocked(ok) }
            }
        }
    }

    private fun captureStill() {
        val s = session
        val camera = device
        val cfg = config
        val reader = jpegReader
        val request = pendingPhoto
        if (s == null || camera == null || cfg == null || reader == null || request == null) {
            resetCaptureState()
            request?.let { main.post { it.onError() } }
            return
        }
        captureState = CaptureState.CAPTURING
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            applyCommon(builder, cfg, forStill = true, flash = request.flash)
            builder.set(CaptureRequest.JPEG_ORIENTATION, request.jpegOrientation)
            builder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY)
            if (request.frames > 1) {
                // Merging averages the noise away; keep the fine detail the HAL would smear.
                builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST)
            }
            jpegCallbacks.addLast(request)
            var pending = request.frames
            var failed = false
            val callback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(s: CameraCaptureSession, r: CaptureRequest, timestamp: Long, frame: Long) {
                    if (pending == request.frames) main.post { listener.onShutter() }
                }

                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    if (--pending == 0) finishCapture()
                }

                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, failure: CaptureFailure) {
                    Log.e(TAG, "Still capture failed: ${failure.reason}")
                    if (!failed) {
                        failed = true
                        jpegCallbacks.remove(request)
                        main.post { request.onError() }
                    }
                    if (--pending == 0) finishCapture()
                }
            }
            val still = builder.build()
            if (request.frames == 1) s.capture(still, callback, handler)
            else s.captureBurst(List(request.frames) { still }, callback, handler)
        } catch (e: Exception) {
            Log.e(TAG, "Still capture error", e)
            jpegCallbacks.remove(request)
            main.post { request.onError() }
            resetCaptureState()
        }
    }

    private fun finishCapture() {
        resetCaptureState()
        // Release the AF lock taken for the shot, unless the user locked it on purpose.
        if (meteringRegion == null) sendAfTrigger(CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        updateRepeating()
    }

    private fun resetCaptureState() {
        captureState = CaptureState.IDLE
        pendingPhoto = null
    }

    private fun onJpegAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireNextImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        val bytes = image.use {
            val buffer = it.planes[0].buffer
            ByteArray(buffer.remaining()).also { array -> buffer.get(array) }
        }
        val request = jpegCallbacks.peekFirst() ?: return
        request.received += bytes
        if (request.received.size == request.frames) {
            jpegCallbacks.pollFirst()
            request.onJpegs(request.received.toList())
        }
    }

    private companion object {
        const val TAG = "CameraSession"
        const val FIRST_FRAME_TIMEOUT_MS = 4_000L
        const val CLOSE_TIMEOUT_MS = 1_500L
        const val FOCUS_TIMEOUT_MS = 800L
        const val PRECAPTURE_TIMEOUT_MS = 1_200L
        const val JPEG_QUALITY: Byte = 95
        /** Face.score goes from 1 to 100; below this, detections are mostly false positives. */
        const val MIN_FACE_SCORE = 30
        const val FACE_LINGER_MS = 600L
        const val FACE_REGION_INTERVAL_MS = 300L
        /** Time-lapse: one frame every half second, played back at 30 fps (15x faster). */
        const val TIMELAPSE_CAPTURE_RATE = 2.0
        /** Exposure seconds x ISO; ~= 1/10 s at ISO 800 enters night mode. */
        const val NIGHT_ENTER_DARKNESS = 80f
        const val NIGHT_EXIT_DARKNESS = 45f
    }
}
