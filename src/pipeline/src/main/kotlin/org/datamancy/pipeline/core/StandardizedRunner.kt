package org.datamancy.pipeline.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.scheduling.SourceScheduler
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.sinks.VectorDocument
import org.datamancy.pipeline.storage.DeduplicationStore
import org.datamancy.pipeline.storage.SourceMetadataStore

private val logger = KotlinLogging.logger {}

/**
 * Standardized runner for pipeline sources
 *
 * ALL sources run through this - NO MORE CUSTOM LOOPS IN MAIN.KT!
 *
 * This handles:
 * - Scheduling (initial pull + resync)
 * - Chunking (if source needs it)
 * - Embedding (with retry)
 * - Deduplication
 * - Vector storage
 * - Metrics/logging
 *
 * Usage:
 * ```
 * val runner = StandardizedRunner(
 *     source = RssStandardizedSource(feedUrls),
 *     qdrantSink = QdrantSink(...),
 *     embedder = Embedder(...),
 *     dedupStore = dedupStore,
 *     metadataStore = metadataStore
 * )
 *
 * runner.run()  // That's it! Handles everything.
 * ```
 */
class StandardizedRunner<T : Chunkable>(
    private val source: StandardizedSource<T>,
    private val qdrantSink: QdrantSink,
    private val embedder: Embedder,
    private val dedupStore: DeduplicationStore,
    private val metadataStore: SourceMetadataStore,
    private val scheduler: SourceScheduler? = null  // Allow injection for testing
) {
    private val sourceName = source.name

    /**
     * Run the source with standardized scheduling, chunking, embedding, storage
     * This is the ONLY way sources should run - enforces consistency
     */
    suspend fun run() {
        logger.info { "[$sourceName] Starting standardized runner" }
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
                                            val vectorDocs = processItem(batchItem, dedupStore)
                                            vectorDocs to null
                                        } catch (e: Exception) {
                                            emptyList<VectorDocument>() to e
                                        }
                                    }
                                }.awaitAll().forEach { (vectorDocs, error) ->
                                    if (error != null) {
                                        logger.error(error) { "[$sourceName] Failed to process item: ${error.message}" }
                                        failed++
                                    } else {
                                        vectorDocs.forEach { vectorDoc ->
                                            qdrantSink.write(vectorDoc)
                                            processed++
                                        }
                                    }
                                }
                            }

                            if ((processed + failed) % 100 == 0) {
                                logger.info { "[$sourceName] Progress: $processed processed, $failed failed, $deduplicated deduplicated" }
                            }
                        }
                    }

                // Process remaining items in batch
                if (batch.isNotEmpty()) {
                    coroutineScope {
                        batch.map { batchItem ->
                            async {
                                try {
                                    val vectorDocs = processItem(batchItem, dedupStore)
                                    vectorDocs to null
                                } catch (e: Exception) {
                                    emptyList<VectorDocument>() to e
                                }
                            }
                        }.awaitAll().forEach { (vectorDocs, error) ->
                            if (error != null) {
                                logger.error(error) { "[$sourceName] Failed to process item: ${error.message}" }
                                failed++
                            } else {
                                vectorDocs.forEach { vectorDoc ->
                                    qdrantSink.write(vectorDoc)
                                    processed++
                                }
                            }
                        }
                    }
                }

                val durationMs = System.currentTimeMillis() - startTime
                logger.info { "[$sourceName] === ${metadata.runType} COMPLETE: $processed processed, $failed failed, $deduplicated deduplicated in ${durationMs}ms ===" }

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
     * Process a single item: dedup, chunk (if needed), embed, create vectors
     * Returns list of VectorDocuments (1 if no chunking, N if chunked)
     */
    private suspend fun processItem(item: T, dedupStore: DeduplicationStore): List<VectorDocument> {
        // Check deduplication
        val itemId = item.getId()
        val hash = itemId.hashCode().toString()

        if (dedupStore.checkAndMark(hash, itemId)) {
            logger.debug { "[$sourceName] Skipping duplicate: $itemId" }
            return emptyList()
        }

        // Chunk if needed
        if (source.needsChunking()) {
            return processWithChunking(item)
        } else {
            return listOf(processSingle(item))
        }
    }

    /**
     * Process item without chunking (short content)
     */
    private suspend fun processSingle(item: T): VectorDocument {
        val text = item.toText()
        val vector = embedder.process(text)

        return VectorDocument(
            id = item.getId(),
            vector = vector,
            metadata = item.getMetadata()
        )
    }

    /**
     * Process item with chunking (long content)
     * Embeds all chunks in parallel for maximum throughput
     */
    private suspend fun processWithChunking(item: T): List<VectorDocument> = coroutineScope {
        val text = item.toText()
        val chunker = source.chunker()
        val chunks = chunker.process(text)

        // Embed all chunks in parallel
        chunks.map { chunk ->
            async {
                val chunkText = chunk.text
                val vector = embedder.process(chunkText)

                val chunkedItem = ChunkedItem(
                    originalItem = item,
                    chunkText = chunkText,
                    chunkIndex = chunk.index,
                    totalChunks = chunks.size,
                    startPos = chunk.startPos,
                    endPos = chunk.endPos
                )

                VectorDocument(
                    id = chunkedItem.getId(),
                    vector = vector,
                    metadata = chunkedItem.getMetadata()
                )
            }
        }.awaitAll()
    }
}
