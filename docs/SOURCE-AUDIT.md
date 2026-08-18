# Source Audit — reference location stack

Findings from a line-by-line read of the reference implementation (2026-07-30). Every item cites `file:line` within the reference source tree. These are the things Traker must **fix**, not port. Each has a corresponding entry in [EDGE-CASES.md](EDGE-CASES.md).

Verdict legend: **DEFECT** = wrong behaviour, reproducible · **HAZARD** = correct today, breaks under a plausible change · **SMELL** = works, but must not be carried into a public SDK.

---

## A1 — DEFECT: the fix clock is object-construction time, not the GPS fix time

`LatLng.startTime` defaults to `TimeUtil.currentTime` (`utility/location/LatLng.kt:81`). `LatLngFactory.from(location)` (`LatLng.kt:338-352`) copies `speed`, `provider`, `accuracy`, `altitude`, `hasBearing`, `hasSpeed` — **but never `location.time`**. `LocationUpdateViewModel.toLatLng()` (`database/viewmodel/LocationUpdateViewModel.kt:252`) is just `LatLngFactory.from(this)`.

So the value that flows into the entire filter as the fix clock is *when the Kotlin object was constructed*:

```kotlin
// LocationUtil.kt:232
val timeDeltaSec = (startTime - past.startTime) / 1000f
// LocationUtil.kt:325
val processingGapSec = (startTime - kalman.getTimeStamp()) / 1000f
// LocationUtil.kt:191,236,309,344,358,419
kalman.setState(latitude, longitude, accuracy, startTime)
```

and into `MapUtils.getMovementSpeed` (`utility/googleMap/MapUtils.kt:37`) which derives `calcSpeedMps` from the same field.

**Consequences.** For a single live fix, construction time ≈ delivery time, so the error is a latency offset and mostly harmless. It stops being harmless when:
- fixes arrive **batched** (`setMaxUpdateDelayMillis(60_000)`, `providers/base/LocationProviderBase.kt:117`) — every member of a batch is constructed within milliseconds, so Δt collapses toward 0 and `calcSpeed` → 0 for all of them;
- the point comes **from the database** (see A2);
- the OS delivers a **cached** fix on resume — the 60 s `elapsedRealtimeNanos` gate at `providers/BackgroundLocationProvider.kt:325-329` catches this, but only on the background stream, not on the one-shot path.

**Traker fix.** `TrackFix` carries three clocks and the filter uses the monotonic one:
`timeMs` (= `Location.getTime()`, wall clock, for storage/display), `elapsedRealtimeNanos` (**all Δt, gaps, and age arithmetic**), `receivedAtMs` (diagnostics only). Wall-clock time never participates in filter math.

---

## A2 — DEFECT: restoring `past` from the database produces `startTime = now`

`Location.toLatLng()` (`database/dao/Location.kt:126-136`) maps `latitude/longitude/address/accuracy/movementSpeed/provider/hasBearing/hasSpeed` — **and does not map `time`**. It goes through `LatLngFactory.create()`, so the resulting `LatLng.startTime` is `TimeUtil.currentTime`.

That is exactly the value the service uses as the process-death anchor:

```kotlin
// service/AttendanceLoggerService.kt:310-315
lastKnownLocation = (LocationRepository.getLastInsertedLocation()?.toLatLng()
    ?: CompanyAttendanceMediator.getLastKnown())
    ?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }
```

**Consequence.** After every process death, the restored `past` claims to have been observed *now*. Then in `isKalmanFilteredLocation`:

- `timeDeltaSec ≈ 0` (or negative, since `past` is built *after* the incoming fix in some interleavings). Negative lands on `LocationUtil.kt:235-239` — `setState` + `setOrigin` + **`return true`**, an unconditional accept with no accuracy, NLP, sanity, or sigma check. That is precisely the "post-restart NLP fix uploading as a blind Init" the resume path at `LocationUtil.kt:197-213` was written to prevent.
- If Δt lands slightly positive: `isSignalGap` is false and `processingGapSec` is measured against a filter that was just seeded with `past.startTime = now`, so `isRecoveryNeeded` (`LocationUtil.kt:327-334`) can never fire. A genuine "phone was off for two hours and moved 40 km" resume is then handled by the sigma gate burning `maxRejects` and force-resetting — the slow path, with 2–4 fixes discarded.
- `calcSpeedMps` is 0 because `MapUtils.getMovementSpeed` returns `0f` when `timeElapsedSec < 1.0` (`MapUtils.kt:40-45`), so Stage 2's GPS-trust override and the NLP vehicular bypass are both disabled on the first post-restart fix.

