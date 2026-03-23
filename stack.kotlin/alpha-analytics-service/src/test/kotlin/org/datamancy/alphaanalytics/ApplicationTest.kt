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
import org.datamancy.trading.analytics.crosssectional.CrossSectionalResearchResult
import org.datamancy.trading.analytics.crosssectional.ExchangeCatalogSnapshot
import org.datamancy.trading.analytics.crosssectional.ExchangeCapabilitiesSnapshot
import org.datamancy.trading.analytics.crosssectional.ExchangePlan
import org.datamancy.trading.analytics.crosssectional.ResearchConfig
import org.datamancy.trading.analytics.crosssectional.ResearchDiagnostics

class ApplicationTest {
    @Test
    fun `health endpoint responds healthy`() = testApplication {
        application {
            configureAlphaAnalyticsApp { fakeResult(it) }
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("healthy"))
    }

    @Test
    fun `default config endpoint exposes service defaults`() = testApplication {
        application {
            configureAlphaAnalyticsApp { fakeResult(it) }
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
            configureAlphaAnalyticsApp { config -> fakeResult(config) }
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
}
