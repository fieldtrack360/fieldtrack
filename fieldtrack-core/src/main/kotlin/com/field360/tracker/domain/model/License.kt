package com.field360.tracker.domain.model

/**
 * The domain model of the online licence check — no Gson, no OkHttp, no `Context`.
 *
 * The online check is the only licence enforcement in the SDK: the offline signature gate
 * was removed, so a token is never verified locally. These types describe what the server
 * says about the licence today: *has it been revoked or expired since it was issued?*
 *
 * Wire spellings live in `data/remote`. Nothing here knows the transport is HTTP or the
 * encoding is JSON, which is what lets the use case be tested without either.
 */

/**
 * What the SDK asks the licence server.
 *
 * Deliberately free of `@SerializedName`: the annotated shape is `VerifyRequestDto` in
 * `data/remote`, and keeping the two apart is what stops a wire rename from reaching into
 * the use case that builds this.
 */
internal data class LicenseCheckRequest(
    val accessKey: String,
    val packageName: String,
    /** `"android"` from this SDK. A bridge reports itself: `react-native`, `flutter`. */
    val sdkType: String,
    val sdkVersion: String? = null,
    val nonce: String? = null,
)

/**
 * A server answer that passed every check in `VerdictAuthenticator`.
 *
 * There is no way to construct one that did not — the only producer is the authenticator
 * — so a `SignedVerdict` in hand is already proof of authenticity, not a parsed payload
 * still waiting to be validated.
 */
internal data class SignedVerdict(
    val status: LicenseStatus,
    val valid: Boolean,
    val keyId: String,
    val packageName: String,
    val checkedAt: String,
    /** How long this answer may keep being trusted. A cache lifetime, not a heartbeat. */
    val ttlSeconds: Long,
    val nonce: String?,
    val reason: String?,
    /**
     * The signed document verbatim, byte for byte as it arrived.
     *
     * Kept whole so the store can re-verify it on the way back out, which is what makes
     * a cached verdict tamper-proof without encrypting anything.
     */
    val raw: String,
)

/**
 * The statuses the SDK acts on, plus one for everything else.
 *
 * Public, because it is what a host reads off [LicenseInfo]. The wire spellings are not
 * public — `fromWire` is internal, so a host cannot come to depend on the strings.
 *
 * An enum rather than the raw string, so the branch in [toAction] is exhaustive and a
 * typo is a compile error instead of a silent `CarryOn`. [UNRECOGNISED] keeps that
 * strictness from becoming brittleness — see its own note.
 */
public enum class LicenseStatus {
    ACTIVE,
    REVOKED,
    EXPIRED,
    UNKNOWN_KEY,
    INVALID_KEY,
    PACKAGE_MISMATCH,
    SDK_MISMATCH,

    /**
     * A status this build has never been taught.
     *
     * Not an error. A server that starts sending something new must not be able to stop
     * every already-shipped install by accident, so this maps to `CarryOn` like any other
     * inconclusive result.
     */
    UNRECOGNISED,
    ;

    internal companion object {
        /** The wire spellings. Unknown input is [UNRECOGNISED], never an exception. */
        fun fromWire(value: String): LicenseStatus = when (value) {
            "active" -> ACTIVE
            "revoked" -> REVOKED
            "expired" -> EXPIRED
            "unknown_key" -> UNKNOWN_KEY
            "invalid_key" -> INVALID_KEY
            "package_mismatch" -> PACKAGE_MISMATCH
            "sdk_mismatch" -> SDK_MISMATCH
            else -> UNRECOGNISED
        }
    }
}

/** What the caller should do about a verdict. */
internal sealed interface LicenseAction {

    /** Keep tracking. Also the answer to every failure. */
    data object CarryOn : LicenseAction

    /** Stop tracking and tell the host why. */
    data class Stop(val code: ErrorCode, val reason: String?) : LicenseAction

    /** Keep tracking, but say something. A ledger gap on our side, not a bad customer. */
    data class Diagnose(val code: ErrorCode, val reason: String?) : LicenseAction
}

/**
 * **Only [LicenseStatus.REVOKED] and [LicenseStatus.EXPIRED] stop anything.**
 *
 * `unknown_key` and `invalid_key` mean the token verified offline — against a key we
 * compiled in ourselves — but the backend has no matching record. That combination is a
 * data problem on our side, not a customer who did anything wrong, and a paying user must
 * not lose tracking over it. Same for the two mismatches: they are worth a diagnostic and
 * a support ticket, not a stopped session.
 */
internal fun SignedVerdict.toAction(): LicenseAction = when (status) {
    LicenseStatus.ACTIVE -> LicenseAction.CarryOn
    LicenseStatus.REVOKED -> LicenseAction.Stop(ErrorCode.LICENSE_REVOKED, reason)
    LicenseStatus.EXPIRED -> LicenseAction.Stop(ErrorCode.LICENSE_EXPIRED, reason)
    LicenseStatus.UNKNOWN_KEY -> LicenseAction.Diagnose(ErrorCode.LICENSE_UNKNOWN, reason)
    LicenseStatus.INVALID_KEY -> LicenseAction.Diagnose(ErrorCode.LICENSE_INVALID, reason)
    LicenseStatus.PACKAGE_MISMATCH ->
        LicenseAction.Diagnose(ErrorCode.LICENSE_PACKAGE_MISMATCH, reason)
    LicenseStatus.SDK_MISMATCH ->
        LicenseAction.Diagnose(ErrorCode.LICENSE_SDK_MISMATCH, reason)
    LicenseStatus.UNRECOGNISED -> LicenseAction.CarryOn
}

