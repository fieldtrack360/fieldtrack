package com.field360.tracker.data.remote

import com.field360.tracker.domain.model.LicenseCheckRequest
import com.google.gson.annotations.SerializedName

/**
 * The wire shape of `POST {root}/verify` — see docs/android-kotlin-integration.md.
 *
 * `@SerializedName` on every field is load-bearing rather than decorative. Gson maps by
 * reflected field name and the release AAR is R8-minified, so without the annotation the
 * request would go out as `{"a":…,"b":…}`, the server would answer `400`, and the check
 * would fail open — which looks exactly like everything working. `consumer-rules.pro`
 * keeps the annotated fields; this is the half that survives on its own.
 *
 * Separate from [LicenseCheckRequest] on purpose. The domain model is what the use case
 * builds and what its tests assert on; this is what leaves the device. Renaming a wire
 * field is then a change to one file, and a mapper the compiler checks, rather than an
 * edit that reaches into a use case which should never have known the spelling.
 */
internal data class VerifyRequestDto(
    @SerializedName("access_key") val accessKey: String,
    @SerializedName("package_name") val packageName: String,
    @SerializedName("sdk_type") val sdkType: String,
    @SerializedName("sdk_version") val sdkVersion: String? = null,
    @SerializedName("nonce") val nonce: String? = null,
)

internal fun LicenseCheckRequest.toDto(): VerifyRequestDto = VerifyRequestDto(
    accessKey = accessKey,
    packageName = packageName,
    sdkType = sdkType,
    sdkVersion = sdkVersion,
    nonce = nonce,
)
