# Device Integrity Layer — Implementation Plan

Status: **implemented** (2026-08-18). Kept as the design record — the rationale below is
why the code looks the way it does. Host-facing documentation lives in
[INTEGRATION-GUIDE.md §19](INTEGRATION-GUIDE.md#19-device-integrity).
Scope: `fieldtrack-core`, `fieldtrack-sync`, new `fieldtrack-lint`, buildSrc, docs
Target version: next minor after `0.1.1-alpha01`

---

## 1. Goal

Add a second security layer beside the license gate: detect a device or process
environment that can forge location data, report it to the host and the backend, and
optionally refuse to track.

Six checks requested:

| # | Check | Threat it addresses |
|---|---|---|
| 1 | Accessibility service enabled | Automation frameworks driving the app UI, click-farm tooling |
| 2 | Developer options / ADB on | Prerequisite for selecting a mock-location app and for `adb` injection |
| 3 | Frida / instrumentation running | Runtime hooking of `Location` objects, license bypass, filter bypass |
| 4 | Timezone or clock tampering | Backdating punches, faking a shift in another region |
| 5 | Third-party mock-location app | Fake GPS apps |
| 6 | Release build shipping with checks disabled | Integrator neutering the layer, deliberately or by copy-paste |

Rule that governs all of them: **enforcement only in release. A debuggable host app is
fully waived.** This mirrors `LicenseEnvironment.isWaived()` in
`fieldtrack-core/src/main/kotlin/com/devstree/traker/license/LicenseGate.kt:44`, which
already waives licensing when `ApplicationInfo.FLAG_DEBUGGABLE` is set. The integrity
layer reuses that exact predicate, so there is one definition of "debug" in the SDK.

### 1.1 Honest limits (read before committing to this)

- Client-side anti-tamper is defence in depth, never proof. A determined attacker with
  Frida can patch the evaluator itself. The value is raising cost and producing an
  **auditable signal** the backend can act on.
- Therefore every signal must also travel to the server with the point that produced it.
  Server-side evaluation is the part that cannot be patched by the attacker.
- Package-visibility rules on Android 11+ make "enumerate installed mock apps" only
  partially possible without `QUERY_ALL_PACKAGES` (a Play-policy-sensitive permission
  this SDK must not force on hosts). See §4.5 for what is actually detectable.
- Accessibility services are also how disabled users operate their phone. Blocking on
  accessibility by default would lock those users out of the host app. Default policy for
  that signal is `WARN`, with a system-service allowlist. This is a deliberate product
  decision, not an oversight.

---

## 2. Architecture

New package `com.devstree.traker.integrity` inside `fieldtrack-core`. No new runtime
module — the layer must be able to gate `ready()`/`start()`, and a gate that a host can
choose not to depend on is not a gate.

```
fieldtrack-core/src/main/kotlin/com/devstree/traker/integrity/
├── IntegrityModels.kt      // public: IntegritySignal, IntegrityFinding, IntegrityReport, IntegrityPolicy
├── IntegrityProbe.kt       // internal port: fun probe(): IntegrityFinding?   (one per check)
├── IntegrityEvaluator.kt   // internal: runs probes, applies policy, produces IntegrityReport
├── IntegrityMonitor.kt     // internal: StateFlow<IntegrityReport>, re-evaluation cadence, event emission
└── probes/
    ├── AccessibilityProbe.kt
    ├── DeveloperModeProbe.kt
    ├── HookingProbe.kt          // Frida + ptrace + Xposed
    ├── ClockIntegrityProbe.kt
    └── MockLocationProbe.kt
```

Probes are `internal` and behind a port, so `IntegrityEvaluator` is unit-testable with
fakes on the JVM — same pattern as `MotionPorts.kt` / `BatteryProbe.kt`. Wiring goes into
`di/TrackerGraph.kt` next to `sensorProbe` and `batteryMonitor`.

### 2.1 Public model

```kotlin
public enum class IntegritySignal {
    ACCESSIBILITY_SERVICE_ACTIVE,
    DEVELOPER_MODE_ENABLED,
    ADB_ENABLED,
    HOOKING_FRAMEWORK_DETECTED,
    DEBUGGER_ATTACHED,
    CLOCK_SKEWED,
    AUTO_TIME_DISABLED,
    TIMEZONE_MISMATCH,
    MOCK_LOCATION_APP_SELECTED,
    MOCK_LOCATION_FIX,
}

public enum class IntegrityPolicy { ALLOW, WARN, BLOCK }

public data class IntegrityFinding(
    val signal: IntegritySignal,
    val policy: IntegrityPolicy,     // policy applied to this signal
    val detail: String,              // human-readable, safe to log
    val confidence: Int,             // 0..100 — HookingProbe is multi-indicator
)

public data class IntegrityReport(
    val evaluatedAtMs: Long,
    val waived: Boolean,             // true in a debuggable host app
    val findings: List<IntegrityFinding>,
) {
    public val blocked: Boolean get() = findings.any { it.policy == IntegrityPolicy.BLOCK }
    public val flags: Int            // stable bitmask for storage and wire — see §6
}
```

One `ErrorCode` is added, not one per signal: `DEVICE_INTEGRITY_BLOCKED`. Detail lives in
the report, and the error message names the blocking signals. This keeps the existing
`ErrorCode` enum (`domain/model/TrackSession.kt:34`) from doubling in size.

### 2.2 Config

New `SecurityConfig` block on `TrackerConfig`, alongside `sensors`/`persistence`:

```kotlin
@Serializable
public data class SecurityConfig(
    val enabled: Boolean = true,
    val accessibility: IntegrityPolicy = IntegrityPolicy.WARN,
    val developerMode: IntegrityPolicy = IntegrityPolicy.WARN,
    val hooking: IntegrityPolicy = IntegrityPolicy.BLOCK,
    val clock: IntegrityPolicy = IntegrityPolicy.WARN,
    val mockLocation: IntegrityPolicy = IntegrityPolicy.BLOCK,
    /** Accessibility packages that never raise a finding. System a11y services are always allowed. */
    val accessibilityAllowlist: Set<String> = emptySet(),
    /** Max tolerated |GNSS UTC − system clock| before CLOCK_SKEWED. */
    val maxClockSkewMs: Long = 120_000,
    /** Re-evaluation cadence while a session is open. 0 disables periodic re-checks. */
    val recheckIntervalMs: Long = 15 * 60_000,
)
```

Validation added to `TrackerConfig.validate()`: `maxClockSkewMs >= 0`,
`recheckIntervalMs == 0L || recheckIntervalMs >= 60_000`.

`mockLocation = BLOCK` also forces `geolocation.mockLocationPolicy = MockPolicy.REJECT`
during config resolution, so the two settings cannot contradict each other. That
resolution belongs in `ResolveConfigUseCase` (`domain/usecase/TrackingUseCases.kt`).

### 2.3 The waiver

```kotlin
internal object IntegrityEnvironment {
    fun isWaived(context: Context): Boolean = LicenseEnvironment.hasGetTaskAllow(context)
}
```

When waived: probes do not run at all (zero cost in debug), the report is
`IntegrityReport(waived = true, findings = emptyList())`, nothing blocks, and a single
`TrackerEvent.Diagnostic("integrity: waived — debuggable build")` is emitted at `ready()`
so the state is visible rather than silent.

**Hardening (recommended, optional):** a repackaged APK can set `debuggable=true` to
claim the waiver. Re-signing changes the signing certificate, so bind the waiver to the
signature: extend `LicenseVerifier` to compare the SHA-256 of
`PackageInfo.signingInfo.apkContentsSigners[0]` against a hash carried in the license
token. Waiver then requires debuggable **and** an unknown/dev signature; a release-signed
APK cannot buy the waiver by flipping a manifest flag. Track as a separate task — it is a
license-token format change.

---

## 3. Enforcement points

| Point | Behaviour |
|---|---|
| `Tracker.ready()` | Evaluate after the license gate passes, before `resolveConfig`. `BLOCK` → emit `TrackerEvent.Error` and return `TrackerResult.Error(DEVICE_INTEGRITY_BLOCKED, …)`, exactly like the license path at `Tracker.kt:210-219`. |
| `Tracker.start()` | Re-evaluate (cheap probes only) — a device can be tampered with between `ready()` and `start()`. `BLOCK` → `TrackerResult.Error`, no session opened. |
| `HealthLoop` | Every `recheckIntervalMs` while tracking, full re-evaluation. On a new `BLOCK` finding: emit the error event and stop the session through the existing `StopTrackingUseCase`. |
| `FixIngestor` | Stamps the current report's `flags` onto every accepted point (no probe work per fix — reads the monitor's cached `StateFlow` value). |
| `TrackerSync` | Uploads the flags with the point (§6). |

