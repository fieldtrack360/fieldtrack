# Gap Analysis — reference spec vs. implementation

**Audited 2026-08-10 against `a6f9ce3`.** Every finding below was verified in source; each cites `file:line`. Part C (source protection and distribution, G-29…G-36) added in the same pass.

Three axes are audited, and they are different questions:

- **Part A — spec conformance.** [reference/capture-and-plotting-spec.md](reference/capture-and-plotting-spec.md) describes behaviour TrackIt set out to reproduce. What of it is not reproduced?
- **Part B — self-conformance.** TrackIt's own public surface promises things. What does it promise and not do?
- **Part C — source protection.** The published artifacts are intended to protect the implementation. What actually reaches a consumer?

Part B matters more than it looks. A missing spec feature is a known absence; a config field that reads as supported and silently does nothing is a *false* affordance, and a host has no way to discover it except in the field.

Part C was added 2026-08-10 and produced the most consequential single finding in this document. The R8 configuration is careful, thoroughly reasoned and correctly applied — to the wrong module.

> **On the source document.** `Location-Tracking-and-Plotting-RN-Spec.md`, circulated separately, is the pre-strip original of the in-tree spec — the version that still targeted React Native across iOS and Android. Diffed against `reference/capture-and-plotting-spec.md` it produces 32 hunks, every one of them platform framing: the §26.1 library table, the §26.2 iOS strategy, the §26.4 TypeScript module layout, and two provenance notes TrackIt added. **Every algorithm, constant, threshold and rendering rule is identical.** It introduces no requirement this document does not already cover, and it is not a second source of truth.

**Verdict legend**

| Verdict | Meaning |
|---|---|
| **BROKEN CHAIN** | Wired correctly at every layer and never fed at the source. Compiles, runs, produces nulls. The most expensive class here, because nothing fails. |
| **MISSING** | Spec behaviour with no implementation. |
| **DIVERGENT** | Implemented, but not as specified. May be a better answer; it is not the specified one, and nothing recorded the decision. |
| **EXPOSURE** | A published artifact reveals more than intended. Part C only. |

---

## Summary

