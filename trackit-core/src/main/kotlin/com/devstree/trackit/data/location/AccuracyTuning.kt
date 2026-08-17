package com.devstree.trackit.data.location

import com.devstree.trackit.GeolocationConfig
import com.devstree.trackit.LocationProviderType
import com.devstree.trackit.geo.filter.TrackItConstants

/**
 * Projects the host's accuracy meter onto the engine's tuning constants.
 *
 * The one place `TrackItConfig` is allowed to move a number inside [TrackItConstants], and
 * it moves exactly three of them. PLAN.md §3 invariant 1 says no algorithm lives above
 * `trackit-geo`; a *ceiling* is not an algorithm, but a config surface that could rewrite
 * arbitrary constants would be one in practice, so the mapping is enumerated here rather
 * than left open.
 *
 * The ceilings that are deliberately **not** touched:
 *  - `accuracyStationaryLimit`, `accuracyHigh`, `accuracyMedium`, `accuracyMaxVehicular` —
 *    these classify a fix rather than admit it. Dragging them along with the moving ceiling
 *    would re-tune the motion state machine as a side effect of a storage decision.
 *  - the sigma-gate family — a fix's error radius and the filter's own disagreement with it
 *    are independent evidence, and collapsing them is how a gate stops catching multipath.
 */
internal object AccuracyTuning {

    fun apply(base: TrackItConstants, geolocation: GeolocationConfig): TrackItConstants {
        val meter = geolocation.accuracy
        return base.copy(
            accuracyMovingMax = meter.maxAccuracyM,
            accuracyRecoveryTrust = meter.recoveryTrustM,
            // EC-32 rejects network fixes worse than 25 m because, on a fused stream, a
            // network fix is what a Wi-Fi teleport arrives as. On NETWORK_ONLY it is what
            // *every* fix arrives as, so the same bound would silence the provider the host
            // explicitly chose. The moving ceiling still applies — the host's own number is
            // simply the only bound left, which is what asking for NETWORK_ONLY means.
            accuracyNlpReject = if (geolocation.providerType == LocationProviderType.NETWORK_ONLY) {
                meter.maxAccuracyM
            } else {
                base.accuracyNlpReject
            },
        )
    }
}
