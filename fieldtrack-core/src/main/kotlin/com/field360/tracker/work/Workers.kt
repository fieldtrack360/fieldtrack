package com.field360.tracker.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.field360.tracker.TrackerConfig
import com.field360.tracker.di.TrackerGraph
import com.field360.tracker.service.TrackingService
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Dependency access for workers.
 *
 * Workers are constructed by WorkManager, so there is no constructor to wire — the graph
 * is looked up from the application context instead. The property that matters is that
 * **the host does nothing**: WorkManager's default initialisation is enough, with no
 * `Configuration.Provider` and no custom `WorkerFactory` to install. That was the
 * argument for `EntryPointAccessors` over `@HiltWorker` when this used Hilt, and it is
 * the same argument now that there is no Hilt at all.
 */
private fun Context.trackItGraph(): TrackerGraph = TrackerGraph.get(applicationContext)

/**
 * The 15-minute safety net.
 *
 * Captures a fix even when the continuous stream has died — an OEM kill, a refused FGS
 * promotion, a provider that stopped delivering. It feeds the **shared** ingestor
 * rather than deriving its own `past`, which is the whole point: the reference had the
 * worker and the service judging fixes against different anchors while mutating one
 * static filter (SOURCE-AUDIT A3).
 *
 * Best-effort by nature — in the `RESTRICTED` app standby bucket it may not run at all
 * (EC-22).
 */
internal class BackstopWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = applicationContext.trackItGraph()
        val session = deps.sessions.current() ?: return Result.success()
        val config = deps.config.load() ?: TrackerConfig()

        // Runs whether or not a fix arrives. This is the only supervision that survives a
        // dead service, so it is the one thing that can notice a backlog left behind by a
        // drain that failed while the process was gone (spec §12.2 check 3).
        deps.syncScheduler.onSupervisionTick()

        // Through OneShotProvider, not the raw source: it carries the timeout, the
        // retry cap and the mutex that stops a coincident activity-transition capture
        // from firing a second concurrent request (EC-17, EC-20).
        val fix = deps.oneShotProvider.capture(config)
            ?: return Result.retry() // Linear backoff; a fix may just not be available yet.

        return Result.success()
    }

    companion object {
        const val NAME = "fieldtrack-backstop"

        fun enqueue(context: Context, intervalMinutes: Int) {
            val request = PeriodicWorkRequestBuilder<BackstopWorker>(
                intervalMinutes.toLong(),
                TimeUnit.MINUTES,
            )
                .setBackoffCriteria(BackoffPolicy.LINEAR, MIN_BACKOFF_SECONDS, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                // KEEP: re-enqueuing on every start would reset the 15-minute clock and
                // the backstop would never actually fire on a frequently-restarted app.
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        private const val MIN_BACKOFF_SECONDS = 30L
    }
}

/**
 * Re-promotes the foreground service after it was killed or refused.
 *
 * Expedited, because the window in which the app is eligible to start an FGS may be
 * short (EC-62, EC-69).
 */
internal class RestoreWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = applicationContext.trackItGraph()
        deps.sessions.current() ?: return Result.success()
        val config = deps.config.load() ?: TrackerConfig()
        TrackingService.start(applicationContext, config.service)
        return Result.success()
    }

    companion object {
        const val NAME = "fieldtrack-restore"

        fun enqueueExpedited(context: Context) {
            val request = OneTimeWorkRequestBuilder<RestoreWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(Constraints.NONE)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/**
 * TTL enforcement for points and the decision log.
 *
 * Never touches rows belonging to an **open** session — pruning mid-session would put a
 * hole in the track being recorded (EC-81). The decision log is capped by both age and
 * row count (EC-87).
 */
internal class PruneWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = applicationContext.trackItGraph()
        val config = deps.config.load() ?: TrackerConfig()
        val now = deps.clock.wallTimeMs()

        val persistence = config.persistence
        if (persistence.maxDaysToPersist > 0) {
            deps.trackPoints.prune(now - persistence.maxDaysToPersist * MILLIS_PER_DAY)
        }
        if (persistence.persistDecisions) {
            deps.decisions.prune(
                cutoffMs = now - persistence.decisionRetentionDays * MILLIS_PER_DAY,
                maxRows = persistence.decisionMaxRows,
            )
        }
        return Result.success()
    }

    companion object {
        const val NAME = "fieldtrack-prune"
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<PruneWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder().setRequiresBatteryNotLow(true).build(),
                )
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
