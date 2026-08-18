package com.devstree.traker.geo.plot

import com.devstree.traker.geo.math.Haversine
import com.devstree.traker.geo.model.TrackPoint
import kotlin.math.max

/**
 * Turns consecutive significant nodes into travel/dwell segments (spec §19).
 *
 * The interesting question is whether a span between two nodes represents *real travel*
 * or just scatter, and net displacement alone answers it wrongly in both directions.
 * Three independent tests, any of which suffices:
 *
 *  1. net displacement over 100 m — the ordinary case;
 *  2. a **sustained excursion**: at least two *consecutive* points beyond 150 m from the
 *     start **and** a peak leg speed of at least 1 m/s. This catches an out-and-back
 *     lunch trip whose net displacement is ~0 (EC-97), while the two AND-gates keep
 *     home GPS scatter of 150-185 m out — an isolated spike fails the consecutive test,
 *     and slow drift fails the speed floor (EC-98);
 *  3. total path over 500 m with speeds that corroborate it.
 *
 * The **carry-forward** handles a blackout: real displacement with no intermediate
 * samples would otherwise attribute the entire silent gap to travel and invent hours of
 * driving. Implied travel time is bounded to `clamp(dist / 5 m/s, 60 s, 300 s)` so the
 * UI can render a drive window and a dwell window separately (EC-99).
 *
 * All of that classifies a span it was handed. A span can also *contain* a stop the
 * pipeline never sampled, and no amount of classifying it as a whole will find one —
 * see [isDwellGap] (EC-140).
 */
public object Clusters {

    public data class Segment(
        val fromIndex: Int,
        val toIndex: Int,
        val points: List<TrackPoint>,
        val isRealMovement: Boolean,
        val stats: SpeedStats.Stats,
        /** Non-zero only when carry-forward applied; start of the implied drive window. */
        val travelStartMs: Long = 0,
    ) {
        val startMs: Long get() = points.first().timeMs
        val endMs: Long get() = points.last().timeMs
    }

    public fun build(points: List<TrackPoint>, nodeIndices: List<Int>): List<Segment> {
        if (points.size < 2 || nodeIndices.size < 2) return emptyList()

        return nodeIndices.zipWithNext().flatMap { (start, end) ->
            val last = end.coerceAtMost(points.lastIndex)
            splitAtDwellGaps(points, start, last).map { range ->
                segment(points.subList(range.from, range.to + 1), range.from, range.to, range.role)
            }
        }
    }

    /**
     * What a range is before it is classified.
     *
     * [APPROACH] exists because splitting a span shortens it. The run up to a stop is
     * only as long as the deceleration took — 57 m on the capture that motivated this —
     * and [isRealMovement]'s 100 m net threshold is calibrated for node-to-node spans,
     * not for the offcuts. Left alone it called that final approach a stop of its own,
     * and one real stop rendered as two.
     */
    private enum class Role { PLAIN, APPROACH, DWELL }

    private data class Range(val from: Int, val to: Int, val role: Role)

    private fun segment(
        span: List<TrackPoint>,
        fromIndex: Int,
        toIndex: Int,
        role: Role,
    ): Segment {
        val stats = SpeedStats.compute(span)
        val real = when (role) {
            // Already decided by the gap test, and it has to override isRealMovement
            // rather than consult it: the two points bracketing a stop can be far apart
            // (the fix that catches a departure is often the first good one after it),
            // so net displacement would call the stop a drive.
            Role.DWELL -> false
            // Too short to judge by displacement, so judge it by speed. Peak leg speed
            // is the right question for an offcut and the wrong one for a whole span —
            // scatter around a house produces speed spikes too (EC-98), which is why
            // this is not the general rule.
            Role.APPROACH -> isRealMovement(span, stats) || stats.maxSpeedMps >= APPROACH_MPS
            Role.PLAIN -> isRealMovement(span, stats)
        }

        return Segment(
            fromIndex = fromIndex,
            toIndex = toIndex,
            points = span,
            isRealMovement = real,
            stats = stats,
            travelStartMs = carryForwardStart(span, stats, real),
        )
    }

