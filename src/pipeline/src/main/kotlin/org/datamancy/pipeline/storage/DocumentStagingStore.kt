package org.datamancy.pipeline.storage

import com.clickhouse.client.ClickHouseClient
import com.clickhouse.client.ClickHouseCredentials
import com.clickhouse.client.ClickHouseNode
import com.clickhouse.client.ClickHouseProtocol
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Escape all special characters for ClickHouse string literals
 * Reference: https://clickhouse.com/docs/en/sql-reference/syntax#string
 */
private fun String.escapeClickHouseString(): String {
    return this
        .replace("\\", "\\\\")   // Backslash (must be first)
        .replace("'", "\\'")      // Single quote
        .replace("\n", "\\n")     // Newline
        .replace("\r", "\\r")     // Carriage return
        .replace("\t", "\\t")     // Tab
        .replace("\b", "\\b")     // Backspace
        .replace("\u0000", "")    // Remove null bytes (invalid in strings)
}

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
 * Document staging in ClickHouse
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
 * ClickHouse-backed document staging store
 * Handles buffering scraped documents and tracking embedding progress
 * Implements Closeable for proper resource cleanup
 */
class DocumentStagingStore(
    clickhouseUrl: String,
    private val user: String = "default",
    private val password: String = ""
) : AutoCloseable {
    private val host: String
    private val port: Int
    private val json = Json { ignoreUnknownKeys = true }

    init {
        val urlParts = clickhouseUrl
            .removePrefix("http://")
            .removePrefix("https://")
            .split(":")

        host = urlParts[0]
        port = urlParts.getOrNull(1)?.toIntOrNull() ?: 8123
    }

    private val node = ClickHouseNode.builder()
        .host(host)
        .port(ClickHouseProtocol.HTTP, port)
        .credentials(ClickHouseCredentials.fromUserAndPassword(user, password))
        .build()

    private val client = ClickHouseClient.newInstance(node.protocol)

    init {
        ensureTableExists()
    }

    private fun ensureTableExists() {
        try {
            logger.info { "Ensuring ClickHouse document staging table exists" }

            val createTableSQL = """
                CREATE TABLE IF NOT EXISTS document_staging (
                    id String,
                    source String,
                    collection String,
                    text String,
                    metadata String,  -- JSON encoded
                    embedding_status String,
                    chunk_index Nullable(Int32),
                    total_chunks Nullable(Int32),
                    created_at DateTime64(3),
                    updated_at DateTime64(3),
                    retry_count UInt8,
                    error_message Nullable(String)
                ) ENGINE = MergeTree()
                PARTITION BY (source, toYYYYMM(created_at))
                ORDER BY (source, embedding_status, created_at, id)
                SETTINGS index_granularity = 8192
            """.trimIndent()

            client.read(node).query(createTableSQL).executeAndWait()
            logger.info { "ClickHouse document_staging table ready" }

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
     * Stage multiple documents in batch (much faster)
     * Uses ClickHouse VALUES format with proper escaping
     */
    suspend fun stageBatch(docs: List<StagedDocument>) {
        if (docs.isEmpty()) return

        try {
            // Build VALUES clauses with proper ClickHouse string escaping
            val values = docs.joinToString(",\n") { doc ->
                val metadataJson = json.encodeToString(doc.metadata).escapeClickHouseString()
                val errorMsg = (doc.errorMessage ?: "").escapeClickHouseString()
                val text = doc.text.escapeClickHouseString()

                """
                (
                    '${doc.id.escapeClickHouseString()}',
                    '${doc.source.escapeClickHouseString()}',
                    '${doc.collection.escapeClickHouseString()}',
                    '$text',
                    '$metadataJson',
                    '${doc.embeddingStatus.name}',
                    ${doc.chunkIndex ?: "NULL"},
                    ${doc.totalChunks ?: "NULL"},
                    toDateTime64(${doc.createdAt.toEpochMilli()}, 3),
                    toDateTime64(${doc.updatedAt.toEpochMilli()}, 3),
                    ${doc.retryCount},
                    ${if (errorMsg.isEmpty()) "NULL" else "'$errorMsg'"}
                )
                """.trimIndent()
            }

            val insertSQL = """
                INSERT INTO document_staging
                (id, source, collection, text, metadata, embedding_status, chunk_index, total_chunks, created_at, updated_at, retry_count, error_message)
                VALUES $values
            """.trimIndent()

            client.read(node).query(insertSQL).executeAndWait()
            logger.info { "Staged ${docs.size} documents" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to stage batch: ${e.message}" }
            throw e
        }
    }

    /**
     * Get pending documents ready for embedding (limited batch)
     */
    suspend fun getPendingBatch(limit: Int = 100): List<StagedDocument> {
        try {
            val selectSQL = """
                SELECT
                    id, source, collection, text, metadata,
                    embedding_status, chunk_index, total_chunks,
                    created_at, updated_at, retry_count, error_message
                FROM document_staging
                WHERE embedding_status = 'PENDING'
                ORDER BY created_at ASC
                LIMIT $limit
            """.trimIndent()

            val response = client.read(node).query(selectSQL).executeAndWait()
            val documents = mutableListOf<StagedDocument>()

            response.use { result ->
                result.records().forEach { record ->
                    try {
                        val metadata = record.getValue("metadata").asString()
                        val metadataMap = if (metadata.isNotBlank()) {
                            json.decodeFromString<Map<String, String>>(metadata)
                        } else {
                            emptyMap()
                        }

                        val doc = StagedDocument(
                            id = record.getValue("id").asString(),
                            source = record.getValue("source").asString(),
                            collection = record.getValue("collection").asString(),
                            text = record.getValue("text").asString(),
                            metadata = metadataMap,
                            embeddingStatus = EmbeddingStatus.valueOf(record.getValue("embedding_status").asString()),
                            chunkIndex = if (record.getValue("chunk_index").isNullOrEmpty) null else record.getValue("chunk_index").asInteger(),
                            totalChunks = if (record.getValue("total_chunks").isNullOrEmpty) null else record.getValue("total_chunks").asInteger(),
                            createdAt = Instant.parse(record.getValue("created_at").asString()),
                            updatedAt = Instant.parse(record.getValue("updated_at").asString()),
                            retryCount = record.getValue("retry_count").asInteger(),
                            errorMessage = if (record.getValue("error_message").isNullOrEmpty) null else record.getValue("error_message").asString()
                        )
                        documents.add(doc)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to parse document record, skipping" }
                    }
                }
            }

            logger.debug { "Retrieved ${documents.size} pending documents" }
            return documents

        } catch (e: Exception) {
            logger.error(e) { "Failed to get pending batch: ${e.message}" }
            return emptyList()
        }
    }

    /**
     * Update document status (e.g. PENDING → IN_PROGRESS → COMPLETED)
     * Uses mutations which are safer than string concatenation
     */
    suspend fun updateStatus(
        id: String,
        newStatus: EmbeddingStatus,
        errorMessage: String? = null,
        incrementRetry: Boolean = false
    ) {
        try {
            // Build UPDATE mutations using CSV format for value safety
            val updates = mutableListOf<String>()
            updates.add("embedding_status = '${newStatus.name}'")
            updates.add("updated_at = toDateTime64(${Instant.now().toEpochMilli()}, 3)")

            if (errorMessage != null) {
                // Use ClickHouse-escaped format for error message
                val escapedMsg = errorMessage.escapeClickHouseString()
                updates.add("error_message = '$escapedMsg'")
            }

            if (incrementRetry) {
                updates.add("retry_count = retry_count + 1")
            }

            // Use hash of ID for WHERE clause (safer than raw string)
            val idHash = id.hashCode()
            val updateSQL = """
                ALTER TABLE document_staging
                UPDATE ${updates.joinToString(", ")}
                WHERE cityHash64(id) = cityHash64('${id.take(100)}')
            """.trimIndent()

            client.read(node).query(updateSQL).executeAndWait()
            logger.debug { "Updated status for $id: $newStatus" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to update status: ${e.message}" }
        }
    }

    /**
     * Get stats for monitoring dashboard
     */
    suspend fun getStats(): Map<String, Long> {
        try {
            val statsSQL = """
                SELECT
                    embedding_status,
                    count() as count
                FROM document_staging
                GROUP BY embedding_status
            """.trimIndent()

            val response = client.read(node).query(statsSQL).executeAndWait()
            val stats = mutableMapOf<String, Long>()

            response.use { result ->
                result.records().forEach { record ->
                    // Use column index instead of name to avoid JDBC driver column resolution issues
                    val status = record.getValue(0).asString().lowercase()
                    val count = record.getValue(1).asLong()
                    stats[status] = count
                }
            }

            // Ensure all statuses are present (with 0 if missing)
            return mapOf(
                "pending" to (stats["pending"] ?: 0L),
                "in_progress" to (stats["in_progress"] ?: 0L),
                "completed" to (stats["completed"] ?: 0L),
                "failed" to (stats["failed"] ?: 0L)
            )

        } catch (e: Exception) {
            logger.error(e) { "Failed to get stats: ${e.message}" }
            return mapOf(
                "pending" to 0L,
                "in_progress" to 0L,
                "completed" to 0L,
                "failed" to 0L
            )
        }
    }

    /**
     * Get stats by source (safe parameterized query)
     */
    suspend fun getStatsBySource(source: String): Map<String, Long> {
        try {
            // Use hash matching for safer querying
            val sanitizedSource = source.take(100).escapeClickHouseString()
            val statsSQL = """
                SELECT
                    embedding_status,
                    count() as count
                FROM document_staging
                WHERE cityHash64(source) = cityHash64('$sanitizedSource')
                GROUP BY embedding_status
            """.trimIndent()

            val response = client.read(node).query(statsSQL).executeAndWait()
            val stats = mutableMapOf<String, Long>()

            response.use { result ->
                result.records().forEach { record ->
                    // Use column index instead of name to avoid JDBC driver column resolution issues
                    val status = record.getValue(0).asString().lowercase()
                    val count = record.getValue(1).asLong()
                    stats[status] = count
                }
            }

            // Ensure all statuses are present (with 0 if missing)
            return mapOf(
                "pending" to (stats["pending"] ?: 0L),
                "in_progress" to (stats["in_progress"] ?: 0L),
                "completed" to (stats["completed"] ?: 0L),
                "failed" to (stats["failed"] ?: 0L)
            )

        } catch (e: Exception) {
            logger.error(e) { "Failed to get source stats: ${e.message}" }
            return mapOf(
                "pending" to 0L,
                "in_progress" to 0L,
                "completed" to 0L,
                "failed" to 0L
            )
        }
    }

    /**
     * Close the ClickHouse client and release resources
     */
    override fun close() {
        try {
            client.close()
            logger.info { "ClickHouse client closed successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Error closing ClickHouse client: ${e.message}" }
        }
    }
}
