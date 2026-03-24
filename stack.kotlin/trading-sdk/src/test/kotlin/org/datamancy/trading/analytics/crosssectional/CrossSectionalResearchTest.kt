package org.datamancy.trading.analytics.crosssectional

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun `computeStrategyRobustness captures symbol and regime breadth`() {
        val trades = listOf(
            tradeRecord(
                entryTime = Instant.parse("2026-03-20T00:00:00Z"),
                exitTime = Instant.parse("2026-03-20T04:00:00Z"),
                edgeAfterCostBps = 12.0,
                expectedNetEdgeBps = 8.0,
                grossReturnFraction = 0.004,
                netReturnFraction = 0.003
            ).copy(symbol = "SOL", entryVolRegime = 0.8),
            tradeRecord(
                entryTime = Instant.parse("2026-03-20T05:00:00Z"),
                exitTime = Instant.parse("2026-03-20T09:00:00Z"),
                edgeAfterCostBps = 10.0,
                expectedNetEdgeBps = 7.0,
                grossReturnFraction = 0.0035,
                netReturnFraction = 0.0025
            ).copy(symbol = "SOL", entryVolRegime = 0.9),
            tradeRecord(
                entryTime = Instant.parse("2026-03-20T10:00:00Z"),
                exitTime = Instant.parse("2026-03-20T14:00:00Z"),
                edgeAfterCostBps = 8.0,
                expectedNetEdgeBps = 6.0,
                grossReturnFraction = 0.003,
                netReturnFraction = 0.002
            ).copy(symbol = "TAO", entryVolRegime = 1.1),
            tradeRecord(
                entryTime = Instant.parse("2026-03-20T15:00:00Z"),
                exitTime = Instant.parse("2026-03-20T19:00:00Z"),
                edgeAfterCostBps = 7.5,
                expectedNetEdgeBps = 5.5,
                grossReturnFraction = 0.0028,
                netReturnFraction = 0.0018
            ).copy(symbol = "TAO", entryVolRegime = 1.2),
            tradeRecord(
                entryTime = Instant.parse("2026-03-20T20:00:00Z"),
                exitTime = Instant.parse("2026-03-21T00:00:00Z"),
                edgeAfterCostBps = -5.0,
                expectedNetEdgeBps = 2.0,
                grossReturnFraction = -0.003,
                netReturnFraction = -0.004
            ).copy(symbol = "APT", entryVolRegime = 1.8),
            tradeRecord(
                entryTime = Instant.parse("2026-03-21T01:00:00Z"),
                exitTime = Instant.parse("2026-03-21T05:00:00Z"),
                edgeAfterCostBps = -4.2,
                expectedNetEdgeBps = 2.0,
                grossReturnFraction = -0.0025,
                netReturnFraction = -0.0035
            ).copy(symbol = "APT", entryVolRegime = 1.9)
        )

        val robustness = assertNotNull(computeStrategyRobustness(StrategyKind.TREND, trades))

        assertEquals(3, robustness.symbolCount)
        assertEquals(3, robustness.regimeCount)
        assertEquals(listOf("calm", "normal", "stress"), robustness.regimeSlices.map { it.label })
        assertTrue(robustness.largestSymbolTradeShare < 0.4)
        assertEquals((2.0 / 3.0).round(4), robustness.profitableSymbolShare)
        assertEquals((2.0 / 3.0).round(4), robustness.profitableRegimeShare)
        assertTrue(robustness.worstRegimeEdgeAfterCostBps < 0.0)
        assertTrue(robustness.stabilityScore > 0.0)
    }

    @Test
    fun `computeStrategySearchFitness penalizes concentrated symbol dependency`() {
        val searchConfig = CrossSectionalSearchConfig(
            minBacktestTrades = 8,
            minForwardTrades = 3,
            minSearchFillRatio = 0.6,
            maxSearchDrawdownPct = 14.0
        )
        val aggregate = StrategyAggregateSnapshot(
            exchanges = listOf("hyperliquid"),
            trades = 18,
            winRate = 0.62,
            netReturnPct = 6.8,
            maxDrawdownPct = 3.2,
            sharpe = 1.8,
            avgEdgeAfterCostBps = 11.4,
            avgTotalCostBps = 5.6,
            avgFillRatio = 0.79,
            avgSubmitToFillMs = 92.0
        )
        val forward = StrategyAggregateSnapshot(
            exchanges = listOf("hyperliquid"),
            trades = 5,
            winRate = 0.6,
            netReturnPct = 1.8,
            maxDrawdownPct = 1.4,
            sharpe = 1.2,
            avgEdgeAfterCostBps = 8.6,
            avgTotalCostBps = 5.3,
            avgFillRatio = 0.77,
            avgSubmitToFillMs = 88.0
        )

        val concentratedFitness = computeStrategySearchFitness(
            searchConfig = searchConfig,
            kind = StrategyKind.TREND,
            backtest = aggregate,
            forward = forward,
            backtestRobustness = robustnessSnapshot(
                largestSymbolTradeShare = 0.92,
                largestRegimeTradeShare = 0.58,
                profitableSymbolShare = 0.67,
                profitableRegimeShare = 0.67,
                stabilityScore = 41.0
            ),
            forwardRobustness = robustnessSnapshot(
                largestSymbolTradeShare = 0.9,
                largestRegimeTradeShare = 0.6,
                profitableSymbolShare = 0.67,
                profitableRegimeShare = 0.67,
                stabilityScore = 39.0
            )
        )
        val diversifiedFitness = computeStrategySearchFitness(
            searchConfig = searchConfig,
            kind = StrategyKind.TREND,
            backtest = aggregate,
            forward = forward,
            backtestRobustness = robustnessSnapshot(
                largestSymbolTradeShare = 0.42,
                largestRegimeTradeShare = 0.48,
                profitableSymbolShare = 0.67,
                profitableRegimeShare = 0.67,
                stabilityScore = 77.0
            ),
            forwardRobustness = robustnessSnapshot(
                largestSymbolTradeShare = 0.45,
                largestRegimeTradeShare = 0.5,
                profitableSymbolShare = 0.67,
                profitableRegimeShare = 0.67,
                stabilityScore = 74.0
            )
        )

        assertFalse(concentratedFitness.passesFilters)
        assertTrue(concentratedFitness.rejectionReasons.any { it.startsWith("symbol_concentration") })
        assertTrue(diversifiedFitness.passesFilters)
        assertTrue(diversifiedFitness.score > concentratedFitness.score)
        assertTrue(diversifiedFitness.robustnessScore > concentratedFitness.robustnessScore)
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
            trendSlowBars = 48,
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
            maxSymbols = listOf(baseConfig.maxSymbols),
            discoveryMaxSymbols = listOf(baseConfig.discoveryMaxSymbols),
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
            reversionCooldownBars = listOf(1),
            maxConcurrentPositions = listOf(baseConfig.maxConcurrentPositions),
            maxConcurrentLongs = listOf(baseConfig.maxConcurrentLongs),
            maxConcurrentShorts = listOf(baseConfig.maxConcurrentShorts),
            maxNetExposureFraction = listOf(baseConfig.maxNetExposureFraction),
            maxPortfolioBetaBtcAbs = listOf(baseConfig.maxPortfolioBetaBtcAbs),
            maxPortfolioBetaEthAbs = listOf(baseConfig.maxPortfolioBetaEthAbs)
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
        assertTrue(topReversion.reversionFitness.passesFilters)
        assertTrue(result.evaluatedConfigs >= 4)
    }

    @Test
    fun `search engine covers reversion mutations under tight budgets`() {
        val baseConfig = ResearchConfig(
            marketExchange = "hyperliquid_mainnet",
            barMinutes = 60,
            lookbackHours = 720,
            forwardHours = 72,
            betaLookbackBars = 72,
            trendLookbackBars = 12,
            trendSlowBars = 48,
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
            beamWidth = 1,
            rounds = 1,
            maxEvaluations = 4,
            leaderboardSize = 1,
            minBacktestTrades = 8,
            minForwardTrades = 3,
            barMinutes = listOf(60, 240),
            lookbackHours = listOf(720, 1080),
            forwardHours = listOf(72),
            betaLookbackBars = listOf(72),
            trendLookbackBars = listOf(12),
            trendSlowBars = listOf(48),
            reversionLookbackBars = listOf(8),
            trendHoldBars = listOf(4),
            reversionHoldBars = listOf(2),
            topPerSide = listOf(1),
            trendEntryScore = listOf(1.0, 1.4),
            reversionZEntry = listOf(1.6, 2.15),
            reversionZExit = listOf(0.45),
            maxSpreadBps = listOf(8.0, 11.0),
            minDepthMultiple = listOf(10.0),
            minFillRatio = listOf(0.58),
            minVolumeRatio = listOf(0.35),
            maxVolumeRatio = listOf(4.5),
            maxVolRegime = listOf(2.0),
            executionSafetyMarginBps = listOf(8.0),
            minExpectedNetEdgeBps = listOf(4.0),
            trendMinFlowAlignment = listOf(0.08),
            reversionMaxContinuationPressure = listOf(0.18),
            calibrationLookbackHours = listOf(360, 720),
            minCalibrationSamples = listOf(4),
            strongCalibrationSamples = listOf(12),
            minCalibrationLowerBoundBps = listOf(0.5),
            minCalibrationWinRate = listOf(0.52),
            trendCooldownBars = listOf(2),
            reversionCooldownBars = listOf(1)
        )

        val result = searchCrossSectionalResearch(searchConfig) { config ->
            fakeCoverageSearchResult(config)
        }

        val topReversion = result.topReversionConfigs.first()

        assertEquals(4, result.evaluatedConfigs)
        assertEquals(1.6, topReversion.config.reversionZEntry)
        assertTrue(topReversion.reversionFitness.passesFilters)
    }

    @Test
    fun `search engine budgets evaluations across rounds to reach multi step winners`() {
        val baseConfig = ResearchConfig(
            marketExchange = "hyperliquid_mainnet",
            barMinutes = 60,
            lookbackHours = 720,
            forwardHours = 72,
            betaLookbackBars = 72,
            trendLookbackBars = 12,
            trendSlowBars = 48,
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
            beamWidth = 1,
            rounds = 2,
            maxEvaluations = 3,
            leaderboardSize = 1,
            minBacktestTrades = 8,
            minForwardTrades = 3,
            barMinutes = listOf(60, 240),
            lookbackHours = listOf(720),
            forwardHours = listOf(72),
            betaLookbackBars = listOf(72),
            trendLookbackBars = listOf(12, 24),
            trendSlowBars = listOf(48),
            reversionLookbackBars = listOf(8),
            trendHoldBars = listOf(4),
            reversionHoldBars = listOf(2),
            topPerSide = listOf(1),
            trendEntryScore = listOf(1.0),
            reversionZEntry = listOf(2.15),
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
            fakeTwoStepSearchResult(config)
        }

        val topTrend = result.topTrendConfigs.first()

        assertEquals(240, topTrend.config.barMinutes)
        assertEquals(3, result.evaluatedConfigs)
        assertEquals(2, result.roundsCompleted)
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

    @Test
    fun `selectResearchUniverseFromBars prioritizes recent tradable coverage`() {
        val config = ResearchConfig(
            barMinutes = 240,
            forwardHours = 72,
            maxSymbols = 2,
            minDepthMultiple = 8.0,
            maxSpreadBps = 4.0,
            persistBacktest = false,
            persistForward = false
        )
        val start = Instant.parse("2026-03-20T00:00:00Z")
        val bars = buildList {
            listOf("BTC", "ETH").forEach { benchmark ->
                repeat(6) { index ->
                    add(
                        bar(
                            symbol = benchmark,
                            time = start.plusSeconds(index * 14_400L),
                            close = if (benchmark == "BTC") 100_000.0 else 4_000.0,
                            volume = 10_000.0,
                            spreadPct = 0.01,
                            depthUnitsPerSide = 500.0,
                            executionObserved = true
                        )
                    )
                }
            }
            repeat(6) { index ->
                add(
                    bar(
                        symbol = "SOL",
                        time = start.plusSeconds(index * 14_400L),
                        close = 100.0,
                        volume = 8_000.0,
                        spreadPct = 0.01,
                        depthUnitsPerSide = 600.0,
                        executionObserved = true
                    )
                )
                add(
                    bar(
                        symbol = "TAO",
                        time = start.plusSeconds(index * 14_400L),
                        close = 400.0,
                        volume = 3_500.0,
                        spreadPct = 0.015,
                        depthUnitsPerSide = 220.0,
                        executionObserved = true
                    )
                )
                add(
                    bar(
                        symbol = "APT",
                        time = start.plusSeconds(index * 14_400L),
                        close = 10.0,
                        volume = 30_000.0,
                        spreadPct = if (index < 2) 0.01 else 0.08,
                        depthUnitsPerSide = if (index < 2) 900.0 else 8.0,
                        executionObserved = index < 2
                    )
                )
            }
        }

        val selected = selectResearchUniverseFromBars(bars, config)
        val symbols = selected.getValue("hyperliquid")

        assertTrue("SOL" in symbols)
        assertTrue("TAO" in symbols)
        assertFalse("APT" in symbols)
    }

    @Test
    fun `discovery candidate limit keeps full-universe scan when discovery override is unset`() {
        assertEquals(0, discoveryCandidateLimit(maxSymbols = 12, discoveryMaxSymbols = 0))
        assertEquals(48, discoveryCandidateLimit(maxSymbols = 12, discoveryMaxSymbols = 48))
    }

    @Test
    fun `buildUniverseProfiles captures candidate versus selected breadth`() {
        val config = ResearchConfig(
            barMinutes = 240,
            forwardHours = 72,
            maxSymbols = 2,
            minDepthMultiple = 8.0,
            maxSpreadBps = 4.0,
            persistBacktest = false,
            persistForward = false
        )
        val start = Instant.parse("2026-03-20T00:00:00Z")
        val bars = buildList {
            listOf("BTC", "ETH", "SOL", "TAO").forEachIndexed { offset, symbol ->
                repeat(6) { index ->
                    add(
                        bar(
                            symbol = symbol,
                            time = start.plusSeconds(index * 14_400L),
                            close = 100.0 + offset,
                            volume = 6_000.0 + (offset * 1_000.0),
                            spreadPct = 0.01 + (offset * 0.002),
                            depthUnitsPerSide = 450.0 + (offset * 40.0),
                            executionObserved = true
                        )
                    )
                }
            }
        }

        val profiles = buildUniverseProfiles(
            candidates = rankResearchUniverseCandidates(bars, config),
            selectedUniverse = mapOf("hyperliquid" to listOf("BTC", "ETH", "SOL")),
            config = config
        )
        val profile = profiles.single()

        assertEquals(4, profile.candidateSymbols)
        assertEquals(3, profile.selectedSymbols)
        assertEquals(listOf("BTC", "ETH", "SOL"), profile.selectedUniverse)
        assertTrue(profile.topCandidates.isNotEmpty())
        assertTrue(profile.liquidityBuckets.isNotEmpty())
    }

    @Test
    fun `simulateStrategy respects portfolio capacity and ranks strongest candidate first`() {
        val config = ResearchConfig(
            betaLookbackBars = 4,
            trendLookbackBars = 2,
            trendSlowBars = 4,
            reversionLookbackBars = 2,
            trendHoldBars = 1,
            topPerSide = 2,
            maxConcurrentPositions = 1,
            maxConcurrentLongs = 1,
            maxConcurrentShorts = 1,
            maxNetExposureFraction = 1.0,
            maxPortfolioBetaBtcAbs = 1.0,
            maxPortfolioBetaEthAbs = 1.0,
            persistBacktest = false,
            persistForward = false
        )
        val t0 = Instant.parse("2026-03-23T10:00:00Z")
        val t1 = t0.plusSeconds(3_600L)
        val rows = listOf(
            featureRow(symbol = "SOL", liquid = true, barIndex = 10, trendScore = 1.5, trendLongRank = 1, trendExpectedGrossEdgeBps = 42.0)
                .copy(time = t0, close = 100.0),
            featureRow(symbol = "TAO", liquid = true, barIndex = 10, trendScore = 1.45, trendLongRank = 2, trendExpectedGrossEdgeBps = 34.0)
                .copy(time = t0, close = 120.0),
            featureRow(symbol = "SOL", liquid = true, barIndex = 11, trendScore = 0.0, trendLongRank = Int.MAX_VALUE, trendExpectedGrossEdgeBps = 0.0)
                .copy(time = t1, close = 101.0),
            featureRow(symbol = "TAO", liquid = true, barIndex = 11, trendScore = 0.0, trendLongRank = Int.MAX_VALUE, trendExpectedGrossEdgeBps = 0.0)
                .copy(time = t1, close = 121.0)
        )

        val trades = simulateStrategy(
            strategyName = "cross_section_beta_trend_v1",
            kind = StrategyKind.TREND,
            rows = rows,
            config = config
        )

        assertEquals(1, trades.size)
        assertEquals("SOL", trades.single().symbol)
    }

    @Test
    fun `simulateStrategy rejects candidates that breach portfolio beta budget`() {
        val config = ResearchConfig(
            betaLookbackBars = 4,
            trendLookbackBars = 2,
            trendSlowBars = 4,
            reversionLookbackBars = 2,
            trendHoldBars = 1,
            topPerSide = 1,
            maxConcurrentPositions = 2,
            maxConcurrentLongs = 2,
            maxConcurrentShorts = 2,
            maxNetExposureFraction = 1.0,
            maxPortfolioBetaBtcAbs = 0.05,
            maxPortfolioBetaEthAbs = 1.0,
            persistBacktest = false,
            persistForward = false
        )
        val t0 = Instant.parse("2026-03-23T10:00:00Z")
        val t1 = t0.plusSeconds(3_600L)
        val rows = listOf(
            featureRow(
                symbol = "SOL",
                liquid = true,
                barIndex = 10,
                trendScore = 1.5,
                trendLongRank = 1,
                trendExpectedGrossEdgeBps = 42.0,
                betaBtc = 0.6
            ).copy(time = t0, close = 100.0),
            featureRow(
                symbol = "SOL",
                liquid = true,
                barIndex = 11,
                trendScore = 0.0,
                trendLongRank = Int.MAX_VALUE,
                trendExpectedGrossEdgeBps = 0.0,
                betaBtc = 0.6
            ).copy(time = t1, close = 101.0)
        )

        val trades = simulateStrategy(
            strategyName = "cross_section_beta_trend_v1",
            kind = StrategyKind.TREND,
            rows = rows,
            config = config
        )

        assertTrue(trades.isEmpty())
    }

    @Test
    fun `buildEntryCandidate caps calibrated edge at structural edge and shrinks to conservative calibration`() {
        val config = ResearchConfig(
            betaLookbackBars = 8,
            trendLookbackBars = 4,
            trendSlowBars = 8,
            reversionLookbackBars = 4,
            trendEntryScore = 1.0,
            reversionZEntry = 2.2,
            minCalibrationSamples = 4,
            strongCalibrationSamples = 12,
            minCalibrationLowerBoundBps = 0.5,
            minExpectedNetEdgeBps = 4.0,
            persistBacktest = false,
            persistForward = false
        )
        val row = featureRow(
            symbol = "SOL",
            liquid = true,
            barIndex = 16,
            trendScore = 1.5,
            trendLongRank = 1,
            residualZ = 0.4,
            flowSignal = 0.6,
            rawTrend = 1.2,
            residualMomFast = 0.7,
            residualMomSlow = 0.9,
            trendExpectedGrossEdgeBps = 42.0,
            volumeRatio = 1.2,
            depthUsd = 420_000.0,
            spreadBps = 0.8,
            spreadPct = 0.008,
            imbalance = 0.3,
            executionObserved = true
        )

        val structural = assertNotNull(
            buildStructuralCandidate(StrategyKind.TREND, row, 1, config)
        )
        val examples = List(6) { index ->
            CalibrationExample(
                key = calibrationBaseKey(StrategyKind.TREND, row, 1, config),
                entryTime = row.time.plusSeconds(index.toLong() * 60L),
                availableAt = row.time.plusSeconds((index.toLong() + 1L) * 60L),
                grossEdgeBps = 180.0,
                netEdgeBps = 120.0,
                totalCostBps = 20.0,
                fillRatio = 0.82
            )
        }
        val state = buildCalibrationState(examples)

        val candidate = assertNotNull(
            buildEntryCandidate(StrategyKind.TREND, row, 1, config, state)
        )

        assertTrue(candidate.expectedNetEdgeBps <= structural.expectedNetEdgeBps.round(4) + 1e-6)
        assertTrue(candidate.expectedNetEdgeBps < 120.0)
        assertEquals(
            candidate.expectedNetEdgeBps.round(4),
            (candidate.expectedGrossEdgeBps - candidate.expectedRoundTripCostBps).round(4)
        )
    }

    private fun featureRow(
        symbol: String,
        liquid: Boolean,
        barIndex: Int,
        time: Instant = Instant.parse("2026-03-23T10:00:00Z"),
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
        betaBtc: Double = 0.3,
        betaEth: Double = 0.5,
        executionObserved: Boolean = true
    ) = FeatureRow(
        exchange = "hyperliquid",
        symbol = symbol,
        time = time,
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
        betaBtc = betaBtc,
        betaEth = betaEth,
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
            candidateUniverse = mapOf("hyperliquid" to listOf("BTC", "ETH", "SOL", "TAO")),
            discoveredUniverse = mapOf("hyperliquid" to listOf("BTC", "ETH", "SOL")),
            universeProfiles = listOf(universeProfile()),
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
            calibrationExampleCounts = mapOf("trend" to 10, "reversion" to 9),
            backtestPortfolioProfiles = mapOf(
                "trend" to portfolioProfile("trend", "backtest"),
                "reversion" to portfolioProfile("reversion", "backtest")
            ),
            forwardPortfolioProfiles = mapOf(
                "trend" to portfolioProfile("trend", "forward"),
                "reversion" to portfolioProfile("reversion", "forward")
            )
        )
    }

    private fun fakeCoverageSearchResult(config: ResearchConfig): CrossSectionalResearchResult {
        val trendBoost =
            (if (config.barMinutes == 240) 0.8 else 0.0) +
                (if (config.trendEntryScore == 1.4) 0.6 else 0.0)
        val reversionBoost = if (config.reversionZEntry == 1.6) 4.5 else 0.0

        val trendBacktest = summary(
            config = config,
            strategyName = "cross_section_beta_trend_v1",
            strategyKind = "trend",
            timeframe = "candle_${config.barMinutes}m",
            trades = 9,
            netReturnPct = 1.8 + trendBoost,
            maxDrawdownPct = 2.2,
            sharpe = 0.9 + (trendBoost * 0.2),
            avgEdgeAfterCostBps = 4.2 + (trendBoost * 1.4),
            avgTotalCostBps = 5.2,
            avgFillRatio = 0.73
        )
        val trendForward = summary(
            config = config,
            strategyName = "cross_section_beta_trend_v1",
            strategyKind = "trend",
            timeframe = "forward_${config.barMinutes}m",
            trades = 3,
            netReturnPct = 0.4 + (trendBoost * 0.5),
            maxDrawdownPct = 0.8,
            sharpe = 0.5 + (trendBoost * 0.2),
            avgEdgeAfterCostBps = 3.2 + (trendBoost * 1.0),
            avgTotalCostBps = 4.9,
            avgFillRatio = 0.71
        )
        val reversionBacktest = summary(
            config = config,
            strategyName = "cross_section_beta_reversion_v1",
            strategyKind = "reversion",
            timeframe = "candle_${config.barMinutes}m",
            trades = 11,
            netReturnPct = 1.9 + reversionBoost,
            maxDrawdownPct = 2.0,
            sharpe = 0.8 + reversionBoost,
            avgEdgeAfterCostBps = 4.1 + (reversionBoost * 2.0),
            avgTotalCostBps = 5.0,
            avgFillRatio = 0.75
        )
        val reversionForward = summary(
            config = config,
            strategyName = "cross_section_beta_reversion_v1",
            strategyKind = "reversion",
            timeframe = "forward_${config.barMinutes}m",
            trades = 4,
            netReturnPct = 0.6 + reversionBoost,
            maxDrawdownPct = 0.9,
            sharpe = 0.6 + reversionBoost,
            avgEdgeAfterCostBps = 3.8 + (reversionBoost * 1.7),
            avgTotalCostBps = 4.8,
            avgFillRatio = 0.74
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
            candidateUniverse = mapOf("hyperliquid" to listOf("BTC", "ETH", "SOL", "TAO")),
            discoveredUniverse = mapOf("hyperliquid" to listOf("BTC", "ETH", "SOL")),
            universeProfiles = listOf(universeProfile()),
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
            calibrationExampleCounts = mapOf("trend" to 10, "reversion" to 9),
            backtestPortfolioProfiles = mapOf(
                "trend" to portfolioProfile("trend", "backtest"),
                "reversion" to portfolioProfile("reversion", "backtest")
            ),
            forwardPortfolioProfiles = mapOf(
                "trend" to portfolioProfile("trend", "forward"),
                "reversion" to portfolioProfile("reversion", "forward")
            )
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

    private fun robustnessSnapshot(
        strategyKind: String = "trend",
        totalTrades: Int = 18,
        symbolCount: Int = 3,
        regimeCount: Int = 3,
        effectiveSymbolCount: Double = 2.6,
        effectiveRegimeCount: Double = 2.4,
        largestSymbolTradeShare: Double = 0.42,
        largestRegimeTradeShare: Double = 0.5,
        profitableSymbolShare: Double = 0.67,
        profitableRegimeShare: Double = 0.67,
        worstSymbolNetReturnPct: Double = -0.8,
        worstSymbolEdgeAfterCostBps: Double = -1.2,
        worstRegimeNetReturnPct: Double = -0.5,
        worstRegimeEdgeAfterCostBps: Double = -0.9,
        stabilityScore: Double = 72.0
    ) = StrategyRobustnessSnapshot(
        strategyKind = strategyKind,
        totalTrades = totalTrades,
        symbolCount = symbolCount,
        regimeCount = regimeCount,
        effectiveSymbolCount = effectiveSymbolCount,
        effectiveRegimeCount = effectiveRegimeCount,
        largestSymbolTradeShare = largestSymbolTradeShare,
        largestRegimeTradeShare = largestRegimeTradeShare,
        profitableSymbolShare = profitableSymbolShare,
        profitableRegimeShare = profitableRegimeShare,
        worstSymbolNetReturnPct = worstSymbolNetReturnPct,
        worstSymbolEdgeAfterCostBps = worstSymbolEdgeAfterCostBps,
        worstRegimeNetReturnPct = worstRegimeNetReturnPct,
        worstRegimeEdgeAfterCostBps = worstRegimeEdgeAfterCostBps,
        stabilityScore = stabilityScore,
        symbolSlices = emptyList(),
        regimeSlices = emptyList()
    )

    private fun fakeTwoStepSearchResult(config: ResearchConfig): CrossSectionalResearchResult {
        val comboBoost = if (config.barMinutes == 240 && config.trendLookbackBars == 24) 6.0 else 0.0
        val oneStepBoost = if (config.barMinutes == 240) 1.5 else 0.0
        val trendBoost = comboBoost + oneStepBoost
        val trendBacktest = summary(
            config = config,
            strategyName = "cross_section_beta_trend_v1",
            strategyKind = "trend",
            timeframe = "candle_${config.barMinutes}m",
            trades = 12,
            netReturnPct = 2.0 + trendBoost,
            maxDrawdownPct = 2.0,
            sharpe = 0.8 + trendBoost,
            avgEdgeAfterCostBps = 6.0 + (trendBoost * 4.0),
            avgTotalCostBps = 5.2,
            avgFillRatio = 0.78
        )
        val trendForward = summary(
            config = config,
            strategyName = "cross_section_beta_trend_v1",
            strategyKind = "trend",
            timeframe = "forward_${config.barMinutes}m",
            trades = 4,
            netReturnPct = 0.8 + trendBoost,
            maxDrawdownPct = 1.0,
            sharpe = 0.7 + trendBoost,
            avgEdgeAfterCostBps = 5.0 + (trendBoost * 4.0),
            avgTotalCostBps = 5.0,
            avgFillRatio = 0.76
        )
        val reversionBacktest = summary(
            config = config,
            strategyName = "cross_section_beta_reversion_v1",
            strategyKind = "reversion",
            timeframe = "candle_${config.barMinutes}m",
            trades = 8,
            netReturnPct = 0.4,
            maxDrawdownPct = 1.5,
            sharpe = 0.2,
            avgEdgeAfterCostBps = 1.5,
            avgTotalCostBps = 5.3,
            avgFillRatio = 0.72
        )
        val reversionForward = summary(
            config = config,
            strategyName = "cross_section_beta_reversion_v1",
            strategyKind = "reversion",
            timeframe = "forward_${config.barMinutes}m",
            trades = 3,
            netReturnPct = 0.2,
            maxDrawdownPct = 1.0,
            sharpe = 0.1,
            avgEdgeAfterCostBps = 1.0,
            avgTotalCostBps = 5.1,
            avgFillRatio = 0.71
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
            candidateUniverse = mapOf("hyperliquid" to listOf("BTC", "ETH", "SOL", "TAO")),
            discoveredUniverse = mapOf("hyperliquid" to listOf("BTC", "ETH", "SOL")),
            universeProfiles = listOf(universeProfile()),
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
            calibrationExampleCounts = mapOf("trend" to 10, "reversion" to 9),
            backtestPortfolioProfiles = mapOf(
                "trend" to portfolioProfile("trend", "backtest"),
                "reversion" to portfolioProfile("reversion", "backtest")
            ),
            forwardPortfolioProfiles = mapOf(
                "trend" to portfolioProfile("trend", "forward"),
                "reversion" to portfolioProfile("reversion", "forward")
            )
        )
    }

    private fun universeProfile() = UniverseProfileSnapshot(
        exchange = "hyperliquid",
        candidateSymbols = 4,
        selectedSymbols = 3,
        benchmarkSymbols = 2,
        candidateAvgRecentTradableRatio = 0.71,
        selectedAvgRecentTradableRatio = 0.82,
        candidateAvgRecentObservedRatio = 0.86,
        selectedAvgRecentObservedRatio = 0.92,
        candidateAvgRecentSpreadBps = 1.6,
        selectedAvgRecentSpreadBps = 1.2,
        candidateMedianRecentSpreadBps = 1.4,
        selectedMedianRecentSpreadBps = 1.1,
        candidateAvgRecentDepthUsd = 420_000.0,
        selectedAvgRecentDepthUsd = 520_000.0,
        candidateAvgRecentVolumeUsd = 1_800_000.0,
        selectedAvgRecentVolumeUsd = 2_100_000.0,
        candidateObservedExecutionShare = 0.84,
        selectedObservedExecutionShare = 0.91,
        candidateTradableExecutionShare = 0.68,
        selectedTradableExecutionShare = 0.8,
        liquidityBuckets = listOf(
            UniverseLiquidityBucketSnapshot("deep", 2, 1.0, 700_000.0, 2_400_000.0, 0.92),
            UniverseLiquidityBucketSnapshot("core", 1, 1.5, 420_000.0, 1_800_000.0, 0.76),
            UniverseLiquidityBucketSnapshot("fragile", 1, 2.9, 160_000.0, 800_000.0, 0.42)
        ),
        selectedUniverse = listOf("BTC", "ETH", "SOL"),
        topCandidates = listOf("SOL", "TAO")
    )

    private fun portfolioProfile(kind: String, stage: String) = PortfolioProfileSnapshot(
        strategyKind = kind,
        stage = stage,
        exchanges = listOf("hyperliquid"),
        trades = 12,
        policyMaxConcurrentPositions = 4,
        policyMaxConcurrentLongs = 2,
        policyMaxConcurrentShorts = 2,
        policyMaxNetExposureFraction = 0.4,
        policyMaxAbsBetaBtc = 0.35,
        policyMaxAbsBetaEth = 0.4,
        maxConcurrentPositions = 3,
        maxConcurrentLongs = 2,
        maxConcurrentShorts = 1,
        avgConcurrentPositions = 1.8,
        avgConcurrentLongs = 1.1,
        avgConcurrentShorts = 0.7,
        maxGrossExposureUsd = 15_000.0,
        avgGrossExposureUsd = 9_000.0,
        maxNetExposureUsd = 5_000.0,
        avgNetExposureUsd = 2_200.0,
        maxAbsNetExposureFraction = 0.33,
        avgAbsNetExposureFraction = 0.18,
        maxAbsBetaBtc = 0.21,
        avgAbsBetaBtc = 0.12,
        maxAbsBetaEth = 0.24,
        avgAbsBetaEth = 0.14,
        avgCapacityUtilization = 0.45,
        maxCapacityUtilization = 0.75,
        entryConstraints = PortfolioConstraintSnapshot(
            candidateEntries = 16,
            acceptedEntries = 12,
            rejectedOpenSymbol = 1,
            rejectedGrossLimit = 1,
            rejectedLongLimit = 1,
            rejectedShortLimit = 0,
            rejectedNetLimit = 1,
            rejectedBetaLimit = 0
        )
    )
}
