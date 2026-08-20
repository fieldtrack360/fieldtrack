# Android integration — Kotlin + Retrofit

A complete, copy-paste integration of the Field Track 360 licence API for
Android, using Retrofit over OkHttp, with Gson.

**This is now shipped code, not only a guide.** `fieldtrack-core` implements all
of it, wired by hand in `di/TrackerGraph.kt` and driven by `work/Workers.kt`.
Each section below names the file that holds the real thing. Read this to
understand *why* the code is shaped the way it is, or to port it to a bridge —
React Native and Flutter need their own.

Two values are still empty and must be filled before any of it enforces
anything: `LicenseConfig.RESPONSE_PUBLIC_KEY_BASE64` and
`LicenseVerifier.productionKeys`. See §13.

What a host actually calls is four things — `Tracker.licenseInfo()`,
`Tracker.checkLicense()`, `TrackerEvent.LicenseChecked` and `LicenseStatus`. See
§8.5 if that is all you need.

**Minimum:** `minSdk 26`, Kotlin 1.9+, coroutines.

---

## 1. What you are building

Two layers, and they are not the same thing.

| | |
|---|---|
| **The offline gate** | Parse the token, verify its Ed25519 signature against a key compiled into the app, confirm it covers your package. **No network.** This is what licenses the app |
| **The online check** | One opportunistic call that answers what the offline gate structurally cannot: *has this licence been revoked or expired since it was issued?* |

This document covers the second. Three rules govern it:

1. **Never on the startup path.** Not in `ready()`, not blocking `start()`.
2. **Fail open.** Network error, timeout, 5xx, garbage — carry on.
3. **Verify what comes back, twice** — the signature, *and* what it is a
   signature of. §5 explains why one without the other is worthless.

### Where it lives

The online check follows the same layering as the rest of `fieldtrack-core`:
the domain declares what it needs, `data/` supplies it, and the use case in the
middle has never heard of Retrofit, Gson or `Context`.

| Layer | File | Holds |
|---|---|---|
| **domain/model** | `domain/model/License.kt` | `LicenseCheckRequest`, `SignedVerdict`, `LicenseAction`, `LicenseCheckResult`, `CachedVerdict`, and the two public types — `LicenseStatus` and `LicenseInfo` |
| **domain/repository** | `domain/repository/LicenseRepositories.kt` | `LicenseApi`, `VerdictAuthenticator`, `LicenseVerdictStore` — three interfaces, no implementations |
| **domain/usecase** | `domain/usecase/LicenseUseCases.kt` | `CheckLicenseRevocationUseCase`, `GetCachedLicenseActionUseCase`, `GetLicenseInfoUseCase` |
| **data/remote** | `LicenseService.kt`, `RetrofitLicenseApi.kt`, `ApiCall.kt`, `GsonVerdictAuthenticator.kt`, `LicenseResponseParser.kt`, `CanonicalJson.kt`, `LicenseApiDto.kt`, `RedactingLogInterceptor.kt` | the Retrofit service, the call, the generic result wrapper, verification, parsing, canonicalisation, the `@SerializedName` DTO, wire logging |
| **data/repository** | `data/repository/LicenseVerdictStoreImpl.kt` | the DataStore-backed cache |
| **license/** | `Ed25519.kt`, `LicenseVerifier.kt`, `LicenseToken.kt`, `LicenseGate.kt`, `LicenseState.kt`, `LicenseConfig.kt` | the offline gate, the shared crypto, the in-process latch, configuration |

Two things fall out of that split, and both are the reason for it:

- **The wire spelling and the domain shape are different types.** `VerifyRequestDto`
  carries `@SerializedName("access_key")`; `LicenseCheckRequest` carries
  `accessKey` and no annotations. Renaming a wire field is then one file and a
  mapper the compiler checks, rather than an edit reaching into a use case that
  should never have known the spelling.
- **The use case runs with no Android at all.** Every dependency is an interface
  arriving through the constructor, so all thirteen of its tests are plain JVM
  tests — no Robolectric, no MockWebServer, no DataStore file. That is what makes
  the fail-open paths cheap enough to actually test.

**No DI framework.** `di/TrackerGraph.kt` wires it by hand with `by lazy`, the
same as every other feature here — a host needs no Gradle plugin, no
`@HiltAndroidApp`, and no annotation processor of its own. The graph is also the
only file that knows Retrofit and Gson are the answer: every property is declared
as its interface type, so swapping a transport is one line.

---

## 2. Dependencies

```kotlin
// fieldtrack-core/build.gradle.kts
dependencies {
    implementation(libs.retrofit)                  // the call
    implementation(libs.retrofit.converter.gson)   // the request body only
    implementation(libs.gson)                      // the wire, and the canonical form
    implementation(libs.tink.android)              // Ed25519
    implementation(libs.okhttp)                    // what Retrofit runs on

    implementation(libs.androidx.work.runtime.ktx)      // scheduling
    implementation(libs.androidx.datastore.preferences) // cache

    testImplementation(libs.okhttp.mockwebserver)
}
```

> **Retrofit 3 requires OkHttp 5**, which is what the catalog already pins.
> Retrofit does not replace the OkHttp client — it runs on the one handed to it,
> so timeouts, interceptors and any host configuration still live there.

> **The Gson converter is used in one direction only.** It serialises the
> request. It must never deserialise the response — §3.1 is why.

> **On Ed25519:** `java.security` only gained it at API 33, and `minSdk` is 26.
> Before Tink arrived, `LicenseVerifier` called `Signature.getInstance("Ed25519")`
> directly and **every device on Android 8.0 through 12L failed the offline gate**
> and refused to start. One dependency now covers both the licence token and the
> `/verify` response — see `license/Ed25519.kt`. If you already ship
> BouncyCastle use that instead; do not ship both.

---

## 3. The transport — `domain/repository` + `data/remote`

The domain declares what it needs and never learns what answers it:

```kotlin
internal fun interface LicenseApi {
    /** The response body verbatim, wrapped. Never a parsed type — see §3.1. */
    suspend fun verify(request: LicenseCheckRequest): ApiResult<String>
}
```

The Retrofit service is the whole HTTP declaration:

```kotlin
internal interface LicenseService {
    @POST("verify")
    suspend fun verify(@Body request: VerifyRequestDto): Response<ResponseBody>
}
```

```kotlin
internal class RetrofitLicenseApi(
    private val baseUrl: String,
    private val logger: TrackLogger = TrackLogger.NoOp,
    private val client: OkHttpClient = defaultClient(logger),
) : LicenseApi {

    private val call = ApiCall(logger)
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /** Null when `baseUrl` is unusable — a typo must not crash the graph at launch. */
    private val service: LicenseService? by lazy {
        runCatching {
            Retrofit.Builder()
                .baseUrl(baseUrl.trimEnd('/') + "/")   // the trailing slash is load-bearing
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(LicenseService::class.java)
        }.getOrNull()
    }

    override suspend fun verify(request: LicenseCheckRequest): ApiResult<String> {
        if (baseUrl.isBlank()) return ApiResult.Failure(ApiError(ApiErrorCode.NOT_CONFIGURED))
        val api = service ?: return ApiResult.Failure(ApiError(ApiErrorCode.NOT_CONFIGURED))

        return call.execute(label = "POST $VERIFY_PATH") { api.verify(request.toDto()) }
    }
}
```

### 3.1 `Response<ResponseBody>`, never a typed DTO

This is the most important line in the licence layer, and it reads like a missed
refactor. The obvious Retrofit signature is:

```kotlin
// WRONG — this silently disables licence enforcement
@POST("verify")
suspend fun verify(@Body request: VerifyRequestDto): VerifyResponseDto
```

The server signs the response, and **the signature covers the exact JSON text
that arrived**: every key in its original order, every number in its original
spelling, no whitespace added or removed. A converter parses those bytes into an
object and throws the original away. Reconstructing them is where two
implementations stop agreeing — re-serialising `86400` as `86400.0` is enough,
and so is a differently-escaped `<`.

When that happens the signature check fails. **The check fails open**, by design,
so nothing breaks visibly: the app keeps running, no error is reported, and the
only symptom is that a revoked licence never stops. Nobody finds that by testing
the happy path.

So the body reaches the caller as bytes, and `LicenseResponseParser` reads them
*after* `GsonVerdictAuthenticator` has verified them.
`retrofitPreservesTheExactBytes` sends a body with deliberately odd spacing and
key order and asserts it comes back byte-identical — that test is what stops this
being tidied up later.

The request direction has no such constraint. Nothing signs what we send, so
`@Body VerifyRequestDto` goes through the Gson converter normally.

`Response<...>` rather than a bare body for a second reason: a non-2xx then
arrives as a value with its status intact. Retrofit would otherwise throw
`HttpException`, and this call sits behind a contract that never throws.

### 3.2 The trailing slash

```kotlin
.baseUrl(baseUrl.trimEnd('/') + "/")
```

Retrofit **drops the last path segment of a base URL that does not end in `/`**.
Without it, `https://licence.example.com/api/v1` + `verify` resolves to
`.../api/verify` — a 404, which fails open and looks exactly like a healthy
licence. `theVersionSegmentSurvivesRetrofitsBaseUrlHandling` pins it.

