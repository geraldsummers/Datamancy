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


class SearchGateway(
    private val qdrantUrl: String,
    private val postgresJdbcUrl: String,
    private val embeddingServiceUrl: String
) {
    private val postgresUser = System.getenv("POSTGRES_USER") ?: "datamancer"
    private val postgresPassword = System.getenv("POSTGRES_PASSWORD") ?: ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    
    private val qdrantHost = qdrantUrl.removePrefix("http://").removePrefix("https://").split(":")[0]
    private val qdrantPort = qdrantUrl.removePrefix("http://").removePrefix("https://").split(":").getOrNull(1)?.toIntOrNull() ?: 6334
    private val qdrant = QdrantClient(QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false).build())

    
    private val postgresPool: HikariDataSource by lazy {
        HikariConfig().apply {
            jdbcUrl = postgresJdbcUrl
            username = postgresUser
            password = postgresPassword
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = 10
            minimumIdle = 2
            connectionTimeout = 5000
            idleTimeout = 300000
            maxLifetime = 600000
            poolName = "search-postgres-pool"
            isReadOnly = true
        }.let { HikariDataSource(it) }
    }

    
    suspend fun search(
        query: String,
        collections: List<String>,
        mode: String = "hybrid",
        limit: Int = 20
    ): List<SearchResult> = coroutineScope {
        
        val targetCollections = if (collections.contains("*")) {
            listCollections()
        } else {
            collections
        }

        logger.info { "Searching in ${targetCollections.size} collections: mode=$mode, query=$query" }

        when (mode) {
            "vector" -> searchVector(query, targetCollections, limit)
            "bm25", "fulltext" -> searchFullText(query, targetCollections, limit)
            "hybrid" -> {
                
                val vectorDeferred = async { searchVector(query, targetCollections, limit / 2) }
                val fulltextDeferred = async { searchFullText(query, targetCollections, limit / 2) }

                val vectorResults = vectorDeferred.await()
                val fulltextResults = fulltextDeferred.await()

                
                rerank(vectorResults, fulltextResults, limit)
            }
            else -> throw IllegalArgumentException("Unknown search mode: $mode (use vector, bm25, or hybrid)")
        }
    }

    
    private suspend fun searchVector(query: String, collections: List<String>, limit: Int): List<SearchResult> = coroutineScope {
        
        val embedding = getEmbedding(query)

        
        val results = collections.map { collection ->
            async {
                try {
                    searchQdrant(collection, embedding, limit)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to search Qdrant collection: $collection" }
                    emptyList<SearchResult>()
                }
            }
        }.awaitAll().flatten()

        results.sortedByDescending { it.score }.take(limit)
    }

    
    private suspend fun searchBM25(query: String, collections: List<String>, limit: Int): List<SearchResult> {
        return searchFullText(query, collections, limit)
    }

    
    private suspend fun searchFullText(query: String, collections: List<String>, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        val results = collections.flatMap { collection ->
            try {
                searchPostgres(collection, query, limit)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to search PostgreSQL for collection: $collection" }
                emptyList()
            }
        }

        results.sortedByDescending { it.score }.take(limit)
    }

    
    private suspend fun searchQdrant(collection: String, embedding: List<Float>, limit: Int): List<SearchResult> {
        val searchRequest = SearchPoints.newBuilder()
            .setCollectionName(collection)
            .addAllVector(embedding)
            .setLimit(limit.toLong())
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
            .build()

        val response = qdrant.searchAsync(searchRequest).get()

        return response.map { point ->
            @Suppress("DEPRECATION")
            val payload = point.payload
            val url = payload["url"]?.stringValue ?: ""
            val title = payload["title"]?.stringValue ?: ""
            val content = payload["content"]?.stringValue ?: payload["text"]?.stringValue ?: ""
            val snippet = content.take(200)

            val metadata = payload.mapValues { (_, value) -> value.stringValue }
            SearchResult(
                url = url,
                title = title,
                snippet = snippet,
                score = point.score.toDouble(),
                source = collection,
                metadata = metadata + mapOf("type" to "vector"),
                contentType = SearchResult.inferContentType(collection, url, metadata),
                capabilities = SearchResult.inferCapabilities(collection, url, metadata)
            )
        }
    }

    
    private suspend fun searchPostgres(collection: String, query: String, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, 1000)

        
        val sql = """
            SELECT
                id,
                text,
                metadata,
                ts_rank(to_tsvector('english', text), plainto_tsquery('english', ?)) as rank
            FROM public.document_staging
            WHERE
                collection = ?
                AND to_tsvector('english', text) @@ plainto_tsquery('english', ?)
                AND embedding_status = 'COMPLETED'
            ORDER BY rank DESC
            LIMIT ?
        """.trimIndent()

        val results = mutableListOf<SearchResult>()

        postgresPool.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, query)
                stmt.setString(2, collection)
                stmt.setString(3, query)
                stmt.setInt(4, safeLimit)

                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val id = rs.getString("id") ?: ""
                    val text = rs.getString("text") ?: ""
                    val metadataJson = rs.getString("metadata") ?: "{}"
                    val rank = rs.getDouble("rank")

                    
                    @Suppress("UNCHECKED_CAST")
                    val metadata = try {
                        gson.fromJson(metadataJson, Map::class.java) as? Map<String, String> ?: emptyMap()
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to parse metadata JSON" }
                        emptyMap()
                    }

                    val url = metadata["url"] ?: ""
                    val title = metadata["title"] ?: id
                    val snippet = text.take(200)

                    results.add(SearchResult(
                        url = url,
                        title = title,
                        snippet = snippet,
                        score = rank,
                        source = collection,
                        metadata = metadata + mapOf("type" to "fulltext"),
                        contentType = SearchResult.inferContentType(collection, url, metadata),
                        capabilities = SearchResult.inferCapabilities(collection, url, metadata)
                    ))
                }
            }
        }

        results
    }

    
    private fun rerank(vectorResults: List<SearchResult>, fulltextResults: List<SearchResult>, limit: Int): List<SearchResult> {
        val k = 60 
        val scores = mutableMapOf<String, Double>()
        val resultMap = mutableMapOf<String, SearchResult>()

        
        vectorResults.forEachIndexed { index, result ->
            val key = result.url.ifBlank { "${result.source}::${result.title}" }
            scores[key] = scores.getOrDefault(key, 0.0) + (1.0 / (k + index + 1))
            resultMap[key] = result
        }

        
        fulltextResults.forEachIndexed { index, result ->
            val key = result.url.ifBlank { "${result.source}::${result.title}" }
            scores[key] = scores.getOrDefault(key, 0.0) + (1.0 / (k + index + 1))
            if (!resultMap.containsKey(key)) {
                resultMap[key] = result
            }
        }

        
        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { resultMap[it.key]?.copy(score = it.value) }
    }

    
    private suspend fun listCollections(): List<String> {
        return try {
            
            qdrant.listCollectionsAsync().get()
        } catch (e: Exception) {
            logger.error(e) { "Failed to list Qdrant collections" }
            emptyList()
        }
    }

    
    suspend fun getCollections(): List<String> = listCollections()

    
    private suspend fun getEmbedding(text: String): List<Float> = withContext(Dispatchers.IO) {
        val requestBody = gson.toJson(mapOf("inputs" to text))
        val request = Request.Builder()
            .url("$embeddingServiceUrl/embed")
            .post(requestBody.toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Embedding service error: ${response.code}")
            }

            
            val json = gson.fromJson(response.body?.string(), Array<FloatArray>::class.java)
            if (json.isEmpty()) {
                throw Exception("Empty embedding response")
            }
            json[0].toList()
        }
    }

    
    fun close() {
        try {
            
            postgresPool.close()
        } catch (e: Exception) {
            logger.debug(e) { "PostgreSQL pool close failed" }
        }

        try {
            
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (e: Exception) {
            logger.debug(e) { "HTTP client close failed" }
        }

        try {
            
            qdrant.close()
        } catch (e: Exception) {
            logger.debug(e) { "Qdrant client close failed" }
        }

        logger.info { "SearchGateway closed successfully" }
    }
}


