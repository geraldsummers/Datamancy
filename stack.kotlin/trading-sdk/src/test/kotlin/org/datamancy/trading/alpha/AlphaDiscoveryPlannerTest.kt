package org.datamancy.trading.alpha

import org.datamancy.trading.policy.DatamancyTradingPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlphaDiscoveryPlannerTest {
    private val planner = AlphaDiscoveryPlanner { DatamancyTradingPolicy.default() }

    @Test
    fun `discovery planner prioritizes interday defaults`() {
        val candidates = planner.candidateTemplates(AlphaDiscoveryCandidateRequest(maxCandidates = 5))

        assertEquals(5, candidates.size)
        assertEquals(240, candidates.first().signalBarMinutes)
        assertEquals(72, candidates.first().rebalanceCadenceHours)
        assertTrue(candidates.all { it.forwardHours == 72 })
    }
}
