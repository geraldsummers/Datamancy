package org.datamancy.searchservice

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.oshai.kotlinlogging.KotlinLogging
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Points.*
import io.qdrant.client.grpc.Collections.*
import io.qdrant.client.WithPayloadSelectorFactory
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
                    logger.warn(e) { "Failed to search Qdrant collection: $collection" }
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
     */
    private fun searchQdrant(collection: String, embedding: List<Float>, limit: Int): List<SearchResult> {
        val points = qdrant.searchAsync(
            SearchPoints.newBuilder()
                .setCollectionName(collection)
                .addAllVector(embedding)
                .setLimit(limit.toLong())
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .build()
        ).get()

        return points.map { point ->
            val payload = point.payloadMap
            // Pipeline stores: title, link, description (or url, text, etc.)
            val url = payload["link"]?.stringValue ?: payload["url"]?.stringValue ?: ""
            val title = payload["title"]?.stringValue ?: payload["name"]?.stringValue ?: ""
            val snippet = payload["description"]?.stringValue ?: payload["text"]?.stringValue?.take(200) ?: ""
            val metadata = mapOf("type" to "vector")
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
     * Searches ClickHouse table using full-text search.
     */
    private fun searchClickHouse(collection: String, query: String, limit: Int): List<SearchResult> {
        val tableName = collection.replace("-", "_")
        val escapedQuery = query.replace("'", "''")
        val jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"

        // Check if table exists first
        val tableExists = DriverManager.getConnection(jdbcUrl, clickhouseUser, clickhousePassword).use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("EXISTS TABLE default.$tableName")
                rs.next() && rs.getInt(1) == 1
            }
        }

        if (!tableExists) {
            logger.debug { "ClickHouse table default.$tableName does not exist, skipping BM25 search" }
            return emptyList()
        }

        // Simple full-text search using LIKE (could be improved with ClickHouse's full-text functions)
        val sql = """
            SELECT
                page_id,
                page_name,
                page_url,
                substring(content, 1, 200) as snippet,
                length(content) as doc_length,
                (length(content) - length(replaceAll(lower(content), lower('$escapedQuery'), ''))) / length('$escapedQuery') as term_freq
            FROM default.$tableName
            WHERE positionCaseInsensitive(content, '$escapedQuery') > 0
            ORDER BY term_freq DESC
            LIMIT $limit
        """.trimIndent()

        val results = mutableListOf<SearchResult>()

        DriverManager.getConnection(jdbcUrl, clickhouseUser, clickhousePassword).use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(sql)
                while (rs.next()) {
                    val url = rs.getString("page_url")
                    val title = rs.getString("page_name")
                    val snippet = rs.getString("snippet")
                    val metadata = mapOf("type" to "bm25")
                    results.add(SearchResult(
                        url = url,
                        title = title,
                        snippet = snippet,
                        score = rs.getDouble("term_freq"),
                        source = collection,
                        metadata = metadata,
                        contentType = SearchResult.inferContentType(collection, url, metadata),
                        capabilities = SearchResult.inferCapabilities(collection, url, metadata)
                    ))
                }
            }
        }

        return results
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
     */
    private fun generateEmbedding(text: String): List<Float> {
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
            return embeddingArray.map { it.asFloat }
        }
    }

    /**
     * Lists available Qdrant collections.
     */
    fun listCollections(): List<String> {
        return try {
            qdrant.listCollectionsAsync().get()
        } catch (e: Exception) {
            logger.error(e) { "Failed to list collections" }
            emptyList()
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
                    humanFriendly = false,
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