@Serializable
data class SearchResult(
    val url: String,
    val title: String,
    val snippet: String,
    val score: Double,
    val source: String,
    val metadata: Map<String, String> = emptyMap(),
    val contentType: String = "unknown",
    val capabilities: Map<String, Boolean> = emptyMap()
) {
    companion object {
        fun inferContentType(collection: String, url: String, metadata: Map<String, String>): String {
            return when {
                collection.contains("rss") || metadata["type"] == "rss" -> "article"
                collection.contains("wiki") || url.contains("wiki") -> "documentation"
                collection.contains("cve") -> "vulnerability"
                collection.contains("legal") || collection.contains("law") -> "legal"
                collection.contains("code") || url.contains("github") -> "code"
                else -> "document"
            }
        }

        fun inferCapabilities(collection: String, url: String, metadata: Map<String, String>): Map<String, Boolean> {
            val contentType = inferContentType(collection, url, metadata)
            return mapOf(
                "humanFriendly" to (contentType in listOf("article", "documentation", "legal")),
                "agentFriendly" to (contentType in listOf("code", "documentation", "vulnerability")),
                "hasTimeSeries" to (collection.contains("market") || metadata.containsKey("timeseries")),
                "hasRichContent" to (url.isNotBlank()),
                "isInteractive" to (metadata.containsKey("interactive")),
                "isStructured" to (contentType in listOf("code", "legal", "vulnerability"))
            )
        }
    }
}
