# FieldTrack — API & Implementation Reference

Concrete code for every public surface and every load-bearing internal. This is the contract Kotlin and Java host apps consume.

Namespace `com.field360.tracker` (Maven group `com.github.fieldtrack360.fieldtrack`). Toolchain matched to the reference app: AGP 9.3.1, Kotlin 2.4.10, Gradle 9.7.0, `compileSdk 37`, `minSdk 26`, JVM target 17.

---

## 1. Single source of truth — the rule that makes this work

```
                    ┌───────────────────────────────────────┐
                    │  fieldtrack-geo   (pure Kotlin/JVM)      │
                    │  every algorithm, every constant,     │
                    │  every decision. No Android. No I/O.  │
                    └──────────────────┬────────────────────┘
                                       │ implements ports
                    ┌──────────────────▼────────────────────┐
                    │  fieldtrack-core  (Android library)      │
                    │  platform plumbing + Tracker API      │
                    │  + TrackerJava (Java facade)          │
                    └──────────────────┬────────────────────┘
                                       │
                          Kotlin / Java host app
                              (direct API)
```

Two invariants, enforced in CI:

1. **No algorithm above `fieldtrack-geo`.** A Konsist/Detekt rule fails the build if `fieldtrack-core` contains a numeric literal in a decision expression, or imports `kotlin.math` outside `provider/FixMapper`.
2. **No platform types in `fieldtrack-geo`.** It is a plain Kotlin/JVM module; `android.location.Location` never appears, so the whole engine runs under JVM unit tests with no emulator. Conversion happens once, in `FixMapper`.

Consequence: a behaviour change is a one-file change in `fieldtrack-geo`, proven by the JVM fixture suite before any device runs it.

---

## 2. Core types (`fieldtrack-geo`)

```kotlin
package com.field360.traker.geo.model

/** A raw sample handed to the pipeline. Platform-agnostic; produced by FixMapper. */
data class TrackFix(
    /** Wall-clock fix time (Location.getTime()). Storage and display ONLY. */
    val timeMs: Long,
    /** Monotonic fix time. THE clock for every delta, gap and age in the pipeline. */
    val elapsedRealtimeNanos: Long,
    /** When the SDK received it. Diagnostics only. */
    val receivedAtElapsedNanos: Long,
    val latitude: Double,
    val longitude: Double,
    /** metres, always > 0 after FixMapper clamping */
    val accuracy: Float,
    val altitude: Double?,
    val verticalAccuracy: Float?,
    /** m/s, hardware. Meaningless unless hasSpeed. */
    val speedMps: Float,
    val bearingDeg: Float,
    /** Hardware validity flags. DEFAULT FALSE — see SOURCE-AUDIT A8. */
    val hasSpeed: Boolean = false,
    val hasBearing: Boolean = false,
    val provider: String,
    val isMock: Boolean = false,
    val satelliteCount: Int? = null,
)

/** An accepted, stored point. */
data class TrackPoint(
    val id: Long = 0,
    val uuid: String,
    val sessionId: String,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val localDate: String,          // yyyy-MM-dd in `timezone`
    val timezone: String,           // IANA id, per point (EC-89)
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double?,
    val speedMps: Float,
    val bearingDeg: Float,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
    val provider: String,
    val isMock: Boolean,
    val movementStatus: MovementStatus,
    val detectedActivity: ActivityType?,
    val activityStartTimeMs: Long,
    val odometerMeters: Double,
    val batteryPct: Int?,
    val isCharging: Boolean?,
    val address: Address? = null,
    val extras: String? = null,     // host-app JSON blob
    val acceptReason: String,       // the reason vocabulary — kept on the row
)

enum class MovementStatus { STEADY, MOVING }

enum class ActivityType { IN_VEHICLE, ON_BICYCLE, ON_FOOT, WALKING, RUNNING, STILL, TILTING, UNKNOWN }

enum class MotionState { STOPPED, MOVING, STOP_PENDING, STATIONARY }

/** Immutable. Replaced wholesale per fix; serialised to `filter_state`. See A6. */
data class FilterState(
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    /** P, metres². Negative = uninitialised sentinel. */
    val variance: Float = -1f,
    val elapsedNanos: Long = 0L,
    val lastHwVehicularNanos: Long = 0L,
    val consecutiveRejectCount: Int = 0,
    val origin: Anchor? = null,
    val departCount: Int = 0,
    val prevNetMeters: Float = 0f,
    val movingMode: Boolean = false,
    val settleCount: Int = 0,
    val recoveryPending: Anchor? = null,
    val lastFixElapsedNanos: Long = 0L,   // burst gate, keyed on FIX time (A5)
    /** Heading at the last STORED point, or -1. Keyed on the last stored point rather
     *  than the last fix: keyed on the last fix a sweeping bend accumulates nothing,
     *  because each leg turns only a few degrees. (EC-45) */
    val lastCapturedBearingDeg: Float = -1f,
    /** Constant-velocity state, m/s on the local tangent plane, plus its 2×2 covariance.
     *  INFERRED from successive position corrections — not the fix's hardware speed.
     *  (§4, EC-44a) */
    val velocityNorthMps: Float = 0f,
    val velocityEastMps: Float = 0f,
    val covPosVel: Float = 0f,            // m²/s — how a position fix informs velocity
    val varianceVel: Float = 25f,         // (m/s)² — σ = 5 m/s: "walking or driving?"
) {
    data class Anchor(val lat: Double, val lng: Double)
    val isInitialised get() = variance >= 0f
    fun accuracy(): Float = if (variance < 0) Float.MAX_VALUE else sqrt(variance)
}

sealed interface Verdict {
    val reason: String
    data class Accept(override val reason: String) : Verdict
    data class Skip(override val reason: String) : Verdict
    data class Reject(override val reason: String) : Verdict
}

data class FixDecision(
    val fix: TrackFix,
    val verdict: Verdict,
    val filterLat: Double,
    val filterLng: Double,
    val sigma: Float,
    val threshold: Float,
    val distanceMovedM: Double,
    val effectiveSpeedMps: Float,
    val motionState: MotionState,
)

data class PipelineResult(
    val decision: FixDecision,
    val state: FilterState,           // new state, always returned
    val point: TrackPoint?,           // non-null iff Accept
)
```

**The reason vocabulary is API.** Keep these strings byte-identical to the reference — they are the field-debugging language and the fixture assertions key on them:

```
Init · Resume · Burst · NLP Fallback · Impossible Speed
Recovery Confirmed · Recovery Reset · Recovery Held
Sigma Gate Outlier · Sigma Forced Reset · Sigma Junk Fail
Vehicular · Moving/Walking · Indoor Arrival
Arrival · Stationary Recovery · Blackout Arrival · Walk Arrival · 15-Min Heartbeat
Origin Set · Departure Held · Drift Suppressed · HeartBeat Skipped
Heuristic Gate · Session Closed · Mock Location · Invalid Coordinates · Stale Fix · Reboot Boundary
```

---

## 3. Ports — what `fieldtrack-geo` needs from the platform

```kotlin
package com.field360.traker.geo.port

interface PointStore {
    suspend fun lastPoint(sessionId: String): TrackPoint?
    suspend fun insert(point: TrackPoint): Long
    suspend fun loadFilterState(): FilterState?
    suspend fun saveFilterState(state: FilterState)
    suspend fun recordDecision(decision: FixDecision)
}

interface Clock {
    fun wallTimeMs(): Long
    fun elapsedRealtimeNanos(): Long
}

interface TrackLogger { fun d(tag: String, msg: String); fun w(tag: String, msg: String) }

/** Optional road snapping — the ONLY port core does not implement, because core must
 *  carry no HTTP client and no API key. Install one with Tracker.setRoadSnapProvider().
 *
 *  Implementations must DEGRADE rather than fail: returning an empty list makes
 *  TrackBuilder fall back to raw geometry and emit a snap_unavailable warning rather
 *  than losing the track (EC-100). Tracker wraps the call in a catch anyway — "must" is
 *  not "will", and a third-party provider throwing into a host's coroutine is a crash
 *  the host cannot reasonably prevent (EC-75). */
interface RoadSnapProvider {
    suspend fun snap(path: List<GeoPoint>): List<GeoPoint>
    object Disabled : RoadSnapProvider   // the default
}
```

