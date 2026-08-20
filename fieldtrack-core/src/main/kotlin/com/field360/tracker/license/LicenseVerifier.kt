package com.field360.tracker.license

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
         * Intentionally empty here. Until it is filled from the issuing flow every
         * non-debuggable build fails with "Unknown license key id", because an empty map
         * cannot answer any `kid`. That is the correct default for a gate — a licence
         * check that passes when it has nothing to check against is not a gate — but it
         * does mean **release enforcement does not work until this map has real keys**.
         */
        val productionKeys: Map<Int, ByteArray> = emptyMap()
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
