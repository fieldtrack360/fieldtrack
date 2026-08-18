# Review — third-party EKF location-tracking SDK design document

Source: an externally authored SDK design document, shared for review.
Reviewed 2026-07-30 against the production reference implementation and the field-verified spec in [capture-and-plotting-spec.md](capture-and-plotting-spec.md).

**Overall:** structurally sound as a *project* document — module layout, testing tiers, docs plan, release process and privacy section are all reasonable and Traker keeps several of them. As an *engineering* design for background location on Android it is unsound: the core filter choice is wrong for the sampling rate, and the entire stationary-drift problem — the single hardest thing this domain has — is absent. A build following it would demo well and fail in the field.

Findings ordered by severity.

---

## Critical

### C1 — A CTRV Extended Kalman Filter is the wrong model at this cadence

> *"EkfEngine uses a constant-turn-rate-and-velocity (CTRV) motion model to estimate smooth position/velocity/bearing from noisy GNSS data."*

CTRV carries a 5-dimensional state `(x, y, v, ψ, ψ̇)`. The turn-rate `ψ̇` is only observable if you sample fast enough to see the turn develop — automotive fusion runs CTRV at 10–100 Hz against IMU data. This SDK samples GNSS at, per its own config, an interval governed by `distanceFilterMeters = 0f` and no stated interval; the comparable production system samples at **60 s** (30 s fastest).

At 60 s a car at 40 km/h covers ~660 m between fixes. The turn-rate state is not merely noisy, it is **unobservable**: any `ψ̇` consistent with the two endpoints is equally likely, so the filter's turn estimate is driven by process noise. The predicted position then curves in an invented direction, the next real fix lands far from the prediction, the innovation gate rejects it, and the estimate lags further. That is the classic post-turn rejection cascade — the document has no mechanism to break it.

The production system uses a deliberately **scalar** Kalman filter — one variance shared by lat and lng, no velocity state — and puts all the sophistication into per-fix Q/R tuning. That is not naivety; it is the correct response to sparse, unmodelled dynamics. Cheap, stable, and it cannot invent a turn it did not observe.

If the goal is genuine turn geometry, the answer is more samples (adaptive cadence at speed) plus map-matching at render time — not a richer motion model over the same sparse data.

> **Editorial note, added after implementation.** This section is left as written — it is a review of a third-party design and the argument against CTRV still holds. But Traker no longer ships the scalar filter this paragraph endorses, and the reasoning is worth separating:
>
> - The objections above are to **CTRV specifically**: a turn-rate state `ψ̇` that is unobservable at 60 s, so the prediction curves in an invented direction. Traker now uses **constant velocity**, which carries no turn state and can only extrapolate a straight line. Neither objection transfers.
> - The premise moved. This was written against 60 s sampling; the prescription in the last line — *more samples at speed* — shipped as adaptive cadence and the turn-burst tier, and 12 s (4 s while turning) is what makes velocity observable.
> - What this review did not anticipate is that the scalar filter has a defect of its own. With no term for a moving target it lags a fixed amount every fix; on a straight 40 km/h road that cost **one rejected fix in four**, each recovered by a forced reset that visibly jumped the track. See [API.md](../API.md) §4 and EC-44a.
>
> The recommendation in §Verdict below should now read "replace the CTRV EKF with a constant-velocity Kalman + staged acceptance pipeline".

### C2 — No defence against stationary drift, at all

The pipeline is:

> `Timestamp → Accuracy → Speed → Bearing → Activity Recognition → GNSS Quality → EKF → Duplicate & Outlier → Confidence → Persist`

Nothing in that chain addresses the dominant field failure: **a phone that is not moving does not produce a constant coordinate.** Multipath indoors makes every fix a random draw inside a 20–80 m circle; plotting them draws a random walk, and the user's map shows them pacing around a building they never left.

Five distinct mechanisms are needed and none are present:

| Mechanism | What it kills | Present in document |
|---|---|---|
| Hardware-stationary classification (`hwSpeed < 0.3 m/s`) — trust the chip's Doppler, never position deltas | Misclassifying noise as movement | ✗ |
| Wobble guard (40 m, 80 m when the fix has no hardware speed) | Small drift promoted to "walking" | ✗ |
| Anchor R-penalty `R *= clamp(dist/5, 1, 100)` when virtually stopped | The single most important stationary fix — freezes the estimate at the anchor | ✗ |
| `Q = 0.0001` while hardware-stationary | Keeps the gate tight so drift excursions are rejected rather than averaged in | ✗ |
| Net-displacement persistence (upload only after net > 100 m or two monotonic growth steps) | Slow drift-loops that individually pass every gate | ✗ |
| Heartbeat suppression (one fix per 15 min, filtered but **not stored**) | The stationary blob, server-side | ✗ |

An accuracy gate (`maxHorizontalAccuracy = 50f`) does not help here: a drift fix indoors routinely reports 8–15 m accuracy and is *wrong by 40 m*. Reported accuracy and truth are different quantities.

### C3 — A stateless validator chain cannot express the required logic

> *"Each stage modeled as a pure `FixValidator` function, returning `ValidationResult.Accept` or `ValidationResult.Reject(reason)`"*
> `private val fixValidators = listOf(TimestampValidator(…), AccuracyValidator(…), /* ... */)`

Two problems.

**Order is load-bearing, and a `listOf` implies it is not.** The burst gate must run before anything updates the last-fix clock; the network-fix check must run before motion-state determination (a network fix has no speed and would masquerade as stationary); recovery must run before the outlier gate or a post-gap fix burns the reject counter. A list of independent predicates invites reordering, and reordering silently changes behaviour.

**Several stages are not predicates.** They are state machines spanning fixes:
- *tiered recovery* holds a candidate and requires a **second** fix within 60 m to confirm it;
- *net-displacement persistence* accumulates `departCount` and `prevNetMeters` across fixes and latches `movingMode`;
- *forced reset* counts consecutive rejections and re-seeds after N;
- *settle detection* counts stationary fixes to exit moving-mode.

None can be written as `(Fix) -> ValidationResult`. The correct signature is `(fix, past, state) -> (verdict, newState)`, which is what Traker uses.

### C4 — No network-positioning (NLP) rejection, and no speed/bearing validity handling

When GNSS is weak, Android silently substitutes WiFi/cell-database fixes that can teleport 50–500 m and back. They are identified by having **no hardware speed and no hardware bearing** with medium accuracy — they arrive "fresh", so timestamp gates never catch them.

The document's `LocationPointEntity` stores only `lat, lng, timestamp, confidence, activityState`. `hasSpeed` / `hasBearing` are not captured at all, so this class of false update cannot be detected — not at capture, and not retrospectively from the stored data either. This is the same trap the reference spec calls out as the single bug that reproduces the "steady user drifts" symptom.

### C5 — No gap-recovery logic

Elevators, basements, tunnels and airplane mode produce multi-minute silences; the first fix afterwards is often junk. Accept it and the user teleports; reject everything and the filter wedges at a stale position forever.

The production answer is tiered: immediate re-seed only on strong evidence (≥ 150 m, or gap + vehicular + 200 m), otherwise **hold** the fix — warm the filter but store nothing — until a second fix lands within 60 m. Plus a forced reset after N consecutive rejections so the filter can never wedge permanently.

The document has neither a recovery path nor an un-wedge mechanism. "Duplicate & Outlier Detection" as a single unnamed stage is not a substitute.

---

## Significant

### S1 — "Confidence score 0–1" is not a usable abstraction

> *"`ConfidenceScorer` computes a final 0–1 score from various quality signals… Lets clients filter out fixes below their use-case-specific threshold."*
> `confidenceThresholds: mapOf(STILL to 0.7f, WALKING to 0.8f, …)`

Collapsing independent, differently-shaped failure modes into one scalar destroys the information needed to act. "0.62" tells a developer nothing. "REJECT — NLP Fallback" tells them the OS substituted a WiFi fix. The reason vocabulary *is* the debugging language, and it is what makes fixture-replay regression tests possible.

The thresholds are also stated with no derivation. Why 0.7 for STILL and 0.9 for IN_VEHICLE? Nothing in the document says how those numbers were obtained or how a team would re-tune them. Compare the reference constants, each of which has a documented physical rationale and a named symptom it prevents.