**Note the asymmetry:** `CompanyAttendanceMediator.getLastKnown()` (`ui/company/network/repo/CompanyAttendanceMediator.kt:286-292`) returns a *persisted* `LatLng` whose `startTime` round-tripped through ObjectBox, so it is correct. The DB path is wrong and the mediator path is right, and the service prefers the wrong one.

**Traker fix.** `filter_state` is a first-class persisted row (position, variance, timestamp, origin, movingMode, recovery-pending, motion state). `ready()` restores it before any fix is processed. The anchor's timestamp is the stored fix timestamp, always.

---

## A3 — DEFECT: the two capture entry points feed the filter different `past` anchors

| Entry point | `past` source |
|---|---|
| `AttendanceLoggerService.updateLocation` (`service/AttendanceLoggerService.kt:310-315`) | last inserted DB row → `toLatLng()`, else mediator `getLastKnown()` |
| `UpdateLocationWorker.doWork` (`network/worker/UpdateLocationWorker.kt:49-50`) | `CompanyAttendanceMediator.getLastKnown()` **only** |

Both then call `isKalmanFilteredLocation(past = …, kalman = kalmanFilter)` against the **same** static filter (`AttendanceLoggerService.kt:1053`, imported by the worker at `UpdateLocationWorker.kt:23`).

**Consequence.** `distanceMoved`, `timeDeltaSec`, `calcSpeedMps`, and every gate derived from them differ depending on which entry point happened to run. `getLastKnown()` is only updated on attendance-record writes, so it lags the last inserted fix; a worker-delivered fix is therefore judged against a stale anchor while the shared Kalman state reflects the fresher one. Interleaved, the two disagree about how far the user moved.

**Traker fix.** One `FixIngestor` actor with a single `Channel<TrackFix>`. Stream, one-shot, backstop, and manual insert all send to that channel. `past` is a field of the actor, never re-derived per call site.

---

## A4 — DEFECT: batched fixes are silently discarded

```kotlin
// providers/BackgroundLocationProvider.kt:158-165
override fun onLocationResult(locationResult: LocationResult) {
    locationResult.lastLocation?.let { updateBackgroundLocation(location = it) }
}
```
Same at `providers/CurrentLocationProvider.kt:212-219`.

The request sets `setMaxUpdateDelayMillis(60_000L)` (`providers/base/LocationProviderBase.kt:117`), which explicitly asks the OS to **batch**. `LocationResult.getLocations()` returns every fix in the batch, oldest first; `lastLocation` returns one. On a drive through a Doze window, a batch of 4–6 fixes collapses to 1 — the exact samples the turn geometry needs.

**Interaction with A5:** iterating the batch as-is would immediately hit the 500 ms burst gate, because that gate is keyed on delivery time. So the two bugs mask each other, and fixing one without the other makes things worse.

**Traker fix.** Iterate `locationResult.locations` sorted by `elapsedRealtimeNanos`; the burst gate keys on fix time, so a legitimate batch passes and a genuine double-fire does not.

---

## A5 — DEFECT: the burst gate uses wall-clock delivery time

```kotlin
// LocationUtil.kt:169
private var lastProcessingTime: Long = 0L
// LocationUtil.kt:178,183-186
val currentTime = TimeUtil.currentTime
if (currentTime - lastProcessingTime < BURST_REJECTION_MS) { … return false }
```

Two problems:

1. **It is a static field on an `object`**, shared by the service and the worker — global mutable state with no synchronisation, mutated from `Dispatchers.Main.immediate` (service) and `Dispatchers.Default` (worker).
2. **It measures the interval between *processing* calls, not between *fixes*.** Two fixes 45 s apart delivered in one batch are 2 ms apart by this clock and the second is rejected as a "burst".

**Traker fix.** Burst gate compares `fix.elapsedRealtimeNanos` against the previous *fix's* elapsed nanos. The dedupe key is `elapsedRealtimeNanos`, which is unique per fix and monotonic.

