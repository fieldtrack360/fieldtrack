package com.devstree.trackit.domain.model

/**
 * Charge level and power state, as the platform last reported them.
 *
 * Diagnostic, never a gate — nothing in the capture pipeline reads this. It exists because
 * it is the first question asked of every field report: did the tracker stop because the OEM
 * killed it, or because the phone was at 3 % (§11.1).
 *
 * Every field is nullable-or-unknown rather than defaulted, and that is deliberate. A phone
 * that will not say what its charge is has not told you it is at 0 %, and a `0` there reads
 * as a device about to die — the opposite of the truth.
 *
 * @property percent 0..100, or null when the platform will not say.
 * @property isCharging null when unknown. True while plugged in or full.
 * @property powerSource what it is plugged into, or [PowerSource.NONE] on battery.
 */
public data class BatteryInfo(
    val percent: Int? = null,
    val isCharging: Boolean? = null,
    val powerSource: PowerSource = PowerSource.UNKNOWN,
) {
    /** True only when the platform gave a level and it is at or below [LOW_PERCENT]. */
    public val isLow: Boolean get() = percent != null && percent <= LOW_PERCENT

    public companion object {
        /**
         * Matches the level at which Android itself fires `ACTION_BATTERY_LOW` on most
         * devices. An SDK-side number rather than the system's, because the system's is not
         * readable — so it is documented rather than pretended to be authoritative.
         */
        public const val LOW_PERCENT: Int = 15

        /** No reading yet, or the platform declined to give one. */
        public val Unknown: BatteryInfo = BatteryInfo()
    }
}

public enum class PowerSource {
    NONE,
    AC,
    USB,
    WIRELESS,
    DOCK,

    /** No reading yet, or a plug type this SDK does not recognise. */
    UNKNOWN,
}
