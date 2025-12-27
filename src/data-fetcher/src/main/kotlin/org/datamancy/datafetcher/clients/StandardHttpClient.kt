package org.datamancy.datafetcher.clients

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

/**
 * Standardized HTTP client with built-in:
 * - Exponential backoff with jitter for retries
 * - Rate limiting per host
 * - Standard headers (User-Agent, Accept-Encoding)
 * - Configurable timeouts
 *
 * Usage:
 * ```
 * val client = StandardHttpClient.builder()
 *     .maxRetries(3)
 *     .perHostConcurrency(5)
 *     .build()
 *
 * val response = client.get("https://example.com/api/data")
 * ```
 */
class StandardHttpClient private constructor(
    private val httpClient: OkHttpClient,
    private val maxRetries: Int,
    private val initialBackoffMs: Long,
    private val maxBackoffMs: Long,
    private val perHostConcurrency: Int,
    private val rateLimitConfig: RateLimitConfig
) {
    private val hostSemaphores = ConcurrentHashMap<String, Semaphore>()
    private val hostRateLimiters = ConcurrentHashMap<String, RateLimiter>()

    /**
     * Perform GET request with automatic retries and rate limiting.
     */
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Response {
        val makeRequest: suspend () -> Response = suspend {
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            executeRateLimited(url, request)
        }
        return executeWithRetry(makeRequest)
    }

    /**
     * Perform POST request with automatic retries and rate limiting.
     */
    suspend fun post(url: String, body: String, contentType: String = "application/json", headers: Map<String, String> = emptyMap()): Response {
        val makeRequest: suspend () -> Response = suspend {
            val request = Request.Builder()
                .url(url)
                .post(RequestBody.create(contentType.toMediaType(), body))
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            executeRateLimited(url, request)
        }
        return executeWithRetry(makeRequest)
    }

    /**
     * Perform HEAD request to get metadata without downloading body.
     * Useful for checking Last-Modified headers.
     */
    suspend fun head(url: String, headers: Map<String, String> = emptyMap()): Response {
        val makeRequest: suspend () -> Response = suspend {
            val request = Request.Builder()
                .url(url)
                .head()
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build()

            executeRateLimited(url, request)
        }
        return executeWithRetry(makeRequest)
    }

    /**
     * Execute request with per-host rate limiting.
     */
    private suspend fun executeRateLimited(url: String, request: Request): Response {
        val host = request.url.host

        // Acquire semaphore for concurrency control
        val semaphore = hostSemaphores.computeIfAbsent(host) { Semaphore(perHostConcurrency) }
        semaphore.acquire()

        try {
            // Apply rate limiting
            val rateLimiter = hostRateLimiters.computeIfAbsent(host) {
                RateLimiter(rateLimitConfig.requestsPerSecond, rateLimitConfig.burstSize)
            }
            rateLimiter.acquire()

            // Execute request
            return httpClient.newCall(request).execute()
        } finally {
            semaphore.release()
        }
    }

    /**
     * Execute request with exponential backoff retry logic.
     */
    private suspend fun executeWithRetry(block: suspend () -> Response): Response {
        var lastException: Exception? = null
        var attempt = 0

        while (attempt <= maxRetries) {
            try {
                val response = block()

                // Retry on 429 (rate limit) or 5xx (server errors)
                if (response.code in 500..599 || response.code == 429) {
                    if (attempt < maxRetries) {
                        val backoff = calculateBackoff(attempt)
                        logger.warn { "HTTP ${response.code} on attempt ${attempt + 1}, retrying in ${backoff}ms" }
                        response.close()
                        delay(backoff)
                        attempt++
                        continue
                    }
                }

                // Success or non-retryable error
                return response

            } catch (e: IOException) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoff = calculateBackoff(attempt)
                    logger.warn { "Network error on attempt ${attempt + 1}: ${e.message}, retrying in ${backoff}ms" }
                    delay(backoff)
                    attempt++
                } else {
                    break
                }
            }
        }

        throw lastException ?: IOException("Max retries exceeded")
    }

    /**
     * Calculate exponential backoff with jitter.
     */
    private fun calculateBackoff(attempt: Int): Long {
        val exponentialBackoff = (initialBackoffMs * 2.0.pow(attempt)).toLong()
        val cappedBackoff = min(exponentialBackoff, maxBackoffMs)
        val jitter = Random.nextLong(0, cappedBackoff / 4)
        return cappedBackoff + jitter
    }

    companion object {
        fun builder() = Builder()
    }

    class Builder {
        private var connectTimeoutSeconds: Long = 10
        private var readTimeoutSeconds: Long = 30
        private var writeTimeoutSeconds: Long = 30
        private var maxRetries: Int = 3
        private var initialBackoffMs: Long = 1000
        private var maxBackoffMs: Long = 30000
        private var perHostConcurrency: Int = 5
        private var userAgent: String = "Datamancy/1.0"
        private var followRedirects: Boolean = true
        private var rateLimitConfig: RateLimitConfig = RateLimitConfig()

        fun connectTimeout(seconds: Long) = apply { this.connectTimeoutSeconds = seconds }
        fun readTimeout(seconds: Long) = apply { this.readTimeoutSeconds = seconds }
        fun writeTimeout(seconds: Long) = apply { this.writeTimeoutSeconds = seconds }
        fun maxRetries(retries: Int) = apply { this.maxRetries = retries }
        fun initialBackoffMs(ms: Long) = apply { this.initialBackoffMs = ms }
        fun maxBackoffMs(ms: Long) = apply { this.maxBackoffMs = ms }
        fun perHostConcurrency(concurrency: Int) = apply { this.perHostConcurrency = concurrency }
        fun userAgent(ua: String) = apply { this.userAgent = ua }
        fun followRedirects(follow: Boolean) = apply { this.followRedirects = follow }
        fun rateLimit(requestsPerSecond: Int, burstSize: Int = requestsPerSecond * 2) = apply {
            this.rateLimitConfig = RateLimitConfig(requestsPerSecond, burstSize)
        }

        fun build(): StandardHttpClient {
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(writeTimeoutSeconds, TimeUnit.SECONDS)
                .followRedirects(followRedirects)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", userAgent)
                        .header("Accept-Encoding", "gzip, deflate")
                        .build()
                    chain.proceed(request)
                }
                .build()

            return StandardHttpClient(
                httpClient,
                maxRetries,
                initialBackoffMs,
                maxBackoffMs,
                perHostConcurrency,
                rateLimitConfig
            )
        }
    }
}

