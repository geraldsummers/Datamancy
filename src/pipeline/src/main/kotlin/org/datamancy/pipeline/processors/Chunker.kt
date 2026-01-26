package org.datamancy.pipeline.processors

import io.github.oshai.kotlinlogging.KotlinLogging
import org.datamancy.pipeline.core.Processor

private val logger = KotlinLogging.logger {}

/**
 * Standardized text chunker with configurable overlap
 *
 * Chunking Strategy:
 * - Chunks text into overlapping segments to preserve context
 * - Default 20% overlap (configurable)
 * - Attempts to break on sentence boundaries when possible
 * - Falls back to character-based splitting if no good break point
 *
 * Token Estimation:
 * - Uses conservative estimate of 3.5 characters per token
 * - For 512 token limit: 512 * 3.5 = 1792 chars max
 * - Default chunk size: 1500 chars (~428 tokens) with 300 char overlap (20%)
 *
 * Usage:
 * ```
 * val chunker = Chunker(
 *     maxChunkSize = 1500,
 *     overlapSize = 300,
 *     breakOnSentences = true
 * )
 * val chunks = chunker.process(longText)
 * ```
 */
class Chunker(
    internal val maxChunkSize: Int = 1500,  // Conservative for 512 token limit
    internal val overlapSize: Int = 300,    // 20% overlap
    private val breakOnSentences: Boolean = true,
    private val minChunkSize: Int = 200    // Don't create tiny chunks
) : Processor<String, List<TextChunk>> {
    override val name = "Chunker"

    init {
        require(overlapSize < maxChunkSize) {
            "Overlap size ($overlapSize) must be less than max chunk size ($maxChunkSize)"
        }
        require(overlapSize >= 0) {
            "Overlap size must be non-negative"
        }
        require(minChunkSize > 0) {
            "Min chunk size must be positive"
        }
    }

    override suspend fun process(text: String): List<TextChunk> {
        if (text.length <= maxChunkSize) {
            return listOf(TextChunk(
                text = text,
                index = 0,
                startPos = 0,
                endPos = text.length,
                totalChunks = 1
            ))
        }

        val chunks = mutableListOf<TextChunk>()
        var startPos = 0
        var chunkIndex = 0

        while (startPos < text.length) {
            // Calculate potential end position
            val idealEnd = minOf(startPos + maxChunkSize, text.length)

            // Try to find a good break point
            val actualEnd = if (breakOnSentences && idealEnd < text.length) {
                findSentenceBreak(text, startPos, idealEnd)
            } else {
                idealEnd
            }

            val chunkText = text.substring(startPos, actualEnd).trim()

            // Only add if chunk meets minimum size (except for last chunk)
            if (chunkText.length >= minChunkSize || actualEnd >= text.length) {
                chunks.add(TextChunk(
                    text = chunkText,
                    index = chunkIndex,
                    startPos = startPos,
                    endPos = actualEnd,
                    totalChunks = -1  // Will update after all chunks created
                ))
                chunkIndex++
            }

            // Move to next chunk with overlap
            startPos = actualEnd - overlapSize

            // Prevent infinite loop if overlap is too large
            if (startPos <= chunks.lastOrNull()?.startPos ?: -1) {
                startPos = actualEnd
            }

            // Stop if we've reached the end
            if (actualEnd >= text.length) break
        }

        // Update totalChunks for all chunks
        val totalChunks = chunks.size
        chunks.forEachIndexed { index, chunk ->
            chunks[index] = chunk.copy(totalChunks = totalChunks)
        }

        logger.debug { "Chunked ${text.length} chars into ${chunks.size} chunks (overlap: ${overlapSize})" }
        return chunks
    }

    /**
     * Find the best sentence break point near the ideal end position
     * Looks for: . ! ? followed by space or newline
     */
    private fun findSentenceBreak(text: String, start: Int, idealEnd: Int): Int {
        // Search backwards from ideal end for sentence terminators
        val searchStart = maxOf(start + minChunkSize, idealEnd - 200)  // Don't go too far back

        for (i in idealEnd downTo searchStart) {
            if (i >= text.length) continue

            val char = text[i]
            val nextChar = if (i + 1 < text.length) text[i + 1] else ' '

            // Check for sentence ending punctuation followed by whitespace
            if (char in listOf('.', '!', '?') && nextChar in listOf(' ', '\n', '\r', '\t')) {
                // Make sure we're not breaking on abbreviations (e.g., "Dr.", "U.S.")
                if (i > 0 && text[i - 1].isUpperCase() && text.getOrNull(i - 2) == '.') {
                    continue  // Likely an abbreviation
                }
                return minOf(i + 2, text.length)  // Include punctuation and space
            }
        }

        // If no sentence break found, try paragraph break
        for (i in idealEnd downTo searchStart) {
            if (i >= text.length) continue
            if (text[i] == '\n' && text.getOrNull(i + 1) == '\n') {
                return minOf(i + 2, text.length)
            }
        }

        // Fall back to ideal end
        return idealEnd
    }

    companion object {
        /**
         * Calculate recommended chunk size based on token limit
         * Uses conservative 3.5 chars/token estimate
         */
        fun chunkSizeForTokenLimit(tokenLimit: Int, safetyFactor: Double = 0.85): Int {
            return (tokenLimit * 3.5 * safetyFactor).toInt()
        }

        /**
         * Calculate recommended overlap for a given overlap percentage
         */
        fun calculateOverlap(chunkSize: Int, overlapPercent: Double = 0.20): Int {
            return (chunkSize * overlapPercent).toInt()
        }

        /**
         * Create a chunker optimized for embedding models with token limits
         */
        fun forEmbeddingModel(tokenLimit: Int = 512, overlapPercent: Double = 0.20): Chunker {
            val chunkSize = chunkSizeForTokenLimit(tokenLimit)
            val overlap = calculateOverlap(chunkSize, overlapPercent)
            return Chunker(
                maxChunkSize = chunkSize,
                overlapSize = overlap,
                breakOnSentences = true
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
