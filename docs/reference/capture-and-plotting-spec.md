# Employee Location Tracking & Plotting — Cross-Platform Implementation Specification

**Target:** Android (native Kotlin)
**Source:** Production-proven native Android implementation (values verified against source, 2026-07)
**Audience:** Implementation team. This document is self-contained: every algorithm, constant, payload shape, and rendering rule needed to implement the system is included. Verify §28 (checklist) against your backend before coding.

> **Provenance note.** This document was originally written targeting React Native across iOS and Android. Traker is Android-only, so the React Native and iOS material has been removed: the cross-platform library mapping, the iOS strategy section, and the TypeScript module layout. Every algorithm, constant, threshold and rendering rule is unchanged from the field-verified original — those are the parts Traker ports. §26 keeps the platform-agnostic pitfalls, which are the ones that actually reproduce the §8 symptoms.

---

## Executive Summary — Accuracy Profile & How the Reference Implementation Handles It

The reference implementation is a native Android tracker hardened over three generations of field use (§5). Its accuracy characteristics, which this spec reproduces:

| Scenario | Achieved behavior |
|---|---|
| **Steady user** (office, home — hours at a stretch) | The track shows **exactly one plotted point** for the whole stationary period; nothing syncs while steady. No drift cloud, no phantom pacing. Achieved by trusting the GNSS chip's speed over position deltas, freezing the filter with an anchor noise-penalty, and suppressing stationary uploads entirely. |
| **Walking** | Points accepted only with accuracy better than 40–70 m; a trip registers only after net displacement exceeds ~100 m from the departure anchor (or grows monotonically twice) — wandering around a room never plots as travel. |
| **Driving** | Gates widen proportionally to speed, so fast legitimate displacement is never rejected; fixes up to 85 m accuracy are usable. Turn geometry is reconstructed at render time by snapping travel segments onto the road network — corners come from the map, not from sparse samples. A 30-min urban drive typically stores ~25–35 points. |
| **Signal gaps** (elevator, basement, tunnel, airplane mode) | The first fix after a gap is never trusted alone: it either passes a strong-evidence immediate reset or is *held* until a second fix within 60 m confirms it. Post-gap teleports do not plot. |
| **False updates** | Nine distinct noise classes (callback bursts, network-positioning teleports, stale cache replays, impossible speeds, multipath spikes, phantom speed readings, drift loops, post-gap jumps, clock skew) each have a dedicated gate — see the triage table in §8.3. |

**Honest bounds:** raw per-fix accuracy is set by the device GNSS — typically 3–10 m outdoors with clear sky, 10–30 m in urban canyons, 20–80 m indoors. The pipeline cannot improve a single fix; its job is (a) to prevent that error envelope from ever being *displayed* as false movement, and (b) to reconstruct plausible geometry between honest samples. It also cannot recover events that fall entirely between samples (a U-turn completed within one 60 s interval) — that is a sampling-cadence limit, with an optional adaptive-cadence upgrade described in §8.2.

**How the reference implementation handles it, end to end** (each step detailed in the body):

1. **Session-gated capture** — tracking runs only during an explicit duty session (punch-in → punch-out), inside a persistent foreground service. The OS supplies a continuous high-accuracy stream every 60 s (fastest 30 s) with **no OS-level distance filter**: all thinning is done in software, deliberately, because OS distance filters turn stationary noise into fake movement.
2. **Per-fix gauntlet** — every fix passes staleness gates, then a 7-stage acceptance pipeline built around a lightweight scalar Kalman filter: burst rejection → fix-authenticity check → motion-state classification → physical sanity → gap recovery → adaptive 3-sigma outlier gate (with a forced reset so it can never wedge) → per-motion-state heuristic acceptance with adaptive process/measurement noise.
3. **Store-then-sync** — accepted points are written to a local queue and synced immediately; failures stay queued and retry. Stationary periods intentionally sync nothing (liveness is judged from the raw fix clock, never from sync recency).
4. **Self-healing** — a 60 s watchdog, a 15-min backstop capture job, and a restore path keep the pipeline alive across process death, reboots, and aggressive OEM battery managers.
5. **Semantic plotting** — the viewer re-derives meaning from stored points: stop consolidation (60 m / 10 min centroids) → significant-node detection → travel clusters with duration-weighted speed statistics → activity labels → road-snapped, speed-colored polyline with numbered stop markers and a dwell/travel timeline.

This summary assumes **no specific backend or third-party service** — the transport shapes in §11 and the routing calls in §22 describe *semantics* to be mapped onto whatever backend the app talks to (§28 checklist).

---

## Table of Contents

- Executive Summary (above) — accuracy profile and end-to-end handling at a glance
- Part A — Capture (§1–§13): how an individual employee's location is captured, filtered, stored, uploaded
- **§8 — Accuracy Playbook: stationary drift, turns, false updates (start here if debugging)**
- Part B — Plotting (§14–§25): how an individual employee's day is fetched, processed, snapped and rendered
- Part C — implementation pitfalls (§26), consolidated config (§27), verification checklist (§28)

---

## 1. System Overview

Two independent planes share one server:

```
CAPTURE PLANE (employee device)                      PLOTTING PLANE (viewer device)
┌─────────────────────────────────┐                 ┌─────────────────────────────────┐
│ Session start ──> tracking on   │                 │ Open employee day view          │
│   ├─ Foreground tracking service│                 │   ├─ Fetch day history          │
│   │   └─ 60s continuous stream  │                 │   ├─ Multi-device attribution   │
│   ├─ 15-min periodic backstop   │    upload       │   ├─ Stop/punch consolidation   │
│   ├─ Activity recognition       │  ─────────>     │   ├─ Significant-node detection │
│   └─ Watchdog (60s liveness)    │                 │   ├─ Cluster/segment build      │
│         │                       │                 │   ├─ Road snapping              │
│   Filter pipeline (Kalman +     │                 │   ├─ Map render (polyline etc.) │
│   7-stage acceptance)           │                 │   └─ Timeline + day summary     │
│         │                       │                 └─────────────────────────────────┘
│   Local DB row ──> sync queue ──┘
└─────────────────────────────────┘
```

Key design decisions:

1. **Capture is gated by session state.** Tracking runs only between session start (punch-in) and session end (punch-out); never capture outside an explicit session, and tear down cleanly (stop stream, reset filter, flush queue) when the session ends.
2. **Filtering happens on-device at capture time.** The server stores already-filtered points; the plotting side assumes clean data and applies only *semantic* processing (stops, segments, snapping).
3. **A single shared Kalman filter state** is consumed by all capture entry points (continuous stream and periodic backstop) so they never fight each other.
4. **Stationary devices intentionally upload nothing** (heartbeat suppression, §12) — liveness is judged from the raw GPS fix clock, never from upload recency.

---

## 2. Cadence & Frequency Reference

All timing knobs in one place. These are the answers to "how often":

| Signal | Cadence | Notes |
|---|---|---|
| Continuous location stream | every **60 s** (fastest **30 s**) | high accuracy, **0 m** distance filter; batching window 60 s; fixes older than 10 s discarded by request config |
| Periodic backstop capture | every **15 min** | background job; single fix with **30 s** timeout; linear retry backoff |
| Service health loop | every **2 min** | checks worker liveness and unsynced rows (see §3.4) |
| Watchdog liveness check | every **60 s** | alarm/reminder driven; fallback actions throttled to once per **15 min** |
| Tracker declared dead | no raw fix for **30 min** (moving) / **60 min** (stationary) | triggers full-screen "re-enable tracking" user nudge |
| Stationary heartbeat | one accepted fix per **≥ 15 min** (900 s) while stationary | **filtered but NOT uploaded** — warms the Kalman state only (§12) |
| Upload | immediate, per accepted point | plus whatever the 15-min backstop captures; failed rows stay queued (`syncTime = 0`) |
| Re-sync nudge | last row unsynced or last sync **≥ 16 min** ago | performed by health loop / watchdog |
| Activity-recognition capture | event-driven | activity transition forces an immediate extra fix |
| Plotting refresh | **no polling** | fetch on open / date change / device-filter change; ongoing dwell durations computed against wall clock at render time |
| Map overlay re-render | on camera idle, only when zoom changed by **> 0.5** | arrows only; polylines/markers static |

---

## 3. Capture Lifecycle (State Machine)

### 3.1 Start triggers

| Trigger | Action |
|---|---|
| **Session start / punch-in** (primary) | register the session → start periodic backstop job → start tracking foreground service → schedule 60 s watchdog → register activity-recognition transitions |
| **Process death / OS kill** | one-shot expedited **restore job** re-promotes the foreground service (enqueued by watchdog when it finds the service dead) |
| **Device reboot** | boot receiver restarts the reminder/watchdog chain, which re-promotes the service on its next 60 s tick |

### 3.2 Suppression gates (checked on every capture)

A fix is dropped (not stored, not uploaded) when ANY of:

1. No active tracking session.
2. Location permission revoked (watched live via OS permission-change callbacks → service stops itself cleanly).

### 3.3 Stop triggers

