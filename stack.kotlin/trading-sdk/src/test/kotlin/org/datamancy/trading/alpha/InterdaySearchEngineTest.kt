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
import kotlin.test.assertFalse

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
        assertTrue(response.selectedSignals.all { it.marketBeta.isFinite() })
        assertTrue(response.selectedSignals.all { it.residualRank.isFinite() })
        assertTrue(response.inspection!!.compressionDiagnostics.isNotEmpty())
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
                    trendScoreModes = listOf(
                        InterdayTrendScoreMode.LEGACY,
                        InterdayTrendScoreMode.VOL_NORM_RETURN_STACK
                    ),
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
    fun `run supports explicit trend score modes`() = kotlinx.coroutines.runBlocking {
        val modes = listOf(
            InterdayTrendScoreMode.LEGACY,
            InterdayTrendScoreMode.VOL_NORM_RETURN_STACK,
            InterdayTrendScoreMode.REGRESSION_TSTAT,
            InterdayTrendScoreMode.EMA_RETURN_STACK,
            InterdayTrendScoreMode.VOL_NORM_PLUS_TSTAT
        )

        modes.forEach { mode ->
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
                        trendScoreMode = mode
                    )
                )
            )

            assertTrue(response.selectedSignals.isNotEmpty(), "mode=$mode")
            assertTrue(response.selectedSignals.all { it.trendScore.isFinite() }, "mode=$mode")
        }
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

    @Test
    fun `flat regime gross throttle only activates in flat low breadth states`() {
        val method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod("flatRegimeGrossScale", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, InterdayAlphaConfig::class.java)
        method.isAccessible = true

        val config = InterdayAlphaConfig(
            flatRegimeGateMode = InterdayFlatRegimeGateMode.GROSS_THROTTLE,
            flatRegimeMarketTrendThreshold = 0.15,
            flatRegimeBreadthThreshold = 0.10,
            flatRegimeGrossScale = 0.65
        )

        val active = method.invoke(null, 0.08, 0.04, config) as Double
        val inactive = method.invoke(null, 0.22, 0.04, config) as Double

        assertEquals(0.65, active)
        assertEquals(1.0, inactive)
    }

    @Test
    fun `flat regime edge boost only activates for configured gate modes`() {
        val method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod("flatRegimeEntryEdgeBoostBps", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, InterdayAlphaConfig::class.java)
        method.isAccessible = true

        val entryOnly = InterdayAlphaConfig(
            flatRegimeGateMode = InterdayFlatRegimeGateMode.ENTRY_EDGE_BOOST,
            flatRegimeMarketTrendThreshold = 0.15,
            flatRegimeBreadthThreshold = 0.10,
            flatRegimeEntryEdgeFloorBoostBps = 0.5
        )
        val combined = entryOnly.copy(flatRegimeGateMode = InterdayFlatRegimeGateMode.COMBINED)
        val throttleOnly = entryOnly.copy(flatRegimeGateMode = InterdayFlatRegimeGateMode.GROSS_THROTTLE)

        val entryBoost = method.invoke(null, 0.05, 0.03, entryOnly) as Double
        val combinedBoost = method.invoke(null, 0.05, 0.03, combined) as Double
        val throttleBoost = method.invoke(null, 0.05, 0.03, throttleOnly) as Double

        assertEquals(0.5, entryBoost)
        assertEquals(0.5, combinedBoost)
        assertEquals(0.0, throttleBoost)
    }

    @Test
    fun `flat regime gate requires both low market trend and low breadth`() {
        val method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod("flatRegimeGateActive", Double::class.javaPrimitiveType, Double::class.javaPrimitiveType, InterdayAlphaConfig::class.java)
        method.isAccessible = true

        val config = InterdayAlphaConfig(
            flatRegimeGateMode = InterdayFlatRegimeGateMode.COMBINED,
            flatRegimeMarketTrendThreshold = 0.15,
            flatRegimeBreadthThreshold = 0.10
        )

        assertTrue(method.invoke(null, 0.05, 0.04, config) as Boolean)
        assertFalse(method.invoke(null, 0.20, 0.04, config) as Boolean)
        assertFalse(method.invoke(null, 0.05, 0.18, config) as Boolean)
    }

    @Test
    fun `flat regime dispersion guard only blocks new entries below configured dispersion`() {
        val method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod(
                "flatRegimeDispersionAllowed",
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                InterdayAlphaConfig::class.java
            )
        method.isAccessible = true

        val config = InterdayAlphaConfig(
            flatRegimeEntryControlMode = InterdayFlatRegimeEntryControlMode.DISPERSION_GUARD,
            flatRegimeMarketTrendThreshold = 0.15,
            flatRegimeBreadthThreshold = 0.10,
            flatRegimeMinDispersion = 0.20
        )

        assertFalse(method.invoke(null, 0.05, 0.03, 0.12, false, config) as Boolean)
        assertTrue(method.invoke(null, 0.05, 0.03, 0.24, false, config) as Boolean)
        assertTrue(method.invoke(null, 0.05, 0.03, 0.12, true, config) as Boolean)
    }

    @Test
    fun `flat regime confirmation boost only raises trend agreement floor for new flat entries`() {
        val method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod(
                "flatRegimeTrendAgreementFloor",
                Double::class.javaPrimitiveType,
                Double::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                InterdayAlphaConfig::class.java
            )
        method.isAccessible = true

        val config = InterdayAlphaConfig(
            minTrendAgreement = 0.10,
            flatRegimeEntryControlMode = InterdayFlatRegimeEntryControlMode.CONFIRMATION_BOOST,
            flatRegimeMarketTrendThreshold = 0.15,
            flatRegimeBreadthThreshold = 0.10,
            flatRegimeTrendAgreementBoost = 0.12
        )

        assertEquals(0.22, method.invoke(null, 0.05, 0.03, false, config) as Double)
        assertEquals(0.10, method.invoke(null, 0.25, 0.03, false, config) as Double)
        assertEquals(0.10, method.invoke(null, 0.05, 0.03, true, config) as Double)
    }

    @Test
    fun `compression diagnostics detect high shared variation and finite z scores`() {
        val pc1Method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod("pc1Share", List::class.java)
        pc1Method.isAccessible = true
        val zMethod = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod("robustZScore", List::class.java, Double::class.javaPrimitiveType)
        zMethod.isAccessible = true

        val shared = listOf(
            listOf(0.01, 0.02, 0.015, 0.018, 0.016, 0.019),
            listOf(0.011, 0.021, 0.014, 0.019, 0.017, 0.020),
            listOf(0.009, 0.019, 0.016, 0.017, 0.015, 0.018)
        )
        val dispersed = listOf(
            listOf(0.01, -0.02, 0.015, -0.018, 0.016, -0.019),
            listOf(-0.011, 0.021, -0.014, 0.019, -0.017, 0.020),
            listOf(0.009, 0.004, -0.016, -0.007, 0.015, 0.002)
        )
        val sharedPc1 = pc1Method.invoke(null, shared) as Double
        val dispersedPc1 = pc1Method.invoke(null, dispersed) as Double
        val zScore = zMethod.invoke(null, listOf(0.10, 0.11, 0.12, 0.13, 0.11, 0.10, 0.09, 0.12), 0.20) as Double

        assertTrue(sharedPc1 > dispersedPc1)
        assertTrue(zScore.isFinite())
        assertTrue(zScore > 0.0)
    }

    @Test
    fun `compression penalty scale decreases as pc1 share z moves through threshold`() {
        val method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod("compressionPenaltyScale", Double::class.javaPrimitiveType, InterdayAlphaConfig::class.java)
        method.isAccessible = true

        val config = InterdayAlphaConfig(
            compressionPenaltyMode = InterdayCompressionPenaltyMode.PC1_SHARE,
            compressionThresholdZ = 1.0,
            compressionPenaltyStrength = 0.5
        )

        val low = method.invoke(null, 0.0, config) as Double
        val threshold = method.invoke(null, 1.0, config) as Double
        val high = method.invoke(null, 2.0, config) as Double

        assertTrue(low > threshold)
        assertTrue(threshold > high)
        assertTrue(high < 1.0)
    }

    @Test
    fun `flat trend hazard component rises as market trend approaches zero`() {
        val method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod("flatTrendHazardComponent", Double::class.javaPrimitiveType, InterdayAlphaConfig::class.java)
        method.isAccessible = true

        val config = InterdayAlphaConfig(
            flatHazardMode = InterdayFlatHazardMode.MARKET_TREND_ONLY,
            flatRegimeMarketTrendThreshold = 0.15
        )

        val far = method.invoke(null, 0.30, config) as Double
        val edge = method.invoke(null, 0.15, config) as Double
        val center = method.invoke(null, 0.0, config) as Double

        assertEquals(0.0, far, 1e-9)
        assertEquals(0.0, edge, 1e-9)
        assertEquals(1.0, center, 1e-9)
    }

    @Test
    fun `flat hazard gross scale reaches floor only when compression confirm is elevated`() {
        val confirmMethod = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod("flatHazardCompressionConfirm", Double::class.javaPrimitiveType, InterdayAlphaConfig::class.java)
        confirmMethod.isAccessible = true
        val scaleMethod = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod("flatHazardGrossScale", Double::class.javaPrimitiveType, InterdayAlphaConfig::class.java)
        scaleMethod.isAccessible = true

        val config = InterdayAlphaConfig(
            flatHazardMode = InterdayFlatHazardMode.MARKET_TREND_AND_PC1_SHARE,
            flatHazardGrossScaleFloor = 0.65,
            flatHazardCompressionThresholdZ = 0.75
        )

        val lowConfirm = confirmMethod.invoke(null, 0.0, config) as Double
        val highConfirm = confirmMethod.invoke(null, 2.0, config) as Double
        val lowScale = scaleMethod.invoke(null, lowConfirm, config) as Double
        val highScale = scaleMethod.invoke(null, highConfirm, config) as Double

        assertTrue(lowConfirm < highConfirm)
        assertTrue(lowScale > highScale)
        assertTrue(highScale >= 0.65)
    }

    @Test
    fun `training target bars follow forward horizon rather than rebalance cadence`() {
        val method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod("trainingTargetBars", InterdayAlphaConfig::class.java)
        method.isAccessible = true

        val dailyBars = method.invoke(null, InterdayAlphaConfig(signalBarMinutes = 1440, forwardHours = 72, rebalanceCadenceHours = 24)) as Int
        val fourHourBars = method.invoke(null, InterdayAlphaConfig(signalBarMinutes = 240, forwardHours = 72, rebalanceCadenceHours = 24)) as Int

        assertEquals(3, dailyBars)
        assertEquals(18, fourHourBars)
    }

    @Test
    fun `structural factor lookback honors requested daily window once minimum signal history is satisfied`() {
        val method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod(
                "structuralLookbackBars",
                Int::class.javaPrimitiveType,
                InterdayAlphaConfig::class.java,
                InterdaySearchEngine.IndicatorWindows::class.java
            )
        method.isAccessible = true

        fun lookbackFor(days: Int): Int {
            val config = InterdayAlphaConfig(
                signalBarMinutes = 1440,
                factorLookbackDays = days,
                fastTrendDays = 3,
                mediumTrendDays = 7,
                slowTrendDays = 14,
                regressionDays = 14,
                volatilityDays = 14,
                adxDays = 14,
                perturbationLookbackBars = 3
            )
            val indicators = InterdaySearchEngine.IndicatorWindows.fromConfig(config)
            return method.invoke(null, 64, config, indicators) as Int
        }

        assertEquals(14, lookbackFor(3))
        assertEquals(14, lookbackFor(14))
        assertEquals(16, lookbackFor(16))
        assertEquals(18, lookbackFor(18))
        assertEquals(21, lookbackFor(21))
    }

    @Test
    fun `market residualization exposes market beta on signal snapshots`() = kotlinx.coroutines.runBlocking {
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
                    residualizationMode = InterdayResidualizationMode.MARKET,
                    factorLookbackDays = 21
                )
            )
        )

        assertTrue(response.selectedSignals.isNotEmpty())
        assertTrue(response.selectedSignals.any { kotlin.math.abs(it.marketBeta) > 1e-9 })
        assertTrue(response.selectedSignals.all { it.residualRank in -1.0..1.0 })
    }

    @Test
    fun `none residualization mode still runs without market adjustment`() = kotlinx.coroutines.runBlocking {
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
                    residualizationMode = InterdayResidualizationMode.NONE
                )
            )
        )

        assertTrue(response.selectedSignals.isNotEmpty())
        assertTrue(response.selectedSignals.all { it.marketBeta == 0.0 })
    }

    @Test
    fun `ewma liquidity weighted residualization still produces finite market betas`() = kotlinx.coroutines.runBlocking {
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
                    residualizationMode = InterdayResidualizationMode.MARKET,
                    residualizationBetaMode = InterdayResidualizationBetaMode.EWMA,
                    residualizationMarketProxyMode = InterdayResidualizationMarketProxyMode.LIQUIDITY_WEIGHTED,
                    residualizationHalfLifeDays = 10
                )
            )
        )

        assertTrue(response.selectedSignals.isNotEmpty())
        assertTrue(response.selectedSignals.all { it.marketBeta.isFinite() })
        assertTrue(response.backtest.regimeSlices.any { it.slice == "market_trend" })
    }

    @Test
    fun `bounded funding overlay exposes non unit sizing multipliers`() = kotlinx.coroutines.runBlocking {
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
                    fundingWeight = 0.35,
                    fundingOverlayMode = InterdayFundingOverlayMode.BOUNDED_REINFORCEMENT
                )
            )
        )

        assertTrue(response.selectedSignals.isNotEmpty())
        assertTrue(response.selectedSignals.any { kotlin.math.abs(it.fundingOverlayMultiplier - 1.0) > 1e-6 })
    }

    @Test
    fun `time stop exit overlay triggers after stale low progress hold`() {
        val method = Class.forName("org.datamancy.trading.alpha.InterdaySearchEngineKt")
            .getDeclaredMethod(
                "shouldForceFlattenByExitOverlay",
                Double::class.javaPrimitiveType,
                Instant::class.java,
                Double::class.javaObjectType,
                InterdaySignalSnapshot::class.java,
                InterdayAlphaConfig::class.java,
                Instant::class.java
            )
        method.isAccessible = true

        val entryTime = Instant.parse("2026-01-01T00:00:00Z")
        val currentTime = entryTime.plusSeconds(3L * 24L * 3600L)
        val signal = InterdaySignalSnapshot(
            symbol = "ALPHA",
            direction = AlphaDirection.LONG,
            score = 2.0,
            empiricalScore = 0.0002,
            residualRank = 0.5,
            confidence = 0.7,
            liquidityScore = 1.0,
            trendScore = 0.5,
            trendAgreement = 0.4,
            pullbackScore = 0.2,
            fundingScore = 0.0,
            openInterestScore = 0.0,
            expansionScore = 0.4,
            reversalRiskScore = 0.1,
            marketBeta = 0.3,
            upperBound = 2.0,
            lowerBound = -2.0,
            expectedResidualReturnBps = 4.0,
            expectedEntryCostBps = 1.0,
            expectedTurnoverPenaltyBps = 0.5,
            expectedNetEdgeBps = 2.5,
            close = 100.0,
            predictedVolatility = 0.3
        )

        val triggered = method.invoke(
            null,
            0.08,
            entryTime,
            0.10,
            signal,
            InterdayAlphaConfig(
                signalBarMinutes = 1440,
                exitOverlayMode = InterdayExitOverlayMode.TIME_STOP,
                timeStopBars = 3,
                timeStopMinProgressVol = 0.25
            ),
            currentTime
        ) as Boolean

        assertTrue(triggered)
    }
}

