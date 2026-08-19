package com.field360.traker.snap

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The slice of OSRM's `/match` response this SDK reads.
 *
 * Every field is optional and decoding runs with `ignoreUnknownKeys`, because the parts
 * deliberately not modelled — legs, durations, annotations — are exactly the parts most
 * likely to change shape between OSRM versions and between OSRM and a compatible
 * server. A field this SDK does not read must never be able to fail a decode.
 */
@Serializable
internal data class OsrmMatchResponse(
    /** `"Ok"`, or an error string such as `"NoMatch"` / `"TooBig"`. */
    val code: String = "",
    val matchings: List<OsrmMatching> = emptyList(),
    /**
     * One entry per input coordinate, in input order; `null` where OSRM matched
     * nothing. This is the only thing that says which input belongs to which matching,
     * and without it a response split at a trace gap cannot be reassembled without
     * inventing a segment across the gap (SMOOTH-NAV-PLAN Phase 5).
     */
    val tracepoints: List<OsrmTracepoint?> = emptyList(),
)

@Serializable
internal data class OsrmMatching(
    val geometry: OsrmGeometry? = null,
    /**
     * OSRM's own `[0, 1]` confidence in this matching.
     *
     * Defaults to `1` rather than `0`: a server that does not report confidence has not
     * told us the match is bad, and treating silence as a failure would make every such
     * server look unusable (EC-100).
     */
    val confidence: Double = 1.0,
)

@Serializable
internal data class OsrmTracepoint(
    /** Index into [OsrmMatchResponse.matchings]. */
    @SerialName("matchings_index") val matchingsIndex: Int = -1,
    /** Index of this point within its matching. */
    @SerialName("waypoint_index") val waypointIndex: Int = -1,
)

/** GeoJSON LineString: positions are `[longitude, latitude]`, the opposite of `GeoPoint`. */
@Serializable
internal data class OsrmGeometry(
    val coordinates: List<List<Double>> = emptyList(),
)
