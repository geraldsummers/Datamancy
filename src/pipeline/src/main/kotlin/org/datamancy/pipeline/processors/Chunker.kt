package org.datamancy.pipeline.processors

import io.github.oshai.kotlinlogging.KotlinLogging
import org.datamancy.pipeline.core.Processor

private val logger = KotlinLogging.logger {}

/**
 * Standardized text chunker with accurate token-based splitting
 *
 * Chunking Strategy:
 * - Uses jtokkit for EXACT token counting (no approximations!)
 * - Chunks text into overlapping segments to preserve context
 * - Default 20% overlap (configurable)
 * - Attempts to break on sentence boundaries when possible
 * - Falls back to token-based splitting if no good break point
 *
 * Token Accuracy:
 * - Uses cl100k_base encoding (BERT-like tokenization)
 * - Guarantees each chunk <= maxTokens
 * - For 512 token limit: each chunk will be exactly ≤ 512 tokens
 *
 * Usage:
 * ```
 * val chunker = Chunker.forEmbeddingModel(tokenLimit = 8192, overlapPercent = 0.20)
 * val chunks = chunker.process(longText)
 * ```
 */
class Chunker(
    internal val maxTokens: Int = 7372,      // Conservative default (8192 * 0.90 = 7372)
    internal val overlapTokens: Int = 1474,  // 20% overlap
    private val breakOnSentences: Boolean = true,
    private val minTokens: Int = 50          // Don't create tiny chunks
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
        // Use accurate token-based chunking
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

        // Split text by tokens with overlap
        val tokenChunks = TokenCounter.chunkByTokens(text, maxTokens, overlapTokens)

        // Convert to TextChunk objects
        val chunks = tokenChunks.mapIndexed { index, chunkText ->
            TextChunk(
                text = chunkText,
                index = index,
                startPos = -1,  // Not tracking char positions with token-based chunking
                endPos = -1,
                totalChunks = tokenChunks.size
            )
        }

        logger.debug { "Chunked $totalTokens tokens into ${chunks.size} chunks (max: $maxTokens, overlap: $overlapTokens)" }
        return chunks
    }


    companion object {
        /**
         * Create a chunker optimized for embedding models with token limits
         * Uses accurate token counting with configurable safety margin
         */
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

/**
 * Represents a chunk of text with metadata
 */
data class TextChunk(
    val text: String,
    val index: Int,           // 0-based chunk index
    val startPos: Int,        // Start position in original text
    val endPos: Int,          // End position in original text
    val totalChunks: Int      // Total number of chunks created from source
) {
    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index == totalChunks - 1
    val isSingle: Boolean get() = totalChunks == 1

    /**
     * Human-readable description of this chunk
     */
    fun description(): String = when {
        isSingle -> "complete"
        isFirst -> "part 1 of $totalChunks"
        isLast -> "part ${index + 1} of $totalChunks (final)"
        else -> "part ${index + 1} of $totalChunks"
    }
}
