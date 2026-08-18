# Android — the upload path, 17 August 2026

Closes the seven gaps listed in `Https request-Development.md` §5 and adds the two upload
observability APIs from §1 (§§1–5). All of them applied to Android unchanged: the transport
here was a thinner version of the same design, and repo-wide greps for `gzip`, `Retry-After`,
`403` and any scheme check returned nothing.

Then two `GAPS.md` findings that the above made unavoidable: **G-4** — nothing ever started a
drain, which made the rest of it moot (§6) — and **G-2** — every uploaded point carried
`"battery_percentage": null` (§7).

§§1–5 are entirely in `fieldtrack-sync`. §§6–7 touch `fieldtrack-core`, and neither puts a socket or
an HTTP concept in it: G-4 goes through a new `SyncTrigger` port that core calls out through
without knowing what an upload is, and G-2 is a battery reading with no network involvement at
all.

---

## 1. What a host can now do that it could not before

| API | What it answers |
|---|---|
| `TrakerSync.events: SharedFlow<SyncEvent>` | What the server said, once per exchange — including the background ones nobody was watching |
| `TrakerSync.endpoint` / `.isConfigured` | Where uploads are going, and whether they are going anywhere |
| `SyncConfig.timeouts` | Connect/read/write, without building an `OkHttpClient` |
| `SyncConfig.gzipRequestBody` | Compress the batch, opt-in |
| `SyncConfig.allowCleartext` | Point at a local dev server on purpose |
| `SyncConfig.validate()` | Every problem with a config, as a list, before anything throws |
| `SyncQueue.Result.Retry.retryAfterMs` | The server's own schedule, when it sent one |
| `SyncQueue.Result.Forbidden` | A 403 — terminal, but not the same as a 401 |
| `SyncConfig.autoSync` | Finally means something — see §6 |
| `TrakerArtifacts.registerSyncTrigger` | Core's door to an uploader it knows nothing about |

`SyncEvent.HttpResponse.statusCode` is `null` when no exchange completed at all. A dead
network and a dead server are different problems and a diagnostics screen should not draw
them the same way. The response body is deliberately not carried — it can be megabytes, and
a host that needs it implements `SyncTransport`.

`isConfigured` is derived from `endpoint` so the two cannot disagree, and the headers are not
exposed at all: they carry the host's bearer token, and a property that returns a credential
is a property that ends up in a log.

---

## 2. The seven gaps

**1 — The response body was discarded.** Non-2xx responses now carry up to 4 KB of body on
`SyncResponse.Failure.body`, read with `peekBody`, so a 40 MB error page costs 4 KB rather
than all of it. Success bodies are still discarded. The SDK never logs the body: an error
body can echo a request header.

**2 — `Retry-After` was ignored.** Parsed in both RFC 9110 forms — delta-seconds and
IMF-fixdate — clamped to 1 s–6 h, and carried through `SyncResponse.Failure.retryAfterMs` →
`SyncQueue.Result.Retry.retryAfterMs` → the worker.

The clamp is not decoration: this value schedules background work, so an unbounded header
parks the queue and looks exactly like a broken SDK. Neither is the cap before the multiply —
`9999999999999999` seconds overflows `Long` once multiplied by 1000 and comes back negative,
which would read as "no opinion" instead of "an absurd wait".

**The WorkManager interaction is the part worth knowing.** `Result.retry()` cannot carry a
delay — WorkManager applies the *request's* backoff policy, fixed when the request was built,
and `setInitialDelay` affects only a newly enqueued one. And `requestSync()` enqueues with
`ExistingWorkPolicy.KEEP` on purpose, so a fresh request carrying the server's delay would
have been silently discarded in favour of our 30-second default. So the worker enqueues
afresh with `REPLACE` on that one path, and reports the attempt as done. `KEEP` stays
everywhere else: it exists so a burst of accepted points cannot reset the backoff clock, and
this path is server-rate-limited by construction, so there is no burst to defend against.

A `Retry-After` seen by a host's own `syncNow()` is reported, not acted on. That call is
inline and the host owns the schedule.

