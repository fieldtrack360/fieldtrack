package com.devstree.traker.integrity

import kotlinx.serialization.Serializable

/**
 * One observable property of the device or process that can be used to forge location data.
 *
 * The [bit] assignments are **frozen**. They are stamped onto every stored point and
 * uploaded with it, so a backend that has persisted a bitmask must be able to read it
 * against this table forever. Append new signals at the next free bit; never renumber.
 */
public enum class IntegritySignal(internal val bit: Int) {
    /** A non-system accessibility service is enabled — the usual driver for UI automation. */
    ACCESSIBILITY_SERVICE_ACTIVE(0),

    /** Developer options are switched on. Prerequisite for selecting a mock-location app. */
    DEVELOPER_MODE_ENABLED(1),

    /** USB debugging is on. Strictly worse than [DEVELOPER_MODE_ENABLED] — `adb` can inject. */
    ADB_ENABLED(2),

    /** Frida, Xposed or a comparable hooking framework is loaded into this process. */
    HOOKING_FRAMEWORK_DETECTED(3),

    /** A native or Java debugger is attached (`TracerPid`, `Debug.isDebuggerConnected`). */
    DEBUGGER_ATTACHED(4),

    /** Automatic time **and** automatic time zone are both off — a manual clock. */
    AUTO_TIME_DISABLED(5),

    /** The device time zone does not belong to the country of the serving cellular network. */
    TIMEZONE_MISMATCH(6),

    /** An installed, visible package holds the mock-location app-op. */
    MOCK_LOCATION_APP_SELECTED(7),

    /** At least one fix arrived flagged as mock by the platform. */
    MOCK_LOCATION_FIX(8),

    /** System wall clock disagrees with GNSS UTC by more than the configured tolerance. */
    CLOCK_SKEWED(9),
    ;

    /** This signal's bit in [IntegrityReport.flags]. */
    public val mask: Int get() = 1 shl bit
}

/**
 * What the SDK does about a signal.
 *
 * [WARN] is not "ignore": the finding is reported, stamped on every point and uploaded.
 * It only stops short of refusing to track.
 */
public enum class IntegrityPolicy {
    /** Do not probe, do not report. */
    ALLOW,

    /** Report, stamp, upload — keep tracking. */
    WARN,

    /** Report, stamp, upload — and refuse to `ready()`/`start()`, stopping a live session. */
    BLOCK,
}

/**
 * One signal, as it was observed and as policy judged it.
 *
 * @property confidence 0..100. Factual queries (a settings flag, an app-op) answer 100.
 *   [IntegritySignal.HOOKING_FRAMEWORK_DETECTED] is inferred from several weak
 *   indicators and answers the summed weight, capped at 100 — a host logging findings
 *   can tell "Frida's default port answered" from "Frida's agent is mapped into us".
 * @property detail short, human-readable, safe to log. Never contains a full package list;
 *   long lists are truncated at [MAX_DETAIL_ITEMS] items.
 */
@Serializable
public data class IntegrityFinding(
    val signal: IntegritySignal,
    val policy: IntegrityPolicy,
    val detail: String,
    val confidence: Int = FULL_CONFIDENCE,
) {
    public companion object {
        public const val FULL_CONFIDENCE: Int = 100
        public const val MAX_DETAIL_ITEMS: Int = 5
    }
}

/**
 * The result of one evaluation pass.
 *
 * @property waived `true` in a debuggable host app. Probes did not run, [findings] is
 *   empty, and nothing blocks. This is the deliberate development escape hatch — see
 *   `IntegrityEnvironment`.
 */
@Serializable
public data class IntegrityReport(
    val evaluatedAtMs: Long = 0,
    val waived: Boolean = false,
    val findings: List<IntegrityFinding> = emptyList(),
) {
    /** `true` when at least one finding carries [IntegrityPolicy.BLOCK]. */
    public val blocked: Boolean
        get() = findings.any { it.policy == IntegrityPolicy.BLOCK }

    /**
     * The frozen bitmask of every signal in [findings], regardless of policy.
     *
     * A `WARN` signal is in the mask: the backend decides what a warned point is worth,
     * and it cannot decide on data it was never sent.
     */
    public val flags: Int
        get() = findings.fold(0) { acc, finding -> acc or finding.signal.mask }

    /** Signals that carry [IntegrityPolicy.BLOCK], in declaration order. */
    public val blockingSignals: List<IntegritySignal>
        get() = findings.filter { it.policy == IntegrityPolicy.BLOCK }.map { it.signal }

    /** One line, suitable for an `ErrorCode.DEVICE_INTEGRITY_BLOCKED` message. */
    public fun describeBlocking(): String =
        findings.filter { it.policy == IntegrityPolicy.BLOCK }
            .joinToString("; ") { "${it.signal.name} (${it.detail})" }

    public companion object {
        /** The report before anything has been evaluated. Not "clean" — "unknown". */
        public val Unknown: IntegrityReport = IntegrityReport()

        /** The report a debuggable build always gets. */
        public fun waived(atMs: Long): IntegrityReport =
            IntegrityReport(evaluatedAtMs = atMs, waived = true, findings = emptyList())
    }
}
