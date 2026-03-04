package org.datamancy.pipeline.processors

import io.github.oshai.kotlinlogging.KotlinLogging
import org.datamancy.pipeline.core.Processor

private val logger = KotlinLogging.logger {}

/**
 * Splits long text documents into overlapping chunks that fit within BGE-M3 embedding model's token limit.
 *
 * ## Purpose
 * The BGE-M3 embedding model has a hard limit of 8192 tokens. Documents exceeding this limit must be
 * chunked before embedding generation. This processor ensures all text fits the token budget while
 * maintaining semantic coherence through overlapping chunks.
 *
 * ## Token Limits & Safety Margins
 * - BGE-M3 max tokens: 8192
 * - Default maxTokens: 7372 (90% safety factor to prevent edge-case truncation)
 * - Default overlapTokens: 1474 (20% of maxTokens for context preservation)
 *
 * The safety margin accounts for tokenization variability between tiktoken (cl100k_base) and
 * the model's actual tokenizer. A 10% buffer prevents rejected requests due to slight differences.
 *
 * ## Overlap Strategy
 * Overlapping chunks ensure that semantic relationships spanning chunk boundaries are preserved:
 * - Chunk 1: tokens [0...7372]
 * - Chunk 2: tokens [5898...13270] (last 1474 tokens of chunk 1 reappear)
 * - Chunk 3: tokens [11796...19168]
 *
 * This strategy improves embedding quality for cross-boundary concepts and enables better
 * retrieval in Search-Service when queries match content near chunk edges.
 *
 * ## Integration with TokenCounter
 * This processor delegates all token operations to [TokenCounter]:
 * - [TokenCounter.countTokens] checks if chunking is needed
 * - [TokenCounter.chunkByTokens] performs the actual splitting with overlap
 *
 * TokenCounter uses JTokkit (tiktoken port) with cl100k_base encoding, which approximates
 * the BGE-M3 model's tokenization behavior.
 *
 * ## Data Flow in Pipeline
 * ```
 * Source → RssToText → Chunker → Embedder → TextToVector → QdrantSink
 *                          ↓
 *                  [TextChunk, TextChunk, ...]
 * ```
 *
 * Each chunk is embedded separately, then stored as individual vectors in Qdrant. The chunk metadata
 * (index, totalChunks) enables Search-Service to reconstruct document context when retrieving results.
 *
 * @param maxTokens Maximum tokens per chunk (default 7372 = 90% of BGE-M3's 8192 limit)
 * @param overlapTokens Number of tokens to overlap between consecutive chunks (default 1474 = 20% of maxTokens)
 * @param breakOnSentences Currently unused; reserved for future sentence-boundary detection
 * @param minTokens Minimum tokens to consider a chunk valid (prevents tiny fragments)
 */
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

    /**
     * Chunks text into overlapping segments that respect BGE-M3's token limit.
     *
     * ## Processing Logic
     * 1. Count total tokens using TokenCounter.countTokens()
     * 2. If text fits within maxTokens, return single chunk (no splitting needed)
     * 3. Otherwise, use TokenCounter.chunkByTokens() to split with overlap
     * 4. Wrap each text segment in TextChunk with index metadata
     *
     * ## Why This Matters
     * Without chunking, the Embedding Service would reject texts exceeding 8192 tokens.
     * By pre-chunking here, we ensure all downstream processors (Embedder, TextToVector)
     * receive token-compliant inputs.
     *
     * @param text The document text to chunk (may be any length)
     * @return List of TextChunks, each guaranteed to be ≤ maxTokens
     */
    override suspend fun process(text: String): List<TextChunk> {
        // Count tokens to determine if chunking is necessary
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

        // Delegate to TokenCounter for overlapping token-based chunking
        val tokenChunks = TokenCounter.chunkByTokens(text, maxTokens, overlapTokens)

        // Wrap each chunk with metadata for downstream tracking
        val chunks = tokenChunks.mapIndexed { index, chunkText ->
            TextChunk(
                text = chunkText,
                index = index,
                startPos = -1,  // Character positions not tracked in token-based chunking
                endPos = -1,
                totalChunks = tokenChunks.size
            )
        }

        logger.debug { "Chunked $totalTokens tokens into ${chunks.size} chunks (max: $maxTokens, overlap: $overlapTokens)" }
        return chunks
    }


    companion object {
        /**
         * Factory method to create a Chunker configured for a specific embedding model's token limit.
         *
         * Automatically calculates safe chunking parameters based on the model's maximum context window.
         * The safety factor prevents edge-case rejections due to tokenization differences between
         * tiktoken (cl100k_base) and the actual model tokenizer.
         *
         * @param tokenLimit Model's maximum token limit (e.g., 8192 for BGE-M3, 512 for older models)
         * @param overlapPercent Percentage of chunk to overlap (default 20% balances context vs duplication)
         * @param safetyFactor Reduction factor for max tokens (default 90% provides buffer for tokenizer variance)
         * @return Chunker instance configured for the specified model constraints
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
 * Represents a single chunk of text with positional metadata.
 *
 * Used by [Chunker] to track chunk relationships and enable downstream processors to
 * preserve document structure information. When chunks are embedded and stored in Qdrant,
 * this metadata helps Search-Service understand that multiple vectors belong to the same
 * logical document.
 *
 * @property text The actual text content of this chunk
 * @property index Zero-based position of this chunk in the document (0 = first chunk)
 * @property startPos Character offset where this chunk begins in original text (-1 if not tracked)
 * @property endPos Character offset where this chunk ends in original text (-1 if not tracked)
 * @property totalChunks Total number of chunks the original document was split into
 */
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

    /**
     * Human-readable description of chunk position within the document.
     * Used in logging and debugging to understand chunk relationships.
     */
    fun description(): String = when {
        isSingle -> "complete"
        isFirst -> "part 1 of $totalChunks"
        isLast -> "part ${index + 1} of $totalChunks (final)"
        else -> "part ${index + 1} of $totalChunks"
    }
}