Pushing this to the client is worse: it makes every consumer responsible for a tuning problem they cannot observe.

### S2 — Defaults are wrong for a background tracking SDK

```kotlin
val stopOnTerminate: Boolean = true,
val startOnBoot: Boolean = false,
val maxHorizontalAccuracy: Float = 50f,
```

- `stopOnTerminate = true` + `startOnBoot = false` means tracking silently dies when the user swipes the app away or reboots — for an SDK whose purpose is background tracking, the defaults disable the product. In fairness both are inherited verbatim from the incumbent SDK, where they are a deliberate conservative choice its documentation tells you to flip; this document copies them without saying so. Traker inverts both and documents the inversion ([SDK-COMPARISON.md §4](../SDK-COMPARISON.md)). (`distanceFilterMeters = 0f` is correct, and notably the only default that is.)
- A flat 50 m accuracy ceiling is wrong in both directions: too loose for a stationary user (the reference uses 40 m) and too tight while driving (85 m, because fast legitimate displacement needs headroom). Accuracy ceilings must vary by motion state.

### S3 — Activity Recognition used as a validation gate

The pipeline lists *"Activity Recognition Validation"* as a filtering stage. AR is laggy (tens of seconds), frequently `UNKNOWN`, and demonstrably wrong — the reference code documents entire 17-minute drives during which AR reported `STILL` on OnePlus and Xiaomi devices under battery saver.

Gating capture on AR means those drives are not recorded at all. AR belongs where the reference puts it: **enrichment** (a label stamped on the point, and a trigger for one extra fix at a motion transition), never a veto. The plotting layer then applies a STILL-override when measured motion contradicts the label.

### S4 — Entity schema cannot support the pipeline or reprocessing

```kotlin
data class LocationPointEntity(
    val id: String, val sessionId: String, val lat: Double, val lng: Double,
    val timestamp: Long, val confidence: Float, val activityState: String,
)
```

Missing: `accuracy`, `speed`, `bearing`, `hasSpeed`, `hasBearing`, `provider`, `isMock`, `altitude`, `elapsedRealtimeNanos`. Without accuracy the Kalman update has no R. Without the validity flags, C4 is unfixable. Without a monotonic timestamp, every delta is vulnerable to wall-clock changes.

It is also unrecoverable: you cannot re-run an improved filter over historical data, so every tuning change requires new field recordings.

### S5 — Hilt inside an SDK, next to "minimize external dependencies"

> *"Standard … MVVM conventions: … Hilt for DI"* and, two sections later, *"Minimize external dependencies to avoid version conflicts in client projects"*.

These contradict. Hilt is an annotation processor plus a Gradle plugin plus a runtime; forcing it on every consumer is exactly the version-conflict problem the other bullet is trying to avoid. An SDK should use constructor injection and a single manual composition root, with zero DI framework in its public surface.

> **Superseded for Traker (2026-07-31).** This finding was overruled by an explicit product decision: Traker uses Hilt *inside* `fieldtrack-core`, not only in the sample. The criticism above still stands on its merits — every consuming app now inherits the Hilt runtime, must apply the Hilt Gradle plugin, and must annotate its `Application` with `@HiltAndroidApp`, and an app on a different DI framework cannot consume the SDK without adopting Hilt. The trade was accepted for consistent constructor injection across the `domain`/`data`/`service` layering. Recorded in [PLAN.md](../PLAN.md) §0. The rest of S5 — keeping Retrofit and any HTTP client out of core — is unchanged and still enforced: `fieldtrack-core` never touches the network.

Similarly `RouteSnapshotApi` pulls Retrofit + OkHttp + a converter into every consumer for a feature many will not use. That belongs in an optional artifact.

### S6 — Callback API is single-consumer

```kotlin
class LocationTrackingClient(
    var locationCallback: LocationCallback? = null,
    var trackingStateCallback: TrackingStateCallback? = null,
    …
)
```

`var callback` means the second registrant silently replaces the first. Two subscribers is the normal case, not an edge case: a screen observing live position while an application-scoped collector handles background work, or a debug overlay attached alongside production code. Whichever registers second silently kills the first, and the failure is invisible. A `SharedFlow` (or at minimum an add/remove listener list) is required.

