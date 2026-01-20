package org.datamancy.datatransformer

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import java.security.MessageDigest
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Data Transformer - Pure orchestration service
 *
 * Responsibilities:
 * 1. Read raw data from ClickHouse via source adapters
 * 2. Apply intelligent chunking strategies
 * 3. Delegate to data-bookstack-writer for wiki population
 * 4. Delegate to data-vector-indexer for vector indexing
 * 5. Track job progress in PostgreSQL
 *
 * Does NOT:
 * - Talk to Qdrant directly
 * - Generate embeddings directly
 * - Write to BookStack directly
 */
class DataTransformer(
    private val database: DatabaseApi,
    private val sourceAdapter: SourceAdapter,
    private val bookstackWriter: BookStackWriterClient,
    private val vectorIndexer: VectorIndexerClient
) {
    private val batchSize = 32

    /**
     * Index a collection with full diff detection and incremental updates.
     * This is the NEW pure orchestration implementation.
     */
    suspend fun indexCollection(collectionName: String, fullReindex: Boolean = false): UUID = coroutineScope {
        logger.info { "🚀 Starting indexing for collection: $collectionName (fullReindex=$fullReindex)" }
        val jobId = database.createJob(collectionName)

        try {
            // Step 1: Fetch pages from source
            logger.info { "📖 Fetching pages from source adapter for $collectionName..." }
            val pages = sourceAdapter.getPages(collectionName)
            database.updateJobProgress(jobId, totalPages = pages.size)
            logger.info { "Found ${pages.size} pages to process" }

            if (pages.isEmpty()) {
                logger.warn { "No pages found for collection: $collectionName" }
                database.markJobComplete(jobId)
                return@coroutineScope jobId
            }

            // Ensure vector collection exists
            vectorIndexer.ensureCollection(collectionName, 768)

            // Step 2: Diff detection (if not full reindex)
            val pagesToProcess = if (fullReindex) {
                logger.info { "Full reindex requested - processing all ${pages.size} pages" }
                pages
            } else {
                logger.info { "Performing diff detection..." }
                detectChanges(jobId, pages)
            }

            logger.info { "Processing ${pagesToProcess.size} pages (${pages.size - pagesToProcess.size} unchanged)" }

            // Step 3: Process pages in batches
            pagesToProcess.chunked(batchSize).forEachIndexed { batchIndex, batch ->
                logger.info { "Processing batch ${batchIndex + 1}/${(pagesToProcess.size + batchSize - 1) / batchSize}" }

                batch.forEach { page ->
                    try {
                        processPage(jobId, collectionName, page)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to process page ${page.id}: ${page.name}" }
                        database.recordError(jobId, page.id, e.message ?: "Unknown error")
                    }
                }
            }

            // Step 4: Mark job complete
            database.markJobComplete(jobId)
            logger.info { "✅ Completed indexing for collection: $collectionName" }

            jobId
        } catch (e: Exception) {
            logger.error(e) { "❌ Indexing failed for collection: $collectionName" }
            database.markJobFailed(jobId, e.message ?: "Unknown error")
            throw e
        }
    }

    /**
     * Process a single page through the pipeline:
     * 1. Export content from source
     * 2. Compute content hash for change detection
     * 3. Apply chunking strategy
     * 4. Write to BookStack
     * 5. Index chunks in vector store
     */
    private suspend fun processPage(jobId: UUID, collectionName: String, page: PageInfo) {
        logger.debug { "Processing page ${page.id}: ${page.name}" }

        // Step 1: Export page content
        val content = sourceAdapter.exportPage(page.id)
        val contentHash = computeHash(content)

        // Check if content changed
        val existingHash = database.getContentHash(page.id)
        if (existingHash == contentHash) {
            logger.debug { "Page ${page.id} unchanged, skipping" }
            database.incrementIndexedPages(jobId)
            return
        }

        // Step 2: Determine source type and apply chunking
        val sourceType = determineSourceType(collectionName)
        val chunks = ChunkingStrategy.chunkContent(sourceType, content, page.name)

        logger.info { "📄 Page ${page.id} chunked into ${chunks.size} pieces" }

        // Step 3: Write to BookStack
        val bookstackResult = try {
            bookstackWriter.createOrUpdatePage(
                sourceType = sourceType,
                category = collectionName,
                title = page.name,
                content = content,
                metadata = mapOf(
                    "source_url" to page.url,
                    "fetched_at" to Clock.System.now().toString(),
                    "content_hash" to contentHash
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to write page ${page.id} to BookStack" }
            throw e
        }

        if (!bookstackResult.success) {
            throw Exception("BookStack write failed: ${bookstackResult.error}")
        }

        logger.info { "📚 Written to BookStack: ${bookstackResult.bookstackUrl}" }

        // Step 4: Index chunks in vector store
        val chunkPayloads = chunks.mapIndexed { idx, chunk ->
            ChunkPayload(
                id = "${page.id}_$idx".hashCode(),
                chunkIndex = chunk.chunkIndex,
                totalChunks = chunk.totalChunks,
                content = chunk.content,
                contentSnippet = chunk.contentSnippet,
                bookstackUrl = bookstackResult.bookstackUrl,
                bookstackPageId = bookstackResult.bookstackPageId,
                sourceType = sourceType,
                category = collectionName,
                title = page.name,
                originalUrl = page.url,
                fetchedAt = Clock.System.now().toString(),
                metadata = mapOf("chunk_hash" to computeHash(chunk.content))
            )
        }

        val indexingResult = try {
            vectorIndexer.indexChunks(collectionName, chunkPayloads)
        } catch (e: Exception) {
            logger.error(e) { "Failed to index chunks for page ${page.id}" }
            throw e
        }

        if (indexingResult.failed > 0) {
            logger.warn { "Some chunks failed to index: ${indexingResult.failed}/${chunks.size}" }
        }

        logger.info { "🔍 Indexed ${indexingResult.indexed} chunks in vector store" }

        // Step 5: Update database tracking
        database.upsertIndexedPage(
            IndexedPage(
                pageId = page.id,
                collectionName = collectionName,
                contentHash = contentHash,
                embeddingVersion = "bge-base-en-v1.5"
            )
        )

        database.incrementIndexedPages(jobId)
    }

    /**
     * Detect which pages have changed since last index
     */
    private suspend fun detectChanges(jobId: UUID, pages: List<PageInfo>): List<PageInfo> {
        val changedPages = mutableListOf<PageInfo>()

        pages.forEach { page ->
            val currentHash = try {
                val content = sourceAdapter.exportPage(page.id)
                computeHash(content)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch page ${page.id} for diff check" }
                database.recordError(jobId, page.id, "Failed to fetch: ${e.message}")
                return@forEach
            }

            val existingHash = database.getContentHash(page.id)

            if (existingHash != currentHash) {
                changedPages.add(page)
            }
        }

        return changedPages
    }

    /**
     * Determine source type from collection name
     */
    private fun determineSourceType(collectionName: String): String {
        return when {
            collectionName == "cve" -> "cve"
            collectionName.startsWith("legal-") -> "legal"
            collectionName.startsWith("rss-") -> "rss"
            collectionName.startsWith("wiki-") -> "wiki"
            collectionName.startsWith("docs-") -> "docs"
            else -> "unknown"
        }
    }

    /**
     * Compute SHA-256 hash of content
     */
    private fun computeHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}

data class PageDiff(
    val new: List<PageInfo>,
    val updated: List<PageInfo>,
    val deleted: List<Int>
)
