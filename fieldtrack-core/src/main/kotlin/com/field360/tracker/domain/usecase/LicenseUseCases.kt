package com.field360.tracker.domain.usecase

import com.field360.tracker.API_TAG
import com.field360.tracker.domain.model.LicenseAction
import com.field360.tracker.domain.model.LicenseCheckRequest
import com.field360.tracker.domain.model.LicenseCheckResult
import com.field360.tracker.domain.model.LicenseInfo
import com.field360.tracker.domain.model.SignedVerdict
import com.field360.tracker.domain.model.toAction
import com.field360.tracker.domain.model.toInfo
import com.field360.tracker.domain.repository.LicenseApi
import com.field360.tracker.domain.repository.LicenseVerdictStore
import com.field360.tracker.domain.repository.VerdictAuthenticator
import com.field360.tracker.sdkLog
import com.field360.traker.geo.port.TrackLogger
import java.security.SecureRandom

/**
 * The online half of licensing: ask, authenticate, cache, decide.
 *
 * Three rules govern it, and they are why this is a use case of its own rather than more
 * code inside the offline gate:
 *
 *  1. **Never blocking the startup path.** `ready()` launches this unawaited and decides
 *     from cache; it also runs on a `LicenseCheckWorker` tick. Nothing waits on it.
 *  2. **Fail open.** Network error, timeout, 5xx, garbage, no compiled-in key — carry on.
 *     A licence server having a bad day must not stop a paying customer's tracking.
 *  3. **Verify twice** — the signature, *and* what it is a signature of. See
 *     [VerdictAuthenticator]; one without the other is worthless.
 *
 * Every dependency arrives through the constructor and every one of them is an interface,
 * so the whole thing runs as a plain JVM test with no server, no keypair and no
 * `Context`. That is the point of the seams, and there is no DI framework behind it —
 * `di/TrackerGraph.kt` wires this by hand like everything else.
 */
