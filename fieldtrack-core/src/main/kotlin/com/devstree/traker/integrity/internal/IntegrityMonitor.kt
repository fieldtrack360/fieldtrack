package com.devstree.traker.integrity.internal

import com.devstree.traker.SecurityConfig
import com.devstree.traker.domain.model.TrackerEvent
import com.devstree.traker.integrity.IntegrityReport
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The live view of device integrity: one [StateFlow], one cached bitmask, one event.
 *
 * The split from [IntegrityEvaluator] is a cost decision. Evaluation reads `/proc`, walks
 * the installed-package list and opens a loopback socket — tens of milliseconds, fine at
 * `ready()`, at `start()` and on a fifteen-minute health loop, and unacceptable on the
 * ingest path, which runs once a second in navigation mode. So the ingest path reads
 * [flags], a plain `Int` field, and never probes.
 *
 * [TrackerEvent.IntegrityChange] is emitted on a change of the flag set, not on every
 * evaluation: a host collecting it sees transitions, matching how `BatteryMonitor` and
 * `ProviderStateMonitor` behave.
 */
internal class IntegrityMonitor(
    private val evaluator: IntegrityEvaluator,
    private val events: MutableSharedFlow<TrackerEvent>,
    private val feed: IntegrityFeed,
) {

    private val _state = MutableStateFlow(IntegrityReport.Unknown)
    val state: StateFlow<IntegrityReport> = _state.asStateFlow()

    /**
     * The last report's bitmask, for the per-fix stamp.
     *
     * Held separately from [_state] so the ingest path does no `StateFlow` read and no
     * list traversal per fix.
     */
    @Volatile
    var flags: Int = 0
        private set

    val current: IntegrityReport get() = _state.value

    /** Re-probes and publishes. Returns the fresh report. */
    fun evaluate(config: SecurityConfig): IntegrityReport {
        val report = evaluator.evaluate(config)
        publish(report)
        return report
    }

    /** Clears per-session evidence. Called from `start()`, before the first fix. */
    fun onSessionStart() {
        feed.reset()
    }

    private fun publish(report: IntegrityReport) {
        val previous = _state.value
        _state.value = report
        flags = report.flags

        if (previous.flags != report.flags || previous.waived != report.waived) {
            events.tryEmit(TrackerEvent.IntegrityChange(report))
        }
    }
}
