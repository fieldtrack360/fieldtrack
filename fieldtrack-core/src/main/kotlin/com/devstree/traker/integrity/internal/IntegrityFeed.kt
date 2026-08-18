package com.devstree.traker.integrity.internal

import com.devstree.traker.geo.model.TrackFix
import com.devstree.traker.geo.port.Clock

/**
 * The two integrity facts only the capture path can see.
 *
 * A probe can ask the platform whether developer options are on. Neither it nor any
 * settings flag can tell you that *this session* received a mock fix, or that the system
 * clock disagrees with the satellites — those are properties of fixes, and fixes arrive
 * on the ingest path. [FixIngestor][com.devstree.traker.capture.FixIngestor] calls
 * [onFix] for every raw fix; the probes read what accumulates here.
 *
 * Written from the single ingest consumer, read from the health loop and from `ready()`,
 * hence `@Volatile` rather than a lock: two longs and a boolean, each independently
 * meaningful, none of them read as a group that has to be consistent.
 */
internal class IntegrityFeed(private val clock: Clock) {

    // Ages are kept on the monotonic clock, never the wall clock: the whole point of this
    // class is that the wall clock may be under the attacker's control, and "was this
    // evidence recent?" must not be answerable by moving the device time.
    // Nullable rather than a `0` sentinel: `elapsedRealtimeNanos()` legitimately reads 0 at
    // the very start of a boot, and "seen at time zero" must not decode as "never seen".
    @Volatile
    private var mockFixSeenAtNanos: Long? = null

    @Volatile
    private var lastSkewMs: Long? = null

    @Volatile
    private var skewObservedAtNanos: Long = 0L

    /**
     * Records what this fix says about the device.
     *
     * GNSS UTC is only trusted from a genuine satellite fix: a fused or network fix carries
     * a `time` the platform derived from the very clock we are trying to check, and a mock
     * fix carries whatever the mock app wrote. Both would make the comparison circular.
     */
    fun onFix(fix: TrackFix) {
        if (fix.isMock) {
            mockFixSeenAtNanos = clock.elapsedRealtimeNanos()
            return
        }

        if (!fix.provider.equals(GPS_PROVIDER, ignoreCase = true)) return

        // fix.timeMs is GNSS UTC; wallTimeMs() is what the device believes. Their gap is
        // measured at the moment of the comparison, so a fix that sat in a queue does not
        // read as skew — receivedAtElapsedNanos would be the correction, and the queue in
        // question is bounded at a few hundred milliseconds.
        lastSkewMs = fix.timeMs - clock.wallTimeMs()
        skewObservedAtNanos = clock.elapsedRealtimeNanos()
    }

    /** Cleared at session start, so "a mock fix arrived" is a fact about the current session. */
    fun reset() {
        mockFixSeenAtNanos = null
        lastSkewMs = null
        skewObservedAtNanos = 0
    }

    /** `true` when a mock fix has been seen recently enough to still be evidence. */
    fun sawMockFix(withinMs: Long = MOCK_MEMORY_MS): Boolean {
        val at = mockFixSeenAtNanos ?: return false
        return (clock.elapsedRealtimeNanos() - at) / NANOS_PER_MS <= withinMs
    }

    /**
     * Signed `GNSS UTC − system clock`, or `null` when no satellite fix has been seen yet
     * or the last one is too old to still describe the current clock.
     */
    fun clockSkewMs(withinMs: Long = SKEW_MEMORY_MS): Long? {
        val skew = lastSkewMs ?: return null
        if ((clock.elapsedRealtimeNanos() - skewObservedAtNanos) / NANOS_PER_MS > withinMs) return null
        return skew
    }

    private companion object {
        const val GPS_PROVIDER = "gps"
        const val NANOS_PER_MS = 1_000_000L

        /** A mock fix an hour ago is still the most important thing about this session. */
        const val MOCK_MEMORY_MS = 60 * 60_000L

        /** A skew reading older than this may describe a clock the user has since fixed. */
        const val SKEW_MEMORY_MS = 30 * 60_000L
    }
}
