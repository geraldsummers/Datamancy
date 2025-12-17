package org.datamancy.unifiedindexer

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.oshai.kotlinlogging.KotlinLogging
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections.*
import io.qdrant.client.grpc.Points.*
import io.qdrant.client.ValueFactory.value
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.VectorsFactory.vectors
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

private val logger = KotlinLogging.logger {}

const val EMBEDDING_VERSION = "all-MiniLM-L6-v2-v1"

/**
 * Unified Indexer with batch processing, diff detection, resumability, and progress tracking.
 */
class UnifiedIndexer(
    private val database: DatabaseApi,
    private val sourceAdapter: SourceAdapter,
    private val qdrantUrl: String,
    private val clickhouseUrl: String,
    private val embeddingServiceUrl: String,
    private val batchSize: Int = 32,
    private val maxConcurrency: Int = 4
) {
    private val clickhouseUser = System.getenv("CLICKHOUSE_USER") ?: "default"
    private val clickhousePassword = System.getenv("CLICKHOUSE_PASSWORD") ?: ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Qdrant client
    private val qdrantHost = qdrantUrl.removePrefix("http://").removePrefix("https://").split(":")[0]
    private val qdrantPort = qdrantUrl.removePrefix("http://").removePrefix("https://").split(":").getOrNull(1)?.toIntOrNull() ?: 6334
    private val qdrant = QdrantClient(QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false).build())

    /**
     * Index a collection with full diff detection and incremental updates.
     */
    suspend fun indexCollection(collectionName: String, fullReindex: Boolean = false): UUID {
        logger.info { "Starting indexing for collection: $collectionName (fullReindex=$fullReindex)" }
        val jobId = database.createJob(collectionName)

        try {
            // Ensure storage backends exist
            ensureQdrantCollection(collectionName)
            ensureClickHouseTable(collectionName)

            // Get all pages from source
            val allPages = sourceAdapter.getPages(collectionName)
            logger.info { "Found ${allPages.size} pages in source for $collectionName" }

            // Compute diff
            val pagesToIndex = if (fullReindex) {
                logger.info { "Full reindex requested, indexing all pages" }
                allPages
            } else {
                val diff = computeDiff(collectionName, allPages)
                logger.info {
                    "Diff computed: ${diff.new.size} new, ${diff.modified.size} modified, " +
                    "${diff.unchanged.size} unchanged, ${diff.deleted.size} deleted"
                }

                // Clean up deleted pages
                diff.deleted.forEach { pageId ->
                    deletePageFromStorage(collectionName, pageId)
                }

                diff.new + diff.modified
            }

            database.updateJobProgress(jobId, 0, pagesToIndex.size, null)

            if (pagesToIndex.isEmpty()) {
                logger.info { "No pages to index for $collectionName" }
                database.completeJob(jobId, "completed", null)
                return jobId
            }

            // Index in batches
            var indexed = 0
            pagesToIndex.chunked(batchSize).forEach { batch ->
                try {
                    indexBatch(collectionName, batch, jobId)
                    indexed += batch.size
                    database.updateJobProgress(jobId, indexed, pagesToIndex.size, null)
                    logger.info { "Progress: $indexed/${pagesToIndex.size} pages indexed" }
                } catch (e: Exception) {
                    logger.error(e) { "Failed to index batch" }
                    // Continue with next batch
                }
                delay(100) // Rate limiting
            }

            database.completeJob(jobId, "completed", null)
            logger.info { "Completed indexing $collectionName: $indexed/${pagesToIndex.size} pages" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to index collection: $collectionName" }
            database.completeJob(jobId, "failed", e.message)
            database.logError(jobId, null, null, e.message ?: "Unknown error", e.stackTraceToString())
        }

        return jobId
    }

    /**
     * Compute diff between source and indexed pages.
     */
    internal suspend fun computeDiff(collectionName: String, sourcePages: List<PageInfo>): PageDiff {
        val indexedPages = database.getIndexedPages(collectionName)
        val sourcePageIds = sourcePages.map { it.id }.toSet()
        val indexedPageIds = indexedPages.keys

        val new = sourcePages.filter { it.id !in indexedPageIds }
        val deleted = (indexedPageIds - sourcePageIds).toList()

        // Check content hashes for existing pages
        val modified = mutableListOf<PageInfo>()
        val unchanged = mutableListOf<PageInfo>()

        sourcePages.filter { it.id in indexedPageIds }.forEach { page ->
            try {
                val content = sourceAdapter.exportPage(page.id)
                val currentHash = computeContentHash(content)
                val storedHash = indexedPages[page.id]

                if (currentHash != storedHash) {
                    modified.add(page)
                } else {
                    unchanged.add(page)
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to check diff for page ${page.id}, treating as modified" }
                modified.add(page)
            }
        }

        return PageDiff(new, modified, unchanged, deleted)
    }

    /**
     * Index a batch of pages in parallel.
     */
    private suspend fun indexBatch(collectionName: String, pages: List<PageInfo>, jobId: UUID) = coroutineScope {
        // Fetch content in parallel
        val contents = pages.map { page ->
            async {
                try {
                    Pair(page, sourceAdapter.exportPage(page.id))
                } catch (e: Exception) {
                    logger.error(e) { "Failed to export page ${page.id}" }
                    database.logError(jobId, page.id, page.name, e.message ?: "Export failed", e.stackTraceToString())
                    null
                }
            }
        }.awaitAll().filterNotNull()

        if (contents.isEmpty()) return@coroutineScope

        // Batch embedding generation
        val texts = contents.map { it.second }
        val embeddings = try {
            generateBatchEmbeddings(texts)
        } catch (e: Exception) {
            logger.error(e) { "Failed to generate batch embeddings" }
            // Fallback to single embeddings
            texts.map { text ->
                try {
                    generateEmbedding(text)
                } catch (ex: Exception) {
                    logger.error(ex) { "Failed to generate embedding" }
                    emptyList()
                }
            }
        }

        // Store in parallel with concurrency control
        val semaphore = Semaphore(maxConcurrency)
        val tasks = contents.zip(embeddings).map { (pageContent, embedding) ->
            async {
                semaphore.withPermit {
                    val (page, content) = pageContent
                    try {
                        if (embedding.isNotEmpty()) {
                            storeInQdrant(collectionName, page, content, embedding)
                        }
                        storeInClickHouse(collectionName, page, content)

                        // Record in database
                        database.upsertIndexedPage(IndexedPage(
                            pageId = page.id,
                            collectionName = collectionName,
                            contentHash = computeContentHash(content),
                            embeddingVersion = EMBEDDING_VERSION
                        ))
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to store page ${page.id}" }
                        database.logError(jobId, page.id, page.name, e.message ?: "Storage failed", e.stackTraceToString())
                    }
                }
            }
        }
        tasks.awaitAll()
    }

    /**
     * Generate embeddings for multiple texts in a single batch request.
     */
    private fun generateBatchEmbeddings(texts: List<String>): List<List<Float>> {
        val payload = gson.toJson(mapOf("texts" to texts))
        val request = Request.Builder()
            .url("$embeddingServiceUrl/embed/batch")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to generate batch embeddings: ${response.code}")
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val embeddingsArray = json.getAsJsonArray("embeddings")
            return embeddingsArray.map { embArray ->
                embArray.asJsonArray.map { it.asFloat }
            }
        }
    }

    /**
     * Generate single embedding (fallback).
     */
    private fun generateEmbedding(text: String): List<Float> {
        val payload = gson.toJson(mapOf("text" to text))
        val request = Request.Builder()
            .url("$embeddingServiceUrl/embed")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to generate embedding: ${response.code}")
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val embeddingArray = json.getAsJsonArray("embedding")
            return embeddingArray.map { it.asFloat }
        }
    }

    private fun storeInQdrant(collectionName: String, page: PageInfo, content: String, embedding: List<Float>) {
        val point = PointStruct.newBuilder()
            .setId(id(page.id.toLong()))
            .setVectors(vectors(embedding))
            .putAllPayload(mapOf(
                "page_id" to value(page.id.toLong()),
                "page_name" to value(page.name),
                "page_url" to value(page.url),
                "content_snippet" to value(content.take(500))
            ))
            .build()

        qdrant.upsertAsync(collectionName, listOf(point)).get()
    }

    private fun storeInClickHouse(collectionName: String, page: PageInfo, content: String) {
        val tableName = collectionName.replace("-", "_")
        val escapedContent = content.replace("'", "''")
        val escapedName = page.name.replace("'", "''")
        val escapedUrl = page.url.replace("'", "''")

        val insertSql = """
            INSERT INTO default.$tableName (page_id, page_name, page_url, content)
            VALUES (${page.id}, '$escapedName', '$escapedUrl', '$escapedContent')
        """.trimIndent()

        val jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"
        DriverManager.getConnection(jdbcUrl, clickhouseUser, clickhousePassword).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(insertSql)
            }
        }
    }

    private fun deletePageFromStorage(collectionName: String, pageId: Int) {
        try {
            qdrant.deleteAsync(collectionName, listOf(id(pageId.toLong()))).get()
            logger.debug { "Deleted page $pageId from Qdrant" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete page $pageId from Qdrant" }
        }

        try {
            val tableName = collectionName.replace("-", "_")
            val jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"
            DriverManager.getConnection(jdbcUrl, clickhouseUser, clickhousePassword).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DELETE FROM default.$tableName WHERE page_id = $pageId")
                }
            }
            logger.debug { "Deleted page $pageId from ClickHouse" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to delete page $pageId from ClickHouse" }
        }
    }

    private fun ensureQdrantCollection(name: String) {
        try {
            val collections = qdrant.listCollectionsAsync().get()
            val exists = collections.contains(name)

            if (!exists) {
                logger.info { "Creating Qdrant collection: $name" }
                qdrant.createCollectionAsync(
                    name,
                    VectorParams.newBuilder()
                        .setSize(384)
                        .setDistance(Distance.Cosine)
                        .build()
                ).get()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure Qdrant collection: $name" }
            throw e
        }
    }

    private fun ensureClickHouseTable(name: String) {
        val tableName = name.replace("-", "_")
        val createTableSql = """
            CREATE TABLE IF NOT EXISTS default.$tableName (
                page_id UInt32,
                page_name String,
                page_url String,
                content String,
                indexed_at DateTime DEFAULT now()
            ) ENGINE = MergeTree()
            ORDER BY page_id
        """.trimIndent()

        try {
            val jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"
            DriverManager.getConnection(jdbcUrl, clickhouseUser, clickhousePassword).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(createTableSql)
                }
            }
            logger.info { "Ensured ClickHouse table: $tableName" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure ClickHouse table: $tableName" }
            throw e
        }
    }

    internal fun computeContentHash(content: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}

data class PageDiff(
    val new: List<PageInfo>,
    val modified: List<PageInfo>,
    val unchanged: List<PageInfo>,
    val deleted: List<Int>
)
