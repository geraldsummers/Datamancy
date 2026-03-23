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
import org.datamancy.trading.analytics.crosssectional.CrossSectionalResearchResult
import org.datamancy.trading.analytics.crosssectional.ExchangeCatalogSnapshot
import org.datamancy.trading.analytics.crosssectional.ExchangeCapabilitiesSnapshot
import org.datamancy.trading.analytics.crosssectional.ExchangePlan
import org.datamancy.trading.analytics.crosssectional.ResearchConfig
import org.datamancy.trading.analytics.crosssectional.ResearchDataKey
import org.datamancy.trading.analytics.crosssectional.ResearchDiagnostics
import org.datamancy.trading.analytics.crosssectional.StrategyAggregateSnapshot
import org.datamancy.trading.analytics.crosssectional.StrategySearchFitness
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
        assertTrue(body.contains("\"barMinutes\": 60"), body)
        assertTrue(body.contains("hyperliquid_mainnet"), body)
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
            discoveredUniverse = mapOf("hyperliquid" to listOf("BTC", "ETH", "SOL")),
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
            calibrationExampleCounts = emptyMap()
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
