package com.devstree.traker.license

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

internal object LicenseToken {
    const val prefix: String = "TRACKIT-"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    internal data class Parts(
        val payload: String,
        val signature: ByteArray,
    )

    @Serializable
    internal data class LicensePayload(
        val primary: String,
        val also: List<String> = emptyList(),
        val kid: Int,
        val v: Int,
        val licensee: String? = null,
        val issued: Long? = null,
    ) {
        fun covers(bundleID: String): Boolean = primary == bundleID || bundleID in also
    }

    internal sealed interface ParseFailure {
        data object WrongPrefix : ParseFailure
        data object WrongShape : ParseFailure
        data object PayloadNotBase64 : ParseFailure
        data object SignatureNotBase64 : ParseFailure
        data object PayloadNotJSON : ParseFailure
        data class UnsupportedVersion(val version: Int) : ParseFailure
    }

    internal sealed interface ParseResult {
        data class Success(val parts: Parts) : ParseResult
        data class Failure(val reason: ParseFailure) : ParseResult
    }

    fun parse(token: String): ParseResult {
        if (!token.startsWith(prefix)) return ParseResult.Failure(ParseFailure.WrongPrefix)

        val body = token.removePrefix(prefix)
        val segments = body.split('.', limit = 2)
        if (segments.size != 2) return ParseResult.Failure(ParseFailure.WrongShape)

        val payload = segments[0]
        val signature = segments[1]
        val payloadBytes = decodeBase64URL(payload) ?: return ParseResult.Failure(ParseFailure.PayloadNotBase64)
        val signatureBytes = decodeBase64URL(signature) ?: return ParseResult.Failure(ParseFailure.SignatureNotBase64)

        val payloadJson = payloadBytes.toString(Charsets.UTF_8)

        val parsed = runCatching { json.decodeFromString(LicensePayload.serializer(), payloadJson) }
            .getOrNull() ?: return ParseResult.Failure(ParseFailure.PayloadNotJSON)

        if (parsed.v != 1) return ParseResult.Failure(ParseFailure.UnsupportedVersion(parsed.v))

        return ParseResult.Success(Parts(payload = payload, signature = signatureBytes))
    }

    fun decodeBase64URL(string: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(string) }.getOrNull()
}