---

## A6 — HAZARD: shared Kalman state across coroutine contexts, by admission

`KalmanLatLngFilter` carries 12 `@Volatile` fields (`utility/location/utils/KalmanLatLngFilter.kt:14-51`). The class comment says it plainly:

> *"Compound updates go through the helpers below; they are not atomic across fields, which is acceptable under the same 'service & worker rarely overlap' assumption that already governs `consecutiveRejectCount`."*

`setOrigin()` writes five fields non-atomically (`KalmanLatLngFilter.kt:54-60`). A concurrent reader can observe a new `originLat` with a stale `prevNetMeters`, which flips the `netGrew` decision at `LocationUtil.kt:591` and either suppresses a real departure or admits a drift step.

"Rarely overlap" is a scheduling assumption, not an invariant — the 15-min worker and the 60 s stream *will* collide roughly once per hour by construction.

**Traker fix.** No shared mutable filter. State lives inside the single ingest actor; `@Volatile` disappears; the state object is an immutable `data class` replaced wholesale per fix, which also makes it trivially serialisable to `filter_state`.

---

## A7 — HAZARD: the sigma gate's prediction uses the *previous* fix's Q

```kotlin
// LocationUtil.kt:374-378
val predictedVariance = (kalman.getVariance() +
        (timeSinceLastUpdateMs * kalman.qMetresPerSecond * kalman.qMetresPerSecond) / 1000f)
```

`kalman.qMetresPerSecond` is assigned inside `processFilter()` (`KalmanLatLngFilter.kt:123`), i.e. by whichever fix was last *accepted*. The gate for the current fix is therefore widened or narrowed by the motion class of a previous, possibly very different fix — e.g. a stationary fix (`Q = 0.0001`) tightens the gate for the first fix of a drive to `3σ ≈ 0`, so the gate degenerates to `1.5·accuracy + 200 m` and the departure is judged almost entirely by the flat term.

Also note the gate re-implements the prediction inline instead of using a `predictedSigma()` helper, so the two copies of the maths can drift.

**Traker fix.** `predictSigma(atNanos, q)` takes `q` explicitly; the gate passes the Q it computed for *this* fix from `effectiveSpeed`, before the accept decision.

---

## A8 — DEFECT: `hasSpeed` / `hasBearing` default to `true`

```kotlin
// utility/location/LatLng.kt:94-95
var hasBearing: Boolean = true,
var hasSpeed: Boolean = true,
// database/dao/Location.kt:73-74  — same defaults
```

Stage 1.5 rejects a fix when `!hasSpeed && !hasBearing` (`LocationUtil.kt:218`). Any construction path that forgets to set these — and `LatLngFactory.create()` is such a path, used by `Location.toLatLng()` — yields a point that claims hardware speed and bearing it never had. The NLP-fallback gate, the single most important defence against WiFi-positioning teleports, silently becomes a no-op.

**Traker fix.** `hasSpeed` / `hasBearing` default to **`false`** and are populated only by the `android.location.Location` mapper. A fix with no flags is treated as network-derived, which is the safe direction.

---

## A9 — SMELL: two different arrow-spacing ladders

| Function | z≥18 | z≥15 | mid | else |
|---|---|---|---|---|
| `splitSegmentForArrows` (`utility/googleMap/MapOverlayUtils.kt:405-411`) | 80 m | 300 m | **z≥12 → 1500 m** | 10000 m |
| `calculateZoomAdaptiveArrows` (`MapOverlayUtils.kt:501-506`) | 80 m | 300 m | **z≥13 → 800 m** | 4000 m |

The first runs on initial draw, the second on the zoom-change path (`MapOverlayUtils.kt:464-492`), so arrow density visibly changes after the first pinch even when returning to the original zoom. Arrow half-length also differs (`±0.01 m` vs `±0.1 m`, lines 418-419 vs 546-547).

**Traker fix.** One pure `Arrows.place(path, zoom, options): List<ArrowAnchor>` in `fieldtrack-geo`, unit-tested, and it is the same function that produces the `arrows[]` array in the exported JSON. Renderers cannot diverge from the export because there is one implementation.

---

## A10 — SMELL: the plotting stage mutates its input

