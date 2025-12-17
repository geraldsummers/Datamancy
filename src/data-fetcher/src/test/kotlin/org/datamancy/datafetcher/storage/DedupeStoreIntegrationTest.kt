package org.datamancy.datafetcher.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class DedupeStoreIntegrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
    }

    private fun createStore(): DedupeStore {
        return DedupeStore(
            host = postgres.host,
            port = postgres.firstMappedPort,
            database = postgres.databaseName,
            user = postgres.username,
            password = postgres.password
        )
    }

    @Test
    fun `can initialize schema`() {
        val store = createStore()
        store.ensureSchema()

        assertTrue(true)
    }

    @Test
    fun `shouldUpsert returns NEW for first item`() {
        val store = createStore()
        store.ensureSchema()

        val result = store.shouldUpsert("test_source", "item1", "hash1", "run1")

        assertEquals(DedupeResult.NEW, result)
    }

    @Test
    fun `shouldUpsert returns UNCHANGED for same hash`() {
        val store = createStore()
        store.ensureSchema()

        store.shouldUpsert("source", "item1", "hash1", "run1")
        val result = store.shouldUpsert("source", "item1", "hash1", "run2")

        assertEquals(DedupeResult.UNCHANGED, result)
    }

    @Test
    fun `shouldUpsert returns UPDATED for different hash`() {
        val store = createStore()
        store.ensureSchema()

        store.shouldUpsert("source", "item1", "hash1", "run1")
        val result = store.shouldUpsert("source", "item1", "hash2", "run2")

        assertEquals(DedupeResult.UPDATED, result)
    }

    @Test
    fun `can retrieve stats for source`() {
        val store = createStore()
        store.ensureSchema()

        store.shouldUpsert("source1", "item1", "hash1", "run1")
        store.shouldUpsert("source1", "item2", "hash2", "run1")
        store.shouldUpsert("source2", "item3", "hash3", "run1")

        val stats = store.getStats("source1")

        assertEquals(2, stats.totalItems)
        assertTrue(stats.lastActivity != null)
    }

    @Test
    fun `multiple runs update last seen time`() {
        val store = createStore()
        store.ensureSchema()

        store.shouldUpsert("source", "item1", "hash1", "run1")

        // Second call with same hash should be UNCHANGED
        val result = store.shouldUpsert("source", "item1", "hash1", "run2")
        assertEquals(DedupeResult.UNCHANGED, result)

        // Stats should still show only 1 item
        val stats = store.getStats("source")
        assertEquals(1, stats.totalItems)
    }
}
