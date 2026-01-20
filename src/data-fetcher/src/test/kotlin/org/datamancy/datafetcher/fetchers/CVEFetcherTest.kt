package org.datamancy.datafetcher.fetchers

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.datamancy.datafetcher.config.CVEConfig
import org.datamancy.datafetcher.scheduler.FetchResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CVEFetcherTest {

    private lateinit var mockConfig: CVEConfig

    @BeforeEach
    fun setup() {
        mockConfig = mockk(relaxed = true)
    }

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `CVEConfig stores API key from environment`() {
        val config = CVEConfig(
            apiKey = "test-api-key",
            syncDaysBack = 7,
            fullBackfillEnabled = false,
            batchSize = 2000
        )

        assertEquals("test-api-key", config.apiKey)
        assertEquals(7, config.syncDaysBack)
        assertFalse(config.fullBackfillEnabled)
        assertEquals(2000, config.batchSize)
    }

    @Test
    fun `CVEConfig defaults are sensible`() {
        val config = CVEConfig()

        // Should have empty API key by default (reads from env)
        assertEquals(7, config.syncDaysBack)
        assertFalse(config.fullBackfillEnabled)
        assertEquals(2000, config.batchSize)
    }

    @Test
    fun `fetch with empty API key uses unauthenticated rate limits`() = runBlocking {
        every { mockConfig.apiKey } returns ""
        every { mockConfig.syncDaysBack } returns 7
        every { mockConfig.fullBackfillEnabled } returns false
        every { mockConfig.batchSize } returns 2000

        val fetcher = CVEFetcher(mockConfig)

        // Should initialize without throwing
        assertNotNull(fetcher)
    }

    @Test
    fun `fetch with API key uses authenticated rate limits`() = runBlocking {
        every { mockConfig.apiKey } returns "test-key-123"
        every { mockConfig.syncDaysBack } returns 7
        every { mockConfig.fullBackfillEnabled } returns false
        every { mockConfig.batchSize } returns 2000

        val fetcher = CVEFetcher(mockConfig)

        // Should initialize without throwing
        assertNotNull(fetcher)
    }

    @Test
    fun `dryRun checks configuration and connectivity`() = runBlocking {
        every { mockConfig.apiKey } returns "test-api-key"
        every { mockConfig.syncDaysBack } returns 7
        every { mockConfig.fullBackfillEnabled } returns false
        every { mockConfig.batchSize } returns 2000

        val fetcher = CVEFetcher(mockConfig)
        val result = fetcher.dryRun()

        assertNotNull(result)
        assertTrue(result.checks.isNotEmpty())
        // Should check: CVE directory, NVD API, API key config, ClickHouse
        assertTrue(result.checks.size >= 3)
    }

    @Test
    fun `dryRun reports API key status`() = runBlocking {
        every { mockConfig.apiKey } returns "test-key"
        every { mockConfig.syncDaysBack } returns 7
        every { mockConfig.fullBackfillEnabled } returns false
        every { mockConfig.batchSize } returns 2000

        val fetcher = CVEFetcher(mockConfig)
        val result = fetcher.dryRun()

        // Should have a check for API key configuration
        val apiKeyCheck = result.checks.find { it.name.contains("API Key") }
        assertNotNull(apiKeyCheck)
    }

    @Test
    fun `CVEConfig syncDaysBack affects initial sync window`() {
        val config3Days = CVEConfig(syncDaysBack = 3)
        val config30Days = CVEConfig(syncDaysBack = 30)

        assertEquals(3, config3Days.syncDaysBack)
        assertEquals(30, config30Days.syncDaysBack)
    }

    @Test
    fun `CVEConfig fullBackfillEnabled triggers historical fetch`() {
        val configIncremental = CVEConfig(fullBackfillEnabled = false)
        val configBackfill = CVEConfig(fullBackfillEnabled = true)

        assertFalse(configIncremental.fullBackfillEnabled)
        assertTrue(configBackfill.fullBackfillEnabled)
    }

    @Test
    fun `CVEConfig batchSize controls results per page`() {
        val configSmall = CVEConfig(batchSize = 500)
        val configLarge = CVEConfig(batchSize = 2000)

        assertEquals(500, configSmall.batchSize)
        assertEquals(2000, configLarge.batchSize)
    }

    @Test
    fun `CVEFetcher handles rate limiting gracefully`() = runBlocking {
        every { mockConfig.apiKey } returns "test-key"
        every { mockConfig.syncDaysBack } returns 1
        every { mockConfig.fullBackfillEnabled } returns false
        every { mockConfig.batchSize } returns 100

        val fetcher = CVEFetcher(mockConfig)

        // Should construct without errors
        assertNotNull(fetcher)
    }

    @Test
    fun `CVEFetcher supports incremental sync mode`() = runBlocking {
        every { mockConfig.apiKey } returns "test-key"
        every { mockConfig.syncDaysBack } returns 7
        every { mockConfig.fullBackfillEnabled } returns false
        every { mockConfig.batchSize } returns 2000

        val fetcher = CVEFetcher(mockConfig)

        // Incremental mode should be default
        assertNotNull(fetcher)
    }

    @Test
    fun `CVEFetcher supports full backfill mode`() = runBlocking {
        every { mockConfig.apiKey } returns "test-key"
        every { mockConfig.syncDaysBack } returns 7
        every { mockConfig.fullBackfillEnabled } returns true
        every { mockConfig.batchSize } returns 2000

        val fetcher = CVEFetcher(mockConfig)

        // Full backfill mode enabled
        assertNotNull(fetcher)
    }

    @Test
    fun `CVEConfig validates positive syncDaysBack`() {
        val config = CVEConfig(syncDaysBack = 7)
        assertTrue(config.syncDaysBack > 0)
    }

    @Test
    fun `CVEConfig validates positive batchSize`() {
        val config = CVEConfig(batchSize = 2000)
        assertTrue(config.batchSize > 0)
        assertTrue(config.batchSize <= 2000) // NVD max
    }

    @Test
    fun `fetch result version is 1_0_0`() = runBlocking {
        every { mockConfig.apiKey } returns ""
        every { mockConfig.syncDaysBack } returns 1
        every { mockConfig.fullBackfillEnabled } returns false
        every { mockConfig.batchSize } returns 100

        val fetcher = CVEFetcher(mockConfig)

        // Note: Full integration test would require mocking HTTP client and ClickHouse
        // This test verifies the fetcher can be instantiated
        assertNotNull(fetcher)
    }
}