`fieldtrack-core` implements the first three (`RoomPointStore`, `SystemClock`, `AndroidLogger`). The fixture harness implements them in-memory, which is how the whole engine is tested without a device.

`RoadSnapProvider` is deliberately left to the host, with one implementation shipped in the optional `fieldtrack-snap` artifact:

```kotlin
// build.gradle.kts — fieldtrack-snap treats OkHttp as compileOnly, exactly like fieldtrack-sync
implementation("com.github.fieldtrack360.fieldtrack:fieldtrack-snap:<version>")
implementation("com.squareup.okhttp3:okhttp:<version>")

// There is deliberately NO default baseUrl. The OSRM demo server has no availability
// guarantee; defaulting to it would put your production traffic on someone else's free
// instance without anyone deciding to. Point this at your own deployment.
trackIt.setRoadSnapProvider(OsrmSnapProvider(baseUrl = "https://osrm.example.com"))
```

`OsrmSnapProvider` chunks long traces (90 coordinates, overlapping by one so the joints are matched rather than guessed) and **degrades per chunk**: a five-request trace losing one to a rate limit keeps the other four and contributes raw coordinates for the failed stretch. `Snapper` then declines to snap anything to those, because they are further from the returned "road" than the 80 m guard allows.

---

## 4. The Kalman filter (`fieldtrack-geo`)

A **constant-velocity** filter: position plus a velocity estimate, one 2×2 covariance shared by latitude and longitude. Concurrency defects from `KalmanLatLngFilter.kt` stay removed — no mutable fields, no `@Volatile`, and `predictSigma` takes `q` explicitly (fixes [A7](SOURCE-AUDIT.md)).

### Why it is no longer scalar (EC-44a)

The original filter was position-only, and [EKF-DESIGN-REVIEW.md](reference/EKF-DESIGN-REVIEW.md) §C1 defends that choice at length. **That defence is against a different model.** It rejects **CTRV** — five states including a turn rate `ψ̇` — on the grounds that a turn rate is unobservable at 60 s sampling, so the prediction curves in an invented direction and the next fix gets gated out. Constant velocity carries no turn state at all; it can only extrapolate a straight line, so neither objection transfers.

What the review did not consider is that a position-only filter has no term for a moving target, so against steady motion it lags by a fixed amount *every fix*. Measured on a straight 40 km/h drive at the 12 s cadence: the estimate trailed ~130 m per fix, the lag accumulated until it breached the 3-sigma gate, and **one fix in four was rejected** and then recovered by a forced reset that visibly snapped the track forward. No Q/R tuning fixes that — the model had no term for the thing it was tracking.

The review's own prescription for turn geometry, *"more samples at speed"*, shipped as adaptive cadence and the turn-burst tier (EC-45). That is what makes velocity observable here, and it is why this model is right now and would not have been then.

### The post-turn cascade cannot happen

A constant-velocity prediction *does* overshoot through a real corner — the same cascade in a new costume if left alone. The pipeline therefore gates against **whichever prediction is closer** to the fix:

- straight road → the extrapolated position is right, the last corrected one lags a full leg. Taking the extrapolation is what removes the lag;
- corner → the extrapolation overshoots down the old heading. Taking the last corrected position reproduces the scalar filter exactly.

So the gate never lags on a straight *and* never widens through a corner. **The filter cannot be worse than what it replaced at any single fix**, which is the property that made the change safe to ship.

That is the *gate*. The **correction** is a separate problem and needed a separate answer: picking the closer prediction stops a corner rejecting good fixes, but the estimate the filter publishes still lags into the turn and overshoots out of it, because the model it is correcting has no term for turning. Field capture showed that as a spike running past a right-hand turn before snapping onto the road.

So the process noise is raised to a lateral **2.0 m/s²** while the model is provably wrong — the filter is told its own prediction is the unreliable party. Turning is read off the filter's own velocity vector against the measured heading (≥ 25°), so nothing has to be plumbed in from `TurnDetector` and it holds for turns that never armed the burst tier.

Two things it deliberately does not do. It does not fire when either the filter's speed or the measured speed is below `turnBurstMinSpeed` — a near-stationary phone's heading swings through the full circle on multipath alone, and boosting there would widen the correction on exactly the fixes the six stationary defences exist to suppress. And **it never reaches the gate**, which keeps the straight-line `q`: a corner is a reason to track harder, not an amnesty for fixes that would otherwise be rejected, and a turn is where multipath off the buildings on the inside of it is worst. One value driving both would have made every corner a quiet exception (EC-45a).

```kotlin
package com.field360.traker.geo.filter

object KalmanFilter {
    const val MIN_ACCURACY = 1f

    data class Prediction(val latitude: Double, val longitude: Double, val sigma: Float)

    /** Hard (re)seed. VELOCITY SURVIVES — a reseed happens because the POSITION was
     *  wrong (gap, teleport, forced reset), and in every one of those cases the velocity
     *  is the part still worth knowing. Zeroing it would make the next prediction claim
     *  the target had stopped, reintroducing one fix of exactly the lag this model
     *  removes. Its variance is reset instead, so it is held loosely and re-earned. */
    fun seed(state: FilterState, lat: Double, lng: Double, accuracy: Float, elapsedNanos: Long): FilterState

    /** Where the target should be at `atElapsedNanos`, extrapolating the velocity. */
    fun predict(state: FilterState, atElapsedNanos: Long, q: Float): Prediction

    /** Predict + update. `q` is an ACCELERATION spectral density in m/s² — see below. */
    fun process(
        state: FilterState, lat: Double, lng: Double,
        r: Float, elapsedNanos: Long, q: Float,
    ): FilterState

    /** Gate width helper. Pure — never mutates state. `q` is THIS fix's q (A7). */
    fun predictSigma(state: FilterState, atElapsedNanos: Long, q: Float): Float

    /** Current speed estimate, m/s. Diagnostics and the decision log — never a gate. */
    fun speedOf(state: FilterState): Float
}
```

Process noise is **continuous** white-noise acceleration, `Q = q²·[[dt³/3, dt²/2], [dt²/2, dt]]`, not the discrete `dt⁴/4` form. The discrete form assumes acceleration holds constant across the whole interval — fair at the 100 Hz an automotive filter runs at, badly wrong at 12 s. Using it inflated sigma from 10 to 105 and the gate from 404 m to 690 m, which would have bought the lag fix by handing every multipath spike a free pass.

The off-diagonal `dt²/2` term is the mechanism by which a *position* correction informs the *velocity* — it is how the filter learns how fast the target is going without ever being told. `speedOf()` is therefore an inference, not a copy of the fix's hardware speed.

**`q` changed units, so the constants were renamed.** The scalar filter's `q` was a position-drift rate; the new one is acceleration in m/s². `qVehicular = 1.2f` became `qAccelVehicular = 0.6f`, and so on for the whole set. The rename is deliberate: carrying the numbers over unchanged would have silently meant something else, and forcing every call site to be re-read is the only way that gets caught.

---

## 5. Acceptance pipeline (`fieldtrack-geo`)

