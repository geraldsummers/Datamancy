package org.datamancy.trading.analytics.crosssectional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.time.Instant

class CrossSectionalResearchTest {
    @Test
    fun `selectResearchCandleSource chooses coarsest supported base interval`() {
        assertEquals(CandleSource("1m", 1), selectResearchCandleSource(1))
        assertEquals(CandleSource("5m", 5), selectResearchCandleSource(10))
        assertEquals(CandleSource("15m", 15), selectResearchCandleSource(30))
        assertEquals(CandleSource("1h", 60), selectResearchCandleSource(60))
        assertEquals(CandleSource("1h", 60), selectResearchCandleSource(240))
    }

    @Test
    fun `scaleRequiredSourceBars preserves minimum wall clock coverage`() {
        assertEquals(360, scaleRequiredSourceBars(minBars = 360, sourceMinutes = 1))
        assertEquals(72, scaleRequiredSourceBars(minBars = 360, sourceMinutes = 5))
        assertEquals(24, scaleRequiredSourceBars(minBars = 360, sourceMinutes = 15))
        assertEquals(6, scaleRequiredSourceBars(minBars = 360, sourceMinutes = 60))
    }

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

    @Test
    fun `search engine ranks trend and reversion leaders independently`() {
        val baseConfig = ResearchConfig(
            marketExchange = "hyperliquid_mainnet",
            barMinutes = 60,
            lookbackHours = 720,
            forwardHours = 72,
            betaLookbackBars = 72,
            trendLookbackBars = 12,
            trendSlowBars = 24,
            reversionLookbackBars = 8,
            trendHoldBars = 4,
            reversionHoldBars = 2,
            topPerSide = 1,
            trendEntryScore = 1.0,
            reversionZEntry = 2.15,
            reversionZExit = 0.45,
            maxSpreadBps = 8.0,
            minDepthMultiple = 10.0,
            minFillRatio = 0.58,
            minVolumeRatio = 0.35,
            maxVolumeRatio = 4.5,
            maxVolRegime = 2.0,
            executionSafetyMarginBps = 8.0,
            minExpectedNetEdgeBps = 4.0,
            trendMinFlowAlignment = 0.08,
            reversionMaxContinuationPressure = 0.18,
            calibrationLookbackHours = 360,
            minCalibrationSamples = 4,
            strongCalibrationSamples = 12,
            minCalibrationLowerBoundBps = 0.5,
            minCalibrationWinRate = 0.52,
            trendCooldownBars = 2,
            reversionCooldownBars = 1,
            persistBacktest = false,
            persistForward = false
        )
        val searchConfig = CrossSectionalSearchConfig(
            baseConfig = baseConfig,
            beamWidth = 2,
            rounds = 3,
            maxEvaluations = 18,
            leaderboardSize = 2,
            minBacktestTrades = 8,
            minForwardTrades = 3,
            barMinutes = listOf(60, 240),
            lookbackHours = listOf(720),
            forwardHours = listOf(72),
            betaLookbackBars = listOf(72),
            trendLookbackBars = listOf(12, 24),
            trendSlowBars = listOf(24, 48),
            reversionLookbackBars = listOf(8),
            trendHoldBars = listOf(4),
            reversionHoldBars = listOf(2),
            topPerSide = listOf(1),
            trendEntryScore = listOf(1.0),
            reversionZEntry = listOf(1.6, 2.15),
            reversionZExit = listOf(0.45),
            maxSpreadBps = listOf(8.0),
            minDepthMultiple = listOf(10.0),
            minFillRatio = listOf(0.58),
            minVolumeRatio = listOf(0.35),
            maxVolumeRatio = listOf(4.5),
            maxVolRegime = listOf(2.0),
            executionSafetyMarginBps = listOf(8.0),
            minExpectedNetEdgeBps = listOf(4.0),
            trendMinFlowAlignment = listOf(0.08),
            reversionMaxContinuationPressure = listOf(0.18),
            calibrationLookbackHours = listOf(360),
            minCalibrationSamples = listOf(4),
            strongCalibrationSamples = listOf(12),
            minCalibrationLowerBoundBps = listOf(0.5),
            minCalibrationWinRate = listOf(0.52),
            trendCooldownBars = listOf(2),
            reversionCooldownBars = listOf(1)
        )

        val result = searchCrossSectionalResearch(searchConfig) { config ->
            fakeSearchResult(config)
        }

        val topTrend = result.topTrendConfigs.first()
        val topReversion = result.topReversionConfigs.first()

        assertEquals(240, topTrend.config.barMinutes)
        assertEquals(24, topTrend.config.trendLookbackBars)
        assertEquals(48, topTrend.config.trendSlowBars)
        assertEquals(1.6, topReversion.config.reversionZEntry)
        assertTrue(topTrend.trendFitness.score > topReversion.trendFitness.score)
        assertTrue(topReversion.reversionFitness.passesFilters)
        assertTrue(result.evaluatedConfigs >= 4)
    }

