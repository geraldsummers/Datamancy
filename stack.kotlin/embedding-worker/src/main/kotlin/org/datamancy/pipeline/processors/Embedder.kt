package org.datamancy.pipeline.processors

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.datamancy.pipeline.core.Processor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

/**
 * Converts text into dense vector embeddings using an external Embedding Service (BGE-M3 model).
 *
 * ## Purpose
 * This processor is the critical bridge between text chunks (from [Chunker]) and vector storage (Qdrant).
 * It calls the Embedding Service HTTP API to transform text into 1024-dimensional float vectors suitable
 * for semantic similarity search.
 *
 * ## Integration with Embedding Service
 * - **Service URL**: Configured via constructor (typically http://embedding-service:8000)
 * - **Model**: BGE-M3 (BAAI's multilingual embedding model, 1024 dimensions)
 * - **API Endpoint**: POST /embed
 * - **Request Format**: `{"inputs": "text...", "truncate": true}`
 * - **Response Format**: `[[0.123, -0.456, ...]]` (array of float arrays, we take first)
 *
 * The Embedding Service is a separate Docker container running a HuggingFace inference server.
 * It may be slow (GPU-bound) or temporarily unavailable (during restarts), hence the robust retry logic.
 *
 * ## Token Limits & Truncation
 * - **BGE-M3 hard limit**: 8192 tokens
 * - **Default maxTokens**: 7782 (95% safety margin)
 * - **Pre-truncation**: If text exceeds maxTokens, truncate using [TokenCounter.truncateToTokens]
 * - **Service-side truncation**: Pass `"truncate": true` as additional safety net
 *
 * Truncation happens in two layers:
 * 1. Client-side (this processor): Prevents sending oversized payloads that waste bandwidth
 * 2. Service-side: Embedding Service's final safeguard if tokenization differs slightly
 *
 * Why truncate instead of rejecting? In some pipelines, Chunker may not run (e.g., for small documents),
 * so Embedder must handle arbitrary inputs gracefully.
 *
 * ## Retry Logic & Error Handling
 * Embedding Service failures fall into two categories:
 *
 * ### Retryable Errors (with exponential backoff + jitter)
 * - **HTTP 429 (Too Many Requests)**: Service rate-limited, backoff and retry
 * - **HTTP 500/502/503/504**: Server errors or proxy issues, likely transient
 * - **ConnectException**: Service restarting or network partition
 * - **SocketTimeoutException**: Service overloaded or processing very long text
 * - **IOException**: Network instability
 *
 * Retry strategy:
 * - Exponential backoff: delay = baseDelayMs * 2^attempt (100ms, 200ms, 400ms, 800ms, 1600ms)
 * - Jitter: Random 0-50% added to prevent thundering herd
 * - Max retries: 5 attempts before giving up
 * - Total max time: ~3.1 seconds (100 + 200 + 400 + 800 + 1600 + jitter)
 *
 * ### Non-Retryable Errors (immediate failure)
 * - **HTTP 4xx (except 429)**: Invalid request, bad API key, malformed JSON
 * - **JSON parsing errors**: Response format changed unexpectedly
 * - **Empty embedding array**: Service returned success but no data
 *
 * ## Performance Telemetry
 * Tracks embedding operation metrics:
 * - **totalRequests**: Count of successful embedding calls
 * - **totalRetries**: Count of retry attempts across all requests
 * - **totalDurationMs**: Cumulative latency for performance monitoring
 *
 * Metrics are logged every hour (3,600,000ms) to help diagnose:
 * - Slow embedding service (high average latency)
 * - Flaky service (high retry rate)
 * - Throughput bottlenecks (requests/hour)
 *
 * ## Vector Dimensions
 * BGE-M3 outputs 1024-dimensional vectors (not 768 like BERT, not 1536 like OpenAI embeddings).
 * This dimension count is fixed by the model architecture and must match Qdrant's collection schema.
 *
 * If Qdrant expects 1024 dimensions but receives 768, inserts will fail with schema mismatch errors.
 * Always verify model output dimensions match Qdrant configuration.
 *
 * ## Data Flow in Pipeline
 * ```
 * Chunker → Embedder → TextToVector → QdrantSink
 *   ↓          ↓
 * String   FloatArray[1024]
 * ```
 *
 * Each chunk is embedded independently. For a document split into 3 chunks, Embedder is called 3 times,
 * producing 3 separate vectors stored as individual Qdrant points.
 *
 * @param serviceUrl Base URL of Embedding Service (e.g., http://embedding-service:8000)
 * @param model Model identifier (default "bge-m3", must match service configuration)
 * @param maxTokens Maximum tokens to send (default 7782 = 95% of BGE-M3's 8192 limit)
 * @param maxRetries Number of retry attempts for transient failures (default 5)
 * @param baseDelayMs Initial retry delay in milliseconds (doubles each attempt, default 100ms)
 */
