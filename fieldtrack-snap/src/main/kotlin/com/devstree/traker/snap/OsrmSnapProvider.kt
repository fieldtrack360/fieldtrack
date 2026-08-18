package com.devstree.traker.snap

import com.devstree.traker.geo.model.GeoPoint
import com.devstree.traker.geo.port.RoadSnapProvider
import com.devstree.traker.geo.port.SnapFix
import com.devstree.traker.geo.port.SnapRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Map-matching against an OSRM server.
 *
 * OSRM's `/match` endpoint is true map matching — a Hidden Markov model over the whole
 * trace — rather than nearest-road projection per point, which is what makes it able to
 * pick the right carriageway of a dual carriageway instead of the nearest one.
 *
 * **Why OSRM and not the vendor named in PLAN.md §5.** ORS has no public matching
 * endpoint; its `/directions` service re-*routes* between coordinates, which invents a
 * plausible path rather than reporting the one that was driven, and would quietly
 * straighten out any detour the user actually took. Mapbox and Google Roads both match
 * properly but need an account and a key before a single line can be tested. OSRM matches
 * properly, is self-hostable, and is exercised here against a `MockWebServer`. None of
 * this is load-bearing: [RoadSnapProvider] is one function, and a host wanting Google
 * Roads writes that function instead of taking this dependency.
 *
 * **There is no default [baseUrl] on purpose.** The OSRM demo server is documented as
 * having no availability guarantee and no rate limit worth relying on; defaulting to it
 * would put every host's production traffic on somebody else's free instance without
 * anyone deciding to. Point this at your own deployment.
 *
 * OkHttp is `compileOnly` in this module, exactly as in `trackit-sync`: it is linked only
 * if the host already has it or adds it.
 */