- Session end / punch-out → stop service, clear local location DB, stop backstop job, deregister activity recognition.
- Auth token expiry (HTTP 401 on upload) → same full teardown.
- Explicit stop action; unknown intent action (defensive).

### 3.4 Foreground service & health loop

- The tracking service runs as a **foreground service of type "location"** with a persistent notification (Android). If the OS refuses foreground promotion (background-start restrictions), the service aborts cleanly and the restore job re-promotes it later — never crash-loop.
- **Health loop (every 2 min)** inside the service:
  1. Is the periodic backstop job alive (enqueued/running)? If failed/cancelled and a session is active → restart it.
  2. Is the app foregrounded? → prefer foreground one-shot capture; backgrounded → continuous stream.
  3. Is the newest stored row unsynced (`syncTime == 0`) or last sync ≥ 16 min old? → run the sync queue.
  4. No active session? → stop the service.

### 3.5 Tracker selection

- Background permission granted **and** app in background → **continuous stream provider** (the primary 60 s pipeline).
- Otherwise → one-shot high-accuracy fix, collected once, then provider stopped (repeats on next trigger).

---

## 4. OS Location Request Configuration

Values from the production configuration.

| Request | interval | fastest | distance filter | max fix age | priority | extras |
|---|---|---|---|---|---|---|
| **Continuous stream (primary)** | 60 000 ms | 30 000 ms | 0 m | 10 000 ms | HIGH_ACCURACY | max batch delay 60 s; wait-for-accurate on |
| Low-power stream (defined, currently unused) | 300 000 ms | 10 000 ms | 5 m | 30 000 ms | BALANCED | max batch delay 15 s |
| One-shot current fix | — | — | — | 5 000 ms | HIGH_ACCURACY | 30 s duration limit |
| Quick fix | 0 | — | — | 0 | HIGH_ACCURACY | 20 s duration, max 1 update, wait-for-accurate |
| Cached last-location | — | — | — | 15 000 ms | permission-level granularity | — |

**Staleness gates (applied on top of request config):**
- Any fix older than **60 s** at delivery is rejected outright (both providers).
- One-shot requests time out after **30 s** → capture attempt fails (backstop job retries with linear backoff).

Note: the OS request is **fixed** — there is no per-motion-state modulation at the request layer. All motion adaptation happens inside the filter (§6–§7). A speed-based high/low-power mode switch exists in the reference code but is disabled; do not port it.

---

## 5. Filtering Methodology — Evolution & Advancements

Understanding the generations explains *why* the current pipeline looks the way it does:

**Gen 1 — distance/time filters (legacy, still used for auxiliary stamping only):**
accept a point if it moved > 50 m, or > 90 m outdoors / 70 m indoors, or 15 min elapsed. Problems: GPS drift indoors produced phantom movement ("stationary blob"), urban-canyon jumps passed the distance gate, and slow walking was swallowed.

**Gen 2 — scalar Kalman filter + heuristic acceptance:**
a lightweight 2-value (lat/lng) Kalman filter with a single scalar variance smooths the track; heuristic branches accept/reject per motion state. Solved smoothing, still let network-provider (cell/WiFi-positioning) fallback fixes and long-gap outliers through.

**Gen 3 — current pipeline (implement this):** the Kalman core plus seven hardening layers:
1. **Burst rejection** — ignore fixes < 500 ms apart.
2. **Network-fix (NLP) authenticity check** — fixes with no hardware speed *and* no bearing are treated as network-derived; rejected when accuracy > 25 m unless a vehicular bypass applies.
3. **3-sigma gate with forced reset** — a predicted-position gate that widens with speed and rejects outliers, but force-resets after N consecutive rejects so the filter can never wedge permanently.
4. **Tiered recovery** — after signal gaps, either immediately re-seed (clear evidence of travel) or hold one fix and require a second confirming fix within 60 m.
5. **Net-displacement persistence** — an origin anchor that suppresses drift-loops: movement uploads only after net displacement from the anchor exceeds 100 m or grows monotonically twice.
6. **Arrival-transition detection** — recognizes "walked during a signal blackout, now stationary at a new place" and accepts the new anchor.
7. **Stationary heartbeat suppression** — while stationary, one fix per 15 min is *filtered* (keeps the Kalman state warm) but *not uploaded*, eliminating the stationary blob server-side.

Plotting-side advancements (Part B): road-snapper V1 (per-point veto + turn anchors) → hybrid experiment (Kalman pre-smooth + per-segment routing + plausibility veto) → **V2 (production: snap + both-ends-on-road gate + Bézier corner rounding)**, plus a Rauch-Tung-Striebel smoother prototype (kept as reference, not wired — see §22.4).

---

## 6. Kalman Filter (Exact Math)

A deliberately simple **scalar** Kalman filter — one shared variance for both coordinates, no velocity state. Cheap, stable, and all sophistication lives in the per-fix Q/R tuning (§7 Stage 7). Port as-is.

> **Superseded in Traker.** This document records the reference implementation as field-verified, and this description of it stays accurate. Traker ported the scalar filter as instructed and then replaced it: at the faster cadences Traker samples at, a position-only filter lags a moving target by a fixed amount every fix, which cost one rejected fix in four on a straight road. The replacement is **constant velocity** — position plus an inferred velocity — with the gate measuring against whichever prediction is closer, so a corner is still judged exactly as described here. The Q/R tuning in §7 Stage 7 is unchanged apart from `q` becoming an acceleration. See [API.md](../API.md) §4 and EC-44a.

### 6.1 State

```
q                 // process-noise rate, metres/second — constructor value 5.0
lat, lng          // filtered position (degrees)
variance          // P, metres²; -1 == uninitialised sentinel
timestampMs       // of last processed fix
minAccuracy = 1   // metres, accuracy floor
// session extensions (all must be reset together):
lastHwVehicularMs         // last time a hardware fix showed vehicular speed
consecutiveRejectCount
originLat/Lng, originSet  // net-displacement anchor (§7 Stage 7)
departCount, prevNetMeters, movingMode, settleCount
recoveryPendingLat/Lng/Active
```

### 6.2 Operations

```
setState(lat, lng, accuracy, tMs):        // hard (re)seed
    this.lat = lat; this.lng = lng
    variance = accuracy²
    timestampMs = tMs

processFilter(latMeas, lngMeas, accuracy, tMs, q):
    accuracy = max(accuracy, minAccuracy)
    if variance < 0:                       // first fix
        lat = latMeas; lng = lngMeas
        variance = accuracy²; timestampMs = tMs
        return
    // PREDICT
    dtMs = tMs - timestampMs
    if dtMs > 0:
        variance += dtMs * q² / 1000       // process noise grows with elapsed time
        timestampMs = tMs
    // UPDATE
    K = variance / (variance + accuracy²)  // Kalman gain
    lat += K * (latMeas - lat)
    lng += K * (lngMeas - lng)
    variance *= (1 - K)

predictedSigma(tMs):                       // used by the gate, does NOT mutate state
    return sqrt(max(0, variance + (tMs - timestampMs) * q² / 1000))

getAccuracy() = sqrt(variance)

reset():   // on service destroy / full teardown
    variance = -1; lat = lng = 0; timestampMs = 0
    clear ALL session extensions (reject count, origin, movingMode, recovery, hwVehicular)
```

### 6.3 Concurrency requirement

One filter instance is shared by the continuous stream and the 15-min backstop. Route **all** fixes through a single serialized consumer — never let two subscriptions feed the filter concurrently.

---

## 7. Acceptance Pipeline (7 Stages — Full Logic)

Entry point per fix: `accept(fix, past, kalman) → ACCEPT | SKIP | REJECT`
- `fix`: `{lat, lng, accuracy m, hwSpeed m/s, hasSpeed, hasBearing, timeMs}`
- `past`: the previous **stored** point (null on cold start)
- ACCEPT → store + upload; SKIP → filter state was updated ("warmed") but nothing stored; REJECT → dropped.

Derived inputs:

```
timeDeltaSec  = (fix.timeMs - past.timeMs) / 1000
distanceMoved = haversine(past, fix)                    // metres, R = 6 371 000
calcSpeed     = point-to-point speed vs past (0 if Δt < 1 s)
```

### Stage 1 — Burst / cold start / resume

```
if now - lastProcessingTime < 500 ms          → REJECT "Burst"
if kalman.variance < 0 OR past == null:
    if past == null or past == (0,0):
        kalman.setState(fix); kalman.setOrigin(fix)     → ACCEPT "Init"
    else:  // process-death resume: re-seed from last stored point, then judge this fix normally
        kalman.setState(past.lat, past.lng, past.accuracy ?? 25, past.timeMs)
        kalman.setOrigin(past); kalman.clearMovement()
        // fall through — do NOT auto-accept
```

### Stage 1.5 — Network-fix (NLP) authenticity

```
looksLikeNlp = !fix.hasSpeed && !fix.hasBearing
vehicularBypass = calcSpeed > 3.0 && (fix.timeMs - kalman.lastHwVehicularMs) < 10 min
if looksLikeNlp && fix.accuracy > 25 && !vehicularBypass   → REJECT "NLP Fallback"
if timeDeltaSec < 0:  kalman.setState(fix); kalman.setOrigin(fix)  → ACCEPT   // clock went backwards
```