private fun syntheticPanel(): InterdayPanel {
    val start = Instant.parse("2026-01-01T00:00:00Z")
    val points = 180
    val timeline = List(points) { index -> start.plusSeconds(index * 14_400L) }
    val symbols = listOf("ALPHA", "BRAVO", "CHARLIE", "DELTA")
    val series = symbols.map { symbol ->
        InterdaySymbolSeries(
            symbol = symbol,
            bars = timeline.mapIndexed { index, time ->
                val close = when (symbol) {
                    "ALPHA" -> 100.0 * exp(0.009 * index) * (1.0 + 0.02 * sin(index / 3.5)) * alphaPullback(index)
                    "BRAVO" -> 180.0 * exp(-0.007 * index) * (1.0 + 0.02 * cos(index / 4.0)) * bravoBounce(index)
                    "DELTA" -> 70.0 * exp(0.004 * index) * (1.0 + 0.015 * cos(index / 6.0))
                    else -> 90.0 * (1.0 + 0.002 * sin(index / 2.0))
                }
                val open = close * 0.997
                val high = close * 1.012
                val low = close * 0.988
                val volume = when (symbol) {
                    "ALPHA" -> 1_250.0 + index * 6.0
                    "BRAVO" -> 1_050.0 + index * 4.0
                    "DELTA" -> 880.0 + index * 3.0
                    else -> 650.0 + index * 2.0
                }
                val funding = when (symbol) {
                    "ALPHA" -> -0.00008 + index * 0.0000001
                    "BRAVO" -> 0.00011 - index * 0.0000001
                    "DELTA" -> -0.00002 + index * 0.00000005
                    else -> 0.00001
                }
                val openInterest = when (symbol) {
                    "ALPHA" -> 30_000.0 + index * 220.0
                    "BRAVO" -> 55_000.0 - index * 150.0
                    "DELTA" -> 28_000.0 + index * 110.0
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
                        "DELTA" -> 7.5
                        else -> 5.0
                    },
                    depthUsd = when (symbol) {
                        "ALPHA" -> 250_000.0 + index * 1_000.0
                        "BRAVO" -> 220_000.0 + index * 800.0
                        "DELTA" -> 140_000.0 + index * 500.0
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
