package org.datamancy.pipeline.storage

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.io.File
import java.time.Instant

/**
 * Unit tests for DocumentStagingStore with PostgreSQL + Exposed
 * Uses in-memory H2 database for fast testing without Docker
 */
class DocumentStagingStoreTest {

    companion object {
        private lateinit var testDb: Database
        private lateinit var stagingStore: DocumentStagingStore

        @BeforeAll
        @JvmStatic
        fun setupDatabase() {
            // Use H2 in-memory database for testing
            stagingStore = DocumentStagingStore(
                jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                user = "sa",
                dbPassword = ""
            )
        }

        @AfterAll
        @JvmStatic
        fun teardownDatabase() {
            stagingStore.close()
        }
    }

    @BeforeEach
    fun clearDatabase() = runBlocking {
        // Clear the database before each test
        // (Exposed will handle schema creation automatically)
    }

    @Test
    fun `StagedDocument data class has correct properties`() {
        val doc = StagedDocument(
            id = "test-id",
            source = "test-source",
            collection = "test-collection",
            text = "test content",
            metadata = mapOf("key" to "value"),
            embeddingStatus = EmbeddingStatus.PENDING
        )

        assertEquals("test-id", doc.id)
        assertEquals("test-source", doc.source)
        assertEquals("test-collection", doc.collection)
        assertEquals("test content", doc.text)
        assertEquals(EmbeddingStatus.PENDING, doc.embeddingStatus)
        assertEquals("value", doc.metadata["key"])
    }

    @Test
    fun `StagedDocument supports chunking metadata`() {
        val doc = StagedDocument(
            id = "chunk-1",
            source = "test",
            collection = "test",
            text = "chunk content",
            metadata = emptyMap(),
            embeddingStatus = EmbeddingStatus.PENDING,
            chunkIndex = 2,
            totalChunks = 10
        )

        assertEquals(2, doc.chunkIndex)
        assertEquals(10, doc.totalChunks)
    }

    @Test
    fun `EmbeddingStatus enum has all required states`() {
        val statuses = EmbeddingStatus.values()

        assertTrue(statuses.contains(EmbeddingStatus.PENDING))
        assertTrue(statuses.contains(EmbeddingStatus.IN_PROGRESS))
        assertTrue(statuses.contains(EmbeddingStatus.COMPLETED))
        assertTrue(statuses.contains(EmbeddingStatus.FAILED))
    }

    @Test
    fun `stageBatch should insert documents into database`() = runBlocking {
        val docs = listOf(
            StagedDocument(
                id = "test-1",
                source = "test-source",
                collection = "test-collection",
                text = "test content 1",
                metadata = mapOf("key" to "value1"),
                embeddingStatus = EmbeddingStatus.PENDING
            ),
            StagedDocument(
                id = "test-2",
                source = "test-source",
                collection = "test-collection",
                text = "test content 2",
                metadata = mapOf("key" to "value2"),
                embeddingStatus = EmbeddingStatus.PENDING
            )
        )

        stagingStore.stageBatch(docs)

        // Verify by getting pending batch
        val pending = stagingStore.getPendingBatch(10)
        assertTrue(pending.size >= 2, "Should have at least 2 pending documents")
    }

    @Test
    fun `getPendingBatch should return pending documents`() = runBlocking {
        // Insert test documents
        val docs = listOf(
            StagedDocument(
                id = "pending-1",
                source = "test",
                collection = "test",
                text = "pending content",
                metadata = emptyMap(),
                embeddingStatus = EmbeddingStatus.PENDING
            )
        )
        stagingStore.stageBatch(docs)

        // Get pending documents
        val pending = stagingStore.getPendingBatch(10)
        assertTrue(pending.isNotEmpty(), "Should have pending documents")
    }

    @Test
    fun `updateStatus should change document status`() = runBlocking {
        // Insert test document
        val doc = StagedDocument(
            id = "status-test",
            source = "test",
            collection = "test",
            text = "content",
            metadata = emptyMap(),
            embeddingStatus = EmbeddingStatus.PENDING
        )
        stagingStore.stageBatch(listOf(doc))

        // Update status
        stagingStore.updateStatus("status-test", EmbeddingStatus.COMPLETED)

        // Verify - pending batch should not include completed doc
        val pending = stagingStore.getPendingBatch(10)
        val hasStatusTest = pending.any { it.id == "status-test" }
        assertFalse(hasStatusTest, "Completed document should not be in pending batch")
    }

    @Test
    fun `getStats should return correct counts`() = runBlocking {
        // Insert documents with different statuses
        val docs = listOf(
            StagedDocument("stat-1", "test", "test", "text", emptyMap(), EmbeddingStatus.PENDING),
            StagedDocument("stat-2", "test", "test", "text", emptyMap(), EmbeddingStatus.PENDING),
            StagedDocument("stat-3", "test", "test", "text", emptyMap(), EmbeddingStatus.COMPLETED)
        )
        stagingStore.stageBatch(docs)
        stagingStore.updateStatus("stat-3", EmbeddingStatus.COMPLETED)

        val stats = stagingStore.getStats()
        assertTrue(stats["pending"]!! >= 2, "Should have at least 2 pending")
        assertTrue(stats["completed"]!! >= 1, "Should have at least 1 completed")
    }

    @Test
    fun `StagedDocument handles special characters`() {
        val doc = StagedDocument(
            id = "special-chars",
            source = "source-with-'quotes'",
            collection = "collection",
            text = "Content with <html> & 'quotes'",
            metadata = mapOf("key" to "value with 'quotes'"),
            embeddingStatus = EmbeddingStatus.PENDING
        )

        assertTrue(doc.text.contains("<html>"))
        assertTrue(doc.metadata["key"]!!.contains("'quotes'"))
    }

    @Test
    fun `stageBatch handles empty list gracefully`() = runBlocking {
        // Should not throw exception
        stagingStore.stageBatch(emptyList())
    }
}
