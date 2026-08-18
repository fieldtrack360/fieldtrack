package com.devstree.traker.geo.export

import com.devstree.traker.geo.filter.AcceptancePipeline
import com.devstree.traker.geo.filter.TrakerConstants
import com.devstree.traker.geo.model.FilterState
import com.devstree.traker.geo.model.FixDecision
import com.devstree.traker.geo.model.IngestContext
import com.devstree.traker.geo.model.TrackPoint
import com.devstree.traker.geo.model.Verdict

/**
 * Runs a [Fixture] back through the pipeline with no device attached.
 *
 * Deliberately mirrors what `FixIngestor` does at runtime — single consumer, `past`
 * advanced only on accept, state threaded through every fix including rejects — so a
 * replay and a live run cannot diverge. Same fixture twice ⇒ byte-identical decision
 * sequence (PLAN.md §6 criterion 7).
 */
public object FixtureReplay {

    public data class Result(
        val decisions: List<FixDecision>,
        val points: List<TrackPoint>,
        val finalState: FilterState,
    ) {
        public val verdicts: List<FixtureVerdict>
            get() = decisions.mapIndexed { index, decision ->
                FixtureVerdict(
                    i = index,
                    verdict = when (decision.verdict) {
                        is Verdict.Accept -> ACCEPT
                        is Verdict.Skip -> SKIP
                        is Verdict.Reject -> REJECT
                    },
                    reason = decision.reason,
                )
            }
    }

    public fun replay(
        fixture: Fixture,
        constants: TrakerConstants = TrakerConstants.Default,
        context: IngestContext = defaultContext(),
        initialState: FilterState = FilterState(),
        initialPast: TrackPoint? = null,
    ): Result {
        val pipeline = AcceptancePipeline(constants)
        val decisions = ArrayList<FixDecision>(fixture.fixes.size)
        val points = ArrayList<TrackPoint>()

        var state = initialState
        var past = initialPast

        for (fixtureFix in fixture.fixes) {
            val perFixContext = context.copy(stepsSinceLastPoint = fixtureFix.steps)
            val result = pipeline.accept(fixtureFix.toTrackFix(), past, state, perFixContext)

            // The new state is kept even on reject — a rejected fix may still have
            // burned a reject count or advanced the settle tally.
            state = result.state
            decisions += result.decision
            result.point?.let {
                points += it
                past = it
            }
        }

        return Result(decisions, points, state)
    }

    public fun defaultContext(sessionId: String = "fixture"): IngestContext = IngestContext(
        sessionId = sessionId,
        timezone = "UTC",
        localDate = "1970-01-01",
    )

    public const val ACCEPT: String = "ACCEPT"
    public const val SKIP: String = "SKIP"
    public const val REJECT: String = "REJECT"
}
