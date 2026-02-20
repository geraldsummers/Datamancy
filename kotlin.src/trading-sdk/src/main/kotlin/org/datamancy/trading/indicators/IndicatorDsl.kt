package org.datamancy.trading.indicators

import org.datamancy.trading.data.Candle
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Indicator DSL - Technical indicators with fluent API
 *
 * Example:
 * ```kotlin
 * val indicators = indicators {
 *     val sma20 = sma(20)
 *     val sma50 = sma(50)
 *     val rsi = rsi(14)
 *     val atr = atr(14)
 *     val bbands = bollingerBands(20, 2.0)
 * }
 *
 * stream.onCandle { candle ->
 *     indicators.update(candle)
 *
 *     if (indicators.sma20 crossesAbove indicators.sma50) {
 *         // Golden cross!
 *     }
 * }
 * ```
 */

// ============================================================================
// Base Indicator Interface
// ============================================================================

interface Indicator<T> {
    val value: T?
    val isReady: Boolean
    fun update(candle: Candle)
    fun reset()
}

// ============================================================================
// Simple Moving Average (SMA)
// ============================================================================

class SMA(private val period: Int) : Indicator<BigDecimal> {
    private val prices = ArrayDeque<BigDecimal>(period)

    override var value: BigDecimal? = null
        private set

    override val isReady: Boolean
        get() = prices.size == period

    override fun update(candle: Candle) {
        prices.addLast(candle.close)
        if (prices.size > period) {
            prices.removeFirst()
        }

        if (isReady) {
            value = prices.fold(BigDecimal.ZERO) { sum, price -> sum + price } / BigDecimal.valueOf(period.toLong())
        }
    }

    override fun reset() {
        prices.clear()
        value = null
    }
}

// ============================================================================
// Exponential Moving Average (EMA)
// ============================================================================

class EMA(private val period: Int) : Indicator<BigDecimal> {
    private val multiplier = BigDecimal.valueOf(2.0 / (period + 1))
    private var initialized = false

    override var value: BigDecimal? = null
        private set

    override val isReady: Boolean
        get() = initialized

    override fun update(candle: Candle) {
        if (!initialized) {
            value = candle.close
            initialized = true
        } else {
            value = candle.close * multiplier + value!! * (BigDecimal.ONE - multiplier)
        }
    }

    override fun reset() {
        value = null
        initialized = false
    }
}

// ============================================================================
// Relative Strength Index (RSI)
// ============================================================================

class RSI(private val period: Int) : Indicator<BigDecimal> {
    private val gains = ArrayDeque<BigDecimal>(period)
    private val losses = ArrayDeque<BigDecimal>(period)
    private var lastPrice: BigDecimal? = null

    override var value: BigDecimal? = null
        private set

    override val isReady: Boolean
        get() = gains.size == period

    override fun update(candle: Candle) {
        lastPrice?.let { last ->
            val change = candle.close - last
            val gain = if (change > BigDecimal.ZERO) change else BigDecimal.ZERO
            val loss = if (change < BigDecimal.ZERO) change.abs() else BigDecimal.ZERO

            gains.addLast(gain)
            losses.addLast(loss)

            if (gains.size > period) {
                gains.removeFirst()
                losses.removeFirst()
            }

            if (isReady) {
                val avgGain = gains.fold(BigDecimal.ZERO) { sum, g -> sum + g } / BigDecimal.valueOf(period.toLong())
                val avgLoss = losses.fold(BigDecimal.ZERO) { sum, l -> sum + l } / BigDecimal.valueOf(period.toLong())

                value = if (avgLoss == BigDecimal.ZERO) {
                    BigDecimal.valueOf(100)
                } else {
                    val rs = avgGain / avgLoss
                    BigDecimal.valueOf(100) - (BigDecimal.valueOf(100) / (BigDecimal.ONE + rs))
                }
            }
        }
        lastPrice = candle.close
    }

    override fun reset() {
        gains.clear()
        losses.clear()
        lastPrice = null
        value = null
    }
}

// ============================================================================
// Average True Range (ATR)
// ============================================================================

class ATR(private val period: Int) : Indicator<BigDecimal> {
    private val trueRanges = ArrayDeque<BigDecimal>(period)
    private var lastClose: BigDecimal? = null

    override var value: BigDecimal? = null
        private set

    override val isReady: Boolean
        get() = trueRanges.size == period

    override fun update(candle: Candle) {
        val tr = if (lastClose != null) {
            maxOf(
                candle.high - candle.low,
                (candle.high - lastClose!!).abs(),
                (candle.low - lastClose!!).abs()
            )
        } else {
            candle.high - candle.low
        }

        trueRanges.addLast(tr)
        if (trueRanges.size > period) {
            trueRanges.removeFirst()
        }

        if (isReady) {
            value = trueRanges.fold(BigDecimal.ZERO) { sum, tr -> sum + tr } / BigDecimal.valueOf(period.toLong())
        }

        lastClose = candle.close
    }

    override fun reset() {
        trueRanges.clear()
        lastClose = null
        value = null
    }
}

// ============================================================================
// Bollinger Bands
// ============================================================================

data class BollingerBands(
    val upper: BigDecimal,
    val middle: BigDecimal,
    val lower: BigDecimal,
    val bandwidth: BigDecimal
)

