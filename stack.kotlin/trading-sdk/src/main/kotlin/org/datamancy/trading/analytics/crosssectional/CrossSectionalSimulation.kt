package org.datamancy.trading.analytics.crosssectional

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

fun buildExecutionEstimate(row: FeatureRow, notionalUsd: Double, side: Int, kind: StrategyKind): ExecutionEstimate {
    val spreadHalfBps = row.spreadBps / 2.0
    val depthPressure = notionalUsd / max(row.depthUsd, notionalUsd)
    val volatilityPenalty = min(row.volBps / 70.0, 4.0)
    val imbalanceAgainstTrade = max(0.0, -side.toDouble() * row.imbalance)
    val imbalanceWithTrade = max(0.0, side.toDouble() * row.imbalance)
    val flowAgainstTrade = max(0.0, -side.toDouble() * row.flowSignal)
    val makerFeeBps = 1.0
    val takerFeeBps = 4.0
    var makerShare = clamp(
        (if (kind == StrategyKind.REVERSION) 0.62 else 0.52) -
            (depthPressure * 0.32) -
            (volatilityPenalty * 0.03) -
            (flowAgainstTrade * 0.05) +
            (imbalanceWithTrade * 0.05) -
            (spreadHalfBps / 45.0),
        0.12,
        0.88
    )
    var fillRatio = clamp(
        0.96 -
            (spreadHalfBps / 30.0) -
            (depthPressure * 2.8) -
            (volatilityPenalty * 0.04) -
            (flowAgainstTrade * 0.05) -
            (imbalanceAgainstTrade * 0.03) +
            (imbalanceWithTrade * 0.02),
        0.25,
        1.0
    )
    var slippageBps = clamp(
        0.20 +
            (spreadHalfBps * 0.18) +
            (depthPressure * 9.0) +
            (volatilityPenalty * 0.70) +
            (flowAgainstTrade * 1.9) +
            (imbalanceAgainstTrade * 0.8) -
            (makerShare * 0.70),
        0.15,
        18.0
    )
    var impactBps = clamp(
        0.15 +
            (depthPressure * 14.0) +
            (max(0.0, row.volumeRatio - 2.0) * 0.9) +
            (max(0.0, row.volRegime - 1.3) * 1.8),
        0.10,
        22.0
    )
    var adverseSelectionBps = clamp(
        (abs(row.residualZ) * 0.35) +
            (flowAgainstTrade * 2.5) +
            (max(0.0, row.volRegime - 1.0) * 2.2) +
            (max(0.0, abs(row.rawTrend) - 1.5) * 1.2),
        0.0,
        10.0
    )
    if (!row.executionObserved) {
        makerShare = clamp(makerShare - 0.10, 0.05, 0.78)
        fillRatio = clamp(fillRatio - 0.08, 0.18, 0.96)
        slippageBps = clamp((slippageBps * 1.18) + 0.9, 0.25, 24.0)
        impactBps = clamp((impactBps * 1.20) + 1.1, 0.15, 28.0)
        adverseSelectionBps = clamp((adverseSelectionBps * 1.15) + 0.6, 0.0, 12.0)
    }
    val feeBps = (makerFeeBps * makerShare) + (takerFeeBps * (1.0 - makerShare))
    val totalCostBps = feeBps + spreadHalfBps + slippageBps + impactBps + adverseSelectionBps
    return ExecutionEstimate(
        fillRatio = fillRatio,
        feeBps = feeBps,
        feeTier = if (makerShare >= 0.5) "retail_mixed_maker_bias" else "retail_mixed_taker_bias",
        feeTierAdjustmentBps = (feeBps - takerFeeBps).round(4),
        makerFeeBps = makerFeeBps,
        takerFeeBps = takerFeeBps,
        spreadCostBps = spreadHalfBps,
        slippageBps = slippageBps,
        impactBps = impactBps,
        adverseSelectionBps = adverseSelectionBps,
        fundingDriftBps = 0.0,
        basisDriftBps = 0.0,
        totalCostBps = totalCostBps,
        estimatedFeeUsd = notionalUsd * feeBps / 10000.0,
        estimatedCostUsd = notionalUsd * totalCostBps / 10000.0
    )
}

fun buildExpectedRoundTripCostBps(row: FeatureRow, entryEstimate: ExecutionEstimate): Double {
    val regimeBuffer = max(0.0, row.volRegime - 1.0) * 1.75
    val flowBuffer = max(0.0, abs(row.flowSignal) - 0.8) * 1.40
    return (entryEstimate.totalCostBps * 2.0) + regimeBuffer + flowBuffer
}

fun reversionUniverseBucket(row: UnrankedFeature): String {
    val liquidityScore = min(((min(row.depthRatio, 3.0) + min(row.volumeRatio, 3.0)) / 2.0), 3.0)
    val liquidityBucket = when {
        liquidityScore < 0.9 -> "thin"
        liquidityScore < 1.45 -> "normal"
        else -> "deep"
    }
    return "$liquidityBucket|${volatilityRegimeBucket(row.volRegime)}"
}

fun breadthTilt(row: FeatureRow): Double =
    (row.breadth - 0.5) * 2.0

private fun confidenceToExposureFraction(confidence: Double, config: ResearchConfig): Double =
    clamp(
        config.minTargetExposureFraction +
            ((config.maxTargetExposureFraction - config.minTargetExposureFraction) * clamp(confidence, 0.0, 1.0)),
        config.minTargetExposureFraction,
        config.maxTargetExposureFraction
    ).round(4)

fun targetExposureFraction(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    expectedNetEdgeBps: Double,
    config: ResearchConfig
): Pair<Double, Double> {
    if (side == 0) return config.minTargetExposureFraction.round(4) to 0.0
    val expectedEdgeConfidence = clamp(
        expectedNetEdgeBps / max(config.minExpectedNetEdgeBps + 12.0, 20.0),
        0.0,
        1.0
    )
    val confidence = when (kind) {
        StrategyKind.TREND -> {
            val signalStrength = clamp((side.toDouble() * row.trendScore) / max(config.trendEntryScore * 2.0, 1.0), 0.0, 1.25)
            val confirmation = clamp(row.trendConfirmationScore / 3.0, 0.0, 1.25)
            val flowAlignment = clamp((side.toDouble() * row.flowSignal) / 1.25, 0.0, 1.0)
            val breadthAlignment = clamp((side.toDouble() * breadthTilt(row) + 0.35) / 1.35, 0.0, 1.0)
            val persistence = clamp(row.trendPersistence, 0.0, 1.0)
            val pullbackSupport = clamp(row.trendPullback / 1.15, 0.0, 1.0)
            val exhaustionPenalty = clamp(max(0.0, row.trendExhaustion - 0.9) / 1.2, 0.0, 1.0)
            clamp(
                (signalStrength * 0.28) +
                    (confirmation * 0.24) +
                    (persistence * 0.16) +
                    (flowAlignment * 0.10) +
                    (breadthAlignment * 0.08) +
                    (pullbackSupport * 0.06) +
                    (expectedEdgeConfidence * 0.18) -
                    (exhaustionPenalty * 0.18),
                0.0,
                1.0
            )
        }
        StrategyKind.REVERSION -> {
            val entryBound = if (side > 0) abs(row.reversionEntryLowerBound) else abs(row.reversionEntryUpperBound)
            val exitBound = if (side > 0) abs(row.reversionExitLowerBound) else abs(row.reversionExitUpperBound)
            val stateAbs = abs(row.reversionState)
            val penetration = clamp((stateAbs - entryBound) / max(entryBound, 0.35), 0.0, 1.4)
            val traversal = clamp(
                (stateAbs - exitBound) / max(entryBound - exitBound, 0.2),
                0.0,
                1.25
            )
            val antiContinuation = clamp(
                ((-direction(row.reversionState) * row.flowSignal) + 0.35) / 1.4,
                0.0,
                1.0
            )
            val pullbackSupport = clamp(max(row.trendPullback, row.trendExhaustion) / 1.1, 0.0, 1.0)
            val rawTrendPenalty = clamp(abs(row.rawTrend) / max(config.trendEntryScore * 1.6, 1.0), 0.0, 1.0)
            val scoreStrength = clamp(row.reversionScore / 3.0, 0.0, 1.2)
            clamp(
                (penetration * 0.28) +
                    (traversal * 0.18) +
                    (antiContinuation * 0.14) +
                    (pullbackSupport * 0.10) +
                    (scoreStrength * 0.14) +
                    (expectedEdgeConfidence * 0.18) -
                    (rawTrendPenalty * 0.16),
                0.0,
                1.0
            )
        }
    }
    return confidenceToExposureFraction(confidence, config) to confidence.round(4)
}

