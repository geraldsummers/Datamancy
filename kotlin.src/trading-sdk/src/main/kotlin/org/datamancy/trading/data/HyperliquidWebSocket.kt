package org.datamancy.trading.data

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Instant

/**
 * Hyperliquid WebSocket Client
 *
 * Connects to Hyperliquid's public WebSocket API for real-time market data
 * Documentation: https://hyperliquid.gitbook.io/hyperliquid-docs/
 */
class HyperliquidWebSocket(
    private val url: String = "wss://api.hyperliquid.xyz/ws"
) {
    private val logger = LoggerFactory.getLogger(HyperliquidWebSocket::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val client = HttpClient(CIO) {
        install(WebSockets) {
            pingInterval = 30_000
        }
    }

    private var session: DefaultClientWebSocketSession? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val tradeChannels = mutableMapOf<String, Channel<Trade>>()
    private val candleChannels = mutableMapOf<String, Channel<Candle>>()
    private val orderbookChannels = mutableMapOf<String, Channel<Orderbook>>()

    /**
     * Connect to WebSocket
     */
    suspend fun connect() {
        if (session?.isActive == true) {
            logger.warn("Already connected")
            return
        }

        logger.info("Connecting to Hyperliquid WebSocket: $url")

        try {
            client.webSocket(url) {
                session = this
                logger.info("Connected to Hyperliquid WebSocket")

                // Start message handler
                handleMessages()
            }
        } catch (e: Exception) {
            logger.error("WebSocket connection failed", e)
            throw e
        }
    }

    /**
     * Subscribe to trades for a symbol
     */
    suspend fun subscribeTrades(symbol: String): Flow<Trade> {
        ensureConnected()

        val channel = Channel<Trade>(Channel.BUFFERED)
        tradeChannels[symbol] = channel

        // Send subscription message
        val subscribeMsg = buildJsonObject {
            put("method", "subscribe")
            put("subscription", buildJsonObject {
                put("type", "trades")
                put("coin", symbol)
            })
        }

        session?.send(Frame.Text(subscribeMsg.toString()))
        logger.info("Subscribed to trades: $symbol")

        return channel.receiveAsFlow()
    }

    /**
     * Subscribe to orderbook for a symbol
     */
    suspend fun subscribeOrderbook(symbol: String, depth: Int = 20): Flow<Orderbook> {
        ensureConnected()

        val channel = Channel<Orderbook>(Channel.BUFFERED)
        orderbookChannels[symbol] = channel

        val subscribeMsg = buildJsonObject {
            put("method", "subscribe")
            put("subscription", buildJsonObject {
                put("type", "l2Book")
                put("coin", symbol)
            })
        }

        session?.send(Frame.Text(subscribeMsg.toString()))
        logger.info("Subscribed to orderbook: $symbol")

        return channel.receiveAsFlow()
    }

    /**
     * Subscribe to candles (aggregated from trades)
     */
    suspend fun subscribeCandles(symbol: String, interval: String = "1m"): Flow<Candle> {
        ensureConnected()

        val channel = Channel<Candle>(Channel.BUFFERED)
        val key = "$symbol:$interval"
        candleChannels[key] = channel

        val subscribeMsg = buildJsonObject {
            put("method", "subscribe")
            put("subscription", buildJsonObject {
                put("type", "candle")
                put("coin", symbol)
                put("interval", interval)
            })
        }

        session?.send(Frame.Text(subscribeMsg.toString()))
        logger.info("Subscribed to candles: $symbol $interval")

        return channel.receiveAsFlow()
    }

    /**
     * Handle incoming WebSocket messages
     */
    private suspend fun DefaultClientWebSocketSession.handleMessages() {
        try {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> {
                        val text = frame.readText()
                        processMessage(text)
                    }
                    is Frame.Close -> {
                        logger.info("WebSocket closed: ${frame.readReason()}")
                        break
                    }
                    else -> { /* Ignore other frame types */ }
                }
            }
        } catch (e: Exception) {
            logger.error("Error handling WebSocket messages", e)
        } finally {
            cleanup()
        }
    }

    /**
     * Process individual message
     */
    private suspend fun processMessage(text: String) {
        try {
            val jsonElement = json.parseToJsonElement(text)
            val jsonObject = jsonElement.jsonObject

            val channel = jsonObject["channel"]?.jsonPrimitive?.content
            val data = jsonObject["data"]

            when (channel) {
                "trades" -> handleTrades(data)
                "l2Book" -> handleOrderbook(data)
                "candle" -> handleCandle(data)
                else -> logger.trace("Unhandled channel: $channel")
            }
        } catch (e: Exception) {
            logger.error("Error processing message: $text", e)
        }
    }

    /**
     * Handle trade messages
     */
    private suspend fun handleTrades(data: JsonElement?) {
        data ?: return

        try {
            val tradesArray = data.jsonArray
            tradesArray.forEach { tradeElement ->
                val tradeObj = tradeElement.jsonObject

                val symbol = tradeObj["coin"]?.jsonPrimitive?.content ?: return@forEach
                val price = tradeObj["px"]?.jsonPrimitive?.content?.toBigDecimal() ?: return@forEach
                val size = tradeObj["sz"]?.jsonPrimitive?.content?.toBigDecimal() ?: return@forEach
                val side = tradeObj["side"]?.jsonPrimitive?.content?.lowercase() ?: return@forEach
                val time = tradeObj["time"]?.jsonPrimitive?.long?.let { Instant.ofEpochMilli(it) }
                    ?: Instant.now()

                val trade = Trade(
                    time = time,
                    symbol = symbol,
                    exchange = "hyperliquid",
                    tradeId = null,
                    price = price,
                    size = size,
                    side = if (side == "buy" || side == "b") Side.BUY else Side.SELL,
                    isLiquidation = false
                )

                tradeChannels[symbol]?.send(trade)
            }
        } catch (e: Exception) {
            logger.error("Error handling trades", e)
        }
    }

    /**
     * Handle orderbook messages
     */
    private suspend fun handleOrderbook(data: JsonElement?) {
        data ?: return

        try {
            val bookObj = data.jsonObject
            val symbol = bookObj["coin"]?.jsonPrimitive?.content ?: return

            val bids = bookObj["levels"]?.jsonArray?.get(0)?.jsonArray?.map { level ->
                val arr = level.jsonArray
                OrderbookLevel(
                    price = arr[0].jsonObject["px"]?.jsonPrimitive?.content?.toBigDecimal()
                        ?: BigDecimal.ZERO,
                    size = arr[0].jsonObject["sz"]?.jsonPrimitive?.content?.toBigDecimal()
                        ?: BigDecimal.ZERO
                )
            } ?: emptyList()

            val asks = bookObj["levels"]?.jsonArray?.get(1)?.jsonArray?.map { level ->
                val arr = level.jsonArray
                OrderbookLevel(
                    price = arr[0].jsonObject["px"]?.jsonPrimitive?.content?.toBigDecimal()
                        ?: BigDecimal.ZERO,
                    size = arr[0].jsonObject["sz"]?.jsonPrimitive?.content?.toBigDecimal()
                        ?: BigDecimal.ZERO
                )
            } ?: emptyList()

            val orderbook = Orderbook(
                time = Instant.now(),
                symbol = symbol,
                exchange = "hyperliquid",
                bids = bids,
                asks = asks
            )

            orderbookChannels[symbol]?.send(orderbook)
        } catch (e: Exception) {
            logger.error("Error handling orderbook", e)
        }
    }

    /**
     * Handle candle messages
     */
    private suspend fun handleCandle(data: JsonElement?) {
        data ?: return

        try {
            val candleObj = data.jsonObject
            val symbol = candleObj["s"]?.jsonPrimitive?.content ?: return
            val interval = candleObj["i"]?.jsonPrimitive?.content ?: "1m"

            val candle = Candle(
                time = candleObj["t"]?.jsonPrimitive?.long?.let { Instant.ofEpochMilli(it) }
                    ?: Instant.now(),
                symbol = symbol,
                exchange = "hyperliquid",
                interval = interval,
                open = candleObj["o"]?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO,
                high = candleObj["h"]?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO,
                low = candleObj["l"]?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO,
                close = candleObj["c"]?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO,
                volume = candleObj["v"]?.jsonPrimitive?.content?.toBigDecimal() ?: BigDecimal.ZERO,
                numTrades = candleObj["n"]?.jsonPrimitive?.int ?: 0
            )

            val key = "$symbol:$interval"
            candleChannels[key]?.send(candle)
        } catch (e: Exception) {
            logger.error("Error handling candle", e)
        }
    }

    /**
     * Ensure WebSocket is connected
     */
    private suspend fun ensureConnected() {
        if (session?.isActive != true) {
            connect()
        }
    }

    /**
     * Cleanup resources
     */
    private fun cleanup() {
        tradeChannels.values.forEach { it.close() }
        candleChannels.values.forEach { it.close() }
        orderbookChannels.values.forEach { it.close() }

        tradeChannels.clear()
        candleChannels.clear()
        orderbookChannels.clear()

        session = null
    }

    /**
     * Close WebSocket connection
     */
    suspend fun close() {
        logger.info("Closing Hyperliquid WebSocket")
        session?.close(CloseReason(CloseReason.Codes.NORMAL, "Client closed"))
        cleanup()
        scope.cancel()
        client.close()
    }
}
