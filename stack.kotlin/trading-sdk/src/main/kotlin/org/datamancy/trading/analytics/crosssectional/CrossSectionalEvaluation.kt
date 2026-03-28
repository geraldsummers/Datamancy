package org.datamancy.trading.analytics.crosssectional

import java.time.Duration
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

fun evaluateCrossSectionalResearch(
    context: ResearchDataContext,
    config: ResearchConfig
): CrossSectionalResearchResult =
    evaluateCrossSectionalResearchRows(
        context = context,
        researchFeatureRows = engineerFeatures(context.bars, config),
        config = config
    )

internal fun splitResearchWindow(
    researchFeatureRows: List<FeatureRow>,
    config: ResearchConfig
): ResearchWindowSplit {
    val forwardCutoff = researchFeatureRows.maxOfOrNull { it.time }
        ?.minus(config.forwardHours.toLong(), ChronoUnit.HOURS)
        ?: return ResearchWindowSplit(
            forwardCutoff = null,
            calibrationRows = researchFeatureRows,
            backtestCalibrationRows = emptyList(),
            backtestRows = researchFeatureRows,
            forwardRows = emptyList()
        )
    val calibrationRows = researchFeatureRows.filter { it.time.isBefore(forwardCutoff) }
    if (calibrationRows.isEmpty()) {
        return ResearchWindowSplit(
            forwardCutoff = forwardCutoff,
            calibrationRows = emptyList(),
            backtestCalibrationRows = emptyList(),
            backtestRows = emptyList(),
            forwardRows = researchFeatureRows.filter { !it.time.isBefore(forwardCutoff) }
        )
    }
    val calibrationStart = calibrationRows.minOf { it.time }
    val calibrationHours = max(Duration.between(calibrationStart, forwardCutoff).toHours(), 1L)
    val backtestCalibrationHours = min(
        max(calibrationHours / 2L, 1L),
        max(config.calibrationLookbackHours.toLong(), 1L)
    )
    val backtestCalibrationCutoff = calibrationStart.plus(backtestCalibrationHours, ChronoUnit.HOURS)
    return ResearchWindowSplit(
        forwardCutoff = forwardCutoff,
        calibrationRows = calibrationRows,
        backtestCalibrationRows = calibrationRows.filter { it.time.isBefore(backtestCalibrationCutoff) },
        backtestRows = calibrationRows.filter { !it.time.isBefore(backtestCalibrationCutoff) },
        forwardRows = researchFeatureRows.filter { !it.time.isBefore(forwardCutoff) }
    )
}

