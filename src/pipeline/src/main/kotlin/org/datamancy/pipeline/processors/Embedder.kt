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
 * Generates embeddings for text using an embedding service with exponential backoff retry
 */
class Embedder(
    private val serviceUrl: String,
    private val model: String = "bge-m3",
    private val maxTokens: Int = 8192,  // BGE-M3 supports 8192 tokens
    private val maxRetries: Int = 5,
    private val baseDelayMs: Long = 100
) : Processor<String, FloatArray> {
    override val name = "Embedder"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    // Telemetry counters
    private val totalRequests = AtomicLong(0)
    private val totalRetries = AtomicLong(0)
    private val totalDurationMs = AtomicLong(0)
    private var lastReportTime = System.currentTimeMillis()
    private val reportIntervalMs = 3_600_000L // Report every 60 minutes (quieter)

    override suspend fun process(text: String): FloatArray {
        val startTime = System.currentTimeMillis()
        var lastException: Exception? = null

        // Use accurate token counting and truncation
        val actualTokens = TokenCounter.countTokens(text)
        val truncatedText = if (actualTokens > maxTokens) {
            logger.debug { "Text has $actualTokens tokens, truncating to $maxTokens tokens" }
            TokenCounter.truncateToTokens(text, maxTokens)
        } else {
            text
        }

        // Retry loop with exponential backoff + jitter
        for (attempt in 0..maxRetries) {
            try {
                val requestBody = gson.toJson(mapOf("inputs" to truncatedText))
                    .toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("$serviceUrl/embed")
                    .post(requestBody)
                    .build()

                val result = client.newCall(request).execute().use { response ->
                    // Handle retryable errors
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

                // Success! Track telemetry
                val duration = System.currentTimeMillis() - startTime
                totalRequests.incrementAndGet()
                totalDurationMs.addAndGet(duration)

                // Periodic reporting
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

                    // Add jitter: random 0-50% of backoff time
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs

                    totalRetries.incrementAndGet()
                    logger.warn { "Embedding attempt ${attempt + 1}/$maxRetries failed, retrying in ${totalDelay}ms: ${e.message}" }
                    delay(totalDelay)
                } else {
                    logger.error(e) { "Embedding failed after $maxRetries retries: ${e.message}" }
                }
            } catch (e: Exception) {
                // Non-retryable error
                logger.error(e) { "Failed to generate embedding (non-retryable): ${e.message}" }
                throw e
            }
        }

        // All retries exhausted
        throw lastException ?: Exception("Failed to generate embedding after $maxRetries retries")
    }

    fun getStats(): EmbedderStats {
        val requests = totalRequests.get()
        val avgLatency = if (requests > 0) totalDurationMs.get() / requests else 0
        return EmbedderStats(
            totalRequests = requests,
            averageLatencyMs = avgLatency
        )
    }
}

data class EmbedderStats(
    val totalRequests: Long,
    val averageLatencyMs: Long
)

data class EmbeddingResponse(
    val embedding: List<Float>
)

/**
 * Exception indicating a retryable error (network issues, rate limits, server errors)
 */
class RetryableException(message: String, cause: Throwable? = null) : Exception(message, cause)
