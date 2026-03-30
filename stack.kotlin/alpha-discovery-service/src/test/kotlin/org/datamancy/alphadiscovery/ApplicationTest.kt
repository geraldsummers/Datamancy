package org.datamancy.alphadiscovery

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.datamancy.trading.alpha.http.AlphaServiceJson
import org.datamancy.trading.alpha.AlphaDiscoveryPlanner
import org.datamancy.trading.alpha.InterdayBar
import org.datamancy.trading.alpha.InterdayPanel
import org.datamancy.trading.alpha.InterdayPanelRequest
import org.datamancy.trading.alpha.InterdayPanelSource
import org.datamancy.trading.alpha.InterdaySearchEngine
import org.datamancy.trading.alpha.InterdaySymbolSeries
import org.datamancy.trading.policy.DatamancyTradingPolicy
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun `defaults endpoint exposes locked daily control config`() = testApplication {
        application {
            configureAlphaDiscoveryApp(
                planner = AlphaDiscoveryPlanner { DatamancyTradingPolicy.default() },
                engine = InterdaySearchEngine(
                    panelSource = object : InterdayPanelSource {
                        override suspend fun load(request: InterdayPanelRequest): InterdayPanel = syntheticPanelForServiceTest()
                    },
                    policyProvider = { DatamancyTradingPolicy.default() }
                ),
                latestSearch = AtomicReference(null)
            )
        }

        val response = client.get("/api/v1/discovery/defaults")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        val defaults = AlphaServiceJson.gson.fromJson(body, org.datamancy.trading.alpha.AlphaDiscoveryDefaults::class.java)
        assertEquals(1_440, defaults.defaultSignalBarMinutes)
        assertEquals(listOf(0.02, 0.021), defaults.selectionQuantiles)
        assertEquals(1_440, defaults.defaultConfig.signalBarMinutes)
        assertEquals(org.datamancy.trading.alpha.InterdayTrendScoreMode.EMA_RETURN_STACK, defaults.defaultConfig.trendScoreMode)
    }

    @Test
    fun `search endpoint updates leaderboard cache`() = testApplication {
        val panel = syntheticPanelForServiceTest()
        application {
            configureAlphaDiscoveryApp(
                planner = AlphaDiscoveryPlanner { DatamancyTradingPolicy.default() },
                engine = InterdaySearchEngine(
                    panelSource = object : InterdayPanelSource {
                        override suspend fun load(request: InterdayPanelRequest): InterdayPanel = panel
                    },
                    policyProvider = { DatamancyTradingPolicy.default() }
                ),
                latestSearch = AtomicReference(null)
            )
        }

        val searchResponse = client.post("/api/v1/discovery/search") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "baseConfig": {
                    "exchange": "hyperliquid_mainnet",
                    "signalBarMinutes": 240,
                    "lookbackHours": 480,
                    "forwardHours": 72,
                    "rebalanceCadenceHours": 24,
                    "selectionQuantile": 0.34,
                    "minConfidence": 0.15
                  },
                  "searchSpace": {
                    "slopeWeight": [0.15],
                    "pullbackWeight": [0.20]
                  },
                  "maxEvaluations": 1,
                  "leaderboardSize": 1
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, searchResponse.status)
        assertTrue(searchResponse.bodyAsText().contains("\"leaderboard\""))

        val leaderboardResponse = client.get("/api/v1/discovery/leaderboard")
        assertEquals(HttpStatusCode.OK, leaderboardResponse.status)
        val leaderboardBody = leaderboardResponse.bodyAsText()
        assertTrue(leaderboardBody.contains("\"sourceSearchGeneratedAt\""))
        assertTrue(leaderboardBody.contains("\"ALPHA\"") || leaderboardBody.contains("\"BRAVO\""))
    }

    @Test
    fun `run endpoint returns targets and inspection`() = testApplication {
        val panel = syntheticPanelForServiceTest()
        var recorded = false
        application {
            configureAlphaDiscoveryApp(
                planner = AlphaDiscoveryPlanner { DatamancyTradingPolicy.default() },
                engine = InterdaySearchEngine(
                    panelSource = object : InterdayPanelSource {
                        override suspend fun load(request: InterdayPanelRequest): InterdayPanel = panel
                    },
                    policyProvider = { DatamancyTradingPolicy.default() }
                ),
                latestSearch = AtomicReference(null),
                runRecorder = AlphaResearchRunRecorder { _, _ ->
                    recorded = true
                    AlphaResearchRunRecord(
                        runId = "run-123",
                        grafanaPath = "/d/alpha-run-explorer/alpha-run-explorer?var-alpha_run_id=run-123"
                    )
                }
            )
        }

        val runResponse = client.post("/api/v1/discovery/run") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "config": {
                    "exchange": "hyperliquid_mainnet",
                    "signalBarMinutes": 240,
                    "lookbackHours": 480,
                    "forwardHours": 72,
                    "rebalanceCadenceHours": 24,
                    "selectionQuantile": 0.34,
                    "minConfidence": 0.15
                  },
                  "comparisonConfigs": [
                    {
                      "exchange": "hyperliquid_mainnet",
                      "signalBarMinutes": 240,
                      "lookbackHours": 480,
                      "forwardHours": 72,
                      "rebalanceCadenceHours": 24,
                      "selectionQuantile": 0.30,
                      "minConfidence": 0.15
                    }
                  ],
                  "mode": "OFFLINE_BACKTEST",
                  "includeInspection": true
                }
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.OK, runResponse.status)
        assertTrue(recorded)
        val body = runResponse.bodyAsText()
        assertTrue(body.contains("\"targets\""))
        assertTrue(body.contains("\"inspection\""))
        assertTrue(body.contains("\"expectedNetEdgeBps\""))
        assertTrue(body.contains("\"runId\""))
        assertTrue(body.contains("run-123"))
        assertTrue(body.contains("\"grafanaPath\""))
        assertTrue(body.contains("alpha-run-explorer"))
        assertTrue(body.contains("var-alpha_run_id"))
        assertTrue(body.contains("\"multiplicity\""))
        assertTrue(body.contains("\"comparisonEvaluations\""))
    }

    @Test
    fun `threshold calibration endpoint returns recommended policy`() = testApplication {
        val panel = syntheticPanelForServiceTest()
        application {
            configureAlphaDiscoveryApp(
                planner = AlphaDiscoveryPlanner { DatamancyTradingPolicy.default() },
                engine = InterdaySearchEngine(
                    panelSource = object : InterdayPanelSource {
                        override suspend fun load(request: InterdayPanelRequest): InterdayPanel = panel
                    },
                    policyProvider = { DatamancyTradingPolicy.default() }
                ),
                latestSearch = AtomicReference(null)
            )
        }

        val response = client.post("/api/v1/discovery/calibrate-thresholds") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "baseConfig": {
                    "exchange": "hyperliquid_mainnet",
                    "signalBarMinutes": 240,
                    "lookbackHours": 480,
                    "forwardHours": 72,
                    "rebalanceCadenceHours": 24,
                    "selectionQuantile": 0.34,
                    "minConfidence": 0.15
                  },
                  "searchSpace": {
                    "slopeWeight": [0.15, 0.25],
                    "pullbackWeight": [0.10, 0.20]
                  },
                  "maxEvaluations": 4,
                  "leaderboardSize": 2,
                  "thresholdCalibration": {
                    "thresholdGridBps": [0.5, 1.0, 1.5, 2.0],
                    "minAcceptedCandidates": 1,
                    "minForwardPositiveRatio": 0.0
                  }
                }
                """.trimIndent()
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"thresholdCalibration\""))
        assertTrue(body.contains("\"recommendedPolicy\""))
        assertTrue(body.contains("\"selectedMinNetEdgeBps\""))
    }
}

private fun syntheticPanelForServiceTest(): InterdayPanel {
    val start = Instant.parse("2026-01-01T00:00:00Z")
    val points = 180
    val timeline = List(points) { index -> start.plusSeconds(index * 14_400L) }
    val symbols = listOf("ALPHA", "BRAVO", "CHARLIE")
    val series = symbols.map { symbol ->
        InterdaySymbolSeries(
            symbol = symbol,
            bars = timeline.mapIndexed { index, time ->
                val close = when (symbol) {
                    "ALPHA" -> 100.0 * exp(0.009 * index) * (1.0 + 0.02 * sin(index / 3.5)) * alphaPullback(index)
                    "BRAVO" -> 180.0 * exp(-0.007 * index) * (1.0 + 0.02 * cos(index / 4.0)) * bravoBounce(index)
                    else -> 90.0 * (1.0 + 0.002 * sin(index / 2.0))
                }
                InterdayBar(
                    time = time,
                    open = close * 0.997,
                    high = close * 1.012,
                    low = close * 0.988,
                    close = close,
                    volume = 1_000.0 + index * 3.0,
                    tradeVolume = 1_000.0 + index * 3.0,
                    buyVolume = 580.0,
                    sellVolume = 420.0,
                    spreadBps = if (symbol == "CHARLIE") 16.0 else 5.0,
                    depthUsd = if (symbol == "CHARLIE") 90_000.0 else 220_000.0 + index * 700.0,
                    fundingRate = when (symbol) {
                        "ALPHA" -> -0.00008 + index * 0.0000001
                        "BRAVO" -> 0.00011 - index * 0.0000001
                        else -> 0.00001
                    },
                    openInterest = when (symbol) {
                        "ALPHA" -> 30_000.0 + index * 220.0
                        "BRAVO" -> 55_000.0 - index * 150.0
                        else -> 25_000.0
                    },
                    tradeObservedRatio = 1.0,
                    orderbookObservedRatio = 1.0,
                    assetContextObservedRatio = 1.0
                )
            }
        )
    }
    return InterdayPanel(
        exchange = "hyperliquid_mainnet",
        signalBarMinutes = 240,
        timeline = timeline,
        series = series
    )
}

private fun alphaPullback(index: Int): Double = when (index % 19) {
    16 -> 0.975
    17 -> 0.965
    18 -> 0.985
    else -> 1.0
}

private fun bravoBounce(index: Int): Double = when (index % 17) {
    14 -> 1.020
    15 -> 1.030
    16 -> 1.010
    else -> 1.0
}