- `filterLocationForStopsAndPunches` writes into the caller's list: `entryPoint.latitude = lastKnown.latitude` (`ui/company/network/viewmodel/EmployeeLocationHistoryViewModel.kt:679-680`).
- `buildNodeSegment` writes `points[start].nodeNumber = …` and `points[endIndex].nodeNumber = …` (`EmployeeLocationHistoryViewModel.kt:953, 1054, 1112`).
- `RoadSnapperV2.reconstructPath` overwrites `currentRaw.latitude/longitude/activityStatus` in place (`utility/location/routing/processing/RoadSnapperV2.kt:98-105`).

Re-running the pipeline on the same list therefore produces different output the second time — snapped coordinates get re-snapped, node numbers get reassigned. It also makes `activityStatus` do double duty as both a captured field (`"gps@moving"`) and a render tag (`"snapped_to_road"`, `"raw_punch"`, `"rounded_curve"`).

**Traker fix.** `fieldtrack-geo` is pure: every stage takes `List<TrackPoint>` and returns a new list. Render tags live in a separate `RenderTag` enum on the output type, never on the stored point.

---

## A11 — DEFECT: `getSubPath` does identity lookup on floating-point coordinates

```kotlin
// RoadSnapperV2.kt:205-210
val startIndex = path.indexOf(start)
val endIndex = path.indexOf(end)
if (startIndex == -1 || endIndex == -1 || startIndex >= endIndex) return emptyList()
```

`start`/`end` come from `findClosestOnPath`, which returns an element of `path`, so `indexOf` usually succeeds — but `LatLng.equals` is value equality on doubles, and a road geometry that revisits a coordinate (roundabout, out-and-back spur) returns the **first** match, so the sub-path can be empty or reversed. Cost is O(n) per lookup inside an O(n) loop over raw points, i.e. O(n²) per chunk on top of the O(n²) already in `findClosestOnPath`.

**Traker fix.** `findClosestOnPath` returns `(index, point, distance)`; the sub-path is a plain `subList(i+1, j)`.

---

## A12 — HAZARD: activity-recognition snapshot subscription can leak

`registerActivityTransitions` requests a one-shot snapshot with `requestActivityUpdates(0, pendingIntent)` — detection interval **0 ms** (`utility/location/activityrecognition/ActivityTransitionManager.kt:106`). It is cancelled by `stopSnapshotUpdates()`, which the KDoc says must be called by the receiver "after the first `ActivityRecognitionResult` has been processed" (`ActivityTransitionManager.kt:129-159`).

If that intent never arrives — permission revoked between register and delivery, GMS unavailable, process killed before the broadcast — nothing cancels it and GMS keeps firing at maximum rate for the life of the app. The KDoc names the consequence: *"draining battery and flooding the receiver"*.

**Traker fix.** Arm a 30 s watchdog alongside the snapshot request that cancels it unconditionally, plus cancel in `stop()` and in the service's `onDestroy`.

---

## A13 — HAZARD: force-capture starts a foreground service from a broadcast receiver

```kotlin
// ActivityTransitionManager.kt:209-215
private fun triggerImmediateLocationCapture() {
    val ctx = appContext ?: return
    if (!AttendanceLoggerService.running) return
    ContextCompat.startForegroundService(ctx, intent)   // ACTION_FORCE_CAPTURE
}
```

The `running` guard makes this *usually* safe, because starting an already-foreground service is allowed. But `running` is a plain `var` on a companion object set in `onStartCommand` and cleared in `onDestroy` (`AttendanceLoggerService.kt:1031, 1009`) — between the OS killing the service and `onDestroy` running, `running` is stale `true`, and `startForegroundService` from a background broadcast then throws `ForegroundServiceStartNotAllowedException`. There is no try/catch at this call site.

**Traker fix.** Force-capture is delivered over an in-process `MutableSharedFlow` that the running service collects. No `Intent`, no FGS start, nothing to throw. If the service is not running there is simply no collector.

---

## A14 — SMELL: `START_STICKY_COMPATIBILITY`

`LocationService.onStartCommand` returns `START_STICKY_COMPATIBILITY` (`utility/location/service/LocationService.kt:63`), as do two branches of `AttendanceLoggerService` (`AttendanceLoggerService.kt:169, 177`). That mode restarts the service but **does not redeliver the Intent and does not guarantee `onStartCommand` is called** — so an action-dispatched service silently comes back in an unconfigured state.

