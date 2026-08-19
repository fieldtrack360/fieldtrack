package com.field360.traker.maps

import android.graphics.Color
import com.field360.traker.geo.plot.PolylineCodec
import com.field360.traker.geo.plot.model.SegmentType
import com.field360.traker.geo.plot.model.Track
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import kotlin.math.abs

/**
 * Draws a [Track] on a [GoogleMap].
 *
 * **It recomputes nothing.** Arrow positions and bearings come from `track.arrows`,
 * which the engine produced with the same `Arrows.place()` that fills the JSON export.
 * That is the guarantee the reference could not make: it had two spacing ladders, so
 * arrow density visibly changed after the first pinch and the drawn track disagreed
 * with the exported one (SOURCE-AUDIT A9, EC-108).
 *
 * Not thread-safe and not a view: construct it where the map lives, call [render], and
 * call [clear] when the map goes away.
 */
public class TrackRenderer(
    private val map: GoogleMap,
    private val options: RendererOptions = RendererOptions(),
) {

    public data class RendererOptions(
        val basePathColor: Int = Color.argb(190, 66, 66, 66),
        val basePathWidth: Float = 16f,
        val speedOverlayWidth: Float = 16f,
        val speedOverlayAlpha: Int = 160,
        val cameraPaddingPx: Int = 80,
        val cameraPaddingFallbackPx: Int = 50,
        val arrowSizePx: Int = 48,
        val arrowColor: Int = Color.WHITE,
        val showStopMarkers: Boolean = true,
        val showArrows: Boolean = true,
    )

    private val polylines = mutableListOf<Polyline>()
    private val markers = mutableListOf<Marker>()
    private var arrowIcon: BitmapDescriptor? = null

    /**
     * Pins keyed by number: rasterising a bitmap with a shadow layer per stop per
     * render pass is the expensive way to draw the same digit twice.
     */
    private val pinIcons = mutableMapOf<Int, BitmapDescriptor>()
    private var lastRenderedZoom: Float? = null

    /**
     * @param fitCamera pass false when the user has panned and you do not want the
     *   camera yanked back.
     */
    public fun render(track: Track, fitCamera: Boolean = true) {
        clear()
        if (track.points.isEmpty()) return

        val path = PolylineCodec.decode(track.encodedPolyline, track.precision)
            .map { LatLng(it.latitude, it.longitude) }

        // Fewer than two points cannot be a line — drop straight to markers and a
        // tighter camera fit rather than drawing a degenerate polyline.
        if (path.size < 2) {
            if (options.showStopMarkers) drawStops(track)
            if (fitCamera) fitCamera(track, options.cameraPaddingFallbackPx)
            return
        }

        drawBasePath(path)
        drawSpeedOverlay(track)
        if (options.showArrows) drawArrows(track)
        if (options.showStopMarkers) drawStops(track)
        if (fitCamera) fitCamera(track, options.cameraPaddingPx)

        lastRenderedZoom = map.cameraPosition.zoom
    }

    /**
     * Call from `setOnCameraIdleListener`.
     *
     * Arrow spacing is the only zoom-dependent thing, and re-placing it below half a
     * zoom level is visually identical work. Polylines and markers are never re-drawn on
     * camera moves.
     *
     * @return true when the caller should rebuild the track at the new zoom.
     */
    public fun needsArrowRefresh(): Boolean {
        val previous = lastRenderedZoom ?: return true
        return abs(map.cameraPosition.zoom - previous) > ZOOM_EPSILON
    }

    public fun clear() {
        polylines.forEach { it.remove() }
        polylines.clear()
        markers.forEach { it.remove() }
        markers.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────

    private fun drawBasePath(path: List<LatLng>) {
        polylines += map.addPolyline(
            PolylineOptions()
                .addAll(path)
                .color(options.basePathColor)
                .width(options.basePathWidth)
                .geodesic(true)
                .zIndex(Z_BASE),
        )
    }

    /** One coloured overlay per travel segment, using the engine's speed band. */
    private fun drawSpeedOverlay(track: Track) {
        track.segments
            .filter { it.type == SegmentType.TRAVEL && it.encodedPolyline.isNotEmpty() }
            .forEach { segment ->
                val points = PolylineCodec.decode(segment.encodedPolyline, track.precision)
                    .map { LatLng(it.latitude, it.longitude) }
                if (points.size < 2) return@forEach

                polylines += map.addPolyline(
                    PolylineOptions()
                        .addAll(points)
                        .color(bandColor(segment.speedBand, options.speedOverlayAlpha))
                        .width(options.speedOverlayWidth)
                        .geodesic(true)
                        .zIndex(Z_SPEED),
                )
            }
    }

    private fun drawArrows(track: Track) {
        if (track.arrows.isEmpty()) return
        val icon = arrowIcon ?: ArrowIcons.chevron(options.arrowSizePx, options.arrowColor)
            .also { arrowIcon = it }

        track.arrows.forEach { arrow ->
            markers += map.addMarker(
                MarkerOptions()
                    .position(LatLng(arrow.lat, arrow.lng))
                    .icon(icon)
                    .anchor(HALF, HALF)
                    .rotation(arrow.bearing.toFloat())
                    .flat(true)
                    .zIndex(Z_ARROW),
            ) ?: return@forEach
        }
    }

    private fun drawStops(track: Track) {
        val seen = mutableSetOf<Pair<Double, Double>>()

        track.stops.forEach { stop ->
            // EC-109 — perfectly co-located markers leave only the top one tappable.
            // A sub-metre deterministic nudge keeps them all reachable without moving
            // anything visibly.
            var lat = stop.lat
            var lng = stop.lng
            var attempt = 0
            while (!seen.add(round(lat) to round(lng)) && attempt < MAX_JITTER_ATTEMPTS) {
                attempt++
                lat += JITTER_DEGREES * attempt
                lng += JITTER_DEGREES * attempt
            }

            markers += map.addMarker(
                MarkerOptions()
                    .position(LatLng(lat, lng))
                    .icon(pinIcons.getOrPut(stop.index) { ArrowIcons.numberedPin(stop.index, options.arrowSizePx * 2) })
                    .anchor(HALF, 1f)
                    .title("Stop ${stop.index}")
                    .snippet(stopSnippet(stop.dwellSec, stop.isOngoing))
                    .zIndex(Z_MARKER),
            ) ?: return@forEach
        }
    }

    private fun fitCamera(track: Track, paddingPx: Int) {
        val bounds = track.bounds ?: return
        val latLngBounds = LatLngBounds(
            LatLng(bounds.south, bounds.west),
            LatLng(bounds.north, bounds.east),
        )
        runCatching {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, paddingPx))
        }
    }

    private fun stopSnippet(dwellSec: Long, isOngoing: Boolean): String {
        val minutes = dwellSec / 60
        val text = if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
        return if (isOngoing) "$text (ongoing)" else text
    }

    private fun round(value: Double) = kotlin.math.round(value * ROUND_FACTOR) / ROUND_FACTOR

    private fun bandColor(band: String?, alpha: Int): Int = when (band) {
        "green" -> Color.argb(alpha, 4, 217, 92)
        "yellow" -> Color.argb(alpha, 245, 188, 0)
        else -> Color.argb(alpha, 242, 2, 2)
    }

    private companion object {
        const val Z_BASE = 0f
        const val Z_SPEED = 2f
        const val Z_ARROW = 3f
        const val Z_MARKER = 4f
        const val HALF = 0.5f
        const val ZOOM_EPSILON = 0.5f

        /** ~0.56 m at the equator — enough to separate taps, invisible on screen. */
        const val JITTER_DEGREES = 0.000005
        const val MAX_JITTER_ATTEMPTS = 8
        const val ROUND_FACTOR = 1_000_000.0
    }
}
