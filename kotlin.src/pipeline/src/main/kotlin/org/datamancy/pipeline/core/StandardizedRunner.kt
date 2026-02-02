package org.datamancy.pipeline.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.*
import org.datamancy.pipeline.scheduling.SourceScheduler
import org.datamancy.pipeline.storage.DeduplicationStore
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.EmbeddingStatus
import org.datamancy.pipeline.storage.SourceMetadataStore
import org.datamancy.pipeline.storage.StagedDocument
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Standardized runner for pipeline sources (SIMPLIFIED VERSION)
 *
 * ALL sources run through this - NO MORE CUSTOM LOOPS IN MAIN.KT!
 *
 * NEW ARCHITECTURE (Decoupled Scraping/Embedding):
 * - Scraping → PostgreSQL staging (fast, unlimited buffering)
 * - Separate scheduler pulls from PostgreSQL → Embedding → Qdrant
 * - Benefits: no backpressure, resumable, observable, rate-limited
 *
 * SIMPLIFICATIONS:
 * - Removed generic <T : Chunkable> (now concrete)
 * - Sequential processing instead of parallel batching with semaphores
 * - Cleaner control flow with batch insert every 100 docs
 *
 * This handles:
 * - Scheduling (initial pull + resync)
 * - Chunking (if source needs it)
 * - Staging to PostgreSQL (replaces direct embedding)
 * - Deduplication
 * - Metrics/logging
 *
 * Usage:
 * ```
 * val runner = StandardizedRunner(
 *     source = RssStandardizedSource(feedUrls),
 *     collectionName = "rss",
 *     stagingStore = stagingStore,
 *     dedupStore = dedupStore,
 *     metadataStore = metadataStore
 * )
 *
 * runner.run()  // Scrapes and stages to PostgreSQL
 * ```
 *
 * Then separately run EmbeddingScheduler to process staged docs.
 */
