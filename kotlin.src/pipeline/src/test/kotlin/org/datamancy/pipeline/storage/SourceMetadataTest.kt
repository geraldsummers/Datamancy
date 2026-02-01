package org.datamancy.pipeline.storage

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceMetadataTest {

    @Test
    fun `test load returns default metadata for non-existent source`(@TempDir tempDir: Path) {
        val store = SourceMetadataStore(tempDir.toString())

        val metadata = store.load("non-existent")

        assertEquals("non-existent", metadata.sourceName)
        assertNull(metadata.lastSuccessfulRun)
        assertEquals(0L, metadata.totalItemsProcessed)
        assertEquals(0L, metadata.totalItemsFailed)
        assertEquals(0, metadata.consecutiveFailures)
    }

    @Test
    fun `test save and load metadata`(@TempDir tempDir: Path) {
        val store = SourceMetadataStore(tempDir.toString())

        val metadata = SourceMetadata(
            sourceName = "test-source",
            lastSuccessfulRun = "2024-01-01T00:00:00.000Z",
            lastAttemptedRun = "2024-01-01T00:00:00.000Z",
            totalItemsProcessed = 100,
            totalItemsFailed = 5,
            consecutiveFailures = 0,
            sourceVersion = "1.0",
            checkpointData = mapOf("lastId" to "12345")
        )

        store.save(metadata)

        val loaded = store.load("test-source")

        assertEquals("test-source", loaded.sourceName)
        assertEquals("2024-01-01T00:00:00.000Z", loaded.lastSuccessfulRun)
        assertEquals(100L, loaded.totalItemsProcessed)
        assertEquals(5L, loaded.totalItemsFailed)
        assertEquals(0, loaded.consecutiveFailures)
        assertEquals(mapOf("lastId" to "12345"), loaded.checkpointData)
    }

    @Test
    fun `test recordSuccess updates metadata correctly`(@TempDir tempDir: Path) {
        val store = SourceMetadataStore(tempDir.toString())

        store.recordSuccess("test-source", 50, 2, mapOf("checkpoint" to "abc"))

        val metadata = store.load("test-source")

        assertEquals("test-source", metadata.sourceName)
        assertNotNull(metadata.lastSuccessfulRun)
        assertNotNull(metadata.lastAttemptedRun)
        assertEquals(50L, metadata.totalItemsProcessed)
        assertEquals(2L, metadata.totalItemsFailed)
        assertEquals(0, metadata.consecutiveFailures)
        assertEquals(mapOf("checkpoint" to "abc"), metadata.checkpointData)
    }

    @Test
    fun `test recordSuccess accumulates totals`(@TempDir tempDir: Path) {
        val store = SourceMetadataStore(tempDir.toString())

        store.recordSuccess("test-source", 50, 2)
        store.recordSuccess("test-source", 30, 1)
        store.recordSuccess("test-source", 20, 0)

        val metadata = store.load("test-source")

        assertEquals(100L, metadata.totalItemsProcessed)
        assertEquals(3L, metadata.totalItemsFailed)
    }

    @Test
    fun `test recordSuccess resets consecutive failures`(@TempDir tempDir: Path) {
        val store = SourceMetadataStore(tempDir.toString())

        // Record some failures first
        store.recordFailure("test-source")
        store.recordFailure("test-source")
        store.recordFailure("test-source")

        var metadata = store.load("test-source")
        assertEquals(3, metadata.consecutiveFailures)

        // Record success
        store.recordSuccess("test-source", 10, 0)

        metadata = store.load("test-source")
        assertEquals(0, metadata.consecutiveFailures)
    }

    @Test
    fun `test recordFailure increments consecutive failures`(@TempDir tempDir: Path) {
        val store = SourceMetadataStore(tempDir.toString())

        store.recordFailure("test-source")
        var metadata = store.load("test-source")
        assertEquals(1, metadata.consecutiveFailures)

        store.recordFailure("test-source")
        metadata = store.load("test-source")
        assertEquals(2, metadata.consecutiveFailures)

        store.recordFailure("test-source")
        metadata = store.load("test-source")
        assertEquals(3, metadata.consecutiveFailures)
    }

    @Test
    fun `test recordFailure updates lastAttemptedRun`(@TempDir tempDir: Path) {
        val store = SourceMetadataStore(tempDir.toString())

        val before = Instant.now()
        store.recordFailure("test-source")
        val after = Instant.now()

        val metadata = store.load("test-source")

        assertNotNull(metadata.lastAttemptedRun)
        val attemptTime = Instant.parse(metadata.lastAttemptedRun)
        assertTrue(attemptTime >= before && attemptTime <= after)
    }

    @Test
    fun `test listAll returns all sources`(@TempDir tempDir: Path) {
        val store = SourceMetadataStore(tempDir.toString())

        store.recordSuccess("source1", 10, 0)
        store.recordSuccess("source2", 20, 1)
        store.recordSuccess("source3", 30, 2)

        val all = store.listAll()

        assertEquals(3, all.size)
        val sourceNames = all.map { it.sourceName }.toSet()
        assertTrue(sourceNames.contains("source1"))
        assertTrue(sourceNames.contains("source2"))
        assertTrue(sourceNames.contains("source3"))
    }

    @Test
    fun `test listAll returns empty list when no sources exist`(@TempDir tempDir: Path) {
        val store = SourceMetadataStore(tempDir.toString())

        val all = store.listAll()

        assertEquals(0, all.size)
    }

    @Test
    fun `test checkpoint data is preserved across updates`(@TempDir tempDir: Path) {
        val store = SourceMetadataStore(tempDir.toString())

        store.recordSuccess("test-source", 10, 0, mapOf("index" to "100"))
        store.recordSuccess("test-source", 10, 0, mapOf("index" to "200"))

        val metadata = store.load("test-source")

        // Last checkpoint should be preserved
        assertEquals(mapOf("index" to "200"), metadata.checkpointData)
        assertEquals(20L, metadata.totalItemsProcessed)
    }

    @Test
    fun `test empty checkpoint data is handled`(@TempDir tempDir: Path) {
        val store = SourceMetadataStore(tempDir.toString())

        store.recordSuccess("test-source", 10, 0, emptyMap())

        val metadata = store.load("test-source")

        assertEquals(emptyMap(), metadata.checkpointData)
    }

    @Test
    fun `test metadata persists across store instances`(@TempDir tempDir: Path) {
        val storePath = tempDir.toString()

        // Create first store and save data
        val store1 = SourceMetadataStore(storePath)
        store1.recordSuccess("test-source", 100, 5, mapOf("key" to "value"))

        // Create second store (simulates restart)
        val store2 = SourceMetadataStore(storePath)
        val metadata = store2.load("test-source")

        assertEquals(100L, metadata.totalItemsProcessed)
        assertEquals(5L, metadata.totalItemsFailed)
        assertEquals(mapOf("key" to "value"), metadata.checkpointData)
    }
}
