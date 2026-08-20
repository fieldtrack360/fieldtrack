# Licensing flow — client app, SDK, backend

How a licence travels from purchase to a running app, what belongs on each side
of the boundary, and the code for Android, iOS, Flutter and React Native.

**For whoever builds the SDK.** Integrating the finished SDK into an app is a
different, much shorter job — that is
[`client-app-integration.md`](client-app-integration.md).

[`android-kotlin-integration.md`](android-kotlin-integration.md) is the deep
version for Android; [`api-integration.md`](api-integration.md) is the endpoint
reference.

---

## 1. Three parties, and who does what

The single most common mistake is putting licensing logic in the client app.
It belongs in the SDK — the client app should be unable to get it wrong.

| | Responsibility |
|---|---|
| **Client app** (your customer's app) | Supply the access key. Handle the error codes. **Nothing else.** |
| **Your SDK** | Verify the token offline. Call `/verify` opportunistically. Check the signature, the `key_id` and the nonce. Cache. Fail open. |
| **Licence backend** | Mint, revoke, expire. Answer `/verify` with a signed verdict. |

If an integrator can disable licensing by skipping a call, the design is wrong.
The SDK verifies inside `ready()`; there is no opt-out surface.

---

## 2. What the SDK ships with

Two **public** keys, compiled into the binary. Neither is a secret — they
verify signatures and cannot produce them.

| Key | Verifies | Note |
|---|---|---|
| **Licence signing key**, by `kid` | the token itself, offline | Hold a **map** `kid → key`, never a single value |
| **Response signing key** | the `/verify` reply | One value today |

Both are in **Admin → Settings → System configuration**, with copy buttons.

On Android they are **build inputs, not source**. `local.properties` is gitignored, and CI
passes the same values as Gradle properties or environment variables:

```properties
# local.properties
FIELDTRACK_LICENSE_KEYS=1:Base64OfThirtyTwoRawBytes=,2:AnotherKey=
FIELDTRACK_RESPONSE_KEY=Base64OfThirtyTwoRawBytes=
FIELDTRACK_LICENSE_URL=https://licence.example.com/api/v1
```

They reach the code as `BuildConfig` fields — `LicenseVerifier.productionKeys` parses the
`kid:key` pairs, `LicenseConfig.responsePublicKey()` decodes the other. That keeps them out
of the repository; it does not keep them out of the artifact, which is fine and in fact the
point. **A public key compiled in is exactly what makes it unsubstitutable.**

> **Blank is the shipped default, and it fails closed.** With no signing keys every
> non-debuggable build fails the offline gate with "Unknown license key id"; with no
> response key the online check makes no request at all. Neither shows up in development,
> where debuggable installs are waived, and neither fails the build. Verify on a release
> build before shipping one.

> **Never fetch either at runtime.** An app that downloads its own trust anchor
> can be handed a different one by anyone controlling the network — and then a
> forged `"active"` verifies perfectly. `GET /api/v1/public-key` exists for
> tooling and humans, not for the app.

The `kid → key` map matters even with one entry. Tokens carry the `kid` they
were signed with, so rotation adds a key rather than replacing one. Shipping a
single value now means a second SDK release later, at the worst moment.

---

## 3. The flow

### 3a. Integration — once, by your customer

```
  Customer                 Storefront / Portal              Backend
     │                            │                            │
     │  buy a plan                │                            │
     ├───────────────────────────▶│  POST /api/v1/orders       │
     │                            ├───────────────────────────▶│
     │                            │        Cashfree            │
     │                            │◀── webhook: paid ──────────┤
     │                            │                            │ mint / grant
     │  TRACKIT-eyJ2IjoxLCJraWQ… ◀┤  emailed + shown in portal │
     │                            │                            │
     │  pastes the key into their app config
     ▼
  TrackItConfig(accessKey = "TRACKIT-…")
```

The key is bound to **one application id**. It is sealed into the signature at
mint time and cannot be edited afterwards.

### 3b. Runtime — every launch

```
 Client app            Your SDK                              Backend
     │                    │                                     │
     │ TrackIt.start()    │                                     │
     ├───────────────────▶│                                     │
     │                    │                                     │
     │                    │ ┌─── OFFLINE GATE ──────────────┐   │
     │                    │ │ 1 parse TRACKIT-<payload>.<sig>│  │
     │                    │ │ 2 look up publicKeys[kid]      │  │
     │                    │ │ 3 Ed25519 verify the payload   │  │
     │                    │ │ 4 package id covered?          │  │
     │                    │ │   NO NETWORK. This licenses    │  │
     │                    │ │   the app.                     │  │
     │                    │ └────────────────────────────────┘  │
     │                    │                                     │
     │  ok / LICENSE_*    │                                     │
     │◀───────────────────┤                                     │
     │  tracking runs     │                                     │
     │                    │                                     │
     │                    │ ══ fired by ready() UNAWAITED, ══  │
     │                    │    and every 12h after. Never on   │
     │                    │    the path the host awaited.      │
     │                    │                                     │
     │                    │ cached verdict still inside TTL? ───┼── yes ─▶ done
     │                    │                                     │
     │                    │ POST /api/v1/verify                 │
     │                    │ {access_key, package_name,          │
     │                    │  sdk_type, sdk_version, nonce}      │
     │                    ├────────────────────────────────────▶│
     │                    │                                     │ revoked?
     │                    │                                     │ expired?
     │                    │                                     │ sign reply
     │                    │◀────────────────────────────────────┤
     │                    │ {status, valid, key_id, ttl_seconds,│
     │                    │  nonce, signature}                  │
     │                    │                                     │
     │                    │ ┌─── THREE CHECKS ───────────────┐  │
     │                    │ │ a signature ← RESPONSE key     │  │
     │                    │ │ b key_id == sha256(my token)   │  │
     │                    │ │ c nonce echoed unchanged       │  │
     │                    │ │ any fail → discard, as if the  │  │
     │                    │ │ call never happened            │  │
     │                    │ └────────────────────────────────┘  │
     │                    │ persist the WHOLE signed reply      │
     │                    │                                     │
     │ LICENSE_REVOKED    │ revoked/expired → stop              │
     │◀───────────────────┤ anything else   → carry on          │
```

**Step "offline gate" is what licenses the app.** The network call answers only
what that gate structurally cannot: *has this been revoked since it was issued?*

**The invariant is "never blocks startup", not "never at startup".** An earlier draft said
the latter, and it cost real coverage: the check ran only on a periodic worker whose first
tick lands up to 12 hours after install, so a fresh install could run most of a day against
a licence nobody had verified. `ready()` now fires a check itself and does not wait for it —
the gate still answers from cache, so a licence server that is down cannot delay a launch.
A verified answer lands moments later through `LicenseChecked`, the revocation latch, and a
stop if the verdict is `revoked` or `expired`.

---

## 4. Why `key_id` is not optional

The check people skip, and the one that matters most.

Without it, a signed `"active"` is a **bearer token for every licence**. Nothing
else in the payload says which licence the answer was about. So:

1. An attacker buys one legitimate licence.
2. They capture its `"active"` response once.
3. They replay that response whenever the SDK checks a **revoked** token.
4. The signature verifies perfectly, every time.

The adversary is the device owner, who controls its DNS, proxy and trust store.
Assume they replay anything they have seen. `key_id` is the SHA-256 of the token
presented — compare it to a hash of your own token, or the signature buys you
nothing.

---

## 5. The boundary with the client app

Everything above is yours. What the integrator writes is two lines — a key in,
error codes out:

```kotlin
tracker.ready(TrackerConfig.builder().license(BuildConfig.FIELDTRACK_LICENSE).build())
tracker.events.filterIsInstance<TrackerEvent.Error>()   // host decides the UI
```

Design it so that is the **entire** surface. If an integrator can skip a call
and disable licensing, the design is wrong: verification happens inside
`ready()`, with no opt-out and no flag. The equivalent for all four platforms,
plus the error-code contract, is in
[`client-app-integration.md`](client-app-integration.md) — that is the page to
hand a customer.

The SDK also shows **no UI of its own**. It returns a code; the host decides
what the user sees. That mirrors how the permission ladder already behaves.

---

## 6. SDK implementation

### 6a. Where the work goes on each platform

| Platform | Verification runs in | Reports `sdk_type` |
|---|---|---|
| Android | Kotlin/Java | `android` |
| iOS | Swift | `ios` |
| **Flutter** | **the native layer**, via the plugin | `flutter` |
| **React Native** | **the native layer**, via the bridge | `react-native` |

> **Flutter and React Native must not verify in Dart or JavaScript.** A JS bundle
> can be swapped at runtime and Dart can be patched; both put your trust anchor
> and your verdict logic somewhere the device owner can edit. The bridge passes
> the key down and surfaces error codes up — nothing more.

The bridges report **themselves**, not the native SDK underneath: a React Native
app on an Android device sends `react-native`. Licences cover every platform, so
this never changes a verdict — it tells support which integration is calling.

### 6b. Canonical JSON — where implementations diverge

The signature covers the reply **with `signature` removed and keys sorted**, as
UTF-8, with no whitespace. The signature itself is **unpadded base64url**.

```
{"checked_at":"2026-08-19T10:45:00.000Z","key_id":"18dd…","nonce":"9f3a1c7b","package_name":"com.acme.app","status":"active","ttl_seconds":86400,"valid":true}
```

> **`ttl_seconds` must render as `86400`, never `86400.0`.**
> JSON parsers that decode numbers into a floating-point type — Moshi, Gson and
> `JSONSerialization` into `Any` — re-serialise integers with a decimal point.
> The bytes then differ, every signature check fails, and because verification
> is fail-open the app keeps running and **nobody notices until a revoked
> licence never stops.**

> **Your HTTP client must hand back the response as bytes, not as an object.**
> The same trap, one layer up. Retrofit with a converter, Alamofire's
> `responseDecodable`, `dio` with a model — every one of them parses the body and
> discards the original text, which is the text the signature covers. Declare the
> raw type (`Response<ResponseBody>`, `Data`, `String`) and parse **after**
> verifying. On Android this is why `LicenseService.verify` returns
> `Response<ResponseBody>` and looks like a missed refactor; it is not one.

**Android** — Retrofit 3 over OkHttp 5 for the call, with the Gson converter on the
request side only. Verification uses Gson's `JsonParser`, which keeps every
number as a `LazilyParsedNumber` holding the original text, so `asString` gives back
`86400` exactly. `org.json.JSONObject` also preserves `Int` and was the earlier
recommendation here, but it is a **stub under plain JVM unit tests** — which is where every
negative test that matters runs, so the advice cost more than it bought. What must be
avoided either way is `fromJson(raw, Map::class.java)`: that is the route that turns
`86400` into `86400.0`.

Ed25519 needs Tink or BouncyCastle at `minSdk 26` — `java.security` has it only from API 33,
and calling `Signature.getInstance("Ed25519")` there means **every device on Android 8.0
through 12L fails the offline gate and refuses to start**. Full implementation in
[`android-kotlin-integration.md`](android-kotlin-integration.md).

**iOS** — CryptoKit has `Curve25519.Signing`:
```swift
guard let sig = response["signature"] as? String else { return nil }
var payload = response
payload.removeValue(forKey: "signature")

// .sortedKeys matches the server; .withoutEscapingSlashes avoids "\/"
guard let body = try? JSONSerialization.data(
        withJSONObject: payload,
        options: [.sortedKeys, .withoutEscapingSlashes]),
      let key = try? Curve25519.Signing.PublicKey(rawRepresentation: responseKeyBytes),
      key.isValidSignature(base64URLDecode(sig), for: body)
else { return nil }

guard response["key_id"] as? String == sha256Hex(myToken) else { return nil }
```
Assert in a test that the serialised body contains `"ttl_seconds":86400` —
`JSONSerialization` will happily emit `86400.0` if the value became a `Double`.

**Flutter** — the plugin calls into the native verifier:
```dart
// lib/src/licence_channel.dart — no crypto here, on purpose
class LicenceChannel {
  static const _ch = MethodChannel('trackit/licence');

  Future<String> verify(String accessKey) async =>
      await _ch.invokeMethod('verify', {'accessKey': accessKey}) as String;
}
```

**React Native** — same shape, native module:
```ts
// index.ts — the bridge surfaces verdicts; it never produces them
import { NativeModules, NativeEventEmitter } from 'react-native';
const { TrackItLicence } = NativeModules;

export const verify = (accessKey: string): Promise<string> =>
  TrackItLicence.verify(accessKey);

new NativeEventEmitter(TrackItLicence)
  .addListener('licenceStatus', (code: string) => handle(code));
```

### 6c. Caching

Persist the **whole signed reply**, and re-verify its signature when reading it
back. That makes the cache tamper-proof: editing `"revoked"` to `"active"` on
disk breaks the signature.

`ttl_seconds` is **how long you may keep trusting a good answer** — a cache
lifetime, not a heartbeat interval and not a deadline. `86400` for paid
licences, `21600` for trials.

| Platform | Store |
|---|---|
| Android | DataStore / EncryptedSharedPreferences |
| iOS | Keychain, or a file with protection class |
| Flutter / RN | whatever the **native** side already uses |

---

## 7. Verdicts, and what each one does

| `status` | `valid` | SDK action |
|---|---|---|
| `active` | `true` | Carry on. Refresh the cache |
| `revoked` | `false` | **Stop tracking.** `LICENSE_REVOKED` |
| `expired` | `false` | **Stop tracking.** `LICENSE_EXPIRED` — a trial ended |
| `unknown_key` | `false` | **Keep working**, emit a diagnostic — `LICENSE_UNKNOWN`. The token verified offline, so this is a backend ledger gap, not a bad customer |
| `invalid_key` | `false` | Same — keep working, `LICENSE_INVALID` |
| `package_mismatch` / `sdk_mismatch` | `false` | Diagnostic — `LICENSE_PACKAGE_MISMATCH` / `LICENSE_SDK_MISMATCH`. The offline gate reports its own mismatch as `LICENSE_BUNDLE_MISMATCH`, which is a different signal: a wrong token, rather than our records disagreeing with a good one |
| *(anything unrecognised)* | — | Carry on. A status a shipped SDK was never taught must not be able to stop it |
| *(failed any §4 check)* | — | Keep working, and treat as hostile |
| *(network error, timeout, 5xx)* | — | Keep working on the cached verdict |

**Only `revoked` and `expired` stop anything.** A tracking SDK going dark
mid-shift because a server blipped is a far worse outcome than a revoked licence
surviving another few hours. Never punish a paying customer for our data problem.

---

## 8. Before shipping

- [ ] Both public keys **compiled in**, licence key held as a `kid → key` map
- [ ] Neither key fetched at runtime
- [ ] Signature verified, and tested against a **deliberately wrong signature**
- [ ] `key_id` compared to SHA-256 of the SDK's own token, and tested by
      **replaying a genuine response from a different licence**
- [ ] A `nonce` is sent and its echo checked
- [ ] Canonical form renders `ttl_seconds` as `86400`, not `86400.0`
- [ ] The HTTP layer returns the response **body as bytes**, and a test asserts an
      oddly-spaced body survives the round trip byte-identical
- [ ] Airplane mode: the app starts and tracks
- [ ] Server 500: the app starts and tracks
- [ ] A revoked licence stops within one TTL + heartbeat cycle
- [ ] An expired trial reports `LICENSE_EXPIRED`, not `LICENSE_INVALID`
- [ ] Cached verdict survives reboot; a hand-edited cache is rejected
- [ ] Verified on a **minified/release** build, not only debug
- [ ] Flutter and RN verify natively, not in Dart or JS
- [ ] Nothing licence-related **blocks** the startup path — a check may fire there, but
      nothing awaits it
- [ ] Keys supplied from `local.properties` / CI, and a release build verified with them
      actually present
