package org.datamancy.searchservice

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.oshai.kotlinlogging.KotlinLogging
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Points.*
import io.qdrant.client.grpc.Collections.*
import io.qdrant.client.WithPayloadSelectorFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Hybrid search gateway combining vector search (Qdrant) and BM25 (ClickHouse).
 * Provides close() method for proper resource management.
 */
class SearchGateway(
    private val qdrantUrl: String,
    private val clickhouseUrl: String,
    private val embeddingServiceUrl: String
) {
    private val clickhouseUser = System.getenv("CLICKHOUSE_USER") ?: "default"
    private val clickhousePassword = System.getenv("CLICKHOUSE_PASSWORD") ?: ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Qdrant client
    private val qdrantHost = qdrantUrl.removePrefix("http://").removePrefix("https://").split(":")[0]
    private val qdrantPort = qdrantUrl.removePrefix("http://").removePrefix("https://").split(":").getOrNull(1)?.toIntOrNull() ?: 6334
    private val qdrant = QdrantClient(QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false).build())

    // ClickHouse connection pool (prevents connection exhaustion under load)
    private val clickhousePool: HikariDataSource by lazy {
        HikariConfig().apply {
            jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"
            username = clickhouseUser
            password = clickhousePassword
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 5000
            idleTimeout = 300000
            maxLifetime = 600000
            poolName = "search-clickhouse-pool"
            isReadOnly = true
        }.let { HikariDataSource(it) }
    }

    /**
     * Performs hybrid search across specified collections.
     */
    suspend fun search(
        query: String,
        collections: List<String>,
        mode: String = "hybrid",
        limit: Int = 20
    ): List<SearchResult> = coroutineScope {
        // Resolve collections (handle wildcard)
        val targetCollections = if (collections.contains("*")) {
            listCollections()
        } else {
            collections
        }

        logger.info { "Searching in ${targetCollections.size} collections: mode=$mode, query=$query" }

        when (mode) {
            "vector" -> searchVector(query, targetCollections, limit)
            "bm25" -> searchBM25(query, targetCollections, limit)
            "hybrid" -> {
                // Run both searches in parallel
                val vectorDeferred = async { searchVector(query, targetCollections, limit / 2) }
                val bm25Deferred = async { searchBM25(query, targetCollections, limit / 2) }

                val vectorResults = vectorDeferred.await()
                val bm25Results = bm25Deferred.await()

                // Merge and rerank results
                rerank(vectorResults, bm25Results, limit)
            }
            else -> throw IllegalArgumentException("Invalid search mode: $mode")
        }
    }

    /**
     * Vector search using Qdrant.
     */
    private suspend fun searchVector(query: String, collections: List<String>, limit: Int): List<SearchResult> = coroutineScope {
        // Generate query embedding
        val embedding = generateEmbedding(query)

        // Search in parallel across collections
        val results = collections.map { collection ->
            async {
                try {
                    searchQdrant(collection, embedding, limit)
                } catch (e: Exception) {
                    when {
                        e.message?.contains("doesn't exist", ignoreCase = true) == true -> {
                            logger.info { "Collection does not exist yet: $collection (skipping)" }
                        }
                        e.message?.contains("NOT_FOUND", ignoreCase = true) == true -> {
                            logger.info { "Collection not found: $collection (skipping)" }
                        }
                        else -> {
                            logger.warn(e) { "Failed to search Qdrant collection: $collection" }
                        }
                    }
                    emptyList()
                }
            }
        }.awaitAll().flatten()

        // Sort by score and take top results
        results.sortedByDescending { it.score }.take(limit)
    }

    /**
     * BM25 search using ClickHouse.
     */
    private suspend fun searchBM25(query: String, collections: List<String>, limit: Int): List<SearchResult> = coroutineScope {
        // Search in parallel across collections
        val results = collections.map { collection ->
            async {
                try {
                    searchClickHouse(collection, query, limit)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to search ClickHouse table: $collection" }
                    emptyList()
                }
            }
        }.awaitAll().flatten()

        // Sort by score and take top results
        results.sortedByDescending { it.score }.take(limit)
    }

    /**
     * Searches Qdrant collection.
     * Runs on IO dispatcher to avoid blocking coroutine threads.
     */
    private suspend fun searchQdrant(collection: String, embedding: List<Float>, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        val points = qdrant.searchAsync(
            SearchPoints.newBuilder()
                .setCollectionName(collection)
                .addAllVector(embedding)
                .setLimit(limit.toLong())
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .build()
        ).get()

        return@withContext points.map { point ->
            val payload = point.payloadMap
            // Pipeline stores: title, link, description (or url, text, etc.)
            val url = payload["link"]?.stringValue ?: payload["url"]?.stringValue ?: ""
            val title = payload["title"]?.stringValue ?: payload["name"]?.stringValue ?: ""
            val snippet = payload["description"]?.stringValue ?: payload["text"]?.stringValue?.take(200) ?: ""

            // Preserve ALL payload fields in metadata (not just hardcoded ones)
            val metadata = mutableMapOf<String, String>("type" to "vector")
            payload.forEach { (key, value) ->
                when {
                    value.hasStringValue() -> metadata[key] = value.stringValue
                    value.hasIntegerValue() -> metadata[key] = value.integerValue.toString()
                    value.hasDoubleValue() -> metadata[key] = value.doubleValue.toString()
                    value.hasBoolValue() -> metadata[key] = value.boolValue.toString()
                }
            }

            SearchResult(
                url = url,
                title = title,
                snippet = snippet,
                score = point.score.toDouble(),
                source = collection,
                metadata = metadata,
                contentType = SearchResult.inferContentType(collection, url, metadata),
                capabilities = SearchResult.inferCapabilities(collection, url, metadata)
            )
        }
    }

    /**
     * Validates and sanitizes table/collection name to prevent SQL injection.
     */
    private fun sanitizeTableName(name: String): String {
        // ClickHouse table names: alphanumeric, underscore only (no dashes, no special chars)
        require(name.isNotBlank()) { "Table name cannot be blank" }
        val sanitized = name.replace("-", "_")
        require(sanitized.matches(Regex("^[a-zA-Z0-9_]{1,64}$"))) {
            "Invalid table name: $name (must be alphanumeric/underscore, max 64 chars)"
        }
        return sanitized
    }

    /**
     * Searches ClickHouse table using full-text search.
     * Uses parameterized queries to prevent SQL injection.
     * Runs on IO dispatcher to avoid blocking coroutine threads.
     */
    private suspend fun searchClickHouse(collection: String, query: String, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        val tableName = sanitizeTableName(collection)
        val safeLimit = limit.coerceIn(1, 1000) // Prevent DoS with massive limits

        // Check if table exists first (using connection pool)
        val tableExists = clickhousePool.connection.use { conn ->
            // ClickHouse doesn't support PreparedStatement for EXISTS, but table name is now sanitized
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("EXISTS TABLE default.$tableName")
                rs.next() && rs.getInt(1) == 1
            }
        }

        if (!tableExists) {
            logger.debug { "ClickHouse table default.$tableName does not exist, skipping BM25 search" }
            return@withContext emptyList()
        }

        // Use parameterized query to prevent SQL injection
        // Note: ClickHouse JDBC driver has limited PreparedStatement support, but we use it where possible
        val sql = """
            SELECT
                page_id,
                page_name,
                page_url,
                substring(content, 1, 200) as snippet,
                length(content) as doc_length,
                (length(content) - length(replaceAll(lower(content), lower(?), ''))) / length(?) as term_freq
            FROM default.$tableName
            WHERE positionCaseInsensitive(content, ?) > 0
            ORDER BY term_freq DESC
            LIMIT ?
        """.trimIndent()

        val results = mutableListOf<SearchResult>()

        clickhousePool.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                // Set parameters (query appears 3 times in SQL)
                stmt.setString(1, query)
                stmt.setString(2, query)
                stmt.setString(3, query)
                stmt.setInt(4, safeLimit)

                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val url = rs.getString("page_url") ?: ""
                    val title = rs.getString("page_name") ?: ""
                    val snippet = rs.getString("snippet") ?: ""
                    val termFreq = rs.getDouble("term_freq")

                    val metadata = mapOf("type" to "bm25")
                    results.add(SearchResult(
                        url = url,
                        title = title,
                        snippet = snippet,
                        score = termFreq,
                        source = collection,
                        metadata = metadata,
                        contentType = SearchResult.inferContentType(collection, url, metadata),
                        capabilities = SearchResult.inferCapabilities(collection, url, metadata)
                    ))
                }
            }
        }

        return@withContext results
    }

    /**
     * Reranks and merges vector and BM25 results.
     * Uses reciprocal rank fusion (RRF).
     */
    private fun rerank(vectorResults: List<SearchResult>, bm25Results: List<SearchResult>, limit: Int): List<SearchResult> {
        val k = 60 // RRF constant
        val scores = mutableMapOf<String, Double>()
        val resultMap = mutableMapOf<String, SearchResult>()

        // Add vector results
        vectorResults.forEachIndexed { index, result ->
            val url = result.url
            scores[url] = (scores[url] ?: 0.0) + (1.0 / (k + index + 1))
            resultMap[url] = result
        }

        // Add BM25 results
        bm25Results.forEachIndexed { index, result ->
            val url = result.url
            scores[url] = (scores[url] ?: 0.0) + (1.0 / (k + index + 1))
            if (!resultMap.containsKey(url)) {
                resultMap[url] = result
            }
        }

        // Sort by combined score
        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { (url, score) ->
                val original = resultMap[url]
                original?.copy(
                    score = score,
                    metadata = original.metadata + ("reranked_score" to score.toString())
                )
            }
    }

    /**
     * Generates embedding using embedding service.
     * Runs on IO dispatcher to avoid blocking coroutine threads.
     */
    private suspend fun generateEmbedding(text: String): List<Float> = withContext(Dispatchers.IO) {
        // Embedding service expects {"inputs": text} format
        val payload = gson.toJson(mapOf("inputs" to text))

        val request = Request.Builder()
            .url("$embeddingServiceUrl/embed")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to generate embedding: ${response.code}")
            }

            // Response is [[embedding]] - double array
            val responseBody = response.body?.string()
            val json = gson.fromJson(responseBody, com.google.gson.JsonArray::class.java)
            val embeddingArray = json.get(0).asJsonArray
            return@withContext embeddingArray.map { it.asFloat }
        }
    }

    /**
     * Lists available Qdrant collections.
     * Runs on IO dispatcher to avoid blocking coroutine threads.
     */
    suspend fun listCollections(): List<String> = withContext(Dispatchers.IO) {
        return@withContext try {
            qdrant.listCollectionsAsync().get()
        } catch (e: Exception) {
            logger.error(e) { "Failed to list collections" }
            emptyList()
        }
    }

    /**
     * Ensures required Qdrant collections exist. Creates them if missing.
     * Called on service startup to prevent search failures.
     */
    fun ensureCollectionsExist() {
        val requiredCollections = listOf(
            "bookstack-docs", "rss_feeds", "cve", "torrents",
            "wikipedia", "australian_laws", "linux_docs", "debian_wiki", "arch_wiki"
        )

        logger.info { "Checking existence of ${requiredCollections.size} required collections..." }

        for (collectionName in requiredCollections) {
            try {
                // Check if collection exists
                qdrant.getCollectionInfoAsync(collectionName).get()
                logger.debug { "Collection exists: $collectionName" }
            } catch (e: Exception) {
                // Collection doesn't exist - create it
                logger.info { "Creating missing collection: $collectionName" }
                try {
                    qdrant.createCollectionAsync(
                        collectionName,
                        io.qdrant.client.grpc.Collections.VectorParams.newBuilder()
                            .setSize(1024)  // BGE-M3 embedding size
                            .setDistance(io.qdrant.client.grpc.Collections.Distance.Cosine)
                            .build()
                    ).get()
                    logger.info { "Created collection: $collectionName" }
                } catch (createError: Exception) {
                    logger.warn(createError) { "Failed to create collection $collectionName (may already exist or permissions issue)" }
                }
            }
        }
    }

    /**
     * Cleanup resources (connection pools, HTTP clients) on shutdown.
     */
    fun close() {
        try {
            // Close ClickHouse connection pool
            try {
                clickhousePool.close()
            } catch (e: Exception) {
                logger.debug(e) { "ClickHouse pool close failed" }
            }

            logger.info { "SearchGateway resources cleaned up successfully" }
        } catch (e: Exception) {
            logger.warn(e) { "Error during SearchGateway cleanup" }
        }
    }
}