New event: `TrackerEvent.IntegrityChange(val report: IntegrityReport)` — emitted only when
the flag bitmask changes, not on every re-evaluation.

New public API on `Tracker`:

```kotlin
public fun integrity(): IntegrityReport                  // last evaluation, no probing
public suspend fun checkIntegrity(): IntegrityReport     // force a fresh evaluation
public fun integrityState(): StateFlow<IntegrityReport>  // live, like providerState()
```

---

## 4. The probes

Each probe returns `null` when clean. Each catches its own `Throwable` and returns `null`
on failure — a probe that crashes must never take down `ready()`.

### 4.1 AccessibilityProbe → `ACCESSIBILITY_SERVICE_ACTIVE`

- `AccessibilityManager.getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)`; fall back
  to `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` + `ACCESSIBILITY_ENABLED` when the
  manager returns empty (some OEM ROMs under-report).
- Filter out: services whose package is a system package (`ApplicationInfo.FLAG_SYSTEM`),
  the host's own package, and anything in `accessibilityAllowlist`.
- `detail` lists the offending package names, capped at five.
- Confidence 100 — this is a factual query, not an inference.

### 4.2 DeveloperModeProbe → `DEVELOPER_MODE_ENABLED`, `ADB_ENABLED`

- `Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0)`
- `Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, 0)` → separate finding, since
  "developer options open" and "USB debugging on" are different risk levels.