private fun buildSizedCandidate(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    expectedGrossEdgeBps: Double,
    cappedNetEdgeBps: Double,
    config: ResearchConfig,
    calibrationSamples: Int = 0,
    calibrationWinRate: Double = 0.0,
    calibrationLowerBoundBps: Double = 0.0,
    calibrationScope: String = "heuristic"
): EntryCandidate {
    val sizing = targetExposureFraction(kind, row, side, cappedNetEdgeBps, config)
    val targetExposureFraction = sizing.first
    val scaledNotionalUsd = config.notionalUsd * targetExposureFraction
    val entryEstimate = buildExecutionEstimate(row, scaledNotionalUsd, side, kind)
    val expectedRoundTripCostBps = buildExpectedRoundTripCostBps(row, entryEstimate)
    val feasibleNetEdgeBps = max(0.0, expectedGrossEdgeBps - expectedRoundTripCostBps)
    val expectedNetEdgeBps = min(max(cappedNetEdgeBps, 0.0), feasibleNetEdgeBps).round(4)
    return EntryCandidate(
        row = row,
        side = side,
        entryEstimate = entryEstimate,
        expectedGrossEdgeBps = expectedGrossEdgeBps.round(4),
        expectedRoundTripCostBps = expectedRoundTripCostBps.round(4),
        expectedNetEdgeBps = expectedNetEdgeBps,
        targetExposureFraction = targetExposureFraction,
        signalConfidence = sizing.second,
        calibrationSamples = calibrationSamples,
        calibrationWinRate = calibrationWinRate,
        calibrationLowerBoundBps = calibrationLowerBoundBps,
        calibrationScope = calibrationScope
    )
}

fun volatilityRegimeBucket(volRegime: Double): String =
    when {
        volRegime < 0.95 -> "calm"
        volRegime < 1.45 -> "normal"
        else -> "stress"
    }

fun calibrationRegimeBucket(row: FeatureRow): String =
    volatilityRegimeBucket(row.volRegime)

fun tradeRegimeBucket(trade: TradeRecord): String =
    volatilityRegimeBucket(trade.entryVolRegime)

fun calibrationSignalBucket(kind: StrategyKind, row: FeatureRow): String =
    when (kind) {
        StrategyKind.TREND -> when {
            max(abs(row.trendScore), abs(row.mediumTrendScore)) < 1.35 -> "entry"
            max(abs(row.trendScore), abs(row.mediumTrendScore)) < 1.90 -> "strong"
            else -> "extreme"
        }
        StrategyKind.REVERSION -> when {
            abs(row.reversionState) < max(abs(row.reversionEntryLowerBound), abs(row.reversionEntryUpperBound)) * 1.2 -> "entry"
            abs(row.reversionState) < max(abs(row.reversionEntryLowerBound), abs(row.reversionEntryUpperBound)) * 1.8 -> "deep"
            else -> "extreme"
        }
    }

fun calibrationConfirmationBucket(kind: StrategyKind, row: FeatureRow, side: Int, config: ResearchConfig): String {
    val flowAlignment = side.toDouble() * row.flowSignal
    val fastAlignment = side.toDouble() * direction(row.residualMomFast)
    val slowAlignment = side.toDouble() * direction(row.residualMomSlow)
    val mediumAlignment = side.toDouble() * direction(row.mediumTrendScore)
    val continuationPressure = direction(row.reversionState) * row.flowSignal
    return when (kind) {
        StrategyKind.TREND -> when {
            flowAlignment >= config.trendMinFlowAlignment &&
                slowAlignment > 0.0 &&
                mediumAlignment > 0.0 -> "confirmed"
            flowAlignment >= -0.04 && (fastAlignment > 0.0 || slowAlignment > 0.0 || mediumAlignment > 0.0) -> "mixed"
            else -> "fragile"
        }
        StrategyKind.REVERSION -> when {
            row.trendPullback >= 0.35 || row.trendExhaustion >= 0.55 -> "confirmed"
            flowAlignment >= 0.08 && fastAlignment > 0.0 -> "confirmed"
            continuationPressure <= (config.reversionMaxContinuationPressure * 0.55) &&
                flowAlignment >= -0.04 &&
                abs(row.rawTrend) < (config.trendEntryScore * 0.95) -> "stall"
            else -> "fragile"
        }
    }
}

fun calibrationBaseKey(kind: StrategyKind, row: FeatureRow, side: Int, config: ResearchConfig): CalibrationKey =
    CalibrationKey(
        strategyKind = kind,
        exchange = row.exchange,
        symbol = row.symbol,
        side = side,
        regimeBucket = calibrationRegimeBucket(row),
        signalBucket = calibrationSignalBucket(kind, row),
        confirmationBucket = calibrationConfirmationBucket(kind, row, side, config)
    )

fun calibrationScopesForKey(key: CalibrationKey): List<Pair<String, CalibrationKey>> =
    listOf(
        "symbol_regime_signal_confirmation" to key,
        "market_regime_signal_confirmation" to key.copy(symbol = "ALL"),
        "symbol_regime_confirmation" to key.copy(signalBucket = "ALL"),
        "market_regime_confirmation" to key.copy(symbol = "ALL", signalBucket = "ALL"),
        "symbol_confirmation" to key.copy(regimeBucket = "ALL", signalBucket = "ALL"),
        "market_confirmation" to key.copy(symbol = "ALL", regimeBucket = "ALL", signalBucket = "ALL"),
        "symbol_all" to key.copy(regimeBucket = "ALL", signalBucket = "ALL", confirmationBucket = "ALL"),
        "market_all" to key.copy(symbol = "ALL", regimeBucket = "ALL", signalBucket = "ALL", confirmationBucket = "ALL")
    )

fun calibrationScopesForRow(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    config: ResearchConfig
): List<Pair<String, CalibrationKey>> =
    calibrationScopesForKey(calibrationBaseKey(kind, row, side, config))

fun CalibrationAccumulator.applyExample(example: CalibrationExample, multiplier: Int) {
    val factor = multiplier.toDouble()
    samples += multiplier
    wins += if (example.netEdgeBps > 0.0) multiplier else 0
    sumGrossEdgeBps += example.grossEdgeBps * factor
    sumNetEdgeBps += example.netEdgeBps * factor
    sumNetEdgeSqBps += example.netEdgeBps.pow(2.0) * factor
    sumTotalCostBps += example.totalCostBps * factor
    sumFillRatio += example.fillRatio * factor
}

fun CalibrationAccumulator.toStats(scope: String): CalibrationStats? {
    if (samples <= 0) return null
    val sampleCount = samples.toDouble()
    val avgNetEdgeBps = sumNetEdgeBps / sampleCount
    val variance = max((sumNetEdgeSqBps / sampleCount) - avgNetEdgeBps.pow(2.0), 0.0)
    val stderr = sqrt(variance / sampleCount)
    return CalibrationStats(
        samples = samples,
        winRate = wins.toDouble() / sampleCount,
        avgGrossEdgeBps = sumGrossEdgeBps / sampleCount,
        avgNetEdgeBps = avgNetEdgeBps,
        avgTotalCostBps = sumTotalCostBps / sampleCount,
        avgFillRatio = sumFillRatio / sampleCount,
        lowerBoundNetEdgeBps = avgNetEdgeBps - (1.28 * stderr),
        scope = scope
    )
}

fun addCalibrationExample(state: CalibrationState, example: CalibrationExample) {
    calibrationScopesForKey(example.key).forEach { (_, scopedKey) ->
        state.scoped.getOrPut(scopedKey) { CalibrationAccumulator() }.applyExample(example, 1)
    }
}

fun removeCalibrationExample(state: CalibrationState, example: CalibrationExample) {
    calibrationScopesForKey(example.key).forEach { (_, scopedKey) ->
        val accumulator = state.scoped[scopedKey] ?: return@forEach
        accumulator.applyExample(example, -1)
        if (accumulator.samples <= 0) {
            state.scoped.remove(scopedKey)
        }
    }
}

fun buildCalibrationState(examples: List<CalibrationExample>): CalibrationState {
    val state = CalibrationState()
    examples.forEach { addCalibrationExample(state, it) }
    return state
}

fun blendCalibrationStats(primary: CalibrationStats, fallback: CalibrationStats, config: ResearchConfig): CalibrationStats {
    if (primary.scope == fallback.scope) return primary
    val weight = clamp(primary.samples.toDouble() / max(config.strongCalibrationSamples, 1).toDouble(), 0.0, 1.0)
    return CalibrationStats(
        samples = primary.samples,
        winRate = (primary.winRate * weight) + (fallback.winRate * (1.0 - weight)),
        avgGrossEdgeBps = (primary.avgGrossEdgeBps * weight) + (fallback.avgGrossEdgeBps * (1.0 - weight)),
        avgNetEdgeBps = (primary.avgNetEdgeBps * weight) + (fallback.avgNetEdgeBps * (1.0 - weight)),
        avgTotalCostBps = (primary.avgTotalCostBps * weight) + (fallback.avgTotalCostBps * (1.0 - weight)),
        avgFillRatio = (primary.avgFillRatio * weight) + (fallback.avgFillRatio * (1.0 - weight)),
        lowerBoundNetEdgeBps = (primary.lowerBoundNetEdgeBps * weight) + (fallback.lowerBoundNetEdgeBps * (1.0 - weight)),
        scope = primary.scope
    )
}

