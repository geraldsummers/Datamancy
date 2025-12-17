package org.datamancy.searchindexer

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.oshai.kotlinlogging.KotlinLogging
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections.*
import io.qdrant.client.grpc.Points.*
import io.qdrant.client.ValueFactory.value
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.VectorsFactory.vectors
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Indexes content from BookStack into Qdrant (vector) and ClickHouse (BM25).
 */
class SearchIndexer(
    private val bookstackUrl: String,
    private val qdrantUrl: String,
    private val clickhouseUrl: String,
    private val embeddingServiceUrl: String
) {
    private val bookstackToken = System.getenv("BOOKSTACK_API_TOKEN_ID") ?: ""
    private val bookstackSecret = System.getenv("BOOKSTACK_API_TOKEN_SECRET") ?: ""
    private val clickhouseUser = System.getenv("CLICKHOUSE_USER") ?: "default"
    private val clickhousePassword = System.getenv("CLICKHOUSE_PASSWORD") ?: ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Qdrant client
    private val qdrantHost = qdrantUrl.removePrefix("http://").removePrefix("https://").split(":")[0]
    private val qdrantPort = qdrantUrl.removePrefix("http://").removePrefix("https://").split(":").getOrNull(1)?.toIntOrNull() ?: 6334
    private val qdrant = QdrantClient(QdrantGrpcClient.newBuilder(qdrantHost, qdrantPort, false).build())

    private var indexingStatus = mutableMapOf<String, IndexStatus>()

    /**
     * Indexes all Australian legislation collections.
     */
    suspend fun indexAllCollections() {
        val collections = listOf("legislation_federal", "legislation_nsw", "legislation_vic",
                                 "legislation_qld", "legislation_wa", "legislation_sa",
                                 "legislation_tas", "legislation_act", "legislation_nt")

        collections.forEach { collection ->
            try {
                indexCollection(collection)
            } catch (e: Exception) {
                logger.error(e) { "Failed to index collection: $collection" }
            }
        }
    }

    /**
     * Indexes a specific collection (e.g., "legislation_federal").
     */
    suspend fun indexCollection(collectionName: String) {
        logger.info { "Indexing collection: $collectionName" }
        indexingStatus[collectionName] = IndexStatus("in_progress", 0, 0)

        try {
            // Ensure Qdrant collection exists
            ensureQdrantCollection(collectionName)

            // Ensure ClickHouse table exists
            ensureClickHouseTable(collectionName)

            // Get pages from BookStack based on collection tags
            val pages = getBookStackPages(collectionName)
            logger.info { "Found ${pages.size} pages for $collectionName" }

            var indexed = 0
            pages.forEach { page ->
                try {
                    indexPage(collectionName, page)
                    indexed++
                } catch (e: Exception) {
                    logger.error(e) { "Failed to index page: ${page.name}" }
                }
                delay(100) // Rate limiting
            }

            indexingStatus[collectionName] = IndexStatus("completed", indexed, pages.size)
            logger.info { "Completed indexing $collectionName: $indexed/${pages.size} pages" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to index collection: $collectionName" }
            indexingStatus[collectionName] = IndexStatus("failed", 0, 0, e.message)
        }
    }

    /**
     * Indexes a single page into both Qdrant and ClickHouse.
     */
    private suspend fun indexPage(collectionName: String, page: BookStackPageInfo) {
        // Get plain text export from BookStack
        val plainText = exportBookStackPage(page.id, "plain-text")

        if (plainText.isBlank()) {
            logger.warn { "Empty content for page: ${page.name}" }
            return
        }

        // Generate embedding
        val embedding = generateEmbedding(plainText)

        // Store in Qdrant
        storeInQdrant(collectionName, page, plainText, embedding)

        // Store in ClickHouse
        storeInClickHouse(collectionName, page, plainText)

        logger.debug { "Indexed page: ${page.name}" }
    }

    /**
     * Ensures Qdrant collection exists with proper configuration.
     */
    private fun ensureQdrantCollection(name: String) {
        try {
            // Check if collection exists
            val collections = qdrant.listCollectionsAsync().get()
            val exists = collections.contains(name)

            if (!exists) {
                logger.info { "Creating Qdrant collection: $name" }

                // Create collection with 384-dimensional vectors (for all-MiniLM-L6-v2)
                qdrant.createCollectionAsync(
                    name,
                    VectorParams.newBuilder()
                        .setSize(384)
                        .setDistance(Distance.Cosine)
                        .build()
                ).get()
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure Qdrant collection: $name" }
            throw e
        }
    }

    /**
     * Ensures ClickHouse table exists.
     */
    private fun ensureClickHouseTable(name: String) {
        val tableName = name.replace("-", "_")

        val createTableSql = """
            CREATE TABLE IF NOT EXISTS default.$tableName (
                page_id UInt32,
                page_name String,
                page_url String,
                content String,
                indexed_at DateTime DEFAULT now()
            ) ENGINE = MergeTree()
            ORDER BY page_id
        """.trimIndent()

        try {
            val jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"
            DriverManager.getConnection(jdbcUrl, clickhouseUser, clickhousePassword).use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute(createTableSql)
                }
            }
            logger.info { "Ensured ClickHouse table: $tableName" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure ClickHouse table: $tableName" }
            throw e
        }
    }

    /**
     * Gets BookStack pages for a collection based on tags.
     */
    private fun getBookStackPages(collectionName: String): List<BookStackPageInfo> {
        // Extract jurisdiction from collection name (e.g., "legislation_federal" -> "federal")
        val jurisdiction = collectionName.removePrefix("legislation_")

        val request = Request.Builder()
            .url("$bookstackUrl/api/search?query=type:page jurisdiction:$jurisdiction&count=1000")
            .get()
            .header("Authorization", "Token $bookstackToken:$bookstackSecret")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to get BookStack pages: ${response.code}")
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val results = json.getAsJsonArray("data")
            val pages = mutableListOf<BookStackPageInfo>()

            results?.forEach { result ->
                val obj = result.asJsonObject
                if (obj.get("type")?.asString == "page") {
                    pages.add(BookStackPageInfo(
                        id = obj.get("id").asInt,
                        name = obj.get("name").asString,
                        url = obj.get("url")?.asString ?: ""
                    ))
                }
            }

            return pages
        }
    }

    /**
     * Exports a BookStack page in specified format.
     */
    private fun exportBookStackPage(pageId: Int, format: String): String {
        val request = Request.Builder()
            .url("$bookstackUrl/api/pages/$pageId/export/$format")
            .get()
            .header("Authorization", "Token $bookstackToken:$bookstackSecret")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to export page: ${response.code}")
            }
            return response.body?.string() ?: ""
        }
    }

    /**
     * Generates embedding using embedding service.
     * Assumes service accepts JSON: {"text": "..."} and returns {"embedding": [...]}.
     */
    private fun generateEmbedding(text: String): List<Float> {
        val payload = gson.toJson(mapOf("text" to text))

        val request = Request.Builder()
            .url("$embeddingServiceUrl/embed")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to generate embedding: ${response.code}")
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val embeddingArray = json.getAsJsonArray("embedding")
            return embeddingArray.map { it.asFloat }
        }
    }

    /**
     * Stores document in Qdrant.
     */
    private fun storeInQdrant(collectionName: String, page: BookStackPageInfo, content: String, embedding: List<Float>) {
        val point = PointStruct.newBuilder()
            .setId(id(page.id.toLong()))
            .setVectors(vectors(embedding))
            .putAllPayload(mapOf(
                "page_id" to value(page.id.toLong()),
                "page_name" to value(page.name),
                "page_url" to value(page.url),
                "content_snippet" to value(content.take(500))
            ))
            .build()

        qdrant.upsertAsync(collectionName, listOf(point)).get()
    }

    /**
     * Stores document in ClickHouse for BM25 search.
     */
    private fun storeInClickHouse(collectionName: String, page: BookStackPageInfo, content: String) {
        val tableName = collectionName.replace("-", "_")

        // Escape single quotes in content
        val escapedContent = content.replace("'", "''")
        val escapedName = page.name.replace("'", "''")
        val escapedUrl = page.url.replace("'", "''")

        val insertSql = """
            INSERT INTO default.$tableName (page_id, page_name, page_url, content)
            VALUES (${page.id}, '$escapedName', '$escapedUrl', '$escapedContent')
        """.trimIndent()

        val jdbcUrl = "jdbc:clickhouse://${clickhouseUrl.removePrefix("http://").removePrefix("https://")}/default"
        DriverManager.getConnection(jdbcUrl, clickhouseUser, clickhousePassword).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(insertSql)
            }
        }
    }

    fun getStatus(): Map<String, IndexStatus> = indexingStatus.toMap()
}

data class BookStackPageInfo(
    val id: Int,
    val name: String,
    val url: String
)

data class IndexStatus(
    val status: String,
    val indexed: Int,
    val total: Int,
    val error: String? = null
)
