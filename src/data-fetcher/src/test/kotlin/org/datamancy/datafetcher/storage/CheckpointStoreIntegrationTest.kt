package org.datamancy.datafetcher.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class CheckpointStoreIntegrationTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test")
    }

    private fun createStore(): CheckpointStore {
        return CheckpointStore(
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

        // No exception means success
        assertTrue(true)
    }

    @Test
    fun `can store and retrieve checkpoint`() {
        val store = createStore()
        store.ensureSchema()

        store.set("test_source", "test_key", "test_value")
        val value = store.get("test_source", "test_key")

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

        store.set("source", "key", "value1")
        store.set("source", "key", "value2")
        val value = store.get("source", "key")

        assertEquals("value2", value)
    }

    @Test
    fun `can delete checkpoint`() {
        val store = createStore()
        store.ensureSchema()

        store.set("source", "key", "value")
        store.delete("source", "key")
        val value = store.get("source", "key")

        assertNull(value)
    }

    @Test
    fun `can retrieve all checkpoints for source`() {
        val store = createStore()
        store.ensureSchema()

        store.set("source", "key1", "value1")
        store.set("source", "key2", "value2")
        store.set("other_source", "key3", "value3")

        val checkpoints = store.getAll("source")

        assertEquals(2, checkpoints.size)
        assertEquals("value1", checkpoints["key1"])
        assertEquals("value2", checkpoints["key2"])
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
