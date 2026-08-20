package com.field360.tracker.license

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.field360.tracker.domain.model.ErrorCode

internal class LicenseGate(
    private val context: Context,
    private val verifier: LicenseVerifier = LicenseVerifier(),
) {

    fun check(explicit: String?): LicenseVerdict {
        if (LicenseEnvironment.isWaived(context)) return LicenseVerdict.Waived

        return verifier.verify(token(explicit), context.packageName)
    }

    fun failure(forVerdict: LicenseVerdict): Failure? = when (forVerdict) {
        LicenseVerdict.Licensed, LicenseVerdict.Waived -> null
        LicenseVerdict.Missing -> Failure(ErrorCode.LICENSE_MISSING, "Tracker license token is required")
        is LicenseVerdict.Invalid -> Failure(ErrorCode.LICENSE_INVALID, forVerdict.detail)
        is LicenseVerdict.BundleMismatch -> Failure(
            ErrorCode.LICENSE_BUNDLE_MISMATCH,
            "Tracker license is for ${forVerdict.licensed}, not ${forVerdict.actual}",
        )
    }

    /**
     * The token this process is licensed with, from the host's explicit config or the
     * manifest — the same resolution [check] uses.
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

    internal data class Failure(
        val code: ErrorCode,
        val message: String,
    )

    companion object {
        const val infoPlistKey: String = "TrackItLicense"
    }
}

internal object LicenseEnvironment {
    fun isWaived(context: Context): Boolean = hasGetTaskAllow(context)

    fun hasGetTaskAllow(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
