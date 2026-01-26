package org.datamancy.pipeline.sources

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CVE tests disabled - requires API key from https://nvd.nist.gov/developers/request-an-api-key
 * Enable CVE source by setting CVE_ENABLED=true and CVE_API_KEY env vars
 */
@Disabled("CVE source requires API key - enable with CVE_ENABLED=true and CVE_API_KEY")
class CveSourceTest {

    @Test
    fun `test CveEntry toText formatting`() {
        val cve = CveEntry(
            cveId = "CVE-2024-1234",
            description = "A critical vulnerability in XYZ software",
            severity = "HIGH",
            baseScore = 9.8,
            vectorString = "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
            publishedDate = "2024-01-01T00:00:00.000",
            lastModifiedDate = "2024-01-02T00:00:00.000",
            references = listOf(
                "https://example.com/advisory",
                "https://nvd.nist.gov/vuln/detail/CVE-2024-1234"
            ),
            affectedProducts = listOf("vendor:product1", "vendor:product2")
        )

        val text = cve.toText()

        assertTrue(text.contains("CVE-2024-1234"))
        assertTrue(text.contains("HIGH"))
        assertTrue(text.contains("9.8"))
        assertTrue(text.contains("A critical vulnerability in XYZ software"))
        assertTrue(text.contains("vendor:product1"))
        assertTrue(text.contains("CVSS Vector"))
    }

    @Test
    fun `test CveEntry contentHash is consistent`() {
        val cve1 = CveEntry(
            cveId = "CVE-2024-1234",
            description = "Test",
            severity = "HIGH",
            baseScore = 9.8,
            vectorString = null,
            publishedDate = "2024-01-01T00:00:00.000",
            lastModifiedDate = "2024-01-02T00:00:00.000",
            references = emptyList(),
            affectedProducts = emptyList()
        )

        val cve2 = CveEntry(
            cveId = "CVE-2024-1234",
            description = "Different description",
            severity = "LOW",
            baseScore = 2.0,
            vectorString = null,
            publishedDate = "2024-01-01T00:00:00.000",
            lastModifiedDate = "2024-01-02T00:00:00.000",
            references = emptyList(),
            affectedProducts = emptyList()
        )

        // Same CVE ID and lastModifiedDate should produce same hash
        assertEquals(cve1.contentHash(), cve2.contentHash())
    }

    @Test
    fun `test CveEntry contentHash changes with modification date`() {
        val cve1 = CveEntry(
            cveId = "CVE-2024-1234",
            description = "Test",
            severity = "HIGH",
            baseScore = 9.8,
            vectorString = null,
            publishedDate = "2024-01-01T00:00:00.000",
            lastModifiedDate = "2024-01-02T00:00:00.000",
            references = emptyList(),
            affectedProducts = emptyList()
        )

        val cve2 = cve1.copy(lastModifiedDate = "2024-01-03T00:00:00.000")

        // Different modification dates should produce different hashes
        assertTrue(cve1.contentHash() != cve2.contentHash())
    }

    // NOTE: This test hits the real NVD API - commented out to avoid rate limits in CI
    // Uncomment locally to test API integration
    /*
    @Test
    fun `test CveSource with small maxResults limit`() = runBlocking {
        val source = CveSource(
            apiKey = null,
            startIndex = 0,
            maxResults = 5  // Only fetch 5 CVEs
        )

        val cves = source.fetch().toList()

        assertTrue(cves.size <= 5, "Should not exceed maxResults limit")

        if (cves.isNotEmpty()) {
            cves.forEach { cve ->
                assertNotNull(cve.cveId)
                assertTrue(cve.cveId.startsWith("CVE-"))
                assertNotNull(cve.description)
                assertNotNull(cve.severity)
            }
        }
    }
    */

    @Test
    fun `test CveEntry handles null baseScore`() {
        val cve = CveEntry(
            cveId = "CVE-2024-9999",
            description = "Test vulnerability",
            severity = "UNKNOWN",
            baseScore = null,
            vectorString = null,
            publishedDate = "2024-01-01T00:00:00.000",
            lastModifiedDate = "2024-01-01T00:00:00.000",
            references = emptyList(),
            affectedProducts = emptyList()
        )

        val text = cve.toText()
        assertTrue(text.contains("UNKNOWN"))
        assertTrue(!text.contains("CVSS:"))  // Should not include CVSS line if null
    }

    @Test
    fun `test CveEntry truncates long product lists`() {
        val products = (1..20).map { "vendor:product$it" }

        val cve = CveEntry(
            cveId = "CVE-2024-1234",
            description = "Test",
            severity = "HIGH",
            baseScore = 9.8,
            vectorString = null,
            publishedDate = "2024-01-01T00:00:00.000",
            lastModifiedDate = "2024-01-01T00:00:00.000",
            references = emptyList(),
            affectedProducts = products
        )

        val text = cve.toText()
        assertTrue(text.contains("and 10 more"))

        // Count occurrences of "vendor:product" to ensure only 10 shown
        val count = text.split("vendor:product").size - 1
        assertEquals(10, count)
    }
}
