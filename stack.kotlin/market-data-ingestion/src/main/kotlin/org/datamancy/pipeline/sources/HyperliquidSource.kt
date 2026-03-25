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
import java.io.IOException
import java.time.Instant

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
    private val subscribeToAssetCtx: Boolean = true,
    private val url: String = "wss://api.hyperliquid.xyz/ws",
    private val receiveIdleTimeoutMs: Long = 120_000L
) : Source<HyperliquidMarketData> {
    override val name = "HyperliquidSource"

    private val json = Json { ignoreUnknownKeys = true }
    // Hyperliquid pushes market data continuously; rely on the explicit idle watchdog
    // instead of Ktor's pinger, which was forcing avoidable reconnects under shard load.
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    override suspend fun fetch(): Flow<HyperliquidMarketData> = flow {
        logger.info { "Starting Hyperliquid WebSocket ingestion for ${symbols.size} symbols" }
        logger.info {
            "Subscriptions - Trades: $subscribeToTrades, Candles: $subscribeToCandles (${candleIntervals.joinToString()}), " +
                "Orderbook: $subscribeToOrderbook, AssetCtx: $subscribeToAssetCtx"
        }
        logger.info { "WebSocket idle watchdog: ${receiveIdleTimeoutMs}ms" }

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
                    if (subscribeToAssetCtx) {
                        subscribeActiveAssetCtx(symbol)
                    }
                }

                // Force reconnection if the socket stops delivering market data frames without closing.
                while (currentCoroutineContext().isActive) {
                    val frame = awaitNextFrame()
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
            logger.info { "Hyperliquid WebSocket session ended" }
        }
    }

    private suspend fun DefaultClientWebSocketSession.awaitNextFrame(): Frame {
        return try {
            withTimeout(receiveIdleTimeoutMs) {
                incoming.receive()
            }
        } catch (e: TimeoutCancellationException) {
            val message = "No WebSocket frames received for ${receiveIdleTimeoutMs}ms"
            logger.warn { "$message; forcing reconnect" }
            runCatching {
                close(CloseReason(CloseReason.Codes.GOING_AWAY, "idle timeout"))
            }
            throw IOException(message, e)
        }
    }

    /**
     * Close network resources on shutdown.
     */
    fun close() {
        client.close()
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
     * Subscribe to active asset context for a symbol.
     *
     * This public feed includes funding and open interest for perp markets.
     */
    private suspend fun DefaultClientWebSocketSession.subscribeActiveAssetCtx(symbol: String) {
        val subscribeMsg = buildJsonObject {
            put("method", "subscribe")
            put("subscription", buildJsonObject {
                put("type", "activeAssetCtx")
                put("coin", symbol)
            })
        }
        send(Frame.Text(subscribeMsg.toString()))
        logger.debug { "Subscribed to active asset context: $symbol" }
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
                "activeAssetCtx" -> parseActiveAssetCtx(data)
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
            val eventTime = bookObj["time"]?.jsonPrimitive?.longOrNull
                ?: bookObj["t"]?.jsonPrimitive?.longOrNull

            val bids = bookObj["levels"]?.jsonArray?.get(0)?.jsonArray?.mapNotNull { level ->
                try {
                    parseOrderbookLevel(level)
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()

            val asks = bookObj["levels"]?.jsonArray?.get(1)?.jsonArray?.mapNotNull { level ->
                try {
                    parseOrderbookLevel(level)
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()

            HyperliquidMarketData.Orderbook(
                HyperliquidOrderbook(
                    time = eventTime?.let { Instant.ofEpochMilli(it) } ?: Instant.now(),
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

    /**
     * Parse active asset context messages carrying funding and open interest.
     */
    private fun parseActiveAssetCtx(data: JsonElement?): HyperliquidMarketData? {
        data ?: return null

        return try {
            val payload = data.jsonObject
            val symbol = payload["coin"]?.jsonPrimitive?.content ?: return null
            val ctx = payload["ctx"]?.jsonObject ?: payload
            val fundingRate = ctx.doubleField("funding") ?: return null
            val openInterest = ctx.doubleField("openInterest") ?: return null

            HyperliquidMarketData.AssetContext(
                HyperliquidAssetContext(
                    time = Instant.now(),
                    symbol = symbol,
                    fundingRate = fundingRate,
                    openInterest = openInterest,
                    markPrice = ctx.doubleField("markPx"),
                    oraclePrice = ctx.doubleField("oraclePx"),
                    midPrice = ctx.doubleField("midPx"),
                    dayNotionalVolume = ctx.doubleField("dayNtlVlm"),
                    previousDayPrice = ctx.doubleField("prevDayPx")
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Error parsing active asset context" }
            null
        }
    }

    private fun parseOrderbookLevel(level: JsonElement): HyperliquidOrderbookLevel? {
        val obj = when {
            level is JsonObject -> level
            level is JsonArray && level.isNotEmpty() && level[0] is JsonObject -> level[0].jsonObject
            else -> return null
        }
        val price = obj["px"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        val size = obj["sz"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return null
        return HyperliquidOrderbookLevel(price = price, size = size)
    }

    private fun JsonObject.doubleField(name: String): Double? {
        val element = this[name] ?: return null
        return runCatching {
            when (element) {
                is JsonPrimitive -> element.content.toDouble()
                else -> element.toString().toDouble()
            }
        }.getOrNull()
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
    data class AssetContext(val assetContext: HyperliquidAssetContext) : HyperliquidMarketData()
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

data class HyperliquidAssetContext(
    val time: Instant,
    val symbol: String,
    val fundingRate: Double,
    val openInterest: Double,
    val markPrice: Double? = null,
    val oraclePrice: Double? = null,
    val midPrice: Double? = null,
    val dayNotionalVolume: Double? = null,
    val previousDayPrice: Double? = null
) {
    fun toText(): String = buildString {
        appendLine("# Asset Context: $symbol")
        appendLine("**Time:** $time")
        appendLine("**Funding Rate:** $fundingRate")
        appendLine("**Open Interest:** $openInterest")
        markPrice?.let { appendLine("**Mark Price:** $it") }
        oraclePrice?.let { appendLine("**Oracle Price:** $it") }
        midPrice?.let { appendLine("**Mid Price:** $it") }
    }

    fun contentHash(): String = "$symbol:${time.epochSecond}:$fundingRate:$openInterest".hashCode().toString()
}
