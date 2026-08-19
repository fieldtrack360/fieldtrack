package com.field360.tracker.data.location

import android.location.Location
import android.os.Build
import com.field360.traker.geo.model.MockPolicy
import com.field360.traker.geo.model.TrackFix
import com.field360.traker.geo.port.Clock

/**
 * The **only** place `android.location.Location` is touched (PLAN.md §3 invariant 2),
 * and therefore the only place platform validity rules live.
 *
 * Everything above this boundary works on [TrackFix], which is why the engine compiles
 * and runs on a plain JVM.
 */
internal class FixMapper(
    private val clock: Clock,
) {

    /** @return `null` when the fix is unusable; the caller simply drops it. */
    fun map(location: Location, policy: MockPolicy, maxDeliveryAgeMs: Long = MAX_DELIVERY_AGE_MS): TrackFix? {
        // EC-33: the OS replays cached fixes on resume and flushes batched backlogs.
        // Age is measured on the monotonic clock, never on wall time.
        val ageMs = (clock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / NANOS_PER_MILLI
        if (ageMs > maxDeliveryAgeMs) return null

        if (location.latitude == 0.0 && location.longitude == 0.0) return null // EC-24
        if (location.latitude.isNaN() || location.longitude.isNaN()) return null // EC-25
        if (location.latitude !in MIN_LAT..MAX_LAT) return null
        if (location.longitude !in MIN_LNG..MAX_LNG) return null

        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
        if (isMock && policy == MockPolicy.REJECT) return null // EC-28

        return TrackFix(
            timeMs = location.time,
            // A1 — the fix's own monotonic stamp, not construction or delivery time.
            elapsedRealtimeNanos = location.elapsedRealtimeNanos,
            receivedAtElapsedNanos = clock.elapsedRealtimeNanos(),
            latitude = location.latitude,
            longitude = location.longitude,
            // EC-23: a zero accuracy would drive Kalman gain to 1 and track noise exactly.
            accuracy = location.accuracy.coerceIn(MIN_ACCURACY_M, MAX_ACCURACY_M),
            altitude = if (location.hasAltitude()) location.altitude else null,
            verticalAccuracy = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters else null,
            speedMps = if (location.hasSpeed()) location.speed else 0f,
            bearingDeg = if (location.hasBearing()) location.bearing else 0f,
            // A8 — read the flags, never assume them. Defaulting these to true silently
            // disables the network-fix rejection, the main defence against WiFi teleports.
            hasSpeed = location.hasSpeed(),
            hasBearing = location.hasBearing(),
            provider = location.provider ?: TrackFix.UNKNOWN_PROVIDER,
            isMock = isMock,
            // Same contract as the validity flags above: read what the hardware claims,
            // never assume. `null` — not 0 — when the platform reports no confidence.
            speedAccuracyMps = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
            bearingAccuracyDeg = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees else null,
        )
    }

    private companion object {
        const val MAX_DELIVERY_AGE_MS = 60_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val MIN_ACCURACY_M = 1f
        const val MAX_ACCURACY_M = 10_000f
        const val MIN_LAT = -90.0
        const val MAX_LAT = 90.0
        const val MIN_LNG = -180.0
        const val MAX_LNG = 180.0
    }
}