    /**
     * A stop the capture pipeline recorded as silence rather than as points.
     *
     * Once a device settles the acceptance gates reject nearly everything, so a stop
     * leaves no cluster to classify — it leaves a *hole* between two points, and both
     * of them belong to the drive. Net displacement across such a span clears 100 m
     * easily, so the whole thing reads as travel and the plotted line runs straight
     * through a car park at an average speed nobody drove.
     *
     * The tell is implied speed, not distance. Distance fails here: on a field capture
     * the fix that caught the departure was already 158 m along the road, well outside
     * any sane stop radius. But 158 m in 363 s is 0.44 m/s — under walking pace, so
     * whatever else happened, most of that time was spent stationary. Implied speed also
     * keeps a genuine signal blackout out: 5 km of tunnel in ten minutes is 8 m/s and
     * stays travel, where [carryForwardStart] handles it.
     *
     * **This is not [SignificantNodes]'s gap-stop protection, though it reads like it.**
     * That guard asks whether a silence should stop a node cluster from ending, and its
     * answer suppresses a boundary. This one asks whether a silence *is* a stop, and its
     * answer creates a segment. They share the speed bar for that reason — [DWELL_GAP_MPS]
     * is derived from it — but not the duration: a node has to earn its place on a
     * timeline and ten minutes is a fair price, while a six-minute stop still has to stop
     * the line being drawn through it.
     */
    private fun isDwellGap(from: TrackPoint, to: TrackPoint): Boolean {
        val seconds = (to.timeMs - from.timeMs) / 1000.0
        if (seconds < DWELL_GAP_SEC) return false

        val distance = Haversine.metres(from.latitude, from.longitude, to.latitude, to.longitude)
        return distance / seconds <= DWELL_GAP_MPS
    }

    /**
     * Cuts `[start, last]` into alternating travel and dwell ranges, inclusive on both
     * ends. Indices stay absolute into `points` because [Snapper.spanFor] and the arrow
     * placement slice by them.
     *
     * A dwell range is exactly the pair straddling the gap: arrival where the device
     * stopped, departure at the first fix that saw it leave.
     */
    private fun splitAtDwellGaps(points: List<TrackPoint>, start: Int, last: Int): List<Range> {
        val gaps = (start until last).filter { isDwellGap(points[it], points[it + 1]) }
        if (gaps.isEmpty()) return listOf(Range(start, last, Role.PLAIN))

        val ranges = mutableListOf<Range>()
        var cursor = start
        for (gap in gaps) {
            if (gap > cursor) ranges += Range(cursor, gap, Role.APPROACH)
            ranges += Range(gap, gap + 1, Role.DWELL)
            cursor = gap + 1
        }
        if (cursor < last) ranges += Range(cursor, last, Role.APPROACH)
        return ranges
    }

    internal fun isRealMovement(span: List<TrackPoint>, stats: SpeedStats.Stats): Boolean {
        if (span.size < 2) return false

        val first = span.first()
        val last = span.last()
        val net = Haversine.metres(first.latitude, first.longitude, last.latitude, last.longitude)

        if (net > NET_MOVE_M) return true

        if (stats.distanceMeters >= LONG_PATH_M &&
            (stats.maxSpeedMps >= LONG_PATH_MAX_MPS || stats.p75SpeedMps >= LONG_PATH_P75_MPS)
        ) {
            return true
        }

        return hasSustainedExcursion(span, first)
    }

