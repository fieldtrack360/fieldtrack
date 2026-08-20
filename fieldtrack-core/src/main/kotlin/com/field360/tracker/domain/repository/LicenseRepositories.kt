package com.field360.tracker.domain.repository

import com.field360.tracker.domain.model.CachedVerdict
import com.field360.tracker.domain.model.LicenseCheckRequest
import com.field360.tracker.domain.model.SignedVerdict

/**
 * The three seams the revocation use case sits on: somewhere to ask, something to decide
 * whether the answer is genuine, and somewhere to keep it.
 *
 * All three are `internal`. Unlike [PendingUploadStore], none of this is a door for an
 * optional module — licensing has no host-supplied implementation, deliberately, since a
 * host that could substitute the verifier could substitute a verdict.
 */

/**
 * The one network call the licence layer makes.
 *
 * **The body comes back verbatim.** The signature covers the exact document that arrived,
 * so it has to reach [VerdictAuthenticator] as those bytes. Returning a parsed object
 * here would throw the original away, and reconstructing it is precisely where these
 * implementations quietly break — see `data/remote/CanonicalJson.kt`.
 *
 * Null covers every failure without distinguishing them, because the caller treats them
 * identically: no network, DNS failure, timeout, 5xx, 4xx, empty body, unconfigured
 * build. None of those is evidence about a licence.
 */
internal fun interface LicenseApi {
    suspend fun verify(request: LicenseCheckRequest): String?
}

/**
 * Turns a response document into a [SignedVerdict], or refuses.
 *
 * An interface for the same reason the API is one: the tests that matter here are the
 * ones where the answer is hostile, and the use case must be provable without a keypair.
 * The real implementation is `data/remote/GsonVerdictAuthenticator.kt`, and its KDoc
 * carries the three checks and why each exists.
 */
internal interface VerdictAuthenticator {

    /** False when no key was compiled in, in which case no response can be trusted. */
    val isConfigured: Boolean

    /**
     * A verdict only if every check passes, otherwise null.
     *
     * Null means "treat this as if the call never happened" — never "the licence is
     * invalid". A tampered or unparseable response must not be able to stop a paying
     * customer's tracking, which is exactly what an attacker with a proxy would arrange
     * if it could.
     *
     * [sentNonce] is null on the cache-read path, where a nonce proves nothing about a
     * value read back from disk.
     */
    fun authenticate(document: String, token: String, sentNonce: String?): SignedVerdict?
}

/**
 * Where a verdict is kept between checks.
 *
 * Implementations store the **whole signed document** and re-verify it on read, which is
 * what makes the record tamper-proof without encrypting anything. The device owner is the
 * expected adversary, so a store that trusted its own contents would be a way around the
 * entire layer.
 */
internal interface LicenseVerdictStore {

    /** Null for absent, unreadable, or failing re-verification. */
    suspend fun read(token: String): CachedVerdict?

    suspend fun write(token: String, verdict: SignedVerdict)

    /** Drops this token's entry. Keyed per token, so other licences are untouched. */
    suspend fun clear(token: String)
}
