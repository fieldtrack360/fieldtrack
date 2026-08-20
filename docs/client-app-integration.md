# Integrating the Field Track 360 SDK

For developers adding the SDK to an application. Licensing is handled inside
the SDK — you supply a key and handle a few error codes. There is nothing to
verify, no endpoint to call, and no crypto to write.

Building the SDK itself? That is [`sdk-licensing-flow.md`](sdk-licensing-flow.md).
Want to understand *why* it works offline? That is
[`how-the-local-licence-works.md`](how-the-local-licence-works.md), in plain English.

---

## 1. What you need

| | |
|---|---|
| **An access key** | `TRACKIT-eyJ2IjoxLCJraWQiOjEs…` — emailed on purchase, and shown in your portal |
| **Your application id** | The key is bound to it. `com.acme.app` on Android, the bundle identifier on iOS |

The key covers **one application, on every platform** — Android, iOS, Flutter
and React Native from a single key. It is permanent: it does not expire and does
not need renewing.

> The application id is sealed into the key's signature when it is issued and
> **cannot be changed afterwards**. If you need a different id, request a
> re-issue from the portal.

Evaluating? `POST /api/v1/trials` gives a 30-day key instantly, and buying later
keeps **the same key working** with no code change.

---

## 2. Add the key

Keep it out of source control — a build config field or CI secret, as you would
any other credential. It is not a password, but it is worth what you paid.

**Android**

Keep the key in `local.properties` — gitignored — and inject it as a `BuildConfig` field:

```properties
# local.properties
FIELDTRACK_LICENSE=TRACKIT-eyJ2IjoxLCJraWQiOjEs…
```

```kotlin
// build.gradle.kts
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { load(it) }
}

android {
  defaultConfig {
    buildConfigField(
      "String", "FIELDTRACK_LICENSE",
      "\"${localProperties.getProperty("FIELDTRACK_LICENSE", "")}\"",
    )
  }
}
```

```kotlin
val tracker = Tracker.getInstance(context)
tracker.ready(
    TrackerConfig.builder()
        .license(BuildConfig.FIELDTRACK_LICENSE)
        .build()
)
```

Or, if you prefer the manifest:

```xml
<meta-data android:name="TrackItLicense" android:value="TRACKIT-…" />
```

> **`TrackItLicense`, exactly.** Not `TrackerLicense`, not `com.fieldtrack.ACCESS_KEY` —
> both names have appeared in earlier drafts of these pages and the SDK reads neither. A
> release build with the wrong name fails as `LICENSE_MISSING`, which looks like a missing
> key rather than a misspelled one.

CI has no `local.properties`. Pass `-PfieldtrackLicense=…` or read the key from an
environment variable there.

**iOS**

```swift
try TrackIt.start(config: TrackItConfig(accessKey: Secrets.trackItKey))
```

Or `TrackItLicense` in `Info.plist`.

**Flutter**

```dart
await TrackIt.start(const TrackItConfig(
  accessKey: String.fromEnvironment('TRACKIT_KEY'),
));
```

**React Native**

```ts
import TrackIt from '@fieldtrack/react-native';
await TrackIt.start({ accessKey: Config.TRACKIT_KEY });
```

> **Do not copy the key into your own settings store.** The SDK reads it from
> config every launch on purpose. A stale key resurrected from disk turns
> "I updated my licence" into a support ticket.

---

## 3. Handle the error codes

`start()` succeeds or reports a code. The SDK shows no UI of its own — you
decide what the user sees.

```kotlin
when (val result = tracker.ready(config)) {
    is TrackerResult.Ok -> Unit
    is TrackerResult.Error -> when (result.code) {
        ErrorCode.LICENSE_REVOKED, ErrorCode.LICENSE_EXPIRED -> showRenewPrompt()
        ErrorCode.LICENSE_MISSING, ErrorCode.LICENSE_INVALID,
        ErrorCode.LICENSE_BUNDLE_MISMATCH -> reportToCrashlytics(result.code)
        else -> Log.w("FieldTrack", "${result.code}: ${result.message}")
    }
}
```

Failures also arrive on the event flow, which is where a mid-session revocation shows up —
`ready()` has long since returned by then:

```kotlin
tracker.events
    .filterIsInstance<TrackerEvent.Error>()
    .onEach { handleLicence(it.code, it.message) }
    .launchIn(scope)
```

| Code | Meaning | What to do |
|---|---|---|
| `LICENSE_MISSING` | No key supplied | Build/config problem — fix the wiring |
| `LICENSE_INVALID` | The key failed verification | Truncated paste, or an SDK too old for the key |
| `LICENSE_BUNDLE_MISMATCH` | The key is for a different application id — **found offline** | Check the id, or request a re-issue |
| `LICENSE_REVOKED` | Withdrawn by us | **Tracking stops.** Contact support |
| `LICENSE_EXPIRED` | A trial ended | **Tracking stops.** Buy a plan — the same key resumes |
| `LICENSE_UNKNOWN` | The key verified offline, but our backend has no record | Diagnostic. Tracking continues. Tell support |
| `LICENSE_PACKAGE_MISMATCH` | The **server** disagrees about the application id | Diagnostic. The offline check should have caught it first |
| `LICENSE_SDK_MISMATCH` | The server does not recognise this SDK type | Diagnostic. Tracking continues |

Only `LICENSE_REVOKED` and `LICENSE_EXPIRED` stop tracking. The rest are diagnostics.

> **Two mismatch codes, on purpose.** `LICENSE_BUNDLE_MISMATCH` comes from the offline
> gate — the token itself says a different application id. `LICENSE_PACKAGE_MISMATCH`
> comes from the server. The first is a wrong key; the second is our records disagreeing
> with a key that is otherwise fine, and only one of them is your problem to fix.

---

## 4. What happens behind the call

You do not implement any of this — it explains the behaviour you will observe.

```
  your app                      the SDK
     │                             │
     │ TrackIt.start()             │
     ├────────────────────────────▶│  verifies the key OFFLINE:
     │                             │  signature + application id.
     │                             │  No network. Works on a plane,
     │                             │  on first launch, forever.
     │◀────────────────────────────┤
     │  tracking runs              │
     │                             │
     │                             │  moments later, and every 12h
     │                             │  after, it asks our server
     │                             │  whether the licence was revoked.
     │                             │  Never on the path you awaited.
     │◀─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ┤  LicenseChecked
```

Three consequences worth knowing:

- **Startup never blocks on the network.** The gate that decides whether `ready()`
  succeeds reads a cached verdict and nothing else. A check *is* fired on every
  launch — unawaited, so a licence server that is down, slow or unreachable cannot
  delay a start or refuse one. Its answer arrives moments later on the event flow.
- **Offline devices keep working.** No connectivity, a timeout, a server
  outage — the SDK carries on. It is fail-open by design.
- **Revocation is not instant.** If we revoke a licence, it stops at the
  device's next check-in — the next launch, or the next 12-hour tick — not
  immediately. A permanently offline device keeps
  running. That is the accepted cost of never going dark on a blip.

---

## 5. If something is wrong

| Symptom | Cause |
|---|---|
| `LICENSE_INVALID` on a key that works elsewhere | Truncated paste. Copy the whole string including `TRACKIT-` |
| `LICENSE_PACKAGE_MISMATCH` | The build's application id differs from the one licensed — check flavours and `applicationIdSuffix` |
| Works in debug, fails in release | The key is missing from the release config, or minification stripped it |
| Tracking stopped days after a trial ended | Expected: expiry lands at the first check-in after the end date, not at midnight |
| Nothing happens at all | `start()` was never reached, or the permission ladder blocked it — that is not licensing |

Support needs three things: the **first 12 characters** of the key, your
**application id**, and the **SDK version**. Never send the whole key.

---

## 6. Before you ship

- [ ] Key supplied from `local.properties` or a CI secret, not hard-coded in a committed file
- [ ] Release build verified — not only debug
- [ ] Every application id you ship is covered (flavours, `.dev` suffixes)
- [ ] `LICENSE_REVOKED` and `LICENSE_EXPIRED` show the user something useful
- [ ] Airplane mode tested: the app still starts and tracks
- [ ] The key is not persisted anywhere by your own code