### S7 — No plotting, despite the stated objective

The stated goal is *"smooth, reliable route polylines"*, but the only rendering guidance is *"Track rendering via GoogleMap Compose, observing a Room-backed repository as a `Flow<List<LocationPointEntity>>`"* — i.e. draw a line through the stored points.

That produces straight lines cutting every corner, no stop consolidation (a 2-hour dwell renders as a cloud of dots), no direction indication, no travel/dwell segmentation, no speed colouring — and it pushes all of that onto every consuming app, each of which then reimplements it slightly differently. The plotting plane is roughly half the work in this domain and it is one sentence here.

---

## Minor, but worth naming

| # | Issue |
|---|---|
| M1 | *"Duty cycling (e.g. 1 min on, 9 min off)"* — a 9-minute blind window destroys track fidelity and creates exactly the signal gaps that recovery logic exists to survive. Motion-gated shutdown (off while genuinely still, on while moving) achieves the battery goal without the data loss. |
| M2 | *"Zero background kills per hundred hours"* as a success metric is not achievable on Android. MIUI, ColorOS and One UI will kill the process; the honest goal is fast, automatic recovery, and a metric like "median recovery time after kill < 90 s". |
| M3 | *"Median location confidence > 0.8"* is self-referential — the SDK computes the number it is graded on. |
| M4 | *"90th percentile outlier displacement < 100 m"* has no stated measurement method. Against what ground truth? |
| M5 | *"< 5 % battery drain per hour"* needs a stated device, mode and screen state to mean anything. |
| M6 | Adoption metrics ("10+ third-party apps", "thousands of MAU", "no increase in churn") are product goals, not engineering plan. Harmless, but they occupy the space where a phased delivery plan with dependencies should be. |
| M7 | *"Foreground `LocationTrackingService` (not WorkManager)"* is right, but there is no mention of `ForegroundServiceStartNotAllowedException` (API 31+), the API 34 `SecurityException` for location-typed FGS, `foregroundServiceType`, `POST_NOTIFICATIONS`, or the two-stage background-permission ladder. These are where background-location SDKs actually break. |
| M8 | No `elapsedRealtimeNanos`. `fixFreshnessMs = 10_000` implies comparing wall-clock timestamps, which is wrong on clock changes and on batched delivery. |
| M9 | No mention of `LocationResult.getLocations()` vs `lastLocation`, so batched fixes will be dropped — the same defect the reference implementation has ([A4](../SOURCE-AUDIT.md)). |
| M10 | Privacy section is good and Traker adopts its spirit (no transmission without opt-in; the SDK is offline-first by default). "Geofenced private zones" is a genuinely good idea worth adding to the roadmap. |

---

## What Traker takes from it

Not everything here is wrong, and some of it is better organised than the reference:

- **Session as a first-class entity** (`LocationSessionEntity`, `sessionId` on every point) — adopted.
- **`FixValidator` with a `Reject(reason)` type** — the *reason* idea is right and matches the reference's reason vocabulary. Traker keeps the typed verdict, drops the stateless-list structure (C3).
- **Testing tiers** (unit / integration / device, in-memory Room, MockWebServer, manufacturer-specific battery tests) — adopted almost verbatim, plus fixture replay.
- **Docs plan** (KDoc + Dokka to GitHub Pages, developer guide, compelling example app) — adopted.
- **Semantic versioning + automated publication** — adopted, private-registry variant.
- **Privacy posture** — adopted and strengthened by making the SDK offline-first.
- **"Minimize external dependencies"** — adopted as a hard rule, which is why Hilt and Retrofit stay out of the core (S5).

## Verdict

Follow the project-management half; discard the filtering design. Specifically: keep sessions, typed reject reasons, the testing tiers, the docs and release plan, and the privacy stance. Replace the CTRV EKF with the scalar Kalman + staged acceptance pipeline, add the six stationary-drift defences, add NLP rejection and gap recovery, capture the full fix schema, make AR enrichment rather than a gate, and flip `stopOnTerminate` / `startOnBoot`.
