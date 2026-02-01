package org.datamancy.pipeline.sources

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.datamancy.pipeline.core.Source
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URL
import java.util.zip.GZIPInputStream

private val logger = KotlinLogging.logger {}

/**
 * Fetches torrent metadata from torrents-csv dataset
 *
 * Current dataset: https://codeberg.org/heretic/torrents-csv-data
 * Direct download: https://codeberg.org/heretic/torrents-csv-data/raw/branch/main/torrents.csv
 * Old dataset: https://gitlab.com/dessalines/torrents.csv (archived)
 *
 * CSV format: infohash,name,size_bytes,created_unix,seeders,leechers,completed,scraped_date
 */
class TorrentsSource(
    private val dataPath: String,  // Path to torrents.csv or URL
    private val startLine: Long = 0,  // For resuming from checkpoint
    private val maxTorrents: Int = Int.MAX_VALUE
) : Source<TorrentEntry> {
    override val name = "TorrentsSource"

    override suspend fun fetch(): Flow<TorrentEntry> = flow {
        logger.info { "Starting torrent fetch from line $startLine (max: $maxTorrents)" }

        var currentLine = 0L
        var emittedCount = 0
        var parseFailureCount = 0  // Permanent failures (bad data)
        var transientFailureCount = 0  // Transient failures (IO/network)

        try {
            val reader: BufferedReader = when {
                dataPath.startsWith("http://") || dataPath.startsWith("https://") -> {
                    logger.info { "Fetching torrents.csv from URL: $dataPath" }
                    val connection = java.net.URI(dataPath).toURL().openConnection()
                    val inputStream = connection.getInputStream()

                    // Check if gzipped
                    val stream = if (dataPath.endsWith(".gz")) {
                        GZIPInputStream(inputStream)
                    } else {
                        inputStream
                    }

                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                }
                else -> {
                    logger.info { "Reading torrents.csv from file: $dataPath" }
                    val file = File(dataPath)
                    if (!file.exists()) {
                        logger.error { "Torrents data file not found: $dataPath" }
                        return@flow
                    }

                    val inputStream = file.inputStream()
                    val stream = if (dataPath.endsWith(".gz")) {
                        GZIPInputStream(inputStream)
                    } else {
                        inputStream
                    }

                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                }
            }

            reader.use { br ->
                // Skip header
                var line = br.readLine()
                if (line != null) {
                    currentLine++
                    logger.debug { "Header: $line" }
                }

                // Skip to start position if resuming
                while (currentLine < startLine) {
                    line = br.readLine()
                    if (line == null) {
                        logger.warn { "Reached end of file before startLine $startLine" }
                        return@flow
                    }
                    currentLine++
                }

                // Process torrents
                while (emittedCount < maxTorrents) {
                    line = br.readLine()
                    if (line == null) {
                        logger.info { "Reached end of file" }
                        break
                    }

                    currentLine++

                    try {
                        val torrent = parseTorrentLine(line, currentLine)
                        if (torrent != null) {
                            emit(torrent)
                            emittedCount++

                            if (emittedCount % 10000 == 0) {
                                logger.info { "Processed $emittedCount torrents (line $currentLine), parse failures: $parseFailureCount, transient: $transientFailureCount" }
                            }
                        }
                    } catch (e: java.io.IOException) {
                        // Transient failures (network, IO) - don't count toward fail-fast
                        transientFailureCount++
                        logger.warn(e) { "Transient failure at line $currentLine: ${e.message}" }
                    } catch (e: NumberFormatException) {
                        // Parse failures (bad data) - count toward fail-fast
                        parseFailureCount++
                        logger.error(e) { "Failed to parse torrent at line $currentLine: ${e.message}" }

                        // Fail fast if PARSE failure rate exceeds 10% after processing at least 100 records
                        val totalProcessed = emittedCount + parseFailureCount
                        if (totalProcessed >= 100 && parseFailureCount.toDouble() / totalProcessed > 0.10) {
                            val failureRate = "%.2f%%".format(parseFailureCount.toDouble() / totalProcessed * 100)
                            throw IllegalStateException("Torrent parsing failure rate too high: $parseFailureCount/$totalProcessed ($failureRate). Aborting to prevent data corruption.")
                        }
                    } catch (e: Exception) {
                        // Unknown failures - treat as parse failures for safety
                        parseFailureCount++
                        logger.error(e) { "Unknown error parsing torrent at line $currentLine: ${e.message}" }

                        val totalProcessed = emittedCount + parseFailureCount
                        if (totalProcessed >= 100 && parseFailureCount.toDouble() / totalProcessed > 0.10) {
                            val failureRate = "%.2f%%".format(parseFailureCount.toDouble() / totalProcessed * 100)
                            throw IllegalStateException("Torrent parsing failure rate too high: $parseFailureCount/$totalProcessed ($failureRate). Aborting to prevent data corruption.")
                        }
                    }
                }
            }

            logger.info { "Torrent fetch complete: $emittedCount torrents emitted, parse failures: $parseFailureCount, transient failures: $transientFailureCount" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch torrents: ${e.message}" }
        }
    }

    private fun parseTorrentLine(line: String, lineNumber: Long): TorrentEntry? {
        if (line.isBlank()) return null

        // CSV parsing with proper escaping
        val parts = parseCSVLine(line)

        if (parts.size < 3) {
            logger.warn { "Invalid torrent line $lineNumber: not enough fields" }
            return null
        }

        return try {
            TorrentEntry(
                infohash = parts[0].trim(),
                name = parts[1].trim(),
                sizeBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
                createdUnix = parts.getOrNull(3)?.toLongOrNull(),
                seeders = parts.getOrNull(4)?.toIntOrNull() ?: 0,
                leechers = parts.getOrNull(5)?.toIntOrNull() ?: 0,
                completed = parts.getOrNull(6)?.toIntOrNull() ?: 0,
                scrapedDate = parts.getOrNull(7)?.toLongOrNull(),
                lineNumber = lineNumber
            )
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse torrent line $lineNumber: $line" }
            null
        }
    }

    /**
     * RFC 4180 compliant CSV parser that handles quoted fields
     * Properly handles escaped quotes ("") per CSV standard
     */
    private fun parseCSVLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val char = line[i]

            when {
                char == '"' -> {
                    if (inQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        // Escaped quote ("") - add one quote and skip next
                        current.append('"')
                        i++  // Skip the second quote
                    } else {
                        // Toggle quote state
                        inQuotes = !inQuotes
                    }
                }
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current.clear()
                }
                else -> {
                    current.append(char)
                }
            }

            i++
        }

        // Add last field
        result.add(current.toString())

        return result
    }
}

data class TorrentEntry(
    val infohash: String,
    val name: String,
    val sizeBytes: Long,
    val createdUnix: Long?,
    val seeders: Int,
    val leechers: Int,
    val completed: Int,
    val scrapedDate: Long?,
    val lineNumber: Long  // For checkpointing
) {
    fun toText(): String {
        return buildString {
            appendLine("# $name")
            appendLine()
            appendLine("**Infohash:** $infohash")
            appendLine("**Size:** ${formatBytes(sizeBytes)}")

            if (createdUnix != null) {
                appendLine("**Created:** ${java.time.Instant.ofEpochSecond(createdUnix)}")
            }

            appendLine("**Seeders:** $seeders | **Leechers:** $leechers | **Completed:** $completed")

            if (scrapedDate != null) {
                appendLine("**Last Scraped:** ${java.time.Instant.ofEpochSecond(scrapedDate)}")
            }

            appendLine()
            appendLine("**Magnet Link:** magnet:?xt=urn:btih:$infohash")
        }
    }

    /**
     * Content hash for deduplication
     */
    fun contentHash(): String {
        return infohash  // Infohash is already a unique identifier
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
