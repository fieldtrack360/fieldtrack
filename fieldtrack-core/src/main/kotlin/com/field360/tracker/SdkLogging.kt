package com.field360.tracker

/** Keeps log message construction out of release bytecode while preserving debug logs. */
internal inline fun sdkLog(block: () -> Unit) {
    if (BuildConfig.SDK_LOGGING_ENABLED) block()
}

/**
 * The licence API's logcat tag. `AndroidLogger` prefixes it, so it reads as `Tracker/API`:
 *
 * ```
 * adb logcat -s Tracker/API
 * ```
 *
 * One constant shared by the transport and the use case, so a filtered log shows the
 * whole path — the request, what came back, and what was decided about it — rather than
 * the HTTP half on its own.
 *
 * **Nothing logged under this tag identifies the licence.** No access key, no `key_id`, no
 * signature, no response body. Logcat is readable by `adb` on any developer machine and by
 * anything holding `READ_LOGS`, so an access key written here would be an access key
 * handed to whoever is watching — and unlike a leaked URL, that one is a credential.
 * Release builds compile these calls out entirely (`SDK_LOGGING_ENABLED` is false), but
 * the redaction is deliberate rather than a side effect of that.
 */
internal const val API_TAG: String = "API"
