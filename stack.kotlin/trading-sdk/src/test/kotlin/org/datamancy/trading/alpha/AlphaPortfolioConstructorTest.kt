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
        assertTrue(highConfidence.targetGrossFraction > lowConfidence.targetGrossFraction)
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

    private fun sampleSignals(confidence: Double): List<AlphaSignalScore> = listOf(
        AlphaSignalScore(symbol = "BTC", score = 1.2, confidence = confidence, predictedVolatility = 0.4, liquidityScore = 1.0),
        AlphaSignalScore(symbol = "ETH", score = 0.9, confidence = confidence, predictedVolatility = 0.5, liquidityScore = 1.0),
        AlphaSignalScore(symbol = "SOL", score = 0.7, confidence = confidence, predictedVolatility = 0.6, liquidityScore = 0.9),
        AlphaSignalScore(symbol = "DOGE", score = -0.6, confidence = confidence, predictedVolatility = 0.8, liquidityScore = 0.8),
        AlphaSignalScore(symbol = "XRP", score = -0.9, confidence = confidence, predictedVolatility = 0.5, liquidityScore = 0.9),
        AlphaSignalScore(symbol = "AVAX", score = -1.1, confidence = confidence, predictedVolatility = 0.7, liquidityScore = 0.8)
    )
}
