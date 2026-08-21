package com.field360.tracker.license

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle

/**
 * Resolves the licence token this process runs with. The offline signature gate that used
 * to live here has been removed — the token now feeds only the online revocation check.
 */
internal class LicenseGate(
    private val context: Context,
) {

    /**
     * The token this process is licensed with, from the host's explicit config or the
     * manifest.
     *
     * Exposed because the background revocation check needs the token and cannot get it
     * from [com.field360.tracker.data.repository.ConfigStore]: `TrackerConfig.license` is
     * deliberately dropped on serialisation, so a token resurrected from disk can never
     * turn "I updated my licence" into a bug report.
     */
    fun token(explicit: String?): String? = explicit?.takeIf { it.isNotBlank() }
        ?: readManifestToken(context.applicationInfo.metaData)

    private fun readManifestToken(metaData: Bundle?): String? =
        metaData?.getString(infoPlistKey)?.takeIf { it.isNotBlank() }

    companion object {
        const val infoPlistKey: String = "TrackItLicense"
    }
}

internal object LicenseEnvironment {
    fun hasGetTaskAllow(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
