# FieldTrack Developer Guide

Source-verified integration and API reference for FieldTrack.

Version values used in this guide:

- `TRAKER_VERSION`: the SDK version to publish, install, and reference throughout the doc.

This guide documents the SDK that is implemented in the current repository. It is aimed
at Android application developers who need to install, configure, operate, query, render,
debug, or extend Traker. For algorithm details, see [API.md](API.md). For the exported
JSON contract, see [POLYLINE-JSON.md](POLYLINE-JSON.md).

## Contents

1. [Requirements and modules](#1-requirements-and-modules)
2. [Installation](#2-installation)
3. [Required permissions](#3-required-permissions)
4. [Initialization and lifecycle](#4-initialization-and-lifecycle)
5. [Configuration](#5-configuration)
6. [Complete Traker method reference](#6-complete-traker-method-reference)
7. [Results, state, events, and errors](#7-results-state-events-and-errors)
8. [Reading sessions and points](#8-reading-sessions-and-points)
9. [Historical track plotting and export](#9-historical-track-plotting-and-export)
10. [Live map rendering and navigation](#10-live-map-rendering-and-navigation)
11. [Geofences](#11-geofences)
12. [Diagnostics and custom fixes](#12-diagnostics-and-custom-fixes)
13. [HTTP synchronization](#13-http-synchronization)
14. [Road snapping](#14-road-snapping)
15. [Advanced geo APIs](#15-advanced-geo-apis)
16. [Production checklist](#16-production-checklist)
17. [Troubleshooting](#17-troubleshooting)

## 1. Requirements and modules

- Android only, Kotlin-first
- `minSdk 26`
- `compileSdk 37`
- JDK 17
- Maven group: `com.github.fieldtrack360.fieldtrack`
- Current version: `TRAKER_VERSION`


## 2. Installation

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
}
```

Install the required artifact :
```kotlin
implementation 'com.github.fieldtrack360.fieldtrack:fieldtrack:TAG'
implementation("com.squareup.okhttp3:okhttp:5.4.0")
```

No Hilt plugin, DI framework, annotation processor, or Traker Gradle plugin is required.
The AAR supplies its services, receivers, permissions, and consumer ProGuard rules through
manifest merging.

Release builds also expect a Traker license token. Provide it through
`TrakerConfig.license` or an `AndroidManifest.xml` meta-data entry named
`TrackItLicense`. Debuggable installs are waived automatically. The same release pass
also keeps `Traker.state.value.providerState` and `Traker.state.value.motionState`
synchronized with the matching change events, and emits `TrakerEvent.Heartbeat(atMs)`
after the watchdog check while a session is open.

## 3. Required permissions

The SDK declares permissions in its manifest, but the host application must display the
rationale and make runtime permission requests. Traker never displays permission UI.

Request permissions in this order:

1. Notification permission on API 33+.
2. Fine and coarse foreground location together.
3. Background location separately, after fine location is granted.
4. Activity recognition optionally. Denial reduces motion quality but is not fatal.

```kotlin
val trackIt = Traker.getInstance(applicationContext)
val permissions = trackIt.permissions()

notificationLauncher.launch(permissions.notificationPermissions())
foregroundLocationLauncher.launch(permissions.foregroundPermissions())

when (val request = permissions.backgroundRequest()) {
    PermissionManager.BackgroundRequest.AlreadyGranted -> startTracking()
    PermissionManager.BackgroundRequest.NotApplicable -> startTracking()
    PermissionManager.BackgroundRequest.NeedsForegroundFirst -> requestForegroundFirst()
    is PermissionManager.BackgroundRequest.Prompt -> backgroundLauncher.launch(request.permissions)
    is PermissionManager.BackgroundRequest.NeedsSettings -> startActivity(request.intent)
}

activityRecognitionLauncher.launch(permissions.activityRecognitionPermissions())
```

### PermissionManager methods

| Method | Use |
|---|---|
| `tier()` | Returns `NONE`, `FOREGROUND_ONLY`, or `FULL`. |
| `accuracy()` | Returns `APPROXIMATE` or `PRECISE`. |
| `hasActivityRecognition()` | Checks optional activity-recognition access. |
| `hasNotificationPermission()` | Checks notification permission where applicable. |
| `foregroundPermissions()` | Returns the fine/coarse permission array for a launcher. |
| `notificationPermissions()` | Returns `POST_NOTIFICATIONS` on API 33+, otherwise an empty array. |
| `activityRecognitionPermissions()` | Returns the appropriate permission array, or empty on old Android versions. |
| `backgroundRequest()` | Describes the correct next background-location action for the current API level. |
| `shouldStopAsking(attempts)` | Returns true at three attempts to prevent prompt loops. |
| `appSettingsIntent()` | Opens this app's Android settings page. |

Background permission is not mandatory for a foreground-only session, but the OS may
pause or stop capture after the app leaves the foreground. Observe provider state and SDK
events so the UI can explain degraded operation.

## 4. Initialization and lifecycle

The required call order is:

```text
getInstance() -> ready() -> start() -> stop()
```

Create the process singleton and prepare it once from `Application.onCreate`:

```kotlin
class App : Application() {
    val trackIt by lazy { Traker.getInstance(this) }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            when (val result = trackIt.ready(TrakerConfig())) {
                is TrakerResult.Ok -> Unit
                is TrakerResult.Error -> Log.e("Traker", "${result.code}: ${result.message}")
            }
        }
    }
}
```

Start only after `ready()` succeeds and foreground location permission is granted:

```kotlin
suspend fun startCommute(trackIt: Traker) {
    when (val result = trackIt.start(tag = "commute")) {
        is TrakerResult.Ok -> Log.d("Traker", "Session ${result.value.id}")
        is TrakerResult.Error -> showTrackingError(result.code, result.message)
    }
}

suspend fun stopTracking(trackIt: Traker) {
    when (val result = trackIt.stop()) {
        is TrakerResult.Ok -> Log.d("Traker", "Stopped ${result.value?.id}")
        is TrakerResult.Error -> showTrackingError(result.code, result.message)
    }
}
```

`getInstance()` is idempotent and stores only the application context. `ready()` resolves
and persists configuration, restores filter state, starts provider monitoring, schedules
retention work, and emits `SessionInterrupted` if a previous process died with an open
session. Before any of that, `ready()` checks the license token. Calling `start()` before
`ready()` returns `NOT_READY`.

Typical startup code keeps `ready()` in application scope and collects both the state
snapshot and the event stream from a lifecycle-aware scope:

```kotlin
appScope.launch {
    when (val result = trackIt.ready(
        TrakerConfig.builder()
            .license(BuildConfig.TRAKER_LICENSE.takeIf { it.isNotBlank() })
            .build()
    )) {
        is TrakerResult.Ok -> Unit
        is TrakerResult.Error -> Log.e("Traker", "${result.code}: ${result.message}")
    }
}

lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        launch { trackIt.state.collect(::renderTrackingState) }
        launch {
            trackIt.events.collect { event ->
                when (event) {
                    is TrakerEvent.ProviderChange -> updateProviderUi(event.state)
                    is TrakerEvent.MotionChange -> updateMotionUi(event.state)
                    is TrakerEvent.Heartbeat -> updateHeartbeatUi(event.atMs)
                    else -> Unit
                }
            }
        }
    }
}
```

## 5. Configuration

Use immutable configuration objects directly for simple cases:

```kotlin
val config = TrakerConfig(
    geolocation = GeolocationConfig(
        trackingMode = TrackingMode.ADAPTIVE,
        providerType = LocationProviderType.FUSED,
        accuracy = AccuracyConfig(profile = AccuracyProfile.BALANCED),
        intervalMs = 60_000,
        vehicularIntervalMs = 12_000,
    ),
    service = ServiceConfig(
        foregroundService = true,
        startOnBoot = true,
    ),
)
```

Use the builder for a flat, Java-friendly configuration API:

```kotlin
val config = TrakerConfig.builder()
    .trackingMode(TrackingMode.ADAPTIVE)
    .provider(LocationProviderType.FUSED)
    .accuracyProfile(AccuracyProfile.BALANCED)
    .intervalMs(60_000)
    .vehicularIntervalMs(12_000)
    .foregroundService(true)
    .startOnBoot(true)
    .maxDaysToPersist(7)
    .build()
```

`build()` throws `IllegalArgumentException` when invalid. `buildUnchecked()` constructs
the value without throwing; `ready()` then returns `INVALID_CONFIG`. `config.validate()`
returns every validation message as a list.

### Top-level configuration groups

| Group | Controls |
|---|---|
| `GeolocationConfig` | Provider, tracking mode, accuracy, cadence, batching, navigation, mock policy |
| `MotionConfig` | Activity recognition, stop detection, stationary geofence, heartbeat, turn capture |
| `SensorConfig` | Significant-motion, steps, accelerometer veto, barometer |
| `ServiceConfig` | Foreground service, boot behavior, health/watchdog, notification |
| `PersistenceConfig` | Retention, record caps, raw data, decision logging |

### Important choices

| Setting | Values / meaning |
|---|---|
| `trackingMode` | `CONTINUOUS`: always request fixes; `ADAPTIVE`: reduce capture while stationary; `MOTION_ONLY`: turn location off while stationary. |
| `providerType` | `FUSED`: recommended; `GPS_ONLY`: no Play Services and satellite only; `NETWORK_ONLY`: coarse/cheap; `PASSIVE`: only fixes requested by other apps. |
| `accuracy.profile` | `STRICT` 20 m, `BALANCED` 30 m, `RELAXED` 60 m, or `CUSTOM`. |
| `reset` | `true` uses and persists the supplied object; `false` reuses the complete persisted config when one exists and ignores the newly supplied object. |
| `license` | Supplies a release token at startup; the value is transient, is checked by `ready()`, and is ignored for debuggable installs. |
| `navigationMode` | Enables high-accuracy near-1 Hz capture and disables batching. Requires foreground service. |
| `mockLocationPolicy` | `FLAG`, `REJECT`, or `ALLOW`. |
| `startOnBoot` | Restarts eligible tracking after reboot; default `true`. |
| `stopOnTerminate` | Stops when the task is removed; default `false`. |

### Builder method catalog

| Builder methods | Purpose |
|---|---|
| `geolocation`, `motion`, `service`, `persistence`, `sensors` | Replace a complete configuration group. |
| `reset` | Select supplied-config persistence (`true`) or reuse of an existing persisted config (`false`). |
| `license` | Provide a release token for `ready()`; debug installs are waived and the token is not persisted. |
| `trackingMode`, `provider`, `desiredAccuracy` | Select capture strategy and Android location provider. |
| `accuracyProfile`, `maxAccuracyMeters`, `recoveryTrustMeters`, `accuracy` | Configure moving-fix and post-gap accuracy thresholds. |
| `intervalMs`, `fastestIntervalMs`, `maxUpdateDelayMs`, `maxFixAgeMs` | Configure normal cadence, batching, and accepted fix age. |
| `adaptiveCadence`, `vehicularIntervalMs` | Configure faster capture during vehicle motion. |
| `turnBurst`, `turnBurstIntervalMs`, `bearingChangeCaptureDeg` | Preserve turn geometry. |
| `navigationMode`, `navigationIntervalMs`, `navigationFastestIntervalMs` | Configure navigation cadence. |
| `oneShotTimeoutMs`, `mockLocationPolicy` | Configure one-shot timeout and mock handling. |
| `activityRecognition`, `activityRecognitionIntervalMs`, `activityConfidenceMin`, `snapshotConfidenceMin` | Configure activity enrichment. |
| `disableStopDetection`, `stopOnStationary`, `stopTimeoutMin`, `stationaryRadiusM` | Configure stationary behavior. |
| `stationaryGeofenceId`, `stationaryGeofenceOnEnterEvent`, `stationaryGeofenceOnExitEvent` | Configure the SDK-managed stationary fence. |
| `motionTriggerDelayMs`, `heartbeatIntervalSec`, `persistHeartbeat` | Configure motion wake delay and stationary filter heartbeat. |
| `useSignificantMotion`, `useStepCorroboration`, `useAccelerometerVeto`, `useBarometer`, `stepBatchLatencyMs` | Configure sensor use. |
| `foregroundService`, `stopOnTerminate`, `startOnBoot` | Configure service survival. |
| `healthLoopMs`, `watchdogIntervalMs`, `watchdogThrottleMs`, `backstopIntervalMin` | Configure service health recovery. |
| `deadTrackerMovingMin`, `deadTrackerStationaryMin`, `wakeLockMs` | Configure dead-tracker thresholds and wake duration. |
| `notification`, `notificationChannel`, `notificationSmallIconResName` | Configure the foreground notification. |
| `maxDaysToPersist`, `maxRecords` | Configure accepted-point retention. `maxRecords = 0` means no row cap. |
| `persistRawFixes`, `rawRingCapacity` | Keep raw OS fixes for diagnostics. |
| `persistRawPoints`, `rawPointRingCapacity` | Keep every judged point-shaped candidate. |
| `persistDecisions`, `decisionRetentionDays`, `decisionMaxRows` | Configure the decision audit log. |
| `build`, `buildUnchecked` | Validate-and-build or build for validation later in `ready()`. |

With the default `reset = true`, configuration passed to `ready()` is persisted and used
by subsequent sessions. With `reset = false`, an existing persisted configuration wins in
full. There is no public live `setConfig()` method in this version, so use `reset = true`
on a later process launch when intentionally replacing persisted settings.

If you ship a license token, set it with `TrakerConfig.license` or an `AndroidManifest`
meta-data entry named `TrackItLicense`. The token is checked before config resolution and
is not written back into persisted config.

The Android release changes in this branch follow the same rule: keep the token as a
startup override, use `state` for current UI snapshots, and use `events` when you need
transitions or liveness information. `Traker.state.value.providerState` and
`Traker.state.value.motionState` now stay in sync with the latest provider and motion
events, while `TrakerEvent.Heartbeat(atMs)` gives you a watchdog-backed heartbeat for an
open session.

## 6. Complete Traker method reference

Obtain the API with `Traker.getInstance(context)`. These are all public methods and
observable properties on `Traker` in this version.

### Setup and capture

| API | Returns | Use |
|---|---|---|
| `getInstance(context)` | `Traker` | Get the process-wide SDK singleton. |
| `ready(config = TrakerConfig())` | `TrakerResult<TrakerState>` | Validate/resolve config, check license, restore state, start monitors and retention work. |
| `start(tag = null)` | `TrakerResult<TrackSession>` | Open or return the active session and begin capture. |
| `stop()` | `TrakerResult<TrackSession?>` | Stop capture and close the active session. |
| `getCurrentLocation()` | `TrakerResult<TrackFix>` | Request a fresh, non-persisted location snapshot using the ready configuration. |
| `state` | `StateFlow<TrakerState>` | Observe ready/tracking/motion/provider/session state. |
| `events` | `SharedFlow<TrakerEvent>` | Observe locations, decisions, provider changes, geofences, diagnostics, and errors. |

### Permissions and device status

| API | Returns | Use |
|---|---|---|
| `permissions()` | `PermissionManager` | Get arrays/actions for the host-owned permission flow. |
| `permissionTier()` | `PermissionTier` | Read the current location-permission tier once. |
| `providerState()` | `StateFlow<ProviderState>` | Observe GPS/network availability, permissions, accuracy, fused availability, and power save. |
| `batteryInfo()` | `BatteryInfo` | Read charge level, charging state and power source now. No session or permission needed. |
| `batteryState()` | `StateFlow<BatteryInfo>` | Observe the same, updated on plug/unplug/low/okay. |
| `getSensors()` | `DeviceSensors` | Probe sensor availability and derived `MotionQuality`. |

### Stored data

| API | Returns | Use |
|---|---|---|
| `getPoints(query = PointQuery())` | `List<TrackPoint>` | Read a page of accepted points. |
| `observePoints(sessionId)` | `Flow<List<TrackPoint>>` | Observe accepted points for one session from Room. |
| `getCount(query = PointQuery())` | `Int` | Count points matching a query. |
| `getOdometerMeters()` | `Double` | Read total persisted odometer distance. |
| `getSessions(fromMs = null, toMs = null)` | `List<TrackSession>` | Read sessions in an optional wall-clock range. |
| `currentSession()` | `TrackSession?` | Read the currently open session. |

### Historical and live plotting

| API | Returns | Use |
|---|---|---|
| `buildTrack(query, options)` | `Track` | Build consolidated, smoothed, optionally snapped drawable geometry. |
| `exportPolylineJson(query, options)` | `String` | Export Traker's versioned JSON format. |
| `exportGeoJson(query, options)` | `String` | Export an RFC 7946 GeoJSON `FeatureCollection`. |
| `setRoadSnapProvider(provider)` | `Unit` | Install optional historical road matching. |
| `liveTrack()` | `Flow<LiveTrackUpdate>` | Receive conflated frames for a live map. |
| `setActiveRoute(route)` | `Unit` | Snap only the live puck to a known navigation route; empty clears it. |
| `isOffRoute()` | `Boolean` | Check whether consecutive fixes indicate departure from the active route. |

### Geofences

| API | Returns | Use |
|---|---|---|
| `addGeofence(geofence)` | `TrakerResult<TrakerGeofence>` | Register or replace one of 19 persistent system geofences. |
| `getGeofences()` | `List<TrakerGeofence>` | Return all registered fences. |
| `getGeofence(id = DEFAULT_ID)` | `TrakerGeofence?` | Return the matching registered fence. |
| `removeGeofence(id = DEFAULT_ID)` | `TrakerResult<Boolean>` | Remove the matching fence; `Ok(false)` means none matched. |
| `removeAllGeofences()` | `TrakerResult<Int>` | Remove every SDK-managed fence and return the count. |
| `getGeofenceEvents(...)` | `List<TrakerGeofenceEvent>` | Read newest-first crossing history with filters and paging. |
| `deleteGeofenceEvents(...)` | `Int` | Delete matching crossing history. |

### Diagnostics and testing

| API | Returns | Use |
|---|---|---|
| `getRawFixes(sessionId)` | `List<RawFix>` | Inspect OS-delivered fixes when `persistRawFixes` is enabled. |
| `getRawPoints(sessionId)` | `List<RawPoint>` | Inspect every judged candidate when `persistRawPoints` is enabled. |
| `getDecisions(sessionId, limit = 200, offset = 0)` | `List<FixDecision>` | Read acceptance/rejection reasons. |
| `offerFix(fix)` | `Unit` | Send a custom/test `TrackFix` through the normal validation pipeline. |

Methods described in older planning documents such as `setConfig`, `changePace`,
`getCurrentPosition`, `insertPoint`, `deletePoints`, `resetOdometer`, `requestPermission`,
and `exportFixture` are not public `Traker` methods in this version. Use
`getCurrentLocation()` for the Android one-shot location snapshot.

## 7. Results, state, events, and errors

### Handling results

Fallible entry points return a typed result:

```kotlin
when (val result = trackIt.start()) {
    is TrakerResult.Ok -> useSession(result.value)
    is TrakerResult.Error -> handle(result.code, result.message)
}
```

The SDK aims not to throw from operational entry points. Configuration builder validation
is the intentional exception.

### One-shot location

Call `ready()` first and complete the host-owned foreground location permission flow.
`getCurrentLocation()` uses the provider, accuracy, mock-location policy, and
`oneShotTimeoutMs` from the ready configuration:

```kotlin
when (val result = trackIt.getCurrentLocation()) {
    is TrakerResult.Ok -> {
        val fix = result.value
        Log.d("Traker", "${fix.latitude}, ${fix.longitude} +/- ${fix.accuracy} m")
    }
    is TrakerResult.Error -> handle(result.code, result.message)
}
```

The returned `TrackFix` is a snapshot, not an accepted `TrackPoint`. It has no session
ID, UUID, acceptance reason, or odometer contribution. The SDK does not persist it or
emit `TrakerEvent.Location`; call `start()` when fixes should enter the tracking
pipeline. Expected errors are `NOT_READY`, `PERMISSION_DENIED`, `LOCATION_DISABLED`,
and `FIX_TIMEOUT`.

### State

`TrakerState` contains:

- `isReady`
- `isTracking`
- `motionState`
- `providerState`
- `currentSessionId`

Use `state` for the current snapshot and `events` for the live transition stream. In this
release, `providerState` and `motionState` stay aligned with their matching events, so UI
code should read the snapshot for rendering and the event stream for timing-sensitive
changes such as heartbeat age.

Collect it with lifecycle awareness:

```kotlin
lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        trackIt.state.collect(::renderTrackingState)
    }
}
```

### Events

`events` is a `SharedFlow` with replay `0`. Start collecting before the event of interest.

| Event | Meaning |
|---|---|
| `Location(point)` | A point was accepted and stored. |
| `LocationRejected(decision)` | A fix was rejected and includes the reason. |
| `MotionChange(state, point)` | Motion state changed. |
| `ActivityChange(activity, confidence)` | Activity recognition changed. |
| `EnabledChange(enabled)` | Tracking enabled state changed. |
| `ProviderChange(state)` | Permissions/provider/power state changed. |
| `Heartbeat(atMs)` | Control-plane heartbeat emitted after the watchdog check while a session is open. |
| `PowerSaveChange(enabled)` | Battery saver changed. |
| `GeofenceAdded(geofence)` | SDK geofence was armed. |
| `GeofenceRemoved(geofenceId)` | SDK geofence was removed. |
| `GeofenceEntered(geofence)` | Device entered the SDK geofence. |
| `GeofenceExited(geofence)` | Device exited the SDK geofence. |
| `SessionInterrupted(session)` | `ready()` found an unclosed previous session. |
| `Diagnostic(message)` | Non-fatal operational diagnostic. |
| `Error(code, message)` | Typed SDK error. |

### ErrorCode values

| Code | Typical response |
|---|---|
| `NOT_READY` | Wait for successful `ready()`. |
| `PERMISSION_DENIED` | Request foreground location. |
| `BACKGROUND_PERMISSION_MISSING` | Explain degraded background behavior and offer the correct permission route. |
| `COARSE_ONLY` | Ask the user to enable precise location for modes that require it. |
| `LOCATION_DISABLED` | Ask the user to enable location services. |
| `PLAY_SERVICES_UNAVAILABLE` | Select `GPS_ONLY`, `NETWORK_ONLY`, or `PASSIVE`, or install Play Services. |
| `FGS_START_REFUSED` | Ensure start was initiated from an OS-allowed context. |
| `NOTIFICATION_HIDDEN` | Request notification permission and check channel settings. |
| `FIX_TIMEOUT` | No usable one-shot fix arrived before timeout. |
| `STORAGE_FULL`, `STORAGE_RESET` | Surface data/storage health and inspect diagnostics. |
| `TRACKER_DEAD` | Watchdog detected a stalled tracker. |
| `INVALID_CONFIG` | Display/log the returned validation message. |
| `LICENSE_MISSING` | Provide a token or manifest meta-data entry for release builds. |
| `LICENSE_INVALID` | Check token shape, payload, signature, or key id. |
| `LICENSE_BUNDLE_MISMATCH` | Issue a token for the app's actual bundle id. |
| `MOTION_DETECTION_DEGRADED` | SDK has reduced confidence in motion gating. |
| `SNAP_UNAVAILABLE` | Render returned raw geometry; snapping is non-fatal. |
| `NO_ACTIVITY` | No recognized activity is available. |
| `GEOFENCE_REGISTRATION_FAILED`, `GEOFENCE_REMOVAL_FAILED` | Check permission/provider/device support and retry deliberately. |
| `INTERNAL` | Record diagnostics and report an SDK defect. |

## 8. Reading sessions and points

Every accepted point belongs to a `TrackSession`. Use its ID for reads and plotting:

```kotlin
val sessions = trackIt.getSessions()
val session = sessions.lastOrNull() ?: return

var offset = 0
val pageSize = 500
do {
    val page = trackIt.getPoints(
        PointQuery(sessionId = session.id, limit = pageSize, offset = offset)
    )
    consume(page)
    offset += page.size
} while (page.size == pageSize)
```

`PointQuery` fields are `sessionId`, `fromMs`, `toMs`, `limit`, and `offset`. Its default
page size is 500. A query without a session ID can span sessions.

Important `TrackPoint` fields include both wall time (`timeMs`) and monotonic observation
time (`elapsedRealtimeNanos`), location/accuracy, speed/bearing presence flags, provider,
mock status, motion/activity, odometer, battery, and the acceptance reason. Always check
`hasSpeed` and `hasBearing`; zero is a valid measured value and also the placeholder when
the provider supplied no measurement.

## 9. Historical track plotting and export

```kotlin
val query = PointQuery(sessionId = sessionId)
val options = TrackOptions(
    zoom = 14f,
    smoothing = Smoothing.SPLINE,
    consolidateStops = true,
    snapToRoad = false,
)

val track = trackIt.buildTrack(query, options)
val trackJson = trackIt.exportPolylineJson(query, options)
val geoJson = trackIt.exportGeoJson(query, options)
```

`Track` includes bounds, statistics, encoded geometry, indexed source points, travel/stop
segments, consolidated stops, direction arrows, and warnings. Read `track.precision` when
decoding the polyline; the default is 6, not the commonly assumed 5.

### TrackOptions

| Field | Purpose |
|---|---|
| `zoom` | Select arrow spacing appropriate to the map zoom. |
| `includeRawPoints` | Include source point records in the output. |
| `consolidateStops`, `stopRadiusM`, `stopMinDwellSec` | Configure stop detection for plotting. |
| `smoothing` | `NONE`, `BEZIER`, or default `SPLINE`. |
| `splineSpacingM` | Resampling distance for spline output. |
| `bezierMinAngleDeg`, `bezierCutbackM` | Configure Bezier corner rounding. |
| `snapToRoad`, `snapMaxOffRoadM` | Enable provider-backed road geometry and its safety guard. |
| `polylinePrecision` | Encoded-polyline precision. |
| `speedBandsKmph` | Thresholds for segment speed labels/colors. |
| `arrowMinSegmentM` | Minimum segment length eligible for arrows. |
| `simplifyEpsilonM` | Douglas-Peucker tolerance before smoothing; zero disables. |

Render with Google Maps:

```kotlin
val renderer = TrackRenderer(
    googleMap,
    TrackRenderer.RendererOptions(showArrows = true, showStopMarkers = true),
)
renderer.render(track, fitCamera = true)

googleMap.setOnCameraIdleListener {
    if (renderer.needsArrowRefresh()) {
        lifecycleScope.launch {
            val rebuilt = trackIt.buildTrack(query, options.copy(zoom = googleMap.cameraPosition.zoom))
            renderer.render(rebuilt, fitCamera = false)
        }
    }
}

// Call when the map is disposed.
renderer.clear()
```

`TrackRenderer` is main-thread only and not thread-safe. Its methods are `render`,
`needsArrowRefresh`, and `clear`.

## 10. Live map rendering and navigation

The historical `buildTrack()` product is built on demand. `liveTrack()` is a conflated
per-fix feed intended for an on-screen map.

```kotlin
val renderer = LiveTrackRenderer(
    googleMap,
    LiveTrackRenderer.Options(
        cameraFollow = LiveTrackRenderer.CameraFollowMode.FOLLOW_BEARING,
    ),
)

lifecycleScope.launch {
    repeatOnLifecycle(Lifecycle.State.STARTED) {
        trackIt.liveTrack().collect(renderer::render)
    }
}
```

`LiveTrackRenderer.render(update)` drops stale sequences, incrementally grows the frozen
tail, redraws the unsettled head, and animates the puck. `clear()` removes all map objects
and cancels animation. The mutable `cameraFollow` property may be changed at runtime to
`NONE`, `FOLLOW`, or `FOLLOW_BEARING`.

When navigating a known route:

```kotlin
trackIt.setActiveRoute(route.map { GeoPoint(it.latitude, it.longitude) })

if (trackIt.isOffRoute()) requestReroute()

// End navigation route projection:
trackIt.setActiveRoute(emptyList())
```

This only projects the live puck. It never changes stored evidence or historical tracks.

## 11. Geofences

This version manages up to 19 persistent system geofences, matching the iOS SDK limit.
The stationary wake fence shares the registry but remains internally marked, so exiting
a host business fence emits an event without changing motion capture state.

```kotlin
val fence = TrakerGeofence(
    id = "warehouse",
    latitude = 23.0225,
    longitude = 72.5714,
    radiusM = 150f,
    onEnterEvent = "warehouse_enter",
    onExitEvent = "warehouse_exit",
)

when (val result = trackIt.addGeofence(fence)) {
    is TrakerResult.Ok -> Unit
    is TrakerResult.Error -> Log.e("Traker", result.message)
}

val armed = trackIt.getGeofence("warehouse")
val removed = trackIt.removeGeofence("warehouse")
```

Listen for `GeofenceAdded`, `GeofenceRemoved`, `GeofenceEntered`, and `GeofenceExited` on
`trackIt.events`. Crossings remain queryable after fence removal through
`getGeofenceEvents()` and can be removed with `deleteGeofenceEvents()`.

## 12. Diagnostics and custom fixes

Enable only the data needed for a diagnostic build because raw layers increase storage
and write volume:

```kotlin
val config = TrakerConfig.builder()
    .persistRawFixes(true)
    .persistRawPoints(true)
    .persistDecisions(true)
    .build()
```

| Layer | Method | Question answered |
|---|---|---|
| Raw OS input | `getRawFixes(sessionId)` | What did Android deliver? |
| Judged candidates | `getRawPoints(sessionId)` | Which candidates were not stored, and why? |
| Decision log | `getDecisions(sessionId, limit, offset)` | Which gate accepted, skipped, or rejected the fix? |

`RawPoint.verdict` is `ACCEPT`, `SKIP`, or `REJECT`; `isAccepted` is a convenience
property. Decision reason strings are part of the SDK contract and should be logged
without rewriting them.

For tests, fixture replay, or a custom provider:

```kotlin
trackIt.offerFix(
    TrackFix(
        timeMs = clock.currentTimeMillis(),
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
        receivedAtElapsedNanos = SystemClock.elapsedRealtimeNanos(),
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        provider = "custom",
    )
)
```

`offerFix()` does not bypass validation. A session must be active for normal ingestion.

## 13. HTTP synchronization

Add `trackit-sync` and either OkHttp or a custom `SyncTransport`. The full endpoint contract —
request body field by field, response status semantics, and every API in its own subsection —
is in [USER-GUIDE.md §11](USER-GUIDE.md#11-optional-modules).

A base URL can be set in either builder. `SyncConfig.builder()` keeps it next to the upload
config; `TrakerConfig.builder().baseUrl(...)` sets it once for an app that already has one,
and `trackit-sync` resolves a bare `path` against it at `configure()` time. An absolute URL on
the `SyncConfig` always wins — the core value is a fallback, never an override.

```kotlin
sync.configure(
    SyncConfig.builder()
        .baseUrl(BuildConfig.API_BASE_URL)     // "https://api.example.com"
        .path("v1/location/batch")             // joined with exactly one "/"
        .header("Authorization", "Bearer $token")
        .build(),
)
```

```kotlin
val sync = TrakerSync.getInstance(applicationContext)
sync.configure(
    SyncConfig(
        url = "https://api.example.com/location/batch",
        method = "POST",
        headers = mapOf("Authorization" to "Bearer $token"),
        autoSync = true,
        batchSize = 100,
        requiresUnmeteredNetwork = false,
        gzipRequestBody = false,          // opt-in; see below
        timeouts = SyncTimeouts(),        // connect 5 s, read 30 s, write 20 s
    )
)

val pending = sync.pendingCount()
sync.requestSync()                   // WorkManager, safe to call repeatedly

when (val result = sync.syncNow()) { // inline, suspend
    is SyncQueue.Result.Uploaded -> Log.d("Traker", "Uploaded ${result.count}")
    SyncQueue.Result.Empty -> Unit
    is SyncQueue.Result.Retry -> Log.w("Traker", result.reason)
    SyncQueue.Result.AuthExpired -> forceLogout()
    SyncQueue.Result.Forbidden -> promptForNewCredential()
}
```

### `autoSync` drives itself

With `autoSync = true` (the default) the SDK requests its own drains and a host need call
nothing. Three triggers, deliberately covering different failures:

| Trigger | When | Why it exists |
|---|---|---|
| Accepted point | a point was stored | What `autoSync` means. Throttled to one request a minute — in navigation mode points arrive every second, and a 100-row batch is nowhere near full in 60 s |
| Health loop | every 2 min while the service runs | Rows queued, or the last confirmed upload ≥ 16 min old |
| Backstop worker | every 15 min | The same check, but it survives a dead service — the only thing that notices a backlog left by a drain that failed while the process was gone |

The supervision triggers ask the queue first: with nothing pending, no worker is woken.

Set `autoSync = false` to own the schedule yourself; nothing is registered and `requestSync()`
/ `syncNow()` are then the only paths. A 401 or 403 also unregisters the trigger, so a dead
credential cannot keep the loop alive.

A parked user uploads nothing, because the filter stores nothing. That is by design and is
not evidence of a dead tracker.

### TrakerSync methods

| Method | Use |
|---|---|
| `getInstance(context)` | Get the process-wide sync instance linked to Traker's database. |
| `configure(config, transport = null)` | Set endpoint behavior and optional custom transport. **Throws `IllegalArgumentException`** if the config fails `SyncConfig.validate()`. |
| `pendingCount()` | Count queued rows. |
| `requestSync()` | Enqueue unique network-constrained WorkManager work. No-op before configuration, and after a 403. |
| `syncNow()` | Drain inline and return `Uploaded`, `Empty`, `Retry`, `AuthExpired`, or `Forbidden`. |
| `events` | `SharedFlow<SyncEvent>` — one `HttpResponse(statusCode, count)` per exchange, including background ones. |
| `endpoint` | The configured URL, or `null`. |
| `isConfigured` | Derived from `endpoint`. Do not cache it — a 401 clears the config with no host involvement. |

The headers are deliberately not exposed: they carry your credential, and a property that
hands a bearer token back is a property that ends up in a log.

### Watching what the server said

`syncNow()` returns the outcome of a drain you asked for. `events` covers the ones you did
not — `requestSync()` hands the work to WorkManager, which may run it minutes later in a
process nobody is watching.

```kotlin
lifecycleScope.launch {
    sync.events.collect { event ->
        when (event) {
            is SyncEvent.HttpResponse -> when (event.statusCode) {
                null -> show("No connection — ${event.count} points still queued")
                else -> show("HTTP ${event.statusCode} for ${event.count} points")
            }
        }
    }
}
```

`statusCode` is `null` when no HTTP exchange completed at all — a dead network, DNS failure
or timeout. A device problem and a server problem should not be reported the same way. The
response body is not carried: it can be megabytes, and a host that needs it implements
`SyncTransport` and sees the whole exchange.

### 401 and 403 are both terminal, and they are not the same

| | 401 → `AuthExpired` | 403 → `Forbidden` |
|---|---|---|
| Stops tracking | yes | no |
| Clears the upload queue | yes | **no** |
| Forgets the sync config | yes | yes |
| Recovery | log in again, then `configure()` | `configure()` with a working credential |

A 401 means the credential this data was recorded under is gone, and the next login may be a
different user — so keeping the queue would leak one user's positions into another's session.
A 403 means *this* credential may not write *this* resource: a scope, a rotated key, a
server-side permission bug. That is the same user's data, so it stays on disk and uploads
when you re-configure. Before this existed, a 403 retried forever — the exact silent battery
burn the 401 handling exists to prevent.

### Rate limiting

A `Retry-After` header on any failure response is parsed (both delta-seconds and HTTP-date
forms), clamped to 1 s–6 h, and returned as `SyncQueue.Result.Retry.retryAfterMs`. When the
background worker sees one it re-enqueues at the server's time instead of the SDK's 30-second
backoff. A `Retry-After` seen by your own `syncNow()` call is reported, not acted on — you
own that schedule.

### Timeouts and compression

`SyncConfig.timeouts` overrides the built-in transport's connect/read/write values without
your having to build an `OkHttpClient`. `SyncTimeouts` is a plain data class with no OkHttp
types in it, so it also reaches a custom transport, on `SyncRequest.timeouts`.

`gzipRequestBody` is **off by default and should stay off unless your server expects it**.
There is no negotiation mechanism for request-body encoding — `Accept-Encoding` is a
*response* preference. A client sending `Content-Encoding: gzip` on a POST is simply
asserting it, and a server that does not expect it answers 400 or stores the compressed bytes
as the payload. Bodies under 1 KB are sent uncompressed regardless.

### Background behaviour

`requestSync()` runs under WorkManager: the request is persisted, survives process death and
reboot, and is gated on network availability. `syncNow()` runs in **your** coroutine scope —
an upload started from a `viewModelScope` is cancelled with it. Prefer `requestSync()` for
anything not user-initiated.

### Custom transports

Implement one method, and return a response instead of throwing:

```kotlin
class AppTransport : SyncTransport {
    override suspend fun upload(request: SyncRequest): SyncResponse = try {
        val response = api.upload(request.url, request.headers, request.jsonBody)
        when (response.code) {
            401 -> SyncResponse.Unauthorized
            403 -> SyncResponse.Forbidden
            in 200..299 -> SyncResponse.Success(response.code)
            else -> SyncResponse.Failure(
                code = response.code,
                message = response.message,
                body = response.errorBody?.take(SyncResponse.Failure.MAX_BODY_CHARS),
                retryAfterMs = response.retryAfterSeconds?.times(1_000),
            )
        }
    } catch (error: IOException) {
        SyncResponse.Failure(null, error.message ?: "network failure")
    }
}
```

`OkHttpSyncTransport.defaultClient()` creates the built-in client with 5-second connect,
30-second read, and 20-second write timeouts; `SyncConfig.timeouts` overrides them per
request without replacing the client.

### The URL must be `https://`

`configure()` rejects a cleartext URL rather than accepting it and failing at upload time.
Android blocks cleartext by default from API 28, so an `http://` endpoint surfaces as a
generic network error and retries forever with nothing naming the cause. Loopback hosts
(`localhost`, `127.0.0.1`, `::1`, `10.0.2.2`) are exempt, matching the platform's own default
network security config; anything else needs `allowCleartext = true` deliberately.

Call `SyncConfig.validate()` yourself first if the URL comes from untrusted input — it
returns every problem as a list rather than throwing.

## 14. Road snapping

Historical snapping is disabled until a provider is installed. The shipped implementation
uses OSRM `/match` and intentionally has no default server URL.

```kotlin
trackIt.setRoadSnapProvider(
    OsrmSnapProvider(
        baseUrl = "https://osrm.example.com",
        profile = "driving",
        chunkSize = 80,
        searchRadiusM = 50,
        headers = mapOf("Authorization" to "Bearer $token"),
        minConfidence = 0.6,
        cacheEntries = 32,
    )
)

val snapped = trackIt.buildTrack(
    PointQuery(sessionId = sessionId),
    TrackOptions(snapToRoad = true, snapMaxOffRoadM = 80.0),
)
```

Provider/network failure falls back to raw geometry and reports `snap_unavailable`; it
does not fail the whole track build. `OsrmSnapProvider.defaultClient()` supplies a default
OkHttp client. Implement `RoadSnapProvider.snap(path)` for another vendor; optionally
override `snap(request)` to consume timestamp and accuracy information.

## 15. Advanced geo APIs

Most apps should use `Traker.buildTrack()` rather than calling the engine directly. The
following public pure-engine APIs are useful for custom renderers, offline processing, and
tests:

| API | Purpose |
|---|---|
| `TrackBuilder.build(...)` | Build a `Track` directly from `List<TrackPoint>`. |
| `TrackJson.encode/decode` | Serialize or deserialize Traker's track format. |
| `GeoJson.encode` | Convert a built `Track` to GeoJSON. |
| `PolylineCodec.encode/decode` | Encode/decode `GeoPoint` lists at an explicit precision. |
| `PolylineCodec.Encoder.add/snapshot`, `Decoder.drain` | Incrementally encode or decode an append-only live polyline. |
| `Haversine.metres` | Compute geodesic distance between coordinates. |
| `Bearing.degrees/difference/signedDifference/ofVelocity` | Compute and compare headings. |
| `Geodesy.interpolate/offsetMeters/projectOntoSegment/crossTrackMeters` | Perform route and segment geometry. |
| `Simplify.simplify/simplifyRendered/douglasPeucker` | Reduce redundant geometry. |
| `Spline.smooth/sampleSpan` | Create centripetal Catmull-Rom geometry. |
| `BezierRounding.round` | Round sharp vertices using Bezier curves. |
| `Arrows.place/shouldReplace` | Generate arrow anchors and determine zoom refresh. |
| `ActivityLabels.label/speedBucket/speedBand` | Label segments by activity and speed. |
| `SpeedStats.compute` | Compute robust speed statistics. |
| `SignificantNodes.detect` | Find meaningful stop/turn nodes. |
| `Clusters.build/isInPlaceWander/dwellSec` | Build and classify travel/dwell clusters. |
| `Consolidation.group/consolidate` | Merge stop geometry. |
| `Snapper.snap/closestFrom/projectFrom/spanFor` | Project captured points onto supplied road geometry. |
| `RouteSnap.snap/toleranceFor` | Project a live puck onto an active route. |
| `PuckAnimation.target/smoothedHeading` | Calculate live animation targets/headings. |
| `LiveTrackEngine.onFix/snapshot` | Produce live frames without Android capture. |
| `KalmanFilter.seed/predict/process/predictSigma/speedOf` | Operate the constant-velocity filter directly. |
| `Validation.check` | Validate a `TrackFix` and mock policy. |
| `ClockGuard.classify/inFixOrder` | Classify reboot/out-of-order time and restore monotonic fix order. |
| `AcceptancePipeline.accept` | Judge a fix using explicit state and ingest context. |
| `MotionStateMachine.onEvent` | Apply a motion event to immutable motion state. |
| `TurnDetector.onFix/reset` | Detect turning cadence from fix headings. |
| `FixtureReplay.replay/defaultContext` | Deterministically replay test fixtures. |

Platform ports available for custom implementations are `PointStore`, `Clock`,
`TrackLogger`, and `RoadSnapProvider`. Direct filter/pipeline use is an advanced API: the
caller owns state persistence, monotonic timing, and correct ingest context.

`TrakerArtifacts.of(context)` is the supported seam used by optional modules. It exposes
the shared `trackIt`, `clock`, `logger`, and `pendingUploads` objects. Repository and queue
interfaces are public for adapters and tests, but normal host code should prefer the
corresponding `Traker` and `TrakerSync` methods so invariants remain centralized.

### Java callers

`Traker.getInstance(context)`, the config builder, permission helpers, and value objects
are callable from Java. Most operational methods are Kotlin `suspend` functions and live
surfaces are Kotlin `Flow`; this release does not ship a callback/Future Java facade. A
Java-only host must add its own small Kotlin adapter that launches coroutines and converts
results/flows to the application's callback, `CompletableFuture`, LiveData, or Rx type.

## 16. Production checklist

- Call `ready()` once from application scope and handle `INVALID_CONFIG`.
- Provide a release license token when building for distribution; debug installs are waived.
- Collect `state` and `events` together when the UI must react to provider, motion, or heartbeat changes.
- Use `Traker.state.value.providerState` and `Traker.state.value.motionState` as the current source of truth.
- Request notification, foreground location, background location, and activity recognition
  in the documented order.
- Never start a foreground service solely from an arbitrary background context.
- Collect `events` before starting when early errors must be observed.
- Observe `providerState()` to explain revocation, location-disabled, and power-save states.
- Use `FOREGROUND_ONLY` as a degraded state rather than assuming background continuity.
- Use `GPS_ONLY` or another platform provider on devices without Play Services.
- Configure a valid notification icon resource for production branding.
- Keep raw fix/raw point persistence off unless actively diagnosing; enforce retention.
- Treat location data, exports, logs, and sync headers as sensitive personal data.
- Use TLS, authenticated endpoints, and an application-owned transport when security policy
  requires pinning or shared auth interceptors.
- Self-host or contract an OSRM service; do not depend on a public demo endpoint.
- Call map renderer `clear()` when its map/view lifecycle ends.
- Test swipe-away, reboot, permission revocation, battery saver, offline mode, and at least
  the major OEMs supported by the app.
- Read `Track.precision` and GeoJSON coordinate order (`[longitude, latitude]`).

## 17. Troubleshooting

| Problem | Check |
|---|---|
| `NOT_READY` | Await successful `ready()` before `start()`. |
| `INVALID_CONFIG` | Log the full returned message or call `config.validate()`. |
| `LICENSE_MISSING` | Add `TrakerConfig.license` or `TrackItLicense` meta-data. |
| `LICENSE_INVALID` | Reissue or fix the token payload/signature/key id. |
| `LICENSE_BUNDLE_MISMATCH` | Use a token issued for the current application id. |
| No heartbeat | Collect `events` and record the last `Heartbeat(atMs)` value; it is only emitted while a session is open. |
| Provider state looks stale | Read `Traker.state.value.providerState` and observe `ProviderChange` events together. |
| No points | Check session state, permission tier, provider state, accuracy profile, and decision log. |
| Sparse stationary data | Expected: stationary drift is suppressed and heartbeat fixes normally are not stored. |
| No background capture | Check `FULL` permission tier, visible FGS notification, battery restrictions, and `stopOnTerminate`. |
| No Play Services | Use `LocationProviderType.GPS_ONLY`, `NETWORK_ONLY`, or `PASSIVE`. |
| Poor turns | Enable adaptive cadence, turn burst, bearing-change capture, and spline plotting. |
| Polyline far from expected location | Decode with `track.precision`; use `[lng, lat]` for GeoJSON. |
| Road snapping warning | Verify OkHttp is installed, OSRM URL/TLS/auth, response confidence, and network access. Raw geometry remains usable. |
| Sync always returns `Retry("sync not configured")` | Call `configure()` in the current process before `syncNow()` or `requestSync()`. |
| Missing diagnostic data | Enable the matching persistence flag before recording the session. |
| Live frame appears to move backward | Drop frames whose sequence is not newer, or use `LiveTrackRenderer`. |
| Map camera keeps jumping | Pass `fitCamera = false` after initial render or set live follow mode to `NONE`. |

For deeper permission and platform behavior, see [PERMISSIONS.md](PERMISSIONS.md). For
build, publishing, and verification commands, see [BUILD.md](BUILD.md).
