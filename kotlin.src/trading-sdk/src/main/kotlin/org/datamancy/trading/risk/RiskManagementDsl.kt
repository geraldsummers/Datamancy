package org.datamancy.trading.risk

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Risk Management DSL - Position sizing, stops, portfolio limits
 *
 * Example:
 * ```kotlin
 * val risk = riskManagement {
 *     sizing {
 *         kellyFraction(0.25)
 *         maxPositionPercent(10.0)
 *     }
 *
 *     exits {
 *         stopLoss {
 *             atrBased(multiplier = 2.0)
 *             maxLoss(percent = 2.0)
 *         }
 *         takeProfit {
 *             riskReward(ratio = 3.0)
 *         }
 *     }
 *
 *     portfolio {
 *         maxDrawdown(10.percent)
 *         dailyLossLimit(5.percent)
 *     }
 * }
 *
 * val size = risk.calculatePositionSize(
 *     equity = 100_000.toBigDecimal(),
 *     stopDistance = 50.toBigDecimal(),
 *     atr = 100.toBigDecimal()
 * )
 * ```
 */

// ============================================================================
// Position Sizing
// ============================================================================

sealed class PositionSizingMethod {
    data class Fixed(val size: BigDecimal) : PositionSizingMethod()
    data class FixedPercent(val percent: BigDecimal) : PositionSizingMethod()
    data class Kelly(val fraction: BigDecimal, val winRate: BigDecimal, val avgWin: BigDecimal, val avgLoss: BigDecimal) : PositionSizingMethod()
    data class ATRBased(val atrMultiplier: BigDecimal, val riskPercent: BigDecimal) : PositionSizingMethod()
    data class VolatilityBased(val targetVolatility: BigDecimal) : PositionSizingMethod()
}

class PositionSizingConfig {
    internal var method: PositionSizingMethod = PositionSizingMethod.FixedPercent(BigDecimal.valueOf(2.0))
    internal var maxPositionPercent: BigDecimal = BigDecimal.valueOf(10.0)
    internal var minPositionSize: BigDecimal = BigDecimal.valueOf(0.01)

    fun fixed(size: BigDecimal) {
        method = PositionSizingMethod.Fixed(size)
    }

    fun fixedPercent(percent: Double) {
        method = PositionSizingMethod.FixedPercent(BigDecimal.valueOf(percent))
    }

    fun kellyFraction(fraction: Double, winRate: Double = 0.5, avgWin: Double = 1.0, avgLoss: Double = 1.0) {
        method = PositionSizingMethod.Kelly(
            BigDecimal.valueOf(fraction),
            BigDecimal.valueOf(winRate),
            BigDecimal.valueOf(avgWin),
            BigDecimal.valueOf(avgLoss)
        )
    }

    fun atrBased(atrMultiplier: Double, riskPercent: Double = 1.0) {
        method = PositionSizingMethod.ATRBased(
            BigDecimal.valueOf(atrMultiplier),
            BigDecimal.valueOf(riskPercent)
        )
    }

    fun volatilityTargeting(targetVolatility: Double) {
        method = PositionSizingMethod.VolatilityBased(BigDecimal.valueOf(targetVolatility))
    }

    fun maxPositionPercent(percent: Double) {
        maxPositionPercent = BigDecimal.valueOf(percent)
    }

    fun minPositionSize(size: Double) {
        minPositionSize = BigDecimal.valueOf(size)
    }
}

// ============================================================================
// Stop Loss Configuration
// ============================================================================

sealed class StopLossMethod {
    data class Fixed(val distance: BigDecimal) : StopLossMethod()
    data class Percent(val percent: BigDecimal) : StopLossMethod()
    data class ATRBased(val multiplier: BigDecimal) : StopLossMethod()
    data class TrailingPercent(val percent: BigDecimal) : StopLossMethod()
    data class TrailingATR(val multiplier: BigDecimal) : StopLossMethod()
}

class StopLossConfig {
    internal val methods = mutableListOf<StopLossMethod>()
    internal var maxLossPercent: BigDecimal? = null

    fun fixed(distance: BigDecimal) {
        methods.add(StopLossMethod.Fixed(distance))
    }

    fun percent(percent: Double) {
        methods.add(StopLossMethod.Percent(BigDecimal.valueOf(percent)))
    }

