package dev.mycam.util

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Process-wide single worker for disk work (saving captures, querying MediaStore). Being
 * process-wide, a save started just before the activity finishes still completes.
 */
object Background {
    val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MyCam-io").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    /** Heavy image processing (night merge, portrait, panorama), kept off the disk queue. */
    val compute: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "MyCam-compute").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val main = Handler(Looper.getMainLooper())

    /** Runs [work] on [io] and delivers its result on the main thread. */
    fun <T> run(work: () -> T, then: (T) -> Unit) = run(io, work, then)

    fun <T> run(executor: ExecutorService, work: () -> T, then: (T) -> Unit) {
        executor.execute {
            val result = work()
            main.post { then(result) }
        }
    }
}
