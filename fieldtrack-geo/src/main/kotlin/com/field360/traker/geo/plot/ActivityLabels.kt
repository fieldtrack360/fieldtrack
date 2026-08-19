package com.field360.traker.geo.plot

import com.field360.traker.geo.model.ActivityType

/**
 * Names what happened during a travel segment (spec §21).
 *
 * The captured activity label is a hint, not testimony. Where measured speed
 * contradicts it the speed wins — a "walk" that peaked at 25 m/s was a drive, and a
 * segment labelled `STILL` that covered half a kilometre was not still (EC-53).
 */
public object ActivityLabels {

    public data class Label(val name: String, val icon: String) {
        /** Collapsed bucket for the day summary. */
        val commuteCategory: String
            get() = when (name) {
                DRIVING, RIDING -> "Vehicle"
                else -> name
            }
    }

    /**
     * @param detected the label stamped at capture time, if any.
     * @param maxSpeedMps peak leg speed for the segment.
     * @param p75SpeedMps duration-weighted 75th percentile.
     */
    public fun label(
        detected: ActivityType?,
        maxSpeedMps: Float,
        p75SpeedMps: Float,
    ): Label {
        // A low-tier label that the speeds flatly contradict is overridden. This is the
        // plot-side half of "AR is enrichment, never authority".
        val contradicted = detected != null &&
            detected.isLowTier &&
            (maxSpeedMps >= OVERRIDE_MAX_MPS || p75SpeedMps >= OVERRIDE_P75_MPS)

        if (detected == null || contradicted) return speedBucket(maxSpeedMps, p75SpeedMps)

        return when (detected) {
            ActivityType.IN_VEHICLE -> Label(DRIVING, "🚗")
            ActivityType.ON_BICYCLE -> Label(CYCLING, "🚲")
            ActivityType.RUNNING -> Label(RUNNING, "🏃")
            ActivityType.WALKING, ActivityType.ON_FOOT -> Label(WALKING, "🚶")
            ActivityType.STILL -> Label(STATIONARY, "🧍")
            ActivityType.TILTING, ActivityType.UNKNOWN -> speedBucket(maxSpeedMps, p75SpeedMps)
        }
    }

    /** Thresholds in m/s, ordered fastest-first (spec §21). */
    public fun speedBucket(maxSpeedMps: Float, p75SpeedMps: Float): Label = when {
        maxSpeedMps >= 70f && p75SpeedMps >= 50f -> Label(FLIGHT, "✈️")
        maxSpeedMps >= 55f && p75SpeedMps >= 35f -> Label(TRAIN, "🚄")
        maxSpeedMps >= 25f -> Label(DRIVING, "🚗")
        maxSpeedMps >= 8f && p75SpeedMps >= 6f -> Label(RIDING, "🏍️")
        maxSpeedMps >= 5f && p75SpeedMps >= 3f -> Label(CYCLING, "🚲")
        p75SpeedMps >= 2.5f -> Label(RUNNING, "🏃")
        else -> Label(WALKING, "🚶")
    }

    /** Colour band for a segment, from the configured km/h thresholds. */
    public fun speedBand(speedMps: Float, bandsKmph: List<Float>): String {
        val kmph = speedMps * MPS_TO_KMPH
        val fast = bandsKmph.getOrElse(1) { 20f }
        val medium = bandsKmph.getOrElse(0) { 10f }
        return when {
            kmph >= fast -> "green"
            kmph >= medium -> "yellow"
            else -> "red"
        }
    }

    public const val FLIGHT: String = "Flight"
    public const val TRAIN: String = "Train"
    public const val DRIVING: String = "Driving"
    public const val RIDING: String = "Riding"
    public const val CYCLING: String = "Cycling"
    public const val RUNNING: String = "Running"
    public const val WALKING: String = "Walking"
    public const val STATIONARY: String = "Stationary"

    private const val OVERRIDE_MAX_MPS = 8f
    private const val OVERRIDE_P75_MPS = 5f
    private const val MPS_TO_KMPH = 3.6f
}
