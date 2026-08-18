# Tracker iOS — change review, 13 Aug 2026

Everything that landed on `main` that day, grouped by area, with the exact
declarations to review. Test count moved **682 → 769**, all passing.

---

## 1. Licensing

New target area: `Tracker/Sources/TrackerCore/License/`.

### New types

| Symbol | File | Access |
|---|---|---|
| `enum LicenseToken` | `License/LicenseToken.swift` | `internal` |
| `LicenseToken.static let prefix = "TRACKIT-"` | " | " |
| `LicenseToken.struct Parts` | " | " |
| `LicenseToken.struct LicensePayload` (`primary`, `also`, `kid`, `v`, `licensee`, `issued`) | " | " |
| `LicenseToken.enum ParseFailure` — `.wrongPrefix`, `.wrongShape`, `.payloadNotBase64`, `.signatureNotBase64`, `.payloadNotJSON`, `.unsupportedVersion(Int)` | " | " |
| `static func parse(_ token: String) -> Result<Parts, ParseFailure>` | " | " |
| `static func decodeBase64URL(_ string: String) -> Data?` | " | " |
| `LicensePayload.func covers(_ bundleID: String) -> Bool` | " | " |
| `struct LicenseVerifier` | `License/LicenseVerifier.swift` | `internal` |
| `func verify(token: String?, bundleID: String) -> LicenseVerdict` | " | " |
| `static let productionKeys: [Int: Data]` (kid → Ed25519 public key) | " | " |
| `enum LicenseVerdict` — `.licensed`, `.waived`, `.missing`, `.invalid(detail:)`, `.bundleMismatch(licensed:actual:)` | " | " |
| `enum LicenseEnvironment` — `static var isWaived`, `static func hasGetTaskAllow(in bundle: Bundle) -> Bool` | " | " |
| `final class LicenseGate` — `func check(explicit: String?) -> LicenseVerdict`, `static func failure(for verdict:) -> (code: ErrorCode, message: String)?`, `static let infoPlistKey = "TrackItLicense"` | " | " |

### Wiring

- `Tracker.ready()` gains **step 0**: `LicenseGate.check(explicit: config.license)` runs
  before anything is mutated or persisted. Verdicts are cached per token, so the
  cryptography runs once per process.
- `TrackerConfig.license: String?` — overrides the `TrackItLicense` Info.plist key.
  **Excluded from `Codable` on purpose** so `reset: false` cannot resurrect a stale token.
- `TrackerConfigBuilder.license(_ value: String?) -> Builder`
- Three new `ErrorCode` cases: `.licenseMissing`, `.licenseInvalid`, `.licenseBundleMismatch`.

### Behaviour to check

- Token format `TRAKER-<base64url payload>.<base64url signature>`; the Ed25519 signature
  covers the **encoded payload string's bytes**, never a re-serialisation.
- Simulator and debuggable installs (`get-task-allow`) are waived. App Store, TestFlight,
  ad-hoc and enterprise enforce.
- Keys are `kid`-keyed so a leak is a rotation, not a global break. An `also` entry that
  does not extend `primary` grants nothing even under a valid signature.
- `scripts/issue-license.swift` — `keygen` / `issue` / `verify`, with `--kid N`.
  `LICENSE-ISSUING.txt` documents re-issue vs rotation.
- The signature-failure message no longer says "tampered" — a re-keyed token and an
  edited payload are indistinguishable to the verifier.

**Tests:** `TrackerCoreTests/LicenseTests.swift` — 18 cases (tampering, impostor keys,
unknown kid, future payload version, prefix collisions, missing-vs-invalid split).

---

## 2. Motion & sensor config gaps

All six default to the SDK's existing behaviour — an upgrading host that changes
nothing gets exactly what it had.

### New `TrackerConfig` fields + builder methods

| Config property | Builder method | Default |
|---|---|---|
| `motion.stopOnStationary: Bool` | `stopOnStationary(_:)` | `false` |
| `motion.disableStopDetection: Bool` | `disableStopDetection(_:)` | `false` |
| `motion.stillConfidenceMin: Int` | *(config only)* | `100` |
| `persistence.persistHeartbeat: Bool` | `persistHeartbeat(_:)` | `false` |
| `sensors.useAccelerometerVeto: Bool` | `useAccelerometerVeto(_:)` | `false` |
| `sensors.useBarometer: Bool` | `useBarometer(_:)` | `false` |
| `sensors.activityRecognitionIntervalMs: Int64` | `activityRecognitionIntervalMs(_:)` | `0` |