    /**
     * Two AND-gates on purpose. Requiring *consecutive* points beyond the radius rejects
     * an isolated multipath spike; requiring a real peak speed rejects slow drift that
     * wanders out and back (EC-97, EC-98).
     */
    private fun hasSustainedExcursion(span: List<TrackPoint>, origin: TrackPoint): Boolean {
        var consecutiveBeyond = 0
        var peakSpeed = 0.0

        for (index in 1 until span.size) {
            val previous = span[index - 1]
            val point = span[index]

            val fromOrigin = Haversine.metres(
                origin.latitude, origin.longitude, point.latitude, point.longitude,
            )
            consecutiveBeyond = if (fromOrigin > EXCURSION_RADIUS_M) consecutiveBeyond + 1 else 0

            val seconds = (point.timeMs - previous.timeMs) / 1000.0
            if (seconds > 0) {
                val distance = Haversine.metres(
                    previous.latitude, previous.longitude, point.latitude, point.longitude,
                )
                peakSpeed = max(peakSpeed, distance / seconds)
            }

            if (consecutiveBeyond >= EXCURSION_MIN_POINTS && peakSpeed >= EXCURSION_PEAK_MPS) {
                return true
            }
        }
        return false
    }

    /**
     * @return the implied drive-window start, or 0 when carry-forward does not apply.
     */
    private fun carryForwardStart(
        span: List<TrackPoint>,
        stats: SpeedStats.Stats,
        isRealMovement: Boolean,
    ): Long {
        // Only when movement was real AND there is nothing in between to explain it.
        if (!isRealMovement || span.size > 2) return 0

        val first = span.first()
        val last = span.last()
        val net = Haversine.metres(first.latitude, first.longitude, last.latitude, last.longitude)
        val distance = max(stats.distanceMeters, net)
        if (distance <= 0.0) return 0

        val impliedSec = (distance / CARRY_FORWARD_SPEED_MPS)
            .coerceIn(CARRY_FORWARD_MIN_SEC, CARRY_FORWARD_MAX_SEC)
        return last.timeMs - (impliedSec * 1000).toLong()
    }

    /** Day-summary helper: in-place wander contributes no commute time (spec §25). */
    public fun isInPlaceWander(span: List<TrackPoint>, stats: SpeedStats.Stats): Boolean {
        if (span.size < 2) return true
        val first = span.first()
        val last = span.last()
        val net = Haversine.metres(first.latitude, first.longitude, last.latitude, last.longitude)
        return max(stats.distanceMeters, net) <= IN_PLACE_WANDER_M
    }

    /** Ongoing dwell is measured against *now*, so an open session's last node grows (EC-111). */
    public fun dwellSec(arrivalMs: Long, departureMs: Long?, nowMs: Long): Long =
        ((departureMs ?: nowMs) - arrivalMs).coerceAtLeast(0) / 1000

    public const val NET_MOVE_M: Double = 100.0
    public const val EXCURSION_RADIUS_M: Double = 150.0
    public const val EXCURSION_MIN_POINTS: Int = 2
    public const val EXCURSION_PEAK_MPS: Double = 1.0
    public const val LONG_PATH_M: Double = 500.0
    public const val LONG_PATH_MAX_MPS: Float = 5f
    public const val LONG_PATH_P75_MPS: Float = 3f
    public const val IN_PLACE_WANDER_M: Double = 90.0

    /**
     * Shortest silence that can be a stop rather than a sparse cadence.
     *
     * Deliberately shorter than [SignificantNodes.GAP_STOP_SEC], which asks a different
     * question about the same silence — see [isDwellGap].
     */
    public const val DWELL_GAP_SEC: Double = 180.0

    /**
     * Same speed bar as [SignificantNodes.GAP_SPEED_M_PER_MIN], expressed per second.
     *
     * Derived rather than restated: two constants that both mean "too slow to have been
     * travelling" are one edit away from disagreeing, and nothing in the build would say so.
     */
    public const val DWELL_GAP_MPS: Double = SignificantNodes.GAP_SPEED_M_PER_MIN / 60.0

    /** Peak leg speed that makes a stop-adjacent offcut travel rather than a second stop. */
    public const val APPROACH_MPS: Float = 1f

    private const val CARRY_FORWARD_SPEED_MPS = 5.0
    private const val CARRY_FORWARD_MIN_SEC = 60.0
    private const val CARRY_FORWARD_MAX_SEC = 300.0
}