fun conservativeCalibrationNetEdgeBps(
    calibration: CalibrationStats,
    config: ResearchConfig
): Double {
    val confidence = clamp(
        (calibration.samples - config.minCalibrationSamples).toDouble() /
            max(config.strongCalibrationSamples - config.minCalibrationSamples, 1).toDouble(),
        0.0,
        1.0
    )
    val scopeBonus = when {
        calibration.scope.startsWith("symbol_regime_signal_confirmation") -> 0.10
        calibration.scope.startsWith("symbol_regime") || calibration.scope.startsWith("symbol_confirmation") -> 0.05
        calibration.scope.startsWith("market_regime") -> 0.02
        else -> 0.0
    }
    val avgWeight = clamp(0.15 + (confidence * 0.35) + scopeBonus, 0.15, 0.60)
    return max(
        (calibration.lowerBoundNetEdgeBps * (1.0 - avgWeight)) +
            (calibration.avgNetEdgeBps * avgWeight),
        0.0
    )
}

fun resolveCalibration(
    state: CalibrationState?,
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    config: ResearchConfig
): CalibrationStats? {
    if (state == null) return null
    val scopedStats = calibrationScopesForRow(kind, row, side, config)
        .mapNotNull { (scope, key) -> state.scoped[key]?.toStats(scope) }
    if (scopedStats.isEmpty()) return null
    val fallback = scopedStats.last()
    val primary = scopedStats.firstOrNull { it.samples >= config.minCalibrationSamples }
        ?: fallback.takeIf { it.samples >= config.minCalibrationSamples }
        ?: return null
    return blendCalibrationStats(primary, fallback, config)
}

fun buildStructuralCandidate(kind: StrategyKind, row: FeatureRow, side: Int, config: ResearchConfig): EntryCandidate? {
    if (!row.liquid) return null
    if (row.volumeRatio < config.minVolumeRatio || row.volumeRatio > config.maxVolumeRatio) return null
    if (row.volRegime > config.maxVolRegime) return null

    val expectedGrossEdgeBps = when (kind) {
        StrategyKind.TREND -> row.trendExpectedGrossEdgeBps
        StrategyKind.REVERSION -> row.reversionExpectedGrossEdgeBps
    }
    val rowBreadthTilt = breadthTilt(row)
    val continuationPressure = direction(row.reversionState) * row.flowSignal

    when (kind) {
        StrategyKind.TREND -> {
            val flowAlignment = side.toDouble() * row.flowSignal
            val breadthAlignment = side.toDouble() * rowBreadthTilt
            val mediumAlignment = side.toDouble() * row.mediumTrendScore
            val pullbackAllowance = if (mediumAlignment > 0.55 && row.trendPersistence >= 0.5) 1.45 else 1.05
            if ((side.toDouble() * row.trendScore) < config.trendEntryScore) return null
            if ((side.toDouble() * row.rawTrend) <= 0.0 && mediumAlignment < 0.35) return null
            if (mediumAlignment <= 0.0) return null
            if (flowAlignment < config.trendMinFlowAlignment && mediumAlignment < 0.65) return null
            if (breadthAlignment < -0.05 && mediumAlignment < 0.65) return null
            if ((side.toDouble() * direction(row.residualMomFast)) <= 0.0 && mediumAlignment < 0.75) return null
            if ((side.toDouble() * direction(row.residualMomSlow)) <= 0.0) return null
            if ((side.toDouble() * direction(row.residualMomMedium)) <= 0.0) return null
            if (abs(row.reversionState) > max(abs(row.reversionEntryLowerBound), abs(row.reversionEntryUpperBound)) * pullbackAllowance) {
                return null
            }
        }
        StrategyKind.REVERSION -> {
            val trendAwareRawTrendCap = if (row.trendPullback > 0.35 || row.trendExhaustion > 0.75) {
                config.trendEntryScore * 1.55
            } else {
                config.trendEntryScore * 1.10
            }
            val continuationCap = if (row.trendPullback > 0.35 || row.trendExhaustion > 0.75) {
                config.reversionMaxContinuationPressure * 1.10
            } else {
                config.reversionMaxContinuationPressure * 0.75
            }
            val breadthCap = if (row.trendPullback > 0.35 || row.trendExhaustion > 0.75) 0.30 else 0.15
            if (side > 0 && row.reversionState > row.reversionEntryLowerBound) return null
            if (side < 0 && row.reversionState < row.reversionEntryUpperBound) return null
            if (row.reversionScore <= 0.0) return null
            if (abs(row.rawTrend) > trendAwareRawTrendCap) return null
            if (continuationPressure > continuationCap) return null
            if (direction(row.reversionState) * rowBreadthTilt > breadthCap) return null
            if ((side.toDouble() * row.flowSignal) < -0.08 && row.trendPullback < 0.35 && row.trendExhaustion < 0.55) return null
            if ((side.toDouble() * direction(row.residualMomFast)) < 0.0 &&
                abs(row.rawTrend) > (config.trendEntryScore * 0.85) &&
                row.trendPullback < 0.35
            ) return null
        }
    }

    val baseSizing = targetExposureFraction(kind, row, side, expectedGrossEdgeBps, config)
    val scaledEntryEstimate = buildExecutionEstimate(row, config.notionalUsd * baseSizing.first, side, kind)
    if (scaledEntryEstimate.fillRatio < config.minFillRatio) return null
    val expectedRoundTripCostBps = buildExpectedRoundTripCostBps(row, scaledEntryEstimate)
    val expectedNetEdgeBps = expectedGrossEdgeBps - expectedRoundTripCostBps
    return buildSizedCandidate(
        kind = kind,
        row = row,
        side = side,
        expectedGrossEdgeBps = expectedGrossEdgeBps,
        cappedNetEdgeBps = expectedNetEdgeBps,
        config = config
    )
}

fun buildEntryCandidate(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): EntryCandidate? {
    val seed = buildStructuralCandidate(kind, row, side, config) ?: return null
    val safetyMarginBps = config.executionSafetyMarginBps + (max(0.0, row.volRegime - 1.0) * 2.5)

    if (calibrationState == null) {
        if (seed.expectedNetEdgeBps < config.minExpectedNetEdgeBps) return null
        if (seed.expectedGrossEdgeBps < seed.expectedRoundTripCostBps + safetyMarginBps) return null
        return seed
    }

    val calibration = resolveCalibration(calibrationState, kind, row, side, config) ?: return null
    if (calibration.avgFillRatio < config.minFillRatio) return null
    if (calibration.winRate < config.minCalibrationWinRate) return null
    if (calibration.lowerBoundNetEdgeBps < config.minCalibrationLowerBoundBps) return null
    val calibratedNetEdgeBps = min(
        seed.expectedNetEdgeBps,
        conservativeCalibrationNetEdgeBps(calibration, config)
    )
    if (calibratedNetEdgeBps < config.minExpectedNetEdgeBps) return null

    val calibratedGrossEdgeBps = seed.expectedRoundTripCostBps + calibratedNetEdgeBps
    if (calibratedNetEdgeBps < safetyMarginBps) return null

    return buildSizedCandidate(
        kind = kind,
        row = row,
        side = side,
        expectedGrossEdgeBps = calibratedGrossEdgeBps.round(4),
        cappedNetEdgeBps = calibratedNetEdgeBps.round(4),
        config = config,
        calibrationSamples = calibration.samples,
        calibrationWinRate = calibration.winRate.round(4),
        calibrationLowerBoundBps = calibration.lowerBoundNetEdgeBps.round(4),
        calibrationScope = calibration.scope
    )
}

fun shouldExitPosition(kind: StrategyKind, position: OpenPosition, current: FeatureRow, config: ResearchConfig): Boolean =
    baseExitTriggered(kind, position, current, config) ||
        trailingStopTriggered(kind, position, current, config) ||
        takeProfitTriggered(kind, position, current, config)

private fun baseExitTriggered(
    kind: StrategyKind,
    position: OpenPosition,
    current: FeatureRow,
    config: ResearchConfig
): Boolean =
    when (kind) {
        StrategyKind.TREND -> {
            val ageBars = current.barIndex - position.entryRow.barIndex
            ageBars >= config.trendHoldBars ||
                ((current.trendScore * position.side.toDouble()) <= 0.12 &&
                    (current.mediumTrendScore * position.side.toDouble()) <= 0.10) ||
                ((position.side.toDouble() * current.flowSignal) < -0.18 && current.trendPullback < 0.35) ||
                current.volRegime > (config.maxVolRegime * 1.15)
        }
        StrategyKind.REVERSION -> {
            val ageBars = current.barIndex - position.entryRow.barIndex
            ageBars >= config.reversionHoldBars ||
                current.reversionState in current.reversionExitLowerBound..current.reversionExitUpperBound ||
                (current.reversionState * position.side.toDouble()) >= -0.05 ||
                (direction(position.entryRow.reversionState) * current.flowSignal) > config.reversionMaxContinuationPressure
        }
    }

