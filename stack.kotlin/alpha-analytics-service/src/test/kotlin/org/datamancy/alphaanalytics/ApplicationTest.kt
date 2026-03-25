package org.datamancy.alphaanalytics

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.datamancy.trading.analytics.crosssectional.CrossSectionalSearchCandidate
import org.datamancy.trading.analytics.crosssectional.CrossSectionalSearchConfig
import org.datamancy.trading.analytics.crosssectional.CrossSectionalSearchResult
import org.datamancy.trading.analytics.crosssectional.CrossSectionalExchangeReadiness
import org.datamancy.trading.analytics.crosssectional.CrossSectionalResearchReadiness
import org.datamancy.trading.analytics.crosssectional.CrossSectionalResearchResult
import org.datamancy.trading.analytics.crosssectional.ExchangeCatalogSnapshot
import org.datamancy.trading.analytics.crosssectional.ExchangeCapabilitiesSnapshot
import org.datamancy.trading.analytics.crosssectional.ExchangePlan
import org.datamancy.trading.analytics.crosssectional.PortfolioConstraintSnapshot
import org.datamancy.trading.analytics.crosssectional.PortfolioProfileSnapshot
import org.datamancy.trading.analytics.crosssectional.ResearchCoverageSnapshot
import org.datamancy.trading.analytics.crosssectional.ResearchConfig
import org.datamancy.trading.analytics.crosssectional.ResearchDataKey
import org.datamancy.trading.analytics.crosssectional.ResearchDiagnostics
import org.datamancy.trading.analytics.crosssectional.StrategyAggregateSnapshot
import org.datamancy.trading.analytics.crosssectional.StrategySearchFitness
import org.datamancy.trading.analytics.crosssectional.UniverseLiquidityBucketSnapshot
import org.datamancy.trading.analytics.crosssectional.UniverseProfileSnapshot
import org.datamancy.trading.analytics.crosssectional.UniverseSnapshotCacheEntryStatus
import org.datamancy.trading.analytics.crosssectional.UniverseSnapshotCacheStatus
import org.datamancy.trading.policy.DatamancyTradingPolicy
import java.time.Instant

