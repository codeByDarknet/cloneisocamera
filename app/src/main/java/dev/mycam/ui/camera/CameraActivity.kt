package dev.mycam.ui.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import dev.mycam.R
import dev.mycam.camera.AspectRatio
import dev.mycam.camera.CameraInventory
import dev.mycam.camera.CameraSession
import dev.mycam.camera.CaptureMode
import dev.mycam.camera.DeviceOrientation
import dev.mycam.camera.FlashMode
import dev.mycam.camera.GyroTracker
import dev.mycam.camera.Lens
import dev.mycam.camera.SizeSelector
import dev.mycam.camera.TimerDelay
import dev.mycam.imaging.NightMerger
import dev.mycam.imaging.PanoramaStitcher
import dev.mycam.imaging.PortraitRenderer
import dev.mycam.media.ImageLoader
import dev.mycam.media.MediaFilter
import dev.mycam.media.MediaItem
import dev.mycam.media.MediaRepository
import dev.mycam.media.MediaSaver
import dev.mycam.ui.gallery.GalleryActivity
import dev.mycam.ui.glass.GlassBackdrop
import dev.mycam.ui.glass.GlassToast
import dev.mycam.ui.glass.GlassView
import dev.mycam.ui.glass.Spring
import dev.mycam.ui.viewer.ViewerActivity
import dev.mycam.util.Background
import dev.mycam.util.Formatters
import dev.mycam.util.Haptics
import dev.mycam.util.Prefs
import dev.mycam.util.SystemBars
import dev.mycam.util.dp
import dev.mycam.util.dpi
import dev.mycam.util.goEdgeToEdge
import dev.mycam.util.onSystemBars
import dev.mycam.util.setVisible
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The camera screen.
 *
 * Robustness first: the app always starts on the main rear lens, never reopens a lens that
 * failed during this run, and falls back to the rear camera when a front sensor is dead (some
 * stock camera apps refuse to start at all in that case).
 *
 * [CaptureIntentActivity] reuses this screen for other apps' "take a picture" requests by
 * overriding the capture hooks at the bottom of the class.
 */
open class CameraActivity : Activity(), CameraSession.Listener {

    private lateinit var prefs: Prefs
    private lateinit var inventory: CameraInventory
    private lateinit var session: CameraSession
    private lateinit var orientation: DeviceOrientation
    private lateinit var backdrop: GlassBackdrop
    protected lateinit var toast: GlassToast
    private val sound = MediaActionSound()
    protected val saver by lazy { MediaSaver(this) }
    private val repository by lazy { MediaRepository(this) }

    // --- Views ----------------------------------------------------------------------------------
    protected lateinit var root: FrameLayout
    private lateinit var previewContainer: FrameLayout
    private lateinit var preview: TextureView
    private lateinit var curtain: ImageView
    private lateinit var frameOverlay: FrameOverlayView
    private lateinit var faceOverlay: FaceOverlayView
    private lateinit var focusIndicator: FocusIndicatorView
    private lateinit var countdownLabel: TextView
    private lateinit var topBar: View
    private lateinit var topPill: GlassView
    private lateinit var flashButton: ImageView
    private lateinit var settingsButton: ImageView
    private lateinit var recordingPill: GlassView
    private lateinit var recordingTime: TextView
    private lateinit var zoomControl: ZoomControl
    private lateinit var modeSwitch: ModePickerView
    private lateinit var nightButton: ImageView
    private lateinit var portraitBadge: View
    private lateinit var panoOverlay: PanoramaOverlayView
    private lateinit var bottomBar: View
    private lateinit var thumbnailButton: GlassView
    private lateinit var thumbnail: ImageView
    private lateinit var shutter: ShutterButton
    private lateinit var flipButton: GlassView
    private lateinit var flipIcon: ImageView
    private lateinit var settingsPanel: QuickSettingsPanel
    private lateinit var permissionOverlay: View
    private lateinit var permissionLabel: TextView

    // --- State ----------------------------------------------------------------------------------
    private var resumed = false
    private var texture: SurfaceTexture? = null
    private var bars = SystemBars(0, 0, 0, 0)
    private lateinit var lens: Lens
    private var mode = CaptureMode.PHOTO
    private var config: CameraSession.Config? = null
    private var previewRunning = false
    /** Zoom as shown to the user: 1 = main lens, 0.5 = ultra-wide, 2 = 2x on the main lens. */
    private var displayZoom = 1f
    private var exposureEv = 0f
    /** Lenses that failed during this run; they are never opened automatically again. */
    private val failedLenses = HashSet<String>()
    private var retries = 0
    private var capturing = false
    private var countdown: Countdown? = null
    private var recording: Recording? = null
    private var latest: MediaItem? = null
    private var permanentlyDenied = false
    /** The scene is dark enough for night mode (reported by the session, still modes). */
    private var lowLight = false
    /** Night mode wanted by the user when the scene is dark; tapping the moon turns it off. */
    private var nightWanted = true
    private val gyro by lazy { GyroTracker(this) }
    private var panorama: PanoramaStitcher? = null

    private class Recording(val output: MediaSaver.PendingVideo, val startedAt: Long) {
        var started = false
    }

    // --- Lifecycle ------------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_camera)
        bindViews()

