package com.devstree.traker

import android.content.Context
import com.devstree.traker.di.TrakerGraph
import com.devstree.traker.domain.repository.PendingUploadStore
import com.devstree.traker.domain.repository.SyncTrigger
import com.devstree.traker.geo.port.Clock
import com.devstree.traker.geo.port.TrackLogger

/**
 * The wiring seam Traker's own optional artifacts build against.
 *
 * `fieldtrack-sync` needs the same clock, the same logger and the same upload queue the
 * capture side is using — not a second set. Under Hilt that was a `@EntryPoint`
 * interface; with the graph wired by hand it is this, and the reason it exists is
 * identical: `TrakerGraph` is `internal` to `fieldtrack-core`, so a sibling artifact in a
 * different Gradle module cannot see it.
 *
 * **Not a host-facing API.** Nothing here is needed to use the SDK — a host wants
 * [Traker.getInstance] and nothing else on this page. It is `public` only because
 * Kotlin has no "visible to my other modules" visibility, and every member is a type the
 * public surface already exposes, so it widens nothing that was not already reachable.
 */
public class TrakerArtifacts private constructor(private val graph: TrakerGraph) {

    public val trackIt: Traker get() = graph.trackIt

    public val clock: Clock get() = graph.clock

    public val logger: TrackLogger get() = graph.logger

    /** The one door `fieldtrack-sync` uploads through. */
    public val pendingUploads: PendingUploadStore get() = graph.pendingUploads

    /**
     * The door in the other direction.
     *
     * Core knows when a point was stored and when a queue has gone stale; it has no idea
     * what uploading one would mean. `fieldtrack-sync` registers a [SyncTrigger] here when
     * `SyncConfig.autoSync` is on and clears it when the configuration goes away, which is
     * what finally makes that flag mean something (GAPS.md G-4).
     *
     * Null clears it. Registering twice replaces — there is one uploader per process, and
     * a second registrant would otherwise double every request.
     */
    public fun registerSyncTrigger(trigger: SyncTrigger?) {
        graph.syncScheduler.register(trigger)
    }

    /**
     * [TrakerConfig.baseUrl] as `ready()` resolved it, or `null`.
     *
     * Core stores it and never reads it — this is the one door it leaves for the module that
     * does. Returns `null` before `ready()` has run, which is why `fieldtrack-sync` treats it as
     * a fallback and reports a missing endpoint rather than waiting for one.
     */
    public val baseUrl: String? get() = graph.configStore.cached?.baseUrl

    public companion object {
        /** Same process-wide graph [Traker.getInstance] returns from. */
        @JvmStatic
        public fun of(context: Context): TrakerArtifacts =
            TrakerArtifacts(TrakerGraph.get(context))
    }
}
