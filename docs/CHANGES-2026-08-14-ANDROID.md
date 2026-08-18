# Tracker Android — change review, 14 Aug 2026

This is the Android-side follow-up to the iOS review in
[`CHANGES-2026-08-13-IOS.md`](CHANGES-2026-08-13-IOS.md).

Important context:

- Most of the motion, geofence, config, and capture items from the iOS review were already present in this Android repo before this pass.
- The Android-side changes applied in this workspace are the release-license gate, public-state sync, heartbeat emission, and the matching public docs.
- The production Ed25519 public keys are not present in this repo, so the verifier plumbing is in place but the key map still needs the real release keys.

---

## 1. Licensing gate

New target area: `fieldtrack-core/src/main/kotlin/com/devstree/traker/license/`.

### New types

| Symbol | File | Access |
|---|---|---|
| `object LicenseToken` | `license/LicenseToken.kt` | `internal` |
| `const val prefix = "TRACKIT-"` | " | " |
| `data class Parts` | " | " |
| `data class LicensePayload` (`primary`, `also`, `kid`, `v`, `licensee`, `issued`) | " | " |
| `sealed interface ParseFailure` — `.WrongPrefix`, `.WrongShape`, `.PayloadNotBase64`, `.SignatureNotBase64`, `.PayloadNotJSON`, `.UnsupportedVersion(Int)` | " | " |
| `sealed interface ParseResult` — `.Success(parts)`, `.Failure(reason)` | " | " |
| `fun parse(token: String): ParseResult` | " | " |
| `fun decodeBase64URL(string: String): ByteArray?` | " | " |
| `fun covers(bundleID: String): Boolean` | " | " |
| `class LicenseVerifier` | `license/LicenseVerifier.kt` | `internal` |
| `fun verify(token: String?, bundleID: String): LicenseVerdict` | " | " |
| `val productionKeys: Map<Int, ByteArray>` | " | " |
| `sealed interface LicenseVerdict` — `.Licensed`, `.Waived`, `.Missing`, `.Invalid(detail)`, `.BundleMismatch(licensed, actual)` | " | " |
| `class LicenseGate` | `license/LicenseGate.kt` | `internal` |
| `fun check(explicit: String?): LicenseVerdict` | " | " |
| `fun failure(forVerdict: LicenseVerdict): Failure?` | " | " |
| `const val infoPlistKey = "TrackItLicense"` | " | " |
| `object LicenseEnvironment` | " | " |
| `fun hasGetTaskAllow(context: Context): Boolean` | " | " |

### Wiring

- `Tracker.ready()` now runs a license check before config resolution or any state mutation.
- `TrackerConfig.license: String?` was added as a transient override.
- `TrackerConfig.Builder.license(_:)` was added for Java-friendly setup.
- New `ErrorCode` values were added:
  - `LICENSE_MISSING`
  - `LICENSE_INVALID`
  - `LICENSE_BUNDLE_MISMATCH`
- `Tracker.state.value.providerState` now tracks `ProviderChange` events.
- `Tracker.state.value.motionState` now tracks `MotionChange` events.
- `TrackerEvent.Heartbeat(atMs)` is now emitted by `HealthLoop` after each check when a session is open.

### Behaviour to check

- Token format: `TRAKER-<base64url payload>.<base64url signature>`.
- The signature is verified against the encoded payload bytes, not a re-serialised JSON string.
- Debuggable installs are waived.
- Release builds require a valid token or manifest metadata entry.

### Tests

- `fieldtrack-core/src/test/kotlin/com/devstree/traker/license/LicenseTokenTest.kt`
  - confirms `TrackerConfig.license` is not persisted
  - checks the token shape parser
  - checks version rejection

The broader motion, geofence, and capture behavior already matched the iOS review
before this pass; the remaining work here was to close the public state and heartbeat
gaps alongside the new license gate.

---

## 2. Existing Android parity that was already in place