### Stage 2 — Motion-state determination

```
isHardwareStationary = fix.hwSpeed < 0.3
isSignalGap          = timeDeltaSec > 110

effectiveSpeed:
    if isHardwareStationary && calcSpeed > 1.5:   max(hwSpeed, calcSpeed * 0.85)
    elif hwSpeed > 3.0 && calcSpeed < 0.6:        calcSpeed        // phantom-Doppler correction
    else:                                          max(hwSpeed, calcSpeed)

isVehicular = effectiveSpeed > 3.0
wobbleGuard = (isHardwareStationary && !fix.hasSpeed) ? 80 : 40    // metres, indoor GPS wobble
isMoving    = (effectiveSpeed > 0.6 && (effectiveSpeed >= 2.0 || distanceMoved > wobbleGuard))
              || (distanceMoved > 40 && !isHardwareStationary && calcSpeed > 0.3)

// walked away during a signal blackout, now stopped at a new place:
impliedBlackoutSpeed = distanceMoved / timeDeltaSec
isArrivalTransition = isSignalGap && isHardwareStationary && hwSpeed < 0.3
                      && past.movementSpeed >= 1.0
                      && impliedBlackoutSpeed in [0.3, 3.0]
```

### Stage 3 — Physical sanity

```
instantKmph = (distanceMoved / timeDeltaSec) * 3.6
if instantKmph > 140                                     → REJECT "Impossible Speed"
```

### Stage 4 — Tiered recovery (after gaps)

```
// pending-confirm from a previous held recovery:
if kalman.recoveryPendingActive:
    if haversine(fix, kalman.recoveryPending) <= 60:     // confirm
        clear pending; kalman.setState(fix); setOrigin(fix); clearMovement()
        rejectCount = 0                                  → ACCEPT "Recovery Confirmed"
    else: clear pending  // fall through

processingGapSec = (fix.timeMs - kalman.timestampMs)/1000   // vs filter clock, NOT vs upload
isProcessingGap  = processingGapSec > 110
recoveryNeeded = fix.accuracy < 70 && (
       (processingGapSec > 900 && distanceMoved > 100)
    || (isProcessingGap && isVehicular && distanceMoved > 200)
    || (isProcessingGap && distanceMoved > 100 && fix.accuracy < 40) )

if recoveryNeeded:
    immediate = distanceMoved >= 150 || (isProcessingGap && isVehicular && distanceMoved > 200)
    if immediate: kalman.setState(fix); setOrigin(fix); clearMovement() → ACCEPT "Recovery Reset"
    else:         kalman.setState(fix); kalman.setRecoveryPending(fix) → SKIP  "Recovery Held"
```

### Stage 5 — 3-sigma gate

```
sigma          = kalman.predictedSigma(fix.timeMs)
baseGate       = 3*sigma + fix.accuracy*1.5 + 200
speedExpansion = effectiveSpeed * timeDeltaSec * 1.2
maxGate        = isVehicular ? 2500 : isMoving ? 800 : (400 + rejectCount*200)
threshold      = clamp(baseGate + speedExpansion, 50, maxGate)
predictedDelta = haversine(fix, kalman.position)

if predictedDelta > threshold:
    rejectCount++
    maxRejects = isSignalGap ? 2
               : (isHardwareStationary && effectiveSpeed < 2.0) ? 4
               : isVehicular ? 2 : 3
    if rejectCount >= maxRejects:
        if fix.accuracy > 85                             → REJECT "Sigma Junk Fail"
        kalman.setState(fix)          // forced reset — filter can never wedge
        return distanceMoved > 10 ? ACCEPT : REJECT      // "Sigma Forced Reset"
    else                                                 → REJECT "Sigma Gate Outlier"
```

The forced reset is the mechanism that un-wedges a stuck filter — implement it exactly as specified; do not soften its constants.

### Stage 6 — Heuristic acceptance branches

```
if isVehicular:
    maxAcc = isSignalGap ? 85 : looksLikeNlp ? 70 : (hwSpeed < 2.0 ? 50 : 85)
    accept iff fix.accuracy < maxAcc && distanceMoved < 45*timeDeltaSec + 200

elif isMoving:
    accLimit = isArrivalTransition ? 70 : isHardwareStationary ? 40 : 70
    accept iff fix.accuracy < accLimit && (distanceMoved > 10 || timeDeltaSec > 50)

else:  // stationary — accept only for one of these reasons (precedence order):
    isArrival        = past.hwSpeed > 0.5 && hwSpeed < 0.5 && accuracy < 40 && distanceMoved < 40
    isGPSRecovery    = (distanceMoved > 150 && accuracy < 40)
                    || (distanceMoved > 400 && accuracy < 80)
                    || (isSignalGap && distanceMoved > 100 && accuracy < 25)
    isHeartbeat      = timeDeltaSec > 900 && accuracy < 70 && distanceMoved < 100
    isBlackoutArrival= isArrivalTransition && accuracy < 70 && distanceMoved > 40
    isWalkArrival    = kalman.departCount >= 1 && netFromOrigin > 40 && accuracy < 40
    reason = first true of [Arrival, StationaryRecovery(GPSRecovery), BlackoutArrival,
                            WalkArrival, HeartBeat]; none → REJECT "Heuristic Gate"
```

### Stage 7 — Filter processing, Q/R tuning, persistence, heartbeat skip

```
speedKmph     = effectiveSpeed * 3.6
isHighwaySpeed= isVehicular && speedKmph > 45

Q = isHighwaySpeed ? 0.8
  : isVehicular    ? 1.2
  : isHardwareStationary ? 0.0001
  : isMoving       ? (accuracy > 35 ? 0.1 : 0.8)
  : 0.1

R = accuracy
if accuracy > 30: R *= isHighwaySpeed ? 3.0 : 2.5
expectedTravel = effectiveSpeed * timeDeltaSec
driftTolerance = isHighwaySpeed ? max(25, expectedTravel*1.3)
               : isVehicular    ? max(60, expectedTravel*1.3)
               : 50
if predictedDelta > driftTolerance && !isSignalGap:
    R *= clamp(predictedDelta / (isHighwaySpeed ? 15 : 30), 1, isHighwaySpeed ? 15 : 8)
// anchor penalty — kill stationary drift:
isVirtuallyStopped = effectiveSpeed < 2.0
shouldAnchor = isVirtuallyStopped && (
       (isHardwareStationary && distanceMoved > 10)
    || (!isSignalGap && distanceMoved > 15) )
if shouldAnchor: R *= clamp(distanceMoved / 5.0, 1, 100)

// A) NET-DISPLACEMENT PERSISTENCE — only when (isVehicular||isMoving) && !isArrivalTransition && !movingMode:
if !kalman.originSet: kalman.setOrigin(fix); processFilter(...)      → SKIP "Origin Set"
net     = haversine(fix, kalman.origin)
netGrew = net > kalman.prevNetMeters + 20
if net > 40 && netGrew:
    kalman.departCount++; kalman.prevNetMeters = net
    if net > 100 || departCount >= 2:
        kalman.movingMode = true; settleCount = 0        // latched — fall through to upload
    else: processFilter(...)                             → SKIP "Departure Held"
else: kalman.setOrigin(fix); processFilter(...)          → SKIP "Drift Suppressed"

// B) reason routing:
if isArrivalTransition: setOrigin(fix); clearMovement()
if reason == "Stationary Recovery": kalman.setState(fix)             // hard reseed
elif reason == "15-Min Heartbeat":  processFilter(fix, Q, R)         → SKIP "HeartBeat Skipped"
elif reason == "Walk Arrival":      processFilter; setOrigin(fix); clearMovement()
else:                                processFilter(fix, Q, R)

if (fix.hasSpeed || fix.hasBearing) && effectiveSpeed >= 3.0:
    kalman.lastHwVehicularMs = fix.timeMs
rejectCount = 0; lastProcessingTime = now                            → ACCEPT

// C) on any REJECT while movingMode:
if movingMode && distanceMoved < 15 && hwSpeed < 0.6:
    settleCount++
    if settleCount >= 2: movingMode = false; setOrigin(fix)          // settled — re-anchor
```

**Log every decision** with its reason string (ACCEPT/SKIP/REJECT + reason). The reason vocabulary above is load-bearing for field debugging — keep it identical.

---

## 8. Accuracy Playbook — Stationary Drift, Turns, False Updates

This section maps the three hardest field problems to their root causes and to the exact defenses in this spec. The key insight: **each defense targets one specific noise class**. A partial implementation looks fine in a demo and fails in the field, because the field produces all the noise classes at once.

### 8.1 Symptom: track wanders while the user is steady ("stationary drift" / blob)

**Physics of the problem.** A phone that isn't moving does not produce a constant coordinate. GPS multipath (indoors, near buildings) makes each fix a random draw inside a 20–80 m error circle — plotting raw fixes draws a random walk. Worse, when GPS is weak the OS silently substitutes **network-positioning fixes** (WiFi/cell databases) which can teleport 50–500 m and back. A naive pipeline uploads all of it; the map shows the user "pacing" around a building they never left.

