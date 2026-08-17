# Smooth Navigation Plan — Live Rendering & Turn Fidelity

Status: Phases 1–5 implemented (2026-08-04; Phase 1 review-verified)
Date: 2026-08-04
Scope: replicate the "Google Maps smooth navigation feel" (live polyline, animated position, smooth turns) on-device, with **no new third-party libraries** — Android SDK + Google Play services (already present) only.
Prerequisite reading: PLAN.md (locked decisions §0), EDGE-CASES.md, POLYLINE-JSON.md.

---

## 1. Background — what Google Maps actually does

Findings from research pass (sources at end):

1. **FusedLocationProviderClient** is itself a server-assisted Kalman-style fusion engine (GNSS + Wi-Fi + cell + IMU). Since Dec 2020 Google adds 3D-building-model ML corrections server-side. Apps inherit this for free through FLP; it cannot be re-implemented client-side.
2. **Noise filtering**: constant-velocity Kalman filter, accuracy gating, outlier rejection (speed gates + Mahalanobis/innovation gate against χ²(2) ≈ 9.21), with mandatory filter reseed after N consecutive rejects or a long gap.
3. **Duplicate suppression**: minimum-displacement thresholding (`max(D_min, 0.5·accuracy)`), stationary clustering to a centroid stop node, time dedup for batched deliveries.
4. **Simplification**: Douglas-Peucker — ε ≈ 2 m walk / 5 m drive for storage; zoom-dependent ε at render (`ε = tolerance_px × metersPerPixel(zoom, lat)`).
5. **Turn smoothness is map matching, not geometry**: the route line hugs roads because it *is* road geometry (Newson-Krumm HMM, the algorithm OSRM `/match` implements). During turn-by-turn, the blue dot is snapped to the *known active route* — the single cheapest replicable trick.
6. **The "smooth feel" is animation, not data**: the blue dot never teleports. `ValueAnimator` interpolation between ~1 Hz fixes with spherical lat/lng lerp, shortest-signed-angle bearing slerp (EMA α ≈ 0.25, bearing frozen below ~1 m/s), incremental polyline append, and chained `animateCamera`.

## 2. Current-state audit

What TrackIt already has (verified against source, do **not** rebuild):

| Google technique | TrackIt equivalent | Status |
|---|---|---|
| CV Kalman + gating | `KalmanFilter.kt` + 7-stage `AcceptancePipeline.kt` (3σ gate, forced-reset un-wedge) | Done, field-tuned |
| Duplicate/near-duplicate suppression | 500 ms burst gate, 40/80 m wobble guard, anchor R-penalty, net-displacement persistence, `Consolidation.kt` stop collapse | Done |
| Spline turn smoothing | Centripetal Catmull-Rom α=0.5, 5 m resample (`Spline.kt`); `BezierRounding.kt` fallback | Done |
| Map matching | OSRM `/match` HMM (`OsrmSnapProvider`, optional, self-hosted) | Done |
| Path simplification (Douglas-Peucker) | — | **Missing** |
| Live map updates | — map redraws only on manual `refresh()`; `TrackRenderer.render()` is full clear+redraw | **Missing** |
| Position puck + marker animation | — | **Missing** |
| Camera follow | — camera set once to bounds | **Missing** |

**Conclusion: the gap is not coordinate filtering — it is live rendering.** The fix flow currently ends at Room; there is no push path from accepted fix to map geometry.

### Verified weaknesses feeding this plan

Each verified against code by an adversarial pass (verdict REAL unless noted):

