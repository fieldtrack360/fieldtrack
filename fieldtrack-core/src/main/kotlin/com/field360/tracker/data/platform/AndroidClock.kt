package com.field360.tracker.data.platform

import android.os.SystemClock
import android.util.Log
import com.field360.tracker.BuildConfig
import com.field360.traker.geo.port.Clock
import com.field360.traker.geo.port.TrackLogger

/**
 * The two clocks, kept apart on purpose.
 *
 * [wallTimeMs] is for storage and display and may jump backwards; only
 * [elapsedRealtimeNanos] is allowed into filter arithmetic (SOURCE-AUDIT A1).
 */
internal class AndroidClock() : Clock {
    override fun wallTimeMs(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}

internal class AndroidLogger() : TrackLogger {
    override fun d(tag: String, message: String) {
        if (BuildConfig.SDK_LOGGING_ENABLED) Log.d(prefixed(tag), message)
    }

    override fun w(tag: String, message: String) {
        if (BuildConfig.SDK_LOGGING_ENABLED) Log.w(prefixed(tag), message)
    }

    private fun prefixed(tag: String) = "Tracker/$tag"
}
