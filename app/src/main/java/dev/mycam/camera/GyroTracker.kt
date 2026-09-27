package dev.mycam.camera

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread

/**
 * Integrates the gyroscope into the two angles a portrait panorama needs, in radians since
 * [start]: [yaw] grows when the phone turns right, [pitch] when it tilts up.
 *
 * A short history is kept so that a camera frame can be matched with the angle the phone had
 * when the frame was exposed (camera frames reach the app 50 to 100 ms late).
 */
class GyroTracker(context: Context) {

    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var thread: HandlerThread? = null

    private val times = LongArray(HISTORY)
    private val yaws = FloatArray(HISTORY)
    private val pitches = FloatArray(HISTORY)
    private var count = 0
    private var head = 0
    private var lastTimestamp = 0L
    private var yawNow = 0f
    private var pitchNow = 0f

    val isAvailable: Boolean get() = gyroscope != null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            synchronized(this@GyroTracker) {
                if (lastTimestamp != 0L) {
                    val dt = (event.timestamp - lastTimestamp) / 1e9f
                    // Portrait: turning right is a negative rotation about the device's Y axis,
                    // tilting the rear camera up a positive one about its X axis.
                    yawNow -= event.values[1] * dt
                    pitchNow += event.values[0] * dt
                }
                lastTimestamp = event.timestamp
                times[head] = event.timestamp
                yaws[head] = yawNow
                pitches[head] = pitchNow
                head = (head + 1) % HISTORY
                count = minOf(count + 1, HISTORY)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    fun start() {
        val sensor = gyroscope ?: return
        stop()
        synchronized(this) {
            count = 0
            head = 0
            lastTimestamp = 0L
            yawNow = 0f
            pitchNow = 0f
        }
        val t = HandlerThread("Gyro").apply { start() }
        thread = t
        sensors?.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, Handler(t.looper))
    }

    fun stop() {
        sensors?.unregisterListener(listener)
        thread?.quitSafely()
        thread = null
    }

    /**
     * (yaw, pitch) at [timestampNs] (elapsedRealtimeNanos base), interpolated from the history;
     * the latest angles when the timestamp is unknown or outside it.
     */
    @Synchronized
    fun anglesAt(timestampNs: Long?): FloatArray {
        if (timestampNs == null || count < 2) return floatArrayOf(yawNow, pitchNow)
        var newer = -1
        for (k in 0 until count) {
            val i = (head - 1 - k + HISTORY) % HISTORY
            if (times[i] <= timestampNs) {
                if (newer < 0) return floatArrayOf(yawNow, pitchNow)
                val t = (timestampNs - times[i]).toFloat() / (times[newer] - times[i]).coerceAtLeast(1)
                return floatArrayOf(
                    yaws[i] + (yaws[newer] - yaws[i]) * t,
                    pitches[i] + (pitches[newer] - pitches[i]) * t,
                )
            }
            newer = i
        }
        val oldest = (head - count + HISTORY) % HISTORY
        return floatArrayOf(yaws[oldest], pitches[oldest])
    }

    private companion object {
        /** About one second of samples at the fastest rate. */
        const val HISTORY = 512
    }
}