**The defense stack, in firing order** (all seven are required; each catches what the previous one misses):

| # | Defense | Where | What it kills |
|---|---|---|---|
| 1 | **Hardware-stationary detection**: `hwSpeed < 0.3 m/s` classifies the state | Stage 2 | Trust the GNSS chip's Doppler speed, *never* position deltas, to decide "is the user moving". Position deltas while stationary are noise by definition. |
| 2 | **Wobble guard**: while HW-stationary, displacement < **40 m** (< **80 m** when the fix has no hardware speed at all — indoor case) can never classify the state as moving | Stage 2 | Small drift steps promoted to "movement". |
| 3 | **Anchor R-penalty**: when `effectiveSpeed < 2.0` and displacement > 10–15 m, inflate measurement noise `R *= clamp(dist/5, 1, 100)` | Stage 7 | **The single most important stationary fix.** The Kalman output freezes at the anchor point; a 40 m drift fix gets R inflated ×8 and moves the filtered position by ~1–2 m instead of ~20 m. |
| 4 | **Q = 0.0001 while HW-stationary** | Stage 7 | Prediction variance barely grows between fixes → the 3-sigma gate stays *tight* around the anchor → larger drift excursions get rejected outright instead of averaged in. |
| 5 | **Net-displacement persistence**: nothing uploads as "movement" until net displacement from the origin anchor exceeds **100 m**, or grows monotonically (>20 m growth) on **2 consecutive** fixes. Drift that circles back re-anchors the origin (SKIP "Drift Suppressed"). | Stage 7-A | Slow drift-loops that individually pass every gate. This is what stops the "user walked 60 m at 2 am" artifacts — drift wanders out and back; real departure grows monotonically. |
| 6 | **Heartbeat suppression**: while stationary, at most one fix per **15 min** is accepted *into the filter* and it is **not uploaded** | Stage 6/7 | Even a perfectly-filtered stationary point stream is pointless data. Server sees *zero* rows while steady. |
| 7 | **Plot-side consolidation**: 60 m-centroid grouping, dwell ≥ 10 min → a single arrival/departure node pair at the *centroid* | §17 | Whatever residue reaches the server collapses to one plotted point. |

**Acceptance criterion:** a user steady for 2 hours must produce **exactly one plotted point** (the arrival node), not a cloud. During capture, the decision log for that period should be dominated by REJECT "Sigma Gate Outlier" / SKIP "Drift Suppressed" / SKIP "HeartBeat Skipped".

**If you still see drift after implementing the stack, check in this order:**
1. `hasSpeed` / `hasBearing` actually reach the filter. The validity flags live on `android.location.Location` (`hasSpeed()` / `hasBearing()`); any layer that coerces invalid speed to `0` instead of carrying the flag makes every network fix look like a legitimate stationary GPS fix and defeats Stage 1.5 *and* defense #1. This single bug reproduces the exact "steady user drifts" symptom.
2. `distanceFilter` must be **0**. A nonzero distance filter is a stationary-drift *generator*: the OS only wakes you when noise exceeds the filter, so every update looks like movement.
3. Speed units: everything in this spec is **m/s**. A km/h value fed in makes `hwSpeed < 0.3` never true while walking speed noise floats around 1–3.
4. One serialized filter instance (§6.3). Two subscriptions (e.g. the continuous stream *and* the backstop worker) each with their own filter state will interleave and fight; the burst gate catches some of it but not all.
5. Cold-start seeding (§7 Stage 1): after process death or a service restart, `variance = -1`; if you don't re-seed from the last *stored* point, the first drift fix becomes the new "Init" truth and plots a teleport.

### 8.2 Symptom: turns look wrong (corners cut, zig-zag, or post-turn jumps)

Two independent problems hide under "turns look bad" — one at capture, one at plotting. Diagnose which one you have by comparing the *stored points* against the *rendered polyline*: if the stored points already miss the corner, it's capture; if the points are fine but the line is wrong, it's rendering.

**Capture-side causes and defenses:**

1. **Sampling gap** — at a 60 s cadence, a vehicle at 40 km/h travels ~660 m between fixes; a 90° turn simply happens between samples. No filter can recover data that was never captured. The reference system accepted this and repaired it at plot time via road snapping (below). If your product needs true turn geometry, add **adaptive cadence** (an honest improvement over the reference): when `effectiveSpeed > 3 m/s` for 2 consecutive fixes, drop the request interval to 10–15 s; return to 60 s after 2 settle fixes (`movingMode` already gives you the state machine for free, §7 Stage 7-C). Battery cost is negligible because vehicular sessions are short and heartbeat suppression still governs stationary time.
2. **Kalman lag → post-turn rejection cascade.** With naive noise settings, the filter's predicted position continues straight through the turn while the vehicle goes sideways; the next real fix lands far from the prediction, R gets drift-penalized, the state lags further, and the sigma gate starts rejecting *real* fixes — the plotted track goes straight for a kilometer, then teleports. The spec's defenses, all in Stage 5/7 and all mandatory:
   - `speedExpansion = effectiveSpeed × Δt × 1.2` widens the gate proportionally to how far the vehicle *could legitimately* have gone — a turn never exceeds it;
   - `driftTolerance` scales with `expectedTravel = speed × Δt × 1.3` so the R drift-penalty is **not** applied to displacement explained by speed (the comment on this line in the reference source reads: *"prevents R-value over-inflation that causes Kalman state lag and sigma gate cascade"* — that is precisely this bug);
   - vehicular Q is high (1.2; 0.8 highway) so the prediction variance grows fast and the gate stays wide while driving;
   - vehicular `maxRejects = 2` → even if the gate does reject a turn, the forced reset re-seeds on the *second* fix past the corner, bounding the damage to one sample.
3. **Fix-on-turn trigger.** The reference system got a free extra fix at motion changes from activity-recognition transitions (§10). A cheap additional heuristic: when the bearing between consecutive accepted fixes changes by more than ~35–45° at speed, fire a one-shot high-accuracy fix immediately. One extra sample at the apex transforms turn geometry.

**Plot-side causes and defenses:**

1. **Straight lines through sparse points cut corners.** The production answer is **road snapping** (§22.2): the snap service pulls each vehicular segment onto the actual street geometry, so the *road's* corner replaces the missing samples — this is where most of the perceived "turn accuracy" comes from, not from capture. Respect the guards: never move session bookend points, keep raw any point > 80 m off-road, inject road geometry between two points only when both are on-road, fall back to the raw track when the API fails.
2. **Sawtooth at vertices** after snapping (or on dense raw tracks) → the **Bézier corner-rounding pass** (§22.2): at any interior vertex with turn angle > 30°, cut back `min(25 m, 0.4 × adjacent-leg)` on both sides and emit 5 quadratic-Bézier points.
3. If you have **no routing API**, the fallback ladder is: (a) Bézier rounding alone on the raw polyline (cheap, fixes sawtooth, cannot invent the true corner); (b) the offline RTS smoother (§22.4) for whole-day beautification; (c) V1-style *turn anchors* (§22.3) if fixes are dense (≤15 s cadence): insert a synthetic point nudged 15 m along the incoming bearing at 45–135° turns. But for vehicular tracks at 60 s cadence, snapping is the only honest fix.

### 8.3 Symptom: false updates (teleports / spikes / movement that never happened)

Every false update belongs to one of these classes, and each class has a dedicated stage. Use the table as a triage checklist — find which class your artifacts belong to (log the raw fixes!), then verify that specific stage:

