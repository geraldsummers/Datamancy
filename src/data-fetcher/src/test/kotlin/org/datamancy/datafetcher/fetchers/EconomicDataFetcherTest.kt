package org.datamancy.datafetcher.fetchers

import kotlinx.coroutines.test.runTest
import org.datamancy.datafetcher.config.EconomicConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EconomicDataFetcherTest {

    private val testConfig = EconomicConfig(
        fredApiKey = "test_key_12345"
    )

    private val testConfigNoKey = EconomicConfig(
        fredApiKey = ""
    )

    @Test
    fun `test EconomicSeries data class`() {
        val series = EconomicSeries(
            source = "fred",
            seriesId = "GDP",
            name = "US GDP",
            frequency = "quarterly"
        )

        assertEquals("fred", series.source)
        assertEquals("GDP", series.seriesId)
        assertEquals("US GDP", series.name)
        assertEquals("quarterly", series.frequency)
    }

    @Test
    fun `test EconomicSeries data class with default frequency`() {
        val series = EconomicSeries(
            source = "imf",
            seriesId = "NGDP_R",
            name = "Real GDP"
        )

        assertEquals("monthly", series.frequency)
    }

    @Test
    fun `test EconomicDataFetcher instantiation with API key`() {
        val fetcher = EconomicDataFetcher(testConfig)
        assertNotNull(fetcher)
    }

    @Test
    fun `test EconomicDataFetcher instantiation without API key`() {
        val fetcher = EconomicDataFetcher(testConfigNoKey)
        assertNotNull(fetcher)
    }

    @Test
    fun `test dryRun with API key returns checks`() = runTest {
        val fetcher = EconomicDataFetcher(testConfig)
        val result = fetcher.dryRun()

        assertNotNull(result)
        assertNotNull(result.checks)
        assertTrue(result.checks.isNotEmpty())

        // Should have checks for FRED, World Bank, IMF, OECD, directory
        assertTrue(result.checks.size >= 5)
    }

    @Test
    fun `test dryRun without API key returns checks`() = runTest {
        val fetcher = EconomicDataFetcher(testConfigNoKey)
        val result = fetcher.dryRun()

        assertNotNull(result)
        assertNotNull(result.checks)
        assertTrue(result.checks.isNotEmpty())
    }

    @Test
    fun `test dryRun checks FRED API when key configured`() = runTest {
        val fetcher = EconomicDataFetcher(testConfig)
        val result = fetcher.dryRun()

        val fredCheck = result.checks.find { it.name.contains("FRED") }
        assertNotNull(fredCheck)
    }

    @Test
    fun `test dryRun reports FRED key not configured`() = runTest {
        val fetcher = EconomicDataFetcher(testConfigNoKey)
        val result = fetcher.dryRun()

        val fredCheck = result.checks.find { it.name.contains("FRED") }
        assertNotNull(fredCheck)
        assertEquals(false, fredCheck.passed)
        assertTrue(fredCheck.message.contains("not configured"))
    }

    @Test
    fun `test dryRun checks World Bank`() = runTest {
        val fetcher = EconomicDataFetcher(testConfig)
        val result = fetcher.dryRun()

        val wbCheck = result.checks.find { it.name.contains("World Bank") }
        assertNotNull(wbCheck)
    }

    @Test
    fun `test dryRun checks IMF`() = runTest {
        val fetcher = EconomicDataFetcher(testConfig)
        val result = fetcher.dryRun()

        val imfCheck = result.checks.find { it.name.contains("IMF") }
        assertNotNull(imfCheck)
    }

    @Test
    fun `test dryRun checks OECD`() = runTest {
        val fetcher = EconomicDataFetcher(testConfig)
        val result = fetcher.dryRun()

        val oecdCheck = result.checks.find { it.name.contains("OECD") }
        assertNotNull(oecdCheck)
    }

    @Test
    fun `test dryRun checks data directory`() = runTest {
        val fetcher = EconomicDataFetcher(testConfig)
        val result = fetcher.dryRun()

        val dirCheck = result.checks.find { it.name.contains("directory") }
        assertNotNull(dirCheck)
    }
}
