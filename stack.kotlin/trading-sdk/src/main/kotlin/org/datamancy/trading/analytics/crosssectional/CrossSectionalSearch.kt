package org.datamancy.trading.analytics.crosssectional

import java.time.Instant
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private data class SearchMutation(
    val name: String,
    val group: String,
    val variants: (ResearchConfig) -> List<ResearchConfig>
)

private data class SearchEvaluation(
    val config: ResearchConfig,
    val result: CrossSectionalResearchResult,
    val trendFitness: StrategySearchFitness,
    val score: Double,
    val evaluatedAt: Instant
)

fun searchSafeConfig(config: ResearchConfig): ResearchConfig =
    config.copy(
        persistBacktest = false,
        persistForward = false,
        enablePaperOrders = false
    )

fun normalizeSearchConfig(searchConfig: CrossSectionalSearchConfig): CrossSectionalSearchConfig =
    searchConfig.copy(
        baseConfig = searchSafeConfig(searchConfig.baseConfig),
        beamWidth = max(1, searchConfig.beamWidth),
        rounds = max(1, searchConfig.rounds),
        maxEvaluations = max(1, searchConfig.maxEvaluations),
        leaderboardSize = max(1, searchConfig.leaderboardSize),
        minBacktestTrades = max(1, searchConfig.minBacktestTrades),
        minForwardTrades = max(1, searchConfig.minForwardTrades),
        minSearchFillRatio = clamp(searchConfig.minSearchFillRatio, 0.0, 1.0),
        maxSearchDrawdownPct = max(0.0, searchConfig.maxSearchDrawdownPct)
    )

fun isValidResearchConfig(config: ResearchConfig): Boolean {
    val minimumHours = minimumResearchLookbackHours(config)
    return config.barMinutes > 0 &&
        config.lookbackHours >= minimumHours &&
        config.forwardHours > 0 &&
        config.betaLookbackBars > 1 &&
        config.trendLookbackBars > 1 &&
        config.trendSlowBars > config.trendLookbackBars &&
        config.trendMediumBars > config.trendSlowBars &&
        config.trendLongBars > config.trendMediumBars &&
        config.trendHoldBars > 0 &&
        config.topPerSide > 0 &&
        config.notionalUsd > 0.0 &&
        (config.maxSymbols == 0 || config.maxSymbols >= 2) &&
        config.discoveryMaxSymbols >= 0 &&
        config.minBars > 0 &&
        config.maxSpreadBps > 0.0 &&
        config.minDepthMultiple > 0.0 &&
        config.minFillRatio in 0.0..1.0 &&
        config.minVolumeRatio >= 0.0 &&
        config.maxVolumeRatio > config.minVolumeRatio &&
        config.maxVolRegime > 0.0 &&
        config.executionSafetyMarginBps >= 0.0 &&
        config.minExpectedNetEdgeBps >= 0.0 &&
        config.calibrationLookbackHours > 0 &&
        config.minCalibrationSamples > 0 &&
        config.strongCalibrationSamples >= config.minCalibrationSamples &&
        config.minCalibrationWinRate in 0.0..1.0 &&
        config.trendCooldownBars >= 0 &&
        config.trendTrailingStopVolMultiple >= 0.0 &&
        config.trendTakeProfitVolMultiple >= 0.0 &&
        config.minTargetExposureFraction > 0.0 &&
        config.maxTargetExposureFraction >= config.minTargetExposureFraction &&
        config.rebalanceTargetExposureStep >= 0.0 &&
        config.maxConcurrentPositions > 0 &&
        config.maxConcurrentLongs in 1..config.maxConcurrentPositions &&
        config.maxConcurrentShorts in 1..config.maxConcurrentPositions &&
        config.maxNetExposureFraction in 0.0..1.0 &&
        config.maxPortfolioBetaBtcAbs >= 0.0 &&
        config.maxPortfolioBetaEthAbs >= 0.0
}

private fun normalizeIntCandidates(
    current: Int,
    values: List<Int>,
    predicate: (Int) -> Boolean = { it > 0 }
): List<Int> =
    (listOf(current) + values)
        .filter(predicate)
        .distinct()
        .sorted()

private fun normalizeDoubleCandidates(
    current: Double,
    values: List<Double>,
    predicate: (Double) -> Boolean = { it > 0.0 }
): List<Double> =
    (listOf(current) + values)
        .filter { it.isFinite() && predicate(it) }
        .map { it.round(6) }
        .distinct()
        .sorted()

private fun <T> rotate(values: List<T>, offset: Int): List<T> {
    if (values.isEmpty()) return values
    val normalized = ((offset % values.size) + values.size) % values.size
    if (normalized == 0) return values
    return values.drop(normalized) + values.take(normalized)
}

private fun intMutation(
    name: String,
    group: String,
    values: List<Int>,
    current: (ResearchConfig) -> Int,
    apply: (ResearchConfig, Int) -> ResearchConfig,
    predicate: (Int) -> Boolean = { it > 0 }
): SearchMutation =
    SearchMutation(name, group) { config ->
        normalizeIntCandidates(current(config), values, predicate)
            .asSequence()
            .filter { it != current(config) }
            .map { apply(config, it) }
            .toList()
    }

private fun doubleMutation(
    name: String,
    group: String,
    values: List<Double>,
    current: (ResearchConfig) -> Double,
    apply: (ResearchConfig, Double) -> ResearchConfig,
    predicate: (Double) -> Boolean = { it > 0.0 }
): SearchMutation =
    SearchMutation(name, group) { config ->
        normalizeDoubleCandidates(current(config), values, predicate)
            .asSequence()
            .filter { abs(it - current(config)) > 1e-9 }
            .map { apply(config, it) }
            .toList()
    }