- Emitted as two findings so a host can set a stricter policy for ADB by editing the enum
  map, but both are driven by `SecurityConfig.developerMode` in v1 to keep config small.

### 4.3 HookingProbe → `HOOKING_FRAMEWORK_DETECTED`, `DEBUGGER_ATTACHED`

Multi-indicator, confidence-scored. No single indicator blocks on its own; the finding is
raised at confidence ≥ 60.

| Indicator | Method | Weight |
|---|---|---|
| Frida agent mapped | scan `/proc/self/maps` for `frida-agent`, `frida-gadget`, `libgadget`, `re.frida` | 60 |
| Frida thread names | read `/proc/self/task/*/comm` for `gmain`, `gum-js-loop`, `pool-frida`, `gdbus` | 40 |
| Frida default ports | `Socket()` connect to `127.0.0.1:27042` and `:27043`, 100 ms timeout, off the main thread | 30 |
| Xposed / LSPosed | stack-trace scan for `de.robv.android.xposed`, `XposedBridge` class load attempt | 40 |
| Native tracer | `/proc/self/status` → `TracerPid` non-zero | 50 → also raises `DEBUGGER_ATTACHED` |
| Java debugger | `android.os.Debug.isDebuggerConnected()` | 30 → `DEBUGGER_ATTACHED` |

Notes:
- All file reads are `/proc/self/*` — no permission needed, no `QUERY_ALL_PACKAGES`.
- Run on `Dispatchers.IO`. Budget: full sweep ≤ 30 ms on a mid-range device. Measure it.
- Port-scan indicator is off by default on emulators (`Build.FINGERPRINT` contains
  `generic`/`sdk_gphone`) because CI emulators produce noise.
- Do not centralise the verdict in one `fun isTampered(): Boolean`. Evaluate at each
  enforcement point from the monitor's state, so one patched method does not disable the
  layer everywhere. The release AAR is already R8-obfuscated (`fieldtrack-core/build.gradle.kts:31`),
  which helps; do not add `-keep` rules for the `integrity` package beyond the public
  model types.

### 4.4 ClockIntegrityProbe → `CLOCK_SKEWED`, `AUTO_TIME_DISABLED`, `TIMEZONE_MISMATCH`

The requested "timezone changed, check with network or wifi" needs a trusted reference.
The SDK has three, in order of trust:

1. **GNSS UTC (best, offline).** `Location.getTime()` from a GPS-provider fix is UTC from
   the satellite signal and is not settable from the Settings app. `FixMapper` already
   has the `Location` in hand (`data/location/FixMapper.kt:21`). Compute
   `skew = |location.time − System.currentTimeMillis()|` and feed the last value into the
   probe through a small internal holder. `skew > maxClockSkewMs` → `CLOCK_SKEWED`.
   Only trust fixes where `provider == "gps"` and `isMock == false`.
