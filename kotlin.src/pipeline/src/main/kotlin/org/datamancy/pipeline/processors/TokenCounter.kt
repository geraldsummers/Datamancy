package org.datamancy.pipeline.processors

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import com.knuddels.jtokkit.api.IntArrayList

/**
 * Accurate token counter using jtokkit (tiktoken for JVM)
 *
 * BGE embeddings use BERT tokenizer which is similar to cl100k_base
 * This provides accurate token counting for proper chunking and truncation
 */
object TokenCounter {
    private val registry: EncodingRegistry = Encodings.newDefaultEncodingRegistry()

    // Use cl100k_base encoding (GPT-4/BERT-like tokenization)
    private val encoding: Encoding = registry.getEncoding(EncodingType.CL100K_BASE)

    /**
     * Count exact number of tokens in text
     */
    fun countTokens(text: String): Int {
        return encoding.countTokens(text)
    }

    /**
     * Truncate text to exact token count
     * Returns truncated text that fits within maxTokens
     */
    fun truncateToTokens(text: String, maxTokens: Int): String {
        val tokenCount = encoding.countTokens(text)
        if (tokenCount <= maxTokens) {
            return text
        }

        // Encode and take first maxTokens, then decode
        val tokens = encoding.encode(text)
        val truncatedList = IntArrayList()
        for (i in 0 until minOf(maxTokens, tokens.size())) {
            truncatedList.add(tokens.get(i))
        }
        return encoding.decode(truncatedList)
    }

    /**
     * Split text into chunks with exact token counts
     * Each chunk will be <= maxTokens, with optional overlap
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

            // Move forward with overlap
            start += (maxTokens - overlapTokens)
            if (start >= tokens.size()) break
        }

        return chunks
    }
}
