package com.devstree.traker.motion

import com.devstree.traker.domain.model.TrakerGeofence

/**
 * The seams between motion *decisions* and the Android hardware that serves them.
 *
 * `MotionController` decides; these three interfaces do. Depending on abstractions
 * rather than on `SensorManager` and `GeofencingClient` directly is what makes the
 * controller's wiring testable at all — and the wiring is where the interesting bugs
 * live, since the state machine underneath is already covered in `trackit-geo`.
 *
 * All three are genuinely optional at runtime: the sensor may not exist, geofence
 * registration fails for real reasons, and `MOTION_ONLY` deliberately stops the stream.
 * Each implementation degrades rather than throwing.
 */

/**
 * A hardware wake path out of STATIONARY.
 *
 * Implemented by [SignificantMotionWake]. Trigger sensors are one-shot by contract, so
 * an implementation must re-arm itself after firing (EC-132).
 */
internal interface MotionWakeSource {
    val isAvailable: Boolean
    fun arm(onMotion: () -> Unit)
    fun disarm()
}

/**
 * A system-registered fence around the stop anchor.
 *
 * Implemented by [StationaryFence]. Registration genuinely fails — GMS errors, the
 * 100-geofence per-app cap — and an implementation must degrade to the heartbeat path
 * rather than propagate (EC-58).
 */
internal interface GeofenceRegistrar {
    fun register(geofence: TrakerGeofence): Boolean
    fun unregister(id: String): Boolean
}

/**
 * The location stream, as the motion layer sees it.
 *
 * Implemented by [com.devstree.traker.capture.LocationStreamController]. Narrow on
 * purpose: motion may change *cadence* and, in `MOTION_ONLY`, whether the stream runs
 * at all — but it can never gate capture itself, because activity recognition is wrong
 * often enough to lose whole trips (EC-53).
 */
internal interface CaptureStream {
    fun onMoving()
    fun onStationary()
    fun setVehicular(vehicular: Boolean)
}