/** A stored verdict and when it was stored, which is all staleness needs. */
internal data class CachedVerdict(
    val verdict: SignedVerdict,
    val storedAt: Long,
    private val nowMs: () -> Long,
) {
    /**
     * `ttl_seconds` is how long this answer may keep being trusted — a cache lifetime,
     * not a heartbeat interval and not a deadline. Paid licences return 86400 (24h);
     * trials return less.
     */
    fun isStale(): Boolean = ageMs() > verdict.ttlSeconds * MILLIS_PER_SECOND

    /**
     * How long ago this was stored. Separate from [isStale] because a forced refresh
     * ignores the TTL but still needs a floor — see `CheckLicenseRevocationUseCase`.
     *
     * **A verdict stamped in the future counts as maximally old, not as brand new.**
     * `storedAt` is wall-clock, the device's wall clock belongs to the device owner, and
     * the cache file is on their disk. Reading a negative age as "just stored" would mean
     * a clock rolled forward once, or a hand-edited timestamp, freezing the last verdict
     * in place forever: never stale, always inside the refresh floor, never re-checked.
     * Treating it as ancient forces a fresh check, which is the safe direction.
     */
    fun ageMs(): Long = (nowMs() - storedAt).let { if (it < 0) Long.MAX_VALUE else it }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}

/**
 * What a host learns from a completed licence check.
 *
 * This is the whole host-visible surface of the online layer, and it is deliberately
 * narrow. It carries what the server said about the licence and nothing that identifies
 * it: no access key, no `key_id`, and not the signed document itself. Those are the
 * SDK's business — a host that could read them could replay them, and a host that could
 * construct one of these could not, which is the point of the private constructor path
 * through [toInfo]. `@ConsistentCopyVisibility` extends that to the generated
 * `copy()`, which would otherwise be a public back door to the same constructor.
 *
 * Reached three ways, all of which mean the same thing has already been authenticated:
 * `Tracker.licenseInfo()`, `Tracker.checkLicense()`, and [TrackerEvent.LicenseChecked].
 */
@ConsistentCopyVisibility
public data class LicenseInfo internal constructor(

    /** [LicenseStatus.ACTIVE] is the success case. Everything else is described below. */
    val status: LicenseStatus,

    /**
     * The server's own boolean, alongside [status].
     *
     * Do not branch on this instead of [status]. `valid` answers "is this licence in
     * good standing", which is not the same question as "should tracking stop" — only
     * `REVOKED` and `EXPIRED` stop anything, and a `false` here with `UNKNOWN_KEY` is a
     * gap in our ledger rather than a customer who did something wrong.
     */
    val valid: Boolean,

    /** The package the licence was issued against, as the server has it. */
    val packageName: String,

    /**
     * When the server made this decision, ISO-8601, verbatim.
     *
     * Not parsed into a timestamp on purpose: this is the server's clock, the device's
     * clock is attacker-controlled, and a comparison between them means less than it
     * appears to.
     */
    val checkedAt: String,

    /**
     * How long this answer may keep being trusted, in seconds. A cache lifetime, not a
     * heartbeat interval — paid licences return 86400, trials return less.
     */
    val ttlSeconds: Long,

    /** The server's explanation when it sent one. Safe to show a developer, not a user. */
    val reason: String?,

    /**
     * True when this came from the on-device cache rather than a call made just now.
     *
     * Not a quality warning. A cached verdict was signature-checked on the way in *and*
     * again on the way out, so it is exactly as trustworthy as a fresh one — it is only
     * older. `Tracker.licenseInfo()` always returns `true` here; it never uses the
     * network.
     */
    val fromCache: Boolean,
)

/** The only way a [LicenseInfo] comes into existence: from something already verified. */
internal fun SignedVerdict.toInfo(fromCache: Boolean): LicenseInfo = LicenseInfo(
    status = status,
    valid = valid,
    packageName = packageName,
    checkedAt = checkedAt,
    ttlSeconds = ttlSeconds,
    reason = reason,
    fromCache = fromCache,
)

/**
 * What one run of the check produced: what the SDK should do, and what the host is told.
 *
 * Two fields rather than one because they answer to different audiences. [action] is
 * internal policy — only `revoked` and `expired` stop anything. [info] is the report, and
 * it is present for **every** authenticated answer including `active`, which is the case
 * a host most needs to be able to observe and the one an action-only return value has
 * nothing to say about.
 *
 * [info] is null only when nothing was learned: no network, an unconfigured build, or a
 * response that failed authentication. All three also give [LicenseAction.CarryOn], so a
 * host that sees no event has not been told the licence is fine — it has been told
 * nothing, which is the honest thing to report.
 */
internal data class LicenseCheckResult(
    val action: LicenseAction,
    val info: LicenseInfo?,
    /**
     * Why the check produced nothing, when it produced nothing.
     *
     * Reported to the host as a [TrackerEvent.Diagnostic] and **never** as a
     * [TrackerEvent.LicenseChecked] — the two say different things and conflating them is
     * exactly the mistake this layer is built to avoid. A diagnostic says "the check did
     * not happen"; a `LicenseChecked` says "the server answered, and here is what it said".
     * A host that treated the first as the second would read a licence server outage as a
     * valid licence.
     *
     * Null on success, and on the paths where nothing was attempted.
     */
    val error: ApiError? = null,
) {
    internal companion object {
        /** The failure shape: carry on, and say why — but say it as a diagnostic. */
        fun inconclusive(error: ApiError? = null): LicenseCheckResult =
            LicenseCheckResult(LicenseAction.CarryOn, info = null, error = error)

        /** Nothing was attempted and nothing went wrong: an unconfigured build. */
        val Inconclusive: LicenseCheckResult = inconclusive()
    }
}
