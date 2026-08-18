package com.devstree.traker.geo.port

import com.devstree.traker.geo.model.FilterState
import com.devstree.traker.geo.model.FixDecision
import com.devstree.traker.geo.model.GeoPoint
import com.devstree.traker.geo.model.TrackPoint

/**
 * What the engine needs from a platform, and nothing more.
 *
 * `fieldtrack-core` supplies Room- and Android-backed implementations; the fixture
 * harness supplies in-memory ones. That symmetry is why the entire pipeline is
 * testable on the JVM with no emulator (PLAN.md §6, tier T1).
 */
public interface PointStore {
    public suspend fun lastPoint(sessionId: String): TrackPoint?
    public suspend fun insert(point: TrackPoint): Long
    public suspend fun loadFilterState(): FilterState?
    public suspend fun saveFilterState(state: FilterState)
    public suspend fun recordDecision(decision: FixDecision)
}

/**
 * Two clocks, never conflated.
 *
 * [wallTimeMs] can jump backwards when the user or NTP changes the device time;
 * [elapsedRealtimeNanos] cannot, but resets on reboot. The pipeline uses only the
 * monotonic one (SOURCE-AUDIT A1).
 */
public interface Clock {
    public fun wallTimeMs(): Long
    public fun elapsedRealtimeNanos(): Long
}

public interface TrackLogger {
    public fun d(tag: String, message: String)
    public fun w(tag: String, message: String)

    public object NoOp : TrackLogger {
        override fun d(tag: String, message: String): Unit = Unit
        override fun w(tag: String, message: String): Unit = Unit
    }
}

/**
 * Optional road-snapping. Deliberately an interface in `fieldtrack-geo` with its only
 * real implementation in an optional artifact, so core carries no HTTP client, no
 * vendor lock and no API key (PLAN.md §5).
 *
 * Implementations must degrade rather than fail: returning an empty list makes
 * `TrackBuilder` fall back to the raw path and emit a `snap_unavailable` warning
 * rather than losing the track (EC-100).
 */
public interface RoadSnapProvider {
    public suspend fun snap(path: List<GeoPoint>): List<GeoPoint>

    /**
     * The same request, plus what the engine knows about each fix
     * (SMOOTH-NAV-PLAN Phase 5).
     *
     * A matcher's job is choosing between candidate roads, and the two facts that
     * decide it are how far a fix could plausibly have travelled since the last one and
     * how much the fix is worth trusting. A bare coordinate list carries neither, so a
     * matcher fed one has to assume every point is equally good and equally spaced.
     *
     * Defaulted to [snap] rather than added to it, so every provider written against
     * the original contract keeps compiling and keeps working — the extra facts are an
     * opportunity, never a requirement (EC-100).
     */
    public suspend fun snap(request: SnapRequest): List<GeoPoint> = snap(request.path)

    public object Disabled : RoadSnapProvider {
        override suspend fun snap(path: List<GeoPoint>): List<GeoPoint> = emptyList()
    }
}

/**
 * @property timeMs wall-clock fix time. Wall clock rather than the monotonic clock the
 *   pipeline uses, because it is leaving the device: a matcher needs an absolute
 *   instant, and the interval between two of them is what it actually consumes.
 * @property accuracyM the fix's reported accuracy, metres — how wide a net the matcher
 *   should cast around it.
 */
public data class SnapFix(
    val point: GeoPoint,
    val timeMs: Long = 0,
    val accuracyM: Float = 0f,
)

public data class SnapRequest(val fixes: List<SnapFix>) {
    public val path: List<GeoPoint> get() = fixes.map { it.point }

    /** True when every fix carries a usable, strictly increasing instant. */
    public val hasTimestamps: Boolean
        get() = fixes.all { it.timeMs > 0 } && fixes.zipWithNext().all { (a, b) -> b.timeMs > a.timeMs }
}
