package org.datamancy.trading.alpha

import org.datamancy.trading.policy.AlphaSearchPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InterdayThresholdCalibratorTest {
    private val calibrator = InterdayThresholdCalibrator()

    @Test
    fun `calibration lowers edge gate when lower threshold preserves stronger median forward edge`() {
        val policy = AlphaSearchPolicy(
            minBacktestTrades = 12,
            minForwardTrades = 4,
            minNetEdgeBps = 2.0,
            minForwardCalmar = 0.1
        )
        val evaluations = listOf(
            candidate(rank = 1, backtestEdge = 2.04, forwardEdge = 0.10, tradeCount = 230, forwardTrades = 11),
            candidate(rank = 2, backtestEdge = 2.03, forwardEdge = 0.11, tradeCount = 229, forwardTrades = 11),
            candidate(rank = 3, backtestEdge = 2.01, forwardEdge = 0.12, tradeCount = 228, forwardTrades = 11),
            candidate(rank = 4, backtestEdge = 1.69, forwardEdge = 2.55, tradeCount = 234, forwardTrades = 8),
            candidate(rank = 5, backtestEdge = 1.58, forwardEdge = 2.64, tradeCount = 232, forwardTrades = 7),
            candidate(rank = 6, backtestEdge = 1.57, forwardEdge = 2.63, tradeCount = 232, forwardTrades = 7)
        )

        val result = calibrator.calibrate(
            evaluations = evaluations,
            currentPolicy = policy,
            request = InterdayThresholdCalibrationRequest(
                thresholdGridBps = listOf(1.5, 1.75, 2.0),
                minAcceptedCandidates = 3,
                minForwardPositiveRatio = 1.0,
                minMedianForwardEdgeBps = 0.0
            )
        )

        assertEquals(1.5, result.selectedMinNetEdgeBps)
        assertEquals(1.5, result.recommendedPolicy.minNetEdgeBps)
        assertTrue(result.acceptedCandidatesAtSelectedThreshold >= 6)
    }

    @Test
    fun `calibration keeps current gate when no threshold satisfies breadth and forward quality constraints`() {
        val policy = AlphaSearchPolicy(minNetEdgeBps = 2.0)
        val evaluations = listOf(
            candidate(rank = 1, backtestEdge = 1.25, forwardEdge = -0.10, tradeCount = 20, forwardTrades = 5),
            candidate(rank = 2, backtestEdge = 1.50, forwardEdge = -0.05, tradeCount = 21, forwardTrades = 5)
        )

        val result = calibrator.calibrate(
            evaluations = evaluations,
            currentPolicy = policy,
            request = InterdayThresholdCalibrationRequest(
                thresholdGridBps = listOf(1.0, 1.5, 2.0),
                minAcceptedCandidates = 2,
                minForwardPositiveRatio = 0.5,
                minMedianForwardEdgeBps = 0.0
            )
        )

        assertEquals(2.0, result.selectedMinNetEdgeBps)
        assertEquals(2.0, result.recommendedPolicy.minNetEdgeBps)
        assertTrue(result.notes.any { it.contains("keeping the current minNetEdgeBps") })
    }

    private fun candidate(
        rank: Int,
        backtestEdge: Double,
        forwardEdge: Double,
        tradeCount: Int,
        forwardTrades: Int
    ): InterdayCandidateEvaluation = InterdayCandidateEvaluation(
        rank = rank,
        config = InterdayAlphaConfig(),
        backtest = performance(segment = "backtest", edge = backtestEdge, trades = tradeCount, calmar = 6.0),
        forward = performance(segment = "forward", edge = forwardEdge, trades = forwardTrades, calmar = if (forwardEdge >= 0.0) 1.0 else -1.0),
        validation = InterdayValidation(
            accepted = false,
            backtestAccepted = false,
            forwardAccepted = false,
            reasons = listOf("synthetic")
        ),
        selectedSignals = emptyList(),
        targets = emptyList()
    )

    private fun performance(
        segment: String,
        edge: Double,
        trades: Int,
        calmar: Double
    ): InterdayPerformance = InterdayPerformance(
        segment = segment,
        startTime = null,
        endTime = null,
        netReturnPct = edge / 10.0,
        annualizedReturnPct = edge,
        grossReturnPct = edge,
        sharpe = edge / 10.0,
        maxDrawdownPct = 5.0,
        tradeCount = trades,
        winRate = if (edge >= 0.0) 0.55 else 0.45,
        avgTurnoverPct = 10.0,
        edgeAfterCostBps = edge,
        bootstrapReturnP05Pct = 0.0,
        bootstrapSharpeP05 = 0.0,
        stabilityScore = 0.5,
        calmar = calmar,
        ulcerIndex = 1.0,
        timeUnderWaterPct = 50.0,
        cvar1dPct = 1.0,
        alignedParticipationRate = 0.5,
        wrongWayExposurePct = 10.0,
        profitGivebackPct = 5.0,
        pnlSkew = 0.0,
        avgWinnerLoserRatio = 1.1,
        killSwitchUtilizationMax = 0.5
    )
}
