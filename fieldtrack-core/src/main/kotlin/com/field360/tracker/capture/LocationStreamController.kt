package com.field360.tracker.capture

import com.field360.tracker.TrackerConfig
import com.field360.tracker.TrackingMode
import com.field360.tracker.sdkLog
import com.field360.tracker.data.location.FixMapper
import com.field360.tracker.data.location.LocationRequests
import com.field360.tracker.data.location.LocationSource
import com.field360.traker.geo.port.TrackLogger
import com.field360.tracker.motion.CaptureStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the location stream's lifetime and its cadence.
 *
 * Split out from the start/stop use cases because cadence has to change *during* a
 * session: at 60 s a car at 40 km/h covers ~660 m between fixes, so a 90° turn happens
 * entirely between samples and no filter can recover data that was never captured.
 * Dropping to 12 s while vehicular is the largest turn-fidelity win available without a
 * routing API (EC-45, spec §8.2).
 *
 * A third tier sits above vehicular: while `fieldtrack-geo`'s `TurnDetector` says the
 * vehicle is measurably turning, cadence drops again to `turnBurstIntervalMs`. Adaptive
 * cadence is a guess about the whole drive; this spends the battery only where the
 * geometry actually is (EC-45).
 *
 * Restarts only on an actual cadence *change* — tearing the request down and rebuilding
 * it on every motion event would cost more than the extra samples buy. That guard is why
 * the turn burst holds for 30 s rather than following each fix: the detector's hold
 * window is what keeps this from thrashing the location request through a bend.
 */
internal class LocationStreamController(
    private val locationSource: LocationSource,
    private val fixMapper: FixMapper,
    private val ingestor: FixIngestor,
    private val logger: TrackLogger,
    private val scope: CoroutineScope,
) : CaptureStream {

    private var job: Job? = null
    private var config: TrackerConfig? = null

    @Volatile
    private var vehicular: Boolean = false

    @Volatile
    private var turning: Boolean = false

    val isRunning: Boolean get() = job?.isActive == true

    fun start(config: TrackerConfig, vehicular: Boolean = false) {
        this.config = config
        this.vehicular = vehicular
        this.turning = false
        restart()
    }

    /**
     * Called by [FixIngestor] as `TurnDetector` arms and disarms. A no-op unless the
     * cadence tier actually flips, same as [setVehicular] (EC-45).
     */
    fun setTurning(turning: Boolean) {
        val active = config ?: return
        if (this.turning == turning) return

        val before = LocationRequests.intervalFor(active.geolocation, vehicular, this.turning)
        // The field is recorded before the guards, never after. A guarded early return
        // that left it stale would hand the next restart a tier the detector no longer
        // asks for — a burst that outlives the bend that armed it.
        this.turning = turning
        if (!active.geolocation.turnBurst) return
        // Nothing to accelerate. Restarting a stopped stream here would resume capture in
        // MOTION_ONLY on the strength of a reading from before it was stopped.
        if (!isRunning) return
        // A flip that does not change the request must not restart it. Navigation is the
        // case that made this real: it outranks every tier, so with it on the rebuilt
        // request is byte-identical — and each needless teardown re-arms
        // waitForAccurateLocation, holding back fixes exactly where the 1 Hz feed
        // matters most (SMOOTH-NAV-PLAN Phase 1).
        if (LocationRequests.intervalFor(active.geolocation, vehicular, turning) == before) return

        sdkLog { logger.d(TAG, "Cadence -> ${if (turning) "turn burst" else "steady"}") }
        restart()
    }

    /**
     * Called by [com.field360.tracker.motion.MotionController] as motion state changes.
     * A no-op unless the cadence tier actually flips.
     */
    override fun setVehicular(vehicular: Boolean) {
        val active = config ?: return
        if (this.vehicular == vehicular) return
        if (!active.geolocation.adaptiveCadence) return

        val before = LocationRequests.intervalFor(active.geolocation, this.vehicular, turning)
        this.vehicular = vehicular
        // Same guard as setTurning: an identical request is never restarted (navigation
        // outranks the tiers, and a turn burst already outranks vehicular).
        if (LocationRequests.intervalFor(active.geolocation, vehicular, turning) == before) return

        sdkLog { logger.d(TAG, "Cadence -> ${if (vehicular) "vehicular" else "normal"}") }
        restart()
    }

    /**
     * In `MOTION_ONLY` the stream is genuinely switched off while stationary — that is
     * the mode's entire point. `ADAPTIVE` keeps it running and lets the filter thin,
     * because the heartbeat is what self-corrects a device whose wake paths all failed
     * (EC-57).
     */
    override fun onStationary() {
        val active = config ?: return
        if (active.geolocation.trackingMode == TrackingMode.MOTION_ONLY) {
            turning = false
            sdkLog { logger.d(TAG, "MOTION_ONLY: stopping stream while stationary") }
            stop()
            return
        }

        // A parked vehicle is not turning, whatever the last fix said. Dropped first and
        // on its own path, because `setVehicular` returns early when the vehicular tier
        // is already off — and a burst left running against a parked phone is the
        // battery complaint this feature would otherwise earn.
        setTurning(false)
        setVehicular(false)
    }

    override fun onMoving() {
        val active = config ?: return
        if (!isRunning) start(active, vehicular = false)
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun release() {
        stop()
        config = null
        vehicular = false
        turning = false
    }

    private fun restart() {
        val active = config ?: return
        job?.cancel()
        job = scope.launch {
            // The tier is stamped per fix, at capture: this collector knows exactly
            // which request produced its fixes. Sampling the controller at consume time
            // instead would mislabel queued fixes across a flip and stamp one-shot and
            // backstop fixes with a tier they never had (SMOOTH-NAV-PLAN Phase 1).
            val intervalMs = LocationRequests.intervalFor(active.geolocation, vehicular, turning)
            locationSource.stream(active.geolocation, vehicular, turning).collect { batch ->
                // Whole batch, ascending. Reading only the last member is the defect
                // that silently discards 4-6 fixes per Doze window (SOURCE-AUDIT A4).
                batch.forEach { location ->
                    fixMapper.map(location, active.geolocation.mockLocationPolicy)
                        ?.let { ingestor.offer(it, cadenceTierMs = intervalMs) }
                }
            }
        }
    }

    private companion object {
        const val TAG = "StreamController"
    }
}
