package org.datamancy.datatransformer

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class DataTransformerTest {

    private lateinit var database: DatabaseApi
    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var indexer: DataTransformer

    @BeforeEach
    fun setup() {
        database = mockk(relaxed = true)
        sourceAdapter = mockk(relaxed = true)
        indexer = DataTransformer(
            database = database,
            sourceAdapter = sourceAdapter,
            qdrantUrl = "http://localhost:6334",
            clickhouseUrl = "http://localhost:8123",
            embeddingServiceUrl = "http://localhost:8080",
            batchSize = 10,
            maxConcurrency = 2
        )
    }

    @Test
    fun `computeContentHash should return consistent SHA-256 hash`() {
        // Given
        val content = "Test content for hashing"

        // When
        val hash1 = indexer.computeContentHash(content)
        val hash2 = indexer.computeContentHash(content)

        // Then
        assertEquals(hash1, hash2, "Hash should be deterministic")
        assertEquals(64, hash1.length, "SHA-256 hash should be 64 hex characters")
        assertTrue(hash1.matches(Regex("[0-9a-f]{64}")), "Hash should be lowercase hex")
    }

    @Test
    fun `computeContentHash should return different hashes for different content`() {
        // Given
        val content1 = "Content A"
        val content2 = "Content B"

        // When
        val hash1 = indexer.computeContentHash(content1)
        val hash2 = indexer.computeContentHash(content2)

        // Then
        assertNotEquals(hash1, hash2, "Different content should produce different hashes")
    }

    @Test
    fun `computeDiff should identify new pages`() = runTest {
        // Given
        val collection = "test-collection"
        val sourcePage1 = PageInfo(1, "Page 1", "http://example.com/1")
        val sourcePage2 = PageInfo(2, "Page 2", "http://example.com/2")
        val sourcePages = listOf(sourcePage1, sourcePage2)

        every { database.getIndexedPages(collection) } returns emptyMap()

        // When
        val diff = indexer.computeDiff(collection, sourcePages)

        // Then
        assertEquals(2, diff.new.size, "Should identify 2 new pages")
        assertEquals(0, diff.modified.size, "Should have no modified pages")
        assertEquals(0, diff.unchanged.size, "Should have no unchanged pages")
        assertEquals(0, diff.deleted.size, "Should have no deleted pages")
        assertTrue(diff.new.contains(sourcePage1))
        assertTrue(diff.new.contains(sourcePage2))
    }

    @Test
    fun `computeDiff should identify deleted pages`() = runTest {
        // Given
        val collection = "test-collection"
        val sourcePages = emptyList<PageInfo>()
        val indexedPages = mapOf(
            1 to "hash1",
            2 to "hash2"
        )

        every { database.getIndexedPages(collection) } returns indexedPages

        // When
        val diff = indexer.computeDiff(collection, sourcePages)

        // Then
        assertEquals(0, diff.new.size, "Should have no new pages")
        assertEquals(0, diff.modified.size, "Should have no modified pages")
        assertEquals(0, diff.unchanged.size, "Should have no unchanged pages")
        assertEquals(2, diff.deleted.size, "Should identify 2 deleted pages")
        assertTrue(diff.deleted.contains(1))
        assertTrue(diff.deleted.contains(2))
    }

    @Test
    fun `computeDiff should identify modified pages`() = runTest {
        // Given
        val collection = "test-collection"
        val sourcePage = PageInfo(1, "Page 1", "http://example.com/1")
        val sourcePages = listOf(sourcePage)
        val newContent = "New content"
        val newHash = indexer.computeContentHash(newContent)
        val oldHash = "oldhash123"
        val indexedPages = mapOf(1 to oldHash)

        every { database.getIndexedPages(collection) } returns indexedPages
        coEvery { sourceAdapter.exportPage(1) } returns newContent

        // When
        val diff = indexer.computeDiff(collection, sourcePages)

        // Then
        assertEquals(0, diff.new.size, "Should have no new pages")
        assertEquals(1, diff.modified.size, "Should identify 1 modified page")
        assertEquals(0, diff.unchanged.size, "Should have no unchanged pages")
        assertEquals(0, diff.deleted.size, "Should have no deleted pages")
        assertTrue(diff.modified.contains(sourcePage))
    }

    @Test
    fun `computeDiff should identify unchanged pages`() = runTest {
        // Given
        val collection = "test-collection"
        val sourcePage = PageInfo(1, "Page 1", "http://example.com/1")
        val sourcePages = listOf(sourcePage)
        val content = "Same content"
        val hash = indexer.computeContentHash(content)
        val indexedPages = mapOf(1 to hash)

        every { database.getIndexedPages(collection) } returns indexedPages
        coEvery { sourceAdapter.exportPage(1) } returns content

        // When
        val diff = indexer.computeDiff(collection, sourcePages)

        // Then
        assertEquals(0, diff.new.size, "Should have no new pages")
        assertEquals(0, diff.modified.size, "Should have no modified pages")
        assertEquals(1, diff.unchanged.size, "Should identify 1 unchanged page")
        assertEquals(0, diff.deleted.size, "Should have no deleted pages")
        assertTrue(diff.unchanged.contains(sourcePage))
    }

    @Test
    fun `computeDiff should handle mixed changes`() = runTest {
        // Given
        val collection = "test-collection"
        val newPage = PageInfo(3, "New Page", "http://example.com/3")
        val unchangedPage = PageInfo(1, "Unchanged", "http://example.com/1")
        val modifiedPage = PageInfo(2, "Modified", "http://example.com/2")

        val sourcePages = listOf(newPage, unchangedPage, modifiedPage)

        val unchangedContent = "Unchanged content"
        val unchangedHash = indexer.computeContentHash(unchangedContent)

        val modifiedContent = "Modified content"
        val oldHash = "oldmodifiedhash"

        val indexedPages = mapOf(
            1 to unchangedHash,
            2 to oldHash,
            4 to "deletedhash"
        )

        every { database.getIndexedPages(collection) } returns indexedPages
        coEvery { sourceAdapter.exportPage(1) } returns unchangedContent
        coEvery { sourceAdapter.exportPage(2) } returns modifiedContent

        // When
        val diff = indexer.computeDiff(collection, sourcePages)

        // Then
        assertEquals(1, diff.new.size, "Should identify 1 new page")
        assertEquals(1, diff.modified.size, "Should identify 1 modified page")
        assertEquals(1, diff.unchanged.size, "Should identify 1 unchanged page")
        assertEquals(1, diff.deleted.size, "Should identify 1 deleted page")

        assertTrue(diff.new.contains(newPage))
        assertTrue(diff.modified.contains(modifiedPage))
        assertTrue(diff.unchanged.contains(unchangedPage))
        assertTrue(diff.deleted.contains(4))
    }

    @Test
    fun `computeDiff should treat export failure as modified`() = runTest {
        // Given
        val collection = "test-collection"
        val sourcePage = PageInfo(1, "Page 1", "http://example.com/1")
        val sourcePages = listOf(sourcePage)
        val indexedPages = mapOf(1 to "somehash")

        every { database.getIndexedPages(collection) } returns indexedPages
        coEvery { sourceAdapter.exportPage(1) } throws Exception("Export failed")

        // When
        val diff = indexer.computeDiff(collection, sourcePages)

        // Then
        assertEquals(0, diff.new.size)
        assertEquals(1, diff.modified.size, "Should treat failed export as modified")
        assertEquals(0, diff.unchanged.size)
        assertEquals(0, diff.deleted.size)
    }
}
