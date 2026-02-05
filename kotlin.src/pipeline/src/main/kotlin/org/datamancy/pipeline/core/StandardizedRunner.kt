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


class StandardizedRunner<T : Chunkable>(
    private val source: StandardizedSource<T>,
    private val collectionName: String,              
    private val stagingStore: DocumentStagingStore,  
    private val dedupStore: DeduplicationStore,
    private val metadataStore: SourceMetadataStore,
    private val scheduler: SourceScheduler? = null     
) {
    private val sourceName = source.name

    
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
