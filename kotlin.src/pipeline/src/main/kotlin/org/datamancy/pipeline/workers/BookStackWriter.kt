package org.datamancy.pipeline.workers

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.datamancy.pipeline.sinks.BookStackDocument
import org.datamancy.pipeline.sinks.BookStackSink
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.EmbeddingStatus
import org.datamancy.pipeline.storage.StagedDocument
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}


class BookStackWriter(
    private val stagingStore: DocumentStagingStore,
    private val bookStackSink: BookStackSink,
    private val pollIntervalSeconds: Long = 5,
    private val batchSize: Int = 200,
    private val maxRetries: Int = 3
) {
    
    suspend fun start() {
        logger.info { "BookStack writer starting (poll interval: ${pollIntervalSeconds}s, batch size: $batchSize)" }

        while (true) {
            try {
                
                val pendingDocs = stagingStore.getPendingForBookStack(limit = batchSize)

                if (pendingDocs.isEmpty()) {
                    logger.debug { "No pending BookStack writes" }
                    delay(pollIntervalSeconds.seconds)
                    continue
                }


                coroutineScope {
                    pendingDocs.map { doc ->
                        async {
                            try {
                                // Check if document has exceeded retry limit
                                if (doc.retryCount >= maxRetries) {
                                    logger.warn { "Document ${doc.id} exceeded max BookStack retries (${doc.retryCount}/$maxRetries), skipping" }
                                    return@async
                                }

                                // Apply exponential backoff if this is a retry
                                if (doc.retryCount > 0) {
                                    val backoffSeconds = 2.0.pow(doc.retryCount.toDouble()).toLong()
                                    logger.debug { "Applying backoff for ${doc.id}: ${backoffSeconds}s (retry ${doc.retryCount}/$maxRetries)" }
                                    delay(backoffSeconds * 1000)
                                }


                                val bookStackDoc = toBookStackDocument(doc)


                                bookStackSink.write(bookStackDoc)


                                val pageUrl = bookStackSink.getLastPageUrl(bookStackDoc.pageTitle)


                                if (pageUrl != null) {
                                    stagingStore.updateBookStackUrl(doc.id, pageUrl)
                                }


                                stagingStore.markBookStackComplete(doc.id)

                                logger.debug { "Wrote document ${doc.id} to BookStack: $pageUrl" }

                            } catch (e: Exception) {
                                logger.error(e) { "Failed to write document ${doc.id} to BookStack (attempt ${doc.retryCount + 1}/$maxRetries): ${e.message}" }


                                stagingStore.markBookStackFailed(doc.id, e.message ?: "Unknown error")
                            }
                        }
                    }.awaitAll()
                }

            } catch (e: Exception) {
                logger.error(e) { "Error in BookStack writer loop: ${e.message}" }
            }

            
            delay(pollIntervalSeconds.seconds)
        }
    }

    
    private fun toBookStackDocument(doc: StagedDocument): BookStackDocument {
        
        val title = doc.metadata["title"] ?: doc.id

        
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

    
    private fun buildTags(doc: StagedDocument): Map<String, String> {
        val tags = mutableMapOf<String, String>()

        
        tags["source"] = doc.source

        
        tags["collection"] = doc.collection

        
        if (doc.chunkIndex != null && doc.totalChunks != null) {
            tags["chunk"] = "${doc.chunkIndex + 1}/${doc.totalChunks}"
        }

        
        doc.metadata["url"]?.let { tags["url"] = it }

        
        doc.metadata["published_at"]?.let { tags["published"] = it }

        return tags
    }
}
