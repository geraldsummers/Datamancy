package org.datamancy.trading.analytics.crosssectional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant

class CrossSectionalResearchTest {
    @Test
    fun `buildStrategySummaries carries bar minutes into metrics`() {
        val config = ResearchConfig(barMinutes = 60, persistBacktest = false, persistForward = false)
        val trade = tradeRecord(
            entryTime = Instant.parse("2026-03-20T00:00:00Z"),
            exitTime = Instant.parse("2026-03-21T00:00:00Z"),
            edgeAfterCostBps = 18.0,
            expectedNetEdgeBps = 11.0,
            grossReturnFraction = 0.003,
            netReturnFraction = 0.0018
        )

        val summaries = buildStrategySummaries(
            config = config,
            strategyName = "cross_section_beta_trend_v1",
            strategyKind = StrategyKind.TREND,
            trades = listOf(trade),
            timeframe = "candle_60m",
            notes = "test"
        )

        assertEquals(2, summaries.size)
        assertTrue(summaries.all { it.metricsJson.contains("\"bar_minutes\": 60") })
        assertTrue(summaries.any { it.symbol == "SOL" })
        assertTrue(summaries.any { it.symbol == "ALL" })
    }

    @Test
    fun `computeResearchDiagnostics exposes liquid and seeded counts`() {
        val config = ResearchConfig(
            barMinutes = 30,
            betaLookbackBars = 8,
            trendSlowBars = 8,
            topPerSide = 1,
            trendEntryScore = 1.0,
            reversionZEntry = 2.0,
            persistBacktest = false,
            persistForward = false
        )

        val rows = listOf(
            featureRow(
                symbol = "SOL",
                liquid = true,
                barIndex = 12,
                trendScore = 1.35,
                trendLongRank = 1,
                residualZ = 0.35,
                flowSignal = 0.55,
                rawTrend = 1.2,
                residualMomFast = 0.8,
                residualMomSlow = 0.9,
                trendExpectedGrossEdgeBps = 32.0,
                volumeRatio = 0.9,
                depthUsd = 450_000.0,
                spreadBps = 0.2,
                spreadPct = 0.002,
                imbalance = 0.35
            ),
            featureRow(
                symbol = "AVAX",
                liquid = false,
                barIndex = 4,
                trendScore = 0.5,
                volumeRatio = 0.1,
                depthUsd = 10_000.0,
                spreadBps = 18.0,
                spreadPct = 0.18
            )
        )

        val diagnostics = computeResearchDiagnostics(rows, config)

        assertEquals(2, diagnostics.totalRows)
        assertEquals(1, diagnostics.liquidRows)
        assertEquals(1, diagnostics.rankEligibleCounts.getValue("trendLong"))
        assertTrue(diagnostics.seedCounts.getValue("trend") >= 1)
        assertEquals("SOL", diagnostics.topTrendSeeds.first().symbol)
        assertEquals(1, diagnostics.liquidPerSymbol.getValue("SOL"))
        assertEquals(0, diagnostics.liquidPerSymbol.getValue("AVAX"))
    }

    private fun featureRow(
        symbol: String,
        liquid: Boolean,
        barIndex: Int,
        trendScore: Double,
        trendLongRank: Int = Int.MAX_VALUE,
        residualZ: Double = 0.0,
        flowSignal: Double = 0.2,
        rawTrend: Double = 0.6,
        residualMomFast: Double = 0.5,
        residualMomSlow: Double = 0.5,
        trendExpectedGrossEdgeBps: Double = 12.0,
        volumeRatio: Double = 0.8,
        depthUsd: Double = 250_000.0,
        spreadBps: Double = 0.5,
        spreadPct: Double = 0.005,
        imbalance: Double = 0.15
    ) = FeatureRow(
        exchange = "hyperliquid",
        symbol = symbol,
        time = Instant.parse("2026-03-23T10:00:00Z"),
        barIndex = barIndex,
        close = 100.0,
        volume = 50_000.0,
        spreadPct = spreadPct,
        spreadBps = spreadBps,
        depthUsd = depthUsd,
        midPrice = 100.0,
        ret1m = 0.001,
        vol30 = 0.004,
        volBps = 40.0,
        btcRet1m = 0.001,
        ethRet1m = 0.001,
        betaBtc = 0.3,
        betaEth = 0.5,
        residualRet = 0.001,
        residualMomFast = residualMomFast,
        residualMomSlow = residualMomSlow,
        residualZ = residualZ,
        imbalance = imbalance,
        volumeRatio = volumeRatio,
        depthRatio = 1.8,
        volRegime = 1.0,
        flowSignal = flowSignal,
        breadth = 0.55,
        rawTrend = rawTrend,
        trendScore = trendScore,
        reversionScore = 0.4,
        trendExpectedGrossEdgeBps = trendExpectedGrossEdgeBps,
        reversionExpectedGrossEdgeBps = 8.0,
        liquid = liquid,
        trendLongRank = trendLongRank,
        trendShortRank = Int.MAX_VALUE,
        reversionLongRank = Int.MAX_VALUE,
        reversionShortRank = Int.MAX_VALUE
    )

    private fun tradeRecord(
        entryTime: Instant,
        exitTime: Instant,
        edgeAfterCostBps: Double,
        expectedNetEdgeBps: Double,
        grossReturnFraction: Double,
        netReturnFraction: Double
    ) = TradeRecord(
        strategyName = "cross_section_beta_trend_v1",
        strategyKind = "trend",
        exchange = "hyperliquid",
        symbol = "SOL",
        side = "BUY",
        entryTime = entryTime,
        exitTime = exitTime,
        entryPrice = 100.0,
        exitPrice = 101.0,
        holdBars = 8,
        grossReturnFraction = grossReturnFraction,
        netReturnFraction = netReturnFraction,
        fillRatio = 0.92,
        feeBps = 2.5,
        feeTier = "retail_mixed_maker_bias",
        feeTierAdjustmentBps = -1.5,
        makerFeeBps = 1.0,
        takerFeeBps = 4.0,
        spreadCostBps = 0.5,
        slippageBps = 1.2,
        impactBps = 0.8,
        adverseSelectionBps = 0.4,
        fundingDriftBps = 0.0,
        basisDriftBps = 0.0,
        totalCostBps = 5.4,
        edgeAfterCostBps = edgeAfterCostBps,
        estimatedFeeUsd = 1.25,
        estimatedCostUsd = 2.7,
        entryTrendScore = 1.2,
        entryResidualZ = 0.2,
        expectedGrossEdgeBps = 16.4,
        expectedRoundTripCostBps = 5.4,
        expectedNetEdgeBps = expectedNetEdgeBps,
        calibrationSamples = 6,
        calibrationWinRate = 0.6,
        calibrationLowerBoundBps = 1.4,
        calibrationScope = "market_all",
        entryImbalance = 0.2,
        entryFlowSignal = 0.3,
        entryVolumeRatio = 1.1,
        entryVolRegime = 1.0,
        betaBtc = 0.3,
        betaEth = 0.5,
        decisionLatencyMs = 12.0,
        submitToAckMs = 80.0,
        submitToFillMs = 120.0,
        p50RoundtripMs = 92.0,
        p95RoundtripMs = 160.0,
        p99RoundtripMs = 240.0,
        jitterMs = 3.0
    )
}
