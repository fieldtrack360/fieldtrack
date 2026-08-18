package com.devstree.trackit.sample.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.devstree.traker.domain.model.TrackSession
import com.devstree.trackit.sample.TrackItViewModel

@Composable
fun HomeScreen(
    state: TrackItViewModel.UiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestPermissions: () -> Unit,
    onAllowBackground: () -> Unit,
    onShareLog: () -> Unit = {},
    onClearLog: () -> Unit = {},
    onOpenSession: (String) -> Unit = {},
    onTestCurrentLocation: () -> Unit = {},
    onAddTestGeofence: () -> Unit = {},
    onAddTenTestGeofences: () -> Unit = {},
    onListGeofences: () -> Unit = {},
    onGetTestGeofence: () -> Unit = {},
    onRemoveTestGeofence: () -> Unit = {},
    onRemoveAllGeofences: () -> Unit = {},
    onReadGeofenceHistory: () -> Unit = {},
    onClearGeofenceHistory: () -> Unit = {},
) {
    val backgroundActionable = state.backgroundStep != TrackItViewModel.BackgroundStep.GRANTED &&
        state.backgroundStep != TrackItViewModel.BackgroundStep.NOT_APPLICABLE

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (state.isTracking) "Tracking" else "Idle",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text("Motion: ${state.motionState}")
                    Text(
                        "Provider: gps=${state.providerState.gpsEnabled} " +
                            "network=${state.providerState.networkEnabled} " +
                            "powerSave=${state.providerState.powerSaveMode}",
                    )
                    Text("Permission: ${state.permissionTier}")
                    Text("License: ${state.licenseStatus.ifBlank { "unknown" }}")
                    Text(
                        "Heartbeat: ${state.lastHeartbeatAtMs?.toString() ?: "none"}",
                    )
                    Text("Session: ${state.sessionId?.take(8) ?: "—"}")
                    Text("Points accepted: ${state.pointCount}")
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart, enabled = !state.isTracking) { Text("Start") }
                OutlinedButton(onClick = onStop, enabled = state.isTracking) { Text("Stop") }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRequestPermissions) { Text("Grant location") }
                // Opens the rationale dialog, which then routes to the runtime prompt (API 29)
                // or to Settings (API 30+, where the prompt does not exist — EC-05).
                OutlinedButton(onClick = onAllowBackground, enabled = backgroundActionable) {
                    Text("Allow all the time")
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("API checks", style = MaterialTheme.typography.titleMedium)
                    Text(
                        state.apiCheckResult,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "Geofences: ${state.registeredGeofenceCount} · crossings: ${state.geofenceEventCount}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = onTestCurrentLocation,
                        enabled = !state.apiCheckRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Test current location") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onAddTestGeofence,
                            enabled = !state.apiCheckRunning,
                        ) { Text("Add fence here") }
                        OutlinedButton(
                            onClick = onListGeofences,
                            enabled = !state.apiCheckRunning,
                        ) { Text("List fences") }
                    }
                    OutlinedButton(
                        onClick = onAddTenTestGeofences,
                        enabled = !state.apiCheckRunning,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add 10 fences") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onGetTestGeofence,
                            enabled = !state.apiCheckRunning,
                        ) { Text("Get test fence") }
                        OutlinedButton(
                            onClick = onRemoveTestGeofence,
                            enabled = !state.apiCheckRunning,
                        ) { Text("Remove test") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onReadGeofenceHistory,
                            enabled = !state.apiCheckRunning,
                        ) { Text("Read history") }
                        OutlinedButton(
                            onClick = onClearGeofenceHistory,
                            enabled = !state.apiCheckRunning,
                        ) { Text("Clear history") }
                    }
                    OutlinedButton(
                        onClick = onRemoveAllGeofences,
                        enabled = !state.apiCheckRunning && !state.isTracking,
                    ) { Text("Remove all fences") }
                }
            }
        }

        // The capture log is the whole point of the sample on a field run, and until now
        // there was no way to get it off the phone without a laptop and `adb pull`.
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Capture log", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${state.logSizeBytes / 1024} KB · ${state.logPath.substringAfterLast('/')}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onShareLog, enabled = state.logSizeBytes > 0) { Text("Share") }
                        OutlinedButton(onClick = onClearLog) { Text("Clear") }
                    }
                }
            }
        }
        item { Text("Live events", style = MaterialTheme.typography.titleMedium) }
        items(state.log) { line ->
            Text(
                text = line,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
@Preview(showBackground = true)
fun HomeScreenPreview() {
    HomeScreen(
        state = TrackItViewModel.UiState(
            isTracking = true,
            pointCount = 10,
            licenseStatus = "debug installs waived",
            lastHeartbeatAtMs = 1234567890L,
            log = listOf("Started", "Moving", "Point collected")
        ),
        onStart = {},
        onStop = {},
        onRequestPermissions = {},
        onAllowBackground = {}
    )
}
