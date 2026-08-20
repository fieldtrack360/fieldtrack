package com.field360.tracker.data.remote

import com.field360.tracker.domain.model.ApiResult
import com.field360.tracker.domain.model.SignedVerdict
import com.field360.tracker.domain.repository.VerdictAuthenticator
import com.field360.tracker.license.Ed25519
import java.security.MessageDigest
import java.util.Base64

/**
 * The three checks a `/verify` response must pass before anything acts on it.
 *
 * | # | Check | On failure |
 * |---|---|---|
 * | 1 | Ed25519 signature over the canonical JSON | discard |
 * | 2 | `key_id` is SHA-256 of **our** token | discard, and treat as hostile |
 * | 3 | `nonce` echoes what we sent | discard |
 *
 * **Check 2 is the one that is easy to leave out, and leaving it out reopens the hole the
 * signature was meant to close.** Nothing else in the payload says which licence the
 * answer is about, so a signed `"active"` would become a bearer token for every licence:
 * an attacker with one legitimate licence captures its `active` response once and replays
 * it whenever the app checks a revoked token, and the signature verifies perfectly every
 * time. The adversary here is the device owner, who controls the device's DNS, its proxy
 * and its trust store — so TLS is not the thing standing in the way.
 *
 * [responsePublicKey] is 32 raw bytes, compiled in. Fetching it at runtime from the same
 * server whose answers it is meant to authenticate would be circular.
 *
 * This lives in `data/remote` rather than `domain` because it is JSON that is being
 * verified, and Gson is how. The domain declares [VerdictAuthenticator] and never learns
 * which library, which is what lets the use case be tested against a fake.
 */
internal class GsonVerdictAuthenticator(
    private val responsePublicKey: ByteArray,
) : VerdictAuthenticator {

    override val isConfigured: Boolean
        get() = responsePublicKey.size == Ed25519.PUBLIC_KEY_BYTES

    override fun authenticate(
        document: String,
        token: String,
        sentNonce: String?,
    ): SignedVerdict? {
        if (!isConfigured) return null

        // Reading and trusting are separate steps, and only the second one is a security
        // decision. Everything below operates on fields that have already parsed.
        val parsed = (LicenseResponseParser.parse(document) as? ApiResult.Success)?.value
            ?: return null

        val signatureBytes = decodeBase64Url(parsed.signature) ?: return null
        val canonical = canonicalize(document) ?: return null

        // 1 — is it genuine?
        val genuine = Ed25519.verify(
            publicKey = responsePublicKey,
            signature = signatureBytes,
            message = canonical.toByteArray(Charsets.UTF_8),
        )
        if (!genuine) return null

        // 2 — is it about OUR licence?
        if (parsed.keyId != sha256Hex(token.trim())) return null

        // 3 — is it fresh? Skipped when the caller had no nonce to send, which is only
        // the cache-read path: a nonce proves freshness on arrival and can prove nothing
        // about a value read back from disk.
        if (sentNonce != null && parsed.nonce != sentNonce) return null

        return parsed.toVerdict()
    }

    private fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /**
         * `java.util.Base64`, not `android.util.Base64`: the latter is a stub under plain
         * JVM unit tests, where every one of the negative tests that matter here runs.
         */
        fun decodeBase64Url(value: String): ByteArray? =
            runCatching { Base64.getUrlDecoder().decode(value) }.getOrNull()
    }
}