class ApplicationTest {
    @Test
    fun `health endpoint responds healthy`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                runAnalysis = { fakeResult(it) },
                runSearch = { fakeSearchResult(it) }
            )
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("healthy"))
    }

    @Test
    fun `default config endpoint exposes service defaults`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                runAnalysis = { fakeResult(it) },
                runSearch = { fakeSearchResult(it) }
            )
        }

        val response = client.get("/api/v1/alpha/cross-sectional/default-config")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"barMinutes\": 30"), body)
        assertTrue(body.contains("\"lookbackHours\": 48"), body)
        assertTrue(body.contains("hyperliquid_mainnet"), body)
    }

    @Test
    fun `trading policy endpoint exposes authoritative runtime policy`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                runAnalysis = { fakeResult(it) },
                runSearch = { fakeSearchResult(it) },
                tradingPolicy = { DatamancyTradingPolicy.default() }
            )
        }

        val response = client.get("/api/v1/policy/trading")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"canonicalFeatureTable\": \"research_features_1m\""), body)
        assertTrue(body.contains("\"exchangeId\": \"hyperliquid_mainnet\""), body)
        assertTrue(body.contains("\"allowRawFallback\": false"), body)
    }

    @Test
    fun `data health summary endpoint exposes policy-backed health rollup`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                runAnalysis = { fakeResult(it) },
                runSearch = { fakeSearchResult(it) },
                loadDataHealthSummary = { exchange, barMinutes ->
                    assertEquals("hyperliquid_mainnet", exchange)
                    assertEquals(1, barMinutes)
                    fakeDataHealthSummary()
                }
            )
        }

        val response = client.get("/api/v1/data-health/summary?exchange=hyperliquid_mainnet&barMinutes=1")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"activeSymbols\": 182"), body)
        assertTrue(body.contains("\"criticalSymbols\": 7"), body)
        assertTrue(body.contains("\"requiredRawChannels\""), body)
    }

    @Test
    fun `data health issues endpoint exposes symbol failures`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                runAnalysis = { fakeResult(it) },
                runSearch = { fakeSearchResult(it) },
                loadDataHealthIssues = { exchange, barMinutes, limit, includeInactive, includeHealthy ->
                    assertEquals("hyperliquid_mainnet", exchange)
                    assertEquals(1, barMinutes)
                    assertEquals(20, limit)
                    assertEquals(false, includeInactive)
                    assertEquals(false, includeHealthy)
                    fakeDataHealthIssues()
                }
            )
        }

        val response = client.get("/api/v1/data-health/issues?exchange=hyperliquid_mainnet&barMinutes=1&limit=20")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"symbol\": \"KAS\""), body)
        assertTrue(body.contains("\"status\": \"CRITICAL\""), body)
        assertTrue(body.contains("missing required raw channel candle_1m"), body)
    }

    @Test
    fun `readiness endpoint exposes engine backed research verdict`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                runAnalysis = { fakeResult(it) },
                runSearch = { fakeSearchResult(it) },
                evaluateReadiness = { config ->
                    assertEquals("hyperliquid_mainnet", config.marketExchange)
                    fakeReadiness(config)
                }
            )
        }

        val response = client.post("/api/v1/alpha/cross-sectional/readiness") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "marketExchange": "hyperliquid_mainnet",
                  "persistBacktest": false,
                  "persistForward": false
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"passed\": true"), body)
        assertTrue(body.contains("\"eligibleSymbols\": 17"), body)
        assertTrue(body.contains("\"requiredBars\": 96"), body)
    }

    @Test
    fun `run endpoint decodes partial config and returns analysis payload`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                runAnalysis = { config -> fakeResult(config) },
                runSearch = { fakeSearchResult(it) }
            )
        }

        val response = client.post("/api/v1/alpha/cross-sectional/run") {
            contentType(ContentType.Application.Json)
            setBody("""
                {
                  "barMinutes": 30,
                  "marketExchange": "hyperliquid_merged",
                  "lookbackHours": 144,
                  "forwardHours": 36,
                  "persistBacktest": false,
                  "persistForward": false
                }
            """.trimIndent())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"barMinutes\": 30"), body)
        assertTrue(body.contains("hyperliquid_merged"), body)
        assertTrue(body.contains("\"barsLoaded\": 42"), body)
    }

    @Test
    fun `search default config endpoint exposes breathing search defaults`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                runAnalysis = { fakeResult(it) },
                runSearch = { fakeSearchResult(it) }
            )
        }

        val response = client.get("/api/v1/alpha/cross-sectional/search/default-config")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"beamWidth\": 6"), body)
        assertTrue(body.contains("\"rounds\": 4"), body)
        assertTrue(body.contains("\"enablePaperOrders\": false"), body)
    }

    @Test
    fun `search run endpoint decodes partial config and returns leaderboards`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                runAnalysis = { fakeResult(it) },
                runSearch = { fakeSearchResult(it) }
            )
        }

        val response = client.post("/api/v1/alpha/cross-sectional/search/run") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "beamWidth": 3,
                  "rounds": 2,
                  "baseConfig": {
                    "barMinutes": 240,
                    "lookbackHours": 720,
                    "forwardHours": 72,
                    "persistBacktest": true,
                    "persistForward": true,
                    "enablePaperOrders": true
                  }
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"evaluatedConfigs\": 5"), body)
        assertTrue(body.contains("\"barMinutes\": 240"), body)
        assertTrue(body.contains("\"topTrendConfigs\""), body)
        assertTrue(body.contains("\"passesFilters\": true"), body)
        assertTrue(body.contains("\"enablePaperOrders\": false"), body)
    }

    @Test
    fun `cache status endpoint exposes ram layer summary`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                runAnalysis = { fakeResult(it) },
                runSearch = { fakeSearchResult(it) },
                cacheStatus = { fakeCacheStatus() },
                warmCache = { fakeCacheStatus() }
            )
        }

        val response = client.get("/api/v1/alpha/cross-sectional/cache/status")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"enabled\": true"), body)
        assertTrue(body.contains("\"barMinutes\": 60"), body)
        assertTrue(body.contains("\"symbols\": 190"), body)
    }

    @Test
    fun `cache warm endpoint decodes config and returns snapshot payload`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                runAnalysis = { fakeResult(it) },
                runSearch = { fakeSearchResult(it) },
                cacheStatus = { fakeCacheStatus() },
                warmCache = { config ->
                    assertEquals(240, config.barMinutes)
                    fakeCacheStatus()
                }
            )
        }

        val response = client.post("/api/v1/alpha/cross-sectional/cache/warm") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "barMinutes": 240,
                  "lookbackHours": 720,
                  "persistBacktest": false,
                  "persistForward": false
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"reloads\": 2"), body)
        assertTrue(body.contains("\"lookbackHours\": 720"), body)
    }

    private fun fakeResult(config: ResearchConfig): CrossSectionalResearchResult {
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
            exchangePlans = listOf(
                ExchangePlan(
                    exchange = "hyperliquid",
                    marketAliases = listOf(config.marketExchange)
                )
            ),
            candidateUniverse = mapOf("hyperliquid" to listOf("BTC", "ETH", "SOL", "TAO")),
            discoveredUniverse = mapOf("hyperliquid" to listOf("BTC", "ETH", "SOL")),
            universeProfiles = listOf(
                UniverseProfileSnapshot(
                    exchange = "hyperliquid",
                    candidateSymbols = 4,
                    selectedSymbols = 3,
                    benchmarkSymbols = 2,
                    candidateAvgRecentTradableRatio = 0.72,
                    selectedAvgRecentTradableRatio = 0.83,
                    candidateAvgRecentObservedRatio = 0.86,
                    selectedAvgRecentObservedRatio = 0.91,
                    candidateAvgRecentSpreadBps = 1.8,
                    selectedAvgRecentSpreadBps = 1.2,
                    candidateMedianRecentSpreadBps = 1.5,
                    selectedMedianRecentSpreadBps = 1.0,
                    candidateAvgRecentDepthUsd = 450_000.0,
                    selectedAvgRecentDepthUsd = 540_000.0,
                    candidateAvgRecentVolumeUsd = 1_900_000.0,
                    selectedAvgRecentVolumeUsd = 2_200_000.0,
                    candidateObservedExecutionShare = 0.84,
                    selectedObservedExecutionShare = 0.92,
                    candidateTradableExecutionShare = 0.69,
                    selectedTradableExecutionShare = 0.81,
                    liquidityBuckets = listOf(
                        UniverseLiquidityBucketSnapshot("deep", 2, 1.0, 700_000.0, 2_400_000.0, 0.92),
                        UniverseLiquidityBucketSnapshot("core", 1, 1.5, 420_000.0, 1_800_000.0, 0.76),
                        UniverseLiquidityBucketSnapshot("fragile", 1, 2.9, 160_000.0, 800_000.0, 0.42)
                    ),
                    selectedUniverse = listOf("BTC", "ETH", "SOL"),
                    topCandidates = listOf("SOL", "TAO")
                )
            ),
            barsLoaded = 42,
            featureRows = 36,
            diagnostics = ResearchDiagnostics(
                barMinutes = config.barMinutes,
                warmupFloorBars = 8,
                totalRows = 36,
                liquidRows = 12,
                rowsPerSymbol = mapOf("SOL" to 12),
                liquidPerSymbol = mapOf("SOL" to 12),
                liquidFailureCounts = mapOf("warmup" to 4),
                rankEligibleCounts = mapOf("trendLong" to 1),
                seedCounts = mapOf("trend" to 1, "reversion" to 0),
                topTrendSeeds = emptyList(),
                topReversionSeeds = emptyList()
            ),
            heuristicSignals = emptyList(),
            latestSignals = emptyList(),
            backtestSummaries = emptyList(),
            forwardSummaries = emptyList(),
            forwardCutoff = null,
            calibrationRows = 0,
            forwardRows = 0,
            calibrationExampleCounts = emptyMap(),
            backtestPortfolioProfiles = mapOf(
                "trend" to PortfolioProfileSnapshot(
                    strategyKind = "trend",
                    stage = "backtest",
                    exchanges = listOf("hyperliquid"),
                    trades = 0,
                    policyMaxConcurrentPositions = 6,
                    policyMaxConcurrentLongs = 3,
                    policyMaxConcurrentShorts = 3,
                    policyMaxNetExposureFraction = 0.4,
                    policyMaxAbsBetaBtc = 0.65,
                    policyMaxAbsBetaEth = 0.65,
                    maxConcurrentPositions = 0,
                    maxConcurrentLongs = 0,
                    maxConcurrentShorts = 0,
                    avgConcurrentPositions = 0.0,
                    avgConcurrentLongs = 0.0,
                    avgConcurrentShorts = 0.0,
                    maxGrossExposureUsd = 0.0,
                    avgGrossExposureUsd = 0.0,
                    maxNetExposureUsd = 0.0,
                    avgNetExposureUsd = 0.0,
                    maxAbsNetExposureFraction = 0.0,
                    avgAbsNetExposureFraction = 0.0,
                    maxAbsBetaBtc = 0.0,
                    avgAbsBetaBtc = 0.0,
                    maxAbsBetaEth = 0.0,
                    avgAbsBetaEth = 0.0,
                    avgCapacityUtilization = 0.0,
                    maxCapacityUtilization = 0.0,
                    entryConstraints = PortfolioConstraintSnapshot(0, 0, 0, 0, 0, 0, 0, 0)
                )
            )
        )
    }

    private fun fakeDataHealthSummary(): DataHealthSummary {
        return DataHealthSummary(
            exchange = "hyperliquid_mainnet",
            barMinutes = 1,
            asOf = Instant.parse("2026-03-25T05:00:00Z"),
            thresholds = DataHealthThresholds(
                exchange = "hyperliquid_mainnet",
                barMinutes = 1,
                requiredRawChannels = listOf("candle_1m", "funding", "orderbook_l2", "trade"),
                rawStaleAfterSeconds = 120,
                candleRawLagMaxSeconds = 90,
                featureLagMaxSeconds = 180,
                finalizedLagMaxMinutes = 5,
                minCoverageRatio = 0.98,
                minFinalizedRatio = 0.95,
                minExecutionObservedRatio = 0.55,
                minUniverseSymbols = 12
            ),
            trackedSymbols = 185,
            activeSymbols = 182,
            inactiveSymbols = 3,
            healthySymbols = 119,
            degradedSymbols = 56,
            criticalSymbols = 7,
            symbolsMissingRequiredChannels = 2,
            staleCandleSymbols = 5,
            staleFeatureSymbols = 7,
            coverageFailSymbols = 61,
            finalizedFailSymbols = 88,
            executionFailSymbols = 12,
            avgCoverageRatioActive = 0.941,
            avgFinalizedRatioActive = 0.903,
            avgRecentExecutionObservedShare24hActive = 0.811,
            maxCandleLagSecondsActive = 4_532,
            maxFeatureLagSecondsActive = 4_500,
            criticalSample = listOf("KAS", "KAITO", "YZY")
        )
    }

    private fun fakeDataHealthIssues(): DataHealthIssuesResponse {
        return DataHealthIssuesResponse(
            exchange = "hyperliquid_mainnet",
            barMinutes = 1,
            asOf = Instant.parse("2026-03-25T05:00:00Z"),
            thresholds = fakeDataHealthSummary().thresholds,
            totalIssues = 2,
            issues = listOf(
                DataHealthSymbolIssue(
                    exchange = "hyperliquid_mainnet",
                    symbol = "KAS",
                    status = DataHealthStatus.CRITICAL,
                    activeRecent = true,
                    missingRequiredChannels = listOf("candle_1m"),
                    staleChannels = listOf("candle_1m"),
                    reasons = listOf(
                        "missing required raw channel candle_1m",
                        "candle_1m lag 4532s exceeds 90s"
                    ),
                    latestAnyRawTime = Instant.parse("2026-03-25T05:00:00Z"),
                    candleLatestRawTime = null,
                    tradeLatestRawTime = Instant.parse("2026-03-25T04:59:59Z"),
                    orderbookLatestRawTime = Instant.parse("2026-03-25T04:59:58Z"),
                    fundingLatestRawTime = Instant.parse("2026-03-25T04:59:57Z"),
                    latestFeatureTime = null,
                    finalizedThrough = null,
                    candleRawLagSeconds = null,
                    tradeRawLagSeconds = 1,
                    orderbookRawLagSeconds = 2,
                    fundingRawLagSeconds = 3,
                    featureLagSeconds = null,
                    finalizedLagMinutes = null,
                    materializerLagSeconds = 600,
                    coverageRatio = 0.0,
                    finalizedRatio = 0.0,
                    recentExecutionObservedShare24h = 0.0,
                    recentFeatureRows24h = 0
                ),
                DataHealthSymbolIssue(
                    exchange = "hyperliquid_mainnet",
                    symbol = "NOT",
                    status = DataHealthStatus.DEGRADED,
                    activeRecent = true,
                    missingRequiredChannels = emptyList(),
                    staleChannels = emptyList(),
                    reasons = listOf(
                        "coverage 0.021 below 0.980",
                        "finalized coverage 0.001 below 0.950"
                    ),
                    latestAnyRawTime = Instant.parse("2026-03-25T04:59:00Z"),
                    candleLatestRawTime = Instant.parse("2026-03-25T04:59:00Z"),
                    tradeLatestRawTime = Instant.parse("2026-03-25T04:59:20Z"),
                    orderbookLatestRawTime = Instant.parse("2026-03-25T04:59:22Z"),
                    fundingLatestRawTime = Instant.parse("2026-03-25T04:59:25Z"),
                    latestFeatureTime = Instant.parse("2026-03-25T04:59:00Z"),
                    finalizedThrough = Instant.parse("2026-03-25T04:57:00Z"),
                    candleRawLagSeconds = 60,
                    tradeRawLagSeconds = 40,
                    orderbookRawLagSeconds = 38,
                    fundingRawLagSeconds = 35,
                    featureLagSeconds = 60,
                    finalizedLagMinutes = 3.0,
                    materializerLagSeconds = 60,
                    coverageRatio = 0.021,
                    finalizedRatio = 0.001,
                    recentExecutionObservedShare24h = 0.62,
                    recentFeatureRows24h = 21
                )
            )
        )
    }

    private fun fakeCacheStatus(): UniverseSnapshotCacheStatus {
        return UniverseSnapshotCacheStatus(
            enabled = true,
            ttlSeconds = 300,
            maxEntries = 8,
            hits = 4,
            misses = 2,
            reloads = 2,
            lastLoadMs = 183,
            lastError = null,
            entries = listOf(
                UniverseSnapshotCacheEntryStatus(
                    aliases = listOf("hyperliquid_mainnet"),
                    barMinutes = 60,
                    lookbackHours = 720,
                    loadedAt = Instant.parse("2026-03-24T12:20:00Z"),
                    ageSeconds = 12,
                    symbols = 190,
                    bars = 4_560,
                    firstBarTime = Instant.parse("2026-03-19T12:00:00Z"),
                    lastBarTime = Instant.parse("2026-03-24T12:00:00Z")
                )
            )
        )
    }

    private fun fakeReadiness(config: ResearchConfig): CrossSectionalResearchReadiness {
        return CrossSectionalResearchReadiness(
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
            exchangePlans = listOf(
                ExchangePlan(
                    exchange = "hyperliquid",
                    marketAliases = listOf("hyperliquid_mainnet")
                )
            ),
            exchangeCatalogMs = 21,
            discoveryMs = 84,
            discoveryCandidateLimit = 64,
            requiredBars = 96,
            minimumEligibleSymbols = 12,
            passed = true,
            reason = null,
            exchanges = listOf(
                CrossSectionalExchangeReadiness(
                    exchange = "hyperliquid",
                    marketAliases = listOf("hyperliquid_mainnet"),
                    discoveredSymbols = 19,
                    eligibleSymbols = 17,
                    requiredBars = 96,
                    minimumEligibleSymbols = 12,
                    passed = true,
                    reason = null,
                    sampleEligibleSymbols = listOf("BTC", "ETH", "SOL"),
                    sampleCoverageFailures = listOf(
                        ResearchCoverageSnapshot(
                            symbol = "BOME",
                            expectedBars = 96,
                            observedBars = 40,
                            finalizedBars = 38,
                            executionObservedBars = 36,
                            coverageRatio = 0.4167,
                            finalizedRatio = 0.3958,
                            executionObservedRatio = 0.3750,
                            latestFeatureTime = Instant.parse("2026-03-25T05:00:00Z"),
                            finalizedThrough = Instant.parse("2026-03-25T04:58:00Z"),
                            latestFeatureLagSeconds = 60,
                            finalizedLagMinutes = 3
                        )
                    )
                )
            )
        )
    }

    private fun fakeSearchResult(searchConfig: CrossSectionalSearchConfig): CrossSectionalSearchResult {
        val normalizedBase = searchConfig.baseConfig.copy(
            persistBacktest = false,
            persistForward = false,
            enablePaperOrders = false
        )
        val candidateConfig = normalizedBase.copy(
            barMinutes = 240,
            lookbackHours = 720,
            forwardHours = 72,
            trendHoldBars = 8,
            reversionHoldBars = 2,
            trendLookbackBars = 18,
            trendSlowBars = 48,
            reversionLookbackBars = 8
        )
        val fitness = StrategySearchFitness(
            strategyKind = "trend",
            score = 42.5,
            passesFilters = true,
            rejectionReasons = emptyList(),
            backtest = StrategyAggregateSnapshot(
                exchanges = listOf("hyperliquid"),
                trades = 18,
                winRate = 0.61,
                netReturnPct = 7.2,
                maxDrawdownPct = 3.8,
                sharpe = 1.9,
                avgEdgeAfterCostBps = 12.4,
                avgTotalCostBps = 5.8,
                avgFillRatio = 0.81,
                avgSubmitToFillMs = 92.0
            ),
            forward = StrategyAggregateSnapshot(
                exchanges = listOf("hyperliquid"),
                trades = 6,
                winRate = 0.67,
                netReturnPct = 2.1,
                maxDrawdownPct = 1.2,
                sharpe = 1.3,
                avgEdgeAfterCostBps = 9.5,
                avgTotalCostBps = 5.4,
                avgFillRatio = 0.79,
                avgSubmitToFillMs = 88.0
            )
        )
        val reversionFitness = StrategySearchFitness(
            strategyKind = "reversion",
            score = 24.8,
            passesFilters = true,
            rejectionReasons = emptyList(),
            backtest = StrategyAggregateSnapshot(
                exchanges = listOf("hyperliquid"),
                trades = 12,
                winRate = 0.58,
                netReturnPct = 4.3,
                maxDrawdownPct = 2.9,
                sharpe = 1.4,
                avgEdgeAfterCostBps = 8.2,
                avgTotalCostBps = 5.6,
                avgFillRatio = 0.77,
                avgSubmitToFillMs = 84.0
            ),
            forward = StrategyAggregateSnapshot(
                exchanges = listOf("hyperliquid"),
                trades = 4,
                winRate = 0.5,
                netReturnPct = 0.9,
                maxDrawdownPct = 1.0,
                sharpe = 0.8,
                avgEdgeAfterCostBps = 6.1,
                avgTotalCostBps = 5.1,
                avgFillRatio = 0.74,
                avgSubmitToFillMs = 81.0
            )
        )
        val candidate = CrossSectionalSearchCandidate(
            rank = 1,
            combinedScore = 67.3,
            config = candidateConfig,
            dataKey = ResearchDataKey(
                txGatewayUrl = candidateConfig.txGatewayUrl,
                marketExchange = candidateConfig.marketExchange,
                executionExchangeOverride = candidateConfig.executionExchangeOverride,
                barMinutes = candidateConfig.barMinutes,
                lookbackHours = candidateConfig.lookbackHours,
                discoveryMaxSymbols = candidateConfig.discoveryMaxSymbols,
                maxSymbols = candidateConfig.maxSymbols,
                minBars = candidateConfig.minBars
            ),
            evaluatedAt = Instant.parse("2026-03-24T01:00:00Z"),
            barsLoaded = 420,
            featureRows = 360,
            calibrationRows = 240,
            forwardRows = 120,
            trendHoldHours = 32.0,
            reversionHoldHours = 8.0,
            trendFitness = fitness,
            reversionFitness = reversionFitness
        )
        return CrossSectionalSearchResult(
            searchConfig = searchConfig.copy(baseConfig = normalizedBase),
            startedAt = Instant.parse("2026-03-24T00:00:00Z"),
            completedAt = Instant.parse("2026-03-24T00:05:00Z"),
            roundsCompleted = 2,
            evaluatedConfigs = 5,
            topTrendConfigs = listOf(candidate),
            topReversionConfigs = listOf(candidate.copy(rank = 1)),
            topCombinedConfigs = listOf(candidate.copy(rank = 1))
        )
    }
}
