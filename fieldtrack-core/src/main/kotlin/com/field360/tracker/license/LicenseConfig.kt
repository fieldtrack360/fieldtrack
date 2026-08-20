package com.field360.tracker.license

import android.content.Context
import android.content.pm.PackageManager
import com.field360.tracker.BuildConfig
import java.util.Base64

/**
 * The two values the online check cannot work without, and where they come from.
 *
 * Both default to empty, and empty means the check never runs — `VerdictAuthenticator.isConfigured`
 * is false, `CheckLicenseRevocationUseCase` returns `CarryOn` immediately, and no request
 * is ever made. That is the honest default for an unconfigured build: a licence layer that
 * fell back to trusting whatever answered would be worse than none.
 *
 * **Both must be filled before release enforcement means anything.**
 */
internal object LicenseConfig {

    /**
     * The licence API **root, including the version segment** — e.g.
     * `https://licence.example.com/api/v1`. `OkHttpLicenseApi` appends `/verify`
     * to it and nothing else, so the version lives here rather than in the SDK: moving
     * to `/api/v2` becomes a configuration change instead of a release.
     *
     * Not hardcoded. It comes from `BuildConfig.LICENSE_BASE_URL`, which the module's
     * build file resolves from the Gradle property `fieldtrackLicenseUrl`, the
     * environment variable `FIELDTRACK_LICENSE_URL`, or `local.properties` — the last of
     * which is gitignored, which is the whole point of the indirection:
     *
     * ```properties
     * FIELDTRACK_LICENSE_URL=https://licence.example.com/api/v1
     * ```
     *
     * Keeping it out of the repository is not the same as keeping it secret. The value
     * is compiled into the artifact and is readable in any published AAR or installed
     * APK. An endpoint the device has to reach cannot be hidden from the device, and
     * nothing here should ever carry a credential.
     *
     * Overridable per install from the host manifest, which takes precedence, for
     * pointing a build at a local server without rebuilding the SDK:
     *
     * ```xml
     * <meta-data android:name="FieldTrackLicenseUrl" android:value="http://10.0.2.2:5858/api/v1" />
     * ```
     *
     * `10.0.2.2` is the emulator's route to the host machine, and reaching it over
     * cleartext needs a **debug-only** network-security config. Release builds use https.
     */
    val defaultBaseUrl: String get() = BuildConfig.LICENSE_BASE_URL

    /**
     * The key that verifies `/verify` **responses**, standard base64 of 32 raw bytes,
     * from `GET /api/v1/public-key`.
     *
     * This is not the key that verifies licence tokens. They are two different keys,
     * deliberately: one authenticates what we issued, the other authenticates what the
     * server says about it today. [LicenseVerifier.productionKeys] holds the other one.
     *
     * Compiled in, never fetched at runtime — fetching it from the same server whose
     * answers it authenticates would be circular, and a device owner with a proxy would
     * simply serve their own.
     */
    const val RESPONSE_PUBLIC_KEY_BASE64: String = ""

    /** Empty when unset or malformed, which the authenticator reads as "not configured". */
    fun responsePublicKey(): ByteArray =
        runCatching { Base64.getDecoder().decode(RESPONSE_PUBLIC_KEY_BASE64) }
            .getOrNull()
            ?.takeIf { it.size == Ed25519.PUBLIC_KEY_BYTES }
            ?: ByteArray(0)

    /** The manifest override, else [defaultBaseUrl]. Blank means no check is made. */
    fun baseUrl(context: Context): String {
        val fromManifest = runCatching {
            context.packageManager
                .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData
                ?.getString(BASE_URL_META_DATA)
        }.getOrNull()

        return fromManifest?.takeIf { it.isNotBlank() } ?: defaultBaseUrl
    }

    const val BASE_URL_META_DATA: String = "FieldTrackLicenseUrl"
}
