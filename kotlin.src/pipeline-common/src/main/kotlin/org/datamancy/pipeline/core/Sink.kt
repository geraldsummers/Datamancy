package org.datamancy.pipeline.core

/**
 * Interface for pipeline output destinations that persist or publish processed data.
 *
 * Sinks are the final stage of the pipeline, responsible for writing data to external systems:
 * - PostgreSQL (document_staging table) - Staging area with status tracking (PENDING/COMPLETED/FAILED)
 * - Qdrant (gRPC:6334) - Vector database for semantic search
 * - BookStack API (HTTP) - Knowledge base publishing
 *
 * Integration points:
 * - PostgreSQL: DocumentStagingSink writes with embedding_status=PENDING for async processing
 * - Qdrant: QdrantSink stores 1024-dimensional vectors in collection-specific indexes
 * - BookStack: BookStackSink creates pages organized by book/chapter hierarchy
 * - EmbeddingScheduler: Polls PostgreSQL for PENDING documents and updates status to COMPLETED
 * - Search-Service: Queries Qdrant and PostgreSQL for hybrid search results
 *
 * Design rationale:
 * - Batch writes improve throughput for high-volume ingestion (e.g., 100k+ Wikipedia articles)
 * - Health checks enable circuit breaker patterns when external services are unavailable
 * - Suspend functions allow non-blocking I/O for database and HTTP operations
 */
interface Sink<T> {
    /**
     * Writes a single item to the sink.
     *
     * @param item The item to persist or publish
     * @throws Exception if write fails (caller should handle retry logic)
     */
    suspend fun write(item: T)

    /**
     * Writes multiple items in a single batch for improved throughput.
     *
     * Default implementation writes items sequentially. Implementations should override
     * to use batch APIs (e.g., PostgreSQL COPY, Qdrant batch upsert).
     *
     * @param items The items to write in a batch
     */
    suspend fun writeBatch(items: List<T>) {
        items.forEach { write(it) }
    }

    /**
     * Unique identifier for this sink, used for logging and metrics.
     */
    val name: String

    /**
     * Checks if the sink's external dependency is reachable and accepting writes.
     *
     * @return true if the sink is healthy and ready to accept writes
     */
    suspend fun healthCheck(): Boolean
}