**3 — No compression.** `SyncConfig.gzipRequestBody`, **off by default and deliberately so.**
There is no negotiation mechanism for request-body encoding — `Accept-Encoding` is a
*response* preference; a client sending `Content-Encoding: gzip` on a POST is asserting it,
and a server that does not expect it answers 400 or stores the compressed bytes as the
payload. Defaulting it on would break working integrations on an upgrade with a failure that
reads as a server bug. Bodies under 1 KB are never compressed — the framing costs more than
it saves.

Implemented in the transport, not in `SyncQueue`, and via `java.util.zip` rather than an
okio `GzipSink`, so no dependency was added. `SyncRequest.gzip` carries the flag so a custom
transport can honour it or ignore it; ignoring it is correct behaviour, not a bug.
`Accept-Encoding` is still never set by hand — OkHttp adds it itself and transparently
gunzips only when it did, and setting it manually would make `peekBody` return gzip bytes.

**4 — Not a background session. Not applicable on Android, and that is the finding.**
Uploads already run in `SyncWorker`, a WorkManager `CoroutineWorker` under a network
constraint: the request is persisted, survives process death and reboot, and re-runs the
whole drain from durable state. That is a stronger guarantee than a background `URLSession`,
which hands over one request. No code was written for this item.

What *is* documented instead: `syncNow()` runs in the caller's scope and dies with it, so
`requestSync()` is the right call for anything not user-initiated.

**5 — Timeouts were baked in.** `SyncConfig.timeouts: SyncTimeouts`, a plain data class with
no OkHttp types in it — the point of the transport seam is that a host with its own client
never links OkHttp, so the place timeouts are *configured* must not drag it back in. The
built-in transport derives a client per distinct timeout set via `newBuilder()` (shared pool
and dispatcher) and memoises it. A host that passed its own fully configured `OkHttpClient`
gets exactly that client back.

**6 — `http://` was accepted.** `configure()` now runs `SyncConfig.validate()` and throws
`IllegalArgumentException`. Cleartext is blocked at runtime by Android's default network
security policy from API 28, so an `http://` endpoint used to surface as an ordinary network
error and retry forever with nothing naming the cause.

Loopback (`localhost`, `127.0.0.1`, `::1`, `10.0.2.2`) is exempt without a flag, matching the
platform's own default config, so a local dev server needs no ceremony. Anything else needs
`allowCleartext = true` deliberately.

Throwing is the same deliberate exception to the no-throw contract that
`TrakerConfig.Builder.build()` already makes, for the same reason: it runs on the host's own
thread while it assembles a value, which is where fail-fast belongs. `validate()` is public
for hosts assembling a config from untrusted input.

**7 — Only 401 was terminal.** 403 now maps to `SyncResponse.Forbidden` →
`SyncQueue.Result.Forbidden`, and `requestSync()` becomes a no-op until the next
`configure()`.

The teardown is **not** 401's:

| | 401 `AuthExpired` | 403 `Forbidden` |
|---|---|---|
| Stops tracking | yes | no |
| Clears the upload queue | yes | **no** |
| Forgets the config | yes | yes |

A 401 means the credential this data was recorded under is gone and the next login may be a
different user — that is what justifies clearing the queue. A 403 means *this* credential may
not write *this* resource: a scope, a rotated key, a server-side permission bug. Same user,
same valid data. Destroying it to fix a permissions mistake is the more expensive of the two
errors, so the rows stay and the loop stops.

404 was deliberately left retryable — it is as often a mid-deploy blip as a typo, and the
recovery for a typo is the same either way. The terminal set is not configurable: a knob here
is a knob that silently disables the protection, and the `SyncTransport` seam is already the
escape hatch for a host that genuinely disagrees.

---

## 3. Compatibility

Three kinds of change, and they are not equally safe.

**Source-breaking — one item.** `SyncQueue.Result.Forbidden` and `SyncResponse.Forbidden` are
new subtypes of public sealed types, so an exhaustive `when` without an `else` stops
compiling. `DEVELOPER-GUIDE.md` §13 shows hosts exactly such a `when`, so this will be hit.

