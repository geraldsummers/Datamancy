package org.datamancy.datafetcher.clients

import com.google.gson.Gson
import com.google.gson.JsonObject
import io.github.oshai.kotlinlogging.KotlinLogging
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Client for interacting with BookStack API.
 * API Docs: https://demo.bookstackapp.com/api/docs
 */
class BookStackClient(
    private val baseUrl: String = System.getenv("BOOKSTACK_URL") ?: "http://bookstack:80",
    private val apiToken: String = System.getenv("BOOKSTACK_API_TOKEN_ID") ?: "",
    private val apiSecret: String = System.getenv("BOOKSTACK_API_TOKEN_SECRET") ?: ""
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Dry-run check: verifies BookStack API connectivity.
     */
    fun dryRun(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$baseUrl/api/docs")
                .get()
                .addAuth()
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            logger.error(e) { "BookStack dry-run failed" }
            false
        }
    }

    /**
     * Creates a new shelf or returns existing shelf ID if it already exists.
     */
    fun getOrCreateShelf(name: String, description: String = ""): Int {
        // Search for existing shelf
        val searchRequest = Request.Builder()
            .url("$baseUrl/api/shelves")
            .get()
            .addAuth()
            .build()

        client.newCall(searchRequest).execute().use { response ->
            if (response.isSuccessful) {
                val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
                val shelves = json.getAsJsonArray("data")

                shelves?.forEach { shelfElement ->
                    val shelf = shelfElement.asJsonObject
                    if (shelf.get("name").asString == name) {
                        val id = shelf.get("id").asInt
                        logger.info { "Found existing shelf: $name (ID: $id)" }
                        return id
                    }
                }
            }
        }

        // Create new shelf
        logger.info { "Creating new shelf: $name" }
        val payload = mapOf(
            "name" to name,
            "description" to description
        )

        val createRequest = Request.Builder()
            .url("$baseUrl/api/shelves")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .addAuth()
            .build()

        client.newCall(createRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to create shelf: ${response.code} - ${response.body?.string()}")
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            return json.get("id").asInt
        }
    }

    /**
     * Creates a new book within a shelf or returns existing book ID.
     */
    fun getOrCreateBook(shelfId: Int, name: String, description: String = ""): Int {
        // Search for existing book in shelf
        val shelfRequest = Request.Builder()
            .url("$baseUrl/api/shelves/$shelfId")
            .get()
            .addAuth()
            .build()

        client.newCall(shelfRequest).execute().use { response ->
            if (response.isSuccessful) {
                val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
                val books = json.getAsJsonArray("books")

                books?.forEach { bookElement ->
                    val book = bookElement.asJsonObject
                    if (book.get("name").asString == name) {
                        val id = book.get("id").asInt
                        logger.info { "Found existing book: $name (ID: $id)" }
                        return id
                    }
                }
            }
        }

        // Create new book
        logger.info { "Creating new book: $name in shelf $shelfId" }
        val payload = mapOf(
            "name" to name,
            "description" to description
        )

        val createRequest = Request.Builder()
            .url("$baseUrl/api/books")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .addAuth()
            .build()

        client.newCall(createRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to create book: ${response.code} - ${response.body?.string()}")
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val bookId = json.get("id").asInt

            // Add book to shelf
            addBookToShelf(shelfId, bookId)

            return bookId
        }
    }

    /**
     * Adds a book to a shelf.
     */
    private fun addBookToShelf(shelfId: Int, bookId: Int) {
        // Get current shelf to preserve existing books
        val shelfRequest = Request.Builder()
            .url("$baseUrl/api/shelves/$shelfId")
            .get()
            .addAuth()
            .build()

        val existingBookIds = mutableListOf<Int>()
        client.newCall(shelfRequest).execute().use { response ->
            if (response.isSuccessful) {
                val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
                val books = json.getAsJsonArray("books")
                books?.forEach { bookElement ->
                    existingBookIds.add(bookElement.asJsonObject.get("id").asInt)
                }
            }
        }

        // Add new book if not already present
        if (!existingBookIds.contains(bookId)) {
            existingBookIds.add(bookId)

            val payload = mapOf(
                "books" to existingBookIds
            )

            val updateRequest = Request.Builder()
                .url("$baseUrl/api/shelves/$shelfId")
                .put(gson.toJson(payload).toRequestBody(jsonMediaType))
                .addAuth()
                .build()

            client.newCall(updateRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.warn { "Failed to add book $bookId to shelf $shelfId: ${response.code}" }
                }
            }
        }
    }

    /**
     * Creates or updates a page within a book.
     * Returns page ID.
     */
    fun createOrUpdatePage(
        bookId: Int,
        name: String,
        markdownContent: String,
        tags: Map<String, String> = emptyMap()
    ): Int {
        // Search for existing page in book
        val bookRequest = Request.Builder()
            .url("$baseUrl/api/books/$bookId")
            .get()
            .addAuth()
            .build()

        var existingPageId: Int? = null
        client.newCall(bookRequest).execute().use { response ->
            if (response.isSuccessful) {
                val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
                val contents = json.getAsJsonArray("contents")

                contents?.forEach { item ->
                    val itemObj = item.asJsonObject
                    if (itemObj.get("type")?.asString == "page" &&
                        itemObj.get("name")?.asString == name) {
                        existingPageId = itemObj.get("id").asInt
                    }
                }
            }
        }

        val tagsList = tags.map { (key, value) ->
            mapOf("name" to key, "value" to value)
        }

        if (existingPageId != null) {
            // Update existing page
            logger.info { "Updating existing page: $name (ID: $existingPageId)" }
            val payload = mapOf(
                "markdown" to markdownContent,
                "tags" to tagsList
            )

            val updateRequest = Request.Builder()
                .url("$baseUrl/api/pages/$existingPageId")
                .put(gson.toJson(payload).toRequestBody(jsonMediaType))
                .addAuth()
                .build()

            client.newCall(updateRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to update page: ${response.code} - ${response.body?.string()}")
                }
            }

            return existingPageId!!
        } else {
            // Create new page
            logger.info { "Creating new page: $name in book $bookId" }
            val payload = mapOf(
                "book_id" to bookId,
                "name" to name,
                "markdown" to markdownContent,
                "tags" to tagsList
            )

            val createRequest = Request.Builder()
                .url("$baseUrl/api/pages")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .addAuth()
                .build()

            client.newCall(createRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to create page: ${response.code} - ${response.body?.string()}")
                }

                val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
                return json.get("id").asInt
            }
        }
    }

    /**
     * Exports a page in the specified format.
     * Formats: html, pdf, markdown, plain-text
     */
    fun exportPage(pageId: Int, format: String = "plain-text"): String {
        val request = Request.Builder()
            .url("$baseUrl/api/pages/$pageId/export/$format")
            .get()
            .addAuth()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to export page: ${response.code}")
            }

            return response.body?.string() ?: ""
        }
    }

    /**
     * Searches for pages matching a query.
     */
    fun searchPages(query: String, limit: Int = 100): List<BookStackPage> {
        val request = Request.Builder()
            .url("$baseUrl/api/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}&page=1&count=$limit")
            .get()
            .addAuth()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to search pages: ${response.code}")
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val results = json.getAsJsonArray("data")
            val pages = mutableListOf<BookStackPage>()

            results?.forEach { result ->
                val obj = result.asJsonObject
                if (obj.get("type")?.asString == "page") {
                    pages.add(BookStackPage(
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
     * Gets all pages in a book.
     */
    fun getBookPages(bookId: Int): List<BookStackPage> {
        val request = Request.Builder()
            .url("$baseUrl/api/books/$bookId")
            .get()
            .addAuth()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Failed to get book pages: ${response.code}")
            }

            val json = gson.fromJson(response.body?.string(), JsonObject::class.java)
            val contents = json.getAsJsonArray("contents")
            val pages = mutableListOf<BookStackPage>()

            contents?.forEach { item ->
                val obj = item.asJsonObject
                if (obj.get("type")?.asString == "page") {
                    pages.add(BookStackPage(
                        id = obj.get("id").asInt,
                        name = obj.get("name").asString,
                        url = obj.get("url")?.asString ?: ""
                    ))
                }
            }

            return pages
        }
    }

    private fun Request.Builder.addAuth(): Request.Builder {
        return this
            .header("Authorization", "Token $apiToken:$apiSecret")
            .header("Content-Type", "application/json")
    }
}

/**
 * Represents a BookStack page.
 */
data class BookStackPage(
    val id: Int,
    val name: String,
    val url: String
)
