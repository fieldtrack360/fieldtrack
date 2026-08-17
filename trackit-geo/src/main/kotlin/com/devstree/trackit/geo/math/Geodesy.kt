package com.devstree.trackit.geo.math

import com.devstree.trackit.geo.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Great-circle interpolation and small metric offsets — the math under the animated
 * puck (SMOOTH-NAV-PLAN Phase 3).
 *
 * Lives here, not in `trackit-maps`, for the same reason every other algorithm does:
 * geometry is engine business, renderers only wire it to platform APIs (PLAN.md §3).
 */
public object Geodesy {

    /**
     * The point [fraction] of the way from [from] to [to] along the great circle —
     * the "spherical lerp" a marker animation must use. Naive per-axis lerp bows a
     * long step off the great circle and, worse, walks the wrong way around the world
     * across the antimeridian; the 3D formulation has neither problem.
     *
     * [fraction] outside `[0, 1]` extrapolates along the same circle, which is exactly
     * what a dead-reckoning overshoot wants.
     */
    public fun interpolate(from: GeoPoint, to: GeoPoint, fraction: Double): GeoPoint {
        val lat1 = Math.toRadians(from.latitude)
        val lng1 = Math.toRadians(from.longitude)
        val lat2 = Math.toRadians(to.latitude)
        val lng2 = Math.toRadians(to.longitude)

        // Angular distance via haversine — stable for the small angles GPS produces.
        val dLat = lat2 - lat1
        val dLng = lng2 - lng1
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLng / 2) * sin(dLng / 2)
        val angle = 2 * kotlin.math.asin(sqrt(h.coerceIn(0.0, 1.0)))

        // Coincident (or antipodal-degenerate) endpoints: nothing to slerp.
        val sinAngle = sin(angle)
        if (sinAngle < MIN_SIN_ANGLE) return from

        val a = sin((1 - fraction) * angle) / sinAngle
        val b = sin(fraction * angle) / sinAngle

        val x = a * cos(lat1) * cos(lng1) + b * cos(lat2) * cos(lng2)
        val y = a * cos(lat1) * sin(lng1) + b * cos(lat2) * sin(lng2)
        val z = a * sin(lat1) + b * sin(lat2)

        return GeoPoint(
            Math.toDegrees(atan2(z, sqrt(x * x + y * y))),
            Math.toDegrees(atan2(y, x)),
        )
    }

    /**
     * [origin] displaced [northM] metres north and [eastM] metres east on the local
     * tangent plane. Flat-earth arithmetic — exact enough for the few metres of
     * dead-reckoning lookahead it exists for, and never used for anything larger.
     */
    public fun offsetMeters(origin: GeoPoint, northM: Double, eastM: Double): GeoPoint {
        val lat = origin.latitude + northM / METERS_PER_DEGREE_LAT
        val lngDelta = eastM / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(origin.latitude)))
        return GeoPoint(lat, Haversine.normaliseLongitudeDelta(origin.longitude + lngDelta))
    }

    /**
     * Where [point] lands when dropped perpendicularly onto the segment `a → b`.
     *
     * Local equirectangular metres about [a] — the same flat-earth treatment
     * [offsetMeters] uses, and exact enough over the road segments and GPS legs this
     * serves (tens to hundreds of metres). [fraction] is clamped to `[0, 1]`, so a
     * point beyond either end projects onto that end rather than onto the segment's
     * infinite extension.
     */
    public fun projectOntoSegment(point: GeoPoint, a: GeoPoint, b: GeoPoint): Projection {
        val lngScale = cos(Math.toRadians(a.latitude))
        fun east(p: GeoPoint) = Haversine.normaliseLongitudeDelta(p.longitude - a.longitude) *
            METERS_PER_DEGREE_LAT * lngScale
        fun north(p: GeoPoint) = (p.latitude - a.latitude) * METERS_PER_DEGREE_LAT

        val bx = east(b)
        val by = north(b)
        val px = east(point)
        val py = north(point)

        val lengthSq = bx * bx + by * by
        // A zero-length segment is a vertex: the projection is that vertex.
        val fraction = if (lengthSq < MIN_SEGMENT_LENGTH_SQ) 0.0 else ((px * bx + py * by) / lengthSq).coerceIn(0.0, 1.0)

        val projected = offsetMeters(a, northM = by * fraction, eastM = bx * fraction)
        return Projection(
            point = projected,
            fraction = fraction,
            distanceM = Haversine.metres(point.latitude, point.longitude, projected.latitude, projected.longitude),
        )
    }

    /**
     * Perpendicular distance from [point] to the segment `a → b`, metres — the
     * Douglas-Peucker criterion, and the reason it keeps corners where
     * area-based simplification rounds them off.
     */
    public fun crossTrackMeters(point: GeoPoint, a: GeoPoint, b: GeoPoint): Double =
        projectOntoSegment(point, a, b).distanceM

    /**
     * @property fraction position along the segment in `[0, 1]`; `0` is [a], `1` is `b`.
     */
    public data class Projection(
        val point: GeoPoint,
        val fraction: Double,
        val distanceM: Double,
    )

    /** `2πR / 360` with the same R the rest of the engine uses. */
    public val METERS_PER_DEGREE_LAT: Double = Math.PI * Haversine.EARTH_RADIUS_M / 180.0

    private const val MIN_SIN_ANGLE = 1e-12

    /** (1 mm)² — below this a segment is a point, and the division below is undefined. */
    private const val MIN_SEGMENT_LENGTH_SQ = 1e-6
}
