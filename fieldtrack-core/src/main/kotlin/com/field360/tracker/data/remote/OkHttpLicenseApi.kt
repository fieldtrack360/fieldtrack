package com.field360.tracker.data.remote

import com.field360.tracker.API_TAG
import com.field360.tracker.domain.model.LicenseCheckRequest
import com.field360.tracker.domain.repository.LicenseApi
import com.field360.tracker.sdkLog
import com.field360.traker.geo.port.TrackLogger
import com.google.gson.GsonBuilder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The default [LicenseApi]. The only place in the licence layer that knows about HTTP.
 *
 * Timeouts are short on purpose. This work is never urgent — it runs on a WorkManager
 * tick, not a startup path — and a long timeout only keeps a wakelock alive for a call
 * that fails open anyway. `retryOnConnectionFailure` is off for the same reason: the next
 * scheduled check is the retry.
 *
 * [baseUrl] is the API root including its version segment, e.g.
 * `https://licence.example.com/api/v1` — see `LicenseConfig.defaultBaseUrl`. Blank is a
 * supported state and means an unconfigured build: no request is attempted.
 *
 * Logs to `Tracker/API` in debug builds — see [API_TAG] for what is deliberately kept out
 * of those lines.
 */
internal class OkHttpLicenseApi(
    private val baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    private val logger: TrackLogger = TrackLogger.NoOp,
) : LicenseApi {

    private val gson = GsonBuilder().disableHtmlEscaping().create()

    override suspend fun verify(request: LicenseCheckRequest): String? =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) {
                sdkLog { logger.d(API_TAG, "skipped: no licence URL configured for this build") }
                return@withContext null
            }

            val url = baseUrl.trimEnd('/') + VERIFY_PATH
            val startedAt = System.nanoTime()

            // `request.accessKey` is deliberately absent from every line below. The
            // package and SDK identity are not secrets; the access key is.
            sdkLog {
                logger.d(
                    API_TAG,
                    "POST $url pkg=${request.packageName} " +
                        "sdk=${request.sdkType}/${request.sdkVersion ?: "?"}",
                )
            }

            runCatching {
                val body = gson.toJson(request.toDto()).toRequestBody(JSON)
                val http = Request.Builder()
                    .url(url)
                    .post(body)
                    .build()

                client.newCall(http).execute().use { response ->
                    if (!response.isSuccessful) {
                        // Warn, not error: a 4xx or 5xx changes nothing. The check fails
                        // open, so this is a note about the server, not about the licence.
                        sdkLog {
                            logger.w(API_TAG, "HTTP ${response.code} in ${startedAt.msSince()}ms")
                        }
                        return@use null
                    }

                    val payload = response.body.string().ifEmpty { null }
                    sdkLog {
                        logger.d(
                            API_TAG,
                            "HTTP ${response.code} in ${startedAt.msSince()}ms, " +
                                "${payload?.length ?: 0} bytes",
                        )
                    }
                    payload
                }
            }.onFailure { error ->
                // Type and message only. Some stack frames carry the request URL with
                // query parameters, and a full trace here is noise for something the
                // caller is going to shrug off anyway.
                sdkLog {
                    logger.w(
                        API_TAG,
                        "failed after ${startedAt.msSince()}ms: " +
                            "${error.javaClass.simpleName}: ${error.message ?: "no detail"}",
                    )
                }
            }.getOrNull()
        }

    /** Monotonic, so a wall-clock jump mid-call cannot produce a negative duration. */
    private fun Long.msSince(): Long = (System.nanoTime() - this) / NANOS_PER_MILLI

    internal companion object {
        /**
         * Appended to the configured root. The `/api/v1` half deliberately lives in the
         * URL rather than here, so a version bump on the server is a configuration
         * change instead of an SDK release.
         */
        const val VERIFY_PATH: String = "/verify"

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val TIMEOUT_SECONDS = 10L
        private const val NANOS_PER_MILLI = 1_000_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}
