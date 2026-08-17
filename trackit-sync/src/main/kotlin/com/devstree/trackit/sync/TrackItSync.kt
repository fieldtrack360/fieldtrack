package com.devstree.trackit.sync

import android.annotation.SuppressLint
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.devstree.trackit.TrackIt
import com.devstree.trackit.TrackItArtifacts
import com.devstree.trackit.geo.port.TrackLogger
import com.devstree.trackit.sync.internal.NoOpTransport
import java.util.concurrent.TimeUnit

/**
 * @property autoSync upload as points arrive. With it off, the host calls [TrackItSync.syncNow].
 * @property batchSize rows per request. Larger means fewer requests but a bigger retry
 *   unit — a failure re-sends the whole batch.
 */
public data class SyncConfig(
    val url: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val autoSync: Boolean = true,
    val batchSize: Int = 100,
    val requiresUnmeteredNetwork: Boolean = false,
)

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
) {

    @Volatile
    private var config: SyncConfig? = null

    @Volatile
    private var transport: SyncTransport? = null

    /**
     * @param transport omit to use the OkHttp default. Supply your own to reuse an
     *   existing authenticated client — then OkHttp is never linked.
     */
    public fun configure(config: SyncConfig, transport: SyncTransport? = null) {
        this.config = config
        this.transport = transport ?: defaultTransport()
    }

    public suspend fun pendingCount(): Int = queue.pendingCount()

    /** Enqueues a network-constrained one-shot; safe to call often. */
    public fun requestSync() {
        if (config == null) return
        SyncWorker.enqueue(context, config?.requiresUnmeteredNetwork == true)
    }

    /** Drains inline. Returns what happened so a host can surface it. */
    public suspend fun syncNow(): SyncQueue.Result {
        val activeConfig = config ?: return SyncQueue.Result.Retry("sync not configured")
        val activeTransport = transport ?: return SyncQueue.Result.Retry("no transport")

        val result = queue.drain(activeConfig, activeTransport)
        if (result is SyncQueue.Result.AuthExpired) tearDown()
        return result
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
            return TrackItSync(
                context = app,
                queue = SyncQueue(access.pendingUploads, access.clock, access.logger),
                trackIt = access.trackIt,
                logger = access.logger,
            )
        }
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
            is SyncQueue.Result.Retry -> Result.retry()
        }
    }

    companion object {
        const val NAME = "trackit-sync"

        fun enqueue(context: Context, requiresUnmetered: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (requiresUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .build()

            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                // KEEP, not REPLACE: a burst of accepted points must not reset the
                // backoff clock and hammer a server that is already struggling.
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }

        private const val BACKOFF_SECONDS = 30L
    }
}
