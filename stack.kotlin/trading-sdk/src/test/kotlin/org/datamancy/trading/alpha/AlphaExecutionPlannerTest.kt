package org.datamancy.trading.alpha

import org.datamancy.trading.policy.DatamancyTradingPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlphaExecutionPlannerTest {
    private val planner = AlphaExecutionPlanner { DatamancyTradingPolicy.default() }

    @Test
    fun `execution planner prefers maker ramp for high confidence targets`() {
        val plan = planner.plan(
            AlphaExecutionPlanRequest(
                targets = listOf(sampleTarget(confidence = 0.9)),
                defaultSpreadBps = 5.0,
                defaultImpactBps = 3.0
            )
        )

        assertTrue(plan.childOrders.isNotEmpty())
        assertEquals(AlphaExecutionStyle.MAKER_RAMP, plan.childOrders.first().style)
        assertTrue(plan.estimatedMakerShare >= 0.8)
    }

    @Test
    fun `execution planner falls back to vwap track for weak conviction`() {
        val plan = planner.plan(
            AlphaExecutionPlanRequest(
                targets = listOf(sampleTarget(confidence = 0.2)),
                defaultSpreadBps = 12.0,
                defaultImpactBps = 8.0
            )
        )

        assertEquals(AlphaExecutionStyle.VWAP_TRACK, plan.childOrders.first().style)
        assertTrue(plan.estimatedCostBps > 0.0)
    }

    private fun sampleTarget(confidence: Double): AlphaPortfolioTarget = AlphaPortfolioTarget(
        symbol = "BTC",
        direction = AlphaDirection.LONG,
        weightFraction = 0.24,
        leverageMultiplier = 1.0 + confidence,
        confidence = confidence,
        score = 1.4,
        normalizedScore = 0.5,
        trailingStopVolMultiple = 1.0,
        takeProfitVolMultiple = 2.5,
        rationale = "test"
    )
}