class BollingerBandsIndicator(
    private val period: Int,
    private val stdDevMultiplier: Double = 2.0
) : Indicator<BollingerBands> {
    private val prices = ArrayDeque<BigDecimal>(period)

    override var value: BollingerBands? = null
        private set

    override val isReady: Boolean
        get() = prices.size == period

    override fun update(candle: Candle) {
        prices.addLast(candle.close)
        if (prices.size > period) {
            prices.removeFirst()
        }

        if (isReady) {
            val sma = prices.fold(BigDecimal.ZERO) { sum, price -> sum + price } / BigDecimal.valueOf(period.toLong())

            // Calculate standard deviation
            val variance = prices.map { price ->
                val diff = (price - sma).toDouble()
                diff * diff
            }.average()

            val stdDev = BigDecimal.valueOf(sqrt(variance))
            val offset = stdDev * BigDecimal.valueOf(stdDevMultiplier)

            val upper = sma + offset
            val lower = sma - offset
            val bandwidth = (upper - lower) / sma * BigDecimal.valueOf(100)

            value = BollingerBands(upper, sma, lower, bandwidth)
        }
    }

    override fun reset() {
        prices.clear()
        value = null
    }
}

// ============================================================================
// MACD (Moving Average Convergence Divergence)
// ============================================================================

data class MACD(
    val macd: BigDecimal,
    val signal: BigDecimal,
    val histogram: BigDecimal
)

class MACDIndicator(
    private val fastPeriod: Int = 12,
    private val slowPeriod: Int = 26,
    private val signalPeriod: Int = 9
) : Indicator<MACD> {
    private val fastEma = EMA(fastPeriod)
    private val slowEma = EMA(slowPeriod)
    private val signalEma = EMA(signalPeriod)
    private var macdValue: BigDecimal? = null

    override var value: MACD? = null
        private set

    override val isReady: Boolean
        get() = fastEma.isReady && slowEma.isReady && signalEma.isReady

    override fun update(candle: Candle) {
        fastEma.update(candle)
        slowEma.update(candle)

        if (fastEma.isReady && slowEma.isReady) {
            macdValue = fastEma.value!! - slowEma.value!!

            // Feed MACD value to signal EMA
            val syntheticCandle = candle.copy(close = macdValue!!)
            signalEma.update(syntheticCandle)

            if (signalEma.isReady) {
                val signal = signalEma.value!!
                val histogram = macdValue!! - signal
                value = MACD(macdValue!!, signal, histogram)
            }
        }
    }

    override fun reset() {
        fastEma.reset()
        slowEma.reset()
        signalEma.reset()
        macdValue = null
        value = null
    }
}

// ============================================================================
// Volume Weighted Average Price (VWAP)
// ============================================================================

class VWAP : Indicator<BigDecimal> {
    private var cumulativePV = BigDecimal.ZERO
    private var cumulativeVolume = BigDecimal.ZERO

    override var value: BigDecimal? = null
        private set

    override val isReady: Boolean
        get() = cumulativeVolume > BigDecimal.ZERO

    override fun update(candle: Candle) {
        val typicalPrice = (candle.high + candle.low + candle.close) / BigDecimal.valueOf(3)
        cumulativePV += typicalPrice * candle.volume
        cumulativeVolume += candle.volume

        value = if (cumulativeVolume > BigDecimal.ZERO) {
            cumulativePV / cumulativeVolume
        } else null
    }

    override fun reset() {
        cumulativePV = BigDecimal.ZERO
        cumulativeVolume = BigDecimal.ZERO
        value = null
    }
}

// ============================================================================
// Indicator DSL Builder
// ============================================================================

class IndicatorSet {
    private val indicators = mutableListOf<Indicator<*>>()

    fun sma(period: Int): SMA {
        return SMA(period).also { indicators.add(it) }
    }

    fun ema(period: Int): EMA {
        return EMA(period).also { indicators.add(it) }
    }

    fun rsi(period: Int): RSI {
        return RSI(period).also { indicators.add(it) }
    }

    fun atr(period: Int): ATR {
        return ATR(period).also { indicators.add(it) }
    }

    fun bollingerBands(period: Int, stdDev: Double = 2.0): BollingerBandsIndicator {
        return BollingerBandsIndicator(period, stdDev).also { indicators.add(it) }
    }

    fun macd(fastPeriod: Int = 12, slowPeriod: Int = 26, signalPeriod: Int = 9): MACDIndicator {
        return MACDIndicator(fastPeriod, slowPeriod, signalPeriod).also { indicators.add(it) }
    }

    fun vwap(): VWAP {
        return VWAP().also { indicators.add(it) }
    }

    fun update(candle: Candle) {
        indicators.forEach { it.update(candle) }
    }

    fun reset() {
        indicators.forEach { it.reset() }
    }
}

fun indicators(config: IndicatorSet.() -> Unit): IndicatorSet {
    return IndicatorSet().apply(config)
}

// ============================================================================
// Helper Extensions for Crosses
// ============================================================================

infix fun BigDecimal.crossesAbove(other: BigDecimal): Boolean {
    // Note: This needs history - simplified for now
    // In practice, you'd track previous values
    return this > other
}

infix fun BigDecimal.crossesBelow(other: BigDecimal): Boolean {
    return this < other
}