private fun positionSignedReturnFraction(position: OpenPosition, current: FeatureRow): Double {
    if (position.entryRow.close <= 0.0 || current.close <= 0.0) return 0.0
    return position.side.toDouble() * ((current.close / position.entryRow.close) - 1.0)
}

private fun exitDistanceFraction(position: OpenPosition, current: FeatureRow, multiple: Double): Double {
    if (!multiple.isFinite() || multiple <= 0.0) return Double.POSITIVE_INFINITY
    val referenceVol = max(max(position.entryRow.vol30, current.vol30), 1e-6)
    return multiple * referenceVol
}

private fun trailingStopMultiple(kind: StrategyKind, config: ResearchConfig): Double =
    when (kind) {
        StrategyKind.TREND -> config.trendTrailingStopVolMultiple
        StrategyKind.REVERSION -> config.reversionTrailingStopVolMultiple
    }

private fun takeProfitMultiple(kind: StrategyKind, config: ResearchConfig): Double =
    when (kind) {
        StrategyKind.TREND -> config.trendTakeProfitVolMultiple
        StrategyKind.REVERSION -> config.reversionTakeProfitVolMultiple
    }

private fun trailingStopTriggered(
    kind: StrategyKind,
    position: OpenPosition,
    current: FeatureRow,
    config: ResearchConfig
): Boolean {
    val distance = exitDistanceFraction(position, current, trailingStopMultiple(kind, config))
    if (!distance.isFinite()) return false
    val favorableReturn = positionSignedReturnFraction(position, current)
    return position.maxFavorableReturnFraction >= distance &&
        (position.maxFavorableReturnFraction - favorableReturn) >= distance
}

private fun takeProfitTriggered(
    kind: StrategyKind,
    position: OpenPosition,
    current: FeatureRow,
    config: ResearchConfig
): Boolean {
    val distance = exitDistanceFraction(position, current, takeProfitMultiple(kind, config))
    if (!distance.isFinite()) return false
    return positionSignedReturnFraction(position, current) >= distance
}

private fun updateOpenPosition(position: OpenPosition, current: FeatureRow): OpenPosition =
    position.copy(
        maxFavorableReturnFraction = max(
            position.maxFavorableReturnFraction,
            positionSignedReturnFraction(position, current)
        )
    )

fun seedCandidateRows(kind: StrategyKind, bucket: List<FeatureRow>, config: ResearchConfig): List<EntryCandidate> =
    when (kind) {
        StrategyKind.TREND -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.trendLongRank <= config.topPerSide &&
                        it.trendScore >= config.trendEntryScore &&
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.TREND, it, 1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.trendShortRank <= config.topPerSide &&
                        it.trendScore <= -config.trendEntryScore &&
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.TREND, it, -1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
        StrategyKind.REVERSION -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.reversionLongRank <= config.topPerSide &&
                        it.reversionState <= it.reversionEntryLowerBound &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.REVERSION, it, 1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.reversionShortRank <= config.topPerSide &&
                        it.reversionState >= it.reversionEntryUpperBound &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.REVERSION, it, -1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
    }

fun candidateRows(
    kind: StrategyKind,
    bucket: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): List<EntryCandidate> =
    when (kind) {
        StrategyKind.TREND -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.trendLongRank <= config.topPerSide &&
                        it.trendScore >= config.trendEntryScore &&
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.TREND, it, 1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.trendShortRank <= config.topPerSide &&
                        it.trendScore <= -config.trendEntryScore &&
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.TREND, it, -1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
        StrategyKind.REVERSION -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.reversionLongRank <= config.topPerSide &&
                        it.reversionState <= it.reversionEntryLowerBound &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.REVERSION, it, 1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.reversionShortRank <= config.topPerSide &&
                        it.reversionState >= it.reversionEntryUpperBound &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.REVERSION, it, -1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
    }

fun latestSignalSnapshots(
    rows: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): List<SignalSnapshot> =
    rows.groupBy { it.exchange to it.symbol }
        .values
        .mapNotNull { series -> series.maxByOrNull { it.time } }
        .map { row ->
            val trendLong = if (
                row.liquid &&
                    row.trendLongRank <= config.topPerSide &&
                    row.trendScore >= config.trendEntryScore
            ) {
                buildEntryCandidate(StrategyKind.TREND, row, 1, config, calibrationState)
            } else {
                null
            }
            val trendShort = if (
                row.liquid &&
                    row.trendShortRank <= config.topPerSide &&
                    row.trendScore <= -config.trendEntryScore
            ) {
                buildEntryCandidate(StrategyKind.TREND, row, -1, config, calibrationState)
            } else {
                null
            }
            val trendCandidate = listOfNotNull(trendLong, trendShort).maxByOrNull { it.expectedNetEdgeBps }
            SignalSnapshot(
                exchange = row.exchange,
                symbol = row.symbol,
                time = row.time.toString(),
                lastPrice = row.close.round(4),
                betaBtc = row.betaBtc.round(4),
                betaEth = row.betaEth.round(4),
                residualZ = row.residualZ.round(4),
                residualCrossSectionalZ = row.residualCrossSectionalZ.round(4),
                mediumTrendScore = row.mediumTrendScore.round(4),
                trendConfirmationScore = row.trendConfirmationScore.round(4),
                trendPersistence = row.trendPersistence.round(4),
                trendPullback = row.trendPullback.round(4),
                trendExhaustion = row.trendExhaustion.round(4),
                trendScore = row.trendScore.round(4),
                breadth = row.breadth.round(4),
                spreadBps = row.spreadBps.round(2),
                depthUsd = row.depthUsd.round(2),
                imbalance = row.imbalance.round(4),
                flowSignal = row.flowSignal.round(4),
                volumeRatio = row.volumeRatio.round(4),
                volRegime = row.volRegime.round(4),
                expectedNetEdgeBps = (trendCandidate?.expectedNetEdgeBps ?: 0.0).round(2),
                targetExposureFraction = (trendCandidate?.targetExposureFraction ?: row.trendTargetExposureFraction).round(4),
                calibrationSamples = trendCandidate?.calibrationSamples ?: 0,
                calibrationLowerBoundBps = (trendCandidate?.calibrationLowerBoundBps ?: 0.0).round(2),
                liquid = row.liquid,
                action = when (trendCandidate?.side) {
                    1 -> "LONG"
                    -1 -> "SHORT"
                    else -> "FLAT"
                },
                fundingZ = row.fundingZ.round(4),
                oiChangeZ = row.oiChangeZ.round(4),
                crowdingScore = row.crowdingScore.round(4),
                participationScore = row.participationScore.round(4)
            )
        }
        .sortedWith(
            compareByDescending<SignalSnapshot> { it.expectedNetEdgeBps }
                .thenByDescending { it.calibrationLowerBoundBps }
                .thenByDescending { abs(it.trendScore) }
                .thenBy { it.exchange }
                .thenBy { it.symbol }
        )

