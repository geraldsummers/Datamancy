package org.datamancy.pipeline.processors

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import com.knuddels.jtokkit.api.IntArrayList

/**
 * Token counting, truncation, and chunking utilities using tiktoken (cl100k_base encoding).
 *
 * ## Purpose
 * This singleton provides the foundational token operations needed across the pipeline to ensure
 * text inputs respect the BGE-M3 embedding model's 8192 token limit. It acts as a shared utility
 * for both [Chunker] (splitting documents) and [Embedder] (truncating oversized inputs).
 *
 * ## Tiktoken & JTokkit
 * - **Library**: JTokkit (Java port of OpenAI's tiktoken)
 * - **Encoding**: cl100k_base (used by GPT-4, GPT-3.5-turbo)
 * - **Why cl100k_base?**: Approximates BGE-M3's tokenization behavior closely enough for safety margins
 *
 * While BGE-M3 uses its own tokenizer internally, cl100k_base provides a reasonable approximation.
 * The pipeline compensates for tokenization differences using safety factors:
 * - Chunker: 90% safety factor (7372 tokens instead of 8192)
 * - Embedder: 95% safety factor (7782 tokens instead of 8192)
 *
 * ## Token Counting vs Character Counting
 * Tokens ≠ characters or words. Token boundaries depend on the model's vocabulary:
 * - "hello" = 1 token
 * - "hello world" = 2 tokens
 * - "🚀" = 1-3 tokens (emoji may split into multiple tokens)
 * - "antidisestablishmentarianism" = 5+ tokens (rare words split into subwords)
 *
 * This is why we can't simply split text at character positions — we must tokenize first,
 * split token arrays, then decode back to text.
 *
 * ## Integration Points
 * Used by:
 * - **Chunker.process()**: Calls countTokens() and chunkByTokens() to split long documents
 * - **Embedder.process()**: Calls countTokens() and truncateToTokens() as safety net
 *
 * Not a Processor itself (no process() method), but a utility object shared across processors.
 *
 * ## Why cl100k_base Instead of BGE-M3's Exact Tokenizer?
 * 1. BGE-M3's tokenizer is embedded in the model (not exposed as standalone library)
 * 2. cl100k_base is widely available, fast, and well-tested (via JTokkit)
 * 3. Safety margins (90-95%) compensate for tokenization differences
 * 4. Embedding Service provides server-side truncation as final safeguard
 *
 * This layered approach (client approximation + safety margin + server-side truncation) ensures
 * no requests exceed the hard limit, even if tokenization differs slightly.
 */
object TokenCounter {
    private val registry: EncodingRegistry = Encodings.newDefaultEncodingRegistry()

    // cl100k_base encoding (GPT-4, GPT-3.5-turbo) approximates BGE-M3 tokenization
    private val encoding: Encoding = registry.getEncoding(EncodingType.CL100K_BASE)

    /**
     * Counts the number of tokens in the given text using cl100k_base encoding.
     *
     * Used by Chunker to determine if splitting is needed, and by Embedder to check if
     * truncation is required before sending to the Embedding Service.
     *
     * @param text Input text (any length)
     * @return Token count (e.g., "hello world" = 2 tokens)
     */
    fun countTokens(text: String): Int {
        return encoding.countTokens(text)
    }

