package org.datamancy.trading.strategy

import org.datamancy.trading.data.*
import org.datamancy.trading.indicators.IndicatorSet
import org.datamancy.trading.risk.RiskManagement
import org.datamancy.trading.models.Side
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Instant
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Strategy DSL - Define trading strategies with declarative syntax
 *
 * Example:
 * ```kotlin
 * strategy("TrendFollower") {
 *     markets {
 *         hyperliquid("BTC-PERP", "ETH-PERP")
 *     }
 *
 *     parameters {
 *         var fastPeriod by 20
 *         var slowPeriod by 50
 *     }
 *
 *     indicators {
 *         val fast = sma(fastPeriod)
 *         val slow = sma(slowPeriod)
 *         val atr = atr(14)
 *     }
 *
 *     onCandle {
 *         if (fast > slow && !hasPosition()) {
 *             enter {
 *                 side = Side.BUY
 *                 size = risk.calculateSize(atr = atr, riskPercent = 1.0)
 *             }
 *         }
 *     }
 * }
 * ```
 */

// ============================================================================
// Signal Types
// ============================================================================

enum class Signal {
    LONG, SHORT, EXIT, NEUTRAL
}

data class SignalEvent(
    val time: Instant,
    val symbol: String,
    val signal: Signal,
    val strength: BigDecimal = BigDecimal.ONE,
    val reason: String? = null,
    val metadata: Map<String, Any> = emptyMap()
)

// ============================================================================
// Position Models
// ============================================================================

data class StrategyPosition(
    val id: String,
    val symbol: String,
    val side: Side,
    val entryTime: Instant,
    val entryPrice: BigDecimal,
    val size: BigDecimal,
    val stopLoss: BigDecimal? = null,
    val takeProfit: List<Pair<BigDecimal, BigDecimal>>? = null, // (price, size%) pairs
    val currentPrice: BigDecimal? = null,
    val unrealizedPnl: BigDecimal = BigDecimal.ZERO,
    val unrealizedPnlPercent: BigDecimal = BigDecimal.ZERO
) {
    val isLong: Boolean get() = side == Side.BUY
    val isShort: Boolean get() = side == Side.SELL

    fun withPrice(price: BigDecimal): StrategyPosition {
        val pnl = if (isLong) {
            (price - entryPrice) * size
        } else {
            (entryPrice - price) * size
        }
        val pnlPercent = if (entryPrice > BigDecimal.ZERO) {
            pnl / (entryPrice * size) * BigDecimal.valueOf(100)
        } else BigDecimal.ZERO

        return copy(
            currentPrice = price,
            unrealizedPnl = pnl,
            unrealizedPnlPercent = pnlPercent
        )
    }
}

// ============================================================================
// Entry/Exit Configuration
// ============================================================================

class EntryConfig {
    var side: Side? = null
    var size: BigDecimal? = null
    var price: BigDecimal? = null // null = market order
    var stopLoss: BigDecimal? = null
    var takeProfit: BigDecimal? = null
    var takeProfitTargets: List<Pair<BigDecimal, BigDecimal>>? = null
    var metadata: MutableMap<String, Any> = mutableMapOf()
}

class ExitConfig {
    var size: BigDecimal? = null // null = close entire position
    var price: BigDecimal? = null // null = market order
    var reason: String? = null
}

// ============================================================================
// Strategy Parameters
// ============================================================================

class StrategyParameters {
    private val params = mutableMapOf<String, Any>()

    operator fun <T> getValue(thisRef: Any?, property: KProperty<*>): T {
        @Suppress("UNCHECKED_CAST")
        return params[property.name] as T
    }

    operator fun <T> setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        params[property.name] = value as Any
    }

    fun getAll(): Map<String, Any> = params.toMap()

    fun set(name: String, value: Any) {
        params[name] = value
    }

    fun get(name: String): Any? = params[name]
}

// ============================================================================
// Strategy Context
// ============================================================================

interface StrategyContext {
    val symbol: String
    val currentCandle: Candle?
    val currentPrice: BigDecimal?
    val positions: List<StrategyPosition>
    val indicators: IndicatorSet
    val risk: RiskManagement
    val equity: BigDecimal

    fun hasPosition(symbol: String? = null): Boolean
    fun getPosition(symbol: String? = null): StrategyPosition?

    suspend fun enter(config: EntryConfig.() -> Unit)
    suspend fun exit(config: ExitConfig.() -> Unit)
    suspend fun updateStopLoss(price: BigDecimal)
    suspend fun updateTakeProfit(price: BigDecimal)

    fun log(message: String)
    fun logTrade(action: String, details: Map<String, Any>)
}

// ============================================================================
// Market Configuration
// ============================================================================

class MarketConfig {
    internal val symbols = mutableListOf<Pair<String, String>>() // (symbol, exchange)

    fun hyperliquid(vararg symbols: String) {
        this.symbols.addAll(symbols.map { it to "hyperliquid" })
    }

    fun binance(vararg symbols: String) {
        this.symbols.addAll(symbols.map { it to "binance" })
    }

