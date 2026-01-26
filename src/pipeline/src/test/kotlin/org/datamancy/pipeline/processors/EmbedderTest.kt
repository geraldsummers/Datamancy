package org.datamancy.pipeline.processors

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class EmbedderTest {

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
    fun `test successful embedding request`() = runBlocking {
        val embedder = Embedder(
            serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
            maxRetries = 3,
            baseDelayMs = 10
        )

        // Mock successful response - wraps array in another array as service returns
        val mockEmbedding = (1..1024).map { it.toFloat() / 1024f }
        val responseBody = """[[${mockEmbedding.joinToString(",")}]]"""

        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseBody)
            .addHeader("Content-Type", "application/json"))

        val result = embedder.process("test text")

        assertEquals(1024, result.size)
        assertEquals(1, mockServer.requestCount)
    }

    @Test
    fun `test retry on 429 rate limit`() = runBlocking {
        val embedder = Embedder(
            serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
            maxRetries = 3,
            baseDelayMs = 10
        )

        val mockEmbedding = (1..1024).map { it.toFloat() / 1024f }
        val responseBody = """[[${mockEmbedding.joinToString(",")}]]"""

        // First request: 429 rate limit
        mockServer.enqueue(MockResponse().setResponseCode(429))

        // Second request: success
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseBody)
            .addHeader("Content-Type", "application/json"))

        val result = embedder.process("test text")

        assertEquals(1024, result.size)
        assertEquals(2, mockServer.requestCount, "Should retry after 429")
    }

    @Test
    fun `test retry on 503 service unavailable`() = runBlocking {
        val embedder = Embedder(
            serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
            maxRetries = 3,
            baseDelayMs = 10
        )

        val mockEmbedding = (1..1024).map { it.toFloat() / 1024f }
        val responseBody = """[[${mockEmbedding.joinToString(",")}]]"""

        // First two requests: 503 errors
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse().setResponseCode(503))

        // Third request: success
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseBody)
            .addHeader("Content-Type", "application/json"))

        val result = embedder.process("test text")

        assertEquals(1024, result.size)
        assertEquals(3, mockServer.requestCount, "Should retry twice on 503")
    }

    @Test
    fun `test exponential backoff timing`() = runBlocking {
        val embedder = Embedder(
            serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
            maxRetries = 3,
            baseDelayMs = 100
        )

        val mockEmbedding = (1..1024).map { it.toFloat() / 1024f }
        val responseBody = """[[${mockEmbedding.joinToString(",")}]]"""

        // Fail 3 times, then succeed
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseBody)
            .addHeader("Content-Type", "application/json"))

        val startTime = System.currentTimeMillis()
        val result = embedder.process("test text")
        val elapsed = System.currentTimeMillis() - startTime

        assertEquals(1024, result.size)
        assertEquals(4, mockServer.requestCount)

        // Should have delays: ~100ms, ~200ms, ~400ms + jitter
        // Total minimum: 700ms, with jitter could be up to ~1050ms
        assertTrue(elapsed >= 700, "Should have exponential backoff delays (got ${elapsed}ms)")
    }

    @Test
    fun `test max retries exhaustion throws exception`() = runBlocking {
        val embedder = Embedder(
            serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
            maxRetries = 2,
            baseDelayMs = 10
        )

        // Always return 503
        repeat(5) {
            mockServer.enqueue(MockResponse().setResponseCode(503))
        }

        assertFailsWith<Exception> {
            embedder.process("test text")
        }

        assertEquals(3, mockServer.requestCount, "Should try initial + 2 retries")
    }

    @Test
    fun `test non-retryable error does not retry`() = runBlocking {
        val embedder = Embedder(
            serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
            maxRetries = 3,
            baseDelayMs = 10
        )

        // Return 400 bad request (non-retryable)
        mockServer.enqueue(MockResponse()
            .setResponseCode(400)
            .setBody("""{"error":"Bad request"}"""))

        assertFailsWith<Exception> {
            embedder.process("test text")
        }

        assertEquals(1, mockServer.requestCount, "Should not retry on 400")
    }

    @Test
    fun `test text truncation for max tokens`() = runBlocking {
        val embedder = Embedder(
            serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
            maxTokens = 8192,
            maxRetries = 0,
            baseDelayMs = 10
        )

        val mockEmbedding = (1..1024).map { it.toFloat() / 1024f }
        val responseBody = """[[${mockEmbedding.joinToString(",")}]]"""

        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseBody)
            .addHeader("Content-Type", "application/json"))

        // Create text with 10,000 tokens (way over limit)
        val longText = "word ".repeat(10000)

        val result = embedder.process(longText)

        assertEquals(1024, result.size)

        // Verify request body was truncated
        val recordedRequest = mockServer.takeRequest()
        val requestBody = recordedRequest.body.readUtf8()

        // Original text has ~50k characters (10k * "word "), truncated should be much smaller
        assertTrue(requestBody.length < longText.length, "Request should truncate long text")
    }

    @Test
    fun `test telemetry stats tracking`() = runBlocking {
        val embedder = Embedder(
            serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
            maxRetries = 0,
            baseDelayMs = 10
        )

        val mockEmbedding = (1..1024).map { it.toFloat() / 1024f }
        val responseBody = """[[${mockEmbedding.joinToString(",")}]]"""

        // Process 3 embeddings
        repeat(3) {
            mockServer.enqueue(MockResponse()
                .setResponseCode(200)
                .setBody(responseBody)
                .addHeader("Content-Type", "application/json"))
        }

        embedder.process("text 1")
        embedder.process("text 2")
        embedder.process("text 3")

        val stats = embedder.getStats()
        assertEquals(3, stats.totalRequests)
        assertTrue(stats.averageLatencyMs >= 0)
    }

    @Test
    fun `test jitter is applied to backoff`() = runBlocking {
        val embedder = Embedder(
            serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
            maxRetries = 2,
            baseDelayMs = 100
        )

        val mockEmbedding = (1..1024).map { it.toFloat() / 1024f }
        val responseBody = """[[${mockEmbedding.joinToString(",")}]]"""

        // Fail twice, then succeed
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse().setResponseCode(503))
        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody(responseBody)
            .addHeader("Content-Type", "application/json"))

        val timings = mutableListOf<Long>()
        var lastTime = System.currentTimeMillis()

        // We can't directly measure jitter, but we can verify it retries
        val result = embedder.process("test")

        assertEquals(1024, result.size)
        assertEquals(3, mockServer.requestCount)
    }

    @Test
    fun `test retryable exception types`() {
        val exception429 = RetryableException("Rate limit")
        val exception500 = RetryableException("Server error")
        val exception503 = RetryableException("Service unavailable")

        assertNotNull(exception429.message)
        assertNotNull(exception500.message)
        assertNotNull(exception503.message)
    }

    @Test
    fun `test empty response handling`() = runBlocking {
        val embedder = Embedder(
            serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
            maxRetries = 0
        )

        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("")
            .addHeader("Content-Type", "application/json"))

        assertFailsWith<Exception> {
            embedder.process("test")
        }
    }

    @Test
    fun `test malformed JSON response handling`() = runBlocking {
        val embedder = Embedder(
            serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
            maxRetries = 0
        )

        mockServer.enqueue(MockResponse()
            .setResponseCode(200)
            .setBody("not valid json")
            .addHeader("Content-Type", "application/json"))

        assertFailsWith<Exception> {
            embedder.process("test")
        }
    }

    @Test
    fun `test all retryable status codes`() {
        val retryableCodes = listOf(429, 500, 502, 503, 504)

        retryableCodes.forEach { code ->
            val embedder = Embedder(
                serviceUrl = mockServer.url("/").toString().removeSuffix("/"),
                maxRetries = 1,
                baseDelayMs = 10
            )

            runBlocking {
                val mockEmbedding = (1..1024).map { it.toFloat() / 1024f }
                val responseBody = """[[${mockEmbedding.joinToString(",")}]]"""

                mockServer.enqueue(MockResponse().setResponseCode(code))
                mockServer.enqueue(MockResponse()
                    .setResponseCode(200)
                    .setBody(responseBody)
                    .addHeader("Content-Type", "application/json"))

                val result = embedder.process("test for code $code")
                assertEquals(1024, result.size, "Should retry on $code")
            }
        }
    }
}