### 3.3 `ApiCall` — one place that decides what a failure was

Every call goes through a shared executor, so the mapping from "what went wrong"
to a reported code exists **once**. A second endpoint added later inherits the
same taxonomy, the same redaction rules and the same never-throws guarantee,
rather than growing its own opinion about what a 429 means.

```kotlin
internal sealed interface ApiResult<out T> {
    data class Success<T>(val value: T, val httpStatus: Int) : ApiResult<T>
    data class Failure(val error: ApiError) : ApiResult<Nothing>
}

internal enum class ApiErrorCode {
    NOT_CONFIGURED, NO_NETWORK, TIMEOUT, UNAUTHORIZED, NOT_FOUND,
    RATE_LIMITED, CLIENT_ERROR, SERVER_ERROR, EMPTY_BODY, MALFORMED_BODY, UNKNOWN,
}
```

> **Nothing branches on `ApiErrorCode` to decide a licence.** The adversary is the
> device owner, who controls DNS, the proxy and the trust store, so every value
> here is something they can produce on demand — a 401 by pointing the app at a
> server that refuses, a timeout by dropping packets. If any of them changed the
> verdict, that would be a way to disable the SDK rather than a way to enforce a
> licence. The use case maps the whole type to one outcome: carry on, and
> `everyApiErrorProducesTheSameOutcome` iterates all eleven to prove it.
>
> What the codes *are* for: a log line that says which of a dozen
> indistinguishable failures actually happened.

### Where `baseUrl` comes from

Not a hardcoded constant. `LicenseConfig.defaultBaseUrl` returns
`BuildConfig.LICENSE_BASE_URL`, which `fieldtrack-core/build.gradle.kts` resolves
at configuration time from the first of these that is set:

| Source | For |
|---|---|
| `-PfieldtrackLicenseUrl=...` | CI, JitPack |
| `FIELDTRACK_LICENSE_URL` environment variable | CI secrets |
| `FIELDTRACK_LICENSE_URL` in `local.properties` | local development, gitignored |

with the `FieldTrackLicenseUrl` manifest meta-data entry overriding all three at
runtime, per install.

```properties
# local.properties
FIELDTRACK_LICENSE_URL=https://licence.example.com/api/v1
```

**Include the version segment.** The transport appends `/verify` and nothing
else, so `/api/v2` later is a configuration change rather than an SDK release.

Two things this does not do. It does not hide the URL — the value is compiled
into `BuildConfig` and is readable in any published AAR or installed APK, which
is the right trade for an endpoint the device has to reach, and the reason no
credential should ever travel this way. And it does not fail a build that forgot
it: **CI has no `local.properties`, so a release built without the Gradle
property or the environment variable ships with the check inert and looks
entirely successful.**

> **The body comes back as a `String`, deliberately.** The signature covers the
> exact JSON text, so it has to reach the verifier as the bytes that arrived.
> Letting Gson hand back a data class throws the original away, and
> reconstructing it is where these implementations quietly break — see §5.2.
> Gson is used for the **request** and nothing else.

> **Null means "it went wrong", without saying how.** No network, DNS failure,
> timeout, 5xx, 4xx, empty body — the caller treats them identically, because
> none of them is evidence about a licence.

### The wire types — `data/remote/LicenseApiDto.kt` + `domain/model/License.kt`

