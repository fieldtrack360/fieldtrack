package com.field360.tracker.license

import com.field360.tracker.domain.model.LicenseAction

/**
 * The revocation latch for this process.
 *
 * The cache is the durable record — it holds the signed response and re-verifies it on
 * read, so a stop survives a reboot. This exists for the window the cache cannot cover on
 * its own: a background check that lands **mid-session**, after `ready()` has already let
 * the host through. Without it a host could call `stop()` and `start()` again and be
 * tracking on a revoked licence until the next scheduled check.
 *
 * Only [LicenseAction.Stop] is latched. A `Diagnose` is reported and forgotten, because a
 * ledger gap on our side is not grounds for refusing to start.
 */
internal object LicenseState {

    @Volatile
    private var stopped: LicenseAction.Stop? = null

    /** Non-null once a verified `revoked` or `expired` verdict has arrived. */
    val current: LicenseAction.Stop? get() = stopped

    /**
     * Applies a verified action. `CarryOn` clears the latch: a licence that was reinstated
     * on the server must be able to reinstate the app without a reinstall, and the only
     * thing that can clear it is another verdict that passed all three checks.
     */
    fun apply(action: LicenseAction) {
        stopped = when (action) {
            is LicenseAction.Stop -> action
            LicenseAction.CarryOn -> null
            is LicenseAction.Diagnose -> stopped
        }
    }

    /** Test seam only. */
    fun resetForTest() {
        stopped = null
    }
}