It is accepted rather than avoided. The alternative was folding 403 into `AuthExpired`, which
would silently force-logout every host and clear their queue on what may be a key rotation. A
compile error that takes one branch to fix beats a wrong runtime behaviour nobody notices.

**Binary-breaking.** `SyncResponse.Failure` gained `body` and `retryAfterMs`;
`SyncQueue.Result.Retry` gained `retryAfterMs`; `SyncRequest` gained `gzip` and `timeouts`;
`SyncConfig` gained three fields. All with defaults, so source compatibility holds, but
`componentN`/`copy` descriptors move. At `0.1.1-alpha01`, with hosts building from source and
no binary-compatibility validator in the build, this is recorded rather than engineered
around. Adding one is the right follow-up — taken against the post-change surface, so the
baseline does not bake in these breaks.

**Behavioural break.** A host currently passing an `http://` URL now crashes at `configure()`
where it previously failed silently at upload time. That is the intent.

**Additive.** `SyncEvent`, `SyncTimeouts`, `TrakerSync.events`/`.endpoint`/`.isConfigured`,
`SyncConfig.validate()`.

---

## 4. Verified

**`fieldtrack-sync` had no `src/test` directory at all.** It now has 47 tests, all passing —
`RetryAfterTest` (15), `SyncQueueTest` (11), `OkHttpSyncTransportTest` (11),
`SyncConfigValidationTest` (10). MockWebServer was already a declared test dependency and
unused; no build file change was needed. Note the OkHttp 5 package is `mockwebserver3`.

Covered: both `Retry-After` forms including a past date, garbage, and the overflow case; 403
halting without `markSynced` or `clearQueue` being called and with `pendingCount()` unchanged;
a 1 MB error body truncated to 4 KB; a gzip round-trip decompressed server-side and compared
byte-for-byte; the sub-threshold body staying uncompressed; a timeout returning `Failure`
rather than throwing; event order across a three-batch drain; a 401 mid-drain keeping the
batch that already succeeded; loopback and `allowCleartext` acceptance.

`:fieldtrack-core:testDebugUnitTest` passes unchanged. `:fieldtrack-sync:assembleRelease` and the
root `verifyReleaseObfuscation` audit both pass with the new ProGuard keeps for `SyncEvent`
and `SyncTimeouts`.

**Not verified:** no device testing, and specifically not the WorkManager scheduling itself.
`work-testing` is not in the version catalog and Robolectric is not on this module's test
classpath, and neither was added to assert three builder calls. What is testable was kept
pure — the clamp and the parse are covered directly, and the enqueue reduces to configuration
with no branching. A `SyncWorkerTest` with those two dependencies is a self-contained
follow-up if the scheduling behaviour is ever in doubt.

---

## 6. G-4 — the SDK now triggers its own uploads

Everything above was moot while nothing started a drain. `SyncConfig.autoSync` was documented
as "upload as points arrive" and read nowhere; repo-wide, nothing outside `TrakerSync` called
`requestSync()` or `syncNow()`. A host that configured sync and never called it itself
accumulated rows forever (GAPS.md G-4, spec §3.4 step 3 and §12.2 check 3).

**The seam.** Core cannot depend on `fieldtrack-sync` — that is the whole offline-first
arrangement — so the fix is a second narrow port next to `PendingUploadStore`, pointing the
other way:

```kotlin
public fun interface SyncTrigger { public fun requestSync() }
```

`TrakerArtifacts.registerSyncTrigger(trigger)` registers it; `fieldtrack-sync` calls that from
`configure()` when `autoSync` is on, and passes `null` when it is off or when a 401/403 tears
the configuration down. Nothing registered means core does nothing and asks the database
nothing — a host without the sync artifact pays for none of it. **No network type crosses into
core, and no new dependency was added in either direction.**

**Three triggers**, in `SyncScheduler` (`fieldtrack-core/.../work/SyncScheduler.kt`):