- **Kalman output is never plotted** — accepted `TrackPoint`s store raw fix coordinates; filter output is used only for gating/anchoring (`AcceptancePipeline.kt` accept path).
- **No simplification stage anywhere** — spline output at ~1 vertex/5 m means a 50 km track approaches 10k encoded-polyline vertices.
- **All-or-nothing spline standdown** — one `SNAPPED_TO_ROAD` vertex anywhere disables smoothing for the whole path (`Spline.kt` bailout); partially snapped tracks keep raw 120 m chords on off-road legs.
- **Snapper uses nearest-vertex matching, not segment projection** (`Snapper.kt`) — fixes get dragged up to half the road-vertex spacing along-track; coarse road geometry renders as a chorded polygon and is then exempt from smoothing.
- **`TrackRenderer` has no incremental path** — destructive `clear()` + re-add of every polyline/marker per render; no `Polyline.setPoints()` mutation, no position marker, no `animateCamera`.
- **Batching latency is hostile to live drawing** — `maxUpdateDelay` coerced to [interval, 2×interval]: worst case 120 s at normal tier, 8 s even at turn-burst tier.
- **No speed/bearing accuracy captured** — `FixMapper`/`TrackFix` drop `speedAccuracyMetersPerSecond`/`bearingAccuracyDegrees`, so nothing can weight Doppler by its own confidence.
- **`Bearing.difference` is unsigned** (`Haversine.kt`) — signed-curvature / left-vs-right logic cannot be built on it as-is.
- **Pipeline constants are cadence-sensitive but the pipeline is cadence-blind** — `IngestContext` carries no tier hint; count-based gates (e.g. `persistDepartCount`) mean ~8 s at the 4 s tier vs ~24 s at 12 s.
- **OSRM confidence unparsed, no timestamps sent** (`OsrmSnapProvider.kt`, `OsrmMatchResponse.kt`) — a garbage low-confidence match is accepted like a perfect one, and OSRM's HMM runs without speed plausibility.
- **`Spline.kt` has no antimeridian handling** — raw longitudes enter the math (`BezierRounding`/`Arrows` already normalise via `normaliseLongitudeDelta`).

## 3. Locked decisions this plan respects

From PLAN.md §0 / EDGE-CASES.md — unchanged:

- trackit-geo stays pure Kotlin; all algorithms and numeric constants live there (Konsist-enforced).
- Stage order in `AcceptancePipeline` is load-bearing; never reorder, never cross-tune.
- CV Kalman, not CTRV (EKF review C1); turn boost applies to the correction only, never the gate (EC-45a).
- Raw coordinates remain the stored truth. This plan *emits* filtered coordinates for live display; it does not change what is persisted.
- `distanceFilterM = 0` stays contractual (EC-119); all thinning is software-side.
- Drawn track and exported track come from the same functions (A9). The live surface is additive, not a fork of the plot pipeline.
- Snapping stays cosmetic and optional; core never touches network.

---

## 4. Phases

### Phase 1 — Navigation cadence profile (trackit-core)

The pipeline cannot animate what it does not receive. Fastest current tier is turn-burst 4 s.

1. `TrackItConfig.GeolocationConfig`: add `navigationMode: Boolean` (or a `CadenceProfile` enum) →
   - `intervalMs = 1000`, `fastestIntervalMs = 500`
   - `maxUpdateDelayMs = 0` (no batching while navigating; batching stays for background tiers)
   - keep `minUpdateDistanceMeters = 0`, `waitForAccurateLocation = true`
   - `validate()`: navigation profile only meaningful with `foregroundService = true`; warn otherwise.
2. `FixMapper`/`TrackFix`: capture `speedAccuracyMetersPerSecond` and `bearingAccuracyDegrees` (nullable, additive schema change → Room migration v5). Downstream weighting of Doppler becomes possible; no behaviour change yet.
3. `IngestContext`: add `cadenceTierMs` hint so cadence-sensitive gates can scale (constants themselves stay in `TrackItConstants.kt`). First consumer: departure ladder counts.
4. `Haversine.kt`: add `Bearing.signedDifference` (−180..180]. Needed by Phase 3 bearing slerp and any future curvature logic. Additive; existing `difference` untouched.

Acceptance: navigation profile delivers ≥ 0.9 fixes/s to `FixIngestor` on a reference device with screen on; existing fixture replay byte-identical (no pipeline behaviour change).

### Phase 2 — Live track surface (trackit-core; the missing push path)

