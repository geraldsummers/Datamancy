package org.datamancy.pipeline.core

import kotlinx.coroutines.flow.Flow
import org.datamancy.pipeline.scheduling.BackfillStrategy
import org.datamancy.pipeline.scheduling.ResyncStrategy
import org.datamancy.pipeline.scheduling.RunMetadata
import org.datamancy.pipeline.processors.Chunker

/**
 * Extended Source interface with scheduling, chunking, and backfill support for production sources.
 *
 * StandardizedSource adds production-grade features beyond basic Source interface:
 * - Scheduled execution (daily resyncs, incremental updates)
 * - Automatic chunking for large documents (respects BGE-M3 8192 token limit)
 * - Backfill strategies for historical data ingestion
 * - Run metadata tracking (initial pull vs resync, attempt number)
 *
 * Integration points:
 * - StandardizedRunner: Orchestrates scheduled runs and handles deduplication/staging
 * - SourceScheduler: Manages resync timing and backfill execution
 * - Chunker: Splits documents with 20% overlap for semantic continuity across chunks
 * - DocumentStagingStore: Chunks stored as separate documents with chunk_index metadata
 * - Embedding Service: Each chunk embedded independently (max 8192 tokens per request)
 *
 * Design rationale:
 * - Chunking at source level ensures all sources handle large docs consistently
 * - 20% overlap preserves context across chunk boundaries for better embeddings
 * - Scheduling built-in enables long-running sources (Wikipedia, CVE) to run automatically
 * - Metadata tracking supports idempotent reruns and failure recovery
 */
interface StandardizedSource<T : Chunkable> : Source<T> {
    /**
     * Defines when this source should be re-executed for updates.
     * Default: Daily at 1:00 AM (low-traffic hours to minimize system load).
     */
    fun resyncStrategy(): ResyncStrategy = ResyncStrategy.DailyAt(hour = 1, minute = 0)

    /**
     * Defines how to fetch historical data before the initial run.
     * Required for sources with time-based data (RSS, CVE feeds).
     */
    fun backfillStrategy(): BackfillStrategy

    /**
     * Returns true if this source's items exceed embedding model token limits.
     * Large documents (Wikipedia, legal docs) should return true.
     */
    fun needsChunking(): Boolean = false

    /**
     * Provides the chunking strategy for this source.
     * Default: 8192 tokens (BGE-M3 limit) with 20% overlap for semantic continuity.
     */
    fun chunker(): Chunker = Chunker.forEmbeddingModel(tokenLimit = 8192, overlapPercent = 0.20)

    /**
     * Fetches data with run context (initial pull, resync, backfill).
     *
     * @param metadata Context about the current run (type, attempt number, first run flag)
     * @return Flow of items with metadata for deduplication and staging
     */
    suspend fun fetchForRun(metadata: RunMetadata): Flow<T>

    /**
     * Backwards-compatible fetch without run metadata.
     * Defaults to INITIAL_PULL for ad-hoc pipeline executions.
     */
    override suspend fun fetch(): Flow<T> {
        return fetchForRun(RunMetadata(
            runType = org.datamancy.pipeline.scheduling.RunType.INITIAL_PULL,
            attemptNumber = 1,
            isFirstRun = true
        ))
    }
}


/**
 * Interface for items that can be chunked for embedding models.
 *
 * All StandardizedSource items must implement Chunkable to support:
 * - Text extraction for embedding generation
 * - Unique ID generation for deduplication
 * - Metadata preservation through chunking process
 *
 * Integration points:
 * - Chunker: Extracts text via toText() for token counting and splitting
 * - DeduplicationStore: Uses getId() to check for duplicate ingestion
 * - DocumentStagingStore: Stores metadata for search result enrichment
 * - Search-Service: Returns metadata to LLM for context (source, publish date, author, etc.)
 */
interface Chunkable {
    /**
     * Extracts plain text for embedding generation.
     * Should strip HTML, normalize whitespace, and handle encoding issues.
     */
    fun toText(): String

    /**
     * Returns globally unique identifier for deduplication.
     * Format should be stable (e.g., RSS GUID, CVE ID, Wikipedia page title).
     */
    fun getId(): String

    /**
     * Returns metadata for search result enrichment and filtering.
     * Common keys: source, title, url, publish_date, author, content_type, audience
     */
    fun getMetadata(): Map<String, String>
}

/**
 * Wrapper for chunked items that preserves original metadata while adding chunk-specific fields.
 *
 * Integration points:
 * - DocumentStagingStore: Chunks stored as separate rows with chunk_index/total_chunks
 * - Qdrant: Each chunk gets its own vector, linked via parent document ID
 * - Search-Service: Reconstructs full document from chunks for LLM context
 */
data class ChunkedItem<T : Chunkable>(
    val originalItem: T,
    val chunkText: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val startPos: Int,
    val endPos: Int
) : Chunkable {
    override fun toText(): String = chunkText

    override fun getId(): String = "${originalItem.getId()}-chunk-$chunkIndex"

    override fun getMetadata(): Map<String, String> {
        val baseMetadata = originalItem.getMetadata().toMutableMap()
        baseMetadata["is_chunked"] = "true"
        baseMetadata["chunk_index"] = chunkIndex.toString()
        baseMetadata["total_chunks"] = totalChunks.toString()
        baseMetadata["chunk_start"] = startPos.toString()
        baseMetadata["chunk_end"] = endPos.toString()
        return baseMetadata
    }

    val isFirst: Boolean get() = chunkIndex == 0
    val isLast: Boolean get() = chunkIndex == totalChunks - 1
    val isSingle: Boolean get() = totalChunks == 1

    fun description(): String = when {
        isSingle -> "complete"
        isFirst -> "part 1 of $totalChunks"
        isLast -> "part ${chunkIndex + 1} of $totalChunks (final)"
        else -> "part ${chunkIndex + 1} of $totalChunks"
    }
}
