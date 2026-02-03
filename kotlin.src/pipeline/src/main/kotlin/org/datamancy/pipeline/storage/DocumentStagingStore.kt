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
 * Status tracking for documents in the embedding pipeline
 */
enum class EmbeddingStatus {
    PENDING,      // Scraped, waiting for embedding
    IN_PROGRESS,  // Currently being embedded
    COMPLETED,    // Successfully embedded and inserted to Qdrant
    FAILED        // Embedding/insertion failed (will retry)
}

/**
 * Document staging in PostgreSQL
 * Decouples scraping from embedding - stores raw documents and tracks embedding progress
 */
data class StagedDocument(
    val id: String,                          // Unique document ID
    val source: String,                      // Source name (rss, wikipedia, etc)
    val collection: String,                  // Target Qdrant collection
    val text: String,                        // Document text (or chunk text)
    val metadata: Map<String, String>,       // Document metadata
    val embeddingStatus: EmbeddingStatus,    // Current status
    val chunkIndex: Int? = null,             // Chunk index (null if not chunked)
    val totalChunks: Int? = null,            // Total chunks (null if not chunked)
    val createdAt: Instant = Instant.now(),  // When scraped
    val updatedAt: Instant = Instant.now(),  // Last status update
    val retryCount: Int = 0,                 // Number of retry attempts
    val errorMessage: String? = null         // Last error (if failed)
)

/**
 * Exposed table definition for document staging
 */
object DocumentStagingTable : Table("document_staging") {
    val id = varchar("id", 500)
    val sourceName = varchar("source", 255)
    val collection = varchar("collection", 255)
    val text = text("text")
    val metadata = text("metadata")  // JSON-encoded
    val embeddingStatus = varchar("embedding_status", 50)
    val chunkIndex = integer("chunk_index").nullable()
    val totalChunks = integer("total_chunks").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val retryCount = integer("retry_count").default(0)
    val errorMessage = text("error_message").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * PostgreSQL-backed document staging store using Exposed ORM
 * Handles buffering scraped documents and tracking embedding progress
 */
class DocumentStagingStore(
    private val jdbcUrl: String,
    private val user: String = "datamancer",
    private val dbPassword: String = ""
) : AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val dataSource: HikariDataSource

    init {
        // DEBUG: Log what we're receiving
        logger.info { "DocumentStagingStore init: jdbcUrl=$jdbcUrl, user=$user, password.length=${dbPassword.length}" }

        // Configure HikariCP connection pool
        val config = HikariConfig().apply {
            // Use explicit setter syntax to avoid shadowing issues
            setJdbcUrl(this@DocumentStagingStore.jdbcUrl)
            setUsername(user)
            setPassword(dbPassword)

            // DEBUG: Verify what was set
            logger.info { "HikariConfig: jdbcUrl=$jdbcUrl, username=$username, password.length=${dbPassword.length}" }
            // Auto-detect driver based on JDBC URL
            driverClassName = when {
                this@DocumentStagingStore.jdbcUrl.startsWith("jdbc:postgresql") -> "org.postgresql.Driver"
                this@DocumentStagingStore.jdbcUrl.startsWith("jdbc:h2") -> "org.h2.Driver"
                else -> "org.postgresql.Driver"
            }

            // Connection pool settings
            maximumPoolSize = 20
            minimumIdle = 5
            connectionTimeout = 30000  // 30 seconds
            idleTimeout = 600000       // 10 minutes
            maxLifetime = 1800000      // 30 minutes

            // Performance
            isAutoCommit = true
            transactionIsolation = "TRANSACTION_READ_COMMITTED"

            // Validation
            connectionTestQuery = "SELECT 1"
            validationTimeout = 5000
        }

        dataSource = HikariDataSource(config)
        Database.connect(dataSource)

        logger.info { "Connected to PostgreSQL: $jdbcUrl (HikariCP pool size: ${config.maximumPoolSize})" }

        ensureTableExists()
    }

    private fun ensureTableExists() {
        try {
            transaction {
                SchemaUtils.createMissingTablesAndColumns(DocumentStagingTable)

                // Create indexes for performance (only in production PostgreSQL)
                // Skip index creation for H2 test database to avoid compatibility issues
                if (jdbcUrl.startsWith("jdbc:postgresql")) {
                    // PostgreSQL supports partial indexes with WHERE clause
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
     * Stage a document for embedding (single document - prefer stageBatch for performance)
     */
    suspend fun stage(doc: StagedDocument) {
        stageBatch(listOf(doc))
    }

    /**
     * Stage multiple documents in batch (much faster than individual inserts)
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
                }
            }

            logger.debug { "Staged ${docs.size} documents" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to stage batch: ${e.message}" }
            throw e
        }
    }

    /**
     * Get pending documents ready for embedding (limited batch)
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
                            errorMessage = row[DocumentStagingTable.errorMessage]
                        )
                    }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get pending batch: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Update document status (e.g. PENDING → IN_PROGRESS → COMPLETED)
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
     * Get stats for monitoring dashboard
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
     * Get stats by source
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
     * Get pending documents for BookStack writing
     */
    suspend fun getPendingForBookStack(limit: Int = 50): List<StagedDocument> {
        return try {
            transaction {
                DocumentStagingTable
                    .selectAll()
                    .where {
                        // FIX: Only select documents that have been successfully embedded
                        // and haven't exceeded retry limit for BookStack writes
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
                            errorMessage = row[DocumentStagingTable.errorMessage]
                        )
                    }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get pending BookStack docs: ${e.message}" }
            emptyList()
        }
    }

    /**
     * Mark document as BookStack write complete
     */
    suspend fun markBookStackComplete(id: String) {
        try {
            transaction {
                DocumentStagingTable.update({ DocumentStagingTable.id eq id }) {
                    it[updatedAt] = Instant.now()
                    // We don't change embedding status - that's independent
                }
            }
            logger.debug { "Marked BookStack write complete for $id" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark BookStack complete: ${e.message}" }
        }
    }

    /**
     * Mark document as BookStack write failed
     */
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

    /**
     * Get BookStack write statistics
     * Returns pending (embeddingCompleted && retryCount < 3) and failed (retryCount >= 3) counts
     */
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

    /**
     * Close the connection pool and release resources
     */
    override fun close() {
        dataSource.close()
        logger.info { "DocumentStagingStore closed (connection pool released)" }
    }
}
