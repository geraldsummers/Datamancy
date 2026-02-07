package org.datamancy.pipeline.sinks

import io.github.oshai.kotlinlogging.KotlinLogging
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections.*
import io.qdrant.client.grpc.Points.*
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.VectorsFactory.vectors
import io.qdrant.client.ValueFactory.value
import org.datamancy.pipeline.core.Sink
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val logger = KotlinLogging.logger {}
private const val QDRANT_TIMEOUT_SECONDS = 30L

/**
 * Converts a string to a deterministic UUID using SHA-256 hashing.
 *
 * This ensures that the same document ID always produces the same UUID, enabling idempotent writes
 * to Qdrant. When the pipeline re-processes a document (e.g., after a restart or retry), this prevents
 * duplicate vectors in the database - upsert operations will update the existing vector instead of creating
 * a new entry.
 *
 * **Why idempotency matters:**
 * - Pipeline may retry failed embedding operations
 * - EmbeddingScheduler uses exponential backoff and retries (up to 3 attempts)
 * - Prevents vector database bloat from duplicate documents
 * - Enables safe re-processing of entire document collections
 *
 * @receiver The document ID string to convert
 * @return A deterministic UUID derived from the SHA-256 hash of the input string
 */
private fun String.toDeterministicUUID(): UUID {
    val md = MessageDigest.getInstance("SHA-256")
    val hash = md.digest(this.toByteArray())

    return UUID.nameUUIDFromBytes(hash.copyOf(16))
}


/**
 * Sink implementation for writing vector embeddings to Qdrant vector database via gRPC.
 *
 * **Qdrant Integration:**
 * - Uses gRPC client (port 6334) for high-performance binary protocol communication
 * - Each sink instance manages a single Qdrant collection (e.g., "rss_feeds", "cve", "wikipedia")
 * - Vectors are indexed using cosine similarity for semantic search
 * - Collection is auto-created on initialization if it doesn't exist
 *
 * **Usage by EmbeddingScheduler:**
 * The EmbeddingScheduler polls PostgreSQL for documents with status=PENDING, generates embeddings
 * via the Embedding Service (BGE-M3 model), then writes vectors to Qdrant using this sink. After
 * successful write, document status is updated to COMPLETED.
 *
 * **Vector Storage Model:**
 * - Each vector is 1024-dimensional (BGE-M3 embedding model output)
 * - Vectors are stored with metadata (title, URL, source, collection, etc.)
 * - Deterministic UUIDs ensure idempotent writes (same document ID always maps to same Qdrant ID)
 * - Upsert semantics allow safe re-processing without duplicates
 *
 * **Downstream Consumers:**
 * - search-service module queries these vectors for semantic search (via cosine similarity)
 * - agent-tool-server provides search_qdrant tool for LLM agents to query vectors
 *
 * @param qdrantHost The Qdrant hostname (e.g., "qdrant")
 * @param qdrantPort The Qdrant gRPC port (default: 6334)
 * @param collectionName The Qdrant collection to write to (maps to document source/type)
 * @param vectorSize The dimensionality of embedding vectors (default: 1024 for BGE-M3)
 *
 * @see org.datamancy.pipeline.embedding.EmbeddingScheduler
 * @see VectorDocument
 */