```kotlin
internal data class VerifyRequestDto(
    @SerializedName("access_key") val accessKey: String,
    @SerializedName("package_name") val packageName: String,
    /** `"android"` from this SDK. A bridge reports itself: react-native, flutter. */
    @SerializedName("sdk_type") val sdkType: String,
    @SerializedName("sdk_version") val sdkVersion: String? = null,
    @SerializedName("nonce") val nonce: String? = null,
)

/** Only ever produced after all three checks pass. */
internal data class SignedVerdict(
    val status: String,        // active | revoked | expired | unknown_key | …
    val valid: Boolean,
    val keyId: String,
    val packageName: String,
    val checkedAt: String,
    val ttlSeconds: Long,
    val nonce: String?,
    val reason: String?,
    val raw: String,           // the full signed JSON, for the cache
)
```

> **`@SerializedName` on every wire field is not optional.** Gson maps by
> reflected field name, and the release AAR is R8-minified. Without it the
> request goes out as `{"a":…,"b":…}`, the server answers `400`, and the check
> fails open — which looks exactly like nothing being wrong. Both
> `consumer-rules.pro` and `proguard-rules.pro` carry the matching `-keep`; §9.

### Where the base URL and keys come from — `license/LicenseConfig.kt`

`RESPONSE_PUBLIC_KEY_BASE64` and `DEFAULT_BASE_URL` are compiled in. Empty means
the layer is inert: `VerdictAuthenticator.isConfigured` is false, the use case returns
`CarryOn` without making a request, and nothing is ever trusted. A licence layer
that fell back to believing whatever answered would be worse than none.

The base URL takes a manifest override for local testing:

```xml
<meta-data android:name="FieldTrackLicenseUrl" android:value="http://10.0.2.2:5858" />
```

`10.0.2.2` is the emulator's route to the host machine, and cleartext to it needs
a **debug-only** network-security config.

---

## 4. What the server sends

Always `200`, always this shape — the only exception is a malformed body,
which is `400` and a programming error:

```json
{"checked_at":"2026-08-19T10:45:00.000Z","key_id":"18dd7a94…","nonce":"9f3a1c7b","package_name":"com.acme.app","status":"active","ttl_seconds":86400,"valid":true,"signature":"Z1dkvYosLit_myvNkfK7lW901GdZozryohaASBoT6Km…"}
```

The **signature covers the same JSON with `signature` removed and keys sorted**:

```
{"checked_at":"2026-08-19T10:45:00.000Z","key_id":"18dd7a94…","nonce":"9f3a1c7b","package_name":"com.acme.app","status":"active","ttl_seconds":86400,"valid":true}
```

Note `86400` — an **integer**, with no decimal point. Hold that thought.

---

## 5. Verification

### 5.1 The three checks

| # | Check | If it fails |
|---|---|---|
| 1 | Ed25519 signature over the canonical JSON | Discard the response entirely |
| 2 | `key_id` equals SHA-256 of **your** token | Discard, and treat as hostile |
| 3 | `nonce` echoes what you sent | Discard |

**Check 2 is the one people skip, and skipping it reopens the exact hole the
signature was meant to close.** Nothing else in the payload says *which* licence
the answer is about, so a signed `"active"` becomes a bearer token for every
licence. An attacker holds one legitimate licence, captures its `active`
response once, and replays it whenever your app checks a **revoked** token — the
signature verifies perfectly every time. The adversary is the device owner, who
controls its DNS, its proxy and its trust store.

### 5.2 Canonical JSON — `data/remote/CanonicalJson.kt`

The signature is over exact bytes, so you must reproduce them exactly.

> ### The `86400.0` bug
>
> `gson.fromJson(raw, Map::class.java)` parses **every** JSON number into a
> `Double`. Re-serialize and `86400` becomes `86400.0`, the bytes no longer
> match, and **every signature check fails**. Because verification fails open,
> the app keeps working and nobody notices — until a revoked licence never
> stops. Moshi does the same thing; this is not a reason to switch libraries.
>
> The fix is to not go through a `Map` at all. `JsonParser` keeps each number
> as a `LazilyParsedNumber` that still holds the **original text**, so
> `asString` on it gives you back `86400`, exactly as it arrived.

```kotlin
/** The same non-HTML-escaping instance as §3. */
private val canonicalGson = GsonBuilder().disableHtmlEscaping().create()

/**
 * Rebuilds the exact bytes the server signed: every key except `signature`,
 * sorted, no whitespace, numbers rendered as they arrived.
 *
 * Null rather than an exception for anything unexpected — see below.
 */
internal fun canonicalize(raw: String): String? = runCatching {
    val json = JsonParser.parseString(raw).asJsonObject
    val keys = json.keySet()
        .filter { it != SIGNATURE_FIELD }
        .sorted()                      // lexicographic, matching the server

    buildString {
        append('{')
        keys.forEachIndexed { index, key ->
            if (index > 0) append(',')
            append(canonicalGson.toJson(JsonPrimitive(key)))
            append(':')
            append(encodeCanonical(json.get(key)) ?: return@runCatching null)
        }
        append('}')
    }
}.getOrNull()

private fun encodeCanonical(value: JsonElement?): String? = when {
    value == null -> null
    value.isJsonNull -> "null"
    value !is JsonPrimitive -> null                   // nested object or array
    value.isString -> canonicalGson.toJson(value)     // quoted and escaped
    value.isBoolean -> value.asString                 // true / false
    value.isNumber -> value.asString                  // 86400, never 86400.0
    else -> null
}
```

**Null, not `error(...)`.** Every value the server signs today is a string, a
boolean or an integer — never a nested object or array — so the temptation is to
throw loudly on anything else and catch it in tests. But this runs on a
WorkManager thread behind a check whose entire contract is to fail open, and the
caller catches nothing: throwing there would turn a backend deploying one new
field into a crash inside every host app. Null flows into the same "failed
check, carry on" path as a bad signature, and
`unexpectedNestingReturnsNullRatherThanThrowing` pins it.

> **Non-ASCII, if the signed set ever grows one.** Gson writes UTF-8
> straight through; a server using Python's `json.dumps` default escapes it to
> `\uXXXX`. Every field signed today is ASCII — an ISO timestamp, a hex
> `key_id`, a package name, a status word — so the two agree. Add a field that
> can carry free text and confirm which side escapes before you ship it.

### 5.3 The authenticator — `data/remote/GsonVerdictAuthenticator.kt`

