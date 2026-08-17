package com.devstree.trackit.sync

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.devstree.trackit.TrackIt
import com.devstree.trackit.TrackItArtifacts
import com.devstree.trackit.domain.repository.SyncTrigger
import com.devstree.trackit.geo.port.TrackLogger
import com.devstree.trackit.sync.internal.NoOpTransport
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * @property autoSync upload as points arrive. With it off, the host calls [TrackItSync.syncNow].
 * @property batchSize rows per request. Larger means fewer requests but a bigger retry
 *   unit — a failure re-sends the whole batch.
 * @property gzipRequestBody compress the JSON body. Off by default and deliberately so:
 *   there is no negotiation for request-body encoding — a client sending
 *   `Content-Encoding: gzip` is asserting it, and a server that does not expect it answers
 *   400 or stores the compressed bytes as the payload. Turning this on by default would
 *   break working integrations on an upgrade, with a failure that reads as a server bug.
 * @property allowCleartext permit an `http://` URL. For a local development server only —
 *   see [validate].
 * @property timeouts applied by the built-in transport. Ignored by a custom [SyncTransport],
 *   which owns the client that would honour them.
 */
public data class SyncConfig(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val autoSync: Boolean = true,
    val batchSize: Int = 100,
    val requiresUnmeteredNetwork: Boolean = false,
    val gzipRequestBody: Boolean = false,
    val allowCleartext: Boolean = false,
    val timeouts: SyncTimeouts = SyncTimeouts(),
) {

    /**
     * Everything wrong with this config, or an empty list.
     *
     * Mirrors `TrackItConfig.validate()`: [TrackItSync.configure] runs this and throws, and
     * a host assembling a config from untrusted input can read it first instead.
     *
     * The scheme check is the one that earns its place. Android blocks cleartext by default
     * from API 28, so an `http://` URL is accepted here, uploaded to, and fails at runtime
     * as a generic network error — retried forever, on battery, with nothing in the logs
     * naming the real cause. Loopback is exempt because the platform's own default network
     * security config exempts it, so a local dev server needs no flag at all.
     */
    public fun validate(): List<String> = buildList {
        if (url.isBlank()) add("url must not be blank")

        val uri = runCatching { URI(url) }.getOrNull()
        val scheme = uri?.scheme?.lowercase()
        when {
            uri == null || scheme == null -> add("url is not a valid absolute URL: $url")
            scheme == "https" -> Unit
            scheme == "http" && (allowCleartext || uri.isLoopback()) -> Unit
            scheme == "http" -> add(
                "url must be https://. Cleartext is blocked at runtime by Android's default " +
                    "network security policy, so an http:// endpoint fails as an ordinary " +
                    "network error and retries forever. Set allowCleartext = true if this is " +
                    "deliberate; loopback addresses are already exempt.",
            )
            else -> add("url scheme must be https (or http for a local server), not $scheme")
        }

        if (method.isBlank()) add("method must not be blank")
        if (batchSize !in 1..MAX_BATCH_SIZE) add("batchSize must be in 1..$MAX_BATCH_SIZE")
        if (timeouts.connectMs <= 0) add("timeouts.connectMs must be > 0")
        if (timeouts.readMs <= 0) add("timeouts.readMs must be > 0")
        if (timeouts.writeMs <= 0) add("timeouts.writeMs must be > 0")
    }

    private fun URI.isLoopback(): Boolean = host in LOOPBACK_HOSTS

    private companion object {
        const val MAX_BATCH_SIZE = 1_000

        /** `10.0.2.2` is the emulator's route to the developer's own machine. */
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1", "[::1]", "10.0.2.2")
    }
}

/**
 * The optional upload half.
 *
 * `trackit-core` never opens a socket; this artifact does, and a host that does not
 * depend on it gets an offline-first SDK with no network code linked at all
 * (PLAN.md §0).
 */
