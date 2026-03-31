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
        val defaults = planner.defaults()

        assertEquals(5, candidates.size)
        assertEquals(defaults.defaultSignalBarMinutes, candidates.first().signalBarMinutes)
        assertEquals(defaults.defaultConfig.rebalanceCadenceHours, candidates.first().rebalanceCadenceHours)
        assertTrue(candidates.all { it.forwardHours == 72 })
    }
}
