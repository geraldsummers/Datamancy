package org.datamancy.datafetcher.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

/**
 * Generic checkpoint storage for incremental fetching.
 *
 * Allows fetchers to store checkpoints like:
 * - Last fetch timestamp
 * - Last page token
 * - Last seen ID
 * - Any other resumption state
 *
 * Usage:
 * ```
 * val checkpoint = checkpointStore.get("rss_feeds", "last_fetch_time")
 * // ... fetch data since checkpoint ...
 * checkpointStore.set("rss_feeds", "last_fetch_time", newTimestamp.toString())
 * ```
 */
class CheckpointStore(
    private val host: String = System.getenv("POSTGRES_HOST") ?: "postgres",
    private val port: Int = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: 5432,
    private val database: String = System.getenv("POSTGRES_DB") ?: "datamancy",
    private val user: String = System.getenv("POSTGRES_USER") ?: "datamancer",
    private val password: String = System.getenv("POSTGRES_PASSWORD") ?: ""
) : AutoCloseable {
    private val connection: Connection by lazy {
        val url = "jdbc:postgresql://$host:$port/$database"
        DriverManager.getConnection(url, user, password).apply {
            autoCommit = true
        }
    }

    override fun close() {
        try {
            if (!connection.isClosed) {
                connection.close()
            }
        } catch (e: Exception) {
            // Connection not initialized or already closed
        }
    }

    /**
     * Get checkpoint value for a source and key.
     * Returns null if checkpoint doesn't exist.
     */
    fun get(source: String, key: String, retry: Boolean = true): String? {
        return try {
            val sql = "SELECT value FROM checkpoints WHERE source = ? AND key = ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, source)
                stmt.setString(2, key)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    rs.getString("value")
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            // If query fails, ensure schema exists and retry once
            if (retry) {
                try {
                    ensureSchema()
                    return get(source, key, retry = false)
                } catch (retryException: Exception) {
                    logger.error(e) { "Failed to get checkpoint after retry: $source/$key" }
                    return null
                }
            } else {
                logger.error(e) { "Failed to get checkpoint: $source/$key" }
                return null
            }
        }
    }

    /**
     * Set checkpoint value for a source and key.
     * Creates or updates the checkpoint.
     */
    fun set(source: String, key: String, value: String, retry: Boolean = true) {
        try {
            val sql = """
                INSERT INTO checkpoints (source, key, value, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (source, key) DO UPDATE SET
                    value = EXCLUDED.value,
                    updated_at = EXCLUDED.updated_at
            """
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, source)
                stmt.setString(2, key)
                stmt.setString(3, value)
                stmt.setTimestamp(4, java.sql.Timestamp.from(
                    java.time.Instant.now()
                ))
                stmt.executeUpdate()
            }
            logger.debug { "Set checkpoint: $source/$key = $value" }
        } catch (e: Exception) {
            // If query fails, ensure schema exists and retry once
            if (retry) {
                try {
                    ensureSchema()
                    set(source, key, value, retry = false)
                } catch (retryException: Exception) {
                    logger.error(e) { "Failed to set checkpoint after retry: $source/$key" }
                }
            } else {
                logger.error(e) { "Failed to set checkpoint: $source/$key" }
            }
        }
    }

    /**
     * Delete checkpoint for a source and key.
     */
    fun delete(source: String, key: String) {
        try {
            val sql = "DELETE FROM checkpoints WHERE source = ? AND key = ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, source)
                stmt.setString(2, key)
                stmt.executeUpdate()
            }
            logger.debug { "Deleted checkpoint: $source/$key" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete checkpoint: $source/$key" }
        }
    }

    /**
     * Get all checkpoints for a source.
     */
    fun getAll(source: String, retry: Boolean = true): Map<String, String> {
        return try {
            val sql = "SELECT key, value FROM checkpoints WHERE source = ?"
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, source)
                val rs = stmt.executeQuery()
                val checkpoints = mutableMapOf<String, String>()
                while (rs.next()) {
                    checkpoints[rs.getString("key")] = rs.getString("value")
                }
                checkpoints
            }
        } catch (e: Exception) {
            // If query fails, ensure schema exists and retry once
            if (retry) {
                try {
                    ensureSchema()
                    return getAll(source, retry = false)
                } catch (retryException: Exception) {
                    logger.error(e) { "Failed to get all checkpoints after retry for: $source" }
                    return emptyMap()
                }
            } else {
                logger.error(e) { "Failed to get all checkpoints for: $source" }
                return emptyMap()
            }
        }
    }

    /**
     * Ensure the checkpoints table exists.
     */
    fun ensureSchema() {
        try {
            val sql = """
                CREATE TABLE IF NOT EXISTS checkpoints (
                    id SERIAL PRIMARY KEY,
                    source VARCHAR(100) NOT NULL,
                    key VARCHAR(100) NOT NULL,
                    value TEXT NOT NULL,
                    updated_at TIMESTAMP NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(source, key)
                );
                CREATE INDEX IF NOT EXISTS idx_checkpoints_source ON checkpoints(source);
            """
            connection.createStatement().use { stmt ->
                stmt.execute(sql)
            }
            logger.info { "Checkpoint schema ensured" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure checkpoint schema: ${e.message}" }
        }
    }
}

/**
 * Helper data class for checkpoint values
 */
data class Checkpoint(
    val source: String,
    val key: String,
    val value: String,
    val updatedAt: Instant
)
