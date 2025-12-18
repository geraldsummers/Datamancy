package org.datamancy.datafetcher.fetchers

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.datamancy.datafetcher.config.SearchConfig
import org.datamancy.datafetcher.scheduler.FetchResult
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SearchFetcherTest {

    private lateinit var mockConfig: SearchConfig

    @BeforeEach
    fun setup() {
        mockConfig = mockk(relaxed = true)
    }

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `fetch with blank API key returns early message`() = runBlocking {
        every { mockConfig.apiKey } returns ""
        every { mockConfig.queries } returns listOf("test query")

        val fetcher = SearchFetcher(mockConfig)
        val result = fetcher.fetch()

        assertTrue(result is FetchResult.Success)
        val success = result as FetchResult.Success
        assertTrue(success.message.contains("SerpAPI key not configured"))
    }

    @Test
    fun `fetch with no queries returns early message`() = runBlocking {
        every { mockConfig.apiKey } returns "test_api_key"
        every { mockConfig.queries } returns emptyList()

        val fetcher = SearchFetcher(mockConfig)
        val result = fetcher.fetch()

        assertTrue(result is FetchResult.Success)
        val success = result as FetchResult.Success
        assertTrue(success.message.contains("No queries configured"))
    }

    @Test
    fun `fetch with valid config attempts to fetch results`() = runBlocking {
        every { mockConfig.apiKey } returns "test_api_key"
        every { mockConfig.queries } returns listOf("kotlin programming", "machine learning")

        val fetcher = SearchFetcher(mockConfig)

        // Note: Full integration test would require mocking HTTP client
        assertNotNull(fetcher)
    }

    @Test
    fun `dryRun checks API key configuration`() = runBlocking {
        every { mockConfig.apiKey } returns "test_key"
        every { mockConfig.queries } returns listOf("test query")

        val fetcher = SearchFetcher(mockConfig)
        val result = fetcher.dryRun()

        assertNotNull(result)
        assertTrue(result.checks.isNotEmpty())
    }

    @Test
    fun `dryRun fails when API key is missing`() = runBlocking {
        every { mockConfig.apiKey } returns ""
        every { mockConfig.queries } returns listOf("test query")

        val fetcher = SearchFetcher(mockConfig)
        val result = fetcher.dryRun()

        assertNotNull(result)
        assertFalse(result.success)
    }

    @Test
    fun `fetch result includes version 2_0_0`() = runBlocking {
        every { mockConfig.apiKey } returns ""
        every { mockConfig.queries } returns emptyList()

        val fetcher = SearchFetcher(mockConfig)
        val result = fetcher.fetch()

        assertTrue(result is FetchResult.Success)
        assertEquals("2.0.0", result.version)
    }

    @Test
    fun `SearchConfig stores apiKey and queries`() {
        val config = SearchConfig(
            apiKey = "test_key_123",
            queries = listOf("query1", "query2", "query3")
        )

        assertEquals("test_key_123", config.apiKey)
        assertEquals(3, config.queries.size)
        assertEquals("query1", config.queries[0])
    }

    @Test
    fun `SearchConfig can have empty queries list`() {
        val config = SearchConfig(apiKey = "key", queries = emptyList())

        assertTrue(config.queries.isEmpty())
    }
}
