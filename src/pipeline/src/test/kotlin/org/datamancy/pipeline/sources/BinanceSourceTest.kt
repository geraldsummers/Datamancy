package org.datamancy.pipeline.sources

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BinanceSourceTest {

    @Test
    fun `test BinanceKline toText formatting`() {
        val kline = BinanceKline(
            symbol = "BTCUSDT",
            interval = "1h",
            openTime = 1704067200000,
            open = 45000.50,
            high = 45500.75,
            low = 44800.25,
            close = 45200.00,
            volume = 1234.56,
            closeTime = 1704070800000,
            quoteAssetVolume = 55555555.55,
            numberOfTrades = 12345,
            takerBuyBaseAssetVolume = 600.00,
            takerBuyQuoteAssetVolume = 27000000.00
        )

        val text = kline.toText()

        assertTrue(text.contains("BTCUSDT"))
        assertTrue(text.contains("1h"))
        assertTrue(text.contains("45000.5"))
        assertTrue(text.contains("45500.75"))
        assertTrue(text.contains("44800.25"))
        assertTrue(text.contains("45200.0"))
        assertTrue(text.contains("1234.56"))
        assertTrue(text.contains("12345"))
    }

    @Test
    fun `test BinanceKline contentHash is unique per symbol interval and time`() {
        val kline1 = BinanceKline(
            symbol = "BTCUSDT",
            interval = "1h",
            openTime = 1704067200000,
            open = 45000.0,
            high = 46000.0,
            low = 44000.0,
            close = 45500.0,
            volume = 1000.0,
            closeTime = 1704070800000,
            quoteAssetVolume = 45000000.0,
            numberOfTrades = 5000,
            takerBuyBaseAssetVolume = 500.0,
            takerBuyQuoteAssetVolume = 22500000.0
        )

        val kline2 = BinanceKline(
            symbol = "BTCUSDT",
            interval = "1h",
            openTime = 1704067200000,
            open = 50000.0,  // Different price
            high = 51000.0,
            low = 49000.0,
            close = 50500.0,
            volume = 2000.0,
            closeTime = 1704070800000,
            quoteAssetVolume = 100000000.0,
            numberOfTrades = 10000,
            takerBuyBaseAssetVolume = 1000.0,
            takerBuyQuoteAssetVolume = 50000000.0
        )

        // Same symbol, interval, and openTime should produce same hash
        assertEquals(kline1.contentHash(), kline2.contentHash())
    }

    @Test
    fun `test BinanceKline contentHash differs for different symbols`() {
        val kline1 = BinanceKline(
            symbol = "BTCUSDT",
            interval = "1h",
            openTime = 1704067200000,
            open = 45000.0,
            high = 46000.0,
            low = 44000.0,
            close = 45500.0,
            volume = 1000.0,
            closeTime = 1704070800000,
            quoteAssetVolume = 45000000.0,
            numberOfTrades = 5000,
            takerBuyBaseAssetVolume = 500.0,
            takerBuyQuoteAssetVolume = 22500000.0
        )

        val kline2 = kline1.copy(symbol = "ETHUSDT")

        assertTrue(kline1.contentHash() != kline2.contentHash())
    }

    @Test
    fun `test BinanceKline contentHash differs for different intervals`() {
        val kline1 = BinanceKline(
            symbol = "BTCUSDT",
            interval = "1h",
            openTime = 1704067200000,
            open = 45000.0,
            high = 46000.0,
            low = 44000.0,
            close = 45500.0,
            volume = 1000.0,
            closeTime = 1704070800000,
            quoteAssetVolume = 45000000.0,
            numberOfTrades = 5000,
            takerBuyBaseAssetVolume = 500.0,
            takerBuyQuoteAssetVolume = 22500000.0
        )

        val kline2 = kline1.copy(interval = "1d")

        assertTrue(kline1.contentHash() != kline2.contentHash())
    }

    @Test
    fun `test BinanceKline contentHash differs for different times`() {
        val kline1 = BinanceKline(
            symbol = "BTCUSDT",
            interval = "1h",
            openTime = 1704067200000,
            open = 45000.0,
            high = 46000.0,
            low = 44000.0,
            close = 45500.0,
            volume = 1000.0,
            closeTime = 1704070800000,
            quoteAssetVolume = 45000000.0,
            numberOfTrades = 5000,
            takerBuyBaseAssetVolume = 500.0,
            takerBuyQuoteAssetVolume = 22500000.0
        )

        val kline2 = kline1.copy(openTime = 1704070800000)

        assertTrue(kline1.contentHash() != kline2.contentHash())
    }

    // NOTE: These tests hit the real Binance API - commented out to avoid CI issues
    // Uncomment locally to test API integration
    /*
    @Test
    fun `test BinanceSource fetches recent data for single symbol`() = runBlocking {
        val source = BinanceSource(
            symbols = listOf("BTCUSDT"),
            interval = "1h",
            startTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000),  // Last 7 days
            endTime = null,
            limit = 100
        )

        // Only fetch first 10 to keep test fast
        val klines = source.fetch().take(10).toList()

        assertTrue(klines.isNotEmpty(), "Should fetch at least some klines")

        klines.forEach { kline ->
            assertEquals("BTCUSDT", kline.symbol)
            assertEquals("1h", kline.interval)
            assertNotNull(kline.openTime)
            assertTrue(kline.open > 0)
            assertTrue(kline.high >= kline.open)
            assertTrue(kline.low <= kline.close)
            assertTrue(kline.volume >= 0)
        }
    }

    @Test
    fun `test BinanceSource handles multiple symbols`() = runBlocking {
        val source = BinanceSource(
            symbols = listOf("BTCUSDT", "ETHUSDT"),
            interval = "1d",
            startTime = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000),  // Last 30 days
            endTime = null,
            limit = 10
        )

        val klines = source.fetch().take(20).toList()

        assertTrue(klines.isNotEmpty())

        val symbols = klines.map { it.symbol }.distinct()
        assertTrue(symbols.contains("BTCUSDT") || symbols.contains("ETHUSDT"))
    }

    @Test
    fun `test BinanceSource respects time range`() = runBlocking {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - (24 * 60 * 60 * 1000)  // Last 24 hours

        val source = BinanceSource(
            symbols = listOf("BTCUSDT"),
            interval = "1h",
            startTime = startTime,
            endTime = endTime,
            limit = 100
        )

        val klines = source.fetch().toList()

        klines.forEach { kline ->
            assertTrue(kline.openTime >= startTime, "Kline openTime should be >= startTime")
            assertTrue(kline.openTime <= endTime, "Kline openTime should be <= endTime")
        }
    }
    */
}
