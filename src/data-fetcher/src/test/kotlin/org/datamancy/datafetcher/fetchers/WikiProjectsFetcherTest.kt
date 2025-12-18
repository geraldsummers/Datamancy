package org.datamancy.datafetcher.fetchers

import kotlinx.coroutines.test.runTest
import org.datamancy.datafetcher.config.WikiConfig
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WikiProjectsFetcherTest {

    private val testConfig = WikiConfig(
        apiUrl = "https://en.wikipedia.org/w/api.php",
        dumpsUrl = "https://dumps.wikimedia.org/",
        wikidataUrl = "https://www.wikidata.org/w/api.php"
    )

    @Test
    fun `test WikiProjectsFetcher instantiation`() {
        val fetcher = WikiProjectsFetcher(testConfig)
        assertNotNull(fetcher)
    }

    @Test
    fun `test dryRun returns checks`() = runTest {
        val fetcher = WikiProjectsFetcher(testConfig)
        val result = fetcher.dryRun()

        assertNotNull(result)
        assertNotNull(result.checks)
        assertTrue(result.checks.isNotEmpty())

        // Should have at least 4 checks: 3 URLs + directory
        assertTrue(result.checks.size >= 4)
    }

    @Test
    fun `test dryRun checks Wikipedia dumps URL`() = runTest {
        val fetcher = WikiProjectsFetcher(testConfig)
        val result = fetcher.dryRun()

        val dumpsCheck = result.checks.find { it.name.contains("dumps") }
        assertNotNull(dumpsCheck)
    }

    @Test
    fun `test dryRun checks Wikipedia API URL`() = runTest {
        val fetcher = WikiProjectsFetcher(testConfig)
        val result = fetcher.dryRun()

        val apiCheck = result.checks.find { it.name.contains("API") || it.name.contains("Wikipedia") }
        assertNotNull(apiCheck)
    }

    @Test
    fun `test dryRun checks Wikidata URL`() = runTest {
        val fetcher = WikiProjectsFetcher(testConfig)
        val result = fetcher.dryRun()

        val wikidataCheck = result.checks.find { it.name.contains("Wikidata") }
        assertNotNull(wikidataCheck)
    }

    @Test
    fun `test dryRun checks data directory`() = runTest {
        val fetcher = WikiProjectsFetcher(testConfig)
        val result = fetcher.dryRun()

        val dirCheck = result.checks.find { it.name.contains("directory") }
        assertNotNull(dirCheck)
    }
}
