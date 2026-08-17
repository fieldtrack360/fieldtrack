package com.devstree.trackit.data.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.devstree.trackit.domain.model.BatteryInfo
import com.devstree.trackit.domain.model.TrackItEvent
import com.devstree.trackit.geo.port.Clock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One [StateFlow] of battery state, fed from two directions.
 *
 * **Events, for the transitions that matter.** A receiver on plug, unplug, low and okay —
 * four broadcasts a day on a normal phone. Deliberately *not* `ACTION_BATTERY_CHANGED`,
 * which fires on every percentage point and every temperature wobble: registering for it
 * continuously is the thing Android's own documentation tells you not to do, and it would
 * make this SDK a background wake source for a diagnostic field.
 *
 * **Polling, for the drift in between.** [read] is called from the ingest path and refreshes
 * behind a one-minute TTL, so a percentage that slides from 80 to 79 with nothing plugged in
 * is still noticed — at most a minute late, on a value that moves once every several minutes.
 *
 * The two paths converge on [refresh], which emits only on an actual change, so a host
 * collecting [TrackItEvent.BatteryChange] sees transitions rather than a heartbeat.
 */
internal class BatteryMonitor(
    private val context: Context,
    private val probe: BatteryReader,
    private val clock: Clock,
    private val events: MutableSharedFlow<TrackItEvent>,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : BatteryReader {

    private val _state = MutableStateFlow(BatteryInfo.Unknown)
    val state: StateFlow<BatteryInfo> = _state.asStateFlow()

    @Volatile
    private var readAtNanos: Long = 0

    private var registered = false

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    fun start() {
        if (registered) return
        registered = true

        runCatching {
            context.registerReceiver(
                powerReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_POWER_CONNECTED)
                    addAction(Intent.ACTION_POWER_DISCONNECTED)
                    addAction(Intent.ACTION_BATTERY_LOW)
                    addAction(Intent.ACTION_BATTERY_OKAY)
                },
            )
        }

        refresh()
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(powerReceiver) }
    }

    /**
     * The cached value, refreshed at most once per [ttlMs].
     *
     * This is what the ingest path calls, once per fix. Navigation mode ingests a fix a
     * second and the read is a binder call — cheap, but not free on the one thread where a
     * few milliseconds is the difference between a point written and a point lost to a
     * frozen process, which is the same reason the pedometer query is bounded at 1.5 s.
     */
    override fun read(): BatteryInfo {
        val now = clock.elapsedRealtimeNanos()
        if (readAtNanos != 0L && now - readAtNanos < ttlMs * NANOS_PER_MS) return _state.value
        return refresh()
    }

    /**
     * Reads the platform now, regardless of the cache, and emits if anything changed.
     *
     * A probe that throws yields [BatteryInfo.Unknown] rather than propagating: this runs on
     * the ingest path, and the point is the record while the battery is a note in the margin.
     */
    fun refresh(): BatteryInfo {
        val next = runCatching { probe.read() }.getOrDefault(BatteryInfo.Unknown)
        readAtNanos = clock.elapsedRealtimeNanos()

        if (next == _state.value) return next

        _state.value = next
        events.tryEmit(TrackItEvent.BatteryChange(next))
        return next
    }

    private companion object {
        const val DEFAULT_TTL_MS = 60_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
