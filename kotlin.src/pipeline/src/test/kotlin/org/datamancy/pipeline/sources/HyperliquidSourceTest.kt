package org.datamancy.pipeline.sources

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for HyperliquidSource
 *
 * Note: Most tests are disabled by default as they require live network connection
 * to Hyperliquid's WebSocket API. Enable them for manual testing.
 */
class HyperliquidSourceTest {

    @Test
    fun `source has correct name`() {
        val source = HyperliquidSource(listOf("BTC"))
        assertEquals("HyperliquidSource", source.name)
    }

    @Test
    fun `source configuration is validated`() {
        val source = HyperliquidSource(
            symbols = listOf("BTC", "ETH"),
            subscribeToTrades = true,
            subscribeToCandles = true,
            candleIntervals = listOf("1m", "5m"),
            subscribeToOrderbook = false
        )

        assertNotNull(source)
        assertEquals("HyperliquidSource", source.name)
    }

    @Test
    @Disabled("Requires live Hyperliquid WebSocket connection")
    fun `can connect to Hyperliquid WebSocket`() = runBlocking {
        val source = HyperliquidSource(
            symbols = listOf("BTC"),
            subscribeToTrades = true,
            subscribeToCandles = false,
            subscribeToOrderbook = false
        )

        withTimeout(30.seconds) {
            val firstItem = source.fetch().first()
            assertNotNull(firstItem)
            assertTrue(firstItem is HyperliquidMarketData.Trades)
        }
    }

    @Test
    @Disabled("Requires live Hyperliquid WebSocket connection")
    fun `receives trade data`() = runBlocking {
        val source = HyperliquidSource(
            symbols = listOf("BTC"),
            subscribeToTrades = true,
            subscribeToCandles = false,
            subscribeToOrderbook = false
        )

        withTimeout(60.seconds) {
            source.fetch().collect { data ->
                when (data) {
                    is HyperliquidMarketData.Trades -> {
                        assertTrue(data.trades.isNotEmpty())
                        val trade = data.trades.first()
                        assertEquals("BTC", trade.symbol)
                        assertTrue(trade.price > 0.0)
                        assertTrue(trade.size > 0.0)
                        assertTrue(trade.side in listOf("buy", "sell"))
                        return@collect // Exit after first batch
                    }
                    else -> {
                        // Keep waiting for trades
                    }
                }
            }
        }
    }

    @Test
    @Disabled("Requires live Hyperliquid WebSocket connection")
    fun `receives candle data`() = runBlocking {
        val source = HyperliquidSource(
            symbols = listOf("BTC"),
            subscribeToTrades = false,
            subscribeToCandles = true,
            candleIntervals = listOf("1m"),
            subscribeToOrderbook = false
        )

        withTimeout(120.seconds) {
            source.fetch().collect { data ->
                when (data) {
                    is HyperliquidMarketData.Candle -> {
                        val candle = data.candle
                        assertEquals("BTC", candle.symbol)
                        assertEquals("1m", candle.interval)
                        assertTrue(candle.open > 0.0)
                        assertTrue(candle.high >= candle.low)
                        assertTrue(candle.close > 0.0)
                        return@collect // Exit after first candle
                    }
                    else -> {
                        // Keep waiting for candles
                    }
                }
            }
        }
    }

    @Test
    @Disabled("Requires live Hyperliquid WebSocket connection")
    fun `receives orderbook data`() = runBlocking {
        val source = HyperliquidSource(
            symbols = listOf("BTC"),
            subscribeToTrades = false,
            subscribeToCandles = false,
            subscribeToOrderbook = true
        )

        withTimeout(60.seconds) {
            source.fetch().collect { data ->
                when (data) {
                    is HyperliquidMarketData.Orderbook -> {
                        val orderbook = data.orderbook
                        assertEquals("BTC", orderbook.symbol)
                        assertTrue(orderbook.bids.isNotEmpty())
                        assertTrue(orderbook.asks.isNotEmpty())
                        assertTrue(orderbook.bids.first().price < orderbook.asks.first().price)
                        return@collect // Exit after first orderbook
                    }
                    else -> {
                        // Keep waiting for orderbook
                    }
                }
            }
        }
    }

    @Test
    fun `trade data model has correct properties`() {
        val trade = HyperliquidTrade(
            time = java.time.Instant.now(),
            symbol = "BTC",
            price = 50000.0,
            size = 0.5,
            side = "buy",
            tradeId = "test123"
        )

        assertEquals("BTC", trade.symbol)
        assertEquals(50000.0, trade.price)
        assertEquals(0.5, trade.size)
        assertEquals("buy", trade.side)
        assertEquals("test123", trade.tradeId)
        assertNotNull(trade.toText())
        assertNotNull(trade.contentHash())
    }

    @Test
    fun `candle data model has correct properties`() {
        val candle = HyperliquidCandle(
            time = java.time.Instant.now(),
            symbol = "ETH",
            interval = "5m",
            open = 3000.0,
            high = 3100.0,
            low = 2900.0,
            close = 3050.0,
            volume = 1000.0,
            numTrades = 500
        )

        assertEquals("ETH", candle.symbol)
        assertEquals("5m", candle.interval)
        assertEquals(3000.0, candle.open)
        assertEquals(3100.0, candle.high)
        assertEquals(2900.0, candle.low)
        assertEquals(3050.0, candle.close)
        assertEquals(1000.0, candle.volume)
        assertEquals(500, candle.numTrades)
        assertNotNull(candle.toText())
        assertNotNull(candle.contentHash())
    }

    @Test
    fun `orderbook data model has correct properties`() {
        val orderbook = HyperliquidOrderbook(
            time = java.time.Instant.now(),
            symbol = "SOL",
            bids = listOf(
                HyperliquidOrderbookLevel(100.0, 10.0),
                HyperliquidOrderbookLevel(99.5, 20.0)
            ),
            asks = listOf(
                HyperliquidOrderbookLevel(100.5, 15.0),
                HyperliquidOrderbookLevel(101.0, 25.0)
            )
        )

        assertEquals("SOL", orderbook.symbol)
        assertEquals(2, orderbook.bids.size)
        assertEquals(2, orderbook.asks.size)
        assertEquals(100.0, orderbook.bids.first().price)
        assertEquals(100.5, orderbook.asks.first().price)
        assertNotNull(orderbook.toText())
        assertNotNull(orderbook.contentHash())
    }
}
