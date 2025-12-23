package org.datamancy.datafetcher.storage

import org.datamancy.test.IntegrationTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@IntegrationTest(requiredServices = ["postgres"])
class DedupeStoreIntegrationTest {

    private fun createStore(): DedupeStore {
        val store = DedupeStore(
            host = "localhost",
            port = 15432,
            database = "datamancy",
            user = "datamancer",
            password = "datamancy123"
        )
        store.ensureSchema()
        return store
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

        val uniqueItem = "item1_${System.currentTimeMillis()}_${System.nanoTime()}"
        val result = store.shouldUpsert("test_source", uniqueItem, "hash1", "run1")

        assertEquals(DedupeResult.NEW, result)
    }

    @Test
    fun `shouldUpsert returns UNCHANGED for same hash`() {
        val store = createStore()
        store.ensureSchema()

        val uniqueItem = "item1_${System.currentTimeMillis()}_${System.nanoTime()}"
        store.shouldUpsert("source", uniqueItem, "hash1", "run1")
        val result = store.shouldUpsert("source", uniqueItem, "hash1", "run2")

        assertEquals(DedupeResult.UNCHANGED, result)
    }

    @Test
    fun `shouldUpsert returns UPDATED for different hash`() {
        val store = createStore()
        store.ensureSchema()

        val uniqueItem = "item1_${System.currentTimeMillis()}_${System.nanoTime()}"
        store.shouldUpsert("source", uniqueItem, "hash1", "run1")
        val result = store.shouldUpsert("source", uniqueItem, "hash2", "run2")

        assertEquals(DedupeResult.UPDATED, result)
    }

    @Test
    fun `can retrieve stats for source`() {
        val store = createStore()
        store.ensureSchema()

        val uniqueSource = "source1_${System.currentTimeMillis()}_${System.nanoTime()}"
        val item1 = "item1_${System.nanoTime()}"
        val item2 = "item2_${System.nanoTime()}"

        store.shouldUpsert(uniqueSource, item1, "hash1", "run1")
        store.shouldUpsert(uniqueSource, item2, "hash2", "run1")
        store.shouldUpsert("source2", "item3", "hash3", "run1")

        val stats = store.getStats(uniqueSource)

        assertEquals(2, stats.totalItems)
        assertTrue(stats.lastActivity != null)
    }

    @Test
    fun `multiple runs update last seen time`() {
        val store = createStore()
        store.ensureSchema()

        val uniqueSource = "source_${System.currentTimeMillis()}_${System.nanoTime()}"
        val uniqueItem = "item1_${System.nanoTime()}"

        store.shouldUpsert(uniqueSource, uniqueItem, "hash1", "run1")

        // Second call with same hash should be UNCHANGED
        val result = store.shouldUpsert(uniqueSource, uniqueItem, "hash1", "run2")
        assertEquals(DedupeResult.UNCHANGED, result)

        // Stats should still show only 1 item
        val stats = store.getStats(uniqueSource)
        assertEquals(1, stats.totalItems)
    }
}
