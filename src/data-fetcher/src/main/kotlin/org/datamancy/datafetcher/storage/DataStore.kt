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
class PostgresStore {
    private val host = System.getenv("POSTGRES_HOST") ?: "postgres"
    private val port = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: 5432
    private val database = System.getenv("POSTGRES_DB") ?: "datamancy"
    private val user = System.getenv("POSTGRES_USER") ?: "datamancer"
    private val password = System.getenv("POSTGRES_PASSWORD") ?: ""

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
class ClickHouseStore {
    private val host = System.getenv("CLICKHOUSE_HOST") ?: "clickhouse"
    private val port = System.getenv("CLICKHOUSE_PORT")?.toIntOrNull() ?: 8123
    private val user = System.getenv("CLICKHOUSE_USER") ?: "default"
    private val password = System.getenv("CLICKHOUSE_PASSWORD") ?: ""

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
 * Filesystem storage for raw files
 */
class FileSystemStore {
    private val basePath = System.getenv("DATAFETCHER_DATA_PATH") ?: "/app/data"

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

    fun storeRawText(category: String, filename: String, content: String) {
        storeRawData(category, filename, content.toByteArray())
    }
}
