# FieldTrack

Background location tracking and track plotting SDK for Android.

- **Single user.** `start()` / `stop()`. No company, no employee, no attendance.
- **Motion-aware capture.** Activity recognition + hardware speed + stationary geofence drive a moving/stationary state machine; a 7-stage acceptance pipeline over a constant-velocity Kalman filter rejects nine named classes of GPS noise. A user sitting still for two hours produces one point, not a drift cloud.
- **Offline-first.** Room storage, no backend required. HTTP sync is a separate optional module.
- **Plotting is the product.** `buildTrack()` returns a ready-to-draw polyline: encoded geometry, per-segment speed bands, stop nodes, and **precomputed arrow anchors with bearings** — as portable JSON or GeoJSON, drawable by any map library.
- **Turn geometry, five ways.** Adaptive cadence at speed, a turn-burst sampling tier, bearing-change force-capture, cornering process noise so a constant-velocity filter stops predicting straight through a turn, and a centripetal Catmull-Rom spline through every vertex so a 120 m leg is a curve and not a chord — all offline and on by default. Map-matching is optional on top: install a `RoadSnapProvider` (one ships in `trackit-snap`, against OSRM) and the polyline follows the road, with an 80 m guard so a parallel service road never gets to relocate the user. No provider, no network, no key: the track still renders.
- **Every decision has one home.** Algorithms live in `trackit-geo` as pure Kotlin with no Android imports, so the whole engine runs in local unit tests with no emulator. It is packaged as an Android AAR so release bytecode can be R8-obfuscated. Constants live in one `TrakerConstants`. `Arrows.place()` feeds both the map renderer and the JSON export, so the drawn track and the exported track cannot disagree.
- **Every fix is answerable.** A decision log records why each fix was accepted, skipped or rejected, with reason strings as API. Any accuracy complaint replays deterministically as a JVM test.

**Android only, Kotlin only.** No iOS and no Flutter, permanently. The engine runs on Android and nowhere else. `trackit-geo` keeps a pure-Kotlin source boundary for local testing; its published form is an AAR, not a multiplatform artifact.

Namespace com.devstree.traker and Maven group com.github.fieldtrack360.fieldtrack. minSdk 26, compileSdk 37, JDK 17.

## Status

**Implemented and building; not yet released.** The engine, the Android capture stack, plotting, the sample app and the optional artifacts all exist. Publishing is configured and works locally. What is missing is field validation and a first release.

| Phase | State |
|---|---|
| 0 Scaffold · 1 Geo engine · 2 Android capture | Done |
| 3 Permissions & resilience · 4 Plotting · 6 Optional modules | Done |
| 5 Sample app | Mostly — 5 of the 7 planned screens; fixture record/replay and export screens are not built |
| 7 Harden & ship | **Not started** — see below |

Known gaps, stated rather than discovered:

- **Nothing has been published to a remote yet.** The plumbing exists — six artifacts under `com.github.fieldtrack360.fieldtrack`, with obfuscated AARs and javadoc jars but no source jars, and `./gradlew publishToMavenLocal` works with no configuration — but no repository URL is configured and no release has been cut. See [BUILD.md](docs/BUILD.md) §5.5.
- **Release mappings are archived locally, not published.** `./gradlew archiveReleaseMappings` copies the R8 outputs into `build/release-mappings/<version>/<module>/` for the release storage handoff described in [PROGUARD-SETUP.md](docs/PROGUARD-SETUP.md).
- **`trackit-maps` has no tests.** It is thin by design — it consumes `Arrows.place()` and draws — but "thin" is not "verified", and nothing currently fails if it stops rendering.
- **No fixture corpus.** The replay *harness* is done and used in tests (`FixtureReplay`), but no recorded field fixtures are committed. Constant tuning against real drives is phase 7.
- **No OEM field matrix.** The survival stack is written and unit-tested; it has not been run across the four-OEM matrix phase 7 calls for.
- **Room migrations are untested.** The database is at v4 and all three migrations are hand-written and additive, but `MigrationTestHelper` needs the schema directory in androidTest assets, which AGP 9 currently rejects ([BUILD.md](docs/BUILD.md) §7).
- **The platform `LocationManager` source has no instrumented test.** It ships — `GeolocationConfig.providerType` selects `GPS_ONLY`, `NETWORK_ONLY` or `PASSIVE`, none of which need Play Services, which is the remedy EC-19 previously had none for — but registration has only been covered at the config level on the JVM, never on a device.
- **`./gradlew lintDebug` is red** on one pre-existing `InlinedApi` error in `TrackingService`. Every other check passes.
- **[API.md](docs/API.md) §10 is partly ahead of the code.** Several target entries (`setConfig`, `changePace`, `insertPoint`, `deletePoints`, `requestPermission`, `exportFixture`) are not on `Traker` yet. Android exposes `getCurrentLocation()` for the one-shot location operation.

## Modules

| Module | What it is |
|---|---|
| `trackit-geo` | Pure-Kotlin engine packaged as an obfuscated AAR. Kalman filter, acceptance pipeline, motion state machine, turn detection, and plotting. |
| `trackit-core` | The Android library and the public SDK surface. Capture, Room storage, foreground service, permissions, workers, DI. |
| `trackit-maps` | Optional. Google Maps rendering that consumes `Arrows.place()` rather than recomputing it. |
| `trackit-sync` | Optional. HTTP upload with a retry queue and 401 teardown. OkHttp is `compileOnly`. |
| `trackit-snap` | Optional. `OsrmSnapProvider` — map-matching against an OSRM server. Depends on `trackit-geo` only. OkHttp is `compileOnly`. |
| `sample-android` | The demo app: start/stop, live map with arrows, 3-layer debug overlay, decision-log viewer. |

Dependency direction, with nothing pointing backwards: `sample-android`, `trackit-sync` → `trackit-core` → `trackit-geo` ← `trackit-maps`, `trackit-snap`.

## Using it

**No DI framework, no Gradle plugin, no annotation processor.** The whole integration is one call. The graph is wired by hand in `di/TrakerGraph.kt`; an earlier revision shipped Hilt inside `trackit-core` and required every host to apply the Hilt plugin and annotate its `Application` with `@HiltAndroidApp` — that was removed, and the reasoning is in [PLAN.md](docs/PLAN.md) §0.

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
