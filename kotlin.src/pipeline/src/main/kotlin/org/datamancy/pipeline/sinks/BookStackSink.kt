package org.datamancy.pipeline.sinks

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.datamancy.pipeline.core.Sink
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private val logger = KotlinLogging.logger {}

/**
 * Sink implementation for writing documents to BookStack knowledge base via REST API.
 *
 * **BookStack Integration:**
 * - Uses REST API with token-based authentication (tokenId:tokenSecret)
 * - Manages three-level hierarchy: Books → Chapters → Pages
 * - Implements smart deduplication to avoid creating duplicate content
 * - Stores HTML-formatted content with metadata tags
 *
 * **Usage by BookStackWriter:**
 * The BookStackWriter polls PostgreSQL for documents with embedding_status=COMPLETED and
 * bookstack_status=PENDING, then writes them to BookStack using this sink. This ensures only
 * fully indexed documents (with vectors in Qdrant) appear in the knowledge base.
 *
 * **Hierarchy Model:**
 * - **Books**: Top-level containers (e.g., "RSS Articles", "CVE Database")
 * - **Chapters**: Organized by source type (e.g., "Hacker News", "High Severity CVEs")
 * - **Pages**: Individual documents with HTML content and metadata tags
 *
 * **Smart Deduplication:**
 * Three-level caching prevents duplicate API calls and ensures pages are updated (not duplicated)
 * when re-processing the same document:
 * - bookCache: Maps book name → BookStack book ID
 * - chapterCache: Maps "bookId:chapterName" → BookStack chapter ID
 * - pageCache: Maps "bookId:chapterId:pageTitle" → BookStack page ID
 *
 * **Idempotent Writes:**
 * When a page already exists, it's updated via PUT instead of creating a duplicate. This enables
 * safe re-processing of documents after content updates or pipeline restarts.
 *
 * **Downstream Consumers:**
 * - Human users browse BookStack for readable documentation
 * - Search results include bookstack_url metadata for easy navigation
 * - Future enhancement: Agent tools for programmatic BookStack access
 *
 * @param bookstackUrl The BookStack API base URL (e.g., "https://bookstack.example.com")
 * @param tokenId API token ID for authentication
 * @param tokenSecret API token secret for authentication
 *
 * @see org.datamancy.pipeline.workers.BookStackWriter
 * @see BookStackDocument
 */