class StandardizedRunner<T : Chunkable>(
    private val source: StandardizedSource<T>,
    private val collectionName: String,              // Target Qdrant collection
    private val stagingStore: DocumentStagingStore,  // PostgreSQL staging
    private val dedupStore: DeduplicationStore,
    private val metadataStore: SourceMetadataStore,
    private val scheduler: SourceScheduler? = null     // Allow injection for testing
) {
    private val sourceName = source.name

    /**
     * Run the source with standardized scheduling, chunking, staging
     * This is the ONLY way sources should run - enforces consistency
     *
     * NEW: Instead of embed→insert, we stage→PostgreSQL
     * Separate EmbeddingScheduler handles embedding and Qdrant insertion
     */
    suspend fun run() {
        logger.info { "[$sourceName] Starting standardized runner (SIMPLIFIED ARCHITECTURE)" }
        logger.info { "[$sourceName] Target collection: $collectionName" }
        logger.info { "[$sourceName] Resync: ${source.resyncStrategy().describe()}" }
        logger.info { "[$sourceName] Backfill: ${source.backfillStrategy().describe()}" }
        logger.info { "[$sourceName] Chunking: ${if (source.needsChunking()) "enabled" else "disabled"}" }

        // Create scheduler from source config (or use injected one for testing)
        val actualScheduler = scheduler ?: SourceScheduler(
            sourceName = sourceName,
            resyncStrategy = source.resyncStrategy()
        )

        // Run scheduled pipeline
        actualScheduler.schedule { metadata ->
            logger.info { "[$sourceName] === ${metadata.runType} ===" }

            var processed = 0
            var failed = 0
            var deduplicated = 0
            val startTime = System.currentTimeMillis()

            try {
                // Collect documents in batches for efficient insertion
                val batchSize = 100
                val batch = mutableListOf<StagedDocument>()

                // Sequential processing (simpler than parallel with semaphores)
                source.fetchForRun(metadata)
                    .buffer(100)  // Buffer for smoother flow
                    .collect { item ->
                        try {
                            // Process item: dedup, chunk, stage
                            val stagedDocs = processItemToStaging(item, dedupStore)

                            if (stagedDocs.isEmpty()) {
                                deduplicated++
                            } else {
                                batch.addAll(stagedDocs)
                            }

                            // Batch insert when we have enough documents
                            if (batch.size >= batchSize) {
                                stagingStore.stageBatch(batch)
                                processed += batch.size
                                batch.clear()
                            }

                        } catch (e: Exception) {
                            logger.error(e) { "[$sourceName] Failed to process item: ${e.message}" }
                            failed++
                        }
                    }

                // Flush remaining batch
                if (batch.isNotEmpty()) {
                    stagingStore.stageBatch(batch)
                    processed += batch.size
                }

                val durationMs = System.currentTimeMillis() - startTime
                logger.info { "[$sourceName] === ${metadata.runType} COMPLETE: $processed staged, $failed failed, $deduplicated deduplicated in ${durationMs}ms ===" }
                logger.info { "[$sourceName] Documents staged in PostgreSQL - EmbeddingScheduler will process them" }

                // Update metadata
                metadataStore.recordSuccess(
                    sourceName = sourceName,
                    itemsProcessed = processed.toLong(),
                    itemsFailed = failed.toLong()
                )

            } catch (e: Exception) {
                logger.error(e) { "[$sourceName] ${metadata.runType} failed: ${e.message}" }
                metadataStore.recordFailure(sourceName)
                throw e  // Re-throw for scheduler's exponential backoff
            }
        }
    }

    /**
     * Process a single item: dedup, chunk (if needed), stage to PostgreSQL
     * Returns list of StagedDocuments (1 if no chunking, N if chunked)
     *
     * NEW: No embedding here! Just prepare documents for staging.
     * EmbeddingScheduler will handle embedding later.
     */
    private suspend fun processItemToStaging(item: T, dedupStore: DeduplicationStore): List<StagedDocument> {
        // Check deduplication
        val itemId = item.getId()
        val hash = itemId.hashCode().toString()

        if (dedupStore.checkAndMark(hash, itemId)) {
            logger.debug { "[$sourceName] Skipping duplicate: $itemId" }
            return emptyList()
        }

        // Chunk if needed
        if (source.needsChunking()) {
            return processWithChunkingToStaging(item)
        } else {
            return listOf(processSingleToStaging(item))
        }
    }

    /**
     * Process item without chunking (short content) - stage to PostgreSQL
     */
    private suspend fun processSingleToStaging(item: T): StagedDocument {
        val text = item.toText()

        return StagedDocument(
            id = item.getId(),
            source = sourceName,
            collection = collectionName,
            text = text,
            metadata = item.getMetadata(),
            embeddingStatus = EmbeddingStatus.PENDING,
            chunkIndex = null,
            totalChunks = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
            retryCount = 0,
            errorMessage = null
        )
    }

    /**
     * Process item with chunking (long content) - stage all chunks to PostgreSQL
     * No embedding yet! Just prepare chunks for later processing.
     */
    private suspend fun processWithChunkingToStaging(item: T): List<StagedDocument> {
        val text = item.toText()
        val chunker = source.chunker()
        val chunks = chunker.process(text)

        // Create staged documents for all chunks
        return chunks.map { chunk ->
            val chunkText = chunk.text

            val chunkedItem = ChunkedItem(
                originalItem = item,
                chunkText = chunkText,
                chunkIndex = chunk.index,
                totalChunks = chunks.size,
                startPos = chunk.startPos,
                endPos = chunk.endPos
            )

            StagedDocument(
                id = chunkedItem.getId(),
                source = sourceName,
                collection = collectionName,
                text = chunkText,
                metadata = chunkedItem.getMetadata(),
                embeddingStatus = EmbeddingStatus.PENDING,
                chunkIndex = chunk.index,
                totalChunks = chunks.size,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                retryCount = 0,
                errorMessage = null
            )
        }
    }

}
