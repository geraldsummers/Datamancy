package org.datamancy.pipeline.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}


class DeduplicationStore(
    private val storePath: String = "/app/data/dedup",
    private val maxEntries: Int = 10_000_000  
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

    
    private val seenSync = seen

    private val storeFile: File

    init {
        storeFile = File(storePath)
        storeFile.parentFile?.mkdirs()
        loadFromDisk()
    }

    
    fun isSeen(hash: String): Boolean {
        return synchronized(seenSync) {
            seen.containsKey(hash)
        }
    }

    
    fun markSeen(hash: String, metadata: String = "") {
        synchronized(seenSync) {
            seen[hash] = metadata
        }
    }

    
    fun checkAndMark(hash: String, metadata: String = ""): Boolean {
        return synchronized(seenSync) {
            val wasSeen = seen.containsKey(hash)
            if (!wasSeen) {
                seen[hash] = metadata
            }
            wasSeen
        }
    }

    
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

    
    fun size(): Int = synchronized(seenSync) { seen.size }

    
    fun clear() {
        synchronized(seenSync) {
            seen.clear()
            storeFile.delete()
            logger.warn { "Cleared dedup store" }
        }
    }
}