private fun buildSearchMutations(searchConfig: CrossSectionalSearchConfig): List<SearchMutation> =
    listOf(
        intMutation("barMinutes", "timeframe", searchConfig.barMinutes, { it.barMinutes }, { cfg, value -> cfg.copy(barMinutes = value) }),
        intMutation("lookbackHours", "timeframe", searchConfig.lookbackHours, { it.lookbackHours }, { cfg, value -> cfg.copy(lookbackHours = value) }),
        intMutation("forwardHours", "timeframe", searchConfig.forwardHours, { it.forwardHours }, { cfg, value -> cfg.copy(forwardHours = value) }),
        intMutation("betaLookbackBars", "timeframe", searchConfig.betaLookbackBars, { it.betaLookbackBars }, { cfg, value -> cfg.copy(betaLookbackBars = value) }),
        intMutation("maxSymbols", "universe_breadth", searchConfig.maxSymbols, { it.maxSymbols }, { cfg, value -> cfg.copy(maxSymbols = value) }, predicate = { it == 0 || it >= 2 }),
        intMutation(
            "discoveryMaxSymbols",
            "universe_breadth",
            searchConfig.discoveryMaxSymbols,
            { it.discoveryMaxSymbols },
            { cfg, value -> cfg.copy(discoveryMaxSymbols = value) },
            predicate = { it >= 0 }
        ),
        doubleMutation("trendEntryScore", "trend_signal", searchConfig.trendEntryScore, { it.trendEntryScore }, { cfg, value -> cfg.copy(trendEntryScore = value) }),
        doubleMutation(
            "trendMinFlowAlignment",
            "trend_signal",
            searchConfig.trendMinFlowAlignment,
            { it.trendMinFlowAlignment },
            { cfg, value -> cfg.copy(trendMinFlowAlignment = value) },
            predicate = { it >= 0.0 }
        ),
        intMutation("trendLookbackBars", "trend_signal", searchConfig.trendLookbackBars, { it.trendLookbackBars }, { cfg, value -> cfg.copy(trendLookbackBars = value) }),
        intMutation("trendSlowBars", "trend_signal", searchConfig.trendSlowBars, { it.trendSlowBars }, { cfg, value -> cfg.copy(trendSlowBars = value) }),
        intMutation("trendMediumBars", "trend_signal", searchConfig.trendMediumBars, { it.trendMediumBars }, { cfg, value -> cfg.copy(trendMediumBars = value) }),
        intMutation("trendLongBars", "trend_signal", searchConfig.trendLongBars, { it.trendLongBars }, { cfg, value -> cfg.copy(trendLongBars = value) }),
        intMutation("trendHoldBars", "trend_signal", searchConfig.trendHoldBars, { it.trendHoldBars }, { cfg, value -> cfg.copy(trendHoldBars = value) }),
        intMutation(
            "trendCooldownBars",
            "trend_signal",
            searchConfig.trendCooldownBars,
            { it.trendCooldownBars },
            { cfg, value -> cfg.copy(trendCooldownBars = value) },
            predicate = { it >= 0 }
        ),
        doubleMutation(
            "trendTrailingStopVolMultiple",
            "trend_exit",
            searchConfig.trendTrailingStopVolMultiple,
            { it.trendTrailingStopVolMultiple },
            { cfg, value -> cfg.copy(trendTrailingStopVolMultiple = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation(
            "trendTakeProfitVolMultiple",
            "trend_exit",
            searchConfig.trendTakeProfitVolMultiple,
            { it.trendTakeProfitVolMultiple },
            { cfg, value -> cfg.copy(trendTakeProfitVolMultiple = value) },
            predicate = { it >= 0.0 }
        ),
        intMutation(
            "maxConcurrentPositions",
            "portfolio_policy",
            searchConfig.maxConcurrentPositions,
            { it.maxConcurrentPositions },
            { cfg, value -> cfg.copy(maxConcurrentPositions = value) }
        ),
        intMutation(
            "maxConcurrentLongs",
            "portfolio_policy",
            searchConfig.maxConcurrentLongs,
            { it.maxConcurrentLongs },
            { cfg, value -> cfg.copy(maxConcurrentLongs = value) }
        ),
        intMutation(
            "maxConcurrentShorts",
            "portfolio_policy",
            searchConfig.maxConcurrentShorts,
            { it.maxConcurrentShorts },
            { cfg, value -> cfg.copy(maxConcurrentShorts = value) }
        ),
        doubleMutation(
            "maxNetExposureFraction",
            "portfolio_policy",
            searchConfig.maxNetExposureFraction,
            { it.maxNetExposureFraction },
            { cfg, value -> cfg.copy(maxNetExposureFraction = value) },
            predicate = { it in 0.0..1.0 }
        ),
        doubleMutation(
            "minTargetExposureFraction",
            "portfolio_policy",
            searchConfig.minTargetExposureFraction,
            { it.minTargetExposureFraction },
            { cfg, value -> cfg.copy(minTargetExposureFraction = value) },
            predicate = { it > 0.0 }
        ),
        doubleMutation(
            "maxTargetExposureFraction",
            "portfolio_policy",
            searchConfig.maxTargetExposureFraction,
            { it.maxTargetExposureFraction },
            { cfg, value -> cfg.copy(maxTargetExposureFraction = value) },
            predicate = { it > 0.0 }
        ),
        doubleMutation(
            "rebalanceTargetExposureStep",
            "portfolio_policy",
            searchConfig.rebalanceTargetExposureStep,
            { it.rebalanceTargetExposureStep },
            { cfg, value -> cfg.copy(rebalanceTargetExposureStep = value) },
            predicate = { it >= 0.0 && it <= 1.0 }
        ),
        doubleMutation(
            "maxPortfolioBetaBtcAbs",
            "portfolio_policy",
            searchConfig.maxPortfolioBetaBtcAbs,
            { it.maxPortfolioBetaBtcAbs },
            { cfg, value -> cfg.copy(maxPortfolioBetaBtcAbs = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation(
            "maxPortfolioBetaEthAbs",
            "portfolio_policy",
            searchConfig.maxPortfolioBetaEthAbs,
            { it.maxPortfolioBetaEthAbs },
            { cfg, value -> cfg.copy(maxPortfolioBetaEthAbs = value) },
            predicate = { it >= 0.0 }
        ),
        intMutation("topPerSide", "execution_liquidity", searchConfig.topPerSide, { it.topPerSide }, { cfg, value -> cfg.copy(topPerSide = value) }),
        doubleMutation("maxSpreadBps", "execution_liquidity", searchConfig.maxSpreadBps, { it.maxSpreadBps }, { cfg, value -> cfg.copy(maxSpreadBps = value) }),
        doubleMutation("minDepthMultiple", "execution_liquidity", searchConfig.minDepthMultiple, { it.minDepthMultiple }, { cfg, value -> cfg.copy(minDepthMultiple = value) }),
        doubleMutation(
            "minFillRatio",
            "execution_liquidity",
            searchConfig.minFillRatio,
            { it.minFillRatio },
            { cfg, value -> cfg.copy(minFillRatio = value) },
            predicate = { it in 0.0..1.0 }
        ),
        doubleMutation(
            "minVolumeRatio",
            "execution_liquidity",
            searchConfig.minVolumeRatio,
            { it.minVolumeRatio },
            { cfg, value -> cfg.copy(minVolumeRatio = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation("maxVolumeRatio", "execution_liquidity", searchConfig.maxVolumeRatio, { it.maxVolumeRatio }, { cfg, value -> cfg.copy(maxVolumeRatio = value) }),
        doubleMutation("maxVolRegime", "execution_liquidity", searchConfig.maxVolRegime, { it.maxVolRegime }, { cfg, value -> cfg.copy(maxVolRegime = value) }),
        doubleMutation(
            "executionSafetyMarginBps",
            "execution_liquidity",
            searchConfig.executionSafetyMarginBps,
            { it.executionSafetyMarginBps },
            { cfg, value -> cfg.copy(executionSafetyMarginBps = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation(
            "minExpectedNetEdgeBps",
            "execution_liquidity",
            searchConfig.minExpectedNetEdgeBps,
            { it.minExpectedNetEdgeBps },
            { cfg, value -> cfg.copy(minExpectedNetEdgeBps = value) },
            predicate = { it >= 0.0 }
        ),
        intMutation("calibrationLookbackHours", "calibration", searchConfig.calibrationLookbackHours, { it.calibrationLookbackHours }, { cfg, value -> cfg.copy(calibrationLookbackHours = value) }),
        intMutation("minCalibrationSamples", "calibration", searchConfig.minCalibrationSamples, { it.minCalibrationSamples }, { cfg, value -> cfg.copy(minCalibrationSamples = value) }),
        intMutation("strongCalibrationSamples", "calibration", searchConfig.strongCalibrationSamples, { it.strongCalibrationSamples }, { cfg, value -> cfg.copy(strongCalibrationSamples = value) }),
        doubleMutation(
            "minCalibrationLowerBoundBps",
            "calibration",
            searchConfig.minCalibrationLowerBoundBps,
            { it.minCalibrationLowerBoundBps },
            { cfg, value -> cfg.copy(minCalibrationLowerBoundBps = value) },
            predicate = { it >= 0.0 }
        ),
        doubleMutation(
            "minCalibrationWinRate",
            "calibration",
            searchConfig.minCalibrationWinRate,
            { it.minCalibrationWinRate },
            { cfg, value -> cfg.copy(minCalibrationWinRate = value) },
            predicate = { it in 0.0..1.0 }
        )
    )

fun aggregateStrategySnapshot(
    summaries: List<StrategySummary>,
    kind: StrategyKind
): StrategyAggregateSnapshot? {
    val relevant = summaries.filter {
        it.strategyKind == kind.name.lowercase() &&
            it.symbol == "ALL"
    }
    if (relevant.isEmpty()) return null
    val totalTrades = relevant.sumOf { it.trades }
    if (totalTrades <= 0) return null

    fun weighted(selector: (StrategySummary) -> Double): Double =
        relevant.sumOf { selector(it) * it.trades.toDouble() } / totalTrades.toDouble()

    return StrategyAggregateSnapshot(
        exchanges = relevant.map { it.exchange }.distinct().sorted(),
        trades = totalTrades,
        winRate = weighted { it.winRate }.round(4),
        netReturnPct = weighted { it.netReturnPct }.round(4),
        maxDrawdownPct = (relevant.maxOfOrNull { it.maxDrawdownPct } ?: 0.0).round(4),
        sharpe = weighted { it.sharpe }.round(4),
        avgEdgeAfterCostBps = weighted { it.avgEdgeAfterCostBps }.round(4),
        avgTotalCostBps = weighted { it.avgTotalCostBps }.round(4),
        avgFillRatio = weighted { it.avgFillRatio }.round(4),
        avgSubmitToFillMs = weighted { it.avgSubmitToFillMs }.round(4)
    )
}

fun computeStrategySearchFitness(
    searchConfig: CrossSectionalSearchConfig,
    kind: StrategyKind,
    backtest: StrategyAggregateSnapshot?,
    forward: StrategyAggregateSnapshot?,
    backtestRobustness: StrategyRobustnessSnapshot? = null,
    forwardRobustness: StrategyRobustnessSnapshot? = null
): StrategySearchFitness {
    val rejectionReasons = mutableListOf<String>()
    val backtestTrades = backtest?.trades ?: 0
    val forwardTrades = forward?.trades ?: 0
    val realizedFill = forward?.avgFillRatio ?: backtest?.avgFillRatio ?: 0.0
    val realizedDrawdown = max(backtest?.maxDrawdownPct ?: 0.0, forward?.maxDrawdownPct ?: 0.0)
    val realizedEdge = forward?.avgEdgeAfterCostBps ?: backtest?.avgEdgeAfterCostBps ?: 0.0
    val realizedReturn = forward?.netReturnPct ?: backtest?.netReturnPct ?: 0.0
    val primaryRobustness = backtestRobustness ?: forwardRobustness
    val profitableSymbolShare = primaryRobustness?.profitableSymbolShare ?: 1.0
    val profitableRegimeShare = primaryRobustness?.profitableRegimeShare ?: 1.0
    val largestSymbolTradeShare = primaryRobustness?.largestSymbolTradeShare ?: 0.0
    val largestRegimeTradeShare = primaryRobustness?.largestRegimeTradeShare ?: 0.0
    val worstSymbolEdgeAfterCostBps = listOfNotNull(
        backtestRobustness?.worstSymbolEdgeAfterCostBps,
        forwardRobustness?.worstSymbolEdgeAfterCostBps
    ).minOrNull() ?: 0.0
    val worstRegimeEdgeAfterCostBps = listOfNotNull(
        backtestRobustness?.worstRegimeEdgeAfterCostBps,
        forwardRobustness?.worstRegimeEdgeAfterCostBps
    ).minOrNull() ?: 0.0
    val robustnessScore = listOfNotNull(
        backtestRobustness?.stabilityScore,
        forwardRobustness?.stabilityScore
    ).takeIf { it.isNotEmpty() }
        ?.average()
        ?.round(4)
        ?: 0.0

    if (backtest == null) rejectionReasons += "missing_backtest"
    if (forward == null) rejectionReasons += "missing_forward"
    if (backtestTrades < searchConfig.minBacktestTrades) rejectionReasons += "backtest_trades<${
        searchConfig.minBacktestTrades
    }"
    if (forwardTrades < searchConfig.minForwardTrades) rejectionReasons += "forward_trades<${
        searchConfig.minForwardTrades
    }"
    if (realizedFill < searchConfig.minSearchFillRatio) rejectionReasons += "fill_ratio<${searchConfig.minSearchFillRatio.round(3)}"
    if (realizedDrawdown > searchConfig.maxSearchDrawdownPct) {
        rejectionReasons += "drawdown>${searchConfig.maxSearchDrawdownPct.round(2)}"
    }
    if (realizedEdge <= 0.0) rejectionReasons += "non_positive_edge"
    if (realizedReturn <= 0.0) rejectionReasons += "non_positive_return"
    if ((backtestRobustness?.symbolCount ?: 0) >= 2 && largestSymbolTradeShare > 0.85) {
        rejectionReasons += "symbol_concentration>${0.85.round(2)}"
    }
    if ((backtestRobustness?.regimeCount ?: 0) >= 2 && largestRegimeTradeShare > 0.9) {
        rejectionReasons += "regime_concentration>${0.9.round(2)}"
    }
    if ((backtestRobustness?.symbolCount ?: 0) >= 3 && profitableSymbolShare < 0.34) {
        rejectionReasons += "symbol_fragile"
    }
    if ((backtestRobustness?.regimeCount ?: 0) >= 3 && profitableRegimeShare < 0.34) {
        rejectionReasons += "regime_fragile"
    }

    val backtestReturnScore = (backtest?.netReturnPct ?: 0.0) * 1.8
    val forwardReturnScore = (forward?.netReturnPct ?: 0.0) * 3.4
    val backtestEdgeScore = (backtest?.avgEdgeAfterCostBps ?: 0.0) * 0.45
    val forwardEdgeScore = (forward?.avgEdgeAfterCostBps ?: 0.0) * 0.85
    val backtestSharpeScore = (backtest?.sharpe ?: 0.0) * 2.5
    val forwardSharpeScore = (forward?.sharpe ?: 0.0) * 5.0
    val tradeSupportScore =
        (min(backtestTrades.toDouble(), searchConfig.minBacktestTrades.toDouble()) / searchConfig.minBacktestTrades.toDouble()) * 10.0 +
            (min(forwardTrades.toDouble(), searchConfig.minForwardTrades.toDouble()) / searchConfig.minForwardTrades.toDouble()) * 12.0
    val costPenalty =
        ((backtest?.avgTotalCostBps ?: 0.0) * 0.08) +
            ((forward?.avgTotalCostBps ?: 0.0) * 0.16)
    val drawdownPenalty =
        ((backtest?.maxDrawdownPct ?: 0.0) * 0.45) +
            ((forward?.maxDrawdownPct ?: 0.0) * 1.1)
    val fillPenalty = max(0.0, searchConfig.minSearchFillRatio - realizedFill) * 45.0
    val emptyTradePenalty = when {
        backtestTrades + forwardTrades == 0 -> 180.0
        backtestTrades == 0 || forwardTrades == 0 -> 90.0
        else -> 0.0
    }
    val driftPenalty = max(
        0.0,
        (backtest?.avgEdgeAfterCostBps ?: 0.0) - (forward?.avgEdgeAfterCostBps ?: backtest?.avgEdgeAfterCostBps ?: 0.0)
    ) * 0.4
    val robustnessBonus =
        ((backtestRobustness?.stabilityScore ?: 0.0) * 0.12) +
            ((forwardRobustness?.stabilityScore ?: 0.0) * 0.16)
    val concentrationPenalty =
        (max(0.0, largestSymbolTradeShare - 0.55) * 28.0) +
            (max(0.0, largestRegimeTradeShare - 0.65) * 18.0)
    val fragilityPenalty =
        (max(0.0, 0.55 - profitableSymbolShare) * 20.0) +
            (max(0.0, 0.55 - profitableRegimeShare) * 16.0)
    val worstSlicePenalty =
        (max(0.0, -worstSymbolEdgeAfterCostBps) * 0.8) +
            (max(0.0, -worstRegimeEdgeAfterCostBps) * 0.9)
    val missingPenalty =
        (if (backtest == null) 20.0 else 0.0) +
            (if (forward == null) 24.0 else 0.0)
    val rejectionPenalty = rejectionReasons.size.toDouble() * 9.0
    val score = (
        backtestReturnScore +
            forwardReturnScore +
            backtestEdgeScore +
            forwardEdgeScore +
            backtestSharpeScore +
            forwardSharpeScore +
            tradeSupportScore -
            costPenalty -
            drawdownPenalty -
            fillPenalty -
            emptyTradePenalty -
            driftPenalty -
            concentrationPenalty -
            fragilityPenalty -
            worstSlicePenalty -
            missingPenalty -
            rejectionPenalty +
            robustnessBonus
        ).round(4)

    return StrategySearchFitness(
        strategyKind = kind.name.lowercase(),
        score = score,
        passesFilters = rejectionReasons.isEmpty(),
        rejectionReasons = rejectionReasons,
        backtest = backtest,
        forward = forward,
        robustnessScore = robustnessScore,
        backtestRobustness = backtestRobustness,
        forwardRobustness = forwardRobustness
    )
}

fun researchConfigFingerprint(config: ResearchConfig): String =
    listOf(
        config.txGatewayUrl,
        config.marketExchange,
        config.executionExchangeOverride,
        config.barMinutes,
        config.lookbackHours,
        config.forwardHours,
        config.betaLookbackBars,
        config.trendLookbackBars,
        config.trendSlowBars,
        config.trendMediumBars,
        config.trendLongBars,
        config.reversionLookbackBars,
        config.trendHoldBars,
        config.reversionHoldBars,
        config.topPerSide,
        config.notionalUsd.round(4),
        config.maxSymbols,
        config.discoveryMaxSymbols,
        config.minBars,
        config.trendEntryScore.round(6),
        config.reversionZEntry.round(6),
        config.reversionZExit.round(6),
        config.reversionEntryQuantile.round(6),
        config.reversionExitQuantile.round(6),
        config.reversionCrossSectionalWeight.round(6),
        config.maxSpreadBps.round(6),
        config.minDepthMultiple.round(6),
        config.minFillRatio.round(6),
        config.minVolumeRatio.round(6),
        config.maxVolumeRatio.round(6),
        config.maxVolRegime.round(6),
        config.executionSafetyMarginBps.round(6),
        config.minExpectedNetEdgeBps.round(6),
        config.trendMinFlowAlignment.round(6),
        config.reversionMaxContinuationPressure.round(6),
        config.calibrationLookbackHours,
        config.minCalibrationSamples,
        config.strongCalibrationSamples,
        config.minCalibrationLowerBoundBps.round(6),
        config.minCalibrationWinRate.round(6),
        config.trendCooldownBars,
        config.reversionCooldownBars,
        config.trendTrailingStopVolMultiple.round(6),
        config.reversionTrailingStopVolMultiple.round(6),
        config.trendTakeProfitVolMultiple.round(6),
        config.reversionTakeProfitVolMultiple.round(6),
        config.minTargetExposureFraction.round(6),
        config.maxTargetExposureFraction.round(6),
        config.rebalanceTargetExposureStep.round(6),
        config.maxConcurrentPositions,
        config.maxConcurrentLongs,
        config.maxConcurrentShorts,
        config.maxNetExposureFraction.round(6),
        config.maxPortfolioBetaBtcAbs.round(6),
        config.maxPortfolioBetaEthAbs.round(6),
        contextNames(signalFeatureContexts(config)).joinToString(","),
        contextNames(executionFeatureContexts(config)).joinToString(","),
        contextNames(promotionFeatureContexts(config)).joinToString(",")
    ).joinToString("|")

private fun buildSearchEvaluation(
    searchConfig: CrossSectionalSearchConfig,
    result: CrossSectionalResearchResult,
    evaluatedAt: Instant = Instant.now()
): SearchEvaluation {
    val trendBacktest = aggregateStrategySnapshot(result.backtestSummaries, StrategyKind.TREND)
    val trendForward = aggregateStrategySnapshot(result.forwardSummaries, StrategyKind.TREND)
    val trendFitness = computeStrategySearchFitness(
        searchConfig = searchConfig,
        kind = StrategyKind.TREND,
        backtest = trendBacktest,
        forward = trendForward,
        backtestRobustness = result.backtestRobustness[StrategyKind.TREND.name.lowercase()],
        forwardRobustness = result.forwardRobustness[StrategyKind.TREND.name.lowercase()]
    )
    return SearchEvaluation(
        config = result.config,
        result = result,
        trendFitness = trendFitness,
        score = trendFitness.score.round(4),
        evaluatedAt = evaluatedAt
    )
}

private fun toSearchCandidate(
    evaluation: SearchEvaluation,
    rank: Int
): CrossSectionalSearchCandidate =
    CrossSectionalSearchCandidate(
        rank = rank,
        score = evaluation.score.round(4),
        config = evaluation.config,
        dataKey = researchDataKey(evaluation.config),
        evaluatedAt = evaluation.evaluatedAt,
        barsLoaded = evaluation.result.barsLoaded,
        featureRows = evaluation.result.featureRows,
        calibrationRows = evaluation.result.calibrationRows,
        forwardRows = evaluation.result.forwardRows,
        trendHoldHours = ((evaluation.config.barMinutes.toDouble() * evaluation.config.trendHoldBars.toDouble()) / 60.0).round(4),
        trendFitness = evaluation.trendFitness
    )

private fun rankTrendEvaluations(evaluations: List<SearchEvaluation>): List<SearchEvaluation> =
    evaluations.sortedWith(
        compareByDescending<SearchEvaluation> { if (it.trendFitness.passesFilters) 1 else 0 }
            .thenByDescending { it.trendFitness.score }
            .thenByDescending { it.score }
            .thenByDescending { it.result.forwardRows }
            .thenByDescending { it.result.featureRows }
    )

private fun nextSearchSeeds(
    evaluations: List<SearchEvaluation>,
    searchConfig: CrossSectionalSearchConfig
): List<ResearchConfig> {
    val desiredSeeds = max(searchConfig.beamWidth * 2, searchConfig.beamWidth)
    return rankTrendEvaluations(evaluations)
        .take(max(searchConfig.beamWidth, desiredSeeds))
        .map { it.config }
        .distinctBy(::researchConfigFingerprint)
        .take(desiredSeeds)
}

private fun roundEvaluationBudget(
    searchConfig: CrossSectionalSearchConfig,
    evaluatedConfigs: Int,
    roundsCompleted: Int
): Int {
    val remainingBudget = max(searchConfig.maxEvaluations - evaluatedConfigs, 0)
    if (remainingBudget <= 0) return 0
    val remainingRounds = max(searchConfig.rounds - roundsCompleted, 1)
    val evenSplit = ceil(remainingBudget.toDouble() / remainingRounds.toDouble()).toInt()
    return min(remainingBudget, max(searchConfig.beamWidth, evenSplit))
}

private data class SearchMutationCandidate(
    val seedFingerprint: String,
    val mutationName: String,
    val mutationGroup: String,
    val config: ResearchConfig
)

private fun selectIntSearchValue(
    current: Int,
    values: List<Int>,
    preferred: List<Int> = emptyList(),
    predicate: (Int) -> Boolean = { true }
): Int {
    val candidates = values.filter(predicate).distinct()
    return (preferred + current).firstOrNull { candidate -> candidate in candidates }
        ?: candidates.firstOrNull()
        ?: current
}

private fun selectDoubleSearchValue(
    current: Double,
    values: List<Double>,
    preferred: List<Double> = emptyList(),
    predicate: (Double) -> Boolean = { true }
): Double {
    val candidates = values.filter(predicate).distinct()
    return (preferred + current).firstOrNull { candidate ->
        candidates.any { abs(it - candidate) < 1e-9 }
    }
        ?: candidates.firstOrNull()
        ?: current
}

private fun prioritizedSeedBarMinutes(searchConfig: CrossSectionalSearchConfig): List<Int> {
    val barMinutes = searchConfig.barMinutes.distinct()
    val nonBase = barMinutes.filter { it != searchConfig.baseConfig.barMinutes }
    val shortHorizons = listOf(5, 15, 30).filter { it in nonBase }
    val fallbackHorizons = nonBase
        .filter { it !in shortHorizons }
        .sortedBy { abs(it - searchConfig.baseConfig.barMinutes) }
    return shortHorizons + fallbackHorizons
}

private fun buildSeedAnchorConfig(
    searchConfig: CrossSectionalSearchConfig,
    barMinutes: Int
): ResearchConfig {
    val base = searchConfig.baseConfig
    val sharedLookbackPreferences = when {
        barMinutes <= 5 -> listOf(240, 360, 720)
        barMinutes <= 30 -> listOf(360, 720, 1080)
        else -> listOf(720, 1080, 1440)
    }
    val sharedForwardPreferences = when {
        barMinutes <= 30 -> listOf(24, 48, 72)
        else -> listOf(48, 72, 96)
    }
    val sharedBetaPreferences = when {
        barMinutes <= 30 -> listOf(48, 72, 96)
        else -> listOf(72, 96, 168)
    }
    return base.copy(
        barMinutes = barMinutes,
        lookbackHours = selectIntSearchValue(base.lookbackHours, searchConfig.lookbackHours, sharedLookbackPreferences),
        forwardHours = selectIntSearchValue(base.forwardHours, searchConfig.forwardHours, sharedForwardPreferences),
        betaLookbackBars = selectIntSearchValue(base.betaLookbackBars, searchConfig.betaLookbackBars, sharedBetaPreferences),
        maxSymbols = selectIntSearchValue(base.maxSymbols, searchConfig.maxSymbols, listOf(searchConfig.maxSymbols.maxOrNull() ?: base.maxSymbols)),
        discoveryMaxSymbols = selectIntSearchValue(
            base.discoveryMaxSymbols,
            searchConfig.discoveryMaxSymbols,
            listOf(searchConfig.discoveryMaxSymbols.maxOrNull() ?: base.discoveryMaxSymbols),
            predicate = { it >= 0 }
        ),
        topPerSide = selectIntSearchValue(base.topPerSide, searchConfig.topPerSide, listOf(searchConfig.topPerSide.maxOrNull() ?: base.topPerSide)),
        maxConcurrentPositions = selectIntSearchValue(
            base.maxConcurrentPositions,
            searchConfig.maxConcurrentPositions,
            listOf(searchConfig.maxConcurrentPositions.maxOrNull() ?: base.maxConcurrentPositions)
        ),
        maxConcurrentLongs = selectIntSearchValue(
            base.maxConcurrentLongs,
            searchConfig.maxConcurrentLongs,
            listOf(searchConfig.maxConcurrentLongs.maxOrNull() ?: base.maxConcurrentLongs)
        ),
        maxConcurrentShorts = selectIntSearchValue(
            base.maxConcurrentShorts,
            searchConfig.maxConcurrentShorts,
            listOf(searchConfig.maxConcurrentShorts.maxOrNull() ?: base.maxConcurrentShorts)
        ),
        minTargetExposureFraction = selectDoubleSearchValue(
            base.minTargetExposureFraction,
            searchConfig.minTargetExposureFraction,
            listOf(searchConfig.minTargetExposureFraction.minOrNull() ?: base.minTargetExposureFraction),
            predicate = { it > 0.0 }
        ),
        maxTargetExposureFraction = selectDoubleSearchValue(
            base.maxTargetExposureFraction,
            searchConfig.maxTargetExposureFraction,
            listOf(searchConfig.maxTargetExposureFraction.maxOrNull() ?: base.maxTargetExposureFraction),
            predicate = { it > 0.0 }
        ),
        rebalanceTargetExposureStep = selectDoubleSearchValue(
            base.rebalanceTargetExposureStep,
            searchConfig.rebalanceTargetExposureStep,
            listOf(searchConfig.rebalanceTargetExposureStep.minOrNull() ?: base.rebalanceTargetExposureStep),
            predicate = { it >= 0.0 && it <= 1.0 }
        )
    )
}

private fun buildTrendSearchSeed(
    searchConfig: CrossSectionalSearchConfig,
    barMinutes: Int
): ResearchConfig {
    val anchor = buildSeedAnchorConfig(searchConfig, barMinutes)
    val trendLookbackBars = selectIntSearchValue(
        anchor.trendLookbackBars,
        searchConfig.trendLookbackBars,
        preferred = when {
            barMinutes <= 5 -> listOf(4, 6, 12)
            barMinutes <= 30 -> listOf(6, 12, 18)
            else -> listOf(12, 18, 24)
        }
    )
    val trendSlowBars = selectIntSearchValue(
        anchor.trendSlowBars,
        searchConfig.trendSlowBars,
        preferred = when {
            barMinutes <= 5 -> listOf(12, 18, 24)
            barMinutes <= 30 -> listOf(18, 24, 36)
            else -> listOf(24, 36, 48)
        },
        predicate = { it > trendLookbackBars }
    )
    val trendMediumBars = selectIntSearchValue(
        anchor.trendMediumBars,
        searchConfig.trendMediumBars,
        preferred = when {
            barMinutes <= 5 -> listOf(24, 36, 48)
            barMinutes <= 30 -> listOf(48, 72, 96)
            else -> listOf(72, 96, 144)
        },
        predicate = { it > trendSlowBars }
    )
    val trendLongBars = selectIntSearchValue(
        anchor.trendLongBars,
        searchConfig.trendLongBars,
        preferred = when {
            barMinutes <= 5 -> listOf(48, 72, 96)
            barMinutes <= 30 -> listOf(96, 144, 192)
            else -> listOf(144, 192, 288)
        },
        predicate = { it > trendMediumBars }
    )
    return anchor.copy(
        trendLookbackBars = trendLookbackBars,
        trendSlowBars = trendSlowBars,
        trendMediumBars = trendMediumBars,
        trendLongBars = trendLongBars,
        trendHoldBars = selectIntSearchValue(
            anchor.trendHoldBars,
            searchConfig.trendHoldBars,
            preferred = if (barMinutes <= 30) listOf(1, 2, 3) else listOf(2, 3, 4)
        ),
        trendEntryScore = selectDoubleSearchValue(
            anchor.trendEntryScore,
            searchConfig.trendEntryScore,
            preferred = listOf(0.8, 1.0, 1.2),
            predicate = { it > 0.0 }
        ),
        trendMinFlowAlignment = selectDoubleSearchValue(
            anchor.trendMinFlowAlignment,
            searchConfig.trendMinFlowAlignment,
            preferred = listOf(0.0, 0.05, 0.08),
            predicate = { it >= 0.0 }
        ),
        trendTakeProfitVolMultiple = selectDoubleSearchValue(
            anchor.trendTakeProfitVolMultiple,
            searchConfig.trendTakeProfitVolMultiple,
            preferred = listOf(2.0, 1.5, 3.0),
            predicate = { it > 0.0 }
        ),
        trendTrailingStopVolMultiple = selectDoubleSearchValue(
            anchor.trendTrailingStopVolMultiple,
            searchConfig.trendTrailingStopVolMultiple,
            preferred = listOf(0.0, 0.75, 1.0),
            predicate = { it >= 0.0 }
        )
    )
}

private fun buildReversionSearchSeed(
    searchConfig: CrossSectionalSearchConfig,
    barMinutes: Int
): ResearchConfig {
    val anchor = buildSeedAnchorConfig(searchConfig, barMinutes)
    val trendMediumBars = selectIntSearchValue(
        anchor.trendMediumBars,
        searchConfig.trendMediumBars,
        preferred = when {
            barMinutes <= 5 -> listOf(24, 36, 48)
            barMinutes <= 30 -> listOf(48, 72, 96)
            else -> listOf(72, 96, 144)
        },
        predicate = { it > anchor.trendSlowBars }
    )
    val trendLongBars = selectIntSearchValue(
        anchor.trendLongBars,
        searchConfig.trendLongBars,
        preferred = when {
            barMinutes <= 5 -> listOf(48, 72, 96)
            barMinutes <= 30 -> listOf(96, 144, 192)
            else -> listOf(144, 192, 288)
        },
        predicate = { it > trendMediumBars }
    )
    return anchor.copy(
        trendMediumBars = trendMediumBars,
        trendLongBars = trendLongBars,
        reversionLookbackBars = selectIntSearchValue(
            anchor.reversionLookbackBars,
            searchConfig.reversionLookbackBars,
            preferred = when {
                barMinutes <= 5 -> listOf(3, 4, 8)
                barMinutes <= 30 -> listOf(4, 8, 12)
                else -> listOf(8, 12, 16)
            }
        ),
        reversionHoldBars = selectIntSearchValue(
            anchor.reversionHoldBars,
            searchConfig.reversionHoldBars,
            preferred = if (barMinutes <= 30) listOf(1, 2, 3) else listOf(2, 3, 4)
        ),
        reversionZEntry = selectDoubleSearchValue(
            anchor.reversionZEntry,
            searchConfig.reversionZEntry,
            preferred = searchConfig.reversionZEntry.sorted(),
            predicate = { it > 0.0 }
        ),
        reversionZExit = selectDoubleSearchValue(
            anchor.reversionZExit,
            searchConfig.reversionZExit,
            preferred = searchConfig.reversionZExit.sorted(),
            predicate = { it >= 0.0 }
        ),
        reversionEntryQuantile = selectDoubleSearchValue(
            anchor.reversionEntryQuantile,
            searchConfig.reversionEntryQuantile,
            preferred = searchConfig.reversionEntryQuantile.sorted(),
            predicate = { it in 0.0..0.5 }
        ),
        reversionExitQuantile = selectDoubleSearchValue(
            anchor.reversionExitQuantile,
            searchConfig.reversionExitQuantile,
            preferred = searchConfig.reversionExitQuantile.sortedDescending(),
            predicate = { it in 0.0..0.5 }
        ),
        reversionCrossSectionalWeight = selectDoubleSearchValue(
            anchor.reversionCrossSectionalWeight,
            searchConfig.reversionCrossSectionalWeight,
            preferred = searchConfig.reversionCrossSectionalWeight.sorted(),
            predicate = { it in 0.0..1.0 }
        ),
        reversionMaxContinuationPressure = selectDoubleSearchValue(
            anchor.reversionMaxContinuationPressure,
            searchConfig.reversionMaxContinuationPressure,
            preferred = searchConfig.reversionMaxContinuationPressure.sorted(),
            predicate = { it >= 0.0 }
        ),
        reversionTrailingStopVolMultiple = selectDoubleSearchValue(
            anchor.reversionTrailingStopVolMultiple,
            searchConfig.reversionTrailingStopVolMultiple,
            preferred = listOf(0.75, 1.0, 1.5),
            predicate = { it > 0.0 }
        ),
        reversionTakeProfitVolMultiple = selectDoubleSearchValue(
            anchor.reversionTakeProfitVolMultiple,
            searchConfig.reversionTakeProfitVolMultiple,
            preferred = listOf(0.0, 1.0, 1.5),
            predicate = { it >= 0.0 }
        )
    )
}

private fun prioritizedMediumSeedBarMinutes(searchConfig: CrossSectionalSearchConfig): List<Int> {
    val barMinutes = searchConfig.barMinutes.distinct().filter { it >= 30 }
    val preferred = listOf(30, 60, 120, 240)
    return preferred.filter { it in barMinutes } + barMinutes.filter { it !in preferred }
}

private fun buildMediumTrendSearchSeed(
    searchConfig: CrossSectionalSearchConfig,
    barMinutes: Int
): ResearchConfig {
    val anchor = buildSeedAnchorConfig(searchConfig, barMinutes)
    val trendLookbackBars = selectIntSearchValue(
        anchor.trendLookbackBars,
        searchConfig.trendLookbackBars,
        preferred = when {
            barMinutes <= 30 -> listOf(12, 18, 24)
            barMinutes <= 120 -> listOf(18, 24, 36)
            else -> listOf(24, 36, 48)
        }
    )
    val trendSlowBars = selectIntSearchValue(
        anchor.trendSlowBars,
        searchConfig.trendSlowBars,
        preferred = when {
            barMinutes <= 30 -> listOf(72, 96, 144)
            barMinutes <= 120 -> listOf(96, 144, 192)
            else -> listOf(144, 192, 288)
        },
        predicate = { it > trendLookbackBars }
    )
    val trendMediumBars = selectIntSearchValue(
        anchor.trendMediumBars,
        searchConfig.trendMediumBars,
        preferred = when {
            barMinutes <= 30 -> listOf(144, 192, 288)
            barMinutes <= 120 -> listOf(192, 288, 384)
            else -> listOf(288, 384, 576)
        },
        predicate = { it > trendSlowBars }
    )
    val trendLongBars = selectIntSearchValue(
        anchor.trendLongBars,
        searchConfig.trendLongBars,
        preferred = when {
            barMinutes <= 30 -> listOf(288, 384, 576)
            barMinutes <= 120 -> listOf(384, 576, 768)
            else -> listOf(576, 768, 1152)
        },
        predicate = { it > trendMediumBars }
    )
    return anchor.copy(
        lookbackHours = selectIntSearchValue(anchor.lookbackHours, searchConfig.lookbackHours, listOf(720, 1080, 1440)),
        forwardHours = selectIntSearchValue(anchor.forwardHours, searchConfig.forwardHours, listOf(48, 72, 96)),
        betaLookbackBars = selectIntSearchValue(anchor.betaLookbackBars, searchConfig.betaLookbackBars, listOf(72, 96, 168)),
        trendLookbackBars = trendLookbackBars,
        trendSlowBars = trendSlowBars,
        trendMediumBars = trendMediumBars,
        trendLongBars = trendLongBars,
        trendHoldBars = selectIntSearchValue(
            anchor.trendHoldBars,
            searchConfig.trendHoldBars,
            when {
                barMinutes <= 30 -> listOf(48, 72, 96, 144)
                barMinutes <= 120 -> listOf(24, 36, 48, 72)
                else -> listOf(12, 18, 24, 36)
            }
        ),
        topPerSide = selectIntSearchValue(anchor.topPerSide, searchConfig.topPerSide, listOf(4, 6, 10)),
        trendEntryScore = selectDoubleSearchValue(anchor.trendEntryScore, searchConfig.trendEntryScore, listOf(0.8, 1.0, 1.2), predicate = { it > 0.0 }),
        trendMinFlowAlignment = selectDoubleSearchValue(anchor.trendMinFlowAlignment, searchConfig.trendMinFlowAlignment, listOf(0.0, 0.05, 0.08), predicate = { it >= 0.0 }),
        trendTrailingStopVolMultiple = selectDoubleSearchValue(anchor.trendTrailingStopVolMultiple, searchConfig.trendTrailingStopVolMultiple, listOf(0.75, 1.0, 1.5), predicate = { it >= 0.0 }),
        trendTakeProfitVolMultiple = selectDoubleSearchValue(anchor.trendTakeProfitVolMultiple, searchConfig.trendTakeProfitVolMultiple, listOf(0.0, 1.5, 2.0, 3.0), predicate = { it >= 0.0 })
    )
}

private fun buildMediumReversionSearchSeed(
    searchConfig: CrossSectionalSearchConfig,
    barMinutes: Int
): ResearchConfig {
    val anchor = buildSeedAnchorConfig(searchConfig, barMinutes)
    return anchor.copy(
        lookbackHours = selectIntSearchValue(anchor.lookbackHours, searchConfig.lookbackHours, listOf(720, 1080, 1440)),
        forwardHours = selectIntSearchValue(anchor.forwardHours, searchConfig.forwardHours, listOf(24, 48, 72)),
        betaLookbackBars = selectIntSearchValue(anchor.betaLookbackBars, searchConfig.betaLookbackBars, listOf(72, 96, 168)),
        reversionLookbackBars = selectIntSearchValue(
            anchor.reversionLookbackBars,
            searchConfig.reversionLookbackBars,
            preferred = when {
                barMinutes <= 30 -> listOf(4, 8, 12)
                barMinutes <= 120 -> listOf(8, 12, 16)
                else -> listOf(12, 16, 24)
            }
        ),
        reversionHoldBars = selectIntSearchValue(anchor.reversionHoldBars, searchConfig.reversionHoldBars, listOf(1, 2, 3, 4)),
        topPerSide = selectIntSearchValue(anchor.topPerSide, searchConfig.topPerSide, listOf(4, 6, 10)),
        reversionZEntry = selectDoubleSearchValue(anchor.reversionZEntry, searchConfig.reversionZEntry, listOf(1.0, 1.5, 1.8), predicate = { it > 0.0 }),
        reversionZExit = selectDoubleSearchValue(anchor.reversionZExit, searchConfig.reversionZExit, listOf(0.1, 0.2, 0.45), predicate = { it >= 0.0 }),
        reversionEntryQuantile = selectDoubleSearchValue(anchor.reversionEntryQuantile, searchConfig.reversionEntryQuantile, listOf(0.08, 0.12, 0.18), predicate = { it in 0.0..0.5 }),
        reversionExitQuantile = selectDoubleSearchValue(anchor.reversionExitQuantile, searchConfig.reversionExitQuantile, listOf(0.25, 0.35, 0.45), predicate = { it in 0.0..0.5 }),
        reversionCrossSectionalWeight = selectDoubleSearchValue(anchor.reversionCrossSectionalWeight, searchConfig.reversionCrossSectionalWeight, listOf(0.25, 0.4, 0.55), predicate = { it in 0.0..1.0 }),
        reversionMaxContinuationPressure = selectDoubleSearchValue(
            anchor.reversionMaxContinuationPressure,
            searchConfig.reversionMaxContinuationPressure,
            listOf(0.18, 0.24, 0.32),
            predicate = { it >= 0.0 }
        ),
        reversionTrailingStopVolMultiple = selectDoubleSearchValue(anchor.reversionTrailingStopVolMultiple, searchConfig.reversionTrailingStopVolMultiple, listOf(0.0, 0.75, 1.0, 1.5), predicate = { it >= 0.0 }),
        reversionTakeProfitVolMultiple = selectDoubleSearchValue(anchor.reversionTakeProfitVolMultiple, searchConfig.reversionTakeProfitVolMultiple, listOf(0.0, 0.5, 1.0, 1.5), predicate = { it >= 0.0 })
    )
}

private fun buildBreadthSearchSeed(searchConfig: CrossSectionalSearchConfig): ResearchConfig {
    val base = searchConfig.baseConfig
    return base.copy(
        maxSymbols = selectIntSearchValue(base.maxSymbols, searchConfig.maxSymbols, listOf(searchConfig.maxSymbols.maxOrNull() ?: base.maxSymbols)),
        discoveryMaxSymbols = selectIntSearchValue(
            base.discoveryMaxSymbols,
            searchConfig.discoveryMaxSymbols,
            listOf(searchConfig.discoveryMaxSymbols.maxOrNull() ?: base.discoveryMaxSymbols),
            predicate = { it >= 0 }
        ),
        topPerSide = selectIntSearchValue(base.topPerSide, searchConfig.topPerSide, listOf(searchConfig.topPerSide.maxOrNull() ?: base.topPerSide)),
        maxConcurrentPositions = selectIntSearchValue(
            base.maxConcurrentPositions,
            searchConfig.maxConcurrentPositions,
            listOf(searchConfig.maxConcurrentPositions.maxOrNull() ?: base.maxConcurrentPositions)
        ),
        maxConcurrentLongs = selectIntSearchValue(
            base.maxConcurrentLongs,
            searchConfig.maxConcurrentLongs,
            listOf(searchConfig.maxConcurrentLongs.maxOrNull() ?: base.maxConcurrentLongs)
        ),
        maxConcurrentShorts = selectIntSearchValue(
            base.maxConcurrentShorts,
            searchConfig.maxConcurrentShorts,
            listOf(searchConfig.maxConcurrentShorts.maxOrNull() ?: base.maxConcurrentShorts)
        ),
        minTargetExposureFraction = selectDoubleSearchValue(
            base.minTargetExposureFraction,
            searchConfig.minTargetExposureFraction,
            listOf(searchConfig.minTargetExposureFraction.minOrNull() ?: base.minTargetExposureFraction),
            predicate = { it > 0.0 }
        ),
        maxTargetExposureFraction = selectDoubleSearchValue(
            base.maxTargetExposureFraction,
            searchConfig.maxTargetExposureFraction,
            listOf(searchConfig.maxTargetExposureFraction.maxOrNull() ?: base.maxTargetExposureFraction),
            predicate = { it > 0.0 }
        ),
        rebalanceTargetExposureStep = selectDoubleSearchValue(
            base.rebalanceTargetExposureStep,
            searchConfig.rebalanceTargetExposureStep,
            listOf(searchConfig.rebalanceTargetExposureStep.minOrNull() ?: base.rebalanceTargetExposureStep),
            predicate = { it >= 0.0 && it <= 1.0 }
        ),
        maxNetExposureFraction = selectDoubleSearchValue(
            base.maxNetExposureFraction,
            searchConfig.maxNetExposureFraction,
            listOf(searchConfig.maxNetExposureFraction.maxOrNull() ?: base.maxNetExposureFraction),
            predicate = { it in 0.0..1.0 }
        ),
        maxPortfolioBetaBtcAbs = selectDoubleSearchValue(
            base.maxPortfolioBetaBtcAbs,
            searchConfig.maxPortfolioBetaBtcAbs,
            listOf(searchConfig.maxPortfolioBetaBtcAbs.maxOrNull() ?: base.maxPortfolioBetaBtcAbs),
            predicate = { it >= 0.0 }
        ),
        maxPortfolioBetaEthAbs = selectDoubleSearchValue(
            base.maxPortfolioBetaEthAbs,
            searchConfig.maxPortfolioBetaEthAbs,
            listOf(searchConfig.maxPortfolioBetaEthAbs.maxOrNull() ?: base.maxPortfolioBetaEthAbs),
            predicate = { it >= 0.0 }
        )
    )
}

private fun buildDiversifiedSearchSeeds(searchConfig: CrossSectionalSearchConfig): List<ResearchConfig> {
    val seedBarMinutes = prioritizedSeedBarMinutes(searchConfig)
        .ifEmpty { listOf(searchConfig.baseConfig.barMinutes) }
    val mediumSeedBarMinutes = prioritizedMediumSeedBarMinutes(searchConfig)
    return buildList {
        seedBarMinutes.forEach { barMinutes ->
            add(buildTrendSearchSeed(searchConfig, barMinutes))
            add(buildReversionSearchSeed(searchConfig, barMinutes))
        }
        mediumSeedBarMinutes.forEach { barMinutes ->
            add(buildMediumTrendSearchSeed(searchConfig, barMinutes))
            add(buildMediumReversionSearchSeed(searchConfig, barMinutes))
        }
        add(buildBreadthSearchSeed(searchConfig))
    }
        .filter(::isValidResearchConfig)
        .distinctBy(::researchConfigFingerprint)
}

private fun buildSeedMutationCandidates(
    seed: ResearchConfig,
    mutations: List<SearchMutation>,
    roundIndex: Int
): List<SearchMutationCandidate> {
    if (mutations.isEmpty()) return emptyList()

    val seedFingerprint = researchConfigFingerprint(seed)
    val preferredGroups = listOf(
        "timeframe",
        "universe_breadth",
        "trend_signal",
        "trend_exit",
        "reversion_signal",
        "reversion_exit",
        "portfolio_policy",
        "execution_liquidity",
        "calibration"
    )
    val discoveredGroups = mutations.map { it.group }.distinct()
    val orderedGroups = rotate(
        preferredGroups.filter { it in discoveredGroups } + discoveredGroups.filter { it !in preferredGroups },
        roundIndex
    )
    val seedOffset = abs(seedFingerprint.hashCode())
    val perGroupCandidates = orderedGroups.map { group ->
        val groupMutations = mutations.filter { it.group == group }
        val rotatedMutations = rotate(groupMutations, seedOffset + roundIndex)
        val mutationIterators = rotatedMutations.map { mutation ->
            mutation.variants(seed)
                .map { candidate ->
                    SearchMutationCandidate(
                        seedFingerprint = seedFingerprint,
                        mutationName = mutation.name,
                        mutationGroup = mutation.group,
                        config = candidate
                    )
                }
                .iterator()
        }.toMutableList()

        buildList {
            var progressed = true
            while (progressed) {
                progressed = false
                mutationIterators.forEach { iterator ->
                    if (iterator.hasNext()) {
                        add(iterator.next())
                        progressed = true
                    }
                }
            }
        }
    }

    val groupIterators = perGroupCandidates.map { it.iterator() }.toMutableList()
    return buildList {
        var progressed = true
        while (progressed) {
            progressed = false
            groupIterators.forEach { iterator ->
                if (iterator.hasNext()) {
                    add(iterator.next())
                    progressed = true
                }
            }
        }
    }
}

private fun buildSearchGeneration(
    seeds: List<ResearchConfig>,
    mutations: List<SearchMutation>,
    roundsCompleted: Int,
    limit: Int,
    evaluatedFingerprints: Set<String>
): List<SearchMutationCandidate> {
    if (limit <= 0 || seeds.isEmpty()) return emptyList()

    val rotatedSeeds = rotate(
        seeds.distinctBy(::researchConfigFingerprint),
        roundsCompleted
    )
    val seedIterators = rotatedSeeds.mapIndexed { index, seed ->
        buildList {
            add(
                SearchMutationCandidate(
                    seedFingerprint = researchConfigFingerprint(seed),
                    mutationName = "seed",
                    mutationGroup = "seed_anchor",
                    config = seed
                )
            )
            addAll(buildSeedMutationCandidates(seed, mutations, roundsCompleted + index))
        }.iterator()
    }.toMutableList()
    val seenFingerprints = evaluatedFingerprints.toMutableSet()

    return buildList {
        while (size < limit) {
            var progressed = false
            seedIterators.forEach { iterator ->
                while (iterator.hasNext() && size < limit) {
                    val candidate = iterator.next()
                    val safeConfig = searchSafeConfig(candidate.config)
                    if (!isValidResearchConfig(safeConfig)) {
                        continue
                    }
                    val fingerprint = researchConfigFingerprint(safeConfig)
                    if (!seenFingerprints.add(fingerprint)) {
                        continue
                    }
                    add(candidate.copy(config = safeConfig))
                    progressed = true
                    break
                }
            }
            if (!progressed) break
        }
    }
}

fun searchCrossSectionalResearch(
    searchConfig: CrossSectionalSearchConfig = CrossSectionalSearchConfig()
): CrossSectionalSearchResult {
    val contextCache = mutableMapOf<ResearchDataKey, ResearchDataContext>()
    return searchCrossSectionalResearch(searchConfig) { candidate ->
        val safeConfig = searchSafeConfig(candidate)
        val key = researchDataKey(safeConfig)
        val context = contextCache.getOrPut(key) { loadResearchDataContext(safeConfig) }
        evaluateCrossSectionalResearch(context, safeConfig)
    }
}

fun searchCrossSectionalResearch(
    searchConfig: CrossSectionalSearchConfig,
    evaluator: (ResearchConfig) -> CrossSectionalResearchResult
): CrossSectionalSearchResult {
    val normalizedSearch = normalizeSearchConfig(searchConfig)
    val startedAt = Instant.now()
    val mutations = buildSearchMutations(normalizedSearch)
    val evaluations = linkedMapOf<String, SearchEvaluation>()
    val attemptedFingerprints = linkedSetOf<String>()

    fun evaluateCandidate(candidate: ResearchConfig): SearchEvaluation? {
        val safeConfig = searchSafeConfig(candidate)
        if (!isValidResearchConfig(safeConfig)) return null
        val fingerprint = researchConfigFingerprint(safeConfig)
        if (!attemptedFingerprints.add(fingerprint)) {
            return evaluations[fingerprint]
        }
        if (evaluations.size >= normalizedSearch.maxEvaluations) return null
        evaluations[fingerprint]?.let { return it }
        val result = try {
            evaluator(safeConfig)
        } catch (e: ResearchCoverageException) {
            println(
                "Cross-sectional search skipped config fingerprint=$fingerprint " +
                    "reason=${e.message ?: "coverage gate failed"}"
            )
            return null
        }
        val evaluation = buildSearchEvaluation(normalizedSearch, result, Instant.now())
        evaluations[fingerprint] = evaluation
        return evaluation
    }

    evaluateCandidate(normalizedSearch.baseConfig)

    var roundsCompleted = 0
    var seeds = listOf(normalizedSearch.baseConfig) + buildDiversifiedSearchSeeds(normalizedSearch)
    while (
        roundsCompleted < normalizedSearch.rounds &&
        evaluations.size < normalizedSearch.maxEvaluations &&
        seeds.isNotEmpty()
    ) {
        val remainingBudget = normalizedSearch.maxEvaluations - evaluations.size
        val roundBudget = roundEvaluationBudget(normalizedSearch, evaluations.size, roundsCompleted)
        val generation = buildSearchGeneration(
            seeds = seeds,
            mutations = mutations,
            roundsCompleted = roundsCompleted,
            limit = min(remainingBudget, roundBudget),
            evaluatedFingerprints = evaluations.keys
        )

        if (generation.isEmpty()) break
        generation.forEach { evaluateCandidate(it.config) }
        val mutationCoverage = generation.groupingBy { it.mutationGroup }
            .eachCount()
            .toSortedMap()
            .entries
            .joinToString(",") { (group, count) -> "$group:$count" }
        val mutationSample = generation.take(6)
            .joinToString(",") { "${it.mutationGroup}:${it.mutationName}" }
        println(
            "Cross-sectional search round=${roundsCompleted + 1} " +
                "seeds=${seeds.size} evaluated=${generation.size} total=${evaluations.size} " +
                "mutationCoverage=$mutationCoverage mutationSample=$mutationSample"
        )
        roundsCompleted += 1
        seeds = nextSearchSeeds(evaluations.values.toList(), normalizedSearch)
    }

    val rankedTrend = rankTrendEvaluations(evaluations.values.toList())

    return CrossSectionalSearchResult(
        searchConfig = normalizedSearch,
        startedAt = startedAt,
        completedAt = Instant.now(),
        roundsCompleted = roundsCompleted,
        evaluatedConfigs = evaluations.size,
        topTrendConfigs = rankedTrend.take(normalizedSearch.leaderboardSize).mapIndexed { index, evaluation ->
            toSearchCandidate(evaluation, index + 1)
        }
    )
}
