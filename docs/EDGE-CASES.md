# Edge Case Catalogue

Every condition TrackIt must handle, with the trigger, the observable symptom if unhandled, the handling, and where it is verified. IDs are referenced from [PLAN.md](PLAN.md) and [API.md](API.md).

**Columns:** `#` · **Trigger** — what happens in the world · **Unhandled symptom** — what the user or the data sees · **Handling** — the SDK's response · **Owner** — module/class · **Test** — how it's proven.

Test tiers: **T1** pure JVM (`trackit-geo`, fixture replay) · **T2** Robolectric · **T3** instrumented · **T4** manual field matrix.

---

## 1. Permissions & consent

| # | Trigger | Unhandled symptom | Handling | Owner | Test |
|---|---|---|---|---|---|
| EC-01 | `start()` with no location permission at all | Crash (`SecurityException`) or silent no-op | Return `TrackItResult.Error(PERMISSION_DENIED)`; never throw; never start the service | `PermissionManager` | T2 |
| EC-02 | Only `ACCESS_COARSE_LOCATION` granted (user picked "Approximate") | ~1–3 km accuracy fixes pollute the track and pass no gate | Detect via `Granularity`; emit `ProviderChange(accuracy=COARSE)`; refuse to start in `CONTINUOUS`/`ADAPTIVE`, allow only if host explicitly opts in | `PermissionManager`, `ProviderStateMonitor` | T2, T4 |
| EC-03 | Fine granted, background **not** granted (Android 10+) | Reference impl gives *zero* tracking (see [A16](SOURCE-AUDIT.md)) | Degrade to `FOREGROUND_ONLY`: track while app visible, pause on background, emit `Error(BACKGROUND_PERMISSION_MISSING)`, auto-resume on grant | `PermissionManager` | T2, T3 |
| EC-04 | Background permission requested in the same prompt as fine (Android 11+) | OS silently denies; prompt never shown | Two-step ladder enforced in code: fine first, then background only after fine is granted **and** after a rationale screen | `PermissionManager` | T3 |
| EC-05 | Android 11+ — background can no longer be prompted, only granted in Settings | Prompt appears to do nothing | `PermissionResult.NeedsSettings(intent)` with a deep link to the app's location settings page; host shows the "Allow all the time" explainer | `PermissionManager` | T3, T4 |
| EC-06 | Permission revoked **while tracking** | Provider stops delivering, service lingers, silent data loss | `AppOpsManager.startWatchingMode(OPSTR_FINE_LOCATION)` (pattern from `AttendanceLoggerService.kt:877-892`) → clean stop + `ProviderChange` + `Error` | `ProviderStateMonitor` | T3, T4 |
| EC-07 | Permission re-granted while stopped-due-to-revocation | Tracking never resumes | `ProviderStateMonitor` re-emits; if a session is still open, auto-restart the service | `SessionManager` | T3 |
| EC-08 | `POST_NOTIFICATIONS` denied (Android 13+) | FGS runs with an invisible notification; user has no idea tracking is on | Request before `start()`; if denied, still start (FGS is legal) but emit `Error(NOTIFICATION_HIDDEN)` so the host can explain | `PermissionManager` | T2, T4 |
| EC-09 | `ACTIVITY_RECOGNITION` denied | AR registration throws or silently fails | Guard every AR call with a permission check (pattern at `ActivityTransitionManager.kt:145-149`); degrade to speed-only motion detection; feature still works | `ActivityRecognizer` | T2 |
| EC-10 | `ACTIVITY_RECOGNITION` granted *after* tracking started | AR never registers for the session | `onActivityRecognitionPermissionGranted()` re-registers; `FLAG_UPDATE_CURRENT` makes it idempotent | `ActivityRecognizer` | T3 |
| EC-11 | Android 14+ — location FGS start requires the permission *already granted* | `SecurityException` on `startForeground` | Check before starting; if missing, don't start the service at all | `TrackingService` | T3 |
| EC-12 | User downgrades precise → approximate in Settings mid-session, leaving `ACCESS_COARSE` only | Track degrades silently | `ProviderStateMonitor` watches granularity, not just grant | `ProviderStateMonitor` | T4 |
| EC-13 | Host app calls `requestPermission()` from a non-`Activity` context | Crash | Require `Activity`; typed compile-time signature, plus runtime guard returning `Error(NO_ACTIVITY)` | `PermissionManager` | T2 |
| EC-14 | User denies twice → "Don't ask again" | Infinite prompt loop | Retry cap of 3 (the reference caps at 3 in `CurrentLocationProvider.kt:440`); after that only `NeedsSettings` | `PermissionManager` | T2 |
| EC-15 | Battery-optimisation exemption requested without user intent | Play Store policy violation | Never auto-request. `requestIgnoreBatteryOptimizations(activity)` is host-invoked only, documented as policy-sensitive | `PermissionManager` | doc |

## 2. Location services & provider state

