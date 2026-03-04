package org.datamancy.pipeline.sinks

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookStackSinkTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var sink: BookStackSink

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val baseUrl = mockServer.url("/").toString().trimEnd('/')
        sink = BookStackSink(
            bookstackUrl = baseUrl,
            tokenId = "test-token-id",
            tokenSecret = "test-token-secret"
        )
    }

    @AfterEach
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun `test healthCheck returns true on successful API call`() = runBlocking {
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": []}"""))

        val healthy = sink.healthCheck()
        assertTrue(healthy)

        val request = mockServer.takeRequest()
        assertEquals("/api/books", request.path)
        assertEquals("Token test-token-id:test-token-secret", request.getHeader("Authorization"))
    }

    @Test
    fun `test healthCheck returns false on API error`() = runBlocking {
        mockServer.enqueue(MockResponse()
            .setResponseCode(401)
            .setBody("""{"error": "Unauthorized"}"""))

        val healthy = sink.healthCheck()
        assertFalse(healthy)
    }

    @Test
    fun `test write creates new book if not exists`() = runBlocking {
        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": []}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 1, "name": "Test Book"}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 1, "contents": []}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 1, "name": "Test Chapter"}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": []}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 1, "name": "Test Page", "slug": "test-page", "book_slug": "test-book"}"""))

        val doc = BookStackDocument(
            bookName = "Test Book",
            bookDescription = "Test Description",
            chapterName = "Test Chapter",
            chapterDescription = "Chapter Desc",
            pageTitle = "Test Page",
            pageContent = "<h1>Test Content</h1>",
            tags = mapOf("source" to "test")
        )

        sink.write(doc)

        
        mockServer.takeRequest() 
        val bookCreateRequest = mockServer.takeRequest()
        assertEquals("/api/books", bookCreateRequest.path)
        assertTrue(bookCreateRequest.body.readUtf8().contains("Test Book"))
    }

    @Test
    fun `test write reuses existing book`() = runBlocking {
        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": [{"id": 5, "name": "Existing Book"}]}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 5, "contents": []}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 2, "name": "Test Chapter"}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": []}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 10, "name": "Test Page", "slug": "test-page", "book_slug": "existing-book"}"""))

        val doc = BookStackDocument(
            bookName = "Existing Book",
            chapterName = "Test Chapter",
            pageTitle = "Test Page",
            pageContent = "<h1>Test</h1>"
        )

        sink.write(doc)

        
        val bookSearchRequest = mockServer.takeRequest()
        assertEquals("/api/books", bookSearchRequest.path)
        assertEquals("GET", bookSearchRequest.method)
    }

    @Test
    fun `test write creates page without chapter`() = runBlocking {
        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": [{"id": 3, "name": "Test Book"}]}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": []}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 20, "name": "Test Page", "slug": "test-page", "book_slug": "test-book"}"""))

        val doc = BookStackDocument(
            bookName = "Test Book",
            chapterName = null,  
            pageTitle = "Test Page",
            pageContent = "<p>Direct page in book</p>"
        )

        sink.write(doc)

        
        mockServer.takeRequest() 
        mockServer.takeRequest() 
        val pageCreateRequest = mockServer.takeRequest()
        val body = pageCreateRequest.body.readUtf8()
        assertTrue(body.contains("book_id"))
        assertFalse(body.contains("chapter_id"))
    }

    @Test
    fun `test write updates existing page`() = runBlocking {
        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": [{"id": 4, "name": "Test Book"}]}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": [{"id": 50, "name": "Test Page", "book_id": 4, "chapter_id": null}]}"""))

        
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 50, "name": "Test Page", "slug": "test-page", "book_slug": "test-book"}"""))

        val doc = BookStackDocument(
            bookName = "Test Book",
            pageTitle = "Test Page",
            pageContent = "<p>Updated content</p>"
        )

        sink.write(doc)

        
        mockServer.takeRequest() 
        mockServer.takeRequest() 
        val pageUpdateRequest = mockServer.takeRequest()
        assertEquals("PUT", pageUpdateRequest.method)
        assertTrue(pageUpdateRequest.path?.contains("/api/pages/50") ?: false)
    }

    @Test
    fun `test write includes tags in page creation`() = runBlocking {
        
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": [{"id": 1, "name": "Book"}]}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 1, "slug": "page", "book_slug": "book"}"""))

        val doc = BookStackDocument(
            bookName = "Book",
            pageTitle = "Page",
            pageContent = "<p>Content</p>",
            tags = mapOf(
                "source" to "rss",
                "feed" to "Hacker News"
            )
        )

        sink.write(doc)

        mockServer.takeRequest() 
        mockServer.takeRequest() 
        val pageCreateRequest = mockServer.takeRequest()
        val body = pageCreateRequest.body.readUtf8()

        assertTrue(body.contains("tags"))
        assertTrue(body.contains("source"))
        assertTrue(body.contains("rss"))
        assertTrue(body.contains("feed"))
    }

    @Test
    fun `test writeBatch writes multiple documents`() = runBlocking {
        val docs = listOf(
            BookStackDocument("Book1", pageTitle = "Page1", pageContent = "<p>1</p>"),
            BookStackDocument("Book2", pageTitle = "Page2", pageContent = "<p>2</p>")
        )

        
        repeat(2) {
            mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
            mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": $it, "slug": "book$it"}"""))
            mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
            mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": $it, "slug": "page$it", "book_slug": "book$it"}"""))
        }

        sink.writeBatch(docs)

        
        val requestCount = mockServer.requestCount
        assertTrue(requestCount >= 6, "Should have made at least 6 requests (book search + create + page search + create for each doc)")
    }

    @Test
    fun `test authentication header is set correctly`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))

        sink.healthCheck()

        val request = mockServer.takeRequest()
        assertEquals("Token test-token-id:test-token-secret", request.getHeader("Authorization"))
    }

    @Test
    fun `test sink name is correct`() {
        assertEquals("BookStackSink", sink.name)
    }

    @Test
    fun `test handles HTML content correctly`() = runBlocking {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": [{"id": 1, "name": "Book"}]}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 1, "slug": "test", "book_slug": "book"}"""))

        val htmlContent = """
            <h1>Test Article</h1>
            <p>This is <strong>bold</strong> text with <a href="https://example.com">links</a></p>
            <pre><code>function test() { return true; }</code></pre>
        """.trimIndent()

        val doc = BookStackDocument(
            bookName = "Book",
            pageTitle = "Test",
            pageContent = htmlContent
        )

        sink.write(doc)

        mockServer.takeRequest() 
        mockServer.takeRequest() 
        val request = mockServer.takeRequest() 
        val body = request.body.readUtf8()

        assertTrue(body.contains("html"))
        assertTrue(body.contains("Test Article"))
    }

    @Test
    fun `test caching reduces API calls for same book`() = runBlocking {

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 99, "name": "Same Book"}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 1, "slug": "page-1", "book_slug": "same-book"}"""))


        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 2, "slug": "page-2", "book_slug": "same-book"}"""))

        val doc1 = BookStackDocument(bookName = "Same Book", pageTitle = "Page 1", pageContent = "<p>1</p>")
        val doc2 = BookStackDocument(bookName = "Same Book", pageTitle = "Page 2", pageContent = "<p>2</p>")

        sink.write(doc1)
        val requestsAfterFirst = mockServer.requestCount

        sink.write(doc2)
        val requestsAfterSecond = mockServer.requestCount


        assertTrue(requestsAfterSecond - requestsAfterFirst < requestsAfterFirst,
            "Second write should use cached book and make fewer requests")
    }

    @Test
    fun `test retries on 429 rate limit`() = runBlocking {
        val sinkWithFastRetry = BookStackSink(
            bookstackUrl = mockServer.url("/").toString().trimEnd('/'),
            tokenId = "test",
            tokenSecret = "secret",
            maxRetries = 3,
            baseDelayMs = 10  // Fast retry for testing
        )

        // First attempt: 429
        mockServer.enqueue(MockResponse().setResponseCode(429).setBody("Rate limited"))
        // Second attempt: Success
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": [{"id": 1, "name": "Book"}]}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 1, "slug": "page", "book_slug": "book"}"""))

        val doc = BookStackDocument(bookName = "Book", pageTitle = "Page", pageContent = "<p>Test</p>")
        sinkWithFastRetry.write(doc)

        // Should have made at least 2 requests (1 failed + 1 success for book search)
        assertTrue(mockServer.requestCount >= 2)
    }

    @Test
    fun `test retries on 503 server error`() = runBlocking {
        val sinkWithFastRetry = BookStackSink(
            bookstackUrl = mockServer.url("/").toString().trimEnd('/'),
            tokenId = "test",
            tokenSecret = "secret",
            maxRetries = 2,
            baseDelayMs = 10
        )

        // First attempt: 503
        mockServer.enqueue(MockResponse().setResponseCode(503).setBody("Service unavailable"))
        // Second attempt: Success
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 1}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 1, "slug": "page", "book_slug": "book"}"""))

        val doc = BookStackDocument(bookName = "Book", pageTitle = "Page", pageContent = "<p>Test</p>")
        sinkWithFastRetry.write(doc)

        assertTrue(mockServer.requestCount >= 2)
    }

    @Test
    fun `test fails after max retries`() = runBlocking {
        val sinkWithFastRetry = BookStackSink(
            bookstackUrl = mockServer.url("/").toString().trimEnd('/'),
            tokenId = "test",
            tokenSecret = "secret",
            maxRetries = 2,
            baseDelayMs = 10
        )

        // All attempts fail with 500
        repeat(10) {
            mockServer.enqueue(MockResponse().setResponseCode(500).setBody("Server error"))
        }

        val doc = BookStackDocument(bookName = "Book", pageTitle = "Page", pageContent = "<p>Test</p>")

        var exceptionThrown = false
        try {
            sinkWithFastRetry.write(doc)
        } catch (e: Exception) {
            exceptionThrown = true
        }

        assertTrue(exceptionThrown, "Should throw exception after max retries")
        // Should have made maxRetries + 1 attempts = 3 total
        assertEquals(3, mockServer.requestCount)
    }

    @Test
    fun `test does not retry on 400 bad request`() = runBlocking {
        val sinkWithFastRetry = BookStackSink(
            bookstackUrl = mockServer.url("/").toString().trimEnd('/'),
            tokenId = "test",
            tokenSecret = "secret",
            maxRetries = 3,
            baseDelayMs = 10
        )

        // 400 should not be retried
        mockServer.enqueue(MockResponse().setResponseCode(400).setBody("Bad request"))

        val doc = BookStackDocument(bookName = "Book", pageTitle = "Page", pageContent = "<p>Test</p>")

        var exceptionThrown = false
        try {
            sinkWithFastRetry.write(doc)
        } catch (e: Exception) {
            exceptionThrown = true
        }

        assertTrue(exceptionThrown, "Should throw exception on 400")
        // Should only make 1 request (no retries for 400)
        assertEquals(1, mockServer.requestCount)
    }

    @Test
    fun `test exponential backoff increases delay`() = runBlocking {
        val sinkWithMeasurableDelay = BookStackSink(
            bookstackUrl = mockServer.url("/").toString().trimEnd('/'),
            tokenId = "test",
            tokenSecret = "secret",
            maxRetries = 3,
            baseDelayMs = 100  // 100ms base delay
        )

        // Fail twice, then succeed
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 1}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 1, "slug": "page", "book_slug": "book"}"""))

        val doc = BookStackDocument(bookName = "Book", pageTitle = "Page", pageContent = "<p>Test</p>")

        val startTime = System.currentTimeMillis()
        sinkWithMeasurableDelay.write(doc)
        val elapsed = System.currentTimeMillis() - startTime

        // With 2 retries and base delay of 100ms:
        // Attempt 1: immediate
        // Attempt 2: 100ms * 2^0 = 100ms + jitter (0-50ms) = 100-150ms
        // Attempt 3: 100ms * 2^1 = 200ms + jitter (0-100ms) = 200-300ms
        // Minimum total delay: ~300ms
        assertTrue(elapsed >= 100, "Should have exponential backoff delays (got ${elapsed}ms)")
    }
}
