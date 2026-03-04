package org.datamancy.pipeline.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/**
 * In-memory LRU cache with disk persistence for content deduplication across pipeline runs.
 *
 * **Why Deduplication Matters:**
 * Data sources frequently re-fetch the same content (e.g., RSS feeds include recent articles
 * on every poll, Wikipedia dumps may overlap between versions). Without deduplication:
 * - Embedding service would waste resources generating duplicate vectors
 * - Qdrant would store redundant data (increasing storage and search latency)
 * - Search results would contain duplicate entries (poor user experience)
 * - BookStack would have duplicate pages (confusing knowledge base)
 *
 * **Design Decisions:**
 * - LRU eviction: Keeps recently-seen content in memory while aging out old entries
 *   (avoids unbounded memory growth over months of operation)
 * - Disk persistence: Survives pipeline restarts without re-processing millions of docs
 *   (flush() called periodically by data sources to checkpoint state)
 * - Content-based IDs: Documents are hashed (typically SHA-256) before checking
 *   (enables byte-for-byte deduplication even when metadata differs)
 * - Thread-safe: Synchronized access allows concurrent ingestion from multiple sources
 *
 * **Integration with Pipeline:**
 * Data sources call checkAndMark() before staging documents. Only unseen documents
 * proceed to DocumentStagingStore.stageBatch(), dramatically reducing downstream load.
 *
 * Example: Ingesting 10,000 RSS articles might only yield 50 new documents after
 * deduplication, saving 9,950 embedding API calls and vector storage operations.
 *
 * @property storePath Filesystem path for persistent storage (survives container restarts)
 * @property maxEntries Maximum cache size before LRU eviction (default 10M for ~500MB RAM)
 */
class DeduplicationStore(
    private val storePath: String = "/app/data/dedup",
    private val maxEntries: Int = 10_000_000
) {
    /**
     * LRU cache using LinkedHashMap's access-order mode (3rd constructor parameter = true).
     * When size exceeds maxEntries, eldest entry is automatically removed (least recently used).
     */
    private val seen = object : LinkedHashMap<String, String>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, String>): Boolean {
            val shouldRemove = size > maxEntries
            if (shouldRemove) {
                logger.debug { "LRU eviction: removing ${eldest.key} (size: $size)" }
            }
            return shouldRemove
        }
    }

    // Synchronization lock (LinkedHashMap is not thread-safe)
    private val seenSync = seen

    private val storeFile: File

    init {
        storeFile = File(storePath)
        storeFile.parentFile?.mkdirs()
        loadFromDisk()
    }

    /**
     * Checks if content hash has been seen before (read-only).
     * Thread-safe for concurrent access from multiple data sources.
     *
     * @param hash Content identifier (typically SHA-256 of document text)
     * @return true if this content was previously processed, false if novel
     */
    fun isSeen(hash: String): Boolean {
        return synchronized(seenSync) {
            seen.containsKey(hash)
        }
    }

    /**
     * Records content hash as seen without checking (write-only).
     * Use when you've already verified content is novel via external means.
     *
     * @param hash Content identifier to record
     * @param metadata Optional context (e.g., source name, timestamp) for debugging
     */
    fun markSeen(hash: String, metadata: String = "") {
        synchronized(seenSync) {
            seen[hash] = metadata
        }
    }

    /**
     * Atomically checks if content is novel, then marks it as seen (check-and-set).
     *
     * This is the primary API used by data sources to prevent duplicate processing.
     * The atomic operation prevents race conditions where concurrent threads might
     * both see the same content as "new" and process it twice.
     *
     * Example usage in RSS ingestion:
     * ```
     * val hash = article.text.sha256()
     * if (!dedupStore.checkAndMark(hash, "rss:${article.url}")) {
     *     stagingStore.stage(article)  // Only stage if truly novel
     * }
     * ```
     *
     * @param hash Content identifier (typically SHA-256 of document text)
     * @param metadata Optional context for debugging (e.g., "wikipedia:PageTitle")
     * @return true if content was previously seen (duplicate), false if novel (and now marked)
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
     * Persists in-memory cache to disk for survival across pipeline restarts.
     *
     * Called periodically by data sources (e.g., after ingesting each RSS feed) to
     * checkpoint deduplication state. Without this, pipeline restarts would re-process
     * millions of historical documents.
     *
     * Format: Tab-separated values (hash\tmetadata), one entry per line.
     * File size typically ~100MB for 10M entries with short metadata strings.
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
     * Restores deduplication cache from disk on pipeline startup.
     *
     * If file doesn't exist (first run), starts with empty cache. If file exceeds
     * maxEntries capacity, oldest entries are skipped (LRU eviction during load).
     *
     * This enables the pipeline to "remember" which content has been processed even
     * after container restarts or host reboots, preventing redundant work.
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

                    // Respect maxEntries limit (file may have grown beyond current capacity)
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
     * Returns current cache size (thread-safe).
     * Used by monitoring to track deduplication effectiveness over time.
     */
    fun size(): Int = synchronized(seenSync) { seen.size }

    /**
     * Clears in-memory cache and deletes disk file (destructive).
     *
     * Use only for testing or when intentionally forcing re-processing of all historical
     * content (e.g., after fixing a bug in document extraction logic).
     */
    fun clear() {
        synchronized(seenSync) {
            seen.clear()
            storeFile.delete()
            logger.warn { "Cleared dedup store" }
        }
    }
}