```kotlin
internal class GsonVerdictAuthenticator(
    private val responsePublicKey: ByteArray,
) : VerdictAuthenticator {

    /** False when no key was compiled in, in which case no response can be trusted. */
    val isConfigured: Boolean get() = responsePublicKey.size == Ed25519.PUBLIC_KEY_BYTES

    /**
     * A Verdict only if all three checks pass, otherwise null. Null means "treat this
     * as if the call never happened" — never "the licence is invalid".
     */
    fun verify(raw: String, token: String, sentNonce: String?): Verdict? {
        if (!isConfigured) return null

        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return null

        // The server signs in production. An unsigned response is an untrusted one.
        val signature = json.string("signature") ?: return null
        val signatureBytes = decodeBase64Url(signature) ?: return null
        val canonical = canonicalize(raw) ?: return null

        // 1 — is it genuine?
        if (!Ed25519.verify(responsePublicKey, signatureBytes, canonical.toByteArray(UTF_8))) {
            return null
        }

        // 2 — is it about OUR licence?
        if (json.string("key_id") != sha256Hex(token.trim())) return null

        // 3 — is it fresh?
        if (sentNonce != null && json.string("nonce") != sentNonce) return null

        return runCatching { SignedVerdict(/* … */) }.getOrNull()
    }
}
```

Three details that are load-bearing and easy to lose:

- **`java.util.Base64`, not `android.util.Base64`.** The latter is a stub under
  plain JVM unit tests, which is where every negative test in §10 runs. Using it
  makes the whole suite pass for the wrong reason.
- **`json.string(key)` rather than Gson's `asString`,** which coerces a number
  into a string and throws on a missing key. A `key_id` that arrived as a number
  is not a `key_id` worth comparing, and a missing field is a failed check rather
  than an exception on a background thread.
- **The nonce check is skipped when the caller sent none.** That is only the
  cache-read path: a nonce proves freshness on arrival and can prove nothing
  about a value read back from disk.

## 6. Putting it together — `domain/usecase/LicenseUseCases.kt`

```kotlin
internal class CheckLicenseRevocationUseCase(
    private val api: LicenseApi,
    private val authenticator: VerdictAuthenticator,
    private val store: LicenseVerdictStore,
    private val packageName: String,
    private val sdkVersion: String,
    private val random: SecureRandom = SecureRandom(),
) {
    /** One opportunistic check. Never throws. */
    suspend fun check(token: String): LicenseAction {
        if (!verifier.isConfigured) return LicenseAction.CarryOn

        cache.read(token)?.let { cached ->
            if (!cached.isStale()) return cached.verdict.toAction()
        }

        val nonce = newNonce()
        val raw = transport.verify(
            LicenseCheckRequest(token, packageName, SDK_TYPE, sdkVersion, nonce),
        ) ?: return LicenseAction.CarryOn

        val verdict = verifier.verify(raw, token, nonce) ?: return LicenseAction.CarryOn

        cache.write(token, verdict)
        return verdict.toAction()
    }

    /** Cache only, no network, staleness ignored. What `ready()` consults. */
    suspend fun cachedAction(token: String): LicenseAction =
        cache.read(token)?.verdict?.toAction() ?: LicenseAction.CarryOn
}
```

**"Never throws" is a contract, not a hope.** An exception escaping onto a
WorkManager thread turns a licence server outage into a crash loop inside
somebody else's app, so every failure inside resolves to `CarryOn`: no
compiled-in key, no network, a body that will not parse, a signature that will
not verify.

A failed check is also not a verdict. It is treated as if the call never
happened rather than as evidence against the licence — a proxy the device owner
controls can produce a failed check on demand, and must not be able to produce a
stop.

`cachedAction` deliberately ignores the TTL. A stop that has aged past its
lifetime is still the last thing the server actually said, and letting it lapse
back into `CarryOn` would hand every revoked install the same trick: turn the
network off and wait a day.

### The actions

```kotlin
internal sealed interface LicenseAction {
    data object CarryOn : LicenseAction
    data class Stop(val code: ErrorCode, val reason: String?) : LicenseAction
    data class Diagnose(val code: ErrorCode, val reason: String?) : LicenseAction
}

internal fun Verdict.toAction(): LicenseAction = when (status) {
    "active"           -> LicenseAction.CarryOn
    "revoked"          -> LicenseAction.Stop(ErrorCode.LICENSE_REVOKED, reason)
    "expired"          -> LicenseAction.Stop(ErrorCode.LICENSE_EXPIRED, reason)
    "unknown_key"      -> LicenseAction.Diagnose(ErrorCode.LICENSE_UNKNOWN, reason)
    "invalid_key"      -> LicenseAction.Diagnose(ErrorCode.LICENSE_INVALID, reason)
    "package_mismatch" -> LicenseAction.Diagnose(ErrorCode.LICENSE_PACKAGE_MISMATCH, reason)
    "sdk_mismatch"     -> LicenseAction.Diagnose(ErrorCode.LICENSE_SDK_MISMATCH, reason)
    else               -> LicenseAction.CarryOn
}
```

**Only `revoked` and `expired` stop anything.** `unknown_key` and `invalid_key`
mean the token verified offline — against a key we compiled in ourselves — and
the backend then had no matching record. That combination is our ledger being
wrong, not a customer being wrong, and a paying user must not lose tracking over
it.

An unrecognised status carries on, deliberately: a server that starts sending a
status this SDK has never heard of must not be able to stop older installs by
accident.

The five new codes are on the public `ErrorCode` enum, so a host sees them
through the same channel as every other refusal.

---

## 7. Caching the signed response — `data/repository/LicenseVerdictStoreImpl.kt`

Persist the **whole response including its signature**, and re-verify that
signature when reading it back. That is what makes the cache tamper-proof
without encrypting anything: editing `"revoked"` to `"active"` on disk breaks the
signature, the read returns null, and the next check goes to the network. A
device owner with root is the expected adversary, so a cache that trusted its own
contents would be a way around the whole layer.

