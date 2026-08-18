package com.devstree.traker.integrity

import com.devstree.traker.SecurityConfig
import com.devstree.traker.geo.port.Clock

/**
 * Runs the probes, applies policy, produces one [IntegrityReport].
 *
 * Pure with respect to Android: probes arrive as a list of [IntegrityProbe] and the waiver
 * as a lambda, so the whole policy matrix is exercised on the JVM with fakes. The Android
 * pieces live in `integrity/probes`, one class each, each individually Robolectric-tested.
 *
 * There is deliberately no `fun isTampered(): Boolean` anywhere in this layer. Callers
 * read the report and decide, so patching out one method does not disable enforcement
 * everywhere at once.
 */
internal class IntegrityEvaluator(
    private val probes: List<IntegrityProbe>,
    private val clock: Clock,
    private val isWaived: () -> Boolean,
) {

    fun evaluate(config: SecurityConfig): IntegrityReport {
        val now = clock.wallTimeMs()

        // Two separate exits, both producing a waived report. `enabled = false` is the host
        // switching the layer off in config; `isWaived()` is a debuggable build. Neither
        // runs a probe — in debug that is the point, and with the layer off the host has
        // said it does not want the work done.
        if (isWaived() || !config.enabled) return IntegrityReport.waived(now)

        val findings = probes
            .flatMap { it.observeSafely(config) }
            .mapNotNull { observation ->
                val policy = config.policyFor(observation.signal)
                if (policy == IntegrityPolicy.ALLOW) {
                    null
                } else {
                    IntegrityFinding(
                        signal = observation.signal,
                        policy = policy,
                        detail = observation.detail,
                        confidence = observation.confidence,
                    )
                }
            }
            // A probe can legitimately report the same signal twice (two accessibility
            // services, two hooking indicators). One finding per signal, keeping the
            // most confident, so the bitmask and the finding list agree on cardinality.
            .groupBy { it.signal }
            .map { (_, group) -> group.maxBy { it.confidence } }
            .sortedBy { it.signal.ordinal }

        return IntegrityReport(evaluatedAtMs = now, waived = false, findings = findings)
    }
}

/** The signal-to-policy map. One place, so a new signal cannot silently default to allowed. */
internal fun SecurityConfig.policyFor(signal: IntegritySignal): IntegrityPolicy = when (signal) {
    IntegritySignal.ACCESSIBILITY_SERVICE_ACTIVE -> accessibility
    IntegritySignal.DEVELOPER_MODE_ENABLED,
    IntegritySignal.ADB_ENABLED,
    -> developerMode

    IntegritySignal.HOOKING_FRAMEWORK_DETECTED,
    IntegritySignal.DEBUGGER_ATTACHED,
    -> hooking

    IntegritySignal.AUTO_TIME_DISABLED,
    IntegritySignal.TIMEZONE_MISMATCH,
    IntegritySignal.CLOCK_SKEWED,
    -> clock

    IntegritySignal.MOCK_LOCATION_APP_SELECTED,
    IntegritySignal.MOCK_LOCATION_FIX,
    -> mockLocation
}
