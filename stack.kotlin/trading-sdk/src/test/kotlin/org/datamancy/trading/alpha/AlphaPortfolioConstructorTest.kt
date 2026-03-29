package org.datamancy.trading.alpha

import org.datamancy.trading.policy.DatamancyTradingPolicy
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlphaPortfolioConstructorTest {
    private val constructor = AlphaPortfolioConstructor { DatamancyTradingPolicy.default() }

    @Test
    fun `portfolio construction scales exposure gradually with confidence`() {
        val lowConfidence = constructor.construct(
            AlphaPortfolioRequest(
                signals = sampleSignals(confidence = 0.25),
                selectionQuantile = 0.50
            )
        )
        val highConfidence = constructor.construct(
            AlphaPortfolioRequest(
                signals = sampleSignals(confidence = 0.90),
                selectionQuantile = 0.50
            )
        )

        assertTrue(highConfidence.targetExposureFraction > lowConfidence.targetExposureFraction)
        assertTrue(highConfidence.targets.map { it.leverageMultiplier }.average() > lowConfidence.targets.map { it.leverageMultiplier }.average())
    }

    @Test
    fun `portfolio construction builds diversified long short book`() {
        val response = constructor.construct(
            AlphaPortfolioRequest(
                signals = sampleSignals(confidence = 0.70),
                selectionQuantile = 0.25
            )
        )

        val gross = response.targets.sumOf { abs(it.weightFraction) }
        assertTrue(response.selectedLongs > 0)
        assertTrue(response.selectedShorts > 0)
        assertTrue(gross > 0.0)
        assertTrue(response.targets.all { it.weightFraction <= 0.08 + 1e-9 })
        assertEquals(response.targetGrossFraction, gross, 1e-9)
    }

    @Test
    fun `portfolio construction preserves provided eligible basket when requested`() {
        val response = constructor.construct(
            AlphaPortfolioRequest(
                signals = sampleSignals(confidence = 0.70),
                selectionQuantile = 0.05,
                respectProvidedSignalSet = true
            )
        )

        assertEquals(3, response.selectedLongs)
        assertEquals(3, response.selectedShorts)
        assertTrue(response.targets.isNotEmpty())
        assertTrue(response.targets.size <= 6)
        assertTrue(response.targets.all { it.expectedNetEdgeBps > 0.0 })
    }

    @Test
    fun `portfolio construction filters weak net edge below entry floor`() {
        val response = constructor.construct(
            AlphaPortfolioRequest(
                signals = listOf(
                    AlphaSignalScore(
                        symbol = "BTC",
                        score = 0.4,
                        confidence = 0.75,
                        predictedVolatility = 0.4,
                        liquidityScore = 1.0,
                        expectedResidualReturnBps = 6.0,
                        expectedEntryCostBps = 3.0,
                        expectedNetEdgeBps = 0.5
                    ),
                    AlphaSignalScore(
                        symbol = "ETH",
                        score = 1.2,
                        confidence = 0.75,
                        predictedVolatility = 0.4,
                        liquidityScore = 1.0,
                        expectedResidualReturnBps = 8.0,
                        expectedEntryCostBps = 2.0,
                        expectedNetEdgeBps = 3.5
                    )
                ),
                longShort = false,
                respectProvidedSignalSet = true,
                minExpectedNetEdgeBps = 1.0
            )
        )

        assertEquals(listOf("ETH"), response.targets.map { it.symbol })
    }

    @Test
    fun `portfolio construction gives incumbent retention credit when edge is still positive`() {
        val response = constructor.construct(
            AlphaPortfolioRequest(
                signals = listOf(
                    AlphaSignalScore(
                        symbol = "INCUMBENT",
                        score = 4.0,
                        confidence = 0.80,
                        predictedVolatility = 0.5,
                        liquidityScore = 1.0,
                        expectedResidualReturnBps = 8.0,
                        expectedEntryCostBps = 2.0,
                        expectedTurnoverPenaltyBps = 3.0,
                        expectedNetEdgeBps = 4.0,
                        currentWeightFraction = 0.08
                    ),
                    AlphaSignalScore(
                        symbol = "CHALLENGER",
                        score = 5.0,
                        confidence = 0.80,
                        predictedVolatility = 0.5,
                        liquidityScore = 1.0,
                        expectedResidualReturnBps = 8.0,
                        expectedEntryCostBps = 2.0,
                        expectedTurnoverPenaltyBps = 0.0,
                        expectedNetEdgeBps = 5.0,
                        currentWeightFraction = 0.0
                    )
                ),
                longShort = false,
                respectProvidedSignalSet = true,
                currentWeightsBySymbol = mapOf("INCUMBENT" to 0.08),
                targetGrossFraction = 0.08
            )
        )

        val weightsBySymbol = response.targets.associateBy({ it.symbol }, { it.weightFraction })
        assertTrue(weightsBySymbol.getValue("INCUMBENT") > weightsBySymbol.getValue("CHALLENGER"))
    }

    @Test
    fun `portfolio construction drops targets whose adjusted edge turns non positive after turnover`() {
        val response = constructor.construct(
            AlphaPortfolioRequest(
                signals = listOf(
                    AlphaSignalScore(
                        symbol = "KEEP",
                        score = 20.0,
                        confidence = 0.85,
                        predictedVolatility = 0.4,
                        liquidityScore = 1.0,
                        expectedResidualReturnBps = 24.0,
                        expectedEntryCostBps = 2.0,
                        expectedTurnoverPenaltyBps = 0.0,
                        expectedNetEdgeBps = 20.0
                    ),
                    AlphaSignalScore(
                        symbol = "DROP",
                        score = 0.01,
                        confidence = 0.60,
                        predictedVolatility = 0.4,
                        liquidityScore = 1.0,
                        expectedResidualReturnBps = 5.0,
                        expectedEntryCostBps = 2.0,
                        expectedTurnoverPenaltyBps = 0.0,
                        expectedNetEdgeBps = 0.01,
                        currentWeightFraction = 0.08
                    )
                ),
                longShort = false,
                respectProvidedSignalSet = true,
                currentWeightsBySymbol = mapOf("DROP" to 0.08),
                targetGrossFraction = 0.08
            )
        )

        assertTrue("KEEP" in response.targets.map { it.symbol })
        assertTrue("DROP" !in response.targets.map { it.symbol })
        assertTrue(response.targets.all { it.expectedNetEdgeBps > 0.0 })
    }

    private fun sampleSignals(confidence: Double): List<AlphaSignalScore> = listOf(
        AlphaSignalScore(symbol = "BTC", score = 14.0, confidence = confidence, predictedVolatility = 0.4, liquidityScore = 1.0, expectedNetEdgeBps = 14.0),
        AlphaSignalScore(symbol = "ETH", score = 11.0, confidence = confidence, predictedVolatility = 0.5, liquidityScore = 1.0, expectedNetEdgeBps = 11.0),
        AlphaSignalScore(symbol = "SOL", score = 8.0, confidence = confidence, predictedVolatility = 0.6, liquidityScore = 0.9, expectedNetEdgeBps = 8.0),
        AlphaSignalScore(symbol = "DOGE", score = -8.0, confidence = confidence, predictedVolatility = 0.8, liquidityScore = 0.8, expectedNetEdgeBps = -8.0),
        AlphaSignalScore(symbol = "XRP", score = -11.0, confidence = confidence, predictedVolatility = 0.5, liquidityScore = 0.9, expectedNetEdgeBps = -11.0),
        AlphaSignalScore(symbol = "AVAX", score = -14.0, confidence = confidence, predictedVolatility = 0.7, liquidityScore = 0.8, expectedNetEdgeBps = -14.0)
    )
}
