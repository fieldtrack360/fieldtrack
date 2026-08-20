package com.field360.traker.snap

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.Url

/**
 * OSRM's map-matching endpoint, as Retrofit sees it.
 *
 * ### `@Url`, and why there are no path parameters
 *
 * The natural-looking Retrofit form is
 * `@GET("match/v1/{profile}/{coordinates}") suspend fun match(@Path profile: String, …)`.
 * It does not work here. OSRM encodes a whole path into one segment —
 * `…/match/v1/driving/72.5714,23.0225;72.5721,23.0231?radiuses=10;10` — and `@Path`
 * percent-encodes `;` and `,` by default. Turning that off with `@Path(encoded = true)`
 * means hand-encoding every value anyway, which is what [OsrmSnapProvider.urlFor] already
 * does, correctly and with tests.
 *
 * So the URL arrives fully built. Retrofit contributes the call adapter and the suspend
 * bridge; the URL is not its business, and pretending otherwise would only add a place for
 * an escaping bug to live.
 *
 * ### `Response<ResponseBody>`
 *
 * The body is decoded by `kotlinx.serialization` in [OsrmSnapProvider.decode], which already
 * handles OSRM's `code`/`tracepoints`/`matchings` shape and its partial-match semantics.
 * Adding a converter would mean a second parser for the same payload.
 */
internal interface OsrmService {

    @GET
    suspend fun match(
        @Url url: String,
        @HeaderMap headers: Map<String, String>,
    ): Response<ResponseBody>
}
