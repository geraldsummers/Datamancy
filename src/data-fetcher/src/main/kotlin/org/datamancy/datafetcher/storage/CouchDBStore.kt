package org.datamancy.datafetcher.storage

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * CouchDB storage for documents (research papers, strategies, commentary).
 *
 * Document structure:
 * {
 *   "_id": "generated-id-or-provided",
 *   "title": "Document Title",
 *   "content": "Full text content...",
 *   "url": "https://example.com/source",
 *   "type": "research_paper|strategy|commentary|market_report",
 *   "source": "data-fetcher-source-name",
 *   "created_at": "2024-01-15T10:30:00Z",
 *   "metadata": {
 *     "author": "...",
 *     "tags": [...],
 *     "symbol": "BTC",
 *     ...
 *   }
 * }
 */
class CouchDBStore {
    private val couchUrl = System.getenv("COUCHDB_URL") ?: "http://couchdb:5984"
    private val couchUser = System.getenv("COUCHDB_USER") ?: "admin"
    private val couchPassword = System.getenv("COUCHDB_PASSWORD") ?: ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Store a document in CouchDB.
     * Creates the database if it doesn't exist.
     */
    fun storeDocument(
        database: String,
        docId: String,
        title: String,
        content: String,
        url: String,
        type: String,
        source: String,
        metadata: Map<String, Any> = emptyMap()
    ): Boolean {
        // Ensure database exists
        ensureDatabase(database)

        // Check if document already exists (get _rev for update)
        val existingRev = getDocumentRev(database, docId)

        val doc = mutableMapOf(
            "_id" to docId,
            "title" to title,
            "content" to content,
            "url" to url,
            "type" to type,
            "source" to source,
            "created_at" to java.time.Instant.now().toString(),
            "metadata" to metadata
        )

        // If document exists, include _rev for update
        if (existingRev != null) {
            doc["_rev"] = existingRev
        }

        val payload = gson.toJson(doc)
        val request = Request.Builder()
            .url("$couchUrl/$database/$docId")
            .header("Authorization", okhttp3.Credentials.basic(couchUser, couchPassword))
            .put(payload.toRequestBody(jsonMediaType))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    logger.debug { "Stored document in CouchDB: $database/$docId" }
                    true
                } else {
                    logger.warn { "Failed to store document in CouchDB: ${response.code} - ${response.body?.string()}" }
                    false
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to store document in CouchDB: $database/$docId" }
            false
        }
    }

    /**
     * Ensure database exists, create if not.
     */
    private fun ensureDatabase(database: String) {
        val request = Request.Builder()
            .url("$couchUrl/$database")
            .header("Authorization", okhttp3.Credentials.basic(couchUser, couchPassword))
            .put("{}".toRequestBody(jsonMediaType))
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                when (response.code) {
                    201 -> logger.info { "Created CouchDB database: $database" }
                    412 -> logger.debug { "CouchDB database already exists: $database" }
                    else -> logger.warn { "Failed to ensure database: ${response.code}" }
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to ensure database: $database" }
        }
    }

    /**
     * Get document revision for updates.
     */
    private fun getDocumentRev(database: String, docId: String): String? {
        val request = Request.Builder()
            .url("$couchUrl/$database/$docId")
            .header("Authorization", okhttp3.Credentials.basic(couchUser, couchPassword))
            .head()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.code == 200) {
                    response.header("ETag")?.trim('"')
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if document exists.
     */
    fun documentExists(database: String, docId: String): Boolean {
        val request = Request.Builder()
            .url("$couchUrl/$database/$docId")
            .header("Authorization", okhttp3.Credentials.basic(couchUser, couchPassword))
            .head()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                response.code == 200
            }
        } catch (e: Exception) {
            false
        }
    }
}