**Traker fix.** `START_STICKY` with a state machine that re-reads its intent from persisted config on a null-intent restart. `stopWithTask="false"` as in `AndroidManifest.xml:579`.

---

## A15 — DEFECT: dead conditional in `isBetterAndBestLocation`

```kotlin
// LocationUtil.kt:156-159
if (!hasMoved) {
    if (isMoreAccurate) return false
    return false
}
```

Both branches return `false`. A stationary fix with materially better accuracy is always dropped. This function is not on the tracking path — it gates `LocationService` (meeting auto-attend, `LocationService.kt:88`) — but it is exported as a public utility and reads as if the accuracy branch does something.

**Traker fix.** Not ported. Its job (pick the better of two fixes) belongs to the acceptance pipeline.

---

## A16 — Behavioural: background permission is a hard gate on tracking

```kotlin
// AttendanceLoggerService.kt:894-903
private fun areLocationPermissionAOkay(): Boolean {
    val permission = if (SDK_INT >= Q) ACCESS_BACKGROUND_LOCATION else ACCESS_FINE_LOCATION
    return PermissionChecker.checkSelfPermission(this, permission) == PERMISSION_GRANTED
}
```

Called in `onCreate` (`:119-122`); if false the service stops immediately. On Android 10+ a user who granted "While using the app" gets **no tracking at all**, not even while the app is open.

That is a defensible product decision for attendance. It is the wrong default for a general-purpose SDK.

**Traker fix.** Three-tier degradation, surfaced as `ProviderState`:
`FULL` (background granted) → continuous background tracking · `FOREGROUND_ONLY` → track while the app is visible, emit `Error(BACKGROUND_PERMISSION_MISSING)`, auto-resume on grant · `NONE` → refuse `start()` with a typed error.

---

## A17 — Missing: no mock-location detection anywhere

`Location.isMock` (API 31+) / `isFromMockProvider` is never consulted in the capture path. The spec's own §13.9 lists this as a "recommended addition". For an SDK sold on data integrity it is table stakes.

**Traker fix.** `mockLocationPolicy = FLAG | REJECT | ALLOW`, default `FLAG`; the flag is persisted on the point and exported in the JSON.

---

## A18 — Comment/code drift worth noting during the port

- `LocationUtil.kt:409-410`: comment says *"worse than our absolute maximum (125f)"*, code compares against `ACCURACY_MAX_VEHICULAR` = **85f**.
- `LocationUtil.kt:53-56`: `BAD_NETWORK_ACCURACY_M`, `BAD_GPS_ACCURACY_M`, `MAX_POSSIBLE_SPEED_M_S`, `MAX_ACCELERATION_M_S2` are Gen-1 constants only used by `isBetterAndBestLocation` / `isSpeedSpike` / `isImpossibleJump` — not by the production pipeline. Porting them into the SDK's constants file would imply they are live.
- `isKalmanFilteredLocationBackup` (`LocationUtil.kt:695`, ~350 lines) is explicitly marked *"Currently unused. REVERT TARGET"*. Do not port.
- `switchModeIfValid` / `switchToHighAccuracy` / `switchToLowPower` (`BackgroundLocationProvider.kt:395-413`) are reachable only from a commented-out block at `:376-382`. The spec says *"A speed-based high/low-power mode switch exists in the reference code but is disabled; do not port it."* Confirmed dead.

---

## A19 — DEFECT: stop consolidation groups points that are moving

Spec §17: *"greedily group consecutive points within **60 m of the running centroid**"*, then *"shorter → emit only the group's first point"* (`EmployeeLocationHistoryViewModel.kt:679-700`).

Nothing in that rule consults motion, and the centroid is a running mean of the group's members — so it *follows* whatever it is grouping. A mean trails the newest point by roughly half the group's span, which means a vehicle laying points 40 m apart stays inside a 60 m radius for two or three fixes at a time. Each of those runs collapses to its first point.

The stage is described as the last line of defence against stationary drift, and against stationary drift it is correct. It runs over the whole day, though, moving points included, and there it silently halves the track. Replaying a field capture through it: **13 of 28 stored points destroyed, every one of them moving at 5–10 m/s on open road.** The drawn line jumps between the survivors and goes straight where the deleted vertices held a bend. A five-minute stop was swallowed into a group anchored 200 m earlier, so the line cut a diagonal across the car park.

