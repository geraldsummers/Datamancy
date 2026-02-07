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
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun submitEvmTransfer(payload: Map<String, Any>): Map<String, Any> {
        val json = gson.toJson(payload)
        val body = json.toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("$evmBroadcasterUrl/submit")
            .post(body)
            .build()

        logger.info("Submitting EVM transfer: $json")

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

        val request = Request.Builder()
            .url("$hyperliquidWorkerUrl/order")
            .post(body)
            .build()

        logger.info("Submitting Hyperliquid order: $json")

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
        }
    }

    fun cancelHyperliquidOrder(orderId: String): Map<String, Any> {
        val request = Request.Builder()
            .url("$hyperliquidWorkerUrl/cancel/$orderId")
            .post("{}".toRequestBody(jsonMediaType))
            .build()

        logger.info("Cancelling Hyperliquid order: $orderId")

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
        }
    }

    fun getHyperliquidPositions(username: String): List<Map<String, Any>> {
        val request = Request.Builder()
            .url("$hyperliquidWorkerUrl/positions?user=$username")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "[]"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, List::class.java) as List<Map<String, Any>>
        }
    }

    fun getHyperliquidBalance(username: String): Map<String, Any> {
        val request = Request.Builder()
            .url("$hyperliquidWorkerUrl/balance?user=$username")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: "{}"
            if (!response.isSuccessful) {
                throw RuntimeException("Hyperliquid worker error: HTTP ${response.code} - $responseBody")
            }
            @Suppress("UNCHECKED_CAST")
            return gson.fromJson(responseBody, Map::class.java) as Map<String, Any>
        }
    }
}