```kotlin
internal interface LicenseVerdictStore {
    suspend fun read(token: String): CachedVerdict?
    suspend fun write(token: String, verdict: Verdict)
}

internal class LicenseVerdictStoreImpl(
    private val context: Context,
    private val authenticator: VerdictAuthenticator,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LicenseVerdictStore {

    override suspend fun read(token: String): CachedVerdict? = runCatching {
        val prefs = context.licenseDataStore.data.first()
        val raw = prefs[rawKey(token)] ?: return null
        val storedAt = prefs[atKey(token)] ?: return null
        // nonce is not re-checked: it proved freshness on arrival and can prove
        // nothing about a value read back from disk.
        val verdict = verifier.verify(raw, token, sentNonce = null) ?: return null
        CachedVerdict(verdict, storedAt, nowMs)
    }.getOrNull()
}
```

Two things the obvious implementation gets wrong:

- **The whole read sits inside `runCatching`.** A corrupt DataStore file throws
  `IOException` on read, and this runs behind a check that is supposed to fail
  open. Leaving that call outside the guard turns a bad write during a power cut
  into a crash on a background thread.
- **Entries are keyed by a digest of the token, not by a fixed key.** A host that
  swaps its licence gets a cache miss instead of the previous licence's answer —
  the difference between "I updated my licence" working and it becoming a bug
  report a day later. The digest rather than the token itself, because preference
  keys land in a file on disk, and an access key sitting there in the clear is one
  `adb pull` from being someone else's access key.

`ttl_seconds` is **how long you may keep trusting this answer** — a cache
lifetime, not a heartbeat interval and not a deadline. Paid licences return 86400
(24h); trials return less.

---

## 8. Scheduling — `work/Workers.kt`, `Tracker.revocationGate()`

```kotlin
internal class LicenseCheckWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = applicationContext.trackItGraph()
        val token = deps.licenseGate.token(deps.configStore.cached?.license)
            ?: return Result.success()

        val action = deps.licenseChecker.check(token)
        LicenseState.apply(action)

        when (action) {
            is LicenseAction.Stop -> {
                deps.events.tryEmit(TrackerEvent.Error(action.code, action.reason.orEmpty()))
                deps.stopTracking()
            }
            is LicenseAction.Diagnose -> deps.events.tryEmit(TrackerEvent.Diagnostic("licence …"))
            LicenseAction.CarryOn -> Unit
        }

        // Always success: a failed check is not a failed job, and retrying it
        // aggressively is the opposite of what this should do.
        return Result.success()
    }
}
```

Enqueued from `Tracker.ready()` with `KEEP`, every 12 hours, requiring
`NetworkType.CONNECTED`.

`ready()` also fires one check itself, unawaited — see §8.5. The worker's first
tick can be up to 12 hours after install, which without that would leave a fresh
install unverified for most of a day.

- 12 hours is roughly the cache TTL a paid licence returns. The server allows
  **120 requests per minute per IP** — anywhere near that across a fleet of
  installs is a bug, not a configuration.
- `NetworkType.CONNECTED` means WorkManager simply does not run it offline, which
  is exactly right: no connectivity is not a failure state.
- `KEEP`, like the backstop worker: re-enqueuing on every `ready()` would reset
  the 12-hour clock, and a frequently-restarted app would never check at all.
- The token comes from `LicenseGate.token()`, not from the config store.
  `TrackerConfig.license` is dropped on serialisation on purpose, so a token
  resurrected from disk can never turn "I updated my licence" into a bug report.

### What `ready()` does

```kotlin
private suspend fun revocationGate(config: TrackerConfig): TrackerResult.Error? {
    val token = licenseGate.token(config.license) ?: return null

    val action = LicenseState.current ?: licenseChecker.cachedAction(token)
    LicenseCheckWorker.enqueue(context)

    if (action !is LicenseAction.Stop) return null

    val message = action.reason ?: "Tracker license is no longer valid"
    eventSink.tryEmit(TrackerEvent.Error(action.code, message))
    return TrackerResult.Error(action.code, message)
}
```

**No network happens here**, and that restriction is the whole design. A
revocation check on the startup path would mean a licence server outage delaying,
or refusing, every host's launch. The network call runs on the worker's tick and
leaves a signed answer behind; `ready()` reads that answer. A host that has never
been online since being revoked therefore keeps working — the correct trade, since
treating an unreachable server as evidence hands anyone with a firewall rule the
ability to stop a paying customer.

`LicenseState` (`license/LicenseState.kt`) is the in-process half: a check that
lands **mid-session**, after `ready()` has already let the host through, latches
there, so a `stop()` / `start()` cycle cannot resume on a revoked licence. Only
`Stop` latches, and only another fully verified verdict clears it — a licence
reinstated on the server has to be able to reinstate the app without a reinstall.

---

## 8.5 What the host gets — the public surface

Everything above is `internal`. This is the whole of what a third-party app sees,
and it is deliberately small.

```kotlin
public suspend fun Tracker.licenseInfo(): LicenseInfo?   // cache, never the network
public suspend fun Tracker.checkLicense(): LicenseInfo?  // check now
public data class TrackerEvent.LicenseChecked(val info: LicenseInfo)
public enum class LicenseStatus
public data class LicenseInfo                            // internal constructor
```

### When the call happens

| Trigger | Network? | Host sees |
|---|---|---|
| `ready()` — the **gate** | **No.** Cache and the in-process latch only | A `TrackerResult.Error` **only** if the last verdict was `REVOKED`/`EXPIRED` |
| `ready()` — the **check**, launched unawaited | Yes, unless the cached answer is inside its TTL | `LicenseChecked` shortly *after* `ready()` returns; plus `Error` and a stop on a revoked or expired verdict |
| `LicenseCheckWorker`, every 12h, requires connectivity | Same | Same |
| `Tracker.checkLicense()` | Same | The returned `LicenseInfo`, and the same events |
| `Tracker.licenseInfo()` | **No** | The returned `LicenseInfo`, no events |

### Why `ready()` both does and does not check

The two rows for `ready()` are the same method and deliberately different things.

**The gate decides from disk.** Whether the host may proceed is answered from a
verdict already cached, so a licence server that is down, slow, or unreachable
cannot delay a launch or refuse one. Nothing on the return path awaits a socket.

**The check goes out anyway**, launched on the SDK scope and never awaited. Its
answer arrives moments later through `LicenseChecked`, the revocation latch, and
— for `REVOKED` or `EXPIRED` — a stop.

That second row is what closes the first-run gap. `LicenseCheckWorker` is
periodic on a 12-hour interval with the default flex, so **its first tick can land
anywhere in the first 12 hours after install** — without the startup check a fresh
install could run most of a day against a licence nobody had verified. The worker
is still what keeps a long-running install honest; the startup check is what makes
the first one prompt.

