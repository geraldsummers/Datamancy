package org.datamancy.trading.data

import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration

/**
 * Market Data DSL - Fluent API for subscribing to market data streams
 *
 * Example:
 * ```kotlin
 * val stream = marketData.stream("BTC-PERP") {
 *     trades()
 *     candles(1.minutes)
 *     orderbook(depth = 20)
 *     funding()
 * }
 *
 * stream.onTrade { trade ->
 *     println("${trade.price} @ ${trade.size}")
 * }
 * ```
 */

// ============================================================================
// Data Models
// ============================================================================

data class Trade(
    val time: Instant,
    val symbol: String,
    val exchange: String,
    val tradeId: String?,
    val price: BigDecimal,
    val size: BigDecimal,
    val side: Side,
    val isLiquidation: Boolean = false
)

enum class Side {
    BUY, SELL
}

data class Candle(
    val time: Instant,
    val symbol: String,
    val exchange: String,
    val interval: String,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    val numTrades: Int = 0
) {
    val range: BigDecimal get() = high - low
    val body: BigDecimal get() = (close - open).abs()
    val isBullish: Boolean get() = close > open
    val isBearish: Boolean get() = close < open
}

data class OrderbookLevel(
    val price: BigDecimal,
    val size: BigDecimal
)

data class Orderbook(
    val time: Instant,
    val symbol: String,
    val exchange: String,
    val bids: List<OrderbookLevel>,
    val asks: List<OrderbookLevel>
) {
    val spread: BigDecimal get() = asks.firstOrNull()?.price?.minus(bids.firstOrNull()?.price ?: BigDecimal.ZERO) ?: BigDecimal.ZERO
    val midPrice: BigDecimal get() = (bestBid + bestAsk) / BigDecimal.valueOf(2)
    val bestBid: BigDecimal get() = bids.firstOrNull()?.price ?: BigDecimal.ZERO
    val bestAsk: BigDecimal get() = asks.firstOrNull()?.price ?: BigDecimal.ZERO

    fun bidVolume(depth: Int = 10): BigDecimal = bids.take(depth).sumOf { it.size }
    fun askVolume(depth: Int = 10): BigDecimal = asks.take(depth).sumOf { it.size }
    fun imbalance(depth: Int = 10): BigDecimal {
        val bidVol = bidVolume(depth)
        val askVol = askVolume(depth)
        return if (bidVol + askVol > BigDecimal.ZERO) {
            (bidVol - askVol) / (bidVol + askVol)
        } else BigDecimal.ZERO
    }
}

data class FundingRate(
    val time: Instant,
    val symbol: String,
    val exchange: String,
    val rate: BigDecimal,
    val predictedRate: BigDecimal? = null
)

data class OpenInterest(
    val time: Instant,
    val symbol: String,
    val exchange: String,
    val value: BigDecimal
)

// ============================================================================
// Stream Configuration DSL
// ============================================================================

class MarketDataStreamConfig internal constructor(
    val symbol: String,
    val exchange: String = "hyperliquid"
) {
    internal var subscribeTrades = false
    internal var subscribeCandles = mutableListOf<Duration>()
    internal var subscribeOrderbook: OrderbookConfig? = null
    internal var subscribeFunding = false
    internal var subscribeOpenInterest = false

    fun trades() {
        subscribeTrades = true
    }

    fun candles(interval: Duration) {
        subscribeCandles.add(interval)
    }

    fun orderbook(depth: Int = 20, updateInterval: Duration = Duration.ZERO) {
        subscribeOrderbook = OrderbookConfig(depth, updateInterval)
    }

    fun funding() {
        subscribeFunding = true
    }

    fun openInterest() {
        subscribeOpenInterest = true
    }
}

data class OrderbookConfig(
    val depth: Int,
    val updateInterval: Duration
)

// ============================================================================
// Stream Interface
// ============================================================================

interface MarketDataStream {
    val symbol: String
    val exchange: String

    val trades: Flow<Trade>
    fun candles(interval: Duration): Flow<Candle>
    val orderbook: Flow<Orderbook>
    val funding: Flow<FundingRate>
    val openInterest: Flow<OpenInterest>

    suspend fun onTrade(handler: suspend (Trade) -> Unit)
    suspend fun onCandle(interval: Duration, handler: suspend (Candle) -> Unit)
    suspend fun onOrderbook(handler: suspend (Orderbook) -> Unit)
    suspend fun onFunding(handler: suspend (FundingRate) -> Unit)
    suspend fun onOpenInterest(handler: suspend (OpenInterest) -> Unit)

    suspend fun close()
}

// ============================================================================
// Market Data Service
// ============================================================================

interface MarketDataService {
    /**
     * Create a market data stream with DSL configuration
     */
    suspend fun stream(
        symbol: String,
        exchange: String = "hyperliquid",
        config: MarketDataStreamConfig.() -> Unit
    ): MarketDataStream

    /**
     * Fetch historical candles
     */
    suspend fun historicalCandles(
        symbol: String,
        interval: Duration,
        from: Instant,
        to: Instant = Instant.now(),
        exchange: String = "hyperliquid"
    ): List<Candle>

    /**
     * Fetch historical trades
     */
    suspend fun historicalTrades(
        symbol: String,
        from: Instant,
        to: Instant = Instant.now(),
        exchange: String = "hyperliquid"
    ): List<Trade>

    /**
     * Get latest candle
     */
    suspend fun latestCandle(
        symbol: String,
        interval: Duration,
        exchange: String = "hyperliquid"
    ): Candle?

    /**
     * Get current orderbook snapshot
     */
    suspend fun currentOrderbook(
        symbol: String,
        depth: Int = 20,
        exchange: String = "hyperliquid"
    ): Orderbook?
}