| # | Trigger | Unhandled symptom | Handling | Owner | Test |
|---|---|---|---|---|---|
| EC-16 | GPS turned off in system settings | No fixes, no explanation | `SettingsClient.checkLocationSettings` → `ResolvableApiException` → `ProviderChange(gpsEnabled=false)` + resolution `IntentSender` handed to the host | `ProviderStateMonitor` | T3 |
| EC-17 | User declines the location-settings resolution dialog | Retry storm | Cap retries (reference caps at 3/`BackgroundLocationProvider.kt:272` and 2/`CurrentLocationProvider.kt:302`); then emit and stop asking for this session | `ProviderStateMonitor` | T2 |
| EC-18 | Airplane mode on | GPS may still work; network positioning dies | No special case — the NLP gate already rejects network fixes; emit `ProviderChange` | — | T4 |
| EC-19 | Google Play Services missing / out of date (AOSP, Huawei) | `LocationServices` throws at construction | Detect `GoogleApiAvailability`; fall back to platform `LocationManager` (`GPS_PROVIDER`) with the same request semantics; emit `ProviderChange(fusedAvailable=false)` | `LocationSource` abstraction | T2, T4 |
| EC-20 | Location settings satisfied but the device never gets a fix (indoor cold start) | Session appears dead | One-shot timeout 30 s → `Error(FIX_TIMEOUT)`; backstop retries with linear backoff | `OneShotProvider` | T3 |
| EC-21 | OS power-save mode enabled | Fixes throttle to a few per hour | `PowerManager.isPowerSaveMode` + `ACTION_POWER_SAVE_MODE_CHANGED` → emit `PowerSaveChange`; widen the dead-tracker threshold while active so the watchdog doesn't false-fire | `ProviderStateMonitor` | T3, T4 |
| EC-22 | Doze / app standby bucket = restricted | 15-min worker never runs | Expedited restore work + the FGS is Doze-exempt while running; document that the backstop is best-effort | `BackstopWorker` | T4 |
| EC-23 | `FusedLocationProviderClient` returns a fix with `accuracy == 0` or `NaN` | Kalman `variance = 0` → gain 1 → filter tracks noise exactly | Clamp accuracy to `[minAccuracy=1f, 10_000f]`; reject `NaN`/`Infinite` on any field | `FixMapper` | T1 |
| EC-24 | Fix at exactly `(0.0, 0.0)` (null island) | A point in the Gulf of Guinea plots | Reject before the pipeline (reference does this at `AttendanceLoggerService.kt:239`) | `FixMapper` | T1 |
| EC-25 | Latitude out of `[-90, 90]` or longitude out of `[-180, 180]` | Haversine returns `NaN`, poisons the filter | Range-validate at the mapper; reject with `INVALID_COORDINATES` | `FixMapper` | T1 |
| EC-26 | Antimeridian crossing (±180° longitude) | Haversine fine, but polyline encoding and bounds break | Normalise longitude difference to `[-180, 180]` in bearing/bounds; document that encoded polylines are computed on unwrapped deltas | `Geometry`, `PolylineCodec` | T1 |
| EC-27 | Fix has speed but `hasSpeed() == false` (some OEMs set the value without the flag) | Stage 2 misclassifies as stationary | Trust the flag, not the value — this is the documented contract; log a diagnostic when `speed > 0 && !hasSpeed` | `FixMapper` | T1, T4 |
| EC-28 | Mock location provider active (Fake GPS apps) | Fabricated tracks accepted as real | `mockLocationPolicy`: `FLAG` (default, stored + exported), `REJECT`, `ALLOW` | `FixMapper` | T2 |
| EC-29 | Fix arrives while the device is rebooting / `elapsedRealtimeNanos` resets | Gaps computed against a reset monotonic clock become huge negative | Detect `elapsedRealtimeNanos < lastElapsedNanos` → treat as a boot boundary, force a filter reseed, log `Reboot Boundary` | `FixIngestor` | T1 |

## 3. Fix quality & noise classes

The nine classes from the reference spec, plus what the audit added.