public class TrackItSync internal constructor(
    private val context: Context,
    private val queue: SyncQueue,
    private val trackIt: TrackIt,
    private val logger: TrackLogger,
    private val artifacts: TrackItArtifacts,
    private val eventSink: MutableSharedFlow<SyncEvent>,
) {

    @Volatile
    private var config: SyncConfig? = null

    @Volatile
    private var transport: SyncTransport? = null

    /**
     * Whether the server has told us to stop, and why. Set by a 403, cleared by the next
     * [configure] — re-configuring with a working credential is the documented recovery.
     */
    @Volatile
    private var haltedReason: String? = null

    /**
     * What the server said, one event per exchange — including the exchanges the host did
     * not ask for. [syncNow] already returns the outcome of a drain a host requested;
     * [requestSync] hands the work to WorkManager, which may run it minutes later in a
     * process nobody is watching.
     */
    public val events: SharedFlow<SyncEvent> = eventSink.asSharedFlow()

    /**
     * Where uploads are going, or `null` if [configure] has not been called — or if a 401
     * has since torn the configuration down.
     *
     * The headers are deliberately **not** exposed: they carry the host's credential, and a
     * property that hands a bearer token back is a property that ends up in a log.
     */
    public val endpoint: String? get() = config?.url

    /**
     * Derived from [endpoint] rather than tracked separately, so the two cannot disagree.
     *
     * Do not cache it. A 401 clears the configuration with no host involvement, so a
     * remembered value goes stale at exactly the moment it matters.
     */
    public val isConfigured: Boolean get() = endpoint != null

    /**
     * @param transport omit to use the OkHttp default. Supply your own to reuse an
     *   existing authenticated client — then OkHttp is never linked.
     * @throws IllegalArgumentException if [config] does not pass [SyncConfig.validate].
     *   The same deliberate exception to the SDK's no-throw contract that
     *   `TrackItConfig.Builder.build()` makes, for the same reason: this runs on the host's
     *   own thread while it assembles a value, which is where fail-fast belongs. The
     *   alternative — accepting it and failing at upload time — is the silent failure this
     *   validation exists to end.
     */
    public fun configure(config: SyncConfig, transport: SyncTransport? = null) {
        val errors = config.validate()
        require(errors.isEmpty()) { "Invalid SyncConfig: ${errors.joinToString("; ")}" }

        this.config = config
        this.transport = transport ?: defaultTransport()
        this.haltedReason = null

        // What makes autoSync mean anything. Core drives the trigger — on an accepted
        // point, and from its supervision loops when rows are queued or the last upload
        // has gone stale — because those are the moments it can see and this module
        // cannot. With autoSync off nothing is registered and the host owns the schedule.
        artifacts.registerSyncTrigger(if (config.autoSync) SyncTrigger(::requestSync) else null)
    }

    public suspend fun pendingCount(): Int = queue.pendingCount()

    /**
     * Enqueues a network-constrained one-shot; safe to call often.
     *
     * A no-op once a 403 has halted uploads — that loop is the battery burn the halt exists
     * to stop, and re-enqueueing work that will be rejected again is how it would continue.
     */
    public fun requestSync() {
        val activeConfig = config ?: return
        if (haltedReason != null) return
        SyncWorker.enqueue(context, activeConfig.requiresUnmeteredNetwork)
    }

    /**
     * Drains inline. Returns what happened so a host can surface it.
     *
     * Runs in the caller's scope, so an upload started from a `viewModelScope` is cancelled
     * with it. Prefer [requestSync] for anything not user-initiated.
     */
    public suspend fun syncNow(): SyncQueue.Result {
        val activeConfig = config ?: return SyncQueue.Result.Retry("sync not configured")
        val activeTransport = transport ?: return SyncQueue.Result.Retry("no transport")
        // Answer from memory rather than spending a request to be told the same thing.
        if (haltedReason != null) return SyncQueue.Result.Forbidden

        val result = queue.drain(activeConfig, activeTransport)
        when (result) {
            SyncQueue.Result.AuthExpired -> tearDown()
            SyncQueue.Result.Forbidden -> halt()
            else -> Unit
        }
        return result
    }

    /**
     * 403 handling: stop uploading, keep everything.
     *
     * Deliberately not [tearDown]. A 401 means the credential this data was recorded under
     * is gone and the next login may be a different user, which is what justifies clearing
     * the queue. A 403 means *this* credential may not write *this* resource — a scope, a
     * rotated key, a server-side permission bug — and it is the same user's data either
     * way. Destroying it to fix a permissions mistake is the more expensive of the two
     * errors, so tracking continues, the rows stay queued, and the retry loop stops.
     */
    private fun halt() {
        haltedReason = "403 — credential rejected by the server"
        sdkLog { logger.w(TAG, "Uploads halted after a 403; rows kept. Re-configure to resume") }
        config = null
        transport = null
        // Core must stop nudging too, or every accepted point re-enters a loop that has
        // already been told to stop.
        artifacts.registerSyncTrigger(null)
    }

    /**
     * Re-enqueues the drain at the server's own schedule.
     *
     * `REPLACE`, unlike the ordinary [requestSync] path, and the difference is the point:
     * `KEEP` exists there so a burst of accepted points cannot reset the backoff clock and
     * hammer a struggling server. This path is reachable only from a `Retry-After` the
     * server itself sent, so there is no burst to defend against — and `KEEP` here would
     * silently discard the instruction in favour of our 30 s default.
     */
    internal fun rescheduleAfter(delayMs: Long) {
        val unmetered = config?.requiresUnmeteredNetwork == true
        SyncWorker.enqueueAfter(context, unmetered, delayMs)
    }

    /**
     * 401 handling: stop tracking, clear the queue, forget the config.
     *
     * Deliberately drastic. A 401 means the credentials this session was recorded under
     * are gone, so continuing to capture would pile up rows that can never be uploaded,
     * and keeping the queue would leak the previous user's positions into the next login
     * (spec §3.3).
     */
    private suspend fun tearDown() {
        sdkLog { logger.w(TAG, "Auth expired — stopping tracking and clearing the upload queue") }
        runCatching { trackIt.stop() }
        queue.clearOnAuthExpiry()
        config = null
        transport = null
        artifacts.registerSyncTrigger(null)
    }

    private fun defaultTransport(): SyncTransport = runCatching { OkHttpSyncTransport() }
        .getOrElse {
            // compileOnly — absent unless the host added OkHttp or supplied a transport.
            sdkLog { logger.w(TAG, "OkHttp not on the classpath; supply your own SyncTransport") }
            NoOpTransport
        }

    public companion object {
        private const val TAG = "TrackItSync"

        @SuppressLint("StaticFieldLeak") // getInstance() stores only applicationContext.
        @Volatile
        private var instance: TrackItSync? = null

        /**
         * The upload half, for this process.
         *
         * Idempotent and thread-safe, and paired with [TrackIt.getInstance] by
         * construction: both hang off the same core graph, so the queue drains the same
         * database the ingestor writes.
         *
         * [configure] still has to be called before anything uploads — this hands back
         * the object, not a configured transport.
         */
        @JvmStatic
        public fun getInstance(context: Context): TrackItSync {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(app: Context): TrackItSync {
            val access = TrackItArtifacts.of(app)
            // replay = 1 so a host opening an upload screen after a background drain sees
            // what happened rather than a blank panel — these events are a handful per
            // drain, minutes apart, so retaining one costs nothing. DROP_OLDEST with spare
            // capacity means tryEmit always succeeds and a slow collector can never stall
            // the drain that is emitting.
            val sink = MutableSharedFlow<SyncEvent>(
                replay = 1,
                extraBufferCapacity = EVENT_BUFFER,
                onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            return TrackItSync(
                context = app,
                queue = SyncQueue(access.pendingUploads, access.clock, access.logger) {
                    sink.tryEmit(it)
                },
                trackIt = access.trackIt,
                logger = access.logger,
                artifacts = access,
                eventSink = sink,
            )
        }

        private const val EVENT_BUFFER = 32
    }
}

/**
 * Retries the queue on a linear backoff, only when a network is actually available —
 * there is no point waking to fail.
 */
internal class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = TrackItSync.getInstance(applicationContext)

        return when (val result = sync.syncNow()) {
            is SyncQueue.Result.Uploaded, SyncQueue.Result.Empty -> Result.success()
            // Terminal: teardown already happened, so retrying would only re-fail.
            SyncQueue.Result.AuthExpired -> Result.failure()
            // Terminal without a teardown — the rows are still queued, but nothing will
            // accept them until the host re-configures.
            SyncQueue.Result.Forbidden -> Result.failure()
            is SyncQueue.Result.Retry -> reschedule(sync, result)
        }
    }

    /**
     * `Result.retry()` cannot carry a delay — WorkManager applies the *request's* backoff
     * policy, fixed when the request was built, and `setInitialDelay` only affects a newly
     * enqueued one. So a server that named a time is honoured by enqueueing afresh and
     * reporting this attempt as done; without a time, the existing linear backoff stands.
     */
    private fun reschedule(sync: TrackItSync, result: SyncQueue.Result.Retry): Result {
        val delayMs = result.retryAfterMs ?: return Result.retry()
        sync.rescheduleAfter(delayMs)
        return Result.success()
    }

    companion object {
        const val NAME = "trackit-sync"

        fun enqueue(context: Context, requiresUnmetered: Boolean) {
            WorkManager.getInstance(context)
                // KEEP, not REPLACE: a burst of accepted points must not reset the
                // backoff clock and hammer a server that is already struggling.
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, requestFor(requiresUnmetered))
        }

        /**
         * The `Retry-After` path, and the only caller of `REPLACE`.
         *
         * `KEEP` above defends against a burst of accepted points resetting the backoff.
         * There is no burst here — this runs once, from a drain the server itself
         * rate-limited — and `KEEP` would discard the server's schedule in favour of our
         * 30 s default, which is the whole gap being closed.
         */
        fun enqueueAfter(context: Context, requiresUnmetered: Boolean, delayMs: Long) {
            val request = requestFor(requiresUnmetered) {
                setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            }
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        private fun requestFor(
            requiresUnmetered: Boolean,
            extras: OneTimeWorkRequest.Builder.() -> Unit = {},
        ): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (requiresUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .build()

            return OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .apply(extras)
                .build()
        }

        private const val BACKOFF_SECONDS = 30L
    }
}
