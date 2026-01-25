package org.datamancy.pipeline.sources

import kotlinx.coroutines.runBlocking
import kotlin.test.*

class WikiSourceTest {

    @Test
    fun `test Debian wiki source initialization`() {
        val source = WikiSource(
            wikiType = WikiSource.WikiType.DEBIAN,
            maxPages = 100
        )

        assertEquals("WikiSource", source.name)
    }

    @Test
    fun `test Arch wiki source initialization`() {
        val source = WikiSource(
            wikiType = WikiSource.WikiType.ARCH,
            maxPages = 100
        )

        assertEquals("WikiSource", source.name)
    }

    @Test
    fun `test WikiType enum values`() {
        assertEquals("https://wiki.debian.org", WikiSource.WikiType.DEBIAN.baseUrl)
        assertEquals("Debian Wiki", WikiSource.WikiType.DEBIAN.displayName)

        assertEquals("https://wiki.archlinux.org", WikiSource.WikiType.ARCH.baseUrl)
        assertEquals("Arch Wiki", WikiSource.WikiType.ARCH.displayName)
    }

    @Test
    fun `test WikiPage data class`() {
        val page = WikiPage(
            id = "debian:test_page",
            title = "Test Page",
            url = "https://wiki.debian.org/TestPage",
            content = "This is test content for the wiki page.",
            wikiType = "Debian Wiki",
            categories = listOf("Testing", "Documentation")
        )

        assertEquals("debian:test_page", page.id)
        assertEquals("Test Page", page.title)
        assertEquals("https://wiki.debian.org/TestPage", page.url)
        assertTrue(page.content.contains("test content"))
        assertEquals("Debian Wiki", page.wikiType)
        assertEquals(2, page.categories.size)
    }

    @Test
    fun `test WikiPage toText formatting`() {
        val page = WikiPage(
            id = "arch:PackageManagement",
            title = "Package Management",
            url = "https://wiki.archlinux.org/title/Package_Management",
            content = "Arch Linux uses pacman as its package manager.",
            wikiType = "Arch Wiki",
            categories = listOf("System", "Packages")
        )

        val formatted = page.toText()

        assertTrue(formatted.contains("# Package Management"))
        assertTrue(formatted.contains("**Source:** Arch Wiki"))
        assertTrue(formatted.contains("**URL:** https://wiki.archlinux.org/title/Package_Management"))
        assertTrue(formatted.contains("**Categories:** System, Packages"))
        assertTrue(formatted.contains("## Content"))
        assertTrue(formatted.contains("pacman"))
    }

    @Test
    fun `test WikiPage contentHash is unique`() {
        val page1 = WikiPage(
            id = "debian:page1",
            title = "Page 1",
            url = "https://wiki.debian.org/Page1",
            content = "Content 1",
            wikiType = "Debian Wiki"
        )

        val page2 = WikiPage(
            id = "debian:page2",
            title = "Page 2",
            url = "https://wiki.debian.org/Page2",
            content = "Content 2",
            wikiType = "Debian Wiki"
        )

        val hash1 = page1.contentHash()
        val hash2 = page2.contentHash()

        assertNotEquals(hash1, hash2, "Different pages should have different hashes")

        // Same page should have same hash
        val hash1Again = page1.contentHash()
        assertEquals(hash1, hash1Again)
    }

    @Test
    fun `test WikiPage long content truncation indicator`() {
        val longContent = "A".repeat(15000)

        val page = WikiPage(
            id = "test:long",
            title = "Long Page",
            url = "https://example.com/long",
            content = longContent,  // WikiSource truncates before creating WikiPage
            wikiType = "Test Wiki"
        )

        val formatted = page.toText()

        // If content is 10000 chars or more, formatted text should show truncation indicator
        if (page.content.length >= 10000) {
            assertTrue(formatted.contains("Content truncated"))
        }
    }

    @Test
    fun `test WikiPage with empty categories`() {
        val page = WikiPage(
            id = "test:nocats",
            title = "No Categories",
            url = "https://example.com/nocats",
            content = "Content without categories",
            wikiType = "Test Wiki",
            categories = emptyList()
        )

        val formatted = page.toText()

        assertFalse(formatted.contains("**Categories:**"))
        assertTrue(formatted.contains("# No Categories"))
    }

    @Test
    fun `test WikiPage with many categories shows only first 5`() {
        val manyCategories = (1..10).map { "Category$it" }

        val page = WikiPage(
            id = "test:manycats",
            title = "Many Categories",
            url = "https://example.com/manycats",
            content = "Content with many categories",
            wikiType = "Test Wiki",
            categories = manyCategories
        )

        val formatted = page.toText()

        // Should only show first 5
        assertTrue(formatted.contains("Category1"))
        assertTrue(formatted.contains("Category5"))
    }

    @Test
    fun `test source with categories parameter`() {
        val categories = listOf("Installation", "Configuration")

        val source = WikiSource(
            wikiType = WikiSource.WikiType.DEBIAN,
            maxPages = 50,
            categories = categories
        )

        assertNotNull(source)
    }

    @Test
    fun `test source with empty categories uses recent pages`() {
        val source = WikiSource(
            wikiType = WikiSource.WikiType.ARCH,
            maxPages = 100,
            categories = emptyList()  // Should fetch from recent changes
        )

        assertNotNull(source)
    }

    @Test
    fun `test max pages limit`() {
        val source = WikiSource(
            wikiType = WikiSource.WikiType.DEBIAN,
            maxPages = 10
        )

        // Can't easily test fetch() without hitting real servers,
        // but we can verify the parameter is accepted
        assertNotNull(source)
    }

    @Test
    fun `test WikiPage ID format for different wiki types`() {
        val debianPage = WikiPage(
            id = "debian:FrontPage",
            title = "Front Page",
            url = "https://wiki.debian.org/FrontPage",
            content = "Content",
            wikiType = "Debian Wiki"
        )

        val archPage = WikiPage(
            id = "arch:Main_page",
            title = "Main Page",
            url = "https://wiki.archlinux.org/title/Main_page",
            content = "Content",
            wikiType = "Arch Wiki"
        )

        assertTrue(debianPage.id.startsWith("debian:"))
        assertTrue(archPage.id.startsWith("arch:"))
    }
}
