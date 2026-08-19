package com.field360.traker.geo.plot.model

import com.field360.traker.geo.math.Bearing
import com.field360.traker.geo.model.FilterState
import com.field360.traker.geo.model.GeoPoint
import kotlin.math.sqrt

/**
 * One frame of the live rendering surface (SMOOTH-NAV-PLAN Phase 2).
 *
 * The historical [Track] is a *product* — consolidated, snapped, segmented, built on
 * demand. This is a *feed*: what a map should draw right now, cheap enough to emit on
 * every fix. The two meet at the geometry: the frozen tail is the same centripetal
 * Catmull-Rom the full-track spline produces, frozen span by span as knots settle.
 *
 * @property sequence monotonic per session run. A renderer that receives frame 41 after
 *   frame 42 — possible across dispatcher hops — must drop it, not draw it.
 * @property frozenTailPolyline encoded polyline ([PolylineCodec][com.field360.traker.geo.plot.PolylineCodec]
 *   at [precision]) of every settled span. Grows by appending; a renderer may keep its
 *   decoded copy and decode only the suffix beyond what it has, or simply re-decode —
 *   but must never re-smooth it.
 * @property liveHead the unsettled last span, smoothed provisionally, **including** both
 *   of its end vertices — its first vertex is the tail's last, so the two polylines
 *   join C0 by construction. Redrawn wholesale on every accepted fix; its shape changes
 *   as the next knot arrives, which is why it is not part of the tail.
 * @property puck the filter's own estimate — position, speed, heading — for the
 *   animated current-position marker. Updates on every processed fix, accepted or not:
 *   the filter learns from fixes the store never sees. `null` until the filter seeds.
 */
public data class LiveTrackUpdate(
    val sessionId: String,
    val sequence: Long,
    val precision: Int,
    val frozenTailPolyline: String,
    val liveHead: List<GeoPoint>,
    val puck: PuckState?,
)

/**
 * The Kalman filter's current estimate, shaped for a position marker.
 *
 * This is the first surface that shows the *filtered* position rather than raw fix
 * coordinates. Stored points deliberately stay raw (locked decision); a puck gliding on
 * the filter's estimate is the display-side use the filter output always deserved.
 *
 * @property headingDeg compass heading of the velocity estimate, `[0, 360)` — or `null`
 *   when the velocity is too small to have a meaningful direction. A renderer should
 *   hold its last rotation then, never snap to a fabricated 0°.
 * @property accuracyM the filter's 1σ position uncertainty, metres — the honest radius
 *   for an accuracy halo around the puck.
 */
public data class PuckState(
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float,
    val headingDeg: Double?,
    val accuracyM: Float,
) {
    public companion object {
        /** `null` until the filter has been seeded — there is no honest position to show. */
        public fun from(state: FilterState): PuckState? {
            if (!state.isInitialised) return null
            val north = state.velocityNorthMps
            val east = state.velocityEastMps
            return PuckState(
                latitude = state.lat,
                longitude = state.lng,
                speedMps = sqrt(north * north + east * east),
                headingDeg = Bearing.ofVelocity(north, east),
                accuracyM = state.accuracy(),
            )
        }
    }
}