| Trigger | Cadence | Covers |
|---|---|---|
| Accepted point, via `ingestor.onAcceptedPoint` | per stored point, throttled to 1/min | What `autoSync` means |
| `HealthLoop.runCheck` | 2 min, while the service runs | Rows queued, or last confirmed upload ≥ 16 min old |
| `BackstopWorker.doWork` | 15 min | The same check with a **dead service** — a backlog left by a drain that failed while the process was gone |

The throttle is on the point path because navigation mode stores a point a second, and while
WorkManager coalesces the work, the enqueue is still a binder call on the ingest path. One
request a minute is far below a 100-row batch, so nothing waits meaningfully longer.

The staleness clause is what makes the supervision path a net rather than an echo of the point
path: a parked user stores nothing to trigger on, so once the last confirmed upload is 16
minutes old the throttle is bypassed. Both supervision callers ask `pendingUploadCount()`
first — waking a worker to discover there is nothing to send is the wrong direction of the
same mistake.

**Schema.** One new query, `MAX(syncTimeMs) WHERE syncTimeMs > 0` — a read, so the Room
version stays at 6 and no migration was needed. The `> 0` matters: an unsynced row and a row
cleared by a 401 teardown both carry 0, and neither is a confirmed upload.

**Store-then-sync is unchanged.** The row is durable before the trigger fires, so a request
that never arrives costs nothing.

**Tests:** `fieldtrack-core/src/test/.../work/SyncSchedulerTest.kt`, 11 cases — no trigger means
no work and no query at all; the point path throttles at 60 s and fires again after; an empty
queue wakes nothing; a stale sync overrides the throttle; a queue that never synced counts as
stale; clearing the trigger stops the nudging; re-registering resets the throttle so a fresh
`configure()` syncs at once; a failing query cannot take the supervision loop down.
`SyncScheduler` takes a two-method `UploadQueueStats` rather than the DAO, so all of that runs
on plain JUnit with no Room and no Robolectric.

**Not verified:** that WorkManager actually runs the enqueued work on a device — same limit as
§4 above.

---

## 7. G-2 — battery and charging state are now captured

`"battery_percentage": null` shipped on **every** uploaded point, always. The column, the
migration, the mappers, the public `RawPoint` surface and the wire field all existed and
carried the null faithfully; `FixIngestor.contextFor()` simply never set it, so
`IngestContext.batteryPct` and `.isCharging` took their declared defaults on every fix
(GAPS.md G-2, spec §11.1).

This is diagnostic, not functional — nothing in the pipeline gates on it. It is also the
field that answers the first question asked of every field report: did the tracker die
because the OEM killed it, or because the phone was at 3 %.

**`AndroidBatteryProbe`** (`fieldtrack-core/.../data/platform/BatteryProbe.kt`) reads
`BATTERY_PROPERTY_CAPACITY` first — a direct query, no broadcast — and falls through to the
sticky `ACTION_BATTERY_CHANGED` when that answers outside 1..100, which some OEMs do
(`Int.MIN_VALUE`, `-1`, a flat `0` on a phone that plainly is not). The fallback registers a
null receiver, which registers nothing: it returns the broadcast the system already holds.
No permission is required for either.

`EXTRA_SCALE` is honoured rather than assumed to be 100 — a device counting in 255ths at half
charge is 50 %, and dividing by an assumed 100 puts it at 128 %.

**Cached for a minute** (`CachedBatteryReader`), on the elapsed-realtime clock so a wall-clock
jump cannot freeze or thrash it. Navigation mode ingests a fix a second, and while the sticky
read is cheap it is still a binder call on the consumer thread — the same path where the
pedometer query is bounded at 1.5 s, and for the same reason. A probe that throws yields
`BatteryStatus.Unknown`: the point is the record, the battery is a note in the margin.

Stamped for **every verdict**, not only accepted fixes — a rejected fix's raw-point row is
exactly where "the phone was at 4 %" belongs.

`FixtureReplay.defaultContext()` still leaves both null, deliberately: a replay has to stay
byte-deterministic, and a live battery reading would make it a function of the device it ran
on.

