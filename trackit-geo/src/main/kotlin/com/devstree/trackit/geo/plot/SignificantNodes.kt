package com.devstree.trackit.geo.plot

import com.devstree.trackit.geo.math.Haversine
import com.devstree.trackit.geo.model.TrackPoint

/**
 * Picks the indices worth showing as timeline nodes (spec §18).
 *
 * Two guards carry most of the weight:
 *
 * **Gap-stop protection.** A ten-minute silence usually means the user sat still, not
 * that they teleported. If the implied speed across the gap is under 35 m/min, or the
 * displacement is under 100 m, the gap is treated as *dwell* and extends the current
 * stop rather than opening a travel leg. Without it every signal gap becomes a phantom
 * trip.
 *
 * **Jitter guard.** A new node is only added when it is more than 100 m from the last
 * one, so residual scatter cannot produce a row of markers on top of each other.
 *
 * Finalising a stop keeps the **arrival** index, deliberately not the medoid, so the
 * timeline reads "arrived at X" without inventing a phantom walking node first.
 */
public object SignificantNodes {

    public fun detect(
        points: List<TrackPoint>,
        stopRadiusM: Double = STOP_RADIUS_M,
        significantDwellSec: Long = SIGNIFICANT_DWELL_SEC,
        gapStopSec: Long = GAP_STOP_SEC,
        gapSpeedMetresPerMin: Double = GAP_SPEED_M_PER_MIN,
        jitterGuardM: Double = JITTER_GUARD_M,
        hopToleranceM: Double = HOP_TOLERANCE_M,
    ): List<Int> {
        if (points.size <= 1) return points.indices.toList()

        val nodes = mutableListOf(0)

        var clusterStart = 0
        var centroidLat = points[0].latitude
        var centroidLng = points[0].longitude
        var clusterSize = 1

        fun finaliseCluster(endIndex: Int) {
            val dwellSec = (points[endIndex].timeMs - points[clusterStart].timeMs) / 1000
            if (dwellSec >= significantDwellSec && nodes.lastOrNull() != clusterStart) {
                // Finalised stops bypass the jitter guard — a real dwell always earns
                // its node, however close it sits to the previous one.
                nodes += clusterStart
            }
        }

        for (index in 1 until points.size) {
            val previous = points[index - 1]
            val point = points[index]

            val gapMin = (point.timeMs - previous.timeMs) / 60_000.0
            val hop = Haversine.metres(previous.latitude, previous.longitude, point.latitude, point.longitude)
            val fromCentroid = Haversine.metres(centroidLat, centroidLng, point.latitude, point.longitude)

            val gapIsDwell = gapMin >= gapStopSec / 60.0 &&
                (hop / gapMin.coerceAtLeast(1.0) <= gapSpeedMetresPerMin || hop < jitterGuardM)

            val staysInCluster = fromCentroid <= stopRadiusM || hop <= hopToleranceM || gapIsDwell

            if (staysInCluster) {
                clusterSize++
                centroidLat += (point.latitude - centroidLat) / clusterSize
                centroidLng += (point.longitude - centroidLng) / clusterSize
            } else {
                finaliseCluster(index - 1)

                val lastNode = points[nodes.last()]
                val fromLastNode = Haversine.metres(
                    lastNode.latitude,
                    lastNode.longitude,
                    point.latitude,
                    point.longitude,
                )
                if (fromLastNode > jitterGuardM) nodes += index

                clusterStart = index
                centroidLat = point.latitude
                centroidLng = point.longitude
                clusterSize = 1
            }
        }

        finaliseCluster(points.lastIndex)
        if (nodes.last() != points.lastIndex) nodes += points.lastIndex

        return nodes.distinct().sorted()
    }

    public const val STOP_RADIUS_M: Double = 100.0
    public const val SIGNIFICANT_DWELL_SEC: Long = 600
    public const val GAP_STOP_SEC: Long = 600
    public const val GAP_SPEED_M_PER_MIN: Double = 35.0
    public const val JITTER_GUARD_M: Double = 100.0
    public const val HOP_TOLERANCE_M: Double = 15.0
}