Points to review:

- `stopOnStationary` closes the session for real — `.enabledChange(false)` reaches the host.
- `disableStopDetection` closes **all three** doors into the stopped side: an accepted fix
  reporting no motion, a `.still` activity, and `.changePace(false)`. The third arrives from
  CoreLocation, not the fix path. `validate()` rejects it alongside `stopOnStationary`.
- `persistHeartbeat` is the *opposite* direction from Android's default — stage 7-B has always
  turned `15-Min Heartbeat` into `HeartBeat Skipped`.
- `activityRecognitionIntervalMs` is a **delivery throttle only** — it saves no battery, and
  that is documented in code, config and docs.

**Not ported (deliberate):** `useSignificantMotion` (hardware wake-up sensor, no iOS wake path)
and `stepBatchLatencyMs` (CMPedometer takes no batch-latency parameter).

### New pipeline stages in `TrackerGeo/Filter/AcceptancePipeline.swift`

- **Stage 2b — accelerometer veto.** For devices where CMPedometer is absent (iPads, older
  hardware) and stage 2a is skipped entirely. Subordinate to 2a by design.
- **Stage 2c — barometer.** A lift is the one journey the horizontal plane cannot see. It
  *suppresses the stillness vetoes* rather than asserting movement, and is evaluated **before**
  2a and 2b rather than withdrawing a veto afterwards. It gates the pedometer too.

New `IngestContext` members (all `package`):
`accelerationVarianceG: Double?`, `verticalSpeedMps: Double?`, `persistHeartbeat: Bool`.

`nil` from either probe means **not measured** and skips the stage — never "perfectly still".

### `actor DeviceMotionProbe` — `Motion/DeviceMotionProbe.swift`

```swift
func start(accelerometer: Bool, barometer: Bool)
func stop()
func accelerationVarianceG() -> Double?
func verticalSpeedMps() -> Double?
```

Reports **measurements, never verdicts** — mean-square deviation from gravity, and signed
metres per second of vertical travel. Thresholds live in the engine.

Two bugs fixed here the same day (both found by reading the code, neither had coverage):

1. **It was accumulating one sample per fix, not 10 Hz.** `startAccelerometerUpdates()` with
   no handler is the *pull* form — `accelerometerData` holds the latest reading and there is no
   queue behind it. A walking phone passes through 1 g constantly, so a single sample taken at
   such an instant reads near-zero deviation → stage 2b vetoes real movement, on exactly the
   devices the feature exists to help. Now uses the handler form on its own `OperationQueue`,
   accumulating into a lock-guarded `final class Accumulator` (`add(deviation:)`,
   `rootMeanSquare(minimumSamples:)`, `reset()`), and answers `nil` below
   `minimumAccelerometerSamples = 5` (~0.5 s).
2. **`verticalSpeedMps()` held its last derived rate forever.** `CMAltimeter` can stop
   delivering, and a stale "still ascending" suppresses the stillness vetoes for the rest of
   the session. Readings older than `verticalReadingMaxAgeSec = 5` now answer `nil`.

Constants to review: `accelerometerIntervalSec = 0.1`, `accelerometerStillVarianceG = 0.02`,
`verticalMotionMinMps = 0.25`.

### `MotionController` reentrancy guard

With `motion.stopOnStationary`, the state-change callback reaches `Tracker.stop()`, which
reenters `stop()` on this actor. Actors are reentrant, so teardown completed and then `handle()`
resumed and called `enterStationary()` for a closed session — reconfiguring a released manager
via `captureStream.onStationary()`. A guard now returns if the controller is no longer running.

### `Tracker.changePace(isMoving:)` made public

```swift
public func changePace(isMoving: Bool) async -> TrackerResult<Void>
```

`MotionController.onChangePace` existed, was documented "From the host", was handled by the
state machine including the idempotence rule (EC-59) — and **had zero callers**. Requires an
open session; returns `.notReady` otherwise.

### `motion.stillConfidenceMin`, default 100

