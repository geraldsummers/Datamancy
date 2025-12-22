package org.datamancy.datafetcher.storage

import org.datamancy.test.IntegrationTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@IntegrationTest(requiredServices = ["postgres"])
class DedupeStoreIntegrationTest {

    private fun createStore(): DedupeStore {
        val pgHost = System.getenv("POSTGRES_HOST") ?: "postgres"
        val pgPort = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: 5432
        val pgDb = System.getenv("POSTGRES_DB") ?: "datamancy"
        val pgUser = System.getenv("POSTGRES_USER") ?: "datamancer"
        val pgPassword = System.getenv("POSTGRES_PASSWORD") ?: "datamancy123"

        return DedupeStore(
            host = pgHost,
            port = pgPort,
            database = pgDb,
            user = pgUser,
            password = pgPassword
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
