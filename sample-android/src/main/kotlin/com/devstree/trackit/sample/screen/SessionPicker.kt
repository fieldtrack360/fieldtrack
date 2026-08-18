package com.devstree.trackit.sample.screen

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.devstree.traker.domain.model.TrackSession
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Which session the diagnostic screens are showing.
 *
 * **One implementation, used by Track, Debug and Decisions.** Three copies of a session
 * chooser would drift in exactly the way `Arrows.place()` exists to prevent — different
 * date formats, one of them forgetting to mark the open session — and the three tabs are
 * meant to be three views of the *same* selection. Whichever tab you change it on, the
 * others follow, because they all read `selectedSessionId`.
 *
 * A dropdown rather than a trip back to Home: comparing yesterday's commute with today's
 * is the actual field-testing loop, and making that a two-screen round trip each time is
 * what stops anyone doing it.
 */
@Composable
internal fun SessionPicker(
    sessions: List<TrackSession>,
    selectedId: String?,
    onOpenSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = sessions.firstOrNull { it.id == selectedId }

    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedButton(onClick = { expanded = true }, enabled = sessions.isNotEmpty()) {
            Text(
                when {
                    sessions.isEmpty() -> "No sessions"
                    selected != null -> "${sessionLabel(selected)}  ▾"
                    else -> "Pick a session  ▾"
                },
            )
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sessions.forEach { session ->
                val sessionID = session.id
                val sessionTime = sessionLabel(session)
                Log.e("SESSION_DETAILS", "$sessionID-TIME-$sessionTime")
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(sessionTime)
                            Text(
                                text = "ID-$sessionID",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onOpenSession(session.id)
                    },
                )
            }
        }
    }
}

/** `Tue 4 Aug · 08:12 · 34 min`. */
internal fun sessionLabel(session: TrackSession): String =
    "${sessionStart(session.startedAtMs)} · ${sessionDuration(session)}"

internal fun sessionStart(atMs: Long): String =
    SESSION_STAMP.format(Instant.ofEpochMilli(atMs).atZone(ZoneId.systemDefault()))

internal fun sessionDuration(session: TrackSession): String {
    val end = session.endedAtMs ?: return "ongoing"
    val minutes = (end - session.startedAtMs) / 60_000
    return if (minutes < 60) "$minutes min" else "${minutes / 60}h ${minutes % 60}m"
}

internal const val SHORT_ID = 8

private val SESSION_STAMP: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d MMM · HH:mm", Locale.US)