CoreMotion reports `.still` constantly inside ordinary movement — between strides, at a gear
change, at a red light. Each one drove the machine to `.stopPending` and the next walking
reading drove it back: `motionChange` flapping at walking cadence. Only a high-confidence
still may now *start* a stop. Nothing is lost — an activity label may only accelerate a
transition the fixes already justify (EC-53). `validate()` rejects values below
`activityConfidenceMin`.

### Pedometer deadline — `StepCorroborator`

`consumeSteps` awaited `queryPedometerData` with nothing bounding it, on the ingest path,
before the pipeline judges anything. Woken in the background the process has seconds.
Now `queryTimeoutMs = 1_500`, then `nil` — the same fail-open path a device with no pedometer
already takes. Uses `private final class SingleResume` to guarantee one resume.

---

## 3. Two members that were declared, documented and never written

- **`TrackerState.providerState`** — documented as "written by `ProviderStateMonitor`" and
  written by nothing, so every view bound to it read a permanent default of *authorization
  denied*. Two writes fix it and **both are needed**: one inside `monitor.onChange` ahead of
  the `becameUsable` guard (that guard rejects every change except a re-grant, and a host needs
  the downgrade most), and one immediately after installing the handler (because `start()`
  publishes its first snapshot synchronously, before any handler exists).
- **`TrackerEvent.heartbeat(atMs:)`** — declared in the enum, listed in the public API doc,
  required by the sample-app spec, fired by nothing on either platform. Now emitted by
  `HealthLoop` on each tick with a session open, **after** the watchdog has judged — a
  heartbeat sent ahead of the check would claim health the loop had not established.

**`TrackerCoreTests/EventCoverageTests.swift`** — scans the sources for any `TrackerEvent` case
with no emitter and any published `TrackerState` field with no writer. Both scans were vacuous
when first written (one matched a doc comment, the other an unrelated private property of the
same name) and were rewritten until reverting each fix made them fail.

---

## 4. `stopTimeoutSec` → `stopTimeoutMin`

Android states the stop timeout in minutes; iOS stated it in seconds — the same number meant
five minutes on one platform and five seconds on the other.

```swift
public var stopTimeoutMin: Int = 5
package var stopTimeoutMs: Int64 { Int64(self.stopTimeoutMin) * 60_000 }
public func stopTimeoutMin(_ value: Int) -> Builder
```

A config persisted under the old key still decodes via `private enum LegacyKeys` — seconds are
converted and **rounded up, floor of one minute**, so a host that chose two minutes is not
silently reverted to the default.

---

## 5. `getCurrentLocation()`

```swift
public func getCurrentLocation(feedIngestor: Bool = false) async -> TrackerResult<TrackFix>
```

One fix without a session — for a map centre or a check-in. Backed by `OneShotProvider`, which
gains `func capture(...)` and `enum Failure` cases `.busy`, `.denied`, `.timedOut(String)`,
`.rejected`. Requires `ready()`; returns `.notReady` otherwise.

---

## 6. Geofences

### Public API on `Tracker`

```swift
public func addGeofence(_ fence: Geofence) async -> TrackerResult<Geofence>
public func getGeofences() async -> [Geofence]
public func removeGeofence(id: String) async -> Bool
public func removeAllGeofences() async -> Int
public func getGeofenceEvents(
    geofenceID: String? = nil,
    limit: Int = 200,
    offset: Int = 0
) async throws -> [GeofenceEvent]
public func deleteGeofenceEvents(geofenceID: String? = nil) async throws
```

### Public models — `Storage/Models/Geofence.swift`

```swift
public struct Geofence: Sendable, Equatable, Identifiable {
    public init(
        id: String,
        latitude: Double,
        longitude: Double,
        radiusM: Double,
        notifyOnEntry: Bool = true,
        notifyOnExit: Bool = true,
        dwellAfterMs: Int64? = nil
    )
    public var centre: GeoPoint { get }
}

public enum GeofenceTransition: String, Sendable, Codable { case enter, exit, dwell }

public struct GeofenceEvent: Sendable, Equatable, Identifiable {
    public let id: Int64
    public let geofenceID: String
    public let transition: GeofenceTransition
    public let timeMs: Int64
    public let latitude: Double
    public let longitude: Double
    public let radiusM: Double
}
```

New events: `TrackerEvent.geofenceEnter(GeofenceEvent)`, `.geofenceExit(GeofenceEvent)`,
`.geofenceDwell(GeofenceEvent)`.