| # | Trigger | Unhandled symptom | Handling | Stage | Test |
|---|---|---|---|---|---|
| EC-30 | Duplicate/burst callbacks < 500 ms apart | Double-counted movement | Burst gate on **fix** elapsed-nanos, not delivery time ([A5](SOURCE-AUDIT.md)) | 1 | T1 |
| EC-31 | Batched delivery of N fixes at once | Reference keeps only the last ([A4](SOURCE-AUDIT.md)) | Iterate `locationResult.locations` ascending by elapsed-nanos, feed each | pre-1 | T1, T3 |
| EC-32 | Network-positioning (WiFi/cell) fix: no speed, no bearing, acc > 25 m | Teleport to a router's registered address and back | Stage 1.5 NLP reject, with 10-min hardware-vehicular bypass so tunnels/garages still track | 1.5 | T1 |
| EC-33 | Stale cached fix replayed on app resume | Old position plots as a teleport | Reject if `elapsedRealtimeNanos` age > 60 s (pattern at `BackgroundLocationProvider.kt:325-329`) **on every provider path**, not just the stream | pre-1 | T1, T3 |
| EC-34 | Ionospheric glitch / cold-start wild fix (implied > 140 km/h) | Continent-scale spike | Stage 3 physical sanity | 3 | T1 |
| EC-35 | Urban-canyon multipath: one fix 100–2000 m off, then normal | Spike in the polyline | Stage 5 3-sigma gate | 5 | T1 |
| EC-36 | Phantom Doppler — chip reports 3–8 m/s with ~0 displacement (observed on Moto G34 family, `LocationUtil.kt:248-252`) | Stationary user classified as driving; "blob" uploaded | Stage 2: `hwSpeed > 3 && calcSpeed < 0.6 → use calcSpeed` | 2 | T1 |
| EC-37 | Phantom hardware speed 0.6–1.9 m/s while parked (AR=STILL) | Point classified Moving/Walking with zero displacement | Stage 2 `isMoving` requires speed ≥ 2.0 m/s **or** displacement past the wobble guard | 2 | T1 |
| EC-38 | Indoor GPS wobble 30–70 m at 60 s cadence | Random walk plotted as pacing | Wobble guard 40 m, **80 m** when the fix has no hardware speed at all | 2 | T1 |
| EC-39 | Slow drift loop: many small accepted moves netting ≈ 0 | "User walked 60 m at 2 am" | Stage 7-A net-displacement persistence: publish only when net from the origin anchor exceeds 100 m, or clears 40 m while still advancing on 2 consecutive fixes. Drift wanders out and back so its net **stalls against its own high-water mark**; a journey's net only ever climbs. A stalled net re-anchors the origin ("Drift Suppressed") | 7 | T1 |
| EC-39a | Any travel slower than `persistMinNet / cadence` — **all walking, all cycling, slow city driving** | Silently invisible. Stage 7-A re-anchored the origin onto the user on *every* fix that had not yet cleared 40 m, so net displacement could never exceed one fix's travel and the ladder never completed. At the 12 s vehicular cadence that is everything under 12 km/h. Measured: **0 of 25 walking fixes stored** | The origin stays put while net displacement climbs; it re-anchors only when net stalls. The departure test measures **net distance**, not a fixed per-fix step — a 20 m step is one second of driving and fifteen of walking, and unclearable on foot at the 4 s turn-burst tier | 7-A | T1 |
| EC-39b | Steady walker at a cadence faster than ~30 s | Classified **stationary**: 1.3 m/s clears neither the 2 m/s `speedVirtuallyStopped` bar nor the 40 m wobble guard, because a 12 s fix covers 16 m. Both bars were set for 60 s sampling, where a walker delivers 78 m | `corroboratedWalk`: a walking pace the chip reports **and** the ground confirms is a walk, whatever the absolute distance. Both halves are load-bearing — Doppler alone is the phantom-speed failure (EC-36), displacement alone is indoor wobble (EC-38) | 2 | T1 |
| EC-39c | `Walk Arrival` fires mid-walk | Its stage 7-B routing re-anchors the origin and clears `movingMode`, restarting the departure ladder every few fixes, so a walk plots as scattered points instead of a path | Arrival requires the walk to have actually **ended** (`effectiveSpeed < speedWalkingMin`), like `Arrival` already did | 6 | T1 |
| EC-40 | Post-gap teleport (elevator, basement, airplane mode) | A lone bad fix relocates the user | Stage 4 tiered recovery: immediate reset only on strong evidence (≥150 m, or gap+vehicular+200 m); otherwise **hold** and require a second fix within 60 m | 4 | T1 |
| EC-41 | Held recovery followed by a fix that snaps back | Filter stuck holding forever | Pending cleared on the next non-confirming fix; falls through to normal gates | 4 | T1 |
| EC-42 | Device clock jumps backwards (NTP correction, user change) | Negative Δt | Impossible by construction — all Δt comes from `elapsedRealtimeNanos` ([A1](SOURCE-AUDIT.md)). Wall-clock jumps affect only the stored `timeMs`, and are logged | — | T1 |
| EC-43 | Kalman filter wedges (rejects everything after a bad seed) | Tracking dies silently until restart | Stage 5 forced reset after `maxRejects` (2 gap / 4 stationary / 2 vehicular / 3 walking) — the un-wedge mechanism; **do not soften** | 5 | T1 |
| EC-44 | Filter forced-resets repeatedly while driving | Track goes straight then teleports | Symptom of missing drift-tolerance scaling; `driftTolerance = max(60, speed·Δt·1.3)` prevents R over-inflation → gate lag → cascade | 7 | T1 |
| EC-44a | Steady driving on a straight road, no noise at all | **1 fix in 4 rejected** as `Sigma Gate Outlier`, then recovered by `Sigma Forced Reset` — the visible "track jumps between locations". A position-only filter has no term for a moving target, so it lagged ~130 m *every fix* at the 12 s cadence until the lag breached the gate. No Q/R tuning can fix a missing state | Constant-velocity model: position **and** velocity, learned from position corrections via the covariance off-diagonal. The gate measures against whichever prediction is closer — extrapolated or last-corrected — so a straight loses the lag and a corner is judged exactly as the scalar filter judged it. Process noise is continuous (`dt³/3`), not discrete (`dt⁴/4`), which at 12 s would have inflated the gate 404 m → 690 m | `KalmanFilter` | T1 |
| EC-45a | Cornering, with the constant-velocity filter EC-44a introduced | Track cuts the corner and then overshoots it — a spike running past a right-hand turn before snapping back onto the road, visible on a field capture. CV predicts a straight line *by definition*, so at a turn the prediction is wrong and the correction spends the next fixes hauling it back; no tuning on the straight can fix a model that does not represent turning. EC-44a bought lag-free straights and paid for it here | Raise process noise to a lateral **2.0 m/s²** while the model is provably wrong, so the filter weights the measurement over its own prediction. Turning is detected from the filter's *own* velocity vector against the measured heading (≥ 25°) — no state plumbed in from `TurnDetector`, so it holds for turns that never armed the burst tier. Both the filter's speed and the measured speed must clear `turnBurstMinSpeed`, not either: a near-stationary phone's heading swings through the full circle on multipath, and boosting there would widen the correction on exactly the fixes the stationary defences suppress. **Applied to the correction only, never the gate** — a corner is a reason to track harder, not an amnesty for fixes that would otherwise be rejected, and a turn is precisely where multipath off the buildings inside it is worst. A/B on a right-angle turn: 30.4 m accumulated error with the boost, 179.0 m without | `AcceptancePipeline` | T1 |
| EC-45b | Sparse cadence drawn as chords — the "polyline is straight / jumps between coordinates" report | 12 s at 10 m/s puts vertices 120 m apart, and `BezierRounding` cannot help: it rounds *vertices* turning more than 30° and leaves every leg a chord, so it treats the joins when the problem is the legs. Worse, its output only ever reached `Track.encodedPolyline` — `buildSegments`/`buildArrows` sliced the **pre-rounding** path, so a host drawing per-segment speed bands, which is the point of segments carrying their own polyline, saw no smoothing at all | `Spline`: centripetal Catmull-Rom (α = 0.5) through **every** vertex, resampled at 5 m, capped at 64 samples per span so a blackout leg cannot emit thousands of vertices. Segments now slice the smoothed path. Arrows deliberately do **not** — their ladder thins by distance (EC-106a), so a 5 m path roughly doubles the count and lets vertex density decide arrow density instead of zoom; they anchor to the original vertices instead, which costs nothing because Catmull-Rom *interpolates* and every one of those vertices lies exactly on the drawn curve (EC-102a). Cosmetic and says so: between two fixes 120 m apart the curve is an assumption about a road nobody measured, which is why a snapped path is returned untouched (EC-101) and why map-matching remains the real answer | `Spline`, `TrackBuilder` | T1 |
| EC-45 | Turn happens entirely between two 60 s samples | Corner cut | Four layers, three offline. **Capture:** adaptive cadence 12 s while vehicular; a turn-burst tier at 4 s while `TurnDetector` measures ≥ 3 °/s (30 s hold); bearing-change force-capture > 40° measured from the last **stored** point, not the last fix. **Render:** Bézier, then optional map-matching via `RoadSnapProvider`. The burst is reactive by construction — it arms partway through the turn that triggered it, so it buys the rest of a bend and corners 2..n of a roundabout, not the first apex | `LocationStreamController`, `TurnDetector`, `AcceptancePipeline` | T1, T4 |
| EC-46 | Walk shorter than 100 m ending in a stop | Held by persistence and never uploaded → trip invisible | "Walk Arrival" branch: `departCount ≥ 1 && netFromOrigin > 40 && acc < 40` re-anchors and accepts | 6/7 | T1 |
| EC-47 | User walks during a signal blackout and is now still somewhere new | Arrival never registers | `isArrivalTransition` (gap + hw-stationary + past was walking + implied speed 0.3–3.0 m/s) with a relaxed 70 m accuracy ceiling | 2/6 | T1 |
| EC-48 | User stationary for hours | Server/DB fills with drift points | Heartbeat: one fix per 900 s is *filtered but not stored*. **Acceptance criterion: 2 hours steady ⇒ exactly 1 stored point** | 6/7 | T1, T4 |
| EC-49 | Accuracy is excellent but the fix is 400 m away after 20 min of silence | Rejected as an outlier, user "teleports" later | Stationary-branch `isGPSRecovery`: `dist>150 && acc<40`, or `dist>400 && acc<80`, or `gap && dist>100 && acc<25` | 6 | T1 |
| EC-50 | First fix ever (cold start, no stored anchor) | Nothing to compare against | Stage 1 "Init": seed filter + origin, accept unconditionally — **only** when there is genuinely no anchor | 1 | T1 |
| EC-51 | First fix after process death **with** a stored anchor | Reference can blind-accept it ([A2](SOURCE-AUDIT.md)) | Restore `filter_state` in `ready()`; re-seed from the stored anchor **with its stored timestamp**, then judge this fix through all gates | 1 | T1, T2 |
| EC-52 | Two subscriptions feeding the filter concurrently | Interleaved state, gates fight | Single `FixIngestor` actor; stream/one-shot/backstop/manual all funnel through one `Channel` ([A3](SOURCE-AUDIT.md), [A6](SOURCE-AUDIT.md)) | — | T2 |

