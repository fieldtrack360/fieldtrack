package com.devstree.trackit.motion

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.devstree.trackit.domain.model.TrackItEvent
import com.devstree.trackit.domain.model.TrackItGeofence
import com.devstree.trackit.geo.port.TrackLogger
import com.devstree.trackit.sdkLog
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Persistent multi-geofence registry backed by Android's system geofencing service. */
internal class StationaryFence(
    private val context: Context,
    private val events: MutableSharedFlow<TrackItEvent>,
    private val logger: TrackLogger,
) : GeofenceRegistrar {
    internal val store: GeofenceStore = GeofenceStore(context)
    private val mutationLock = Mutex()

    private val client: GeofencingClient by lazy {
        LocationServices.getGeofencingClient(context)
    }

    private val pendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, StationaryFenceReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    fun all(): List<TrackItGeofence> = store.registrations().map { it.geofence }

    fun get(id: String): TrackItGeofence? = store.registration(id)?.geofence

    @SuppressLint("MissingPermission") // Public caller receives a typed failure.
    suspend fun add(geofence: TrackItGeofence): AddResult = mutationLock.withLock {
        if (!store.canAdd(geofence.id, isStationaryWakeFence = false)) {
            return@withLock AddResult.LIMIT_REACHED
        }

        runCatching { client.addGeofences(requestFor(geofence), pendingIntent).await() }
            .onSuccess {
                store.put(RegisteredGeofence(geofence, isStationaryWakeFence = false))
                events.tryEmit(TrackItEvent.GeofenceAdded(geofence))
            }
            .onFailure(::reportRegistrationFailure)
            .fold(onSuccess = { AddResult.ADDED }, onFailure = { AddResult.FAILED })
    }

    suspend fun remove(id: String): Boolean? = mutationLock.withLock {
        val registration = store.registration(id) ?: return@withLock false
        runCatching { client.removeGeofences(listOf(id)).await() }
            .onSuccess {
                store.remove(id)
                events.tryEmit(TrackItEvent.GeofenceRemoved(registration.geofence.id))
            }
            .fold(onSuccess = { true }, onFailure = { null })
    }

    suspend fun removeAll(): Int? = mutationLock.withLock {
        val registrations = store.registrations()
        if (registrations.isEmpty()) return@withLock 0
        runCatching { client.removeGeofences(registrations.map { it.geofence.id }).await() }
            .onSuccess {
                store.clearRegistrations().forEach {
                    events.tryEmit(TrackItEvent.GeofenceRemoved(it.geofence.id))
                }
            }
            .fold(onSuccess = { registrations.size }, onFailure = { null })
    }

    /** Fire-and-observe path used by the motion controller, which is not suspend-based. */
    @SuppressLint("MissingPermission") // StartTrackingUseCase already gates permission.
    override fun register(geofence: TrackItGeofence): Boolean {
        return runCatching { client.addGeofences(requestFor(geofence), pendingIntent) }
            .onSuccess { task ->
                task.addOnSuccessListener {
                    store.put(RegisteredGeofence(geofence, isStationaryWakeFence = true))
                    events.tryEmit(TrackItEvent.GeofenceAdded(geofence))
                }.addOnFailureListener(::reportRegistrationFailure)
            }
            .onFailure(::reportRegistrationFailure)
            .isSuccess
    }

    override fun unregister(id: String): Boolean {
        val registration = store.registration(id) ?: return true
        return runCatching { client.removeGeofences(listOf(id)) }
            .onSuccess { task ->
                task.addOnSuccessListener {
                    store.remove(id)
                    events.tryEmit(TrackItEvent.GeofenceRemoved(registration.geofence.id))
                }
            }
            .isSuccess
    }

    private fun requestFor(geofence: TrackItGeofence): GeofencingRequest {
        val fence = Geofence.Builder()
            .setRequestId(geofence.id)
            .setCircularRegion(geofence.latitude, geofence.longitude, geofence.radiusM)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT,
            )
            .build()
        return GeofencingRequest.Builder()
            .setInitialTrigger(0)
            .addGeofence(fence)
            .build()
    }

    private fun reportRegistrationFailure(error: Throwable) {
        sdkLog { logger.w(TAG, "Geofence registration failed: ${error.message}") }
        events.tryEmit(TrackItEvent.Diagnostic("geofence_unavailable: ${error.message}"))
    }

    internal enum class AddResult { ADDED, LIMIT_REACHED, FAILED }

    private companion object {
        const val TAG = "StationaryFence"
        const val REQUEST_CODE = 8_302
    }
}
