package com.field360.traker.sync

/** Keeps log message construction out of release bytecode while preserving debug logs. */
internal inline fun sdkLog(block: () -> Unit) {
    if (BuildConfig.SDK_LOGGING_ENABLED) block()
}