    fun atrBased(multiplier: Double) {
        methods.add(StopLossMethod.ATRBased(BigDecimal.valueOf(multiplier)))
    }

    fun trailing(distance: BigDecimal) {
        methods.add(StopLossMethod.TrailingPercent(distance))
    }

    fun trailingATR(multiplier: Double) {
        methods.add(StopLossMethod.TrailingATR(BigDecimal.valueOf(multiplier)))
    }

    fun maxLoss(percent: Double) {
        maxLossPercent = BigDecimal.valueOf(percent)
    }
}

// ============================================================================
// Take Profit Configuration
// ============================================================================

data class TakeProfitTarget(
    val distance: BigDecimal, // Multiple of risk
    val sizePercent: BigDecimal // What % of position to close
)

class TakeProfitConfig {
    internal val targets = mutableListOf<TakeProfitTarget>()
    internal var riskRewardRatio: BigDecimal? = null

    fun target(distance: Double, sizePercent: Double = 100.0) {
        targets.add(TakeProfitTarget(
            BigDecimal.valueOf(distance),
            BigDecimal.valueOf(sizePercent)
        ))
    }

    fun targets(vararg pairs: Pair<Double, Double>) {
        pairs.forEach { (distance, percent) ->
            target(distance, percent)
        }
    }

    fun riskReward(ratio: Double) {
        riskRewardRatio = BigDecimal.valueOf(ratio)
    }
}

class ExitConfig {
    internal var stopLossConfig: StopLossConfig? = null
    internal var takeProfitConfig: TakeProfitConfig? = null

    fun stopLoss(config: StopLossConfig.() -> Unit) {
        stopLossConfig = StopLossConfig().apply(config)
    }

    fun takeProfit(config: TakeProfitConfig.() -> Unit) {
        takeProfitConfig = TakeProfitConfig().apply(config)
    }
}

// ============================================================================
// Portfolio Risk Configuration
// ============================================================================

class PortfolioRiskConfig {
    internal var maxDrawdownPercent: BigDecimal = BigDecimal.valueOf(20.0)
    internal var maxOpenPositions: Int = 10
    internal var maxCorrelation: BigDecimal = BigDecimal.valueOf(0.8)
    internal var dailyLossLimitPercent: BigDecimal? = null
    internal var maxLeveragePerPosition: BigDecimal = BigDecimal.ONE

    fun maxDrawdown(percent: BigDecimal) {
        maxDrawdownPercent = percent
    }

    fun maxOpenPositions(count: Int) {
        maxOpenPositions = count
    }

    fun maxCorrelation(value: Double) {
        maxCorrelation = BigDecimal.valueOf(value)
    }

    fun dailyLossLimit(percent: BigDecimal) {
        dailyLossLimitPercent = percent
    }

    fun maxLeverage(leverage: Double) {
        maxLeveragePerPosition = BigDecimal.valueOf(leverage)
    }
}

// ============================================================================
// Main Risk Management DSL
// ============================================================================

class RiskManagement internal constructor() {
    internal var sizingConfig = PositionSizingConfig()
    internal var exitConfig = ExitConfig()
    internal var portfolioConfig = PortfolioRiskConfig()

    fun sizing(config: PositionSizingConfig.() -> Unit) {
        sizingConfig = PositionSizingConfig().apply(config)
    }

    fun exits(config: ExitConfig.() -> Unit) {
        exitConfig = ExitConfig().apply(config)
    }

    fun portfolio(config: PortfolioRiskConfig.() -> Unit) {
        portfolioConfig = PortfolioRiskConfig().apply(config)
    }

