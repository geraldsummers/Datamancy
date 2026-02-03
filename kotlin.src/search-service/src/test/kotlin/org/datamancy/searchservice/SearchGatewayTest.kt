package org.datamancy.searchservice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for SearchGateway data classes and logic
 * No Docker needed - pure unit tests!
 */
class SearchGatewayTest {

    @Test
    fun `SearchResult inferContentType identifies articles`() {
        val type = SearchResult.inferContentType("rss_feeds", "http://news.com", emptyMap())
        assertEquals("article", type)
    }

    @Test
    fun `SearchResult inferContentType identifies wikipedia`() {
        val type = SearchResult.inferContentType("wiki", "http://example.com", emptyMap())
        assertEquals("documentation", type)
    }

    @Test
    fun `SearchResult inferContentType identifies CVE`() {
        val type = SearchResult.inferContentType("cve_database", "http://example.com", emptyMap())
        assertEquals("vulnerability", type)
    }

    @Test
    fun `SearchResult inferContentType identifies legal`() {
        val type = SearchResult.inferContentType("legal_docs", "http://example.com", emptyMap())
        assertEquals("legal", type)
    }

    @Test
    fun `SearchResult inferContentType identifies code`() {
        val type = SearchResult.inferContentType("code_repo", "http://github.com", emptyMap())
        assertEquals("code", type)
    }

    @Test
    fun `SearchResult inferContentType defaults to document`() {
        val type = SearchResult.inferContentType("unknown_source", "http://example.com", emptyMap())
        assertEquals("document", type)
    }

    @Test
    fun `SearchResult inferCapabilities for URL with hasRichContent`() {
        val caps = SearchResult.inferCapabilities("test", "http://example.com", emptyMap())

        assertTrue(caps["hasRichContent"] == true)
    }

    @Test
    fun `SearchResult inferCapabilities for URL without URL`() {
        val caps = SearchResult.inferCapabilities("test", "", emptyMap())

        assertTrue(caps["hasRichContent"] == false)
    }

    @Test
    fun `SearchResult inferCapabilities includes humanFriendly for articles`() {
        val caps = SearchResult.inferCapabilities("rss_feeds", "", emptyMap())

        assertTrue(caps["humanFriendly"] == true)
    }

    @Test
    fun `SearchResult inferCapabilities includes agentFriendly for code`() {
        val caps = SearchResult.inferCapabilities("test", "https://github.com/test", emptyMap())

        assertTrue(caps["agentFriendly"] == true)
    }

    @Test
    fun `SearchResult data class holds correct values`() {
        val result = SearchResult(
            source = "test_source",
            title = "Test Title",
            url = "https://example.com",
            snippet = "Test snippet",
            score = 0.95,
            metadata = mapOf("key" to "value"),
            contentType = "document",
            capabilities = mapOf("linkable" to true)
        )

        assertEquals("test_source", result.source)
        assertEquals("Test Title", result.title)
        assertEquals("https://example.com", result.url)
        assertEquals(0.95, result.score)
        assertEquals("value", result.metadata["key"])
        assertEquals("document", result.contentType)
    }

    @Test
    fun `SearchResult handles empty metadata`() {
        val result = SearchResult(
            source = "test",
            title = "Title",
            url = "https://example.com",
            snippet = "Snippet",
            score = 1.0,
            metadata = emptyMap(),
            contentType = "document",
            capabilities = mapOf("linkable" to true)
        )

        assertTrue(result.metadata.isEmpty())
    }

    @Test
    fun `SearchResult handles special characters in fields`() {
        val result = SearchResult(
            source = "test",
            title = "Title with <html> & 'quotes'",
            url = "https://example.com?param=value&other=test",
            snippet = "Snippet with \"quotes\" and <tags>",
            score = 0.5,
            metadata = mapOf("key" to "value with 'quotes'"),
            contentType = "document",
            capabilities = mapOf("linkable" to true)
        )

        assertTrue(result.title.contains("<html>"))
        assertTrue(result.snippet.contains("\"quotes\""))
        assertTrue(result.metadata["key"]!!.contains("'quotes'"))
    }
}