class QdrantSink(
    qdrantHost: String,
    qdrantPort: Int,
    private val collectionName: String,
    private val vectorSize: Int = 1024,
    private val apiKey: String? = null
) : Sink<VectorDocument> {
    override val name = "QdrantSink[$collectionName]"

    /** gRPC client for high-performance binary communication with Qdrant */
    private val client = QdrantClient(
        QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false)
            .apply {
                if (!apiKey.isNullOrBlank()) {
                    withApiKey(apiKey)
                }
            }
            .usePlaintext()
            .build()
    )

    init {
        ensureCollection()
    }

    /**
     * Ensures the Qdrant collection exists, creating it if necessary.
     *
     * **Collection Configuration:**
     * - Vector size: 1024 dimensions (matches BGE-M3 embedding model output)
     * - Distance metric: Cosine similarity (optimal for semantic search)
     * - Auto-creation: If collection doesn't exist, it's created on first use
     *
     * **Why Cosine Similarity:**
     * Cosine similarity measures the angle between vectors, making it ideal for text embeddings
     * where magnitude is less important than direction. This enables semantic search - documents
     * with similar meaning have vectors pointing in similar directions, regardless of document length.
     *
     * **Collection Lifecycle:**
     * - Called once during sink initialization (in init block)
     * - Gracefully handles ALREADY_EXISTS errors (idempotent operation)
     * - Fails fast if Qdrant is unreachable or configuration is invalid
     *
     * @throws RuntimeException if collection creation times out (30s)
     * @throws Exception if collection creation fails for other reasons
     */
    private fun ensureCollection() {
        try {
            // Attempt to create collection (will fail gracefully if it already exists)
            try {
                logger.info { "Creating Qdrant collection: $collectionName" }
                client.createCollectionAsync(
                    collectionName,
                    VectorParams.newBuilder()
                        .setSize(vectorSize.toLong())
                        .setDistance(Distance.Cosine)
                        .build()
                ).get(QDRANT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                logger.info { "Created Qdrant collection: $collectionName" }
            } catch (e: Exception) {
                // Collection already exists - this is expected and safe
                if (e.message?.contains("ALREADY_EXISTS", ignoreCase = true) == true ||
                    e.message?.contains("already exists", ignoreCase = true) == true) {
                    logger.debug { "Qdrant collection already exists: $collectionName" }
                } else {
                    // Unexpected error - propagate it
                    throw e
                }
            }
        } catch (e: TimeoutException) {
            logger.error { "Timeout ensuring collection exists after ${QDRANT_TIMEOUT_SECONDS}s" }
            throw RuntimeException("Qdrant collection setup timeout", e)
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure collection exists: ${e.message}" }
            throw e
        }
    }

    /**
     * Writes a single vector document to Qdrant using deterministic ID and upsert semantics.
     *
     * **ID Generation:**
     * Document IDs are converted to deterministic UUIDs via SHA-256 hashing. This ensures that
     * re-processing the same document (e.g., after a retry) produces the same Qdrant point ID,
     * enabling idempotent writes.
     *
     * **Upsert Behavior:**
     * - If point ID doesn't exist: Creates new vector entry
     * - If point ID exists: Updates vector and metadata in-place
     * - No duplicates: Same document always maps to same point ID
     *
     * **Metadata Storage:**
     * All metadata fields (title, URL, source, bookstack_url, etc.) are stored as Qdrant payload,
     * making them available in search results without requiring joins to PostgreSQL.
     *
     * **Called by EmbeddingScheduler:**
     * After generating an embedding vector via the Embedding Service, EmbeddingScheduler calls this
     * method to persist the vector to Qdrant. On success, the document status in PostgreSQL is
     * updated from IN_PROGRESS to COMPLETED.
     *
     * @param item The vector document containing ID, embedding vector, and metadata
     * @throws RuntimeException if write operation times out (30s)
     * @throws Exception for other Qdrant API errors
     */
    override suspend fun write(item: VectorDocument) {
        try {
            // Generate deterministic UUID from document ID (enables idempotent writes)
            val deterministicId = item.id.toDeterministicUUID()

            val point = PointStruct.newBuilder()
                .setId(id(deterministicId.mostSignificantBits xor deterministicId.leastSignificantBits))
                .setVectors(vectors(item.vector.toList()))
                .putAllPayload(item.metadata.mapValues { (_, v) -> value(v.toString()) })
                .build()

            // Upsert: Create if new, update if exists (idempotent operation)
            client.upsertAsync(collectionName, listOf(point)).get(QDRANT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            logger.debug { "Wrote vector ${item.id} to $collectionName" }
        } catch (e: TimeoutException) {
            logger.error { "Timeout writing to Qdrant after ${QDRANT_TIMEOUT_SECONDS}s: ${item.id}" }
            throw RuntimeException("Qdrant write timeout", e)
        } catch (e: Exception) {
            logger.error(e) { "Failed to write to Qdrant: ${e.message}" }
            throw e
        }
    }

    /**
     * Writes a batch of vector documents to Qdrant in a single gRPC request.
     *
     * **Performance Benefits:**
     * Batch writes reduce network overhead by sending multiple points in one gRPC call instead of
     * individual requests. This is significantly faster for bulk operations (e.g., initial index
     * population or processing large embedding batches).
     *
     * **Batch Size Considerations:**
     * EmbeddingScheduler uses a configurable batch size (default: 50 documents) to balance throughput
     * and memory usage. Larger batches improve performance but increase memory consumption during
     * vector generation.
     *
     * **Idempotency:**
     * Like single writes, batch writes use deterministic UUIDs and upsert semantics, ensuring safe
     * retry behavior even if the batch is processed multiple times.
     *
     * @param items List of vector documents to write
     * @throws RuntimeException if batch write times out (30s)
     * @throws Exception for other Qdrant API errors
     */
    override suspend fun writeBatch(items: List<VectorDocument>) {
        try {
            val points = items.map { item ->
                // Generate deterministic UUID for each document (enables idempotent batch writes)
                val deterministicId = item.id.toDeterministicUUID()

                PointStruct.newBuilder()
                    .setId(id(deterministicId.mostSignificantBits xor deterministicId.leastSignificantBits))
                    .setVectors(vectors(item.vector.toList()))
                    .putAllPayload(item.metadata.mapValues { (_, v) -> value(v.toString()) })
                    .build()
            }

            // Batch upsert: More efficient than individual writes for bulk operations
            client.upsertAsync(collectionName, points).get(QDRANT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            logger.info { "Wrote ${items.size} vectors to $collectionName" }
        } catch (e: TimeoutException) {
            logger.error { "Timeout writing batch to Qdrant after ${QDRANT_TIMEOUT_SECONDS}s: ${items.size} items" }
            throw RuntimeException("Qdrant batch write timeout", e)
        } catch (e: Exception) {
            logger.error(e) { "Failed to write batch to Qdrant: ${e.message}" }
            throw e
        }
    }

    /**
     * Performs a health check by listing Qdrant collections.
     *
     * This lightweight operation verifies that:
     * - Qdrant gRPC endpoint is reachable
     * - Authentication/authorization is working
     * - gRPC client is properly configured
     *
     * Used by monitoring systems and startup checks to detect Qdrant outages.
     *
     * @return true if Qdrant is healthy and responsive, false otherwise
     */
    override suspend fun healthCheck(): Boolean {
        return try {
            client.listCollectionsAsync().get(QDRANT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            true
        } catch (e: TimeoutException) {
            logger.error { "Qdrant health check timeout after ${QDRANT_TIMEOUT_SECONDS}s" }
            false
        } catch (e: Exception) {
            logger.error(e) { "Qdrant health check failed: ${e.message}" }
            false
        }
    }
}

/**
 * Represents a document with its embedding vector and metadata for storage in Qdrant.
 *
 * **Data Model:**
 * - `id`: Unique document identifier (typically source-specific, e.g., "cve-2024-1234")
 * - `vector`: 1024-dimensional float array from BGE-M3 embedding model
 * - `metadata`: Key-value pairs (title, URL, source, collection, bookstack_url, etc.)
 *
 * **Equality Semantics:**
 * Two VectorDocuments are equal if they have the same ID, regardless of vector or metadata.
 * This enables deduplication and update detection in the pipeline.
 *
 * **Usage:**
 * Created by EmbeddingScheduler after generating embeddings, then passed to QdrantSink for
 * persistence. The metadata fields are stored as Qdrant payload and returned in search results.
 *
 * @property id Unique document identifier
 * @property vector 1024-dimensional embedding vector (BGE-M3 model output)
 * @property metadata Document metadata (title, URL, source, etc.)
 */
data class VectorDocument(
    val id: String,
    val vector: FloatArray,
    val metadata: Map<String, Any>
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VectorDocument
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
