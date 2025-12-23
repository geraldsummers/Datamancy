package org.datamancy.datafetcher.storage

import org.datamancy.test.IntegrationTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@IntegrationTest(requiredServices = ["postgres"])
class CheckpointStoreIntegrationTest {

    private fun createStore(): CheckpointStore {
        val store = CheckpointStore(
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

        // No exception means success (schema is created in createStore())
        assertTrue(true)
    }

    @Test
    fun `can store and retrieve checkpoint`() {
        val store = createStore()

        val testKey = "test_key_${System.currentTimeMillis()}_${System.nanoTime()}"
        store.set("test_source", testKey, "test_value")
        val value = store.get("test_source", testKey)

        assertEquals("test_value", value)
    }

    @Test
    fun `get returns null for non-existent checkpoint`() {
        val store = createStore()
        store.ensureSchema()

        val value = store.get("non_existent", "key")

        assertNull(value)
    }

    @Test
    fun `can update existing checkpoint`() {
        val store = createStore()
        store.ensureSchema()

        val testKey = "key_${System.currentTimeMillis()}_${System.nanoTime()}"
        store.set("source", testKey, "value1")
        store.set("source", testKey, "value2")
        val value = store.get("source", testKey)

        assertEquals("value2", value)
    }

    @Test
    fun `can delete checkpoint`() {
        val store = createStore()
        store.ensureSchema()

        val testKey = "key_${System.currentTimeMillis()}_${System.nanoTime()}"
        store.set("source", testKey, "value")
        store.delete("source", testKey)
        val value = store.get("source", testKey)

        assertNull(value)
    }

    @Test
    fun `can retrieve all checkpoints for source`() {
        val store = createStore()
        store.ensureSchema()

        val uniqueSource = "source_${System.currentTimeMillis()}_${System.nanoTime()}"
        val key1 = "key1_${System.nanoTime()}"
        val key2 = "key2_${System.nanoTime()}"

        store.set(uniqueSource, key1, "value1")
        store.set(uniqueSource, key2, "value2")
        store.set("other_source", "key3", "value3")

        val checkpoints = store.getAll(uniqueSource)

        assertEquals(2, checkpoints.size)
        assertEquals("value1", checkpoints[key1])
        assertEquals("value2", checkpoints[key2])
        assertFalse(checkpoints.containsKey("key3"))
    }

    @Test
    fun `getAll returns empty map for source with no checkpoints`() {
        val store = createStore()
        store.ensureSchema()

        val checkpoints = store.getAll("empty_source")

        assertTrue(checkpoints.isEmpty())
    }
}
