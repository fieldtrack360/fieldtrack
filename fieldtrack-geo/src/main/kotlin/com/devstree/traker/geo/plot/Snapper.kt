package com.devstree.traker.geo.plot

import com.devstree.traker.geo.math.Geodesy
import com.devstree.traker.geo.math.Haversine
import com.devstree.traker.geo.model.GeoPoint
import com.devstree.traker.geo.plot.model.PlotPoint
import com.devstree.traker.geo.plot.model.RenderTag

/**
 * Merges captured points with road geometry fetched from a
 * [com.devstree.traker.geo.port.RoadSnapProvider].
 *
 * **Pure, and the network call is somebody else's problem.** The provider is `suspend`
 * and lives behind an interface in an optional artifact; this takes the geometry it
 * returned and does the geometry. That split is what keeps `TrackBuilder.build()`
 * synchronous, keeps `fieldtrack-geo` free of an HTTP client, and makes every rule below
 * testable with a hand-written road and no server (PLAN.md §5).
 *
 * Three rules, each pinned to the failure it prevents:
 *
 *  - **Never move a point onto a road it is not on** (EC-101). A fix more than
 *    `maxOffRoadM` from the returned geometry keeps its captured position. Parallel
 *    service roads, elevated expressways over surface streets and tunnel exits all
 *    return geometry a couple of hundred metres from the truth, and snapping to it puts
 *    the user on the wrong street with full confidence.
 *  - **Only bridge between two on-road endpoints** (EC-101). Road geometry is injected
 *    into a leg only when the points at *both* ends were themselves snapped. Injecting
 *    from a raw point draws a road the user was never shown to be on.
 *  - **Sub-paths are index spans, never coordinate lookups** (EC-102, SOURCE-AUDIT A11).
 *    The reference did `path.indexOf(start)`, which is value equality on doubles: a
 *    roundabout or an out-and-back spur revisits a coordinate, `indexOf` returns the
 *    *first* visit, and the sub-path comes back empty or reversed. Here the search
 *    returns an index and scans forward from the previous match, so a revisited
 *    coordinate cannot rewind the path.
 *
 * Nothing is mutated: the input list is untouched and render provenance goes on
 * [PlotPoint.tag], never back onto a stored point (SOURCE-AUDIT A10).
 */
public object Snapper {

    /** @property index position in the road geometry — never the coordinate itself (A11). */
    public data class Match(val index: Int, val distanceM: Double)

    /**
     * A fix projected perpendicularly onto the road (SMOOTH-NAV-PLAN Phase 4).
     *
     * @property segmentIndex the segment `road[segmentIndex] → road[segmentIndex + 1]`
     *   the fix landed on. Still an index, never a coordinate (A11).
     * @property point where on that segment it landed — not necessarily a road vertex.
     */
    public data class Projection(
        val segmentIndex: Int,
        val point: GeoPoint,
        val distanceM: Double,
    )

    /**
     * What the caller managed to get out of the provider.
     *
     * [None] and [Unavailable] both fall back to raw geometry, but only [Unavailable]
     * earns a `snap_unavailable` warning: "you did not ask for snapping" and "you asked
     * and I could not deliver" are different facts about a track, and collapsing them
     * would make the warning meaningless (EC-100).
     */
    public sealed interface RoadGeometry {
        /** Snapping was not requested. */
        public data object None : RoadGeometry

        /** The provider answered. An empty or one-point path counts as [Unavailable]. */
        public data class Snapped(val path: List<GeoPoint>) : RoadGeometry

        /** The provider was asked and could not answer — offline, quota, timeout, error. */
        public data object Unavailable : RoadGeometry

        /** True when the caller asked and got nothing usable back (EC-100). */
        public val isUnavailable: Boolean
            get() = this is Unavailable || (this is Snapped && path.size < MIN_ROAD_POINTS)
    }

