package com.field360.fieldtrack.sample.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.field360.fieldtrack.sample.LicenseAlert

/**
 * What the SDK said about the licence, when it was not "fine".
 *
 * The SDK shows no UI of its own — it reports a code and the host decides what a user
 * sees, the same way the permission ladder behaves. This is the sample's answer, and it
 * is deliberately a developer's dialog rather than a customer's: it shows the raw
 * `ErrorCode` and the SDK's own message, because the person running the sample is
 * integrating, not being sold to.
 *
 * A shipping app would map these to something a user can act on — "your licence expired,
 * tap to renew" — and would show nothing at all for the diagnostics.
 */
@Composable
internal fun LicenseAlertDialog(
    alert: LicenseAlert,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (alert.stopsTracking) "Licence problem" else "Licence notice") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(alert.headline, style = MaterialTheme.typography.bodyMedium)

                Text(
                    alert.detail,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )

                if (alert.stopsTracking) {
                    Text(
                        "Tracking has stopped. Only a revoked or expired licence does " +
                            "this — everything else here is informational.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    // Worth stating: the fail-open design means most of what appears in
                    // this dialog changed nothing, and a reader who assumes otherwise
                    // will go looking for a fault that is not there.
                    Text(
                        "Tracking is unaffected.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("OK") }
        },
    )
}
