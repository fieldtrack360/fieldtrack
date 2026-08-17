package com.devstree.trackit.geo.model

/**
 * Why one fix got the verdict it got.
 *
 * Every fix produces one of these, accepted or not. Together they are the decision log —
 * a queryable table rather than a log file — and they are what turns any accuracy
 * complaint into a replayable regression test (PLAN.md §4 improvement 10).
 *
 * The numeric fields exist so a `Sigma Gate Outlier` can be argued with: [sigma] and
 * [threshold] show exactly how wide the gate was and by how much the fix missed.
 */
public data class FixDecision(
    val fix: TrackFix,
    val verdict: Verdict,
    val filterLat: Double,
    val filterLng: Double,
    val sigma: Float,
    val threshold: Float,
    val distanceMovedM: Double,
    val effectiveSpeedMps: Float,
    val motionState: MotionState,
) {
    val reason: String get() = verdict.reason
    val isAccept: Boolean get() = verdict is Verdict.Accept
}

/**
 * The pipeline's return value.
 *
 * [state] is **always** returned — a rejected fix still advances the burst clock and
 * may have burned a reject count, so the caller must never discard it. [point] is
 * non-null if and only if the verdict was [Verdict.Accept].
 */
public data class PipelineResult(
    val decision: FixDecision,
    val state: FilterState,
    val point: TrackPoint? = null,
)
