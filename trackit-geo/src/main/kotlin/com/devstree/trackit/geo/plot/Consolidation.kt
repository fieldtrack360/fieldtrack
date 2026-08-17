package com.devstree.trackit.geo.plot

import com.devstree.trackit.geo.math.Haversine
import com.devstree.trackit.geo.model.MovementStatus
import com.devstree.trackit.geo.model.TrackPoint

/**
 * Collapses a dwell into its centroid.
 *
 * The last line of defence against stationary drift: whatever residue survived the
 * capture-side gates and reached storage becomes one plotted node instead of a cloud
 * of dots. Consecutive **stationary** points within 60 m of where the group started are
 * one group; a group that lasted at least ten minutes emits an **arrival** and a
 * **departure** node at the centroid, so the timeline can say "arrived 09:12, left
 * 10:40" (spec §17).
 *
 * The word doing the work there is *stationary*. This runs over the whole day, moving
 * points included, and for a long time it grouped them too — see [group].
 *
 * Pure — takes a list, returns a new one. The reference wrote coordinates back into the
 * caller's list, so re-running the pipeline on the same data produced different output
 * the second time (SOURCE-AUDIT A10).
 */
public object Consolidation {

    public data class Group(
        val centroidLat: Double,
        val centroidLng: Double,
        val points: List<TrackPoint>,
    ) {
        val startMs: Long get() = points.first().timeMs
        val endMs: Long get() = points.last().timeMs
        val dwellSec: Long get() = (endMs - startMs) / 1000

        /** Furthest member from the centroid — the stop's real footprint. */
        val radiusM: Double
            get() = points.maxOfOrNull {
                Haversine.metres(centroidLat, centroidLng, it.latitude, it.longitude)
            } ?: 0.0
    }

    /**
     * Greedy grouping of **stationary** points against the anchor each group started from
     * (spec §17).
     *
     * Two rules, and both exist because the original had neither:
     *
     *  - **A moving point never joins a dwell.** Nothing here used to look at motion at
     *    all, so a car at 36 km/h was grouped exactly like a parked phone.
     *  - **The radius is measured from the group's first point**, not from a running mean
     *    of its members. A running mean *chases* whatever it is grouping: with points 40 m
     *    apart the centroid trails the newest one, so a moving vehicle stayed inside a
     *    60 m radius for two or three fixes at a time and each of those runs collapsed to
     *    a single vertex.
     *
     * Together those two defects deleted **13 of 28** stored points on a field capture —
     * all of them moving, at 5-10 m/s on open road — and swallowed a five-minute stop into
     * a group anchored 200 m earlier, so the drawn line cut a straight diagonal across it.
     * That is the "polyline goes straight and jumps between coordinates" report, and it
     * happened in the plotting plane, downstream of a capture pipeline that had stored
     * every one of those points correctly (EC-139).
     *
     * The centroid is still the mean of the group's members — that is the right position
     * to report for a dwell. It is simply no longer used to decide membership.
     */
    public fun group(
        points: List<TrackPoint>,
        radiusM: Double = DEFAULT_RADIUS_M,
        movingSpeedMps: Float = DEFAULT_MOVING_MPS,
    ): List<Group> {
        if (points.isEmpty()) return emptyList()

        val groups = mutableListOf<Group>()
        var current = mutableListOf(points.first())
        var anchor = points.first()

        for (point in points.drop(1)) {
            val joins = !point.isMoving(movingSpeedMps) &&
                !anchor.isMoving(movingSpeedMps) &&
                Haversine.metres(anchor.latitude, anchor.longitude, point.latitude, point.longitude) <= radiusM

            if (joins) {
                current += point
            } else {
                groups += centroidOf(current)
                current = mutableListOf(point)
                anchor = point
            }
        }
        groups += centroidOf(current)
        return groups
    }

    /**
     * Moving by the pipeline's own reckoning. `speedMps` on a stored point is the
     * *effective* speed the acceptance pipeline settled on — already reconciled against
     * hardware Doppler and displacement — so this needs no second opinion about it.
     */
    private fun TrackPoint.isMoving(movingSpeedMps: Float): Boolean =
        speedMps >= movingSpeedMps || movementStatus == MovementStatus.MOVING

    private fun centroidOf(points: List<TrackPoint>): Group = Group(
        centroidLat = points.sumOf { it.latitude } / points.size,
        centroidLng = points.sumOf { it.longitude } / points.size,
        points = points.toList(),
    )

    /**
     * How long the dwell really lasted, which is not the same as how long it was recorded.
     *
     * [Group.dwellSec] spans the group's own first and last points. That is an honest
     * answer only if the capture pipeline kept sampling throughout — and it deliberately
     * does the opposite. Once a device settles, the acceptance gates reject almost
     * everything, so an hour in a car park can leave two stored fixes thirty seconds
     * apart and report a thirty-second dwell.
     *
     * The missing hour is not absent from the data, it is the *silence* before the next
     * group: nothing was worth storing because nothing moved. So a dwell lasts until the
     * next group begins, and the recorded span is only the floor (EC-139a).
     */
    internal fun effectiveDwellSec(group: Group, next: Group?): Long {
        if (next == null) return group.dwellSec
        return maxOf(group.dwellSec, (next.startMs - group.endMs) / 1000)
    }

    /**
     * Reduces the day to the points worth plotting.
     *
     * A long dwell contributes exactly two nodes, both at the centroid; a brief pause
     * contributes only its first point. Since [group] no longer absorbs moving points,
     * that first point is now somewhere the device actually stopped — previously a pause
     * could be swallowed by a group anchored hundreds of metres back down the road, and
     * the plotted line cut straight across it.
     */
    public fun consolidate(
        points: List<TrackPoint>,
        radiusM: Double = DEFAULT_RADIUS_M,
        minDwellSec: Long = DEFAULT_MIN_DWELL_SEC,
        movingSpeedMps: Float = DEFAULT_MOVING_MPS,
    ): List<TrackPoint> {
        if (points.size <= 1) return points

        val groups = group(points, radiusM, movingSpeedMps)
        return groups.flatMapIndexed { index, group ->
            when {
                group.points.size == 1 -> group.points
                effectiveDwellSec(group, groups.getOrNull(index + 1)) >= minDwellSec -> listOf(
                    // Identity and time from the real first/last points; position from
                    // the centroid, so the marker sits in the middle of the dwell.
                    group.points.first().copy(
                        latitude = group.centroidLat,
                        longitude = group.centroidLng,
                    ),
                    group.points.last().copy(
                        latitude = group.centroidLat,
                        longitude = group.centroidLng,
                    ),
                )
                else -> listOf(group.points.first())
            }
        }
    }

    public const val DEFAULT_RADIUS_M: Double = 60.0
    public const val DEFAULT_MIN_DWELL_SEC: Long = 600

    /**
     * At or above this, a point is travelling and cannot be part of a dwell.
     *
     * Set at walking pace rather than something more permissive: the cost of calling a
     * moving point stationary is a deleted vertex and a straight line across a turn,
     * while the cost of calling a jittering stationary point "moving" is one extra
     * vertex inside a 60 m circle — and the capture-side gates have already spent seven
     * stages making that case rare.
     */
    public const val DEFAULT_MOVING_MPS: Float = 1.0f
}
