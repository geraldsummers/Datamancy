package org.datamancy.datafetcher.storage

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Instant
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}
private val gson = Gson()

/**
 * PostgreSQL storage for structured metadata
 */
class PostgresStore(
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

    fun storeFetchMetadata(
        source: String,
        category: String,
        itemCount: Int,
        fetchedAt: Instant,
        metadata: Map<String, Any> = emptyMap()
    ) {
        try {
            getConnection().use { conn ->
                conn.autoCommit = true
                val sql = """
                    INSERT INTO fetch_history (source, category, item_count, fetched_at, metadata)
                    VALUES (?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT DO NOTHING
                """
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, source)
                    stmt.setString(2, category)
                    stmt.setInt(3, itemCount)
                    stmt.setTimestamp(4, java.sql.Timestamp.from(java.time.Instant.ofEpochSecond(fetchedAt.epochSeconds)))
                    stmt.setString(5, gson.toJson(metadata))
                    stmt.executeUpdate()
                }
                logger.debug { "Stored fetch metadata: $source/$category" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to store fetch metadata" }
        }
    }

    fun ensureSchema() {
        try {
            getConnection().use { conn ->
                conn.autoCommit = true
                val sql = """
                    CREATE TABLE IF NOT EXISTS fetch_history (
                        id SERIAL PRIMARY KEY,
                        source VARCHAR(100) NOT NULL,
                        category VARCHAR(100) NOT NULL,
                        item_count INTEGER NOT NULL DEFAULT 0,
                        fetched_at TIMESTAMP NOT NULL,
                        metadata JSONB,
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    CREATE INDEX IF NOT EXISTS idx_fetch_history_source ON fetch_history(source);
                    CREATE INDEX IF NOT EXISTS idx_fetch_history_fetched_at ON fetch_history(fetched_at);
                """
                conn.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
                logger.info { "PostgreSQL schema ensured" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure PostgreSQL schema" }
        }
    }
}

/**
 * ClickHouse storage for time-series data
 */
class ClickHouseStore(
    private val host: String = System.getenv("CLICKHOUSE_HOST") ?: "clickhouse",
    private val port: Int = System.getenv("CLICKHOUSE_PORT")?.toIntOrNull() ?: 8123,
    private val user: String = System.getenv("CLICKHOUSE_USER") ?: "default",
    private val password: String = System.getenv("CLICKHOUSE_PASSWORD") ?: ""
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun executeQuery(query: String) {
        val url = "http://$host:$port/?user=$user&password=$password"
        val request = Request.Builder()
            .url(url)
            .post(okhttp3.RequestBody.create(null, query))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("ClickHouse query failed: ${response.code} - ${response.body?.string()}")
            }
            logger.debug { "ClickHouse query executed: ${query.take(100)}..." }
        }
    }

    fun storeMarketData(
        symbol: String,
        price: Double,
        volume: Double?,
        timestamp: Instant,
        source: String,
        metadata: Map<String, Any> = emptyMap()
    ) {
        try {
            val metadataJson = gson.toJson(metadata).replace("'", "\\'")
            val sql = """
                INSERT INTO market_data (timestamp, symbol, price, volume, source, metadata)
                VALUES ('${timestamp}', '$symbol', $price, ${volume ?: 0.0}, '$source', '$metadataJson')
            """
            executeQuery(sql)
        } catch (e: Exception) {
            logger.error(e) { "Failed to store market data for $symbol" }
        }
    }

    fun ensureSchema() {
        try {
            val sql = """
                CREATE TABLE IF NOT EXISTS market_data (
                    timestamp DateTime64(3),
                    symbol String,
                    price Float64,
                    volume Float64,
                    source String,
                    metadata String
                ) ENGINE = MergeTree()
                ORDER BY (symbol, timestamp);
            """
            executeQuery(sql)
            logger.info { "ClickHouse schema ensured" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure ClickHouse schema" }
        }
    }
}

/**
 * Filesystem storage for raw files with canonical path structure.
 *
 * Path convention: /raw/{source}/{yyyy}/{mm}/{dd}/{runId}/{itemId}.{ext}
 * This provides:
 * - Time-based organization for easy cleanup/archival
 * - Run-level isolation
 * - Deterministic paths for retrieval
 *
 * Usage:
 * ```
 * fileStore.storeRaw(
 *     source = "rss_feeds",
 *     runId = "run_123",
 *     itemId = "article_456",
 *     content = jsonBytes,
 *     extension = "json"
 * )
 * ```
 */
class FileSystemStore(
    basePath: String? = null
) {
    private val basePath = basePath ?: System.getenv("DATAFETCHER_DATA_PATH") ?: "/app/data"

    /**
     * Store raw data with canonical path structure.
     * Path: /raw/{source}/{yyyy}/{mm}/{dd}/{runId}/{itemId}.{ext}
     */
    fun storeRaw(
        source: String,
        runId: String,
        itemId: String,
        content: ByteArray,
        extension: String = "bin",
        timestamp: kotlinx.datetime.Instant = kotlinx.datetime.Clock.System.now()
    ): String {
        return try {
            val path = buildCanonicalPath(source, runId, itemId, extension, timestamp)
            val file = File("$basePath/$path")
            file.parentFile.mkdirs()
            file.writeBytes(content)
            logger.info { "Stored raw data: $path (${content.size} bytes)" }
            path
        } catch (e: Exception) {
            logger.error(e) { "Failed to store raw data: $source/$runId/$itemId" }
            throw e
        }
    }

    /**
     * Store raw text with canonical path structure.
     */
    fun storeRawText(
        source: String,
        runId: String,
        itemId: String,
        content: String,
        extension: String = "txt",
        timestamp: kotlinx.datetime.Instant = kotlinx.datetime.Clock.System.now()
    ): String {
        return storeRaw(source, runId, itemId, content.toByteArray(), extension, timestamp)
    }

    /**
     * Read raw data from canonical path.
     */
    fun readRaw(path: String): ByteArray {
        return try {
            File("$basePath/$path").readBytes()
        } catch (e: Exception) {
            logger.error(e) { "Failed to read raw data: $path" }
            throw e
        }
    }

    /**
     * Check if raw data exists at path.
     */
    fun exists(path: String): Boolean {
        return File("$basePath/$path").exists()
    }

    /**
     * Build canonical path: raw/{source}/{yyyy}/{mm}/{dd}/{runId}/{itemId}.{ext}
     */
    private fun buildCanonicalPath(
        source: String,
        runId: String,
        itemId: String,
        extension: String,
        timestamp: kotlinx.datetime.Instant
    ): String {
        val instant = java.time.Instant.ofEpochMilli(timestamp.toEpochMilliseconds())
        val zonedDateTime = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneOffset.UTC)

        val year = zonedDateTime.year.toString().padStart(4, '0')
        val month = zonedDateTime.monthValue.toString().padStart(2, '0')
        val day = zonedDateTime.dayOfMonth.toString().padStart(2, '0')

        // Sanitize itemId for filesystem safety
        val safeItemId = itemId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")

        return "raw/$source/$year/$month/$day/$runId/$safeItemId.$extension"
    }

    /**
     * Legacy method for backward compatibility.
     * @deprecated Use storeRaw with canonical paths instead.
     */
    @Deprecated("Use storeRaw with canonical paths")
    fun storeRawData(category: String, filename: String, content: ByteArray) {
        try {
            val dir = File("$basePath/$category")
            dir.mkdirs()
            val file = File(dir, filename)
            file.writeBytes(content)
            logger.info { "Stored raw data: $category/$filename (${content.size} bytes)" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to store raw data: $category/$filename" }
        }
    }

    /**
     * Legacy method for backward compatibility.
     * @deprecated Use storeRawText with canonical paths instead.
     */
    @Deprecated("Use storeRawText with canonical paths")
    fun storeRawText(category: String, filename: String, content: String) {
        storeRawData(category, filename, content.toByteArray())
    }
}

/**
 * Raw storage metadata record
 */
data class RawStorageRecord(
    val source: String,
    val runId: String,
    val itemId: String,
    val path: String,
    val sizeBytes: Long,
    val storedAt: kotlinx.datetime.Instant
)