| # | Area | Verdict | One line |
|---|---|---|---|
| [G-1](#g-1) | §10 · §21 | BROKEN CHAIN | Activity is never stamped on a point, so every label falls back to speed buckets |
| [G-2](#g-2) | §11.1 | BROKEN CHAIN | Battery and charging state are never captured |
| [G-3](#g-3) | §10 | MISSING | Activity segments: table, entity and DAO exist with no reader or writer |
| [G-4](#g-4) | §3.4 · §12.2 | **FIXED** 17 Aug 2026 | Nothing ever triggers an upload |
| [G-5](#g-5) | §10 | MISSING | The AR snapshot is cancelled by a watchdog but never requested |
| [G-6](#g-6) | §10 | MISSING | Activity confidence is hardcoded to 0; both thresholds are unread |
| [G-7](#g-7) | §12.2 | MISSING | No wake lock anywhere — the OEM soft-wake does not exist |
| [G-8](#g-8) | §13 | DIVERGENT | The "full local wipe" on 401 marks rows synced; it deletes nothing |
| [G-9](#g-9) | §9 | MISSING | Capture-time significant-stop stamping (`isShowPin`) |
| [G-10](#g-10) | §9 | DIVERGENT | `activityStatus` is synthesised at upload, never stored |
| [G-11](#g-11) | §11.1 | MISSING | `mobileServiceStatusFlag` |
| [G-12](#g-12) | §19 · §24 | BROKEN CHAIN | `travelStartMs` is computed and then dropped before export |
| [G-13](#g-13) | §25 | MISSING | `isInPlaceWander` is declared with zero callers |
| [G-14](#g-14) | §25 | DIVERGENT | Total distance uses a different rule than the spec's |
| [G-15](#g-15) | §23.3 | MISSING | Pulsing ground overlay on the live node |
| [G-16](#g-16) | §23.1 | MISSING | Chunked polyline add |
| [G-17](#g-17) | §23.3 | MISSING | Info-window content is a title and a duration |
| [G-18](#g-18) | §23.4 | MISSING | Max-zoom preference |
| [G-19](#g-19) | API | MISSING | The foreground-service notification cannot be configured |
| [G-20](#g-20) | API | BROKEN CHAIN | `persistDecisions = false` has no effect |
| [G-21](#g-21) | API | BROKEN CHAIN | `cadenceTierMs` is plumbed end to end and read by nothing |
| [G-22](#g-22) | API | MISSING | 14 further config fields are declared and unread |
| [G-23](#g-23) | API | MISSING | `TrackItState.motionState` never updates |
| [G-24](#g-24) | API | MISSING | `Watchdog.Action.ReportDead` is returned and not handled |
| [G-25](#g-25) | API | DIVERGENT | `ProviderStateMonitor` watches one AppOp and is never stopped |
| [G-26](#g-26) | Build | MISSING | `trackit-bridge` does not exist; the React Native package cannot compile |
| [G-27](#g-27) | Build | MISSING | No tests exist, in the tree or in history |
| [G-28](#g-28) | Sample | MISSING | `installRoadSnapping()` is an empty stub |
| [G-29](#g-29) | Publishing | **EXPOSURE** | **`trackit-geo` ships completely unobfuscated. R8 never runs on it** |
| [G-30](#g-30) | Publishing | EXPOSURE | Kotlin `@Metadata` hands over the API and its default values without decompilation |
| [G-31](#g-31) | Publishing | EXPOSURE | `TrackItConstants` is `public`, so its defaults *are* the shipped tuning table |
| [G-32](#g-32) | Publishing | MISSING | The R8 `mapping.txt` is not archived per release, so field crashes cannot be retraced |
| [G-33](#g-33) | React Native | EXPOSURE | The RN Android module ships as Kotlin source, necessarily. Correctly mitigated, nowhere recorded |
| [G-34](#g-34) | React Native | MISSING | The npm package has no `files` allowlist and no `.npmignore` |
| [G-35](#g-35) | React Native | EXPOSURE | The npm package will ship JS/TS source; the wire contract becomes fully visible |
| [G-36](#g-36) | Publishing | DIVERGENT | Three-way tension between JVM testability, module optionality and obfuscation — undocumented |

---

# Part A — Spec conformance

## Tier 1 — broken chains

The pattern is identical in all four: the value has a field on `IngestContext`, a mapping in the pipeline, a column in Room, and a mapper on both sides — and no producer.

### G-1 — Activity is never stamped on a point {#g-1}

**Verdict: BROKEN CHAIN.** §10 requires that "at store time, the point gets `detected_activity_type` (the segment label active at fix time) and `detected_activity_start_time`", and §21 builds the whole labelling model on those fields.

`FixIngestor.contextFor()` (`trackit-core/.../capture/FixIngestor.kt:294-307`) constructs `IngestContext` with eight arguments and omits `detectedActivity` and `activityStartTimeMs`. Both take their defaults — `null` and `0`.

Everything downstream is correct and carries the nulls faithfully: `AcceptancePipeline.kt:940-941` copies them onto the `TrackPoint`, `RoomPointStore.kt:112-113` onto the raw-point row, `Mappers.kt:43-44` back out again.

**Consequences, in order:**

1. Every stored `TrackPoint` has `detectedActivity = null`.
2. `TrackBuilder.dominantActivity(cluster)` therefore always returns `null`.
3. `ActivityLabels.label(null, max, p75)` always falls through to `speedBucket(...)` — so §21's detected-label path, and the override rule that is the point of it, **can never execute**.
4. §19's STILL-override is unreachable: there is no STILL label to distrust.
5. `TrackStats.activityBreakdownSec` is always speed-derived.

The system is not wrong, it is *blind*: it produces plausible labels from speed alone and never consults the sensor that exists to disambiguate 6 m/s cycling from 6 m/s driving. `ActivityRecognizer` runs, registers, and emits `ActivityChange` events the whole time — the events reach the host and the motion controller, and never reach a point.

**Fix:** `MotionController` already tracks the current activity. Thread it (and its segment start) onto `FixIngestor` the way `stepsSinceLastPoint` is threaded — a callback set in `StartTrackingUseCase`, read in `contextFor()`. Four lines. Depends on [G-3](#g-3) for the segment start time to mean anything.

### G-2 — Battery and charging state are never captured {#g-2}

**Verdict: BROKEN CHAIN.** §11.1 lists `batteryPercentage` as a per-point diagnostic. Same root cause as G-1: `contextFor()` omits `batteryPct` and `isCharging`.

`TrackPoint.batteryPct` is therefore always `null`, and `trackit-sync` ships `"battery_percentage": null` on every uploaded point (`SyncTransport.kt`, `SyncPoint.battery_percentage`).

Cost is diagnostic, not functional — but this is the field that answers "did the tracker die because the OEM killed it, or because the phone was at 3 %", which is the first question asked of every field gap.

### G-3 — Activity segments are never written {#g-3}

**Verdict: MISSING.** §10 specifies segments: an ENTER closes any open segment and opens `{activityType, startTimeMs, isOngoing}`, an EXIT closes the matching one, and segments older than 24 h are auto-closed on restore.

The storage for this is complete. `ActivitySegmentEntity` (`data/db/Entities.kt:233-240`), `ActivitySegmentDao` with `insert` / `openSegment()` / `close(id, endTimeMs)` / `autoCloseStale(cutoffMs, nowMs)` / `range(from, to)` (`data/db/Daos.kt:178-195`), and a graph binding (`di/TrackItGraph.kt:135`).

**Nothing in the module calls any of it.** `ActivityTransitionReceiver` emits an event and requests a capture; it does not open or close a segment.

Without segments there is no "activity active at fix time" to stamp, which is why G-1 and G-3 have to be fixed together.

### G-12 — `travelStartMs` is computed and then dropped {#g-12}

**Verdict: BROKEN CHAIN.** §19's phantom-leg carry-forward — bound implied travel to `clamp(max(dist, net) / 5.0 m/s, 60 s, 300 s)` so a signal blackout while driving does not attribute the whole silence to travel — is implemented correctly. `Clusters.carryForwardStart()` computes it and `Clusters.Segment.travelStartMs` carries it (`trackit-geo/.../plot/Clusters.kt:40, 97`).

`TrackSegment` — the serialised type that leaves the engine — **has no such field** (`plot/model/Track.kt:89-110`). The value is computed on every build and discarded at the boundary.

So §24's end-node dual window ("render the drive window `travelStart → arrival` separately from the dwell window `arrival → stayEnd`") cannot be drawn by any host, from either the polyline JSON or the in-process `Track`. The maths is done and unreachable.

**Fix:** one nullable field on `TrackSegment`, populated in `TrackBuilder.buildSegments`. Additive to the wire format.

## Tier 2 — missing spec behaviour

### G-4 — Nothing ever triggers an upload {#g-4}

**Verdict: FIXED, 17 Aug 2026.** See `CHANGES-2026-08-17-ANDROID-SYNC.md` §6. Core now owns a
`SyncScheduler` (`trackit-core/.../work/SyncScheduler.kt`) driven from three places — the
accepted-point callback, the health loop, and the periodic backstop — and calls out through a
new `SyncTrigger` seam that `trackit-sync` registers when `SyncConfig.autoSync` is on. Core
still opens no socket and links no network code.

The original finding follows, unchanged.

---

Two separate places in the spec require the SDK to drive its own sync:

- §3.4, health loop step 3 — "Is the newest stored row unsynced (`syncTime == 0`) or last sync ≥ 16 min old? → run the sync queue."
- §12.2, watchdog check 3 — "Newest stored row unsynced → run the sync queue."

`HealthLoop.runCheck()` (`trackit-core/.../service/HealthLoop.kt:60-83`) checks the open session and the backstop worker's `WorkInfo`, and stops. `Watchdog.tick()` returns `RestoreService` or `ReportDead` and has no sync action in its vocabulary.

`SyncConfig.autoSync` (`trackit-sync/.../TrackItSync.kt:26`) — documented as "upload as points arrive" — is read nowhere. Repo-wide, the only references to `autoSync` are its own declaration and KDoc.

Nothing outside `TrackItSync` itself calls `requestSync()` or `syncNow()`. **A host that configures sync and never calls `syncNow()` accumulates rows forever.** The queue, batching, backoff and 401 teardown all work; nothing pulls the trigger.

### G-5 — The AR snapshot is cancelled but never requested {#g-5}

**Verdict: MISSING.** §10 requires "a one-shot snapshot request at registration (accepted only when type ≠ UNKNOWN and confidence ≥ 50; modelled as an ENTER, then snapshot updates stopped immediately)". The snapshot is what gives a session its *initial* activity — without it nothing is known until the first transition fires, which on a stationary user can be hours.

`ActivityRecognizer.register()` (`motion/ActivityRecognizer.kt:57-78`) requests transition updates and then calls `armSnapshotWatchdog()`, which 30 s later calls `removeActivityUpdates(pendingIntent)`. `unregister()` calls it again.

`requestActivityUpdates` is never called. The watchdog that exists to stop the snapshot leaking (SOURCE-AUDIT A12, a real defect in the reference) is guarding a subscription that is never opened.

### G-6 — Confidence gating is absent {#g-6}

**Verdict: MISSING.** §10 sets snapshot confidence ≥ 50 and (per §26.1) transition confidence ≥ 75.

`ActivityTransitionReceiver.kt:38` emits `TrackItEvent.ActivityChange(activity, confidence = 0)` — the literal is hardcoded. No transition is filtered on confidence. `MotionConfig.activityConfidenceMin = 75` and `snapshotConfidenceMin = 50` are declared and unread.

Low-confidence transitions therefore reach `MotionController` and can move the state machine. Bounded in practice — §8's rule that AR may only *accelerate* a transition and never gate capture (EC-53) holds regardless — but a spurious `IN_VEHICLE` at 30 % confidence from a phone on a desk beside a road still cancels a stop-pending and re-arms the vehicular tier (EC-55).

### G-7 — No wake lock exists {#g-7}

**Verdict: MISSING.** §12.2 check 5: "Background soft-wake: 20 s partial wake lock to let the pipeline breathe on aggressive OEM battery managers." §26.2 names the 60 s watchdog plus this wake lock as *the pattern* that keeps the pipeline alive on such devices.

Repo-wide there is no `WakeLock`, no `newWakeLock`, no `PARTIAL_WAKE_LOCK`. `ServiceConfig.wakeLockMs = 20_000` is declared and unread. The `WAKE_LOCK` permission is in the manifest (`trackit-core/src/main/AndroidManifest.xml:17`) and nothing uses it.

Half the OEM survival pattern ships; the half that does the waking does not.

### G-9 — Capture-time significant-stop stamping {#g-9}

**Verdict: MISSING.** §9: "Significant stop detection (used for 'should this point show a pin'): stationary when points stay within a **60 m radius for ≥ 10 min** with speed **< 15 km/h**", stored per point as `isShowPin`.

No such field exists on any entity, and no capture-side stop detector exists. `Consolidation` applies an equivalent rule at plot time, which covers the rendering need — but the *stored* point no longer carries the judgement that was made when the evidence was freshest, and a consumer reading rows directly (via `trackit-sync`) has no way to recover it.

### G-11 — `mobileServiceStatusFlag` {#g-11}

**Verdict: MISSING.** §11.1 lists a 0/1 cell-service diagnostic per point. Not present in any entity or payload. Same class as G-2: this is the field that separates "no fixes because GNSS was denied" from "no fixes because the device had no service at all".

### G-13 — `isInPlaceWander` has no callers {#g-13}

**Verdict: MISSING.** §25: "per cluster, skip *in-place wander* (`max(pathDist, netDist) ≤ 90 m`); otherwise add `travelDuration` to the bucket."

`Clusters.isInPlaceWander(span, stats)` exists and is correct (`plot/Clusters.kt:230`, `IN_PLACE_WANDER_M = 90.0`). Repo-wide it has **zero callers**. `TrackBuilder.statsOf()` sums every travel cluster's duration into the breakdown without the check.

Effect: a cluster that qualified as travel on the sustained-excursion test but never went anywhere still contributes its duration to the commute total.

### G-15 · G-16 · G-17 · G-18 — Renderer shortfalls {#g-15}

All four are `trackit-maps` against §23. None is load-bearing; together they are the difference between "draws the track" and "draws the track the spec describes".

| # | §  | Spec | Implementation |
|---|---|---|---|
| G-15 | 23.3 | Last active node renders a live icon plus a **pulsing circle ground overlay** (2 s repeating radius animation) | `StopNode.isOngoing` is set correctly and both `TrackBuilder.kt:240` and `Track.kt:109` carry comments telling renderers to pulse it. Neither `TrackRenderer` nor `LiveTrackRenderer` draws a `GroundOverlay` — there is no `GroundOverlay` in the module |
| <a id="g-16"></a>G-16 | 23.1 | Add polylines **in chunks of 25 with a ~16 ms yield**, to keep the UI thread fluid on long tracks | `TrackRenderer.render()` adds every polyline in one synchronous pass |
| <a id="g-17"></a>G-17 | 23.3 | Info window: 📍 node number, ⏰ arrival time, ⏳ dwell, 🪪 punch type, 🗺️ address | Title `"Stop N"` and a duration snippet. (Address is out of scope — see below.) |
| <a id="g-18"></a>G-18 | 23.4 | Max zoom preference **20** on the bounds fit | Not set; padding 80 / 50 is correct |

## Tier 3 — divergences

### G-8 — The "full local wipe" is not a wipe {#g-8}

**Verdict: DIVERGENT.** §13 privacy gate 3: "no tracking outside an active session; **full local wipe** at session end/logout/401." §3.3 repeats it: "Session end / punch-out → stop service, **clear local location DB**."

`SyncQueue.clearOnAuthExpiry()` → `PendingUploadStore.clearQueue()` → `TrackPointDao.clearQueue()` = `UPDATE track_point SET syncState = 1, syncTime = 0 WHERE syncState = 0` (`data/db/Daos.kt:102`). It marks unsent rows as sent so they stop being retried. **No row is deleted.** Nothing calls `deleteSession` except `TrackPointRepositoryImpl.delete(query)`, which has no caller inside the SDK.

Points survive session end, logout and a 401 until the TTL prune worker reaches them — `maxDaysToPersist` days later, 7 by default.

This is arguably correct for TrackIt: the spec's wipe exists because the reference uploaded everything and treated the device as a staging buffer, whereas TrackIt is offline-first and the device is the record. **But the divergence is undocumented**, `TrackItSync.tearDown()`'s own comment claims the clear exists "so one user's positions don't leak into the next login", and that is not what the call does. Either implement the wipe or correct the claim — the present state is the one combination that is indefensible.

### G-10 — `activityStatus` is synthesised at upload, never stored {#g-10}

**Verdict: DIVERGENT.** §9 requires the point to carry `activityStatus = "<locationType>@<movementStatus>"` and notes "the server stores this verbatim; the plotting side parses it back."

TrackIt stores `movementStatus` as its own typed column and composes the string only when building a `SyncPoint`. Cleaner as a schema — a parsed enum beats a string that has to be split — but it means the value is not recoverable from a stored point, and a host round-tripping through the sync payload gets a field the local DB cannot reproduce.

### G-14 — Total distance uses a different rule {#g-14}

**Verdict: DIVERGENT.** §25: "sum consecutive-point distances **only where `dist > 50 m` AND `Δt ≥ 1 min`**, skipping AUTO_PUNCH_OUT and break gaps."

`TrackBuilder.statsOf()` sums `distanceMeters` across travel clusters, where each cluster's distance came from `SpeedStats.compute()` — which counts **every** leg's distance including the ones excluded from speed statistics.

Two different numbers, both defensible. The spec's rule discards short and rapid legs as jitter; TrackIt's counts them and relies on the acceptance pipeline having already removed the jitter at capture. TrackIt's is probably the better answer given its filter, and it is not the specified one, and nothing records the choice.

## Out of scope — not gaps

Excluded by locked decisions in [PLAN.md](PLAN.md) §0 and §2, listed so the absence is not rediscovered as a defect:

- §11.3 fetch shape · §14 fetch flow · §15 multi-device attribution — TrackIt is single-user and offline-first; there is no server to fetch from
- Punch bookends (`PUNCH_IN` / `PUNCH_OUT` / `AUTO_PUNCH_OUT`) and every attendance concept
- §24 timeline UI rendering — the SDK ships data, the host ships UI
- Reverse geocoding and all address fields — optional enrichment, deliberately off
- §22.3 V1 and hybrid snappers · §22.4 RTS smoother — the spec itself marks these "do not port unless asked"
- §28 backend verification checklist
- **All of Part C** (React Native / iOS) — except that the bridge is half-built; see [G-26](#g-26)

## Deliberately superseded

Spec behaviour intentionally replaced. Recorded here so a future reader does not file them as gaps.

| § | Spec | TrackIt | Recorded in |
|---|---|---|---|
| 6 | Scalar position-only Kalman | Constant-velocity filter | spec §6 note, EC-44a |
| 4 | Fixed 60 s request, no per-state modulation | Four cadence tiers + turn burst | PLAN §4, EC-45 |
| 7 (1.5) | Negative-Δt reseed branch | Removed as unreachable; `ClockGuard` + monotonic-only arithmetic | EC-42, A1 |
| 22.1 | ORS snap + directions | OSRM `/match` (a real HMM matcher). Chunk 40 → 90, radius 300 m → 40 m | PLAN §5 |
| 22.2 | `stationaryFilter` (drop consecutive < 12 m) | Douglas-Peucker, twice | SMOOTH-NAV §4 |
| 22.2 | Nearest-vertex reconstruct | Perpendicular segment projection, forward-only cursor | EC-102, A11 |
| 22.2 | Bézier rounding only | Centripetal Catmull-Rom spline; Bézier retained as fallback | EC-45b |
| 12.2 | Full-screen "tracking interrupted" nudge | A typed event; the host owns all UI | PLAN §5 |
| 11 | ObjectBox schema with company columns | Room; company columns stripped, `uuid`/`sessionId`/`elapsedRealtimeNanos`/`isMock`/`acceptReason` added | PLAN §5 |

Net-new capability with no spec counterpart: step corroboration · significant-motion wake · turn-burst tier · bearing-change capture · persisted filter state · decision log · two raw diagnostic layers · live track feed and puck · offline route snapping · mock-location policy · `motionQuality` auto-degradation.

---

# Part B — Self-conformance

Gaps against TrackIt's own public surface. Every one of these is a host-visible affordance that does nothing.

### G-19 — The foreground-service notification cannot be configured {#g-19}

**Verdict: MISSING.** `ServiceConfig` declares `notificationTitle`, `notificationText`, `notificationChannelId`, `notificationChannelName` and `notificationSmallIconResName` (`TrackItConfig.kt:517-521`), with builder setters `notification(title, text)`, `notificationChannel(id, name)` and `notificationSmallIconResName(...)` (`:337-343`).

`TrackingService.buildNotification()` (`service/TrackingService.kt:149-167`) reads none of them. It uses its own `CHANNEL_ID`, `CHANNEL_NAME`, `DEFAULT_TITLE`, `DEFAULT_TEXT` constants and `android.R.drawable.ic_menu_mylocation`.

Every host therefore ships a notification reading "Tracking active / Recording your location" with a stock Android icon, and no configuration changes it. This is the SDK's most visible surface — it is on screen for the entire session — and it is the one a host cannot brand. Note also that `TrackItConfig.validate()` has no rule for `notificationSmallIconResName`, so EC-77 ("validate `NotificationConfig` in `ready()` and fail fast") is unimplemented as well.

### G-20 — `persistDecisions = false` has no effect {#g-20}

**Verdict: BROKEN CHAIN.** `RoomPointStore.persistDecisions` (`data/repository/RoomPointStore.kt:45`) defaults `true` and gates the write at `:130`. **No code ever assigns it.** `StartTrackingUseCase` sets five other ingestor flags (`mockPolicy`, `persistRawFixes`, `rawRingCapacity`, `persistRawPoints`, `rawPointCapacity`) and not this one.

`PersistenceConfig.persistDecisions` is read in exactly one place — `PruneWorker` (`work/Workers.kt:146`), to decide whether to prune. So a host that turns it off still writes every decision row, and then prunes none of them.

### G-21 — `cadenceTierMs` is read by nothing {#g-21}

**Verdict: BROKEN CHAIN.** Commit `7a8cf92` records "Cadence-Aware Gating: Plumbed `cadenceTierMs` into `IngestContext` so duration-based logic (e.g. departure confirmation) scales correctly across different capture tiers."

The plumbing is complete and correct: `LocationStreamController.kt:160` stamps it, `FixIngestor.offer()` carries it, `contextFor()` sets it, `IngestContext.cadenceTierMs` holds it. **No gate in `AcceptancePipeline` reads it.**

The stated consumer — the departure ladder — was in fact solved a different way, by `persistAdvanceM` measuring against a net high-water mark, which is cadence-independent by construction (EC-39a). So the field is not wrong, it is *unnecessary*, and it currently reads as a working feature. Either delete it or give it the consumer the commit message claims.

### G-22 — Fourteen further config fields are declared and unread {#g-22}

**Verdict: MISSING.** Verified repo-wide; each appears only in its own declaration, KDoc and builder setter.

| Block | Fields |
|---|---|
| `GeolocationConfig` | `deliveryStalenessMs` (the 60 s gate is hardcoded in `FixMapper`) |
| `MotionConfig` | `activityRecognitionIntervalMs`, `activityConfidenceMin`, `snapshotConfidenceMin`, `disableStopDetection`, `stopOnStationary`, `stopTimeoutMin`, `motionTriggerDelayMs`, `persistHeartbeat` |
| `SensorConfig` | `useAccelerometerVeto`, `useBarometer` |
| `ServiceConfig` | `stopOnTerminate`, `wakeLockMs` |
| `PersistenceConfig` | `maxRecords` |

`MotionConfig.heartbeatIntervalSec` is validated but never applied — the heartbeat cadence is `TrackItConstants.heartbeatSec`.

Worth separating: `stopTimeoutMin` and `motionTriggerDelayMs` are not merely unread, they are **shadowed**. `MotionStateMachine` takes both as constructor parameters with sensible defaults (300 000 ms and 0), and `TrackItGraph.kt:124` constructs it with **no arguments**. A host setting `stopTimeoutMin = 2` gets 5 minutes and no error.

### G-23 — `TrackItState.motionState` never updates {#g-23}

**Verdict: MISSING.** `TrackItState` (`domain/model/TrackSession.kt:112`) declares `motionState: MotionState = STOPPED`. `TrackIt` updates `isReady`, `isTracking`, `currentSessionId` and `providerState`, never this. A host polling `trackIt.state.value.motionState` reads `STOPPED` for the entire life of the process.

Motion *is* observable, via `TrackItEvent.MotionChange` — but §3.13/EC-113's own guidance is "re-read authoritative state via `TrackIt.state` on resubscribe; native state is always the source of truth, never a replayed event buffer". For motion state that guidance is currently wrong.

### G-24 — `Watchdog.Action.ReportDead` is not handled {#g-24}

**Verdict: MISSING.** `Watchdog.tick()` returns `None | RestoreService | ReportDead` (`work/Watchdog.kt:107`). `HealthLoop.runCheck()` branches only on `RestoreService` (`service/HealthLoop.kt:80`).

Benign today — the watchdog itself emits `Error(TRACKER_DEAD, …)` before returning, so the host is told. But the enum value now describes an outcome nobody acts on, which is how the next person to add an action discovers the switch was never exhaustive.

### G-25 — `ProviderStateMonitor` watches one AppOp and is never stopped {#g-25}

**Verdict: DIVERGENT.** Two issues in `permission/ProviderStateMonitor.kt`:

1. `start()` registers `appOps.startWatchingMode(OPSTR_FINE_LOCATION, packageName, listener)` only. The listener body accepts both `OPSTR_FINE_LOCATION` and `OPSTR_COARSE_LOCATION`, but a coarse-only revocation never fires a callback because that op is not watched. EC-12 ("user downgrades precise → approximate mid-session") depends on this path.
2. `stop()` has no caller anywhere in the module. Once `ready()` starts the monitor, the AppOps listener and the broadcast receiver live for the process lifetime. Harmless for a normal app; a leak for a host that expects a tear-down.

### G-26 — `trackit-bridge` does not exist {#g-26}

**Verdict: MISSING — blocking.** `settings.gradle.kts:36` includes `:trackit-bridge`. `gradle/publish.gradle.kts` carries a POM description for it. The README lists it as a shipped module. **It has never existed in git history** (`git log --all --diff-filter=A -- 'trackit-bridge/*'` returns nothing).

Consequences:

- `packages/react-native-trackit/.../TrackItModule.kt:1` imports `com.devstree.trackit.bridge.TrackItJson`. **The React Native module cannot compile.**
- The Java `TrackItClient` callback facade described in the README and PLAN §0 does not exist, so there is no non-`suspend`, non-`Flow` surface for Java hosts.
- `trackit-rn/` contains only `src/verification/AndroidManifest.xml` and has **no `build.gradle.kts`**, so the README's `:trackit-rn:verifyReactNativeSourcesCompiled` task does not exist either.
- `packages/react-native-trackit/` has no `package.json`, no TypeScript, no podspec — the npm package is three Kotlin files.
- `docs/CROSS-PLATFORM.md`, cited from the README, PLAN §0 and `TrackItGraph`'s KDoc, is absent.

The RN bridge surface itself is well designed and complete as source — 26 methods, two events, JSON-string envelopes chosen specifically to avoid `ReadableMap`'s Long→double precision loss. It simply has nothing to sit on.

### G-27 — No tests exist {#g-27}

**Verdict: MISSING — blocking.** There is no `src/test` or `src/androidTest` anywhere in the repository, and none has ever been added (`git log --all --diff-filter=A` matches zero such paths). `trackit-geo/src` contains only `main`.

Test dependencies are declared in every module. Named test classes are cited as evidence throughout the docs — `TrackItGraphTest` (PLAN §0, `TrackItGraph` KDoc), `MixedModeTraceTest` (PLAN §9, EDGE-CASES acceptance criterion 3a), `GoldenWireTest` (`TrackItModule` KDoc) — and none of them exists.

This is the load-bearing one. PLAN §9's entire risk mitigation is "fixture-first: phase 1 ends with golden files before any Android code exists. Any constant change that flips a golden verdict fails CI." The `FixtureReplay` harness is built and correct; **no fixture corpus is committed and nothing replays it.** Every acceptance criterion in EDGE-CASES ("steady 2 hours ⇒ exactly one stored point", "same fixture replayed twice ⇒ byte-identical decision sequence") is currently unverified.

Related: `trackit-core/schemas/` contains only `1.json` while the database is at version 6 with five migrations, so even a `MigrationTestHelper` test could not be written against the shipped schemas.

### G-28 — `installRoadSnapping()` is an empty stub {#g-28}

**Verdict: MISSING.** `sample-android/.../SampleApplication.kt:74-78`:

```kotlin
private fun installRoadSnapping() {
    val baseUrl = BuildConfig.OSRM_BASE_URL
    if (baseUrl.isBlank()) return

}
```

The KDoc above it describes the intended behaviour in full, including why it must run before `ready()`. The body ends after the guard. `trackIt.setRoadSnapProvider(...)` is never called.

So the sample's `:trackit-snap` and `okhttp` dependencies are dead, the "Snap" chip on the Track tab is inert regardless of `OSRM_BASE_URL`, and `OsrmSnapProvider` — the whole optional artifact — has no exercise anywhere in the repository.

---

# Part C — Source protection

The published artifacts are meant to expose the public contract and nothing else. `trackit-core/proguard-rules.pro` says so explicitly, and it is one of the better-reasoned R8 configurations you will read: it keeps by explicit package rather than by visibility (because Kotlin `internal` compiles to public bytecode), it strips `v/d/i` logging *and* the `TrackLogger` interface dispatch feeding it, it repackages everything unkept into `com.devstree.trackit.internal` so the package tree stops describing the architecture, and it states in its own header what obfuscation does **not** buy.

The publishing script is equally careful: **no sources jar anywhere**, with the reasoning recorded — and specifically for the engine, *"trackit-geo is the algorithm module, and a -sources.jar IS the algorithm. This is the artifact with the most to lose from a sources jar."*

All of that is correct. And it protects the wrong module.

### G-29 — `trackit-geo` ships completely unobfuscated {#g-29}

**Verdict: EXPOSURE. The most consequential finding in this document.**

`isMinifyEnabled` is an Android Gradle Plugin build-type property. It exists only for `com.android.application` and `com.android.library` modules.

`trackit-geo/build.gradle.kts:3-6` applies `kotlin.jvm` and `kotlin.serialization`. **It is a plain JVM module.** It produces a JAR, it has no `buildTypes` block, it has no `proguard-rules.pro` (`ls trackit-geo/*.pro` → no matches), and **R8 never runs on it in any configuration.**

Four modules are minified — core, maps, snap, sync. The fifth is not, and it is the one that matters:

| Ships obfuscated | Ships in the clear |
|---|---|
| `trackit-core` — service plumbing, DI graph, Room wiring, permission checks | **`AcceptancePipeline`** — 1074 lines, all seven stages, every gate |
| `trackit-maps` — bitmap drawing, polyline calls | **`KalmanFilter`** — the constant-velocity model and its covariance propagation |
| `trackit-snap` — HTTP and chunking | **`TrackItConstants`** — all 60-odd field-tuned values |
| `trackit-sync` — queue and retry | **The whole plotting plane** — consolidation, clusters, snapper, spline, arrows |

The Android plumbing — reimplementable by any competent Android developer from the public docs — is flattened into `com.devstree.trackit.internal`. The engine, which is the accumulated output of three generations of field work, ships with original class names, original method names, original field names and original package structure.

**It is worse than plain Java bytecode**, for the reason in [G-30](#g-30).

And it reaches every consumer: `trackit-core/build.gradle.kts:79` declares `api(project(":trackit-geo"))`, so the JAR is on the compile *and* runtime classpath of every host, transitively, whether or not they ever name it.

**BUILD.md §5.6 mentions that geo is a plain JAR** — but only to explain that it cannot carry *consumer* rules, and it routes those keeps into core's file. The IP consequence is never stated. The one place the engine's value is explicitly recognised — the publish script's sources-jar comment — is about a jar that is correctly withheld, next to a jar that is shipped and decompiles to nearly the same thing.

**Fix:** there is no one-line version. See [G-36](#g-36) for why, and for the options.

### G-30 — Kotlin metadata hands over the API without decompilation {#g-30}

**Verdict: EXPOSURE.** Compounds G-29.

Every Kotlin class carries a `@kotlin.Metadata` annotation holding a protobuf description of the *source-level* declaration: package, class and member names, full generic signatures, nullability, property vs. field, default-argument presence, and the shape of data classes. It exists so that a Kotlin consumer gets named arguments, default values and null-safety across module boundaries — the same reason `trackit-core/proguard-rules.pro` deliberately keeps `*Annotation*`, and it is right to.

The consequence for an unobfuscated JAR is that reading it needs no decompiler. `kotlinx-metadata-jvm` parses the annotation directly and reconstructs the declaration list. For `TrackItConstants` — a `data class` whose entire content is constructor parameters with default values — that recovers the parameter names, and the defaults are plain constants in the synthetic `<init>$default` bytecode.

So the field-tuned table is readable with a library call and no reverse engineering at all: `accuracyMovingMax = 30f`, `turnBurstEnterDegPerSec = 1.5f`, `qAccelTurning = 2.0f`, `persistAdvanceM = 5.0`, and the rest.

### G-31 — `TrackItConstants` is `public`, and its defaults are the product {#g-31}

**Verdict: EXPOSURE.**

`TrackItConstants.kt:18` — `public data class TrackItConstants(...)` with a default for every one of ~60 parameters.

It is `public` for a defensible reason, recorded in its own KDoc: a data class rather than a file of `const val`s so the fixture harness can sweep one constant and re-run a recorded day. That is a real requirement.

It is also the single most valuable artifact in the repository. Each value has a documented physical rationale and a named symptom it prevents: 30 m from a drive whose clean sections ran 4–8 m at p90 19.4 while every direction reversal sat above 20; 1.5 °/s derived from `L²/8R` chord deviation and cross-checked against straights measured at 0.18–0.92 °/s.

The sweep requirement does **not** require `public`. It requires visibility from the test source set, which in Gradle is the same compilation unit — Kotlin `internal` is visible to `src/test` of the same module. `internal` would satisfy the harness and remove the type from the published surface.

Blocked in practice by G-29 and G-36: `internal` in Kotlin compiles to public bytecode with mangled *function* names only, and it does not span Gradle modules — `trackit-core` needs `TrackItConstants` and is a different module, so it must stay `public` today.

Worth contrasting with the iOS port, where this is solved cleanly: Swift's `package` access level spans targets within one package while staying absent from the distributed module interface. Kotlin has no equivalent. That is a genuine platform difference, not an oversight in either design.

### G-32 — the R8 mapping file is not archived per release {#g-32}

**Verdict: MISSING.**

`proguard-rules.pro` keeps `SourceFile,LineNumberTable` and applies `-renamesourcefileattribute`, explicitly so that *"stack traces from the field must remain mappable (retrace against the mapping file under `build/outputs/mapping/release/`)"*.

That mapping file is a **build output**. It is regenerated on every build, it is not committed, it is not attached to any publication, and BUILD.md references `mapping/release/` only for the sample app's own R8 run (§5.6, around line 459).

So the retrace path the rules were written to preserve does not exist for a published artifact: when a host sends an obfuscated stack trace from `com.devstree.trackit.internal.a.b()`, there is nothing to map it against unless someone still has the exact build tree.

**Fix:** archive `mapping.txt` per module per release, keyed by version, alongside the published artifacts and never with them. Cheap, and it is the difference between a field crash report being actionable and being noise.

### G-33 — the RN Android module ships as Kotlin source {#g-33}

**Verdict: EXPOSURE — inherent, correctly mitigated, nowhere recorded.**

React Native Android modules are compiled by the **host application's** Gradle build (autolinking points at the package's `android/` directory). The Kotlin therefore ships as source in the npm tarball. There is no binary option; this is how RN works.

What ships: `TrackItModule.kt` (284 lines), `TrackItPackage.kt`, `TrackItSpec.kt`. Every RN consumer reads them.

**The architecture already handles this correctly**, and it deserves to be said rather than left as an unexamined exposure. `TrackItModule.kt:3` imports exactly one TrackIt symbol:

```kotlin
import com.devstree.trackit.bridge.TrackItJson
```

Nothing else. The module is a thin JSON facade — argument marshalling, promise settling, event emission — over `trackit-bridge`, which is a published, R8-obfuscated AAR. **The engine is not in the npm package and cannot be.** A reader of the RN source learns the method list and the envelope shape, both of which are public API anyway.

This is the right shape for a bridge, arrived at for other reasons (`ReadableMap` precision loss). It should be documented as a source-protection property so a future refactor does not casually move logic up into the module and give it away.

### G-34 — the npm package has no `files` allowlist {#g-34}

**Verdict: MISSING.**

`packages/react-native-trackit/` contains three Kotlin files and nothing else — no `package.json`, no `.npmignore`, no podspec ([G-26](#g-26)).

When the package is created, this matters immediately: **npm's default is to publish everything in the directory** except a short built-in ignore list. Without an explicit `files` allowlist, a build that leaves `android/build/`, a local `.xcconfig`, a `.env`, test fixtures or a `local.properties` in the tree publishes all of it — irreversibly, to a public registry.

**Fix, before the first `npm publish`:**

```jsonc
{
  "files": ["src", "lib", "android/src", "android/build.gradle", "*.podspec", "README.md", "LICENSE"],
  "//": "allowlist, not .npmignore — a denylist fails open on every new file"
}
```

An allowlist fails closed. A `.npmignore` fails open on every file nobody remembered to add, which is the failure mode that leaks credentials.

Verify with `npm pack --dry-run` and read the file list before every release.

### G-35 — the npm package will ship JS/TS source {#g-35}

**Verdict: EXPOSURE — acceptable, record it.**

RN libraries conventionally ship `src/` alongside compiled `lib/`, because hosts need source maps and Metro benefits from readable modules. Minifying a library is unusual and makes every host's stack traces worse.

What becomes visible: the TypeScript surface, the JSON envelope contract, the error-code list, the listener refcounting, and the backlog-marker protocol.

All of it is public API. The engine is behind `trackit-bridge`. **Accept and record it** — the point of writing it down is that it is now a decision rather than an assumption, and a future contributor cannot quietly relocate logic into the JS layer without noticing they are publishing it.

### G-36 — testability, optionality and obfuscation: pick two {#g-36}

**Verdict: DIVERGENT — a real architectural tension, nowhere recorded.**

G-29 has no cheap fix, and the reason is worth stating precisely rather than discovering during a release.

**R8 in library mode obfuscates one module in isolation.** `trackit-core` is compiled against `trackit-geo`'s original names. Rename geo after core is built and core's references break. So geo cannot simply be obfuscated where it stands.

Three properties are in tension, and the current design has the first two:

1. **JVM testability** — geo is a plain JVM module so `android.location.Location` is not on the classpath, which is what makes the whole engine testable with no emulator (PLAN.md §3 invariant 2).
2. **Module optionality** — `trackit-snap` depends on geo *only*, so a host wanting map-matching does not drag in the capture stack.
3. **Obfuscation** — requires geo's classes to be renamed together with everything that references them.

Options, with what each costs:

| Option | Gets | Costs |
|---|---|---|
| **A. Fat core AAR** — embed geo's classes into `trackit-core` at publish time, run R8 over both, stop publishing geo standalone | Full obfuscation of the engine | Breaks (2): maps and snap would depend on core. Duplicate classes if they keep their own copy |
| **B. Standalone R8 pass over the geo JAR, published as an obfuscated JAR, with core/maps/snap compiled against the obfuscated output** | Full obfuscation, keeps (1) and (2) | Build-order complexity: geo must be R8'd before the modules that consume it compile. Keep rules must cover every cross-module reference, and a missed one is a link error at host-build time |
| **C. Move the algorithms into `trackit-core`, leave geo as models + math** | Obfuscation via the existing core pass | Destroys (1) for the pipeline, which is the part that most needs fixture testing. **Not recommended** |
| **D. Accept the exposure; protect by licence** | Zero engineering | The engine is readable. Legitimate if the commercial model does not depend on secrecy |

**Recommendation: B, or D consciously.** What is not acceptable is the current state — an elaborate, well-argued obfuscation setup that a reader would reasonably assume covers the engine, and does not.

Whichever is chosen, record it in BUILD.md §5.6 next to the existing "geo is a plain JAR" sentence, which is currently the only place a reader could have noticed and is framed as a consumer-rules footnote.

### What obfuscation does not buy, restated

`trackit-core/proguard-rules.pro` says this in its own header and it is worth repeating where the whole picture is visible: renaming is not encryption. Strings survive (`Reasons` is API and must). Tuning constants survive — they have to exist at runtime. Control flow survives. A determined reader with a decompiler sees the algorithm's shape in *any* of these modules.

Obfuscation raises the effort. The durable protections are the licence, the fixture corpus (which is not committed at all — [G-27](#g-27) — and is arguably the most valuable asset because it is what lets you *re*-derive the constants), and the field learning encoded in the KDoc, which does not ship because no sources jar does.

---

## Suggested order

Ranked by ratio of consequence to effort, not by severity.

0. **Before the first publish, because publishing is irreversible:** decide [G-29](#g-29)/[G-36](#g-36) — the engine currently ships in the clear, and even choosing option D takes an hour and turns an unexamined exposure into a recorded decision. Add the npm `files` allowlist ([G-34](#g-34)) before the first `npm publish`. Start archiving `mapping.txt` per release ([G-32](#g-32)) with the first artifact, or the retrace path the R8 rules were written to preserve never exists.
1. **[G-1](#g-1) + [G-3](#g-3)** — activity stamping and segments. Restores §10, §19's STILL override, §21's detected-label path and §25's breakdown in one change. The largest behavioural return in the list.
2. **[G-27](#g-27)** — a fixture corpus and the replay tests. Everything else is a guess until the acceptance criteria run.
3. **[G-26](#g-26)** — `trackit-bridge`. Two documented deliverables (Java client, RN package) are blocked on one absent module.
4. ~~**[G-4](#g-4)** — the sync trigger.~~ **Done, 17 Aug 2026.** It was small, and it was the difference between a sync module that works and one that appears to.
5. **[G-19](#g-19)–[G-22](#g-22)** — either wire the dead config or delete it. A field that reads as supported and does nothing costs more than an honest absence.
6. **[G-2](#g-2), [G-9](#g-9), [G-11](#g-11), [G-12](#g-12), [G-13](#g-13)** — cheap, additive, each closes a named spec clause.
7. **[G-7](#g-7), [G-28](#g-28), [G-15](#g-15)–[G-18](#g-18)** — survival and render polish.
8. **[G-8](#g-8), [G-10](#g-10), [G-14](#g-14), [G-21](#g-21)** — decide, then record the decision. Half of these want a documentation change rather than a code change.
