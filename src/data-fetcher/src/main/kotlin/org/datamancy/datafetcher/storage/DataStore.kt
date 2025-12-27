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

    fun storeLegalDocument(
        docId: String,
        jurisdiction: String,
        docType: String,
        title: String,
        year: String?,
        identifier: String?,
        url: String,
        status: String?,
        sectionNumber: String,
        sectionTitle: String,
        content: String,
        contentMarkdown: String,
        fetchedAt: Instant,
        contentHash: String,
        metadata: Map<String, Any> = emptyMap(),
        supersededBy: String = "",
        validFrom: Instant = fetchedAt,
        validTo: Instant? = null
    ) {
        try {
            val metadataJson = gson.toJson(metadata).replace("'", "\\'")
            val escapedTitle = title.replace("'", "\\'")
            val escapedSectionTitle = sectionTitle.replace("'", "\\'")
            val escapedContent = content.replace("'", "\\'")
            val escapedMarkdown = contentMarkdown.replace("'", "\\'")
            val escapedUrl = url.replace("'", "\\'")
            val escapedSupersededBy = supersededBy.replace("'", "\\'")
            val validToStr = validTo?.let { "'$it'" } ?: "NULL"

            val sql = """
                INSERT INTO legal_documents (
                    doc_id, jurisdiction, doc_type, title, year, identifier, url, status,
                    section_number, section_title, content, content_markdown,
                    fetched_at, content_hash, metadata, superseded_by, valid_from, valid_to, last_checked
                ) VALUES (
                    '$docId', '$jurisdiction', '$docType', '$escapedTitle',
                    '${year ?: ""}', '${identifier ?: ""}', '$escapedUrl', '${status ?: ""}',
                    '$sectionNumber', '$escapedSectionTitle', '$escapedContent', '$escapedMarkdown',
                    '${fetchedAt}', '$contentHash', '$metadataJson', '$escapedSupersededBy',
                    '${validFrom}', $validToStr, now64(3)
                )
            """
            executeQuery(sql)
        } catch (e: Exception) {
            logger.error(e) { "Failed to store legal document: $docId/$sectionNumber" }
        }
    }

    /**
     * Get content hash for a specific legal document section.
     * Returns null if document doesn't exist.
     */
    fun getLegalDocumentHash(url: String, sectionNumber: String): String? {
        return try {
            val escapedUrl = url.replace("'", "\\'")
            val sql = """
                SELECT content_hash
                FROM legal_documents
                WHERE url = '$escapedUrl' AND section_number = '$sectionNumber'
                ORDER BY fetched_at DESC
                LIMIT 1
            """
            val result = executeQuery(sql)
            if (result.isNotEmpty()) result.first().split("\t").firstOrNull() else null
        } catch (e: Exception) {
            logger.error(e) { "Failed to get hash for $url/$sectionNumber" }
            null
        }
    }

    /**
     * Get all active legal documents (by URL) from database.
     */
    fun getAllActiveLegalDocumentUrls(jurisdiction: String? = null): Set<String> {
        return try {
            val whereClause = if (jurisdiction != null) {
                "WHERE jurisdiction = '${jurisdiction.replace("'", "\\'")}' AND (status = 'In force' OR status = '')"
            } else {
                "WHERE status = 'In force' OR status = ''"
            }

            val sql = """
                SELECT DISTINCT url
                FROM legal_documents
                $whereClause
            """
            executeQuery(sql).toSet()
        } catch (e: Exception) {
            logger.error(e) { "Failed to get active document URLs" }
            emptySet()
        }
    }

    /**
     * Mark legal documents as repealed by URL.
     */
    fun markLegalDocumentRepealed(url: String, repealedAt: Instant = Clock.System.now()) {
        try {
            val escapedUrl = url.replace("'", "\\'")

            // Insert new version with Repealed status and valid_to set
            val sql = """
                INSERT INTO legal_documents
                SELECT
                    doc_id, jurisdiction, doc_type, title, year, identifier, url, 'Repealed' AS status,
                    section_number, section_title, content, content_markdown,
                    now64(3) AS fetched_at, content_hash, metadata, superseded_by, valid_from,
                    '$repealedAt' AS valid_to, now64(3) AS last_checked
                FROM legal_documents
                WHERE url = '$escapedUrl'
                ORDER BY fetched_at DESC
                LIMIT 1 BY url, section_number
            """
            executeQuery(sql)
            logger.info { "Marked $url as repealed" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark document as repealed: $url" }
        }
    }

    /**
     * Get last fetch time for a legal document URL.
     */
    fun getLastCheckedTime(url: String): Instant? {
        return try {
            val escapedUrl = url.replace("'", "\\'")
            val sql = """
                SELECT last_checked
                FROM legal_documents
                WHERE url = '$escapedUrl'
                ORDER BY fetched_at DESC
                LIMIT 1
            """
            val result = executeQuery(sql)
            if (result.isNotEmpty()) {
                Instant.parse(result.first().trim())
            } else null
        } catch (e: Exception) {
            logger.error(e) { "Failed to get last checked time for $url" }
            null
        }
    }

    /**
     * Get content hash for a specific legal document section.
     * Returns null if document doesn't exist.
     */
    fun getLegalDocumentHash(url: String, sectionNumber: String): String? {
        return try {
            val escapedUrl = url.replace("'", "\\'")
            val sql = """
                SELECT content_hash
                FROM legal_documents
                WHERE url = '$escapedUrl' AND section_number = '$sectionNumber'
                ORDER BY fetched_at DESC
                LIMIT 1
            """
            val result = executeQuery(sql)
            if (result.isNotEmpty()) result.first().split("\t").firstOrNull() else null
        } catch (e: Exception) {
            logger.error(e) { "Failed to get hash for $url/$sectionNumber" }
            null
        }
    }

    /**
     * Get all active legal documents (by URL) from database.
     */
    fun getAllActiveLegalDocumentUrls(jurisdiction: String? = null): Set<String> {
        return try {
            val whereClause = if (jurisdiction != null) {
                "WHERE jurisdiction = '${jurisdiction.replace("'", "\\'")}' AND (status = 'In force' OR status = '')"
            } else {
                "WHERE status = 'In force' OR status = ''"
            }

            val sql = """
                SELECT DISTINCT url
                FROM legal_documents
                $whereClause
            """
            executeQuery(sql).toSet()
        } catch (e: Exception) {
            logger.error(e) { "Failed to get active document URLs" }
            emptySet()
        }
    }

    /**
     * Mark legal documents as repealed by URL.
     */
    fun markLegalDocumentRepealed(url: String, repealedAt: Instant = Clock.System.now()) {
        try {
            val escapedUrl = url.replace("'", "\\'")

            // Insert new version with Repealed status and valid_to set
            val sql = """
                INSERT INTO legal_documents
                SELECT
                    doc_id, jurisdiction, doc_type, title, year, identifier, url, 'Repealed' AS status,
                    section_number, section_title, content, content_markdown,
                    now64(3) AS fetched_at, content_hash, metadata, superseded_by, valid_from,
                    '$repealedAt' AS valid_to, now64(3) AS last_checked
                FROM legal_documents
                WHERE url = '$escapedUrl'
                ORDER BY fetched_at DESC
                LIMIT 1 BY url, section_number
            """
            executeQuery(sql)
            logger.info { "Marked $url as repealed" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to mark document as repealed: $url" }
        }
    }

    /**
     * Get last fetch time for a legal document URL.
     */
    fun getLastCheckedTime(url: String): Instant? {
        return try {
            val escapedUrl = url.replace("'", "\\'")
            val sql = """
                SELECT last_checked
                FROM legal_documents
                WHERE url = '$escapedUrl'
                ORDER BY fetched_at DESC
                LIMIT 1
            """
            val result = executeQuery(sql)
            if (result.isNotEmpty()) {
                Instant.parse(result.first().trim())
            } else null
        } catch (e: Exception) {
            logger.error(e) { "Failed to get last checked time for $url" }
            null
        }
    }

    fun ensureSchema() {
        try {
            // Market data table
            val marketDataSql = """
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
            executeQuery(marketDataSql)

            // Legal documents table
            val legalDocsSql = """
                CREATE TABLE IF NOT EXISTS legal_documents (
                    doc_id String,
                    jurisdiction String,
                    doc_type String,
                    title String,
                    year String,
                    identifier String,
                    url String,
                    status String,
                    section_number String,
                    section_title String,
                    content String,
                    content_markdown String,
                    fetched_at DateTime64(3),
                    content_hash String,
                    metadata String,
                    superseded_by String DEFAULT '',
                    valid_from DateTime64(3) DEFAULT now64(3),
                    valid_to Nullable(DateTime64(3)) DEFAULT NULL,
                    last_checked DateTime64(3) DEFAULT now64(3)
                ) ENGINE = ReplacingMergeTree(fetched_at)
                ORDER BY (url, section_number);
            """
            executeQuery(legalDocsSql)

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
