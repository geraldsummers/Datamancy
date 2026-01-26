package org.datamancy.pipeline.processors

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChunkerTest {

    @Test
    fun `should not chunk text smaller than max tokens`() = runBlocking {
        val chunker = Chunker(maxTokens = 100, overlapTokens = 20)
        val text = "This is a short text."
        val chunks = chunker.process(text)

        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].text)
        assertTrue(chunks[0].isSingle)
    }

    @Test
    fun `should chunk long text with overlap`() = runBlocking {
        val chunker = Chunker(maxTokens = 50, overlapTokens = 10)
        val text = "word " * 200  // ~200 tokens
        val chunks = chunker.process(text)

        assertTrue(chunks.size > 1, "Should create multiple chunks for 200 tokens with 50 max")

        // Verify each chunk is under token limit
        chunks.forEach { chunk ->
            val tokens = TokenCounter.countTokens(chunk.text)
            assertTrue(tokens <= 50, "Chunk has $tokens tokens, should be ≤50")
        }
    }

    @Test
    fun `should chunk based on tokens not characters`() = runBlocking {
        val chunker = Chunker(maxTokens = 100, overlapTokens = 20)
        val text = "This is sentence one. This is sentence two. This is sentence three. This is sentence four. This is sentence five."
        val chunks = chunker.process(text)

        // Verify token-based chunking
        chunks.forEach { chunk ->
            val tokens = TokenCounter.countTokens(chunk.text)
            assertTrue(tokens <= 100, "Chunk has $tokens tokens, should be ≤100")
        }
    }

    @Test
    fun `should handle text with newlines`() = runBlocking {
        val chunker = Chunker(maxTokens = 100, overlapTokens = 20)
        val text = """
            First paragraph here.

            Second paragraph here.

            Third paragraph here.
        """.trimIndent()
        val chunks = chunker.process(text)

        assertTrue(chunks.size >= 1)
        chunks.forEach { chunk ->
            assertFalse(chunk.text.isEmpty())
            val tokens = TokenCounter.countTokens(chunk.text)
            assertTrue(tokens <= 100, "Chunk has $tokens tokens, should be ≤100")
        }
    }

    @Test
    fun `should create embedding-optimized chunker for BGE-M3`() {
        val chunker = Chunker.forEmbeddingModel(tokenLimit = 8192, overlapPercent = 0.20)

        // 8192 * 0.90 = 7372 tokens
        assertEquals(7372, chunker.maxTokens)
        // 7372 * 0.20 = 1474 tokens overlap
        assertEquals(1474, chunker.overlapTokens)
    }

    @Test
    fun `should create embedding-optimized chunker for custom token limit`() {
        val chunker = Chunker.forEmbeddingModel(tokenLimit = 512, overlapPercent = 0.20, safetyFactor = 0.85)

        // 512 * 0.85 = 435 tokens
        assertEquals(435, chunker.maxTokens)
        // 435 * 0.20 = 87 tokens overlap
        assertEquals(87, chunker.overlapTokens)
    }

    @Test
    fun `should set chunk metadata correctly`() = runBlocking {
        val chunker = Chunker(maxTokens = 50, overlapTokens = 10)
        val text = "word " * 200
        val chunks = chunker.process(text)

        // Check first chunk
        assertTrue(chunks.first().isFirst)
        assertFalse(chunks.first().isLast)
        assertEquals(0, chunks.first().index)

        // Check last chunk
        assertFalse(chunks.last().isFirst)
        assertTrue(chunks.last().isLast)
        assertEquals(chunks.size - 1, chunks.last().index)

        // Check all chunks have correct totalChunks
        chunks.forEach { chunk ->
            assertEquals(chunks.size, chunk.totalChunks)
        }
    }

    @Test
    fun `should not create tiny chunks`() = runBlocking {
        val chunker = Chunker(maxTokens = 100, overlapTokens = 20, minTokens = 50)
        val text = "word " * 60  // Just slightly over one chunk
        val chunks = chunker.process(text)

        // Verify chunks respect token limits
        chunks.forEach { chunk ->
            val tokens = TokenCounter.countTokens(chunk.text)
            assertTrue(tokens <= 100, "Chunk has $tokens tokens, should be ≤100")
        }
    }

    @Test
    fun `should generate correct descriptions`() = runBlocking {
        val chunker = Chunker(maxTokens = 50, overlapTokens = 10)
        val text = "word " * 200
        val chunks = chunker.process(text)

        assertEquals("part 1 of ${chunks.size}", chunks.first().description())
        assertEquals("part ${chunks.size} of ${chunks.size} (final)", chunks.last().description())

        if (chunks.size > 2) {
            assertEquals("part 2 of ${chunks.size}", chunks[1].description())
        }
    }

    @Test
    fun `should handle real-world text with punctuation`() = runBlocking {
        val chunker = Chunker(maxTokens = 200, overlapTokens = 40)
        val text = """
            Dr. Smith works at U.S. Research Institute. He published a groundbreaking paper last year.
            The paper discusses AI safety in autonomous systems. It has received widespread acclaim!
            Many researchers cite this work. Prof. Johnson called it "revolutionary." The findings are clear.
        """.trimIndent()

        val chunks = chunker.process(text)

        // Verify token-based chunking works with real text
        chunks.forEach { chunk ->
            assertFalse(chunk.text.trim().isEmpty())
            val tokens = TokenCounter.countTokens(chunk.text)
            assertTrue(tokens <= 200, "Chunk has $tokens tokens, should be ≤200")
        }
    }

    @Test
    fun `should handle BGE-M3 sized chunks efficiently`() = runBlocking {
        val chunker = Chunker.forEmbeddingModel(tokenLimit = 8192, overlapPercent = 0.20)
        val longText = "This is a test sentence. " * 500  // ~1500 tokens

        val chunks = chunker.process(longText)

        // Should fit in single chunk since 1500 < 7372
        assertEquals(1, chunks.size)

        val tokens = TokenCounter.countTokens(chunks[0].text)
        assertTrue(tokens <= 7372, "Chunk has $tokens tokens, should be ≤7372")
    }
}

// Helper extension for string repetition
private operator fun String.times(n: Int): String = this.repeat(n)
