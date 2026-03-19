package org.datamancy.pipeline.embedding

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.sinks.VectorDocument
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.EmbeddingStatus
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}


class EmbeddingScheduler(
    private val stagingStore: DocumentStagingStore,
    private val embedder: Embedder,
    private val qdrantSinks: Map<String, QdrantSink>,
    private val batchSize: Int = 50,
    private val pollInterval: Int = 10,
    private val maxConcurrentEmbeddings: Int = 10,
    private val maxRetries: Int = 3,
    private val embeddingRequestBatchSize: Int = 32,
    private val maxConcurrentBatchRequests: Int = 4
) {


    suspend fun start() {
        logger.info { "🚀 Embedding scheduler starting..." }
        logger.info { "  Batch size: $batchSize docs" }
        logger.info { "  Poll interval: $pollInterval seconds" }
        logger.info { "  Max concurrent embeddings: $maxConcurrentEmbeddings" }
        logger.info { "  Max retries: $maxRetries" }
        logger.info { "  Embedding request batch size: $embeddingRequestBatchSize docs" }
        logger.info { "  Max concurrent embedding batches: $maxConcurrentBatchRequests" }

        while (true) {
            var shouldSleep = true
            try {
                val processed = processBatch()
                shouldSleep = processed == 0
                if (processed == 0) {
                    logger.debug { "No pending documents, sleeping..." }
                }
            } catch (e: Exception) {
                logger.error(e) { "Scheduler error: ${e.message}" }
                shouldSleep = true
            }

            if (shouldSleep) {
                delay(pollInterval.seconds)
            }
        }
    }

    
    private suspend fun processBatch(): Int {
        val pendingDocs = stagingStore.getPendingBatch(batchSize)

        if (pendingDocs.isEmpty()) {
            return 0
        }

        logger.debug { "🔄 Processing batch of ${pendingDocs.size} documents" }

        val chunkSize = embeddingRequestBatchSize.coerceAtLeast(1)
        val semaphore = Semaphore(maxConcurrentBatchRequests.coerceAtLeast(1))
        coroutineScope {
            pendingDocs.chunked(chunkSize).map { chunk ->
                async {
                    semaphore.withPermit {
                        processDocumentBatch(chunk)
                    }
                }
            }.awaitAll()
        }

        return pendingDocs.size
    }

    private suspend fun processDocumentBatch(docs: List<org.datamancy.pipeline.storage.StagedDocument>) {
        if (docs.isEmpty()) return

        val invalidDocs = docs.filter { it.text.isBlank() }
        invalidDocs.forEach { doc ->
            stagingStore.updateStatus(
                id = doc.id,
                newStatus = EmbeddingStatus.FAILED,
                errorMessage = "Document text is empty",
                incrementRetry = false
            )
        }

        val validDocs = docs.filterNot { it.text.isBlank() }
        if (validDocs.isEmpty()) return

        try {
            // Mark entire batch as in-progress before issuing the embedding request.
            val batchIds = validDocs.map { it.id }
            if (validDocs.any { it.embeddingStatus != EmbeddingStatus.IN_PROGRESS }) {
                stagingStore.updateStatusBatch(batchIds, EmbeddingStatus.IN_PROGRESS)
            }

            val vectors = embedder.processBatch(validDocs.map { it.text })
            if (vectors.size != validDocs.size) {
                throw IllegalStateException(
                    "Embedding size mismatch: got ${vectors.size} vectors for ${validDocs.size} docs"
                )
            }

            val vectorsByCollection = linkedMapOf<String, MutableList<VectorDocument>>()
            validDocs.forEachIndexed { index, doc ->
                val enrichedMetadata = if (doc.bookstackUrl != null) {
                    doc.metadata + ("bookstack_url" to doc.bookstackUrl)
                } else {
                    doc.metadata
                }
                val safeMetadata: Map<String, Any> = enrichedMetadata.mapValues { it.value ?: "" }
                val vectorDoc = VectorDocument(
                    id = doc.id,
                    vector = vectors[index],
                    metadata = safeMetadata
                )
                vectorsByCollection.getOrPut(doc.collection) { mutableListOf() }.add(vectorDoc)
            }

            vectorsByCollection.forEach { (collection, items) ->
                val sink = qdrantSinks[collection]
                if (sink == null) {
                    throw IllegalStateException("No Qdrant sink configured for collection: $collection")
                }
                sink.writeBatch(items)
            }

            stagingStore.updateStatusBatch(batchIds, EmbeddingStatus.COMPLETED)
        } catch (e: Exception) {
            logger.warn(e) {
                "Batched processing failed for ${validDocs.size} docs, falling back to single-doc mode"
            }

            // Fallback path isolates bad inputs and keeps throughput moving.
            val fallbackSemaphore = Semaphore(maxConcurrentEmbeddings.coerceAtLeast(1))
            coroutineScope {
                validDocs.map { doc ->
                    async {
                        fallbackSemaphore.withPermit {
                            val sink = qdrantSinks[doc.collection]
                            if (sink == null) {
                                logger.error { "No Qdrant sink configured for collection: ${doc.collection}" }
                                stagingStore.updateStatus(
                                    id = doc.id,
                                    newStatus = EmbeddingStatus.FAILED,
                                    errorMessage = "No sink configured for collection ${doc.collection}"
                                )
                            } else {
                                processDocument(doc, sink)
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    
    private suspend fun processDocument(doc: org.datamancy.pipeline.storage.StagedDocument, sink: QdrantSink) {
        try {
            if (doc.text.isBlank()) {
                stagingStore.updateStatus(
                    id = doc.id,
                    newStatus = EmbeddingStatus.FAILED,
                    errorMessage = "Document text is empty",
                    incrementRetry = false
                )
                return
            }

            // For Postgres, getPendingBatch atomically claims rows as IN_PROGRESS.
            // Keep this fallback for non-Postgres test environments.
            if (doc.embeddingStatus != EmbeddingStatus.IN_PROGRESS) {
                stagingStore.updateStatus(doc.id, EmbeddingStatus.IN_PROGRESS)
            }

            
            logger.debug { "Embedding document: ${doc.id}" }
            val vector = embedder.process(doc.text)

            
            val enrichedMetadata = if (doc.bookstackUrl != null) {
                doc.metadata + ("bookstack_url" to doc.bookstackUrl)
            } else {
                doc.metadata
            }

            val safeMetadata: Map<String, Any> = enrichedMetadata.mapValues { it.value ?: "" }

            val vectorDoc = VectorDocument(
                id = doc.id,
                vector = vector,
                metadata = safeMetadata
            )

            
            logger.debug { "Inserting to Qdrant: ${doc.id}" }
            sink.write(vectorDoc)

            
            stagingStore.updateStatus(doc.id, EmbeddingStatus.COMPLETED)
            logger.debug { "✓ Completed: ${doc.id}" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to process ${doc.id}: ${e.message}" }

            
            if (doc.retryCount >= maxRetries) {
                logger.error { "Max retries exceeded for ${doc.id}, marking as failed" }
                stagingStore.updateStatus(
                    id = doc.id,
                    newStatus = EmbeddingStatus.FAILED,
                    errorMessage = "Max retries exceeded: ${e.message}",
                    incrementRetry = false
                )
            } else {
                
                val backoffSeconds = 2.0.pow(doc.retryCount.toDouble()).toLong()
                logger.warn { "Retry ${doc.retryCount + 1}/$maxRetries for ${doc.id} after ${backoffSeconds}s backoff" }

                
                delay(backoffSeconds * 1000)

                
                stagingStore.updateStatus(
                    id = doc.id,
                    newStatus = EmbeddingStatus.PENDING,
                    errorMessage = e.message,
                    incrementRetry = true
                )
            }
        }
    }

    
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

    
    suspend fun getStatsBySource(source: String): Map<String, Long> {
        return stagingStore.getStatsBySource(source)
    }
}