Signature and stage order. The stage bodies port `LocationUtil.isKalmanFilteredLocation` (`LocationUtil.kt:172-669`) verbatim; **the order is load-bearing and must not change** — burst before anything mutates the last-fix clock, NLP before state determination (an NLP fix has no speed and would masquerade as stationary), recovery before the sigma gate (a post-gap fix would otherwise burn the reject counter).

```kotlin
package com.field360.traker.geo.filter

class AcceptancePipeline(private val c: TrackerConstants = TrackerConstants.Default) {

    fun accept(fix: TrackFix, past: TrackPoint?, state: FilterState): PipelineResult {
        // Stage 0 — validity (EC-23..EC-28)
        Validation.check(fix)?.let { return reject(fix, state, it) }

        // Stage 1 — burst / cold start / resume.  Burst keyed on FIX time, not delivery (A5).
        if (state.lastFixElapsedNanos != 0L &&
            (fix.elapsedRealtimeNanos - state.lastFixElapsedNanos) < c.burstMs * 1_000_000L
        ) return reject(fix, state, "Burst")

        var s = state
        if (!s.isInitialised || past == null) {
            if (past == null) {
                s = KalmanFilter.seed(s, fix.latitude, fix.longitude, fix.accuracy, fix.elapsedRealtimeNanos)
                    .copy(origin = FilterState.Anchor(fix.latitude, fix.longitude))
                return accept(fix, s, "Init")
            }
            // Resume after process death: re-seed from the STORED anchor with its STORED
            // timestamp (A2), then fall through so THIS fix is judged by every gate.
            s = KalmanFilter.seed(s, past.latitude, past.longitude,
                    past.accuracy.takeIf { it > 0f } ?: 25f, past.elapsedRealtimeNanos)
                .copy(origin = FilterState.Anchor(past.latitude, past.longitude),
                      movingMode = false, settleCount = 0, departCount = 0, prevNetMeters = 0f)
        }

        // Stage 1.5 — NLP authenticity
        val looksLikeNlp = !fix.hasSpeed && !fix.hasBearing
        val dtSec = (fix.elapsedRealtimeNanos - past.elapsedRealtimeNanos) / 1e9f
        val distMoved = Haversine.metres(past.latitude, past.longitude, fix.latitude, fix.longitude)
        val calcSpeed = if (dtSec >= 1f) (distMoved / dtSec).toFloat() else 0f
        val bypass = calcSpeed > c.speedVehicularMin &&
            (fix.elapsedRealtimeNanos - s.lastHwVehicularNanos) < c.nlpBypassWindowMs * 1_000_000L
        if (looksLikeNlp && fix.accuracy > c.accuracyNlpReject && !bypass)
            return reject(fix, s, "NLP Fallback")
        // NOTE: no negative-dt branch. dtSec cannot be negative — elapsedRealtimeNanos is
        // monotonic. Boot boundaries are caught upstream in FixIngestor (EC-29).

        // Stage 2 — motion state       Stage 3 — physical sanity
        // Stage 4 — tiered recovery    Stage 5 — 3-sigma gate + forced reset
        // Stage 6 — heuristic branches Stage 7 — Q/R, persistence, heartbeat routing
        …
    }
}
```

**Constants** live in one `TrackerConstants` data class so the fixture harness can sweep them; `Default` holds the production values. Only live constants are ported — the Gen-1 leftovers at `LocationUtil.kt:51-61` are not ([A18](SOURCE-AUDIT.md)).

```kotlin
data class TrackerConstants(
    val burstMs: Long = 500,
    val signalGapSec: Float = 110f,
    val recoveryTimeoutSec: Float = 900f,
    val heartbeatSec: Float = 900f,
    val speedStationaryMax: Float = 0.3f,
    val speedWalkingMin: Float = 0.6f,
    val speedVehicularMin: Float = 3.0f,
    val speedGpsTrust: Float = 1.5f,
    val speedVirtuallyStopped: Float = 2.0f,
    val speedMaxPhysicalKmph: Float = 140f,
    val distMinMove: Double = 10.0,
    val distJitter: Double = 15.0,
    val distStationaryWobble: Double = 40.0,
    val distRecoveryWakeup: Double = 100.0,
    val distRecoveryVehicular: Double = 200.0,
    val distGpsRecoveryLarge: Double = 400.0,
    /** Net-displacement persistence. `persistAdvanceM` replaced the old 20 m per-fix
     *  `persistGrowMargin`, which was a statement about SAMPLING CADENCE rather than
     *  about movement: one second of driving, fifteen of walking, and unclearable on
     *  foot at the 4 s turn-burst tier. Measured against the net high-water mark
     *  instead, it means the same thing at every cadence. (EC-39a) */
    val persistAdvanceM: Double = 5.0,
    val persistConfirmNet: Double = 100.0,
    val persistDepartCount: Int = 2,
    val settleFixesToExit: Int = 2,
    val recoveryImmediateDist: Double = 150.0,
    val recoveryConfirmNear: Double = 60.0,
    val accuracyHigh: Float = 40f,
    val accuracyMedium: Float = 70f,
    val accuracyStationaryLimit: Float = 40f,
    val accuracyMaxVehicular: Float = 85f,
    val accuracyNlpReject: Float = 25f,
    val nlpBypassWindowMs: Long = 10 * 60 * 1000L,

    /** Process noise as an ACCELERATION spectral density, m/s² — the constant-velocity
     *  model's unit. Renamed from the scalar filter's `q*` set, and every value
     *  re-derived, because the meaning changed with the model (§4, EC-44a). Values are
     *  what a vehicle's acceleration actually varies by between fixes: a motorway cruise
     *  barely changes speed, city driving brakes and accelerates hard. */
    val qAccelHighway: Float = 0.25f,
    val qAccelVehicular: Float = 0.6f,
    val qAccelStationary: Float = 0.02f,          // pins the filter to its anchor (EC-38)
    val qAccelMovingPoorAccuracy: Float = 0.2f,   // poor fix ⇒ SMALLER noise ⇒ tighter gate
    val qAccelMovingGoodAccuracy: Float = 0.45f,
    val qAccelDefault: Float = 0.3f,
    val qAccelTurning: Float = 2.0f,              // lateral; CORRECTION only, never the gate (EC-45a)
    val qTurnMinDeltaDeg: Float = 25f,            // filter heading vs measured, not a rate
) { companion object { val Default = TrackerConstants() } }
```

---

## 6. The ingestor — one consumer, no shared state

Fixes [A3](SOURCE-AUDIT.md), [A5](SOURCE-AUDIT.md), [A6](SOURCE-AUDIT.md) and EC-52 in one place.

```kotlin
package com.field360.tracker.core.capture

internal class FixIngestor(
    private val store: PointStore,
    private val pipeline: AcceptancePipeline,
    private val motion: MotionController,
    private val events: MutableSharedFlow<TrackerEvent>,
    private val scope: CoroutineScope,
) {
    private val channel = Channel<TrackFix>(capacity = 256, onBufferOverflow = DROP_OLDEST)

    private var state: FilterState = FilterState()
    private var past: TrackPoint? = null
    private var sessionId: String? = null
    private var lastElapsedNanos = 0L

    /** Every source calls this: stream, one-shot, backstop, manual insert. */
    fun offer(fix: TrackFix) { channel.trySend(fix) }

    suspend fun start(session: TrackSession) {
        sessionId = session.id
        state = store.loadFilterState() ?: FilterState()   // A2 / EC-51
        past = store.lastPoint(session.id)
        scope.launch { for (fix in channel) consume(fix) }  // SINGLE consumer
    }

    private suspend fun consume(fix: TrackFix) {
        val sid = sessionId ?: return   // EC-73: late fix after stop()

        // EC-29 / EC-92: elapsedRealtimeNanos resets across reboot.
        if (fix.elapsedRealtimeNanos < lastElapsedNanos) {
            state = FilterState()
            past = store.lastPoint(sid)
            events.emit(TrackerEvent.Diagnostic("Reboot Boundary"))
        }
        lastElapsedNanos = fix.elapsedRealtimeNanos

        motion.onRawFix(fix)          // liveness clock updates pre-filter (EC-70)
        val result = pipeline.accept(fix, past, state)
        state = result.state.copy(lastFixElapsedNanos = fix.elapsedRealtimeNanos)
        store.saveFilterState(state)
        store.recordDecision(result.decision)
        events.emit(TrackerEvent.LocationRejected(result.decision).takeIf { result.point == null }
            ?: TrackerEvent.Location(result.point!!))
        result.point?.let { past = it; store.insert(it) }
    }

    suspend fun stop() { channel.close(); sessionId = null }
}
```

