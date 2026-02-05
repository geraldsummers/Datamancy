package org.datamancy.pipeline.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Tracks the lifecycle of document embeddings from ingestion through vector storage.
 *
 * The staging pattern decouples data ingestion from embedding generation:
 * - PENDING: Document staged, waiting for EmbeddingScheduler to process
 * - IN_PROGRESS: Currently being embedded by the embedding service
 * - COMPLETED: Vector stored in Qdrant, ready for BookStackWriter
 * - FAILED: Embedding failed (transient errors retry via retryCount)
 *
 * This state machine enables fault-tolerant async processing where ingestion continues
 * even when the embedding service (BGE-M3 model) is slow or temporarily unavailable.
 */
enum class EmbeddingStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

/**
 * Represents a document in the staging buffer between ingestion and vector storage.
 *
 * Documents flow through the pipeline:
 * 1. Data sources (RSS, CVE, Wikipedia) create documents with status=PENDING
 * 2. EmbeddingScheduler polls for PENDING docs, generates vectors, marks COMPLETED
 * 3. BookStackWriter polls for COMPLETED docs, publishes to BookStack knowledge base
 *
 * Large documents may be split into chunks (chunkIndex/totalChunks) to fit within
 * the embedding model's 8192 token limit while maintaining traceability.
 *
 * @property id Unique identifier (SHA-256 hash of content for deduplication)
 * @property source Origin system (e.g., "rss", "cve", "wikipedia")
 * @property collection Qdrant collection name for vector storage routing
 * @property text Raw document content (may be a chunk of larger document)
 * @property metadata Source-specific fields (URL, timestamp, author, etc.)
 * @property embeddingStatus Current processing state in the staging lifecycle
 * @property chunkIndex 0-based chunk number if document was split (null if not chunked)
 * @property totalChunks Total chunks for this document (null if not chunked)
 * @property createdAt When document first entered staging (for FIFO ordering)
 * @property updatedAt Last status change (for monitoring stuck documents)
 * @property retryCount Failed embedding attempts (max 3 before permanent FAILED)
 * @property errorMessage Last error from embedding service (for debugging)
 * @property bookstackUrl URL of published BookStack page (set by BookStackWriter)
 */
data class StagedDocument(
    val id: String,
    val source: String,
    val collection: String,
    val text: String,
    val metadata: Map<String, String>,
    val embeddingStatus: EmbeddingStatus,
    val chunkIndex: Int? = null,
    val totalChunks: Int? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val bookstackUrl: String? = null
)

/**
 * PostgreSQL schema for the document staging table.
 *
 * This table acts as the central buffer in the Datamancy pipeline, enabling:
 * - Persistent storage across pipeline restarts (unlike in-memory queues)
 * - Queryable status tracking for monitoring and debugging
 * - Transactional updates to prevent race conditions during status changes
 * - Full-text search via PostgreSQL's tsvector (used by search-service)
 *
 * Indexes optimize common access patterns:
 * - idx_staging_status_created: Fast PENDING document polling by EmbeddingScheduler
 * - idx_staging_source: Per-source stats and debugging queries
 */