2. **Server `Date` header (needs network).** `fieldtrack-sync` reads the `Date` response
   header on every successful upload and reports the skew back to core through a new
   internal callback on `SyncTrigger`/`PendingUploadStore`'s neighbour port. Optional —
   core must stay functional with `fieldtrack-sync` absent.
3. **Settings flags (cheap, weak).** `Settings.Global.AUTO_TIME` and
   `Settings.Global.AUTO_TIME_ZONE` both `0` → `AUTO_TIME_DISABLED`, confidence 50. This
   is the direct answer to "timezone changed": manual timezone requires `AUTO_TIME_ZONE=0`.

Additionally `TIMEZONE_MISMATCH`: compare `TelephonyManager.getNetworkCountryIso()` (the
country of the serving network, not the SIM) against the countries `TimeZone.getDefault()`
belongs to. Mismatch → confidence 60. Skipped when there is no cellular service or the ISO
is blank — Wi-Fi-only tablets must not be flagged for this.

Policy note: default `WARN`, never `BLOCK` by default. A user flying across timezones with
a briefly stale clock is not an attacker, and a blocked SDK there is a support ticket.

### 4.5 MockLocationProbe → `MOCK_LOCATION_APP_SELECTED`, `MOCK_LOCATION_FIX`

Reality check on Android 11+: without `QUERY_ALL_PACKAGES` the SDK cannot enumerate every
installed app to test each for the mock-location app-op. So the probe layers what *is*
available:

1. **Per-fix truth (already shipped).** `Location.isMock` / `isFromMockProvider` is read
   in `FixMapper.kt:32-38`, and `MockPolicy.REJECT` already drops those fixes
   (`fieldtrack-geo/.../Validation.kt:38`). The probe surfaces "a mock fix was seen in
   this session" as `MOCK_LOCATION_FIX` by reading a counter the ingestor maintains. This
   is the strongest signal and needs no package visibility at all.
2. **Selected mock app, best effort.** For each package returned by
   `PackageManager.getInstalledPackages()` (already filtered by the platform to the visible
   set), call `AppOpsManager.unsafeCheckOpNoThrow(OPSTR_MOCK_LOCATION, uid, pkg)` and flag
   `MODE_ALLOWED`. On devices/host manifests where visibility is broad this catches the
   selected fake-GPS app before the first fix arrives; where it is narrow it returns
   nothing and costs one loop.
3. **Documented opt-in.** `INTEGRATION-GUIDE` gains a snippet a host may add to its own
   manifest to widen visibility for known fake-GPS packages via `<queries><package>`
   entries. Shipping that list inside the SDK manifest is rejected: it would be stale
   within a month and merges into every host's manifest.

`ALLOW_MOCK_LOCATION` in `Settings.Secure` is API < 23 only and is not used.

---

## 5. Storage — Room v7

Current schema version is 6 (`data/db/TrackerDatabase.kt:21`, `fieldtrack-core/schemas/`).

- Add `integrityFlags INTEGER NOT NULL DEFAULT 0` to `TrackPointEntity` and
  `RawFixEntity` (`data/db/Entities.kt:50`, `:220` neighbourhood).
- Bump `@Database(version = 7)`, add an explicit `Migration(6, 7)` — the file already
  states destructive migration is never used (`TrackerDatabase.kt:22-25`), so this is
  mandatory, not optional.
- Export `7.json` into `fieldtrack-core/schemas/com.devstree.traker.data.db.TrackerDatabase/`
  and commit it.
- Map through `Mappers.kt` and expose on the public `TrackPoint` (`fieldtrack-geo`
  `model/TrackPoint.kt`) as `val integrityFlags: Int = 0` — defaulted, so it is a
  source-compatible addition.

Bitmask assignment is fixed forever once shipped (backends will persist it):

```
1 << 0  ACCESSIBILITY_SERVICE_ACTIVE     1 << 5  AUTO_TIME_DISABLED
1 << 1  DEVELOPER_MODE_ENABLED           1 << 6  TIMEZONE_MISMATCH
1 << 2  ADB_ENABLED                      1 << 7  MOCK_LOCATION_APP_SELECTED
1 << 3  HOOKING_FRAMEWORK_DETECTED       1 << 8  MOCK_LOCATION_FIX
1 << 4  DEBUGGER_ATTACHED                1 << 9  CLOCK_SKEWED
```

