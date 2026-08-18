# FieldTrack

Background location tracking and track plotting SDK for Android.

- **Single user.** `start()` / `stop()`. No company, no employee, no attendance.
- **Motion-aware capture.** Activity recognition + hardware speed + stationary geofence drive a moving/stationary state machine; a 7-stage acceptance pipeline over a constant-velocity Kalman filter rejects nine named classes of GPS noise. A user sitting still for two hours produces one point, not a drift cloud.
- **Offline-first.** Room storage, no backend required. HTTP sync is a separate optional module.
- **Plotting is the product.** `buildTrack()` returns a ready-to-draw polyline: encoded geometry, per-segment speed bands, stop nodes, and **precomputed arrow anchors with bearings** — as portable JSON or GeoJSON, drawable by any map library.
- **Turn geometry, five ways.** Adaptive cadence at speed, a turn-burst sampling tier, bearing-change force-capture, cornering process noise so a constant-velocity filter stops predicting straight through a turn, and a centripetal Catmull-Rom spline through every vertex so a 120 m leg is a curve and not a chord — all offline and on by default. Map-matching is optional on top: install a `RoadSnapProvider` (one ships in `fieldtrack-snap`, against OSRM) and the polyline follows the road, with an 80 m guard so a parallel service road never gets to relocate the user. No provider, no network, no key: the track still renders.
- **Every decision has one home.** Algorithms live in `fieldtrack-geo` as pure Kotlin with no Android imports, so the whole engine runs in local unit tests with no emulator. It is packaged as an Android AAR so release bytecode can be R8-obfuscated. Constants live in one `TrakerConstants`. `Arrows.place()` feeds both the map renderer and the JSON export, so the drawn track and the exported track cannot disagree.
- **Every fix is answerable.** A decision log records why each fix was accepted, skipped or rejected, with reason strings as API. Any accuracy complaint replays deterministically as a JVM test.

**Android only, Kotlin only.** No iOS and no Flutter, permanently. The engine runs on Android and nowhere else. `fieldtrack-geo` keeps a pure-Kotlin source boundary for local testing; its published form is an AAR, not a multiplatform artifact.

Namespace com.devstree.traker and Maven group com.github.fieldtrack360.fieldtrack. minSdk 26, compileSdk 37, JDK 17.

## Using it

```kotlin
val trackIt = Traker.getInstance(context)   // idempotent, one per process

suspend fun begin() {
    trackIt.ready(TrakerConfig())          // resolves config, restores filter state
    trackIt.start(tag = "commute")
}

// Later, anywhere:
val track = trackIt.buildTrack(PointQuery(sessionId = id))
val json = trackIt.exportPolylineJson(PointQuery(sessionId = id))
```

Optional map-matching, if you run an OSRM instance:

```kotlin
trackIt.setRoadSnapProvider(OsrmSnapProvider(baseUrl = "https://osrm.example.com"))
```

There is deliberately no default `baseUrl` — see [API.md](docs/API.md) §3.

## Building

```bash
./gradlew assembleDebug
./gradlew :sample-android:installDebug
./gradlew verifyReleaseObfuscation                # inspect release AAR bytecode
./gradlew archiveReleaseMappings                  # collect R8 mappings for release storage
./gradlew publishToMavenLocal                     # six artifacts, com.github.fieldtrack360.fieldtrack
./gradlew :sample-android:assembleRelease          # runs R8 over the SDK's consumer rules
```

Full manual, including the AGP 9 gotchas, in [BUILD.md](docs/BUILD.md).

## Documentation

| Document | What it holds |
|---|---|
| [docs/DEVELOPER-GUIDE.md](docs/DEVELOPER-GUIDE.md) | Source-verified developer reference: setup, complete method catalog, configuration, lifecycle, maps, sync, snapping, diagnostics, and production checklist |
| [docs/USER-GUIDE.md](docs/USER-GUIDE.md) | The integration manual: install, permissions, config, plotting, live tracking, diagnostics, troubleshooting — **start here if you are using the SDK** |
| [docs/PLAN.md](docs/PLAN.md) | Scope, architecture, provenance, phases, risks — **start here if you are working on it** |
| [docs/BUILD.md](docs/BUILD.md) | Build manual: prerequisites, module recipes, version catalog, commands, gotchas, CI |
| [docs/API.md](docs/API.md) | Real Kotlin: types, pipeline, ingestor, ports, service, Room schema, public API, config, plotting, manifest |
| [docs/PERMISSIONS.md](docs/PERMISSIONS.md) | Permission ladder, tier degradation, live revocation, FGS by API level, survival stack |
| [docs/EDGE-CASES.md](docs/EDGE-CASES.md) | 138 catalogued cases: trigger, symptom, handling, owner, test |
| [docs/POLYLINE-JSON.md](docs/POLYLINE-JSON.md) | The export contract — polyline JSON, arrows, GeoJSON, fixture format |
| [docs/SDK-COMPARISON.md](docs/SDK-COMPARISON.md) | Feature-by-feature identification vs the incumbent SDK; stop detection, headless, config reset, device sensors |
| [docs/SOURCE-AUDIT.md](docs/SOURCE-AUDIT.md) | 18 findings from reading the reference implementation, with `file:line` |
| [docs/reference/capture-and-plotting-spec.md](docs/reference/capture-and-plotting-spec.md) | The algorithm bible — every filter stage, constant and plotting rule, field-verified |
| [docs/reference/EKF-DESIGN-REVIEW.md](docs/reference/EKF-DESIGN-REVIEW.md) | Review of a third-party EKF-based SDK design document |
