package org.datamancy.vectorindexer

import com.google.gson.Gson
import com.google.gson.JsonArray
import io.github.oshai.kotlinlogging.KotlinLogging
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections.*
import io.qdrant.client.grpc.Points.*
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.VectorsFactory.vectors
import io.qdrant.client.ValueFactory.value
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

const val DEFAULT_VECTOR_SIZE = 768 // BAAI/bge-base-en-v1.5

/**
 * Service for generating embeddings and indexing vectors into Qdrant.
 * Handles batch embedding generation and efficient vector storage with rich metadata.
 */
class VectorIndexer(
    private val qdrantUrl: String,
    private val embeddingServiceUrl: String,
    private val batchSize: Int = 32,
    private val maxConcurrency: Int = 4
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Qdrant client
    private val qdrantHost = qdrantUrl.removePrefix("http://").removePrefix("https://").split(":")[0]
    private val qdrantPort = qdrantUrl.removePrefix("http://").removePrefix("https://").split(":").getOrNull(1)?.toIntOrNull() ?: 6334
    private val qdrant = QdrantClient(QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false).build())

    /**
     * Test connection to Qdrant and embedding service
     */
    fun testConnection(): Boolean {
        return try {
            // Test Qdrant
            qdrant.listCollectionsAsync().get()

            // Test embedding service
            val request = Request.Builder()
                .url("$embeddingServiceUrl/health")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            logger.error(e) { "Connection test failed" }
            false
        }
    }

    /**
     * Index chunks with automatic embedding generation
     */
    suspend fun indexChunks(collection: String, chunks: List<ChunkData>): IndexResult = coroutineScope {
        logger.info { "Indexing ${chunks.size} chunks into collection: $collection" }

        // Ensure collection exists
        ensureCollection(collection)

        val errors = mutableListOf<String>()
        var indexed = 0

        // Process in batches
        chunks.chunked(batchSize).forEach { batch ->
            try {
                // Generate embeddings for batch
                val texts = batch.map { it.content }
                val embeddings = generateBatchEmbeddings(texts)

                // Store in Qdrant with concurrency control
                val semaphore = Semaphore(maxConcurrency)
                val tasks = batch.zip(embeddings).map { (chunk, embedding) ->
                    async {
                        semaphore.withPermit {
                            try {
                                storeVector(collection, chunk, embedding)
                                indexed++
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to store chunk ${chunk.id}" }
                                errors.add("Chunk ${chunk.id}: ${e.message}")
                            }
                        }
                    }
                }
                tasks.awaitAll()

            } catch (e: Exception) {
                logger.error(e) { "Failed to process batch" }
                errors.add("Batch error: ${e.message}")
            }
        }

        IndexResult(
            indexed = indexed,
            failed = chunks.size - indexed,
            errors = errors.take(10) // Limit error list
        )
    }

    /**
     * Delete vectors by source type and optional category
     * Note: Currently simplified - recommend full reindex for updates
     */
    fun deleteBySource(collection: String, sourceType: String, category: String? = null): Int {
        logger.info { "Deleting vectors from $collection: sourceType=$sourceType, category=$category" }
        logger.warn { "Filtered delete by source not fully implemented - recommend full reindex" }
        return 0
    }

    /**
     * List all collections
     */
    fun listCollections(): List<String> {
        return try {
            qdrant.listCollectionsAsync().get()
        } catch (e: Exception) {
            logger.error(e) { "Failed to list collections" }
            emptyList()
        }
    }

    /**
     * Ensure collection exists with proper vector configuration
     */
    fun ensureCollection(name: String, vectorSize: Int = DEFAULT_VECTOR_SIZE) {
        try {
            val collections = qdrant.listCollectionsAsync().get()
            val exists = collections.contains(name)

            if (!exists) {
                logger.info { "Creating Qdrant collection: $name with vector size $vectorSize" }
                qdrant.createCollectionAsync(
                    name,
                    VectorParams.newBuilder()
                        .setSize(vectorSize.toLong())
                        .setDistance(Distance.Cosine)
                        .build()
                ).get()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure collection: $name" }
            throw e
        }
    }

    /**
     * Generate embeddings for multiple texts in a single batch request
     */
    private fun generateBatchEmbeddings(texts: List<String>): List<List<Float>> {
        val payload = gson.toJson(mapOf("inputs" to texts))
        val request = Request.Builder()
            .url("$embeddingServiceUrl/embed")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to generate batch embeddings: ${response.code}")
            }

            val jsonArray = gson.fromJson(response.body?.string(), JsonArray::class.java)
            return jsonArray.map { embArray ->
                embArray.asJsonArray.map { it.asFloat }
            }
        }
    }

    /**
     * Store vector with rich metadata in Qdrant
     */
    private fun storeVector(collection: String, chunk: ChunkData, embedding: List<Float>) {
        val payload = mutableMapOf(
            "chunk_id" to value(chunk.id.toLong()),
            "chunk_index" to value(chunk.chunkIndex.toLong()),
            "total_chunks" to value(chunk.totalChunks.toLong()),
            "source_type" to value(chunk.sourceType),
            "category" to value(chunk.category),
            "title" to value(chunk.title),
            "content_snippet" to value(chunk.contentSnippet),
            "original_url" to value(chunk.originalUrl),
            "fetched_at" to value(chunk.fetchedAt)
        )

        // Add BookStack metadata if present
        chunk.bookstackUrl?.let { payload["bookstack_url"] = value(it) }
        chunk.bookstackPageId?.let { payload["bookstack_page_id"] = value(it.toLong()) }

        // Add custom metadata
        chunk.metadata.forEach { (key, value) ->
            payload[key] = value(value)
        }

        val point = PointStruct.newBuilder()
            .setId(id(chunk.id.toLong()))
            .setVectors(vectors(embedding))
            .putAllPayload(payload)
            .build()

        qdrant.upsertAsync(collection, listOf(point)).get()
    }
}
