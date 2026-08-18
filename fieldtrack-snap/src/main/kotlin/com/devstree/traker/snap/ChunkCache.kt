package com.devstree.traker.snap

import com.devstree.traker.geo.model.GeoPoint

/**
 * Remembers what a stretch of road matched to, so a redraw is not a round trip (EC-100a).
 *
 * The problem this exists for is not obvious from a single call. `buildTrack` fetches road
 * geometry every time it runs, and a host drawing a live map runs it on **every accepted
 * fix** — that is the whole point of observing the point stream. At the 4 s turn-burst
 * cadence a twenty-minute drive is three hundred rebuilds, each re-matching the entire
 * trace from the beginning. Against a self-hosted OSRM that is merely wasteful; against
 * any hosted one it is how a host discovers their rate limit.
 *
 * Caching whole *traces* would not help, because a live trace is a different trace every
 * time a fix lands. Caching **chunks** does: [OsrmSnapProvider] already splits at 90
 * coordinates, and as a track grows only its final chunk changes. Everything before it is
 * byte-identical to the request made four seconds ago, so a growing track costs one
 * request per redraw instead of one per ninety coordinates.
 *
 * Safe to cache because the question is pure — the same coordinates matched against the
 * same server give the same road. A server whose map data is updated mid-session would
 * serve stale geometry until eviction, which is a trade worth making for a road network
 * that changes on the order of months.
 */
internal class ChunkCache(private val maxEntries: Int = DEFAULT_MAX_ENTRIES) {

    /**
     * `LinkedHashMap` in access order is an LRU with no dependency and no ceremony.
     * Guarded by the monitor rather than a concurrent map because `removeEldestEntry`
     * has to see a consistent size, and because reads *mutate* access order — a
     * `ConcurrentHashMap` would not make this safe, only make it look safe.
     */
    private val entries = object : LinkedHashMap<Key, List<GeoPoint>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, List<GeoPoint>>) =
            size > maxEntries
    }

    /**
     * @param compute run only on a miss, outside the lock.
     * @return the matched geometry, or whatever [compute] returned.
     *
     * An **empty** result is deliberately not cached. Empty means the chunk failed —
     * timeout, rate limit, a server restart — and those are transient by nature. Caching
     * a failure would turn one bad moment into a stretch of road that stays raw for the
     * rest of the session, which is exactly the wrong direction: the caller already
     * degrades gracefully per chunk (EC-100), so the cost of retrying is one request and
     * the cost of not retrying is a permanently wrong-looking track.
     */
    fun getOrPut(chunk: List<GeoPoint>, compute: (List<GeoPoint>) -> List<GeoPoint>): List<GeoPoint> {
        val key = Key(chunk)
        synchronized(this) { entries[key] }?.let { return it }

        val computed = compute(chunk)
        if (computed.isNotEmpty()) synchronized(this) { entries[key] = computed }
        return computed
    }

    internal fun size(): Int = synchronized(this) { entries.size }

    /**
     * Identity by coordinate, not by list reference.
     *
     * `List<GeoPoint>` already has structural equality, so this could have been the list
     * itself — but that hashes every coordinate on every lookup, and the lookup happens
     * per chunk per redraw. Hashing once at construction moves that cost to the miss path.
     */
    private class Key(chunk: List<GeoPoint>) {
        private val points = chunk.toList()
        private val hash = points.hashCode()

        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean =
            other is Key && other.hash == hash && other.points == points
    }

    companion object {
        /**
         * 90 coordinates a chunk, so this holds roughly 5 400 matched coordinates — a
         * long day's driving. Bounded because a `Tracker` instance lives as long as the
         * process and an unbounded cache on a long-running service is a leak with a
         * politer name.
         */
        const val DEFAULT_MAX_ENTRIES: Int = 60
    }
}
