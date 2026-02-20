package org.datamancy.pipeline.sources

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.datamancy.pipeline.core.Source
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Hyperliquid WebSocket Source for Market Data Ingestion
 *
 * Connects to Hyperliquid's public WebSocket API and ingests real-time market data.
 * This source handles reconnection, subscription management, and data normalization.
 *
 * Supported data types:
 * - Trades: Real-time trade execution data
 * - Candles: Aggregated OHLCV data at various intervals
 * - Orderbook: Level 2 order book snapshots
 *
 * Documentation: https://hyperliquid.gitbook.io/hyperliquid-docs/
 */
class HyperliquidSource(
    private val symbols: List<String>,
    private val subscribeToTrades: Boolean = true,
    private val subscribeToCandles: Boolean = true,
    private val candleIntervals: List<String> = listOf("1m", "5m", "15m", "1h"),
    private val subscribeToOrderbook: Boolean = false,
    private val url: String = "wss://api.hyperliquid.xyz/ws"
) : Source<HyperliquidMarketData> {
    override val name = "HyperliquidSource"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 30.seconds
        }
    }

    override suspend fun fetch(): Flow<HyperliquidMarketData> = flow {
        logger.info { "Starting Hyperliquid WebSocket ingestion for ${symbols.size} symbols" }
        logger.info { "Subscriptions - Trades: $subscribeToTrades, Candles: $subscribeToCandles (${candleIntervals.joinToString()}), Orderbook: $subscribeToOrderbook" }

        try {
            client.webSocket(url) {
                logger.info { "Connected to Hyperliquid WebSocket: $url" }

                // Subscribe to configured data streams
                symbols.forEach { symbol ->
                    if (subscribeToTrades) {
                        subscribeTrades(symbol)
                    }
                    if (subscribeToCandles) {
                        candleIntervals.forEach { interval ->
                            subscribeCandles(symbol, interval)
                        }
                    }
                    if (subscribeToOrderbook) {
                        subscribeOrderbook(symbol)
                    }
                }

                // Process incoming messages
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            processMessage(text)?.let { emit(it) }
                        }
                        is Frame.Close -> {
                            logger.info { "WebSocket closed: ${frame.readReason()}" }
                            break
                        }
                        else -> { /* Ignore other frame types */ }
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Hyperliquid WebSocket connection failed: ${e.message}" }
            throw e
        } finally {
            client.close()
            logger.info { "Hyperliquid WebSocket connection closed" }
        }
    }

    /**
     * Subscribe to trades for a symbol
     */
    private suspend fun DefaultClientWebSocketSession.subscribeTrades(symbol: String) {
        val subscribeMsg = buildJsonObject {
            put("method", "subscribe")
            put("subscription", buildJsonObject {
                put("type", "trades")
                put("coin", symbol)
            })
        }
        send(Frame.Text(subscribeMsg.toString()))
        logger.debug { "Subscribed to trades: $symbol" }
    }

    /**
     * Subscribe to candles for a symbol
     */
    private suspend fun DefaultClientWebSocketSession.subscribeCandles(symbol: String, interval: String) {
        val subscribeMsg = buildJsonObject {
            put("method", "subscribe")
            put("subscription", buildJsonObject {
                put("type", "candle")
                put("coin", symbol)
                put("interval", interval)
            })
        }
        send(Frame.Text(subscribeMsg.toString()))
        logger.debug { "Subscribed to candles: $symbol $interval" }
    }

    /**
     * Subscribe to orderbook for a symbol
     */
    private suspend fun DefaultClientWebSocketSession.subscribeOrderbook(symbol: String) {
        val subscribeMsg = buildJsonObject {
            put("method", "subscribe")
            put("subscription", buildJsonObject {
                put("type", "l2Book")
                put("coin", symbol)
            })
        }
        send(Frame.Text(subscribeMsg.toString()))
        logger.debug { "Subscribed to orderbook: $symbol" }
    }

    /**
     * Process incoming WebSocket message
     */
    private fun processMessage(text: String): HyperliquidMarketData? {
        return try {
            val jsonElement = json.parseToJsonElement(text)
            val jsonObject = jsonElement.jsonObject

            val channel = jsonObject["channel"]?.jsonPrimitive?.content
            val data = jsonObject["data"]

            when (channel) {
                "trades" -> parseTrades(data)
                "candle" -> parseCandle(data)
                "l2Book" -> parseOrderbook(data)
                else -> {
                    logger.trace { "Unhandled channel: $channel" }
                    null
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Error processing message: ${text.take(200)}" }
            null
        }
    }

    /**
     * Parse trade messages
     */
    private fun parseTrades(data: JsonElement?): HyperliquidMarketData? {
        data ?: return null

        return try {
            val tradesArray = data.jsonArray
            val trades = tradesArray.mapNotNull { tradeElement ->
                try {
                    val tradeObj = tradeElement.jsonObject
                    val symbol = tradeObj["coin"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val price = tradeObj["px"]?.jsonPrimitive?.content?.toDouble() ?: return@mapNotNull null
                    val size = tradeObj["sz"]?.jsonPrimitive?.content?.toDouble() ?: return@mapNotNull null
                    val side = tradeObj["side"]?.jsonPrimitive?.content?.lowercase() ?: return@mapNotNull null
                    val time = tradeObj["time"]?.jsonPrimitive?.long?.let { Instant.ofEpochMilli(it) }
                        ?: Instant.now()

                    HyperliquidTrade(
                        time = time,
                        symbol = symbol,
                        price = price,
                        size = size,
                        side = side,
                        tradeId = tradeObj["tid"]?.jsonPrimitive?.content
                    )
                } catch (e: Exception) {
                    logger.error(e) { "Failed to parse trade" }
                    null
                }
            }

            if (trades.isNotEmpty()) {
                HyperliquidMarketData.Trades(trades)
            } else null
        } catch (e: Exception) {
            logger.error(e) { "Error parsing trades" }
            null
        }
    }

    /**
     * Parse candle messages
     */
    private fun parseCandle(data: JsonElement?): HyperliquidMarketData? {
        data ?: return null

        return try {
            val candleObj = data.jsonObject
            val symbol = candleObj["s"]?.jsonPrimitive?.content ?: return null
            val interval = candleObj["i"]?.jsonPrimitive?.content ?: "1m"

            HyperliquidMarketData.Candle(
                HyperliquidCandle(
                    time = candleObj["t"]?.jsonPrimitive?.long?.let { Instant.ofEpochMilli(it) }
                        ?: Instant.now(),
                    symbol = symbol,
                    interval = interval,
                    open = candleObj["o"]?.jsonPrimitive?.content?.toDouble() ?: 0.0,
                    high = candleObj["h"]?.jsonPrimitive?.content?.toDouble() ?: 0.0,
                    low = candleObj["l"]?.jsonPrimitive?.content?.toDouble() ?: 0.0,
                    close = candleObj["c"]?.jsonPrimitive?.content?.toDouble() ?: 0.0,
                    volume = candleObj["v"]?.jsonPrimitive?.content?.toDouble() ?: 0.0,
                    numTrades = candleObj["n"]?.jsonPrimitive?.int ?: 0
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Error parsing candle" }
            null
        }
    }

    /**
     * Parse orderbook messages
     */
    private fun parseOrderbook(data: JsonElement?): HyperliquidMarketData? {
        data ?: return null

        return try {
            val bookObj = data.jsonObject
            val symbol = bookObj["coin"]?.jsonPrimitive?.content ?: return null

            val bids = bookObj["levels"]?.jsonArray?.get(0)?.jsonArray?.mapNotNull { level ->
                try {
                    val arr = level.jsonArray
                    val priceObj = arr[0].jsonObject
                    HyperliquidOrderbookLevel(
                        price = priceObj["px"]?.jsonPrimitive?.content?.toDouble() ?: return@mapNotNull null,
                        size = priceObj["sz"]?.jsonPrimitive?.content?.toDouble() ?: return@mapNotNull null
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()

            val asks = bookObj["levels"]?.jsonArray?.get(1)?.jsonArray?.mapNotNull { level ->
                try {
                    val arr = level.jsonArray
                    val priceObj = arr[0].jsonObject
                    HyperliquidOrderbookLevel(
                        price = priceObj["px"]?.jsonPrimitive?.content?.toDouble() ?: return@mapNotNull null,
                        size = priceObj["sz"]?.jsonPrimitive?.content?.toDouble() ?: return@mapNotNull null
                    )
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()

            HyperliquidMarketData.Orderbook(
                HyperliquidOrderbook(
                    time = Instant.now(),
                    symbol = symbol,
                    bids = bids,
                    asks = asks
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Error parsing orderbook" }
            null
        }
    }
}

// ============================================================================
// Data Models
// ============================================================================

/**
 * Sealed class for different types of Hyperliquid market data
 */
sealed class HyperliquidMarketData {
    data class Trades(val trades: List<HyperliquidTrade>) : HyperliquidMarketData()
    data class Candle(val candle: HyperliquidCandle) : HyperliquidMarketData()
    data class Orderbook(val orderbook: HyperliquidOrderbook) : HyperliquidMarketData()
}

data class HyperliquidTrade(
    val time: Instant,
    val symbol: String,
    val price: Double,
    val size: Double,
    val side: String,  // "buy" or "sell"
    val tradeId: String? = null
) {
    fun toText(): String = buildString {
        appendLine("# Trade: $symbol")
        appendLine("**Time:** $time")
        appendLine("**Side:** ${side.uppercase()}")
        appendLine("**Price:** $$price")
        appendLine("**Size:** $size")
        tradeId?.let { appendLine("**Trade ID:** $it") }
    }

    fun contentHash(): String = "$symbol:${time.epochSecond}:$price:$size".hashCode().toString()
}

data class HyperliquidCandle(
    val time: Instant,
    val symbol: String,
    val interval: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val numTrades: Int
) {
    fun toText(): String = buildString {
        appendLine("# Candle: $symbol ($interval)")
        appendLine("**Time:** $time")
        appendLine("**OHLC:** O=$open H=$high L=$low C=$close")
        appendLine("**Volume:** $volume")
        appendLine("**Trades:** $numTrades")
    }

    fun contentHash(): String = "$symbol:$interval:${time.epochSecond}".hashCode().toString()
}

data class HyperliquidOrderbookLevel(
    val price: Double,
    val size: Double
)

data class HyperliquidOrderbook(
    val time: Instant,
    val symbol: String,
    val bids: List<HyperliquidOrderbookLevel>,
    val asks: List<HyperliquidOrderbookLevel>
) {
    fun toText(): String = buildString {
        appendLine("# Orderbook: $symbol")
        appendLine("**Time:** $time")
        appendLine("**Best Bid:** ${bids.firstOrNull()?.price ?: "N/A"}")
        appendLine("**Best Ask:** ${asks.firstOrNull()?.price ?: "N/A"}")
        appendLine("**Spread:** ${(asks.firstOrNull()?.price ?: 0.0) - (bids.firstOrNull()?.price ?: 0.0)}")
        appendLine("**Bid Depth:** ${bids.size} levels")
        appendLine("**Ask Depth:** ${asks.size} levels")
    }

    fun contentHash(): String = "$symbol:${time.epochSecond}".hashCode().toString()
}