    @Test
    fun `engineerFeatures backfills conservative execution proxies when orderbooks are missing`() {
        val config = ResearchConfig(
            betaLookbackBars = 1,
            trendLookbackBars = 1,
            trendSlowBars = 1,
            reversionLookbackBars = 1,
            persistBacktest = false,
            persistForward = false
        )
        val t0 = Instant.parse("2026-03-20T00:00:00Z")
        val t1 = Instant.parse("2026-03-20T04:00:00Z")
        val bars = listOf(
            bar(symbol = "BTC", time = t0, close = 100.0, volume = 5_000.0, executionObserved = false),
            bar(symbol = "BTC", time = t1, close = 101.0, volume = 5_200.0, spreadPct = 0.02, depthUnitsPerSide = 1_100.0),
            bar(symbol = "ETH", time = t0, close = 80.0, volume = 4_000.0, executionObserved = false),
            bar(symbol = "ETH", time = t1, close = 81.0, volume = 4_300.0, spreadPct = 0.03, depthUnitsPerSide = 950.0),
            bar(symbol = "SOL", time = t0, close = 20.0, volume = 8_000.0, executionObserved = false),
            bar(symbol = "SOL", time = t1, close = 20.5, volume = 8_400.0, spreadPct = 0.06, depthUnitsPerSide = 3_200.0)
        )

        val proxied = engineerFeatures(bars, config)
            .first { it.symbol == "SOL" && it.time == t0 }

        assertFalse(proxied.executionObserved)
        assertTrue(proxied.spreadBps > 0.0)
        assertTrue(proxied.depthUsd > config.notionalUsd)
    }

    @Test
    fun `buildExecutionEstimate penalizes proxy backed rows`() {
        val observed = featureRow(
            symbol = "SOL",
            liquid = true,
            barIndex = 16,
            trendScore = 1.35,
            trendLongRank = 1,
            trendExpectedGrossEdgeBps = 36.0,
            depthUsd = 420_000.0,
            spreadBps = 0.8,
            spreadPct = 0.008,
            executionObserved = true
        )
        val proxied = observed.copy(executionObserved = false)

        val observedEstimate = buildExecutionEstimate(observed, notionalUsd = 5_000.0, side = 1, kind = StrategyKind.TREND)
        val proxyEstimate = buildExecutionEstimate(proxied, notionalUsd = 5_000.0, side = 1, kind = StrategyKind.TREND)

        assertTrue(proxyEstimate.fillRatio < observedEstimate.fillRatio)
        assertTrue(proxyEstimate.totalCostBps > observedEstimate.totalCostBps)
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
        imbalance: Double = 0.15,
        executionObserved: Boolean = true
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
        reversionShortRank = Int.MAX_VALUE,
        executionObserved = executionObserved
    )