These items were already implemented in this repo before the licensing pass:

- `stopOnStationary`
- `disableStopDetection`
- `persistHeartbeat`
- `useAccelerometerVeto`
- `useBarometer`
- `activityRecognitionIntervalMs`
- `stopTimeoutMin`
- `getCurrentLocation()`
- geofence CRUD and event history
- `TrackerEvent.Heartbeat`
- `TrackerState.providerState`
- `TrackerState.motionState`

So unlike the iOS change review, there was no broad motion/config port to make here.

---

## 3. Docs synced

- `docs/SDK-COMPARISON.md` now reflects the Android release-license requirement.
- `docs/DEVELOPER-GUIDE.md` now documents the `license` builder method and the manifest override path.

---

## 4. Verification

- `./gradlew :fieldtrack-core:testDebugUnitTest`

---

## 5. How to use the new Android changes

### Updated parameters

| Parameter | Where | What it does |
|---|---|---|
| `TrackerConfig.license` | `TrackerConfig` / `TrackerConfig.Builder.license(...)` | Supplies a release token at startup. Debuggable installs are waived. |
| `TrackerState.providerState` | `Tracker.state` | Mirrors the latest provider snapshot from `ProviderChange` events. |
| `TrackerState.motionState` | `Tracker.state` | Mirrors the latest motion state from `MotionChange` events. |
| `TrackerEvent.Heartbeat(atMs)` | `Tracker.events` | Emits a control-plane heartbeat after the watchdog check when a session is open. |
| `ErrorCode.LICENSE_MISSING` | `TrackerResult.Error` / `TrackerEvent.Error` | Returned when a release build starts without a token. |
| `ErrorCode.LICENSE_INVALID` | `TrackerResult.Error` / `TrackerEvent.Error` | Returned when the token format, payload, signature, or key id is wrong. |
| `ErrorCode.LICENSE_BUNDLE_MISMATCH` | `TrackerResult.Error` / `TrackerEvent.Error` | Returned when the token was issued for a different app id. |

### What it does

- Fails `ready()` before any config is persisted if the token is missing or invalid.
- Keeps `Tracker.state.value.providerState` and `Tracker.state.value.motionState` current for UI and diagnostics.
- Emits heartbeat events so the sample can show liveness, not just tracking state.
- Keeps the token out of persisted config so `reset = false` cannot resurrect a stale release token.

### How to use it

```kotlin
val config = TrackerConfig.builder()
    .license(BuildConfig.TRAKER_LICENSE.takeIf { it.isNotBlank() })
    .provider(LocationProviderType.FUSED)
    .accuracyProfile(AccuracyProfile.BALANCED)
    .build()

when (val result = trackIt.ready(config)) {
    is TrackerResult.Ok -> Unit
    is TrackerResult.Error -> Log.e("Tracker", "${result.code}: ${result.message}")
}

viewModelScope.launch {
    trackIt.state.collect { sdk ->
        println("provider=${sdk.providerState} motion=${sdk.motionState}")
    }
}

viewModelScope.launch {
    trackIt.events.collect { event ->
        when (event) {
            is TrackerEvent.ProviderChange -> println("provider changed")
            is TrackerEvent.MotionChange -> println("motion changed")
            is TrackerEvent.Heartbeat -> println("heartbeat at ${event.atMs}")
            else -> Unit
        }
    }
}
```

### What you can do now

- Show provider status, motion state, and heartbeat age in the sample UI.
- Distinguish SDK liveness from tracked-point arrival.
- Build a release flow that rejects missing or malformed license tokens before tracking starts.
- Keep debug and release behavior aligned while still allowing local development without a token.

---

## 6. Notes

- `LicenseVerifier.productionKeys` is intentionally empty in this repo until the real release keys are supplied.
- If you want a fully enforced release pipeline, the next step is to add the actual key material and the issuing script used by your iOS side.
