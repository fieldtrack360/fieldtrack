package com.devstree.trackit.sample.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.devstree.trackit.geo.model.FixDecision
import com.devstree.trackit.geo.model.Verdict
import com.devstree.trackit.sample.TrackItViewModel

/**
 * The decision log — a queryable table, not a log file.
 *
 * The reason vocabulary *is* the debugging language, so what matters is the shape of
 * the distribution. A healthy parked hour is dominated by `Drift Suppressed`,
 * `HeartBeat Skipped` and `Sigma Gate Outlier`; a healthy 30-minute drive is ~25-35
 * `Vehicular` accepts with **no** `Sigma Forced Reset`. Repeated forced resets while
 * driving mean the drift-tolerance scaling is wrong (spec §8.4).
 */
@Composable
fun DecisionLogScreen(
    state: TrackItViewModel.UiState,
    onOpenSession: (String) -> Unit = {},
) {
    var showAccept by remember { mutableStateOf(true) }
    var showSkip by remember { mutableStateOf(true) }
    var showReject by remember { mutableStateOf(true) }

    val visible = state.decisions.filter { decision ->
        when (decision.verdict) {
            is Verdict.Accept -> showAccept
            is Verdict.Skip -> showSkip
            is Verdict.Reject -> showReject
        }
    }

    Column(Modifier.fillMaxSize()) {
        SessionPicker(
            sessions = state.sessions,
            selectedId = state.selectedSessionId,
            onOpenSession = onOpenSession,
        )

        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(showAccept, { showAccept = !showAccept }, { Text("Accept") })
                FilterChip(showSkip, { showSkip = !showSkip }, { Text("Skip") })
                FilterChip(showReject, { showReject = !showReject }, { Text("Reject") })
            }

            Text(
                text = summarise(state.decisions),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            HorizontalDivider()

            if (visible.isEmpty()) {
                // Distinguish "this session recorded nothing" from "you filtered
                // everything out" — they look identical and have different answers.
                Centered(
                    if (state.decisions.isEmpty()) {
                        "No decisions recorded for this session."
                    } else {
                        "All ${state.decisions.size} decisions are hidden by the filters above."
                    },
                )
                return@Column
            }

            LazyColumn(Modifier.fillMaxWidth()) {
                items(visible) { decision -> DecisionRow(decision) }
            }
        }
    }
}

@Composable
private fun DecisionRow(decision: FixDecision) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = verdictLabel(decision),
                color = verdictColour(decision),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = decision.reason,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        // The numbers that let a "Sigma Gate Outlier" be argued with rather than guessed at.
        Text(
            text = "moved ${"%.0f".format(decision.distanceMovedM)}m · " +
                "σ ${"%.0f".format(decision.sigma)} · " +
                "gate ${"%.0f".format(decision.threshold)} · " +
                "spd ${"%.1f".format(decision.effectiveSpeedMps)}m/s · " +
                "acc ${"%.0f".format(decision.fix.accuracy)}m",
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun summarise(decisions: List<FixDecision>): String {
    if (decisions.isEmpty()) return "—"
    val byReason = decisions.groupingBy { it.reason }.eachCount()
        .entries.sortedByDescending { it.value }.take(TOP_REASONS)
    return byReason.joinToString("  ") { "${it.key}×${it.value}" }
}

private fun verdictLabel(decision: FixDecision): String = when (decision.verdict) {
    is Verdict.Accept -> "ACCEPT"
    is Verdict.Skip -> "SKIP  "
    is Verdict.Reject -> "REJECT"
}

private fun verdictColour(decision: FixDecision): Color = when (decision.verdict) {
    is Verdict.Accept -> Color(0xFF2E7D32)
    is Verdict.Skip -> Color(0xFFF57C00)
    is Verdict.Reject -> Color(0xFFC62828)
}

private const val TOP_REASONS = 4
