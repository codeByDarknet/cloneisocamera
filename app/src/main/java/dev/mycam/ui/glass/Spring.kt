package dev.mycam.ui.glass

import android.view.Choreographer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Damped spring driven by [Choreographer], used for every glass interaction (press, morph,
 * slide). Retargeting mid-flight keeps the velocity, so interrupted gestures stay fluid.
 *
 * @param stiffness spring constant (higher = faster).
 * @param dampingRatio 1 = no overshoot, < 1 = bouncy.
 */
class Spring(
    initial: Float,
    private val stiffness: Float = 380f,
    private val dampingRatio: Float = 0.72f,
    private val onUpdate: (Float) -> Unit,
) : Choreographer.FrameCallback {

    var value: Float = initial
        private set
    var target: Float = initial
        private set

    private var velocity = 0f
    private var lastFrameNanos = 0L
    private var running = false

    fun animateTo(newTarget: Float) {
        target = newTarget
        if (!running) {
            running = true
            lastFrameNanos = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun snapTo(newValue: Float) {
        cancel()
        value = newValue
        target = newValue
        velocity = 0f
        onUpdate(value)
    }

    fun cancel() {
        if (running) Choreographer.getInstance().removeFrameCallback(this)
        running = false
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameNanos == 0L) lastFrameNanos = frameTimeNanos - FRAME_NANOS
        var dt = ((frameTimeNanos - lastFrameNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastFrameNanos = frameTimeNanos

        // Semi-implicit Euler in small sub-steps: stable for the stiffness values in use.
        val damping = 2f * dampingRatio * sqrt(stiffness)
        while (dt > 0f) {
            val step = minOf(dt, MAX_STEP)
            val force = -stiffness * (value - target) - damping * velocity
            velocity += force * step
            value += velocity * step
            dt -= step
        }

        if (abs(value - target) < REST_DISTANCE && abs(velocity) < REST_VELOCITY) {
            value = target
            velocity = 0f
            running = false
            onUpdate(value)
            return
        }
        onUpdate(value)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private companion object {
        const val FRAME_NANOS = 16_666_667L
        const val MAX_STEP = 1f / 240f
        const val REST_DISTANCE = 0.0005f
        const val REST_VELOCITY = 0.005f
    }
}
