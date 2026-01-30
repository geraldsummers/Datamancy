package org.datamancy.pipeline.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.scheduling.SourceScheduler
import org.datamancy.pipeline.sinks.BookStackDocument
import org.datamancy.pipeline.sinks.BookStackSink
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.sinks.VectorDocument
import org.datamancy.pipeline.storage.DeduplicationStore
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.EmbeddingStatus
import org.datamancy.pipeline.storage.SourceMetadataStore
import org.datamancy.pipeline.storage.StagedDocument
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Standardized runner for pipeline sources
 *
 * ALL sources run through this - NO MORE CUSTOM LOOPS IN MAIN.KT!
 *
 * NEW ARCHITECTURE (Decoupled Scraping/Embedding):
 * - Scraping → ClickHouse staging (fast, unlimited buffering)
 * - Separate scheduler pulls from ClickHouse → Embedding → Qdrant
 * - Benefits: no backpressure, resumable, observable, rate-limited
 *
 * This handles:
 * - Scheduling (initial pull + resync)
 * - Chunking (if source needs it)
 * - Staging to ClickHouse (replaces direct embedding)
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
 * runner.run()  // Scrapes and stages to ClickHouse
 * ```
 *
 * Then separately run EmbeddingScheduler to process staged docs.
 */
class StandardizedRunner<T : Chunkable>(
    private val source: StandardizedSource<T>,
    private val collectionName: String,              // Target Qdrant collection
    private val stagingStore: DocumentStagingStore,  // ClickHouse staging
    private val dedupStore: DeduplicationStore,
    private val metadataStore: SourceMetadataStore,
    private val bookStackSink: BookStackSink? = null,  // Optional BookStack sink
    private val scheduler: SourceScheduler? = null     // Allow injection for testing
) {
    private val sourceName = source.name

    /**
     * Run the source with standardized scheduling, chunking, staging
     * This is the ONLY way sources should run - enforces consistency
     *
     * NEW: Instead of embed→insert, we stage→ClickHouse
     * Separate EmbeddingScheduler handles embedding and Qdrant insertion
     */
    suspend fun run() {
        logger.info { "[$sourceName] Starting standardized runner (NEW DECOUPLED ARCHITECTURE)" }
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
                // Fetch from source (handles initial vs resync)
                // Process items in parallel batches for maximum CPU utilization
                val batchSize = 50
                val batch = mutableListOf<T>()

                source.fetchForRun(metadata)
                    .buffer(100)
                    .collect { item ->
                        batch.add(item)

                        // When batch is full, process in parallel
                        if (batch.size >= batchSize) {
                            val currentBatch = batch.toList()
                            batch.clear()

                            // Process entire batch in parallel using coroutineScope
                            coroutineScope {
                                currentBatch.map { batchItem ->
                                    async {
                                        try {
                                            val stagedDocs = processItemToStaging(batchItem, dedupStore)
                                            Triple(batchItem, stagedDocs, null)
                                        } catch (e: Exception) {
                                            Triple(batchItem, emptyList<StagedDocument>(), e)
                                        }
                                    }
                                }.awaitAll().forEach { (batchItem, stagedDocs, error) ->
                                    if (error != null) {
                                        logger.error(error) { "[$sourceName] Failed to process item: ${error.message}" }
                                        failed++
                                    } else {
                                        // Write to ClickHouse staging
                                        if (stagedDocs.isNotEmpty()) {
                                            stagingStore.stageBatch(stagedDocs)
                                            processed += stagedDocs.size
                                        }

                                        // Write to BookStack if enabled (only for first chunk/non-chunked items)
                                        if (bookStackSink != null && stagedDocs.isNotEmpty()) {
                                            try {
                                                writeToBookStack(batchItem)
                                            } catch (e: Exception) {
                                                logger.warn(e) { "[$sourceName] Failed to write to BookStack: ${e.message}" }
                                            }
                                        }
                                    }
                                }
                            }

                            if ((processed + failed) % 100 == 0) {
                                logger.info { "[$sourceName] Progress: $processed staged, $failed failed, $deduplicated deduplicated" }
                            }
                        }
                    }

                // Process remaining items in batch
                if (batch.isNotEmpty()) {
                    coroutineScope {
                        batch.map { batchItem ->
                            async {
                                try {
                                    val stagedDocs = processItemToStaging(batchItem, dedupStore)
                                    Triple(batchItem, stagedDocs, null)
                                } catch (e: Exception) {
                                    Triple(batchItem, emptyList<StagedDocument>(), e)
                                }
                            }
                        }.awaitAll().forEach { (batchItem, stagedDocs, error) ->
                            if (error != null) {
                                logger.error(error) { "[$sourceName] Failed to process item: ${error.message}" }
                                failed++
                            } else {
                                // Write to ClickHouse staging
                                if (stagedDocs.isNotEmpty()) {
                                    stagingStore.stageBatch(stagedDocs)
                                    processed += stagedDocs.size
                                }

                                // Write to BookStack if enabled (only for first chunk/non-chunked items)
                                if (bookStackSink != null && stagedDocs.isNotEmpty()) {
                                    try {
                                        writeToBookStack(batchItem)
                                    } catch (e: Exception) {
                                        logger.warn(e) { "[$sourceName] Failed to write to BookStack: ${e.message}" }
                                    }
                                }
                            }
                        }
                    }
                }

                val durationMs = System.currentTimeMillis() - startTime
                logger.info { "[$sourceName] === ${metadata.runType} COMPLETE: $processed staged, $failed failed, $deduplicated deduplicated in ${durationMs}ms ===" }
                logger.info { "[$sourceName] Documents staged in ClickHouse - EmbeddingScheduler will process them" }

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
     * Process a single item: dedup, chunk (if needed), stage to ClickHouse
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
     * Process item without chunking (short content) - stage to ClickHouse
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
     * Process item with chunking (long content) - stage all chunks to ClickHouse
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

    /**
     * Write item to BookStack using reflection to call toBookStackDocument()
     * All Chunkable implementations should have this method
     */
    private suspend fun writeToBookStack(item: T) {
        if (bookStackSink == null) return

        try {
            // Use reflection to call toBookStackDocument() on the item
            val method = item::class.java.getMethod("toBookStackDocument")
            val bookStackDoc = method.invoke(item) as? BookStackDocument

            if (bookStackDoc != null) {
                bookStackSink.write(bookStackDoc)
                logger.debug { "[$sourceName] Wrote to BookStack: ${bookStackDoc.pageTitle}" }
            } else {
                logger.warn { "[$sourceName] toBookStackDocument() returned null for item ${item.getId()}" }
            }
        } catch (e: NoSuchMethodException) {
            logger.warn { "[$sourceName] Item ${item::class.simpleName} does not implement toBookStackDocument() - skipping BookStack write" }
        } catch (e: Exception) {
            logger.error(e) { "[$sourceName] Error writing to BookStack for item ${item.getId()}: ${e.message}" }
            throw e
        }
    }
}