internal class CheckLicenseRevocationUseCase(
    private val api: LicenseApi,
    private val authenticator: VerdictAuthenticator,
    private val store: LicenseVerdictStore,
    private val packageName: String,
    private val sdkVersion: String,
    private val random: SecureRandom = SecureRandom(),
    private val logger: TrackLogger = TrackLogger.NoOp,
) {

    /**
     * One opportunistic check: what to do, and what to tell the host.
     *
     * **Never throws.** Every failure inside resolves to
     * [LicenseCheckResult.Inconclusive] — carry on, report nothing — because the
     * alternative, an exception escaping onto a WorkManager thread, turns a licence
     * server outage into a crash loop in someone else's app.
     *
     * A fresh cached answer short-circuits the network. That is what makes this safe to
     * expose to hosts through `Tracker.checkLicense()`: calling it in a loop still
     * produces at most one request per TTL, so a host cannot turn it into a way to
     * exhaust the server's 120-requests-per-minute budget.
     */
    suspend operator fun invoke(token: String): LicenseCheckResult {
        if (!authenticator.isConfigured) {
            sdkLog { logger.d(API_TAG, "skipped: no response public key compiled in") }
            return LicenseCheckResult.Inconclusive
        }

        store.read(token)?.let { cached ->
            if (!cached.isStale()) {
                sdkLog {
                    logger.d(API_TAG, "cache fresh (${cached.verdict.status}) — no request")
                }
                return cached.verdict.result(fromCache = true)
            }
            sdkLog { logger.d(API_TAG, "cache stale (${cached.verdict.status}) — rechecking") }
        }

        val nonce = newNonce()
        val document = api.verify(
            LicenseCheckRequest(
                accessKey = token,
                packageName = packageName,
                sdkType = SDK_TYPE,
                sdkVersion = sdkVersion,
                nonce = nonce,
            ),
        ) ?: run {
            sdkLog { logger.d(API_TAG, "no usable response — carrying on") }
            return LicenseCheckResult.Inconclusive
        }

        // A failed check is not a verdict. Treat it as if the call never happened rather
        // than as evidence against the licence — a proxy the device owner controls can
        // produce a failed check on demand, and must not be able to produce a stop.
        val verdict = authenticator.authenticate(document, token, nonce) ?: run {
            // Worth a warning rather than a debug line: a genuine server does not send
            // these, so this is either a proxy in the way or a key rotation nobody
            // shipped. Either way it silently disables enforcement until someone looks.
            sdkLog { logger.w(API_TAG, "response failed verification — ignored, carrying on") }
            return LicenseCheckResult.Inconclusive
        }

        store.write(token, verdict)
        val result = verdict.result(fromCache = false)
        sdkLog {
            logger.d(
                API_TAG,
                "verdict ${verdict.status} valid=${verdict.valid} " +
                    "ttl=${verdict.ttlSeconds}s -> ${result.action.describe()}",
            )
        }
        return result
    }

    /**
     * Spelled out rather than `action::class.simpleName`, which R8 would rename. Logging
     * is compiled out of release builds, so that would not bite today — but it is the
     * kind of line that gets copied into somewhere it does.
     */
    private fun LicenseAction.describe(): String = when (this) {
        LicenseAction.CarryOn -> "carry on"
        is LicenseAction.Stop -> "STOP (${code.name})"
        is LicenseAction.Diagnose -> "diagnose (${code.name})"
    }

    private fun SignedVerdict.result(fromCache: Boolean) =
        LicenseCheckResult(action = toAction(), info = toInfo(fromCache))

    /** 96 bits from [SecureRandom]. Enough that an attacker cannot pre-capture the echo. */
    private fun newNonce(): String {
        val bytes = ByteArray(NONCE_BYTES).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    internal companion object {
        /**
         * `"android"` from this SDK. A React Native or Flutter app reports itself as
         * `react-native` / `flutter` from its bridge — the bridge is what the licence was
         * issued against, not the native SDK underneath it.
         */
        const val SDK_TYPE: String = "android"
        private const val NONCE_BYTES = 12
    }
}

/**
 * The cached verdict only — no network, no matter how stale it is.
 *
 * This is what `ready()` consults, and the staleness is deliberately ignored here. A stop
 * that has aged past its TTL is still the last thing the server actually said, and
 * letting it lapse into `CarryOn` would hand every revoked install the same trick: turn
 * the network off and wait a day.
 *
 * Separate from [CheckLicenseRevocationUseCase] rather than a second method on it,
 * because the two have opposite obligations — this one must never touch the network, and
 * a startup path that could reach a method that does is a latency bug waiting to be
 * introduced by someone reading the wrong overload.
 */
internal class GetCachedLicenseActionUseCase(
    private val store: LicenseVerdictStore,
) {
    suspend operator fun invoke(token: String): LicenseAction =
        store.read(token)?.verdict?.toAction() ?: LicenseAction.CarryOn
}

/**
 * The last authenticated verdict, as a host sees it. Cache only — no network, ever.
 *
 * Backs `Tracker.licenseInfo()`, so a host can ask "what is my licence doing" at any
 * moment without that question costing a request or a round trip. Null means no verified
 * answer has ever been stored: a build with no licence configured, a first run that has
 * not reached its first check yet, or a cache entry that failed re-verification on the
 * way out.
 *
 * Staleness is not filtered here. An answer past its TTL is still the last thing the
 * server actually said, and hiding it would leave a host with `null` where it had a
 * perfectly good — if elderly — verdict. [LicenseInfo.ttlSeconds] and
 * [LicenseInfo.checkedAt] are there for a caller that wants to judge age for itself.
 */
internal class GetLicenseInfoUseCase(
    private val store: LicenseVerdictStore,
) {
    suspend operator fun invoke(token: String): LicenseInfo? =
        store.read(token)?.verdict?.toInfo(fromCache = true)
}
