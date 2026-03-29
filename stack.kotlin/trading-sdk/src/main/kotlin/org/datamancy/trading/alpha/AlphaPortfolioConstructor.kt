package org.datamancy.trading.alpha

import org.datamancy.trading.policy.TradingPolicy
import kotlin.math.abs
import kotlin.math.ceil

class AlphaPortfolioConstructor(
    private val policyProvider: () -> TradingPolicy
) {
    fun defaults(): AlphaPortfolioDefaults = AlphaDefaultsFactory.portfolioDefaults(policyProvider())

    fun construct(request: AlphaPortfolioRequest): AlphaPortfolioResponse {
        require(request.signals.isNotEmpty()) { "portfolio construction requires at least one signal" }
        val policy = policyProvider()
        val defaults = AlphaDefaultsFactory.portfolioDefaults(policy)
        val quantile = (request.selectionQuantile ?: policy.research.discovery.selectionQuantiles.first())
            .coerceIn(0.01, 0.50)
        val longShort = request.longShort ?: defaults.longShort
        val maxLongs = request.maxConcurrentLongs ?: defaults.maxConcurrentLongs
        val maxShorts = request.maxConcurrentShorts ?: defaults.maxConcurrentShorts
        val minExpectedNetEdgeBps = request.minExpectedNetEdgeBps ?: Double.NEGATIVE_INFINITY
        val signals = request.signals.map { signal ->
            val currentWeight = request.currentWeightsBySymbol[signal.symbol] ?: signal.currentWeightFraction
            signal.copy(currentWeightFraction = currentWeight)
        }
        val positive = signals.filter { it.score > 0.0 && directionalEdgeBps(it) >= minExpectedNetEdgeBps }
        val negative = signals.filter { it.score < 0.0 && directionalEdgeBps(it) >= minExpectedNetEdgeBps }
        val selectedLongs = selectSide(
            signals = positive,
            direction = AlphaDirection.LONG,
            respectProvidedSignalSet = request.respectProvidedSignalSet,
            quantile = quantile,
            maxCount = maxLongs,
            defaults = defaults
        )
        val selectedShorts = if (longShort) {
            selectSide(
                signals = negative,
                direction = AlphaDirection.SHORT,
                respectProvidedSignalSet = request.respectProvidedSignalSet,
                quantile = quantile,
                maxCount = maxShorts,
                defaults = defaults
            )
        } else {
            emptyList()
        }
        val selected = selectedLongs + selectedShorts
        require(selected.isNotEmpty()) { "portfolio construction found no eligible signals after filtering" }

        val averageConfidence = selected.map { it.confidence.coerceIn(0.0, 1.0) }.average()
        val exposureFraction = (
            defaults.minTargetExposureFraction +
                (defaults.maxTargetExposureFraction - defaults.minTargetExposureFraction) * averageConfidence
            ).coerceIn(defaults.minTargetExposureFraction, defaults.maxTargetExposureFraction)
        val grossTarget = (request.targetGrossFraction ?: defaults.targetGrossFraction) * exposureFraction
        val netTarget = (request.targetNetFraction ?: defaults.targetNetFraction).coerceIn(-grossTarget, grossTarget)
        val longTarget = if (longShort) ((grossTarget + netTarget) / 2.0).coerceAtLeast(0.0) else grossTarget
        val shortTarget = if (longShort) ((grossTarget - netTarget) / 2.0).coerceAtLeast(0.0) else 0.0
        val maxWeightPerSymbol = request.maxWeightPerSymbol ?: defaults.maxWeightPerSymbol

        val targets = mutableListOf<AlphaPortfolioTarget>()
        targets += allocateSide(
            signals = selectedLongs,
            direction = AlphaDirection.LONG,
            sideTarget = longTarget,
            maxWeightPerSymbol = maxWeightPerSymbol,
            defaults = defaults,
            exposureFraction = exposureFraction
        )
        targets += allocateSide(
            signals = selectedShorts,
            direction = AlphaDirection.SHORT,
            sideTarget = shortTarget,
            maxWeightPerSymbol = maxWeightPerSymbol,
            defaults = defaults,
            exposureFraction = exposureFraction
        )

        return AlphaPortfolioResponse(
            targets = targets.sortedWith(compareBy<AlphaPortfolioTarget> { it.direction.name }.thenByDescending { abs(it.weightFraction) }),
            selectedLongs = selectedLongs.size,
            selectedShorts = selectedShorts.size,
            targetExposureFraction = exposureFraction,
            targetGrossFraction = targets.sumOf { abs(it.weightFraction) },
            targetNetFraction = targets.sumOf {
                if (it.direction == AlphaDirection.LONG) it.weightFraction else -it.weightFraction
            },
            turnoverPenaltyBps = defaults.turnoverPenaltyBps,
            notes = listOf(
                "Exposure ramps with average confirmation instead of jumping straight to max size.",
                if (request.respectProvidedSignalSet) {
                    "Provided signals were treated as the already-eligible basket, so sizing preserved cross-sectional diversification while preferring incumbents that still retain net edge."
                } else {
                    "Weights are volatility-scaled, cost-aware, and retention-biased to diversify trend risk across the universe without unnecessary churn."
                }
            )
        )
    }

    private fun selectSide(
        signals: List<AlphaSignalScore>,
        direction: AlphaDirection,
        respectProvidedSignalSet: Boolean,
        quantile: Double,
        maxCount: Int,
        defaults: AlphaPortfolioDefaults
    ): List<AlphaSignalScore> {
        if (signals.isEmpty() || maxCount <= 0) return emptyList()
        val sorted = signals.sortedByDescending { selectionPriority(it, direction, defaults) }
        return if (respectProvidedSignalSet) {
            sorted.take(maxCount)
        } else {
            val candidateCount = ceil(sorted.size * quantile).toInt().coerceAtLeast(1)
            sorted.take(minOf(candidateCount, maxCount))
        }
    }

    private fun allocateSide(
        signals: List<AlphaSignalScore>,
        direction: AlphaDirection,
        sideTarget: Double,
        maxWeightPerSymbol: Double,
        defaults: AlphaPortfolioDefaults,
        exposureFraction: Double
    ): List<AlphaPortfolioTarget> {
        if (signals.isEmpty() || sideTarget <= 0.0) return emptyList()
        val rawWeights = signals.associateWith {
            val volatility = it.predictedVolatility.coerceAtLeast(0.0001)
            effectiveSizingEdgeBps(it, direction, defaults).coerceAtLeast(0.05) *
                it.confidence.coerceIn(0.0, 1.0) *
                it.liquidityScore.coerceIn(0.1, 1.0) / volatility
        }
        val totalRaw = rawWeights.values.sum().coerceAtLeast(0.0001)
        return signals.map { signal ->
            val normalized = rawWeights.getValue(signal) / totalRaw
            val weight = (sideTarget * normalized).coerceAtMost(maxWeightPerSymbol)
            val currentSigned = signal.currentWeightFraction
            val targetSigned = if (direction == AlphaDirection.LONG) weight else -weight
            val turnoverDeltaFraction = abs(targetSigned - currentSigned)
            val realizedTurnoverPenaltyBps = turnoverPenaltyBps(turnoverDeltaFraction, maxWeightPerSymbol, defaults)
            val adjustedExpectedNetEdgeBps = signal.expectedNetEdgeBps -
                (realizedTurnoverPenaltyBps - signal.expectedTurnoverPenaltyBps)
            AlphaPortfolioTarget(
                symbol = signal.symbol,
                direction = direction,
                weightFraction = weight,
                leverageMultiplier = 1.0 + signal.confidence.coerceIn(0.0, 1.0) * exposureFraction,
                confidence = signal.confidence.coerceIn(0.0, 1.0),
                score = signal.score,
                normalizedScore = normalized,
                expectedNetEdgeBps = adjustedExpectedNetEdgeBps,
                expectedCostBps = signal.expectedEntryCostBps + realizedTurnoverPenaltyBps,
                turnoverDeltaFraction = turnoverDeltaFraction,
                trailingStopVolMultiple = defaults.trailingStopVolMultiple,
                takeProfitVolMultiple = defaults.takeProfitVolMultiple,
                rationale = "${direction.name.lowercase()} edge=${signal.expectedNetEdgeBps}bps residual=${signal.expectedResidualReturnBps}bps current=${signal.currentWeightFraction} turnover=${turnoverDeltaFraction}"
            )
        }
    }

    private fun selectionPriority(
        signal: AlphaSignalScore,
        direction: AlphaDirection,
        defaults: AlphaPortfolioDefaults
    ): Double {
        val currentSigned = signal.currentWeightFraction
        val alignedIncumbent = when (direction) {
            AlphaDirection.LONG -> currentSigned > 1e-9
            AlphaDirection.SHORT -> currentSigned < -1e-9
        }
        val oppositeIncumbent = when (direction) {
            AlphaDirection.LONG -> currentSigned < -1e-9
            AlphaDirection.SHORT -> currentSigned > 1e-9
        }
        val retentionBonus = if (alignedIncumbent) signal.expectedTurnoverPenaltyBps else 0.0
        val flipPenalty = if (oppositeIncumbent) signal.expectedTurnoverPenaltyBps else 0.0
        return directionalEdgeBps(signal) + retentionBonus - flipPenalty + defaults.turnoverPenaltyBps * abs(currentSigned)
    }

    private fun effectiveSizingEdgeBps(
        signal: AlphaSignalScore,
        direction: AlphaDirection,
        defaults: AlphaPortfolioDefaults
    ): Double {
        val currentSigned = signal.currentWeightFraction
        val alignedIncumbent = when (direction) {
            AlphaDirection.LONG -> currentSigned > 1e-9
            AlphaDirection.SHORT -> currentSigned < -1e-9
        }
        return directionalEdgeBps(signal) + if (alignedIncumbent) {
            signal.expectedTurnoverPenaltyBps + defaults.turnoverPenaltyBps * abs(currentSigned)
        } else {
            0.0
        }
    }

    private fun turnoverPenaltyBps(
        turnoverDeltaFraction: Double,
        maxWeightPerSymbol: Double,
        defaults: AlphaPortfolioDefaults
    ): Double {
        if (turnoverDeltaFraction <= 1e-9) return 0.0
        return defaults.turnoverPenaltyBps * (turnoverDeltaFraction / maxWeightPerSymbol.coerceAtLeast(0.01))
    }

    private fun directionalEdgeBps(signal: AlphaSignalScore): Double = abs(
        signal.expectedNetEdgeBps.takeIf { it.isFinite() && abs(it) > 1e-9 } ?: signal.score
    )
}