| False-update class | Signature in raw data | Killed by |
|---|---|---|
| Duplicate / burst callbacks (OS batches flush several fixes at once; a double subscription double-fires) | 2+ fixes < 500 ms apart, near-identical | Stage 1 burst gate (500 ms) |
| Network-positioning fix (WiFi/cell DB) | no speed, no bearing, accuracy > 25 m, position jumps to a nearby router's registered address and back | Stage 1.5 NLP reject (with the 10-min vehicular bypass so tunnels/parking garages still track) |
| Stale/cached fix replayed on app resume | `fix.timestamp` much older than receipt time | 60 s delivery age gate + 10 s request max-age (§4) — compare against the **fix timestamp**, never the callback time |
| Physically impossible jump (ionospheric glitch, cold-start wild fix) | implied speed > 140 km/h | Stage 3 |
| Multipath spike (urban canyon reflection) | single fix 100–2000 m off, then back to normal | Stage 5 sigma gate (and Stage 4's *held + 60 m confirm* if it follows a gap) |
| Phantom Doppler (chip reports speed while position is static — known on some devices) | hwSpeed 3–8 m/s, displacement ≈ 0 | Stage 2 correction: `hwSpeed > 3 && calcSpeed < 0.6 → use calcSpeed` |
| Slow drift-loop uploads | many small accepted "movements" netting ≈ 0 | Stage 7 net-displacement persistence |
| Post-gap teleport (first fix after elevator/basement/airplane mode) | large jump after Δt > 110 s | Stage 4 tiered recovery — immediate reset only with strong evidence (≥150 m, or vehicular+gap+200 m); otherwise the fix is **held** and needs a second fix within 60 m to confirm. A lone spurious post-gap fix never plots. |
| Clock skew (device time jumped) | negative Δt | Stage 1.5 negative-Δt reseed; use a server-corrected clock for stored `time` (§13) |

Two implementation warnings:

- **Do not reorder the stages.** E.g. the burst gate must run before anything mutates `lastProcessingTime`; recovery must run *before* the sigma gate (a post-gap fix would otherwise burn the reject counter); the NLP check must run before state determination (an NLP fix has no speed and would masquerade as stationary).
- **Do not "tune down" a stage to fix a symptom another stage owns.** Widening the sigma gate because turns get rejected (the Stage-5/7 lag bug, §8.2) also lets multipath spikes through; the correct fix is the drift-tolerance/speed-expansion scaling, not a bigger gate.

### 8.4 Diagnostics — instrument before you tune

1. **Decision log**: every fix → `{time, lat, lng, accuracy, hwSpeed, hasSpeed, hasBearing, verdict, reason}`. The reason vocabulary of §7 is the debugging language; keep it verbatim.
2. **Three-layer debug overlay** on a map screen: raw fixes (gray dots), filter output (blue), uploaded/stored points (green). Nearly every accuracy complaint is diagnosed in seconds by seeing *which layer* the artifact first appears in: raw → OS/config problem (§26.4); filter → stage bug or mis-tuned constant; stored-only → sync/dedup bug.
3. **Log-pattern expectations** per scenario:
   - steady hour: REJECTs and SKIPs ("Drift Suppressed", "HeartBeat Skipped") with ~0 ACCEPTs — many ACCEPT "Moving/Walking" while steady means broken `hasSpeed` flags or missing wobble guard;
   - 30-min urban drive: roughly 25–35 ACCEPTs ("Vehicular"), a handful of REJECTs at signal-poor spots, no "Sigma Forced Reset" — repeated forced resets while driving means the drift-tolerance scaling (§8.2 #2) is missing;
   - walk to lunch and back: "Origin Set" → "Departure Held" → ACCEPTs → "Walk Arrival"/arrival at the far end; on return, the same in reverse. If lunch trips are invisible, persistence thresholds are too high or `calcSpeed` is broken (check Δt handling).
4. **Numeric sanity harness**: replay recorded fix logs (JSON) through the pure pipeline in unit tests; assert verdict sequences. Record once on a real device per scenario (steady indoors, drive with turns, walk, elevator gap) and lock the pipeline's behavior against those fixtures — this is how you tune constants without regressing the other symptoms.

---

## 9. Movement Status & Stop Stamping

Separate from acceptance, each stored point is stamped:

- **`movementStatus`** — `STEADY` vs `MOVING`. If the fix has speed accuracy and hardware speed < 5 m/s, trust hardware: `STEADY` when speed < 0.5 m/s. Otherwise fall back to distance vs last stored point against a 50 m movement filter.
- **`activityStatus`** string = `"<locationType>@<movementStatus>"` (e.g. `gps@moving`, `gps@steady`) — the server stores this verbatim; the plotting side parses it back.
- **Significant stop detection** (used for "should this point show a pin"): stationary when points stay within a **60 m radius for ≥ 10 min** with speed **< 15 km/h**.

## 10. Activity Recognition

- Register OS activity-transition updates for ENTER + EXIT of: `IN_VEHICLE, ON_BICYCLE, WALKING, RUNNING, ON_FOOT, STILL`, plus a one-shot snapshot request at registration (accepted only when type ≠ UNKNOWN and confidence ≥ **50**; modeled as an ENTER, then snapshot updates stopped immediately).
- Events are processed chronologically; event timestamp = wall clock corrected by the elapsed-realtime delta (never trust delivery time).
- **Segments**: ENTER closes any previous open segment and opens `{activityType, startTimeMs, isOngoing}`; EXIT closes the matching one. Segments older than **24 h** are auto-closed on restore.
- **Effect on capture**: an ENTER for a *changed* activity fires a force-capture into the tracking service → one immediate extra fix. Activity does **not** alter the OS request or the Kalman Q/R directly.
- **Stamping**: at store time, the point gets `detected_activity_type` (the segment label active at fix time) and `detected_activity_start_time` (segment start, epoch ms). The plotting side uses these to label travel segments (§21).

## 11. Storage, Upload & Server Contract

Endpoint names below are placeholders — map the *shapes and semantics* onto your backend (§28).

### 11.1 Local point schema (one row per accepted fix)

| Field | Type | Notes |
|---|---|---|
| `id` | int64 auto | local PK |
| `time` | int64 | fix time, **epoch ms** (server-corrected clock) |
| `date` | string | `yyyy-MM-dd` local |
| `latitude`, `longitude` | double | WGS84 |
| `accuracy` | float | metres |
| `movementSpeed` | float | hardware speed m/s |
| `hasBearing`, `hasSpeed` | bool | hardware validity flags (feed the NLP check) |
| `provider` | string | `gps` default |
| `type` | string | `ATTENDANCE` (default) / `PUNCH_IN` / `PUNCH_OUT` |
| `locationType` | string | `gps` / `network` … |
| `addressLine1/2, city, state, country, address` | strings | reverse-geocoded (best-effort, async) |
| `timezone` | string | device TZ id |
| `batteryPercentage` | string | at fix time (diagnostics) |
| `mobileServiceStatusFlag` | 0/1 | cell service (diagnostics) |
| `isShowPin` | `"Yes"/"No"` | significant-stop pin hint (§9) |
| `activityStatus` | string | `"<locationType>@<movementStatus>"` |
| `detectedActivity`, `activityStartTimeMs` | string, int64 | from §10 |
| `syncTime` | int64 | 0 = unsynced; set to upload time on success |

Insert rules: reject `(0,0)` coordinates; exactly one row per accepted fix; the session's `lastKnownLocation` is updated on insert. Add TTL pruning (e.g. delete synced rows older than 7 days).

### 11.2 Upload

- Trigger: immediately after each insert, plus health-loop/watchdog nudges, plus whatever the 15-min backstop stores.
- Batch: all rows with `syncTime == 0`, deduplicated by `time`.
- Shape: `POST` to the location-batch upload endpoint, body = auth/default params + `"location": [<point>...]` where each point serializes the schema above with snake_case keys: `time` (epoch ms), `local_date`, `latitude`, `longitude`, `address_line_1/2`, `city`, `state`, `country`, `address`, `time_zone`, `type`, `location_type`, `battery_percentage`, `mobile_service_status`, `is_show_pin` ("Yes"/"No"), `activity_status`, `movementSpeed`, `accuracy`, `provider`, `hasBearing`, `hasSpeed`, `detected_activity_type`, `detected_activity_start_time` (epoch ms).
- Success → stamp `syncTime = now` on the uploaded rows. Failure → rows stay queued; retried on the next trigger. HTTP **401 → full teardown** (token expired).
- Transport timeouts: connect 5 s, read 30 s, write 20 s.

### 11.3 Fetch shape (plotting side)

Day-history query: `{employee_id, local_date: "yyyy-MM-dd", duration: 30}` → employee info + `locations_history: [...]`.

`locations_history` entry fields: `id`, `location_time` (**epoch ms** — the sort key), `location_add_time` ("09:40 PM" display string), `location_add_date`, `latitude`/`longitude` (**strings** in the reference response, default "0.0" — parse defensively), `location_type`, `address*`, `city/state/country`, `time_zone`, `battery_percentage`, `mobile_service_status`, `is_show_pin`, `activity_status`, `detected_activity_type`, `detected_activity_start_time` (ms), `device_id`/`device_type`/`device_info`, and `type` ∈ {`PUNCH_IN`, `PUNCH_OUT`, `AUTO_PUNCH_OUT`, `OTHER`}.

Session start/end calls also carry `latitude`, `longitude`, `address`, `time` (epoch ms), `date` (`yyyy-MM-dd`) — these become the PUNCH_IN / PUNCH_OUT bookend rows.

## 12. Heartbeats & Watchdog (Detail)

Two distinct "heartbeat" concepts — do not conflate them:

### 12.1 Stationary filter heartbeat (data-plane)
While stationary, a fix is *accepted by the filter* at most once per **900 s** (`timeDelta > 900 s`, accuracy < 70 m, moved < 100 m) — but it is deliberately **not stored/uploaded** (Stage 7 routes it to SKIP). Purpose: keep the Kalman prediction clock warm so the next real movement isn't gated as an outlier, while producing **zero server rows for a stationary employee** (no "blob" of drift points, less data, less battery). Consequence: *absence of uploads is not evidence of a dead tracker.*

### 12.2 Liveness watchdog (control-plane)
- Runs every **60 s** (alarm/reminder driven, survives the service).
- Fallback actions throttled to once per **15 min**.
- Checks, in order:
  1. Tracking service not running (while a session is active) → enqueue the expedited **restore job**.
  2. Raw GPS fix clock (`lastGpsFixMs`, updated on every raw provider fix regardless of filter outcome) stale **> 30 min** while moving (or > 60 min stationary) → fire the full-screen "tracking interrupted" user nudge (urgent notification, call-style category, 30 s timeout).
  3. Newest stored row unsynced → run the sync queue.
  4. Stationary > 30 min → no-op (expected: heartbeat suppression).
  5. Background soft-wake: 20 s partial wake lock to let the pipeline breathe on aggressive OEM battery managers.

## 13. Precautions & Safeguards (Capture Plane)

1. **Permissions**: fine + coarse required; background location required for the continuous pipeline (Android 10+). Live revocation watching → immediate clean stop (never a crash). Rationale/settings flows capped (max 3 retries) to avoid prompt loops.
2. **Foreground transparency**: persistent notification while tracking. The user always knows tracking is on.
3. **Privacy gates**: no tracking outside an active session; full local wipe at session end/logout/401.
4. **Battery**: high-accuracy stream only while a session is active; heartbeat suppression while stationary; batching (60 s max update delay); optional battery-optimization exemption prompt (Android; note store-policy sensitivity).
5. **Clock discipline**: all business timestamps use a **server-corrected clock**, not raw device time; fix timestamps back-corrected via elapsed-realtime deltas; negative Δt handled explicitly (Stage 1.5).
6. **Staleness**: 60 s max fix age at the provider layer; 10 s in-request max age; 30 s one-shot timeouts.
7. **Anti-jitter**: burst rejection (500 ms), anchor R-penalty, net-displacement persistence, display-side marker jitter of ~0.56 m so co-located markers don't perfectly stack.
8. **Failure containment**: foreground-start denial → clean abort + restore job; worker retry with backoff; the filter's forced reset guarantees no permanent wedge.
9. **Recommended additions**: mock-location detection (`Location.isMock` API 31+, else `isFromMockProvider`) with flagged points; TTL pruning of synced rows (§11.1); a strictly serialized filter queue (§6.3).

---

# PART B — Plotting an Individual Employee's Day

## 14. Fetch Flow

1. Viewer opens the employee's day (bottom sheet / screen) → emit `PROCESSING` → run the day-history query (§11.3, `duration: 30`).
2. Sort ascending by `location_time` (epoch ms). Empty → `ERROR` state with server message.
3. Run the processing pipeline (§16) off the UI thread → emit `COMPLETED` with the full view-state.
4. View states: `NONE | PROCESSING | COMPLETED | ERROR`.
5. **No polling.** Re-fetch only on: sheet open, date change, device-filter change. "Live-ness" is data-derived: if the last node isn't a punch-out, ongoing dwell = `(now - lastNode.locationTime)/1000` computed at render, and the last node renders as a pulsing "current location" marker.

## 15. Multi-Device Attribution

A day may contain rows from several devices (phone swap, reinstall). Before summarizing:

1. Segment the sorted rows into **sessions**: PUNCH_IN opens, PUNCH_OUT/AUTO_PUNCH_OUT closes.
2. Session owner device = first non-blank `device_id` in the session, else a day-wide fallback (first device seen anywhere that day), else `Unknown`.
3. Intermediate pings belong to their own `device_id` (blank → session owner).
4. **Session bookends are shared** into every device's list that touched the session (so each device's timeline is punch-bounded).
5. Per-device lists: `distinctBy(id)`, sorted by time. Compute a full summary per device.
6. Displayed device: the only one if single; else match the viewer-selected device id; else first. Show a device dropdown only when > 1 device.

## 16. Processing Pipeline Order

For the selected device's rows (`remote`):

```
1. filtered   = consolidateStopsAndPunches(remote)          // §17
2. nodeIdx    = detectSignificantNodes(filtered)            // §18
3. snapped    = isManagerView ? roadSnapV2(filtered) : null // §22 (self-view skips snapping)
4. activity   = buildActivityTimeline(filtered)             // from detected_activity fields
5. clusters   = buildClusters(filtered, nodeIdx, activity)  // §19
6. geocode missing cluster addresses (async, non-blocking)  // §24
7. day summary: tracked time + break durations (raw rows)   // §25
8. commute duration + per-activity breakdown (clusters)     // §25
9. totalDistance = sumQualifiedLegs(remote)                 // §25
```

**Two datasets feed the UI:** the **map polyline** uses `snapped ?? filtered`; the **timeline rows and numbered markers** use `clusters` built from the *unsnapped* `filtered` list. Keep this split — snapping is cosmetic, semantics stay on real points.

## 17. Stop & Punch Consolidation

Input: sorted day rows. Output: reduced list preserving punches and stop centroids.

- Punch-type rows (`PUNCH_IN`, `PUNCH_OUT`, `AUTO_PUNCH_OUT`) always pass through. An `AUTO_PUNCH_OUT`'s lat/lng is **snapped to the previous output point** (server auto-punch-outs carry stale coordinates; avoid a fake jump).
- Non-punch rows: greedily group consecutive points within **60 m of the running centroid**.
  - Group dwell **≥ 10 min** → emit **two** nodes at the centroid position: *arrival* (first point's identity/time) and *departure* (last point's identity/time).
  - Shorter → emit only the group's first point.
- Finish with `distinctBy(id)`.

## 18. Significant-Node Detection

Scans the consolidated list, returns indices of "significant" points (stop anchors + punches). Constants: stop radius **100 m**, significant dwell **10 min**, gap-stop threshold **10 min**, gap speed limit **35 m per minute**, jitter guard **100 m**, hop tolerance **15 m**.

- Only accumulate between PUNCH_IN and its closing punch.
- **Gap-stop protection**: when the time gap to the previous point ≥ 10 min, compute `speed = dist/gapMin`; if `speed ≤ 35 m/min` or `dist < 100 m`, treat the gap as dwell (extend or finalize the cluster) rather than travel.
- **Centroid clustering**: extend the active cluster if the point is ≤ 100 m from its centroid or ≤ 15 m from the previous point; otherwise finalize the previous stop and restart.
- Finalizing keeps the **arrival index** (cluster start) when dwell ≥ 10 min — arrival-anchor, deliberately not the medoid, so the timeline shows "arrived at X" without a phantom walking node.
- Jitter guard: a new index is only added if > 100 m from the last node, or it's a punch of a different type (finalized stops bypass the guard).

## 19. Cluster / Segment Construction (Timeline Nodes)

Each consecutive pair of significant indices becomes a `ClusterRecord` with up to three nodes:

- **startPoint** — arrival at the segment's first point. `staySeconds` = time until the first later point **> 60 m** away (punch-outs → 0; last node non-terminal → ongoing, computed to now). `stayEndTime = locationTime + stay*1000`.
- **Movement detection** across the segment: `moveStartIdx` = first leg > **80 m**, or first point > **100 m** net from start.
- **`isRealMovement`** (the segment truly traveled) — any of:
  1. net displacement start→end > **100 m**;
  2. *sustained excursion*: ≥ **2 consecutive** points beyond **150 m** from start AND peak leg speed ≥ **1.0 m/s**;
  3. total path distance ≥ **500 m** AND (max speed ≥ 5 m/s OR p75 ≥ 3 m/s).
- **STILL override**: if the dominant detected activity says STILL but `isRealMovement`, distrust STILL — re-resolve to the dominant non-STILL activity in the window, else fall back to speed buckets (§21).
- **middleNode** (the "traveled" row) — created only when movement is real and the segment isn't just its endpoint. Carries `travelDistance` (m), `travelDuration` (s), `maxSpeedMps`, `p75SpeedMps`; its end time is clamped to `min(activitySegmentEnd, travelEnd)`.
- **endNode** — created when the segment spans > 1 point AND (end index is significant, or terminal punch, or a middleNode exists, or phantom-leg carry-forward applies).
- **Phantom-leg carry-forward**: when there are *no intermediate samples* but real movement happened (signal blackout while driving), bound the implied travel time to `clamp(max(dist, net) / 5.0 m/s, 60 s, 300 s)` and set `endNode.travelStartMs = travelEnd - boundedTravel` — the UI then renders a separate "drive window" and "dwell window" on the end node.
- Boundary rule: cluster N's endPoint **is** cluster N+1's startPoint (same significant index) — accepted duplication, don't "fix" it.
- A terminal PUNCH_OUT/AUTO_PUNCH_OUT closes the cluster and skips ahead by 2 indices.

## 20. Speed Statistics (per segment)

Per consecutive leg inside the segment:
- Ignore legs **< 20 m** or **< 5 s** (jitter/sub-sampling).
- **Phantom-leg guard**: legs with `Δt ≥ 10 min` AND `dist < 500 m` are excluded from *speed* stats (their distance still counts) — long GPS-silence dwells must not drag the percentile into the walking band.
- `maxSpeedMps` = peak leg speed. `p75SpeedMps` = **duration-weighted 75th percentile**: co-sort (speed, duration) pairs by speed, walk the cumulative duration to 75 % of total weight.

## 21. Activity Labeling

Given the segment's detected-activity label (from capture, §10) and speed stats:

- If the label is low-tier (`WALKING/ON_FOOT/STILL/RUNNING`) but speeds contradict it (`max ≥ 8` OR `p75 ≥ 5` m/s) → override with the speed bucket. No label → speed bucket.
- **Speed buckets** (m/s): `max ≥ 70 && p75 ≥ 50` → Flight ✈️; `max ≥ 55 && p75 ≥ 35` → Train 🚄; `max ≥ 25` → Driving; `max ≥ 8 && p75 ≥ 6` → Riding; `max ≥ 5 && p75 ≥ 3` → Cycling; `p75 ≥ 2.5` → Running; else Walking.
- Commute-category collapse for the day summary: Driving/Riding → "Vehicle"; Flight/Train resolved by speed first.

## 22. Road Snapping (Map Cosmetics)

### 22.1 Routing service configuration
- Provider: any road-network snapping/directions service exposing snap-to-road and routing over GeoJSON (the reference uses an OpenRouteService-compatible API). Endpoints:
  - Snap: `POST {base}/v2/snap/driving-car/geojson`
  - Directions: `POST {base}/v2/directions/driving-car/geojson`
- Auth: `Authorization: <api-key>` header (build-time secret; inject via Gradle/`BuildConfig`, never hardcode in a committed source file).
- Coordinates are **[longitude, latitude]** order. Snap body: `{locations: [[lng,lat],...], radius: 300}`.
- Directions defaults when used: `units=m, continue_straight=true, geometry=true, instructions=false, preference=shortest`.
- Separate HTTP client: connect 5 s / read 30 s / write 20 s, retry on connection failure.
- **Chunking**: max **40 points per request**; subsequent chunks drop their first point when stitching (it duplicates the previous chunk's last point).
- Cache snapped results per (deviceId, date, pointsHash) — re-snapping the same finished day is wasted quota.

### 22.2 Production algorithm (V2 — implement this)

```
snapLargePath(points):
    kept = stationaryFilter(points)         // drop consecutive points < 12 m apart,
                                            // always keep punch-type points
    out = []
    for chunk in kept.windowed(40):         // drop first point of chunks 2..n
        road = snapAPI(chunk)               // GeoJSON first-feature coordinates
        out += road.isEmpty ? chunk         // FALLBACK: raw points on empty/failed snap
                            : reconstruct(chunk, road)
    return bezierRounding(out)

reconstruct(rawPts, road):
    for each raw point p:
        c = closestPointOnPath(p, road)     // linear scan
        if p.isPunch or dist(p,c) > 80 m:   keep p        (tag raw_punch / raw_off_track)
        else:                                keep c        (tag snapped_to_road)
        // between consecutive OUTPUT points, inject the road sub-path
        // ONLY if BOTH endpoints are ≤ 80 m on-road
    return result

bezierRounding(path):                        // corner smoothing
    at each interior vertex with turn angle > 30° and not raw_punch:
        cutback = min(25 m, 0.4 * distToPrev, 0.4 * distToNext)
        replace vertex with 5 quadratic-Bézier points between cutback points (tag rounded_curve)
```

Rules of thumb encoded above: **80 m** off-road threshold (a point farther than 80 m from the snapped road is trusted over the road), punches are never moved, snapping failure degrades gracefully to the raw track.

**Self-view (employee viewing their own dashboard) skips snapping entirely** — draws the consolidated raw path. Snapping runs only for the manager view.

### 22.3 Prior generations (context; do not port unless asked)
- **V1**: 45 m off-road veto per point; inserted synthetic "turn anchors" (15 m along bearing when turn angle 45–135° and legs < 50 m) and midpoint anchors on legs > 400 m. Superseded by V2's Bézier pass.
- **Hybrid** (experiment, disabled): Kalman pre-smooth (Q=3, fixed accuracy 30) → snap to identify on-road runs (off-trail if drift > 50 m) → per-run *directions* routing with plausibility veto (`routeDist > 2 × gpsDist` → fall back to snapped candidates; average drift > 35 m → preserve smoothed points). Kept as the reference design for a future "true map-matching" upgrade.

### 22.4 RTS smoother prototype (reference only)
A forward-Kalman + backward Rauch-Tung-Striebel pass over the day (constant-position model, `F=H=I₂`, `Q=0.5·I₂`, `R=0.01·I₂`, initial `P=0.01·I₂`, coordinates scaled ×10⁶; 1 s virtual interpolation across gaps; last point anchored). Documented as a candidate advancement for offline track beautification; **not** in the production path.

## 23. Map Rendering Rules (Individual Employee)

Route points = snapped path (manager) or consolidated path (self-view), **excluding AUTO_PUNCH_OUT** nodes. Fewer than 2 points → plain markers + padded camera fit (padding 50) and stop.

### 23.1 Polylines
- **Base path**: single polyline over all route points — dark gray, alpha 190, width 16, geodesic, round joints, z-index 0.
- **Speed overlay**: per consecutive pair, `speedKmph = dist/Δt × 3.6`; color `≥ 20` → green `#04d95c`, `≥ 10` → yellow `#f5bc00`, else red `#f20202` (alpha 160, z-index 2, same width).
- Add polylines in chunks of 25 with a ~16 ms yield between chunks (keeps the UI thread fluid on long tracks; it is *not* a playback animation).

### 23.2 Direction arrows
- Custom arrow cap bitmaps placed along the route. Skip a segment if length < **60 m** or zoom < **10**.
- Segment > **50 km** (data jump) → exactly two arrows at ¼ and ¾.
- Segment ≤ **250 m** → one centered arrow.
- Otherwise spacing: route > 40 km → every 5 km; > 10 km → 2.5 km; else zoom-based: `z ≥ 18 → 80 m, ≥ 15 → 300 m, ≥ 13 → 800 m, else 4 km`.
- On camera idle, re-render arrows only when `|zoom − lastRenderedZoom| > 0.5`. Polylines and markers are never re-rendered on camera moves.

### 23.3 Markers
- One **numbered pin** per cluster start/end node (deduped by position index): white circle + number (node order, 1-based) with a downward pointer.
- **Last active node** (day not closed by punch-out): live "current location" icon + a **pulsing circle ground overlay** (2 s repeating radius animation).
- Info window: 📍 node number, ⏰ arrival time (h:mm a + short TZ), ⏳ wait/dwell duration, 🪪 punch event type if any, 🗺️ address. Anchor (0.5, 1).
- Apply ~0.56 m coordinate jitter to perfectly co-located markers so they never fully overlap.

### 23.4 Camera
- Fit bounds of all route points with padding **80** (50 for the 2-point fallback). Max zoom preference 20.

## 24. Timeline UI Rules

The timeline lists `ClusterRecord`s. Per cluster (composed of up to 3 rows):

- Collapsed: **parent row** (startPoint) + **end row** (endPoint), with a summary line (distance/duration/wait) shown on the pair; expand button visible only if a middleNode exists.
- Expanded: insert the **middle row** (travel details) between them and hide the summary line.
- Formulas (used identically everywhere):
  - distance: `km = travelDistance/1000`, display `"%.2f km"`;
  - duration: `h = s/3600, m = (s%3600)/60` → `"Xh Ym"` / `"Ym"` (stays additionally allow `"Zsec"` under a minute);
  - average speed: `travelDistance / max(travelDuration, 1)` m/s;
  - movement row: `Started <activityLabel> Between <arrivalTime> to <stayEnd h:mm a> <icon>`;
  - stationary row: `Stationary … <arrival> to <stayEnd>`; last open node: `Stationary from <arrival> till now`;
  - **end-node dual window** when phantom carry-forward set `travelStartMs > 0`: render the drive window `travelStart → arrival` separately from the dwell window `arrival → stayEnd`;
  - middle row's badge = activity emoji; 🧍 when `travelDuration == 0`.
- Address resolution: async reverse-geocode (1 result max) for start/end nodes with empty addresses, deduped by lat/lng, skipping AUTO_PUNCH_OUT; rows show the resolved address.
- Timezone: all displayed times formatted in the **employee's** stored timezone.

## 25. Day Summary Metrics

Computed once per fetch (all durations in seconds):

- **Tracked time**: consecutive-point gaps summed across the day (timezone-aware); the last point extends to *now* if the day isn't punched out.
- **Break time**: sum of PUNCH_OUT → next PUNCH_IN gaps.
- **Commute time & per-activity breakdown**: per cluster, skip *in-place wander* (`max(pathDist, netDist) ≤ 90 m`); otherwise add `travelDuration` to the bucket keyed by the commute category (§21). Buckets sorted desc; show the breakdown only when > 1 bucket. Bucket total equals commute total by construction.
- **Total distance**: sum consecutive-point distances only where `dist > 50 m` AND `Δt ≥ 1 min`, skipping AUTO_PUNCH_OUT and break gaps; display km with 2 decimals.
- Duration formatter: `"1hr 5mins"`, `"0mins"`.

---

# PART C — Implementation Notes

## 26. Implementation Guidance

### 26.1 Concern → mechanism mapping

| Concern | Mechanism | Config from this spec |
|---|---|---|
| Background location | `FusedLocationProviderClient` inside a foreground service of type `location`; platform `LocationManager` as the no-Play-Services fallback | request tables in §4 |
| Periodic backstop (15 min) | `WorkManager` `PeriodicWorkRequest` | 30 s fix timeout, linear retry |
| Activity recognition | Activity-Transition API + a one-shot snapshot at registration | §10; snapshot confidence ≥ 50, transition confidence ≥ 75 |
| Local DB | Room | schema §11.1, `syncTime` index, TTL pruning |
| Upload/sync | serialized queue keyed on `syncTime == 0` | payload §11.2; 401 → teardown |
| Map | Google Maps SDK — `Polyline`, `Marker`, ground overlay | rendering rules §23 |
| Kalman + acceptance + snapping + timeline math | **Pure Kotlin, no Android imports** — runs and is tested on the JVM | §6, §7, §17–§22 |
| Watchdog | `AlarmManager` (60 s) + expedited restore work | §12.2 |

### 26.2 Android platform specifics

- Foreground service type `location`; `POST_NOTIFICATIONS`, `ACCESS_BACKGROUND_LOCATION`, `ACTIVITY_RECOGNITION` runtime permissions; `RECEIVE_BOOT_COMPLETED` + boot receiver to reschedule the watchdog.
- Background-start restrictions: API 31+ throws `ForegroundServiceStartNotAllowedException`, API 34+ throws `SecurityException` for a location-typed FGS. Catch both, abort cleanly, let the restore path re-promote — never crash-loop.
- Background-job unique names + keep/replace policies per §2's table; expedited one-shot for restore.
- OEM battery managers: the 60 s watchdog + 20 s soft wake lock pattern (§12.2) is what keeps the pipeline alive on aggressive OEMs — keep it.

### 26.3 Implementation phases

1. **Capture core**: session gating + stream + Kalman/acceptance (pure, unit-test against §7's pseudocode with recorded fixtures) + local store + sync queue.
2. **Resilience**: watchdog/restore, health loop, heartbeat semantics.
3. **Activity recognition** — or the bearing-change force-capture substitute (§8.2); enrichment only, capture works without it.
4. **Plotting**: fetch → consolidate → clusters → timeline (no map yet; verify numbers against a known day).
5. **Map render + road snapping**; then the day summary.

Parity test: run capture on a device for a full workday; compare stored rows (count, positions, decision reasons) and the rendered timeline (clusters, durations ±1 min, labels) against the expectations in §8.4.

### 26.4 Pitfalls that produce the three §8 symptoms

Each of these is a known way a location stack breaks the pipeline — audit any existing build against all of them before touching any constant:

1. **`distanceFilter` > 0.** Directly *causes* stationary drift and destroys the filter's Δt assumptions — must be `0`; the pipeline does all thinning itself (§8.1 #2).
2. **Lost speed/bearing validity flags.** Any layer that coerces invalid speed to `0` instead of carrying `hasSpeed()` / `hasBearing()` disables the NLP-fix rejection and hardware-stationary detection — the two pillars of stationary accuracy. Verify at the mapper that touches `android.location.Location`, nowhere later.
3. **Units**: pipeline speeds are m/s, accuracies are metres. A km/h value fed in makes `hwSpeed < 0.3` never true while walking.
4. **Duplicate subscriptions** — the stream and the backstop worker each feeding the filter = two interleaved states fighting. Route everything through one serialized consumer (§6.3); the 500 ms burst gate is a backstop, not the design.
5. **Timestamps**: use the fix's own clock, never receipt time; enforce the 60 s staleness gate. The fused provider delivers batched backlogs and cached fixes on resume — both look like teleports if timed by arrival.
6. **Process restart without re-seeding**: on every start, seed the Kalman from the last stored point *with its stored timestamp* (§7 Stage 1 resume path) *before* processing live fixes; otherwise the first post-restart fix is accepted blind ("Init") wherever it lands.
7. **Coordinate precision**: keep full double precision end-to-end; server responses may return lat/lng as strings (§11.3) — parse defensively, never round below 6 decimals (~0.1 m).
8. **Filter runs before storage, storage before upload** — if a build stores raw OS fixes and filters at render time, that is the root cause of all three symptoms at once; move the pipeline to capture time (§1 decision 2).
9. **OEM battery managers** killing the service silently: implement the watchdog (§12.2) — without it, gaps appear that then stress the recovery path.
10. **Batched delivery read as one fix** — `LocationResult.lastLocation` discards the rest of the batch. Iterate `getLocations()`, and key the burst gate on fix time so a legitimate batch is not rejected wholesale.

## 27. Consolidated Constants Appendix

| Group | Constant | Value |
|---|---|---|
| Sampling | stream interval / fastest / distance | 60 s / 30 s / 0 m |
| Sampling | backstop period / fix timeout | 15 min / 30 s |
| Sampling | max fix age (request / delivery gate) | 10 s / 60 s |
| Loops | health loop / watchdog / watchdog throttle | 2 min / 60 s / 15 min |
| Liveness | dead threshold moving / stationary | 30 min / 60 min |
| Sync | re-sync age trigger | ≥ 16 min |
| Filter | burst / signal gap / recovery timeout / heartbeat | 500 ms / 110 s / 900 s / 900 s |
| Speeds (m/s) | stationary max / walking min / vehicular min / GPS-trust / virtually-stopped | 0.3 / 0.6 / 3.0 / 1.5 / 2.0 |
| Speeds | max physical / vehicular leg cap / highway threshold | 140 km/h / 45 m/s·Δt + 200 m / 45 km/h |
| Distances (m) | min move / jitter / wobble / wobble (no-speed HW) | 10 / 15 / 40 / 80 |
| Recovery (m) | wakeup / vehicular / GPS-large / immediate / confirm-near | 100 / 200 / 400 / 150 / 60 |
| Persistence | grow margin / confirm net / depart count / settle fixes | 20 m / 100 m / 2 / 2 |
| Accuracy (m) | high / medium / stationary limit / vehicular max / NLP reject | 40 / 70 / 40 / 85 / 25 |
| NLP | HW-vehicular bypass window | 10 min |
| Sigma gate | base / speed factor / max (veh/mov/still) / clamp min | 3σ + 1.5·acc + 200 / 1.2 / 2500 / 800 / 400+200·rejects / 50 |
| Sigma gate | max rejects (gap/still/veh/walk) | 2 / 4 / 2 / 3 |
| Kalman | ctor q / Q (hwy/veh/still/moving) | 5 / 0.8 / 1.2 / 0.0001 / 0.1–0.8 |
| Kalman R | bad-acc multiplier (hwy/other) / drift divisor / max penalty / anchor divisor·cap | 3.0 / 2.5 / 15 / 30 / 15 / 8 / 5 · 100 |
| Stops (capture) | stationary radius / min duration / speed | 60 m / 10 min / < 15 km/h |
| Consolidation | centroid radius / dwell → 2 nodes | 60 m / 10 min |
| Node detection | stop radius / dwell / gap-stop / gap speed / jitter guard / hop | 100 m / 10 min / 10 min / 35 m/min / 100 m / 15 m |
| Clusters | departure leg / net-move / excursion (pts·radius·peak) / long-path | 80 m / 100 m / 2 · 150 m · 1 m/s / 500 m (max ≥ 5 ∨ p75 ≥ 3) |
| Day summary | in-place wander skip | ≤ 90 m |
| Speed stats | leg ignore / phantom leg / percentile | < 20 m ∨ < 5 s / ≥ 10 min ∧ < 500 m / duration-weighted p75 |
| Labels (m/s) | override / drive / ride / cycle / run / train / flight | max ≥ 8 ∨ p75 ≥ 5 / 25 / 8·6 / 5·3 / 2.5 / 55·35 / 70·50 |
| Snapping | radius / chunk / off-road / stationary / Bézier (angle·cut·pts) | 300 m / 40 / 80 m / 12 m / 30° · min(25 m, 0.4·adj) · 5 |
| Rendering | width / speed colors / arrows (min seg·jump·single·zoom) | 16 / 20 & 10 km/h / 60 m · 50 km · 250 m · z<10 skip |
| Rendering | camera padding / chunked add / arrow re-render | 80 (50) / 25 per 16 ms / Δzoom > 0.5 |
| Fetch | history duration | 30 |
| HTTP | connect / read / write | 5 s / 30 s / 20 s |

## 28. Verification Checklist (run BEFORE implementing)

Confirm against the backend you're integrating with:

1. Endpoints exist with these semantics: a location-batch upload accepting the §11.2 point shape (epoch **milliseconds**), a day-history query accepting employee + date + duration, and session start/end calls that carry lat/lng (they become the PUNCH_IN/PUNCH_OUT bookend rows).
2. Field names re-mapped: the snake_case keys in §11.2/§11.3 are the reference shapes — align them with your backend's actual contract (watch for string lat/lng in responses).
3. A routing API key with snap + directions quota exists; confirm base URL and rate limits (chunk size 40 assumes generous quota; a full workday can be 5–15 snap calls per render). **This is the main lever for turn fidelity (§8.2) — prioritize it.**
4. Server-corrected time source available (an endpoint returning server time, used to discipline `time` stamps).
5. Auth: 401 semantics on upload → full teardown is acceptable UX.
6. Before tuning any constant on an existing build: audit against §26.4 (pitfalls) and instrument per §8.4 (decision log + three-layer debug overlay + recorded-fixture harness).

**Reference-only material (do not implement unless asked):** V1/Hybrid snappers (§22.3), the RTS smoother (§22.4), the speed-based high/low-power request switcher (§4).

