package com.field360.traker.sync.internal

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Url

/**
 * The upload endpoint, as Retrofit sees it.
 *
 * ### Three methods rather than one, and why
 *
 * `SyncConfig.method` is a `String` a host sets at runtime. Retrofit's verb annotations are
 * **compile-time constants** — there is no `@HTTP(method = variable)` — so a runtime-chosen
 * verb has to become a dispatch over declared methods. `POST`, `PUT` and `PATCH` are the
 * three that mean anything for uploading a batch.
 *
 * **This narrows what `SyncConfig.method` accepts.** The OkHttp transport it replaced passed
 * the string straight to `Request.Builder.method(...)`, so `REPORT` or any other verb worked.
 * `SyncConfig.validate()` now rejects anything outside the three, which at least moves the
 * failure from upload time to configuration time — but it is a narrowing, not a no-op.
 *
 * ### `@Body RequestBody`, not a typed object
 *
 * The body is already serialised JSON by the time it arrives, and may be gzipped. Handing
 * Retrofit a `RequestBody` bypasses the converters entirely, which is what keeps
 * [bodyFor]'s gzip decision and its `Content-Length` intact.
 *
 * ### `@Url`, not a base URL
 *
 * The host supplies a complete URL per request. A `Retrofit` instance still needs *a* base
 * URL to be constructed, and it is ignored whenever `@Url` is absolute — see
 * [retrofitFor].
 */
internal interface SyncService {

    @POST
    suspend fun post(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @PUT
    suspend fun put(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    @PATCH
    suspend fun patch(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
        @Body body: RequestBody,
    ): Response<ResponseBody>

    companion object {
        /** What [SyncService] can express. `SyncConfig.validate()` enforces it. */
        val SUPPORTED_METHODS: Set<String> = setOf("POST", "PUT", "PATCH")
    }
}