    /**
     * Closest road vertex at or after [fromIndex].
     *
     * Forward-only on purpose. A global search would happily match a point on the exit of
     * a roundabout to the geometry of its entry — same coordinates, one lap earlier — and
     * every span computed from it would run backwards (EC-102).
     */
    public fun closestFrom(
        road: List<GeoPoint>,
        latitude: Double,
        longitude: Double,
        fromIndex: Int = 0,
    ): Match? {
        if (road.isEmpty()) return null
        val start = fromIndex.coerceIn(0, road.lastIndex)

        var bestIndex = start
        var bestDistance = Double.MAX_VALUE
        for (i in start..road.lastIndex) {
            val distance = Haversine.metres(latitude, longitude, road[i].latitude, road[i].longitude)
            if (distance < bestDistance) {
                bestDistance = distance
                bestIndex = i
            }
        }
        return Match(bestIndex, bestDistance)
    }

    /**
     * Closest point on the road *itself* at or after segment [fromIndex] — the
     * perpendicular projection, not the nearest vertex (SMOOTH-NAV-PLAN Phase 4).
     *
     * Snapping to the nearest **vertex** drags a fix along the road by up to half the
     * provider's vertex spacing: on geometry with 30 m vertices, three fixes taken 10 m
     * apart all land on the same vertex and then jump 30 m at once. Worse, on a curve
     * the drawn line becomes the road's chorded polygon — and snapped geometry is
     * exempt from smoothing, so nothing downstream can repair it. Projecting onto the
     * segment puts the fix where it actually was along the road, and the along-track
     * jitter disappears.
     *
     * Forward-only for the same reason [closestFrom] is: a global search matches the
     * exit of a roundabout to the geometry of its entry (EC-102).
     */
    public fun projectFrom(
        road: List<GeoPoint>,
        latitude: Double,
        longitude: Double,
        fromIndex: Int = 0,
    ): Projection? {
        if (road.size < MIN_ROAD_POINTS) return null
        val point = GeoPoint(latitude, longitude)
        val start = fromIndex.coerceIn(0, road.lastIndex - 1)

        var best: Projection? = null
        for (i in start until road.lastIndex) {
            val projection = Geodesy.projectOntoSegment(point, road[i], road[i + 1])
            if (best == null || projection.distanceM < best.distanceM) {
                best = Projection(i, projection.point, projection.distanceM)
            }
        }
        return best
    }

    /**
     * @return a new path in which on-road points sit on the road and the gaps between
     *   consecutive on-road points are filled with the road's own geometry.
     *
     * Off-road and protected points pass through untouched, so a session bookend or a
     * host-inserted marker stays exactly where it was recorded (EC-103).
     */
    public fun snap(
        rawPath: List<PlotPoint>,
        road: List<GeoPoint>,
        maxOffRoadM: Double = DEFAULT_MAX_OFF_ROAD_M,
    ): List<PlotPoint> {
        if (rawPath.size < 2 || road.size < MIN_ROAD_POINTS) return rawPath

        val out = ArrayList<PlotPoint>(rawPath.size + road.size)
        var cursor = 0
        var previousSegment = NO_MATCH
        var previousEmitted: PlotPoint? = null

        for (raw in rawPath) {
            // A protected point is never a snap candidate, which also stops it anchoring
            // an injected span — road drawn up to a point that is not on the road is the
            // same lie as moving the point (EC-101, EC-103).
            val match = if (raw.isProtected) {
                null
            } else {
                projectFrom(road, raw.latitude, raw.longitude, cursor)
            }
            val onRoad = match != null && match.distanceM <= maxOffRoadM

            val emitted = if (onRoad) {
                raw.copy(
                    latitude = match.point.latitude,
                    longitude = match.point.longitude,
                    tag = RenderTag.SNAPPED_TO_ROAD,
                )
            } else {
                raw
            }

            // Both projections sit on the road, so every road vertex strictly between
            // them belongs to the leg: `road[previousSegment + 1 .. match.segmentIndex]`.
            // Landing on the same segment leaves nothing in between.
            val bridgeable = onRoad && previousSegment != NO_MATCH && match.segmentIndex > previousSegment
            if (bridgeable) {
                out += bridge(road, previousSegment, match.segmentIndex, previousEmitted!!, emitted)
            }

            out += emitted
            previousEmitted = emitted
            if (onRoad) {
                previousSegment = match.segmentIndex
                cursor = match.segmentIndex
            } else {
                // Break the chain: the next leg starts from a point we did not snap, so
                // there is nothing to bridge from.
                previousSegment = NO_MATCH
            }
        }
        return out
    }

