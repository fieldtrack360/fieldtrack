# Development record — 13–17 August 2026

Written to be read by whoever picks this up next, including the parts that are
unfinished or unverified. A record that lists only the wins is a record that
sends the next person into the same holes.

---

## 1. What a host can now do that it could not before

### Location

| API | What it answers |
|---|---|
| `getCurrentLocation(feedIngestor:)` | "Where am I now", with no session. `CLLocationManager.requestLocation()` behind a timeout and a circuit breaker |
| `changePace(isMoving:)` | Force the motion machine when the sensors are stuck — indoors, in a car park, when the user just tapped Start trip |

### Geofences

| API | |
|---|---|
| `addGeofence(_:)` | Arms a region under the host's own id; re-adding the same id replaces it |
| `getGeofences()` | What is actually armed, read back from CoreLocation |
| `removeGeofence(id:)` / `removeAllGeofences()` | Disarm one / all |
| `getGeofenceEvents(geofenceID:limit:offset:)` | The crossing history |
| `deleteGeofenceEvents(geofenceID:)` | Drop that history |

Events: `.geofenceEnter`, `.geofenceExit`, `.geofenceDwell`, each carrying a
stored `GeofenceEvent`.

### Upload

| API | |
|---|---|
| `SyncEvent.httpResponse(statusCode:count:)` | What the server actually said, once per exchange |
| `SyncEngine.endpoint` / `.isConfigured` | Where uploads are going, and whether they are going anywhere |

**Already shipped, and repeatedly mistaken for missing:** `SyncEngine.syncNow()`
and `.requestSync()`. Both are public and predate this cycle, which is why they
appear in no commit here — but a competitor comparison marked Tracker ❌ on
"manual sync command" twice, so it is worth writing down where someone will find
it.

`syncNow()` awaits the drain and **returns the outcome** — `.uploaded(count:)`,
`.empty`, `.retry(reason:)` or `.authExpired` — so a "Sync now" button can report
what happened rather than hoping a callback arrives. `requestSync()` returns
immediately and coalesces repeated calls. Together with `httpResponse` above,
every claim on that comparison is now answered.

### Rendering

| API | |
|---|---|
| `LiveTrackMapView(initialCentre:)` | Where the live map points before the first frame arrives |
| `CameraZoom.level(longitudeSpanDeg:viewportWidthPoints:)` | Visible region → tile-pyramid zoom |
| `Arrows.shouldReplace(previousZoom:newZoom:)` | Whether a camera move justifies re-placing arrows |

### Diagnostics

`exportFixture(sessionID:name:)` now ships in **release** builds.

### Configuration

| Field | Default | Effect |
|---|---|---|
| `motion.stopOnStationary` | `false` | Ends the session when the machine settles — a real `stop()` |
| `motion.disableStopDetection` | `false` | Never settles; keeps a live position while parked |
| `motion.stillConfidenceMin` | `100` | The bar a `.still` label must clear to start a stop |
| `persistence.persistHeartbeat` | `false` | Stores the stationary heartbeat instead of discarding it |
| `sensors.useAccelerometerVeto` | `false` | Second stillness signal for devices with no pedometer |
| `sensors.useBarometer` | `false` | Vertical motion, so a lift is not read as standing still |
| `sensors.activityRecognitionIntervalMs` | `0` | Throttles activity updates. Saves no battery |

**Breaking:** `motion.stopTimeoutSec` → `motion.stopTimeoutMin`, in minutes. A
config persisted under the old key still decodes, rounded up. The
`Builder.stopTimeoutSec(_:)` method is likewise `stopTimeoutMin(_:)`.

**Cross-platform:** `motion.motionTriggerDelayMs` and `service.healthLoopMs` are
accepted as decode aliases for the `…Sec` fields. Nothing is renamed — the Swift
names differ, so no host can set the wrong one. It closes the silent case: one
JSON config feeding both platforms.

### Storage

- **v7** — `geofence_event`: every crossing, with the fence's centre and radius
  copied onto the row so it stays readable after the fence is gone.
- **v8** — `geofence_dwell`: the dwell delay per fence. A sidecar because
  `CLCircularRegion` has no user-info field and iOS has no dwell transition.

