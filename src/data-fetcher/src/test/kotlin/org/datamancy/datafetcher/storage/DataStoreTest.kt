package org.datamancy.datafetcher.storage

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DataStoreTest {

    @Test
    fun `test RawStorageRecord data class`() {
        val now = Clock.System.now()
        val record = RawStorageRecord(
            source = "test_source",
            runId = "run_123",
            itemId = "item_456",
            path = "raw/test_source/2024/01/01/run_123/item_456.json",
            sizeBytes = 1024L,
            storedAt = now
        )

        assertEquals("test_source", record.source)
        assertEquals("run_123", record.runId)
        assertEquals("item_456", record.itemId)
        assertEquals("raw/test_source/2024/01/01/run_123/item_456.json", record.path)
        assertEquals(1024L, record.sizeBytes)
        assertEquals(now, record.storedAt)
    }
}

class FileSystemStoreTest {

    private lateinit var store: FileSystemStore
    private val testBasePath = "/tmp/datafetcher_test_${System.currentTimeMillis()}"

    @Before
    fun setup() {
        // Set test environment variable
        System.setProperty("DATAFETCHER_DATA_PATH", testBasePath)
        store = FileSystemStore()
    }

    @After
    fun cleanup() {
        // Clean up test directory
        File(testBasePath).deleteRecursively()
        System.clearProperty("DATAFETCHER_DATA_PATH")
    }

    @Test
    fun `test storeRaw creates file with canonical path`() {
        val content = "test content".toByteArray()
        val timestamp = Instant.parse("2024-01-15T10:30:00Z")

        val path = store.storeRaw(
            source = "test_source",
            runId = "run_123",
            itemId = "item_456",
            content = content,
            extension = "txt",
            timestamp = timestamp
        )

        assertNotNull(path)
        assertTrue(path.startsWith("raw/test_source/2024/01/15/run_123/"))
        assertTrue(path.endsWith("item_456.txt"))

        val file = File("$testBasePath/$path")
        assertTrue(file.exists())
        assertEquals("test content", file.readText())
    }

    @Test
    fun `test storeRawText creates text file`() {
        val content = "test text content"
        val timestamp = Instant.parse("2024-01-15T10:30:00Z")

        val path = store.storeRawText(
            source = "test_source",
            runId = "run_123",
            itemId = "text_item",
            content = content,
            extension = "txt",
            timestamp = timestamp
        )

        assertNotNull(path)
        assertTrue(path.contains("text_item.txt"))

        val file = File("$testBasePath/$path")
        assertTrue(file.exists())
        assertEquals(content, file.readText())
    }

    @Test
    fun `test readRaw reads stored data`() {
        val content = "read test content".toByteArray()
        val timestamp = Instant.parse("2024-01-15T10:30:00Z")

        val path = store.storeRaw(
            source = "test_source",
            runId = "run_123",
            itemId = "read_item",
            content = content,
            extension = "bin",
            timestamp = timestamp
        )

        val readContent = store.readRaw(path)
        assertEquals("read test content", String(readContent))
    }

    @Test
    fun `test exists returns true for existing file`() {
        val content = "exists test".toByteArray()
        val timestamp = Instant.parse("2024-01-15T10:30:00Z")

        val path = store.storeRaw(
            source = "test_source",
            runId = "run_123",
            itemId = "exists_item",
            content = content,
            extension = "txt",
            timestamp = timestamp
        )

        assertTrue(store.exists(path))
    }

    @Test
    fun `test exists returns false for non-existing file`() {
        val exists = store.exists("raw/nonexistent/path/file.txt")
        assertEquals(false, exists)
    }

    @Test
    fun `test storeRaw sanitizes item ID`() {
        val content = "test".toByteArray()
        val timestamp = Instant.parse("2024-01-15T10:30:00Z")

        val path = store.storeRaw(
            source = "test_source",
            runId = "run_123",
            itemId = "item with spaces/and:special*chars",
            content = content,
            extension = "txt",
            timestamp = timestamp
        )

        assertNotNull(path)
        assertTrue(path.contains("item_with_spaces_and_special_chars.txt"))
    }

    @Test
    fun `test storeRaw creates directory structure`() {
        val content = "dir test".toByteArray()
        val timestamp = Instant.parse("2024-03-20T15:45:30Z")

        val path = store.storeRaw(
            source = "new_source",
            runId = "run_999",
            itemId = "item_001",
            content = content,
            extension = "json",
            timestamp = timestamp
        )

        val file = File("$testBasePath/$path")
        assertTrue(file.parentFile.exists())
        assertTrue(file.parentFile.isDirectory)
    }

    @Test
    fun `test storeRawData legacy method creates file`() {
        store.storeRawData("legacy_category", "legacy_file.txt", "legacy content".toByteArray())

        val file = File("$testBasePath/legacy_category/legacy_file.txt")
        assertTrue(file.exists())
        assertEquals("legacy content", file.readText())
    }

    @Test
    fun `test storeRawText legacy method creates file`() {
        store.storeRawText("legacy_category", "legacy_text.txt", "legacy text content")

        val file = File("$testBasePath/legacy_category/legacy_text.txt")
        assertTrue(file.exists())
        assertEquals("legacy text content", file.readText())
    }
}