Worth being precise about where the damage happens: the capture pipeline stored all 28 points correctly. This is a plotting-plane defect, and it is invisible from the capture logs — which is why it survived a full field diagnosis that had already fixed five real capture bugs.

**Traker fix.** A moving point (`speedMps ≥ 1.0 m/s`, or `movementStatus == MOVING`) never joins a dwell, and the radius is measured from the group's **first** point rather than a mean that chases it. The centroid is still the mean and still reports the dwell's position — it no longer decides membership (EC-139).

Related, same stage: §17 reads dwell off the group's own first and last points. That is honest only if sampling continued throughout, and the pipeline deliberately does the opposite — an hour parked can leave two fixes 30 s apart and report 30 s, so the stop is never plotted. Traker measures a dwell to the start of the next group; the recorded span is only the floor (EC-139a).

---

## A20 — GAP: nothing recognises a stop that was recorded as silence rather than as points

Not a defect in the reference so much as a hole between two of its stages, and the hole only opens because Traker's capture side is more aggressive than the reference's.

Once a device settles, the acceptance gates reject almost everything. A stop therefore leaves no cluster for §19 to classify — it leaves a *hole* between two fixes that both belong to the drive. Net displacement across that hole clears the 100 m travel threshold easily, so the span reads as travel however it is classified as a whole, and the plotted line runs through the car park at an average speed nobody drove.

§18's **gap-stop protection** looks like the answer and is not. It asks whether a silence should stop a node cluster from ending, and its answer *suppresses a boundary*; it never creates a stop segment. It also requires ten minutes (`GAP_STOP_SEC`), and on the capture that motivated this the stop was six.

**Traker fix.** `Clusters.build` splits a span at a **dwell gap**: `Δt ≥ 180 s` **and** implied speed `≤ 35 m/min`. Implied speed rather than a radius, because the first fix good enough to catch a departure is rarely the one at the kerb — 158 m out on the capture — while 158 m in 363 s is 0.44 m/s whatever the geometry. A genuine blackout at 8 m/s stays travel and A-side carry-forward (EC-99) still owns it (EC-140).

The speed bar is **derived** from §18's `GAP_SPEED_M_PER_MIN`, not restated. Two constants that both mean "too slow to have been travelling" are one edit away from disagreeing and nothing in the build would notice. The durations differ on purpose: a timeline node has to earn its place and ten minutes is a fair price, but a six-minute stop still has to stop the line being drawn through it.

One consequence for hosts: a `stop` segment carries no geometry, so **the drawn line breaks across it**, and the break is as wide as the departure fix was late. The SDK will not draw a leg it has no evidence for; renderers that want continuity bridge it themselves ([POLYLINE-JSON.md](POLYLINE-JSON.md)).

---

## Summary — what this changes in the plan

| Finding | Plan consequence |
|---|---|
| A1, A2, A5 | Monotonic clock (`elapsedRealtimeNanos`) is the **only** time source for filter math. Wall clock is display/storage only. Kills the whole clock-skew edge-case class. |
| A2 | `filter_state` persisted table, restored in `ready()` before the first fix. Non-optional. |
| A3, A6 | Single-consumer `FixIngestor` actor. Immutable `FilterState`. No `@Volatile`, no statics. |
| A4, A5 | Iterate `locationResult.locations`; dedupe and burst-gate on fix time. |
| A8 | `hasSpeed`/`hasBearing` default `false`. |
| A9, A10, A11 | All geometry pure and in `fieldtrack-geo`; one arrow implementation shared by renderer and JSON export. |
| A12, A13, A14 | In-process force-capture; snapshot watchdog; `START_STICKY`. |
| A16 | Three-tier permission degradation instead of a hard gate. |
| A17 | Mock-location policy in v1. |
| A18 | Constants file contains only live constants; dead paths are not ported. |
| A19, A20 | The plotting plane must know whether the device was moving. Dwell consolidation groups only stationary points and anchors its radius; cluster construction splits a span at a silence that reads as stationary. Both stages had rules that were right for a parked phone and destructive for a moving one — and neither is visible from capture-side logs, so the plotting plane needs its own field replay. |