---

## 2. Bugs found and fixed

Ordered by how badly each one would have hurt in the field.

**The accelerometer veto measured one instant and called it an interval.**
`startAccelerometerUpdates()` with no handler is the *pull* form —
`accelerometerData` is one reading, not a queue — so the "mean since the last
stored point" was a single sample. A carried phone passes through 1 g constantly,
so real movement could be rejected as drift, on exactly the devices (no
pedometer) the feature exists to serve. Now accumulates every sample at 10 Hz and
answers `nil` below half a second of them.

**The barometer could hold a stale rate forever.** If `CMAltimeter` went quiet,
the last vertical speed kept suppressing the stillness checks for the rest of the
session. Readings older than five seconds now read as absent.

**The pedometer query had nothing bounding it.** `consumeSteps()` awaited
CoreMotion on the ingest path, before the pipeline judges anything. Woken in the
background, the process has seconds; spending them inside CoreMotion is how an
accepted point fails to be written. Capped at 1.5 s, then `nil` — the same
fail-open path as a device with no pedometer.

**`stopOnStationary` re-armed the session it had just closed.** Ending a session
from inside the motion callback re-enters the controller; actors are reentrant,
so teardown ran to completion and then `handle()` resumed and carried on
configuring a torn-down stream.

**CoreMotion's stride-pause noise reached the state machine.** `.still` arrives
constantly inside ordinary movement — between strides, at a gear change, at a red
light. Each reading drove `.moving → .stopPending` and the next drove it back, so
`motionChange` flapped at walking cadence and the decision log filled with stops
that never happened.

**A fence armed around your current position fired nothing.** CoreLocation
reports transitions, and arming a fence around yourself is not one — so the most
natural way to create a fence reported nothing until you left and came back, and
its dwell could never start. `requestState(for:)` after arming closes it.
Duplicate entries are dropped so a re-delivered region state cannot restart the
dwell clock.

**Three features were built and never connected.** `onChangePace` was fully
handled by the state machine with zero callers. `TrackerState.providerState` was
declared, documented and written by nothing. `TrackerEvent.heartbeat` was fired by
nothing. `EventCoverageTests` now fails the build for any event case with no
emitter or published field with no writer.

**Both map panes opened on a view of the whole world.** `LiveTrackMapView` moves
the camera only when a frame arrives — with no session, none does, ever. The Plot
pane's `fit()` returned early when there were no bounds and left `.automatic`.

**The Apple Maps attribution left the corner.** `.safeAreaPadding(80)` framed the
track away from the edges, and MapKit anchors its attribution to the safe area, so
the logo floated 80 points up into the route. Framing now expands the fitted
rectangle instead.

**Direction arrows were drawn at a fixed 30 pt** while the stroke scaled from 10
down to 3 — ten times the line width across a city, which turned a 15 km commute
into a chain of chevrons.

**Buttons hyphenated themselves.** SwiftUI hyphenates before it truncates, so
three buttons sharing a row produced `Dump ses-sion` over three lines. The same
rule broke fact rows at accessibility sizes: `Authoriza-tion` opposite
`RECORD-ING`, a session id split as `F480E52 6`.

**The empty Plot state contradicted the summary card six inches below it** — "none
of them was stored", above `7 → 1, Points 1`.

---

## 3. Mistakes I made and corrected

Kept because each one is cheap to repeat.

**A rebuild-on-zoom that starved the map's gestures.** To make arrow spacing
follow the camera I rebuilt the track on every settle — a full `buildTrack`, then
the whole plot replaced, re-rendering every polyline mid-pinch. That is the same
continuous invalidation that had just been removed with the travelling arrow, in a
new shape, and it presented identically: a map that fights back when you zoom out.
Replaced by thinning the engine's own anchors, which computes nothing and rebuilds
nothing.

**A guard that compiled, ran, and did nothing.** `frameIfEmpty` tested
`lastSequence == 0`, but the "no frame ever applied" sentinel is `.min`. It was a
no-op in every case it existed for, and only a screenshot caught it.