    fun custom(exchange: String, vararg symbols: String) {
        this.symbols.addAll(symbols.map { it to exchange })
    }
}

// ============================================================================
// Strategy Builder
// ============================================================================

class StrategyBuilder(val name: String) {
    internal var marketConfig: MarketConfig? = null
    internal var parameters = StrategyParameters()
    internal var indicatorBuilder: (IndicatorSet.() -> Unit)? = null
    internal var riskManagement: RiskManagement? = null

    internal var onCandleHandler: (suspend StrategyContext.() -> Unit)? = null
    internal var onTradeHandler: (suspend StrategyContext.(Trade) -> Unit)? = null
    internal var onPositionHandler: (suspend StrategyContext.(StrategyPosition) -> Unit)? = null
    internal var onSignalHandler: (suspend StrategyContext.(SignalEvent) -> Unit)? = null
    internal var onStartHandler: (suspend StrategyContext.() -> Unit)? = null
    internal var onStopHandler: (suspend StrategyContext.() -> Unit)? = null

    fun markets(config: MarketConfig.() -> Unit) {
        marketConfig = MarketConfig().apply(config)
    }

    fun parameters(config: StrategyParameters.() -> Unit) {
        parameters.apply(config)
    }

    fun indicators(config: IndicatorSet.() -> Unit) {
        indicatorBuilder = config
    }

    fun risk(config: RiskManagement.() -> Unit) {
        riskManagement = org.datamancy.trading.risk.riskManagement(config)
    }

    fun onCandle(handler: suspend StrategyContext.() -> Unit) {
        onCandleHandler = handler
    }

    fun onTrade(handler: suspend StrategyContext.(Trade) -> Unit) {
        onTradeHandler = handler
    }

    fun onPosition(handler: suspend StrategyContext.(StrategyPosition) -> Unit) {
        onPositionHandler = handler
    }

    fun onSignal(handler: suspend StrategyContext.(SignalEvent) -> Unit) {
        onSignalHandler = handler
    }

    fun onStart(handler: suspend StrategyContext.() -> Unit) {
        onStartHandler = handler
    }

    fun onStop(handler: suspend StrategyContext.() -> Unit) {
        onStopHandler = handler
    }

    fun build(): Strategy {
        requireNotNull(marketConfig) { "Markets must be configured" }

        return Strategy(
            name = name,
            marketConfig = marketConfig!!,
            parameters = parameters,
            indicatorBuilder = indicatorBuilder,
            riskManagement = riskManagement,
            onCandleHandler = onCandleHandler,
            onTradeHandler = onTradeHandler,
            onPositionHandler = onPositionHandler,
            onSignalHandler = onSignalHandler,
            onStartHandler = onStartHandler,
            onStopHandler = onStopHandler
        )
    }
}

// ============================================================================
// Strategy
// ============================================================================

data class Strategy(
    val name: String,
    val marketConfig: MarketConfig,
    val parameters: StrategyParameters,
    val indicatorBuilder: (IndicatorSet.() -> Unit)?,
    val riskManagement: RiskManagement?,
    val onCandleHandler: (suspend StrategyContext.() -> Unit)?,
    val onTradeHandler: (suspend StrategyContext.(Trade) -> Unit)?,
    val onPositionHandler: (suspend StrategyContext.(StrategyPosition) -> Unit)?,
    val onSignalHandler: (suspend StrategyContext.(SignalEvent) -> Unit)?,
    val onStartHandler: (suspend StrategyContext.() -> Unit)?,
    val onStopHandler: (suspend StrategyContext.() -> Unit)?
)

fun strategy(name: String, config: StrategyBuilder.() -> Unit): Strategy {
    return StrategyBuilder(name).apply(config).build()
}

// ============================================================================
// Strategy Engine
// ============================================================================

interface StrategyEngine {
    /**
     * Start a strategy
     */
    suspend fun start(strategy: Strategy)

    /**
     * Stop a strategy
     */
    suspend fun stop(strategyName: String)

    /**
     * Get running strategies
     */
    fun getRunningStrategies(): List<String>

    /**
     * Get strategy performance
     */
    suspend fun getPerformance(strategyName: String): StrategyPerformance?

    /**
     * Subscribe to strategy signals
     */
    fun signals(strategyName: String): Flow<SignalEvent>

    /**
     * Subscribe to strategy positions
     */
    fun positions(strategyName: String): Flow<StrategyPosition>
}

data class StrategyPerformance(
    val strategyName: String,
    val startTime: Instant,
    val endTime: Instant?,
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val totalPnl: BigDecimal,
    val totalPnlPercent: BigDecimal,
    val winRate: BigDecimal,
    val profitFactor: BigDecimal,
    val avgWin: BigDecimal,
    val avgLoss: BigDecimal,
    val maxDrawdown: BigDecimal,
    val sharpeRatio: BigDecimal,
    val currentEquity: BigDecimal,
    val peakEquity: BigDecimal
) {
    val expectancy: BigDecimal
        get() = (winRate * avgWin) - ((BigDecimal.ONE - winRate) * avgLoss)
}
