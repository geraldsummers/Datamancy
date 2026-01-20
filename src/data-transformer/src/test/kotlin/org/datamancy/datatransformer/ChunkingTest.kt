package org.datamancy.datatransformer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ChunkingTest {

    @Test
    fun `chunkCVE with small CVE returns single chunk`() {
        val smallCVE = """
            # CVE-2024-12345
            **Severity:** HIGH
            **CVSS Score:** 7.5

            ## Description
            A vulnerability was discovered in the authentication system.
        """.trimIndent()

        val chunks = ChunkingStrategy.chunkCVE(smallCVE, maxSize = 3000)

        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals(1, chunks[0].totalChunks)
        assertTrue(chunks[0].content.contains("CVE-2024-12345"))
    }

    @Test
    fun `chunkCVE with large affected products splits correctly`() {
        val largeCVE = buildString {
            appendLine("# CVE-2024-99999")
            appendLine("**Severity:** CRITICAL")
            appendLine()
            appendLine("## Description")
            appendLine("Critical vulnerability description here.")
            appendLine()
            appendLine("## Affected Products")
            repeat(100) { i ->
                appendLine("- cpe:2.3:a:vendor:product:$i:*:*:*:*:*:*:*")
            }
        }

        val chunks = ChunkingStrategy.chunkCVE(largeCVE, maxSize = 1000)

        assertTrue(chunks.size > 1, "Large CVE should be split into multiple chunks")
        // First chunk should contain description
        assertTrue(chunks[0].content.contains("Description"))
        // Later chunks should contain affected products
        assertTrue(chunks.any { it.content.contains("Affected Products") })
    }

    @Test
    fun `chunkCVE preserves CVE metadata in first chunk`() {
        val cveContent = """
            # CVE-2024-12345
            **Severity:** HIGH
            **CVSS Score:** 7.5
            **Weakness Types (CWE):** CWE-79

            ## Description
            XSS vulnerability description.

            ## Affected Products
            - Product 1
            - Product 2
        """.trimIndent()

        val chunks = ChunkingStrategy.chunkCVE(cveContent)

        assertTrue(chunks[0].content.contains("CVE-2024-12345"))
        assertTrue(chunks[0].content.contains("Severity"))
        assertTrue(chunks[0].content.contains("CVSS Score"))
    }

    @Test
    fun `chunkContent routes CVE source type to chunkCVE`() {
        val cveContent = "# CVE-2024-12345\n\nDescription of vulnerability"

        val chunks = ChunkingStrategy.chunkContent("cve", cveContent, "CVE-2024-12345")

        assertNotNull(chunks)
        assertTrue(chunks.isNotEmpty())
        assertEquals(1, chunks.size) // Small CVE should be single chunk
    }

    @Test
    fun `chunkContent routes legal source type to chunkBySection`() {
        val legalContent = """
            # Legislation Title

            ## Section 1
            Content of section 1.

            ## Section 2
            Content of section 2.
        """.trimIndent()

        val chunks = ChunkingStrategy.chunkContent("legal", legalContent, "Legislation")

        assertNotNull(chunks)
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun `chunkBySection splits by markdown headers`() {
        val content = """
            # Main Title

            ## Section 1
            This is section 1 content with some text.

            ## Section 2
            This is section 2 content with more text.
        """.trimIndent()

        val chunks = ChunkingStrategy.chunkBySection(content, maxSize = 200)

        assertTrue(chunks.size >= 2, "Should split into multiple sections")
        assertTrue(chunks.any { it.content.contains("Section 1") })
        assertTrue(chunks.any { it.content.contains("Section 2") })
    }

    @Test
    fun `chunkByParagraph splits by double newlines`() {
        val content = """
            First paragraph with some content.

            Second paragraph with more content.

            Third paragraph with even more content.
        """.trimIndent()

        val chunks = ChunkingStrategy.chunkByParagraph(content, maxSize = 50)

        assertTrue(chunks.size >= 2, "Should split into multiple chunks")
        chunks.forEach { chunk ->
            assertTrue(chunk.totalChunks == chunks.size)
        }
    }

    @Test
    fun `chunkByCharacterLimit splits long content`() {
        val longContent = "A".repeat(5000)

        val chunks = ChunkingStrategy.chunkByCharacterLimit(longContent, maxSize = 1500, overlap = 100)

        // Should split into multiple chunks
        assertTrue(chunks.size > 1, "Long content should be split into multiple chunks")

        // Each chunk should be reasonable size
        chunks.forEach { chunk ->
            assertTrue(chunk.content.isNotEmpty(), "Chunk should not be empty")
            // Allow some flexibility for overlap and word boundaries
            assertTrue(chunk.content.length <= 2000, "Chunk should be reasonable size: ${chunk.content.length}")
        }
    }

    @Test
    fun `chunks have correct indices and total count`() {
        val content = """
            # Title

            ## Section 1
            Content 1

            ## Section 2
            Content 2

            ## Section 3
            Content 3
        """.trimIndent()

        val chunks = ChunkingStrategy.chunkBySection(content, maxSize = 100)

        assertEquals(chunks.size, chunks[0].totalChunks)
        chunks.forEachIndexed { index, chunk ->
            assertEquals(index, chunk.chunkIndex)
            assertEquals(chunks.size, chunk.totalChunks)
        }
    }

    @Test
    fun `contentSnippet is limited to 200 characters`() {
        val longContent = "A".repeat(500)

        val chunks = ChunkingStrategy.chunkByCharacterLimit(longContent, maxSize = 1500)

        chunks.forEach { chunk ->
            assertTrue(chunk.contentSnippet.length <= 200, "Snippet should be at most 200 chars")
        }
    }

    @Test
    fun `chunkCVE handles empty content gracefully`() {
        val emptyContent = ""

        val chunks = ChunkingStrategy.chunkCVE(emptyContent)

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.isEmpty())
    }

    @Test
    fun `chunkCVE handles CVE without affected products`() {
        val cveNoProducts = """
            # CVE-2024-11111
            **Severity:** MEDIUM

            ## Description
            A minor vulnerability was discovered.

            ## References
            - https://example.com/ref1
        """.trimIndent()

        val chunks = ChunkingStrategy.chunkCVE(cveNoProducts)

        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("CVE-2024-11111"))
        assertFalse(chunks[0].content.contains("## Affected Products"))
    }

    @Test
    fun `chunkByHeading preserves markdown structure`() {
        val content = """
            # Main Heading

            Content under main heading.

            ## Subheading 1
            Content under subheading 1.

            ### Sub-subheading
            Nested content.
        """.trimIndent()

        val chunks = ChunkingStrategy.chunkByHeading(content, maxSize = 150)

        assertTrue(chunks.isNotEmpty())
        // Should preserve heading markers
        assertTrue(chunks.any { it.content.contains("#") })
    }

    @Test
    fun `chunkByCodeBlock creates chunks from content`() {
        val simpleContent = "Some text\n\nMore text\n\nEven more text"

        val chunks = ChunkingStrategy.chunkByCodeBlock(simpleContent, maxSize = 500)

        // Should create at least one chunk
        assertTrue(chunks.isNotEmpty(), "Should create at least one chunk")
        assertTrue(chunks[0].content.isNotEmpty(), "Chunk should have content")

        // Small content should fit in one chunk
        assertEquals(1, chunks.size, "Simple content should fit in one chunk")
    }
}
