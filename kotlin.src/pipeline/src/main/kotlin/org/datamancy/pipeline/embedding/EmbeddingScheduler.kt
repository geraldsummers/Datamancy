package org.datamancy.pipeline.embedding

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.sinks.VectorDocument
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.EmbeddingStatus
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Embedding scheduler - decoupled from scraping
 *
 * Monitors ClickHouse for pending documents and processes them through embedding service
 * at a controlled rate. This prevents backpressure from slow embedding blocking scraping.
 *
 * Architecture:
 * 1. Scraper writes raw docs to ClickHouse (fast, unlimited capacity)
 * 2. Scheduler periodically checks for pending docs
 * 3. Pulls batches based on embedding service capacity
 * 4. Embeds and inserts to Qdrant
 * 5. Updates status in ClickHouse (PENDING → IN_PROGRESS → COMPLETED)
 *
 * Benefits:
 * - Scraping never blocks on embedding
 * - Resumable (restart continues from checkpoint)
 * - Observable (query ClickHouse for queue depth)
 * - Rate-limited (respects embedding service capacity)
 */
class EmbeddingScheduler(
    private val stagingStore: DocumentStagingStore,
    private val embedder: Embedder,
    private val qdrantSinks: Map<String, QdrantSink>,  // collection -> sink
    private val batchSize: Int = 50,                   // Docs per batch
    private val pollInterval: Int = 10,                // Seconds between checks
    private val maxConcurrentEmbeddings: Int = 10,     // Parallel embedding requests
    private val maxRetries: Int = 3                    // Max retry attempts per document
) {

    /**
     * Start the scheduler loop
     * Runs forever, checking for pending docs and processing them
     */
    suspend fun start() {
        logger.info { "🚀 Embedding scheduler starting..." }
        logger.info { "  Batch size: $batchSize docs" }
        logger.info { "  Poll interval: $pollInterval seconds" }
        logger.info { "  Max concurrent embeddings: $maxConcurrentEmbeddings" }
        logger.info { "  Max retries: $maxRetries" }

        while (true) {
            try {
                // Get current stats
                val stats = stagingStore.getStats()
                val pending = stats["pending"] ?: 0
                val inProgress = stats["in_progress"] ?: 0
                val completed = stats["completed"] ?: 0
                val failed = stats["failed"] ?: 0

                if (pending > 0) {
                    logger.info { "📊 Queue status: $pending pending, $inProgress in-progress, $completed completed, $failed failed" }
                    processBatch()
                } else {
                    logger.debug { "💤 No pending documents, sleeping..." }
                }

            } catch (e: Exception) {
                logger.error(e) { "Scheduler error: ${e.message}" }
            }

            delay(pollInterval.seconds)
        }
    }

    /**
     * Process one batch of pending documents
     */
    private suspend fun processBatch() {
        val pendingDocs = stagingStore.getPendingBatch(batchSize)

        if (pendingDocs.isEmpty()) {
            return
        }

        logger.info { "🔄 Processing batch of ${pendingDocs.size} documents" }

        // Group by collection for efficient sink routing
        val byCollection = pendingDocs.groupBy { it.collection }

        byCollection.forEach { (collection, docs) ->
            val sink = qdrantSinks[collection]
            if (sink == null) {
                logger.error { "No Qdrant sink configured for collection: $collection" }
                docs.forEach { doc ->
                    stagingStore.updateStatus(
                        id = doc.id,
                        newStatus = EmbeddingStatus.FAILED,
                        errorMessage = "No sink configured for collection $collection"
                    )
                }
                return@forEach
            }

            // Process docs in parallel (up to maxConcurrentEmbeddings)
            coroutineScope {
                docs.chunked(maxConcurrentEmbeddings).forEach { chunk ->
                    chunk.map { doc ->
                        async {
                            processDocument(doc, sink)
                        }
                    }.awaitAll()
                }
            }
        }
    }

    /**
     * Process a single document: embed → insert → update status
     */
    private suspend fun processDocument(doc: org.datamancy.pipeline.storage.StagedDocument, sink: QdrantSink) {
        try {
            // Mark as in-progress
            stagingStore.updateStatus(doc.id, EmbeddingStatus.IN_PROGRESS)

            // Embed the text
            logger.debug { "Embedding document: ${doc.id}" }
            val vector = embedder.process(doc.text)

            // Create vector document
            val vectorDoc = VectorDocument(
                id = doc.id,
                vector = vector,
                metadata = doc.metadata
            )

            // Insert to Qdrant
            logger.debug { "Inserting to Qdrant: ${doc.id}" }
            sink.write(vectorDoc)

            // Mark as completed
            stagingStore.updateStatus(doc.id, EmbeddingStatus.COMPLETED)
            logger.debug { "✓ Completed: ${doc.id}" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to process ${doc.id}: ${e.message}" }

            // Check retry limit
            if (doc.retryCount >= maxRetries) {
                logger.error { "Max retries exceeded for ${doc.id}, marking as failed" }
                stagingStore.updateStatus(
                    id = doc.id,
                    newStatus = EmbeddingStatus.FAILED,
                    errorMessage = "Max retries exceeded: ${e.message}",
                    incrementRetry = false
                )
            } else {
                // Exponential backoff: 2^retryCount seconds (1s, 2s, 4s, 8s, etc.)
                val backoffSeconds = 2.0.pow(doc.retryCount.toDouble()).toLong()
                logger.warn { "Retry ${doc.retryCount + 1}/$maxRetries for ${doc.id} after ${backoffSeconds}s backoff" }

                // Wait before marking as pending again
                delay(backoffSeconds * 1000)

                // Mark for retry after backoff
                stagingStore.updateStatus(
                    id = doc.id,
                    newStatus = EmbeddingStatus.PENDING,
                    errorMessage = e.message,
                    incrementRetry = true
                )
            }
        }
    }

    /**
     * Get queue statistics for monitoring
     */
    suspend fun getStats(): Map<String, Any> {
        val stats = stagingStore.getStats()
        return mapOf(
            "pending" to (stats["pending"] ?: 0),
            "in_progress" to (stats["in_progress"] ?: 0),
            "completed" to (stats["completed"] ?: 0),
            "failed" to (stats["failed"] ?: 0),
            "batch_size" to batchSize,
            "poll_interval_seconds" to pollInterval,
            "max_concurrent" to maxConcurrentEmbeddings
        )
    }

    /**
     * Get stats by source for detailed monitoring
     */
    suspend fun getStatsBySource(source: String): Map<String, Long> {
        return stagingStore.getStatsBySource(source)
    }
}
