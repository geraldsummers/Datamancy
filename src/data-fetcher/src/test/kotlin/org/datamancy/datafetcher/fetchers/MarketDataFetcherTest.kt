package org.datamancy.datafetcher.fetchers

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.datamancy.datafetcher.config.MarketDataConfig
import org.datamancy.datafetcher.scheduler.FetchResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MarketDataFetcherTest {

    private lateinit var mockConfig: MarketDataConfig

    @BeforeEach
    fun setup() {
        mockConfig = mockk(relaxed = true)
    }

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `fetch with empty symbols returns early`() = runBlocking {
        every { mockConfig.symbols } returns emptyList()
        every { mockConfig.cryptoSources } returns listOf("coingecko")

        val fetcher = MarketDataFetcher(mockConfig)
        val result = fetcher.fetch()

        assertTrue(result is FetchResult.Success)
        val success = result as FetchResult.Success
        // Should complete successfully even with no symbols
        assertNotNull(success.message)
    }

    @Test
    fun `fetch with valid config attempts market data fetch`() = runBlocking {
        every { mockConfig.symbols } returns listOf("BTC", "ETH", "AAPL")
        every { mockConfig.cryptoSources } returns listOf("coingecko")
        every { mockConfig.stockSources } returns listOf("yahoo")

        val fetcher = MarketDataFetcher(mockConfig)

        // Note: Full integration test would require mocking HTTP client
        assertNotNull(fetcher)
    }

    @Test
    fun `dryRun checks configuration`() = runBlocking {
        every { mockConfig.symbols } returns listOf("BTC", "ETH")
        every { mockConfig.cryptoSources } returns listOf("coingecko")
        every { mockConfig.stockSources } returns emptyList()

        val fetcher = MarketDataFetcher(mockConfig)
        val result = fetcher.dryRun()

        assertNotNull(result)
        assertTrue(result.checks.isNotEmpty())
    }

    @Test
    fun `fetch result includes version 2_0_0`() = runBlocking {
        every { mockConfig.symbols } returns emptyList()
        every { mockConfig.cryptoSources } returns emptyList()
        every { mockConfig.stockSources } returns emptyList()

        val fetcher = MarketDataFetcher(mockConfig)
        val result = fetcher.fetch()

        assertTrue(result is FetchResult.Success)
        assertEquals("2.0.0", result.version)
    }

    @Test
    fun `MarketDataConfig stores symbols and sources`() {
        val config = MarketDataConfig(
            symbols = listOf("BTC", "ETH", "SOL"),
            cryptoSources = listOf("coingecko", "binance"),
            stockSources = listOf("yahoo", "alphavantage")
        )

        assertEquals(3, config.symbols.size)
        assertEquals(2, config.cryptoSources.size)
        assertEquals(2, config.stockSources.size)
        assertTrue(config.symbols.contains("BTC"))
    }

    @Test
    fun `MarketDataConfig can have empty crypto sources`() {
        val config = MarketDataConfig(
            symbols = listOf("AAPL"),
            cryptoSources = emptyList(),
            stockSources = listOf("yahoo")
        )

        assertTrue(config.cryptoSources.isEmpty())
        assertFalse(config.stockSources.isEmpty())
    }

    @Test
    fun `config distinguishes between crypto and stock symbols`() {
        val config = MarketDataConfig(
            symbols = listOf("BTC", "AAPL", "ETH", "GOOGL"),
            cryptoSources = listOf("coingecko"),
            stockSources = listOf("yahoo")
        )

        // Config should contain both crypto and stock symbols
        assertEquals(4, config.symbols.size)
        assertTrue(config.symbols.contains("BTC")) // Crypto
        assertTrue(config.symbols.contains("AAPL")) // Stock
    }
}
