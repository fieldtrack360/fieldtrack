# Traker ProGuard and R8 Setup

## Purpose

Traker publishes minified release AARs so an app receives a usable SDK API without receiving readable implementation class names, package structure, debug logs, or source archives. This raises the cost of reverse engineering; it does not encrypt bytecode, hide runtime constants, or make client-side secrets safe.

## Release Artifacts

| Module | Published form | Stable integration package | Obfuscated implementation package |
|---|---|---|---|
| `fieldtrack-geo` | R8-minified AAR | `com.devstree.traker.geo.*` for supported types | `tr.dev.geo` |
| `fieldtrack-core` | R8-minified AAR | `com.devstree.traker.*` supported API | `tr.dev.core` |
| `fieldtrack-maps` | R8-minified AAR | Three renderer entry points and option types | Private methods obfuscated; all helper classes optimized away |
| `fieldtrack-snap` | R8-minified AAR | `OsrmSnapProvider` | `tr.dev.snap` |
| `fieldtrack-sync` | R8-minified AAR | Sync configuration, transport, queue, and facade types | `tr.dev.sync` |
| `traker-all` | Empty umbrella AAR | No classes | Not applicable |

`fieldtrack-geo` still contains pure Kotlin source with no Android imports. Android library packaging is used only so AGP can run R8 and include consumer rules in the published artifact.

## Rule Types

Each code-bearing module has two distinct rule files:

- `proguard-rules.pro` runs while Traker builds its own release AAR. It protects the supported API, preserves reflection entry points, strips logs, and repackages implementation.
- `consumer-rules.pro` is embedded in the AAR and merged into a consuming application's R8 pass. It must contain only rules required for runtime correctness in the host.

Do not put broad rules such as `-keep class com.devstree.traker.** { *; }` in consumer rules. That would disable shrinking and obfuscation for the complete SDK inside every host app.

## Stable API Policy

Names remain unchanged when at least one of these is true:

- The Android sample imports the type or calls the member.
- Another independently published Traker module references the binary name.
- Android, Room, WorkManager, or serialization constructs the type by name or generated contract.
- The type is a documented host extension seam, such as `TrackLogger`, `RoadSnapProvider`, or `SyncTransport`.

Everything else may be optimized, shortened, repackaged, or removed. When adding a supported sample API, update the module's build-time rules and the required API list in `VerifyReleaseObfuscationTask` in the root [build.gradle.kts](../build.gradle.kts).

Some names must remain visible for runtime loading. In core these include manifest components, `ListenableWorker` subclasses, `TrakerDatabase`, and `TrakerDatabase_Impl`. Hiding those names without also rewriting the host's merged manifest or reflective lookup would break the SDK.

If you inspect the release AAR in an IDE, you will still see the kept API and framework seams
under `com.devstree.traker.*`. That is expected. The obfuscated implementation classes and
helper methods are repackaged under `tr.dev.core`, and the release mapping is what retraces
them back to source names.

## Models and Serialization

Supported model packages retain public class and member names so Kotlin/Java consumers keep named accessors and readable API signatures. Generated serializers, internal database entities, and private transport implementation models are obfuscated.

That also means the track-planning model graph stays readable: `Track`, `TrackOptions`,
`TrackSegment`, `TrackStats`, `TrackJsonPoint`, `StopNode`, `ArrowAnchor`,
`LiveTrackUpdate`, `PuckState`, `SegmentType`, and `Smoothing` remain named because the
sample, `fieldtrack-core`, and `fieldtrack-maps` all compile against them.

Kotlin serialization generates descriptors containing wire keys. R8 may rename a Kotlin property without changing the serialized JSON key. Enum constants are separately preserved because persisted rows and some wire values use `name`/`valueOf`; renaming those constants would silently change data semantics.

## Release Logging

Debug variants set `BuildConfig.SDK_LOGGING_ENABLED=true`. Release variants set it to `false`.

All SDK logger calls are inside lazy `sdkLog` blocks. During release optimization R8 constant-folds the flag before the log message is constructed, removing both the call and its message string. Direct `android.util.Log` calls are also declared side-effect-free for every log level in release rules.

Do not add a direct `logger.d`, `logger.w`, `Log.*`, `println`, or `printStackTrace` call. Use the module's `sdkLog` wrapper and add a forbidden marker to the verifier when introducing a new logging vocabulary.

Structured host-facing events such as `TrakerEvent.Error` are API, not logs. Their documented error codes and messages remain available at runtime.

## Publishing Security

- Source JARs are intentionally disabled. Publishing source beside an obfuscated AAR defeats the protection.
- Javadoc JARs contain rendered public API HTML and assets, but no `.kt` or `.java` source entries.
- R8 mappings are not embedded in AARs or Maven publications.
- Mapping files are required to retrace production stack traces and must be archived in restricted release storage.
- Repository credentials come from Gradle properties or `TRAKER_MAVEN_*` environment variables and must never be committed.
- API keys, auth tokens, encryption keys, and server secrets cannot be protected by obfuscation. Do not ship them in the SDK.

## Verification Workflow

```bash
./gradlew clean verifyReleaseObfuscation
./gradlew publishToMavenLocal
./gradlew :sample-android:assembleRelease
```

`verifyReleaseObfuscation` checks the nested `classes.jar` in every release AAR for:

- Required sample and cross-module API class names.
- Shortened implementation classes in `tr.dev.<module>`; Maps currently optimizes all helper classes away.
- Absence of shortened helper classes or generated serializers in public API packages.
- Absence of the former descriptive `*.internal` packages.
- Absence of known SDK log strings.
- Absence of embedded R8 mapping files.
- Absence of generated source JARs.
- Absence of source entries inside generated Javadoc JARs.

Every Maven publish task depends on this verification. The minified sample release then exercises consumer rules after the newly verified artifacts have been published to Maven Local.

## Mapping Files

Each minified module writes:

```text
traker-<module>/build/outputs/mapping/release/
```

Keep at least `mapping.txt`, the SDK version, commit SHA, and artifact checksums together. Use the mapping from the exact released module version when retracing a crash; mappings from a rebuild are not interchangeable.

The build now provides a local archival step for that bundle:

```bash
./gradlew archiveReleaseMappings
```

It copies `mapping.txt`, `seeds.txt`, `usage.txt`, and a `manifest.txt` with the
version, commit SHA, and AAR checksum into `build/release-mappings/<version>/<module>/`.
That directory is the release-storage handoff, not a public artifact.

## Change Checklist

1. Add or change the API and sample usage.
2. Add only the required build-time keep rule; avoid package-wide keeps unless the package is intentionally all model/API surface.
3. Add the required class entry to `VerifyReleaseObfuscationTask`.
4. Run unit tests and `verifyReleaseObfuscation`.
5. Publish locally and build the minified sample release.
6. Inspect `mapping.txt`, `seeds.txt`, and `usage.txt` when a keep rule behaves unexpectedly.
7. Archive mappings and checksums with the release.
