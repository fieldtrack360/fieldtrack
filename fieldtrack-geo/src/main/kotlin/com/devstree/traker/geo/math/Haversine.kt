package com.devstree.traker.geo.math

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Great-circle distance. R = 6 371 000 m, matching the reference (spec §7). */
public object Haversine {
    public const val EARTH_RADIUS_M: Double = 6_371_000.0

    public fun metres(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(normaliseLongitudeDelta(lng2 - lng1))
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val a = sin(dLat / 2).let { it * it } +
            cos(rLat1) * cos(rLat2) * sin(dLng / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Longitude difference wrapped into `[-180, 180]`.
     *
     * Without this a track crossing the antimeridian computes a ~40 000 km step and
     * every gate downstream misfires (EC-26).
     */
    public fun normaliseLongitudeDelta(deltaDeg: Double): Double {
        var d = deltaDeg
        while (d > 180.0) d -= 360.0
        while (d < -180.0) d += 360.0
        return d
    }
}

/** Initial bearing along the great circle, degrees in `[0, 360)`. */
public object Bearing {
    public fun degrees(
        lat1: Double,
        lng1: Double,
        lat2: Double,
        lng2: Double,
    ): Double {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLng = Math.toRadians(Haversine.normaliseLongitudeDelta(lng2 - lng1))
        val y = sin(dLng) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLng)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Smallest absolute turn between two bearings, in `[0, 180]`. */
    public fun difference(fromDeg: Double, toDeg: Double): Double =
        abs(signedDifference(fromDeg, toDeg))

    /**
     * Signed smallest turn from [fromDeg] to [toDeg], degrees in `(-180, 180]`.
     * Positive is clockwise — a right turn; negative is a left turn.
     *
     * [difference] discards the direction, which is all the acceptance gates need. But
     * signed curvature — telling one 90° right from two 45° lefts, or accumulating a
     * roundabout — cannot be built on an absolute value, so the sign is preserved here.
     * The exact-U-turn case lands on `+180`, not `-180`, so the two representations of
     * the same turn cannot both occur.
     *
     * `mod`, not `%`, deliberately: remainder keeps the dividend's sign, so multi-wrap
     * operands — exactly what an accumulating consumer produces after a couple of
     * roundabout circuits — would leave the documented range. Floored `mod` makes any
     * finite pair of bearings, normalised or not, land in contract.
     */
    public fun signedDifference(fromDeg: Double, toDeg: Double): Double {
        val d = (toDeg - fromDeg).mod(360.0)
        return if (d > 180.0) d - 360.0 else d
    }

    /**
     * Heading of a north/east velocity vector, degrees in `[0, 360)`.
     *
     * `atan2(east, north)`, not the usual `atan2(y, x)`: compass bearings run clockwise
     * from north, where mathematical angles run anticlockwise from east.
     *
     * @return `null` for a vector with no meaningful direction — a filter holding
     *   near-zero velocity has a heading made of rounding error, and callers need to know
     *   that rather than be handed a plausible number.
     */
    public fun ofVelocity(northMps: Float, eastMps: Float): Double? {
        if (abs(northMps) < EPSILON_MPS && abs(eastMps) < EPSILON_MPS) return null
        return (Math.toDegrees(atan2(eastMps.toDouble(), northMps.toDouble())) + 360.0) % 360.0
    }

    private const val EPSILON_MPS = 1e-4f
}