class Embedder(
    private val serviceUrl: String,
    private val model: String = "bge-m3",
    private val maxTokens: Int = 7782,
    private val maxRetries: Int = 5,
    private val baseDelayMs: Long = 100
) : Processor<String, FloatArray> {
    override val name = "Embedder"

    // HTTP client with generous timeouts for slow embedding operations
    // 30s connect/read timeout accommodates GPU-bound processing and cold starts
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    // Performance telemetry tracked across all embedding requests
    private val totalRequests = AtomicLong(0)
    private val totalRetries = AtomicLong(0)
    private val totalDurationMs = AtomicLong(0)
    private var lastReportTime = System.currentTimeMillis()
    private val reportIntervalMs = 3_600_000L  // Log stats every hour

    /**
     * Generates a 1024-dimensional embedding vector for the input text.
     *
     * ## Processing Steps
     * 1. **Token count check**: Count tokens using TokenCounter.countTokens()
     * 2. **Truncation if needed**: If text > maxTokens, truncate to prevent service rejection
     * 3. **HTTP POST to /embed**: Send JSON payload with `{"inputs": text, "truncate": true}`
     * 4. **Retry on failure**: Exponential backoff for retryable errors (429, 5xx, network issues)
     * 5. **Parse response**: Extract FloatArray from JSON response `[[...]]`
     * 6. **Telemetry tracking**: Record latency, retry count, request count
     *
     * ## Why Truncation Happens Here
     * While [Chunker] splits large documents, not all text goes through Chunker (e.g., single-page
     * documents, or pipelines without chunking). Embedder must defensively truncate to prevent
     * service rejections, acting as the final safeguard against oversized inputs.
     *
     * ## Retry vs Fail-Fast
     * Retryable errors indicate transient issues (service restart, rate limit, network hiccup).
     * Non-retryable errors indicate permanent problems (bad request, auth failure, invalid JSON).
     *
     * Exponential backoff with jitter prevents retry storms when the service recovers, giving it
     * time to stabilize before handling the next batch of requests.
     *
     * @param text Input text to embed (will be truncated if > maxTokens)
     * @return 1024-dimensional embedding vector (FloatArray)
     * @throws Exception if embedding fails after all retries or encounters non-retryable error
     */
    override suspend fun process(text: String): FloatArray {
        val startTime = System.currentTimeMillis()
        var lastException: Exception? = null

        // Client-side truncation to prevent bandwidth waste and service rejections
        val actualTokens = TokenCounter.countTokens(text)
        val truncatedText = if (actualTokens > maxTokens) {
            logger.debug { "Text has $actualTokens tokens, truncating to $maxTokens tokens" }
            TokenCounter.truncateToTokens(text, maxTokens)
        } else {
            text
        }

        // Retry loop with exponential backoff for transient failures
        for (attempt in 0..maxRetries) {
            try {
                // Build JSON request with service-side truncation as secondary safeguard
                val requestBody = gson.toJson(mapOf(
                    "inputs" to truncatedText,
                    "truncate" to true  // Service-side safety net if tokenization differs
                )).toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("$serviceUrl/embed")
                    .post(requestBody)
                    .build()

                val result = client.newCall(request).execute().use { response ->
                    // Classify HTTP errors as retryable vs non-retryable
                    if (response.code in listOf(429, 500, 502, 503, 504)) {
                        throw RetryableException("Embedding service returned retryable error ${response.code}")
                    }

                    if (!response.isSuccessful) {
                        throw Exception("Embedding service returned ${response.code}: ${response.body?.string()}")
                    }

                    val body = response.body?.string() ?: throw Exception("Empty response from embedding service")
                    val result = gson.fromJson(body, Array<FloatArray>::class.java)

                    result.firstOrNull() ?: throw Exception("Empty embedding array from service")
                }

                // Track successful request metrics
                val duration = System.currentTimeMillis() - startTime
                totalRequests.incrementAndGet()
                totalDurationMs.addAndGet(duration)

                // Periodic telemetry logging (every hour) to monitor service health
                val now = System.currentTimeMillis()
                if (now - lastReportTime >= reportIntervalMs) {
                    val requests = totalRequests.get()
                    val retries = totalRetries.get()
                    val avgLatency = if (requests > 0) totalDurationMs.get() / requests else 0
                    logger.info { "Embedding telemetry: $requests requests, $retries retries, avg latency ${avgLatency}ms" }
                    lastReportTime = now
                }

                return result

            } catch (e: RetryableException) {
                lastException = e

                if (attempt < maxRetries) {
                    // Exponential backoff: 100ms, 200ms, 400ms, 800ms, 1600ms
                    val backoffMs = baseDelayMs * (1 shl attempt)

                    // Jitter (0-50% of backoff) prevents thundering herd on service recovery
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs

                    totalRetries.incrementAndGet()
                    logger.warn { "Embedding attempt ${attempt + 1}/$maxRetries failed, retrying in ${totalDelay}ms: ${e.message}" }
                    delay(totalDelay)
                } else {
                    logger.error(e) { "Embedding failed after $maxRetries retries: ${e.message}" }
                }
            } catch (e: java.net.ConnectException) {
                // Service unreachable (container restarting, network issue)
                lastException = RetryableException("Connection refused (service may be restarting)", e)

                if (attempt < maxRetries) {
                    // Longer backoff for connection failures: 1s, 2s, 4s, 8s, 16s
                    val backoffMs = 1000L * (1 shl attempt)
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs

                    totalRetries.incrementAndGet()
                    logger.warn { "Embedding service unreachable (attempt ${attempt + 1}/$maxRetries), retrying in ${totalDelay}ms" }
                    delay(totalDelay)
                } else {
                    logger.error(e) { "Embedding service unreachable after $maxRetries retries" }
                }
            } catch (e: java.net.SocketTimeoutException) {
                // Service overloaded or processing very long text
                lastException = RetryableException("Socket timeout (service overloaded or restarting)", e)

                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1 shl attempt)
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs

                    totalRetries.incrementAndGet()
                    logger.warn { "Embedding request timeout (attempt ${attempt + 1}/$maxRetries), retrying in ${totalDelay}ms" }
                    delay(totalDelay)
                } else {
                    logger.error(e) { "Embedding request timeout after $maxRetries retries" }
                }
            } catch (e: java.io.IOException) {
                // Network instability (packet loss, DNS issues, etc.)
                lastException = RetryableException("Network IO error", e)

                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1 shl attempt)
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs

                    totalRetries.incrementAndGet()
                    logger.warn { "Network error (attempt ${attempt + 1}/$maxRetries), retrying in ${totalDelay}ms: ${e.message}" }
                    delay(totalDelay)
                } else {
                    logger.error(e) { "Network error after $maxRetries retries: ${e.message}" }
                }
            } catch (e: Exception) {
                // Non-retryable errors (4xx responses, JSON parsing failures, etc.)
                logger.error(e) { "Failed to generate embedding (non-retryable): ${e.message}" }
                throw e
            }
        }

        // All retries exhausted
        throw lastException ?: Exception("Failed to generate embedding after $maxRetries retries")
    }

    /**
     * Returns cumulative performance statistics for this Embedder instance.
     *
     * Used by monitoring systems to track embedding service health and performance.
     * High retry counts indicate service instability. High average latency indicates
     * overloaded GPU or slow network.
     */
    fun getStats(): EmbedderStats {
        val requests = totalRequests.get()
        val avgLatency = if (requests > 0) totalDurationMs.get() / requests else 0
        return EmbedderStats(
            totalRequests = requests,
            averageLatencyMs = avgLatency
        )
    }
}

/**
 * Performance statistics for Embedder operations.
 *
 * @property totalRequests Number of successful embedding requests completed
 * @property averageLatencyMs Mean latency per request in milliseconds (includes retries)
 */
data class EmbedderStats(
    val totalRequests: Long,
    val averageLatencyMs: Long
)

/**
 * Legacy response model for embedding API (currently unused).
 *
 * The actual API returns Array<FloatArray> directly, not this wrapper structure.
 * Kept for backward compatibility if API format changes.
 */
data class EmbeddingResponse(
    val embedding: List<Float>
)

/**
 * Custom exception type to distinguish retryable errors from permanent failures.
 *
 * Used in Embedder's retry logic to identify transient issues (network hiccups, service restarts,
 * rate limits) that warrant retry vs permanent errors (auth failures, malformed requests) that
 * should fail fast.
 */
class RetryableException(message: String, cause: Throwable? = null) : Exception(message, cause)
