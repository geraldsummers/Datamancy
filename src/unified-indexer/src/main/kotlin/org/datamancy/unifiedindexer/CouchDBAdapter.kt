package org.datamancy.unifiedindexer

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * CouchDB adapter for unified indexer.
 * Reads documents from CouchDB databases for indexing into Qdrant.
 *
 * CouchDB document structure expected:
 * {
 *   "_id": "doc_id",
 *   "_rev": "revision",
 *   "title": "Document Title",
 *   "content": "Full text content...",
 *   "url": "https://example.com/doc",
 *   "type": "research_paper|strategy|commentary",
 *   "created_at": "2024-01-15T10:30:00Z",
 *   "metadata": { ... }
 * }
 */
class CouchDBAdapter(
    private val couchUrl: String,
    private val username: String,
    private val password: String
) : SourceAdapter {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    /**
     * Get all documents from a CouchDB database.
     * Collection name maps to database name.
     */
    override suspend fun getPages(collection: String): List<PageInfo> {
        val dbUrl = "$couchUrl/$collection/_all_docs?include_docs=true"

        val request = Request.Builder()
            .url(dbUrl)
            .header("Authorization", okhttp3.Credentials.basic(username, password))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "Failed to fetch documents from CouchDB $collection: ${response.code}" }
                    return emptyList()
                }

                val body = response.body?.string() ?: return emptyList()
                val json = gson.fromJson(body, JsonObject::class.java)
                val rows = json.getAsJsonArray("rows") ?: return emptyList()

                rows.mapNotNull { rowElement ->
                    try {
                        val row = rowElement.asJsonObject
                        val doc = row.getAsJsonObject("doc") ?: return@mapNotNull null

                        // Skip design documents
                        val id = doc.get("_id")?.asString ?: return@mapNotNull null
                        if (id.startsWith("_design/")) return@mapNotNull null

                        // Extract document metadata
                        val title = doc.get("title")?.asString ?: id
                        val url = doc.get("url")?.asString ?: "$couchUrl/$collection/$id"

                        // Use hash of _id as numeric ID (for compatibility with PageInfo)
                        val numericId = id.hashCode()

                        PageInfo(
                            id = numericId,
                            name = title,
                            url = url
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to parse CouchDB document" }
                        null
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to query CouchDB collection: $collection" }
            emptyList()
        }
    }

    /**
     * Export a single document's content.
     * pageId is actually the hash of the document _id.
     */
    override suspend fun exportPage(pageId: Int): String {
        // This is a limitation - we'd need to track _id separately
        // For now, we'll fetch all docs and find by hash
        // In production, consider maintaining an ID mapping table
        throw UnsupportedOperationException(
            "CouchDB adapter requires fetching full document list. " +
            "Use getPages() to retrieve documents with their content."
        )
    }

    /**
     * Extended method to get documents with content directly.
     * Returns list of (PageInfo, content) pairs.
     */
    suspend fun getPagesWithContent(collection: String): List<Pair<PageInfo, String>> {
        val dbUrl = "$couchUrl/$collection/_all_docs?include_docs=true"

        val request = Request.Builder()
            .url(dbUrl)
            .header("Authorization", okhttp3.Credentials.basic(username, password))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "Failed to fetch documents from CouchDB $collection: ${response.code}" }
                    return emptyList()
                }

                val body = response.body?.string() ?: return emptyList()
                val json = gson.fromJson(body, JsonObject::class.java)
                val rows = json.getAsJsonArray("rows") ?: return emptyList()

                rows.mapNotNull { rowElement ->
                    try {
                        val row = rowElement.asJsonObject
                        val doc = row.getAsJsonObject("doc") ?: return@mapNotNull null

                        // Skip design documents
                        val id = doc.get("_id")?.asString ?: return@mapNotNull null
                        if (id.startsWith("_design/")) return@mapNotNull null

                        // Extract document metadata
                        val title = doc.get("title")?.asString ?: id
                        val content = doc.get("content")?.asString ?: ""
                        val url = doc.get("url")?.asString ?: "$couchUrl/$collection/$id"

                        if (content.isEmpty()) {
                            logger.debug { "Skipping document $id - no content" }
                            return@mapNotNull null
                        }

                        val numericId = id.hashCode()

                        val pageInfo = PageInfo(
                            id = numericId,
                            name = title,
                            url = url
                        )

                        Pair(pageInfo, content)
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to parse CouchDB document" }
                        null
                    }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to query CouchDB collection: $collection" }
            emptyList()
        }
    }
}
