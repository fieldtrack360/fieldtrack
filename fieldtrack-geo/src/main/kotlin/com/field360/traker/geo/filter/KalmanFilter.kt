package com.field360.traker.geo.filter

import com.field360.traker.geo.model.FilterState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * A **constant-velocity** Kalman filter: position and velocity, one covariance shared by
 * latitude and longitude.
 *
 * ## Why this is not the model the design review argued against
 *
 * `docs/reference/EKF-DESIGN-REVIEW.md` §C1 rejects a **CTRV** filter — five states,
 * including a turn rate `ψ̇` — and it is right to. A turn rate is only observable if you
 * sample fast enough to watch the turn develop; at the 60 s cadence that review was
 * written against, any turn rate consistent with two fixes 660 m apart is equally likely,
 * so the prediction curves in an invented direction and the next real fix gets gated out.
 *
 * Constant velocity carries no turn state at all. It cannot invent a turn, because it can
 * only ever extrapolate a straight line. The two objections in §C1 — unobservability and
 * invented curvature — do not transfer.
 *
 * ## Why the scalar filter had to go
 *
 * A position-only filter has no notion that the target is moving, so against steady
 * motion its estimate lags by a fixed amount *every fix*. Measured on a straight 40 km/h
 * drive at the 12 s cadence: the estimate trailed ~130 m per fix, the lag accumulated
 * until it breached the 3-sigma gate, and roughly **one fix in four was rejected** and
 * then recovered by a forced reset that snapped the line forward. That was the visible
 * "track jumps from one place to another" symptom, and no amount of Q/R tuning fixes it —
 * the model simply had no term for the thing it was tracking.
 *
 * The review's own prescription for turn geometry, "more samples at speed", shipped as
 * adaptive cadence and the turn-burst tier. That is what makes velocity *observable* here
 * and it is the reason this model is appropriate now and would not have been then.
 *
 * ## The post-turn cascade, and why it cannot happen
 *
 * A constant-velocity prediction does overshoot through a real corner. Left alone that is
 * the same cascade in a new costume. [AcceptancePipeline] therefore gates against
 * whichever of the two predictions is *closer* to the fix — the extrapolated position or
 * the last corrected one. Straight-line driving is judged by the extrapolation, which is
 * what removes the lag; a corner is judged by the last known position, exactly as the
 * scalar filter did. The filter cannot be worse than what it replaced at any fix.
 *
 * Ported from `KalmanLatLngFilter.kt` with the concurrency defects removed: no mutable
 * fields, no `@Volatile`, and [predictSigma] takes `q` explicitly rather than reading
 * whatever `q` the last *accepted* fix happened to leave behind (SOURCE-AUDIT A7).
 */
public object KalmanFilter {

    /** Accuracy floor. A 0 m accuracy would give gain 1 and track noise exactly (EC-23). */
    public const val MIN_ACCURACY: Float = 1f

    /** Metres per degree of latitude. Longitude scales this by `cos(latitude)`. */
    private const val METRES_PER_DEGREE = 111_320.0

    /** Where the filter expects the target to be, and how sure it is. */
    public data class Prediction(
        val latitude: Double,
        val longitude: Double,
        val sigma: Float,
    )

    /**
     * Hard (re)seed: forget where we thought we were and believe this fix.
     *
     * **Velocity survives.** A reseed happens because the *position* estimate was wrong —
     * a gap, a teleport, a forced reset mid-drive — and in every one of those cases the
     * vehicle's velocity is the part still worth knowing. Zeroing it would make the very
     * next prediction claim the target had stopped, reintroducing one fix of exactly the
     * lag this model exists to remove. The variance is reset instead, so the estimate is
     * held loosely and re-earned from the next correction.
     */
    public fun seed(
        state: FilterState,
        lat: Double,
        lng: Double,
        accuracy: Float,
        elapsedNanos: Long,
    ): FilterState {
        val acc = accuracy.coerceAtLeast(MIN_ACCURACY)
        return state.copy(
            lat = lat,
            lng = lng,
            variance = acc * acc,
            covPosVel = 0f,
            varianceVel = FilterState.VELOCITY_VARIANCE_SEED,
            elapsedNanos = elapsedNanos,
        )
    }

    /**
     * Where the target should be at [atElapsedNanos], extrapolating the velocity estimate.
     *
     * Returns the last corrected position unchanged when the filter is uninitialised or
     * time has not advanced — never an extrapolation into the past.
     */
    public fun predict(
        state: FilterState,
        atElapsedNanos: Long,
        q: Float,
    ): Prediction {
        if (!state.isInitialised) {
            return Prediction(state.lat, state.lng, Float.MAX_VALUE)
        }
        val dtSec = secondsBetween(state.elapsedNanos, atElapsedNanos)
        val covariance = predictCovariance(state, dtSec, q)

        val northMetres = state.velocityNorthMps * dtSec
        val eastMetres = state.velocityEastMps * dtSec
        val lat = state.lat + northMetres / METRES_PER_DEGREE
        // Longitude degrees shrink with latitude; skipping this stretches every
        // east-west prediction by 1/cos(lat) — 8 % at this SDK's home latitude.
        val lng = state.lng + eastMetres / metresPerDegreeLongitude(state.lat)

        return Prediction(lat, lng, sqrt(covariance.position.coerceAtLeast(0f)))
    }