Checking on every open is cheap because the cache short-circuits it: an app opened
forty times a day makes one request per TTL, not forty. A `Mutex` serialises the
calls, so two opens in quick succession — or a host calling `checkLicense()` at the
same moment — cannot both slip past a cold cache and go out twice.

The call is wrapped in `runCatching` because it runs unobserved: anything thrown on
the SDK scope has no caller to catch it and would surface as a crash inside the
host's app.

> **A successful `ready()` does not mean the licence was checked.** It means no
> *cached* verdict said to stop. Read `LicenseChecked` or `licenseInfo()` for the
> answer itself — and remember that no event at all means nothing was learned, not
> that everything is fine.

The worker is enqueued from `ready()` with `KEEP`, so it never resets its own
12-hour clock, and a build with no licence configured schedules nothing.

### What arrives on success

`status = ACTIVE` is the success case, and it is reported like any other verified
answer:

```kotlin
LicenseInfo(
    status      = LicenseStatus.ACTIVE,
    valid       = true,
    packageName = "com.acme.app",   // as the server has it
    checkedAt   = "2026-08-19T10:45:00.000Z",
    ttlSeconds  = 86400,
    reason      = null,
    fromCache   = false,
)
```

```kotlin
tracker.events
    .filterIsInstance<TrackerEvent.LicenseChecked>()
    .onEach { Log.i("licence", "${it.info.status} cached=${it.info.fromCache}") }
    .launchIn(scope)

// or, at any moment, with no network cost:
when (tracker.licenseInfo()?.status) {
    LicenseStatus.ACTIVE -> Unit
    null -> Unit                       // not checked yet — NOT a refusal
    else -> showLicenceBanner()
}
```

Four things this surface deliberately does **not** do, each for a reason:

- **It carries no identity.** No access key, no `key_id`, not the signed document.
  A host that could read those could replay them, and `LicenseInfo`'s constructor
  is `internal` (with `@ConsistentCopyVisibility`, so `copy()` cannot route around
  it) precisely so one can only come from something already verified.
- **Silence is not success.** No event is emitted when the network failed, the
  build has no licence configured, or a response failed verification. All three
  carry on tracking and all three report nothing, because a host that read silence
  as approval would be reading a server outage as a valid licence.
- **`null` from either method is not a refusal.** It means nothing is known yet.
  Nothing on this path stops tracking except a verified `REVOKED` or `EXPIRED`.
- **`checkLicense()` cannot be used to hammer the server.** A cached answer inside
  its TTL short-circuits the request, so calling it on every screen still produces
  at most one call per TTL against a 120-per-minute budget.

> **Adding `LicenseChecked` to `TrackerEvent` is a source-breaking change** for any
> host with an exhaustive `when` over the sealed interface. `sample-android` had
> two, and both failed to compile until they were given a branch — which is the
> mechanism working. Hosts on a released version will need the same one-line
> addition, or an `else`.

---

## 8.6 Logs — `Tracker/API_CALL`

Debug builds log the whole path under one tag, so a single filter shows the
request, what came back, and what was decided about it:

```
adb logcat -s Tracker/API_CALL
```

```
D/Tracker/API_CALL: POST https://licence.example.com/api/v1/verify pkg=com.acme.app sdk=android/0.1.1
D/Tracker/API_CALL: HTTP 200 in 412ms, 318 bytes
D/Tracker/API_CALL: verdict ACTIVE valid=true ttl=86400s -> carry on
```

The lines that explain an *absent* request matter as much as the ones around a
real one — without them a build that never calls home looks identical to one
whose call is hanging:

```
D/Tracker/API_CALL: skipped: no licence URL configured for this build
D/Tracker/API_CALL: skipped: no response public key compiled in
D/Tracker/API_CALL: cache fresh (ACTIVE) — no request
D/Tracker/API_CALL: cache stale (ACTIVE) — rechecking
```

Two things are warnings rather than debug lines, and the split is deliberate:

```
W/Tracker/API_CALL: HTTP 503 in 89ms
W/Tracker/API_CALL: failed after 10004ms: SocketTimeoutException: timeout
W/Tracker/API_CALL: response failed verification — ignored, carrying on
```

A 5xx or a timeout is a note about the server; the check fails open and the
licence is unaffected. **The third one is different.** A genuine server does not
send a response that fails verification, so it means a proxy is in the way or a
key rotation was never shipped — and either one silently disables enforcement
until somebody reads the log.

### What is deliberately not in there

> **No access key, no `key_id`, no signature, no response body.** Logcat is
> readable by `adb` on any developer machine and by anything holding `READ_LOGS`.
> The access key is a credential, and `key_id` is a SHA-256 of it — a stable
> fingerprint of which licence an install holds. `theAccessKeyIsNeverLogged`
> asserts this in both the transport and the use-case suites, because a redaction
> nothing checks is one refactor away from being undone in silence.

Failures log the exception type and message, not a stack trace: some frames carry
the request URL with its parameters, and a full trace is noise for something the
caller shrugs off anyway.

`API_TAG` lives in `SdkLogging.kt` and is shared by `data/remote` and
`domain/usecase` so one filter covers both. Every call site is wrapped in
`sdkLog { }`, which is `inline` on `BuildConfig.SDK_LOGGING_ENABLED` — **release
builds contain neither the strings nor the concatenation**, so the redaction above
is a design choice rather than the only thing standing between a credential and a
shipped log.

---

## 9. ProGuard / R8

Two reflective libraries, two rules. The release AAR is itself minified, so both
are needed in **both** files: `proguard-rules.pro` for our own build,
`consumer-rules.pro` for the host's.

**Gson** maps by reflected field name, so R8 cannot see the DTO fields are used:

```proguard
-keepclassmembers,allowobfuscation class com.field360.tracker.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
```

`@SerializedName` carries the wire names, so only the annotated fields need
keeping — the classes themselves are still renamed and repackaged. The rule
matches on the **annotation, not the package**, deliberately: an earlier
package-scoped version stopped matching the moment the DTO moved to
`data/remote`, and nothing said so.

**Retrofit** builds its calls by reading annotations off an interface's methods
at runtime. R8 keeps the interface — it is referenced — but strips annotations
from members it considers unused, and a service whose `@POST` is gone fails at
`create()` with *"Method must have a valid HTTP annotation"*:

```proguard
-keep,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
```

Retrofit, OkHttp and Tink all ship consumer rules covering their own classes.
The two above are for **ours** — our DTOs, our service interfaces — which no
library can know about.

Public API types need explicit keeps too. `LicenseInfo` and `LicenseStatus` were
added to the public surface and not to the keep list, and the symptom was
`sample-android:minifyReleaseWithR8` failing with `Missing class` — only once the
sample was linked against the *minified* AAR. Minifying `fieldtrack-core` alone
never catches that.

> Verify the whole path **on a minified release build**, not just debug. Three
> failure modes look identical from the outside: R8 renaming a DTO field surfaces
> as a `400` from the server, R8 stripping a Retrofit annotation surfaces as an
> exception at `create()`, R8 stripping Tink surfaces as a verification failure.
> All three fail open — indistinguishable from a licence that is fine.

---

## 10. Testing — `src/test/kotlin/.../{data/remote,domain/usecase}/`

The negative tests are the ones that matter. A verifier that accepts everything
passes the positive test too.

**Sign the fixtures; do not paste a captured one.** `testing/LicenseFixtures.kt`
generates a
real Ed25519 keypair per run and signs each response with it:

```kotlin
internal class LicenseFixtures {
    private val keyPair = Ed25519Sign.KeyPair.newKeyPair()
    private val signer = Ed25519Sign(keyPair.privateKey)

    val publicKey: ByteArray = keyPair.publicKey
    val otherPublicKey: ByteArray = Ed25519Sign.KeyPair.newKeyPair().publicKey

    fun response(token: String, status: String = "active", …): String { … }
}
```

A response captured from a real server and pasted in with its signature elided —
`"signature":"Z1dkvYos…"` — verifies nothing, and every negative test around it
passes for the wrong reason. Signing here means the positive test proves the
verifier accepts a genuine response, which is what makes the negative ones mean
anything.

What is covered, and why each one is there:

| Test | Guards against |
|---|---|
| `genuineResponseVerifies` | the verifier rejecting everything, which would make every other test pass |
| `tamperedStatusIsRejected` | an edited body |
| `wrongSignatureIsRejected` | a forged signature |
| `unsignedResponseIsRejected` | a body with the signature simply removed |
| `responseSignedByAnotherKeyIsRejected` | a signature that is genuine, by the wrong signer |
| **`genuineResponseForAnotherLicenceIsRejected`** | **the replay attack — §5.1 check 2** |
| `mismatchedNonceIsRejected` | a stale response replayed later |
| `missingNonceIsRejectedWhenOneWasSent` | a server that drops the echo |
| `nonceIsNotCheckedWhenNoneWasSent` | the cache-read path regressing to "always null" |
| `unconfiguredAuthenticatorTrustsNothing` | an empty key defaulting to trusting everything |
| `integersKeepTheirExactForm` | the `86400.0` bug |
| `htmlCharactersAreNotEscaped` | Gson's default HTML escaping |
| `unexpectedNestingReturnsNullRatherThanThrowing` | a new server field crashing a worker |
| `networkFailureCarriesOn`, `garbageBodyCarriesOn` | fail-open |
| `replayedResponseCarriesOnAndIsNotCached` | a replay poisoning the cache |
| `unknownStatusCarriesOn` | a new status stopping older installs |
| `everyNonceIsFresh` | a reused nonce making replay trivial |
| `freshCacheIsUsedWithoutCallingTheNetwork` | polling |
| `unconfiguredCheckNeverCallsTheNetwork` | an unconfigured build phoning home |
| `cachedActionIsReturnedWithoutAnyApiAtAll`, `anEmptyCacheCarriesOn` | the startup-path read reaching for the network, or a cold cache stopping a start |
| `verifyIsAppendedToTheConfiguredRoot`, `aTrailingSlashOnTheRootDoesNotDoubleUp` | `/api/v1/api/v1/verify` — a 404 that fails open and looks like a healthy licence |
| `aBlankRootMakesNoRequest` | an unconfigured build resolving a hostname |
| **`retrofitPreservesTheExactBytes`** | **a typed Retrofit return value silently disabling enforcement** |
| `theVersionSegmentSurvivesRetrofitsBaseUrlHandling` | Retrofit dropping `/api/v1` from a base URL with no trailing slash |
| `anUnusableBaseUrlIsReportedRatherThanThrown` | a typo in `local.properties` crashing the graph |
| `statusCodesMapToDistinctReportedCodes` | every failure collapsing to one useless log line |
| `aSuccessfulCheckReportsTheLicenceToTheHost` | a success being indistinguishable from a check that never ran |
| `aCachedAnswerIsReportedAsCached` | a cached hit reporting itself as fresh |
| `aStopCarriesTheServersReason` | a refusal reaching the host without its reason |
| `nothingIsReportedWhenNothingWasLearned` | silence being emitted as approval |
| `licenceInfoReadsTheCacheAndNeverTheNetwork`, `licenceInfoIsNullBeforeTheFirstCheck` | a UI-facing query making a request, or a cold cache reading as a refusal |
| **`theAccessKeyIsNeverLogged`** (both suites) | **a credential reaching logcat** |
| `everyLineUsesTheApiTag` | a stray tag breaking `logcat -s Tracker/API_CALL` |
| `theRequestAndItsOutcomeAreBothLogged`, `theVerdictAndTheDecisionAreBothLogged` | a half-logged call that cannot be diagnosed |
| `aCacheHitSaysWhyNoRequestWasMade`, `anUnconfiguredCheckSaysWhichPieceIsMissing` | an absent request looking like a hang |
| `anErrorStatusIsLoggedAsAWarning`, `aFailedVerificationIsAWarningNotADebugLine` | the one line worth noticing scrolling past as debug |

### `ready()` itself — `ReadyLicenseCheckTest`, Robolectric

The suites above prove the licence layer; they cannot prove it is *wired in*. That
wiring was wrong once — nothing called the check at all — and every test above
passed the whole time. `ReadyLicenseCheckTest` drives the real graph through
`Tracker.ready()` with a fake `LicenseApi` and a generated keypair.