---

## 7. Location sources — batching, staleness, fallback

```kotlin
package com.field360.tracker.core.provider

internal object LocationRequests {
    fun stream(cfg: GeolocationConfig, vehicular: Boolean): LocationRequest =
        LocationRequest.Builder(if (vehicular) cfg.vehicularIntervalMs else cfg.intervalMs)
            .setPriority(cfg.desiredAccuracy.toPriority())
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setWaitForAccurateLocation(true)
            .setMinUpdateDistanceMeters(cfg.distanceFilterM)      // 0f — EC-119
            .setMinUpdateIntervalMillis(cfg.fastestIntervalMs)
            .setMaxUpdateDelayMillis(cfg.maxUpdateDelayMs)        // batching → EC-31
            .setMaxUpdateAgeMillis(cfg.maxFixAgeMs)
            .build()

    fun oneShot(cfg: GeolocationConfig): CurrentLocationRequest =
        CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_PERMISSION_LEVEL)
            .setMaxUpdateAgeMillis(5_000L)
            .setDurationMillis(cfg.oneShotTimeoutMs)
            .build()
}
```

The callback iterates the **whole batch** — the single most impactful capture fix ([A4](SOURCE-AUDIT.md)):

```kotlin
private val callback = object : LocationCallback() {
    override fun onLocationResult(result: LocationResult) {
        result.locations                                   // NOT lastLocation
            .sortedBy { it.elapsedRealtimeNanos }
            .forEach { loc -> FixMapper.map(loc, clock)?.let(ingestor::offer) }
    }
    override fun onLocationAvailability(a: LocationAvailability) {
        if (!a.isLocationAvailable) events.tryEmit(ProviderChange(fixAvailable = false))
    }
}
```

`FixMapper` is the only place `android.location.Location` is touched, and it is where every validity rule lives:

```kotlin
internal object FixMapper {
    private const val MAX_DELIVERY_AGE_MS = 60_000L

    fun map(l: Location, clock: Clock, policy: MockPolicy): TrackFix? {
        val ageMs = (clock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000L
        if (ageMs > MAX_DELIVERY_AGE_MS) return null                       // EC-33
        if (l.latitude == 0.0 && l.longitude == 0.0) return null           // EC-24
        if (l.latitude.isNaN() || l.longitude.isNaN()) return null         // EC-25
        if (l.latitude !in -90.0..90.0 || l.longitude !in -180.0..180.0) return null
        val mock = if (SDK_INT >= 31) l.isMock else @Suppress("DEPRECATION") l.isFromMockProvider
        if (mock && policy == MockPolicy.REJECT) return null               // EC-28
        return TrackFix(
            timeMs = l.time,
            elapsedRealtimeNanos = l.elapsedRealtimeNanos,                  // A1
            receivedAtElapsedNanos = clock.elapsedRealtimeNanos(),
            latitude = l.latitude, longitude = l.longitude,
            accuracy = l.accuracy.coerceIn(1f, 10_000f),                    // EC-23
            altitude = if (l.hasAltitude()) l.altitude else null,
            verticalAccuracy = if (SDK_INT >= 26 && l.hasVerticalAccuracy())
                l.verticalAccuracyMeters else null,
            speedMps = if (l.hasSpeed()) l.speed else 0f,
            bearingDeg = if (l.hasBearing()) l.bearing else 0f,
            hasSpeed = l.hasSpeed(),                                        // A8 — no defaults
            hasBearing = l.hasBearing(),
            provider = l.provider ?: "unknown",
            isMock = mock,
        )
    }
}
```

A `LocationSource` interface abstracts fused vs. platform `LocationManager` so EC-19 (no Play Services) is a swap, not a branch everywhere. Both implementations ship: `FusedLocationSource` and `PlatformLocationSource`, with `RoutingLocationSource` picking between them from `GeolocationConfig.providerType` (§11). Two behavioural differences the pipeline sees on the platform source — **no batching** (`LocationManager` has no `maxUpdateDelay`, so every emitted list has one member) and **no `waitForAccurateLocation`** (the first fix after registration is delivered as-is, which is why the accuracy meter is applied to it rather than assumed).

---

## 8. Foreground service

```kotlin
class TrackingService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // EC-64: OS restart delivers a null Intent — reconstruct from persisted state.
        val action = intent?.action ?: Action.RESUME
        if (!promoteToForeground()) return START_NOT_STICKY
        when (action) { … }
        return START_STICKY                                    // A14 — never STICKY_COMPATIBILITY
    }

    /** @return false if the OS refused; the service has already stopped itself. */
    private fun promoteToForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notificationFactory.build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        true
    } catch (e: Exception) {
        // API 31+: ForegroundServiceStartNotAllowedException (backgrounded).
        // API 34+: SecurityException (location is foreground-only; FGS may only START
        //          from an eligible state even when the permission is granted).
        // Stop cleanly to honour the start-foreground contract and avoid the follow-up
        // "did not call startForeground" ANR. RestoreWorker re-promotes later. EC-62.
        logger.w(TAG, "startForeground(location) refused: ${e.message}")
        events.tryEmit(TrackerEvent.Error(ErrorCode.FGS_START_REFUSED, e.message.orEmpty()))
        stopSelf(); false
    }
}
```

Health loop (2 min, from `AttendanceLoggerService.kt:1041`), watchdog (60 s alarm, actions throttled to 15 min), backstop (`PeriodicWorkRequest` 15 min, linear backoff, 30 s fix timeout), restore worker (expedited one-shot), boot receiver (`BOOT_COMPLETED` + `MY_PACKAGE_REPLACED`, EC-65/EC-67).

Force-capture is **in-process** — no `startForegroundService` from a receiver ([A13](SOURCE-AUDIT.md)):

```kotlin
internal object CaptureBus { val forceCapture = MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
// ActivityRecognizer:  CaptureBus.forceCapture.tryEmit(Unit)
// TrackingService:     lifecycleScope.launch { CaptureBus.forceCapture.collect { oneShot() } }
```

---

## 9. Room schema

