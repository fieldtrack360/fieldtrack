package com.devstree.trackit

import android.content.Context
import com.devstree.trackit.di.TrackItGraph
import com.devstree.trackit.domain.repository.PendingUploadStore
import com.devstree.trackit.geo.port.Clock
import com.devstree.trackit.geo.port.TrackLogger

/**
 * The wiring seam TrackIt's own optional artifacts build against.
 *
 * `trackit-sync` needs the same clock, the same logger and the same upload queue the
 * capture side is using — not a second set. Under Hilt that was a `@EntryPoint`
 * interface; with the graph wired by hand it is this, and the reason it exists is
 * identical: `TrackItGraph` is `internal` to `trackit-core`, so a sibling artifact in a
 * different Gradle module cannot see it.
 *
 * **Not a host-facing API.** Nothing here is needed to use the SDK — a host wants
 * [TrackIt.getInstance] and nothing else on this page. It is `public` only because
 * Kotlin has no "visible to my other modules" visibility, and every member is a type the
 * public surface already exposes, so it widens nothing that was not already reachable.
 */
public class TrackItArtifacts private constructor(private val graph: TrackItGraph) {

    public val trackIt: TrackIt get() = graph.trackIt

    public val clock: Clock get() = graph.clock

    public val logger: TrackLogger get() = graph.logger

    /** The one door `trackit-sync` uploads through. */
    public val pendingUploads: PendingUploadStore get() = graph.pendingUploads

    public companion object {
        /** Same process-wide graph [TrackIt.getInstance] returns from. */
        @JvmStatic
        public fun of(context: Context): TrackItArtifacts =
            TrackItArtifacts(TrackItGraph.get(context))
    }
}
