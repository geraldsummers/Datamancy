package org.datamancy.pipeline.processors

import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.Encoding
import com.knuddels.jtokkit.api.EncodingRegistry
import com.knuddels.jtokkit.api.EncodingType
import com.knuddels.jtokkit.api.IntArrayList


object TokenCounter {
    private val registry: EncodingRegistry = Encodings.newDefaultEncodingRegistry()

    
    private val encoding: Encoding = registry.getEncoding(EncodingType.CL100K_BASE)

    
    fun countTokens(text: String): Int {
        return encoding.countTokens(text)
    }

    
    fun truncateToTokens(text: String, maxTokens: Int): String {
        val tokenCount = encoding.countTokens(text)
        if (tokenCount <= maxTokens) {
            return text
        }

        
        val tokens = encoding.encode(text)
        val truncatedList = IntArrayList()
        for (i in 0 until minOf(maxTokens, tokens.size())) {
            truncatedList.add(tokens.get(i))
        }
        return encoding.decode(truncatedList)
    }

    
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

            
            start += (maxTokens - overlapTokens)
            if (start >= tokens.size()) break
        }

        return chunks
    }
}
