package com.field360.traker.geo.plot

import com.field360.traker.geo.math.Bearing
import com.field360.traker.geo.math.Geodesy
import com.field360.traker.geo.model.GeoPoint

/**
 * Projects a live position onto a route the host already has (SMOOTH-NAV-PLAN Phase 5).
 *
 * This is the cheapest trick in navigation rendering and most of why Google's blue dot
 * never wobbles off the road: during turn-by-turn it is not being map-matched against
 * the whole road network, it is being snapped to the *one polyline the app is already
 * navigating*. No network, no road graph, no quota — just a projection onto geometry
 * the host supplied.
 *
 * Two rules keep it honest:
 *
 * - **Tolerance scales with the fix's own uncertainty.** A 10 m fix 40 m from the route
 *   is off the route; a 40 m fix 40 m from the route is probably on it. Snapping at a
 *   fixed radius either abandons the route in a tunnel or drags a genuine detour back
 *   onto it.
 * - **Leaving the route is a decision, not an accident.** A fix beyond tolerance is
 *   drawn where it was measured — the marker never claims to be on a road it is not on
 *   — but *concluding* the user has left the route takes
 *   [Tracker.CONSECUTIVE_OFF_ROUTE] consecutive misses. One miss is multipath; a run of
 *   them is a wrong turn, and only the second reading should trigger whatever the host
 *   does about it.
 *
 * Forward-only along the route, for the reason [Snapper] is: a route that passes near
 * itself — a loop, an out-and-back, a cloverleaf — would otherwise match the wrong lap
 * and teleport the marker (EC-102).
 */
public object RouteSnap {

    /**
     * @property index the route segment the position landed on.
     * @property distanceM how far the position was from the route before snapping.
     */
    public data class Snapped(
        val point: GeoPoint,
        val index: Int,
        val distanceM: Double,
        val bearingDeg: Double?,
    )

    /**
     * @param fromIndex first route segment to consider — the caller's forward cursor.
     * @return the projection, or `null` when nothing within [toleranceM] was found and
     *   the position should be drawn where it was actually measured.
     */
    public fun snap(
        position: GeoPoint,
        route: List<GeoPoint>,
        toleranceM: Double,
        fromIndex: Int = 0,
    ): Snapped? {
        if (route.size < 2) return null
        val start = fromIndex.coerceIn(0, route.lastIndex - 1)

        var best: Snapped? = null
        for (i in start until route.lastIndex) {
            val projection = Geodesy.projectOntoSegment(position, route[i], route[i + 1])
            if (best == null || projection.distanceM < best.distanceM) {
                best = Snapped(
                    point = projection.point,
                    index = i,
                    distanceM = projection.distanceM,
                    // The route's own heading here — steadier than a heading derived
                    // from consecutive fixes, and the direction the user is actually
                    // being sent.
                    bearingDeg = Bearing.degrees(
                        route[i].latitude,
                        route[i].longitude,
                        route[i + 1].latitude,
                        route[i + 1].longitude,
                    ),
                )
            }
        }
        return best?.takeIf { it.distanceM <= toleranceM }
    }

    /**
     * Tolerance for a fix of this accuracy: `accuracy × [TOLERANCE_ACCURACY_MULTIPLE]`,
     * floored at [MIN_TOLERANCE_M] so a suspiciously confident fix still gets a usable
     * window, capped at [MAX_TOLERANCE_M] so a 500 m network fix cannot claim a route
     * two streets away.
     */
    public fun toleranceFor(accuracyM: Float): Double =
        (accuracyM * TOLERANCE_ACCURACY_MULTIPLE).toDouble().coerceIn(MIN_TOLERANCE_M, MAX_TOLERANCE_M)

    /**
     * Carries the forward cursor and the off-route run between fixes.
     *
     * Not thread-safe: owned by whichever single consumer feeds it positions, the same
     * confinement the rest of the live path uses.
     */
    public class Tracker(private val route: List<GeoPoint>) {

        private var cursor = 0
        private var offRouteRun = 0

        /**
         * True once enough consecutive fixes have missed the route to call it a
         * departure rather than noise. Cleared by the first fix back on the route.
         *
         * Deliberately separate from what [onPosition] draws: the marker follows the
         * evidence immediately, while the host's "you have left the route" decision
         * waits for the run. Conflating them either makes the marker lie for a frame or
         * makes the host reroute on a single multipath spike.
         */
        public val isOffRoute: Boolean get() = offRouteRun >= CONSECUTIVE_OFF_ROUTE

        /**
         * @return where to draw this position, or `null` to draw it exactly as measured.
         */
        public fun onPosition(position: GeoPoint, accuracyM: Float): Snapped? {
            val snapped = snap(position, route, toleranceFor(accuracyM), cursor)
            if (snapped == null) {
                offRouteRun++
                return null
            }

            offRouteRun = 0
            // The cursor only ever advances, so a route passing near itself cannot match
            // the wrong lap on the next fix.
            cursor = snapped.index
            return snapped
        }

        public fun reset() {
            cursor = 0
            offRouteRun = 0
        }

        public companion object {
            /**
             * Two consecutive misses before the route is abandoned. One is noise; two at
             * a 1 Hz navigation cadence is a second of being somewhere else.
             */
            public const val CONSECUTIVE_OFF_ROUTE: Int = 2
        }
    }

    /** 2.5σ — inside the accuracy radius a fix claims, without being generous about it. */
    public const val TOLERANCE_ACCURACY_MULTIPLE: Float = 2.5f

    /** Even a perfect fix gets a lane's worth of room. */
    public const val MIN_TOLERANCE_M: Double = 15.0

    /** Beyond this the "route" is a different street, whatever the accuracy claims. */
    public const val MAX_TOLERANCE_M: Double = 75.0
}
