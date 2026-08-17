package com.devstree.trackit.geo.motion

import com.devstree.trackit.geo.math.Haversine
import com.devstree.trackit.geo.model.ActivityType
import com.devstree.trackit.geo.model.GeoPoint
import com.devstree.trackit.geo.model.MotionState

/**
 * `STOPPED → MOVING ⇄ STOP_PENDING ⇄ STATIONARY`.
 *
 * Pure and clock-injected: every transition is a function of the previous state and
 * one event, so the whole machine is testable on the JVM with a fake clock (T2).
 *
 * **Motion detection gates the sampling rate, never capture itself.** The incumbent
 * treats the motion API as authoritative — if it says still, location goes off. On
 * Android that loses whole trips: the reference implementation documents 17-minute
 * drives during which activity recognition reported `STILL` on OnePlus and Xiaomi
 * devices under battery saver (EC-53). So [MotionEvent.ActivityEnter] can only
 * *accelerate* a transition; it can never veto one that measured motion justifies.
 *
 * Four independent triggers can reach [MotionState.MOVING], any one of which suffices
 * (SDK-COMPARISON §3):
 *  - activity-recognition ENTER for a moving class;
 *  - a significant-motion hardware interrupt (permission-free, works with no Play
 *    Services and no AR permission — EC-132);
 *  - exit from the stationary wake fence;
 *  - a heartbeat fix landing beyond `stationaryRadiusM` from the anchor — the safety
 *    net that self-corrects a device whose AR is broken *and* whose geofence never
 *    fired, within one heartbeat instead of never (EC-57).
 */
