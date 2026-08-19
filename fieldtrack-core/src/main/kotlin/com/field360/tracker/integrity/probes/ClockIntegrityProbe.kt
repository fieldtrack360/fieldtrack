package com.field360.tracker.integrity.probes

import android.content.Context
import android.provider.Settings
import android.telephony.TelephonyManager
import com.field360.tracker.SecurityConfig
import com.field360.tracker.integrity.IntegritySignal
import com.field360.tracker.integrity.internal.IntegrityFeed
import com.field360.tracker.integrity.internal.IntegrityObservation
import com.field360.tracker.integrity.internal.IntegrityProbe
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Clock and time-zone tampering: "I was on shift at 09:00" when the phone says so and
 * nothing else does.
 *
 * Three references, in descending order of trust:
 *
 * 1. **GNSS UTC.** `Location.getTime()` on a satellite fix comes from the satellite
 *    signal, not from the device clock, and there is no Settings screen that changes it.
 *    It is the only trusted time source the SDK has offline, and the comparison is fed
 *    from the ingest path through [IntegrityFeed]. Skew above
 *    `SecurityConfig.maxClockSkewMs` raises [IntegritySignal.CLOCK_SKEWED].
 * 2. **The auto-time flags.** Setting a time zone by hand requires `auto_time_zone` off,
 *    so both flags off is the precondition for the manual clock the first reference would
 *    then catch. Weak on its own — some users just prefer it — hence confidence 50 and a
 *    default policy of `WARN`.
 * 3. **The serving network's country.** A phone whose cellular network is in India while
 *    its time zone claims America/Los_Angeles is either roaming oddly or lying. Skipped
 *    entirely with no cellular service: a Wi-Fi tablet has no network country and must not
 *    be flagged for having none.
 */
internal class ClockIntegrityProbe(
    private val context: Context,
    private val feed: IntegrityFeed,
) : IntegrityProbe {

    override fun observe(config: SecurityConfig): List<IntegrityObservation> = buildList {
        val skewMs = feed.clockSkewMs()
        if (skewMs != null && abs(skewMs) > config.maxClockSkewMs) {
            add(
                IntegrityObservation(
                    signal = IntegritySignal.CLOCK_SKEWED,
                    detail = "system clock is ${skewMs / 1000}s from GNSS UTC",
                ),
            )
        }

        if (autoTimeDisabled()) {
            add(
                IntegrityObservation(
                    signal = IntegritySignal.AUTO_TIME_DISABLED,
                    detail = "automatic date/time and time zone are both off",
                    confidence = CONFIDENCE_SETTINGS_ONLY,
                ),
            )
        }

        timezoneMismatch()?.let(::add)
    }

    private fun autoTimeDisabled(): Boolean {
        val resolver = context.contentResolver
        val autoTime = Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME, 1)
        val autoZone = Settings.Global.getInt(resolver, Settings.Global.AUTO_TIME_ZONE, 1)
        return autoTime == 0 && autoZone == 0
    }

    private fun timezoneMismatch(): IntegrityObservation? {
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return null

        // The *serving network's* country, not the SIM's — a traveller's SIM stays Indian
        // while the network they are actually on does not, and it is the network that says
        // where the phone is.
        val networkCountry = telephony.networkCountryIso?.takeIf { it.isNotBlank() }
            ?.uppercase(Locale.ROOT)
            ?: return null

        val zone = TimeZone.getDefault()?.id ?: return null

        // There is no id → country lookup, so the comparison runs the other way round:
        // every zone id the platform knows for this country. A match means the device's
        // zone is plausible for the network it is camped on.
        val idsForCountry = zoneIdsForCountry(networkCountry)
        if (idsForCountry.isEmpty()) return null
        if (zone in idsForCountry) return null

        return IntegrityObservation(
            signal = IntegritySignal.TIMEZONE_MISMATCH,
            detail = "time zone $zone is not used in $networkCountry (serving network)",
            confidence = CONFIDENCE_TIMEZONE,
        )
    }

    private fun zoneIdsForCountry(countryIso: String): Set<String> = runCatching {
        // android.icu is the platform's own tz database — no dependency, no bundled table
        // to go stale, and available since API 24.
        android.icu.util.TimeZone.getAvailableIDs(countryIso).toSet()
    }.getOrDefault(emptySet())

    private companion object {
        /** Both auto-time flags off is a precondition, not proof. */
        const val CONFIDENCE_SETTINGS_ONLY = 50

        /** Roaming and border regions make this suggestive rather than conclusive. */
        const val CONFIDENCE_TIMEZONE = 60
    }
}