## 4. Motion state machine

| # | Trigger | Unhandled symptom | Handling | Owner | Test |
|---|---|---|---|---|---|
| EC-53 | AR reports `STILL` while the user is genuinely driving (common on OnePlus/Xiaomi under battery saver — documented at `EmployeeLocationHistoryViewModel.kt:86-99`) | Whole drive labelled stationary | Never gate capture on AR. AR is enrichment only. Plot-side STILL override: distrust STILL when path ≥ 500 m and (max ≥ 5 m/s or p75 ≥ 3 m/s) | `MotionController`, `Clusters` | T1, T4 |
| EC-54 | AR never fires at all (permission denied, GMS throttled) | Stuck in one state | Motion state is derived from hardware speed + displacement first; AR only *accelerates* transitions | `MotionController` | T2 |
| EC-55 | AR fires `IN_VEHICLE` while the phone sits on a desk near a road | Spurious wake, battery burn | ENTER only opens a stop-pending cancel; actual MOVING requires a fix that passes the pipeline | `MotionController` | T1 |
| EC-56 | `stopTimeout` expires but the user is at a traffic light | Track fragments into false stops | `stopTimeout` default 5 min; any moving-class fix or AR moving-ENTER cancels the timer | `MotionController` | T1 |
| EC-57 | Stationary geofence never fires (user moves < 150 m to a new room) | Stuck stationary | Heartbeat still runs in `ADAPTIVE`; a heartbeat fix that lands > `stationaryRadius` from the anchor forces MOVING | `MotionController` | T1 |
| EC-58 | Geofence registration fails (GMS error, too many geofences) | Silent loss of the wake path | `addGeofences` failure → fall back to `ADAPTIVE` heartbeat behaviour and emit a diagnostic | `StationaryFence` | T2 |
| EC-59 | `changePace(true)` called while already MOVING | Duplicate state emission | Idempotent; `MotionChange` emitted only on actual transition | `MotionController` | T1 |
| EC-60 | Rapid stop/start (delivery rider, 20 stops/hour) | State machine thrashes, battery spikes | `motionTriggerDelay` + `settleCount ≥ 2` before exiting moving-mode; both configurable | `MotionController` | T1, T4 |
| EC-61 | `MOTION_ONLY` mode and the device never becomes still | Location stream never pauses | Correct behaviour; documented. Battery guidance in `CONFIG.md` | — | doc |

## 5. Foreground service, process & OS lifecycle

| # | Trigger | Unhandled symptom | Handling | Owner | Test |
|---|---|---|---|---|---|
| EC-62 | `startForeground` called while the app is backgrounded (API 31+) | `ForegroundServiceStartNotAllowedException` → crash, then ANR for "did not call startForeground" | Catch **both** that and `SecurityException` (API 34+), stop the service cleanly, let the restore worker re-promote when the app is next eligible (pattern at `AttendanceLoggerService.kt:925-954`). Never crash-loop | `TrackingService` | T3 |
| EC-63 | Process killed by the OS mid-session | Filter restarts cold; first fix accepted blind | `filter_state` + `track_session` persisted; `ready()` restores both; `START_STICKY` restarts the service | `TrackIt`, `TrackingService` | T2, T3 |
| EC-64 | Service restarted by the OS with a **null** Intent | Service comes back unconfigured | Re-read the persisted session + config on null-intent restart instead of relying on Intent redelivery ([A14](SOURCE-AUDIT.md)) | `TrackingService` | T2 |
| EC-65 | Device reboots with a session open | Tracking silently stops | `BOOT_COMPLETED` receiver → if `startOnBoot` and a session is open, re-arm watchdog and restore the service | `BootReceiver` | T3, T4 |
| EC-66 | App force-stopped by the user | Everything stops; nothing can restart it | Correct and required by policy. On next app launch, `ready()` detects an open session and emits `SessionInterrupted` so the host can ask | `SessionManager` | T4 |
| EC-67 | App updated / `MY_PACKAGE_REPLACED` | Service gone, alarms cleared | Handle `MY_PACKAGE_REPLACED` alongside `BOOT_COMPLETED` | `BootReceiver` | T3 |
| EC-68 | Task swiped from Recents | Service dies on some OEMs | `android:stopWithTask="false"` (as at `AndroidManifest.xml:579`) + `stopOnTerminate=false` | manifest | T4 |
| EC-69 | OEM battery manager (Xiaomi/Oppo/Vivo/Samsung) silently kills the service | Long unexplained gaps | 60 s watchdog + expedited restore worker + 20 s wake lock; surface `Error(TRACKER_DEAD)` after 30 min moving / 60 min stationary with no raw fix | `Watchdog` | T4 |
| EC-70 | Watchdog fires while the user is legitimately parked | Spurious "tracking interrupted" nudges | Liveness is judged on the **raw fix clock**, updated pre-filter on every fix — never on upload or accept recency, because stationary uploads nothing by design | `Watchdog` | T1, T4 |
| EC-71 | `WorkManager` periodic work stuck `BLOCKED`/`FAILED` | Backstop never runs | Health loop inspects `WorkInfo` state every 2 min and cancels+restarts (pattern at `AttendanceLoggerService.kt:442-451`) | `HealthLoop` | T2 |
| EC-72 | Two `start()` calls in a row | Two services, two streams | `start()` is idempotent; returns the existing `TrackSession` | `TrackIt` | T2 |
| EC-73 | `stop()` while a fix is mid-pipeline | Point written after the session closed | Ingest channel closed first, drained, *then* teardown; late fixes dropped with `Session Closed` | `FixIngestor` | T2 |
| EC-74 | `stop()` called twice / when never started | Exception | No-op, returns `null` | `TrackIt` | T2 |
| EC-75 | Host calls SDK methods before `init()`/`ready()` | `UninitializedPropertyAccessException` | Every entry point checks and returns `Error(NOT_READY)` | `TrackIt` | T2 |
| EC-76 | Notification channel deleted by the user | FGS notification invisible; on some OEMs the service is killed | Re-create the channel on every service start; detect `IMPORTANCE_NONE` and emit a diagnostic | `NotificationFactory` | T3 |
| EC-77 | Host passes a notification with no small icon | `IllegalArgumentException` inside `startForeground` | Validate `NotificationConfig` in `ready()` and fail fast with a clear message, not at service start | `TrackItConfig` | T2 |

