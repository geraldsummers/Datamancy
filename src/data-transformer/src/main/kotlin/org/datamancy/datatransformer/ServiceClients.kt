package org.datamancy.datatransformer

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Client for data-bookstack-writer service
 */
class BookStackWriterClient(
    private val baseUrl: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Create or update a page in BookStack
     */
    fun createOrUpdatePage(
        sourceType: String,
        category: String,
        title: String,
        content: String,
        metadata: Map<String, String> = emptyMap()
    ): BookStackPageResult {
        val payload = mapOf(
            "sourceType" to sourceType,
            "category" to category,
            "title" to title,
            "content" to content,
            "metadata" to metadata
        )

        val request = Request.Builder()
            .url("$baseUrl/create-or-update-page")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("BookStack writer failed: ${response.code} - ${response.body?.string()}")
            }

            val json = gson.fromJson(response.body?.string(), Map::class.java)
            return BookStackPageResult(
                success = json["success"] as? Boolean ?: false,
                bookstackUrl = json["bookstackUrl"] as? String,
                bookstackPageId = (json["bookstackPageId"] as? Double)?.toInt(),
                error = json["error"] as? String
            )
        }
    }

    /**
     * Test connection to BookStack writer
     */
    fun testConnection(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            logger.error(e) { "BookStack writer connection test failed" }
            false
        }
    }
}

data class BookStackPageResult(
    val success: Boolean,
    val bookstackUrl: String?,
    val bookstackPageId: Int?,
    val error: String?
)

/**
 * Client for data-vector-indexer service
 */
class VectorIndexerClient(
    private val baseUrl: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Index chunks with embeddings
     */
    fun indexChunks(collection: String, chunks: List<ChunkPayload>): IndexingResult {
        val payload = mapOf(
            "collection" to collection,
            "chunks" to chunks
        )

        val request = Request.Builder()
            .url("$baseUrl/index-chunks")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Vector indexer failed: ${response.code} - ${response.body?.string()}")
            }

            val json = gson.fromJson(response.body?.string(), Map::class.java)
            return IndexingResult(
                indexed = (json["indexed"] as? Double)?.toInt() ?: 0,
                failed = (json["failed"] as? Double)?.toInt() ?: 0,
                errors = (json["errors"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
            )
        }
    }

    /**
     * Ensure collection exists
     */
    fun ensureCollection(name: String, vectorSize: Int = 768): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/collections/$name?vectorSize=$vectorSize")
                .post("".toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful || response.code == 409 // 409 = already exists
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure collection: $name" }
            false
        }
    }

    /**
     * Test connection to vector indexer
     */
    fun testConnection(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/health")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            logger.error(e) { "Vector indexer connection test failed" }
            false
        }
    }
}

data class ChunkPayload(
    val id: Int,
    val chunkIndex: Int,
    val totalChunks: Int,
    val content: String,
    val contentSnippet: String,
    val bookstackUrl: String?,
    val bookstackPageId: Int?,
    val sourceType: String,
    val category: String,
    val title: String,
    val originalUrl: String,
    val fetchedAt: String,
    val metadata: Map<String, String> = emptyMap()
)

data class IndexingResult(
    val indexed: Int,
    val failed: Int,
    val errors: List<String>
)