enum class AudienceType {
    HUMAN,      // Needs rich UI, modals, charts, interactive elements
    AGENT,      // Needs structured data, APIs, raw content
    BOTH        // Useful for both audiences
}

@Serializable
data class ContentCapabilities(
    val humanFriendly: Boolean,
    val agentFriendly: Boolean,
    val hasTimeSeries: Boolean = false,      // Can be graphed in Grafana
    val hasRichContent: Boolean = false,     // Has full text/media
    val isInteractive: Boolean = false,      // Supports chat/Q&A
    val isStructured: Boolean = false        // Has structured data fields
)

@Serializable
data class SearchResult(
    val url: String,
    val title: String,
    val snippet: String,
    val score: Double,
    val source: String,
    val metadata: Map<String, String> = emptyMap(),
    val contentType: String,
    val capabilities: ContentCapabilities
) {
    companion object {
        fun inferContentType(source: String, url: String, metadata: Map<String, String>): String {
            return when {
                source.contains("bookstack") || url.contains("bookstack") -> "bookstack"
                source.contains("rss") || source.contains("arxiv") || source.contains("news") -> "article"
                source.contains("market") || source.contains("crypto") || source.contains("stock") -> "market"
                source.contains("weather") -> "weather"
                source.contains("cve") || source.contains("security") -> "cve"
                source.contains("wiki") -> "wikipedia"
                source.contains("docs") || source.contains("documentation") -> "documentation"
                else -> "generic"
            }
        }

        fun inferCapabilities(source: String, url: String, metadata: Map<String, String>): ContentCapabilities {
            val type = inferContentType(source, url, metadata)
            return when (type) {
                "bookstack" -> ContentCapabilities(
                    humanFriendly = true,
                    agentFriendly = true,
                    hasTimeSeries = false,
                    hasRichContent = true,
                    isInteractive = true,      // Can chat with OpenWebUI
                    isStructured = false
                )
                "article" -> ContentCapabilities(
                    humanFriendly = true,
                    agentFriendly = true,
                    hasTimeSeries = false,
                    hasRichContent = true,
                    isInteractive = true,      // Can chat with OpenWebUI
                    isStructured = false
                )
                "market" -> ContentCapabilities(
                    humanFriendly = true,
                    agentFriendly = true,
                    hasTimeSeries = true,      // Can graph in Grafana
                    hasRichContent = false,
                    isInteractive = false,
                    isStructured = true        // Has numeric fields
                )
                "weather" -> ContentCapabilities(
                    humanFriendly = true,
                    agentFriendly = true,
                    hasTimeSeries = true,      // Can graph trends
                    hasRichContent = false,
                    isInteractive = false,
                    isStructured = true        // Has temp/humidity/etc fields
                )
                "cve" -> ContentCapabilities(
                    humanFriendly = true,
                    agentFriendly = true,
                    hasTimeSeries = false,
                    hasRichContent = true,
                    isInteractive = true,      // Can chat about CVE
                    isStructured = true        // Has CVSS, affected systems
                )
                "wikipedia" -> ContentCapabilities(
                    humanFriendly = true,
                    agentFriendly = true,
                    hasTimeSeries = false,
                    hasRichContent = true,
                    isInteractive = true,      // Can chat with OpenWebUI
                    isStructured = false
                )
                "documentation" -> ContentCapabilities(
                    humanFriendly = true,
                    agentFriendly = true,
                    hasTimeSeries = false,
                    hasRichContent = true,
                    isInteractive = true,      // Can chat about docs
                    isStructured = false
                )
                else -> ContentCapabilities(
                    humanFriendly = true,
                    agentFriendly = true,
                    hasTimeSeries = false,
                    hasRichContent = false,
                    isInteractive = false,
                    isStructured = false
                )
            }
        }
    }
}