## 6. Storage & data integrity

| # | Trigger | Unhandled symptom | Handling | Owner | Test |
|---|---|---|---|---|---|
| EC-78 | Disk full | Insert throws, fix lost, possible crash | Catch `SQLiteFullException`; emit `Error(STORAGE_FULL)`; drop the oldest synced/expired rows and retry once | `RoomPointStore` | T2 |
| EC-79 | DB corrupted | Crash loop on every start | `RoomDatabase.Callback.onCorruption` → recreate; emit `Error(STORAGE_RESET)` | `TrackItDatabase` | T2 |
| EC-80 | Long session, hundreds of thousands of rows | `getPoints()` OOM | All queries paged; `observePoints` is a paged `Flow`; `buildTrack` streams by session/time window | DAOs | T2 |
| EC-81 | `maxDaysToPersist` prunes rows still needed for an open session | Track has a hole | Pruning never touches rows belonging to an **open** session | `PruneWorker` | T2 |
| EC-82 | Two writers (service + backstop) insert the same fix | Duplicate points | `uuid` derived from `(sessionId, elapsedRealtimeNanos)`; `INSERT … ON CONFLICT IGNORE` | `RoomPointStore` | T2 |
| EC-83 | SDK upgraded, schema changed | Destructive migration wipes user data | `exportSchema = true`, committed schemas, explicit `Migration` classes, migration tests. **Never** `fallbackToDestructiveMigration()` in a library | `TrackItDatabase` | T2 |
| EC-84 | Host app also uses Room | Database name collision | DB file name `trackit-<packageName>.db`, own `RoomDatabase` instance, no shared `Migration` registry | `TrackItDatabase` | T2 |
| EC-85 | Host reads points while the ingestor writes | Inconsistent snapshots | WAL + `Flow` queries; readers never block writers | `TrackItDatabase` | T2 |
| EC-86 | `insertPoint()` called by the host with a bogus point | Corrupt track | Same validation as a real fix (coords, accuracy, time); rejected with a typed error | `TrackIt` | T2 |
| EC-87 | Decision log grows unbounded on a long trip | DB bloat | Ring-capped by count **and** `decisionRetentionDays`; `persistDecisions` can be turned off | `PruneWorker` | T2 |

## 7. Time & clock

| # | Trigger | Unhandled symptom | Handling | Owner | Test |
|---|---|---|---|---|---|
| EC-88 | Wall clock changed by the user mid-session | Points out of order; day boundaries wrong | Ordering and all Δt use `elapsedRealtimeNanos`; `timeMs` is display-only. Emit a diagnostic on a > 60 s wall-clock jump | `FixIngestor` | T1 |
| EC-88a | The EC-88 rule stated on the write path, never enforced on the **read** path | Polyline crosses itself. `TrackPointDao.query`/`observe` sorted by `timeMs` while `last` and the sync query used the monotonic clock — three queries on one table, two clocks, and the two that feed plotting picked the wrong one. One NTP correction reorders the result and the drawn line braids; field capture `90a38095` shows it. A range query legitimately *filters* on wall time, which is what disguised it | Filter on `timeMs`, order by `id`. Not `elapsedRealtimeNanos`: it restarts at zero on reboot, so a session spanning one sorts its whole post-reboot tail to the front — a worse failure than the wall clock's. Insertion order has neither problem and is exact for this table, because `ClockGuard` drops out-of-order deliveries before they are stored (EC-92a) | `TrackPointDao` | T1 |
| EC-88b | Drawing the **raw** diagnostic layer, which is written before `ClockGuard` runs | Same braid, different cause and not fixable the same way. The raw layer exists to show what the OS handed over, so it keeps the stragglers the live path drops — insertion order is therefore delivery order, not fix order. But `elapsedRealtimeNanos` cannot sort it either, being exact within a boot and meaningless across one | `ClockGuard.inFixOrder` uses each column where it holds: split the list wherever the monotonic clock rewinds far enough to be a reboot, sort within each run, never across a boundary. Rewind is measured from the run's high-water mark, not its previous element — a straggler is by definition older than what preceded it, so measuring from it understates the next rewind and hides a reboot that lands right after one | `ClockGuard` | T1 |
| EC-89 | Timezone changes mid-session (flight) | `localDate` inconsistent within one session | Store the IANA zone id **per point**; the day summary groups by the point's own zone | `TrackPointEntity` | T1 |
| EC-90 | DST transition during a dwell | Dwell computes as −60 or +60 min | All durations from epoch-millis differences, never from local-time arithmetic | `Clusters` | T1 |
| EC-91 | Session spans midnight | Day query splits the track | Track queries are by explicit `[from, to]` range and by `sessionId`, not by calendar day | `TrackQuery` | T1 |
| EC-92 | `elapsedRealtimeNanos` resets after reboot | Huge negative deltas | Boot-boundary detection → reseed (EC-29) | `FixIngestor` | T1 |