object DocumentStagingTable : Table("document_staging") {
    val id = varchar("id", 500)
    val sourceName = varchar("source", 255)
    val collection = varchar("collection", 255)
    val text = text("text")
    val metadata = text("metadata")  
    val embeddingStatus = varchar("embedding_status", 50)
    val chunkIndex = integer("chunk_index").nullable()
    val totalChunks = integer("total_chunks").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val retryCount = integer("retry_count").default(0)
    val errorMessage = text("error_message").nullable()
    val bookstackUrl = text("bookstack_url").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * PostgreSQL-backed staging buffer for fault-tolerant document processing.
 *
 * This store implements the staging pattern (ARCHITECTURE.md Flow 1) that decouples
 * data ingestion from embedding generation. Documents are written to PostgreSQL
 * immediately upon ingestion (status=PENDING), then processed asynchronously by
 * downstream components:
 *
 * **Integration with Pipeline Components:**
 * - Data sources (RSS, CVE, Wikipedia) → stageBatch() with status=PENDING
 * - EmbeddingScheduler → getPendingBatch() → updateStatus(COMPLETED) after Qdrant write
 * - BookStackWriter → getPendingForBookStack() → updateBookStackUrl() after publishing
 * - Search-Service → Reads COMPLETED docs for hybrid search (vector + full-text)
 *
 * **Why PostgreSQL?**
 * - Persistence: Survives pipeline crashes/restarts (vs. in-memory queues)
 * - Queryability: Status tracking, per-source stats, debugging stuck documents
 * - Transactions: ACID guarantees prevent duplicate processing during status changes
 * - Full-text: Built-in tsvector enables keyword search (BM25 ranking in search-service)
 * - Scalability: HikariCP connection pooling supports high-throughput concurrent access
 *
 * **Fault Tolerance:**
 * - Retry logic: Failed embeddings retry up to 3 times via retryCount field
 * - Idempotency: Documents use content-based IDs (SHA-256) to prevent duplicates
 * - Graceful degradation: Ingestion continues even when embedding service is down
 *
 * @property jdbcUrl PostgreSQL connection string (e.g., jdbc:postgresql://db:5432/datamancy)
 * @property user Database username (default: datamancer)
 * @property dbPassword Database password
 */
class DocumentStagingStore(
    private val jdbcUrl: String,
    private val user: String = "datamancer",
    private val dbPassword: String = ""
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val dataSource: HikariDataSource

    init {
        
        logger.info { "DocumentStagingStore init: jdbcUrl=$jdbcUrl, user=$user, password.length=${dbPassword.length}" }

        
        val config = HikariConfig().apply {
            
            setJdbcUrl(this@DocumentStagingStore.jdbcUrl)
            setUsername(user)
            setPassword(dbPassword)

            
            logger.info { "HikariConfig: jdbcUrl=$jdbcUrl, username=$username, password.length=${dbPassword.length}" }
            
            driverClassName = when {
                this@DocumentStagingStore.jdbcUrl.startsWith("jdbc:postgresql") -> "org.postgresql.Driver"
                this@DocumentStagingStore.jdbcUrl.startsWith("jdbc:h2") -> "org.h2.Driver"
                else -> "org.postgresql.Driver"
            }

            
            maximumPoolSize = 20
            minimumIdle = 5
            connectionTimeout = 30000  
            idleTimeout = 600000       
            maxLifetime = 1800000      

            
            isAutoCommit = true
            transactionIsolation = "TRANSACTION_READ_COMMITTED"

            
            connectionTestQuery = "SELECT 1"
            validationTimeout = 5000
        }

        dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        logger.info { "Connected to PostgreSQL: $jdbcUrl (HikariCP pool size: ${config.maximumPoolSize})" }

        ensureTableExists()
    }

    /**
     * Creates table schema and performance indexes if not present.
     *
     * **Critical indexes for staging pattern:**
     * - idx_staging_status_created: Partial index on PENDING docs ordered by creation time
     *   Enables EmbeddingScheduler to efficiently poll oldest unprocessed documents (FIFO)
     * - idx_staging_source: Per-source filtering for stats and debugging
     *
     * These indexes are PostgreSQL-specific optimizations. H2 (used in tests) silently
     * skips them, allowing the same code to work in both production and test environments.
     */
    private fun ensureTableExists() {
        try {
            transaction {
                SchemaUtils.createMissingTablesAndColumns(DocumentStagingTable)

                if (jdbcUrl.startsWith("jdbc:postgresql")) {
                    // Partial index dramatically speeds up EmbeddingScheduler's getPendingBatch()
                    // by only indexing the subset of rows it actually queries
                    exec("""
                        CREATE INDEX IF NOT EXISTS idx_staging_status_created
                        ON document_staging(embedding_status, created_at)
                        WHERE embedding_status = 'PENDING'
                    """.trimIndent())

                    exec("""
                        CREATE INDEX IF NOT EXISTS idx_staging_source
                        ON document_staging(source)
                    """.trimIndent())
                }
            }

            logger.info { "PostgreSQL document_staging table ready" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to create document_staging table: ${e.message}" }
            throw e
        }
    }

    /**
     * Stages a single document for asynchronous embedding processing.
     * Convenience wrapper around stageBatch() for single-document workflows.
     */
    suspend fun stage(doc: StagedDocument) {
        stageBatch(listOf(doc))
    }

    /**
     * Atomically inserts multiple documents into staging buffer.
     *
     * Used by data sources (RSS, CVE, Wikipedia) after deduplication to queue documents
     * for embedding. Batch insertion reduces database round-trips for high-throughput
     * ingestion (e.g., processing 10,000+ Wikipedia articles).
     *
     * Documents are inserted with status=PENDING, making them visible to EmbeddingScheduler's
     * next polling cycle. Metadata is serialized to JSON for flexible schema evolution.
     *
     * @param docs Documents to stage (typically all have embeddingStatus=PENDING)
     * @throws Exception if database write fails (caller should handle retry logic)
     */
    suspend fun stageBatch(docs: List<StagedDocument>) {
        if (docs.isEmpty()) return

        try {
            transaction {
                DocumentStagingTable.batchInsert(docs) { doc ->
                    this[DocumentStagingTable.id] = doc.id
                    this[DocumentStagingTable.sourceName] = doc.source
                    this[DocumentStagingTable.collection] = doc.collection
                    this[DocumentStagingTable.text] = doc.text
                    this[DocumentStagingTable.metadata] = json.encodeToString(doc.metadata)
                    this[DocumentStagingTable.embeddingStatus] = doc.embeddingStatus.name
                    this[DocumentStagingTable.chunkIndex] = doc.chunkIndex
                    this[DocumentStagingTable.totalChunks] = doc.totalChunks
                    this[DocumentStagingTable.createdAt] = doc.createdAt
                    this[DocumentStagingTable.updatedAt] = doc.updatedAt
                    this[DocumentStagingTable.retryCount] = doc.retryCount
                    this[DocumentStagingTable.errorMessage] = doc.errorMessage
                    this[DocumentStagingTable.bookstackUrl] = doc.bookstackUrl
                }
            }

            logger.debug { "Staged ${docs.size} documents" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to stage batch: ${e.message}" }
            throw e
        }
    }

    /**
     * Polls for oldest unprocessed documents awaiting embedding.
     *
     * Called by EmbeddingScheduler on a fixed interval (e.g., every 5 seconds) to fetch
     * the next batch of work. Documents are returned in FIFO order (oldest first) to
     * ensure fair processing across all data sources.
     *
     * The partial index idx_staging_status_created makes this query extremely fast even
     * when the table contains millions of COMPLETED documents, since it only scans the
     * subset of PENDING rows.
     *
     * @param limit Maximum documents to return (default 100 for embedding service rate limiting)
     * @return List of PENDING documents, oldest first (empty if nothing to process)
     */
    suspend fun getPendingBatch(limit: Int = 100): List<StagedDocument> {
        return try {
            transaction {
                DocumentStagingTable
                    .selectAll()
                    .where { DocumentStagingTable.embeddingStatus eq EmbeddingStatus.PENDING.name }
                    .orderBy(DocumentStagingTable.createdAt to SortOrder.ASC)
                    .limit(limit)
                    .map { row ->
                        val metadataStr = row[DocumentStagingTable.metadata]
                        val metadata = if (metadataStr.isNotBlank()) {
                            json.decodeFromString<Map<String, String>>(metadataStr)
                        } else {
                            emptyMap()
                        }

                        StagedDocument(
                            id = row[DocumentStagingTable.id],
                            source = row[DocumentStagingTable.sourceName],
                            collection = row[DocumentStagingTable.collection],
                            text = row[DocumentStagingTable.text],
                            metadata = metadata,
                            embeddingStatus = EmbeddingStatus.valueOf(row[DocumentStagingTable.embeddingStatus]),
                            chunkIndex = row[DocumentStagingTable.chunkIndex],
                            totalChunks = row[DocumentStagingTable.totalChunks],
                            createdAt = row[DocumentStagingTable.createdAt],
                            updatedAt = row[DocumentStagingTable.updatedAt],
                            retryCount = row[DocumentStagingTable.retryCount],
                            errorMessage = row[DocumentStagingTable.errorMessage],
                            bookstackUrl = row[DocumentStagingTable.bookstackUrl]
                        )
                    }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get pending batch: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Updates document processing status after embedding service interaction.
     *
     * Called by EmbeddingScheduler to record state transitions:
     * - PENDING → IN_PROGRESS: Embedding request sent to BGE-M3 service
     * - IN_PROGRESS → COMPLETED: Vector successfully stored in Qdrant
     * - IN_PROGRESS → FAILED: Embedding service error (retry if count < 3)
     *
     * The updatedAt timestamp enables monitoring for stuck documents (e.g., if a document
     * stays IN_PROGRESS for > 5 minutes, the embedding service may have crashed).
     *
     * @param id Document ID to update
     * @param newStatus Target state (typically COMPLETED or FAILED)
     * @param errorMessage Optional error details from embedding service (for debugging)
     * @param incrementRetry True to increment retry counter on failure (max 3 attempts)
     */
    suspend fun updateStatus(
        id: String,
        newStatus: EmbeddingStatus,
        errorMessage: String? = null,
        incrementRetry: Boolean = false
    ) {
        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id eq id }) {
                    it[embeddingStatus] = newStatus.name
                    it[updatedAt] = Instant.now()

                    if (errorMessage != null) {
                        it[DocumentStagingTable.errorMessage] = errorMessage
                    }

                    if (incrementRetry) {
                        it[retryCount] = DocumentStagingTable.retryCount + 1
                    }
                }
            }

            logger.debug { "Updated status for $id: $newStatus" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update status: ${e.message}" }
        }
    }

    /**
     * Aggregates document counts by processing status for monitoring dashboards.
     *
     * Used by MonitoringServer to expose Prometheus metrics showing pipeline health:
     * - High pending count: Embedding service may be bottleneck
     * - Growing failed count: Investigate error messages for systemic issues
     * - Low completed count: Pipeline may not be ingesting data
     *
     * @return Map of status → count (keys: "pending", "in_progress", "completed", "failed")
     */
    suspend fun getStats(): Map<String, Long> {
        return try {
            transaction {
                val countExpr = DocumentStagingTable.id.count()
                val stats = DocumentStagingTable
                    .select(DocumentStagingTable.embeddingStatus, countExpr)
                    .groupBy(DocumentStagingTable.embeddingStatus)
                    .associate { row ->
                        row[DocumentStagingTable.embeddingStatus].lowercase() to row[countExpr]
                    }

                mapOf(
                    "pending" to (stats["pending"] ?: 0L),
                    "in_progress" to (stats["in_progress"] ?: 0L),
                    "completed" to (stats["completed"] ?: 0L),
                    "failed" to (stats["failed"] ?: 0L)
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get stats: ${e.message}" }
            mapOf(
                "pending" to 0L,
                "in_progress" to 0L,
                "completed" to 0L,
                "failed" to 0L
            )
        }
    }

    /**
     * Records BookStack publication URL after document is published to knowledge base.
     *
     * Called by BookStackWriter after successfully creating a page via BookStack API.
     * The URL enables:
     * - Users to view published documents in human-readable format
     * - Monitoring dashboards to link from staging table to BookStack pages
     * - Deduplication across pipeline restarts (skip if bookstackUrl already set)
     *
     * @param id Document ID
     * @param bookstackUrl Full URL to BookStack page (e.g., https://docs.datamancy.net/books/123/page/456)
     */
    suspend fun updateBookStackUrl(id: String, bookstackUrl: String) {
        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id eq id }) {
                    it[DocumentStagingTable.bookstackUrl] = bookstackUrl
                    it[updatedAt] = Instant.now()
                }
            }
            logger.debug { "Updated BookStack URL for $id: $bookstackUrl" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to update BookStack URL for $id: ${e.message}" }
        }
    }

    /**
     * Provides per-source status breakdown for debugging and capacity planning.
     *
     * Enables operators to identify which data sources are:
     * - Producing most content (high completed count)
     * - Experiencing errors (high failed count)
     * - Bottlenecked (high pending count relative to ingestion rate)
     *
     * @param source Source identifier (e.g., "rss", "cve", "wikipedia")
     * @return Map of status → count for this source only
     */
    suspend fun getStatsBySource(source: String): Map<String, Long> {
        return try {
            transaction {
                val countExpr = DocumentStagingTable.id.count()
                val stats = DocumentStagingTable
                    .select(DocumentStagingTable.embeddingStatus, countExpr)
                    .where { DocumentStagingTable.sourceName eq source }
                    .groupBy(DocumentStagingTable.embeddingStatus)
                    .associate { row ->
                        row[DocumentStagingTable.embeddingStatus].lowercase() to row[countExpr]
                    }

                mapOf(
                    "pending" to (stats["pending"] ?: 0L),
                    "in_progress" to (stats["in_progress"] ?: 0L),
                    "completed" to (stats["completed"] ?: 0L),
                    "failed" to (stats["failed"] ?: 0L)
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get source stats: ${e.message}" }
            mapOf(
                "pending" to 0L,
                "in_progress" to 0L,
                "completed" to 0L,
                "failed" to 0L
            )
        }
    }

    /**
     * Polls for embedded documents awaiting BookStack publication.
     *
     * Called by BookStackWriter on a fixed interval to fetch the next batch of documents
     * that have been successfully embedded in Qdrant and are ready for human consumption
     * in the knowledge base.
     *
     * Documents must have:
     * - embeddingStatus = COMPLETED (vector stored in Qdrant)
     * - retryCount < 3 (not permanently failed due to BookStack API errors)
     *
     * Note: The bookstackUrl field tracks publication separately from embedding status.
     * This decoupling allows the same document to be searchable (via Qdrant) even if
     * BookStack publishing fails.
     *
     * @param limit Maximum documents to return (default 50 for BookStack API rate limiting)
     * @return List of COMPLETED documents ready for publication, oldest first
     */
    suspend fun getPendingForBookStack(limit: Int = 50): List<StagedDocument> {
        return try {
            transaction {
                DocumentStagingTable
                    .selectAll()
                    .where {
                        // Documents must be embedded AND not exceed retry limit for BookStack writes
                        // Note: bookstackUrl field tracks publication status independently
                        (DocumentStagingTable.embeddingStatus eq EmbeddingStatus.COMPLETED.name) and
                        (DocumentStagingTable.retryCount less 3)
                    }
                    .orderBy(DocumentStagingTable.createdAt to SortOrder.ASC)
                    .limit(limit)
                    .map { row ->
                        val metadataStr = row[DocumentStagingTable.metadata]
                        val metadata = if (metadataStr.isNotBlank()) {
                            json.decodeFromString<Map<String, String>>(metadataStr)
                        } else {
                            emptyMap()
                        }

                        StagedDocument(
                            id = row[DocumentStagingTable.id],
                            source = row[DocumentStagingTable.sourceName],
                            collection = row[DocumentStagingTable.collection],
                            text = row[DocumentStagingTable.text],
                            metadata = metadata,
                            embeddingStatus = EmbeddingStatus.valueOf(row[DocumentStagingTable.embeddingStatus]),
                            chunkIndex = row[DocumentStagingTable.chunkIndex],
                            totalChunks = row[DocumentStagingTable.totalChunks],
                            createdAt = row[DocumentStagingTable.createdAt],
                            updatedAt = row[DocumentStagingTable.updatedAt],
                            retryCount = row[DocumentStagingTable.retryCount],
                            errorMessage = row[DocumentStagingTable.errorMessage],
                            bookstackUrl = row[DocumentStagingTable.bookstackUrl]
                        )
                    }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get pending BookStack docs: ${e.message}" }
            emptyList()
        }
    }

    
    suspend fun markBookStackComplete(id: String) {
        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id eq id }) {
                    it[updatedAt] = Instant.now()
                    
                }
            }
            logger.debug { "Marked BookStack write complete for $id" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark BookStack complete: ${e.message}" }
        }
    }

    
    suspend fun markBookStackFailed(id: String, error: String) {
        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id eq id }) {
                    it[updatedAt] = Instant.now()
                    it[retryCount] = DocumentStagingTable.retryCount + 1
                    it[errorMessage] = "BookStack write failed: $error"
                }
            }
            logger.debug { "Marked BookStack write failed for $id: $error" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark BookStack failed: ${e.message}" }
        }
    }

    
    suspend fun getBookStackStats(): Map<String, Long> {
        return try {
            transaction {
                val totalCompleted = DocumentStagingTable
                    .select(DocumentStagingTable.id.count())
                    .where { DocumentStagingTable.embeddingStatus eq EmbeddingStatus.COMPLETED.name }
                    .single()[DocumentStagingTable.id.count()]

                val pending = DocumentStagingTable
                    .select(DocumentStagingTable.id.count())
                    .where {
                        (DocumentStagingTable.embeddingStatus eq EmbeddingStatus.COMPLETED.name) and
                        (DocumentStagingTable.retryCount less 3)
                    }
                    .single()[DocumentStagingTable.id.count()]

                val failed = DocumentStagingTable
                    .select(DocumentStagingTable.id.count())
                    .where {
                        (DocumentStagingTable.embeddingStatus eq EmbeddingStatus.COMPLETED.name) and
                        (DocumentStagingTable.retryCount greaterEq 3)
                    }
                    .single()[DocumentStagingTable.id.count()]

                mapOf(
                    "total_embedded" to totalCompleted,
                    "bookstack_pending" to pending,
                    "bookstack_failed" to failed,
                    "bookstack_completed" to (totalCompleted - pending - failed)
                )
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get BookStack stats: ${e.message}" }
            mapOf(
                "total_embedded" to 0L,
                "bookstack_pending" to 0L,
                "bookstack_failed" to 0L,
                "bookstack_completed" to 0L
            )
        }
    }

    
    override fun close() {
        dataSource.close()
        logger.info { "DocumentStagingStore closed (connection pool released)" }
    }
}
