package org.datamancy.datafetcher.storage

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

private val logger = KotlinLogging.logger {}

/**
 * Result of dedupe check
 */
enum class DedupeResult {
    NEW,        // Item has never been seen before
    UPDATED,    // Item exists but content has changed
    UNCHANGED   // Item exists with same content
}

/**
 * Generic deduplication storage for preventing repeated ingestion.
 *
 * Tracks items by source, itemId, and content hash to determine if:
 * - Item is NEW (never seen)
 * - Item is UPDATED (seen before but content changed)
 * - Item is UNCHANGED (seen before with same content)
 *
 * Usage:
 * ```
 * val result = dedupeStore.shouldUpsert("rss_feeds", itemId, contentHash, runId)
 * when (result) {
 *     DedupeResult.NEW -> { /* insert new item */ }
 *     DedupeResult.UPDATED -> { /* update existing item */ }
 *     DedupeResult.UNCHANGED -> { /* skip */ }
 * }
 * ```
 */
class DedupeStore(
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
     * Check if item should be upserted (inserted or updated).
     * Automatically records the check in the dedupe table.
     *
     * @param source Source identifier (e.g., "rss_feeds", "market_data")
     * @param itemId Unique identifier for the item within the source
     * @param contentHash Hash of the item's content
     * @param runId ID of the current fetch run
     * @return DedupeResult indicating whether item is NEW, UPDATED, or UNCHANGED
     */
    fun shouldUpsert(source: String, itemId: String, contentHash: String, runId: String): DedupeResult {
        return try {
            // Check if item exists
            val existingHash = getExistingHash(connection, source, itemId)

            val result = when {
                existingHash == null -> DedupeResult.NEW
                existingHash != contentHash -> DedupeResult.UPDATED
                else -> DedupeResult.UNCHANGED
            }

            // Update dedupe record
            upsertDedupeRecord(connection, source, itemId, contentHash, runId)

            logger.debug { "Dedupe check: $source/$itemId = $result" }
            result
        } catch (e: Exception) {
            logger.error(e) { "Failed dedupe check for $source/$itemId, defaulting to NEW" }
            DedupeResult.NEW // Fail open - allow upsert on error
        }
    }

    /**
     * Get the existing content hash for an item, if any.
     */
    private fun getExistingHash(conn: Connection, source: String, itemId: String): String? {
        val sql = "SELECT content_hash FROM dedupe_records WHERE source = ? AND item_id = ?"
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, source)
            stmt.setString(2, itemId)
            val rs = stmt.executeQuery()
            return if (rs.next()) {
                rs.getString("content_hash")
            } else {
                null
            }
        }
    }

    /**
     * Insert or update dedupe record.
     */
    private fun upsertDedupeRecord(conn: Connection, source: String, itemId: String, contentHash: String, runId: String) {
        val sql = """
            INSERT INTO dedupe_records (source, item_id, content_hash, last_seen_run_id, last_seen_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (source, item_id) DO UPDATE SET
                content_hash = EXCLUDED.content_hash,
                last_seen_run_id = EXCLUDED.last_seen_run_id,
                last_seen_at = EXCLUDED.last_seen_at
        """
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, source)
            stmt.setString(2, itemId)
            stmt.setString(3, contentHash)
            stmt.setString(4, runId)
            stmt.setTimestamp(5, java.sql.Timestamp.from(java.time.Instant.now()))
            stmt.executeUpdate()
        }
    }

    /**
     * Get stats for a source (total items tracked).
     */
    fun getStats(source: String): DedupeStats {
        return try {
            val sql = """
                SELECT
                    COUNT(*) as total_items,
                    COUNT(DISTINCT last_seen_run_id) as total_runs,
                    MAX(last_seen_at) as last_activity
                FROM dedupe_records
                WHERE source = ?
            """
            connection.prepareStatement(sql).use { stmt ->
                stmt.setString(1, source)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    DedupeStats(
                        source = source,
                        totalItems = rs.getLong("total_items"),
                        totalRuns = rs.getLong("total_runs"),
                        lastActivity = rs.getTimestamp("last_activity")?.toInstant()?.let {
                            Instant.fromEpochMilliseconds(it.toEpochMilli())
                        }
                    )
                } else {
                    DedupeStats(source, 0, 0, null)
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to get dedupe stats for: $source" }
            DedupeStats(source, 0, 0, null)
        }
    }

    /**
     * Ensure the dedupe_records table exists.
     */
    fun ensureSchema() {
        try {
            val sql = """
                CREATE TABLE IF NOT EXISTS dedupe_records (
                    id SERIAL PRIMARY KEY,
                    source VARCHAR(100) NOT NULL,
                    item_id VARCHAR(500) NOT NULL,
                    content_hash VARCHAR(64) NOT NULL,
                    last_seen_run_id VARCHAR(100) NOT NULL,
                    last_seen_at TIMESTAMP NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE(source, item_id)
                );
                CREATE INDEX IF NOT EXISTS idx_dedupe_source ON dedupe_records(source);
                CREATE INDEX IF NOT EXISTS idx_dedupe_last_seen ON dedupe_records(last_seen_at);
            """
            connection.createStatement().use { stmt ->
                stmt.execute(sql)
            }
            logger.info { "Dedupe schema ensured" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure dedupe schema" }
        }
    }
}

/**
 * Statistics about dedupe records for a source
 */
data class DedupeStats(
    val source: String,
    val totalItems: Long,
    val totalRuns: Long,
    val lastActivity: Instant?
)

/**
 * Utility functions for content hashing
 */
object ContentHasher {
    /**
     * Compute SHA-256 hash of content.
     * Useful for creating stable content hashes.
     */
    fun hash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(content.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Compute hash of normalized JSON content.
     * Removes whitespace variations.
     */
    fun hashJson(json: String): String {
        val normalized = json.replace("\\s+".toRegex(), "")
        return hash(normalized)
    }
}