## 8. Plotting & export

| # | Trigger | Unhandled symptom | Handling | Owner | Test |
|---|---|---|---|---|---|
| EC-93 | `buildTrack` on 0 points | Crash / empty JSON with `NaN` bounds | Return a well-formed empty `Track` (`points: []`, zeroed stats, `null` bounds) | `TrackBuilder` | T1 |
| EC-94 | `buildTrack` on exactly 1 point | Bounds degenerate, encoded polyline empty | Single-point track: bounds = point, no segments, no arrows, one stop node | `TrackBuilder` | T1 |
| EC-95 | 2 identical points | Zero-length segment, division by zero in speed | Legs < 20 m or < 5 s excluded from speed stats (as at `EmployeeLocationHistoryViewModel.kt:1257`) | `SpeedStats` | T1 |
| EC-96 | Dwell with 10+ min between pings inside a stop | Phantom leg drags duration-weighted p75 into the walking band, mislabelling a ride | Phantom-leg guard: legs with `Δt ≥ 10 min && dist < 500 m` excluded from speed stats but **counted in distance** (`:1272-1279`) | `SpeedStats` | T1 |
| EC-97 | Out-and-back trip returning to the start | Net ≈ 0 ⇒ classified stationary | Sustained-excursion detector: ≥ 2 **consecutive** points beyond 150 m from anchor **and** peak ≥ 1.0 m/s (`:1019-1033`) | `Clusters` | T1 |
| EC-98 | Home GPS scatter of 150–185 m | Excursion detector false-positives a "walk" | Two AND-gates: consecutive-points *and* speed floor; isolated spikes fail the first, slow drift fails the second (phantom-leg guard already zeroes its max) | `Clusters` | T1 |
| EC-99 | Segment with no intermediate samples but real displacement (blackout while driving) | Whole silent gap attributed to travel → phantom hours | Carry-forward: bound implied travel to `clamp(dist / 5.0 m/s, 60 s, 300 s)` (`:1095-1103`); render drive-window and dwell-window separately | `Clusters` | T1 |
| EC-100 | Snap API unavailable / returns empty | Whole track lost | Never propagates: raw geometry plus `warnings: ["snap_unavailable"]` and a `SNAP_UNAVAILABLE` event. `OsrmSnapProvider` degrades **per chunk**, so a five-request trace losing one to a 429 keeps the other four. "Not requested" and "asked and could not answer" are different `RoadGeometry` values — collapsing them would make the warning meaningless | `TrackBuilder`, `OsrmSnapProvider` | T1 |
| EC-100a | Host draws a **live** map with a `RoadSnapProvider` installed | A matching request per accepted fix. `buildTrack` fetches road geometry every call, and observing the point stream — the reason the API is a `Flow` — means calling it on every fix. At the 4 s turn-burst cadence a twenty-minute drive is ~300 rebuilds, each re-matching the whole trace from coordinate one. Against a self-hosted OSRM merely wasteful; against a hosted one it is how a host finds their rate limit. Invisible in any single-call test | Cache **chunks**, not traces: a growing track is a different trace every time but its leading chunks are byte-identical, so a redraw costs one request instead of one per 90 coordinates. LRU, bounded (a `TrackIt` lives as long as the process). Empty results are deliberately **not** cached — empty means transient failure, and caching it would freeze one timeout into a stretch of road that renders raw for the rest of the session, which is the opposite of what EC-100's per-chunk degradation assumes | `ChunkCache`, `OsrmSnapProvider` | T1 |
| EC-101 | Snapped road is 200 m from the raw fix (parallel service road, tunnel) | User teleported onto the wrong street | Keep raw when off-road distance > 80 m; only inject road geometry when **both** endpoints are on-road. Protected bookends are never snapped and cannot anchor an injected span either | `Snapper` | T1 |
| EC-102 | Road geometry revisits a coordinate (roundabout) | `indexOf` sub-path lookup returns the wrong span ([A11](SOURCE-AUDIT.md)) | Closest-point search returns an **index** and scans forward from the previous match; sub-path is `subList(i+1, j)` | `Snapper` | T1 |
| EC-102a | Snapped track drawn with raw-derived segments and arrows | Coloured spans and arrows float beside the road the polyline draws — the [A9](SOURCE-AUDIT.md) divergence class | Segment polylines and arrow anchors are sliced out of the snapped path by **source index**, never by position: injection changes every position in the list | `TrackBuilder` | T1 |
| EC-103 | Bézier rounding applied at a session bookend or a host-inserted marker | Marker moves | Protected nodes never rounded (`RoadSnapperV2.kt:149-151`) | `BezierRounding` | T1 |
| EC-104 | Very short leg between two sharp turns | Bézier cutback overshoots past the neighbour | `cutback = min(25 m, 0.4·distToPrev, 0.4·distToNext)` | `BezierRounding` | T1 |
| EC-105 | Data jump > 50 km between consecutive points | One arrow at a meaningless midpoint | Exactly two arrows at ¼ and ¾ | `Arrows` | T1 |
| EC-106 | Segment shorter than 60 m | Arrow overlaps the whole segment | Skipped | `Arrows` | T1 |
| EC-106a | Snapped track: road vertices land 10–30 m apart on a bend | EC-106 applied leg-by-leg deletes nearly every arrow — on exactly the tracks with the best geometry | Placement thins the path to vertices ≥ 60 m apart *first*, then applies the ladder; bearing becomes the chord across that span. Identity on a raw path whose legs already clear the threshold, so unsnapped tracks do not move. The drawn polyline keeps full road detail | `Arrows` | T1 |
| EC-107 | Zoom < 10 | Hundreds of arrows on screen | No arrows below z10; spacing ladder above it | `Arrows` | T1 |
| EC-108 | Renderer and JSON export disagree on arrow placement ([A9](SOURCE-AUDIT.md)) | Map ≠ exported data | One `Arrows.place()` used by both | `Arrows` | T1 |
| EC-109 | Co-located markers exactly overlap | Only the top one is tappable | ~0.56 m deterministic jitter on identical coordinates | `TrackBuilder` | T1 |
| EC-110 | Polyline precision 6 vs 5 mismatch with the consumer | Track appears in the wrong hemisphere / scaled | `precision` is an explicit field in the JSON, not an assumption | `PolylineCodec` | T1 |
| EC-111 | Ongoing session — last node has no departure | Dwell shows 0 | Ongoing dwell computed against **now** at build time, and `isOngoing: true` in the JSON so the renderer can pulse it | `Clusters` | T1 |
| EC-139 | Vehicle driving through the dwell-consolidation pass | Half the track deleted, and the drawn line jumps between the survivors. Grouping tested membership against a **running centroid**, which trails the newest point by about half the group's span — so a vehicle laying points 40 m apart stayed inside the 60 m radius for two or three fixes at a time and each of those runs collapsed to its first point. Nothing in the pass consulted motion at all. On a field capture: 13 of 28 stored points destroyed, every one of them moving at 5–10 m/s, plus a five-minute stop swallowed into a group anchored 200 m back down the road so the line cut a straight diagonal across it | A moving point (`speedMps ≥ 1.0` or `movementStatus == MOVING`) never joins a dwell, and the radius is measured from the group's **first** point, not from a running mean. Centroid is still the mean and still reports the dwell's position — it no longer decides membership | `Consolidation` | T1 |
| EC-139a | Dwell whose stored points span a minute, but which really lasted an hour | Stop is never plotted. Dwell was read off the group's own first and last points, and that is honest only if the pipeline kept sampling — it deliberately does the opposite, so an hour parked can leave two fixes 30 s apart and report 30 s | A dwell lasts until the next group begins; the recorded span is only the floor. The missing hour is not absent from the data, it is the silence before the next group | `Consolidation` | T1 |
| EC-140 | Stop that the capture pipeline recorded as **silence** rather than as points, sitting inside a travel span | Line runs straight through a car park at an average speed nobody drove, and no stop node appears. Once a device settles the acceptance gates reject nearly everything, so the stop leaves no cluster to classify — only a hole between two fixes that both belong to the drive. Net displacement across that hole clears 100 m, so the span reads as travel however it is classified as a whole | Split the span at a **dwell gap**: `Δt ≥ 180 s` **and** implied speed `≤ 35 m/min` — the same bar as EC-99's sibling guard in `SignificantNodes`, *derived* from it rather than restated so the two cannot drift apart. Durations differ on purpose: a timeline node earns its place over ten minutes, but a six-minute stop still has to stop the line being drawn through it. Implied speed, not radius — the fix that catches a departure is rarely the one at the kerb (158 m out on the capture), so any meaningful radius misses it, while 158 m in 363 s is 0.44 m/s regardless. A genuine blackout at 8 m/s stays travel and EC-99 keeps it. The dwell range is the pair straddling the gap and is forced non-travel; indices stay absolute so `Snapper.spanFor` and arrow placement still slice correctly | `Clusters` | T1 |
| EC-140a | The braking run up to an EC-140 stop | One real stop renders as two, the second sitting where the vehicle was still doing 3 m/s. Splitting a span shortens it, and the approach is only as long as the deceleration took — 57 m on the capture, far under the 100 m net threshold `isRealMovement` is calibrated for | A stop-adjacent offcut is judged by peak leg speed (`≥ 1 m/s`) instead of displacement. Deliberately not the general rule: house scatter produces speed spikes too (EC-98), so only ranges created by a split take this path | `Clusters` | T1 |

