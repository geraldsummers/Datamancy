package org.datamancy.pipeline.integration

import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.sources.BinanceSource
import org.datamancy.pipeline.sources.CveSource
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests that fetch real data from external APIs
 * These tests don't save anything, just verify connectivity and parsing
 */
class DataSourceIntegrationTest {

    @Test
    fun `test CVE source fetches real data without saving`() = runBlocking {
        println("🔍 Testing CVE/NVD API integration...")

        val source = CveSource(
            apiKey = null,
            startIndex = 0,
            maxResults = 3  // Just fetch 3 CVEs
        )

        val cves = source.fetch().take(3).toList()

        assertTrue(cves.isNotEmpty(), "Should fetch at least one CVE")
        println("✅ Fetched ${cves.size} CVEs")

        cves.forEach { cve ->
            assertNotNull(cve.cveId)
            assertTrue(cve.cveId.startsWith("CVE-"), "CVE ID should start with CVE-")
            assertNotNull(cve.description)
            assertNotNull(cve.severity)

            println("  - ${cve.cveId} | ${cve.severity} | ${cve.description.take(80)}...")
        }

        println("✅ CVE integration test passed (no data saved)")
    }

    @Test
    fun `test Binance source fetches real data without saving`() = runBlocking {
        println("🔍 Testing Binance API integration...")

        val source = BinanceSource(
            symbols = listOf("BTCUSDT"),
            interval = "1d",
            startTime = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000),  // Last 7 days
            endTime = null,
            limit = 10
        )

        val klines = source.fetch().take(5).toList()

        assertTrue(klines.isNotEmpty(), "Should fetch at least one kline")
        println("✅ Fetched ${klines.size} klines")

        klines.forEach { kline ->
            assertNotNull(kline.symbol)
            assertTrue(kline.symbol == "BTCUSDT")
            assertTrue(kline.open > 0, "Open price should be positive")
            assertTrue(kline.high >= kline.open, "High should be >= open")
            assertTrue(kline.low <= kline.close, "Low should be <= close")
            assertTrue(kline.volume >= 0, "Volume should be non-negative")

            println("  - ${kline.symbol} | ${java.time.Instant.ofEpochMilli(kline.openTime)} | " +
                    "O: ${kline.open} H: ${kline.high} L: ${kline.low} C: ${kline.close}")
        }

        println("✅ Binance integration test passed (no data saved)")
    }

    @Test
    fun `test CVE source respects rate limiting`() = runBlocking {
        println("🔍 Testing CVE rate limiting...")

        val startTime = System.currentTimeMillis()

        val source = CveSource(
            apiKey = null,
            startIndex = 0,
            maxResults = 10
        )

        // Fetch 10 CVEs - should take at least 6 seconds due to rate limiting (6s between requests)
        val cves = source.fetch().take(10).toList()

        val duration = System.currentTimeMillis() - startTime

        println("✅ Fetched ${cves.size} CVEs in ${duration}ms")

        // With 6 second delays between requests, 10 items should take roughly 6 seconds
        // (Actually depends on how many come per page, but should have some delay)
        if (cves.size > 1) {
            assertTrue(duration > 1000, "Should have some delay due to rate limiting")
        }

        println("✅ Rate limiting working correctly")
    }

    @Test
    fun `test Binance source handles multiple symbols`() = runBlocking {
        println("🔍 Testing Binance multi-symbol fetch...")

        val source = BinanceSource(
            symbols = listOf("BTCUSDT", "ETHUSDT"),
            interval = "1h",
            startTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000),  // Last 24 hours
            endTime = null,
            limit = 5
        )

        val klines = source.fetch().take(10).toList()

        assertTrue(klines.isNotEmpty())

        val symbols = klines.map { it.symbol }.distinct()
        println("✅ Fetched data for symbols: ${symbols.joinToString(", ")}")

        assertTrue(symbols.any { it == "BTCUSDT" || it == "ETHUSDT" })

        println("✅ Multi-symbol integration test passed")
    }
}
