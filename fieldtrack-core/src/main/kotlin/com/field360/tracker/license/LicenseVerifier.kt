package com.field360.tracker.license

import androidx.annotation.VisibleForTesting
import com.field360.tracker.BuildConfig
import java.util.Base64

internal class LicenseVerifier {

    private val json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
    }

    fun verify(token: String?, bundleID: String): LicenseVerdict {
        val raw = token?.trim().orEmpty()
        if (raw.isEmpty()) return LicenseVerdict.Missing

        val parsed = when (val result = LicenseToken.parse(raw)) {
            is LicenseToken.ParseResult.Success -> result.parts
            is LicenseToken.ParseResult.Failure -> {
                return LicenseVerdict.Invalid(result.reason.message())
            }
        }

        val payloadJson = LicenseToken.decodeBase64URL(parsed.payload)?.toString(Charsets.UTF_8)
            ?: return LicenseVerdict.Invalid("License payload could not be decoded")
        val payload = runCatching {
            json.decodeFromString(LicenseToken.LicensePayload.serializer(), payloadJson)
        }.getOrElse {
            return LicenseVerdict.Invalid("License payload could not be decoded")
        }

        if (!payload.covers(bundleID)) {
            return LicenseVerdict.BundleMismatch(licensed = payload.primary, actual = bundleID)
        }

        val publicKey = productionKeys[payload.kid]
            ?: return LicenseVerdict.Invalid("Unknown license key id ${payload.kid}")

        // The signature is over the base64url payload segment as it appears in the token,
        // not over the decoded JSON: re-encoding is exactly where two implementations
        // stop agreeing on the bytes.
        val signed = Ed25519.verify(
            publicKey = publicKey,
            signature = parsed.signature,
            message = parsed.payload.toByteArray(Charsets.UTF_8),
        )

        return if (signed) LicenseVerdict.Licensed
        else LicenseVerdict.Invalid("License signature verification failed")
    }

    companion object {
        /**
         * Key ids to **32 raw bytes** of Ed25519 public key — the form
         * [Ed25519.PUBLIC_KEY_BYTES] describes, not DER.
         *
         * A **map**, not one key, and that matters even with a single entry. Tokens carry
         * the `kid` they were signed with, so rotating adds an entry rather than replacing
         * one; a single compiled-in value would mean an SDK release at the moment a key
         * has to change, which is the worst moment there is.
         *
         * Parsed from `BuildConfig.LICENSE_SIGNING_KEYS`, which the build file fills from
         * `-PfieldtrackLicenseKeys`, `FIELDTRACK_LICENSE_KEYS`, or `local.properties`:
         *
         * ```properties
         * FIELDTRACK_LICENSE_KEYS=1:Base64OfThirtyTwoRawBytes=,2:AnotherKey=
         * ```
         *
         * Empty when unset, and empty means **every non-debuggable build fails with
         * "Unknown license key id"** — no map can answer a `kid` it does not hold. That is
         * the correct default for a gate: one that passes with nothing to check against is
         * not a gate. It is also invisible in development, where debuggable installs are
         * waived, so it is worth verifying on a release build before shipping one.
         */
        val productionKeys: Map<Int, ByteArray> by lazy {
            parseSigningKeys(BuildConfig.LICENSE_SIGNING_KEYS)
        }

        /**
         * `kid:base64` pairs, comma separated. Malformed entries are dropped rather than
         * thrown on: this is read during the licence gate, and a typo in a build property
         * should fail the gate closed — the outcome an empty map already produces — not
         * crash a host at launch.
         *
         * Whitespace is trimmed because these values get pasted out of a portal, and a
         * trailing newline in `local.properties` is otherwise a very confusing failure.
         */
        @VisibleForTesting
        internal fun parseSigningKeys(raw: String): Map<Int, ByteArray> =
            raw.split(',')
                .mapNotNull { entry ->
                    val parts = entry.trim().split(':', limit = 2)
                    if (parts.size != 2) return@mapNotNull null

                    val kid = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
                    val key = runCatching { Base64.getDecoder().decode(parts[1].trim()) }
                        .getOrNull()
                        ?.takeIf { it.size == Ed25519.PUBLIC_KEY_BYTES }
                        ?: return@mapNotNull null

                    kid to key
                }
                .toMap()
    }
}

internal sealed interface LicenseVerdict {
    data object Licensed : LicenseVerdict
    data object Waived : LicenseVerdict
    data object Missing : LicenseVerdict
    data class Invalid(val detail: String) : LicenseVerdict
    data class BundleMismatch(val licensed: String, val actual: String) : LicenseVerdict
}

internal fun LicenseToken.ParseFailure.message(): String = when (this) {
    LicenseToken.ParseFailure.WrongPrefix -> "License token has the wrong prefix"
    LicenseToken.ParseFailure.WrongShape -> "License token must contain exactly one '.' separator"
    LicenseToken.ParseFailure.PayloadNotBase64 -> "License payload is not base64url"
    LicenseToken.ParseFailure.SignatureNotBase64 -> "License signature is not base64url"
    LicenseToken.ParseFailure.PayloadNotJSON -> "License payload is not valid JSON"
    is LicenseToken.ParseFailure.UnsupportedVersion -> "Unsupported license version $version"
}