1. New API: `TrackIt.liveTrack(): Flow<LiveTrackUpdate>`, fed from the accepted-point path in `FixIngestor` (same place `TrackItEvent.Location` is emitted today).
2. `LiveTrackUpdate` =
   - `frozenTail`: encoded polyline of all points except the last N (computed incrementally, never re-smoothed),
   - `liveHead`: last ~2 spline spans as plain points, re-smoothed per fix,
   - `puck`: Kalman state snapshot — filtered position, velocity vector, heading, accuracy (this is where filter output finally reaches a display surface; storage still records raw, per locked decision),
   - `sequence`: monotonic counter so renderers drop stale updates.
3. Live-head smoothing runs in trackit-geo (new `LiveSpline` entry point reusing `Spline` internals on a 4-knot window) so the drawn head and the eventual full-track spline agree where they overlap.
4. Backpressure: conflated flow — renderer always gets the latest state, never a queue.

Acceptance: JVM test — feeding fixture fixes through ingest produces `LiveTrackUpdate` stream whose final frozen tail + head equals `TrackBuilder.build()` geometry for the same points (modulo the head window); no additional DB writes.

### Phase 3 — Smooth rendering (trackit-maps)

1. `TrackRenderer`: add incremental mode —
   - one mutable base `Polyline`, `setPoints()` append for frozen-tail growth; live head drawn as a second small polyline rebuilt per update,
   - stop clear-and-redraw per fix; full redraw only on zoom-ladder change (`needsArrowRefresh`) or track switch.
2. **Position puck**: new `PuckController` —
   - `ValueAnimator`, duration ≈ fix interval, spherical lat/lng interpolation,
   - target = dead-reckoned `position + velocity × lookahead` from `LiveTrackUpdate.puck` (avoids perpetual one-interval lag),
   - bearing: shortest signed angle (`Bearing.signedDifference`), EMA α ≈ 0.25, frozen below 1 m/s (GPS bearing is noise when slow).
