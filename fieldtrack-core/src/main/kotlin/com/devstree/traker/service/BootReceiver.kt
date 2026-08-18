package com.devstree.traker.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.devstree.traker.di.TrakerGraph
import com.devstree.traker.domain.repository.ConfigRepository
import com.devstree.traker.domain.repository.SessionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Resumes an open session after a reboot or an app update.
 *
 * `MY_PACKAGE_REPLACED` matters as much as `BOOT_COMPLETED`: an update tears down the
 * service and clears alarms just as a reboot does, and handling only the latter leaves
 * tracking silently dead after every Play Store update (EC-65, EC-67).
 *
 * This cannot save a force-stopped app — nothing can, by policy. That case surfaces as
 * `SessionInterrupted` on next launch instead (EC-66).
 */
public class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        // Looked up after the action check, deliberately: this receiver is woken for
        // every boot of every install, and building the graph — which opens the database
        // on first touch — before knowing the broadcast is ours would put disk I/O on the
        // main thread of a boot storm.
        val graph = TrakerGraph.get(context)
        val sessions: SessionRepository = graph.sessions
        val config: ConfigRepository = graph.config

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val serviceConfig = config.load()?.service ?: return@launch
                if (!serviceConfig.startOnBoot) return@launch
                if (sessions.current() == null) return@launch
                TrackingService.start(context, serviceConfig)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
