package org.datamancy.pipeline.core

import kotlinx.coroutines.flow.Flow
import org.datamancy.pipeline.scheduling.BackfillStrategy
import org.datamancy.pipeline.scheduling.ResyncStrategy
import org.datamancy.pipeline.scheduling.RunMetadata
import org.datamancy.pipeline.processors.Chunker


interface StandardizedSource<T : Chunkable> : Source<T> {
    
    fun resyncStrategy(): ResyncStrategy = ResyncStrategy.DailyAt(hour = 1, minute = 0)

    
    fun backfillStrategy(): BackfillStrategy

    
    fun needsChunking(): Boolean = false

    
    fun chunker(): Chunker = Chunker.forEmbeddingModel(tokenLimit = 8192, overlapPercent = 0.20)

    
    suspend fun fetchForRun(metadata: RunMetadata): Flow<T>

    
    override suspend fun fetch(): Flow<T> {
        return fetchForRun(RunMetadata(
            runType = org.datamancy.pipeline.scheduling.RunType.INITIAL_PULL,
            attemptNumber = 1,
            isFirstRun = true
        ))
    }
}


interface Chunkable {
    
    fun toText(): String

    
    fun getId(): String

    
    fun getMetadata(): Map<String, String>
}


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
