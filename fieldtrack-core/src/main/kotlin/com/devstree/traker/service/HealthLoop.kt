package com.devstree.traker.service

import android.content.Context
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.devstree.traker.TrakerConfig
import com.devstree.traker.sdkLog
import com.devstree.traker.domain.model.TrakerEvent
import com.devstree.traker.domain.repository.SessionRepository
import com.devstree.traker.geo.port.Clock
import com.devstree.traker.geo.model.MotionState
import com.devstree.traker.geo.port.TrackLogger
import com.devstree.traker.motion.MotionController
import com.devstree.traker.permission.ProviderStateMonitor
import com.devstree.traker.work.BackstopWorker
import com.devstree.traker.work.RestoreWorker
import com.devstree.traker.work.SyncScheduler
import com.devstree.traker.work.Watchdog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.time.Duration.Companion.milliseconds

/**
 * The in-service supervision loop, every two minutes.
 *
 * Its job is to notice the failures nothing else reports: a periodic worker stuck in
 * `FAILED`/`CANCELLED`, a session that ended while the service kept running, a tracker
 * that has gone quiet. The reference runs the same loop at the same cadence
 * (`AttendanceLoggerService.kt:442-451`, `:1041`).
 */
internal class
HealthLoop(
    private val context: Context,
    private val sessions: SessionRepository,
    private val clock: Clock,
    private val events: MutableSharedFlow<TrakerEvent>,
    private val watchdog: Watchdog,
    private val motionController: MotionController,
    private val providerState: ProviderStateMonitor,
    private val syncScheduler: SyncScheduler,
    private val logger: TrackLogger,
) {

    private var job: Job? = null

    fun start(scope: CoroutineScope, config: TrakerConfig, onSessionClosed: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                delay(config.service.healthLoopMs.milliseconds)
                withContext(NonCancellable) { runCheck(config, onSessionClosed) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun runCheck(config: TrakerConfig, onSessionClosed: () -> Unit) {
        val session = sessions.current()
        if (session == null) {
            // No session but the service is alive — stop rather than burn battery.
            sdkLog { logger.d(TAG, "No open session; stopping service") }
            onSessionClosed()
            return
        }

        ensureBackstopAlive(config)

        val action = watchdog.tick(
            config = config.service,
            serviceRunning = TrackingService.running,
            hasOpenSession = true,
            // A moving user is judged on the tighter threshold; a parked one is expected
            // to be quiet, so the two cases cannot share a limit (EC-70).
            moving = motionController.motionState == MotionState.MOVING,
            powerSave = providerState.state.value.powerSaveMode,
        )
        if (action == Watchdog.Action.RestoreService) {
            RestoreWorker.enqueueExpedited(context)
        }

        // Spec §3.4 step 3 and §12.2 check 3: rows queued, or the last sync gone stale,
        // means run the queue. A no-op unless the host configured sync with autoSync on.
        syncScheduler.onSupervisionTick()

        // Health is judged first; the heartbeat then records that the loop is alive.
        events.tryEmit(TrakerEvent.Heartbeat(clock.wallTimeMs()))
    }

    /**
     * WorkManager periodic work can land in `FAILED` or `CANCELLED` and then simply
     * never run again — silent, and the backstop is precisely the thing you rely on
     * when the stream has already failed. Re-enqueue it (EC-71).
     */
    private suspend fun ensureBackstopAlive(config: TrakerConfig) {
        // Flow variant so no ListenableFuture/Guava bridge is pulled into the AAR.
        val infos = runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(BackstopWorker.NAME)
                .first()
        }.getOrNull() ?: return

        val healthy = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        if (!healthy) {
            sdkLog { logger.w(TAG, "Backstop work not alive (${infos.map { it.state }}); re-enqueuing") }
            BackstopWorker.enqueue(context, config.service.backstopIntervalMin)
        }
    }

    private companion object {
        const val TAG = "HealthLoop"
    }
}