| Test | Guards against |
|---|---|
| **`readyFiresALicenceCheck`** | **the regression: `ready()` reading an empty cache and calling nothing** |
| `readyDoesNotWaitForTheCheckToFinish` | a licence server that never replies delaying a host's launch — asserts *both* that `ready()` returned and that the call did go out, or it would pass for the wrong reason |
| `aSuccessfulCheckReachesTheHostAsAnEvent` | `LicenseChecked` never arriving for `ACTIVE` |
| `aFailedCheckReportsNothingAndStillLetsTheHostThrough` | silence being emitted as approval |
| `aReplayedResponseChangesNothing` | a replay reaching the cache through the startup path |
| `aRevokedVerdictLatchesAndRefusesTheNextReady` | a revoked licence surviving a restart |
| `theVerdictIsCachedAndReadableWithoutAnotherCall` | `licenseInfo()` making a request |
| `asecondReadyInsideTheTtlMakesNoSecondCall` | a check on every open becoming a request on every open |
| `readyAlsoEnqueuesThePeriodicWorker` | the 12-hour backstop being dropped |

Comment out `launchStartupLicenseCheck()` and **eight of the nine fail** — the ninth
covers the worker, which is a separate mechanism. That is the check worth running
on any test claiming to cover wiring.

Three things this needed, each a way the test would otherwise lie:

- **`runBlocking`, not `runTest`.** `ready()` touches Room, DataStore and the SDK
  scope, all on real dispatchers. Under `runTest` the virtual clock jumps ahead
  while those suspend, so every `withTimeoutOrNull` fires immediately.
- **Subscribe to `events` before calling `ready()`, and wait until the subscription
  is live.** The flow has `replay = 0`, so an event emitted before the collector
  attaches is gone — the version that raced this passed locally and would not have
  on CI.
- **`TrackerGraph.installForTest`.** The transport is built from a compiled-in URL
  and the trust anchor from a compiled-in key, so without a seam a test can only
  ever watch an unconfigured build decide to do nothing. It is `internal` on an
  `internal` class and unreachable from any host — deliberately, because a
  *runtime-settable* trust anchor would let whoever could reach it sign their own
  verdicts, which is the whole reason the key is compiled in.

Robolectric also needs two things stated before `ready()` will run at all: the
debuggable flag, since the offline gate waives debuggable builds and would
otherwise stop at `LICENSE_MISSING`; and a `com.google.android.gms.version`
meta-data entry, without which the provider monitor dies inside
`GooglePlayServicesMissingManifestValueException` before any licence code runs.
| `requestCarriesThePackageAndSdkIdentity` | a renamed field going out silently |

`LicenseVerdictStore` is an interface purely so these run as plain JVM tests. The real
cache needs a `Context` and a DataStore file, and neither is worth booting
Robolectric for to assert that a 500 changes nothing.

```
./gradlew :fieldtrack-core:testDebugUnitTest
```

---

## 11. Before you ship

- [ ] Signature verification implemented, and tested against a **deliberately
      wrong signature**
- [ ] `key_id` compared against SHA-256 of your own token, and tested by
      **replaying a genuine response from a different licence**
- [ ] A `nonce` is sent and its echo checked
- [ ] Canonical JSON renders `ttl_seconds` as `86400`, not `86400.0` — built
      from `JsonParser`, never from `gson.fromJson(raw, Map::class.java)`
- [ ] Every wire field carries `@SerializedName`, and the request body was
      inspected **on a minified build** to confirm the names went out intact
- [ ] The `Gson` instance has `disableHtmlEscaping()`
- [ ] Both public keys are **compiled in**, not fetched at runtime
- [ ] Verified on a **minified release build**, not only debug
- [ ] Verified on an **API 26 device or emulator**, where `java.security` has no
      Ed25519 at all
- [ ] Airplane mode: the app still starts and tracks
- [ ] Server returning 500: the app still starts and tracks
- [ ] A revoked licence stops tracking within one TTL + check cycle
- [ ] A revoked licence stays stopped across `stop()` / `start()` and across a
      process restart
- [ ] An expired trial reports `LICENSE_EXPIRED`, not `LICENSE_INVALID`
- [ ] The cached verdict survives a reboot, and a **hand-edited cache is
      rejected**
- [ ] `sdk_version` is populated from the real build version
- [ ] Nothing licence-related makes a network call on the startup path
- [ ] Cleartext HTTP is not permitted in the release manifest

---

## 12. Notes specific to Android

| | |
|---|---|
| **`sdk_type`** | Sent as `"android"` by `fieldtrack-core`. A React Native or Flutter app sends `"react-native"` / `"flutter"` — bridges report themselves, not the native SDK underneath |
| **`package_name`** | `context.packageName`, derived by the SDK. Never let an integrator pass it |
| **The access key is never persisted** | `TrackerConfig.license` is dropped on serialisation, and `LicenseGate.token()` re-resolves it from config or manifest each time. A stale token resurrected from disk turns "I updated my licence" into a bug report |
| **Two public keys** | `LicenseVerifier.productionKeys` verifies licence **tokens**; `LicenseConfig.RESPONSE_PUBLIC_KEY_BASE64` verifies `/verify` **responses**. Different keys, deliberately. Hold both |
| **Keys are 32 raw bytes** | Not DER, not `X509EncodedKeySpec`. That wrapping was an artefact of the `java.security` API and is gone |
| **Cleartext** | `https` in release. `10.0.2.2:5858` for local testing needs a debug-only network-security config |

---

## 13. What is still empty

The layer is wired, tested and inert. Two compiled-in constants and one build
input decide whether it enforces anything, and all three are deliberately empty
rather than placeholder values — a wrong key fails closed and loudly, a
placeholder fails open and quietly.

| Constant | File | Until it is filled |
|---|---|---|
| `LicenseVerifier.productionKeys` | `license/LicenseVerifier.kt` | **every non-debuggable build fails the offline gate** with "Unknown license key id". Debuggable builds are waived, which is why this is invisible in development |
| `LicenseConfig.RESPONSE_PUBLIC_KEY_BASE64` | `license/LicenseConfig.kt` | `VerdictAuthenticator.isConfigured` is false, the use case returns `CarryOn` without making a request, and no response is ever trusted |
| `FIELDTRACK_LICENSE_URL` | `local.properties`, or `-PfieldtrackLicenseUrl` / the environment on CI | no licence host to call, and the transport returns null before opening a socket. Not a compiled-in constant — see §3 |

Fill them from the issuing flow and `GET /api/v1/public-key`.