    private fun bar(
        symbol: String,
        time: Instant,
        close: Double,
        volume: Double,
        spreadPct: Double = 0.0,
        depthUnitsPerSide: Double = 0.0,
        executionObserved: Boolean = true
    ) = Bar(
        exchange = "hyperliquid",
        symbol = symbol,
        time = time,
        close = close,
        volume = volume,
        spreadPct = spreadPct,
        bidDepth10 = depthUnitsPerSide,
        askDepth10 = depthUnitsPerSide,
        midPrice = close,
        executionObserved = executionObserved
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

    private fun fakeSearchResult(config: ResearchConfig): CrossSectionalResearchResult {
        val trendBoost =
            (if (config.barMinutes == 240) 2.4 else 0.0) +
                (if (config.trendLookbackBars == 24) 1.8 else 0.0) +
                (if (config.trendSlowBars == 48) 1.2 else 0.0)
        val reversionBoost =
            (if (config.reversionZEntry == 1.6) 2.6 else 0.0) +
                (if (config.reversionLookbackBars == 8) 0.6 else 0.0)
        val trendBacktest = summary(
            config = config,
            strategyName = "cross_section_beta_trend_v1",
            strategyKind = "trend",
            timeframe = "candle_${config.barMinutes}m",
            trades = 14,
            netReturnPct = 3.5 + trendBoost,
            maxDrawdownPct = 3.0,
            sharpe = 1.1 + (trendBoost * 0.2),
            avgEdgeAfterCostBps = 8.0 + (trendBoost * 1.4),
            avgTotalCostBps = 5.4,
            avgFillRatio = 0.78
        )
        val trendForward = summary(
            config = config,
            strategyName = "cross_section_beta_trend_v1",
            strategyKind = "trend",
            timeframe = "forward_${config.barMinutes}m",
            trades = 4,
            netReturnPct = 0.8 + trendBoost,
            maxDrawdownPct = 1.2,
            sharpe = 0.9 + (trendBoost * 0.25),
            avgEdgeAfterCostBps = 6.5 + (trendBoost * 1.2),
            avgTotalCostBps = 5.0,
            avgFillRatio = 0.76
        )
        val reversionBacktest = summary(
            config = config,
            strategyName = "cross_section_beta_reversion_v1",
            strategyKind = "reversion",
            timeframe = "candle_${config.barMinutes}m",
            trades = 12,
            netReturnPct = 2.4 + reversionBoost,
            maxDrawdownPct = 2.6,
            sharpe = 0.8 + (reversionBoost * 0.25),
            avgEdgeAfterCostBps = 5.4 + (reversionBoost * 1.3),
            avgTotalCostBps = 5.1,
            avgFillRatio = 0.74
        )
        val reversionForward = summary(
            config = config,
            strategyName = "cross_section_beta_reversion_v1",
            strategyKind = "reversion",
            timeframe = "forward_${config.barMinutes}m",
            trades = 4,
            netReturnPct = 0.5 + reversionBoost,
            maxDrawdownPct = 1.0,
            sharpe = 0.6 + (reversionBoost * 0.25),
            avgEdgeAfterCostBps = 4.8 + (reversionBoost * 1.1),
            avgTotalCostBps = 4.9,
            avgFillRatio = 0.73
        )
        return CrossSectionalResearchResult(
            config = config,
            exchangeCatalog = listOf(
                ExchangeCatalogSnapshot(
                    apiName = "hyperliquid",
                    implementationStatus = "INTEGRATED",
                    defaultExecutionMode = "forward_paper",
                    supportedExecutionModes = listOf("backtest", "forward_paper"),
                    capabilities = ExchangeCapabilitiesSnapshot(
                        paperOrder = true,
                        liveOrder = true,
                        nativeOrderAdapter = true,
                        marketDataIngress = true,
                        bestQuoteDefault = true
                    ),
                    notes = "test"
                )
            ),
            exchangePlans = listOf(ExchangePlan(exchange = "hyperliquid", marketAliases = listOf(config.marketExchange))),
            discoveredUniverse = mapOf("hyperliquid" to listOf("BTC", "ETH", "SOL")),
            barsLoaded = 240,
            featureRows = 180,
            diagnostics = ResearchDiagnostics(
                barMinutes = config.barMinutes,
                warmupFloorBars = config.trendSlowBars,
                totalRows = 180,
                liquidRows = 96,
                rowsPerSymbol = mapOf("SOL" to 60),
                liquidPerSymbol = mapOf("SOL" to 48),
                liquidFailureCounts = mapOf("warmup" to 8),
                rankEligibleCounts = mapOf("trendLong" to 3),
                seedCounts = mapOf("trend" to 4, "reversion" to 4),
                topTrendSeeds = emptyList(),
                topReversionSeeds = emptyList()
            ),
            heuristicSignals = emptyList(),
            latestSignals = emptyList(),
            backtestSummaries = listOf(trendBacktest, reversionBacktest),
            forwardSummaries = listOf(trendForward, reversionForward),
            forwardCutoff = Instant.parse("2026-03-23T00:00:00Z"),
            calibrationRows = 120,
            forwardRows = 60,
            calibrationExampleCounts = mapOf("trend" to 10, "reversion" to 9)
        )
    }

    private fun summary(
        config: ResearchConfig,
        strategyName: String,
        strategyKind: String,
        timeframe: String,
        trades: Int,
        netReturnPct: Double,
        maxDrawdownPct: Double,
        sharpe: Double,
        avgEdgeAfterCostBps: Double,
        avgTotalCostBps: Double,
        avgFillRatio: Double
    ) = StrategySummary(
        strategyName = strategyName,
        strategyKind = strategyKind,
        exchange = "hyperliquid",
        symbol = "ALL",
        timeframe = timeframe,
        startTime = Instant.parse("2026-03-20T00:00:00Z"),
        endTime = Instant.parse("2026-03-24T00:00:00Z"),
        trades = trades,
        winRate = 0.6,
        netReturnPct = netReturnPct,
        maxDrawdownPct = maxDrawdownPct,
        sharpe = sharpe,
        avgEdgeAfterCostBps = avgEdgeAfterCostBps,
        avgTotalCostBps = avgTotalCostBps,
        avgSlippageBps = 1.1,
        avgFillRatio = avgFillRatio,
        avgSubmitToFillMs = 90.0,
        notes = "test",
        metricsJson = """{"bar_minutes": ${config.barMinutes}}"""
    )
}
