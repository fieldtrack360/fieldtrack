package com.devstree.traker.geo.plot

import com.devstree.traker.geo.math.Bearing
import com.devstree.traker.geo.math.Geodesy
import com.devstree.traker.geo.model.GeoPoint
import com.devstree.traker.geo.plot.model.PuckState
import kotlin.math.cos
import kotlin.math.sin

/**
 * The decision half of the puck animation (SMOOTH-NAV-PLAN Phase 3): where the marker
 * should be easing *to*, and which way it should point. The renderer owns the
 * `ValueAnimator`; the numbers come from here, so they are JVM-testable and live in
 * the module every other constant lives in.
 */
public object PuckAnimation {

    /**
     * The animation target: the filter position dead-reckoned [lookaheadMs] ahead
     * along the current velocity.
     *
     * Animating toward the *reported* position leaves the marker perpetually one fix
     * interval behind — by the time the ease arrives, the vehicle has moved on.
     * Easing toward where the filter says it will be at the ease's end keeps the puck
     * on top of the present. With no meaningful heading there is no direction to
     * reckon along, and the honest target is the position itself.
     */
    public fun target(puck: PuckState, lookaheadMs: Long): GeoPoint {
        val heading = puck.headingDeg ?: return GeoPoint(puck.latitude, puck.longitude)
        val seconds = lookaheadMs / 1000.0
        val headingRad = Math.toRadians(heading)
        return Geodesy.offsetMeters(
            GeoPoint(puck.latitude, puck.longitude),
            northM = puck.speedMps * cos(headingRad) * seconds,
            eastM = puck.speedMps * sin(headingRad) * seconds,
        )
    }

    /**
     * Exponentially smoothed heading, `[0, 360)` — or [previousDeg] unchanged when the
     * new estimate is not worth following.
     *
     * Two rules, both load-bearing:
     * - **Freeze below [BEARING_FREEZE_BELOW_MPS]**: a near-stationary velocity's
     *   direction is rounding error, and following it makes the puck spin on the spot
     *   at every red light. Hold the last good heading instead.
     * - **EMA via the signed short way round** ([Bearing.signedDifference]): a naive
     *   lerp from 350° to 10° swings the long way through 180°. `α = 0.25` follows a
     *   genuine turn in a few fixes while flattening per-fix Doppler jitter.
     */
    public fun smoothedHeading(previousDeg: Double?, puck: PuckState): Double? {
        val measured = puck.headingDeg
        if (measured == null || puck.speedMps < BEARING_FREEZE_BELOW_MPS) return previousDeg
        if (previousDeg == null) return measured.mod(360.0)
        return (previousDeg + BEARING_EMA_ALPHA * Bearing.signedDifference(previousDeg, measured)).mod(360.0)
    }

    /** Follows a real 90° turn to within a few degrees in ~8 fixes; kills per-fix jitter. */
    public const val BEARING_EMA_ALPHA: Double = 0.25

    /** GPS/filter heading below this speed is noise, not direction (research brief §6). */
    public const val BEARING_FREEZE_BELOW_MPS: Float = 1.0f
}
