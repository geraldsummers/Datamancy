package org.datamancy.pipeline.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * Hash-based deduplication store with LRU eviction
 *
 * NOTE: This implementation uses file-based storage, NOT PostgreSQL.
 * The PostgreSQL tables `dedupe_records` and `fetch_history` are legacy/unused.
 *
 * Architecture:
 * - In-memory: ConcurrentHashMap with LRU eviction for fast lookups
 * - Persistence: Flat file at /app/data/dedup with tab-separated hash\tmetadata
 * - Performance: O(1) lookups, periodic flush to disk
 * - Memory: Max 10M entries (configurable) - oldest entries evicted when full
 *
 * If PostgreSQL-backed deduplication is needed for multi-instance deployments,
 * implement a PostgresDeduplicationStore that writes to the existing schema.
 */
class DeduplicationStore(
    private val storePath: String = "/app/data/dedup",
    private val maxEntries: Int = 10_000_000  // 10M entries ~ 500MB RAM
) {
    private val seen = object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean {
            val shouldRemove = size > maxEntries
            if (shouldRemove) {
                logger.debug { "LRU eviction: removing ${eldest.key} (size: $size)" }
            }
            return shouldRemove
        }
    }

    // Wrap LinkedHashMap with synchronization for thread safety
    private val seenSync = seen

    private val storeFile: File

    init {
        storeFile = File(storePath)
        storeFile.parentFile?.mkdirs()
        loadFromDisk()
    }

    /**
     * Check if content with this hash has been seen before (thread-safe)
     */
    fun isSeen(hash: String): Boolean {
        return synchronized(seenSync) {
            seen.containsKey(hash)
        }
    }

    /**
     * Mark content with this hash as seen (thread-safe)
     * @param hash Content hash
     * @param metadata Optional metadata (e.g., ID, timestamp)
     */
    fun markSeen(hash: String, metadata: String = "") {
        synchronized(seenSync) {
            seen[hash] = metadata
        }
    }

    /**
     * Check and mark in one atomic operation (thread-safe with LRU)
     * @return true if was already seen, false if newly marked
     */
    fun checkAndMark(hash: String, metadata: String = ""): Boolean {
        return synchronized(seenSync) {
            val wasSeen = seen.containsKey(hash)
            if (!wasSeen) {
                seen[hash] = metadata
            }
            wasSeen
        }
    }

    /**
     * Persist to disk (thread-safe)
     */
    fun flush() {
        try {
            synchronized(seenSync) {
                storeFile.bufferedWriter().use { writer ->
                    seen.forEach { (hash, metadata) ->
                        writer.write("$hash\t$metadata\n")
                    }
                }
                logger.debug { "Flushed ${seen.size} dedup entries to disk (max: $maxEntries)" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to flush dedup store to disk: ${e.message}" }
        }
    }

    /**
     * Load from disk (called during init, before multi-threading)
     */
    private fun loadFromDisk() {
        try {
            if (!storeFile.exists()) {
                logger.info { "Dedup store file not found, starting fresh: ${storeFile.absolutePath}" }
                return
            }

            var count = 0
            var skipped = 0
            storeFile.forEachLine { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.isNotEmpty()) {
                    val hash = parts[0]
                    val metadata = parts.getOrNull(1) ?: ""

                    // Only load up to maxEntries (most recent ones due to file ordering)
                    if (count < maxEntries) {
                        seen[hash] = metadata
                        count++
                    } else {
                        skipped++
                    }
                }
            }

            logger.info { "Loaded $count dedup entries from disk (max: $maxEntries, skipped: $skipped)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load dedup store from disk: ${e.message}" }
        }
    }

    /**
     * Get current size (thread-safe)
     */
    fun size(): Int = synchronized(seenSync) { seen.size }

    /**
     * Clear all entries (use with caution, thread-safe)
     */
    fun clear() {
        synchronized(seenSync) {
            seen.clear()
            storeFile.delete()
            logger.warn { "Cleared dedup store" }
        }
    }
}
