package com.devstree.traker.domain.repository

import com.devstree.traker.geo.model.TrackPoint

/**
 * The seam `trackit-sync` uploads through.
 *
 * It exists so that core can stay genuinely network-free while still letting an optional
 * artifact drain the queue. Entities and DAOs are `internal` on purpose — this is the
 * only public door, and it deals in domain types.
 *
 * The same pattern as `RoadSnapProvider`: core declares a narrow interface, an optional
 * module implements or consumes it, and a host that wants neither pays for neither.
 */
public interface PendingUploadStore {

    /** Oldest-first, so a queue that has been offline a while drains in order. */
    public suspend fun pending(limit: Int): List<TrackPoint>

    /** Number of rows still waiting. Cheap enough to poll for a UI badge. */
    public suspend fun pendingCount(): Int

    /**
     * Marks rows uploaded. Keyed on `uuid` rather than row id, because the uuid is the
     * stable identity that survives a re-insert (EC-82).
     */
    public suspend fun markSynced(uuids: List<String>, syncedAtMs: Long)

    /**
     * Drops every queued row. Called on a 401 teardown, where the queue is no longer
     * uploadable and holding it would leak the previous user's positions into the next
     * session (spec §3.3).
     */
    public suspend fun clearQueue()
}
