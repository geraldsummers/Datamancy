package org.datamancy.searchservice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Unit tests for SearchGateway data classes and logic
 * No Docker needed - pure unit tests!
 */
class SearchGatewayTest {

    @Test
    fun `SearchResult inferContentType identifies bookstack`() {
        val type = SearchResult.inferContentType("bookstack_docs", "http://bookstack.com", emptyMap())
        assertEquals("bookstack", type)
    }

    @Test
    fun `SearchResult inferContentType identifies articles`() {
        val type = SearchResult.inferContentType("rss_feeds", "http://news.com", emptyMap())
        assertEquals("article", type)
    }

    @Test
    fun `SearchResult inferContentType identifies market data`() {
        val type = SearchResult.inferContentType("market_data", "http://example.com", emptyMap())
        assertEquals("market", type)
    }

    @Test
    fun `SearchResult inferContentType identifies CVE`() {
        val type = SearchResult.inferContentType("cve_database", "http://example.com", emptyMap())
        assertEquals("cve", type)
    }

    @Test
    fun `SearchResult inferContentType identifies wikipedia`() {
        val type = SearchResult.inferContentType("wiki", "http://example.com", emptyMap())
        assertEquals("wikipedia", type)
    }

    @Test
    fun `SearchResult inferContentType defaults to generic`() {
        val type = SearchResult.inferContentType("unknown_source", "http://example.com", emptyMap())
        assertEquals("generic", type)
    }

    @Test
    fun `SearchResult inferCapabilities for bookstack`() {
        val caps = SearchResult.inferCapabilities("bookstack_docs", "", emptyMap())

        assertTrue(caps.humanFriendly)
        assertTrue(caps.agentFriendly)
        assertTrue(caps.hasRichContent)
        assertTrue(caps.isInteractive)
    }

    @Test
    fun `SearchResult inferCapabilities for market data`() {
        val caps = SearchResult.inferCapabilities("market_data", "", emptyMap())

        assertTrue(caps.hasTimeSeries)
        assertTrue(caps.isStructured)
        assertFalse(caps.hasRichContent)
    }

    @Test
    fun `SearchResult inferCapabilities for CVE`() {
        val caps = SearchResult.inferCapabilities("cve", "", emptyMap())

        assertTrue(caps.hasRichContent)
        assertTrue(caps.isStructured)
        assertTrue(caps.isInteractive)
    }

    @Test
    fun `SearchResult inferCapabilities for weather`() {
        val caps = SearchResult.inferCapabilities("weather_api", "", emptyMap())

        assertTrue(caps.hasTimeSeries)
        assertTrue(caps.isStructured)
    }

    @Test
    fun `SearchResult inferCapabilities defaults`() {
        val caps = SearchResult.inferCapabilities("unknown", "", emptyMap())

        assertTrue(caps.humanFriendly)
        assertTrue(caps.agentFriendly)
        assertFalse(caps.hasRichContent)
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
            contentType = "generic",
            capabilities = ContentCapabilities(
                humanFriendly = true,
                agentFriendly = true
            )
        )

        assertEquals("test_source", result.source)
        assertEquals("Test Title", result.title)
        assertEquals("https://example.com", result.url)
        assertEquals(0.95, result.score)
        assertEquals("value", result.metadata["key"])
    }

    @Test
    fun `ContentCapabilities data class has sensible defaults`() {
        val caps = ContentCapabilities(
            humanFriendly = true,
            agentFriendly = true
        )

        assertTrue(caps.humanFriendly)
        assertTrue(caps.agentFriendly)
        assertFalse(caps.hasRichContent)
        assertFalse(caps.isInteractive)
        assertFalse(caps.hasTimeSeries)
        assertFalse(caps.isStructured)
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
            contentType = "generic",
            capabilities = ContentCapabilities(
                humanFriendly = true,
                agentFriendly = true
            )
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
            contentType = "generic",
            capabilities = ContentCapabilities(
                humanFriendly = true,
                agentFriendly = true
            )
        )

        assertTrue(result.title.contains("<html>"))
        assertTrue(result.snippet.contains("\"quotes\""))
        assertTrue(result.metadata["key"]!!.contains("'quotes'"))
    }
}