    /**
     * Predict, then update.
     *
     * @param r effective measurement noise after the Q/R tuning stage — *not* the raw
     *   accuracy. Inflating it is how stationary drift gets frozen out.
     * @param q process noise for **this** fix, as an acceleration spectral density in
     *   m/s². The pipeline's motion-class values carry over directly: ~1.2 for a car is a
     *   plausible acceleration noise, and the near-zero stationary value pins the filter
     *   to its anchor exactly as before.
     */
    public fun process(
        state: FilterState,
        lat: Double,
        lng: Double,
        r: Float,
        elapsedNanos: Long,
        q: Float,
    ): FilterState {
        val acc = r.coerceAtLeast(MIN_ACCURACY)
        if (!state.isInitialised) {
            return seed(state, lat, lng, acc, elapsedNanos)
        }

        val dtSec = secondsBetween(state.elapsedNanos, elapsedNanos)
        val predicted = predict(state, elapsedNanos, q)
        val covariance = predictCovariance(state, dtSec, q)

        // UPDATE — one scalar gain pair, applied to both axes. Innovations are converted
        // to metres so the gain is dimensionally consistent with a velocity in m/s.
        val innovationNorth = (lat - predicted.latitude) * METRES_PER_DEGREE
        val innovationEast = (lng - predicted.longitude) * metresPerDegreeLongitude(state.lat)

        val s = covariance.position + acc * acc
        val gainPosition = covariance.position / s
        val gainVelocity = covariance.posVel / s

        return state.copy(
            lat = predicted.latitude + gainPosition * innovationNorth / METRES_PER_DEGREE,
            lng = predicted.longitude +
                gainPosition * innovationEast / metresPerDegreeLongitude(state.lat),
            velocityNorthMps = state.velocityNorthMps + gainVelocity * innovationNorth.toFloat(),
            velocityEastMps = state.velocityEastMps + gainVelocity * innovationEast.toFloat(),
            variance = (1 - gainPosition) * covariance.position,
            covPosVel = (1 - gainPosition) * covariance.posVel,
            varianceVel = covariance.velocity - gainVelocity * covariance.posVel,
            elapsedNanos = if (dtSec > 0f) elapsedNanos else state.elapsedNanos,
        )
    }

    /**
     * Gate width helper. Pure — never mutates state.
     *
     * The reference re-implemented this arithmetic inline inside the sigma gate, so the
     * two copies could drift, and it read `q` from the filter (i.e. the previous accepted
     * fix's motion class). A stationary `q` of 0.0001 then tightened the gate for the
     * *first fix of a drive* to ~0 (SOURCE-AUDIT A7).
     */
    public fun predictSigma(
        state: FilterState,
        atElapsedNanos: Long,
        q: Float,
    ): Float {
        if (!state.isInitialised) return sqrt(state.variance.coerceAtLeast(0f))
        val dtSec = secondsBetween(state.elapsedNanos, atElapsedNanos)
        return sqrt(predictCovariance(state, dtSec, q).position.coerceAtLeast(0f))
    }

    /** Current speed estimate, m/s. Diagnostics and the decision log — never a gate. */
    public fun speedOf(state: FilterState): Float =
        sqrt(
            state.velocityNorthMps * state.velocityNorthMps +
                state.velocityEastMps * state.velocityEastMps,
        )

    // ─────────────────────────────────────────────────────────────────────────

    private data class Covariance(val position: Float, val posVel: Float, val velocity: Float)

    /**
     * Continuous white-noise acceleration: `Q = q² · [[dt³/3, dt²/2], [dt²/2, dt]]`.
     *
     * CWNA rather than the discrete (piecewise-constant) form, whose `dt⁴/4` position
     * term assumes the acceleration holds steady across the whole interval. That is a
     * fair assumption at the 100 Hz an automotive filter runs at and a badly wrong one at
     * 12 s: it inflated the gate from ~400 m to ~690 m, handing every multipath spike a
     * free pass in exchange for fixing the lag. Modelling acceleration as a continuous
     * noise process integrated over the gap gives a gate the same order as the scalar
     * filter's while still carrying a velocity estimate.
     *
     * The off-diagonal term is what makes a position correction also inform the velocity —
     * the whole mechanism by which this filter learns how fast the target is going without
     * ever being told.
     */
    private fun predictCovariance(state: FilterState, dtSec: Float, q: Float): Covariance {
        if (dtSec <= 0f) {
            return Covariance(state.variance, state.covPosVel, state.varianceVel)
        }
        val qq = q * q
        val dt2 = dtSec * dtSec
        val dt3 = dt2 * dtSec

        return Covariance(
            position = state.variance +
                2 * state.covPosVel * dtSec +
                state.varianceVel * dt2 +
                qq * dt3 / 3f,
            posVel = state.covPosVel + state.varianceVel * dtSec + qq * dt2 / 2f,
            velocity = state.varianceVel + qq * dtSec,
        )
    }

    private fun secondsBetween(fromNanos: Long, toNanos: Long): Float {
        val dt = (toNanos - fromNanos).toFloat() / NANOS_PER_SECOND
        // Never negative: the pipeline feeds a monotonic clock, and a negative dt would
        // shrink the covariance and extrapolate backwards.
        return if (dt > 0f) dt else 0f
    }

    /**
     * Guarded against the poles, where `cos(latitude)` reaches zero and the conversion
     * would divide by it. `abs` keeps the southern hemisphere on the same branch.
     */
    private fun metresPerDegreeLongitude(latitude: Double): Double {
        val scale = cos(Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))
        return METRES_PER_DEGREE * abs(scale).coerceAtLeast(MIN_COS_LATITUDE)
    }

    /** Kept off the exact poles; `Validation` already rejects anything beyond ±90. */
    private const val MAX_LATITUDE = 89.9
    private const val MIN_COS_LATITUDE = 1e-6
    private const val NANOS_PER_SECOND = 1_000_000_000f
}
