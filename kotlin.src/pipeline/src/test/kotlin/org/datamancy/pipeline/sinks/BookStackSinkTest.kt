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
        // Mock book search (empty results)
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": []}"""))

        // Mock book creation
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 1, "name": "Test Book"}"""))

        // Mock book detail for chapter search
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 1, "contents": []}"""))

        // Mock chapter creation
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 1, "name": "Test Chapter"}"""))

        // Mock page search
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": []}"""))

        // Mock page creation
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 1, "name": "Test Page"}"""))

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

        // Verify book creation request
        mockServer.takeRequest() // book search
        val bookCreateRequest = mockServer.takeRequest()
        assertEquals("/api/books", bookCreateRequest.path)
        assertTrue(bookCreateRequest.body.readUtf8().contains("Test Book"))
    }

    @Test
    fun `test write reuses existing book`() = runBlocking {
        // Mock book search (book exists)
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": [{"id": 5, "name": "Existing Book"}]}"""))

        // Mock book detail for chapter search
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 5, "contents": []}"""))

        // Mock chapter creation
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 2, "name": "Test Chapter"}"""))

        // Mock page search
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": []}"""))

        // Mock page creation
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 10, "name": "Test Page"}"""))

        val doc = BookStackDocument(
            bookName = "Existing Book",
            chapterName = "Test Chapter",
            pageTitle = "Test Page",
            pageContent = "<h1>Test</h1>"
        )

        sink.write(doc)

        // Should NOT create a new book, only search
        val bookSearchRequest = mockServer.takeRequest()
        assertEquals("/api/books", bookSearchRequest.path)
        assertEquals("GET", bookSearchRequest.method)
    }

    @Test
    fun `test write creates page without chapter`() = runBlocking {
        // Mock book search
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": [{"id": 3, "name": "Test Book"}]}"""))

        // Mock page search
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": []}"""))

        // Mock page creation
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 20, "name": "Test Page"}"""))

        val doc = BookStackDocument(
            bookName = "Test Book",
            chapterName = null,  // No chapter
            pageTitle = "Test Page",
            pageContent = "<p>Direct page in book</p>"
        )

        sink.write(doc)

        // Verify page creation includes book_id but no chapter_id
        mockServer.takeRequest() // book search
        mockServer.takeRequest() // page search
        val pageCreateRequest = mockServer.takeRequest()
        val body = pageCreateRequest.body.readUtf8()
        assertTrue(body.contains("book_id"))
        assertFalse(body.contains("chapter_id"))
    }

    @Test
    fun `test write updates existing page`() = runBlocking {
        // Mock book search
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": [{"id": 4, "name": "Test Book"}]}"""))

        // Mock page search (page exists)
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"data": [{"id": 50, "name": "Test Page", "book_id": 4, "chapter_id": null}]}"""))

        // Mock page update
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("""{"id": 50, "name": "Test Page"}"""))

        val doc = BookStackDocument(
            bookName = "Test Book",
            pageTitle = "Test Page",
            pageContent = "<p>Updated content</p>"
        )

        sink.write(doc)

        // Verify PUT request for update
        mockServer.takeRequest() // book search
        mockServer.takeRequest() // page search
        val pageUpdateRequest = mockServer.takeRequest()
        assertEquals("PUT", pageUpdateRequest.method)
        assertTrue(pageUpdateRequest.path?.contains("/api/pages/50") ?: false)
    }

    @Test
    fun `test write includes tags in page creation`() = runBlocking {
        // Mock responses
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": [{"id": 1, "name": "Book"}]}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 1}"""))

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

        mockServer.takeRequest() // book search
        mockServer.takeRequest() // page search
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

        // Mock responses for both documents
        repeat(2) {
            mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
            mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": $it}"""))
            mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
            mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": $it}"""))
        }

        sink.writeBatch(docs)

        // Should have made requests for both documents
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
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 1}"""))

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

        mockServer.takeRequest() // book
        mockServer.takeRequest() // page search
        val request = mockServer.takeRequest() // page create
        val body = request.body.readUtf8()

        assertTrue(body.contains("html"))
        assertTrue(body.contains("Test Article"))
    }

    @Test
    fun `test caching reduces API calls for same book`() = runBlocking {
        // First write - creates book
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 99, "name": "Same Book"}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 1}"""))

        // Second write - should use cached book ID
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"data": []}"""))
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"id": 2}"""))

        val doc1 = BookStackDocument(bookName = "Same Book", pageTitle = "Page 1", pageContent = "<p>1</p>")
        val doc2 = BookStackDocument(bookName = "Same Book", pageTitle = "Page 2", pageContent = "<p>2</p>")

        sink.write(doc1)
        val requestsAfterFirst = mockServer.requestCount

        sink.write(doc2)
        val requestsAfterSecond = mockServer.requestCount

        // Second write should make fewer requests (no book creation, uses cache)
        assertTrue(requestsAfterSecond - requestsAfterFirst < requestsAfterFirst,
            "Second write should use cached book and make fewer requests")
    }
}