**Tests:** `fieldtrack-core/src/test/.../data/platform/BatteryProbeTest.kt`, 8 cases — scale
honoured, range ends, an absent extra reading as unknown rather than 0 %, the charging
tri-state (`UNKNOWN` stays null instead of becoming "not charging"), 60 fixes costing one
binder call, refresh after the window, a throwing probe, and a first read on a zero elapsed
clock still missing the cache.

**Not verified:** no device run. Whether a given OEM's `BATTERY_PROPERTY_CAPACITY` is honest
is exactly what the fallback exists for, and only hardware can confirm which path a given
phone takes.

### The host-facing half

Storing it on a point answers the question after the fact. A host also wants to ask now, and
to be told when it changes:

```kotlin
val info: BatteryInfo = trackIt.batteryInfo()        // reads the platform now
trackIt.batteryState()                               // StateFlow<BatteryInfo>
// and on Traker.events:
is TrakerEvent.BatteryChange -> show(event.battery)
```

`BatteryInfo` carries `percent` (0..100 or null), `isCharging` (tri-state), `powerSource`
(`NONE`/`AC`/`USB`/`WIRELESS`/`DOCK`/`UNKNOWN`) and a derived `isLow` at ≤ 15 %. Every field
is nullable-or-`UNKNOWN` rather than defaulted, for the reason above: a phone that will not
say what its charge is has not said it is at 0 %.

`batteryInfo()` needs no session, no permission and no `ready()` — it is safe from anywhere,
including before tracking has ever started. It is a binder call, so it belongs in a refresh
rather than a per-frame render; `batteryState()` is there for a live display.

`batteryState()` and the stored points read the same monitor, so a host's display and its
uploaded rows cannot disagree.

**Events are transitions, not a heartbeat.** `BatteryMonitor` registers for
`ACTION_POWER_CONNECTED`, `ACTION_POWER_DISCONNECTED`, `ACTION_BATTERY_LOW` and
`ACTION_BATTERY_OKAY` — four broadcasts on a normal day. Deliberately **not**
`ACTION_BATTERY_CHANGED`, which fires on every percentage point and temperature wobble;
registering for it continuously is the thing Android's own documentation warns against, and
it would make this SDK a background wake source for a diagnostic field. The percentage drift
between those transitions is picked up by the capture path's one-minute refresh, and an
unchanged reading emits nothing.

Started from `ready()` alongside `ProviderStateMonitor`, so `batteryState()` is live from
then on rather than only during a session. Like that monitor, it is not stopped — the
receiver lives for the process, which for four broadcasts a day is the right trade, and it is
the same open question as G-25.

**Compatibility:** `TrakerEvent.BatteryChange` is a **new sealed subtype**, so an exhaustive
`when (event)` without an `else` stops compiling — the same break class as
`SyncQueue.Result.Forbidden` in §7 above, and accepted for the same reason. `sample-android`
now includes `BatteryChange` handling in both the screen log and capture file log while
building against the published Maven artifact (`libs.trackit.sdk`). Everything else here is
additive.

**Tests:** 8 more cases in the same file — plug-type mapping, `isLow` needing a reading,
`refresh()` ignoring the cache because a host asking now means now, a change reaching both the
state flow and the event flow, an unchanged reading emitting nothing, state starting `Unknown`
rather than claiming a flat battery, and `start`/`stop` being idempotent so no receiver is
leaked or double-unregistered.

---

## 8. `SyncConfig.Builder`, and the endpoint contract written down

**A builder, with `baseUrl` + `path`.** Most hosts already keep a base URL for their own API
and want the SDK pointed at a path under it — handing it a second full URL is a value that
drifts out of step the moment the environment changes.

```kotlin
SyncConfig.builder()
    .baseUrl(BuildConfig.API_BASE_URL)
    .path("v1/location/batch")
    .header("Authorization", "Bearer $token")
    .build()
```

The two halves join with exactly one `/` regardless of which side carries it, because a
double slash is a 404 on some servers and a redirect on others. `.url(...)` still sets the
whole thing and wins over both. `build()` validates and throws, `buildUnchecked()` does not —
mirroring `TrakerConfig.Builder` exactly, including the reasoning for the throw.