fun buildTradeRecord(position: OpenPosition, current: FeatureRow, config: ResearchConfig): TradeRecord {
    val kind = position.strategyKind
    val entryNotionalUsd = config.notionalUsd * position.targetExposureFraction
    val exitEstimate = buildExecutionEstimate(current, entryNotionalUsd, -position.side, kind)
    val effectiveFill = min(position.entryEstimate.fillRatio, exitEstimate.fillRatio)
    val deployedGrossReturn = position.side * ((current.close / position.entryRow.close) - 1.0) * effectiveFill
    val totalCostBps = position.entryEstimate.totalCostBps + exitEstimate.totalCostBps
    val grossReturn = deployedGrossReturn * position.targetExposureFraction
    val netReturn = (deployedGrossReturn - (totalCostBps / 10000.0)) * position.targetExposureFraction
    val signalMagnitude = when (kind) {
        StrategyKind.TREND -> abs(position.entryRow.trendScore)
        StrategyKind.REVERSION -> abs(position.entryRow.reversionState)
    }
    val jitter = deterministicJitter(current.time, position.side)
    val decisionLatencyMs = clamp(6.0 + (signalMagnitude * 7.0) + (jitter * 0.6), 4.0, 60.0)
    val submitToAckMs = clamp(
        55.0 +
            (current.spreadBps * 1.1) +
            ((entryNotionalUsd / max(current.depthUsd, entryNotionalUsd)) * 120.0) +
            (jitter * 1.5),
        20.0,
        900.0
    )
    val submitToFillMs = clamp(submitToAckMs + ((1.0 - effectiveFill) * 260.0) + 18.0, 30.0, 1800.0)
    val p50RoundtripMs = clamp(submitToAckMs + 12.0, 20.0, 1000.0)
    val p95RoundtripMs = clamp(submitToAckMs * 2.0, 25.0, 1800.0)
    val p99RoundtripMs = clamp(submitToAckMs * 3.0, 30.0, 2500.0)

    return TradeRecord(
        strategyName = position.strategyName,
        strategyKind = kind.name.lowercase(),
        exchange = position.exchange,
        symbol = position.symbol,
        side = if (position.side > 0) "BUY" else "SELL",
        entryTime = position.entryRow.time,
        exitTime = current.time,
        entryPrice = position.entryRow.close,
        exitPrice = current.close,
        holdBars = current.barIndex - position.entryRow.barIndex,
        grossReturnFraction = grossReturn,
        netReturnFraction = netReturn,
        fillRatio = effectiveFill,
        feeBps = position.entryEstimate.feeBps + exitEstimate.feeBps,
        feeTier = position.entryEstimate.feeTier,
        feeTierAdjustmentBps = position.entryEstimate.feeTierAdjustmentBps + exitEstimate.feeTierAdjustmentBps,
        makerFeeBps = position.entryEstimate.makerFeeBps + exitEstimate.makerFeeBps,
        takerFeeBps = position.entryEstimate.takerFeeBps + exitEstimate.takerFeeBps,
        spreadCostBps = position.entryEstimate.spreadCostBps + exitEstimate.spreadCostBps,
        slippageBps = position.entryEstimate.slippageBps + exitEstimate.slippageBps,
        impactBps = position.entryEstimate.impactBps + exitEstimate.impactBps,
        adverseSelectionBps = position.entryEstimate.adverseSelectionBps + exitEstimate.adverseSelectionBps,
        fundingDriftBps = 0.0,
        basisDriftBps = 0.0,
        totalCostBps = totalCostBps,
        edgeAfterCostBps = (deployedGrossReturn - (totalCostBps / 10000.0)) * 10000.0,
        targetExposureFraction = position.targetExposureFraction,
        entryNotionalUsd = entryNotionalUsd.round(4),
        estimatedFeeUsd = position.entryEstimate.estimatedFeeUsd + exitEstimate.estimatedFeeUsd,
        estimatedCostUsd = position.entryEstimate.estimatedCostUsd + exitEstimate.estimatedCostUsd,
        entryTrendScore = position.entryRow.trendScore,
        entryResidualZ = position.entryRow.residualZ,
        expectedGrossEdgeBps = position.expectedGrossEdgeBps,
        expectedRoundTripCostBps = position.expectedRoundTripCostBps,
        expectedNetEdgeBps = position.expectedNetEdgeBps,
        calibrationSamples = position.calibrationSamples,
        calibrationWinRate = position.calibrationWinRate,
        calibrationLowerBoundBps = position.calibrationLowerBoundBps,
        calibrationScope = position.calibrationScope,
        entryImbalance = position.entryRow.imbalance,
        entryFlowSignal = position.entryRow.flowSignal,
        entryVolumeRatio = position.entryRow.volumeRatio,
        entryVolRegime = position.entryRow.volRegime,
        betaBtc = position.entryRow.betaBtc,
        betaEth = position.entryRow.betaEth,
        decisionLatencyMs = decisionLatencyMs,
        submitToAckMs = submitToAckMs,
        submitToFillMs = submitToFillMs,
        p50RoundtripMs = p50RoundtripMs,
        p95RoundtripMs = p95RoundtripMs,
        p99RoundtripMs = p99RoundtripMs,
        jitterMs = jitter
    )
}

fun simulateIndependentTrade(
    strategyName: String,
    kind: StrategyKind,
    candidate: EntryCandidate,
    series: List<FeatureRow>,
    startIndex: Int,
    config: ResearchConfig
): TradeRecord? {
    if (series.isEmpty() || startIndex !in series.indices) return null
    var position = OpenPosition(
        strategyName = strategyName,
        strategyKind = kind,
        exchange = candidate.row.exchange,
        symbol = candidate.row.symbol,
        side = candidate.side,
        entryRow = candidate.row,
        entryEstimate = candidate.entryEstimate,
        expectedGrossEdgeBps = candidate.expectedGrossEdgeBps,
        expectedRoundTripCostBps = candidate.expectedRoundTripCostBps,
        expectedNetEdgeBps = candidate.expectedNetEdgeBps,
        targetExposureFraction = candidate.targetExposureFraction,
        calibrationSamples = candidate.calibrationSamples,
        calibrationWinRate = candidate.calibrationWinRate,
        calibrationLowerBoundBps = candidate.calibrationLowerBoundBps,
        calibrationScope = candidate.calibrationScope,
        maxFavorableReturnFraction = 0.0
    )
    for (index in (startIndex + 1) until series.size) {
        val current = series[index]
        position = updateOpenPosition(position, current)
        if (shouldExitPosition(kind, position, current, config)) {
            return buildTradeRecord(position, current, config)
        }
    }
    val last = series.lastOrNull() ?: return null
    return if (last.time == candidate.row.time) {
        null
    } else {
        buildTradeRecord(updateOpenPosition(position, last), last, config)
    }
}

fun buildCalibrationExamples(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig
): List<CalibrationExample> {
    if (rows.isEmpty()) return emptyList()
    val grouped = rows.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val seriesByExchangeSymbol = rows.groupBy { it.exchange to it.symbol }
        .mapValues { (_, series) -> series.sortedBy { it.time } }
    val indexLookup = mutableMapOf<Triple<String, String, Instant>, Int>()
    seriesByExchangeSymbol.forEach { (key, series) ->
        series.forEachIndexed { index, row ->
            indexLookup[Triple(key.first, key.second, row.time)] = index
        }
    }

    val examples = mutableListOf<CalibrationExample>()
    for (key in orderedKeys) {
        val bucket = grouped[key].orEmpty()
        seedCandidateRows(kind, bucket, config).forEach { candidate ->
            val series = seriesByExchangeSymbol[candidate.row.exchange to candidate.row.symbol] ?: return@forEach
            val startIndex = indexLookup[Triple(candidate.row.exchange, candidate.row.symbol, candidate.row.time)] ?: return@forEach
            val trade = simulateIndependentTrade(strategyName, kind, candidate, series, startIndex, config) ?: return@forEach
            val realizedGrossEdgeBps = if (trade.targetExposureFraction > 1e-9) {
                (trade.grossReturnFraction * 10000.0) / trade.targetExposureFraction
            } else {
                0.0
            }
            examples += CalibrationExample(
                key = calibrationBaseKey(kind, candidate.row, candidate.side, config),
                entryTime = candidate.row.time,
                availableAt = trade.exitTime,
                grossEdgeBps = realizedGrossEdgeBps,
                netEdgeBps = trade.edgeAfterCostBps,
                totalCostBps = trade.totalCostBps,
                fillRatio = trade.fillRatio
            )
        }
    }
    return examples.sortedBy { it.availableAt }
}

private fun effectiveLongCapacity(config: ResearchConfig): Int =
    min(max(config.maxConcurrentLongs, 1), max(config.maxConcurrentPositions, 1))

private fun effectiveShortCapacity(config: ResearchConfig): Int =
    min(max(config.maxConcurrentShorts, 1), max(config.maxConcurrentPositions, 1))

private fun currentGrossExposureUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.targetExposureFraction }

private fun currentLongExposureUnits(positions: Collection<OpenPosition>): Double =
    positions.filter { it.side > 0 }.sumOf { it.targetExposureFraction }

private fun currentShortExposureUnits(positions: Collection<OpenPosition>): Double =
    positions.filter { it.side < 0 }.sumOf { it.targetExposureFraction }

private fun currentBetaBtcUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.side.toDouble() * it.targetExposureFraction * it.entryRow.betaBtc }

private fun currentBetaEthUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.side.toDouble() * it.targetExposureFraction * it.entryRow.betaEth }

private fun currentNetUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.side.toDouble() * it.targetExposureFraction }

private fun portfolioTelemetryPoint(
    positions: Collection<OpenPosition>
): PortfolioTelemetryPoint {
    val grossPositions = positions.size
    val longPositions = positions.count { it.side > 0 }
    val shortPositions = positions.count { it.side < 0 }
    return PortfolioTelemetryPoint(
        grossPositions = grossPositions,
        longPositions = longPositions,
        shortPositions = shortPositions,
        grossExposureUnits = currentGrossExposureUnits(positions).round(4),
        longExposureUnits = currentLongExposureUnits(positions).round(4),
        shortExposureUnits = currentShortExposureUnits(positions).round(4),
        netExposureUnits = currentNetUnits(positions).round(4),
        betaBtcUnits = currentBetaBtcUnits(positions).round(4),
        betaEthUnits = currentBetaEthUnits(positions).round(4)
    )
}

