package com.devstree.trackit.motion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.devstree.trackit.di.TrackItGraph
import com.devstree.trackit.domain.model.GeofenceTransition
import com.devstree.trackit.domain.model.TrackItEvent
import com.google.android.gms.location.Geofence
import com.devstree.trackit.service.CaptureBus
import com.google.android.gms.location.GeofencingEvent
import kotlinx.coroutines.flow.MutableSharedFlow

/** Fence exit means the user left the stop. Wake and capture. */
public class StationaryFenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return

        // Manifest-declared receiver: constructed by the system, so the graph is looked
        // up rather than injected. Lazy, so this builds nothing that is not already up.
        val graph = TrackItGraph.get(context)
        val events: MutableSharedFlow<TrackItEvent> = graph.events
        val motionController = graph.motionController

        if (event.hasError()) {
            events.tryEmit(TrackItEvent.Diagnostic("geofence_error: ${event.errorCode}"))
            return
        }

        val transition = when (event.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> GeofenceTransition.ENTER
            Geofence.GEOFENCE_TRANSITION_EXIT -> GeofenceTransition.EXIT
            else -> {
                events.tryEmit(
                    TrackItEvent.Diagnostic("stationary_fence_transition:${event.geofenceTransition}"),
                )
                return
            }
        }

        var stationaryFenceExited = false
        event.triggeringGeofences.orEmpty().forEach { triggered ->
            val registration = graph.stationaryFence.store.registration(triggered.requestId)
            if (registration == null) {
                events.tryEmit(TrackItEvent.Diagnostic("unknown_geofence:${triggered.requestId}"))
                return@forEach
            }
            graph.stationaryFence.store.record(registration, transition, System.currentTimeMillis())
            when (transition) {
                GeofenceTransition.ENTER ->
                    events.tryEmit(TrackItEvent.GeofenceEntered(registration.geofence))
                GeofenceTransition.EXIT -> {
                    events.tryEmit(TrackItEvent.GeofenceExited(registration.geofence))
                    stationaryFenceExited = stationaryFenceExited || registration.isStationaryWakeFence
                }
            }
        }

        if (stationaryFenceExited) {
            motionController.onStationaryFenceExit()
            // In-process request; never startForegroundService from a receiver (A13).
            CaptureBus.request()
        }
    }
}
