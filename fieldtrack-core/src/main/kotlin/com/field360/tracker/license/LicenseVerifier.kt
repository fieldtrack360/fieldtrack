package com.field360.tracker.license

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

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

        val signature = runCatching { Signature.getInstance("Ed25519") }.getOrNull()
            ?: return LicenseVerdict.Invalid("Ed25519 is unavailable on this device")

        return runCatching {
            val keyFactory = KeyFactory.getInstance("Ed25519")
            val key = keyFactory.generatePublic(X509EncodedKeySpec(publicKey))
            signature.initVerify(key)
            signature.update(parsed.payload.toByteArray(Charsets.UTF_8))
            if (signature.verify(parsed.signature)) LicenseVerdict.Licensed
            else LicenseVerdict.Invalid("License signature verification failed")
        }.getOrElse { error ->
            LicenseVerdict.Invalid(error.message ?: error::class.simpleName.orEmpty())
        }
    }

    companion object {
        /**
         * Key ids to Ed25519 public keys. The production map is intentionally empty here;
         * fill it with the real keys from the issuing flow for release enforcement.
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
