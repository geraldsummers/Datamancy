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
) {
    private fun getConnection(): Connection {
        val url = "jdbc:postgresql://$host:$port/$database"
        return DriverManager.getConnection(url, user, password)
    }

    /**
     * Get checkpoint value for a source and key.
     * Returns null if checkpoint doesn't exist.
     */
    fun get(source: String, key: String): String? {
        return try {
            getConnection().use { conn ->
                val sql = "SELECT value FROM checkpoints WHERE source = ? AND key = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, source)
                    stmt.setString(2, key)
                    val rs = stmt.executeQuery()
                    if (rs.next()) {
                        rs.getString("value")
                    } else {
                        null
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get checkpoint: $source/$key" }
            null
        }
    }

    /**
     * Set checkpoint value for a source and key.
     * Creates or updates the checkpoint.
     */
    fun set(source: String, key: String, value: String) {
        try {
            getConnection().use { conn ->
                val sql = """
                    INSERT INTO checkpoints (source, key, value, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (source, key) DO UPDATE SET
                        value = EXCLUDED.value,
                        updated_at = EXCLUDED.updated_at
                """
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, source)
                    stmt.setString(2, key)
                    stmt.setString(3, value)
                    stmt.setTimestamp(4, java.sql.Timestamp.from(
                        java.time.Instant.now()
                    ))
                    stmt.executeUpdate()
                }
                logger.debug { "Set checkpoint: $source/$key = $value" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to set checkpoint: $source/$key" }
        }
    }

    /**
     * Delete checkpoint for a source and key.
     */
    fun delete(source: String, key: String) {
        try {
            getConnection().use { conn ->
                val sql = "DELETE FROM checkpoints WHERE source = ? AND key = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, source)
                    stmt.setString(2, key)
                    stmt.executeUpdate()
                }
                logger.debug { "Deleted checkpoint: $source/$key" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to delete checkpoint: $source/$key" }
        }
    }

    /**
     * Get all checkpoints for a source.
     */
    fun getAll(source: String): Map<String, String> {
        return try {
            getConnection().use { conn ->
                val sql = "SELECT key, value FROM checkpoints WHERE source = ?"
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, source)
                    val rs = stmt.executeQuery()
                    val checkpoints = mutableMapOf<String, String>()
                    while (rs.next()) {
                        checkpoints[rs.getString("key")] = rs.getString("value")
                    }
                    checkpoints
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get all checkpoints for: $source" }
            emptyMap()
        }
    }

    /**
     * Ensure the checkpoints table exists.
     */
    fun ensureSchema() {
        try {
            getConnection().use { conn ->
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
                conn.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
                logger.info { "Checkpoint schema ensured" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure checkpoint schema" }
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
