package com.devstree.trackit.domain.model

import com.devstree.trackit.geo.model.ActivityType
import com.devstree.trackit.geo.model.FixDecision
import com.devstree.trackit.geo.model.MotionState
import com.devstree.trackit.geo.model.TrackPoint
import kotlinx.serialization.Serializable

/**
 * A tracking run, first-class rather than implicit.
 *
 * Every point belongs to a session, and the session carries the config snapshot that
 * was in effect. That is what lets a track recorded six weeks ago be interpreted
 * correctly after the config changed, and it is what makes fixture replay honest —
 * a fixture replays under the config it was recorded with (SDK-COMPARISON §5).
 */
public data class TrackSession(
    val id: String,
    val startedAtMs: Long,
    val startedAtElapsedNanos: Long,
    val endedAtMs: Long? = null,
    val tag: String? = null,
    val configSnapshot: String? = null,
) {
    val isOpen: Boolean get() = endedAtMs == null
}

/** Result type for every fallible entry point. The SDK returns errors; it never throws. */
public sealed interface TrackItResult<out T> {
    public data class Ok<T>(val value: T) : TrackItResult<T>
    public data class Error(val code: ErrorCode, val message: String) : TrackItResult<Nothing>
}

public enum class ErrorCode {
    NOT_READY,
    PERMISSION_DENIED,
    BACKGROUND_PERMISSION_MISSING,
    COARSE_ONLY,
    LOCATION_DISABLED,
    PLAY_SERVICES_UNAVAILABLE,
    FGS_START_REFUSED,
    NOTIFICATION_HIDDEN,
    FIX_TIMEOUT,
    STORAGE_FULL,
    STORAGE_RESET,
    TRACKER_DEAD,
    INVALID_CONFIG,
    LICENSE_MISSING,
    LICENSE_INVALID,
    LICENSE_BUNDLE_MISMATCH,
    NO_ACTIVITY,

    /** motionQuality = POOR — motion gating is not trustworthy on this hardware (EC-137). */
    MOTION_DETECTION_DEGRADED,

    /** Geofence registration failed. The internal stationary wake path degrades. */
    GEOFENCE_REGISTRATION_FAILED,

    /** Geofence removal failed. The active fence may still be armed in Play Services. */
    GEOFENCE_REMOVAL_FAILED,

    /** The SDK-managed geofence registry already contains [TrackItGeofence.MAX_GEOFENCES]. */
    GEOFENCE_LIMIT_REACHED,

    /**
     * A `RoadSnapProvider` was installed but could not answer. **Never fatal**: the track
     * is built from raw geometry and carries a `snap_unavailable` warning (EC-100).
     */
    SNAP_UNAVAILABLE,

    /**
     * Something threw where the contract says nothing throws.
     *
     * Exists for the bridge surfaces, which have to turn *any* failure into a value: a
     * Java callback and a JS Promise both need a code, and inventing a plausible-looking
     * one — `STORAGE_FULL` for an arbitrary exception — would send a host debugging the
     * wrong subsystem. The message carries what actually happened.
     *
     * A host seeing this has found a bug in the SDK, not a condition to handle.
     */
    INTERNAL,
}

public enum class PermissionTier { NONE, FOREGROUND_ONLY, FULL }

public enum class LocationAccuracy { APPROXIMATE, PRECISE }

public data class ProviderState(
    val gpsEnabled: Boolean = false,
    val networkEnabled: Boolean = false,
    val permission: PermissionTier = PermissionTier.NONE,
    val accuracyAuthorization: LocationAccuracy = LocationAccuracy.APPROXIMATE,
    val fusedAvailable: Boolean = false,
    val powerSaveMode: Boolean = false,
)

/**
 * Events the host may observe.
 *
 * Exposed as a `SharedFlow` with replay 0 and unlimited subscribers — never a
 * `var callback`, which silently lets the second registrant replace the first
 * (EC-112).
 */
public sealed interface TrackItEvent {
    public data class Location(val point: TrackPoint) : TrackItEvent
    public data class LocationRejected(val decision: FixDecision) : TrackItEvent
    public data class MotionChange(val state: MotionState, val point: TrackPoint?) : TrackItEvent
    public data class ActivityChange(val activity: ActivityType, val confidence: Int) : TrackItEvent
    public data class EnabledChange(val enabled: Boolean) : TrackItEvent
    public data class ProviderChange(val state: ProviderState) : TrackItEvent
    public data class Heartbeat(val atMs: Long) : TrackItEvent
    public data class PowerSaveChange(val enabled: Boolean) : TrackItEvent
    public data class GeofenceAdded(val geofence: TrackItGeofence) : TrackItEvent
    public data class GeofenceRemoved(val geofenceId: String) : TrackItEvent
    public data class GeofenceEntered(val geofence: TrackItGeofence) : TrackItEvent
    public data class GeofenceExited(val geofence: TrackItGeofence) : TrackItEvent

    /** A session was found still open at launch — the host decides what to do (EC-66). */
    public data class SessionInterrupted(val session: TrackSession) : TrackItEvent
    public data class Diagnostic(val message: String) : TrackItEvent
    public data class Error(val code: ErrorCode, val message: String) : TrackItEvent
}

/**
 * Public state for a persistent circular system geofence.
 *
 * Hosts may register 19 fences. The internal stationary wake fence uses a reserved slot
 * and the same model so all additions and crossings share one event contract.
 */
@Serializable
public data class TrackItGeofence(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val radiusM: Float,
    val onEnterEvent: String = DEFAULT_ENTER_EVENT,
    val onExitEvent: String = DEFAULT_EXIT_EVENT,
) {
    public companion object {
        /** Public parity limit; the SDK's internal stationary wake fence does not count. */
        public const val MAX_GEOFENCES: Int = 19
        public const val DEFAULT_ID: String = "trackit-stationary"
        public const val DEFAULT_ENTER_EVENT: String = "stationary_fence_enter"
        public const val DEFAULT_EXIT_EVENT: String = "stationary_fence_exit"
    }
}

/** A persisted crossing of a registered geofence. */
@Serializable
public data class TrackItGeofenceEvent(
    val geofence: TrackItGeofence,
    val transition: GeofenceTransition,
    val timestampMs: Long,
    val eventName: String,
)

@Serializable
public enum class GeofenceTransition { ENTER, EXIT }

/** Coarse lifecycle state of the SDK itself. */
public data class TrackItState(
    val isReady: Boolean = false,
    val isTracking: Boolean = false,
    val motionState: MotionState = MotionState.STOPPED,
    val providerState: ProviderState = ProviderState(),
    val currentSessionId: String? = null,
)

/**
 * Query surface for stored points. All reads are paged (EC-80).
 *
 * `@Serializable` in place for the same reason as `TrackOptions`: a pure input value
 * object has no internals a mirror DTO would decouple (CROSS-PLATFORM.md B-5).
 */
@Serializable
public data class PointQuery @JvmOverloads constructor(
    val sessionId: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val limit: Int = 500,
    val offset: Int = 0,
)
