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


enum class EmbeddingStatus {
    PENDING,      
    IN_PROGRESS,  
    COMPLETED,    
    FAILED        
}


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

    private fun ensureTableExists() {
        try {
            transaction {
                SchemaUtils.createMissingTablesAndColumns(DocumentStagingTable)

                
                
                if (jdbcUrl.startsWith("jdbc:postgresql")) {
                    
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

    
    suspend fun stage(doc: StagedDocument) {
        stageBatch(listOf(doc))
    }

    
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

    
    suspend fun getPendingForBookStack(limit: Int = 50): List<StagedDocument> {
        return try {
            transaction {
                DocumentStagingTable
                    .selectAll()
                    .where {
                        
                        
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