**And on `TrakerConfig.Builder` too**, by request, for an app that already keeps one base URL
for its whole API:

```kotlin
trackIt.ready(TrakerConfig.builder().baseUrl("https://api.example.com").build())
sync.configure(SyncConfig.builder().path("v1/location/batch").build())
```

The reservation stands and is worth recording: `fieldtrack-core` now carries a field it can never
read, because it opens no socket and has no endpoint of its own. What makes it defensible is
that the field is inert there — `TrakerArtifacts.baseUrl` is the only door out, and it is the
same seam `PendingUploadStore` and `SyncTrigger` already use. Core gained no network concept,
only a string it stores.

Resolution runs in `TrakerSync.configure()` and is a **fallback, never an override**:

| `SyncConfig` carries | `TrakerConfig.baseUrl` | Endpoint |
|---|---|---|
| an absolute `url` | anything | the absolute `url` |
| `baseUrl` + `path` | anything | the sync-level pair |
| `path` only | set | base + path, one `/` |
| `path` only | unset | `configure()` throws, naming both places a base can come from |

Two consequences worth stating. A **path-only `SyncConfig` passes `build()` while still
invalid** — the builder cannot see the core config, so that one check is deferred to
`configure()`, which resolves first and then validates. And `ready()` must run before
`configure()`, since that is what loads the base URL; a `configure()` before it gets the same
"not a valid absolute URL" error as a host that set no base at all.

`ConfigStore` gained a `cached` snapshot for this: `configure()` runs on the host's thread and
cannot suspend to read DataStore, and a blocking disk read there to answer "nothing yet" would
be worse than the honest `null`.

`TrakerConfig.baseUrl` is validated in core's own `validate()`, so a typo fails while the host
is assembling config rather than one `configure()` call later in a different module, reported
against a path the host did not write. It is persisted like every other field — **not**
`@Transient` like `license`, so do not put a credential in it.

**The contract is now documented** in `USER-GUIDE.md` §11, restructured into six subsections:
setup, the API surface (every member with what it returns and when it no-ops), who triggers an
upload, the request, the response, and custom transports.

The request section documents every field of the uploaded JSON — type, meaning and the traps:
`time` is epoch **milliseconds**, `time_zone` is per point because a session can cross zones,
`activity_status` is `"<provider>@<movementStatus>"` lowercase, `battery_percentage` is a
**string**, `movementSpeed` is `0.0` when the provider reported none so `hasSpeed` has to be
read first, and **nullable fields are omitted rather than sent as `null`** — which matters to
any backend that distinguishes absent from null, and was previously undocumented.

That last point is now pinned by `SyncPayloadWireTest`, which asserts the serialised body
literally. The wire format is a published contract with a backend written against it, so a
field rename should fail a test here rather than someone's ingest later.

The response section states what each status does to the queue: 2xx marks synced, 401 clears
it, 403 keeps it, everything else retries, and 404 is deliberately not terminal — plus
`Retry-After` in both forms and the 4 KB error-body capture.

**Tests:** `SyncConfigBuilderTest` (16), `SyncPayloadWireTest` (4) and `TrakerConfigBaseUrlTest` (7). Slash-joining in every
combination including doubled slashes, whitespace, base-only URLs, an explicit `url` winning,
header accumulation and replacement, every knob reaching the config, `build()` throwing with
every problem named, the four resolution outcomes in the table above, and — core side — the
builder round trip, a relative `baseUrl` failing validation, and `baseUrl` surviving
persistence while `license` does not.

---

## 9. Still open

Out of scope for this pass, and none of it is on the doc's list of seven:

- `changePace` has no public entry point on Android, though `API.md` and `SDK-COMPARISON.md`
  both claim it ships. iOS made it public this cycle.
- No geofence dwell, no initial-state trigger on arming (`setInitialTrigger(0)`), and geofence
  definitions and crossings live in SharedPreferences rather than Room. iOS added dwell plus
  schema v7/v8 this cycle.
- `motion.stillConfidenceMin` does not exist on Android under any name.