    /**
     * Truncates text to a maximum number of tokens, preserving token boundaries.
     *
     * ## How It Works
     * 1. Tokenize the full text into integer token IDs
     * 2. Take only the first `maxTokens` token IDs
     * 3. Decode those tokens back to text
     *
     * This ensures the truncated text is exactly `maxTokens` long (or shorter if original
     * text was already under the limit). The truncation happens at token boundaries, not
     * arbitrary character positions.
     *
     * ## Why Truncate at Token Boundaries?
     * If we truncated at character positions (e.g., text.substring(0, 10000)), we might:
     * - Split a multi-token character (emoji, unicode) in the middle
     * - End up with slightly more tokens than expected (if last character is multi-token)
     *
     * Token-based truncation guarantees the result fits the token budget exactly.
     *
     * ## Use Case in Pipeline
     * Embedder uses this as a safety net for texts that bypass Chunker (e.g., short documents
     * that don't need chunking but somehow exceed maxTokens due to token-dense content).
     *
     * @param text Input text to truncate
     * @param maxTokens Maximum tokens to keep (e.g., 7782 for Embedder's safety margin)
     * @return Truncated text with ≤ maxTokens tokens (returns original if already under limit)
     */
    fun truncateToTokens(text: String, maxTokens: Int): String {
        val tokenCount = encoding.countTokens(text)
        if (tokenCount <= maxTokens) {
            return text
        }

        // Encode text to token IDs, take first maxTokens, decode back to text
        val tokens = encoding.encode(text)
        val truncatedList = IntArrayList()
        for (i in 0 until minOf(maxTokens, tokens.size())) {
            truncatedList.add(tokens.get(i))
        }
        return encoding.decode(truncatedList)
    }

    /**
     * Splits text into overlapping chunks, each with at most `maxTokens` tokens.
     *
     * ## Algorithm
     * 1. Tokenize entire text into integer token IDs
     * 2. Extract sliding windows of token IDs:
     *    - Window 1: tokens [0 ... maxTokens-1]
     *    - Window 2: tokens [(maxTokens - overlapTokens) ... (2*maxTokens - overlapTokens - 1)]
     *    - Window 3: tokens [(2*maxTokens - 2*overlapTokens) ... (3*maxTokens - 2*overlapTokens - 1)]
     *    - ...continue until all tokens covered
     * 3. Decode each token window back to text
     *
     * ## Why Overlapping Chunks?
     * Without overlap, semantic relationships spanning chunk boundaries would be lost:
     * - Chunk 1 ends: "...the company announced a new"
     * - Chunk 2 starts: "product launch in Q4."
     *
     * With overlap:
     * - Chunk 1 ends: "...the company announced a new product launch in Q4."
     * - Chunk 2 starts: "announced a new product launch in Q4. The CEO stated..."
     *
     * Overlapping chunks ensure concepts near boundaries appear in multiple embeddings,
     * improving Search-Service retrieval accuracy when queries match those boundary regions.
     *
     * ## Overlap Strategy in Datamancy
     * - Default overlap: 20% of maxTokens (1474 tokens for 7372 max)
     * - Last 20% of Chunk N becomes first 20% of Chunk N+1
     * - Trade-off: Better retrieval accuracy vs increased storage (more vectors in Qdrant)
     *
     * ## Token-Based vs Character-Based Chunking
     * Character-based chunking (e.g., split every 5000 chars) would produce variable token counts:
     * - Chunk A: 4500 characters = 6000 tokens (over limit!)
     * - Chunk B: 4500 characters = 3000 tokens (inefficient, could fit more)
     *
     * Token-based chunking ensures every chunk uses the token budget efficiently and safely.
     *
     * @param text Input text to chunk (any length)
     * @param maxTokens Maximum tokens per chunk (e.g., 7372 for 90% safety margin)
     * @param overlapTokens Number of tokens to overlap between chunks (e.g., 1474 = 20% of 7372)
     * @return List of text chunks, each with ≤ maxTokens tokens
     */
    fun chunkByTokens(text: String, maxTokens: Int, overlapTokens: Int = 0): List<String> {
        val tokenCount = encoding.countTokens(text)
        if (tokenCount <= maxTokens) {
            return listOf(text)
        }

        val chunks = mutableListOf<String>()
        val tokens = encoding.encode(text)
        var start = 0

        while (start < tokens.size()) {
            val end = minOf(start + maxTokens, tokens.size())
            val chunkList = IntArrayList()
            for (i in start until end) {
                chunkList.add(tokens.get(i))
            }
            chunks.add(encoding.decode(chunkList))

            // Move start forward by (maxTokens - overlapTokens) to create overlap
            start += (maxTokens - overlapTokens)
            if (start >= tokens.size()) break
        }

        return chunks
    }
}
