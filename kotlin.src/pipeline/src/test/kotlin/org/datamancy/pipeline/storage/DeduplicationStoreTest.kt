package org.datamancy.pipeline.storage

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeduplicationStoreTest {

    @Test
    fun `test isSeen returns false for new items`(@TempDir tempDir: Path) {
        val store = DeduplicationStore(tempDir.resolve("dedup.txt").toString(), maxEntries = 20000)

        assertFalse(store.isSeen("hash1"))
        assertFalse(store.isSeen("hash2"))
    }

    @Test
    fun `test markSeen and isSeen`(@TempDir tempDir: Path) {
        val store = DeduplicationStore(tempDir.resolve("dedup.txt").toString(), maxEntries = 20000)

        store.markSeen("hash1", "item1")
        store.markSeen("hash2", "item2")

        assertTrue(store.isSeen("hash1"))
        assertTrue(store.isSeen("hash2"))
        assertFalse(store.isSeen("hash3"))
    }

    @Test
    fun `test checkAndMark returns false for new items`(@TempDir tempDir: Path) {
        val store = DeduplicationStore(tempDir.resolve("dedup.txt").toString(), maxEntries = 20000)

        val wasSeen = store.checkAndMark("hash1", "item1")

        assertFalse(wasSeen, "Should return false for new item")
        assertTrue(store.isSeen("hash1"), "Should now be marked as seen")
    }

    @Test
    fun `test checkAndMark returns true for existing items`(@TempDir tempDir: Path) {
        val store = DeduplicationStore(tempDir.resolve("dedup.txt").toString(), maxEntries = 1000)

        store.markSeen("hash1", "item1")
        val wasSeen = store.checkAndMark("hash1", "item1-updated")

        assertTrue(wasSeen, "Should return true for existing item")
    }

    @Test
    fun `test flush persists data to disk`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("dedup.txt").toString()
        val store = DeduplicationStore(storePath, maxEntries = 20000)

        store.markSeen("hash1", "item1")
        store.markSeen("hash2", "item2")
        store.markSeen("hash3", "item3")

        store.flush()

        
        val store2 = DeduplicationStore(storePath, maxEntries = 20000)

        assertTrue(store2.isSeen("hash1"))
        assertTrue(store2.isSeen("hash2"))
        assertTrue(store2.isSeen("hash3"))
        assertEquals(3, store2.size())
    }

    @Test
    fun `test persistence with metadata`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("dedup.txt").toString()
        val store = DeduplicationStore(storePath, maxEntries = 20000)

        store.markSeen("hash1", "CVE-2024-1234")
        store.markSeen("hash2", "CVE-2024-5678")
        store.flush()

        
        val store2 = DeduplicationStore(storePath, maxEntries = 20000)
        assertTrue(store2.isSeen("hash1"))
        assertTrue(store2.isSeen("hash2"))
    }

    @Test
    fun `test size returns correct count`(@TempDir tempDir: Path) {
        val store = DeduplicationStore(tempDir.resolve("dedup.txt").toString(), maxEntries = 20000)

        assertEquals(0, store.size())

        store.markSeen("hash1", "item1")
        assertEquals(1, store.size())

        store.markSeen("hash2", "item2")
        store.markSeen("hash3", "item3")
        assertEquals(3, store.size())
    }

    @Test
    fun `test clear removes all entries`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("dedup.txt").toString()
        val store = DeduplicationStore(storePath, maxEntries = 20000)

        store.markSeen("hash1", "item1")
        store.markSeen("hash2", "item2")
        store.flush()

        assertEquals(2, store.size())

        store.clear()

        assertEquals(0, store.size())
        assertFalse(store.isSeen("hash1"))
        assertFalse(store.isSeen("hash2"))
    }

    @Test
    fun `test handles many items`(@TempDir tempDir: Path) {
        val store = DeduplicationStore(tempDir.resolve("dedup.txt").toString(), maxEntries = 20000)

        repeat(10000) {
            store.markSeen("hash$it", "item$it")
        }

        assertEquals(10000, store.size())
        assertTrue(store.isSeen("hash0"))
        assertTrue(store.isSeen("hash5000"))
        assertTrue(store.isSeen("hash9999"))
        assertFalse(store.isSeen("hash10000"))
    }

    @Test
    fun `test concurrent access to same hash`(@TempDir tempDir: Path) {
        val store = DeduplicationStore(tempDir.resolve("dedup.txt").toString(), maxEntries = 20000)

        
        val results = List(10) {
            store.checkAndMark("same-hash", "metadata")
        }

        
        assertEquals(1, results.count { !it })
        assertEquals(9, results.count { it })
    }

    @Test
    fun `test empty metadata is handled`(@TempDir tempDir: Path) {
        val storePath = tempDir.resolve("dedup.txt").toString()
        val store = DeduplicationStore(storePath, maxEntries = 20000)

        store.markSeen("hash1")
        store.markSeen("hash2", "")
        store.flush()

        val store2 = DeduplicationStore(storePath, maxEntries = 20000)
        assertTrue(store2.isSeen("hash1"))
        assertTrue(store2.isSeen("hash2"))
    }
}