---

## 6. Wire format — `fieldtrack-sync`

`SyncPoint` (`fieldtrack-sync/.../SyncTransport.kt:108`) gains two fields, both defaulted
so existing backends keep parsing:

```kotlin
val integrity_flags: Int = 0,
val integrity_signals: List<String> = emptyList(),   // enum names, human-readable
```

`is_mock` already exists and stays — `integrity_flags` bit 8 duplicates it deliberately so
a backend can evaluate one field.

Server-side guidance to add to `INTEGRATION-GUIDE` §14.5: treat these as advisory input to
a server-side rule, never as the only defence, and alert on the *absence* of the field
from a client version known to send it — that absence is itself a tamper signal.

---

## 7. Lint layer — `fieldtrack-lint`

New Gradle module producing a lint JAR shipped inside the AARs via `lintPublish`, so the
rules fire in the **host's** build, not only in this repo:

```kotlin
// fieldtrack-core/build.gradle.kts
dependencies { lintPublish(project(":fieldtrack-lint")) }
```

Module uses `com.android.tools.lint:lint-api` / `lint-checks` (add to
`gradle/libs.versions.toml`; pin to the version matching AGP 9.3.0).

### 7.1 Issues

| Issue id | Severity | Detects |
|---|---|---|
| `FieldTrackSecurityDisabled` | `FATAL` | `SecurityConfig(enabled = false)`, or any `IntegrityPolicy.ALLOW` passed to `hooking`/`mockLocation`, in a non-debug source set |
| `FieldTrackMockLocationAllowed` | `FATAL` | `MockPolicy.ALLOW` in `mockLocationPolicy(...)` |
| `FieldTrackDebuggableRelease` | `FATAL` | `android:debuggable="true"` in the merged manifest — this is what buys the runtime waiver |
| `FieldTrackLicenseHardcoded` | `WARNING` | license token as a string literal in source rather than manifest meta-data |

`FATAL` matters: AGP runs `lintVital` on release assembly, so a fatal issue fails
`assembleRelease` in the host app. That is the mechanism that answers "check if the
version is released and they bypass the details with debug mode" — a host cannot ship a
release APK with the layer switched off without explicitly adding a `lintOptions` disable
or `@Suppress`, which is a visible, reviewable act.

Detector shape: `UastScanner` with `getApplicableMethodNames()` for the builder calls plus
`getApplicableUastTypes()` for enum references; `XmlScanner` on `manifest/application` for
the debuggable rule. Source-set awareness comes from `context.project.isAndroidProject` +
checking the file path against `src/debug/` — a rule that fires in `src/debug/` would make
the debug waiver unusable, which is the opposite of the requirement.

### 7.2 Gradle release verification (this repo)

Add `verifyReleaseIntegrity` to `buildSrc/src/main/kotlin/TrackerReleaseTasks.kt`, wired as
a dependency of the publish task:

- Every library module's release build type has `isMinifyEnabled = true`.
- No module ships `debuggable = true` in any manifest under `src/main/`.
- `SDK_LOGGING_ENABLED` is `false` for release (already set in
  `fieldtrack-core/build.gradle.kts:26`) — assert rather than assume.
- The default `SecurityConfig` in source has `enabled = true` and `hooking = BLOCK` — a
  guard against someone flipping the default while debugging and committing it.

---

## 8. Testing

JVM unit tests (Robolectric where a `Context` is needed — already in the test stack):

- `IntegrityEvaluatorTest` — policy matrix: each signal × `ALLOW/WARN/BLOCK` → expected
  `blocked` and expected flags. Fake probes, no Android.
- `IntegrityWaiverTest` — debuggable `ApplicationInfo` → probes never invoked (assert with
  a counting fake), report `waived = true`.
- `AccessibilityProbeTest` — Robolectric `ShadowAccessibilityManager`; system service and
  allowlisted package produce no finding.
- `DeveloperModeProbeTest` — `Settings.Global` values via Robolectric.
- `HookingProbeTest` — probe reads `/proc` through an injected reader port; feed captured
  fixture text (clean maps, frida-agent maps, `TracerPid: 0` vs `TracerPid: 2411`) and
  assert confidence arithmetic.
- `ClockIntegrityProbeTest` — GNSS-vs-system skew boundary at `maxClockSkewMs`, mock fix
  ignored as a reference, telephony ISO mismatch and the blank-ISO skip.
