package org.datamancy.bookstackwriter

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
 * Service for writing cleaned content to BookStack.
 * Organizes content by: Shelf (source type) → Book (category) → Page (article)
 */
class BookStackWriter(
    private val baseUrl: String,
    private val apiToken: String,
    private val apiSecret: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Cache for shelf and book IDs to minimize API calls
    private val shelfCache = mutableMapOf<String, Int>()
    private val bookCache = mutableMapOf<Pair<Int, String>, Int>()

    /**
     * Test connection to BookStack API
     */
    fun testConnection(): Boolean {
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
            logger.error(e) { "BookStack connection test failed" }
            false
        }
    }

    /**
     * Create or update a page with proper organization
     */
    fun createOrUpdatePage(
        sourceType: String,
        category: String,
        title: String,
        content: String,
        metadata: Map<String, String> = emptyMap()
    ): PageResult {
        return try {
            // Get or create shelf for source type
            val shelfId = getOrCreateShelf(sourceType)

            // Get or create book for category
            val bookId = getOrCreateBook(shelfId, category)

            // Build tags from metadata
            val tags = metadata.map { (key, value) ->
                mapOf("name" to key, "value" to value)
            }

            // Create or update the page
            val pageId = createOrUpdateBookStackPage(bookId, title, content, tags)

            PageResult(
                success = true,
                bookstackUrl = "$baseUrl/books/$bookId/page/${urlSlug(title)}",
                bookstackPageId = pageId
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to create/update page: $title" }
            PageResult(
                success = false,
                error = e.message ?: "Unknown error"
            )
        }
    }

    /**
     * Bulk create/update pages
     */
    fun bulkCreateOrUpdatePages(pages: List<CreatePageRequest>): List<PageResult> {
        return pages.map { page ->
            createOrUpdatePage(
                sourceType = page.sourceType,
                category = page.category,
                title = page.title,
                content = page.content,
                metadata = page.metadata
            )
        }
    }

    private fun getOrCreateShelf(name: String): Int {
        // Check cache
        shelfCache[name]?.let { return it }

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
                        shelfCache[name] = id
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
            "description" to "Content from $name data source"
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
            val id = json.get("id").asInt
            shelfCache[name] = id
            return id
        }
    }

    private fun getOrCreateBook(shelfId: Int, name: String): Int {
        val cacheKey = Pair(shelfId, name)

        // Check cache
        bookCache[cacheKey]?.let { return it }

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
                        bookCache[cacheKey] = id
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
            "description" to "Category: $name"
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

            bookCache[cacheKey] = bookId
            return bookId
        }
    }

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

            val payload = mapOf("books" to existingBookIds)

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

    private fun createOrUpdateBookStackPage(
        bookId: Int,
        name: String,
        markdownContent: String,
        tags: List<Map<String, String>>
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

        if (existingPageId != null) {
            // Update existing page
            logger.info { "Updating existing page: $name (ID: $existingPageId)" }
            val payload = mapOf(
                "markdown" to markdownContent,
                "tags" to tags
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
                return existingPageId!!
            }
        } else {
            // Create new page
            logger.info { "Creating new page: $name in book $bookId" }
            val payload = mapOf(
                "book_id" to bookId,
                "name" to name,
                "markdown" to markdownContent,
                "tags" to tags
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

    private fun Request.Builder.addAuth(): Request.Builder {
        return this.header("Authorization", "Token $apiToken:$apiSecret")
    }

    private fun urlSlug(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
    }
}
