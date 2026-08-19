package com.field360.tracker.integrity.probes

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.field360.tracker.SecurityConfig
import com.field360.tracker.integrity.IntegrityFinding
import com.field360.tracker.integrity.IntegritySignal
import com.field360.tracker.integrity.internal.IntegrityEnvironment
import com.field360.tracker.integrity.internal.IntegrityFeed
import com.field360.tracker.integrity.internal.IntegrityObservation
import com.field360.tracker.integrity.internal.IntegrityProbe

/**
 * Fake-GPS apps, from two directions.
 *
 * **The fix itself.** `Location.isMock` is the platform's own answer and cannot be argued
 * with; `FixMapper` already reads it and `MockPolicy.REJECT` already drops those fixes.
 * What this probe adds is memory: [IntegritySignal.MOCK_LOCATION_FIX] says a mock fix
 * arrived *in this session*, so a session that produced no stored points because every
 * fix was rejected still reports why. Needs no permission and no package visibility.
 *
 * **The selected app, best effort.** The mock-location app-op is granted to exactly one
 * package at a time, and it can be read for any package whose `uid` is known. Android 11
 * package visibility caps what `getInstalledPackages()` returns, so this catches the app
 * *before* the first fake fix on devices and hosts where the list is broad, and returns
 * nothing where it is narrow. That limit is accepted deliberately: the alternative is
 * `QUERY_ALL_PACKAGES`, which this SDK will not force on its hosts — a Play policy
 * declaration for a diagnostic signal that the per-fix flag already covers.
 */
internal class MockLocationProbe(
    private val context: Context,
    private val feed: IntegrityFeed,
) : IntegrityProbe {

    override fun observe(config: SecurityConfig): List<IntegrityObservation> = buildList {
        if (feed.sawMockFix()) {
            add(
                IntegrityObservation(
                    signal = IntegritySignal.MOCK_LOCATION_FIX,
                    detail = "a mock fix was delivered in this session",
                ),
            )
        }

        val holders = mockOpHolders()
        if (holders.isNotEmpty()) {
            add(
                IntegrityObservation(
                    signal = IntegritySignal.MOCK_LOCATION_APP_SELECTED,
                    detail = holders.take(IntegrityFinding.MAX_DETAIL_ITEMS).joinToString(", "),
                ),
            )
        }
    }

    // The narrowed Android 11+ result IS this probe's documented behaviour: the SDK will not
    // request QUERY_ALL_PACKAGES, and MOCK_LOCATION_FIX covers what this misses.
    @SuppressLint("QueryPermissionsNeeded")
    private fun mockOpHolders(): List<String> {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return emptyList()
        val packageManager = context.packageManager

        val installed = runCatching {
            packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())

        return installed.asSequence()
            .filterNot { it.packageName == context.packageName }
            // A system package holding the op is the platform's own test infrastructure,
            // not a fake-GPS app off the store.
            .filterNot { IntegrityEnvironment.isSystemPackage(it) }
            .filter { info -> holdsMockOp(appOps, info.uid, info.packageName) }
            .map { it.packageName }
            .toList()
    }

    /**
     * `unsafeCheckOpNoThrow` is deprecated but has no replacement that answers this
     * question for *another* package: the successors (`noteOp`, `checkOpNoThrow` with a
     * `Context`-derived attribution) all ask about the caller. The deprecation is
     * suppressed rather than worked around, and a failure is read as "not granted" — an
     * app-op query that throws must not turn into a claim about the device.
     */
    @Suppress("DEPRECATION")
    private fun holdsMockOp(appOps: AppOpsManager, uid: Int, packageName: String): Boolean =
        runCatching {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, packageName)
            } else {
                // The pre-29 spelling of the same query. Both are deprecated and neither has
                // a successor that answers about another package — see the KDoc above.
                appOps.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, uid, packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
}
