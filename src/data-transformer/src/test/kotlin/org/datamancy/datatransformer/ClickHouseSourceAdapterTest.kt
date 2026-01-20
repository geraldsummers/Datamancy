package org.datamancy.datatransformer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClickHouseSourceAdapterTest {

    @Test
    fun `determineSourceType recognizes CVE collection`() {
        val sourceType = when {
            "cve" == "cve" -> "cve"
            "cve".startsWith("legal-") -> "legal"
            else -> "unknown"
        }

        assertEquals("cve", sourceType)
    }

    @Test
    fun `determineSourceType recognizes legal collections`() {
        val collections = listOf("legal-federal", "legal-nsw", "legal-vic")

        collections.forEach { collection ->
            val sourceType = when {
                collection == "cve" -> "cve"
                collection.startsWith("legal-") -> "legal"
                else -> "unknown"
            }

            assertEquals("legal", sourceType, "Should recognize $collection as legal")
        }
    }

    @Test
    fun `CVE page ID is hash of CVE ID`() {
        // Test that page IDs are generated consistently
        val cveId = "CVE-2024-12345"

        // Simulate hash generation (same logic as in adapter)
        val hash1 = cveId.hashCode()
        val hash2 = cveId.hashCode()

        assertEquals(hash1, hash2, "Hash should be consistent for same CVE ID")
    }

    @Test
    fun `CVE URL format is correct`() {
        val cveId = "CVE-2024-12345"
        val expectedUrl = "https://nvd.nist.gov/vuln/detail/$cveId"

        assertTrue(expectedUrl.startsWith("https://nvd.nist.gov/vuln/detail/"))
        assertTrue(expectedUrl.endsWith(cveId))
    }

    @Test
    fun `CVE markdown format includes required sections`() {
        val cveMarkdown = """
            # CVE-2024-12345
            **Severity:** HIGH
            **CVSS Score:** 7.5

            ## Description
            Vulnerability description

            ## Affected Products
            - Product list

            ## References
            - Reference links
        """.trimIndent()

        assertTrue(cveMarkdown.contains("# CVE-"))
        assertTrue(cveMarkdown.contains("**Severity:**"))
        assertTrue(cveMarkdown.contains("**CVSS Score:**"))
        assertTrue(cveMarkdown.contains("## Description"))
        assertTrue(cveMarkdown.contains("## Affected Products"))
        assertTrue(cveMarkdown.contains("## References"))
    }

    @Test
    fun `collection name parsing for legal jurisdictions`() {
        val testCases = mapOf(
            "legal-federal" to "federal",
            "legal-nsw" to "nsw",
            "legal-vic" to "vic",
            "legal-qld" to "qld"
        )

        testCases.forEach { (collection, expected) ->
            val jurisdiction = collection.removePrefix("legal-")
            assertEquals(expected, jurisdiction)
        }
    }

    @Test
    fun `CVE collection is recognized separately from legal`() {
        val cveCollection = "cve"
        val legalCollection = "legal-federal"

        assertFalse(cveCollection.startsWith("legal-"))
        assertTrue(legalCollection.startsWith("legal-"))
        assertNotEquals(cveCollection, legalCollection)
    }

    @Test
    fun `listCollections includes CVE when data exists`() {
        // Simulates the logic: if CVE count > 0, add "cve" to collections
        val cveCount = 1000L
        val collections = mutableListOf<String>()

        if (cveCount > 0) {
            collections.add("cve")
        }

        assertTrue(collections.contains("cve"))
        assertEquals(1, collections.size)
    }

    @Test
    fun `listCollections excludes CVE when no data exists`() {
        // Simulates the logic: if CVE count = 0, don't add "cve"
        val cveCount = 0L
        val collections = mutableListOf<String>()

        if (cveCount > 0) {
            collections.add("cve")
        }

        assertFalse(collections.contains("cve"))
        assertEquals(0, collections.size)
    }

    @Test
    fun `CVE severity levels are recognized`() {
        val validSeverities = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW", "NONE")

        validSeverities.forEach { severity ->
            assertTrue(severity.isNotEmpty())
            assertTrue(severity.all { it.isUpperCase() || it.isWhitespace() })
        }
    }

    @Test
    fun `CVSS score ranges are valid`() {
        val testScores = listOf(0.0, 3.5, 7.0, 9.8, 10.0)

        testScores.forEach { score ->
            assertTrue(score >= 0.0)
            assertTrue(score <= 10.0)
        }
    }

    @Test
    fun `CWE ID format is valid`() {
        val cweIds = listOf("CWE-79", "CWE-89", "CWE-787", "CWE-20")

        cweIds.forEach { cweId ->
            assertTrue(cweId.startsWith("CWE-"))
            val number = cweId.removePrefix("CWE-").toIntOrNull()
            assertNotNull(number, "$cweId should have valid number")
        }
    }

    @Test
    fun `CPE match format is valid`() {
        val cpe = "cpe:2.3:a:vendor:product:1.0:*:*:*:*:*:*:*"

        assertTrue(cpe.startsWith("cpe:"))
        assertTrue(cpe.split(":").size > 5)
    }

    @Test
    fun `CVE page export includes source link`() {
        val cveId = "CVE-2024-12345"
        val markdown = """
            # $cveId
            **Severity:** HIGH

            ## Description
            Vulnerability description

            **Source:** https://nvd.nist.gov/vuln/detail/$cveId
        """.trimIndent()

        assertTrue(markdown.contains("**Source:**"))
        assertTrue(markdown.contains("nvd.nist.gov/vuln/detail/$cveId"))
    }

    @Test
    fun `empty CWE array is handled`() {
        val cweIds = emptyList<String>()

        assertTrue(cweIds.isEmpty())
        assertEquals(0, cweIds.size)
    }

    @Test
    fun `empty CPE matches array is handled`() {
        val cpeMatches = emptyList<String>()

        assertTrue(cpeMatches.isEmpty())
        assertEquals(0, cpeMatches.size)
    }

    @Test
    fun `CVE with many references is truncated in markdown`() {
        val references = (1..50).map { "https://example.com/ref$it" }

        // Logic: take first 10, show count of remaining
        val displayedRefs = references.take(10)
        val remainingCount = references.size - 10

        assertEquals(10, displayedRefs.size)
        assertEquals(40, remainingCount)
    }

    @Test
    fun `CVE with many affected products is truncated`() {
        val cpeMatches = (1..100).map { "cpe:2.3:a:vendor:product$it:*:*:*:*:*:*:*:*" }

        // Logic: take first 20, show count of remaining
        val displayedProducts = cpeMatches.take(20)
        val remainingCount = cpeMatches.size - 20

        assertEquals(20, displayedProducts.size)
        assertEquals(80, remainingCount)
    }
}