**An app icon with no icon in it.** The first `Contents.json` used `scale` where
the single-size iOS icon needs `size`. `actool` warned "the app icon set has an
unassigned child" and the build reported success with no icon in the bundle.

**Four failed icon drafts**, all recorded in `scripts/make-app-icon.py`: PIL
cannot draw wide strokes (no round joins or caps); chaining cubics and shortening
the last one curls the line, because the derivative at the end is a function of
the final control point; and an arrowhead needs a gap wider than its own arms
reach or the shaft ends inside the V.

---

## 4. The sample app

- **Fences tab** — arm a region at your position with an optional dwell, the armed
  list, a live feed and the stored history side by side, because the difference
  between those two lists is the whole reason crossings are stored.
- **Upload screen** (Home → Upload) — endpoint, token, pending count, both manual
  triggers, and one feed line per HTTP exchange.
- **One fix** button, and a **fixture recorder** that now works in both package
  modes.
- **An app icon**, generated by `scripts/make-app-icon.py`.
- Maps that open on the device instead of a continent.

Both new screens paid for themselves immediately: the Fences tab exposed the
missing initial trigger and the duplicate-entry dwell reset; the Upload screen
exposed that a host had no way to ask the engine whether it was still configured.

---

## 5. The HTTP layer, as it stands

`SyncTransport` is a one-method protocol — `upload(SyncRequest) async ->
SyncResponse` — whose implementations **must not throw**: the queue needs one of
three outcomes, and a thrown error cannot distinguish a dead credential from a
dropped tunnel.

`URLSessionSyncTransport` sends `POST` with a JSON body, applies the host's
headers, and sets `Content-Type` only if the host did not. 30 s request timeout,
60 s resource timeout, `waitsForConnectivity = true`. 2xx is the only outcome that
marks rows uploaded; 401 is terminal and tears the uploader down; everything else
leaves every row queued and steps the backoff, which doubles from 30 s to a
30-minute ceiling and resets on success or an empty queue.

Deliberately absent: token refresh (the host's auth stack owns it) and pinning or
signing (the transport seam covers both).

**Gaps worth knowing before extending it:**

1. The response body is discarded — the server cannot report partial success or
   ask the client to stop.
2. `Retry-After` is ignored, so a 429 gets our schedule rather than the server's.
3. No compression; a 100-point batch is tens of KB of JSON over cellular.
4. Not a background `URLSession` — a suspension mid-flight kills the request
   rather than handing it to the system.
5. Timeouts are baked into the default transport; overriding means supplying a
   whole `URLSession`.
6. `SyncConfig(url:)` accepts `http://`, which ATS blocks at runtime rather than
   at configuration time.
7. Only 401 is terminal. A 403 for a revoked key retries forever — the same silent
   battery burn 401 handling exists to prevent.

---

## 6. What is verified, and what is not

**Verified by the test suite** — 769 tests, one skipped, engine golden corpus
untouched throughout.

**Verified by driving the real API in the simulator:**

- One-shot location returned the simulated coordinate.
- Geofence exit, re-entry, and an immediate entry for a fence armed in place.
- A one-minute dwell recorded at entry + 60 s — three seconds *before* the SDK
  noticed it, which is the timestamp rule working.
- Sync against a server scripted to answer 200, 200, 500: two batches uploaded
  (100 + 87 points), then `500 for 6 points` with the retry scheduled 120 s out.
- The fixture recorder wrote a valid fixture from the **release** frameworks.
- Layout at three Dynamic Type sizes, including the largest accessibility size.

**Verified against the shipped binaries**, not the source package: the example app
builds against the published XCFrameworks, and every new symbol appears in the
stripped `.swiftinterface`.

**Not verified at all — no device testing was run this cycle.** Everything below
is unproven on hardware and cannot fail in a simulator, because a simulator never
suspends or terminates your app the way iOS does:

- Geofence crossings delivered while suspended.
- The relaunch that delivers a crossing to a terminated app.
- All four dwell evaluation paths except the in-process timer.
- The authorization ladder, background relaunch, and termination → filter reseed.
- The 20-region cap against a real CoreLocation.

If a customer reports "my fence never fired", that list is where to look first.

---