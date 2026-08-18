package com.devstree.traker.geo.plot

import com.devstree.traker.geo.math.Haversine
import com.devstree.traker.geo.model.FilterState
import com.devstree.traker.geo.model.GeoPoint
import com.devstree.traker.geo.plot.model.LiveTrackUpdate
import com.devstree.traker.geo.plot.model.PuckState
import com.devstree.traker.geo.plot.model.Smoothing
import com.devstree.traker.geo.plot.model.Track

/**
 * Incremental spline state for the live surface (SMOOTH-NAV-PLAN Phase 2).
 *
 * A centripetal Catmull-Rom span between knots `i` and `i+1` depends on exactly four
 * knots, `i-1 .. i+2` — so the span is **final** the moment knot `i+2` arrives, and only
 * the very last span of a growing track is ever provisional. This engine exploits that:
 * each accepted point finalises one span, whose samples are appended to an incrementally
 * encoded frozen tail exactly once ([Spline.sampleSpan]); the last span alone is
 * re-smoothed per fix as the [LiveTrackUpdate.liveHead]. Per-fix cost is O(one span),
 * never O(track).
 *
 * Agreement contract: after the final point, decoded tail + head equals
 * [Spline.smooth] over the same knots — same sample counts, same evaluation — within
 * the sub-centimetre longitude-scale divergence documented on [Spline.sampleSpan].
 * Degenerate sizes are graceful: one knot renders as a dot, two as chord samples.
 *
 * Not thread-safe by design: owned by the single ingest consumer, the same confinement
 * `FilterState` relies on (EC-52).
 */
public class LiveTrackEngine(
    private val sessionId: String,
    initialPath: List<GeoPoint> = emptyList(),
    private val spacingM: Double = Smoothing.DEFAULT_SPACING_M,
    private val maxPerSpan: Int = Spline.DEFAULT_MAX_PER_SPAN,
    private val precision: Int = Track.DEFAULT_PRECISION,
) {

    private val knots = mutableListOf<GeoPoint>()
    private val tail = PolylineCodec.Encoder(precision)
    private var sequence = 0L
    private var puck: PuckState? = null

    init {
        // A resumed session replays its stored points through the same append path a
        // live fix takes, so the tail is identical either way.
        initialPath.forEach(::appendKnot)
    }

    /**
     * Fold one processed fix in. [accepted] is the stored point's position when the
     * pipeline accepted it, `null` on skip/reject — the puck still moves then, because
     * the filter learns from fixes the store never keeps.
     */
    public fun onFix(state: FilterState, accepted: GeoPoint? = null): LiveTrackUpdate {
        if (accepted != null) appendKnot(accepted)
        puck = PuckState.from(state)
        return snapshot()
    }

    /** The current frame. Each call is a new [LiveTrackUpdate.sequence]. */
    public fun snapshot(): LiveTrackUpdate = LiveTrackUpdate(
        sessionId = sessionId,
        sequence = ++sequence,
        precision = precision,
        frozenTailPolyline = tail.snapshot(),
        liveHead = head(),
        puck = puck,
    )

    private fun appendKnot(point: GeoPoint) {
        val last = knots.lastOrNull()
        // Same coincidence rule as Spline.smooth: a knot on top of the previous one
        // degenerates the centripetal parameterisation and draws nothing.
        if (last != null &&
            Haversine.metres(last.latitude, last.longitude, point.latitude, point.longitude) <= Spline.COINCIDENT_M
        ) {
            return
        }

        knots += point
        when (val n = knots.size) {
            // One knot: nothing frozen, the knot itself is the head.
            1 -> Unit
            // Two knots: the tail's origin vertex is now known — the span itself stays live.
            2 -> tail.add(knots[0])
            // Knot n-1 arrived, so the span (n-3, n-2) has all four of its knots and is
            // final: sample it once, append, and move the frozen boundary to knot n-2.
            else -> {
                Spline.sampleSpan(
                    knots[(n - 4).coerceAtLeast(0)],
                    knots[n - 3],
                    knots[n - 2],
                    knots[n - 1],
                    spacingM,
                    maxPerSpan,
                ).forEach(tail::add)
                tail.add(knots[n - 2])
            }
        }
    }

    /**
     * The provisional last span, endpoint-clamped exactly the way [Spline.smooth]
     * clamps a track's true end — because right now, this *is* the track's true end.
     */
    private fun head(): List<GeoPoint> {
        val n = knots.size
        return when {
            n == 0 -> emptyList()
            n == 1 -> listOf(knots[0])
            else -> buildList {
                add(knots[n - 2])
                addAll(
                    Spline.sampleSpan(
                        knots[(n - 3).coerceAtLeast(0)],
                        knots[n - 2],
                        knots[n - 1],
                        knots[n - 1],
                        spacingM,
                        maxPerSpan,
                    ),
                )
                add(knots[n - 1])
            }
        }
    }
}
