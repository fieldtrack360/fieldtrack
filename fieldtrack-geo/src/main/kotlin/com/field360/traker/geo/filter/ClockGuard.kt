package com.field360.traker.geo.filter

/**
 * Tells a reboot apart from reordered delivery (EC-92a).
 *
 * Both look identical at the point of arrival — a fix whose monotonic timestamp is older
 * than one already processed — and they need opposite responses. A reboot invalidates
 * everything the filter holds, because position, velocity, origin and captured heading
 * all describe a timeline that no longer exists. Reordering invalidates nothing; it is
 * two batched deliveries interleaving, and the right answer is to drop the straggler and
 * carry on.
 *
 * The shipped code could not tell them apart: it called *any* backward step a reboot and
 * wiped the filter. A field capture caught that firing mid-drive on two batches arriving
 * 5.2 seconds out of order, throwing away a working filter for nothing.
 *
 * **Magnitude is the discriminator.** A reboot restarts `elapsedRealtimeNanos` at zero,
 * so it rewinds by the device's entire prior uptime — minutes at the very least, usually
 * hours. Reordering rewinds by at most a batching window, which this SDK caps at twice
 * the sampling interval.
 *
 * Pure and stateless: the caller owns the two counters, which keeps this unit-testable
 * and keeps the decision itself in `fieldtrack-geo` where every other one lives
 * (PLAN.md §3 invariant 1).
 */
public object ClockGuard {

    /** What a fix's monotonic timestamp says happened since the previous one. */
    public enum class Step {
        /** Time advanced, or this is the first fix. Process normally. */
        FORWARD,

        /** Older than one already processed. Drop it; change nothing. */
        OUT_OF_ORDER,

        /** The clock restarted. Everything the filter holds is stale. */
        REBOOT,
    }

    /**
     * @param lastElapsedNanos newest timestamp processed so far; `0` before the first fix.
     * @param outOfOrderRun consecutive fixes already dropped as [Step.OUT_OF_ORDER].
     */
    public fun classify(
        lastElapsedNanos: Long,
        fixElapsedNanos: Long,
        outOfOrderRun: Int,
        c: TrackerConstants = TrackerConstants.Default,
    ): Step {
        // 0 means "nothing seen yet", not "boot". Under a monotonic clock a genuine first
        // fix can legitimately arrive at nanos 0, and treating that as a rewind would
        // declare a reboot on the first fix of a session started right after boot.
        if (lastElapsedNanos == 0L) return Step.FORWARD

        val backwardNanos = lastElapsedNanos - fixElapsedNanos
        if (backwardNanos <= 0L) return Step.FORWARD
        if (backwardNanos >= c.rebootBackwardStepMs * NANOS_PER_MILLI) return Step.REBOOT

        // A small step is reordering — unless it keeps happening. A reboot early in a
        // session rewinds by less than the threshold, and without this escape every
        // subsequent fix would be dropped forever, because the held timestamp could never
        // advance past it. Reordering is sporadic; three in a row with no forward fix
        // between them is not reordering.
        return if (outOfOrderRun >= c.maxOutOfOrderRun) Step.REBOOT else Step.OUT_OF_ORDER
    }

    /**
     * Puts a delivery-ordered list back into the order the fixes actually happened.
     *
     * [classify] is for the live path, where each fix is judged as it arrives and a
     * straggler is simply dropped. Diagnostics keep the stragglers — the raw layer exists
     * to show what the OS handed over — and then has to draw them, at which point
     * delivery order braids the polyline back on itself.
     *
     * Neither timestamp sorts this alone, which is the whole reason [classify] exists:
     * `elapsedRealtimeNanos` is exact within a boot and meaningless across one, while
     * insertion order is exact across boots and wrong within a batch. So use each where
     * it holds — split the list wherever the monotonic clock rewinds far enough to be a
     * reboot, then sort inside each run.
     *
     * Stable within a run, so fixes sharing a timestamp keep their delivery order rather
     * than being shuffled by an unstable sort.
     *
     * @param elapsedNanos reads the monotonic timestamp; the list must be in insertion order.
     */
    public fun <T> inFixOrder(
        items: List<T>,
        c: TrackerConstants = TrackerConstants.Default,
        elapsedNanos: (T) -> Long,
    ): List<T> {
        if (items.size < 2) return items

        val rebootNanos = c.rebootBackwardStepMs * NANOS_PER_MILLI
        val ordered = ArrayList<T>(items.size)
        var run = mutableListOf(items.first())
        // Against the run's high-water mark, not its last element: a straggler is by
        // definition older than what came before it, and measuring the next fix against
        // *it* would understate the rewind and hide a reboot that lands right after one.
        var highWater = elapsedNanos(items.first())

        for (item in items.drop(1)) {
            val nanos = elapsedNanos(item)
            if (highWater - nanos >= rebootNanos) {
                ordered += run.sortedBy(elapsedNanos)
                run = mutableListOf(item)
                highWater = nanos
            } else {
                run += item
                if (nanos > highWater) highWater = nanos
            }
        }
        ordered += run.sortedBy(elapsedNanos)
        return ordered
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
