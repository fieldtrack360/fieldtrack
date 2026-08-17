package com.devstree.trackit.geo.filter

import com.devstree.trackit.geo.model.MockPolicy
import com.devstree.trackit.geo.model.Reasons
import com.devstree.trackit.geo.model.TrackFix

/**
 * Stage 0 — structural validity.
 *
 * These are the checks that must run before any arithmetic, because a NaN or an
 * out-of-range coordinate poisons Haversine and therefore every gate downstream
 * (EC-23 … EC-28).
 *
 * `FixMapper` in `trackit-core` applies the same rules at the platform boundary so
 * junk never even becomes a [TrackFix]; this is the engine-side guarantee for fixes
 * that arrive from a fixture replay or a host `insertPoint()` call (EC-86).
 *
 * @return the rejection reason, or `null` when the fix is structurally sound.
 */
public object Validation {

    public fun check(fix: TrackFix, mockPolicy: MockPolicy = MockPolicy.FLAG): String? {
        // Null island — a real coordinate, and never where the user is (EC-24).
        if (fix.latitude == 0.0 && fix.longitude == 0.0) return Reasons.INVALID_COORDINATES

        if (fix.latitude.isNaN() || fix.longitude.isNaN()) return Reasons.INVALID_COORDINATES
        if (fix.latitude.isInfinite() || fix.longitude.isInfinite()) {
            return Reasons.INVALID_COORDINATES
        }

        // Out of range would make Haversine return NaN and poison the filter (EC-25).
        if (fix.latitude !in MIN_LAT..MAX_LAT) return Reasons.INVALID_COORDINATES
        if (fix.longitude !in MIN_LNG..MAX_LNG) return Reasons.INVALID_COORDINATES

        if (fix.accuracy.isNaN() || fix.accuracy.isInfinite()) return Reasons.INVALID_COORDINATES
        if (fix.accuracy <= 0f) return Reasons.INVALID_COORDINATES

        if (fix.isMock && mockPolicy == MockPolicy.REJECT) return Reasons.MOCK_LOCATION

        return null
    }

    private const val MIN_LAT = -90.0
    private const val MAX_LAT = 90.0
    private const val MIN_LNG = -180.0
    private const val MAX_LNG = 180.0
}