3. **Camera follow**: opt-in `CameraFollowMode` — chained `animateCamera` (start next ease when a new fix lands, don't await completion), optional bearing-up + 45–60° tilt for the navigation look.
4. Cache the numbered-pin bitmap per number (currently re-rasterised per stop per render).

Acceptance: 20-min drive replay on device — no polyline flicker, puck moves continuously between fixes, steady-state render cost per fix is O(head), not O(track).

### Phase 4 — Turn geometry quality (trackit-geo)

1. **Douglas-Peucker stage** — DP (not Visvalingam) because it preserves corner apexes.

   *Implementation note (2026-08-04): the plan's mechanism was wrong and the code
   corrects it.* Simplifying only **before** the spline cannot bound the polyline:
   `Spline` resamples at a fixed 5 m, so a straight 140 m leg emits ~28 vertices
   regardless of how few knots it was given. Worse, at a 12 s driving cadence
   `SignificantNodes` makes almost every fix a cluster boundary, and boundaries are
   anchors — so the pre-smoothing pass is close to a no-op on exactly the tracks the
   size complaint came from.

   Shipped as **two passes**, each doing what only it can:
   - **Pre-smoothing** (`Simplify.simplify`, anchored on cluster boundaries and
     timeline nodes): removes jitter knots the curve would otherwise be obliged to
     trace. Matters for dense walking data; a shape fix, not a size fix.
   - **Post-smoothing** (`Simplify.simplifyRendered`, anchored on provenance — only
     `ROUNDED_CURVE` samples are candidates): makes the resampled density adaptive.
     A straight leg collapses back to its endpoints, a bend keeps every sample it
     needs. **Measured: 96–97 % fewer vertices on a 50 km track** (10 053 → 360
     straight; 22 092 → 1 047 curved), against the ≥ 60 % target — with every vertex
     of the unsmoothed curve still within ε of the drawn line.

   One tolerance, `TrackOptions.simplifyEpsilonM`, default 2 m (`0` disables). A single
   number is safe at both cadences: at driving spacing a real corner deviates far more
   than 2 m, and on dense walking data 2 m is squarely inside GPS noise.
2. **Per-span spline standdown**: replace the all-or-nothing `SNAPPED_TO_ROAD` bailout — smooth raw spans, pass snapped spans through verbatim, join with shared endpoints. Partially snapped tracks stop rendering 120 m chords on off-road legs.
3. **Snapper segment projection**: project fixes perpendicularly onto road *segments* instead of nearest vertex. Kills along-track jitter and the chorded-polygon look on curves. Forward-only cursor and 80 m guard (EC-101/102) unchanged.
4. `Spline.kt`: antimeridian handling via `normaliseLongitudeDelta` (parity with `BezierRounding`/`Arrows`).
5. Zoom-dependent render simplification (optional, renderer-side): ε = 1.5 px × metersPerPixel(zoom, lat) applied to the frozen tail only.

Acceptance: fixture replay updated goldens reviewed hand-by-hand (geometry intentionally changes); hairpin fixture renders without chord artifacts; encoded polyline size for the 50 km fixture drops ≥ 60 %.

### Phase 5 — Snap upgrades (trackit-snap; optional tier, still zero client deps)

1. Send `&timestamps=` with each `/match` request (materially strengthens OSRM's HMM via speed plausibility); add `gaps=split` and stop flat-mapping matchings across genuine trace gaps.
2. Parse `matchings[].confidence`; discard matchings below threshold (constant in trackit-geo) so the 80 m Snapper guard is no longer the only defence.
3. Parse `tracepoints` to recover input-index ↔ matched-geometry correspondence, enabling timestamp re-attachment to snapped geometry (unblocks time-parameterised interpolation along roads for the live head).
4. If the host app has a planned route: **snap-to-route** — project the puck onto the active route polyline, tolerance 2–3 × accuracy, fall back to raw when persistently off-route. Fully offline; delivers most of Google's "glued to road" effect. New optional input on the live surface, not a pipeline change.

   *Implementation note:* shipped as `RouteSnap` (geo) plus `TrackIt.setActiveRoute` /
   `isOffRoute`. Two decisions worth recording. **Only the puck is snapped** — stored
   points and `buildTrack` are untouched, because the route is the host's claim about
   intent and the track is the SDK's record of evidence. And **drawing and deciding are
   separated**: a fix beyond tolerance is drawn where it was measured (the marker never
   claims a road it is not on), while `isOffRoute` waits for
   `CONSECUTIVE_OFF_ROUTE` misses, so a multipath spike cannot trigger a reroute.

   Sending timestamps needed a port change: `RoadSnapProvider.snap(SnapRequest)` carries
   per-fix time and accuracy, defaulted to the original `snap(path)` so every existing
   provider keeps compiling and working.

Acceptance: recorded OSRM responses replayed in JVM tests; low-confidence fixture no longer snaps; gap fixture no longer draws a false connecting segment.

---

## 5. Honest limits (not replicable client-side)

- 3D-building GNSS corrections (server ML + Google's 3D models) — inherited via FLP only.
- Google's proprietary road graph, Wi-Fi/cell fingerprint DB, fleet priors.
- Offline with no route: best achievable is filtered + spline-smoothed geometry (Phases 1–4). Road-glued geometry requires OSRM (self-hosted service; OkHttp stays compileOnly) or an active route to snap to.

## 6. Order & effort

Phase 2 → 3 produce the visible "Google feel"; Phase 4 produces turn fidelity; Phase 1 underpins both. Recommended order: **1 → 2 → 3 → 4 → 5**. Phases 1–3 are additive (no golden-fixture changes expected); Phase 4 intentionally changes geometry and re-goldens fixtures; Phase 5 is independent and optional.

## 7. Sources

- Newson & Krumm, *Hidden Markov Map Matching Through Noise and Sparseness*, ACM SIGSPATIAL 2009
- Valhalla/Meili map-matching algorithm docs (emission/transition parameters)
- Android Developers Blog: *Improving urban GPS accuracy* (3D mapping-aided corrections, Dec 2020)
- Yuksel et al. — centripetal Catmull-Rom: no cusps/self-intersection within spans
- Google maps-samples marker animation (`LatLngInterpolator.Spherical`)
- OSRM `/match` service documentation (timestamps, gaps, confidence, tidy)
