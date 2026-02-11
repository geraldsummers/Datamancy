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
 * Core hybrid search gateway that combines vector and full-text search for RAG capabilities.
 *
 * This class implements a sophisticated hybrid search strategy that provides the best of both worlds:
 *
 * ## Search Modes
 * 1. **Vector Search** (semantic understanding)
 *    - Uses Qdrant vector database with cosine similarity
 *    - Queries are vectorized via BGE-M3 embedding model (1024 dimensions)
 *    - Excellent for conceptual similarity and semantic understanding
 *    - Example: "kubernetes scaling" matches "horizontal pod autoscaling" even without keyword overlap
 *
 * 2. **Full-Text Search** (keyword precision)
 *    - Uses PostgreSQL ts_rank with tsvector/tsquery
 *    - Searches the document_staging table with embedding_status=COMPLETED filter
 *    - Excellent for exact keyword matches and domain-specific terminology
 *    - Example: "CVE-2024-1234" requires exact match, not semantic similarity
 *
 * 3. **Hybrid Search** (Reciprocal Rank Fusion)
 *    - Runs both searches in parallel for performance
 *    - Merges results using RRF algorithm (see [rerank] method for details)
 *    - Provides superior results by combining semantic understanding with keyword precision
 *
 * ## Integration with Datamancy Stack
 * - **Data Source**: Pipeline module writes to document_staging table and Qdrant collections
 * - **Query Vectorization**: Embedding Service (BGE-M3 model on HTTP:8000)
 * - **Vector Search**: Qdrant (gRPC:6334) with collections: rss_feeds, cve, wikipedia, etc.
 * - **Full-Text Search**: PostgreSQL (JDBC:5432) using ts_rank on tsvector
 * - **RAG Consumer**: Agent-tool-server's semantic_search tool uses this for LLM context retrieval
 *
 * ## Why Hybrid Search?
 * - Vector search alone misses exact technical terms (e.g., error codes, version numbers)
 * - Full-text search alone misses semantic variations (e.g., "container orchestration" vs "k8s")
 * - Hybrid search combines strengths of both approaches for maximum relevance
 *
 * @property qdrantUrl Qdrant gRPC endpoint (default: qdrant:6334)
 * @property postgresJdbcUrl PostgreSQL JDBC URL (default: jdbc:postgresql://postgres:5432/datamancy)
 * @property embeddingServiceUrl Embedding Service HTTP endpoint (default: http://embedding-service:8000)
 */
class SearchGateway(
    private val qdrantUrl: String,
    private val qdrantApiKey: String = "",
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

    // Qdrant gRPC client for vector similarity search
    // Uses cosine distance to find semantically similar documents
    // Parse the Qdrant URL - supports formats like "qdrant:6334", "grpc://qdrant:6334", or just "qdrant"
    private val qdrantCleanUrl = qdrantUrl
        .removePrefix("http://")
        .removePrefix("https://")
        .removePrefix("grpc://")
        .trimStart('/') // Remove leading slashes that might cause Unix socket interpretation
    private val qdrantHost = qdrantCleanUrl.split(":")[0]
    private val qdrantPort = qdrantCleanUrl.split(":").getOrNull(1)?.toIntOrNull() ?: 6334
    private val qdrant = QdrantClient(
        QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false)
            .apply {
                if (qdrantApiKey.isNotBlank()) {
                    withApiKey(qdrantApiKey)
                }
            }
            .build()
    )

    // PostgreSQL connection pool for full-text search
    // Read-only pool to prevent accidental data modification
    // Searches document_staging table with ts_rank for keyword relevance
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

    /**
     * Main search entry point - supports vector, full-text, and hybrid search modes.
     *
     * This method orchestrates the entire search process:
     * 1. Resolves wildcard collections (["*"]) to all available Qdrant collections
     * 2. Delegates to appropriate search strategy based on mode
     * 3. Returns ranked results ready for audience filtering
     *
     * ## Search Mode Details
     * - **vector**: Semantic search only via Qdrant (best for conceptual queries)
     * - **bm25/fulltext**: Keyword search only via PostgreSQL (best for exact terms)
     * - **hybrid**: Both in parallel, merged with RRF (best overall results)
     *
     * In hybrid mode, each backend receives limit/2 requests to balance representation
     * before RRF fusion produces the final ranked list.
     *
     * @param query Natural language query to search for
     * @param collections List of collection names to search (use ["*"] for all)
     * @param mode Search strategy: "vector", "bm25", or "hybrid"
     * @param limit Maximum number of results to return
     * @return Ranked list of SearchResult objects with relevance scores
     */
    suspend fun search(
        query: String,
        collections: List<String>,
        mode: String = "hybrid",
        limit: Int = 20
    ): List<SearchResult> = coroutineScope {
        // Resolve wildcard collections to actual Qdrant collection names
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
                // Execute vector and full-text searches in parallel for performance
                // Each backend gets limit/2 to ensure balanced representation before RRF fusion
                val vectorDeferred = async { searchVector(query, targetCollections, limit / 2) }
                val fulltextDeferred = async { searchFullText(query, targetCollections, limit / 2) }

                val vectorResults = vectorDeferred.await()
                val fulltextResults = fulltextDeferred.await()

                // Merge results with Reciprocal Rank Fusion (RRF) algorithm
                // This is the key innovation that provides superior hybrid search results
                rerank(vectorResults, fulltextResults, limit)
            }
            else -> throw IllegalArgumentException("Unknown search mode: $mode (use vector, bm25, or hybrid)")
        }
    }

    /**
     * Performs semantic vector search across multiple Qdrant collections.
     *
     * This method implements the vector search component of hybrid search:
     * 1. Vectorizes the query text via Embedding Service (BGE-M3 model, 1024 dimensions)
     * 2. Searches each collection in parallel using Qdrant's cosine similarity
     * 3. Aggregates and ranks results by similarity score
     *
     * Vector search excels at semantic understanding - it matches concepts rather than keywords.
     * Example: "kubernetes scaling" will match "horizontal pod autoscaling" even without
     * shared keywords, because their embeddings are similar in vector space.
     *
     * @param query Natural language query to vectorize and search
     * @param collections List of Qdrant collection names to search
     * @param limit Maximum results per collection (de-duplicated and ranked afterward)
     * @return List of SearchResult with cosine similarity scores
     */
    private suspend fun searchVector(query: String, collections: List<String>, limit: Int): List<SearchResult> = coroutineScope {
        // Vectorize query via Embedding Service (BGE-M3 model)
        // This converts natural language to a 1024-dimensional vector for semantic comparison
        val embedding = getEmbedding(query)

        // Search all collections in parallel for performance
        // Each collection is independent, so parallel execution reduces latency
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

    /**
     * Legacy alias for full-text search (BM25-style ranking via PostgreSQL ts_rank).
     */
    private suspend fun searchBM25(query: String, collections: List<String>, limit: Int): List<SearchResult> {
        return searchFullText(query, collections, limit)
    }

    /**
     * Performs full-text keyword search across PostgreSQL document_staging table.
     *
     * This method implements the keyword search component of hybrid search:
     * 1. Searches document_staging table using PostgreSQL's full-text search capabilities
     * 2. Uses ts_rank with tsvector/tsquery for BM25-style relevance ranking
     * 3. Filters to only COMPLETED documents (embedding_status=COMPLETED)
     * 4. Aggregates results across collections and ranks by ts_rank score
     *
     * Full-text search excels at exact keyword matches and technical terminology.
     * Example: "CVE-2024-1234" requires exact string matching, not semantic similarity.
     *
     * ## Why Filter by embedding_status=COMPLETED?
     * This ensures only fully indexed documents appear in search results. Pipeline writes
     * documents with PENDING status, which are processed asynchronously. Only COMPLETED
     * documents have been embedded and are ready for search.
     *
     * @param query Keyword query string (converted to PostgreSQL tsquery internally)
     * @param collections List of collection names to filter by in document_staging table
     * @param limit Maximum results per collection (de-duplicated and ranked afterward)
     * @return List of SearchResult with ts_rank scores
     */
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

    /**
     * Executes vector similarity search on a single Qdrant collection.
     *
     * Uses cosine similarity to find documents whose embeddings are closest to the query embedding
     * in vector space. This captures semantic similarity even when keywords don't overlap.
     *
     * @param collection Qdrant collection name to search
     * @param embedding Query vector (1024-dimensional from BGE-M3)
     * @param limit Maximum number of results to return
     * @return List of SearchResult with cosine similarity scores (higher = more similar)
     */
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

    /**
     * Executes full-text keyword search on PostgreSQL document_staging table.
     *
     * Uses PostgreSQL's full-text search with ts_rank for BM25-style relevance scoring.
     * Only searches documents with embedding_status=COMPLETED to ensure they're fully indexed.
     *
     * ## Why embedding_status=COMPLETED Filter?
     * Pipeline writes documents with PENDING status, which are asynchronously embedded.
     * This filter ensures search results only include fully processed documents that
     * also exist in Qdrant (for hybrid search consistency).
     *
     * @param collection Collection name to filter in document_staging table
     * @param query Keyword query (converted to PostgreSQL tsquery)
     * @param limit Maximum number of results to return
     * @return List of SearchResult with ts_rank scores (higher = better keyword match)
     */
    private suspend fun searchPostgres(collection: String, query: String, limit: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceIn(1, 1000)

        // PostgreSQL full-text search with ts_rank for BM25-style relevance
        // Uses 'english' dictionary for stemming (e.g., "running" matches "run")
        // embedding_status=COMPLETED ensures only fully indexed documents appear
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

                    // Parse metadata JSON stored in document_staging.metadata column
                    // This contains URL, title, and other document metadata from Pipeline
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

    /**
     * Merges vector and full-text search results using Reciprocal Rank Fusion (RRF) algorithm.
     *
     * ## What is Reciprocal Rank Fusion?
     * RRF is a sophisticated rank aggregation method that combines multiple ranked lists without
     * needing to normalize or compare raw scores between different search systems. It's particularly
     * powerful for hybrid search because:
     * 1. Vector search scores (cosine similarity 0-1) and full-text scores (ts_rank) are incompatible
     * 2. RRF uses rank positions instead of raw scores, making it score-agnostic
     * 3. Documents appearing in both result sets get boosted naturally
     *
     * ## The RRF Formula
     * For each document appearing in either result set:
     * ```
     * RRF_score = Σ (1 / (k + rank))
     * ```
     * Where:
     * - `k` is a constant that controls the impact of high-ranked documents (we use k=60)
     * - `rank` is the 0-based position in the result list (0 = top result)
     * - The sum is computed across all result sets containing that document
     *
     * ## Why k=60?
     * The value k=60 is empirically proven optimal for hybrid search (Cormack et al., SIGIR 2009).
     * - Lower k (e.g., 10): Heavily favors top-ranked results, ignoring lower ranks
     * - Higher k (e.g., 100): Treats all ranks more equally, diluting top result importance
     * - k=60: Balances top result importance while still considering lower-ranked matches
     *
     * ## Example Calculation
     * Suppose a document appears at:
     * - Rank 0 in vector results (top result)
     * - Rank 2 in full-text results (3rd result)
     *
     * RRF score = 1/(60+0) + 1/(60+2) = 1/60 + 1/62 = 0.0167 + 0.0161 = 0.0328
     *
     * A document appearing ONLY at rank 0 in one list would score:
     * RRF score = 1/(60+0) = 0.0167
     *
     * The hybrid document scores nearly 2x higher, demonstrating how RRF naturally boosts
     * documents that appear in both result sets (consensus).
     *
     * ## Why RRF vs Simple Score Averaging?
     * - Vector search scores are cosine similarity (0-1 range, higher = better)
     * - Full-text scores are ts_rank (unbounded, highly variable per query)
     * - Normalizing these scores is complex and query-dependent
     * - RRF completely sidesteps score normalization by using ranks instead
     *
     * ## Benefits for RAG (Retrieval-Augmented Generation)
     * - LLMs receive the most relevant documents based on BOTH semantic meaning and keyword precision
     * - Reduces false positives from vector search alone (e.g., "python" matching "snake")
     * - Reduces false negatives from keyword search alone (e.g., missing "k8s" when searching "kubernetes")
     * - Provides more reliable context for LLM knowledge augmentation
     *
     * @param vectorResults Results from Qdrant vector search (cosine similarity ranked)
     * @param fulltextResults Results from PostgreSQL full-text search (ts_rank ranked)
     * @param limit Maximum number of results to return after fusion
     * @return Merged and re-ranked results with RRF scores
     */
    private fun rerank(vectorResults: List<SearchResult>, fulltextResults: List<SearchResult>, limit: Int): List<SearchResult> {
        val k = 60 // RRF constant - empirically optimal for hybrid search (Cormack et al., SIGIR 2009)
        val scores = mutableMapOf<String, Double>()
        val resultMap = mutableMapOf<String, SearchResult>()

        // Process vector search results
        // Each document contributes 1/(k + rank) to its RRF score based on its vector rank
        vectorResults.forEachIndexed { index, result ->
            val key = result.url.ifBlank { "${result.source}::${result.title}" }
            scores[key] = scores.getOrDefault(key, 0.0) + (1.0 / (k + index + 1))
            resultMap[key] = result
        }

        // Process full-text search results
        // Documents in both lists accumulate scores from both ranks (natural boosting)
        fulltextResults.forEachIndexed { index, result ->
            val key = result.url.ifBlank { "${result.source}::${result.title}" }
            scores[key] = scores.getOrDefault(key, 0.0) + (1.0 / (k + index + 1))
            if (!resultMap.containsKey(key)) {
                resultMap[key] = result
            }
        }

        // Return final ranked list by RRF score (highest first)
        // Documents appearing in both result sets will naturally rank higher due to score accumulation
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

    /**
     * Converts query text to a 1024-dimensional vector via the Embedding Service.
     *
     * This method integrates with the Embedding Service (BGE-M3 model) to vectorize queries
     * for semantic search. The same model is used by the Pipeline to embed documents, ensuring
     * query and document embeddings are in the same vector space.
     *
     * ## BGE-M3 Model Details
     * - Model: BAAI/bge-m3 (multilingual, 1024 dimensions)
     * - Max tokens: 8192
     * - Output: Dense vector representation capturing semantic meaning
     * - Used consistently across Pipeline (document embedding) and Search (query embedding)
     *
     * ## Why Consistent Embeddings Matter
     * Queries and documents MUST use the same embedding model for accurate similarity comparison.
     * Using different models would place them in incompatible vector spaces, breaking semantic search.
     *
     * @param text Query text to vectorize
     * @return 1024-dimensional float vector representing semantic meaning
     * @throws Exception if Embedding Service is unavailable or returns invalid response
     */
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

            // Parse embedding response - API returns Array<FloatArray> format
            // We take the first (and only) embedding from the response
            val json = gson.fromJson(response.body?.string(), Array<FloatArray>::class.java)
            if (json.isEmpty()) {
                throw Exception("Empty embedding response")
            }
            json[0].toList()
        }
    }

    /**
     * Gracefully shuts down all external service connections.
     *
     * Closes connections to:
     * - PostgreSQL connection pool (HikariCP)
     * - HTTP client for Embedding Service (OkHttp)
     * - Qdrant gRPC client
     *
     * Called automatically via shutdown hook registered in Main.kt.
     */
    fun close() {
        try {
            // Close PostgreSQL connection pool
            postgresPool.close()
        } catch (e: Exception) {
            logger.debug(e) { "PostgreSQL pool close failed" }
        }

        try {
            // Close HTTP client used for Embedding Service requests
            httpClient.dispatcher.executorService.shutdown()
            httpClient.connectionPool.evictAll()
        } catch (e: Exception) {
            logger.debug(e) { "HTTP client close failed" }
        }

        try {
            // Close Qdrant gRPC client
            qdrant.close()
        } catch (e: Exception) {
            logger.debug(e) { "Qdrant client close failed" }
        }

        logger.info { "SearchGateway closed successfully" }
    }
}
/**
 * Search result model with content type inference and capability metadata.
 *
 * Each result represents a document from either Qdrant (vector search) or PostgreSQL (full-text),
 * enriched with inferred capabilities for audience filtering.
 *
 * @property url Document URL (primary deduplication key in RRF)
 * @property title Document title extracted from metadata
 * @property snippet Content preview (first 200 characters)
 * @property score Relevance score (cosine similarity for vector, ts_rank for full-text, RRF score for hybrid)
 * @property source Collection name (e.g., "rss_feeds", "cve", "wikipedia")
 * @property metadata Additional document metadata from Qdrant payload or PostgreSQL JSON column
 * @property contentType Inferred content type for filtering (article, documentation, vulnerability, code, etc.)
 * @property capabilities Inferred boolean capabilities (humanFriendly, agentFriendly, isStructured, etc.)
 */
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
        /**
         * Infers content type from collection name, URL, and metadata.
         *
         * This heuristic classification enables audience filtering - LLMs can request
         * only "code" and "vulnerability" types, while humans prefer "article" and "documentation".
         *
         * @return Content type: article, documentation, vulnerability, legal, code, or document
         */
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

        /**
         * Infers boolean capabilities from content type for audience filtering.
         *
         * These capabilities enable fine-grained filtering in the REST API:
         * - audience=human → filter to humanFriendly=true (articles, docs, legal)
         * - audience=agent → filter to agentFriendly=true (code, APIs, structured data)
         *
         * This is crucial for RAG applications where LLMs need different content than humans:
         * - LLMs benefit from structured data (code, CVEs) that they can parse and reason about
         * - Humans benefit from narrative content (articles, documentation) that's readable
         *
         * @return Map of capability flags indicating content suitability for different audiences
         */
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
