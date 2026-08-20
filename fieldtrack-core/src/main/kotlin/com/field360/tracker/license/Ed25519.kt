package com.field360.tracker.license

import com.google.crypto.tink.subtle.Ed25519Verify

/**
 * Ed25519 signature verification for both licence layers.
 *
 * `java.security` only gained Ed25519 at API 33, and `minSdk` is 26. Before this existed
 * [LicenseVerifier] called `Signature.getInstance("Ed25519")` directly, so every device
 * on Android 8.0 through 12L failed the offline gate with "Ed25519 is unavailable on this
 * device" and the SDK refused to start — on roughly a third of the install base.
 *
 * Tink is the whole answer rather than half of one: the same primitive verifies the
 * licence token here and the `/verify` response in `data/remote/GsonVerdictAuthenticator.kt`, so there is one
 * crypto dependency and one code path at every API level.
 *
 * Keys are **32 raw bytes**, not DER. Tink's `Ed25519Verify` takes the raw form, which is
 * also what the issuing flow and `GET /api/v1/public-key` hand out; the previous
 * `X509EncodedKeySpec` wrapping was an artefact of the `java.security` API and is gone.
 */
internal object Ed25519 {

    /** The length Tink requires. A key of any other size is a packaging mistake. */
    const val PUBLIC_KEY_BYTES: Int = 32

    /**
     * True only if [signature] is a genuine Ed25519 signature over [message] by [publicKey].
     *
     * Never throws. Tink signals failure by exception — a bad signature, a malformed key,
     * a key of the wrong length — and every one of those means the same thing to both
     * callers: do not trust this. Collapsing them into `false` is what lets the callers
     * stay branch-free.
     */
    fun verify(publicKey: ByteArray, signature: ByteArray, message: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_BYTES) return false
        return runCatching {
            Ed25519Verify(publicKey).verify(signature, message)
        }.isSuccess
    }
}