- `MockLocationProbeTest` — app-op `MODE_ALLOWED` on a visible package; empty visible list
  → no finding, no crash.
- `TrackerReadyIntegrityTest` — `ready()` returns `DEVICE_INTEGRITY_BLOCKED` and emits the
  matching `TrackerEvent.Error`; waived build returns `Ok`.
- `SyncPayloadWireTest` (existing file) — extend with the two new fields, asserting old
  payloads still deserialize.
- `MigrationTest` 6 → 7 once the schema is exported.

Lint detector tests use `com.android.tools.lint:lint-tests` with `TestFiles.kotlin(...)`
snippets — one expected-failure and one clean case per issue, plus a `src/debug/` case
asserting no warning.

---

## 9. Documentation

- `docs/INTEGRATION-GUIDE.md`: new §20 "Device integrity", TOC entry, and cross-links from
  §5 (config), §7 (events), §14.5 (wire format), §18 (ProGuard). Renumber §20 Troubleshooting
  → §21 and add rows for the new error code.
- `docs/USER-GUIDE.md`: short "what gets blocked and why" section aimed at integrators.
- `docs/EDGE-CASES.md`: new EC entries for accessibility-user lockout, timezone traveller,
  emulator false positives, host without package visibility.
- `docs/PROGUARD-SETUP.md` + `consumer-rules.pro`: keep the public `integrity` model types
  (they are `@Serializable` and cross the boundary); keep nothing else in that package.
- `docs/PERMISSIONS.md`: state explicitly that this layer adds **no** new permission, and
  that `QUERY_ALL_PACKAGES` is deliberately not requested.

---

## 10. Phases

| Phase | Deliverable | Depends on |
|---|---|---|
| P0 | `integrity` package: models, ports, evaluator, `SecurityConfig`, waiver, unit tests with fake probes. Nothing wired. | — |
| P1 | The five real probes + their Robolectric tests. Still not wired. | P0 |
| P2 | Wiring: `TrackerGraph`, `ready()`/`start()` gates, `HealthLoop` re-check, `TrackerEvent.IntegrityChange`, `ErrorCode.DEVICE_INTEGRITY_BLOCKED`, public `Tracker` API. | P1 |
| P3 | Persistence: Room v7 + migration + schema export + `TrackPoint.integrityFlags`. | P2 |
| P4 | `fieldtrack-sync` wire fields + `SyncPayloadWireTest`. | P3 |
| P5 | `fieldtrack-lint` module, four detectors, detector tests, `lintPublish` wiring, `verifyReleaseIntegrity` task. | P2 |
| P6 | Documentation sweep (§9). | P5 |

P5 is independent of P3/P4 and can run in parallel with them.

---

## 11. Decisions taken

1. **Default policy for `hooking` is `BLOCK`**, as proposed. The false-positive risk is
   handled inside the probe instead of by weakening the default: no single indicator can
   raise the finding, the threshold is two, and emulators skip the port scan. A host that
   wants the lower-risk posture sets `hookingPolicy(IntegrityPolicy.WARN)` and lets the
   server reject — the flags are uploaded either way.
2. **Signature-bound waiver: out of scope for this change.** It is a license-token format
   change affecting every issued token, and it belongs with the next token revision.
   `IntegrityProbe.kt` carries the note so the gap is documented where the waiver lives.
3. **Server `Date` header skew: not built.** GNSS UTC is a stronger reference, works
   offline, and needs no new core↔sync callback. Reconsider only if field data shows
   sessions that never see a satellite fix.
4. **Root / Magisk detection: not built.** Outside the six requested checks. `HookingProbe`
   is weighted, so adding it later is one indicator and one weight.

## 12. What was not built

- **Room migration test 6 → 7.** The repository cannot wire `MigrationTestHelper` yet —
  AGP 9 rejects the `sourceSets.assets` wiring the helper needs, which is a pre-existing
  limitation recorded in `USER-GUIDE.md`. The migration itself is additive, hand-written
  and exercised by Room's schema validation on every debug open.
- **An end-to-end `ready()` gate test.** `Tracker` construction pulls the whole graph and
  Robolectric's application is not debuggable, so the license gate answers first. The gate
  is covered at its two seams instead: `IntegrityEvaluatorTest` (does this report block?)
  and `IntegrityMonitorTest` (what is published when it does).
