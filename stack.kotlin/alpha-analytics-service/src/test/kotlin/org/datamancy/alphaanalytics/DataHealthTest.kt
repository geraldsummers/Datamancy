package org.datamancy.alphaanalytics

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataHealthTest {
    @Test
    fun `orderbook-live low-activity symbol becomes idle-live and not readiness-eligible`() {
        val issue = evaluateDataHealthRow(
            row = baseRow(
                symbol = "LOW",
                candleLatestRawTime = Instant.parse("2026-03-27T05:00:00Z"),
                tradeLatestRawTime = Instant.parse("2026-03-27T04:58:00Z"),
                orderbookLatestRawTime = Instant.parse("2026-03-27T05:00:55Z"),
                candleRawLagSeconds = 180,
                tradeRawLagSeconds = 240,
                orderbookRawLagSeconds = 5,
                recentTradeObservedShare24h = 0.02
            ),
            thresholds = thresholds(minTradeObservedRatioForEligibility = 0.10)
        )

        assertEquals(DataHealthStatus.IDLE_LIVE, issue.status)
        assertEquals(DataHealthLivenessClass.LIVE_SPARSE, issue.livenessClass)
        assertFalse(issue.readinessEligible)
        assertEquals(listOf("candle_1m", "trade"), issue.idleButLiveChannels.sorted())
        assertTrue(issue.staleChannels.isEmpty())
        assertTrue(issue.reasons.any { it.contains("idle but live") })
    }

    @Test
    fun `eligible symbol with stale candle remains critical`() {
        val issue = evaluateDataHealthRow(
            row = baseRow(
                symbol = "ACTIVE",
                candleLatestRawTime = Instant.parse("2026-03-27T04:55:00Z"),
                tradeLatestRawTime = Instant.parse("2026-03-27T05:00:30Z"),
                orderbookLatestRawTime = Instant.parse("2026-03-27T05:00:55Z"),
                candleRawLagSeconds = 360,
                tradeRawLagSeconds = 30,
                orderbookRawLagSeconds = 5,
                recentTradeObservedShare24h = 0.60
            ),
            thresholds = thresholds(minTradeObservedRatioForEligibility = 0.10)
        )

        assertEquals(DataHealthStatus.DEGRADED, issue.status)
        assertEquals(DataHealthLivenessClass.LIVE_SPARSE, issue.livenessClass)
        assertTrue(issue.readinessEligible)
        assertTrue("candle_1m" in issue.staleChannels)
        assertTrue(issue.idleButLiveChannels.isEmpty())
        assertTrue(issue.reasons.any { it.contains("live sparse market") })
    }

    @Test
    fun `eligible symbol with stale trade but live candle becomes live sparse not execution stale`() {
        val issue = evaluateDataHealthRow(
            row = baseRow(
                symbol = "TRADEQUIET",
                candleLatestRawTime = Instant.parse("2026-03-27T05:00:00Z"),
                tradeLatestRawTime = Instant.parse("2026-03-27T04:58:00Z"),
                orderbookLatestRawTime = Instant.parse("2026-03-27T05:00:55Z"),
                candleRawLagSeconds = 30,
                tradeRawLagSeconds = 240,
                orderbookRawLagSeconds = 5,
                recentTradeObservedShare24h = 0.60
            ),
            thresholds = thresholds(minTradeObservedRatioForEligibility = 0.10)
        )

        assertEquals(DataHealthStatus.DEGRADED, issue.status)
        assertEquals(DataHealthLivenessClass.LIVE_SPARSE, issue.livenessClass)
        assertTrue(issue.readinessEligible)
        assertTrue("trade" in issue.staleChannels)
        assertTrue(issue.reasons.any { it.contains("live sparse market") })
    }

    @Test
    fun `active symbol with stale execution context becomes critical and blocks readiness`() {
        val issue = evaluateDataHealthRow(
            row = baseRow(
                symbol = "STALLED",
                candleLatestRawTime = Instant.parse("2026-03-27T05:00:00Z"),
                tradeLatestRawTime = Instant.parse("2026-03-27T04:40:00Z"),
                orderbookLatestRawTime = Instant.parse("2026-03-27T04:40:00Z"),
                candleRawLagSeconds = 30,
                tradeRawLagSeconds = 1_260,
                orderbookRawLagSeconds = 1_260,
                recentTradeObservedShare24h = 0.60
            ),
            thresholds = thresholds(minTradeObservedRatioForEligibility = 0.10)
        )

        assertEquals(DataHealthStatus.CRITICAL, issue.status)
        assertEquals(DataHealthLivenessClass.LOCAL_STALE, issue.livenessClass)
        assertFalse(issue.readinessEligible)
        assertTrue("trade" in issue.staleChannels)
        assertTrue("orderbook_l2" in issue.staleChannels)
        assertTrue(issue.reasons.any { it.contains("execution context is not currently live") })
    }

    private fun thresholds(minTradeObservedRatioForEligibility: Double) = DataHealthThresholds(
        exchange = "hyperliquid_mainnet",
        barMinutes = 1,
        requiredRawChannels = listOf("candle_1m", "orderbook_l2", "trade"),
        rawStaleAfterSeconds = 120,
        candleRawLagMaxSeconds = 90,
        featureLagMaxSeconds = 180,
        finalizedLagMaxMinutes = 5,
        minCoverageRatio = 0.98,
        minFinalizedRatio = 0.95,
        minExecutionObservedRatio = 0.55,
        minUniverseSymbols = 12,
        minTradeObservedRatioForEligibility = minTradeObservedRatioForEligibility
    )

    private fun baseRow(
        symbol: String,
        candleLatestRawTime: Instant?,
        tradeLatestRawTime: Instant?,
        orderbookLatestRawTime: Instant?,
        candleRawLagSeconds: Long?,
        tradeRawLagSeconds: Long?,
        orderbookRawLagSeconds: Long?,
        recentTradeObservedShare24h: Double
    ) = DataHealthSymbolRow(
        exchange = "hyperliquid_mainnet",
        symbol = symbol,
        activeRecent = true,
        latestAnyRawTime = orderbookLatestRawTime ?: tradeLatestRawTime ?: candleLatestRawTime,
        candleLatestRawTime = candleLatestRawTime,
        tradeLatestRawTime = tradeLatestRawTime,
        orderbookLatestRawTime = orderbookLatestRawTime,
        fundingLatestRawTime = null,
        openInterestLatestRawTime = null,
        candleRawLagSeconds = candleRawLagSeconds,
        tradeRawLagSeconds = tradeRawLagSeconds,
        orderbookRawLagSeconds = orderbookRawLagSeconds,
        fundingRawLagSeconds = null,
        openInterestRawLagSeconds = null,
        latestFeatureTime = Instant.parse("2026-03-27T05:00:00Z"),
        finalizedThrough = Instant.parse("2026-03-27T04:59:00Z"),
        featureLagSeconds = 60,
        finalizedLagMinutes = 2.0,
        featureRows = 1_440L,
        materializerLagSeconds = 60,
        coverageRatio = 0.99,
        finalizedRatio = 0.98,
        expectedBars = 1_440,
        observedBars = 1_425,
        finalizedBars = 1_410,
        recentFeatureRows24h = 1_400,
        recentTradeObservedShare24h = recentTradeObservedShare24h,
        recentOrderbookObservedShare24h = 0.99,
        recentExecutionObservedShare24h = 0.75,
        recentFinalizedShare24h = 0.98
    )
}