public class MotionStateMachine(
    private val stopTimeoutMs: Long = DEFAULT_STOP_TIMEOUT_MS,
    private val stationaryRadiusM: Double = DEFAULT_STATIONARY_RADIUS_M,
    private val motionTriggerDelayMs: Long = 0L,
) {

    /** Immutable snapshot; replaced wholesale, same discipline as `FilterState`. */
    public data class State(
        val motion: MotionState = MotionState.STOPPED,
        val anchor: GeoPoint? = null,
        val stopPendingSinceNanos: Long = 0L,
        val movingSinceNanos: Long = 0L,
        /**
         * Nullable rather than a 0 sentinel: `elapsedRealtimeNanos` is 0 at boot, so a
         * trigger firing at monotonic zero would be indistinguishable from "no move
         * pending" and the delayed transition would never commit.
         */
        val pendingMoveSinceNanos: Long? = null,
    )

    public data class Transition(
        val state: State,
        /** Non-null only when [State.motion] actually changed — emit an event then, and
         *  only then, so `changePace(true)` while already moving is a no-op (EC-59). */
        val changedTo: MotionState? = null,
    )

    public fun onEvent(state: State, event: MotionEvent, nowNanos: Long): Transition = when (event) {
        is MotionEvent.AcceptedFix -> onAcceptedFix(state, event, nowNanos)
        is MotionEvent.ActivityEnter -> onActivityEnter(state, event, nowNanos)
        is MotionEvent.SignificantMotion -> toMoving(state, nowNanos)
        is MotionEvent.StationaryFenceExit -> toMoving(state, nowNanos)
        is MotionEvent.ChangePace -> if (event.moving) toMoving(state, nowNanos) else toStationary(state, nowNanos)
        is MotionEvent.Tick -> onTick(state, nowNanos)
    }

    private fun onAcceptedFix(state: State, event: MotionEvent.AcceptedFix, nowNanos: Long): Transition {
        if (event.isMoving) return toMoving(state, nowNanos)

        // A stationary fix beyond the radius means the anchor is stale — the user moved
        // without any wake path firing. Treat it as movement (EC-57).
        val anchor = state.anchor
        if (anchor != null) {
            val drift = Haversine.metres(anchor.latitude, anchor.longitude, event.latitude, event.longitude)
            if (drift > stationaryRadiusM) return toMoving(state, nowNanos)
        }

        // Not moving. Open the stop-pending window rather than committing to stationary,
        // so a traffic light does not fragment the track into false stops (EC-56).
        return when (state.motion) {
            MotionState.MOVING -> Transition(
                state.copy(
                    motion = MotionState.STOP_PENDING,
                    stopPendingSinceNanos = nowNanos,
                    anchor = GeoPoint(event.latitude, event.longitude),
                ),
                changedTo = MotionState.STOP_PENDING,
            )
            else -> Transition(state)
        }
    }

    private fun onActivityEnter(state: State, event: MotionEvent.ActivityEnter, nowNanos: Long): Transition =
        when {
            event.activity.isMovingClass -> {
                // ENTER only cancels a pending stop; committing to MOVING still needs a
                // fix that passed the pipeline, so a phone vibrating on a desk near a
                // road cannot fake a trip (EC-55).
                if (state.motion == MotionState.STOP_PENDING) {
                    Transition(state.copy(motion = MotionState.MOVING, stopPendingSinceNanos = 0L), MotionState.MOVING)
                } else {
                    toMoving(state, nowNanos)
                }
            }
            event.activity == ActivityType.STILL && state.motion == MotionState.MOVING ->
                Transition(
                    state.copy(motion = MotionState.STOP_PENDING, stopPendingSinceNanos = nowNanos),
                    MotionState.STOP_PENDING,
                )
            else -> Transition(state)
        }

    private fun onTick(state: State, nowNanos: Long): Transition {
        // Commit a delayed move once the trigger delay has elapsed and nothing settled
        // it back — damps thrash for a delivery rider with 20 stops an hour (EC-60).
        val pendingSince = state.pendingMoveSinceNanos
        if (pendingSince != null &&
            nowNanos - pendingSince >= motionTriggerDelayMs * NANOS_PER_MILLI
        ) {
            return Transition(
                state.copy(
                    motion = MotionState.MOVING,
                    movingSinceNanos = nowNanos,
                    pendingMoveSinceNanos = null,
                    stopPendingSinceNanos = 0L,
                ),
                MotionState.MOVING,
            )
        }

        if (state.motion == MotionState.STOP_PENDING &&
            nowNanos - state.stopPendingSinceNanos >= stopTimeoutMs * NANOS_PER_MILLI
        ) {
            return toStationary(state, nowNanos)
        }

        return Transition(state)
    }

    private fun toMoving(state: State, nowNanos: Long): Transition {
        if (state.motion == MotionState.MOVING) return Transition(state)

        if (motionTriggerDelayMs > 0L && state.pendingMoveSinceNanos == null) {
            return Transition(state.copy(pendingMoveSinceNanos = nowNanos))
        }

        return Transition(
            state.copy(
                motion = MotionState.MOVING,
                movingSinceNanos = nowNanos,
                stopPendingSinceNanos = 0L,
                pendingMoveSinceNanos = null,
            ),
            changedTo = MotionState.MOVING,
        )
    }

    private fun toStationary(state: State, nowNanos: Long): Transition {
        if (state.motion == MotionState.STATIONARY) return Transition(state)
        return Transition(
            state.copy(
                motion = MotionState.STATIONARY,
                stopPendingSinceNanos = nowNanos,
                pendingMoveSinceNanos = null,
            ),
            changedTo = MotionState.STATIONARY,
        )
    }

    public companion object {
        public const val DEFAULT_STOP_TIMEOUT_MS: Long = 5 * 60 * 1000L
        public const val DEFAULT_STATIONARY_RADIUS_M: Double = 150.0
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Everything that can move the machine. */
public sealed interface MotionEvent {
    public data class AcceptedFix(
        val latitude: Double,
        val longitude: Double,
        val isMoving: Boolean,
    ) : MotionEvent

    public data class ActivityEnter(val activity: ActivityType) : MotionEvent

    /** Hardware trigger sensor. One-shot by contract — the platform layer must re-arm. */
    public data object SignificantMotion : MotionEvent

    public data object StationaryFenceExit : MotionEvent

    /** Host-driven override, `changePace(moving)`. */
    public data class ChangePace(val moving: Boolean) : MotionEvent

    /** Clock advance; drives the stop timeout and the trigger delay. */
    public data object Tick : MotionEvent
}

private val ActivityType.isMovingClass: Boolean
    get() = this == ActivityType.IN_VEHICLE ||
        this == ActivityType.ON_BICYCLE ||
        this == ActivityType.WALKING ||
        this == ActivityType.RUNNING ||
        this == ActivityType.ON_FOOT
