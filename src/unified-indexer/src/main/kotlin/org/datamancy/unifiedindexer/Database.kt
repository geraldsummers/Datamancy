package org.datamancy.unifiedindexer

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.*

private val logger = KotlinLogging.logger {}

class Database(
    private val jdbcUrl: String,
    private val username: String,
    private val password: String
) {
    private val dataSource: HikariDataSource

    init {
        val config = HikariConfig().apply {
            this.jdbcUrl = this@Database.jdbcUrl
            this.username = this@Database.username
            this.password = this@Database.password
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 30000
            idleTimeout = 600000
            maxLifetime = 1800000
        }
        dataSource = HikariDataSource(config)
        initializeSchema()
    }

    private fun initializeSchema() {
        val schema = this::class.java.getResourceAsStream("/schema.sql")?.bufferedReader()?.readText()
            ?: throw Exception("schema.sql not found in resources")

        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(schema)
            }
        }
        logger.info { "Database schema initialized" }
    }

    fun createJob(collectionName: String): UUID {
        val jobId = UUID.randomUUID()
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO indexing_jobs (job_id, collection_name, status, started_at)
                VALUES (?, ?, 'running', NOW())
            """).use { stmt ->
                stmt.setObject(1, jobId)
                stmt.setString(2, collectionName)
                stmt.executeUpdate()
            }
        }
        return jobId
    }

    fun updateJobProgress(jobId: UUID, indexedPages: Int, totalPages: Int, currentPageId: Int? = null) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE indexing_jobs
                SET indexed_pages = ?, total_pages = ?, current_page_id = ?
                WHERE job_id = ?
            """).use { stmt ->
                stmt.setInt(1, indexedPages)
                stmt.setInt(2, totalPages)
                if (currentPageId != null) stmt.setInt(3, currentPageId) else stmt.setNull(3, java.sql.Types.INTEGER)
                stmt.setObject(4, jobId)
                stmt.executeUpdate()
            }
        }
    }

    fun completeJob(jobId: UUID, status: String, error: String? = null) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                UPDATE indexing_jobs
                SET status = ?, completed_at = NOW(), error_message = ?
                WHERE job_id = ?
            """).use { stmt ->
                stmt.setString(1, status)
                stmt.setString(2, error)
                stmt.setObject(3, jobId)
                stmt.executeUpdate()
            }
        }
    }

    fun getJob(jobId: UUID): IndexingJob? {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM indexing_jobs WHERE job_id = ?
            """).use { stmt ->
                stmt.setObject(1, jobId)
                val rs = stmt.executeQuery()
                return if (rs.next()) rs.toIndexingJob() else null
            }
        }
    }

    fun getAllJobs(): List<IndexingJob> {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("""
                    SELECT * FROM indexing_jobs
                    ORDER BY started_at DESC
                    LIMIT 100
                """)
                val jobs = mutableListOf<IndexingJob>()
                while (rs.next()) {
                    jobs.add(rs.toIndexingJob())
                }
                return jobs
            }
        }
    }

    fun upsertIndexedPage(page: IndexedPage) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO indexed_pages
                (page_id, collection_name, content_hash, indexed_at, embedding_version, vector_stored, fulltext_stored)
                VALUES (?, ?, ?, NOW(), ?, true, true)
                ON CONFLICT (page_id) DO UPDATE SET
                    content_hash = EXCLUDED.content_hash,
                    indexed_at = EXCLUDED.indexed_at,
                    embedding_version = EXCLUDED.embedding_version,
                    vector_stored = EXCLUDED.vector_stored,
                    fulltext_stored = EXCLUDED.fulltext_stored
            """).use { stmt ->
                stmt.setInt(1, page.pageId)
                stmt.setString(2, page.collectionName)
                stmt.setString(3, page.contentHash)
                stmt.setString(4, page.embeddingVersion)
                stmt.executeUpdate()
            }
        }
    }

    fun getIndexedPages(collectionName: String): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT page_id, content_hash
                FROM indexed_pages
                WHERE collection_name = ?
            """).use { stmt ->
                stmt.setString(1, collectionName)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    result[rs.getInt("page_id")] = rs.getString("content_hash")
                }
            }
        }
        return result
    }

    fun logError(jobId: UUID, pageId: Int?, pageName: String?, errorMessage: String, stackTrace: String) {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                INSERT INTO indexing_errors
                (job_id, page_id, page_name, error_message, stack_trace)
                VALUES (?, ?, ?, ?, ?)
            """).use { stmt ->
                stmt.setObject(1, jobId)
                if (pageId != null) stmt.setInt(2, pageId) else stmt.setNull(2, java.sql.Types.INTEGER)
                stmt.setString(3, pageName)
                stmt.setString(4, errorMessage)
                stmt.setString(5, stackTrace)
                stmt.executeUpdate()
            }
        }
    }

    fun getJobErrors(jobId: UUID): List<IndexingError> {
        dataSource.connection.use { conn ->
            conn.prepareStatement("""
                SELECT * FROM indexing_errors
                WHERE job_id = ?
                ORDER BY occurred_at DESC
            """).use { stmt ->
                stmt.setObject(1, jobId)
                val rs = stmt.executeQuery()
                val errors = mutableListOf<IndexingError>()
                while (rs.next()) {
                    errors.add(IndexingError(
                        errorId = rs.getInt("error_id"),
                        jobId = rs.getObject("job_id") as UUID,
                        pageId = rs.getInt("page_id").takeIf { !rs.wasNull() },
                        pageName = rs.getString("page_name"),
                        errorMessage = rs.getString("error_message"),
                        stackTrace = rs.getString("stack_trace"),
                        occurredAt = rs.getTimestamp("occurred_at").time,
                        retryCount = rs.getInt("retry_count")
                    ))
                }
                return errors
            }
        }
    }

    private fun ResultSet.toIndexingJob() = IndexingJob(
        jobId = getObject("job_id") as UUID,
        collectionName = getString("collection_name"),
        status = getString("status"),
        startedAt = getTimestamp("started_at")?.time,
        completedAt = getTimestamp("completed_at")?.time,
        totalPages = getInt("total_pages"),
        indexedPages = getInt("indexed_pages"),
        failedPages = getInt("failed_pages"),
        currentPageId = getInt("current_page_id").takeIf { !wasNull() },
        errorMessage = getString("error_message")
    )

    fun close() {
        dataSource.close()
    }
}

data class IndexingJob(
    val jobId: UUID,
    val collectionName: String,
    val status: String,
    val startedAt: Long?,
    val completedAt: Long?,
    val totalPages: Int,
    val indexedPages: Int,
    val failedPages: Int,
    val currentPageId: Int?,
    val errorMessage: String?
)

data class IndexedPage(
    val pageId: Int,
    val collectionName: String,
    val contentHash: String,
    val embeddingVersion: String
)

data class IndexingError(
    val errorId: Int,
    val jobId: UUID,
    val pageId: Int?,
    val pageName: String?,
    val errorMessage: String,
    val stackTrace: String,
    val occurredAt: Long,
    val retryCount: Int
)
