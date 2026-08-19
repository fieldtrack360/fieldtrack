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
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

internal object NoOpTransport : SyncTransport {
    override suspend fun upload(request: SyncRequest): SyncResponse =
        SyncResponse.Failure(null, "No transport configured")
}

internal suspend fun executeOkHttpUpload(
    client: OkHttpClient,
    request: SyncRequest,
    nowMs: Long,
): SyncResponse = withContext(Dispatchers.IO) {
    val httpRequest = Request.Builder()
        .url(request.url)
        .method(request.method, bodyFor(request))
        .apply { request.headers.forEach { (name, value) -> addHeader(name, value) } }
        .apply { if (isGzipped(request)) addHeader("Content-Encoding", "gzip") }
        .build()

    try {
        client.newCall(httpRequest).execute().use { response ->
            when {
                response.isSuccessful -> SyncResponse.Success(response.code)
                response.code == HTTP_UNAUTHORIZED -> SyncResponse.Unauthorized
                response.code == HTTP_FORBIDDEN -> SyncResponse.Forbidden
                else -> SyncResponse.Failure(
                    code = response.code,
                    message = response.message,
                    // peekBody, not body: it reads a bounded prefix and leaves the stream
                    // intact, so a 40 MB error page costs 4 KB of memory rather than all of
                    // it. Only on the failure path — a success body is of no use to anyone
                    // and can be just as large.
                    body = errorBody(response),
                    retryAfterMs = parseRetryAfter(response.header(RETRY_AFTER), nowMs),
                )
            }
        }
    } catch (e: IOException) {
        SyncResponse.Failure(null, e.message ?: "network error")
    }
}

private fun errorBody(response: Response): String? = runCatching {
    response.peekBody(SyncResponse.Failure.MAX_BODY_CHARS.toLong()).string().ifEmpty { null }
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
 * A client carrying [timeouts], derived from [base].
 *
 * `newBuilder()` shares the connection pool, dispatcher and cache, so this is cheap — but not
 * free, hence the cache: a steady stream of batches with identical timeouts builds one client
 * and reuses it. Returns [base] untouched for the default values so a host that supplied a
 * fully configured client of its own gets exactly that client back.
 */
internal fun clientFor(
    base: OkHttpClient,
    timeouts: SyncTimeouts,
    cache: MutableMap<SyncTimeouts, OkHttpClient>,
): OkHttpClient {
    if (timeouts == DEFAULT_TIMEOUTS) return base
    return cache.getOrPut(timeouts) {
        base.newBuilder()
            .connectTimeout(timeouts.connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(timeouts.writeMs, TimeUnit.MILLISECONDS)
            .build()
    }
}

private val DEFAULT_TIMEOUTS = SyncTimeouts()
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val RETRY_AFTER = "Retry-After"
private const val GZIP_MIN_CHARS = 1_024
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