## 9. Multi-consumer & host integration

| # | Trigger | Unhandled symptom | Handling | Owner | Test |
|---|---|---|---|---|---|
| EC-112 | The host UI and a background collector both listen for events | Reference-style single-callback fields overwrite each other | `SharedFlow` with replay 0 and unlimited subscribers — never a `var callback` | `TrackIt.events` | T2 |
| EC-113 | Host `Activity`/`ViewModel` is destroyed and recreated (rotation, process restore) | A collector leaks, or the new instance never re-subscribes and events vanish | Events are cold on the host side: collect in a lifecycle-scoped coroutine, and re-read authoritative state via `TrackIt.state` on resubscribe. Native state is always the source of truth, never a replayed event buffer | `TrackIt.events` | T2 |
| EC-114 | Host process has no UI on screen (backgrounded, activity finished) | Custom host work stops running; developer assumes capture stopped too | Capture, filtering and storage run in `trackit-core` inside the foreground service and **never depend on a host collector being alive**. A host that wants work with no UI collects `TrackIt.events` from an application- or service-scoped `CoroutineScope` | design | T3 |
| EC-115 | Host collector throws | Exception cancels the shared scope, all delivery stops | Emission is isolated per subscriber; a throwing collector is logged and dropped, never propagated back into the ingestor | `TrackIt.events` | T2 |
| EC-116 | Host passes an out-of-range config value | Undefined behaviour deep in the provider | `TrackItConfig.validate()` runs in `ready()` and fails fast with a typed error naming the field (EC-77, EC-120, EC-121) | `TrackItConfig` | T2 |
| EC-117 | Host app upgraded; a persisted enum name is no longer known | Crash on deserialise | Unknown enum values decode to `UNKNOWN` and are preserved verbatim on re-encode | `TrackItConverters` | T1 |
| EC-118 | Very large `getPoints()` result held by the host | OOM / frozen UI | Queries are paged (EC-80); `buildTrack` output is the intended input for rendering, not the raw point list | `TrackIt` | T2 |

## 10. Configuration & API misuse

