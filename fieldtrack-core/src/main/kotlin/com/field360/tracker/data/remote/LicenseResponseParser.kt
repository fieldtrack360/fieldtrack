package com.field360.tracker.data.remote

import com.field360.tracker.domain.model.ApiError
import com.field360.tracker.domain.model.ApiErrorCode
import com.field360.tracker.domain.model.ApiResult
import com.field360.tracker.domain.model.LicenseStatus
import com.field360.tracker.domain.model.SignedVerdict
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The `/verify` body, read into fields. **Reading is all it does.**
 *
 * Split out of `GsonVerdictAuthenticator` because the two jobs have opposite failure
 * meanings and were previously interleaved in one method: a field that will not parse is a
 * malformed response, while a signature that will not verify is a *hostile* one. Keeping
 * them apart means the log can say which, and the authenticator reads as three checks
 * rather than three checks tangled through a dozen `json.string(...)` calls.
 *
 * [raw] is carried through untouched. The signature covers the exact bytes that arrived, so
 * a parsed object is never a substitute for them — the store persists [raw] and re-verifies
 * it on the way back out.
 */
internal data class ParsedVerifyResponse(
    val raw: String,
    val json: JsonObject,
    val signature: String,
    val status: String,
    val valid: Boolean,
    val keyId: String,
    val packageName: String,
    val checkedAt: String,
    val ttlSeconds: Long,
    val nonce: String?,
    val reason: String?,
) {
    /**
     * Only called once every check has passed, which is why [SignedVerdict] has no other
     * producer — holding one is already proof of authenticity.
     */
    fun toVerdict(): SignedVerdict = SignedVerdict(
        status = LicenseStatus.fromWire(status),
        valid = valid,
        keyId = keyId,
        packageName = packageName,
        checkedAt = checkedAt,
        ttlSeconds = ttlSeconds,
        nonce = nonce,
        reason = reason,
        raw = raw,
    )
}

/**
 * Turns a response body into fields, or says why it could not.
 *
 * Every failure is [ApiErrorCode.MALFORMED_BODY] with a note naming the field, because
 * "the licence check stopped working" and "the backend renamed `ttl_seconds`" are the same
 * event from the outside, and only the note tells them apart. Nothing here throws: this
 * runs on a WorkManager thread behind a check whose contract is to fail open.
 */
internal object LicenseResponseParser {

    fun parse(raw: String): ApiResult<ParsedVerifyResponse> {
        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
            ?: return malformed("not a JSON object")

        val signature = json.string(SIGNATURE_FIELD)
            // The server signs in production. An unsigned response is an untrusted one,
            // and reaching here means something is answering that is not the server.
            ?: return malformed("missing $SIGNATURE_FIELD")

        val status = json.string("status") ?: return malformed("missing status")
        val keyId = json.string("key_id") ?: return malformed("missing key_id")
        val packageName = json.string("package_name") ?: return malformed("missing package_name")
        val checkedAt = json.string("checked_at") ?: return malformed("missing checked_at")
        val valid = json.bool("valid") ?: return malformed("missing valid")
        val ttlSeconds = json.long("ttl_seconds") ?: return malformed("missing ttl_seconds")

        return ApiResult.Success(
            ParsedVerifyResponse(
                raw = raw,
                json = json,
                signature = signature,
                status = status,
                valid = valid,
                keyId = keyId,
                packageName = packageName,
                checkedAt = checkedAt,
                ttlSeconds = ttlSeconds,
                nonce = json.string("nonce"),
                reason = json.string("reason"),
            ),
            httpStatus = PARSED,
        )
    }

    private fun malformed(detail: String) =
        ApiResult.Failure(ApiError(ApiErrorCode.MALFORMED_BODY, detail = detail))

    /**
     * Null for absent, JSON-null, non-string or empty.
     *
     * Gson's `asString` would coerce a number into a string and throw on a missing key.
     * Neither is wanted: a `key_id` that arrived as a number is not a `key_id` worth
     * comparing against, and a missing field is a failed check rather than an exception on
     * a background thread.
     */
    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.long(key: String): Long? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.let { runCatching { it.asLong }.getOrNull() }

    /** Not an HTTP status — parsing has none. Kept out of the log by [ApiError.describe]. */
    private const val PARSED = 0
}
