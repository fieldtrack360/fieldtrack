package com.devstree.traker.capture

import com.devstree.traker.domain.model.PointQuery
import com.devstree.traker.domain.repository.TrackPointRepository
import com.devstree.traker.geo.model.FilterState
import com.devstree.traker.geo.model.GeoPoint
import com.devstree.traker.geo.model.TrackPoint
import com.devstree.traker.geo.plot.LiveTrackEngine
import com.devstree.traker.geo.plot.PuckAnimation
import com.devstree.traker.geo.plot.RouteSnap
import com.devstree.traker.geo.plot.model.LiveTrackUpdate
import com.devstree.traker.geo.plot.model.PuckState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * The push path from accepted fix to map geometry (SMOOTH-NAV-PLAN Phase 2).
 *
 * Before this, the fix flow ended at Room and a live map had to poll `buildTrack` —
 * a full DB query and a full rebuild per refresh. This feed is in-memory only: it
 * writes nothing, reads the store once per session start to seed a resumed track, and
 * emits a [LiveTrackUpdate] per processed fix.
 *
 * Backpressure by conflation: a `StateFlow` holds only the latest frame, so a slow
 * renderer skips frames rather than queueing them — and can never backpressure the
 * single ingest consumer the way a suspending emit would (EC-114).
 */
internal class LiveTrackFeed(
    private val points: TrackPointRepository,
) {

    private val _updates = MutableStateFlow<LiveTrackUpdate?>(null)

    /** Latest frame only; completes never. `null` frames (no active session) are skipped. */
    val updates: Flow<LiveTrackUpdate> = _updates.filterNotNull()

    private var engine: LiveTrackEngine? = null

    @Volatile
    private var routeTracker: RouteSnap.Tracker? = null

    /**
     * Snap the puck to a route the host is navigating (SMOOTH-NAV-PLAN Phase 5).
     *
     * Set from any thread, read on the ingest consumer — hence `@Volatile`; the
     * [RouteSnap.Tracker] it wraps is only ever touched from the consumer. Passing an
     * empty route clears it.
     */
    fun setActiveRoute(route: List<GeoPoint>) {
        routeTracker = if (route.size < MIN_ROUTE_POINTS) null else RouteSnap.Tracker(route)
    }

    /** True once the position has missed the active route for long enough to mean it. */
    val isOffRoute: Boolean get() = routeTracker?.isOffRoute == true

    /**
     * Called by [FixIngestor.start] before the consumer launches, so the engine exists
     * before the first fix and is confined to the same consumer thereafter. Seeds from
     * the session's stored points so a post-process-death resume keeps its tail (EC-51).
     */
    suspend fun start(sessionId: String) {
        val initial = points
            .query(PointQuery(sessionId = sessionId, limit = INITIAL_POINT_LIMIT))
            .map { GeoPoint(it.latitude, it.longitude) }
        engine = LiveTrackEngine(sessionId = sessionId, initialPath = initial)
        _updates.value = null
    }

    fun stop() {
        engine = null
        _updates.value = null
        // The route outlives the session deliberately: a host that set one before
        // starting should not have to set it again, and a stale cursor would otherwise
        // match the next session's first fix against wherever the last one ended.
        routeTracker?.reset()
    }

    /** One processed fix: new filter state always, a stored point only on accept. */
    fun onFix(state: FilterState, accepted: TrackPoint?) {
        val active = engine ?: return
        val update = active.onFix(
            state = state,
            accepted = accepted?.let { GeoPoint(it.latitude, it.longitude) },
        )
        _updates.value = update.copy(puck = snapToRoute(update.puck))
    }

    /**
     * The puck alone is route-snapped, never the recorded geometry.
     *
     * The route is the host's claim about where the user intends to go; the track is
     * the SDK's record of where they were. Snapping the marker to the route is the
     * trick that makes navigation look glued to the road, and writing it back into the
     * track would be fabricating evidence for a road nobody was measured on.
     */
    private fun snapToRoute(puck: PuckState?): PuckState? {
        val tracker = routeTracker ?: return puck
        if (puck == null) return null

        val snapped = tracker.onPosition(GeoPoint(puck.latitude, puck.longitude), puck.accuracyM)
            ?: return puck
        return puck.copy(
            latitude = snapped.point.latitude,
            longitude = snapped.point.longitude,
            // The route's own heading here is steadier than one derived from successive
            // fixes — but only while actually moving, for the reason PuckAnimation
            // freezes bearing at rest.
            headingDeg = if (puck.speedMps >= PuckAnimation.BEARING_FREEZE_BELOW_MPS) {
                snapped.bearingDeg ?: puck.headingDeg
            } else {
                puck.headingDeg
            },
        )
    }

    private companion object {
        /** A single coordinate is a destination, not a route to project onto. */
        const val MIN_ROUTE_POINTS = 2

        /**
         * Seed ceiling for a resumed session — matches the scale of the raw-fix ring
         * rather than a day-spanning query; a session this long has bigger problems
         * than its live tail's first metres.
         */
        const val INITIAL_POINT_LIMIT = 10_000
    }
}
