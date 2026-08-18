package com.devstree.fieldtrack.sample.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.devstree.fieldtrack.sample.TrackerViewModel

/**
 * What the user sees when `BACKGROUND_PERMISSION_MISSING` fires, or when they ask for
 * all-the-time access themselves.
 *
 * It lives in the sample, not the SDK: `PermissionManager` deliberately shows no UI and
 * only hands back the ladder (PERMISSIONS.md §5). The host owns every prompt.
 *
 * The steps are the whole content, not decoration. From Android 11 the OS shows no
 * background-location prompt at all — a runtime request appears to do nothing (EC-05) —
 * so a user who is told "background permission missing" has no way to act on it unless
 * something spells out the Settings path and the exact option to look for.
 */
@Composable
fun BackgroundLocationDialog(
    step: TrackerViewModel.BackgroundStep,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val guide = guideFor(step)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(guide.title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(guide.reason, style = MaterialTheme.typography.bodyMedium)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    guide.steps.forEachIndexed { index, line ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}.", style = MaterialTheme.typography.bodyMedium)
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }

                guide.note?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(guide.confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}

private data class BackgroundGuide(
    val title: String,
    val reason: String,
    val steps: List<String>,
    val note: String?,
    val confirm: String,
)

private const val WHY =
    "Tracker records your route while the app is closed or the screen is off. " +
        "Right now it can only record while the app is open, so any movement after you " +
        "leave the app is lost."

private fun guideFor(step: TrackerViewModel.BackgroundStep): BackgroundGuide = when (step) {

    // Android 11+. There is no prompt to show — Settings is the only route (EC-05).
    TrackerViewModel.BackgroundStep.SETTINGS -> BackgroundGuide(
        title = "Allow location all the time",
        reason = "$WHY\n\nAndroid 11 and later will not ask for this in a pop-up. " +
            "It can only be turned on in Settings:",
        steps = listOf(
            "Tap \"Open settings\" below — this opens Tracker's App info page.",
            "Tap \"Permissions\".",
            "Tap \"Location\".",
            "Select \"Allow all the time\".",
            "Turn on \"Use precise location\" if it is shown.",
            "Press Back to return to Tracker.",
        ),
        note = "Manufacturers word this differently. On the Location screen, pick " +
            "whichever option means all the time — not \"Only while using the app\".",
        confirm = "Open settings",
    )

    // API 29 — the runtime prompt still exists and still includes the option.
    TrackerViewModel.BackgroundStep.PROMPT -> BackgroundGuide(
        title = "Allow location all the time",
        reason = "$WHY\n\nAndroid will ask you now:",
        steps = listOf(
            "Tap \"Continue\" below.",
            "In the Android dialog, choose \"Allow all the time\".",
        ),
        note = "If no dialog appears, Android has remembered an earlier answer. " +
            "Open Settings → Permissions → Location and choose \"Allow all the time\" there.",
        confirm = "Continue",
    )

    // Background is not grantable before fine is granted — asking first is a silent
    // denial, so the ladder has to be climbed one rung at a time (EC-04).
    TrackerViewModel.BackgroundStep.NEEDS_FOREGROUND_FIRST -> BackgroundGuide(
        title = "Grant location first",
        reason = "$WHY\n\nAndroid only offers all-the-time access after basic location " +
            "access is granted, so this takes two steps:",
        steps = listOf(
            "Tap \"Grant location\" below.",
            "Choose \"While using the app\" and, if asked, \"Precise\".",
            "Confirm once more here to move on to the all-the-time step.",
        ),
        note = "Asking for both at once makes Android deny the background half silently, " +
            "which is why it is split.",
        confirm = "Grant location",
    )

    else -> BackgroundGuide(
        title = "Background location",
        reason = "Background location is already available. Nothing to do.",
        steps = emptyList(),
        note = null,
        confirm = "OK",
    )
}
