package com.devstree.trackit.geo.plot

import com.devstree.trackit.geo.model.GeoPoint
import kotlin.math.roundToLong

/**
 * Google's encoded-polyline algorithm.
 *
 * Precision is a **parameter, never an assumption**. Default 6 (~0.11 m resolution);
 * pass 5 for libraries that hardcode it. The chosen value travels with the track in
 * `Track.precision`, because a precision mismatch does not fail loudly — it silently
 * scales the track by 10× and drops it in the wrong hemisphere (EC-110).
 */
public object PolylineCodec {

    public fun encode(points: List<GeoPoint>, precision: Int = 6): String {
        if (points.isEmpty()) return ""
        val factor = pow10(precision)
        val out = StringBuilder()
        var prevLat = 0L
        var prevLng = 0L

        for (point in points) {
            val lat = (point.latitude * factor).roundToLong()
            val lng = (point.longitude * factor).roundToLong()
            encodeValue(lat - prevLat, out)
            encodeValue(lng - prevLng, out)
            prevLat = lat
            prevLng = lng
        }
        return out.toString()
    }

    public fun decode(encoded: String, precision: Int = 6): List<GeoPoint> {
        if (encoded.isEmpty()) return emptyList()
        val factor = pow10(precision)
        val result = mutableListOf<GeoPoint>()
        var index = 0
        var lat = 0L
        var lng = 0L

        while (index < encoded.length) {
            val dLat = decodeValue(encoded, index) ?: break
            index = dLat.second
            lat += dLat.first

            val dLng = decodeValue(encoded, index) ?: break
            index = dLng.second
            lng += dLng.first

            result += GeoPoint(lat / factor, lng / factor)
        }
        return result
    }

    private fun encodeValue(value: Long, out: StringBuilder) {
        var v = if (value < 0) (value shl 1).inv() else (value shl 1)
        while (v >= 0x20) {
            out.append(((0x20 or (v and 0x1f).toInt()) + 63).toChar())
            v = v shr 5
        }
        out.append((v.toInt() + 63).toChar())
    }

    /** @return decoded delta paired with the next index, or null when input is exhausted. */
    private fun decodeValue(encoded: String, start: Int): Pair<Long, Int>? {
        var index = start
        var shift = 0
        var result = 0L
        var byte: Int

        do {
            if (index >= encoded.length) return null
            byte = encoded[index++].code - 63
            result = result or ((byte and 0x1f).toLong() shl shift)
            shift += 5
        } while (byte >= 0x20)

        val delta = if (result and 1L != 0L) (result shr 1).inv() else (result shr 1)
        return delta to index
    }

    private fun pow10(precision: Int): Double {
        var factor = 1.0
        repeat(precision) { factor *= 10 }
        return factor
    }

    /**
     * Streaming variant of [encode] for a polyline that only ever grows.
     *
     * The algorithm is per-point deltas, so appending is natural — what a live track
     * must never do is re-encode its whole tail on every fix (SMOOTH-NAV-PLAN Phase 2).
     * [snapshot] at any moment equals `encode(pointsSoFar, precision)` exactly, so a
     * consumer can decode with the plain [decode].
     */
    public class Encoder(private val precision: Int = 6) {
        private val out = StringBuilder()
        private val factor = pow10(precision)
        private var prevLat = 0L
        private var prevLng = 0L

        public fun add(point: GeoPoint) {
            val lat = (point.latitude * factor).roundToLong()
            val lng = (point.longitude * factor).roundToLong()
            encodeValue(lat - prevLat, out)
            encodeValue(lng - prevLng, out)
            prevLat = lat
            prevLng = lng
        }

        public fun snapshot(): String = out.toString()
    }

    /**
     * Streaming counterpart of [Encoder] for an append-only polyline.
     *
     * A delta encoding cannot be decoded from an arbitrary offset — every point is
     * relative to the previous one — so a renderer receiving a growing tail would have
     * to re-decode the whole string per frame. This keeps the running reference
     * instead: each [drain] decodes only what was appended since the last call.
     *
     * The caller must pass ever-extending snapshots of the *same* polyline (which
     * [Encoder] guarantees); a different or rewritten string needs a fresh decoder.
     */
    public class Decoder(private val precision: Int = 6) {
        private val factor = pow10(precision)
        private var index = 0
        private var lat = 0L
        private var lng = 0L

        /** The points appended beyond what previous calls consumed; empty when none. */
        public fun drain(encoded: String): List<GeoPoint> {
            val out = mutableListOf<GeoPoint>()
            while (index < encoded.length) {
                val dLat = decodeValue(encoded, index) ?: break
                val dLng = decodeValue(encoded, dLat.second) ?: break
                index = dLng.second
                lat += dLat.first
                lng += dLng.first
                out += GeoPoint(lat / factor, lng / factor)
            }
            return out
        }
    }
}
