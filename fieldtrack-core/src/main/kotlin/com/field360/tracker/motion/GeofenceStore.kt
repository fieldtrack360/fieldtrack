package com.field360.tracker.motion

import android.content.Context
import androidx.core.content.edit
import com.field360.tracker.domain.model.GeofenceTransition
import com.field360.tracker.domain.model.TrackerGeofence
import com.field360.tracker.domain.model.TrackerGeofenceEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Small synchronous store because geofence broadcasts must survive process recreation. */
internal class GeofenceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    fun registrations(): List<RegisteredGeofence> = synchronized(lock) {
        decodeRegistrations()
    }

    fun registration(id: String): RegisteredGeofence? = synchronized(lock) {
        decodeRegistrations().firstOrNull { it.geofence.id == id }
    }

    fun canAdd(id: String, isStationaryWakeFence: Boolean): Boolean = synchronized(lock) {
        val registrations = decodeRegistrations()
        isStationaryWakeFence || registrations.any { it.geofence.id == id } ||
            registrations.count { !it.isStationaryWakeFence } < TrackerGeofence.MAX_GEOFENCES
    }

    fun put(registration: RegisteredGeofence) {
        synchronized(lock) {
            val registrations = decodeRegistrations().toMutableList()
            registrations.removeAll { it.geofence.id == registration.geofence.id }
            registrations += registration
            preferences.edit { putString(REGISTRATIONS_KEY, json.encodeToString(registrations)) }
        }
    }

    fun remove(id: String): RegisteredGeofence? = synchronized(lock) {
        val registrations = decodeRegistrations().toMutableList()
        val removed = registrations.firstOrNull { it.geofence.id == id } ?: return@synchronized null
        registrations.removeAll { it.geofence.id == id }
        preferences.edit { putString(REGISTRATIONS_KEY, json.encodeToString(registrations)) }
        removed
    }

    fun clearRegistrations(): List<RegisteredGeofence> = synchronized(lock) {
        val registrations = decodeRegistrations()
        preferences.edit { remove(REGISTRATIONS_KEY) }
        registrations
    }

    fun record(
        registration: RegisteredGeofence,
        transition: GeofenceTransition,
        timestampMs: Long,
    ): TrackerGeofenceEvent = synchronized(lock) {
        val event = TrackerGeofenceEvent(
            geofence = registration.geofence,
            transition = transition,
            timestampMs = timestampMs,
            eventName = when (transition) {
                GeofenceTransition.ENTER -> registration.geofence.onEnterEvent
                GeofenceTransition.EXIT -> registration.geofence.onExitEvent
            },
        )
        val history = decodeEvents().toMutableList()
        history += event
        if (history.size > MAX_HISTORY_SIZE) {
            history.subList(0, history.size - MAX_HISTORY_SIZE).clear()
        }
        preferences.edit { putString(EVENTS_KEY, json.encodeToString(history)) }
        event
    }

    fun events(
        geofenceId: String?,
        fromMs: Long?,
        toMs: Long?,
        limit: Int,
        offset: Int,
    ): List<TrackerGeofenceEvent> = synchronized(lock) {
        decodeEvents()
            .asReversed()
            .asSequence()
            .filter { geofenceId == null || it.geofence.id == geofenceId }
            .filter { fromMs == null || it.timestampMs >= fromMs }
            .filter { toMs == null || it.timestampMs <= toMs }
            .drop(offset.coerceAtLeast(0))
            .take(limit.coerceIn(0, MAX_QUERY_SIZE))
            .toList()
    }

    fun deleteEvents(geofenceId: String?, fromMs: Long?, toMs: Long?): Int = synchronized(lock) {
        val history = decodeEvents().toMutableList()
        val before = history.size
        history.removeAll {
            (geofenceId == null || it.geofence.id == geofenceId) &&
                (fromMs == null || it.timestampMs >= fromMs) &&
                (toMs == null || it.timestampMs <= toMs)
        }
        preferences.edit { putString(EVENTS_KEY, json.encodeToString(history)) }
        before - history.size
    }

    private fun decodeRegistrations(): List<RegisteredGeofence> = decode(
        key = REGISTRATIONS_KEY,
        serializer = ListSerializer(RegisteredGeofence.serializer()),
    )

    private fun decodeEvents(): List<TrackerGeofenceEvent> = decode(
        key = EVENTS_KEY,
        serializer = ListSerializer(TrackerGeofenceEvent.serializer()),
    )

    private fun <T> decode(key: String, serializer: kotlinx.serialization.KSerializer<List<T>>): List<T> {
        val encoded = preferences.getString(key, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, encoded) }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFERENCES_NAME = "trackit_geofences"
        const val REGISTRATIONS_KEY = "registrations"
        const val EVENTS_KEY = "events"
        const val MAX_HISTORY_SIZE = 10_000
        const val MAX_QUERY_SIZE = 5_000
    }
}

@Serializable
internal data class RegisteredGeofence(
    val geofence: TrackerGeofence,
    val isStationaryWakeFence: Boolean,
)
