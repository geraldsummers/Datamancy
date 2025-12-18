package org.datamancy.datafetcher.fetchers

import kotlinx.coroutines.test.runTest
import org.datamancy.datafetcher.config.LegalConfig
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LegalDocsFetcherTest {

    private val testConfig = LegalConfig(
        ausLegislationUrl = "https://www.legislation.gov.au/",
        stateUrls = mapOf(
            "nsw" to "https://legislation.nsw.gov.au/",
            "vic" to "https://www.legislation.vic.gov.au/",
            "qld" to "https://www.legislation.qld.gov.au/"
        )
    )

    @Test
    fun `test LegislationItem data class`() {
        val item = LegislationItem(
            title = "Test Act 2024",
            jurisdiction = "federal",
            year = "2024",
            type = "Act",
            identifier = "C2024A00001",
            url = "https://example.com/act",
            status = "In force",
            registrationDate = "2024-01-01",
            metadata = mapOf("key" to "value")
        )

        assertEquals("Test Act 2024", item.title)
        assertEquals("federal", item.jurisdiction)
        assertEquals("2024", item.year)
        assertEquals("Act", item.type)
        assertEquals("C2024A00001", item.identifier)
        assertEquals("https://example.com/act", item.url)
        assertEquals("In force", item.status)
        assertEquals("2024-01-01", item.registrationDate)
        assertEquals("value", item.metadata["key"])
    }

    @Test
    fun `test LegislationSection data class`() {
        val section = LegislationSection(
            actTitle = "Test Act 2024",
            actUrl = "https://example.com/act",
            jurisdiction = "federal",
            sectionNumber = "42",
            sectionTitle = "Test Section",
            markdownContent = "# Test Content",
            year = "2024",
            identifier = "C2024A00001"
        )

        assertEquals("Test Act 2024", section.actTitle)
        assertEquals("https://example.com/act", section.actUrl)
        assertEquals("federal", section.jurisdiction)
        assertEquals("42", section.sectionNumber)
        assertEquals("Test Section", section.sectionTitle)
        assertEquals("# Test Content", section.markdownContent)
        assertEquals("2024", section.year)
        assertEquals("C2024A00001", section.identifier)
    }

    @Test
    fun `test LegalDocsFetcher instantiation`() {
        val fetcher = LegalDocsFetcher(testConfig)
        assertNotNull(fetcher)
    }

    @Test
    fun `test dryRun returns checks`() = runTest {
        val fetcher = LegalDocsFetcher(testConfig)
        val result = fetcher.dryRun()

        assertNotNull(result)
        assertNotNull(result.checks)
        assertTrue(result.checks.isNotEmpty())

        // Should have federal + state URLs + directory checks
        assertTrue(result.checks.size >= 4)
    }

    @Test
    fun `test dryRun checks federal legislation URL`() = runTest {
        val fetcher = LegalDocsFetcher(testConfig)
        val result = fetcher.dryRun()

        val federalCheck = result.checks.find { it.name.contains("Federal") }
        assertNotNull(federalCheck)
    }

    @Test
    fun `test dryRun checks state legislation URLs`() = runTest {
        val fetcher = LegalDocsFetcher(testConfig)
        val result = fetcher.dryRun()

        val nswCheck = result.checks.find { it.name.contains("NSW") }
        assertNotNull(nswCheck)

        val vicCheck = result.checks.find { it.name.contains("VIC") }
        assertNotNull(vicCheck)

        val qldCheck = result.checks.find { it.name.contains("QLD") }
        assertNotNull(qldCheck)
    }

    @Test
    fun `test dryRun checks data directory`() = runTest {
        val fetcher = LegalDocsFetcher(testConfig)
        val result = fetcher.dryRun()

        val dirCheck = result.checks.find { it.name.contains("directory") }
        assertNotNull(dirCheck)
    }
}
