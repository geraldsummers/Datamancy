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
 * Production runner for StandardizedSource implementations with scheduling and staging integration.
 *
 * StandardizedRunner orchestrates the complete ingestion workflow:
 * 1. SourceScheduler triggers runs based on resync/backfill strategy
 * 2. Source fetches items with run metadata
 * 3. DeduplicationStore filters out previously seen content
 * 4. Chunker splits large documents (if needsChunking() is true)
 * 5. DocumentStagingStore persists to PostgreSQL with status=PENDING
 * 6. EmbeddingScheduler (separate process) polls for PENDING docs
 * 7. Embedder generates vectors via Embedding Service
 * 8. QdrantSink stores vectors and updates status=COMPLETED
 * 9. BookStackWriter (separate process) polls for COMPLETED docs
 *
 * Integration points:
 * - PostgreSQL (document_staging table): Staging buffer between ingestion and embedding
 * - DeduplicationStore: Content hash check prevents re-ingesting unchanged documents
 * - SourceScheduler: Cron-like scheduling with metadata tracking for idempotent reruns
 * - Embedding Service: Async processing decouples ingestion speed from embedding latency
 * - Qdrant: Collection name maps to source type (rss_feeds, cve, wikipedia, etc.)
 *
 * Design rationale:
 * - Batch size of 100 balances PostgreSQL insert performance vs memory usage
 * - status=PENDING enables fault-tolerant async processing (EmbeddingScheduler can restart)
 * - Chunking happens before staging to preserve chunk metadata for search results
 * - Bandwidth metrics track ingestion throughput for monitoring dashboards (Grafana)
 */
class StandardizedRunner<T : Chunkable>(
    private val source: StandardizedSource<T>,
    private val collectionName: String,
    private val stagingStore: DocumentStagingStore,
    private val dedupStore: DeduplicationStore,
    private val metadataStore: SourceMetadataStore,
    private val scheduler: SourceScheduler? = null
) {
    private val sourceName = source.name

    /**
     * Executes the source with scheduling, deduplication, chunking, and staging.
     *
     * Run phases:
     * 1. Scheduler determines run type (INITIAL_PULL, RESYNC, BACKFILL)
     * 2. Source.fetchForRun() called with run metadata
     * 3. Items buffered (100) for efficient batch processing
     * 4. Each item checked for duplicates (DeduplicationStore)
     * 5. Non-duplicate items chunked if needed (Chunker)
     * 6. Chunks converted to StagedDocument (status=PENDING)
     * 7. Batches written to PostgreSQL (stagingStore.stageBatch)
     * 8. Metrics recorded (items processed, failed, bandwidth)
     *
     * Failure handling:
     * - Individual item failures logged, don't stop run
     * - Run-level failures recorded in SourceMetadataStore for alerting
     * - Scheduler automatically retries failed runs (exponential backoff)
     */
    suspend fun run() {
        val actualScheduler = scheduler ?: SourceScheduler(
            sourceName = sourceName,
            resyncStrategy = source.resyncStrategy()
        )

        actualScheduler.schedule { metadata ->

            var processed = 0
            var failed = 0
            var deduplicated = 0
            var totalBytes = 0L
            val startTime = System.currentTimeMillis()

            try {
                
                val batchSize = 100
                val batch = mutableListOf<StagedDocument>()

                
                source.fetchForRun(metadata)
                    .buffer(100)  
                    .collect { item ->
                        try {
                            
                            val stagedDocs = processItemToStaging(item, dedupStore)

                            if (stagedDocs.isEmpty()) {
                                deduplicated++
                            } else {
                                
                                stagedDocs.forEach { doc ->
                                    totalBytes += doc.text.toByteArray(Charsets.UTF_8).size
                                }
                                batch.addAll(stagedDocs)
                            }

                            
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

                
                if (batch.isNotEmpty()) {
                    stagingStore.stageBatch(batch)
                    processed += batch.size
                }

                val durationMs = System.currentTimeMillis() - startTime
                val bandwidthMB = totalBytes / (1024.0 * 1024.0)
                val throughputMBps = if (durationMs > 0) (bandwidthMB / (durationMs / 1000.0)) else 0.0

                logger.info { "[$sourceName] Completed: $processed staged, $failed failed (${durationMs/1000}s, %.2f MB/s)".format(throughputMBps) }

                
                metadataStore.recordSuccess(
                    sourceName = sourceName,
                    itemsProcessed = processed.toLong(),
                    itemsFailed = failed.toLong()
                )

            } catch (e: Exception) {
                logger.error(e) { "[$sourceName] ${metadata.runType} failed: ${e.message}" }
                metadataStore.recordFailure(sourceName)
                throw e  
            }
        }
    }

    /**
     * Converts a Chunkable item to StagedDocument(s) after deduplication check.
     *
     * Deduplication logic:
     * - Uses item.getId() to generate content hash
     * - DeduplicationStore checks PostgreSQL for existing hash
     * - If duplicate found, returns empty list (item skipped)
     * - If new, marks hash in DeduplicationStore to prevent future duplicates
     *
     * Chunking decision:
     * - If source.needsChunking() is false: Single StagedDocument created
     * - If source.needsChunking() is true: Multiple StagedDocuments (one per chunk)
     *
     * @return List of StagedDocument (empty if duplicate, 1+ if new)
     */
    private suspend fun processItemToStaging(item: T, dedupStore: DeduplicationStore): List<StagedDocument> {
        val itemId = item.getId()
        val hash = itemId.hashCode().toString()

        if (dedupStore.checkAndMark(hash, itemId)) {
            logger.debug { "[$sourceName] Skipping duplicate: $itemId" }
            return emptyList()
        }

        if (source.needsChunking()) {
            return processWithChunkingToStaging(item)
        } else {
            return listOf(processSingleToStaging(item))
        }
    }

    /**
     * Converts a non-chunked item to a StagedDocument ready for PostgreSQL.
     *
     * Field mappings:
     * - id: From item.getId() (must be globally unique)
     * - source: Source name for filtering in Search-Service
     * - collection: Qdrant collection name (e.g., "rss_feeds", "cve")
     * - text: Plain text for embedding generation
     * - metadata: Preserved for search result enrichment
     * - embeddingStatus: PENDING signals EmbeddingScheduler to process
     * - chunkIndex/totalChunks: null for non-chunked items
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
     * Splits a large item into multiple StagedDocuments using the source's Chunker.
     *
     * Chunking process:
     * 1. Extract full text via item.toText()
     * 2. Pass to Chunker (default: 8192 tokens with 20% overlap)
     * 3. Create ChunkedItem wrapper for each chunk
     * 4. Convert to StagedDocument with chunk metadata
     *
     * Chunk metadata:
     * - id: "{originalId}-chunk-{index}" for unique identification
     * - chunkIndex/totalChunks: Enables reassembly in Search-Service
     * - startPos/endPos: Character positions in original text
     * - is_chunked=true: Signals to downstream processors
     *
     * Integration with Embedding Service:
     * - Each chunk embedded independently (respects 8192 token limit)
     * - 20% overlap ensures semantic continuity across boundaries
     * - Metadata preserved so LLM knows chunk context
     */
    private suspend fun processWithChunkingToStaging(item: T): List<StagedDocument> {
        val text = item.toText()
        val chunker = source.chunker()
        val chunks = chunker.process(text)

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