private fun portfolioAcceptanceScore(
    positions: Collection<OpenPosition>,
    candidate: EntryCandidate,
    config: ResearchConfig
): Double {
    val capacity = max(config.maxConcurrentPositions, 1).toDouble()
    val candidateNetContribution = candidate.side.toDouble() * candidate.targetExposureFraction
    val currentNetFraction = abs(currentNetUnits(positions)) / capacity
    val candidateNetFraction = abs(currentNetUnits(positions) + candidateNetContribution) / capacity
    val currentBetaPenalty =
        (abs(currentBetaBtcUnits(positions)) / capacity) +
            (abs(currentBetaEthUnits(positions)) / capacity)
    val candidateBetaPenalty =
        (abs(currentBetaBtcUnits(positions) + (candidateNetContribution * candidate.row.betaBtc)) / capacity) +
            (abs(currentBetaEthUnits(positions) + (candidateNetContribution * candidate.row.betaEth)) / capacity)
    val balanceBonus = (currentNetFraction - candidateNetFraction) * 6.0
    val betaBonus = (currentBetaPenalty - candidateBetaPenalty) * 10.0
    val capacityPenalty = (currentGrossExposureUnits(positions) / capacity) * 0.75
    val similarityPenalty = positions
        .filter { it.side == candidate.side }
        .map {
            val betaDistance = (abs(it.entryRow.betaBtc - candidate.row.betaBtc) + abs(it.entryRow.betaEth - candidate.row.betaEth)) / 2.0
            clamp(1.0 - betaDistance, 0.0, 1.0)
        }
        .takeIf { it.isNotEmpty() }
        ?.let(::mean)
        ?.times(2.0)
        ?: 0.0
    return candidate.expectedNetEdgeBps + balanceBonus + betaBonus - capacityPenalty - similarityPenalty
}

private fun canAddCandidateToPortfolio(
    positions: Map<String, OpenPosition>,
    candidate: EntryCandidate,
    config: ResearchConfig,
    counters: PortfolioConstraintCounters
): Boolean {
    counters.candidateEntries += 1
    val positionKey = "${candidate.row.exchange}|${candidate.row.symbol}"
    if (positions.containsKey(positionKey)) {
        counters.rejectedOpenSymbol += 1
        return false
    }

    val grossAfter = positions.size + 1
    if (grossAfter > config.maxConcurrentPositions) {
        counters.rejectedGrossLimit += 1
        return false
    }

    val longAfter = positions.values.count { it.side > 0 } + if (candidate.side > 0) 1 else 0
    val shortAfter = positions.values.count { it.side < 0 } + if (candidate.side < 0) 1 else 0
    if (candidate.side > 0 && longAfter > effectiveLongCapacity(config)) {
        counters.rejectedLongLimit += 1
        return false
    }
    if (candidate.side < 0 && shortAfter > effectiveShortCapacity(config)) {
        counters.rejectedShortLimit += 1
        return false
    }

    val capacity = max(config.maxConcurrentPositions, 1).toDouble()
    val candidateNetContribution = candidate.side.toDouble() * candidate.targetExposureFraction
    val nextNetFraction = abs(currentNetUnits(positions.values) + candidateNetContribution) / capacity
    if (nextNetFraction > config.maxNetExposureFraction + 1e-9) {
        counters.rejectedNetLimit += 1
        return false
    }

    val nextBetaBtc = abs(currentBetaBtcUnits(positions.values) + (candidateNetContribution * candidate.row.betaBtc)) / capacity
    val nextBetaEth = abs(currentBetaEthUnits(positions.values) + (candidateNetContribution * candidate.row.betaEth)) / capacity
    if (nextBetaBtc > config.maxPortfolioBetaBtcAbs + 1e-9 || nextBetaEth > config.maxPortfolioBetaEthAbs + 1e-9) {
        counters.rejectedBetaLimit += 1
        return false
    }

    counters.acceptedEntries += 1
    return true
}

