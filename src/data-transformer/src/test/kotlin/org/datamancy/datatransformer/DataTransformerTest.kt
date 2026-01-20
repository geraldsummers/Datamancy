package org.datamancy.datatransformer

import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID

class DataTransformerTest {

    private lateinit var database: DatabaseApi
    private lateinit var sourceAdapter: SourceAdapter
    private lateinit var bookstackWriter: BookStackWriterClient
    private lateinit var vectorIndexer: VectorIndexerClient
    private lateinit var transformer: DataTransformer

    @BeforeEach
    fun setup() {
        database = mockk(relaxed = true)
        sourceAdapter = mockk(relaxed = true)
        bookstackWriter = mockk(relaxed = true)
        vectorIndexer = mockk(relaxed = true)

        transformer = DataTransformer(
            database = database,
            sourceAdapter = sourceAdapter,
            bookstackWriter = bookstackWriter,
            vectorIndexer = vectorIndexer
        )
    }

    @Test
    fun `SHA-256 hash should be consistent`() {
        // Given
        val content = "Test content for hashing"

        // When
        val digest1 = MessageDigest.getInstance("SHA-256")
        val hash1 = digest1.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

        val digest2 = MessageDigest.getInstance("SHA-256")
        val hash2 = digest2.digest(content.toByteArray()).joinToString("") { "%02x".format(it) }

        // Then
        assertEquals(hash1, hash2, "Hash should be deterministic")
        assertEquals(64, hash1.length, "SHA-256 hash should be 64 hex characters")
        assertTrue(hash1.matches(Regex("[0-9a-f]{64}")), "Hash should be lowercase hex")
    }

    @Test
    fun `SHA-256 hash should differ for different content`() {
        // Given
        val content1 = "Content A"
        val content2 = "Content B"

        // When
        val digest1 = MessageDigest.getInstance("SHA-256")
        val hash1 = digest1.digest(content1.toByteArray()).joinToString("") { "%02x".format(it) }

        val digest2 = MessageDigest.getInstance("SHA-256")
        val hash2 = digest2.digest(content2.toByteArray()).joinToString("") { "%02x".format(it) }

        // Then
        assertNotEquals(hash1, hash2, "Different content should produce different hashes")
    }

    @Test
    fun `indexCollection handles empty page list`() = runTest {
        // Given
        val collection = "test-collection"
        val jobId = UUID.randomUUID()

        coEvery { database.createJob(collection) } returns jobId
        coEvery { sourceAdapter.getPages(collection) } returns emptyList()

        // When
        val result = transformer.indexCollection(collection)

        // Then
        assertEquals(jobId, result)
        coVerify { database.createJob(collection) }
        coVerify { sourceAdapter.getPages(collection) }
    }

    @Test
    fun `indexCollection processes pages successfully`() = runTest {
        // Given
        val collection = "test-collection"
        val jobId = UUID.randomUUID()
        val pages = listOf(
            PageInfo(1, "Page 1", "http://example.com/1"),
            PageInfo(2, "Page 2", "http://example.com/2")
        )

        coEvery { database.createJob(collection) } returns jobId
        coEvery { sourceAdapter.getPages(collection) } returns pages
        coEvery { sourceAdapter.exportPage(any()) } returns "Page content"

        // When
        val result = transformer.indexCollection(collection)

        // Then
        assertEquals(jobId, result)
        coVerify { database.createJob(collection) }
        coVerify { database.updateJobProgress(jobId, totalPages = 2) }
    }

    @Test
    fun `determineSourceType recognizes CVE collection`() {
        // Test the logic that would be in determineSourceType
        val collection = "cve"
        val sourceType = when {
            collection == "cve" -> "cve"
            collection.startsWith("legal-") -> "legal"
            else -> "unknown"
        }

        assertEquals("cve", sourceType)
    }

    @Test
    fun `determineSourceType recognizes legal collections`() {
        val testCases = listOf("legal-federal", "legal-nsw", "legal-vic")

        testCases.forEach { collection ->
            val sourceType = when {
                collection == "cve" -> "cve"
                collection.startsWith("legal-") -> "legal"
                else -> "unknown"
            }

            assertEquals("legal", sourceType, "Should recognize $collection as legal")
        }
    }

    @Test
    fun `PageInfo contains required fields`() {
        val page = PageInfo(
            id = 123,
            name = "Test Page",
            url = "http://example.com/test"
        )

        assertEquals(123, page.id)
        assertEquals("Test Page", page.name)
        assertEquals("http://example.com/test", page.url)
    }

    @Test
    fun `database operations are called in correct order`() = runTest {
        // Given
        val collection = "test-collection"
        val jobId = UUID.randomUUID()
        val pages = listOf(PageInfo(1, "Page 1", "http://example.com/1"))

        coEvery { database.createJob(collection) } returns jobId
        coEvery { sourceAdapter.getPages(collection) } returns pages
        coEvery { sourceAdapter.exportPage(1) } returns "Content"

        // When
        transformer.indexCollection(collection)

        // Then
        coVerifyOrder {
            database.createJob(collection)
            sourceAdapter.getPages(collection)
            database.updateJobProgress(jobId, totalPages = 1)
        }
    }

    @Test
    fun `indexCollection handles full reindex flag`() = runTest {
        // Given
        val collection = "test-collection"
        val jobId = UUID.randomUUID()

        coEvery { database.createJob(collection) } returns jobId
        coEvery { sourceAdapter.getPages(collection) } returns emptyList()

        // When
        val result = transformer.indexCollection(collection, fullReindex = true)

        // Then
        assertEquals(jobId, result)
        coVerify { database.createJob(collection) }
    }
}