    /**
     * The road's own vertices between two projected points — `road[from + 1 .. to]`.
     *
     * A plain index span, which is the whole of the fix for A11. The endpoints
     * themselves are excluded by the coincidence filter rather than by arithmetic:
     * a fix projecting exactly onto a road vertex (`fraction` 0 or 1) would otherwise
     * have that vertex emitted twice, once as the snapped fix and once as bridge.
     *
     * Timestamps are interpolated across the leg so downstream stages that read `timeMs`
     * see a monotonic path; they are presentation values on geometry the device never
     * sampled, and carry [RenderTag.SNAPPED_TO_ROAD] and `sourceIndex = -1` to say so.
     */
    private fun bridge(
        road: List<GeoPoint>,
        fromSegment: Int,
        toSegment: Int,
        from: PlotPoint,
        to: PlotPoint,
    ): List<PlotPoint> {
        val span = road.subList(fromSegment + 1, toSegment + 1)
            .filter { vertex ->
                Haversine.metres(from.latitude, from.longitude, vertex.latitude, vertex.longitude) > COINCIDENT_M &&
                    Haversine.metres(to.latitude, to.longitude, vertex.latitude, vertex.longitude) > COINCIDENT_M
            }
        val steps = span.size + 1
        return span.mapIndexed { offset, vertex ->
            val fraction = (offset + 1).toDouble() / steps
            PlotPoint(
                latitude = vertex.latitude,
                longitude = vertex.longitude,
                timeMs = from.timeMs + ((to.timeMs - from.timeMs) * fraction).toLong(),
                sourceIndex = INJECTED,
                activity = from.activity,
                tag = RenderTag.SNAPPED_TO_ROAD,
            )
        }
    }

    /**
     * The stretch of a rendered path belonging to one cluster.
     *
     * Segments index into the *consolidated points*, but the rendered path also holds
     * injected road vertices that index into nothing. Slicing by [PlotPoint.sourceIndex]
     * rather than by position is what keeps a segment's polyline aligned with its
     * `from`/`to` after injection has changed every position in the list.
     */
    public fun spanFor(
        rendered: List<PlotPoint>,
        fromSourceIndex: Int,
        toSourceIndex: Int,
    ): List<PlotPoint> {
        val start = rendered.indexOfFirst { it.sourceIndex == fromSourceIndex }
        val end = rendered.indexOfLast { it.sourceIndex == toSourceIndex }
        if (start < 0 || end < start) return emptyList()
        return rendered.subList(start, end + 1)
    }

    /**
     * 80 m. Wide enough to cover ordinary urban GPS error against a road centreline,
     * tight enough that a parallel service road or a frontage road stays a different
     * road (EC-101).
     */
    public const val DEFAULT_MAX_OFF_ROAD_M: Double = 80.0

    /** A single vertex is a point, not a road; it cannot bridge anything. */
    public const val MIN_ROAD_POINTS: Int = 2

    /** [PlotPoint.sourceIndex] of a vertex that came from the road, not from a fix. */
    public const val INJECTED: Int = -1

    private const val NO_MATCH = -1

    /** A bridge vertex this close to a projected fix is that fix, not a step along the road. */
    private const val COINCIDENT_M = 0.05
}
