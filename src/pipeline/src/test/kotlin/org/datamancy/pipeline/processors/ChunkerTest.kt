package org.datamancy.pipeline.processors

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ChunkerTest {

    @Test
    fun `should not chunk text smaller than max size`() = runBlocking {
        val chunker = Chunker(maxChunkSize = 100, overlapSize = 20)
        val text = "This is a short text."
        val chunks = chunker.process(text)

        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].text)
        assertTrue(chunks[0].isSingle)
    }

    @Test
    fun `should chunk long text with overlap`() = runBlocking {
        val chunker = Chunker(maxChunkSize = 50, overlapSize = 10, breakOnSentences = false, minChunkSize = 10)
        val text = "a".repeat(150)  // 150 character string
        val chunks = chunker.process(text)

        assertTrue(chunks.size > 1, "Should create multiple chunks")

        // Check overlap exists
        for (i in 1 until chunks.size) {
            val prevEnd = chunks[i - 1].endPos
            val currStart = chunks[i].startPos
            val overlap = prevEnd - currStart
            assertTrue(overlap >= 10, "Should have at least 10 char overlap, got $overlap")
        }
    }

    @Test
    fun `should break on sentence boundaries when enabled`() = runBlocking {
        val chunker = Chunker(maxChunkSize = 100, overlapSize = 20, breakOnSentences = true)
        val text = "This is sentence one. This is sentence two. This is sentence three. This is sentence four. This is sentence five."
        val chunks = chunker.process(text)

        // Should break cleanly on sentences
        chunks.forEach { chunk ->
            assertTrue(
                chunk.text.trim().endsWith(".") || chunk.isLast,
                "Chunk should end with period or be last chunk: '${chunk.text.takeLast(20)}'"
            )
        }
    }

    @Test
    fun `should handle text with newlines`() = runBlocking {
        val chunker = Chunker(maxChunkSize = 100, overlapSize = 20)
        val text = """
            First paragraph here.

            Second paragraph here.

            Third paragraph here.
        """.trimIndent()
        val chunks = chunker.process(text)

        assertTrue(chunks.size >= 1)
        chunks.forEach { chunk ->
            assertFalse(chunk.text.isEmpty())
        }
    }

    @Test
    fun `should calculate correct token-based chunk sizes`() {
        // For 512 token limit with 85% safety factor
        val chunkSize = Chunker.chunkSizeForTokenLimit(512, 0.85)

        // 512 * 3.5 * 0.85 = 1523.2 ≈ 1523
        assertEquals(1523, chunkSize)
    }

    @Test
    fun `should calculate correct overlap percentages`() {
        val chunkSize = 1500
        val overlap = Chunker.calculateOverlap(chunkSize, 0.20)

        // 1500 * 0.20 = 300
        assertEquals(300, overlap)
    }

    @Test
    fun `should create embedding-optimized chunker`() {
        val chunker = Chunker.forEmbeddingModel(tokenLimit = 512, overlapPercent = 0.20)

        assertEquals(1523, chunker.maxChunkSize)
        assertEquals(304, chunker.overlapSize)  // 1523 * 0.20 = 304.6 ≈ 304
    }

    @Test
    fun `should set chunk metadata correctly`() = runBlocking {
        val chunker = Chunker(maxChunkSize = 50, overlapSize = 10, breakOnSentences = false, minChunkSize = 10)
        val text = "a".repeat(150)
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
        val chunker = Chunker(maxChunkSize = 100, overlapSize = 20, minChunkSize = 50)
        val text = "a".repeat(110)  // Just slightly over one chunk
        val chunks = chunker.process(text)

        // Should not create a second tiny chunk
        chunks.forEach { chunk ->
            assertTrue(
                chunk.text.length >= 50 || chunk.isLast,
                "Chunk size ${chunk.text.length} should be >= 50 or be last chunk"
            )
        }
    }

    @Test
    fun `should generate correct descriptions`() = runBlocking {
        val chunker = Chunker(maxChunkSize = 50, overlapSize = 10, breakOnSentences = false, minChunkSize = 10)
        val text = "a".repeat(150)
        val chunks = chunker.process(text)

        assertEquals("part 1 of ${chunks.size}", chunks.first().description())
        assertEquals("part ${chunks.size} of ${chunks.size} (final)", chunks.last().description())

        if (chunks.size > 2) {
            assertEquals("part 2 of ${chunks.size}", chunks[1].description())
        }
    }

    @Test
    fun `should handle real-world text with punctuation`() = runBlocking {
        val chunker = Chunker(maxChunkSize = 200, overlapSize = 40, breakOnSentences = true)
        val text = """
            Dr. Smith works at U.S. Research Institute. He published a groundbreaking paper last year.
            The paper discusses AI safety in autonomous systems. It has received widespread acclaim!
            Many researchers cite this work. Prof. Johnson called it "revolutionary." The findings are clear.
        """.trimIndent()

        val chunks = chunker.process(text)

        // Should not break on abbreviations like "Dr." or "U.S."
        chunks.forEach { chunk ->
            assertFalse(chunk.text.trim().isEmpty())
            // Check that we didn't cut in the middle of "Dr." or "U.S."
            if (!chunk.isLast) {
                val trimmed = chunk.text.trim()
                assertFalse(trimmed.endsWith("Dr"), "Should not end with 'Dr'")
                assertFalse(trimmed.endsWith("U"), "Should not end with 'U'")
            }
        }
    }
}
