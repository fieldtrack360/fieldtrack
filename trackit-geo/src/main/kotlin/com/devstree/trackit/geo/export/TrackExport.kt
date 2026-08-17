package com.devstree.trackit.geo.export

import com.devstree.trackit.geo.plot.PolylineCodec
import com.devstree.trackit.geo.plot.model.SegmentType
import com.devstree.trackit.geo.plot.model.Track
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Polyline JSON — the wire format in POLYLINE-JSON.md §1. */
public object TrackJson {

    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        explicitNulls = false
    }

    public fun encode(track: Track): String = json.encodeToString(track)

    public fun decode(raw: String): Track = json.decodeFromString(raw)
}

/**
 * GeoJSON `FeatureCollection` (POLYLINE-JSON.md §3).
 *
 * **Coordinates are `[longitude, latitude]`** per RFC 7946 — the opposite order from
 * `points[]` in the polyline JSON. That inversion is the classic source of "my track is
 * in the ocean", so it is called out here as well as in the doc.
 */
public object GeoJson {

    public fun encode(track: Track): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            track.segments.forEach { segment ->
                if (segment.type != SegmentType.TRAVEL) return@forEach
                val coordinates = PolylineCodec.decode(segment.encodedPolyline, track.precision)
                if (coordinates.size < 2) return@forEach

                addJsonObject {
                    put("type", "Feature")
                    putJsonObject("geometry") {
                        put("type", "LineString")
                        putJsonArray("coordinates") {
                            coordinates.forEach { point ->
                                addJsonArray {
                                    add(point.longitude) // lng first — RFC 7946
                                    add(point.latitude)
                                }
                            }
                        }
                    }
                    putJsonObject("properties") {
                        put("type", "travel")
                        put("speedBand", segment.speedBand)
                        put("activity", segment.activity)
                        put("distanceMeters", segment.distanceMeters)
                        put("durationSec", segment.durationSec)
                        put("avgSpeedMps", segment.avgSpeedMps)
                    }
                }
            }

            track.stops.forEach { stop ->
                addJsonObject {
                    put("type", "Feature")
                    putJsonObject("geometry") {
                        put("type", "Point")
                        putJsonArray("coordinates") {
                            add(stop.lng)
                            add(stop.lat)
                        }
                    }
                    putJsonObject("properties") {
                        put("index", stop.index)
                        put("dwellSec", stop.dwellSec)
                        put("arrivalMs", stop.arrivalMs)
                        put("departureMs", stop.departureMs)
                        put("address", stop.address)
                        put("isOngoing", stop.isOngoing)
                    }
                }
            }

            track.arrows.forEach { arrow ->
                addJsonObject {
                    put("type", "Feature")
                    putJsonObject("geometry") {
                        put("type", "Point")
                        putJsonArray("coordinates") {
                            add(arrow.lng)
                            add(arrow.lat)
                        }
                    }
                    putJsonObject("properties") {
                        // Style with icon-rotate: ["get", "bearing"].
                        put("bearing", arrow.bearing)
                        put("segment", arrow.segment)
                    }
                }
            }
        }
    }.toString()
}
