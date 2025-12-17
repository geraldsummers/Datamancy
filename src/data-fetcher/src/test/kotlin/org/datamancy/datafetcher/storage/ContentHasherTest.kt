package org.datamancy.datafetcher.storage

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ContentHasherTest {

    @Test
    fun `hashJson produces consistent SHA-256 hash`() {
        val json = """{"title":"Test","value":123}"""
        val hash1 = ContentHasher.hashJson(json)
        val hash2 = ContentHasher.hashJson(json)

        assertEquals(hash1, hash2, "Same content should produce same hash")
        assertEquals(64, hash1.length, "SHA-256 hash should be 64 characters")
    }

    @Test
    fun `hashJson produces different hashes for different content`() {
        val json1 = """{"title":"Test1"}"""
        val json2 = """{"title":"Test2"}"""

        val hash1 = ContentHasher.hashJson(json1)
        val hash2 = ContentHasher.hashJson(json2)

        assertNotEquals(hash1, hash2, "Different content should produce different hashes")
    }

    @Test
    fun `hashJson is order-sensitive for JSON strings`() {
        val json1 = """{"a":"1","b":"2"}"""
        val json2 = """{"b":"2","a":"1"}"""

        val hash1 = ContentHasher.hashJson(json1)
        val hash2 = ContentHasher.hashJson(json2)

        assertNotEquals(hash1, hash2, "Different order produces different hashes for raw strings")
    }

    @Test
    fun `hashJson handles empty string`() {
        val hash = ContentHasher.hashJson("")
        assertEquals(64, hash.length, "Hash of empty string should still be 64 characters")
    }

    @Test
    fun `hashJson handles large content`() {
        val largeContent = "x".repeat(100000)
        val hash = ContentHasher.hashJson(largeContent)
        assertEquals(64, hash.length, "Hash of large content should be 64 characters")
    }
}
