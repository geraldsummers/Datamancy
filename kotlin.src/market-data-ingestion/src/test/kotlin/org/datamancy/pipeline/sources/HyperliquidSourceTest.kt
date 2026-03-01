package org.datamancy.pipeline.sources

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for HyperliquidSource
 *
 * Note: Tests requiring live network connection have been moved to
 * HyperliquidSourceIntegrationTest in the test-runner module.
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
