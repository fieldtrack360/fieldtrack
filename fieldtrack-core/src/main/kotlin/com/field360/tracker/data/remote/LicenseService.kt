package com.field360.tracker.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * The licence endpoint, as Retrofit sees it.
 *
 * ### `ResponseBody`, never a typed DTO — and this is not a style choice
 *
 * The obvious form is `suspend fun verify(@Body request: VerifyRequestDto): VerifyResponseDto`,
 * with a converter doing the parsing. **That form silently disables licence enforcement.**
 *
 * The server signs the response, and the signature covers the exact JSON text that arrived:
 * every key in its original order, every number in its original spelling, no whitespace
 * added or removed. A converter parses those bytes into an object and throws the original
 * away. Reconstructing them is where two implementations stop agreeing — re-serialising
 * `86400` as `86400.0` is enough, and so is a differently-escaped `<`.
 *
 * When that happens the signature check fails. The check fails **open**, by design, so
 * nothing breaks visibly: the app keeps running, no error is reported, and the only symptom
 * is that a revoked licence never stops. That is a bug nobody finds by testing the happy
 * path.
 *
 * So the body reaches [RetrofitLicenseApi] as bytes, and [LicenseResponseParser] reads them
 * *after* [GsonVerdictAuthenticator] has verified them. `retrofitPreservesTheExactBytes` in
 * `RetrofitLicenseApiTest` is what stops this being quietly "cleaned up" later.
 *
 * The request direction has no such constraint — nothing signs what we send — so
 * `@Body VerifyRequestDto` goes through the Gson converter normally.
 */
internal interface LicenseService {

    /**
     * Relative to the configured root, which already carries its version segment: a base of
     * `https://licence.example.com/api/v1/` gives `…/api/v1/verify`. Keeping the version in
     * configuration rather than here means a server-side bump is a properties change instead
     * of an SDK release.
     *
     * `Response<…>` rather than a bare body so a non-2xx arrives as a value with its status
     * intact. Retrofit would otherwise throw `HttpException`, and this call sits behind a
     * contract that never throws.
     */
    @POST("verify")
    suspend fun verify(@Body request: VerifyRequestDto): Response<ResponseBody>
}
