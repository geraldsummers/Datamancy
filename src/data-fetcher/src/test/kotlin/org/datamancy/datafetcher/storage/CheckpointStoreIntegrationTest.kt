package org.datamancy.datafetcher.storage

import org.datamancy.test.IntegrationTest
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

@IntegrationTest(requiredServices = ["postgres"])
class CheckpointStoreIntegrationTest {

    private lateinit var store: CheckpointStore

    @BeforeEach
    fun setup() {
        store = CheckpointStore(
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
    fun `can store and retrieve checkpoint`() {
        val testKey = "test_key_${System.nanoTime()}"
        store.set("test_source", testKey, "test_value")

        assertEquals("test_value", store.get("test_source", testKey))
    }

    @Test
    fun `get returns null for non-existent checkpoint`() {
        assertNull(store.get("non_existent_${System.nanoTime()}", "key"))
    }

    @Test
    fun `can update existing checkpoint`() {
        val testKey = "key_${System.nanoTime()}"
        store.set("source", testKey, "value1")
        store.set("source", testKey, "value2")

        assertEquals("value2", store.get("source", testKey))
    }

    @Test
    fun `can delete checkpoint`() {
        val testKey = "key_${System.nanoTime()}"
        store.set("source", testKey, "value")
        store.delete("source", testKey)

        assertNull(store.get("source", testKey))
    }

    @Test
    fun `can retrieve all checkpoints for source`() {
        val uniqueSource = "source_${System.nanoTime()}"
        val key1 = "key1"
        val key2 = "key2"

        store.set(uniqueSource, key1, "value1")
        store.set(uniqueSource, key2, "value2")

        val checkpoints = store.getAll(uniqueSource)

        assertEquals(2, checkpoints.size)
        assertEquals("value1", checkpoints[key1])
        assertEquals("value2", checkpoints[key2])
    }

    @Test
    fun `getAll returns empty map for source with no checkpoints`() {
        val checkpoints = store.getAll("empty_source_${System.nanoTime()}")

        assertTrue(checkpoints.isEmpty())
    }
}
