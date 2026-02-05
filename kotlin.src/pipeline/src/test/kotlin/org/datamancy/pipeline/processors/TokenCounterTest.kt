package org.datamancy.pipeline.processors

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenCounterTest {

    @Test
    fun `countTokens - short text`() {
        val text = "Hello world"
        val tokens = TokenCounter.countTokens(text)

        
        assertEquals(2, tokens)
    }

    @Test
    fun `countTokens - longer text`() {
        val text = "The quick brown fox jumps over the lazy dog"
        val tokens = TokenCounter.countTokens(text)

        
        assertTrue(tokens in 9..10, "Expected 9-10 tokens, got $tokens")
    }

    @Test
    fun `countTokens - CJK text has higher token count`() {
        val englishText = "Hello world this is a test"
        val chineseText = "你好世界这是一个测试"

        val englishTokens = TokenCounter.countTokens(englishText)
        val chineseTokens = TokenCounter.countTokens(chineseText)

        
        assertTrue(chineseTokens > 8, "Chinese should have multiple tokens, got $chineseTokens")
    }

    @Test
    fun `truncateToTokens - text under limit`() {
        val text = "Hello world"
        val truncated = TokenCounter.truncateToTokens(text, 100)

        assertEquals(text, truncated)
    }

    @Test
    fun `truncateToTokens - text over limit`() {
        val text = "The quick brown fox jumps over the lazy dog " * 100
        val truncated = TokenCounter.truncateToTokens(text, 50)

        val tokens = TokenCounter.countTokens(truncated)
        assertTrue(tokens <= 50, "Truncated text should have ≤50 tokens, got $tokens")
        assertTrue(truncated.length < text.length, "Truncated text should be shorter")
    }

    @Test
    fun `truncateToTokens - exactly at limit`() {
        val text = "word " * 50  
        val truncated = TokenCounter.truncateToTokens(text, 50)

        val tokens = TokenCounter.countTokens(truncated)
        assertTrue(tokens <= 50, "Should be ≤50 tokens, got $tokens")
    }

    @Test
    fun `chunkByTokens - short text no chunking needed`() {
        val text = "Hello world"
        val chunks = TokenCounter.chunkByTokens(text, 100, 0)

        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun `chunkByTokens - long text requires chunking`() {
        val text = "word " * 200  
        val chunks = TokenCounter.chunkByTokens(text, 50, 0)

        assertTrue(chunks.size >= 4, "Should have at least 4 chunks for 200 tokens with 50 max, got ${chunks.size}")

        
        chunks.forEach { chunk ->
            val tokens = TokenCounter.countTokens(chunk)
            assertTrue(tokens <= 50, "Chunk has $tokens tokens, should be ≤50")
        }
    }

    @Test
    fun `chunkByTokens - with overlap`() {
        val text = "word " * 200  
        val chunks = TokenCounter.chunkByTokens(text, 50, 10)

        assertTrue(chunks.size >= 4, "Should have multiple chunks")

        
        chunks.forEach { chunk ->
            val tokens = TokenCounter.countTokens(chunk)
            assertTrue(tokens <= 50, "Chunk has $tokens tokens, should be ≤50")
        }
    }

    @Test
    fun `chunkByTokens - all text is preserved`() {
        val text = "The quick brown fox jumps over the lazy dog. " * 50
        val chunks = TokenCounter.chunkByTokens(text, 50, 10)

        
        assertTrue(chunks.first().contains("The quick brown fox"))

        
        assertTrue(chunks.last().contains("lazy dog"))
    }

    @Test
    fun `countTokens - empty string`() {
        val tokens = TokenCounter.countTokens("")
        assertEquals(0, tokens)
    }

    @Test
    fun `truncateToTokens - empty string`() {
        val truncated = TokenCounter.truncateToTokens("", 100)
        assertEquals("", truncated)
    }

    @Test
    fun `chunkByTokens - empty string`() {
        val chunks = TokenCounter.chunkByTokens("", 100, 0)
        assertEquals(1, chunks.size)
        assertEquals("", chunks[0])
    }

    @Test
    fun `countTokens - BGE-M3 realistic case`() {
        
        val article = """
            Machine learning is a subset of artificial intelligence that focuses on the
            development of algorithms and statistical models that enable computers to
            perform tasks without explicit instructions. It relies on patterns and
            inference instead. Machine learning algorithms build a model based on sample
            data, known as training data, to make predictions or decisions without being
            explicitly programmed to do so.
        """.trimIndent()

        val tokens = TokenCounter.countTokens(article)

        
        assertTrue(tokens in 60..90, "Expected 60-90 tokens for article, got $tokens")
    }

    @Test
    fun `chunkByTokens - BGE-M3 8192 token limit`() {
        
        val longText = "word " * 10000
        val chunks = TokenCounter.chunkByTokens(longText, 8192, 1638)  

        
        assertTrue(chunks.size >= 2, "Should need at least 2 chunks, got ${chunks.size}")

        chunks.forEach { chunk ->
            val tokens = TokenCounter.countTokens(chunk)
            assertTrue(tokens <= 8192, "Chunk has $tokens tokens, should be ≤8192")
        }
    }
}


private operator fun String.times(n: Int): String = this.repeat(n)