/**
 * Rate limiting configuration
 */
data class RateLimitConfig(
    val requestsPerSecond: Int = 10,
    val burstSize: Int = 20
)

/**
 * Token bucket rate limiter
 */
class RateLimiter(
    private val requestsPerSecond: Int,
    private val burstSize: Int
) {
    private var tokens = burstSize.toDouble()
    private var lastRefillTime = System.nanoTime()
    private val refillRatePerNano = requestsPerSecond.toDouble() / 1_000_000_000.0

    suspend fun acquire() {
        synchronized(this) {
            refillTokens()

            while (tokens < 1.0) {
                val waitTimeMs = ((1.0 - tokens) / refillRatePerNano / 1_000_000).toLong()
                // Release lock during delay
                val wait = waitTimeMs.coerceAtLeast(1)
                return@synchronized wait
            }

            tokens -= 1.0
            return@synchronized 0L
        }.let { waitMs ->
            if (waitMs > 0) {
                delay(waitMs)
                acquire() // Re-acquire after waiting
            }
        }
    }

    private fun refillTokens() {
        val now = System.nanoTime()
        val timePassed = now - lastRefillTime
        val tokensToAdd = timePassed * refillRatePerNano
        tokens = min(tokens + tokensToAdd, burstSize.toDouble())
        lastRefillTime = now
    }
}
