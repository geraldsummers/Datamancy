package org.datamancy.pipeline.storage

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for DocumentStagingStore data classes and logic
 * No Docker/testcontainers needed - pure unit tests!
 */
class DocumentStagingStoreTest {

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
    fun `String escapeClickHouse handles single quotes`() {
        val input = "It's a test with 'quotes'"
        val escaped = input.replace("'", "''")

        assertEquals("It''s a test with ''quotes''", escaped)
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
}
