package dev.mycam.ui.camera

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.mycam.R
import dev.mycam.camera.CaptureMode
import dev.mycam.media.MediaSaver
import dev.mycam.ui.glass.GlassView
import dev.mycam.util.Background
import dev.mycam.util.dpi
import java.io.ByteArrayInputStream

/**
 * Answers `IMAGE_CAPTURE` / `VIDEO_CAPTURE` from other apps: the same camera screen, locked to
 * one mode, followed by a "Retake / Use photo" review for photos. The result goes to
 * `EXTRA_OUTPUT` when the caller provided one, as the specification asks.
 */
class CaptureIntentActivity : CameraActivity() {

    private val isVideo get() = intent.action == MediaStore.ACTION_VIDEO_CAPTURE
    private val output: Uri? get() = extraOutput(intent)

    override val lockedMode: CaptureMode get() = if (isVideo) CaptureMode.VIDEO else CaptureMode.PHOTO
    override val showsThumbnail: Boolean = false

    private var review: FrameLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (review != null) closeReview() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    // --- Photo ----------------------------------------------------------------------------------

    override fun onPhotoCaptured(jpeg: ByteArray, square: Boolean) {
        Background.run({
            val data = if (square) saver.cropToSquare(jpeg) ?: jpeg else jpeg
            data to decodeUpright(data, resources.displayMetrics.widthPixels)
        }) { (data, preview) ->
            if (preview == null) toast.show(getString(R.string.photo_failed)) else showReview(data, preview)
        }
    }

    private fun showReview(jpeg: ByteArray, preview: Bitmap) {
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            isClickable = true
            alpha = 0f
        }
        overlay.addView(
            ImageView(this).apply {
                setImageBitmap(preview)
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16.dpi, 0, 16.dpi, 0)
            addView(button(R.string.retake, accent = false) { closeReview() })
            addView(button(R.string.use_photo, accent = true) { deliverPhoto(jpeg) })
        }
        overlay.addView(
            buttons,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ).apply { bottomMargin = 48.dpi },
        )
        root.addView(overlay)
        overlay.animate().alpha(1f).setDuration(200).start()
        review = overlay
    }

    private fun closeReview() {
        val overlay = review ?: return
        review = null
        overlay.animate().alpha(0f).setDuration(180).withEndAction { root.removeView(overlay) }.start()
    }

    private fun button(textRes: Int, accent: Boolean, onClick: () -> Unit) = GlassView(this).apply {
        interactive = true
        addView(
            TextView(context).apply {
                setText(textRes)
                textSize = 16f
                setTextColor(if (accent) getColor(R.color.ios_yellow) else getColor(R.color.white))
                gravity = Gravity.CENTER
                setPadding(24.dpi, 0, 24.dpi, 0)
            },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 48.dpi, Gravity.CENTER),
        )
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, 48.dpi, 1f).apply {
            marginStart = 6.dpi
            marginEnd = 6.dpi
        }
    }

    private fun deliverPhoto(jpeg: ByteArray) {
        val target = output
        Background.run({
            if (target != null) {
                runCatching {
                    contentResolver.openOutputStream(target, "w")!!.use { it.write(jpeg) }
                }.onFailure { Log.e(TAG, "Unable to write $target", it) }.isSuccess to null
            } else {
                // No output requested: the spec says to return a small bitmap in "data".
                true to decodeUpright(jpeg, THUMBNAIL_SIZE)
            }
        }) { (ok, thumbnail) ->
            if (!ok) {
                toast.show(getString(R.string.storage_full))
                return@run
            }
            val result = Intent()
            if (thumbnail != null) result.putExtra("data", thumbnail)
            if (target != null) result.data = target
            setResult(RESULT_OK, result)
            finish()
        }
    }

    // --- Video ----------------------------------------------------------------------------------

    override fun openVideoOutput(): MediaSaver.PendingVideo? {
        val target = output ?: return saver.createVideo()
        return try {
            contentResolver.openFileDescriptor(target, "rw")?.let { MediaSaver.PendingVideo(target, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to open $target", e)
            null
        }
    }

    override fun finishVideoOutput(output: MediaSaver.PendingVideo, success: Boolean): Uri? =
        if (this.output == null) {
            saver.finishVideo(output, success)
        } else {
            runCatching { output.descriptor.close() }
            if (success) output.uri else null
        }

    override fun onVideoHandled(uri: Uri?) {
        if (uri == null) return
        setResult(RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        finish()
    }

    // --- Helpers --------------------------------------------------------------------------------

    /** Decodes [jpeg] to roughly [maxSide] pixels, applying the EXIF rotation. */
    private fun decodeUpright(jpeg: ByteArray, maxSide: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
        val degrees = ExifInterface(ByteArrayInputStream(jpeg)).rotationDegrees
        if (degrees == 0 || bitmap == null) bitmap
        else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    } catch (e: Exception) {
        Log.e(TAG, "Decode failed", e)
        null
    }

    private val ExifInterface.rotationDegrees: Int
        get() = when (getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

    private companion object {
        const val TAG = "CaptureIntent"
        const val THUMBNAIL_SIZE = 256

        fun extraOutput(intent: Intent): Uri? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
            }
    }
}