internal fun evaluateCrossSectionalResearchRows(
    context: ResearchDataContext,
    researchFeatureRows: List<FeatureRow>,
    config: ResearchConfig
): CrossSectionalResearchResult {
    val researchBars = context.bars
    val diagnostics = computeResearchDiagnostics(researchFeatureRows, config)
    val heuristicSignals = latestSignalSnapshots(researchFeatureRows, config)
    val windowSplit = splitResearchWindow(researchFeatureRows, config)
    val backtestSeedRows = if (windowSplit.backtestRows.isNotEmpty()) {
        windowSplit.backtestCalibrationRows
    } else {
        emptyList()
    }
    val backtestRows = if (windowSplit.backtestRows.isNotEmpty()) {
        windowSplit.backtestRows
    } else {
        windowSplit.calibrationRows
    }

    val trendStrategyName = "cross_section_beta_trend_v1"
    val strategyNamesByKind = mapOf(
        StrategyKind.TREND.name.lowercase() to trendStrategyName
    )
    val backtestCalibrationSeedExamples = buildCalibrationExamples(
        strategyName = trendStrategyName,
        kind = StrategyKind.TREND,
        rows = backtestSeedRows,
        config = config
    )

    val trendBacktest = simulateStrategyWalkForwardResult(
        strategyName = trendStrategyName,
        kind = StrategyKind.TREND,
        rows = backtestRows,
        config = config,
        seedExamples = backtestCalibrationSeedExamples,
        stage = "backtest"
    )
    val backtestSummaries =
        buildStrategySummaries(
            config = config,
            strategyName = trendStrategyName,
            strategyKind = StrategyKind.TREND,
            trades = trendBacktest.trades,
            timeframe = "candle_${config.barMinutes}m",
            notes = "${config.barMinutes}m beta-adjusted cross-sectional trend with causal calibration gating"
        )
    val backtestPortfolioProfiles = mapOf(
        StrategyKind.TREND.name.lowercase() to trendBacktest.portfolioProfile
    )
    val backtestRobustness = mutableMapOf<String, StrategyRobustnessSnapshot>().apply {
        computeStrategyRobustness(StrategyKind.TREND, trendBacktest.trades)?.let {
            put(StrategyKind.TREND.name.lowercase(), it)
        }
    }

    if (config.persistBacktest && backtestSummaries.isNotEmpty()) {
        persistBacktestSummaries(backtestSummaries)
        persistPortfolioProfiles(
            config = config,
            timeframe = "candle_${config.barMinutes}m",
            strategyNames = strategyNamesByKind,
            profiles = backtestPortfolioProfiles
        )
    }
    if ((config.persistBacktest || config.persistForward) && context.universeProfiles.isNotEmpty()) {
        persistUniverseProfiles(
            config = config,
            timeframe = "candle_${config.barMinutes}m",
            strategyNames = strategyNamesByKind.values,
            profiles = context.universeProfiles
        )
    }

    var latestSignals = heuristicSignals
    var forwardSummaries = emptyList<StrategySummary>()
    var calibrationRowsCount = 0
    var forwardRowsCount = 0
    var calibrationCounts = emptyMap<String, Int>()
    var forwardPortfolioProfiles = emptyMap<String, PortfolioProfileSnapshot>()
    var forwardRobustness = emptyMap<String, StrategyRobustnessSnapshot>()

    if (windowSplit.forwardCutoff != null) {
        val calibrationRows = windowSplit.calibrationRows
        val forwardRows = windowSplit.forwardRows
        calibrationRowsCount = calibrationRows.size
        forwardRowsCount = forwardRows.size

        val calibrationTrendExamples = buildCalibrationExamples(
            strategyName = trendStrategyName,
            kind = StrategyKind.TREND,
            rows = calibrationRows,
            config = config
        )
        calibrationCounts = mapOf(
            "trend" to calibrationTrendExamples.size
        )
        val forwardCalibrationState = buildCalibrationState(calibrationTrendExamples)
        val baselineMap = backtestSummaries.associateBy { Triple(it.strategyName, it.exchange, it.symbol) }

        val forwardTrend = simulateStrategyResult(
            strategyName = trendStrategyName,
            kind = StrategyKind.TREND,
            rows = forwardRows,
            config = config,
            calibrationState = forwardCalibrationState,
            stage = "forward"
        )
        val forwardTrendTrades = forwardTrend.trades
        val forwardTrades = forwardTrendTrades
        forwardPortfolioProfiles = mapOf(
            StrategyKind.TREND.name.lowercase() to forwardTrend.portfolioProfile
        )
        forwardSummaries =
            buildStrategySummaries(
                config = config,
                strategyName = trendStrategyName,
                strategyKind = StrategyKind.TREND,
                trades = forwardTrendTrades,
                timeframe = "forward_${config.barMinutes}m",
                notes = "forward ${config.barMinutes}m slice with calibrated promotion gating"
            )
        forwardRobustness = mutableMapOf<String, StrategyRobustnessSnapshot>().apply {
            computeStrategyRobustness(StrategyKind.TREND, forwardTrendTrades)?.let {
                put(StrategyKind.TREND.name.lowercase(), it)
            }
        }

        latestSignals = latestSignalSnapshots(researchFeatureRows, config, forwardCalibrationState)

        if (config.persistForward && forwardTrades.isNotEmpty()) {
            persistForwardTelemetry(
                config = config,
                trades = forwardTrades,
                baselines = baselineMap,
                source = "alpha-analytics-service"
            )
            persistPortfolioProfiles(
                config = config,
                timeframe = "forward_${config.barMinutes}m",
                strategyNames = strategyNamesByKind,
                profiles = forwardPortfolioProfiles
            )
        }
    }

    return CrossSectionalResearchResult(
        config = config,
        exchangeCatalog = context.exchangeCatalog,
        exchangePlans = context.exchangePlans,
        candidateUniverse = context.candidateUniverse,
        discoveredUniverse = context.discoveredUniverse,
        universeProfiles = context.universeProfiles,
        barsLoaded = researchBars.size,
        featureRows = researchFeatureRows.size,
        diagnostics = diagnostics,
        heuristicSignals = heuristicSignals,
        latestSignals = latestSignals,
        backtestSummaries = backtestSummaries,
        forwardSummaries = forwardSummaries,
        forwardCutoff = windowSplit.forwardCutoff,
        calibrationRows = calibrationRowsCount,
        forwardRows = forwardRowsCount,
        calibrationExampleCounts = calibrationCounts,
        backtestPortfolioProfiles = backtestPortfolioProfiles,
        forwardPortfolioProfiles = forwardPortfolioProfiles,
        backtestRobustness = backtestRobustness,
        forwardRobustness = forwardRobustness
    )
}

fun runCrossSectionalResearch(config: ResearchConfig = ResearchConfig()): CrossSectionalResearchResult =
    evaluateCrossSectionalResearch(loadResearchDataContext(config), config)

