package com.field360.tracker.domain.model

/**
 * One shape for every call the SDK makes, and one place that decides what a failure was.
 *
 * Before this, the licence transport returned `String?` and every failure — no network, a
 * 401, a 500, an empty body — collapsed into the same null. That was correct as *policy*
 * and useless as *diagnosis*: a licence that silently never enforces looks identical to
 * one that is fine, and the log said nothing about which.
 *
 * **The collapse is preserved on purpose; only the reporting changed.** See [ApiError].
 */
internal sealed interface ApiResult<out T> {

    /** A call that reached a server and came back with a body worth looking at. */
    data class Success<T>(val value: T, val httpStatus: Int) : ApiResult<T>

    /** Everything else. Carries why, for the log — never for a decision. */
    data class Failure(val error: ApiError) : ApiResult<Nothing>

    val successOrNull: T? get() = (this as? Success)?.value

    val errorOrNull: ApiError? get() = (this as? Failure)?.error
}

/**
 * A failed call, described once.
 *
 * **Nothing branches on [code] to decide a licence.** The adversary is the device owner,
 * who controls DNS, the proxy and the trust store, so every value here is something they
 * can produce on demand — a 401 by pointing the app at a server that refuses, a timeout by
 * dropping packets. If any of them changed the verdict, that would be a way to disable the
 * SDK rather than a way to enforce a licence. `CheckLicenseRevocationUseCase` maps the
 * whole type to one outcome: carry on.
 *
 * What it *is* for: a log line that says which of a dozen indistinguishable failures
 * actually happened, and a support conversation that does not start from zero.
 */
internal data class ApiError(
    val code: ApiErrorCode,
    /** Null when the call never reached a server at all. */
    val httpStatus: Int? = null,
    /** Exception type, or a short note. Never a body, never a stack trace. */
    val detail: String? = null,
) {
    /** Compact and safe to log — see `API_TAG` for what is deliberately kept out. */
    fun describe(): String = buildString {
        append(code.name)
        httpStatus?.let { append(" (HTTP ").append(it).append(')') }
        detail?.let { append(": ").append(it) }
    }
}

/**
 * The failure taxonomy, mapped in exactly one place — [com.field360.tracker.data.remote.toApiError].
 *
 * Deliberately coarse. These exist to be read by a human in a log, not to be switched on,
 * and a finer taxonomy would only invite someone to start switching on it.
 */
internal enum class ApiErrorCode {
    /** No licence URL compiled in. Not an error — an unconfigured build. */
    NOT_CONFIGURED,

    /** DNS failure, no route, connection refused. The call never left the device usefully. */
    NO_NETWORK,

    /** Connect or read timeout. */
    TIMEOUT,

    /** 401 or 403. The server would not talk to us — which is not a verdict about a licence. */
    UNAUTHORIZED,

    /** 404. Usually a base URL missing or carrying its version segment twice. */
    NOT_FOUND,

    /** 429. The fleet is checking too often, or the cache stopped short-circuiting. */
    RATE_LIMITED,

    /** Any other 4xx. */
    CLIENT_ERROR,

    /** Any 5xx. The server is having a bad day; the licence is unaffected. */
    SERVER_ERROR,

    /** 2xx with nothing in it. */
    EMPTY_BODY,

    /** A body that is not the JSON object this endpoint is documented to return. */
    MALFORMED_BODY,

    /** Anything the mapping did not anticipate. */
    UNKNOWN,
}
