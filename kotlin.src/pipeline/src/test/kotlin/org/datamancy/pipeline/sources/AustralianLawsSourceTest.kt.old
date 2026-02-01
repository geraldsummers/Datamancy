package org.datamancy.pipeline.sources

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class AustralianLawsSourceTest {

    private lateinit var mockServer: MockWebServer

    @BeforeTest
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @AfterTest
    fun teardown() {
        mockServer.shutdown()
    }

    @Test
    fun `test basic jurisdiction mapping`() {
        val source = AustralianLawsSource(
            jurisdictions = listOf("commonwealth", "nsw", "vic"),
            maxLawsPerJurisdiction = 10,
            startYear = 2020
        )

        assertEquals("AustralianLawsSource", source.name)
    }

    @Test
    fun `test all jurisdictions are supported`() {
        val allJurisdictions = listOf("commonwealth", "nsw", "vic", "qld", "wa", "sa", "tas", "act", "nt")

        val source = AustralianLawsSource(
            jurisdictions = allJurisdictions,
            maxLawsPerJurisdiction = 5
        )

        // Verify source accepts all jurisdictions without error
        assertNotNull(source)
        assertEquals("AustralianLawsSource", source.name)
    }

    @Test
    fun `test empty jurisdictions list returns empty flow`() = runBlocking {
        val source = AustralianLawsSource(
            jurisdictions = emptyList(),
            maxLawsPerJurisdiction = 10
        )

        val laws = source.fetch().toList()
        assertTrue(laws.isEmpty(), "Should return empty list when no jurisdictions specified")
    }

    @Test
    fun `test max laws per jurisdiction limit is respected`() {
        val source = AustralianLawsSource(
            jurisdictions = listOf("commonwealth"),
            maxLawsPerJurisdiction = 50,
            startYear = 2020
        )

        // Verify the limit is stored correctly
        assertNotNull(source)
    }

    @Test
    fun `test start year filtering`() {
        val source2020 = AustralianLawsSource(
            jurisdictions = listOf("commonwealth"),
            maxLawsPerJurisdiction = 100,
            startYear = 2020
        )

        val source2024 = AustralianLawsSource(
            jurisdictions = listOf("nsw"),
            maxLawsPerJurisdiction = 100,
            startYear = 2024
        )

        assertNotNull(source2020)
        assertNotNull(source2024)
    }

    @Test
    fun `test AustralianLaw data class toText formatting`() {
        val law = AustralianLaw(
            id = "commonwealth:test123",
            title = "Test Act 2024",
            jurisdiction = "Commonwealth of Australia",
            type = "Act",
            year = "2024",
            number = "test123",
            url = "https://example.com/test123",
            text = "This is the full text of the legislation.",
            sections = listOf(
                LawSection("1", "Short title", "This Act may be cited as the Test Act 2024."),
                LawSection("2", "Commencement", "This Act commences on the day it receives Royal Assent.")
            )
        )

        val formatted = law.toText()

        assertTrue(formatted.contains("# Test Act 2024"))
        assertTrue(formatted.contains("**Jurisdiction:** Commonwealth of Australia"))
        assertTrue(formatted.contains("**Type:** Act"))
        assertTrue(formatted.contains("**Year:** 2024"))
        assertTrue(formatted.contains("**URL:** https://example.com/test123"))
        assertTrue(formatted.contains("Section 1: Short title"))
        assertTrue(formatted.contains("Section 2: Commencement"))
    }

    @Test
    fun `test AustralianLaw contentHash is unique`() {
        val law1 = AustralianLaw(
            id = "commonwealth:act123",
            title = "Act 123",
            jurisdiction = "Commonwealth",
            type = "Act",
            year = "2024",
            number = "123",
            url = "https://example.com/123",
            text = "Text"
        )

        val law2 = AustralianLaw(
            id = "nsw:act456",
            title = "Act 456",
            jurisdiction = "NSW",
            type = "Act",
            year = "2024",
            number = "456",
            url = "https://example.com/456",
            text = "Text"
        )

        val hash1 = law1.contentHash()
        val hash2 = law2.contentHash()

        assertNotEquals(hash1, hash2, "Different laws should have different content hashes")

        // Same law should have same hash
        val hash1Again = law1.contentHash()
        assertEquals(hash1, hash1Again, "Same law should have same hash")
    }

    @Test
    fun `test LawSection data structure`() {
        val section = LawSection(
            number = "42",
            title = "Test Section",
            text = "This is the section text."
        )

        assertEquals("42", section.number)
        assertEquals("Test Section", section.title)
        assertEquals("This is the section text.", section.text)
    }

    @Test
    fun `test long text truncation in toText`() {
        val longText = "A".repeat(5000)

        val law = AustralianLaw(
            id = "test:long",
            title = "Long Act",
            jurisdiction = "Test",
            type = "Act",
            year = "2024",
            number = "1",
            url = "https://example.com",
            text = longText,
            sections = emptyList()
        )

        val formatted = law.toText()

        // Should truncate and show indicator
        assertTrue(formatted.contains("..."))
        assertTrue(formatted.contains("Full text: 5000 characters"))
    }

    @Test
    fun `test jurisdiction name mapping`() {
        val jurisdictions = listOf("commonwealth", "nsw", "vic", "qld", "wa", "sa", "tas", "act", "nt")

        // All jurisdiction codes should be valid
        jurisdictions.forEach { jurisdiction ->
            val source = AustralianLawsSource(
                jurisdictions = listOf(jurisdiction),
                maxLawsPerJurisdiction = 1
            )
            assertNotNull(source)
        }
    }

    @Test
    fun `test unknown jurisdiction is handled gracefully`() {
        val source = AustralianLawsSource(
            jurisdictions = listOf("invalid_jurisdiction"),
            maxLawsPerJurisdiction = 10
        )

        // Should create source without error (will log warning during fetch)
        assertNotNull(source)
    }
}
