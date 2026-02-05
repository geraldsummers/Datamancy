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


class EmbeddingScheduler(
    private val stagingStore: DocumentStagingStore,
    private val embedder: Embedder,
    private val qdrantSinks: Map<String, QdrantSink>,  
    private val batchSize: Int = 50,                   
    private val pollInterval: Int = 10,                
    private val maxConcurrentEmbeddings: Int = 10,     
    private val maxRetries: Int = 3                    
) {

    
    suspend fun start() {
        logger.info { "🚀 Embedding scheduler starting..." }
        logger.info { "  Batch size: $batchSize docs" }
        logger.info { "  Poll interval: $pollInterval seconds" }
        logger.info { "  Max concurrent embeddings: $maxConcurrentEmbeddings" }
        logger.info { "  Max retries: $maxRetries" }

        while (true) {
            try {
                
                val stats = stagingStore.getStats()
                val pending = stats["pending"] ?: 0

                if (pending > 0) {
                    logger.debug { "Processing batch..." }
                    processBatch()
                } else {
                    logger.debug { "No pending documents, sleeping..." }
                }

            } catch (e: Exception) {
                logger.error(e) { "Scheduler error: ${e.message}" }
            }

            delay(pollInterval.seconds)
        }
    }

    
    private suspend fun processBatch() {
        val pendingDocs = stagingStore.getPendingBatch(batchSize)

        if (pendingDocs.isEmpty()) {
            return
        }

        logger.debug { "🔄 Processing batch of ${pendingDocs.size} documents" }

        
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

    
    private suspend fun processDocument(doc: org.datamancy.pipeline.storage.StagedDocument, sink: QdrantSink) {
        try {
            
            stagingStore.updateStatus(doc.id, EmbeddingStatus.IN_PROGRESS)

            
            logger.debug { "Embedding document: ${doc.id}" }
            val vector = embedder.process(doc.text)

            
            val enrichedMetadata = if (doc.bookstackUrl != null) {
                doc.metadata + ("bookstack_url" to doc.bookstackUrl)
            } else {
                doc.metadata
            }

            val vectorDoc = VectorDocument(
                id = doc.id,
                vector = vector,
                metadata = enrichedMetadata
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
