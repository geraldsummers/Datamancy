package org.datamancy.pipeline.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Simple hash-based deduplication store using local file persistence
 */
class DeduplicationStore(
    private val storePath: String = "/app/data/dedup"
) {
    private val seen = ConcurrentHashMap<String, String>()
    private val storeFile: File

    init {
        storeFile = File(storePath)
        storeFile.parentFile?.mkdirs()
        loadFromDisk()
    }

    /**
     * Check if content with this hash has been seen before
     */
    fun isSeen(hash: String): Boolean {
        return seen.containsKey(hash)
    }

    /**
     * Mark content with this hash as seen
     * @param hash Content hash
     * @param metadata Optional metadata (e.g., ID, timestamp)
     */
    fun markSeen(hash: String, metadata: String = "") {
        seen[hash] = metadata
    }

    /**
     * Check and mark in one operation
     * @return true if was already seen, false if newly marked
     */
    fun checkAndMark(hash: String, metadata: String = ""): Boolean {
        val wasSeen = seen.containsKey(hash)
        if (!wasSeen) {
            seen[hash] = metadata
        }
        return wasSeen
    }

    /**
     * Persist to disk
     */
    fun flush() {
        try {
            storeFile.bufferedWriter().use { writer ->
                seen.forEach { (hash, metadata) ->
                    writer.write("$hash\t$metadata\n")
                }
            }
            logger.debug { "Flushed ${seen.size} dedup entries to disk" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to flush dedup store to disk: ${e.message}" }
        }
    }

    /**
     * Load from disk
     */
    private fun loadFromDisk() {
        try {
            if (!storeFile.exists()) {
                logger.info { "Dedup store file not found, starting fresh: ${storeFile.absolutePath}" }
                return
            }

            var count = 0
            storeFile.forEachLine { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.isNotEmpty()) {
                    val hash = parts[0]
                    val metadata = parts.getOrNull(1) ?: ""
                    seen[hash] = metadata
                    count++
                }
            }

            logger.info { "Loaded $count dedup entries from disk" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load dedup store from disk: ${e.message}" }
        }
    }

    /**
     * Get current size
     */
    fun size(): Int = seen.size

    /**
     * Clear all entries (use with caution!)
     */
    fun clear() {
        seen.clear()
        storeFile.delete()
        logger.warn { "Cleared dedup store" }
    }
}
