package org.datamancy.alphaanalytics

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.datamancy.trading.policy.DatamancyTradingPolicy
import java.time.Instant

class ApplicationTest {
    @Test
    fun `health endpoint responds healthy`() = testApplication {
        application {
            configureAlphaAnalyticsApp()
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("healthy"))
    }

    @Test
    fun `root endpoint exposes retained service surface only`() = testApplication {
        application {
            configureAlphaAnalyticsApp()
        }

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("/api/v1/policy/trading"), body)
        assertTrue(body.contains("/api/v1/data-health/summary"), body)
        assertTrue(!body.contains("/api/v1/alpha/cross-sectional"), body)
    }

    @Test
    fun `legacy cross sectional route is gone`() = testApplication {
        application {
            configureAlphaAnalyticsApp()
        }

        val response = client.get("/api/v1/alpha/cross-sectional/default-config")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `trading policy endpoint exposes authoritative runtime policy`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
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
    fun `data health summary endpoint exposes policy backed health rollup`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
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
    fun `data health venue sanity endpoint exposes live sparse classification`() = testApplication {
        application {
            configureAlphaAnalyticsApp(
                loadVenueSanity = { exchange, symbol, barMinutes ->
                    assertEquals("hyperliquid_mainnet", exchange)
                    assertEquals("MAVIA", symbol)
                    assertEquals(1, barMinutes)
                    DataHealthVenueSanity(
                        exchange = "hyperliquid_mainnet",
                        symbol = "MAVIA",
                        checkedAt = Instant.parse("2026-03-28T00:00:00Z"),
                        localStatus = DataHealthStatus.CRITICAL,
                        localLivenessClass = DataHealthLivenessClass.LIVE_SPARSE,
                        localReadinessEligible = true,
                        localCandleLagSeconds = 263,
                        localTradeLagSeconds = 304,
                        localOrderbookLagSeconds = 14,
                        venueMidPresent = true,
                        venueBookPresent = true,
                        venueBookTime = Instant.parse("2026-03-28T00:00:00Z"),
                        venueBookAgeSeconds = 0,
                        classification = VenueSanityClassification.LIVE_SPARSE,
                        reasons = listOf("venue l2Book returned live levels"),
                        probeError = null
                    )
                }
            )
        }

        val response = client.get("/api/v1/data-health/venue-sanity?exchange=hyperliquid_mainnet&barMinutes=1&symbol=MAVIA")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"classification\": \"LIVE_SPARSE\""), body)
        assertTrue(body.contains("\"venueBookPresent\": true"), body)
        assertTrue(body.contains("\"localLivenessClass\": \"LIVE_SPARSE\""), body)
    }

    @Test
    fun `data health venue sanity requires a symbol`() = testApplication {
        application {
            configureAlphaAnalyticsApp()
        }

        val response = client.get("/api/v1/data-health/venue-sanity?exchange=hyperliquid_mainnet&barMinutes=1")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("requires symbol"))
    }

    private fun fakeDataHealthSummary(): DataHealthSummary = DataHealthSummary(
        exchange = "hyperliquid_mainnet",
        barMinutes = 1,
        asOf = Instant.parse("2026-03-28T00:00:00Z"),
        thresholds = thresholds(),
        trackedSymbols = 189,
        activeSymbols = 182,
        eligibleSymbols = 173,
        idleLiveSymbols = 4,
        liveSparseSymbols = 3,
        inactiveSymbols = 7,
        healthySymbols = 171,
        degradedSymbols = 4,
        criticalSymbols = 7,
        symbolsMissingRequiredChannels = 3,
        staleCandleSymbols = 2,
        staleFeatureSymbols = 5,
        coverageFailSymbols = 3,
        finalizedFailSymbols = 2,
        executionFailSymbols = 1,
        avgCoverageRatioActive = 0.992,
        avgFinalizedRatioActive = 0.981,
        avgRecentExecutionObservedShare24hActive = 0.776,
        maxCandleLagSecondsActive = 263,
        maxFeatureLagSecondsActive = 151,
        criticalSample = listOf("KAS", "MAVIA")
    )

    private fun fakeDataHealthIssues(): DataHealthIssuesResponse = DataHealthIssuesResponse(
        exchange = "hyperliquid_mainnet",
        barMinutes = 1,
        asOf = Instant.parse("2026-03-28T00:00:00Z"),
        thresholds = thresholds(),
        totalIssues = 2,
        issues = listOf(
            DataHealthSymbolIssue(
                exchange = "hyperliquid_mainnet",
                symbol = "KAS",
                status = DataHealthStatus.CRITICAL,
                livenessClass = DataHealthLivenessClass.LOCAL_STALE,
                activeRecent = true,
                readinessEligible = false,
                missingRequiredChannels = listOf("candle_1m"),
                idleButLiveChannels = emptyList(),
                staleChannels = listOf("trade", "orderbook_l2"),
                reasons = listOf(
                    "missing required raw channel candle_1m",
                    "execution context is not currently live"
                ),
                latestAnyRawTime = Instant.parse("2026-03-27T23:56:00Z"),
                candleLatestRawTime = null,
                tradeLatestRawTime = Instant.parse("2026-03-27T23:55:00Z"),
                orderbookLatestRawTime = Instant.parse("2026-03-27T23:55:05Z"),
                fundingLatestRawTime = Instant.parse("2026-03-27T23:58:00Z"),
                latestFeatureTime = Instant.parse("2026-03-27T23:53:00Z"),
                finalizedThrough = Instant.parse("2026-03-27T23:52:00Z"),
                candleRawLagSeconds = null,
                tradeRawLagSeconds = 300,
                orderbookRawLagSeconds = 295,
                fundingRawLagSeconds = 120,
                featureLagSeconds = 180,
                finalizedLagMinutes = 8.0,
                materializerLagSeconds = 180,
                coverageRatio = 0.91,
                finalizedRatio = 0.88,
                recentExecutionObservedShare24h = 0.32,
                recentFeatureRows24h = 811
            )
        )
    )

    private fun thresholds(): DataHealthThresholds = DataHealthThresholds(
        exchange = "hyperliquid_mainnet",
        barMinutes = 1,
        requiredRawChannels = listOf("candle_1m", "trade", "orderbook_l2", "funding"),
        rawStaleAfterSeconds = 120,
        candleRawLagMaxSeconds = 90,
        featureLagMaxSeconds = 180,
        finalizedLagMaxMinutes = 5,
        minCoverageRatio = 0.98,
        minFinalizedRatio = 0.95,
        minExecutionObservedRatio = 0.55,
        minUniverseSymbols = 12,
        minTradeObservedRatioForEligibility = 0.4
    )
}
