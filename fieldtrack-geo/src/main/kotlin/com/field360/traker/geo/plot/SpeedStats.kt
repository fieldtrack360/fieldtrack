package com.field360.traker.geo.plot

import com.field360.traker.geo.math.Haversine
import com.field360.traker.geo.model.TrackPoint

/**
 * Per-segment speed statistics (spec §20).
 *
 * Two filters decide whether the numbers mean anything:
 *
 * **Sub-threshold legs are ignored** — under 20 m or under 5 s is jitter, and dividing
 * a metre of noise by a second of time produces a speed that is pure artefact (EC-95).
 *
 * **The phantom-leg guard** excludes legs of ≥ 10 min covering < 500 m from the *speed*
 * statistics while still counting their distance. Such a leg is a GPS-silent dwell, not
 * travel; leaving it in drags the duration-weighted percentile down into the walking
 * band and a car ride gets labelled a walk (EC-96).
 *
 * The percentile is **duration-weighted**: a five-second burst of 20 m/s should not
 * outweigh twenty minutes at 8 m/s.
 */
public object SpeedStats {

    public data class Stats(
        val distanceMeters: Double = 0.0,
        val durationSec: Long = 0,
        val maxSpeedMps: Float = 0f,
        val p75SpeedMps: Float = 0f,
        val avgSpeedMps: Float = 0f,
    )

    private data class Leg(val speedMps: Double, val durationSec: Double, val distanceM: Double)

    public fun compute(points: List<TrackPoint>): Stats {
        if (points.size < 2) return Stats()

        val legs = mutableListOf<Leg>()
        var totalDistance = 0.0

        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val distance = Haversine.metres(a.latitude, a.longitude, b.latitude, b.longitude)
            val seconds = (b.timeMs - a.timeMs) / 1000.0

            totalDistance += distance

            if (distance < MIN_LEG_DISTANCE_M || seconds < MIN_LEG_SECONDS) continue

            // Long, short-displacement legs are dwells with the GPS off, not travel.
            // Their distance still counts; their speed must not (EC-96).
            val isPhantom = seconds >= PHANTOM_LEG_SECONDS && distance < PHANTOM_LEG_DISTANCE_M
            if (isPhantom) continue

            legs += Leg(distance / seconds, seconds, distance)
        }

        val durationSec = ((points.last().timeMs - points.first().timeMs) / 1000).coerceAtLeast(0)
        if (legs.isEmpty()) {
            return Stats(distanceMeters = totalDistance, durationSec = durationSec)
        }

        val movingSeconds = legs.sumOf { it.durationSec }
        val movingDistance = legs.sumOf { it.distanceM }

        return Stats(
            distanceMeters = totalDistance,
            durationSec = durationSec,
            maxSpeedMps = legs.maxOf { it.speedMps }.toFloat(),
            p75SpeedMps = durationWeightedPercentile(legs, PERCENTILE).toFloat(),
            avgSpeedMps = if (movingSeconds > 0) (movingDistance / movingSeconds).toFloat() else 0f,
        )
    }

    /** Co-sort by speed, then walk cumulative duration to the target weight fraction. */
    private fun durationWeightedPercentile(legs: List<Leg>, percentile: Double): Double {
        val sorted = legs.sortedBy { it.speedMps }
        val totalWeight = sorted.sumOf { it.durationSec }
        if (totalWeight <= 0.0) return 0.0

        val target = totalWeight * percentile
        var cumulative = 0.0
        for (leg in sorted) {
            cumulative += leg.durationSec
            if (cumulative >= target) return leg.speedMps
        }
        return sorted.last().speedMps
    }

    public const val MIN_LEG_DISTANCE_M: Double = 20.0
    public const val MIN_LEG_SECONDS: Double = 5.0
    public const val PHANTOM_LEG_SECONDS: Double = 600.0
    public const val PHANTOM_LEG_DISTANCE_M: Double = 500.0
    public const val PERCENTILE: Double = 0.75
}