### Design points to review

- **CoreLocation is the list.** There is no fence table. `monitoredRegions` already survives
  termination and reboot, and a second copy in the database is a copy that can disagree with the
  thing doing the monitoring. `getGeofences()` maps regions back and filters out the SDK's own
  `StationaryFence.identifier = "fieldtrack-stationary"`.
- **Crossings are stored *and* emitted, row first.** iOS relaunches a terminated app to deliver
  one; `events()` has no replay, so at that moment no host is subscribed. The process may not
  outlive the emission. Centre and radius are copied onto each row so a crossing stays readable
  after the fence is gone.
- **Region cap.** `platformRegionLimit = 20`, `hostRegionLimit = 19` — one slot reserved so a
  stop can always arm the stationary fence. CoreLocation's own answer to the cap is silence.
- **`reliableMinimumRadiusM = 100`** — a smaller radius is armed and reported by *diagnostic*
  rather than refused.
- Every refusal is named: authorization, empty/reserved id, invalid coordinate, non-positive
  radius, both notify flags off, region cap.

### New storage

| Symbol | File |
|---|---|
| `final class GeofenceManager: NSObject, CLLocationManagerDelegate` | `Motion/GeofenceManager.swift` |
| `struct GeofenceEventRecord` | `Storage/Records/GeofenceEventRecord.swift` |
| `struct GeofenceEventRepository` — `record(_:)`, `events(geofenceID:limit:offset:)`, `latest(geofenceID:)`, `delete(geofenceID:)`, `removeAll()` | `Storage/Repositories/GeofenceEventRepository.swift` |
| `struct GeofenceDwellStore` — `set(...)`, `delay(geofenceID:)`, `all()` | `Storage/Repositories/GeofenceDwellStore.swift` |
| `Migrations.createGeofenceEvent(_:)` — **v7** | `Storage/Migrations/Migrations.swift` |
| `Migrations.createGeofenceDwell(_:)` — **v8** | " |

### Dwell — the transition iOS does not have

`CLCircularRegion` reports entry and exit only, and there is no out-of-process loitering timer.

- **No pending-dwell state is kept anywhere.** The crossing history already answers "is the
  device inside, and since when": the latest row for a fence is `.enter` only while it is inside
  and no dwell has been reported, because a dwell row is always newer than the entry it
  describes. That one query is also the idempotence rule — four paths race and the losers see
  `.dwell` and stop.
- **Four evaluation paths**, every moment the SDK is already awake:

  | Path | State | Latency |
  |---|---|---|
  | timer armed on the enter (`armDwell(for:)`) | app alive | on time |
  | `HealthLoop` tick | session running | within 120 s |
  | `BackstopTask` | suspended | when iOS grants a run |
  | `ready()` | terminated | next launch |

  Placed **ahead of the session checks** in both the loop and the backstop — a geofence has no
  session, and returning early for "no session" would skip the only paths that reach a
  sleeping app.
- `func evaluateDwells(verifyPosition: Bool) async` — on the catch-up paths a one-shot fix
  confirms the device really is inside (`verifyInside` closure). "Still inside" is inferred from
  an exit that never arrived, and an exit iOS dropped would become a dwell that never happened.
  A fix that cannot be had answers `nil` — *could not check* — and the dwell is reported on the
  evidence available, because indoors is where dwells happen and where a fix is hardest.
- **`timeMs` is the moment the condition was met** (entry + delay), never the moment it was
  noticed.
- `dwellAfterMs` needs the sidecar table: `CLCircularRegion` has no user-info field. Written on
  **every** add including when `nil`, so a fence re-added without a dwell cannot inherit the
  delay of the fence it replaced. A region with no row has no dwell; a row with no region is
  ignored.

### Initial trigger — found by building the sample screen

