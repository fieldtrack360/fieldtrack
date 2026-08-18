package com.devstree.traker.sync.internal

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Parses an RFC 9110 `Retry-After` header into a delay in milliseconds.
 *
 * Both forms are accepted because both are in the wild: `Retry-After: 120` (delta-seconds)
 * and `Retry-After: Wed, 21 Oct 2026 07:28:00 GMT` (an HTTP-date). CDNs in front of an API
 * tend to send the date form even when the origin sends seconds.
 *
 * The clamp is not decoration. This value schedules background work, so an unbounded header
 * — a date years out, a nine-digit second count, a clock-skewed origin — parks the upload
 * queue for that long and looks exactly like a broken SDK. Anything outside [MIN_DELAY_MS]
 * to [MAX_DELAY_MS] is pulled to the nearest bound; anything unparseable, negative or
 * already in the past answers `null`, which means "no server opinion, use our own backoff".
 *
 * @param nowMs wall-clock time, passed in rather than read so the date form is testable.
 */
internal fun parseRetryAfter(header: String?, nowMs: Long): Long? {
    val raw = header?.trim().orEmpty()
    if (raw.isEmpty()) return null

    val deltaMs = raw.toLongOrNull()?.let { seconds ->
        if (seconds < 0) return null
        // Capped before the multiply, not after: a header of 9_999_999_999_999_999 seconds
        // overflows Long once multiplied and comes back negative, which would read as "no
        // opinion" instead of "an absurdly long wait".
        seconds.coerceAtMost(MAX_DELAY_MS / MILLIS_PER_SECOND) * MILLIS_PER_SECOND
    } ?: httpDateDelayMs(raw, nowMs) ?: return null

    // A zero or past delay is a server saying "retry now"; that is what our own schedule
    // already does, so it is not an opinion worth overriding it with.
    if (deltaMs <= 0) return null

    return deltaMs.coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
}

private fun httpDateDelayMs(value: String, nowMs: Long): Long? = try {
    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
        .toInstant()
        .toEpochMilli() - nowMs
} catch (_: DateTimeParseException) {
    null
}

private const val MILLIS_PER_SECOND = 1_000L
internal const val MIN_DELAY_MS: Long = 1_000L
internal const val MAX_DELAY_MS: Long = 6L * 60 * 60 * 1_000
