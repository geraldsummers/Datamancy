package org.datamancy.trading.alpha

import org.datamancy.trading.policy.DatamancyTradingPolicy
import java.time.Instant
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InterdaySearchEngineTest {
    private val panel = syntheticPanel()
    private val engine = InterdaySearchEngine(
        panelSource = object : InterdayPanelSource {
            override suspend fun load(request: InterdayPanelRequest): InterdayPanel = panel
        },
        policyProvider = { DatamancyTradingPolicy.default() }
    )

    @Test
    fun `run produces targets trades and inspection on synthetic interday panel`() = kotlinx.coroutines.runBlocking {
        val response = engine.run(
            InterdayAlphaRunRequest(
                config = InterdayAlphaConfig(
                    exchange = "hyperliquid_mainnet",
                    signalBarMinutes = 240,
                    lookbackHours = 480,
                    forwardHours = 72,
                    rebalanceCadenceHours = 24,
                    selectionQuantile = 0.34,
                    minConfidence = 0.15,
                    executionWindowMinutes = 120
                )
            )
        )

        assertTrue(response.selectedSignals.isNotEmpty())
        assertNotNull(response.inspection)
        val point = response.inspection!!.symbols.first().points.first()
        assertTrue(point.expectedNetEdgeBps.isFinite())
        assertTrue(point.expectedResidualReturnBps.isFinite())
        assertTrue(point.expectedEntryCostBps.isFinite())
        assertTrue(point.entryEligible || response.targets.isEmpty())
    }

    @Test
    fun `search evaluates configured space and returns leaderboard`() = kotlinx.coroutines.runBlocking {
        val response = engine.search(
            InterdayAlphaSearchRequest(
                baseConfig = InterdayAlphaConfig(
                    exchange = "hyperliquid_mainnet",
                    signalBarMinutes = 240,
                    lookbackHours = 480,
                    forwardHours = 72,
                    rebalanceCadenceHours = 24,
                    selectionQuantile = 0.34,
                    minConfidence = 0.15
                ),
                searchSpace = InterdaySearchSpace(
                    slopeWeight = listOf(0.15, 0.25),
                    pullbackWeight = listOf(0.10, 0.20)
                ),
                maxEvaluations = 2,
                leaderboardSize = 2
            )
        )

        assertEquals(2, response.evaluatedConfigs)
        assertEquals(2, response.leaderboard.size)
        assertTrue(response.leaderboard.all { it.selectedSignals.isNotEmpty() })
    }

    @Test
    fun `search loads shared panel once per panel key`() = kotlinx.coroutines.runBlocking {
        var loadCount = 0
        val countingEngine = InterdaySearchEngine(
            panelSource = object : InterdayPanelSource {
                override suspend fun load(request: InterdayPanelRequest): InterdayPanel {
                    loadCount += 1
                    return panel
                }
            },
            policyProvider = { DatamancyTradingPolicy.default() }
        )

        val response = countingEngine.search(
            InterdayAlphaSearchRequest(
                baseConfig = InterdayAlphaConfig(
                    exchange = "hyperliquid_mainnet",
                    signalBarMinutes = 240,
                    lookbackHours = 480,
                    forwardHours = 72,
                    rebalanceCadenceHours = 24,
                    selectionQuantile = 0.34,
                    minConfidence = 0.15,
                    maxSymbols = 12
                ),
                searchSpace = InterdaySearchSpace(
                    slopeWeight = listOf(0.15, 0.25),
                    pullbackWeight = listOf(0.10, 0.20)
                ),
                maxEvaluations = 4,
                leaderboardSize = 4
            )
        )

        assertEquals(4, response.evaluatedConfigs)
        assertEquals(1, loadCount)
    }

    @Test
    fun `high perturbation threshold still allows confirmed continuation entries`() = kotlinx.coroutines.runBlocking {
        val response = engine.run(
            InterdayAlphaRunRequest(
                config = InterdayAlphaConfig(
                    exchange = "hyperliquid_mainnet",
                    signalBarMinutes = 240,
                    lookbackHours = 480,
                    forwardHours = 72,
                    rebalanceCadenceHours = 24,
                    selectionQuantile = 0.34,
                    minConfidence = 0.15,
                    perturbationThresholdZ = 10.0,
                    executionWindowMinutes = 120
                )
            )
        )

        assertTrue(response.selectedSignals.isNotEmpty())
        assertTrue(response.targets.isNotEmpty())
        assertTrue(response.targets.all { it.expectedNetEdgeBps > 0.0 })
    }

    @Test
    fun `regime flush flattens wrong way inventory on regime reversal`() = kotlinx.coroutines.runBlocking {
        val method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod("shouldForceFlattenByRegime", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, InterdayAlphaConfig::class.java)
        method.isAccessible = true

        val allowed = method.invoke(null, 0.12, 0.75, InterdayAlphaConfig(regimeDirectionalSuppressionThreshold = 0.55)) as Boolean
        val blocked = method.invoke(null, -0.12, 0.75, InterdayAlphaConfig(regimeDirectionalSuppressionThreshold = 0.55)) as Boolean

        assertTrue(!allowed)
        assertTrue(blocked)
    }
}

private fun syntheticPanel(): InterdayPanel {
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
                val open = close * 0.997
                val high = close * 1.012
                val low = close * 0.988
                val volume = when (symbol) {
                    "ALPHA" -> 1_250.0 + index * 6.0
                    "BRAVO" -> 1_050.0 + index * 4.0
                    else -> 650.0 + index * 2.0
                }
                val funding = when (symbol) {
                    "ALPHA" -> -0.00008 + index * 0.0000001
                    "BRAVO" -> 0.00011 - index * 0.0000001
                    else -> 0.00001
                }
                val openInterest = when (symbol) {
                    "ALPHA" -> 30_000.0 + index * 220.0
                    "BRAVO" -> 55_000.0 - index * 150.0
                    else -> 25_000.0 + 25.0 * sin(index / 5.0)
                }
                InterdayBar(
                    time = time,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume,
                    tradeVolume = volume,
                    buyVolume = volume * if (symbol == "ALPHA") 0.58 else 0.45,
                    sellVolume = volume * if (symbol == "BRAVO") 0.58 else 0.42,
                    spreadBps = when (symbol) {
                        "CHARLIE" -> 16.0
                        else -> 5.0
                    },
                    depthUsd = when (symbol) {
                        "ALPHA" -> 250_000.0 + index * 1_000.0
                        "BRAVO" -> 220_000.0 + index * 800.0
                        else -> 90_000.0
                    },
                    fundingRate = funding,
                    openInterest = openInterest,
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
