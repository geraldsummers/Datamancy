package org.datamancy.pipeline.processors

import io.github.oshai.kotlinlogging.KotlinLogging
import org.datamancy.pipeline.core.Processor

private val logger = KotlinLogging.logger {}


class Chunker(
    internal val maxTokens: Int = 7372,      
    internal val overlapTokens: Int = 1474,  
    private val breakOnSentences: Boolean = true,
    private val minTokens: Int = 50          
) : Processor<String, List<TextChunk>> {
    override val name = "Chunker"

    init {
        require(overlapTokens < maxTokens) {
            "Overlap tokens ($overlapTokens) must be less than max tokens ($maxTokens)"
        }
        require(overlapTokens >= 0) {
            "Overlap tokens must be non-negative"
        }
        require(minTokens > 0) {
            "Min tokens must be positive"
        }
    }

    override suspend fun process(text: String): List<TextChunk> {
        
        val totalTokens = TokenCounter.countTokens(text)

        if (totalTokens <= maxTokens) {
            return listOf(TextChunk(
                text = text,
                index = 0,
                startPos = 0,
                endPos = text.length,
                totalChunks = 1
            ))
        }

        
        val tokenChunks = TokenCounter.chunkByTokens(text, maxTokens, overlapTokens)

        
        val chunks = tokenChunks.mapIndexed { index, chunkText ->
            TextChunk(
                text = chunkText,
                index = index,
                startPos = -1,  
                endPos = -1,
                totalChunks = tokenChunks.size
            )
        }

        logger.debug { "Chunked $totalTokens tokens into ${chunks.size} chunks (max: $maxTokens, overlap: $overlapTokens)" }
        return chunks
    }


    companion object {
        
        fun forEmbeddingModel(tokenLimit: Int = 512, overlapPercent: Double = 0.20, safetyFactor: Double = 0.90): Chunker {
            val maxTokens = (tokenLimit * safetyFactor).toInt()
            val overlapTokens = (maxTokens * overlapPercent).toInt()

            return Chunker(
                maxTokens = maxTokens,
                overlapTokens = overlapTokens,
                breakOnSentences = true,
                minTokens = 50
            )
        }
    }
}


data class TextChunk(
    val text: String,
    val index: Int,           
    val startPos: Int,        
    val endPos: Int,          
    val totalChunks: Int      
) {
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == totalChunks - 1
    val isSingle: Boolean get() = totalChunks == 1

    
    fun description(): String = when {
        isSingle -> "complete"
        isFirst -> "part 1 of $totalChunks"
        isLast -> "part ${index + 1} of $totalChunks (final)"
        else -> "part ${index + 1} of $totalChunks"
    }
}
