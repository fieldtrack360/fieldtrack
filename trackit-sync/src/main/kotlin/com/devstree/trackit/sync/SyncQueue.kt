package com.devstree.trackit.sync

import com.devstree.trackit.domain.repository.PendingUploadStore
import com.devstree.trackit.geo.model.MovementStatus
import com.devstree.trackit.geo.model.TrackPoint
import com.devstree.trackit.geo.port.Clock
import com.devstree.trackit.geo.port.TrackLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Drains the upload queue.
 *
 * Store-then-sync, never sync-then-store: a point is durable in Room before anything is
 * attempted, so a failed upload costs nothing and a dead network costs nothing. Rows are
 * marked synced **only** on a confirmed success — the default state is "still queued",
 * which is the safe direction.
 *
 * A parked user uploads nothing by design, because the filter stores nothing. **Absence
 * of uploads is not evidence of a dead tracker** — that is what the raw-fix watchdog
 * clock is for (EC-70).
 */
public class SyncQueue internal constructor(
    private val store: PendingUploadStore,
    private val clock: Clock,
    private val logger: TrackLogger,
) {

    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    public sealed interface Result {
        public data class Uploaded(val count: Int) : Result
        public data object Empty : Result
        public data class Retry(val reason: String) : Result

        /** Terminal — the caller must tear the session down, not retry (spec §3.3). */
        public data object AuthExpired : Result
    }

    public suspend fun drain(config: SyncConfig, transport: SyncTransport): Result {
        // One drain at a time. A scheduled retry and a manual syncNow() colliding would
        // upload the same rows twice; the server would dedupe on uuid, but the second
        // request is pure waste.
        if (!mutex.tryLock()) return Result.Retry("already draining")
        try {
            var uploaded = 0
            // Bounded so one call cannot hold the lock through an enormous backlog.
            repeat(MAX_BATCHES_PER_DRAIN) {
                val batch = store.pending(config.batchSize)
                if (batch.isEmpty()) {
                    return if (uploaded > 0) Result.Uploaded(uploaded) else Result.Empty
                }

                val response = transport.upload(
                    SyncRequest(
                        url = config.url,
                        method = config.method,
                        headers = config.headers,
                        jsonBody = json.encodeToString(SyncPayload(batch.map(::toSyncPoint))),
                    ),
                )

                when (response) {
                    is SyncResponse.Success -> {
                        store.markSynced(batch.map { it.uuid }, clock.wallTimeMs())
                        uploaded += batch.size
                    }
                    SyncResponse.Unauthorized -> {
                        sdkLog { logger.w(TAG, "401 on upload; auth expired") }
                        return Result.AuthExpired
                    }
                    is SyncResponse.Failure -> {
                        // Rows stay queued deliberately. Retried by SyncWorker's backoff.
                        sdkLog { logger.w(TAG, "Upload failed (${response.code}): ${response.message}") }
                        return Result.Retry(response.message)
                    }
                }
            }
            return Result.Uploaded(uploaded)
        } finally {
            mutex.unlock()
        }
    }

    public suspend fun pendingCount(): Int = store.pendingCount()

    /**
     * Called after a 401. The queued rows belong to a session that can no longer be
     * uploaded, and carrying them forward would leak one user's positions into the next
     * login (spec §3.3).
     */
    public suspend fun clearOnAuthExpiry() {
        store.clearQueue()
    }

    private fun toSyncPoint(point: TrackPoint) = SyncPoint(
        uuid = point.uuid,
        time = point.timeMs,
        local_date = point.localDate,
        latitude = point.latitude,
        longitude = point.longitude,
        accuracy = point.accuracy,
        movementSpeed = point.speedMps,
        provider = point.provider,
        hasSpeed = point.hasSpeed,
        hasBearing = point.hasBearing,
        time_zone = point.timezone,
        // "<locationType>@<movementStatus>" — the server stores this verbatim and the
        // plotting side parses it back (spec §9).
        activity_status = "${point.provider}@${point.movementStatus.wireName()}",
        detected_activity_type = point.detectedActivity?.name,
        detected_activity_start_time = point.activityStartTimeMs,
        battery_percentage = point.batteryPct?.toString(),
        is_mock = point.isMock,
    )

    private fun MovementStatus.wireName() = name.lowercase()

    private companion object {
        const val TAG = "SyncQueue"
        const val MAX_BATCHES_PER_DRAIN = 20
    }
}
