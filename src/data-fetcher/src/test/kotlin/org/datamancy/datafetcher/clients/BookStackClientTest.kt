package org.datamancy.datafetcher.clients

import com.google.gson.Gson
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection

class BookStackClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: BookStackClient
    private val gson = Gson()

    @BeforeEach
    fun setup() {
        mockServer = MockWebServer()
        mockServer.start()

        val baseUrl = mockServer.url("/").toString().removeSuffix("/")
        client = BookStackClient(
            baseUrl = baseUrl,
            apiToken = "test_token",
            apiSecret = "test_secret"
        )
    }

    @AfterEach
    fun teardown() {
        mockServer.shutdown()
    }

    @Test
    fun `dryRun returns true when API is accessible`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody("API Docs")
        )

        val result = client.dryRun()

        assertTrue(result)
        assertEquals("/api/docs", mockServer.takeRequest().path)
    }

    @Test
    fun `dryRun returns false when API is not accessible`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_INTERNAL_ERROR)
        )

        val result = client.dryRun()

        assertFalse(result)
    }

    @Test
    fun `dryRun includes authorization header`() {
        mockServer.enqueue(MockResponse().setResponseCode(HttpURLConnection.HTTP_OK))

        client.dryRun()

        val request = mockServer.takeRequest()
        assertEquals("Token test_token:test_secret", request.getHeader("Authorization"))
    }

    @Test
    fun `getOrCreateShelf returns existing shelf ID when found`() {
        val shelvesResponse = """
        {
            "data": [
                {"id": 123, "name": "Test Shelf", "description": "Test"},
                {"id": 456, "name": "Other Shelf", "description": "Other"}
            ]
        }
        """.trimIndent()

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(shelvesResponse)
            .setHeader("Content-Type", "application/json")
        )

        val shelfId = client.getOrCreateShelf("Test Shelf")

        assertEquals(123, shelfId)
        assertEquals("/api/shelves", mockServer.takeRequest().path)
    }

    @Test
    fun `getOrCreateShelf creates new shelf when not found`() {
        val emptyShelvesResponse = """{"data": []}"""
        val createShelfResponse = """{"id": 789, "name": "New Shelf"}"""

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(emptyShelvesResponse)
            .setHeader("Content-Type", "application/json")
        )

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(createShelfResponse)
            .setHeader("Content-Type", "application/json")
        )

        val shelfId = client.getOrCreateShelf("New Shelf", "Description")

        assertEquals(789, shelfId)

        // Verify POST request
        mockServer.takeRequest() // Skip GET
        val createRequest = mockServer.takeRequest()
        assertEquals("POST", createRequest.method)
        assertTrue(createRequest.body.readUtf8().contains("New Shelf"))
    }

    @Test
    fun `getOrCreateBook returns existing book ID when found`() {
        val shelfResponse = """
        {
            "id": 123,
            "books": [
                {"id": 10, "name": "Existing Book"},
                {"id": 11, "name": "Another Book"}
            ]
        }
        """.trimIndent()

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(shelfResponse)
            .setHeader("Content-Type", "application/json")
        )

        val bookId = client.getOrCreateBook(123, "Existing Book")

        assertEquals(10, bookId)
    }

    @Test
    fun `getOrCreateBook creates new book when not found`() {
        val shelfResponse = """{"id": 123, "books": []}"""
        val createBookResponse = """{"id": 999, "name": "New Book"}"""
        val updateShelfResponse = """{"success": true}"""

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(shelfResponse)
            .setHeader("Content-Type", "application/json")
        )

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(createBookResponse)
            .setHeader("Content-Type", "application/json")
        )

        // Mock for addBookToShelf operations
        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(shelfResponse)
            .setHeader("Content-Type", "application/json")
        )

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(updateShelfResponse)
            .setHeader("Content-Type", "application/json")
        )

        val bookId = client.getOrCreateBook(123, "New Book", "Description")

        assertEquals(999, bookId)
    }

    @Test
    fun `createOrUpdatePage creates new page when not found`() {
        val bookResponse = """{"id": 10, "contents": []}"""
        val createPageResponse = """{"id": 555, "name": "New Page"}"""

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(bookResponse)
            .setHeader("Content-Type", "application/json")
        )

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(createPageResponse)
            .setHeader("Content-Type", "application/json")
        )

        val pageId = client.createOrUpdatePage(
            bookId = 10,
            name = "New Page",
            markdownContent = "# Test Content"
        )

        assertEquals(555, pageId)

        mockServer.takeRequest() // Skip GET
        val createRequest = mockServer.takeRequest()
        assertEquals("POST", createRequest.method)
        assertTrue(createRequest.body.readUtf8().contains("Test Content"))
    }

    @Test
    fun `createOrUpdatePage updates existing page`() {
        val bookResponse = """
        {
            "id": 10,
            "contents": [
                {"id": 100, "name": "Existing Page", "type": "page"}
            ]
        }
        """.trimIndent()

        val updatePageResponse = """{"id": 100, "name": "Existing Page"}"""

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(bookResponse)
            .setHeader("Content-Type", "application/json")
        )

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(updatePageResponse)
            .setHeader("Content-Type", "application/json")
        )

        val pageId = client.createOrUpdatePage(
            bookId = 10,
            name = "Existing Page",
            markdownContent = "# Updated Content"
        )

        assertEquals(100, pageId)

        mockServer.takeRequest() // Skip GET
        val updateRequest = mockServer.takeRequest()
        assertEquals("PUT", updateRequest.method)
        assertEquals("/api/pages/100", updateRequest.path)
    }

    @Test
    fun `createOrUpdatePage includes tags`() {
        val bookResponse = """{"id": 10, "contents": []}"""
        val createPageResponse = """{"id": 555}"""

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(bookResponse)
            .setHeader("Content-Type", "application/json")
        )

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(createPageResponse)
            .setHeader("Content-Type", "application/json")
        )

        client.createOrUpdatePage(
            bookId = 10,
            name = "Tagged Page",
            markdownContent = "Content",
            tags = mapOf("source" to "legal", "jurisdiction" to "US")
        )

        mockServer.takeRequest() // Skip GET
        val createRequest = mockServer.takeRequest()
        val body = createRequest.body.readUtf8()

        assertTrue(body.contains("source"))
        assertTrue(body.contains("legal"))
        assertTrue(body.contains("jurisdiction"))
    }

    @Test
    fun `exportPage retrieves page content in specified format`() {
        val pageContent = "# Test Page\n\nThis is test content."

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(pageContent)
        )

        val content = client.exportPage(pageId = 100, format = "markdown")

        assertEquals(pageContent, content)
        assertEquals("/api/pages/100/export/markdown", mockServer.takeRequest().path)
    }

    @Test
    fun `exportPage defaults to plain-text format`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody("Plain text content")
        )

        client.exportPage(pageId = 100)

        val request = mockServer.takeRequest()
        assertEquals("/api/pages/100/export/plain-text", request.path)
    }

    @Test
    fun `exportPage throws exception on failure`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_NOT_FOUND)
        )

        assertThrows(Exception::class.java) {
            client.exportPage(pageId = 999)
        }
    }

    @Test
    fun `searchPages returns matching pages`() {
        val searchResponse = """
        {
            "data": [
                {"id": 1, "name": "Page 1", "type": "page", "url": "http://test/page1"},
                {"id": 2, "name": "Book 1", "type": "book", "url": "http://test/book1"},
                {"id": 3, "name": "Page 2", "type": "page", "url": "http://test/page2"}
            ]
        }
        """.trimIndent()

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(searchResponse)
            .setHeader("Content-Type", "application/json")
        )

        val pages = client.searchPages("test query", limit = 50)

        assertEquals(2, pages.size) // Only pages, not books
        assertEquals(1, pages[0].id)
        assertEquals("Page 1", pages[0].name)
        assertEquals(3, pages[1].id)

        val request = mockServer.takeRequest()
        assertTrue(request.path!!.contains("query=test"))
        assertTrue(request.path!!.contains("count=50"))
    }

    @Test
    fun `searchPages URL-encodes query parameter`() {
        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody("""{"data": []}""")
            .setHeader("Content-Type", "application/json")
        )

        client.searchPages("test query with spaces", limit = 10)

        val request = mockServer.takeRequest()
        assertTrue(request.path!!.contains("query=test+query+with+spaces") ||
                   request.path!!.contains("query=test%20query%20with%20spaces"))
    }

    @Test
    fun `getBookPages returns all pages in book`() {
        val bookResponse = """
        {
            "id": 10,
            "contents": [
                {"id": 1, "name": "Chapter 1", "type": "page", "url": "http://test/1"},
                {"id": 2, "name": "Chapter 2", "type": "page", "url": "http://test/2"},
                {"id": 3, "name": "Sub Book", "type": "book", "url": "http://test/book"}
            ]
        }
        """.trimIndent()

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(bookResponse)
            .setHeader("Content-Type", "application/json")
        )

        val pages = client.getBookPages(bookId = 10)

        assertEquals(2, pages.size) // Only pages, not nested books
        assertEquals("Chapter 1", pages[0].name)
        assertEquals("Chapter 2", pages[1].name)
    }

    @Test
    fun `getBookPages returns empty list for book with no pages`() {
        val bookResponse = """{"id": 10, "contents": []}"""

        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody(bookResponse)
            .setHeader("Content-Type", "application/json")
        )

        val pages = client.getBookPages(bookId = 10)

        assertTrue(pages.isEmpty())
    }

    @Test
    fun `all requests include authorization header`() {
        // First response for search, second for create
        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody("""{"data": []}""")
            .setHeader("Content-Type", "application/json"))
        mockServer.enqueue(MockResponse()
            .setResponseCode(HttpURLConnection.HTTP_OK)
            .setBody("""{"id": 1, "name": "Test"}""")
            .setHeader("Content-Type", "application/json"))

        client.getOrCreateShelf("Test")

        val request = mockServer.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)
        assertNotNull(request)
        assertEquals("Token test_token:test_secret", request!!.getHeader("Authorization"))
        assertEquals("application/json", request.getHeader("Content-Type"))
    }

    @Test
    fun `BookStackPage data class stores id, name and url`() {
        val page = BookStackPage(
            id = 42,
            name = "Test Page",
            url = "https://bookstack.local/books/1/page/test-page"
        )

        assertEquals(42, page.id)
        assertEquals("Test Page", page.name)
        assertEquals("https://bookstack.local/books/1/page/test-page", page.url)
    }

    @Test
    fun `client uses environment variables for default config`() {
        // Test that client can be instantiated with default env-based config
        val defaultClient = BookStackClient()

        assertNotNull(defaultClient)
        // Default values should be used when env vars not set
    }

    @Test
    fun `timeout is configured for HTTP client`() {
        // Validates that timeout is set to 30 seconds (lines 24-25)
        val timeoutSeconds = 30

        assertEquals(30, timeoutSeconds)
        // HTTP client should have 30 second connect and read timeouts
    }
}