public class OsrmSnapProvider(
    private val baseUrl: String,
    private val profile: String = DEFAULT_PROFILE,
    private val client: OkHttpClient = defaultClient(),
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val searchRadiusM: Int = DEFAULT_SEARCH_RADIUS_M,
    private val headers: Map<String, String> = emptyMap(),
    /**
     * Matchings OSRM is less sure of than this are discarded and their stretch keeps
     * its raw coordinates (SMOOTH-NAV-PLAN Phase 5). Set to `0` to accept whatever
     * comes back, which is what this provider did before the field was read at all.
     */
    private val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    /**
     * Matched chunks kept between calls. Set to 0 to disable — worth doing only if the
     * server's map data changes during a session, which for a road network it does not.
     */
    cacheEntries: Int = ChunkCache.DEFAULT_MAX_ENTRIES,
) : RoadSnapProvider {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Held on the provider, so it survives across `buildTrack` calls. That is the whole
     * value: a host drawing a live map rebuilds the track on every accepted fix, and
     * without this each rebuild re-matches the entire trace from the first coordinate
     * (EC-100a).
     */
    private val cache = ChunkCache(cacheEntries)

    /**
     * @return road geometry for [path], or [path] itself for any stretch that could not
     *   be matched.
     *
     * **Degrades per chunk, never wholesale** (EC-100). A trace long enough to be split
     * across ten requests should not lose the nine that succeeded because the tenth hit a
     * rate limit; the failed stretch contributes its raw coordinates and
     * [com.devstree.traker.geo.plot.Snapper] then declines to snap anything to them,
     * because they are further from the returned "road" than the off-road guard allows.
     *
     * Returning an empty list — the whole trace failed — is the contract's way of saying
     * "unavailable", and `TrackBuilder` turns that into a `snap_unavailable` warning.
     */
    override suspend fun snap(path: List<GeoPoint>): List<GeoPoint> =
        snap(SnapRequest(path.map { SnapFix(it) }))

    /**
     * The richer form (SMOOTH-NAV-PLAN Phase 5). When the caller supplies timestamps
     * they are forwarded to OSRM, which is a material upgrade rather than a detail: the
     * HMM's transition probability compares the distance between two fixes against the
     * road distance between their candidates, and without an interval it cannot tell a
     * plausible 12 s leg from an implausible one. Per-fix accuracy becomes the per-fix
     * search radius for the same reason a fixed radius is a compromise — a 5 m fix and
     * a 40 m fix do not deserve the same net.
     */
    override suspend fun snap(request: SnapRequest): List<GeoPoint> {
        val fixes = request.fixes
        if (fixes.size < MIN_MATCHABLE) return emptyList()

        val sendTimestamps = request.hasTimestamps
        val matched = withContext(Dispatchers.IO) {
            chunk(fixes).map { chunk -> matchOrRaw(chunk, sendTimestamps) }
        }
        // Every chunk failing is indistinguishable from having no provider at all, and
        // saying so is more useful than handing back a copy of the input.
        if (matched.all { it.snapped.isEmpty() }) return emptyList()

        return stitch(matched)
    }

    // ─────────────────────────────────────────────────────────────────────────

    private data class Chunk(val raw: List<GeoPoint>, val snapped: List<GeoPoint>)

    /**
     * Overlapping chunks: each starts on the previous one's last coordinate.
     *
     * Without the overlap the matcher sees each chunk as a trace that begins from
     * nowhere, and the joint between two chunks is a straight line across whatever the
     * matcher decided independently on either side.
     */
    internal fun <T> chunk(items: List<T>): List<List<T>> {
        if (items.size <= chunkSize) return listOf(items)

        val chunks = mutableListOf<List<T>>()
        var start = 0
        while (start < items.lastIndex) {
            val end = minOf(start + chunkSize, items.size)
            chunks += items.subList(start, end)
            start = end - 1
        }
        return chunks
    }

    private fun matchOrRaw(chunk: List<SnapFix>, sendTimestamps: Boolean): Chunk {
        val raw = chunk.map { it.point }
        if (chunk.size < MIN_MATCHABLE) return Chunk(raw, emptyList())
        // Keyed on coordinates alone: the timestamps and accuracies attached to a given
        // stretch of a session do not change between redraws, so they cannot change the
        // answer for a key that already hit (EC-100a).
        val snapped = cache.getOrPut(raw) {
            runCatching { match(chunk, sendTimestamps) }.getOrDefault(emptyList())
        }
        return Chunk(raw, snapped)
    }

    /** A failed chunk contributes the coordinates the device actually recorded. */
    private fun stitch(chunks: List<Chunk>): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        chunks.forEach { chunk ->
            val geometry = chunk.snapped.ifEmpty { chunk.raw }
            // Chunks overlap by one coordinate; drop the duplicate at every joint.
            if (out.isNotEmpty() && geometry.isNotEmpty() && out.last() == geometry.first()) {
                out += geometry.drop(1)
            } else {
                out += geometry
            }
        }
        return out
    }

    private fun match(chunk: List<SnapFix>, sendTimestamps: Boolean): List<GeoPoint> {
        val request = Request.Builder()
            .url(urlFor(chunk, sendTimestamps))
            .get()
            .apply { headers.forEach { (name, value) -> addHeader(name, value) } }
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                decode(response.body.string(), chunk.map { it.point })
            }
        } catch (_: IOException) {
            // A dead network is an expected state for an offline-first SDK. The track
            // still renders; it just renders from raw fixes.
            emptyList()
        }
    }

    internal fun urlFor(chunk: List<SnapFix>, sendTimestamps: Boolean = false): String {
        // OSRM takes lon,lat — the opposite order to every other coordinate in this SDK.
        val coordinates = chunk.joinToString(";") { format(it.point.longitude, it.point.latitude) }
        val radiuses = chunk.joinToString(";") { radiusFor(it) }
        return buildString {
            append(baseUrl.trimEnd('/'))
            append("/match/v1/").append(profile).append('/').append(coordinates)
            append("?geometries=geojson&overview=full&tidy=true")
            // Split the match at genuine gaps rather than bridging them. The default
            // (`ignore`) hands back one matching spanning a blackout, which draws a
            // confident straight line down roads nobody was measured on.
            append("&gaps=split")
            append("&radiuses=").append(radiuses)
            if (sendTimestamps) {
                append("&timestamps=")
                append(chunk.joinToString(";") { (it.timeMs / MILLIS_PER_SECOND).toString() })
            }
        }
    }

    /**
     * Per-fix search radius. A fix's own accuracy is the honest net width; the
     * configured [searchRadiusM] is the floor for fixes that claim to be better than
     * that, and the cap keeps a 500 m network fix from dragging in a road two streets
     * away.
     */
    private fun radiusFor(fix: SnapFix): String {
        val radius = if (fix.accuracyM > 0f) {
            fix.accuracyM.toInt().coerceIn(searchRadiusM, MAX_SEARCH_RADIUS_M)
        } else {
            searchRadiusM
        }
        return radius.toString()
    }

    /** [Locale.ROOT] is load-bearing: a comma decimal separator makes the URL nonsense. */
    private fun format(longitude: Double, latitude: Double): String =
        String.format(Locale.ROOT, "%.6f,%.6f", longitude, latitude)

    /**
     * @param raw the coordinates that were sent, for the stretches OSRM could not match.
     *
     * Assembled per matching rather than by flat-mapping them all together. With
     * `gaps=split` a response holds one matching per confidently-matched stretch, and
     * concatenating them draws a straight line between the end of one and the start of
     * the next — across exactly the blackout the split was reporting. Here the
     * [OsrmMatchResponse.tracepoints] say which inputs belong to which matching, so an
     * unmatched or low-confidence stretch contributes the coordinates the device
     * actually recorded and `Snapper`'s off-road guard then declines to snap to them
     * (EC-100, EC-101).
     */
    internal fun decode(body: String, raw: List<GeoPoint> = emptyList()): List<GeoPoint> {
        val response = runCatching { json.decodeFromString<OsrmMatchResponse>(body) }.getOrNull()
            ?: return emptyList()
        if (response.code != OSRM_OK) return emptyList()

        val accepted = response.matchings.map { it.confidence >= minConfidence }
        // No usable tracepoints — an older or minimal server, or a caller that did not
        // pass the coordinates it sent. Nothing says which input belongs where, so the
        // only safe reading is the one that was always taken: accepted matchings in
        // order.
        if (raw.isEmpty() || response.tracepoints.size != raw.size) {
            return response.matchings
                .filterIndexed { index, _ -> accepted.getOrElse(index) { false } }
                .flatMap { geometryOf(it) }
        }

        val out = mutableListOf<GeoPoint>()
        var index = 0
        while (index < raw.size) {
            val matchingIndex = response.tracepoints[index]
                ?.matchingsIndex
                ?.takeIf { it >= 0 && it < response.matchings.size && accepted[it] }

            // One run per matching (or per unmatched stretch); the run is what decides
            // whether road geometry or raw coordinates go out.
            val start = index
            while (index < raw.size &&
                response.tracepoints[index]?.matchingsIndex?.takeIf {
                    it >= 0 && it < response.matchings.size && accepted[it]
                } == matchingIndex
            ) {
                index++
            }

            out += if (matchingIndex == null) {
                raw.subList(start, index)
            } else {
                geometryOf(response.matchings[matchingIndex])
            }
        }
        return out
    }

    private fun geometryOf(matching: OsrmMatching): List<GeoPoint> =
        matching.geometry?.coordinates.orEmpty()
            // GeoJSON positions are [lon, lat]; anything else is not a position.
            .mapNotNull { position ->
                if (position.size < 2) null else GeoPoint(position[1], position[0])
            }

    public companion object {
        public const val DEFAULT_PROFILE: String = "driving"

        /**
         * OSRM's default `max-matching-size` is 100 coordinates; 90 leaves room for the
         * overlap coordinate and for a server configured slightly tighter.
         */
        public const val DEFAULT_CHUNK_SIZE: Int = 90

        /**
         * 40 m. Wide enough for ordinary urban GPS error, tight enough that the matcher
         * does not consider a road a block away. Deliberately tighter than `Snapper`'s
         * 80 m off-road guard, so the guard stays the last word rather than the only one.
         */
        public const val DEFAULT_SEARCH_RADIUS_M: Int = 40

        /**
         * A fix cannot widen its own net past this, however bad its accuracy claims to
         * be — beyond it the matcher is choosing between streets, not lanes.
         */
        public const val MAX_SEARCH_RADIUS_M: Int = 100

        /**
         * 0.3. OSRM's confidence is famously conservative — a perfectly good urban
         * match on a dense grid routinely scores in the 0.4-0.6 range, so a threshold
         * anywhere near 0.5 throws away geometry that is right. This one is set to
         * catch the matches that are *obviously* guesses while leaving the ordinary
         * ones alone; the 80 m off-road guard in `Snapper` remains the last word
         * either way (EC-101).
         */
        public const val DEFAULT_MIN_CONFIDENCE: Double = 0.3

        public fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        private const val OSRM_OK = "Ok"
        private const val MIN_MATCHABLE = 2
        private const val CONNECT_SECONDS = 5L
        private const val READ_SECONDS = 30L

        /** OSRM's `timestamps` are unix **seconds**; sending millis matches nothing. */
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
