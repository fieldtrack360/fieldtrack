package com.devstree.trackit.work

import com.devstree.trackit.ServiceConfig
import com.devstree.trackit.domain.model.ErrorCode
import com.devstree.trackit.domain.model.TrackItEvent
import com.devstree.trackit.geo.model.TrackFix
import com.devstree.trackit.geo.port.Clock
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Decides whether tracking is actually alive.
 *
 * **Liveness is judged on the raw fix clock, updated *before* the filter on every
 * fix — never on upload or accept recency.** This is the subtlety that makes naive
 * watchdogs fire constantly on parked users: a healthy stationary user accepts and
 * stores nothing by design, so "no points recently" is the expected state, not a
 * fault. The reference documents exactly this at `LocationTrackingManager.kt:378-383`
 * (EC-70).
 *
 * Everything here is pure given a clock, so the thresholds are unit-testable without
 * a device.
 */
internal class Watchdog(
    private val clock: Clock,
    private val events: MutableSharedFlow<TrackItEvent>,
) {

    // Nullable, not a 0 sentinel: elapsedRealtimeNanos is 0 at boot, so a genuine fix
    // arriving then would otherwise be read as "no fix yet" and the tracker could never
    // be declared dead. Same trap as MotionStateMachine.pendingMoveSinceNanos.
    @Volatile
    private var lastRawFixElapsedNanos: Long? = null

    @Volatile
    private var lastActionElapsedNanos: Long? = null

    /** Called from the ingest path for EVERY raw fix, accepted or not. */
    fun onRawFix(fix: TrackFix) {
        lastRawFixElapsedNanos = fix.elapsedRealtimeNanos
    }

    fun reset() {
        lastRawFixElapsedNanos = null
        lastActionElapsedNanos = null
    }

    /**
     * @param serviceRunning whether the foreground service is currently up.
     * @param hasOpenSession whether a session is open and therefore *should* be tracking.
     * @return the action the caller should take, already throttled.
     */
    fun tick(
        config: ServiceConfig,
        serviceRunning: Boolean,
        hasOpenSession: Boolean,
        moving: Boolean,
        powerSave: Boolean,
    ): Action {
        if (!hasOpenSession) return Action.None

        val now = clock.elapsedRealtimeNanos()

        // The service died while a session is open — OEM battery manager, low memory,
        // or an FGS start that was refused. Re-promote via expedited work (EC-69).
        if (!serviceRunning) {
            return throttled(now, config) { Action.RestoreService }
        }

        // No fix has arrived yet this session; nothing to judge.
        val lastRawFix = lastRawFixElapsedNanos ?: return Action.None

        val staleMinutes = (now - lastRawFix) / NANOS_PER_MINUTE
        val baseThreshold = if (moving) {
            config.deadTrackerMovingMin
        } else {
            config.deadTrackerStationaryMin
        }
        // Battery saver legitimately throttles fixes to a few per hour. Widen before
        // declaring death, or the watchdog cries wolf every time the battery dips (EC-21).
        val threshold = if (powerSave) baseThreshold * POWER_SAVE_MULTIPLIER else baseThreshold

        return if (staleMinutes > threshold) {
            throttled(now, config) {
                events.tryEmit(
                    TrackItEvent.Error(
                        ErrorCode.TRACKER_DEAD,
                        "No raw fix for $staleMinutes min (threshold $threshold)",
                    ),
                )
                Action.ReportDead
            }
        } else {
            Action.None
        }
    }

    /** Actions are capped to one per `watchdogThrottleMs` so a broken device is not spammed. */
    private inline fun throttled(now: Long, config: ServiceConfig, action: () -> Action): Action {
        val lastAction = lastActionElapsedNanos
        if (lastAction != null && (now - lastAction) < config.watchdogThrottleMs * NANOS_PER_MILLI) {
            return Action.None
        }
        lastActionElapsedNanos = now
        return action()
    }

    internal enum class Action { None, RestoreService, ReportDead }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val NANOS_PER_MINUTE = 60_000_000_000L
        const val POWER_SAVE_MULTIPLIER = 2
    }
}