        prefs = Prefs(this)
        inventory = CameraInventory(getSystemService(CameraManager::class.java))
        session = CameraSession(this, this)
        orientation = DeviceOrientation(this, ::rotateUi)
        backdrop = GlassBackdrop.install(root, preview)
        toast = GlassToast(root)
        sound.load(MediaActionSound.SHUTTER_CLICK)

        // Always the main rear lens at launch: a broken front camera must never block startup.
        lens = inventory.backMain ?: run {
            permissionOverlay.setVisible(false)
            toast.show(getString(R.string.no_camera), durationMs = 60_000L)
            return
        }
        mode = lockedMode ?: prefs.mode

        setUpControls()
        setUpGestures()
        root.onSystemBars {
            bars = it
            applyLayout()
        }
        preview.surfaceTextureListener = surfaceListener
        refreshControls()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (!::lens.isInitialized) return
        orientation.enable()
        if (hasCameraPermission()) {
            permissionOverlay.setVisible(false)
            openCamera()
        } else {
            showPermissionOverlay()
            if (!permanentlyDenied) requestCameraPermissions()
        }
        if (showsThumbnail) loadLatestCapture()
    }

    override fun onPause() {
        resumed = false
        countdown?.cancel()
        if (::lens.isInitialized) {
            stopRecording()
            orientation.disable()
            settingsPanel.close()
            closeCamera()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (::session.isInitialized) session.release()
        sound.release()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA -> {
                if (event.repeatCount == 0 && shutter.isEnabled) onShutterPressed()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_CAMERA -> true
        else -> super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (settingsPanel.isOpen) settingsPanel.close() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // --- Permissions ----------------------------------------------------------------------------

    private fun hasCameraPermission() =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestCameraPermissions() {
        requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (hasCameraPermission()) {
            permissionOverlay.setVisible(false)
            if (resumed) openCamera()
        } else {
            permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
            showPermissionOverlay()
        }
    }

    private fun showPermissionOverlay() {
        permissionOverlay.setVisible(true)
        permissionLabel.setText(if (permanentlyDenied) R.string.open_settings else R.string.permission_button)
    }

    private fun onPermissionButton() {
        if (permanentlyDenied) {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
        } else {
            requestCameraPermissions()
        }
    }

    // --- Camera ---------------------------------------------------------------------------------

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            texture = surface
            if (resumed && hasCameraPermission()) openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            texture = null
            closeCamera()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = backdrop.requestCapture()
    }

    /** (Re)opens the camera with the current lens, mode and settings. */
    private fun openCamera() {
        val surface = texture ?: return
        if (!resumed || !hasCameraPermission()) return
        val cfg = CameraSession.Config(lens, mode, captureAspect, prefs.videoQuality)
        Log.i(TAG, "Opening camera ${lens.id} (${mode.name})")
        config = cfg
        previewRunning = false
        shutter.isEnabled = false
        showCurtain()
        focusIndicator.hide()
        faceOverlay.clear()
        portraitBadge.setVisible(false)
        lowLight = false
        refreshNightButton()
        exposureEv = 0f
        layoutPreview(cfg)
        session.open(cfg, surface)
        session.setZoom(lensZoomRatio())
    }

    private fun closeCamera() {
        stopPanorama()
        config = null
        previewRunning = false
        capturing = false
        session.close()
    }

    override fun onPreviewStarted(config: CameraSession.Config) {
        if (config !== this.config) return
        previewRunning = true
        retries = 0
        shutter.isEnabled = true
        hideCurtain()
    }

    override fun onCameraFailed(config: CameraSession.Config, reason: CameraSession.Failure) {
        if (config !== this.config || !resumed) return
        this.config = null
        previewRunning = false
        recording?.let { finishRecording(it, success = false) }
        val failed = config.lens

        when {
            reason == CameraSession.Failure.IN_USE -> {
                toast.show(getString(R.string.camera_in_use))
                retryLater()
            }
            failed.facing == Lens.Facing.FRONT -> {
                // A dead front sensor: try another front lens if the phone has one, otherwise go
                // back to the rear camera.
                failedLenses += failed.id
                val alternative = availableFronts().firstOrNull()
                if (alternative != null) {
                    lens = alternative
                } else {
                    toast.show(getString(R.string.front_unavailable), durationMs = 3_500L)
                    switchToLens(inventory.backMain!!, 1f, reopen = false)
                }
                refreshControls()
                openCamera()
            }
            failed === inventory.backUltraWide -> {
                failedLenses += failed.id
                switchToLens(inventory.backMain!!, 1f, reopen = false)
                refreshControls()
                openCamera()
            }
            else -> {
                toast.show(getString(R.string.camera_error))
                retryLater()
            }
        }
    }

    private fun retryLater() {
        if (retries >= MAX_RETRIES) {
            toast.show(getString(R.string.no_camera), durationMs = 6_000L)
            return
        }
        retries++
        root.postDelayed({ if (resumed && config == null) openCamera() }, RETRY_DELAY_MS * retries)
    }

    private fun availableFronts() = inventory.fronts.filter { it.id !in failedLenses }

    override fun onShutter() {
        if (prefs.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
        blink()
    }

    override fun onFocusLocked(success: Boolean) {
        if (success) focusIndicator.pulse()
    }

    override fun onFaces(config: CameraSession.Config, faces: List<RectF>) {
        if (config !== this.config) return
        // A tapped focus point takes over: faces come back once its square times out.
        if (prefs.faceDetection) faceOverlay.setFaces(if (focusIndicator.isShowing) emptyList() else faces)
        portraitBadge.setVisible(mode == CaptureMode.PORTRAIT && faces.isNotEmpty())
    }

    override fun onLowLight(config: CameraSession.Config, lowLight: Boolean) {
        if (config !== this.config) return
        this.lowLight = lowLight
        refreshNightButton()
    }

    // --- Zoom & lenses --------------------------------------------------------------------------

    private val hasUltraWide get() = inventory.backUltraWide?.let { it.id !in failedLenses } == true

    private fun zoomStops(): List<Float> = when {
        lens.facing == Lens.Facing.FRONT -> emptyList()
        hasUltraWide -> listOf(inventory.ultraWideFactor, 1f, 2f)
        else -> listOf(1f, 2f)
    }

    /** Crop ratio to request on the current lens for [displayZoom]. */
    private fun lensZoomRatio(): Float =
        if (lens === inventory.backUltraWide) displayZoom / inventory.ultraWideFactor else displayZoom

    private fun maxDisplayZoom(): Float = when (lens) {
        inventory.backUltraWide -> 1f
        else -> minOf(lens.zoomRange.upper, MAX_ZOOM)
    }

    private fun minDisplayZoom(): Float = when (lens) {
        inventory.backUltraWide -> inventory.ultraWideFactor
        else -> 1f
    }

    /** Preset tapped in the zoom capsule. */
    private fun selectZoom(value: Float) {
        if (lens.facing == Lens.Facing.FRONT) return
        val target = if (value < 1f && hasUltraWide) inventory.backUltraWide!! else inventory.backMain!!
        switchToLens(target, value, reopen = true)
    }

    private fun switchToLens(target: Lens, zoom: Float, reopen: Boolean) {
        val changed = target !== lens
        lens = target
        displayZoom = zoom.coerceIn(minDisplayZoom(), maxDisplayZoom())
        zoomControl.setZoom(displayZoom)
        if (!changed) {
            session.setZoom(lensZoomRatio())
            return
        }
        refreshControls()
        if (reopen) openCamera()
    }

    // --- Capture --------------------------------------------------------------------------------

    private fun onShutterPressed() {
        settingsPanel.close()
        if (!previewRunning) return
        countdown?.let {
            it.cancel()
            return
        }
        when (mode) {
            CaptureMode.PHOTO, CaptureMode.PORTRAIT -> {
                if (capturing) return
                val delay = prefs.timer
                if (delay == TimerDelay.OFF) takePhoto() else startCountdown(delay.seconds)
            }
            CaptureMode.VIDEO, CaptureMode.TIMELAPSE -> if (recording == null) startRecording() else stopRecording()
            CaptureMode.PANO -> if (panorama == null) startPanorama() else stopPanorama()
        }
    }

    /** Photo and portrait keep the chosen format; the other modes have their own. */
    private val captureAspect: AspectRatio
        get() = if (mode == CaptureMode.PHOTO) prefs.aspect else AspectRatio.RATIO_4_3

    private val flashMode: FlashMode get() = if (lens.hasFlash) prefs.flash else FlashMode.OFF

    /** Night mode applies to the next photo: dark scene, not turned off, and no forced flash. */
    private val nightActive: Boolean
        get() = mode == CaptureMode.PHOTO && lowLight && nightWanted && flashMode != FlashMode.ON

    private fun takePhoto() {
        val cfg = config ?: return
        capturing = true
        Haptics.tap(shutter)
        val square = cfg.aspect == AspectRatio.RATIO_1_1
        val night = nightActive
        val portrait = cfg.mode == CaptureMode.PORTRAIT
        session.takePhoto(
            CameraSession.PhotoRequest(
                flash = if (night) FlashMode.OFF else flashMode,
                jpegOrientation = orientation.outputRotation(lens),
                frames = if (night) NIGHT_FRAMES else 1,
                onJpegs = { jpegs ->
                    // Camera thread: hand the bytes over to the main thread right away.
                    runOnUiThread {
                        when {
                            night -> processNight(jpegs, square)
                            portrait -> processPortrait(jpegs.first())
                            else -> {
                                capturing = false
                                onPhotoCaptured(jpegs.first(), square)
                            }
                        }
                    }
                },
                onError = {
                    capturing = false
                    toast.show(getString(R.string.photo_failed))
                },
            ),
        )
    }

    /** Merges the night burst; falls back to its first frame if merging fails. */
    private fun processNight(jpegs: List<ByteArray>, square: Boolean) {
        toast.show(getString(R.string.processing))
        Background.run(Background.compute, {
            NightMerger.merge(jpegs)?.let { result ->
                saver.encode(result.bitmap, result.reference, upright = false).also { result.bitmap.recycle() }
            } ?: jpegs.first()
        }) { jpeg ->
            capturing = false
            onPhotoCaptured(jpeg, square)
        }
    }

    /** Portrait blur; without a face the photo is kept as shot. */
    private fun processPortrait(jpeg: ByteArray) {
        toast.show(getString(R.string.processing))
        Background.run(Background.compute, {
            PortraitRenderer.render(jpeg)?.let { result ->
                saver.encode(result.bitmap, jpeg, upright = true).also { result.bitmap.recycle() }
            }
        }) { rendered ->
            capturing = false
            if (rendered == null) toast.show(getString(R.string.portrait_no_face), durationMs = 3_000L)
            onPhotoCaptured(rendered ?: jpeg, square = false)
        }
    }

    // --- Panorama -------------------------------------------------------------------------------

    private fun startPanorama() {
        val cfg = config ?: return
        if (!gyro.isAvailable) {
            toast.show(getString(R.string.pano_no_gyro))
            return
        }
        val chars = lens.characteristics
        val analysis = SizeSelector.analysisSize(lens, cfg.aspect.sensorRatio)
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 4f
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val sensorShortSide = sensor?.let { minOf(it.width, it.height) } ?: 3.6f
        // Portrait frame width covers the sensor's short side, cropped further by the zoom.
        val pixelsPerRadian = focal / sensorShortSide * minOf(analysis.width, analysis.height) * lensZoomRatio()
        val realtime = chars.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME

        val stitcher = PanoramaStitcher(gyro, pixelsPerRadian, lens.sensorOrientation, realtime, object : PanoramaStitcher.Listener {
            override fun onProgress(progress: PanoramaStitcher.Progress) {
                if (panorama != null) panoOverlay.update(progress)
            }

            override fun onLimitReached() = stopPanorama()
        })
        panorama = stitcher
        gyro.start()
        session.setFrameSink(stitcher::onFrame)
        if (prefs.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
        Haptics.heavy(shutter)
        showCaptureUi(true)
    }

    /** Ends the panorama in progress, if any, and saves it. */
    private fun stopPanorama() {
        val stitcher = panorama ?: return
        panorama = null
        session.setFrameSink(null)
        gyro.stop()
        showCaptureUi(false)
        panoOverlay.setActive(mode == CaptureMode.PANO)
        if (prefs.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        Haptics.heavy(shutter)
        Background.run(Background.compute, {
            stitcher.finish()?.let { bitmap ->
                saver.encode(bitmap, null, upright = true).also { bitmap.recycle() }
            }
        }) { jpeg ->
            if (jpeg == null) toast.show(getString(R.string.pano_too_short)) else onPhotoCaptured(jpeg, square = false)
        }
    }

    private fun startCountdown(seconds: Int) {
        countdown = Countdown(seconds).also { it.start() }
    }

    /** The big white seconds in the middle of the preview; tapping the shutter cancels it. */
    private inner class Countdown(private var remaining: Int) {
        private val tick = object : Runnable {
            override fun run() {
                if (remaining == 0) {
                    finish()
                    takePhoto()
                    return
                }
                countdownLabel.text = remaining.toString()
                countdownSpring.snapTo(1.4f)
                countdownSpring.animateTo(1f)
                Haptics.tick(countdownLabel)
                remaining--
                countdownLabel.postDelayed(this, 1_000L)
            }
        }
        private val countdownSpring = Spring(1f, stiffness = 300f, dampingRatio = 0.6f) {
            countdownLabel.scaleX = it
            countdownLabel.scaleY = it
        }

        fun start() {
            countdownLabel.setVisible(true)
            shutter.recording = true
            tick.run()
        }

        fun cancel() {
            countdownLabel.removeCallbacks(tick)
            finish()
        }

        private fun finish() {
            countdownLabel.setVisible(false)
            shutter.recording = false
            countdown = null
        }
    }

    private fun startRecording() {
        if (recording != null) return
        Background.run({ openVideoOutput() }) { output ->
            if (output == null) {
                toast.show(getString(R.string.storage_full))
                return@run
            }
            if (!previewRunning) {
                Background.io.execute { finishVideoOutput(output, false) }
                return@run
            }
            val rec = Recording(output, SystemClock.elapsedRealtime())
            recording = rec
            if (prefs.shutterSound) sound.play(MediaActionSound.START_VIDEO_RECORDING)
            if (lens.hasFlash && prefs.flash == FlashMode.ON) session.setTorch(true)
            Haptics.heavy(shutter)
            session.startRecording(
                output.descriptor.fileDescriptor,
                orientation.outputRotation(lens),
                withAudio = hasAudioPermission(),
            ) { ok ->
                if (recording !== rec) return@startRecording
                if (ok) {
                    rec.started = true
                    showRecordingUi(true)
                } else {
                    finishRecording(rec, success = false)
                    toast.show(getString(R.string.video_failed))
                }
            }
        }
    }

    private fun stopRecording() {
        val rec = recording ?: return
        if (!rec.started) {
            finishRecording(rec, success = false)
            return
        }
        recording = null
        showRecordingUi(false)
        session.setTorch(false)
        if (prefs.shutterSound) sound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        Haptics.heavy(shutter)
        session.stopRecording { ok ->
            if (!ok) toast.show(getString(R.string.video_failed))
            Background.run({ finishVideoOutput(rec.output, ok) }) { uri -> onVideoHandled(uri) }
        }
    }

    private fun finishRecording(rec: Recording, success: Boolean) {
        if (recording === rec) recording = null
        showRecordingUi(false)
        session.setTorch(false)
        Background.io.execute { finishVideoOutput(rec.output, success) }
    }

    private val recordingTicker = object : Runnable {
        override fun run() {
            val rec = recording ?: return
            recordingTime.text = Formatters.recordingTime(SystemClock.elapsedRealtime() - rec.startedAt)
            recordingTime.postDelayed(this, 250L)
        }
    }

    private fun showRecordingUi(active: Boolean) {
        showCaptureUi(active)
        recordingPill.setVisible(active)
        recordingTime.removeCallbacks(recordingTicker)
        if (active) recordingTicker.run()
    }

    /** Hides what cannot be changed while a video or a panorama is being captured. */
    private fun showCaptureUi(active: Boolean) {
        shutter.recording = active
        topPill.animate().alpha(if (active) 0f else 1f).setDuration(200).start()
        topPill.isEnabled = !active
        flashButton.isEnabled = !active
        settingsButton.isEnabled = !active
        nightButton.isEnabled = !active
        modeSwitch.animate().alpha(if (active) 0f else 1f).setDuration(200).start()
        modeSwitch.isEnabled = !active
        flipButton.animate().alpha(if (active) 0f else 1f).setDuration(200).start()
        flipButton.isEnabled = !active
        thumbnailButton.animate().alpha(if (active || !showsThumbnail) 0f else 1f).setDuration(200).start()
        thumbnailButton.isEnabled = !active
        if (active) settingsPanel.close()
    }

    // --- Capture hooks (overridden by CaptureIntentActivity) ------------------------------------

    /** Mode forced by the caller, or null to let the user choose. */
    protected open val lockedMode: CaptureMode? = null

    /** Whether the last-capture thumbnail (and the library behind it) is available. */
    protected open val showsThumbnail: Boolean = true

    /** Main thread. Saves the photo to the library by default. */
    protected open fun onPhotoCaptured(jpeg: ByteArray, square: Boolean) {
        Background.run({ saver.savePhoto(jpeg, square) }) { uri ->
            if (uri == null) toast.show(getString(R.string.storage_full)) else loadLatestCapture(animate = true)
        }
    }

    /** Runs on the disk worker. */
    protected open fun openVideoOutput(): MediaSaver.PendingVideo? = saver.createVideo()

    /** Runs on the disk worker. */
    protected open fun finishVideoOutput(output: MediaSaver.PendingVideo, success: Boolean): Uri? =
        saver.finishVideo(output, success)

    /** Main thread, after a successful or failed recording was finalized. */
    protected open fun onVideoHandled(uri: Uri?) {
        if (uri != null) loadLatestCapture(animate = true)
    }

    // --- Library thumbnail ----------------------------------------------------------------------

    private fun loadLatestCapture(animate: Boolean = false) {
        Background.run({ repository.latestCapture() }) { item ->
            latest = item
            if (item == null) {
                thumbnail.setImageDrawable(null)
                return@run
            }
            ImageLoader.get(this).thumbnail(item, 96.dpi) { bitmap ->
                if (latest?.id != item.id) return@thumbnail
                thumbnail.setImageBitmap(bitmap)
                if (animate) popThumbnail()
            }
        }
    }

    private fun popThumbnail() {
        Spring(0.6f, stiffness = 380f, dampingRatio = 0.55f) {
            thumbnail.scaleX = it
            thumbnail.scaleY = it
        }.animateTo(1f)
    }

    private fun openLibrary() {
        val item = latest
        startActivity(
            if (item != null) ViewerActivity.intent(this, MediaFilter.MYCAM, item.id)
            else Intent(this, GalleryActivity::class.java),
        )
    }

    // --- Controls -------------------------------------------------------------------------------

    private fun bindViews() {
        root = findViewById(R.id.root)
        previewContainer = findViewById(R.id.preview_container)
        preview = findViewById(R.id.preview)
        curtain = findViewById(R.id.curtain)
        frameOverlay = findViewById(R.id.frame_overlay)
        faceOverlay = findViewById(R.id.face_overlay)
        focusIndicator = findViewById(R.id.focus_indicator)
        countdownLabel = findViewById(R.id.countdown)
        topBar = findViewById(R.id.top_bar)
        topPill = findViewById(R.id.top_pill)
        flashButton = findViewById(R.id.flash_button)
        settingsButton = findViewById(R.id.settings_button)
        recordingPill = findViewById(R.id.recording_pill)
        recordingTime = findViewById(R.id.recording_time)
        zoomControl = findViewById(R.id.zoom_control)
        modeSwitch = findViewById(R.id.mode_switch)
        nightButton = findViewById(R.id.night_button)
        portraitBadge = findViewById(R.id.portrait_badge)
        panoOverlay = findViewById(R.id.pano_overlay)
        bottomBar = findViewById(R.id.bottom_bar)
        thumbnailButton = findViewById(R.id.thumbnail_button)
        thumbnail = findViewById(R.id.thumbnail)
        shutter = findViewById(R.id.shutter)
        flipButton = findViewById(R.id.flip_button)
        flipIcon = findViewById(R.id.flip_icon)
        settingsPanel = findViewById(R.id.settings_panel)
        permissionOverlay = findViewById(R.id.permission_overlay)
        permissionLabel = findViewById(R.id.permission_button_label)
    }

    private fun setUpControls() {
        shutter.setOnClickListener { onShutterPressed() }
        shutter.mode = mode

        modeSwitch.anchorIndex = MODES.indexOf(CaptureMode.PHOTO)
        modeSwitch.items = MODES.map { getString(modeLabel(it)) }
        modeSwitch.selectedTextColor = getColor(R.color.ios_yellow)
        modeSwitch.setSelection(MODES.indexOf(mode))
        modeSwitch.onSelected = { setMode(MODES[it]) }
        modeSwitch.setVisible(lockedMode == null)

        flashButton.setOnClickListener {
            prefs.flash = when (prefs.flash) {
                FlashMode.OFF -> if (mode.isVideo) FlashMode.ON else FlashMode.AUTO
                FlashMode.AUTO -> FlashMode.ON
                FlashMode.ON -> FlashMode.OFF
            }
            Haptics.tick(flashButton)
            refreshControls()
        }

        nightButton.setOnClickListener {
            nightWanted = !(nightActive)
            // The flash and night mode exclude each other.
            if (nightWanted && prefs.flash == FlashMode.ON) prefs.flash = FlashMode.OFF
            Haptics.tick(nightButton)
            toast.show(getString(if (nightWanted) R.string.night_on else R.string.night_off), accent = nightWanted)
            refreshControls()
        }

        settingsButton.setOnClickListener {
            Haptics.tick(settingsButton)
            if (settingsPanel.isOpen) {
                settingsPanel.close()
            } else {
                settingsPanel.bind(prefs, mode, lens.hasFlash, lens.supportsFaceDetection)
                settingsPanel.open()
            }
            updateSettingsChevron()
        }
        settingsPanel.onChanged = ::applySettingChange
        session.setFaceDetection(wantsFaceDetection)

        zoomControl.onStopSelected = ::selectZoom
        focusIndicator.onAutoHide = { session.resetFocus() }

        listOf(nightButton, flashButton, settingsButton).forEach(::springOnPress)

        flipButton.setOnClickListener { flipCamera() }
        thumbnailButton.setOnClickListener { openLibrary() }
        thumbnail.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
        }
        thumbnail.clipToOutline = true
        thumbnailButton.setVisible(showsThumbnail)
        findViewById<View>(R.id.permission_button).setOnClickListener { onPermissionButton() }
    }

    /** The icons in the top pill grow under the finger, like the glass buttons. */
    @SuppressLint("ClickableViewAccessibility")
    private fun springOnPress(view: View) {
        val spring = Spring(1f, stiffness = 520f, dampingRatio = 0.6f) {
            view.scaleX = it
            view.scaleY = it
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> spring.animateTo(1.18f)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> spring.animateTo(1f)
            }
            false
        }
    }

    private fun applySettingChange(setting: QuickSettingsPanel.Setting) {
        when (setting) {
            QuickSettingsPanel.Setting.ASPECT, QuickSettingsPanel.Setting.VIDEO_QUALITY -> openCamera()
            QuickSettingsPanel.Setting.FACES -> {
                session.setFaceDetection(wantsFaceDetection)
                faceOverlay.clear()
            }
            else -> Unit
        }
        refreshControls()
    }

    private fun setMode(newMode: CaptureMode) {
        if (newMode == mode || recording != null || panorama != null || lockedMode != null) return
        countdown?.cancel()
        mode = newMode
        prefs.mode = newMode
        shutter.mode = newMode
        modeSwitch.setSelection(MODES.indexOf(newMode), animate = true)
        settingsPanel.close()
        session.setFaceDetection(wantsFaceDetection)
        faceOverlay.clear()
        refreshControls()
        openCamera()
    }

    /** Portrait always tracks faces, for its badge; elsewhere it is the user's choice. */
    private val wantsFaceDetection get() = prefs.faceDetection || mode == CaptureMode.PORTRAIT

    private fun flipCamera() {
        if (recording != null || panorama != null) return
        Haptics.tick(flipButton)
        flipIcon.animate().rotationYBy(180f).setDuration(350).start()
        val target = if (lens.facing == Lens.Facing.FRONT) {
            inventory.backMain!!
        } else {
            availableFronts().firstOrNull() ?: run {
                toast.show(getString(R.string.front_unavailable))
                return
            }
        }
        switchToLens(target, 1f, reopen = true)
    }

    /** Syncs every control with the current lens, mode and preferences. */
    private fun refreshControls() {
        val video = mode.isVideo
        val flash = prefs.flash.let { if (video && it == FlashMode.AUTO) FlashMode.OFF else it }
        flashButton.setVisible(showsFlash)
        flashButton.setImageResource(
            when (flash) {
                FlashMode.AUTO -> R.drawable.ic_bolt_auto
                FlashMode.ON -> R.drawable.ic_bolt
                FlashMode.OFF -> R.drawable.ic_bolt_slash
            },
        )
        flashButton.setColorFilter(if (flash == FlashMode.ON) getColor(R.color.ios_yellow) else getColor(R.color.white))

        frameOverlay.showGrid = prefs.grid
        frameOverlay.aspect = if (video) AspectRatio.RATIO_16_9 else captureAspect
        if (panorama == null) panoOverlay.setActive(mode == CaptureMode.PANO)
        refreshNightButton()

        val stops = zoomStops()
        zoomControl.setVisible(stops.isNotEmpty())
        if (stops.isNotEmpty()) {
            zoomControl.stops = stops
            zoomControl.setZoom(displayZoom)
        }
        flipButton.alpha = if (availableFronts().isEmpty() && lens.facing != Lens.Facing.FRONT) 0.4f else 1f
        if (settingsPanel.isOpen) settingsPanel.bind(prefs, mode, lens.hasFlash, lens.supportsFaceDetection)
        updateSettingsChevron()
    }

    private val showsFlash get() = lens.hasFlash && mode != CaptureMode.PANO

    /** The moon: shown in the dark, yellow while night mode will be used. */
    private fun refreshNightButton() {
        nightButton.setVisible(mode == CaptureMode.PHOTO && lowLight && recording == null)
        nightButton.setColorFilter(if (nightActive) getColor(R.color.ios_yellow) else getColor(R.color.white))
    }

    private fun updateSettingsChevron() {
        settingsButton.setColorFilter(if (settingsPanel.isOpen) getColor(R.color.ios_yellow) else getColor(R.color.white))
    }

    /** Icons turn to stay upright when the phone is rotated, the UI itself stays portrait. */
    private fun rotateUi(degrees: Int) {
        val views = listOf(flashButton, nightButton, flipIcon, thumbnail, countdownLabel)
        for (v in views) {
            val target = -degrees.toFloat()
            // Shortest way round, so 270 -> 0 degrees turns by 90 and not by 270.
            var delta = (target - v.rotation) % 360f
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            v.animate().rotationBy(delta).setDuration(300).start()
        }
    }

    // --- Curtain --------------------------------------------------------------------------------

    /** Freezes a blurred copy of the last frame over the preview while the camera reopens. */
    private fun showCurtain() {
        curtain.animate().cancel()
        val frame: Bitmap? = backdrop.bitmap?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
        curtain.setImageBitmap(frame)
        curtain.alpha = 1f
    }

    private fun hideCurtain() {
        curtain.animate().alpha(0f).setDuration(220).start()
    }

    private fun blink() {
        curtain.animate().cancel()
        curtain.setImageDrawable(null)
        curtain.alpha = 1f
        curtain.animate().alpha(0f).setStartDelay(40).setDuration(160).start()
    }

    // --- Layout ---------------------------------------------------------------------------------

    /** Sizes the preview to the camera's aspect ratio so the image is never stretched. */
    private fun layoutPreview(cfg: CameraSession.Config) {
        val size = cfg.previewSize
        // Buffers are landscape; the activity is portrait.
        val ratio = maxOf(size.width, size.height).toFloat() / minOf(size.width, size.height)
        val width = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = (width * ratio).roundToInt()
        val lp = previewContainer.layoutParams as FrameLayout.LayoutParams
        if (lp.height != height) {
            lp.height = height
            previewContainer.layoutParams = lp
        }
        applyLayout()
    }

    /**
     * Places everything around the preview, from the bottom up: the row with the
     * thumbnail, modes and flip button, the shutter above it, then the preview under a black
     * band (about a fifth of the screen when there is room).
     */
    private fun applyLayout() {
        val screenHeight = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val previewHeight = (previewContainer.layoutParams as FrameLayout.LayoutParams).height
            .takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels * 4 / 3)

        val topBarHeight = 52.dpi
        (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = bars.top
        topBar.requestLayout()
        toast.topOffset = bars.top + topBarHeight + 8.dpi
        (settingsPanel.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + 8.dpi
        settingsPanel.requestLayout()

        val rowHeight = 52.dpi
        val rowTop = screenHeight - bars.bottom - 14.dpi - rowHeight
        (bottomBar.layoutParams as FrameLayout.LayoutParams).topMargin = rowTop
        bottomBar.requestLayout()
        val shutterSize = 80.dpi
        val lowestShutterTop = rowTop - 10.dpi - shutterSize

        // 4:3 fits between the top bar and the shutter; taller previews (16:9) are centered
        // behind the controls.
        val underTopBar = bars.top + topBarHeight
        val highestFit = lowestShutterTop - 12.dpi - previewHeight
        val previewTop = if (highestFit >= underTopBar) {
            minOf(highestFit, maxOf(underTopBar, (screenHeight * PREVIEW_TOP_FRACTION).roundToInt()))
        } else {
            ((screenHeight - previewHeight) / 2).coerceAtLeast(0)
        }
        (previewContainer.layoutParams as FrameLayout.LayoutParams).topMargin = previewTop
        previewContainer.requestLayout()
        val previewBottom = previewTop + previewHeight

        // The shutter sits in the middle of the space left under the preview.
        val shutterTop = if (previewBottom <= lowestShutterTop) {
            ((previewBottom + rowTop - shutterSize) / 2).coerceAtMost(lowestShutterTop)
        } else {
            lowestShutterTop
        }
        (shutter.layoutParams as FrameLayout.LayoutParams).topMargin = shutterTop
        shutter.requestLayout()

        val zoomHeight = 46.dpi
        val zoomBottom = minOf(previewBottom - 8.dpi, shutterTop - 6.dpi)
        (zoomControl.layoutParams as FrameLayout.LayoutParams).topMargin = zoomBottom - zoomHeight
        zoomControl.requestLayout()
    }

    // --- Gestures -------------------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun setUpGestures() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var pinchZoom = 1f
        var pinching = false
        var downX = 0f
        var downY = 0f
        var axis = 0 // 0 undecided, 1 horizontal (mode swipe), 2 vertical (exposure)
        var startEv = 0f

        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (lens.facing == Lens.Facing.FRONT || !previewRunning) return false
                pinching = true
                pinchZoom = displayZoom
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                pinchZoom *= detector.scaleFactor
                val clamped = pinchZoom.coerceIn(minDisplayZoom(), maxDisplayZoom())
                if (abs(clamped - displayZoom) > 0.005f) {
                    displayZoom = clamped
                    zoomControl.setZoom(displayZoom)
                    session.setZoom(lensZoomRatio())
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Lens switches wait for the end of the pinch: reopening mid-gesture would stutter.
                val ultraWide = inventory.backUltraWide
                if (lens === inventory.backMain && hasUltraWide && pinchZoom < 0.85f) {
                    Haptics.tick(zoomControl)
                    switchToLens(ultraWide!!, inventory.ultraWideFactor, reopen = true)
                } else if (lens === ultraWide && pinchZoom > 1.02f) {
                    Haptics.tick(zoomControl)
                    switchToLens(inventory.backMain!!, 1f, reopen = true)
                }
            }
        })

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (settingsPanel.isOpen) {
                    settingsPanel.close()
                    updateSettingsChevron()
                    return true
                }
                focusAt(e.x, e.y, lock = false)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (pinching || axis != 0) return
                focusAt(e.x, e.y, lock = true)
                Haptics.heavy(previewContainer)
                toast.show(getString(R.string.ae_af_lock), accent = true)
            }
        })

        previewContainer.setOnTouchListener { _, event ->
            if (!previewRunning) return@setOnTouchListener true
            scale.onTouchEvent(event)
            if (event.pointerCount > 1 || pinching) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) pinching = false
                // Keep the gesture detector from firing a tap or long-press under a pinch.
                gestures.cancel(event)
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    axis = 0
                    startEv = exposureEv
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (axis == 0 && (abs(dx) > slop || abs(dy) > slop)) {
                        axis = if (abs(dx) > abs(dy)) 1 else if (focusIndicator.isShowing) 2 else 3
                    }
                    if (axis == 2) {
                        exposureEv = (startEv - dy / EXPOSURE_DRAG_DISTANCE.dp * FocusIndicatorView.MAX_EV)
                            .coerceIn(-FocusIndicatorView.MAX_EV, FocusIndicatorView.MAX_EV)
                        focusIndicator.setExposure(exposureEv)
                        session.setExposure(exposureEv)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    if (axis == 1 && abs(dx) > MODE_SWIPE_DISTANCE.dp && lockedMode == null && recording == null && panorama == null) {
                        val index = MODES.indexOf(mode) + if (dx < 0) 1 else -1
                        if (index in MODES.indices) {
                            Haptics.tick(modeSwitch)
                            setMode(MODES[index])
                        }
                    }
                }
            }
            if (axis == 0) gestures.onTouchEvent(event) else gestures.cancel(event)
            true
        }
    }

    private fun GestureDetector.cancel(from: MotionEvent) {
        val cancel = MotionEvent.obtain(from).apply { action = MotionEvent.ACTION_CANCEL }
        onTouchEvent(cancel)
        cancel.recycle()
    }

    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        exposureEv = 0f
        session.setExposure(0f)
        focusIndicator.showAt(x, y, withExposure = true, autoHide = !lock)
        session.focusAt(x / previewContainer.width, y / previewContainer.height, lock)
    }

    private companion object {
        const val TAG = "CameraActivity"
        const val REQUEST_PERMISSIONS = 1
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 1_200L
        const val MAX_ZOOM = 10f
        /** Finger travel (dp) for the exposure sun to go from 0 to ±MAX_EV. */
        const val EXPOSURE_DRAG_DISTANCE = 160
        const val MODE_SWIPE_DISTANCE = 60
        /** Night mode burst length: about a second of exposures at the dark frame rate. */
        const val NIGHT_FRAMES = 6
        val MODES = listOf(CaptureMode.TIMELAPSE, CaptureMode.VIDEO, CaptureMode.PHOTO, CaptureMode.PORTRAIT, CaptureMode.PANO)

        fun modeLabel(mode: CaptureMode) = when (mode) {
            CaptureMode.TIMELAPSE -> R.string.mode_timelapse
            CaptureMode.VIDEO -> R.string.mode_video
            CaptureMode.PHOTO -> R.string.mode_photo
            CaptureMode.PORTRAIT -> R.string.mode_portrait
            CaptureMode.PANO -> R.string.mode_pano
        }
        /** Where the 4:3 preview starts, as a fraction of the screen height. */
        const val PREVIEW_TOP_FRACTION = 0.19f
    }
}
