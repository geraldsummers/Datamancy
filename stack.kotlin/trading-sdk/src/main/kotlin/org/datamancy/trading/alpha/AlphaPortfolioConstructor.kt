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
        val candidateCount = ceil(request.signals.size * quantile).toInt().coerceAtLeast(1)
        val positive = request.signals.filter { it.score > 0.0 }
        val negative = request.signals.filter { it.score < 0.0 }
        val selectedLongs = positive.sortedByDescending { it.score }.take(minOf(candidateCount, maxLongs))
        val selectedShorts = if (longShort) {
            negative.sortedBy { it.score }.take(minOf(candidateCount, maxShorts))
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
                "Weights are volatility-scaled and confidence-adjusted to diversify trend risk across the universe."
            )
        )
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
            abs(it.score) * it.confidence.coerceIn(0.0, 1.0) * it.liquidityScore.coerceIn(0.1, 1.0) / volatility
        }
        val totalRaw = rawWeights.values.sum().coerceAtLeast(0.0001)
        return signals.map { signal ->
            val normalized = rawWeights.getValue(signal) / totalRaw
            val weight = (sideTarget * normalized).coerceAtMost(maxWeightPerSymbol)
            AlphaPortfolioTarget(
                symbol = signal.symbol,
                direction = direction,
                weightFraction = weight,
                leverageMultiplier = 1.0 + signal.confidence.coerceIn(0.0, 1.0) * exposureFraction,
                confidence = signal.confidence.coerceIn(0.0, 1.0),
                score = signal.score,
                normalizedScore = normalized,
                trailingStopVolMultiple = defaults.trailingStopVolMultiple,
                takeProfitVolMultiple = defaults.takeProfitVolMultiple,
                rationale = "${direction.name.lowercase()} rank built from score=${signal.score} confidence=${signal.confidence} volatility=${signal.predictedVolatility}"
            )
        }
    }
}
