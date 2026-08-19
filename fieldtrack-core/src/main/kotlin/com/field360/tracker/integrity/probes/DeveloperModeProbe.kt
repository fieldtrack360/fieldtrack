package com.field360.tracker.integrity.probes

import android.content.Context
import android.provider.Settings
import com.field360.tracker.SecurityConfig
import com.field360.tracker.integrity.IntegritySignal
import com.field360.tracker.integrity.internal.IntegrityObservation
import com.field360.tracker.integrity.internal.IntegrityProbe

/**
 * Developer options and USB debugging.
 *
 * Neither is an attack. Both are the door every location-spoofing walkthrough opens
 * first: "select mock location app" lives inside developer options, and `adb` is how a
 * fix gets injected without one. Reported as two signals rather than one because they are
 * different risk levels — a developer's own phone has the options on and debugging off,
 * while a rig set up to spoof has both.
 */
internal class DeveloperModeProbe(
    private val context: Context,
) : IntegrityProbe {

    override fun observe(config: SecurityConfig): List<IntegrityObservation> = buildList {
        val resolver = context.contentResolver

        if (Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0) {
            add(
                IntegrityObservation(
                    signal = IntegritySignal.DEVELOPER_MODE_ENABLED,
                    detail = "developer options are enabled",
                ),
            )
        }

        if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) != 0) {
            add(
                IntegrityObservation(
                    signal = IntegritySignal.ADB_ENABLED,
                    detail = "USB debugging is enabled",
                ),
            )
        }
    }
}
