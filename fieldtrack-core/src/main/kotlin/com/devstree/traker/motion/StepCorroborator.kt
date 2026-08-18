package com.devstree.traker.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.devstree.traker.SensorConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * Independent physical evidence of walking.
 *
 * Stationary drift is *displacement without motion*, and every other defence against it
 * is statistical — wobble guards, R-penalties, net-displacement persistence — because
 * position is the only evidence available. The pedometer is different in kind: it is
 * hardware-batched, near-zero power, and **cannot be fooled by multipath**. Zero steps
 * across a 60 m indoor excursion proves drift; 30 steps proves a walk the Doppler never
 * saw. No competing SDK uses this (SDK-COMPARISON §6.2, EC-133).
 *
 * Counts are consumed by [com.devstree.traker.capture.FixIngestor] via
 * `IngestContext.stepsSinceLastPoint` and evaluated in pipeline stage 2a.
 */
internal class StepCorroborator(
    context: Context,
) : SensorEventListener {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val stepDetector: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

    private val stepsSinceLastPoint = AtomicInteger(0)
    private var registered = false

    val isAvailable: Boolean get() = stepDetector != null

    /**
     * Registered only while a session is active, and batched by the sensor hub so the
     * application processor never wakes for a step event (EC-138).
     */
    fun start(config: SensorConfig) {
        val sensor = stepDetector ?: return
        val manager = sensorManager ?: return
        if (registered) return

        registered = manager.registerListener(
            this,
            sensor,
            SensorManager.SENSOR_DELAY_NORMAL,
            (config.stepBatchLatencyMs * MICROS_PER_MILLI).toInt(),
        )
    }

    fun stop() {
        if (!registered) return
        sensorManager?.unregisterListener(this)
        registered = false
        stepsSinceLastPoint.set(0)
    }

    /** Reads the tally and resets it, so each point sees only its own interval. */
    fun consumeSteps(): Int? {
        if (!registered) return null
        return stepsSinceLastPoint.getAndSet(0)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        // STEP_DETECTOR emits one event per step, so deltas need no baseline. This is
        // deliberately preferred over STEP_COUNTER, whose value is since-boot and
        // therefore resets on reboot — a naive delta there goes hugely negative (EC-136).
        if (event?.sensor?.type == Sensor.TYPE_STEP_DETECTOR) {
            stepsSinceLastPoint.incrementAndGet()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int): Unit = Unit

    private companion object {
        const val MICROS_PER_MILLI = 1_000L
    }
}
