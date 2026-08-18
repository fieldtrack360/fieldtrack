package com.devstree.traker.motion

import com.devstree.traker.TrackerConfig
import com.devstree.traker.sdkLog
import com.devstree.traker.domain.model.TrackerEvent
import com.devstree.traker.domain.model.TrackerGeofence
import com.devstree.traker.geo.model.ActivityType
import com.devstree.traker.geo.model.MotionState
import com.devstree.traker.geo.model.MovementStatus
import com.devstree.traker.geo.model.TrackPoint
import com.devstree.traker.geo.motion.MotionEvent
import com.devstree.traker.geo.motion.MotionStateMachine
import com.devstree.traker.geo.port.Clock
import com.devstree.traker.geo.port.TrackLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/**
 * The Android side of motion detection: owns the pure [MotionStateMachine]'s state and
 * turns its transitions into hardware actions.
 *
 * The division is deliberate. Every *decision* lives in `fieldtrack-geo` where it is
 * JVM-testable; this class only arms sensors, registers fences and changes cadence
 * (PLAN.md §3 invariant 1).
 *
 * Events arrive from four independent places — the ingest coroutine, an activity-
 * recognition broadcast, a sensor-hub interrupt on a hardware thread, and a geofence
 * broadcast. They are funnelled through one [Channel] with a single consumer, the same
 * shape as [com.devstree.traker.capture.FixIngestor], so there is no interleaving and
 * no lock. That is precisely what the reference's static, non-atomically-updated motion
 * state got wrong (SOURCE-AUDIT A6).
 */
internal class MotionController(
    private val machine: MotionStateMachine,
    private val streamController: CaptureStream,
    private val significantMotion: MotionWakeSource,
    private val stationaryFence: GeofenceRegistrar,
    private val clock: Clock,
    private val events: MutableSharedFlow<TrackerEvent>,
    private val logger: TrackLogger,
    private val scope: CoroutineScope,
) {

    private val inbox = Channel<MotionEvent>(
        capacity = INBOX_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private var consumerJob: Job? = null
    private var state = MotionStateMachine.State()
    private var config: TrackerConfig? = null
    private var lastPoint: TrackPoint? = null

    val motionState: MotionState get() = state.motion

    fun start(config: TrackerConfig) {
        this.config = config
        state = MotionStateMachine.State()
        lastPoint = null

        consumerJob?.cancel()
        consumerJob = scope.launch {
            for (event in inbox) handle(event)
        }

        // Armed immediately: a session that starts with the user already parked still
        // needs a wake path, and significant motion is the one needing no permission
        // and no Play Services (EC-132).
        if (config.sensors.useSignificantMotion && significantMotion.isAvailable) {
            armSignificantMotion()
        }
    }

    fun stop() {
        consumerJob?.cancel()
        consumerJob = null
        significantMotion.disarm()
        config?.let { stationaryFence.unregister(it.motion.stationaryGeofenceId) }
        config = null
        state = MotionStateMachine.State()
        lastPoint = null
    }

    /** Fed for every point the pipeline accepted. */
    fun onAcceptedPoint(point: TrackPoint) {
        lastPoint = point
        offer(
            MotionEvent.AcceptedFix(
                latitude = point.latitude,
                longitude = point.longitude,
                isMoving = point.movementStatus == MovementStatus.MOVING,
            ),
        )
    }

    /** AR is enrichment: it may accelerate a transition, never veto one (EC-53). */
    fun onActivityTransition(activity: ActivityType) = offer(MotionEvent.ActivityEnter(activity))

    fun onStationaryFenceExit() = offer(MotionEvent.StationaryFenceExit)

    fun onChangePace(moving: Boolean) = offer(MotionEvent.ChangePace(moving))

    /** Drives the stop timeout and any deferred move; called from the health loop. */
    fun tick() = offer(MotionEvent.Tick)

    private fun offer(event: MotionEvent) {
        inbox.trySend(event)
    }

    private fun armSignificantMotion() {
        // Fires on a hardware callback thread — do nothing there but enqueue.
        significantMotion.arm { offer(MotionEvent.SignificantMotion) }
    }

    private fun handle(event: MotionEvent) {
        val active = config ?: return

        val transition = machine.onEvent(state, event, clock.elapsedRealtimeNanos())
        state = transition.state

        // Emitted only on a real transition, so changePace(true) while already moving
        // is a genuine no-op (EC-59).
        val changedTo = transition.changedTo ?: return

        sdkLog { logger.d(TAG, "Motion -> $changedTo") }
        events.tryEmit(TrackerEvent.MotionChange(changedTo, lastPoint))

        when (changedTo) {
            MotionState.MOVING -> onEnterMoving(active)
            MotionState.STATIONARY -> onEnterStationary(active)
            MotionState.STOP_PENDING, MotionState.STOPPED -> Unit
        }
    }

    private fun onEnterMoving(config: TrackerConfig) {
        // Wake paths that only make sense while parked come down; leaving a geofence and
        // a trigger sensor registered through a drive is pure battery cost (EC-138).
        significantMotion.disarm()
        stationaryFence.unregister(config.motion.stationaryGeofenceId)
        streamController.onMoving()
        streamController.setVehicular(true)
    }

    private fun onEnterStationary(config: TrackerConfig) {
        streamController.onStationary()

        if (config.sensors.useSignificantMotion && significantMotion.isAvailable) {
            armSignificantMotion()
        }

        // A system-registered fence survives process death — the one wake path that
        // still works after an OEM battery manager kills us.
        lastPoint?.let { point ->
            stationaryFence.register(
                TrackerGeofence(
                    id = config.motion.stationaryGeofenceId,
                    latitude = point.latitude,
                    longitude = point.longitude,
                    radiusM = config.motion.stationaryRadiusM,
                    onEnterEvent = config.motion.stationaryGeofenceOnEnterEvent,
                    onExitEvent = config.motion.stationaryGeofenceOnExitEvent,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "MotionController"
        const val INBOX_CAPACITY = 64
    }
}
