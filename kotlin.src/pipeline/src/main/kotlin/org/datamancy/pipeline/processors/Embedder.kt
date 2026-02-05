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


class Embedder(
    private val serviceUrl: String,
    private val model: String = "bge-m3",
    private val maxTokens: Int = 7782,  
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

    
    private val totalRequests = AtomicLong(0)
    private val totalRetries = AtomicLong(0)
    private val totalDurationMs = AtomicLong(0)
    private var lastReportTime = System.currentTimeMillis()
    private val reportIntervalMs = 3_600_000L 

    override suspend fun process(text: String): FloatArray {
        val startTime = System.currentTimeMillis()
        var lastException: Exception? = null

        
        val actualTokens = TokenCounter.countTokens(text)
        val truncatedText = if (actualTokens > maxTokens) {
            logger.debug { "Text has $actualTokens tokens, truncating to $maxTokens tokens" }
            TokenCounter.truncateToTokens(text, maxTokens)
        } else {
            text
        }

        
        for (attempt in 0..maxRetries) {
            try {
                val requestBody = gson.toJson(mapOf(
                    "inputs" to truncatedText,
                    "truncate" to true  
                )).toRequestBody(jsonMediaType)

                val request = Request.Builder()
                    .url("$serviceUrl/embed")
                    .post(requestBody)
                    .build()

                val result = client.newCall(request).execute().use { response ->
                    
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

                
                val duration = System.currentTimeMillis() - startTime
                totalRequests.incrementAndGet()
                totalDurationMs.addAndGet(duration)

                
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
                    
                    val backoffMs = baseDelayMs * (1 shl attempt)

                    
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs

                    totalRetries.incrementAndGet()
                    logger.warn { "Embedding attempt ${attempt + 1}/$maxRetries failed, retrying in ${totalDelay}ms: ${e.message}" }
                    delay(totalDelay)
                } else {
                    logger.error(e) { "Embedding failed after $maxRetries retries: ${e.message}" }
                }
            } catch (e: java.net.ConnectException) {
                
                lastException = RetryableException("Connection refused (service may be restarting)", e)

                if (attempt < maxRetries) {
                    
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
                
                logger.error(e) { "Failed to generate embedding (non-retryable): ${e.message}" }
                throw e
            }
        }

        
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


class RetryableException(message: String, cause: Throwable? = null) : Exception(message, cause)
