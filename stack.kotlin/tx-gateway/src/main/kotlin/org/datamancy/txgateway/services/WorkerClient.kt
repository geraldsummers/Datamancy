package org.datamancy.txgateway.services

import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/**
 * HTTP client for communicating with Python workers
 * Using HTTP/JSON instead of gRPC for MVP simplicity
 */
class WorkerClient(
    private val evmBroadcasterUrl: String = System.getenv("EVM_BROADCASTER_URL")
        ?: "http://evm-broadcaster:8081",
    private val hyperliquidWorkerUrl: String = System.getenv("HYPERLIQUID_WORKER_URL")
        ?: "http://hyperliquid-worker:8082"
) {
    private val logger = LoggerFactory.getLogger(WorkerClient::class.java)
    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val workerSharedToken = System.getenv("WORKER_SHARED_TOKEN")?.trim().orEmpty()
    private val sensitivePayloadKeys = setOf("evmPrivateKey", "hyperliquidKey")
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun redactPayload(payload: Map<String, Any>): String {
        val sanitized = payload.mapValues { (key, value) ->
            if (key in sensitivePayloadKeys) "[REDACTED]" else value
        }
        return gson.toJson(sanitized)
    }

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (workerSharedToken.isNotEmpty()) {
            builder.header("X-Worker-Token", workerSharedToken)
        }
        return builder
    }

    fun submitEvmTransfer(payload: Map<String, Any>): Map<String, Any> {
        val json = gson.toJson(payload)
        val body = json.toRequestBody(jsonMediaType)

        val request = requestBuilder("$evmBroadcasterUrl/submit")
            .post(body)
            .build()

        logger.info("Submitting EVM transfer: {}", redactPayload(payload))

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("EVM broadcaster error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
        }
    }

    fun submitHyperliquidOrder(payload: Map<String, Any>): Map<String, Any> {
        val json = gson.toJson(payload)
        val body = json.toRequestBody(jsonMediaType)

        val request = requestBuilder("$hyperliquidWorkerUrl/order")
            .post(body)
            .build()

        logger.info("Submitting Hyperliquid order: {}", redactPayload(payload))

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
        }
    }

    fun cancelHyperliquidOrder(
        orderId: String,
        username: String,
        symbol: String,
        hyperliquidKey: String
    ): Map<String, Any> {
        val payload = mapOf(
            "username" to username,
            "symbol" to symbol,
            "hyperliquidKey" to hyperliquidKey
        )
        val request = requestBuilder("$hyperliquidWorkerUrl/cancel/$orderId")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        logger.info("Cancelling Hyperliquid order: orderId={}, payload={}", orderId, redactPayload(payload))

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
        }
    }

    fun cancelAllHyperliquidOrders(
        username: String,
        hyperliquidKey: String,
        symbol: String? = null
    ): Map<String, Any> {
        val payload = buildMap<String, Any> {
            put("username", username)
            put("hyperliquidKey", hyperliquidKey)
            symbol?.takeIf { it.isNotBlank() }?.let { put("symbol", it) }
        }
        val request = requestBuilder("$hyperliquidWorkerUrl/cancel-all")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        logger.info("Cancelling all Hyperliquid orders for user={}, payload={}", username, redactPayload(payload))

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
        }
    }

    fun getHyperliquidPositions(username: String, hyperliquidKey: String): List<Map<String, Any>> {
        val payload = mapOf(
            "user" to username,
            "hyperliquidKey" to hyperliquidKey
        )
        val request = requestBuilder("$hyperliquidWorkerUrl/positions")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        logger.info("Fetching Hyperliquid positions for user={}, payload={}", username, redactPayload(payload))

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "[]"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, List::class.java) as List<Map<String, Any>>
        }
    }

    fun getHyperliquidBalance(username: String, hyperliquidKey: String): Map<String, Any> {
        val payload = mapOf(
            "user" to username,
            "hyperliquidKey" to hyperliquidKey
        )
        val request = requestBuilder("$hyperliquidWorkerUrl/balance")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        logger.info("Fetching Hyperliquid balance for user={}, payload={}", username, redactPayload(payload))

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
        }
    }

    fun getHyperliquidOrders(
        username: String,
        hyperliquidKey: String,
        symbol: String? = null
    ): List<Map<String, Any>> {
        val payload = buildMap<String, Any> {
            put("user", username)
            put("hyperliquidKey", hyperliquidKey)
            symbol?.takeIf { it.isNotBlank() }?.let { put("symbol", it) }
        }
        val request = requestBuilder("$hyperliquidWorkerUrl/orders")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        logger.info("Fetching Hyperliquid open orders for user={}, payload={}", username, redactPayload(payload))

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "[]"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, List::class.java) as List<Map<String, Any>>
        }
    }

    fun closeHyperliquidPosition(
        username: String,
        hyperliquidKey: String,
        symbol: String
    ): Map<String, Any> {
        val payload = mapOf(
            "username" to username,
            "symbol" to symbol,
            "hyperliquidKey" to hyperliquidKey
        )
        val request = requestBuilder("$hyperliquidWorkerUrl/close")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        logger.info("Closing Hyperliquid position for user={}, symbol={}", username, symbol)

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
        }
    }

    fun closeAllHyperliquidPositions(
        username: String,
        hyperliquidKey: String
    ): Map<String, Any> {
        val payload = mapOf(
            "username" to username,
            "hyperliquidKey" to hyperliquidKey
        )
        val request = requestBuilder("$hyperliquidWorkerUrl/close-all")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        logger.info("Closing all Hyperliquid positions for user={}", username)

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
        }
    }

    fun getHyperliquidMarkets(): List<Map<String, Any?>> {
        val request = requestBuilder("$hyperliquidWorkerUrl/markets")
            .get()
            .build()

        logger.info("Fetching Hyperliquid market catalog")

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }

            @Suppress("UNCHECKED_CAST")
            val payload = gson.fromJson(responseBody, Map::class.java) as Map<String, Any?>
            val markets = payload["markets"] as? List<*> ?: return emptyList()
            return markets.mapNotNull { entry ->
                val raw = entry as? Map<*, *> ?: return@mapNotNull null
                raw.entries.associate { (key, value) -> key.toString() to value }
            }
        }
    }

    fun healthCheck(): Pair<Boolean, Boolean> {
        val evmHealthy = try {
            val request = Request.Builder()
                .url("$evmBroadcasterUrl/health")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }

        val hlHealthy = try {
            val request = Request.Builder()
                .url("$hyperliquidWorkerUrl/health")
                .get()
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }

        return Pair(evmHealthy, hlHealthy)
    }
}
