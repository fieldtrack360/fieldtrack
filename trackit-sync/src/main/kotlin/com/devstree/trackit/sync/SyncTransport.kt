package com.devstree.trackit.sync

import kotlinx.serialization.Serializable

/**
 * How a batch actually reaches a server.
 *
 * Pluggable on purpose. Most apps already have an HTTP client configured with their own
 * auth interceptors, certificate pinning, retry policy and logging; forcing a second one
 * on them is exactly the version-conflict problem that keeps Retrofit and OkHttp out of
 * `trackit-core` (EKF-DESIGN-REVIEW §S5).
 *
 * The default [OkHttpSyncTransport] exists for convenience, and OkHttp is `compileOnly`
 * here — supply your own transport and you never pull it in.
 */
public interface SyncTransport {

    /**
     * Implementations must **not** throw: a network failure is an expected state, not an
     * exception, and the queue depends on being told which of the three it was.
     */
    public suspend fun upload(request: SyncRequest): SyncResponse
}

public data class SyncRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val jsonBody: String,
)

/**
 * @property Success the batch was accepted and may be marked synced.
 * @property Unauthorized a 401. Distinct from [Failure] because it is **terminal**:
 *   retrying cannot help, and the SDK responds by tearing the session down rather than
 *   looping (spec §3.3, §11.2).
 * @property Failure anything else — the rows stay queued and are retried with backoff.
 */
public sealed interface SyncResponse {
    public data class Success(val code: Int) : SyncResponse
    public data object Unauthorized : SyncResponse
    public data class Failure(val code: Int?, val message: String) : SyncResponse
}

/**
 * The upload payload.
 *
 * snake_case keys and epoch milliseconds, matching the reference server contract
 * (spec §11.2). Remap in your own [SyncTransport] if your backend differs — that is
 * cheaper than making this configurable.
 */
@Serializable
public data class SyncPayload(
    val location: List<SyncPoint>,
)

@Serializable
public data class SyncPoint(
    val uuid: String,
    val time: Long,
    val local_date: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val movementSpeed: Float,
    val provider: String,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
    val time_zone: String,
    val activity_status: String,
    val detected_activity_type: String? = null,
    val detected_activity_start_time: Long = 0,
    val battery_percentage: String? = null,
    val is_mock: Boolean = false,
)
