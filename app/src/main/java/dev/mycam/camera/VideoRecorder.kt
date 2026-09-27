package dev.mycam.camera

import android.content.Context
import android.media.CamcorderProfile
import android.media.MediaCodec
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileDescriptor

/**
 * Thin wrapper around [MediaRecorder] fed by a persistent input surface.
 *
 * The persistent surface is part of the capture session from the moment video mode is entered,
 * so starting a recording is instant: no session reconfiguration, no preview glitch.
 *
 * With a [captureRate] the recorder makes a time-lapse: the encoder keeps one camera frame every
 * 1/captureRate second and plays them back at the normal frame rate, without sound.
 */
class VideoRecorder(
    private val context: Context,
    private val lens: Lens,
    val size: Size,
    private val captureRate: Double? = null,
) {
    val surface: Surface = MediaCodec.createPersistentInputSurface()

    private val profile: CamcorderProfile? = findProfile()
    private var recorder: MediaRecorder? = null

    val isRecording: Boolean get() = recorder != null

    init {
        // Preparing (and discarding) a recorder once gives the persistent surface its final
        // buffer size and format before the camera session is created with it.
        val dummy = File(context.cacheDir, "prime.mp4")
        createRecorder(withAudio = false).apply {
            setOutputFile(dummy.absolutePath)
            prepare()
            release()
        }
        dummy.delete()
    }

    fun start(output: FileDescriptor, orientationHint: Int, withAudio: Boolean) {
        check(recorder == null) { "Already recording" }
        val r = createRecorder(withAudio)
        try {
            r.setOutputFile(output)
            r.setOrientationHint(orientationHint)
            r.prepare()
            r.start()
            recorder = r
        } catch (e: Exception) {
            r.release()
            throw e
        }
    }

    /** @return true when a playable file was written. */
    fun stop(): Boolean {
        val r = recorder ?: return false
        recorder = null
        return try {
            r.stop()
            true
        } catch (e: RuntimeException) {
            // Thrown when stop() happens before any frame was encoded.
            Log.w(TAG, "Recording too short, discarded", e)
            false
        } finally {
            r.release()
        }
    }

    fun release() {
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        surface.release()
    }

    private fun createRecorder(withAudio: Boolean): MediaRecorder {
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()

        val audio = withAudio && captureRate == null
        if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)

        val fps = profile?.videoFrameRate?.coerceAtMost(30) ?: 30
        r.setVideoEncodingBitRate(profile?.videoBitRate ?: defaultBitRate(fps))
        r.setVideoFrameRate(fps)
        captureRate?.let { r.setCaptureRate(it) }
        r.setVideoSize(size.width, size.height)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)

        if (audio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(profile?.audioBitRate ?: 128_000)
            r.setAudioSamplingRate(profile?.audioSampleRate ?: 44_100)
            r.setAudioChannels(profile?.audioChannels ?: 1)
        }
        r.setInputSurface(surface)
        return r
    }

    private fun defaultBitRate(fps: Int): Int =
        (size.width.toLong() * size.height * fps * 0.2).toInt().coerceIn(2_000_000, 20_000_000)

    @Suppress("DEPRECATION")
    private fun findProfile(): CamcorderProfile? {
        val id = lens.id.toIntOrNull() ?: return null
        val quality = when (size.height) {
            1080 -> CamcorderProfile.QUALITY_1080P
            720 -> CamcorderProfile.QUALITY_720P
            480 -> CamcorderProfile.QUALITY_480P
            else -> return null
        }
        return try {
            if (CamcorderProfile.hasProfile(id, quality)) CamcorderProfile.get(id, quality) else null
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val TAG = "VideoRecorder"
    }
}
