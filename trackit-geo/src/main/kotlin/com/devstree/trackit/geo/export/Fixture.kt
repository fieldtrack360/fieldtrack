package com.devstree.trackit.geo.export

import com.devstree.trackit.geo.model.TrackFix
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A recorded run plus the verdict each fix received — the wire format behind
 * `exportFixture()` / `replayFixture()` (POLYLINE-JSON.md §4).
 *
 * This is the mechanism that turns every accuracy complaint into a regression test,
 * and it is what lets constants be tuned without a phone in hand. [expected] is the
 * golden file: a constant change that flips any entry fails CI and has to be argued
 * for (PLAN.md §9).
 */
@Serializable
public data class Fixture(
    val version: Int = FORMAT_VERSION,
    val name: String,
    val recordedAtMs: Long = 0,
    val device: FixtureDevice? = null,
    val fixes: List<FixtureFix>,
    val expected: List<FixtureVerdict> = emptyList(),
) {
    public companion object {
        public const val FORMAT_VERSION: Int = 1
    }
}

@Serializable
public data class FixtureDevice(
    val model: String = "",
    val sdkInt: Int = 0,
    val oem: String = "",
)

/**
 * One recorded fix.
 *
 * Carries **both** clocks. That is deliberate: the wall→monotonic migration is a real
 * behavioural risk, so a fixture can be replayed under either clock and the decision
 * diff inspected (PLAN.md §9).
 */
@Serializable
public data class FixtureFix(
    val timeMs: Long,
    @SerialName("elapsedNs") val elapsedRealtimeNanos: Long,
    val lat: Double,
    val lng: Double,
    val acc: Float,
    val spd: Float = 0f,
    val brg: Float = 0f,
    val hasSpeed: Boolean = false,
    val hasBearing: Boolean = false,
    val provider: String = "gps",
    val mock: Boolean = false,
    val steps: Int? = null,
    /** Hardware confidence in `spd`/`brg`; `null` = not reported (SMOOTH-NAV-PLAN Phase 1). */
    val spdAcc: Float? = null,
    val brgAcc: Float? = null,
) {
    public fun toTrackFix(): TrackFix = TrackFix(
        timeMs = timeMs,
        elapsedRealtimeNanos = elapsedRealtimeNanos,
        receivedAtElapsedNanos = elapsedRealtimeNanos,
        latitude = lat,
        longitude = lng,
        accuracy = acc,
        speedMps = spd,
        bearingDeg = brg,
        hasSpeed = hasSpeed,
        hasBearing = hasBearing,
        provider = provider,
        isMock = mock,
        speedAccuracyMps = spdAcc,
        bearingAccuracyDeg = brgAcc,
    )
}

/** `i` indexes [Fixture.fixes]; `verdict` is ACCEPT | SKIP | REJECT. */
@Serializable
public data class FixtureVerdict(
    val i: Int,
    val verdict: String,
    val reason: String,
)
