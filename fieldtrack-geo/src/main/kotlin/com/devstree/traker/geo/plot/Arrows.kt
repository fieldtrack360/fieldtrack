package com.devstree.traker.geo.plot

import com.devstree.traker.geo.math.Bearing
import com.devstree.traker.geo.math.Haversine
import com.devstree.traker.geo.model.GeoPoint
import com.devstree.traker.geo.plot.model.ArrowAnchor

/**
 * Direction arrows — **one implementation**, used by both the map renderer and the JSON
 * export.
 *
 * That is the whole point. The reference had two spacing ladders: one on initial draw,
 * one on the zoom-change path, differing at the middle tiers (1500 m vs 800 m) and in
 * arrow half-length. Arrow density visibly changed after the first pinch even when you
 * returned to the original zoom, and the drawn track disagreed with the exported one
 * (SOURCE-AUDIT A9, EC-108). There is one ladder here and one function; renderers
 * cannot diverge from the export because there is nothing to diverge from.
 */
public object Arrows {

    /**
     * @param path the drawable path, already consolidated/snapped/rounded. Dense road
     *   geometry is fine — see [placementLegs].
     * @param zoom map zoom; selects the spacing tier.
     * @param segmentIndex value written into each anchor's `segment` field.
     */
    public fun place(
        path: List<GeoPoint>,
        zoom: Float,
        segmentIndex: Int = 0,
        minSegmentM: Double = MIN_SEGMENT_M,
    ): List<ArrowAnchor> {
        // Below z10 a track is a few pixels long; arrows would be confetti (EC-107).
        if (zoom < MIN_ZOOM || path.size < 2) return emptyList()

        val legs = placementLegs(path, minSegmentM)
        val routeLength = pathLength(path)
        val spacing = spacingFor(zoom, routeLength)
        val anchors = mutableListOf<ArrowAnchor>()

        for (i in 1 until legs.size) {
            val a = legs[i - 1]
            val b = legs[i]
            val length = Haversine.metres(a.latitude, a.longitude, b.latitude, b.longitude)

            // Too short to hold an arrow without overlapping the whole leg (EC-106).
            if (length < minSegmentM) continue

            val bearing = Bearing.degrees(a.latitude, a.longitude, b.latitude, b.longitude)

            val fractions = when {
                // A data jump is not a journey. Two arrows acknowledge the direction
                // without implying anything about the middle (EC-105).
                length > DATA_JUMP_M -> listOf(0.25, 0.75)
                length <= SINGLE_ARROW_M -> listOf(0.5)
                else -> evenlySpaced(length, spacing)
            }

            fractions.forEach { fraction ->
                val point = interpolate(a, b, fraction)
                anchors += ArrowAnchor(point.latitude, point.longitude, bearing, segmentIndex)
            }
        }
        return anchors
    }

    /**
     * Thins [path] to vertices at least [minSegmentM] apart, for placement only.
     *
     * EC-106 skips any leg shorter than 60 m, because an arrow on one overlaps the whole
     * leg. That rule was written for **sparse** capture, where a short leg means a pause.
     * Once [Snapper] injects road geometry the legs are the road's own vertices — often
     * 10-30 m apart on a bend — and applying the rule leg-by-leg deletes nearly every
     * arrow on exactly the tracks that have the best geometry.
     *
     * Thinning first restores the rule's intent: arrows sit on spans long enough to hold
     * them, and their bearing is the chord across that span rather than across one road
     * vertex pair. On a raw path whose legs already clear the threshold this returns the
     * input unchanged, so nothing about an unsnapped track moves.
     *
     * The drawn polyline is untouched — this is the arrow ladder's view of the path, not
     * the path.
     */
    private fun placementLegs(path: List<GeoPoint>, minSegmentM: Double): List<GeoPoint> {
        val kept = ArrayList<GeoPoint>(path.size)
        kept += path.first()

        for (i in 1 until path.lastIndex) {
            val candidate = path[i]
            val last = kept.last()
            val step = Haversine.metres(last.latitude, last.longitude, candidate.latitude, candidate.longitude)
            if (step >= minSegmentM) kept += candidate
        }
        kept += path.last()
        return kept
    }

    /**
     * Spacing ladder (spec §23.2). Long routes get distance-based spacing so a
     * cross-country track does not accumulate thousands of markers; short ones get
     * zoom-based spacing so detail appears as you zoom in.
     */
    internal fun spacingFor(zoom: Float, routeLengthM: Double): Double = when {
        routeLengthM > 40_000 -> 5_000.0
        routeLengthM > 10_000 -> 2_500.0
        zoom >= 18f -> 80.0
        zoom >= 15f -> 300.0
        zoom >= 13f -> 800.0
        else -> 4_000.0
    }

    /**
     * Re-request only when zoom moved more than half a level; below that the placement
     * is visually identical and re-rendering is wasted work (spec §2 of POLYLINE-JSON).
     */
    public fun shouldReplace(previousZoom: Float, newZoom: Float): Boolean =
        kotlin.math.abs(newZoom - previousZoom) > ZOOM_EPSILON

    private fun evenlySpaced(lengthM: Double, spacingM: Double): List<Double> {
        val count = (lengthM / spacingM).toInt()
        if (count <= 0) return listOf(0.5)
        // Offset by half a step so arrows sit inside the leg rather than on its vertices.
        return (0 until count).map { (it + 0.5) * spacingM / lengthM }.filter { it < 1.0 }
    }

    private fun interpolate(a: GeoPoint, b: GeoPoint, fraction: Double): GeoPoint {
        val lngDelta = Haversine.normaliseLongitudeDelta(b.longitude - a.longitude)
        return GeoPoint(
            latitude = a.latitude + (b.latitude - a.latitude) * fraction,
            longitude = a.longitude + lngDelta * fraction,
        )
    }

    private fun pathLength(path: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 1 until path.size) {
            total += Haversine.metres(
                path[i - 1].latitude, path[i - 1].longitude,
                path[i].latitude, path[i].longitude,
            )
        }
        return total
    }

    public const val MIN_ZOOM: Float = 10f
    public const val MIN_SEGMENT_M: Double = 60.0
    public const val SINGLE_ARROW_M: Double = 250.0
    public const val DATA_JUMP_M: Double = 50_000.0
    private const val ZOOM_EPSILON = 0.5f
}
