package com.field360.tracker.motion

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener

/**
 * A third wake path from STATIONARY to MOVING, independent of both Play Services and
 * runtime permissions.
 *
 * `TYPE_SIGNIFICANT_MOTION` is a hardware trigger: the sensor hub raises a one-shot
 * interrupt when the device has genuinely moved. It costs effectively nothing, needs
 * **no runtime permission**, and needs **no Google Play Services** — so it still works
 * when `ACTIVITY_RECOGNITION` is denied (EC-09), when Play Services is absent on Huawei
 * or AOSP (EC-19), and when geofence registration failed (EC-58). It also fires on
 * movement far below the 150 m stationary radius, so a user who walks to another room
 * is detected in seconds rather than at the next 15-minute heartbeat (EC-57).
 */
internal class SignificantMotionWake(
    context: Context,
) : MotionWakeSource {

    private val sensorManager: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private var listener: TriggerEventListener? = null

    override val isAvailable: Boolean get() = sensor != null

    /**
     * Trigger sensors are **one-shot by contract**: the platform auto-disables the
     * listener after it fires. Forgetting to re-arm inside `onTrigger` is exactly why
     * trigger sensors appear to "stop working" after the first transition (EC-132).
     */
    override fun arm(onMotion: () -> Unit) {
        val target = sensor ?: return
        val manager = sensorManager ?: return

        disarm()
        val triggerListener = object : TriggerEventListener() {
            override fun onTrigger(event: TriggerEvent?) {
                onMotion()
                arm(onMotion) // re-arm; the OS has already disabled this listener
            }
        }
        listener = triggerListener
        manager.requestTriggerSensor(triggerListener, target)
    }

    /** Disarmed on MOVING and in stop()/onDestroy — never left registered (EC-138). */
    override fun disarm() {
        val target = sensor ?: return
        listener?.let { sensorManager?.cancelTriggerSensor(it, target) }
        listener = null
    }
}
