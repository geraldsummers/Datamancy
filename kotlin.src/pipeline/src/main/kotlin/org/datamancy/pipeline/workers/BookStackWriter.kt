package org.datamancy.pipeline.workers

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import org.datamancy.pipeline.sinks.BookStackDocument
import org.datamancy.pipeline.sinks.BookStackSink
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.EmbeddingStatus
import org.datamancy.pipeline.storage.StagedDocument
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}

/**
 * Dedicated worker for writing documents to BookStack
 *
 * Runs independently from the main pipeline, polling the staging store
 * for documents that need to be written to BookStack.
 *
 * Benefits:
 * - Decoupled from StandardizedRunner (cleaner separation of concerns)
 * - Independent retry logic and error handling
 * - Can scale separately (run multiple BookStackWriter instances)
 * - Failed BookStack writes don't block the main pipeline
 *
 * Usage:
 * ```
 * val bookStackWriter = BookStackWriter(
 *     stagingStore = stagingStore,
 *     bookStackSink = bookStackSink,
 *     pollIntervalSeconds = 5,
 *     batchSize = 50
 * )
 *
 * launch {
 *     bookStackWriter.start()
 * }
 * ```
 */
class BookStackWriter(
    private val stagingStore: DocumentStagingStore,
    private val bookStackSink: BookStackSink,
    private val pollIntervalSeconds: Long = 5,
    private val batchSize: Int = 50
) {
    /**
     * Start the BookStack writer worker
     * Continuously polls for pending documents and writes them to BookStack
     */
    suspend fun start() {
        logger.info { "BookStack writer starting (poll interval: ${pollIntervalSeconds}s, batch size: $batchSize)" }

        while (true) {
            try {
                // Get pending documents from staging store
                val pendingDocs = stagingStore.getPendingForBookStack(limit = batchSize)

                if (pendingDocs.isEmpty()) {
                    logger.debug { "No pending BookStack writes" }
                    delay(pollIntervalSeconds.seconds)
                    continue
                }

                // Process each document
                pendingDocs.forEach { doc ->
                    try {
                        // Convert staged document to BookStack document
                        val bookStackDoc = toBookStackDocument(doc)

                        // Write to BookStack
                        bookStackSink.write(bookStackDoc)

                        // Mark as completed
                        stagingStore.markBookStackComplete(doc.id)

                        logger.debug { "Wrote document ${doc.id} to BookStack" }

                    } catch (e: Exception) {
                        logger.error(e) { "Failed to write document ${doc.id} to BookStack: ${e.message}" }

                        // Mark as failed
                        stagingStore.markBookStackFailed(doc.id, e.message ?: "Unknown error")
                    }
                }

            } catch (e: Exception) {
                logger.error(e) { "Error in BookStack writer loop: ${e.message}" }
            }

            // Wait before next poll
            delay(pollIntervalSeconds.seconds)
        }
    }

    /**
     * Convert a staged document to a BookStack document
     */
    private fun toBookStackDocument(doc: StagedDocument): BookStackDocument {
        // Extract title from metadata or use document ID
        val title = doc.metadata["title"] ?: doc.id

        // Determine book name based on source
        val bookName = when (doc.source) {
            "rss" -> "RSS Articles"
            "cve" -> "CVE Database"
            "torrents" -> "Torrent Index"
            "wikipedia" -> "Wikipedia Articles"
            "australian_laws" -> "Australian Legal Corpus"
            "linux_docs" -> "Linux Documentation"
            "debian_wiki" -> "Debian Wiki"
            "arch_wiki" -> "Arch Wiki"
            else -> "Knowledge Base"
        }

        return BookStackDocument(
            pageTitle = title,
            pageContent = doc.text,
            bookName = bookName,
            chapterName = doc.source.replaceFirstChar { it.titlecase() },
            tags = buildTags(doc)
        )
    }

    /**
     * Build BookStack tags from document metadata
     */
    private fun buildTags(doc: StagedDocument): Map<String, String> {
        val tags = mutableMapOf<String, String>()

        // Add source tag
        tags["source"] = doc.source

        // Add collection tag
        tags["collection"] = doc.collection

        // Add chunk info if present
        if (doc.chunkIndex != null && doc.totalChunks != null) {
            tags["chunk"] = "${doc.chunkIndex + 1}/${doc.totalChunks}"
        }

        // Add URL if present in metadata
        doc.metadata["url"]?.let { tags["url"] = it }

        // Add date if present in metadata
        doc.metadata["published_at"]?.let { tags["published"] = it }

        return tags
    }
}
