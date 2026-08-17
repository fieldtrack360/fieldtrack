package com.devstree.trackit.data.platform

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.devstree.trackit.domain.model.BatteryInfo
import com.devstree.trackit.domain.model.PowerSource

internal fun interface BatteryReader {
    fun read(): BatteryInfo
}

/**
 * Reads the platform's battery state.
 *
 * The sticky `ACTION_BATTERY_CHANGED` first, because one call answers all of it — level,
 * scale, charging status and plug type. Registering a **null** receiver does not register
 * anything: it returns the broadcast the system is already holding.
 *
 * [BatteryManager.BATTERY_PROPERTY_CAPACITY] is the fallback for the level alone, for
 * devices whose sticky broadcast is missing or nonsensical. It is not the primary because it
 * answers only the percentage, and it is not universally honest either — some OEMs return
 * `Int.MIN_VALUE`, some `-1`, some a flat `0` on a phone that is plainly not flat, which is
 * why anything outside 1..100 is discarded on both paths.
 *
 * No permission is required for any of this.
 */
internal class AndroidBatteryProbe(private val context: Context) : BatteryReader {

    override fun read(): BatteryInfo {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        val fromSticky = sticky?.let {
            batteryPercent(
                level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            )
        }

        return BatteryInfo(
            percent = fromSticky ?: capacityProperty(),
            isCharging = sticky?.let { isCharging(it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) }
                ?: managerIsCharging(),
            powerSource = sticky?.let { powerSource(it.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) }
                ?: PowerSource.UNKNOWN,
        )
    }

    private fun batteryManager(): BatteryManager? =
        context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager

    private fun capacityProperty(): Int? = runCatching {
        batteryManager()?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }.getOrNull()?.takeIf { it in VALID_PERCENT }

    private fun managerIsCharging(): Boolean? =
        runCatching { batteryManager()?.isCharging }.getOrNull()

    private companion object {
        val VALID_PERCENT = 1..100
    }
}

/**
 * `EXTRA_SCALE` is not always 100 — it is whatever unit the device counts in, and dividing by
 * an assumed 100 is how a tablet reporting a scale of 255 ends up at 128 %.
 */
internal fun batteryPercent(level: Int, scale: Int): Int? {
    if (level < 0 || scale <= 0) return null
    return (level * 100f / scale).toInt().coerceIn(0, 100)
}

internal fun isCharging(status: Int): Boolean? = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
    BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> false
    // BATTERY_STATUS_UNKNOWN, and anything a future platform adds. Not "discharging":
    // "we do not know" and "running on battery" are different answers.
    else -> null
}

internal fun powerSource(plugged: Int): PowerSource = when (plugged) {
    0 -> PowerSource.NONE
    BatteryManager.BATTERY_PLUGGED_AC -> PowerSource.AC
    BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.USB
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> PowerSource.WIRELESS
    // BATTERY_PLUGGED_DOCK, by value: the constant is API 33 and this module compiles
    // against a lower floor.
    PLUGGED_DOCK -> PowerSource.DOCK
    else -> PowerSource.UNKNOWN
}

private const val PLUGGED_DOCK = 8
