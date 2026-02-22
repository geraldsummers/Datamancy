package org.datamancy.integration

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.datamancy.pipeline.sources.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for HyperliquidSource
 *
 * These tests require live network connection to Hyperliquid's WebSocket API
 * and are run in the Docker test environment.
 * They are disabled for regular builds and should be run via Docker Compose.
 */
@Disabled("Integration tests - run via docker compose --profile testing")
class HyperliquidSourceIntegrationTest {

    @Test
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
}
