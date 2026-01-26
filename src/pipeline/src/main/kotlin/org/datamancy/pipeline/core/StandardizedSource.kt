package org.datamancy.pipeline.core

import kotlinx.coroutines.flow.Flow
import org.datamancy.pipeline.scheduling.BackfillStrategy
import org.datamancy.pipeline.scheduling.ResyncStrategy
import org.datamancy.pipeline.scheduling.RunMetadata
import org.datamancy.pipeline.processors.Chunker

/**
 * Standardized pipeline source interface that ALL sources MUST implement
 *
 * This interface enforces:
 * - Scheduling strategy (initial pull + resync)
 * - Backfill strategy (historical data)
 * - Chunking configuration
 * - Resync schedule
 *
 * NO MORE AD-HOC IMPLEMENTATIONS!
 */
interface StandardizedSource<T : Chunkable> : Source<T> {
    /**
     * Resync strategy - when should this source run?
     * Default: Daily at 1am
     */
    fun resyncStrategy(): ResyncStrategy = ResyncStrategy.DailyAt(hour = 1, minute = 0)

    /**
     * Backfill strategy - how to fetch historical data on initial pull?
     * Must be overridden by each source!
     */
    fun backfillStrategy(): BackfillStrategy

    /**
     * Does this source need chunking?
     * If true, must override chunker()
     */
    fun needsChunking(): Boolean = false

    /**
     * Chunker configuration for this source
     * Only called if needsChunking() returns true
     */
    fun chunker(): Chunker = Chunker.forEmbeddingModel(tokenLimit = 512, overlapPercent = 0.20)

    /**
     * Fetch data based on run metadata (initial pull vs resync)
     * Sources MUST implement different logic for each run type:
     * - INITIAL_PULL: Full backfill (e.g., last 7 days, full dump, all records)
     * - RESYNC: Incremental update (e.g., since last run, recent changes only)
     */
    suspend fun fetchForRun(metadata: RunMetadata): Flow<T>

    /**
     * Default fetch() delegates to fetchForRun() with INITIAL_PULL metadata
     * This is for backwards compatibility with old Source interface
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
 * Marker interface for items that can be chunked
 * All source output types should implement this
 */
interface Chunkable {
    /**
     * Convert this item to text for chunking/embedding
     */
    fun toText(): String

    /**
     * Get a unique ID for this item
     */
    fun getId(): String

    /**
     * Get metadata for this item (stored with vector)
     */
    fun getMetadata(): Map<String, String>
}

/**
 * Helper extension for sources that need chunking
 * Returns chunk-aware items with metadata
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
