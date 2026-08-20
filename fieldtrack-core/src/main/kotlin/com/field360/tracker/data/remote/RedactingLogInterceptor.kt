package com.field360.tracker.data.remote

import com.field360.tracker.API_TAG
import com.field360.tracker.sdkLog
import com.field360.traker.geo.port.TrackLogger
import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer

/**
 * Wire-level logging for the licence client, with the credential taken out.
 *
 * **This exists instead of `okhttp3:logging-interceptor`, not alongside it.** That library
 * at `BODY` level writes the request body verbatim, and this request body carries
 * `access_key` — the licence credential. Logcat is readable by `adb` on any developer
 * machine and by anything holding `READ_LOGS`, so `HttpLoggingInterceptor(BODY)` on this
 * client would be a credential handed to whoever is watching. Its `redactHeader` does not
 * help: the key is in the body, not a header, and the library has no body redaction.
 *
 * What is left after redaction is the part worth having. The release AAR is R8-minified and
 * Gson maps by reflected field name, so a keep rule that stops matching turns the request
 * into `{"a":…,"b":…}`, the server answers 400, and the check fails open — which looks
 * exactly like a licence that is fine. **Seeing the field names on the wire, on a minified
 * build, is the only way that gets caught**, and §11 of the integration doc asks for
 * precisely that check.
 *
 * Sits alongside [ApiCall]'s logging rather than duplicating it: this is the wire, that is
 * the outcome. Both are compiled out of release builds by [sdkLog].
 */
internal class RedactingLogInterceptor(
    private val logger: TrackLogger,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        sdkLog {
            val body = request.body
            val text = body?.let { runCatching { Buffer().also(it::writeTo).readUtf8() }.getOrNull() }
            logger.d(
                API_TAG,
                "--> ${request.method} ${request.url.encodedPath} " +
                    "(${body?.contentLength() ?: 0}-byte body)" +
                    (text?.let { "\n    ${redact(it)}" } ?: ""),
            )
        }

        val response = chain.proceed(request)

        sdkLog {
            // `peekBody`, never `body.string()`. The response body is a one-shot stream and
            // the signature covers the exact bytes that arrived, so consuming it here would
            // leave the verifier with nothing to check — and that fails open, invisibly.
            val text = runCatching { response.peekBody(PEEK_BYTES).string() }.getOrNull()
            logger.d(
                API_TAG,
                // No duration here: ApiCall already reports one, and two slightly
                // different numbers for the same call is worse than none.
                "<-- ${response.code} ${response.message}" +
                    (text?.takeIf { it.isNotEmpty() }?.let { "\n    ${redact(it)}" } ?: ""),
            )
        }

        return response
    }

    /**
     * Shortens the values that must not appear in full, leaving every field **name** and
     * every other value intact.
     *
     * - `access_key` — the credential. Twelve characters is what support asks an integrator
     *   to send, so it is enough to tell two licences apart and useless to anyone else.
     * - `key_id` — a SHA-256 of the token. Not a credential, but a stable fingerprint of
     *   which licence an install holds, which is not something a log should publish.
     * - `signature` — no secret, just 86 characters of noise that would push the fields
     *   worth reading off the end of a logcat line.
     *
     * Text-level rather than parsed on purpose: a body that fails to parse is exactly when
     * this log matters most, and a redactor that only works on well-formed JSON would go
     * silent at that moment — or worse, print the raw body as a fallback.
     */
    private fun redact(body: String): String {
        val shortened = SENSITIVE.fold(body) { text, field ->
            runCatching {
                text.replace(Regex("(\"$field\"\\s*:\\s*\")([^\"]*)(\")")) { match ->
                    val value = match.groupValues[2]
                    val kept = value.take(PREVIEW_CHARS)
                    match.groupValues[1] + kept + (if (value.length > kept.length) "…" else "") +
                        match.groupValues[3]
                }
            }.getOrDefault(text)
        }

        return if (shortened.length <= MAX_BODY_CHARS) {
            shortened
        } else {
            shortened.take(MAX_BODY_CHARS) + "… (${shortened.length} chars)"
        }
    }

    private companion object {
        val SENSITIVE = listOf("access_key", "key_id", "signature")

        const val PREVIEW_CHARS = 12
        const val MAX_BODY_CHARS = 1_000
        const val PEEK_BYTES = 8_192L
    }
}