private fun buildPortfolioProfile(
    kind: StrategyKind,
    stage: String,
    exchanges: List<String>,
    trades: List<TradeRecord>,
    telemetry: List<PortfolioTelemetryPoint>,
    counters: PortfolioConstraintCounters,
    config: ResearchConfig
): PortfolioProfileSnapshot {
    val samples = if (telemetry.isEmpty()) {
        listOf(PortfolioTelemetryPoint(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    } else {
        telemetry
    }
    val capacity = max(config.maxConcurrentPositions, 1).toDouble()
    val grossSeries = samples.map { it.grossPositions.toDouble() }
    val longSeries = samples.map { it.longPositions.toDouble() }
    val shortSeries = samples.map { it.shortPositions.toDouble() }
    val grossExposureSeries = samples.map { it.grossExposureUnits }
    val netSeries = samples.map { abs(it.netExposureUnits) }
    val betaBtcSeries = samples.map { abs(it.betaBtcUnits) / capacity }
    val betaEthSeries = samples.map { abs(it.betaEthUnits) / capacity }
    val utilizationSeries = samples.map { it.grossExposureUnits / capacity }

    return PortfolioProfileSnapshot(
        strategyKind = kind.name.lowercase(),
        stage = stage,
        exchanges = exchanges,
        trades = trades.size,
        policyMaxConcurrentPositions = config.maxConcurrentPositions,
        policyMaxConcurrentLongs = effectiveLongCapacity(config),
        policyMaxConcurrentShorts = effectiveShortCapacity(config),
        policyMaxNetExposureFraction = config.maxNetExposureFraction.round(4),
        policyMaxAbsBetaBtc = config.maxPortfolioBetaBtcAbs.round(4),
        policyMaxAbsBetaEth = config.maxPortfolioBetaEthAbs.round(4),
        maxConcurrentPositions = samples.maxOfOrNull { it.grossPositions } ?: 0,
        maxConcurrentLongs = samples.maxOfOrNull { it.longPositions } ?: 0,
        maxConcurrentShorts = samples.maxOfOrNull { it.shortPositions } ?: 0,
        avgConcurrentPositions = mean(grossSeries).round(4),
        avgConcurrentLongs = mean(longSeries).round(4),
        avgConcurrentShorts = mean(shortSeries).round(4),
        maxGrossExposureUsd = ((samples.maxOfOrNull { it.grossExposureUnits } ?: 0.0) * config.notionalUsd).round(4),
        avgGrossExposureUsd = (mean(grossExposureSeries) * config.notionalUsd).round(4),
        maxNetExposureUsd = ((samples.maxOfOrNull { abs(it.netExposureUnits) } ?: 0.0) * config.notionalUsd).round(4),
        avgNetExposureUsd = (mean(netSeries) * config.notionalUsd).round(4),
        maxAbsNetExposureFraction = (netSeries.maxOrNull()?.div(capacity) ?: 0.0).round(4),
        avgAbsNetExposureFraction = (mean(netSeries) / capacity).round(4),
        maxAbsBetaBtc = (betaBtcSeries.maxOrNull() ?: 0.0).round(4),
        avgAbsBetaBtc = mean(betaBtcSeries).round(4),
        maxAbsBetaEth = (betaEthSeries.maxOrNull() ?: 0.0).round(4),
        avgAbsBetaEth = mean(betaEthSeries).round(4),
        avgCapacityUtilization = mean(utilizationSeries).round(4),
        maxCapacityUtilization = (utilizationSeries.maxOrNull() ?: 0.0).round(4),
        entryConstraints = PortfolioConstraintSnapshot(
            candidateEntries = counters.candidateEntries,
            acceptedEntries = counters.acceptedEntries,
            rejectedOpenSymbol = counters.rejectedOpenSymbol,
            rejectedGrossLimit = counters.rejectedGrossLimit,
            rejectedLongLimit = counters.rejectedLongLimit,
            rejectedShortLimit = counters.rejectedShortLimit,
            rejectedNetLimit = counters.rejectedNetLimit,
            rejectedBetaLimit = counters.rejectedBetaLimit
        )
    )
}

private fun buildOpenPosition(
    strategyName: String,
    kind: StrategyKind,
    candidate: EntryCandidate
): OpenPosition =
    OpenPosition(
        strategyName = strategyName,
        strategyKind = kind,
        exchange = candidate.row.exchange,
        symbol = candidate.row.symbol,
        side = candidate.side,
        entryRow = candidate.row,
        entryEstimate = candidate.entryEstimate,
        expectedGrossEdgeBps = candidate.expectedGrossEdgeBps,
        expectedRoundTripCostBps = candidate.expectedRoundTripCostBps,
        expectedNetEdgeBps = candidate.expectedNetEdgeBps,
        targetExposureFraction = candidate.targetExposureFraction,
        calibrationSamples = candidate.calibrationSamples,
        calibrationWinRate = candidate.calibrationWinRate,
        calibrationLowerBoundBps = candidate.calibrationLowerBoundBps,
        calibrationScope = candidate.calibrationScope,
        maxFavorableReturnFraction = 0.0
    )

private fun shouldRebalancePosition(
    existing: OpenPosition,
    candidate: EntryCandidate,
    config: ResearchConfig
): Boolean =
    existing.side == candidate.side &&
        candidate.row.barIndex > existing.entryRow.barIndex &&
        abs(candidate.targetExposureFraction - existing.targetExposureFraction) >= config.rebalanceTargetExposureStep

private fun simulateStrategyWithPortfolio(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    stage: String,
    bucketCandidates: (List<FeatureRow>, Instant) -> List<EntryCandidate>
): StrategySimulationResult {
    if (rows.isEmpty()) {
        return StrategySimulationResult(
            trades = emptyList(),
            portfolioProfile = buildPortfolioProfile(
                kind = kind,
                stage = stage,
                exchanges = emptyList(),
                trades = emptyList(),
                telemetry = emptyList(),
                counters = PortfolioConstraintCounters(),
                config = config
            )
        )
    }

    val grouped = rows.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val positions = mutableMapOf<String, OpenPosition>()
    val cooldownUntilBar = mutableMapOf<String, Int>()
    val trades = mutableListOf<TradeRecord>()
    val telemetry = mutableListOf<PortfolioTelemetryPoint>()
    val counters = PortfolioConstraintCounters()

    for (key in orderedKeys) {
        val exchange = key.first
        val currentTime = key.second
        val bucket = grouped[key].orEmpty()
        val rowBySymbol = bucket.associateBy { it.symbol }

        for ((positionKey, position) in positions.toMap()) {
            if (position.exchange != exchange) continue
            val current = rowBySymbol[position.symbol] ?: continue
            val updatedPosition = updateOpenPosition(position, current)
            positions[positionKey] = updatedPosition
            if (!shouldExitPosition(kind, updatedPosition, current, config)) continue
            trades += buildTradeRecord(updatedPosition, current, config)
            positions.remove(positionKey)
            cooldownUntilBar[positionKey] = current.barIndex + when (kind) {
                StrategyKind.TREND -> config.trendCooldownBars
                StrategyKind.REVERSION -> config.reversionCooldownBars
            }
        }

        val pendingCandidates = bucketCandidates(bucket, currentTime).toMutableList()
        while (pendingCandidates.isNotEmpty()) {
            val candidate = pendingCandidates.maxWithOrNull(
                compareBy<EntryCandidate>(
                    { portfolioAcceptanceScore(positions.values, it, config) },
                    { it.expectedNetEdgeBps },
                    { -it.row.barIndex },
                    { it.row.symbol }
                )
            ) ?: break
            pendingCandidates.remove(candidate)

            val positionKey = "${candidate.row.exchange}|${candidate.row.symbol}"
            val existingPosition = positions[positionKey]
            if (existingPosition != null && shouldRebalancePosition(existingPosition, candidate, config)) {
                trades += buildTradeRecord(updateOpenPosition(existingPosition, candidate.row), candidate.row, config)
                positions.remove(positionKey)
                positions[positionKey] = buildOpenPosition(strategyName, kind, candidate)
                continue
            }
            if ((cooldownUntilBar[positionKey] ?: Int.MIN_VALUE) > candidate.row.barIndex) {
                continue
            }
            if (!canAddCandidateToPortfolio(positions, candidate, config, counters)) {
                continue
            }
            positions[positionKey] = buildOpenPosition(strategyName, kind, candidate)
        }

        telemetry += portfolioTelemetryPoint(positions.values)
    }

    val latestByExchangeSymbol = rows.groupBy { it.exchange to it.symbol }
        .mapValues { (_, series) -> series.maxByOrNull { it.time } }

    for ((positionKey, position) in positions.toMap()) {
        val current = latestByExchangeSymbol[position.exchange to position.symbol] ?: continue
        if (current.time == position.entryRow.time) continue
        trades += buildTradeRecord(updateOpenPosition(position, current), current, config)
        positions.remove(positionKey)
    }

    return StrategySimulationResult(
        trades = trades.sortedBy { it.entryTime },
        portfolioProfile = buildPortfolioProfile(
            kind = kind,
            stage = stage,
            exchanges = rows.map { it.exchange }.distinct().sorted(),
            trades = trades,
            telemetry = telemetry,
            counters = counters,
            config = config
        )
    )
}

internal fun simulateStrategyResult(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null,
    stage: String = "backtest"
): StrategySimulationResult =
    simulateStrategyWithPortfolio(
        strategyName = strategyName,
        kind = kind,
        rows = rows,
        config = config,
        stage = stage
    ) { bucket, _ ->
        candidateRows(kind, bucket, config, calibrationState)
    }

fun simulateStrategy(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): List<TradeRecord> =
    simulateStrategyResult(strategyName, kind, rows, config, calibrationState).trades

internal fun simulateStrategyWalkForwardResult(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    seedExamples: List<CalibrationExample> = emptyList(),
    stage: String = "forward"
): StrategySimulationResult {
    if (rows.isEmpty()) {
        return StrategySimulationResult(
            trades = emptyList(),
            portfolioProfile = buildPortfolioProfile(
                kind = kind,
                stage = stage,
                exchanges = emptyList(),
                trades = emptyList(),
                telemetry = emptyList(),
                counters = PortfolioConstraintCounters(),
                config = config
            )
        )
    }

    val calibrationExamples = buildCalibrationExamples(strategyName, kind, rows, config)
    val calibrationState = buildCalibrationState(seedExamples)
    val activeExamples = ArrayDeque<CalibrationExample>()
    seedExamples
        .sortedBy { it.availableAt }
        .forEach(activeExamples::addLast)
    var exampleIndex = 0

    return simulateStrategyWithPortfolio(
        strategyName = strategyName,
        kind = kind,
        rows = rows,
        config = config,
        stage = stage
    ) { bucket, currentTime ->
        while (exampleIndex < calibrationExamples.size && calibrationExamples[exampleIndex].availableAt.isBefore(currentTime)) {
            val example = calibrationExamples[exampleIndex]
            activeExamples.addLast(example)
            addCalibrationExample(calibrationState, example)
            exampleIndex += 1
        }
        val cutoff = currentTime.minus(config.calibrationLookbackHours.toLong(), ChronoUnit.HOURS)
        while (activeExamples.isNotEmpty() && activeExamples.first().availableAt.isBefore(cutoff)) {
            removeCalibrationExample(calibrationState, activeExamples.removeFirst())
        }
        candidateRows(kind, bucket, config, calibrationState)
    }
}

fun simulateStrategyWalkForward(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig
): List<TradeRecord> =
    simulateStrategyWalkForwardResult(strategyName, kind, rows, config).trades

fun buildStrategySummaries(
    config: ResearchConfig,
    strategyName: String,
    strategyKind: StrategyKind,
    trades: List<TradeRecord>,
    timeframe: String,
    notes: String
): List<StrategySummary> {
    if (trades.isEmpty()) return emptyList()

    fun summarize(exchange: String, symbol: String, bucket: List<TradeRecord>): StrategySummary {
        val sorted = bucket.sortedBy { it.entryTime }
        var equity = 1.0
        var peak = 1.0
        var maxDrawdown = 0.0
        val returns = mutableListOf<Double>()
        sorted.forEach { trade ->
            equity *= (1.0 + trade.netReturnFraction)
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, 1.0 - (equity / peak))
            returns += trade.netReturnFraction
        }
        val netReturnPct = ((equity - 1.0) * 100.0).round(4)
        val winRate = sorted.count { it.netReturnFraction > 0.0 }.toDouble() / sorted.size.toDouble()
        val sharpe = run {
            val sigma = stdev(returns)
            if (sigma < 1e-9) 0.0 else (mean(returns) / sigma) * sqrt(sorted.size.toDouble())
        }
        val avgEdgeAfterCostBps = mean(sorted.map { it.edgeAfterCostBps })
        val avgTotalCostBps = mean(sorted.map { it.totalCostBps })
        val avgSlippageBps = mean(sorted.map { it.slippageBps })
        val avgFillRatio = mean(sorted.map { it.fillRatio })
        val avgSubmitToFillMs = mean(sorted.map { it.submitToFillMs })
        val avgBetaBtc = mean(sorted.map { it.betaBtc })
        val avgBetaEth = mean(sorted.map { it.betaEth })
        val avgExpectedGrossEdgeBps = mean(sorted.map { it.expectedGrossEdgeBps })
        val avgExpectedNetEdgeBps = mean(sorted.map { it.expectedNetEdgeBps })
        val edgePredictionErrorBps = avgEdgeAfterCostBps - avgExpectedNetEdgeBps
        val avgCalibrationSamples = mean(sorted.map { it.calibrationSamples.toDouble() })
        val avgCalibrationWinRate = mean(sorted.map { it.calibrationWinRate })
        val avgCalibrationLowerBoundBps = mean(sorted.map { it.calibrationLowerBoundBps })
        val dominantCalibrationScope = sorted.groupingBy { it.calibrationScope }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: "heuristic"
        val avgEntryImbalance = mean(sorted.map { it.entryImbalance })
        val avgEntryFlowSignal = mean(sorted.map { it.entryFlowSignal })
        val avgEntryVolumeRatio = mean(sorted.map { it.entryVolumeRatio })
        val avgEntryVolRegime = mean(sorted.map { it.entryVolRegime })
        val metricsJson = gson.toJson(
            mapOf(
                "exchange" to exchange,
                "strategyKind" to strategyKind.name.lowercase(),
                "bar_minutes" to config.barMinutes,
                "avg_edge_after_cost_bps" to avgEdgeAfterCostBps.round(4),
                "avg_expected_gross_edge_bps" to avgExpectedGrossEdgeBps.round(4),
                "avg_expected_net_edge_bps" to avgExpectedNetEdgeBps.round(4),
                "edge_prediction_error_bps" to edgePredictionErrorBps.round(4),
                "avg_total_cost_bps" to avgTotalCostBps.round(4),
                "avg_slippage_bps" to avgSlippageBps.round(4),
                "avg_fill_ratio" to avgFillRatio.round(4),
                "avg_submit_to_fill_ms" to avgSubmitToFillMs.round(4),
                "avg_beta_btc" to avgBetaBtc.round(4),
                "avg_beta_eth" to avgBetaEth.round(4),
                "avg_calibration_samples" to avgCalibrationSamples.round(4),
                "avg_calibration_win_rate" to avgCalibrationWinRate.round(4),
                "avg_calibration_lower_bound_bps" to avgCalibrationLowerBoundBps.round(4),
                "dominant_calibration_scope" to dominantCalibrationScope,
                "avg_entry_imbalance" to avgEntryImbalance.round(4),
                "avg_entry_flow_signal" to avgEntryFlowSignal.round(4),
                "avg_entry_volume_ratio" to avgEntryVolumeRatio.round(4),
                "avg_entry_vol_regime" to avgEntryVolRegime.round(4),
                "trend_trailing_stop_vol_multiple" to config.trendTrailingStopVolMultiple.round(4),
                "reversion_trailing_stop_vol_multiple" to config.reversionTrailingStopVolMultiple.round(4),
                "trend_take_profit_vol_multiple" to config.trendTakeProfitVolMultiple.round(4),
                "reversion_take_profit_vol_multiple" to config.reversionTakeProfitVolMultiple.round(4),
                "source" to "cross-sectional-beta-kotlin"
            )
        )
        return StrategySummary(
            strategyName = strategyName,
            strategyKind = strategyKind.name.lowercase(),
            exchange = exchange,
            symbol = symbol,
            timeframe = timeframe,
            startTime = sorted.first().entryTime,
            endTime = sorted.last().exitTime,
            trades = sorted.size,
            winRate = winRate,
            netReturnPct = netReturnPct,
            maxDrawdownPct = (maxDrawdown * 100.0).round(4),
            sharpe = sharpe.round(4),
            avgEdgeAfterCostBps = avgEdgeAfterCostBps.round(4),
            avgTotalCostBps = avgTotalCostBps.round(4),
            avgSlippageBps = avgSlippageBps.round(4),
            avgFillRatio = avgFillRatio.round(4),
            avgSubmitToFillMs = avgSubmitToFillMs.round(4),
            notes = notes,
            metricsJson = metricsJson
        )
    }

    val perSymbol = trades.groupBy { it.exchange to it.symbol }
        .map { (key, bucket) -> summarize(key.first, key.second, bucket) }
    val perExchange = trades.groupBy { it.exchange }
        .map { (exchange, bucket) -> summarize(exchange, "ALL", bucket) }
    return perSymbol + perExchange
}

private fun effectiveSliceCount(counts: List<Int>): Double {
    val total = counts.sum().toDouble()
    if (total <= 0.0) return 0.0
    val hhi = counts.sumOf { count ->
        val share = count.toDouble() / total
        share * share
    }
    if (hhi <= 0.0) return 0.0
    return (1.0 / hhi).round(4)
}

private fun summarizeTradeSlice(label: String, trades: List<TradeRecord>): StrategySliceSnapshot {
    val sorted = trades.sortedBy { it.entryTime }
    var equity = 1.0
    var peak = 1.0
    var maxDrawdown = 0.0
    sorted.forEach { trade ->
        equity *= (1.0 + trade.netReturnFraction)
        peak = max(peak, equity)
        maxDrawdown = max(maxDrawdown, 1.0 - (equity / peak))
    }
    return StrategySliceSnapshot(
        label = label,
        trades = sorted.size,
        winRate = (sorted.count { it.netReturnFraction > 0.0 }.toDouble() / max(sorted.size, 1).toDouble()).round(4),
        netReturnPct = ((equity - 1.0) * 100.0).round(4),
        maxDrawdownPct = (maxDrawdown * 100.0).round(4),
        avgEdgeAfterCostBps = mean(sorted.map { it.edgeAfterCostBps }).round(4),
        avgFillRatio = mean(sorted.map { it.fillRatio }).round(4)
    )
}

fun computeStrategyRobustness(
    kind: StrategyKind,
    trades: List<TradeRecord>
): StrategyRobustnessSnapshot? {
    if (trades.isEmpty()) return null

    val totalTrades = trades.size
    val multipleExchanges = trades.map { it.exchange }.distinct().size > 1
    val symbolSlices = trades.groupBy { it.exchange to it.symbol }
        .map { (key, bucket) ->
            val label = if (multipleExchanges) "${key.first}:${key.second}" else key.second
            summarizeTradeSlice(label, bucket)
        }
        .sortedWith(
            compareByDescending<StrategySliceSnapshot> { it.trades }
                .thenByDescending { it.avgEdgeAfterCostBps }
                .thenBy { it.label }
        )
    val regimeOrder = mapOf("calm" to 0, "normal" to 1, "stress" to 2)
    val regimeSlices = trades.groupBy(::tradeRegimeBucket)
        .map { (bucket, bucketTrades) -> summarizeTradeSlice(bucket, bucketTrades) }
        .sortedWith(
            compareBy<StrategySliceSnapshot> { regimeOrder[it.label] ?: Int.MAX_VALUE }
                .thenByDescending { it.trades }
        )

    val symbolCount = symbolSlices.size
    val regimeCount = regimeSlices.size
    val effectiveSymbolCount = effectiveSliceCount(symbolSlices.map { it.trades })
    val effectiveRegimeCount = effectiveSliceCount(regimeSlices.map { it.trades })
    val largestSymbolTradeShare =
        ((symbolSlices.maxOfOrNull { it.trades } ?: 0).toDouble() / max(totalTrades, 1).toDouble()).round(4)
    val largestRegimeTradeShare =
        ((regimeSlices.maxOfOrNull { it.trades } ?: 0).toDouble() / max(totalTrades, 1).toDouble()).round(4)
    val profitableSymbolShare = if (symbolCount == 0) {
        0.0
    } else {
        symbolSlices.count { it.netReturnPct > 0.0 && it.avgEdgeAfterCostBps > 0.0 }.toDouble() / symbolCount.toDouble()
    }.round(4)
    val profitableRegimeShare = if (regimeCount == 0) {
        0.0
    } else {
        regimeSlices.count { it.netReturnPct > 0.0 && it.avgEdgeAfterCostBps > 0.0 }.toDouble() / regimeCount.toDouble()
    }.round(4)
    val worstSymbolNetReturnPct = (symbolSlices.minOfOrNull { it.netReturnPct } ?: 0.0).round(4)
    val worstSymbolEdgeAfterCostBps = (symbolSlices.minOfOrNull { it.avgEdgeAfterCostBps } ?: 0.0).round(4)
    val worstRegimeNetReturnPct = (regimeSlices.minOfOrNull { it.netReturnPct } ?: 0.0).round(4)
    val worstRegimeEdgeAfterCostBps = (regimeSlices.minOfOrNull { it.avgEdgeAfterCostBps } ?: 0.0).round(4)

    val normalizedSymbolBreadth = if (symbolCount <= 1) {
        0.0
    } else {
        clamp((effectiveSymbolCount - 1.0) / (symbolCount.toDouble() - 1.0), 0.0, 1.0)
    }
    val normalizedRegimeBreadth = if (regimeCount <= 1) {
        0.0
    } else {
        clamp((effectiveRegimeCount - 1.0) / (regimeCount.toDouble() - 1.0), 0.0, 1.0)
    }
    val worstSlicePenalty = clamp(
        (max(0.0, -worstSymbolEdgeAfterCostBps) + max(0.0, -worstRegimeEdgeAfterCostBps)) / 16.0,
        0.0,
        1.0
    )
    val stabilityScore = (
        (normalizedSymbolBreadth * 30.0) +
            (normalizedRegimeBreadth * 25.0) +
            (profitableSymbolShare * 20.0) +
            (profitableRegimeShare * 15.0) +
            ((1.0 - worstSlicePenalty) * 10.0)
        ).round(4)

    return StrategyRobustnessSnapshot(
        strategyKind = kind.name.lowercase(),
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
        symbolSlices = symbolSlices,
        regimeSlices = regimeSlices
    )
}


