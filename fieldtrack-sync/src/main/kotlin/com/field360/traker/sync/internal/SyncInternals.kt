package com.field360.traker.sync.internal

import com.field360.traker.sync.SyncRequest
import com.field360.traker.sync.SyncResponse
import com.field360.traker.sync.SyncTimeouts
import com.field360.traker.sync.SyncTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit

internal object NoOpTransport : SyncTransport {
    override suspend fun upload(request: SyncRequest): SyncResponse =
        SyncResponse.Failure(null, "No transport configured")
}

internal suspend fun executeUpload(
    service: SyncService,
    request: SyncRequest,
    nowMs: Long,
): SyncResponse = withContext(Dispatchers.IO) {
    val headers = buildMap {
        putAll(request.headers)
        if (isGzipped(request)) put("Content-Encoding", "gzip")
    }

    val verb = request.method.uppercase()
    val body = bodyFor(request)

    try {
        val response = when (verb) {
            "POST" -> service.post(request.url, headers, body)
            "PUT" -> service.put(request.url, headers, body)
            "PATCH" -> service.patch(request.url, headers, body)
            // Retrofit cannot express a runtime verb, so anything else is unreachable
            // rather than unsupported-at-the-wire. `SyncConfig.validate()` rejects it
            // first; this is the belt to that brace.
            else -> return@withContext SyncResponse.Failure(
                code = null,
                message = "Unsupported HTTP method \"${request.method}\". " +
                    "Supported: ${SyncService.SUPPORTED_METHODS.joinToString()}",
            )
        }

        when {
            response.isSuccessful -> SyncResponse.Success(response.code())
            response.code() == HTTP_UNAUTHORIZED -> SyncResponse.Unauthorized
            response.code() == HTTP_FORBIDDEN -> SyncResponse.Forbidden
            else -> SyncResponse.Failure(
                code = response.code(),
                message = response.message(),
                // Bounded read of the error body. A 40 MB error page costs 4 KB here
                // rather than all of it. Only on the failure path — a success body is of
                // no use to anyone and can be just as large.
                body = errorBody(response.errorBody()),
                retryAfterMs = parseRetryAfter(response.headers()[RETRY_AFTER], nowMs),
            )
        }
    } catch (e: IOException) {
        SyncResponse.Failure(null, e.message ?: "network error")
    }
}

/**
 * Truncated after reading rather than peeked before it. `Response.peekBody` belongs to
 * OkHttp's response, and Retrofit hands back an already-separated `errorBody`; the cap is
 * kept because the reason for it has not changed.
 */
private fun errorBody(body: ResponseBody?): String? = runCatching {
    body?.use { it.string() }
        ?.take(SyncResponse.Failure.MAX_BODY_CHARS)
        ?.ifEmpty { null }
}.getOrNull()

/**
 * Compressed only when the host asked **and** the body is big enough to be worth it. Below
 * the threshold the gzip header and trailer cost more than the saving, and the request stops
 * being readable in a proxy log for nothing.
 */
private fun isGzipped(request: SyncRequest): Boolean =
    request.gzip && request.jsonBody.length >= GZIP_MIN_CHARS

private fun bodyFor(request: SyncRequest): RequestBody {
    if (!isGzipped(request)) return request.jsonBody.toRequestBody(JSON_MEDIA_TYPE)

    // Buffered rather than streamed through a GzipSink: a batch is tens of KB, and a
    // materialised body keeps the request retryable and its length known, which is what a
    // server behind a proxy wants.
    val compressed = ByteArrayOutputStream().use { sink ->
        GZIPOutputStream(sink).use { it.write(request.jsonBody.toByteArray(Charsets.UTF_8)) }
        sink.toByteArray()
    }
    return compressed.toRequestBody(JSON_MEDIA_TYPE)
}

/**
 * A [SyncService] carrying [timeouts], built on a client derived from [base].
 *
 * `newBuilder()` shares the connection pool, dispatcher and cache, so deriving is cheap —
 * but not free, and a `Retrofit` instance on top is another allocation, hence the cache: a
 * steady stream of batches with identical timeouts builds one service and reuses it.
 *
 * The base URL handed to Retrofit is a placeholder and is never used. Every call passes an
 * absolute `@Url`, which Retrofit resolves against nothing — but the builder still refuses
 * to construct without one.
 */
internal fun serviceFor(
    base: OkHttpClient,
    timeouts: SyncTimeouts,
    cache: MutableMap<SyncTimeouts, SyncService>,
): SyncService = cache.getOrPut(timeouts) {
    val client = if (timeouts == DEFAULT_TIMEOUTS) {
        // A host that supplied a fully configured client of its own gets exactly that
        // client back, untouched.
        base
    } else {
        base.newBuilder()
            .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.writeMs, TimeUnit.MILLISECONDS)
            .build()
    }

    Retrofit.Builder()
        .baseUrl(PLACEHOLDER_BASE_URL)
        .client(client)
        .build()
        .create(SyncService::class.java)
}

/** Ignored at runtime — every call carries an absolute `@Url`. */
private const val PLACEHOLDER_BASE_URL = "http://localhost/"

private val DEFAULT_TIMEOUTS = SyncTimeouts()
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val RETRY_AFTER = "Retry-After"
private const val GZIP_MIN_CHARS = 1_024
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
