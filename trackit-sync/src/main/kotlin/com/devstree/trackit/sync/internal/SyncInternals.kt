package com.devstree.trackit.sync.internal

import com.devstree.trackit.sync.SyncRequest
import com.devstree.trackit.sync.SyncResponse
import com.devstree.trackit.sync.SyncTransport
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal object NoOpTransport : SyncTransport {
    override suspend fun upload(request: SyncRequest): SyncResponse =
        SyncResponse.Failure(null, "No transport configured")
}

internal suspend fun executeOkHttpUpload(
    client: OkHttpClient,
    request: SyncRequest,
): SyncResponse = withContext(Dispatchers.IO) {
    val body = request.jsonBody.toRequestBody(JSON_MEDIA_TYPE)
    val httpRequest = Request.Builder()
        .url(request.url)
        .method(request.method, body)
        .apply { request.headers.forEach { (name, value) -> addHeader(name, value) } }
        .build()

    try {
        client.newCall(httpRequest).execute().use { response ->
            when {
                response.isSuccessful -> SyncResponse.Success(response.code)
                response.code == HTTP_UNAUTHORIZED -> SyncResponse.Unauthorized
                else -> SyncResponse.Failure(response.code, response.message)
            }
        }
    } catch (e: IOException) {
        SyncResponse.Failure(null, e.message ?: "network error")
    }
}

private const val HTTP_UNAUTHORIZED = 401
private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