class BookStackSink(
    private val bookstackUrl: String,
    private val tokenId: String,
    private val tokenSecret: String,
    private val maxRetries: Int = 5,
    private val baseDelayMs: Long = 500
) : Sink<BookStackDocument> {
    override val name = "BookStackSink"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    /** Cache mapping book name → BookStack book ID (prevents duplicate book creation) */
    private val bookCache = mutableMapOf<String, Int>()

    /** Cache mapping "bookId:chapterName" → BookStack chapter ID (prevents duplicate chapter creation) */
    private val chapterCache = mutableMapOf<String, Int>()

    /** Cache mapping "bookId:chapterId:pageTitle" → BookStack page ID (enables page updates instead of duplicates) */
    private val pageCache = mutableMapOf<String, Int>()

    /** Thread-safe map storing last written page URL for each page title (used to update PostgreSQL bookstack_url) */
    private val lastPageUrlMap = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Writes a document to BookStack, creating or updating the book/chapter/page hierarchy as needed.
     *
     * **Workflow:**
     * 1. Ensure book exists (create if needed, use cached ID if available)
     * 2. Ensure chapter exists if specified (create if needed, use cached ID if available)
     * 3. Check if page already exists (by title within the book/chapter)
     * 4. If page exists: Update via PUT (preserves page ID and URL)
     * 5. If page doesn't exist: Create via POST (generates new page ID and URL)
     * 6. Store page URL in lastPageUrlMap for PostgreSQL update
     *
     * **Authentication:**
     * All requests include "Authorization: Token tokenId:tokenSecret" header for API access.
     *
     * **Called by BookStackWriter:**
     * After a document has been embedded and stored in Qdrant (status=COMPLETED), BookStackWriter
     * calls this method to publish it to the human-readable knowledge base. The returned page URL
     * is stored in PostgreSQL's bookstack_url column.
     *
     * **HTML Formatting:**
     * The pageContent is expected to be HTML-formatted. Raw text is supported but may not render
     * optimally. Sources that generate Markdown should convert to HTML before passing to this sink.
     *
     * @param item The BookStack document containing book, chapter, page data, and HTML content
     * @throws Exception if BookStack API call fails (authentication error, network issue, etc.)
     */
    override suspend fun write(item: BookStackDocument) {
        try {
            // Step 1: Ensure book exists (cached lookup or API create)
            val bookId = getOrCreateBook(item.bookName, item.bookDescription)

            // Step 2: Ensure chapter exists if specified (cached lookup or API create)
            val chapterId = if (item.chapterName != null) {
                getOrCreateChapter(bookId, item.chapterName, item.chapterDescription)
            } else null

            // Step 3: Create or update page (API checks for existing page by title)
            val pageUrl = createOrUpdatePage(bookId, chapterId, item)

            // Step 4: Store URL for PostgreSQL update (BookStackWriter retrieves this)
            lastPageUrlMap[item.pageTitle] = pageUrl

            logger.debug { "Wrote page '${item.pageTitle}' to BookStack: $pageUrl" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to write to BookStack: ${e.message}" }
            throw e
        }
    }

    /**
     * Writes a batch of documents by calling write() sequentially for each item.
     *
     * BookStack API doesn't support true batch operations, so this method simply iterates.
     * Future optimization: Parallelize writes with coroutines (requires rate limiting).
     *
     * @param items List of BookStack documents to write
     */
    override suspend fun writeBatch(items: List<BookStackDocument>) {
        items.forEach { write(it) }
    }

    /**
     * Retrieves the last written page URL for a given page title.
     *
     * **Used by BookStackWriter:**
     * After calling write(), BookStackWriter retrieves the page URL to update PostgreSQL's
     * bookstack_url column, enabling search results to link to the knowledge base.
     *
     * @param pageTitle The page title to look up
     * @return The BookStack page URL, or null if not found in cache
     */
    fun getLastPageUrl(pageTitle: String): String? {
        return lastPageUrlMap[pageTitle]
    }

    /**
     * Performs a health check by listing books via the BookStack API.
     *
     * This verifies that:
     * - BookStack REST API endpoint is reachable
     * - Token-based authentication is working
     * - API permissions allow book listing
     *
     * Used by monitoring systems and startup checks to detect BookStack outages.
     *
     * @return true if BookStack is healthy and API is accessible, false otherwise
     */
    override suspend fun healthCheck(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$bookstackUrl/api/books")
                .header("Authorization", "Token $tokenId:$tokenSecret")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            logger.error(e) { "BookStack health check failed: ${e.message}" }
            false
        }
    }

    /**
     * Executes an HTTP request with exponential backoff retry logic for transient failures.
     *
     * **Retryable Errors:**
     * - HTTP 429 (Too Many Requests): Rate limiting
     * - HTTP 500-504: Server errors or proxy issues
     * - ConnectException: Service unreachable
     * - SocketTimeoutException: Request timeout
     * - IOException: Network instability
     *
     * **Retry Strategy:**
     * - Exponential backoff: delay = baseDelayMs * 2^attempt (500ms, 1s, 2s, 4s, 8s)
     * - Jitter: 0-50% added to prevent thundering herd
     * - Max retries: 5 attempts
     *
     * @param request The HTTP request to execute
     * @return The successful HTTP response body as string
     * @throws Exception if all retries exhausted or non-retryable error
     */
    private suspend fun executeWithRetry(request: Request): String {
        var lastException: Exception? = null

        for (attempt in 0..maxRetries) {
            try {
                client.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""

                    // Retryable HTTP errors
                    if (response.code in listOf(429, 500, 502, 503, 504)) {
                        throw RetryableBookStackException("BookStack returned retryable error ${response.code}")
                    }

                    // Non-retryable errors (4xx except 429)
                    if (!response.isSuccessful) {
                        throw Exception("BookStack returned ${response.code}: $bodyString")
                    }

                    return bodyString
                }
            } catch (e: RetryableBookStackException) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = baseDelayMs * (1L shl attempt)
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs
                    logger.warn { "BookStack API attempt ${attempt + 1}/$maxRetries failed (${e.message}), retrying in ${totalDelay}ms" }
                    delay(totalDelay)
                }
            } catch (e: java.net.ConnectException) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1L shl attempt)
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs
                    logger.warn { "BookStack unreachable (attempt ${attempt + 1}/$maxRetries), retrying in ${totalDelay}ms" }
                    delay(totalDelay)
                } else {
                    throw Exception("BookStack unreachable after $maxRetries retries", e)
                }
            } catch (e: java.net.SocketTimeoutException) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1L shl attempt)
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs
                    logger.warn { "BookStack timeout (attempt ${attempt + 1}/$maxRetries), retrying in ${totalDelay}ms" }
                    delay(totalDelay)
                } else {
                    throw Exception("BookStack timeout after $maxRetries retries", e)
                }
            } catch (e: java.io.IOException) {
                lastException = e
                if (attempt < maxRetries) {
                    val backoffMs = 1000L * (1L shl attempt)
                    val jitterMs = (backoffMs * Random.nextDouble(0.0, 0.5)).toLong()
                    val totalDelay = backoffMs + jitterMs
                    logger.warn { "Network error (attempt ${attempt + 1}/$maxRetries), retrying in ${totalDelay}ms: ${e.message}" }
                    delay(totalDelay)
                } else {
                    throw Exception("Network error after $maxRetries retries: ${e.message}", e)
                }
            } catch (e: Exception) {
                // Non-retryable errors
                logger.error(e) { "BookStack API error (non-retryable): ${e.message}" }
                throw e
            }
        }

        throw lastException ?: Exception("BookStack API failed after $maxRetries retries")
    }

    /**
     * Gets or creates a BookStack book by name.
     *
     * **Caching Strategy:**
     * - First check: bookCache (in-memory, fast)
     * - Second check: BookStack API (network call to list all books)
     * - If not found: Create new book via POST /api/books
     *
     * **Deduplication:**
     * The cache ensures we don't create duplicate books. Once a book is found or created, its ID
     * is cached for the lifetime of this sink instance. This prevents redundant API calls when
     * writing multiple pages to the same book.
     *
     * **Auto-generated Description:**
     * If no description is provided, defaults to "Auto-generated by Datamancy Pipeline".
     *
     * **Retry Logic:**
     * All HTTP calls use exponential backoff retry logic for transient failures (rate limits,
     * network issues, server errors).
     *
     * @param name The book name (e.g., "RSS Articles", "CVE Database")
     * @param description Optional book description
     * @return BookStack book ID
     * @throws Exception if API call fails after retries
     */
    private suspend fun getOrCreateBook(name: String, description: String?): Int {
        // Check cache first (fast path)
        bookCache[name]?.let { return it }

        // Search for existing book via API (slow path)
        try {
            val searchRequest = Request.Builder()
                .url("$bookstackUrl/api/books")
                .header("Authorization", "Token $tokenId:$tokenSecret")
                .get()
                .build()

            val bodyString = executeWithRetry(searchRequest)
            val json = JsonParser.parseString(bodyString).asJsonObject
            val books = json.getAsJsonArray("data")

            for (book in books) {
                val bookObj = book.asJsonObject
                if (bookObj.get("name").asString == name) {
                    val id = bookObj.get("id").asInt
                    bookCache[name] = id
                    return id
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to search for book: ${e.message}" }
        }

        // Book doesn't exist - create it
        val payload = mapOf(
            "name" to name,
            "description" to (description ?: "Auto-generated by Datamancy Pipeline")
        )

        val request = Request.Builder()
            .url("$bookstackUrl/api/books")
            .header("Authorization", "Token $tokenId:$tokenSecret")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        val bodyString = executeWithRetry(request)
        val json = JsonParser.parseString(bodyString).asJsonObject
        val id = json.get("id").asInt
        bookCache[name] = id
        logger.info { "Created BookStack book: $name (ID: $id)" }
        return id
    }

    /**
     * Gets or creates a BookStack chapter within a book.
     *
     * **Caching Strategy:**
     * - First check: chapterCache using composite key "bookId:chapterName"
     * - Second check: BookStack API (GET /api/books/{bookId} to list chapters)
     * - If not found: Create new chapter via POST /api/chapters
     *
     * **Deduplication:**
     * Chapters are scoped to their parent book, so the cache key includes both book ID and chapter
     * name. This prevents collisions when different books have chapters with the same name.
     *
     * **Hierarchy Management:**
     * Chapters provide organizational structure within books (e.g., grouping RSS articles by source,
     * or CVEs by severity). Pages can exist directly in books (chapterId=null) or within chapters.
     *
     * **Retry Logic:**
     * All HTTP calls use exponential backoff retry logic for transient failures.
     *
     * @param bookId The parent book ID
     * @param name The chapter name (e.g., "Hacker News", "High Severity CVEs")
     * @param description Optional chapter description
     * @return BookStack chapter ID
     * @throws Exception if API call fails after retries
     */
    private suspend fun getOrCreateChapter(bookId: Int, name: String, description: String?): Int {
        val cacheKey = "$bookId:$name"
        chapterCache[cacheKey]?.let { return it }

        // Search for existing chapter within book (via book contents API)
        try {
            val searchRequest = Request.Builder()
                .url("$bookstackUrl/api/books/$bookId")
                .header("Authorization", "Token $tokenId:$tokenSecret")
                .get()
                .build()

            val bodyString = executeWithRetry(searchRequest)
            val json = JsonParser.parseString(bodyString).asJsonObject
            val contents = json.getAsJsonArray("contents")

            for (content in contents) {
                val contentObj = content.asJsonObject
                if (contentObj.get("type").asString == "chapter" &&
                    contentObj.get("name").asString == name) {
                    val id = contentObj.get("id").asInt
                    chapterCache[cacheKey] = id
                    return id
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to search for chapter: ${e.message}" }
        }

        // Chapter doesn't exist - create it
        val payload = mapOf(
            "book_id" to bookId,
            "name" to name,
            "description" to (description ?: "")
        )

        val request = Request.Builder()
            .url("$bookstackUrl/api/chapters")
            .header("Authorization", "Token $tokenId:$tokenSecret")
            .post(gson.toJson(payload).toRequestBody(jsonMediaType))
            .build()

        val bodyString = executeWithRetry(request)
        val json = JsonParser.parseString(bodyString).asJsonObject
        val id = json.get("id").asInt
        chapterCache[cacheKey] = id
        logger.info { "Created BookStack chapter: $name (ID: $id)" }
        return id
    }

    /**
     * Creates a new page or updates an existing page in BookStack.
     *
     * **Smart Deduplication:**
     * - Searches for existing pages by title within the same book/chapter
     * - If found: Updates page content and tags via PUT (preserves page ID and URL)
     * - If not found: Creates new page via POST (generates new ID and URL)
     *
     * **Page Cache:**
     * Uses composite key "bookId:chapterId:pageTitle" to track page IDs, enabling fast lookups
     * for subsequent updates. This is critical for idempotent writes - re-running the pipeline
     * updates existing pages instead of creating duplicates.
     *
     * **Update Behavior:**
     * When updating an existing page, the entire content and tags are replaced (PUT semantics).
     * This ensures pages reflect the latest document content from the source.
     *
     * **HTML Content:**
     * The doc.pageContent is expected to be HTML-formatted for optimal rendering. Raw text will
     * work but may not display as nicely. Sources should convert Markdown → HTML if needed.
     *
     * **Tags:**
     * Tags are stored as name-value pairs (e.g., "source: rss", "url: https://..."). These appear
     * in BookStack's UI and are searchable within the knowledge base.
     *
     * **Retry Logic:**
     * All HTTP calls use exponential backoff retry logic for transient failures.
     *
     * @param bookId The parent book ID
     * @param chapterId The parent chapter ID, or null for pages directly in the book
     * @param doc The BookStack document containing page title, content, and tags
     * @return The full BookStack page URL (e.g., "https://bookstack.example.com/books/rss-articles/page/my-article")
     * @throws Exception if API call fails after retries
     */
    private suspend fun createOrUpdatePage(bookId: Int, chapterId: Int?, doc: BookStackDocument): String {
        // Generate composite cache key (includes book, chapter, and title for uniqueness)
        val cacheKey = "${bookId}:${chapterId ?: "null"}:${doc.pageTitle}"
        var existingPageId: Int? = pageCache[cacheKey]

        // If not in cache, search via API to check if page already exists
        if (existingPageId == null) {
            try {
                val encodedTitle = URLEncoder.encode(doc.pageTitle, "UTF-8")
                val searchRequest = Request.Builder()
                    .url("$bookstackUrl/api/pages?filter[name]=$encodedTitle")
                    .header("Authorization", "Token $tokenId:$tokenSecret")
                    .get()
                    .build()

                val bodyString = executeWithRetry(searchRequest)
                val json = JsonParser.parseString(bodyString).asJsonObject
                val pages = json.getAsJsonArray("data")

                for (page in pages) {
                    val pageObj = page.asJsonObject
                    // Match on book ID and chapter ID to find correct page (title alone may not be unique)
                    val pageBookId = pageObj.get("book_id").asInt
                    val pageChapterId = pageObj.get("chapter_id")?.let {
                        if (it.isJsonNull) null else it.asInt
                    }

                    if (pageBookId == bookId && pageChapterId == chapterId) {
                        existingPageId = pageObj.get("id").asInt
                        pageCache[cacheKey] = existingPageId!!
                        break
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to search for existing page: ${e.message}" }
            }
        }

        // Build page payload with HTML content and tags
        val payload = mutableMapOf(
            "book_id" to bookId,
            "name" to doc.pageTitle,
            "html" to doc.pageContent,
            "tags" to doc.tags.map { tag ->
                mapOf("name" to tag.key, "value" to tag.value)
            }
        )

        if (chapterId != null) {
            payload["chapter_id"] = chapterId
        }

        if (existingPageId != null) {
            // Page exists - update it via PUT (idempotent write)
            val request = Request.Builder()
                .url("$bookstackUrl/api/pages/$existingPageId")
                .header("Authorization", "Token $tokenId:$tokenSecret")
                .put(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()

            val bodyString = executeWithRetry(request)
            val json = JsonParser.parseString(bodyString).asJsonObject
            val slug = json.get("slug")?.asString ?: throw Exception("No slug in response")
            val pageUrl = "$bookstackUrl/books/${json.get("book_slug")?.asString}/page/$slug"

            logger.debug { "Updated BookStack page: ${doc.pageTitle} (ID: $existingPageId)" }
            return pageUrl
        } else {
            // Page doesn't exist - create it via POST
            val request = Request.Builder()
                .url("$bookstackUrl/api/pages")
                .header("Authorization", "Token $tokenId:$tokenSecret")
                .post(gson.toJson(payload).toRequestBody(jsonMediaType))
                .build()

            val bodyString = executeWithRetry(request)
            val json = JsonParser.parseString(bodyString).asJsonObject
            val pageId = json.get("id").asInt
            val slug = json.get("slug")?.asString ?: throw Exception("No slug in response")
            val pageUrl = "$bookstackUrl/books/${json.get("book_slug")?.asString}/page/$slug"

            // Cache the new page ID for future updates
            pageCache[cacheKey] = pageId

            logger.debug { "Created BookStack page: ${doc.pageTitle} (ID: $pageId)" }
            return pageUrl
        }
    }
}

/**
 * Custom exception for retryable BookStack API errors.
 */
class RetryableBookStackException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Represents a document to be written to BookStack knowledge base.
 *
 * **Hierarchy Model:**
 * - `bookName`: Top-level container (e.g., "RSS Articles", "CVE Database")
 * - `chapterName`: Optional organizational layer (e.g., "Hacker News", "High Severity CVEs")
 * - `pageTitle`: Individual document title
 * - `pageContent`: HTML-formatted content for display
 *
 * **HTML Formatting:**
 * The pageContent field should contain HTML for best rendering in BookStack. Raw text is supported
 * but may not display optimally. Sources that generate Markdown should convert to HTML before
 * creating BookStackDocument instances.
 *
 * **Tags:**
 * Tags are metadata key-value pairs (e.g., "source: rss", "url: https://...", "published: 2024-01-15").
 * These appear in BookStack's UI and are searchable within the knowledge base.
 *
 * **Usage:**
 * Created by BookStackWriter from StagedDocument after embedding is complete. The writer transforms
 * source-specific metadata into BookStack's hierarchy structure.
 *
 * @property bookName The book name (top-level container)
 * @property bookDescription Optional book description
 * @property chapterName Optional chapter name (organizational layer)
 * @property chapterDescription Optional chapter description
 * @property pageTitle The page title (individual document)
 * @property pageContent HTML-formatted content for display
 * @property tags Metadata key-value pairs for search and organization
 */
data class BookStackDocument(
    val bookName: String,
    val bookDescription: String? = null,
    val chapterName: String? = null,
    val chapterDescription: String? = null,
    val pageTitle: String,
    val pageContent: String,
    val tags: Map<String, String> = emptyMap()
)