```kotlin
@Database(
    entities = [TrackSessionEntity::class, TrackPointEntity::class, RawFixEntity::class,
                FixDecisionEntity::class, ActivitySegmentEntity::class, FilterStateEntity::class],
    version = 4, exportSchema = true,        // EC-83 — schemas committed, real migrations only
    // v1->v2 bearingDeg on the capture tables · v2->v3 filter_state.lastCapturedBearingDeg
    // (EC-45) · v3->v4 the constant-velocity state (EC-44a). All additive, all
    // hand-written; NONE has a MigrationTestHelper test yet — see BUILD.md §7.
)
@TypeConverters(TrackerConverters::class)
internal abstract class TrackerDatabase : RoomDatabase() {
    abstract fun points(): TrackPointDao
    abstract fun sessions(): TrackSessionDao
    abstract fun decisions(): FixDecisionDao
    abstract fun filterState(): FilterStateDao
    abstract fun activity(): ActivitySegmentDao

    companion object {
        // EC-84: name is package-scoped so a host app's own Room DB can never collide.
        fun name(ctx: Context) = "traker-${ctx.packageName}.db"
    }
}

@Entity(
    tableName = "track_point",
    indices = [Index("timeMs"), Index("sessionId", "timeMs"),
               Index("localDate"), Index(value = ["uuid"], unique = true)],
    foreignKeys = [ForeignKey(TrackSessionEntity::class, ["id"], ["sessionId"], CASCADE)],
)
data class TrackPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,                    // = sha1(sessionId + elapsedRealtimeNanos) — EC-82
    val sessionId: String,
    val timeMs: Long,
    val elapsedRealtimeNanos: Long,
    val localDate: String,
    val timezone: String,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double?,
    val speedMps: Float,
    val bearingDeg: Float,
    val hasSpeed: Boolean,
    val hasBearing: Boolean,
    val provider: String,
    val isMock: Boolean,
    val movementStatus: String,
    val detectedActivity: String?,
    val activityStartTimeMs: Long,
    val odometerMeters: Double,
    val batteryPct: Int?,
    val isCharging: Boolean?,
    val addressJson: String?,
    val extras: String?,
    val acceptReason: String,
    val syncState: Int = 0,              // 0 pending / 1 synced — used only by fieldtrack-sync
    val syncTimeMs: Long = 0,
)

@Dao
interface TrackPointDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)          // EC-82
    suspend fun insert(p: TrackPointEntity): Long

    @Query("SELECT * FROM track_point WHERE sessionId = :s ORDER BY elapsedRealtimeNanos DESC LIMIT 1")
    suspend fun last(s: String): TrackPointEntity?

    @Query("SELECT * FROM track_point WHERE timeMs BETWEEN :from AND :to ORDER BY timeMs LIMIT :limit OFFSET :offset")
    suspend fun range(from: Long, to: Long, limit: Int, offset: Int): List<TrackPointEntity>   // EC-80

    @Query("SELECT * FROM track_point WHERE sessionId = :s ORDER BY timeMs")
    fun observe(s: String): Flow<List<TrackPointEntity>>

    @Query("SELECT COUNT(*) FROM track_point WHERE timeMs BETWEEN :from AND :to")
    suspend fun count(from: Long, to: Long): Int

    /** EC-81: never prune rows belonging to an open session. */
    @Query("""DELETE FROM track_point WHERE timeMs < :cutoff AND sessionId IN
              (SELECT id FROM track_session WHERE endedAtMs IS NOT NULL)""")
    suspend fun prune(cutoff: Long): Int
}

@Entity(tableName = "filter_state")
data class FilterStateEntity(
    @PrimaryKey val id: Int = 1,         // single row
    val lat: Double, val lng: Double, val variance: Float, val elapsedNanos: Long,
    val originLat: Double?, val originLng: Double?,
    val departCount: Int, val prevNetMeters: Float,
    val movingMode: Boolean, val settleCount: Int,
    val recoveryLat: Double?, val recoveryLng: Double?,
    val lastHwVehicularNanos: Long, val lastFixElapsedNanos: Long,
    val motionState: String, val stopPendingSinceNanos: Long,
    val lastCapturedBearingDeg: Float = -1f,                      // v2 -> v3 (EC-45)
    val velocityNorthMps: Float = 0f, val velocityEastMps: Float = 0f,   // v3 -> v4
    val covPosVel: Float = 0f, val varianceVel: Float = 25f,             // (EC-44a)
)
```

`track_session`, `raw_fix` (debug ring, `persistRawFixes`), `fix_decision` (TTL + count capped, EC-87) and `activity_segment` follow the same shape. Full DDL in `schema/1.json` once Phase 2 lands.

---

## 10. Public API

```kotlin
package com.field360.tracker

object Tracker {
    fun init(app: Application)
    suspend fun ready(config: TrackerConfig): TrackerState
    suspend fun setConfig(edit: TrackerConfig.Builder.() -> Unit): TrackerState
    suspend fun reset(): TrackerState
    val state: StateFlow<TrackerState>

    suspend fun start(tag: String? = null): TrackerResult<TrackSession>
    suspend fun stop(): TrackerResult<TrackSession?>
    suspend fun changePace(moving: Boolean)
    /** Fresh snapshot only; does not persist or feed the tracking pipeline. */
    suspend fun getCurrentLocation(): TrackerResult<TrackFix>

    fun observePoints(q: PointQuery): Flow<List<TrackPoint>>
    suspend fun getPoints(q: PointQuery): List<TrackPoint>
    suspend fun getCount(q: PointQuery): Int
    suspend fun insertPoint(p: TrackPoint): TrackerResult<Long>
    suspend fun deletePoints(q: PointQuery): Int
    suspend fun getOdometerMeters(): Double
    suspend fun resetOdometer()

    data class TrackerGeofence(
        val id: String,
        val latitude: Double,
        val longitude: Double,
        val radiusM: Float,
        val onEnterEvent: String = "stationary_fence_enter",
        val onExitEvent: String = "stationary_fence_exit",
    ) {
        companion object {
            const val DEFAULT_ID = "fieldtrack-stationary"
            const val DEFAULT_ENTER_EVENT = "stationary_fence_enter"
            const val DEFAULT_EXIT_EVENT = "stationary_fence_exit"
        }
    }

    suspend fun addGeofence(geofence: TrackerGeofence): TrackerResult<TrackerGeofence>
    fun getGeofences(): List<TrackerGeofence>
    fun getGeofence(id: String = TrackerGeofence.DEFAULT_ID): TrackerGeofence?
    suspend fun removeGeofence(id: String = TrackerGeofence.DEFAULT_ID): TrackerResult<Boolean>
    suspend fun removeAllGeofences(): TrackerResult<Int>
    fun getGeofenceEvents(
        geofenceId: String? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
        limit: Int = 500,
        offset: Int = 0,
    ): List<TrackerGeofenceEvent>
    fun deleteGeofenceEvents(
        geofenceId: String? = null,
        fromMs: Long? = null,
        toMs: Long? = null,
    ): Int

    val currentSession: StateFlow<TrackSession?>
    suspend fun getSessions(from: Long? = null, to: Long? = null): List<TrackSession>

    // ── plotting ───────────────────────────────────────────────
    suspend fun buildTrack(q: TrackQuery, opts: TrackOptions = TrackOptions()): Track
    suspend fun exportPolylineJson(q: TrackQuery, opts: TrackOptions = TrackOptions()): String
    suspend fun exportGeoJson(q: TrackQuery, opts: TrackOptions = TrackOptions()): String

    /** Optional road snapping; defaults to RoadSnapProvider.Disabled, under which
     *  buildTrack() never leaves the device and never emits a snap_unavailable warning.
     *
     *  A setter rather than a Hilt binding on purpose: a @Binds in core would force every
     *  host wanting its own provider to fight a duplicate binding, and a default a host
     *  CANNOT override is worse than no default. It also keeps the HTTP client and the
     *  API key in the host's artifact, where they belong. (PLAN.md §5) */
    fun setRoadSnapProvider(provider: RoadSnapProvider)

    // ── permissions & provider ─────────────────────────────────
    suspend fun requestPermission(activity: Activity, level: PermissionLevel): PermissionResult
    fun providerState(): StateFlow<ProviderState>
    fun batteryInfo(): BatteryInfo
    fun batteryState(): StateFlow<BatteryInfo>
    fun isIgnoringBatteryOptimizations(): Boolean
    fun requestIgnoreBatteryOptimizations(activity: Activity)

    // ── device ─────────────────────────────────────────────────
    /** Incumbent parity, plus a derived motionQuality the SDK acts on. SDK-COMPARISON §6. */
    suspend fun getSensors(): DeviceSensors
    suspend fun getDeviceInfo(): DeviceInfo

    // ── diagnostics ────────────────────────────────────────────
    fun decisions(): Flow<FixDecision>
    suspend fun getDecisions(q: DecisionQuery): List<FixDecision>
    suspend fun exportFixture(q: PointQuery): String
    suspend fun replayFixture(json: String): List<FixDecision>

    /** replay 0, unlimited subscribers. Collect from any host scope; the service
     *  collects it too, so custom work still runs with no UI on screen. EC-112. */
    val events: SharedFlow<TrackerEvent>
}

sealed interface TrackerResult<out T> {
    data class Ok<T>(val value: T) : TrackerResult<T>
    data class Error(val code: ErrorCode, val message: String) : TrackerResult<Nothing>
}

enum class ErrorCode {
    NOT_READY, PERMISSION_DENIED, BACKGROUND_PERMISSION_MISSING, COARSE_ONLY,
    LOCATION_DISABLED, PLAY_SERVICES_UNAVAILABLE, FGS_START_REFUSED, NOTIFICATION_HIDDEN,
    FIX_TIMEOUT, STORAGE_FULL, STORAGE_RESET, TRACKER_DEAD, INVALID_CONFIG, NO_ACTIVITY,
    MOTION_DETECTION_DEGRADED,       // motionQuality = POOR — see SDK-COMPARISON §6.5
    SNAP_UNAVAILABLE,                // provider installed but could not answer; NEVER
                                     // fatal — raw geometry + a snap_unavailable warning
}

data class DeviceSensors(
    val accelerometer: Boolean, val gyroscope: Boolean, val magnetometer: Boolean,
    val significantMotion: Boolean, val stepDetector: Boolean, val stepCounter: Boolean,
    val barometer: Boolean, val rotationVector: Boolean,
    val motionQuality: MotionQuality,
)
enum class MotionQuality { FULL, DEGRADED, POOR }

sealed interface TrackerEvent {
    data class Location(val point: TrackPoint) : TrackerEvent
    data class LocationRejected(val decision: FixDecision) : TrackerEvent
    data class MotionChange(val state: MotionState, val point: TrackPoint?) : TrackerEvent
    data class ActivityChange(val activity: ActivityType, val confidence: Int) : TrackerEvent
    data class EnabledChange(val enabled: Boolean) : TrackerEvent
    data class ProviderChange(val state: ProviderState) : TrackerEvent
    data class Heartbeat(val at: Long) : TrackerEvent
    data class PowerSaveChange(val enabled: Boolean) : TrackerEvent
    data class GeofenceAdded(val geofence: TrackerGeofence) : TrackerEvent
    data class GeofenceRemoved(val geofenceId: String) : TrackerEvent
    data class GeofenceEntered(val geofence: TrackerGeofence) : TrackerEvent
    data class GeofenceExited(val geofence: TrackerGeofence) : TrackerEvent
    data class SessionInterrupted(val session: TrackSession) : TrackerEvent   // EC-66
    data class Diagnostic(val message: String) : TrackerEvent
    data class Error(val code: ErrorCode, val message: String) : TrackerEvent
}

data class ProviderState(
    val gpsEnabled: Boolean,
    val networkEnabled: Boolean,
    val permission: PermissionTier,        // NONE | FOREGROUND_ONLY | FULL
    val accuracyAuthorization: Accuracy,   // PRECISE | APPROXIMATE
    val fusedAvailable: Boolean,
    val powerSaveMode: Boolean,
)
```

