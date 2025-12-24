package org.datamancy.datafetcher.storage

import org.datamancy.test.IntegrationTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@IntegrationTest(requiredServices = ["postgres"])
class DedupeStoreIntegrationTest {

    private lateinit var store: DedupeStore

    @BeforeEach
    fun setup() {
        store = DedupeStore(
            host = "localhost",
            port = 15432,
            database = "datamancy",
            user = "datamancer",
            password = "wUPAptqQ0dRE-uA-JMc0hgTX2L2Va1fx"
        )
        store.ensureSchema()
    }

    @AfterEach
    fun teardown() {
        store.close()
    }

    @Test
    fun `shouldUpsert returns NEW for first item`() {
        val uniqueItem = "item1_${System.nanoTime()}"
        val result = store.shouldUpsert("test_source", uniqueItem, "hash1", "run1")

        assertEquals(DedupeResult.NEW, result)
    }

    @Test
    fun `shouldUpsert returns UNCHANGED for same hash`() {
        val uniqueItem = "item1_${System.nanoTime()}"
        store.shouldUpsert("source", uniqueItem, "hash1", "run1")
        val result = store.shouldUpsert("source", uniqueItem, "hash1", "run2")

        assertEquals(DedupeResult.UNCHANGED, result)
    }

    @Test
    fun `shouldUpsert returns UPDATED for different hash`() {
        val uniqueItem = "item1_${System.nanoTime()}"
        store.shouldUpsert("source", uniqueItem, "hash1", "run1")
        val result = store.shouldUpsert("source", uniqueItem, "hash2", "run2")

        assertEquals(DedupeResult.UPDATED, result)
    }

    @Test
    fun `can retrieve stats for source`() {
        val uniqueSource = "source1_${System.nanoTime()}"
        val item1 = "item1"
        val item2 = "item2"

        store.shouldUpsert(uniqueSource, item1, "hash1", "run1")
        store.shouldUpsert(uniqueSource, item2, "hash2", "run1")

        val stats = store.getStats(uniqueSource)

        assertEquals(2, stats.totalItems)
        assertNotNull(stats.lastActivity)
    }

    @Test
    fun `multiple runs update last seen time`() {
        val uniqueSource = "source_${System.nanoTime()}"
        val uniqueItem = "item1"

        store.shouldUpsert(uniqueSource, uniqueItem, "hash1", "run1")
        val result = store.shouldUpsert(uniqueSource, uniqueItem, "hash1", "run2")

        assertEquals(DedupeResult.UNCHANGED, result)

        val stats = store.getStats(uniqueSource)
        assertEquals(1, stats.totalItems)
    }
}
