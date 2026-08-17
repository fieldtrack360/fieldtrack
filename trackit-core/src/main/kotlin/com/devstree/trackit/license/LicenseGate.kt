package com.devstree.trackit.license

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.devstree.trackit.domain.model.ErrorCode

internal class LicenseGate(
    private val context: Context,
    private val verifier: LicenseVerifier = LicenseVerifier(),
) {

    fun check(explicit: String?): LicenseVerdict {
        if (LicenseEnvironment.isWaived(context)) return LicenseVerdict.Waived

        val token = explicit?.takeIf { it.isNotBlank() }
            ?: readManifestToken(context.applicationInfo.metaData)
        return verifier.verify(token, context.packageName)
    }

    fun failure(forVerdict: LicenseVerdict): Failure? = when (forVerdict) {
        LicenseVerdict.Licensed, LicenseVerdict.Waived -> null
        LicenseVerdict.Missing -> Failure(ErrorCode.LICENSE_MISSING, "TrackIt license token is required")
        is LicenseVerdict.Invalid -> Failure(ErrorCode.LICENSE_INVALID, forVerdict.detail)
        is LicenseVerdict.BundleMismatch -> Failure(
            ErrorCode.LICENSE_BUNDLE_MISMATCH,
            "TrackIt license is for ${forVerdict.licensed}, not ${forVerdict.actual}",
        )
    }

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