Java callers get a `TrackerJava` facade with `…Async(Callback<T>)` twins for every `suspend` function, and `addListener`/`removeListener` in place of the `SharedFlow`.

---

## 11. Configuration

```kotlin
data class TrackerConfig(
    val geolocation: GeolocationConfig = GeolocationConfig(),
    val motion: MotionConfig = MotionConfig(),
    val service: ServiceConfig = ServiceConfig(),
    val persistence: PersistenceConfig = PersistenceConfig(),
    val plotting: PlottingConfig = PlottingConfig(),
    val sensors: SensorConfig = SensorConfig(),
    val logger: LoggerConfig = LoggerConfig(),
    /**
     * true  (default) — ready() applies this config on top of factory defaults.
     * false           — the persisted config wins and THIS OBJECT IS IGNORED after the
     *                   first launch; only setConfig() can change anything thereafter.
     *
     * Leave true during development. A false here with edited constants is the classic
     * "my config changes do nothing" bug. Matches the incumbent's default and semantics
     * — see SDK-COMPARISON.md §5.
     */
    val reset: Boolean = true,
) {
    fun validate(): List<String>          // EC-77, EC-120, EC-121 — fails ready() fast
    class Builder { … }
}

data class GeolocationConfig(
    val trackingMode: TrackingMode = TrackingMode.ADAPTIVE,
    /** Which hardware produces the fixes — FUSED (default), GPS_ONLY, NETWORK_ONLY,
     *  PASSIVE. Orthogonal to desiredAccuracy, which only biases the FUSED provider's
     *  own choice among the sources it has and cannot exclude any of them. The three
     *  non-fused values run on the platform LocationManager and need no Play Services,
     *  which is the remedy EC-19 previously had none for. */
    val providerType: LocationProviderType = LocationProviderType.FUSED,
    val desiredAccuracy: DesiredAccuracy = DesiredAccuracy.HIGH,
    /** The accuracy meter — how good a fix must be before it is STORED. */
    val accuracy: AccuracyConfig = AccuracyConfig(),
    /** ⚠ MUST stay 0. A non-zero OS distance filter is a stationary-drift GENERATOR:
     *  the OS only wakes you when noise exceeds the filter, so every update looks like
     *  movement. All thinning is done in software, deliberately. (EC-119) */
    val distanceFilterM: Float = 0f,
    val intervalMs: Long = 60_000,
    val fastestIntervalMs: Long = 30_000,
    val maxUpdateDelayMs: Long = 60_000,
    val maxFixAgeMs: Long = 10_000,
    val deliveryStalenessMs: Long = 60_000,
    val adaptiveCadence: Boolean = true,
    val vehicularIntervalMs: Long = 12_000,
    /** Third cadence tier: sampling while the vehicle is measurably TURNING (EC-45).
     *  Adaptive cadence is a guess about the whole drive — fast everywhere, because the
     *  turns could be anywhere. This spends the battery only where the geometry is, and
     *  geo/motion/TurnDetector decides when that is. Off leaves the two-tier behaviour. */
    val turnBurst: Boolean = true,
    val turnBurstIntervalMs: Long = 4_000,   // validated <= the tier it accelerates
    val oneShotTimeoutMs: Long = 30_000,
    val mockLocationPolicy: MockPolicy = MockPolicy.FLAG,
)

enum class LocationProviderType { FUSED, GPS_ONLY, NETWORK_ONLY, PASSIVE }

/** Named points on the accuracy meter; CUSTOM takes maxAccuracyMeters. */
enum class AccuracyProfile { STRICT, BALANCED, RELAXED, CUSTOM }

data class AccuracyConfig(
    val profile: AccuracyProfile = AccuracyProfile.BALANCED,
    val maxAccuracyMeters: Float? = null,      // required by CUSTOM, rejected otherwise
    val recoveryTrustMeters: Float? = null,    // overrides the profile's re-anchor bar
) {
    val maxAccuracyM: Float                    // STRICT 20 / BALANCED 30 / RELAXED 60
    val recoveryTrustM: Float                  // STRICT 15 / BALANCED 25 / RELAXED 40
}
```

### The accuracy meter (§11.1)