    /**
     * Calculate position size based on configured risk parameters
     */
    fun calculatePositionSize(
        equity: BigDecimal,
        entryPrice: BigDecimal,
        stopDistance: BigDecimal? = null,
        atr: BigDecimal? = null
    ): BigDecimal {
        val rawSize = when (val method = sizingConfig.method) {
            is PositionSizingMethod.Fixed -> method.size

            is PositionSizingMethod.FixedPercent -> {
                val value = equity * method.percent / BigDecimal.valueOf(100)
                value.divide(entryPrice, 18, RoundingMode.HALF_UP)
            }

            is PositionSizingMethod.Kelly -> {
                // Kelly Criterion: f = (bp - q) / b
                // where b = avgWin/avgLoss, p = winRate, q = 1-p
                val b = method.avgWin / method.avgLoss
                val p = method.winRate
                val q = BigDecimal.ONE - p
                val kellyPercent = (b * p - q) / b
                val adjustedKelly = kellyPercent * method.fraction // Apply fraction for safety
                equity * adjustedKelly / entryPrice
            }

            is PositionSizingMethod.ATRBased -> {
                requireNotNull(atr) { "ATR required for ATR-based sizing" }
                val riskAmount = equity * method.riskPercent / BigDecimal.valueOf(100)
                val stopDist = atr * method.atrMultiplier
                riskAmount.divide(stopDist, 18, RoundingMode.HALF_UP)
            }

            is PositionSizingMethod.VolatilityBased -> {
                requireNotNull(atr) { "ATR required for volatility-based sizing" }
                val targetValue = equity * method.targetVolatility / BigDecimal.valueOf(100)
                targetValue / entryPrice
            }
        }

        // Apply constraints
        val maxSize = equity * sizingConfig.maxPositionPercent / BigDecimal.valueOf(100) / entryPrice
        val constrainedSize = rawSize.min(maxSize).max(sizingConfig.minPositionSize)

        return constrainedSize.setScale(8, RoundingMode.DOWN)
    }

    /**
     * Calculate stop loss price
     */
    fun calculateStopLoss(
        entryPrice: BigDecimal,
        side: String, // "long" or "short"
        atr: BigDecimal? = null
    ): BigDecimal? {
        val stopConfig = exitConfig.stopLossConfig ?: return null
        if (stopConfig.methods.isEmpty()) return null

        val method = stopConfig.methods.first() // Use first method
        val distance = when (method) {
            is StopLossMethod.Fixed -> method.distance
            is StopLossMethod.Percent -> entryPrice * method.percent / BigDecimal.valueOf(100)
            is StopLossMethod.ATRBased -> {
                requireNotNull(atr) { "ATR required for ATR-based stop loss" }
                atr * method.multiplier
            }
            is StopLossMethod.TrailingPercent -> method.percent // Initial stop
            is StopLossMethod.TrailingATR -> {
                requireNotNull(atr) { "ATR required for trailing ATR stop" }
                atr * method.multiplier
            }
        }

        return if (side == "long") {
            entryPrice - distance
        } else {
            entryPrice + distance
        }
    }

    /**
     * Calculate take profit price
     */
    fun calculateTakeProfit(
        entryPrice: BigDecimal,
        stopPrice: BigDecimal,
        side: String
    ): List<Pair<BigDecimal, BigDecimal>> { // Returns (price, sizePercent) pairs
        val tpConfig = exitConfig.takeProfitConfig ?: return emptyList()

        val risk = (entryPrice - stopPrice).abs()

        return if (tpConfig.targets.isNotEmpty()) {
            tpConfig.targets.map { target ->
                val distance = risk * target.distance
                val price = if (side == "long") {
                    entryPrice + distance
                } else {
                    entryPrice - distance
                }
                price to target.sizePercent
            }
        } else if (tpConfig.riskRewardRatio != null) {
            val distance = risk * tpConfig.riskRewardRatio!!
            val price = if (side == "long") {
                entryPrice + distance
            } else {
                entryPrice - distance
            }
            listOf(price to BigDecimal.valueOf(100))
        } else {
            emptyList()
        }
    }

    /**
     * Check if position passes risk checks
     */
    fun validatePosition(
        currentPositions: Int,
        newPositionValue: BigDecimal,
        totalEquity: BigDecimal
    ): RiskCheckResult {
        // Check max positions
        if (currentPositions >= portfolioConfig.maxOpenPositions) {
            return RiskCheckResult.Rejected("Max positions limit reached")
        }

        // Check position size limit
        val positionPercent = newPositionValue / totalEquity * BigDecimal.valueOf(100)
        if (positionPercent > sizingConfig.maxPositionPercent) {
            return RiskCheckResult.Rejected("Position size exceeds limit")
        }

        return RiskCheckResult.Approved
    }
}

sealed class RiskCheckResult {
    object Approved : RiskCheckResult()
    data class Rejected(val reason: String) : RiskCheckResult()
}

fun riskManagement(config: RiskManagement.() -> Unit): RiskManagement {
    return RiskManagement().apply(config)
}

// ============================================================================
// Percentage Extension
// ============================================================================

val Number.percent: BigDecimal
    get() = BigDecimal.valueOf(this.toDouble())