A fence armed around where the device already is **never fired**: CoreLocation reports
transitions, and no transition happens when you arm around yourself. Its dwell could never
start either, because the dwell hangs off an entry that would never arrive.
`requestState(for:)` after arming closes it, with `didDetermineState` reporting an entry when
the answer is `.inside` (Android's `INITIAL_TRIGGER_ENTER`).

That opened a second door: entries now arrive from the transition callback, from
`requestState`, and from iOS re-delivering region state on its own schedule. A repeat entry
would restart the dwell clock, so a device that never moved would have its dwell pushed further
away every time iOS spoke up. **An entry whose fence is already recorded as entered is dropped.**

**Tests:** `TrackerCoreTests/GeofenceTests.swift`.

---

## 7. Sync

### `SyncEvent.httpResponse(statusCode: Int?, count: Int)`

The status was read from the transport, logged inside the queue, and dropped on the way out —
so a host saw `retryScheduled` for a 500, a 422 and a timeout alike. Three failures with three
different fixes, reported identically.

- Emitted **once per exchange**, not once per drain — a queue larger than `batchSize` is the
  normal case, and the batch that failed is the one worth seeing.
- `nil` means the request never reached a server (offline, DNS, TLS) — a different fact from a
  5xx, kept distinct.
- 401 reports as 401 **and** tears the uploader down (`unauthorizedStatus = 401`).
- **Additive on purpose.** `SyncQueue.Result` is public and hosts pattern-match it; widening
  `.retry` would break every `case .retry(let reason)` written against 1.0.
- The response body is not included and will not be — it can be megabytes; a host that needs it
  implements `SyncTransport`.

Helper: `private static func statusCode(of response: SyncResponse) -> Int?`.

### `SyncEngine.endpoint` / `isConfigured`

```swift
public var endpoint: URL? { self.mutable.active?.config.url }
public var isConfigured: Bool { self.endpoint != nil }
```

- `isConfigured` is derived from `endpoint` rather than being a second read of the same state,
  so the two cannot disagree.
- **Headers are deliberately not exposed.** They carry the host's credential, and a property
  that hands a bearer token back is a property that ends up in a log.
- Remembering the flag host-side would be wrong: a 401 tears the configuration down without the
  host doing anything, so any remembered value drifts the moment the credential dies.

**Tests:** `TrackerSyncTests/SyncResponseStreamTests.swift` — the target held a scaffold
placeholder and now holds 7 tests, including multi-batch reporting order.

---

## 8. Cross-platform config decode aliases

`motionTriggerDelayMs` and `healthLoopMs` are now read as aliases via
`private enum ServiceLegacyKeys`, rounded up so a sub-second delay becomes the smallest value
the field can express rather than none. **The native key wins wherever both appear.**

Not a rename, and the distinction matters: `stopTimeoutSec` *had* to be renamed because the same
field name meant minutes on one platform and seconds on the other — silently wrong by sixty.
These two have different names on each platform, so a Swift host setting the wrong one gets a
compile error. Renaming would be a third source break for cosmetic symmetry.

What is genuinely silent is a team shipping both platforms from one JSON config: their key
decoded to this SDK's default and nothing said so.

---

## 9. Fixture recorder in Release builds

A fixture is worth recording exactly when the anomaly happened on a real device, against the
build the user actually has — and that build is Release. Wrapping `exportFixture` in `#if DEBUG`
alongside `FixtureReplay` meant the only build able to see the anomaly was the one with no
recorder compiled into it.

- `Tracker.exportFixture` and `Fixture` now compile in Release. The method returns a `String` of
  JSON; the fixture types stay `package`, so with `.package.swiftinterface` deleted at package
  time they appear in **no shipped interface**. A host receives JSON and not one declaration
  describing it.
- `FixtureReplay` stays `#if DEBUG` — it is the tuning harness, of no use to a host, and the
  half that would document how the engine is exercised.

Verified against the shipped **1.0.1 XCFrameworks**, not the source package: `exportFixture` and
`getCurrentLocation` both resolve from the stripped `.swiftinterface`.

---

## 10. Sample app

- **Geofences screen** (`Modules/Geofences/GeofenceView.swift`, `Core/AppState/GeofenceViewModel.swift`,
  tab in `App/RootView.swift`) — arm at the current position with radius + optional dwell, the
  armed list read back from CoreLocation, a live feed, and the stored history. The last two are
  **deliberately separate**, because a crossing delivered to a relaunched app appears only in the
  second, and that difference is the whole reason crossings are stored.
- **Sync screen** (`Modules/Sync/SyncView.swift`, `Core/AppState/SyncViewModel.swift`) — behind
  Home rather than a sixth tab, which iOS would collapse into "More". Endpoint + token, pending
  count, both triggers, one feed line per HTTP exchange. The endpoint field is prefilled from the
  engine on appearance and the card shows what is **actually in force**, which is not necessarily
  what is typed.
- **Travelling arrow removed** (`Modules/Track/TravellingArrow.swift`, −289 lines). `progress`
  was read inside the `Map` content builder, so every tick rebuilt every polyline, every pin and
  the raw thread — for a track that had not changed. On a 141-point session that is enough
  continuous invalidation to starve MapKit's own pinch and pan recognisers. Slowing the tick to
  10 Hz reduced the waste without removing it. `Track.arrows` already places static chevrons at
  engine-computed anchors.
- **Map-wide tap gesture removed.** A `simultaneousGesture(TapGesture())` fires on the same tap
  that hits a pin — simultaneously, by definition — so selecting a stop and clearing the
  selection raced on every tap. A tooltip is now dismissed by tapping its pin again or the
  tooltip itself.
- **Both map panes now open on the device**, not on a world view. `LiveTrackMapView` takes an
  `initialCentre: GeoPoint?`, applied once and only while no frame has been rendered.
  `TrackView.fit()` falls back to the device position and reports on the map when it cannot —
  a map sitting on a continent because authorization was refused looks identical to a broken map.
  Both use `Tracker.getCurrentLocation()` rather than MapKit's `.userLocation` camera; a blue dot
  from a second `CLLocationManager` would be a position the sample got from somewhere other than
  the SDK it exists to demonstrate.
  *(Worth keeping: the first attempt was a no-op because the guard read `lastSequence == 0`, but
  the "no frame ever applied" sentinel is `.min` and zero is a legitimate first sequence.)*
- **Empty Plot state no longer contradicts the summary card.** It fires on `hasGeometry`
  (fewer than two points) and then explained itself as though the condition had been "nothing
  was stored" — printing "none of them was stored" six inches above a card reading `7 → 1,
  14% kept, Points 1`. Three situations now get three different sentences and titles: nothing
  captured, captured but all rejected, stored but not enough to draw.
- `HomeView` gains a **"One fix"** button for `getCurrentLocation()`, enabled while stopped —
  being callable without a session is the whole point of that API.
- The fixture recorder is no longer gated on `TRAKER_SDK_DEBUG`, so it is present in both
  package modes, exactly as a host's own build has it.
- `Info.plist` carries a real licence token, so the sample is wired exactly the way the
  documentation tells a host to wire one.
- Exhaustive switches over `SegmentType` and `TrackerEvent` now carry `@unknown default`, which
  the binary framework boundary requires. The first README example omitted it — it would have
  been a compile error in every host that copied the snippet.

---

## 11. Docs

- `README.md` — geofences, dwell, `getCurrentLocation()`, the six optional switches,
  `SyncEvent.httpResponse`, the config aliases. **Android comparisons removed from host-facing
  prose** — a customer reading it has no other platform to compare to.
- `LICENSE-ISSUING.txt` — re-issuing for an already-licensed app (safe; the old token stays
  valid, there is no revocation) and rotating the key (a release, in four ordered steps,
  including *keep the previous public key*).

---

## Review checklist

- [ ] Ed25519 verification covers the encoded payload bytes, never a re-serialisation
- [ ] `TrackerConfig.license` stays out of `Codable`
- [ ] Stage 2c (barometer) is evaluated **before** 2a and 2b, not as a withdrawal
- [ ] `nil` from either sensor probe skips the stage rather than asserting stillness
- [ ] `DeviceMotionProbe` accumulator is lock-guarded, no actor hop at 10 Hz
- [ ] `MotionController` guard actually prevents the post-`stop()` `enterStationary()`
- [ ] Dwell idempotence rests only on the event history, with no separate pending state
- [ ] Dwell evaluation sits ahead of the session checks in `HealthLoop` and `BackstopTask`
- [ ] Geofence row is written **before** the event is emitted
- [ ] `getGeofences()` filters `StationaryFence.identifier`
- [ ] One region slot stays reserved (19 host fences, not 20)
- [ ] `SyncQueue.Result` was not widened; `httpResponse` is purely additive
- [ ] `SyncEngine` exposes no headers
- [ ] `stopTimeoutSec` legacy decode rounds **up** with a one-minute floor
- [ ] `FixtureReplay` is still `#if DEBUG`; `Fixture` types are still `package`
- [ ] `EventCoverageTests` fails when either fix is reverted