A ceiling on the reported error radius of a fix claimed to be **moving** — the one
unconditional bound in the acceptance pipeline. Every other accuracy limit in the engine is
conditional on a motion class that the fix's own displacement helps decide, which is
circular: a 66 m positioning error computes as ~14 m/s, ~14 m/s reads as vehicular, and
vehicular carries the loosest ceiling there is. That circle is how a field capture stored a
153 m spike and a 173° reversal inside an ordinary city drive (EC-139).

`AccuracyTuning` is the **only** place `TrackerConfig` moves a number inside
`TrackerConstants`, and it moves exactly three:

| constant | set from | note |
|---|---|---|
| `accuracyMovingMax` | `accuracy.maxAccuracyM` | the meter itself |
| `accuracyRecoveryTrust` | `accuracy.recoveryTrustM` | clamped to ≤ the moving ceiling (EC-140) |
| `accuracyNlpReject` | `accuracy.maxAccuracyM` | **only** under `NETWORK_ONLY` — see below |

Deliberately untouched: `accuracyHigh`, `accuracyMedium`, `accuracyStationaryLimit`,
`accuracyMaxVehicular` (these *classify* a fix rather than admit it — dragging them along
would re-tune the motion state machine as a side effect of a storage decision), and the
whole sigma-gate family (a fix's error radius and the filter's disagreement with it are
independent evidence). Stationary fixes are exempt by design: they are handled by the anchor
and wobble defences, so tightening this to fix stationary drift is tuning the wrong stage
(spec §8.1, §8.3).

`BALANCED` reproduces the shipped constants byte-for-byte, so the meter changes nothing for
an existing host until it opts in.

Two combinations are refused by `validate()` rather than allowed to present as an empty
track: `NETWORK_ONLY` under a ceiling below 50 m (a GNSS-calibrated ceiling rejects every
fix a Wi-Fi/cell centroid can produce), and `PASSIVE` with `navigationMode` (passive
requests nothing of its own, so there is no cadence to set). Under `NETWORK_ONLY` the EC-32
25 m network bound is lifted to the host's own ceiling — that bound exists because on a
fused stream a network fix is what a Wi-Fi teleport arrives as, and on `NETWORK_ONLY` it is
what *every* fix arrives as.

### `TrackerConfig.Builder` (§11.2)

The `data class` constructor is unchanged and remains the idiomatic Kotlin route. The
builder exists for Java, where the alternative is positionally constructing five nested
classes with ~60 parameters between them and taking a source break on every added field.

```kotlin
val config = TrackerConfig.builder()
    .provider(LocationProviderType.GPS_ONLY)
    .accuracyProfile(AccuracyProfile.STRICT)   // or .maxAccuracyMeters(35f), which implies CUSTOM
    .useSignificantMotion(true)
    .useStepCorroboration(true)
    .build()                                   // throws IllegalArgumentException on validate() errors
```

`build()` is the one fail-fast entry point in the SDK, and deliberately so: everything
reachable from `Tracker` returns a typed `TrackerResult` because it runs inside a host's
coroutine where a throw is an unpreventable crash, whereas this runs on the host's own
thread while it is assembling a value. `buildUnchecked()` returns the same value unvalidated
for hosts assembling config from untrusted input.

```kotlin
data class MotionConfig(
    val activityRecognition: Boolean = true,
    val activityRecognitionIntervalMs: Long = 10_000,   // incumbent parity (min 500)
    val activityConfidenceMin: Int = 75,                // incumbent parity (Android)
    val snapshotConfidenceMin: Int = 50,                // one-shot seed only
    val disableStopDetection: Boolean = false,          // incumbent parity
    val stopOnStationary: Boolean = false,              // incumbent parity: stop() on timeout
    val stopTimeoutMin: Int = 5,
    val stationaryRadiusM: Float = 150f,
    val stationaryGeofenceId: String = TrackerGeofence.DEFAULT_ID,
    val stationaryGeofenceOnEnterEvent: String = TrackerGeofence.DEFAULT_ENTER_EVENT,
    val stationaryGeofenceOnExitEvent: String = TrackerGeofence.DEFAULT_EXIT_EVENT,
    val motionTriggerDelayMs: Long = 0,                 // incumbent parity (Android-only)
    /** DATA-plane heartbeat: warms the filter but is NOT stored — this is what makes a
     *  2-hour steady user produce exactly one point. Distinct from the control-plane
     *  TrackerEvent.Heartbeat. (EC-48, SDK-COMPARISON §3) */
    val heartbeatIntervalSec: Int = 900,
    val persistHeartbeat: Boolean = false,
    /** Store a point whenever the heading has turned this far since the last STORED one,
     *  regardless of what the speed and distance gates decided; 0 disables. This is the
     *  half of turn fidelity adaptive cadence cannot cover: at 12 s a corner taken at
     *  25 km/h falls between two samples that are each individually unremarkable, so both
     *  survive the gates and the polyline draws the chord across the corner. Comparing
     *  headings is what makes the ANGLE itself a reason to keep a point.
     *  Surfaces as `Reasons.BEARING_CHANGE` in the decision log. (EC-45) */
    val bearingChangeCaptureDeg: Int = 40,
)

/** See SDK-COMPARISON.md §6. Sensors are registered only while a session is active. */
data class SensorConfig(
    /** Permission-free, ~zero-power hardware wake for STATIONARY → MOVING (EC-132). */
    val useSignificantMotion: Boolean = true,
    /** Step-count veto on stationary drift / confirmation of indoor walks (EC-133). */
    val useStepCorroboration: Boolean = true,
    /** 1 s accelerometer burst to make the phantom-Doppler correction certain (EC-134). */
    val useAccelerometerVeto: Boolean = true,
    /** Pressure delta distinguishes an elevator from a teleport after a gap (EC-135). */
    val useBarometer: Boolean = false,
    /** Sensor-hub batching so the AP never wakes for step events. */
    val stepBatchLatencyMs: Long = 60_000,
)

data class ServiceConfig(
    val foregroundService: Boolean = true,
    val notification: NotificationConfig,       // required when foregroundService
    val stopOnTerminate: Boolean = false,       // EC-125
    val startOnBoot: Boolean = true,
    val healthLoopMs: Long = 120_000,
    val watchdogIntervalMs: Long = 60_000,
    val watchdogThrottleMs: Long = 900_000,
    val backstopIntervalMin: Int = 15,
    val deadTrackerMovingMin: Int = 30,
    val deadTrackerStationaryMin: Int = 60,
    val wakeLockMs: Long = 20_000,
)

data class PersistenceConfig(
    val maxDaysToPersist: Int = 7,
    val maxRecords: Int = 0,
    val persistRawFixes: Boolean = false,
    val rawRingCapacity: Int = 5_000,
    val persistDecisions: Boolean = true,
    val decisionRetentionDays: Int = 3,
    val decisionMaxRows: Int = 50_000,
)

data class PlottingConfig(
    val consolidateStops: Boolean = true,
    val stopRadiusM: Float = 60f,
    val stopMinDwellSec: Int = 600,
    val smoothing: Smoothing = Smoothing.SPLINE,  // NONE | BEZIER | SPLINE (EC-45b)
    val splineSpacingM: Double = 5.0,
    val bezierMinAngleDeg: Float = 30f,
    val bezierCutbackM: Float = 25f,
    val polylinePrecision: Int = 6,
    val speedBandsKmph: FloatArray = floatArrayOf(10f, 20f),
    val arrowMinSegmentM: Float = 60f,
)

enum class TrackingMode { CONTINUOUS, ADAPTIVE, MOTION_ONLY }
```

Config is persisted in DataStore and restored by `ready()` unless `reset = true` — same contract as BGGeo, so a team migrating recognises it.

---

## 12. Plotting output

The geometry runs entirely in `fieldtrack-geo`:

```
points → consolidateStops(stationary only, 60 m / 10 min)
       → significantNodes(100 m, gap-stop protection)
       → clusters(travel/dwell, split at dwell gaps, duration-weighted p75)
       → labelActivities(AR label, speed-bucket override, STILL override)
       → snap?(Snapper) → smooth(Spline | BezierRounding | none)
       → arrows(zoom) → encodePolyline → stats
```

`TrackOptions.smoothing` defaults to `Smoothing.SPLINE`: centripetal Catmull-Rom through
every vertex, resampled at 5 m. `Smoothing.BEZIER` is the previous behaviour and still
available, but it rounds *vertices* turning more than 30° and leaves every leg a chord —
no help at all when a 12 s cadence at 10 m/s puts vertices 120 m apart, which is the usual
complaint. Rounding the joins does not fix the legs.

Two things about where the smoothed path goes. Segment polylines slice out of it; they
used to slice the pre-smoothing path, so `Track.encodedPolyline` was the only geometry
rounding ever reached and a host drawing per-segment speed bands saw none of it. Arrows
deliberately still anchor to the unsmoothed vertices: their spacing ladder thins by
distance (EC-106a), so a path resampled every 5 m roughly doubles the count and lets vertex
density decide arrow density instead of zoom. That costs no accuracy, because Catmull-Rom
*interpolates* — every original vertex lies exactly on the drawn curve, so the arrows sit
on the line rather than beside it (EC-45b).

It is a rendering transform and says so. Between two fixes 120 m apart the curve is an
assumption about a road nobody measured; a snapped path is returned untouched, and
map-matching stays the real answer.

Two of those stages care whether the device was *moving*, and both learned to the hard way.

`consolidateStops` collapses a dwell to one node. It used to collapse anything: membership
was tested against a running centroid, which trails the newest point by about half the
group's span, so a vehicle laying points 40 m apart stayed inside the 60 m radius for two
or three fixes at a time. Each of those runs became a single vertex. On a field capture it
deleted 13 of 28 stored points, every one of them moving at 5–10 m/s, and the drawn line
jumped between the survivors. A moving point now never joins a dwell, and the radius is
measured from where the group *started* rather than from a mean that follows it (EC-139).

`clusters` classifies the span it is handed, and a span can *contain* a stop that was never
sampled — the acceptance gates go quiet once a device settles, so the stop is a hole
between two fixes that both belong to the drive. Net displacement across that hole clears
100 m, so no whole-span rule can find it. A **dwell gap** (`Δt ≥ 180 s` and implied speed
`≤ 35 m/min`) splits the span instead. Implied speed rather than a radius, because the fix
that catches a departure is rarely the one at the kerb; a genuine blackout at 8 m/s stays
travel and EC-99 still owns it (EC-140).

`SignificantNodes` has a guard that reads like this one and is not: gap-stop protection
asks whether a silence should stop a *node cluster* ending, and suppresses a boundary. The
dwell gap asks whether a silence *is* a stop, and creates a segment. They share the speed
bar — `Clusters.DWELL_GAP_MPS` is derived from `SignificantNodes.GAP_SPEED_M_PER_MIN`, not
restated — but not the duration.

A `stop` segment carries no geometry, so **the drawn line breaks across it** — see
[POLYLINE-JSON.md](POLYLINE-JSON.md) before rendering.

`TrackBuilder.build()` is **synchronous and pure, including the snap stage.** It takes
road geometry the caller already fetched — not a provider it may call. `Tracker` does the
`suspend` round-trip and hands the result down as a value, which is what keeps the HTTP
client out of `fieldtrack-geo` and lets every rule in `Snapper` be tested against a
hand-written road with no server.

**Matched geometry is cached per chunk.** `buildTrack` asks the provider every call, and
a host drawing a live map calls it on every accepted fix — so without a cache a
twenty-minute drive at the burst cadence re-matches the whole trace a few hundred times.
Caching whole traces would not help, because a growing track is a different trace each
time; caching the 90-coordinate chunks does, because everything before the last one is
unchanged. Failures are not cached: empty means timeout or rate limit, and freezing that
into a permanently raw stretch of road is worse than one retry (EC-100a).

**Snapping is opt-in twice over:** `TrackOptions.snapToRoad` defaults `true`, but with no
`RoadSnapProvider` installed nothing is fetched, nothing is warned about, and the output
is byte-identical to before the feature existed. Set it `false` to keep raw geometry even
when a provider *is* installed — useful when auditing a track against the fixes actually
captured.

Three rules govern the merge, each pinned to the failure it prevents:

- **EC-101** — a fix more than `snapMaxOffRoadM` (80 m) from the returned road keeps its
  captured position. Parallel service roads, elevated expressways over surface streets and
  tunnel exits all return geometry a couple of hundred metres from the truth; snapping to
  it puts the user on the wrong street with total confidence.
- **EC-101** — road geometry is injected into a leg only when the points at *both* ends
  were themselves snapped. Protected bookends and host markers are never moved (EC-103)
  and cannot anchor an injected span either.
- **EC-102 / [A11](SOURCE-AUDIT.md)** — the closest-point search returns an **index** and
  scans forward from the previous match. The reference's `path.indexOf(start)` is value
  equality on doubles, so a roundabout or an out-and-back spur returns the *first* visit
  and the sub-path comes back empty or reversed.

Segment polylines and arrow anchors are sliced out of the snapped path by source index, so
coloured spans and arrows follow the same line the track polyline draws — the divergence
class [A9](SOURCE-AUDIT.md) is about.

Anything the provider could not answer degrades to raw geometry plus a `snap_unavailable`
warning and a `SNAP_UNAVAILABLE` event. The track is never lost because a routing service
was (EC-100).

```kotlin
// TrackOptions, snap-related knobs
val snapToRoad: Boolean = true,       // no provider installed ⇒ no-op, no warning
val snapMaxOffRoadM: Double = 80.0,   // EC-101
```

```kotlin
data class Track(
    val version: Int = 1,
    val sessionId: String?,
    val generatedAtMs: Long,
    val from: Long, val to: Long,
    val timezone: String,
    val bounds: Bounds?,                 // null when 0 points (EC-93)
    val stats: TrackStats,
    val encodedPolyline: String,
    val precision: Int,                  // EC-110 — explicit, never assumed
    val points: List<TrackJsonPoint>,
    val segments: List<TrackSegment>,    // type = TRAVEL | STOP
    val stops: List<StopNode>,
    val arrows: List<ArrowAnchor>,       // precomputed: lat, lng, bearing, segment
    val warnings: List<String>,
)

/** THE feature that makes "plot with arrow" trivial for a host.
 *  One implementation, used by fieldtrack-maps AND by the JSON export (A9/EC-108). */
data class ArrowAnchor(val lat: Double, val lng: Double, val bearing: Double, val segment: Int)
```

Wire format, spacing rules and the GeoJSON mapping are specified in [POLYLINE-JSON.md](POLYLINE-JSON.md).

---

## 13. Manifest (merged from the AAR — host app adds nothing)

```xml
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="com.google.android.gms.permission.ACTIVITY_RECOGNITION" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<!-- Host opts in explicitly; never auto-requested (EC-15) -->
<!-- <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" /> -->

<service
    android:name="com.field360.tracker.core.service.TrackingService"
    android:exported="false"
    android:foregroundServiceType="location"
    android:permission="android.permission.FOREGROUND_SERVICE_LOCATION"
    android:stopWithTask="false" />

<receiver android:name="…core.work.BootReceiver" android:exported="false">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
    </intent-filter>
</receiver>
<receiver android:name="…core.motion.ActivityTransitionReceiver" android:exported="false" />
<receiver android:name="…core.motion.StationaryFenceReceiver" android:exported="false" />
```

Every receiver declares `android:exported` explicitly — the reference has one that relies on the default (`AndroidManifest.xml:462`).
