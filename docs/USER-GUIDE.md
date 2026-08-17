# FieldTrack — User Guide

Everything a host app needs to integrate the SDK, in the order you will need it.

This is the **integration** manual. It documents the SDK as it exists in the code today,
not the intended surface. Where a capability is planned but not shipped, it says so.

| I want to… | Section |
|---|---|
| Add the dependency and get a first point on a map | [1](#1-install) · [2](#2-quick-start) |
| Understand what the SDK does before I trust it | [3](#3-mental-model) |
| Ask for permissions correctly | [4](#4-permissions) |
| Configure it — accuracy, provider, cadence, battery | [5](#5-configuration) |
| Read stored points and build a drawable track | [6](#6-reading-data) · [7](#7-plotting) |
| Draw a live, animated position on a map | [8](#8-live-tracking) |
| React to errors, revocations, motion changes | [9](#9-events--state) |
| Work out why a point is missing or wrong | [10](#10-diagnostics) |
| Upload points to my backend | [11](#11-optional-modules) |
| Use it from Java or React Native | [12](#12-java) · [13](#13-react-native) |
| Fix a problem I am seeing right now | [14](#14-troubleshooting) |

---

## 1. Install

Group `com.github.fieldtrack360.fieldtrack`, version `0.1.1-alpha01`. `minSdk 26`, `compileSdk 37`, JDK 17.

Nothing has been published to a remote repository yet. Until a release is cut, build the
artifacts locally:

```bash
./gradlew publishToMavenLocal
```

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenLocal()          // until a remote is configured
        google()
        mavenCentral()
    }
}
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("com.github.fieldtrack360.fieldtrack:trackit-core:0.1.1-alpha01")   // required
    implementation("com.github.fieldtrack360.fieldtrack:trackit-geo:0.1.1-alpha01")    // pulled in transitively; declare if you use the types directly

    // Optional, add only what you use:
    implementation("com.github.fieldtrack360.fieldtrack:trackit-maps:0.1.1-alpha01")   // Google Maps rendering
    implementation("com.github.fieldtrack360.fieldtrack:trackit-sync:0.1.1-alpha01")   // HTTP upload queue
    implementation("com.github.fieldtrack360.fieldtrack:trackit-snap:0.1.1-alpha01")   // OSRM map-matching
    implementation("com.github.fieldtrack360.fieldtrack:trackit-bridge:0.1.1-alpha01") // Java + JSON facades

    // trackit-sync and trackit-snap declare OkHttp as compileOnly — supply your own:
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
}
```

### What you do *not* have to add

- **No DI framework.** No Hilt, no `@HiltAndroidApp`, no KSP, no Gradle plugin. The object
  graph is wired by hand inside the SDK.
- **No manifest entries.** Every permission, the foreground service and all three receivers
  are declared in the AAR and merge into your APK. What merges in:

  `ACCESS_COARSE_LOCATION`, `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`,
  `ACTIVITY_RECOGNITION` (+ the GMS variant), `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`,
  `ACCESS_NETWORK_STATE`.

  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is deliberately **not** declared — it is
  Play-policy sensitive and must be your explicit choice (EC-15).
- **No ProGuard rules.** `consumer-rules.pro` ships in the AAR.

### Play Services

`LocationProviderType.FUSED` (the default) needs Google Play Services. If you must support
devices without it — Huawei, AOSP builds — see [§5.3](#53-provider-type) for `GPS_ONLY`,
which runs on the platform `LocationManager` and needs nothing from Google.

---

## 2. Quick start

Three calls. `getInstance` → `ready` → `start`.

```kotlin
class SampleApplication : Application() {

    val trackIt: TrackIt by lazy { TrackIt.getInstance(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            trackIt.ready(TrackItConfig())
        }
    }
}
```

```kotlin
// In a ViewModel, after permissions are granted:
suspend fun begin() {
    when (val result = trackIt.start(tag = "commute")) {
        is TrackItResult.Ok -> Log.d("app", "session ${result.value.id}")
        is TrackItResult.Error -> Log.w("app", "${result.code}: ${result.message}")
    }
}

suspend fun end() {
    trackIt.stop()
}
```

```kotlin
// Later — a ready-to-draw track:
val track = trackIt.buildTrack(PointQuery(sessionId = sessionId))
val json = trackIt.exportPolylineJson(PointQuery(sessionId = sessionId))
```

### The three calls, precisely

| Call | What it does | When |
|---|---|---|
| `TrackIt.getInstance(context)` | Returns the process-wide instance. Idempotent, thread-safe, cheap — the graph is lazy, so this opens no database and touches no disk. Safe to pass an Activity; the application context is what is retained. | Anywhere |
| `ready(config)` | Resolves the effective config, restores persisted filter state, enqueues the retention worker, and reports a session left open by a crash. | Once, in `Application.onCreate` |
| `start(tag)` | Opens a session and starts the capture pipeline. Idempotent while already tracking — a double tap returns the existing session rather than splitting a drive in two. | After permissions |

Call `ready()` from `Application.onCreate`, not from an Activity: it restores filter state
and reports an interrupted session, and both should happen before any UI exists to observe
them.

### Nothing throws

Every fallible entry point returns `TrackItResult<T>`:

```kotlin
public sealed interface TrackItResult<out T> {
    public data class Ok<T>(val value: T) : TrackItResult<T>
    public data class Error(val code: ErrorCode, val message: String) : TrackItResult<Nothing>
}
```

An SDK that throws into a host's coroutine is a crash the host cannot reasonably prevent.
The one deliberate exception is `TrackItConfig.Builder.build()`, which runs on your own
thread while you assemble a value — see [§5.1](#51-the-builder).

---

## 3. Mental model

Worth ten minutes before you configure anything, because most integration problems are a
misunderstanding of one of these five facts.

### 3.1 A fix is not a point

The OS delivers *fixes*. The SDK stores *points*. Between them sits a seven-stage
acceptance pipeline over a constant-velocity Kalman filter, and it drops most fixes on
purpose.

```
OS fix ──▶ FixMapper ──▶ ClockGuard ──▶ TurnDetector ──▶ AcceptancePipeline ──┬──▶ stored point
           (validity)     (reboot,        (cadence)       (7 stages)          │
                           reordering)                                        └──▶ decision log
```

Every fix gets exactly one of three verdicts:

| Verdict | Meaning |
|---|---|
| `Accept` | Stored, emitted as `TrackItEvent.Location` |
| `Skip` | The filter learned from it; nothing stored |
| `Reject` | Dropped entirely |

**A user sitting still for two hours produces one point, not a drift cloud.** That is the
design working, not a bug. If you expected a point per interval, see
[§14](#14-troubleshooting).

### 3.2 Everything belongs to a session

`start()` opens a session; `stop()` closes it. Points, decisions and raw layers are all
keyed by session id. The session also carries a **snapshot of the config that was in
effect**, so a track recorded six weeks ago can still be interpreted after you changed the
config.

Every `start()` is a new session. A session left open by process death is closed first, so
two runs never share an id.

### 3.3 Motion state drives cadence, never capture

```
STOPPED ──▶ MOVING ⇄ STOP_PENDING ⇄ STATIONARY
```

Activity recognition, hardware speed and a stationary geofence feed this machine. It
changes **how often** location is sampled. It never gates whether a fix is captured —
entire 17-minute drives are reported `STILL` on some OnePlus and Xiaomi devices under
battery saver, and gating capture on that would lose the whole trip (EC-53).

### 3.4 Cadence has four tiers

Fastest wins:

| Tier | Default | When |
|---|---|---|
| Navigation | 1 s | `navigationMode = true` — outranks everything |
| Turn burst | 4 s | `TurnDetector` says the vehicle is measurably turning |
| Vehicular | 12 s | Motion state says vehicular, `adaptiveCadence = true` |
| Normal | 60 s | Everything else |

### 3.5 Stored points are raw; the puck is filtered

Stored `TrackPoint` coordinates are the fix's own coordinates, deliberately — the record
says where the device was measured, not where a filter believed it was. The filter's
estimate is exposed separately, as `PuckState` on the live feed ([§8](#8-live-tracking)),
which is the display-side use it always deserved.

---

## 4. Permissions

**The SDK shows no UI.** No dialogs, no activities, no full-screen intents. It answers
questions and hands you permission arrays and a Settings intent. You own every prompt.

```kotlin
val permissions = trackIt.permissions()   // PermissionManager
```

### 4.1 The ladder, in order

Ask in this order. Each step has a reason.

```kotlin
// Step 0 — API 33+. Ask FIRST. An invisible foreground-service notification is a
// transparency failure and an OEM-kill risk (EC-08).
launcher.launch(permissions.notificationPermissions())

// Step 1 — fine + coarse together, in ONE request. Adding background to this array
// makes Android deny it silently (EC-04).
launcher.launch(permissions.foregroundPermissions())

// Step 2 — background, only after fine is granted and you have shown a rationale.
when (val request = permissions.backgroundRequest()) {
    is PermissionManager.BackgroundRequest.AlreadyGranted -> Unit
    is PermissionManager.BackgroundRequest.NotApplicable -> Unit          // API < 29
    is PermissionManager.BackgroundRequest.NeedsForegroundFirst -> askForeground()
    is PermissionManager.BackgroundRequest.Prompt -> launcher.launch(request.permissions)
    is PermissionManager.BackgroundRequest.NeedsSettings -> startActivity(request.intent)
}

// Step 3 — optional. Denial degrades motion detection to speed + displacement (EC-09).
launcher.launch(permissions.activityRecognitionPermissions())
```

From Android 11 the OS will not show a background-location prompt at all, which is why
`NeedsSettings` exists: a runtime request there appears to do nothing. Deep-link to
Settings and explain "Allow all the time" in your own words.

### 4.2 Don't prompt-loop

```kotlin
if (permissions.shouldStopAsking(attempts)) {
    // Only offer the Settings route from here on. Cap is 3.
    startActivity(permissions.appSettingsIntent())
}
```

### 4.3 Tiers, and what each one allows

| `permissions.tier()` | `start()` behaviour |
|---|---|
| `NONE` | Returns `Error(PERMISSION_DENIED)` |
| `FOREGROUND_ONLY` | **Starts anyway.** Tracks while the app is visible; emits `Error(BACKGROUND_PERMISSION_MISSING)` so you can tell the user. Degrade, don't refuse (EC-03) |
| `FULL` | Full background tracking |

Separately, `permissions.accuracy()` returns `APPROXIMATE` or `PRECISE`. A 1–3 km error
circle defeats every gate in the pipeline, so `start()` refuses `CONTINUOUS` and `ADAPTIVE`
under approximate-only with `Error(COARSE_ONLY)`. `MOTION_ONLY` is allowed.

### 4.4 Revocation mid-session

A permission can be revoked while the foreground service is running. The SDK watches
`AppOpsManager` and reacts immediately — no polling. On a downgrade the stream stops but
the **session stays open**: whether to end it is your decision, never a side effect of a
permission toggle. You get `TrackItEvent.Error(BACKGROUND_PERMISSION_MISSING)` and a
`ProviderChange`.

---

## 5. Configuration

`TrackItConfig` has five blocks: `geolocation`, `motion`, `service`, `persistence`,
`sensors` — plus `reset`.

### 5.1 The builder

```kotlin
val config = TrackItConfig.builder()
    .provider(LocationProviderType.GPS_ONLY)
    .accuracyProfile(AccuracyProfile.STRICT)
    .trackingMode(TrackingMode.ADAPTIVE)
    .intervalMs(60_000)
    .useStepCorroboration(true)
    .notification("Recording your route", "Tap to open")
    .maxDaysToPersist(7)
    .build()

trackIt.ready(config)
```

`build()` runs `validate()` and throws `IllegalArgumentException` on failure. That is the
one fail-fast entry point in the SDK, deliberately: it runs on your own thread while you
assemble a value, which is where fail-fast belongs. `buildUnchecked()` returns the same
value unvalidated if you are assembling config from untrusted input and would rather read
`validate()` yourself.

The Kotlin data-class constructor is unchanged and still idiomatic:

```kotlin
TrackItConfig(
    geolocation = GeolocationConfig(trackingMode = TrackingMode.CONTINUOUS),
    persistence = PersistenceConfig(persistRawPoints = true),
)
```

Use the builder from Java, or when you want to set two knobs without naming five nested
classes.

### 5.2 The `reset` flag — read this one

```kotlin
val reset: Boolean = true
```

- `true` (default) — `ready()` applies your config on top of factory defaults.
- `false` — the **persisted** config wins, and your object is ignored after the first
  launch.

Leave it `true` during development. A `false` here with edited constants is the classic
"my config changes do nothing" bug.

### 5.3 Provider type

Which hardware actually produces the fixes.

```kotlin
.provider(LocationProviderType.FUSED)   // default
```

| Value | Backing | Use when |
|---|---|---|
| `FUSED` | Play Services fused provider | Almost always. Blends GNSS, Wi-Fi, cell and device sensors; best time-to-first-fix, works indoors |
| `GPS_ONLY` | `LocationManager.GPS_PROVIDER` | No Play Services on the device, or a Wi-Fi centroid must never reach the record |
| `NETWORK_ONLY` | `LocationManager.NETWORK_PROVIDER` | Coarse, cheap positioning is enough |
| `PASSIVE` | `LocationManager.PASSIVE_PROVIDER` | You want zero additional battery cost and will take whatever other apps already requested |

The three non-fused values run on the platform `LocationManager` and **need no Play
Services at all**.

Costs, stated rather than discovered:

- **`GPS_ONLY`** — no fix at all indoors, in a car park or in a tunnel. Cold starts of
  30–60 s. Materially more battery than fused at the same interval, because there is no
  cached fix to hand back.
- **`NETWORK_ONLY`** — 20–2000 m accuracy. Needs a matching accuracy ceiling; see below.
- **`PASSIVE`** — cadence, provider and accuracy are all whatever some other app asked for.
  On a device where nothing else is tracking, it delivers nothing. Every cadence tier is
  inert, so `navigationMode` is refused.
- **Platform sources do not batch.** `LocationManager` has no `maxUpdateDelay`, so the
  Doze-window battery win that batching buys is unavailable, and `waitForAccurateLocation`
  does not exist — the first fix after registration arrives as-is.

`desiredAccuracy` is a **different** question: it biases the *fused* provider's choice among
the sources it has, and cannot exclude any of them. `HIGH` → `PRIORITY_HIGH_ACCURACY`,
`BALANCED` → `PRIORITY_BALANCED_POWER_ACCURACY`, `LOW` → `PRIORITY_LOW_POWER`.

### 5.4 The accuracy meter

How good a fix must be before it earns a stored point.

```kotlin
.accuracyProfile(AccuracyProfile.STRICT)     // named point
.maxAccuracyMeters(35f)                      // exact number; implies CUSTOM
.recoveryTrustMeters(18f)                    // optional: post-gap re-anchor bar
```

| Profile | Moving ceiling | Re-anchor bar | Character |
|---|---|---|---|
| `STRICT` | 20 m | 15 m | Sparser track, no zigzag. Urban-canyon safe |
| `BALANCED` | 30 m | 25 m | **Default.** The engine's shipped constants, byte-identical |
| `RELAXED` | 60 m | 40 m | More points, visible wander in poor conditions |
| `CUSTOM` | `maxAccuracyMeters`, 5–500 m | clamped ≤ ceiling | Whatever you set |

**What the ceiling actually is.** A bound on the reported error radius of a fix claimed to
be *moving* — the one unconditional bound in the pipeline. Every other accuracy limit in
the engine is conditional on a motion class that the fix's own displacement helps decide,
which is circular: a 66 m positioning error computes as ~14 m/s, ~14 m/s reads as
vehicular, and vehicular carries the loosest ceiling there is. That circle is how a field
capture stored a 153 m spike and a 173° reversal inside an ordinary city drive (EC-139).

**Two numbers, not one.** The re-anchor bar is separate and stricter because the first fix
after a signal blackout is the least corroborated fix of the session and the most
consequential — every later fix is judged from wherever it lands. A fix above the bar is
*held*, warmed into the filter and stored nowhere, until a second fix lands nearby and
agrees (EC-140).

**Stationary fixes are exempt by design.** They are handled by the anchor and wobble
defences, which treat a poor fix as something to freeze against rather than something to
plot. Tightening this to fix stationary drift is tuning the wrong stage.

**What the meter does not touch.** Classification ceilings (`accuracyHigh`,
`accuracyMedium`, `accuracyStationaryLimit`, `accuracyMaxVehicular`) and the whole
sigma-gate family are left alone: those classify a fix rather than admit it, and dragging
them along would re-tune the motion state machine as a side effect of a storage decision.

**Two combinations are refused by `validate()`:**

- `NETWORK_ONLY` under a ceiling below 50 m — a GNSS-calibrated ceiling rejects every fix a
  Wi-Fi/cell centroid can produce, and the symptom would be an empty track with no error
  anywhere. Pair `NETWORK_ONLY` with `RELAXED` or a `CUSTOM` value ≥ 50 m.
- `PASSIVE` with `navigationMode`.

Under `NETWORK_ONLY` the SDK also lifts its 25 m network-fix rejection to your own ceiling.
That bound exists because on a fused stream a network fix is what a Wi-Fi teleport arrives
as; on `NETWORK_ONLY` it is what *every* fix arrives as.

### 5.5 Tracking mode

```kotlin
.trackingMode(TrackingMode.ADAPTIVE)
```

| Mode | Behaviour | Battery | Stop timing |
|---|---|---|---|
| `CONTINUOUS` | Stream always; the filter does all thinning | Highest | Best |
| `ADAPTIVE` | **Default.** Stream while moving with adaptive cadence, heartbeat-only while stationary | Middle | Good |
| `MOTION_ONLY` | Location fully off while stationary | Lowest | Coarsest |

On a device whose `motionQuality` is `POOR`, `ready()` forces `CONTINUOUS` and emits
`Error(MOTION_DETECTION_DEGRADED)` naming the missing sensors — running a motion-gated
design on hardware that cannot support motion detection produces gaps users blame on the
SDK (EC-137).

### 5.6 Cadence and turn fidelity

```kotlin
.intervalMs(60_000)              // normal tier
.fastestIntervalMs(30_000)       // OS floor; must be <= intervalMs
.maxUpdateDelayMs(60_000)        // OS batching window
.adaptiveCadence(true)
.vehicularIntervalMs(12_000)     // while vehicular
.turnBurst(true)
.turnBurstIntervalMs(4_000)      // while measurably turning
.bearingChangeCaptureDeg(40)     // store on a heading change this large
```

**`distanceFilterM` must stay `0`, and `validate()` enforces it.** A non-zero OS distance
filter is a stationary-drift *generator*: the OS only wakes you when noise exceeds the
filter, so every update looks like movement. All thinning is done in software, deliberately
(EC-119).

Turn geometry is handled five ways, all offline and all on by default: adaptive cadence,
the turn-burst tier, bearing-change force-capture, cornering process noise in the filter,
and a centripetal Catmull-Rom spline at plot time.

`turnBurstIntervalMs` must be ≤ the tier it accelerates, or the "faster" tier is slower than
what it replaces and quietly makes turn geometry worse. `validate()` checks it.

### 5.7 Navigation mode

~1 Hz, high accuracy, OS batching off, overriding every adaptive tier.

```kotlin
.navigationMode(true)
.navigationIntervalMs(1_000)
.navigationFastestIntervalMs(500)
.foregroundService(true)          // REQUIRED
```

Requires `service.foregroundService`; `validate()` refuses the combination otherwise. A
1 Hz stream without a foreground service is throttled or killed at the first backgrounding,
which presents as "navigation randomly stops" and is invisible in any log you would think
to read.

While on, `desiredAccuracy` is ignored (forced high) and `maxUpdateDelayMs` is ignored
(batching off) — a single batching window would hold more fixes than the animation they
feed.

### 5.8 Motion and stop detection

```kotlin
.activityRecognition(true)
.activityConfidenceMin(75)
.stopOnStationary(false)          // call stop() automatically on the stop timeout
.stopTimeoutMin(5)
.stationaryRadiusM(150f)
.stationaryGeofenceId("trackit-stationary")
.stationaryGeofenceOnEnterEvent("stationary_fence_enter")
.stationaryGeofenceOnExitEvent("stationary_fence_exit")
.heartbeatIntervalSec(900)
.persistHeartbeat(false)
```

`stationaryGeofenceId` is the unique id for the system fence the SDK arms while the
device is stationary. `stationaryGeofenceOnEnterEvent` and
`stationaryGeofenceOnExitEvent` are the event labels the host sees when that fence is
entered or exited.

`heartbeatIntervalSec` is the **data-plane** heartbeat: it warms the filter and is not
stored. That is what makes a two-hour steady user produce exactly one point. It must be at
least 5× the sampling interval or it fires every fix and defeats stationary suppression
entirely; `validate()` checks it (EC-121).

Distinct from `TrackItEvent.Heartbeat`, which is the control-plane liveness signal.

### 5.9 Sensors

Registered only while a session is active — a pedometer left registered after a session is
battery drain with nothing to show for it (EC-138).

```kotlin
.useSignificantMotion(true)    // permission-free, ~zero-power hardware wake STATIONARY→MOVING
.useStepCorroboration(true)    // step-count veto on stationary drift; confirms indoor walks
.useAccelerometerVeto(true)    // 1 s burst to make the phantom-Doppler correction certain
.useBarometer(false)           // pressure delta distinguishes an elevator from a teleport
.stepBatchLatencyMs(60_000)    // sensor-hub batching, so the AP never wakes for step events
```

Probe what the device actually has:

```kotlin
val sensors: DeviceSensors = trackIt.getSensors()
// accelerometer, gyroscope, magnetometer, significantMotion,
// stepDetector, stepCounter, barometer, rotationVector, motionQuality
```

### 5.10 Service and notification

```kotlin
.foregroundService(true)
.stopOnTerminate(false)        // inverted from the incumbent, on purpose
.startOnBoot(true)             // ditto
.notification("Tracking active", "Recording your location")
.notificationChannel("trackit_tracking", "Location tracking")
.notificationSmallIconResName("ic_stat_track")
.wakeLockMs(20_000)
.backstopIntervalMin(15)
```

`stopOnTerminate = false` and `startOnBoot = true` are both inverted from the incumbent SDK
deliberately: an SDK whose purpose is surviving termination should not ship defaults under
which a swipe-away silently ends tracking, and that failure is invisible until the data is
missing (EC-125).

### 5.11 Persistence and retention

```kotlin
.maxDaysToPersist(7)           // 0 disables TTL pruning
.maxRecords(0)                 // 0 = unbounded
.persistDecisions(true)
.decisionRetentionDays(3)
.decisionMaxRows(50_000)
.persistRawFixes(false)        // debug layer 1
.persistRawPoints(false)       // debug layer 3
```

Pruning runs daily via WorkManager, enqueued by `ready()` and independent of any session.

### 5.12 Full validation list

`ready()` returns `Error(INVALID_CONFIG)` with every problem joined, and `Builder.build()`
throws with the same text. What is checked:

| Rule | Code |
|---|---|
| `intervalMs >= fastestIntervalMs` | EC-120 |
| `distanceFilterM == 0` | EC-119 |
| `heartbeatIntervalSec >= 5 × interval` | EC-121 |
| `turnBurstIntervalMs > 0` and ≤ the tier it accelerates | EC-45 |
| `navigationIntervalMs > 0`, ≥ its fastest, requires `foregroundService` | — |
| `maxDaysToPersist >= 0` | — |
| `CUSTOM` profile requires `maxAccuracyMeters` in 5–500 m | — |
| `maxAccuracyMeters` set against a named profile is rejected, not ignored | — |
| `NETWORK_ONLY` requires a ceiling ≥ 50 m | EC-32 |
| `PASSIVE` cannot use `navigationMode` | — |

### 5.13 Changing config later

There is **no `setConfig()` on `TrackIt` yet** — `docs/API.md` §10 documents it as intended
surface, and it is not implemented. Today, config is applied at `ready()` and the
provider/accuracy parts are re-applied at each `start()`. To change config, call `ready()`
again with `reset = true` before the next `start()`.

---

## 6. Reading data

All reads are paged. `PointQuery` defaults to `limit = 500`.

```kotlin
public data class PointQuery(
    val sessionId: String? = null,
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val limit: Int = 500,
    val offset: Int = 0,
)
```

```kotlin
val points: List<TrackPoint> = trackIt.getPoints(PointQuery(sessionId = id))
val count: Int = trackIt.getCount(PointQuery(sessionId = id))
val metres: Double = trackIt.getOdometerMeters()

val sessions: List<TrackSession> = trackIt.getSessions(fromMs, toMs)
val open: TrackSession? = trackIt.currentSession()

// Live, from Room:
trackIt.observePoints(sessionId).collect { points -> render(points) }
```

### `TrackPoint`

```kotlin
public data class TrackPoint(
    val id: Long, val uuid: String, val sessionId: String,
    val timeMs: Long,                   // wall clock — display, day bucketing
    val elapsedRealtimeNanos: Long,     // monotonic — real observation time
    val localDate: String,              // yyyy-MM-dd, in the point's own zone
    val timezone: String,               // IANA id, per point (a session can cross zones)
    val latitude: Double, val longitude: Double,
    val accuracy: Float, val altitude: Double?,
    val speedMps: Float, val bearingDeg: Float,
    val hasSpeed: Boolean, val hasBearing: Boolean,   // read these, never assume
    val provider: String, val isMock: Boolean,
    val movementStatus: MovementStatus,               // STEADY | MOVING
    val detectedActivity: ActivityType?,              // enrichment only
    val activityStartTimeMs: Long,
    val odometerMeters: Double,
    val batteryPct: Int?, val isCharging: Boolean?,
    val extras: String?,
    val acceptReason: String,                         // the Reasons vocabulary
)
```

`hasSpeed` / `hasBearing` matter: `speedMps` is `0f` when the provider reported none, and
`0f` is a legal speed. Defaulting these to `true` silently disables the network-fix
rejection, which is the main defence against Wi-Fi teleports.

### `TrackSession`

```kotlin
public data class TrackSession(
    val id: String,
    val startedAtMs: Long,
    val startedAtElapsedNanos: Long,
    val endedAtMs: Long?,          // null while open
    val tag: String?,
    val configSnapshot: String?,   // the config this session ran under, as JSON
) { val isOpen: Boolean }
```

---

## 7. Plotting

`buildTrack()` is the headline deliverable: a ready-to-draw track, computed entirely
on-device. No backend, no routing key, no quota.

```kotlin
val track: Track = trackIt.buildTrack(
    query = PointQuery(sessionId = id),
    options = TrackOptions(zoom = 14f),
)
```

### What comes back

```kotlin
public data class Track(
    val version: Int,                     // format version, currently 1
    val sessionId: String?,
    val generatedAtMs: Long,
    val from: Long, val to: Long,
    val timezone: String,
    val precision: Int,                   // encoded-polyline precision — READ IT, don't assume 5
    val bounds: Bounds?,                  // null, never NaN-filled, when there are no points
    val stats: TrackStats,
    val encodedPolyline: String,
    val points: List<TrackJsonPoint>,     // `i` is the index every other array references
    val segments: List<TrackSegment>,     // travel/stop spans with speed bands
    val stops: List<StopNode>,            // consolidated dwells
    val arrows: List<ArrowAnchor>,        // precomputed direction anchors with bearings
    val warnings: List<String>,
)
```

`precision` defaults to 6, not the more common 5. A consumer that hardcodes 5 against a
6-precision track puts the user in the wrong hemisphere (EC-110).

`warnings` is an open string set — `snap_unavailable`, `coarse_accuracy`,
`mock_locations_present`, `truncated`, `session_interrupted`. **Nothing is ever silently
dropped**; anything omitted is named here.

`StopNode.isOngoing` means the session is still open and dwell was computed against the
wall clock at build time — pulse that marker rather than showing a fixed duration (EC-111).

### `TrackOptions`

```kotlin
TrackOptions(
    zoom = 14f,                       // selects the arrow spacing tier
    consolidateStops = true,
    stopRadiusM = 60.0,
    stopMinDwellSec = 600,
    smoothing = Smoothing.SPLINE,     // NONE | BEZIER | SPLINE
    splineSpacingM = 5.0,
    simplifyEpsilonM = 2.0,           // Douglas-Peucker before smoothing; 0 disables
    snapToRoad = true,                // no-op unless a RoadSnapProvider is installed
    snapMaxOffRoadM = 80.0,
    polylinePrecision = 6,
    speedBandsKmph = listOf(10f, 20f),
    arrowMinSegmentM = 60.0,
)
```

`Smoothing.SPLINE` (default) is centripetal Catmull-Rom through every vertex, resampled.
`BEZIER` only rounds vertices sharper than `bezierMinAngleDeg` and cannot do anything about
a 120 m leg drawn as a chord, which is the usual complaint. `NONE` gives you exactly the
stored vertices.

### Export formats

```kotlin
val polylineJson: String = trackIt.exportPolylineJson(query, options)  // POLYLINE-JSON.md §1
val geoJson: String = trackIt.exportGeoJson(query, options)            // RFC 7946, [lng, lat]
```

### Drawing it with `trackit-maps`

```kotlin
val renderer = TrackRenderer(googleMap, TrackRenderer.RendererOptions(
    basePathWidth = 16f,
    showArrows = true,
    showStopMarkers = true,
))

renderer.render(track, fitCamera = true)   // pass false once the user has panned

if (renderer.needsArrowRefresh()) renderer.render(track, fitCamera = false)

renderer.clear()   // when the map goes away
```

`TrackRenderer` consumes the same `Arrows.place()` the JSON export uses, so the drawn track
and the exported track cannot disagree.

Drawing it yourself, from any map library:

```kotlin
val path = PolylineCodec.decode(track.encodedPolyline, track.precision)
track.arrows.forEach { addMarker(it.lat, it.lng, rotation = it.bearing.toFloat()) }
track.stops.forEach { addStopPin(it.lat, it.lng, it.dwellSec) }
```

### Road snapping (optional)

```kotlin
trackIt.setRoadSnapProvider(
    OsrmSnapProvider(
        baseUrl = "https://osrm.example.com",   // no default — point at your own deployment
        profile = "driving",
        minConfidence = 0.6,
    )
)
```

Install it **before** the first `buildTrack()`: a provider set afterwards leaves that track
unsnapped and every later one snapped, which looks like a bug in the SDK rather than a race
in your app.

Every failure degrades to raw geometry plus a `snap_unavailable` warning and an
`Error(SNAP_UNAVAILABLE)` event. Losing a day's track because a routing service was
rate-limited is not a trade any host would choose (EC-100). Snapping never touches stored
points — the route is a claim about where the user intended to go, and writing it into the
record would fabricate evidence.

---

## 8. Live tracking

`buildTrack()` is a product, built on demand. `liveTrack()` is a **feed**: one frame per
processed fix, cheap enough to emit continuously.

```kotlin
trackIt.liveTrack().collect { update ->
    if (update.sequence <= lastSequence) return@collect   // flows across dispatchers can reorder
    lastSequence = update.sequence

    renderer.render(update)
}
```

```kotlin
public data class LiveTrackUpdate(
    val sessionId: String,
    val sequence: Long,               // monotonic per session run — check before drawing
    val precision: Int,
    val frozenTailPolyline: String,   // settled spans; append-only, NEVER re-smooth it
    val liveHead: List<GeoPoint>,     // the unsettled last span, redrawn wholesale
    val puck: PuckState?,             // the filter's own estimate; null until seeded
)

public data class PuckState(
    val latitude: Double, val longitude: Double,
    val speedMps: Float,
    val headingDeg: Double?,          // null when velocity is too small to have a direction
    val accuracyM: Float,             // 1σ uncertainty — the honest radius for a halo
)
```

The feed is **conflated**: collectors always see the latest frame and can never slow capture
down. `liveHead`'s first vertex is the tail's last, so the two polylines join by
construction.

When `headingDeg` is `null`, hold your last rotation — never snap to a fabricated 0°.

With `trackit-maps`:

```kotlin
val live = LiveTrackRenderer(googleMap)
trackIt.liveTrack().collect(live::render)
live.clear()
```

### Route snapping for the puck

The cheapest trick in navigation rendering, and most of why a well-known blue dot never
wobbles off the road during turn-by-turn: the puck is not matched against the road network,
it is projected onto the one polyline your app is already following. Entirely offline — no
provider, no key, no quota.

```kotlin
trackIt.setActiveRoute(routePoints)      // empty list clears it
if (trackIt.isOffRoute()) offerReroute()
```

Only the live puck moves. Stored points and `buildTrack()` are untouched.

---

## 9. Events & state

### State

```kotlin
trackIt.state.collect { state ->
    // isReady, isTracking, motionState, providerState, currentSessionId
}
```

### Events

`SharedFlow`, replay 0, unlimited subscribers — never a `var callback`, which silently lets
the second registrant replace the first (EC-112). Collect from a lifecycle scope for UI, or
an application scope for work that must continue with no UI on screen.

```kotlin
trackIt.events.collect { event ->
    when (event) {
        is TrackItEvent.Location -> onPoint(event.point)
        is TrackItEvent.LocationRejected -> log(event.decision.reason)
        is TrackItEvent.MotionChange -> onMotion(event.state, event.point)
        is TrackItEvent.ActivityChange -> onActivity(event.activity, event.confidence)
        is TrackItEvent.EnabledChange -> onTrackingToggled(event.enabled)
        is TrackItEvent.ProviderChange -> onProviderState(event.state)
        is TrackItEvent.PowerSaveChange -> onPowerSave(event.enabled)
        is TrackItEvent.Heartbeat -> onAlive(event.atMs)
        is TrackItEvent.SessionInterrupted -> offerResume(event.session)
        is TrackItEvent.Diagnostic -> log(event.message)
        is TrackItEvent.Error -> onError(event.code, event.message)
    }
}
```

`SessionInterrupted` fires from `ready()` when a session was found still open at launch —
a crash or force-stop. The SDK does not decide what to do with it; you do (EC-66).

### Battery

```kotlin
val battery = trackIt.batteryInfo()   // now; no session, no permission, no ready() needed
battery.percent       // 0..100, or null when the platform will not say
battery.isCharging    // true / false / null
battery.powerSource   // NONE, AC, USB, WIRELESS, DOCK, UNKNOWN
battery.isLow         // percent != null && percent <= 15

trackIt.batteryState().collect { battery -> render(battery) }
```

`TrackItEvent.BatteryChange` carries the same transitions on the event flow. Events fire on
plug, unplug, low and okay — plus whatever drift the capture path notices while a session
runs — never on a timer, and never when the reading has not changed.

A null percentage means "the platform would not say", never 0 %. The same reading is stamped
on every stored and uploaded point, so a display and its rows cannot disagree.

### Provider state

```kotlin
trackIt.providerState().collect { state ->
    // gpsEnabled, networkEnabled, permission, accuracyAuthorization,
    // fusedAvailable, powerSaveMode
}
```

Updated by broadcast and by `AppOpsManager`, never polled. `fusedAvailable` answers "is Play
Services here" — a fact about the device, useful for deciding whether to switch to
`GPS_ONLY`.

### Error codes

| Code | Meaning | Fatal to `start()` |
|---|---|---|
| `NOT_READY` | `start()` before `ready()` | Yes |
| `PERMISSION_DENIED` | No location permission at all | Yes |
| `BACKGROUND_PERMISSION_MISSING` | Foreground-only grant, or background revoked mid-session | No — degrades |
| `COARSE_ONLY` | Approximate-only under `CONTINUOUS`/`ADAPTIVE` | Yes |
| `LOCATION_DISABLED` | User turned location services off | — |
| `PLAY_SERVICES_UNAVAILABLE` | Selected provider not present. The message names the remedy | Yes |
| `FGS_START_REFUSED` | OS refused the foreground service | — |
| `NOTIFICATION_HIDDEN` | The FGS notification is not visible | — |
| `FIX_TIMEOUT` | One-shot produced nothing | — |
| `STORAGE_FULL` / `STORAGE_RESET` | Database problems | — |
| `TRACKER_DEAD` | Watchdog saw no fixes for too long | — |
| `INVALID_CONFIG` | `validate()` failed; message lists every problem | Yes |
| `MOTION_DETECTION_DEGRADED` | `motionQuality = POOR`; mode forced to `CONTINUOUS` | No |
| `SNAP_UNAVAILABLE` | Road snapping failed. **Never fatal** — raw geometry is returned | No |
| `NO_ACTIVITY` | — | — |
| `INTERNAL` | Something threw where the contract says nothing throws. This is a bug in the SDK | — |

---

## 10. Diagnostics

Three layers, for three different questions.

| Layer | Call | Answers | Config |
|---|---|---|---|
| 1 — raw fixes | `getRawFixes(sessionId)` | "What did the OS actually deliver?" | `persistRawFixes = true` |
| 2 — decisions | `getDecisions(sessionId, limit, offset)` | "Why was this fix rejected?" | `persistDecisions = true` (default) |
| 3 — raw points | `getRawPoints(sessionId)` | "Why is there no point *here*?" | `persistRawPoints = true` |

```kotlin
val decisions: List<FixDecision> = trackIt.getDecisions(sessionId, limit = 200)

decisions.filterNot { it.isAccept }.forEach {
    Log.d("trackit", "${it.reason}  σ=${it.sigma} thr=${it.threshold} " +
        "moved=${it.distanceMovedM}m at ${it.effectiveSpeedMps}m/s (${it.motionState})")
}
```

`sigma` and `threshold` are on the record so a `Sigma Gate Outlier` can be argued with: they
show exactly how wide the gate was and by how much the fix missed.

Layer 3 comes back in the same shape as `getPoints()`, so a rejected candidate and the
points either side of it can be read side by side. `RawPoint.uuid` joins back to the stored
`TrackPoint` for the ones that were accepted. It is one wide row per fix — real write
amplification at a 12 s cadence — which is why it is off by default.

### Reason vocabulary

These strings **are API** — every fixture assertion keys on them, and changing one is a
breaking change.

| Group | Reasons |
|---|---|
| Lifecycle | `Init`, `Resume`, `Session Closed`, `Reboot Boundary`, `Out Of Order` |
| Timing | `Burst`, `Stale Fix`, `15-Min Heartbeat`, `HeartBeat Skipped` |
| Accept | `Vehicular`, `Moving/Walking`, `Bearing Change`, `Arrival`, `Indoor Arrival`, `Walk Arrival`, `Blackout Arrival`, `Stationary Recovery` |
| Reject | `Poor Accuracy`, `Impossible Speed`, `Sigma Gate Outlier`, `Sigma Junk Fail`, `Mock Location`, `Invalid Coordinates`, `Heuristic Gate` |
| Recovery | `Recovery Confirmed`, `Recovery Reset`, `Recovery Held`, `Sigma Forced Reset` |
| Stationary | `Origin Set`, `Departure Held`, `Drift Suppressed` |
| Network fix | `NLP Fallback` |

### Injecting a fix

```kotlin
trackIt.offerFix(trackFix)
```

Judged by exactly the same gates as a live fix — you cannot inject an unvalidated point
(EC-86). Useful for replay and for a custom provider.

---

## 11. Optional modules

### `trackit-sync` — HTTP upload

`trackit-core` never opens a socket. This artifact does; an app that does not depend on it
gets an offline-first SDK with no network code linked at all.

```kotlin
val sync = TrackItSync.getInstance(context)

sync.configure(
    SyncConfig(
        url = "https://api.example.com/v1/points",   // https, or configure() throws
        method = "POST",
        headers = mapOf("Authorization" to "Bearer $token"),
        autoSync = true,
        batchSize = 100,
        requiresUnmeteredNetwork = false,
        gzipRequestBody = false,                     // opt-in; most servers reject it
        timeouts = SyncTimeouts(readMs = 30_000),    // no OkHttpClient needed
    ),
    // Omit to use the OkHttp default. Supply your own to reuse an authenticated
    // client — then OkHttp is never linked by this module.
    transport = null,
)

sync.requestSync()                       // network-constrained one-shot; safe to call often
val result = sync.syncNow()              // drains inline, returns what happened
val pending = sync.pendingCount()

sync.endpoint                            // where uploads go, or null
sync.isConfigured                        // derived from endpoint; do not cache it
sync.events                              // one HttpResponse(statusCode, count) per exchange
```

Two terminal statuses, with deliberately different consequences. On **401** the queue tears
down: tracking stops, the queue is cleared, the config is forgotten — the credential this
data was recorded under is gone and the next login may be a different user. On **403** only
the retry loop stops: tracking continues and **every queued row is kept**, because a revoked
or under-scoped key is still the same user's data. Re-`configure()` with a working credential
to resume.

A `Retry-After` header is honoured: the background worker re-enqueues at the server's time
instead of its own backoff. Implement `SyncTransport` yourself for a non-HTTP backend.

With `autoSync = true` the SDK drives its own uploads — on stored points (throttled to one
request a minute), from the health loop every two minutes, and from the backstop worker every
fifteen, which is the one that survives a dead service. Set it to `false` to own the schedule
with `requestSync()` / `syncNow()` yourself.

### `trackit-snap` — OSRM map-matching

See [§7](#7-plotting). Depends on `trackit-geo` only; OkHttp is `compileOnly`.

### `trackit-maps` — Google Maps rendering

`TrackRenderer`, `LiveTrackRenderer`, `ArrowIcons`. Not a view and not thread-safe:
construct where the map lives, call `render`, call `clear` when it goes away.

This module has **no tests**. It is thin by design, but "thin" is not "verified".

---

## 12. Java

`trackit-bridge` gives you the SDK without `suspend` and without `Flow`.

```java
TrackItClient client = TrackItClient.getInstance(context);

client.ready(new TrackItConfig(), new ResultCallback<TrackItState>() {
    @Override public void onSuccess(TrackItState state) { /* … */ }
    @Override public void onError(ErrorCode code, String message) { /* … */ }
});

client.start("commute", callback);
client.stop(callback);

client.buildTrack(new PointQuery(sessionId, null, null, 500, 0), trackCallback);
client.getPoints(pointsCallback);
client.getOdometerMeters(odometerCallback);

Cancellable sub = client.addEventListener(event -> handle(event));
sub.cancel();
```

Build config fluently — this is what the builder exists for:

```java
TrackItConfig config = TrackItConfig.builder()
    .provider(LocationProviderType.GPS_ONLY)
    .accuracyProfile(AccuracyProfile.STRICT)
    .trackingMode(TrackingMode.ADAPTIVE)
    .notification("Tracking", "Recording your route")
    .build();
```

Synchronous getters where nothing can fail: `getState()`, `getProviderState()`,
`getSensors()`, `permissions()`, `isOffRoute()`.

`TrackItJson` on the same module is the JSON facade for anything that speaks JSON rather
than Kotlin types.

---

## 13. React Native

npm package `@fieldtrack360/react-native-fieldtrack`, version-locked to the Maven artifacts.

```ts
import * as TrackIt from '@devstree/react-native-trackit';

await TrackIt.ready({ geolocation: { providerType: 'GPS_ONLY' } });
const session = await TrackIt.start('commute');

const points = await TrackIt.getPoints({ sessionId: session.id });
const track = await TrackIt.buildTrack({ sessionId: session.id }, { zoom: 14 });

const sub = TrackIt.addEventListener(event => console.log(event));
const liveSub = TrackIt.addLiveTrackListener(frame => draw(frame));
TrackIt.setLiveTrackThrottleMs(200);

sub.remove();
await TrackIt.stop();
```

Errors reject with a `TrackItError` carrying the SDK's own `code` — branch on that;
`message` is for humans and is not stable.

**Android only.** The package installs on iOS and every call rejects with a typed
`UNSUPPORTED_PLATFORM`. That is deliberate, not an omission: an iOS version would be a
second implementation of a seven-stage acceptance pipeline, not a port. Check
`TrackIt.isSupported` before wiring UI.

---

## 14. Troubleshooting

### "I only get one point every few minutes"

Working as designed. The pipeline stores points that carry information, not points on a
timer. A stationary user produces one point plus a heartbeat every 15 minutes. Confirm with
the decision log: you should see `Drift Suppressed`, `HeartBeat Skipped` and
`Departure Held`.

### "My config changes do nothing"

`reset = false`. The persisted config is winning. Set `reset = true`.

### "The track zigzags around a wrong street after a signal gap"

Tighten the accuracy meter. `AccuracyProfile.STRICT` sets a 20 m moving ceiling and a 15 m
re-anchor bar; the default 30/25 admits fixes that can drag the anchor after a blackout.
Confirm in the decision log — look for `Recovery Reset` where you expected
`Recovery Confirmed` or `Recovery Held`.

### "Tracking stops when I swipe the app away"

Check `service.stopOnTerminate` is `false` (the default) and `foregroundService` is `true`.
On aggressive OEMs (Xiaomi, Oppo, Vivo, Huawei) the user must also exempt your app from
battery optimisation — the SDK deliberately does not request that permission for you.

### "Navigation randomly stops"

`navigationMode` without `foregroundService`. `validate()` refuses that combination now; if
you are seeing it, the config was constructed without going through `ready()`.

### "`start()` returns `PLAY_SERVICES_UNAVAILABLE`"

The device has no Play Services. Switch to `LocationProviderType.GPS_ONLY`, which runs on
the platform `LocationManager`. The error message says this too.

### "`start()` returns `COARSE_ONLY`"

The user granted approximate location. Either request precise location, or drop to
`TrackingMode.MOTION_ONLY`, which is the only mode a 1–3 km error circle can support.

### "No points on a device with no gyroscope"

Check `getSensors().motionQuality`. On `POOR`, `ready()` forces `CONTINUOUS` and emits
`MOTION_DETECTION_DEGRADED` — that is the SDK acting on the hardware rather than merely
reporting it.

### "The polyline is in the wrong hemisphere"

You hardcoded precision 5. Read `track.precision`; it defaults to 6.

### "`buildTrack()` returns a `snap_unavailable` warning"

Your `RoadSnapProvider` could not answer. The track is built from raw geometry and is still
correct — snapping is an enhancement, never a dependency.

---

## 15. Known limitations

Stated rather than discovered:

- **Nothing is published to a remote repository yet.** `publishToMavenLocal` works with no
  configuration.
- **No `setConfig()`, `changePace()`, `getCurrentPosition()`, `insertPoint()`,
  `deletePoints()`, `requestPermission()` or `exportFixture()` on `TrackIt`.** Use
  `getCurrentLocation()` for a fresh non-persisted snapshot. The other names remain
  target-surface entries in `API.md` §10 and are not implemented.
- **`trackit-maps` has no tests.**
- **No committed field fixtures.** The replay harness exists and is used in tests, but
  constant tuning against real drives has not happened.
- **No OEM field matrix.** The survival stack is unit-tested, not device-tested across
  Xiaomi/Oppo/Vivo/Huawei.
- **Room migrations are untested.** The schema is at v4, all migrations are hand-written and
  additive, but `MigrationTestHelper` needs the schema directory in androidTest assets,
  which AGP 9 currently rejects.
- **`PlatformLocationSource` has no instrumented test.** GPS/network/passive registration is
  unit-covered at the config level only.
- **`./gradlew lintDebug` is red** on one pre-existing `InlinedApi` error in
  `TrackingService`. Every other check passes.

---

## 16. Where to go next

| Document | What it holds |
|---|---|
| [API.md](API.md) | The real Kotlin: types, pipeline internals, ports, Room schema, config reference |
| [PERMISSIONS.md](PERMISSIONS.md) | The permission ladder in depth, FGS by API level, the survival stack |
| [EDGE-CASES.md](EDGE-CASES.md) | Every catalogued case: trigger, symptom, handling, owner, test |
| [POLYLINE-JSON.md](POLYLINE-JSON.md) | The export contract — polyline JSON, arrows, GeoJSON, fixture format |
| [CROSS-PLATFORM.md](CROSS-PLATFORM.md) | Java and React Native surfaces, and why there is no iOS |
| [BUILD.md](BUILD.md) | Build manual, module recipes, version catalog, CI, AGP 9 gotchas |
| [PLAN.md](PLAN.md) | Scope, architecture, provenance — start here to understand *why* |
| [reference/capture-and-plotting-spec.md](reference/capture-and-plotting-spec.md) | The algorithm bible: every filter stage, constant and plotting rule |