| # | Trigger | Unhandled symptom | Handling | Owner | Test |
|---|---|---|---|---|---|
| EC-119 | Host sets `distanceFilter > 0` | **Directly causes stationary drift** — the OS only wakes on noise exceeding the filter, so every update looks like movement | Default `0f`; a non-zero value logs a `WARN` naming the consequence and is recorded in the config snapshot | `TrackItConfig` | T2 |
| EC-120 | `intervalMs` set below `fastestIntervalMs` | OS clamps unpredictably | Validate and normalise in the builder | `TrackItConfig` | T2 |
| EC-121 | `heartbeatIntervalSec` < `intervalMs` | Heartbeat fires every fix; stationary suppression defeated | Validate `heartbeat ≥ 5 × interval` | `TrackItConfig` | T2 |
| EC-121a | `turnBurstIntervalMs` set slower than the tier it accelerates | The "faster" tier is slower than what it replaces, so arming the burst quietly makes turn geometry **worse** — a regression that reads as a feature in the config and produces no error anywhere | Validate `turnBurstIntervalMs <= vehicularIntervalMs` (or `intervalMs` when adaptive cadence is off), and `> 0`. Skipped entirely when `turnBurst = false` | `TrackItConfig` | T2 |
| EC-122 | `setConfig()` called while tracking | Half-applied config, provider restarted mid-fix | Applied atomically; provider restart is debounced; the config snapshot on the session records the change with a timestamp | `TrackIt` | T2 |
| EC-123 | `ready()` called twice with different config | Ambiguous state | Second call applies as `setConfig`; documented | `TrackIt` | T2 |
| EC-124 | Config persisted from a previous SDK version has unknown keys | Deserialise failure blocks startup | Forward-compatible decoding; unknown keys dropped with a log | `ConfigStore` | T2 |
| EC-125 | Host expects `stopOnTerminate=true` default (as the reviewed design document proposes) | Tracking silently dies on swipe-away | Default is **`false`** with `startOnBoot=true` — for a background tracking SDK the opposite defaults are a footgun | `TrackItConfig` | doc |

## 11. Device & OEM specifics (field matrix)

| # | Case | Handling |
|---|---|---|
| EC-126 | Xiaomi/MIUI "Autostart" disabled | Restore worker and boot receiver never fire. Detect via a liveness heuristic; expose `Error(TRACKER_DEAD)`; document the OEM setting in `INTEGRATION-ANDROID.md` |
| EC-127 | Samsung "Put unused apps to sleep" | Same; same handling |
| EC-128 | Huawei without GMS | Fused unavailable → platform `LocationManager` fallback (EC-19) |
| EC-129 | Devices with barometer absent | Altitude/floor features degrade silently; never required |
| EC-130 | Low-RAM devices (`ActivityManager.isLowRamDevice`) | Reduce ring buffers and decision retention automatically |
| EC-131 | Emulator / no GNSS hardware | Fixes come from the network provider only; NLP gate would reject everything → detect emulator in debug builds and log a loud hint rather than silently producing an empty track |

## 12. Device sensors

See [SDK-COMPARISON.md §6](SDK-COMPARISON.md) for the design. Every sensor path is optional and degrades to the position-only behaviour above when unavailable.

| # | Trigger | Unhandled symptom | Handling | Owner | Test |
|---|---|---|---|---|---|
| EC-132 | `TYPE_SIGNIFICANT_MOTION` fires once and is never re-armed | Wake path silently dies after the first transition — trigger sensors are one-shot by contract | Re-arm inside `onTrigger`; disarm on `MOVING` and in `stop()`/`onDestroy` | `SignificantMotionWake` | T3 |
| EC-133 | Step detector present but `ACTIVITY_RECOGNITION` denied | `SecurityException` on register, or silent no-events | Probe permission **and** availability at `ready()`; stage 2a is skipped entirely, not partially | `StepCorroborator` | T2 |
| EC-134 | Accelerometer burst requested while the device is asleep | Burst returns nothing; phantom-Doppler veto blocks the pipeline | Veto is best-effort with a 1 s timeout; absent data ⇒ fall back to the displacement-only correction | `AccelerometerVeto` | T2 |
| EC-135 | Barometer reports a pressure change from weather, not altitude | A long dwell is misread as an elevator | Only a **monotonic** change > 0.4 hPa inside a signal gap counts; weather drift is slow and non-monotonic over minutes | `BarometerHint` | T1 |
| EC-136 | `STEP_COUNTER` resets on reboot (counter is since-boot by contract) | Huge negative step delta | Use `STEP_DETECTOR` events for deltas; treat any negative `STEP_COUNTER` delta as a boot boundary and re-baseline (pairs with EC-29) | `StepCorroborator` | T1 |
| EC-137 | Device has no accelerometer at all, or AR denied with no trigger sensor | Motion-gated modes run on hardware that cannot support them; user sees random gaps | `motionQuality = POOR` → force `CONTINUOUS`, emit `Error(MOTION_DETECTION_DEGRADED)` naming the missing sensors | `SensorProbe` | T2, T4 |
| EC-138 | Sensors left registered after `stop()` | Battery drain with no session — the failure users blame the SDK for | Registration is session-scoped; `stop()` and `onDestroy` unregister sensors in the same teardown as the location stream | `SensorProbe` | T3 |

---

## Acceptance criteria derived from this catalogue

These are the pass/fail gates for the field matrix (T4):

1. **Steady 2 hours ⇒ exactly 1 stored point.** Decision log dominated by `Drift Suppressed` / `HeartBeat Skipped` / `Sigma Gate Outlier`. (EC-38, EC-39, EC-48)
2. **30-min urban drive ⇒ 25–35 stored points, zero `Sigma Forced Reset`.** The forced-reset count is the headline number here: under the scalar filter a straight road produced one every four fixes, and each one is a visible jump in the plotted track. (EC-43, EC-44, EC-44a)
3. **Walk to lunch and back ⇒** `Origin Set` → `Departure Held` → accepts → `Walk Arrival`, mirrored on return, **with `Walk Arrival` appearing only at the ends**. A walk that stores nothing, or that repeats `Walk Arrival` mid-route, is EC-39a/EC-39c regressing. (EC-39, EC-46)
3a. **Mixed drive → park → walk → park → drive ⇒** every leg stores points. The `walk` leg storing zero while the drives store normally is the exact signature of EC-39a, and is what `MixedModeTraceTest` pins on the JVM. (EC-39a, EC-39b, EC-39c)
4. **Elevator gap ⇒** `Recovery Held` then `Recovery Confirmed`; no teleport in the plotted track. (EC-40, EC-41)
5. **Force-stop → relaunch ⇒** session detected as interrupted; filter re-seeded from the stored anchor; the first fix is *judged*, not blind-accepted. (EC-51, EC-66)
6. **Reboot mid-session ⇒** tracking resumes within one watchdog tick. (EC-65)
7. **Airplane mode 10 min ⇒** no phantom points on re-acquisition. (EC-40)
8. **Same fixture replayed twice ⇒ byte-identical decision sequence.** (EC-52, A10)
