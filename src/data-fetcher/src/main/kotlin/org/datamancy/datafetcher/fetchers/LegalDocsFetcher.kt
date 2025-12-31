package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okhttp3.OkHttpClient
import okhttp3.Request
import org.datamancy.datafetcher.clients.BookStackClient
import org.datamancy.datafetcher.config.LegalConfig
import org.datamancy.datafetcher.converters.HtmlToMarkdownConverter
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.PostgresStore
import org.datamancy.datafetcher.storage.ClickHouseStore
import org.datamancy.datafetcher.storage.ContentHasher
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Parse HTTP Date header (RFC 1123 format).
 * Example: "Wed, 21 Oct 2015 07:28:00 GMT"
 */
private fun parseHttpDate(dateString: String): Instant? {
    return try {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        val date = format.parse(dateString)
        Instant.fromEpochMilliseconds(date.time)
    } catch (e: Exception) {
        logger.warn { "Failed to parse HTTP date: $dateString" }
        null
    }
}

data class LegislationItem(
    val title: String,
    val jurisdiction: String,
    val year: String?,
    val type: String,
    val identifier: String?,
    val url: String,
    val status: String?,
    val registrationDate: String?,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Represents a section of legislation with markdown content.
 */
data class LegislationSection(
    val actTitle: String,
    val actUrl: String,
    val jurisdiction: String,
    val sectionNumber: String,
    val sectionTitle: String,
    val markdownContent: String,
    val year: String?,
    val identifier: String?
)

class LegalDocsFetcher(private val config: LegalConfig) : Fetcher {
    private val pgStore = PostgresStore()
    private val clickHouseStore = ClickHouseStore()
    private val gson = Gson()
    private val bookstack = BookStackClient()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val userAgent = "Mozilla/5.0 (compatible; DatamancyBot/1.0; +https://datamancy.org)"

    // Rate limiting: delay between requests (in milliseconds)
    private val requestDelay = 1000L

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("legal_docs", version = "2.0.0") { ctx ->
            logger.info { "Starting Australian legislation fetch from ${1 + config.stateUrls.size} jurisdictions..." }

            // Fetch federal legislation
            ctx.markAttempted()
            try {
                logger.info { "Fetching federal legislation from ${config.ausLegislationUrl}" }
                val federalItems = fetchFederalLegislation(ctx)
                logger.info { "Fetched ${federalItems.size} federal legislation items" }
                delay(requestDelay)
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch federal legislation" }
                ctx.markFailed()
                ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", "Federal")
            }

            // Fetch state/territory legislation
            for ((state, url) in config.stateUrls) {
                ctx.markAttempted()
                try {
                    logger.info { "Fetching ${state.uppercase()} legislation from $url" }
                    val stateItems = fetchStateLegislation(ctx, state, url)
                    logger.info { "Fetched ${stateItems.size} ${state.uppercase()} legislation items" }
                    delay(requestDelay)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch ${state.uppercase()} legislation" }
                    ctx.markFailed()
                    ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", state.uppercase())
                }
            }

            // Store metadata
            pgStore.storeFetchMetadata(
                source = "legal",
                category = "australian_legislation",
                itemCount = ctx.metrics.new + ctx.metrics.updated,
                fetchedAt = Clock.System.now(),
                metadata = mapOf(
                    "jurisdictions" to (1 + config.stateUrls.size),
                    "new" to ctx.metrics.new,
                    "updated" to ctx.metrics.updated,
                    "skipped" to ctx.metrics.skipped,
                    "failed" to ctx.metrics.failed
                )
            )

            "Processed ${ctx.metrics.attempted} items: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${ctx.metrics.skipped} skipped (${ctx.metrics.failed} failed)"
        }
    }

    /**
     * Fetches legislation directly to ClickHouse with three-way sync (PRIMARY METHOD).
     * Handles: new Acts, updated Acts, and repealed Acts.
     * Processes all jurisdictions: Federal + 8 states/territories.
     */
    suspend fun fetchToClickHouse(limitPerJurisdiction: Int = Int.MAX_VALUE): FetchResult {
        return FetchExecutionContext.execute("legal_docs_clickhouse", version = "4.0.0") { ctx ->
            logger.info { "Starting three-way sync for all Australian legislation (limit: $limitPerJurisdiction per jurisdiction)..." }

            // Ensure schema exists
            clickHouseStore.ensureSchema()

            var totalSections = 0
            var totalRepealed = 0

            // Process federal legislation
            totalSections += processJurisdiction("FEDERAL", null, limitPerJurisdiction, ctx)

            // Process all state/territory legislation
            for ((state, url) in config.stateUrls) {
                totalSections += processJurisdiction(state.uppercase(), url, limitPerJurisdiction, ctx)
            }

            // Store metadata
            pgStore.storeFetchMetadata(
                source = "legal_clickhouse",
                category = "australian_legislation_sync",
                itemCount = totalSections,
                fetchedAt = Clock.System.now(),
                metadata = mapOf(
                    "jurisdictions" to (1 + config.stateUrls.size),
                    "sections_stored" to totalSections,
                    "repealed" to totalRepealed,
                    "new" to ctx.metrics.new,
                    "updated" to ctx.metrics.updated,
                    "skipped" to ctx.metrics.skipped,
                    "failed" to ctx.metrics.failed
                )
            )

            "Synced ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${totalRepealed} repealed across ${1 + config.stateUrls.size} jurisdictions (${ctx.metrics.failed} failed)"
        }
    }

    /**
     * Process a single jurisdiction with three-way sync logic.
     */
    private suspend fun processJurisdiction(
        jurisdiction: String,
        baseUrl: String?,
        limit: Int,
        ctx: FetchExecutionContext
    ): Int {
        var sectionsProcessed = 0
        var jurisdictionActsNew = 0
        var jurisdictionActsUpdated = 0
        var jurisdictionActsRepealed = 0
        var jurisdictionErrors = 0
        var lastError: String? = null

        try {
            logger.info { "Processing $jurisdiction legislation..." }

            // Update status: in_progress
            pgStore.updateLegalIngestionStatus(
                jurisdiction = jurisdiction,
                syncStatus = "in_progress",
                actsTotal = 0,
                actsNew = 0,
                actsUpdated = 0,
                actsRepealed = 0,
                sectionsTotal = 0,
                errorsCount = 0
            )

            // 1. Fetch current active Acts from legislation website
            val currentActiveActs = if (jurisdiction == "FEDERAL") {
                fetchFederalLegislation(ctx).take(limit)
            } else {
                fetchStateLegislation(ctx, jurisdiction.lowercase(), baseUrl!!).take(limit)
            }

            val currentActiveUrls = currentActiveActs.map { it.url }.toSet()
            logger.info { "$jurisdiction: Found ${currentActiveActs.size} active Acts" }

            // 2. Get existing Acts from database for this jurisdiction
            val existingUrls = clickHouseStore.getAllActiveLegalDocumentUrls(jurisdiction)
            logger.info { "$jurisdiction: ${existingUrls.size} Acts in database" }

            // 3. Three-way comparison
            val newActUrls = currentActiveUrls - existingUrls
            val removedUrls = existingUrls - currentActiveUrls
            val potentialUpdates = currentActiveActs.filter { it.url in existingUrls }

            logger.info { "$jurisdiction: ${newActUrls.size} new, ${potentialUpdates.size} potential updates, ${removedUrls.size} removed" }

            // 4. Mark removed Acts as repealed
            removedUrls.forEach { url ->
                try {
                    clickHouseStore.markLegalDocumentRepealed(url)
                    jurisdictionActsRepealed++
                    ctx.markUpdated()
                } catch (e: Exception) {
                    logger.error(e) { "Failed to mark $url as repealed" }
                    jurisdictionErrors++
                    lastError = e.message
                    ctx.recordError("REPEAL_ERROR", e.message ?: "Unknown error", url)
                }
            }

            // 5. Process new Acts
            currentActiveActs.filter { it.url in newActUrls }.forEach { act ->
                ctx.markAttempted()
                try {
                    delay(requestDelay)
                    val sections = fetchActSections(act)

                    sections.forEach { section ->
                        storeSectionToClickHouse(section, ctx, isNew = true)
                    }

                    // Track the Act
                    pgStore.trackLegalAct(
                        actUrl = act.url,
                        jurisdiction = jurisdiction,
                        actTitle = act.title,
                        year = act.year,
                        identifier = act.identifier,
                        status = "active",
                        sectionsCount = sections.size,
                        contentHash = ContentHasher.hashJson(sections.joinToString { it.markdownContent }),
                        fetchStatus = "success"
                    )

                    sectionsProcessed += sections.size
                    jurisdictionActsNew++
                    ctx.markFetched()
                    ctx.markNew(sections.size)
                    logger.info { "[$jurisdiction] NEW: ${act.title} - ${sections.size} sections" }
                } catch (e: Exception) {
                    logger.error(e) { "[$jurisdiction] Failed to fetch new Act: ${act.title}" }
                    jurisdictionErrors++
                    lastError = e.message

                    // Track failed Act
                    pgStore.trackLegalAct(
                        actUrl = act.url,
                        jurisdiction = jurisdiction,
                        actTitle = act.title,
                        year = act.year,
                        identifier = act.identifier,
                        status = "active",
                        sectionsCount = 0,
                        contentHash = null,
                        fetchStatus = "failed",
                        errorMessage = e.message
                    )

                    ctx.markFailed()
                    ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", act.title)
                }
            }

            // 6. Check for updates (light scan with Last-Modified, then hash comparison)
            potentialUpdates.forEach { act ->
                ctx.markAttempted()
                try {
                    delay(requestDelay)

                    // Optimization: Check Last-Modified header first (saves bandwidth)
                    val shouldCheck = try {
                        val headRequest = okhttp3.Request.Builder().url(act.url).head().build()
                        val headResponse = httpClient.newCall(headRequest).execute()

                        val lastModifiedHeader = headResponse.header("Last-Modified")
                        val lastChecked = clickHouseStore.getLastCheckedTime(act.url)

                        headResponse.close()

                        // If we have Last-Modified header and last check time, compare
                        if (lastModifiedHeader != null && lastChecked != null) {
                            val lastModified = parseHttpDate(lastModifiedHeader)
                            lastModified?.let { it > lastChecked } ?: true
                        } else {
                            // No metadata available, need to check content
                            true
                        }
                    } catch (e: Exception) {
                        // HEAD request failed or no Last-Modified header - fall back to content check
                        logger.debug { "[$jurisdiction] HEAD request failed for ${act.title}, checking content" }
                        true
                    }

                    if (!shouldCheck) {
                        ctx.markSkipped()
                        logger.debug { "[$jurisdiction] UNCHANGED (Last-Modified): ${act.title}" }
                        return@forEach
                    }

                    // Full content check: Fetch sections and compare hashes
                    val sections = fetchActSections(act)

                    var hasChanges = false
                    sections.forEach { section ->
                        val existingHash = clickHouseStore.getLegalDocumentHash(section.actUrl, section.sectionNumber)
                        val newHash = ContentHasher.hashJson(section.markdownContent)

                        if (existingHash == null) {
                            // New section added to existing Act
                            storeSectionToClickHouse(section, ctx, isNew = true)
                            hasChanges = true
                            ctx.markNew()
                        } else if (existingHash != newHash) {
                            // Section content changed
                            storeSectionToClickHouse(section, ctx, isNew = false)
                            hasChanges = true
                            ctx.markUpdated()
                        } else {
                            // Unchanged
                            ctx.markSkipped()
                        }
                    }

                    if (hasChanges) {
                        sectionsProcessed += sections.size
                        jurisdictionActsUpdated++
                        ctx.markFetched()

                        // Track updated Act
                        pgStore.trackLegalAct(
                            actUrl = act.url,
                            jurisdiction = jurisdiction,
                            actTitle = act.title,
                            year = act.year,
                            identifier = act.identifier,
                            status = "active",
                            sectionsCount = sections.size,
                            contentHash = ContentHasher.hashJson(sections.joinToString { it.markdownContent }),
                            fetchStatus = "success"
                        )

                        logger.info { "[$jurisdiction] UPDATED: ${act.title} - ${sections.size} sections" }
                    } else {
                        logger.debug { "[$jurisdiction] UNCHANGED (content): ${act.title}" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "[$jurisdiction] Failed to check Act: ${act.title}" }
                    jurisdictionErrors++
                    lastError = e.message

                    // Track failed check
                    pgStore.trackLegalAct(
                        actUrl = act.url,
                        jurisdiction = jurisdiction,
                        actTitle = act.title,
                        year = act.year,
                        identifier = act.identifier,
                        status = "active",
                        sectionsCount = 0,
                        contentHash = null,
                        fetchStatus = "failed",
                        errorMessage = e.message
                    )

                    ctx.markFailed()
                    ctx.recordError("UPDATE_CHECK_ERROR", e.message ?: "Unknown error", act.title)
                }
            }

            // Update final status for jurisdiction
            pgStore.updateLegalIngestionStatus(
                jurisdiction = jurisdiction,
                syncStatus = if (jurisdictionErrors == 0) "completed" else "completed_with_errors",
                actsTotal = currentActiveActs.size,
                actsNew = jurisdictionActsNew,
                actsUpdated = jurisdictionActsUpdated,
                actsRepealed = jurisdictionActsRepealed,
                sectionsTotal = sectionsProcessed,
                errorsCount = jurisdictionErrors,
                lastErrorMessage = lastError
            )

        } catch (e: Exception) {
            logger.error(e) { "Failed to process $jurisdiction legislation" }
            lastError = e.message
            jurisdictionErrors++

            // Update status: failed
            pgStore.updateLegalIngestionStatus(
                jurisdiction = jurisdiction,
                syncStatus = "failed",
                actsTotal = 0,
                actsNew = jurisdictionActsNew,
                actsUpdated = jurisdictionActsUpdated,
                actsRepealed = jurisdictionActsRepealed,
                sectionsTotal = sectionsProcessed,
                errorsCount = jurisdictionErrors,
                lastErrorMessage = lastError
            )

            ctx.markFailed()
            ctx.recordError("JURISDICTION_ERROR", e.message ?: "Unknown error", jurisdiction)
        }

        return sectionsProcessed
    }

    /**
     * Stores a legislation section to ClickHouse.
     * With ReplacingMergeTree, newer versions automatically replace older ones.
     */
    private fun storeSectionToClickHouse(section: LegislationSection, ctx: FetchExecutionContext, isNew: Boolean) {
        try {
            // Generate unique doc ID from URL
            val docId = section.actUrl.hashCode().toString()
            val contentHash = ContentHasher.hashJson(section.markdownContent)

            clickHouseStore.storeLegalDocument(
                docId = docId,
                jurisdiction = section.jurisdiction,
                docType = "Act",
                title = section.actTitle,
                year = section.year,
                identifier = section.identifier,
                url = section.actUrl,
                status = "In force",
                sectionNumber = section.sectionNumber,
                sectionTitle = section.sectionTitle,
                content = section.markdownContent,
                contentMarkdown = section.markdownContent,
                fetchedAt = Clock.System.now(),
                contentHash = contentHash,
                metadata = mapOf(
                    "fetcher_version" to "4.0.0",
                    "source" to "legislation.gov.au",
                    "sync_type" to if (isNew) "new" else "updated"
                )
            )
        } catch (e: Exception) {
            logger.error(e) { "Failed to store section ${section.sectionNumber} to ClickHouse" }
            ctx.recordError("STORAGE_ERROR", e.message ?: "Unknown error", section.sectionTitle)
        }
    }

    /**
     * Fetches full Act content with sections converted to Markdown.
     * For testing - fetches first N Acts from federal legislation.
     */
    suspend fun fetchWithMarkdown(limitPerJurisdiction: Int = 1): FetchResult {
        return FetchExecutionContext.execute("legal_docs_markdown") { ctx ->
            logger.info { "Fetching legislation with full markdown content (limit: $limitPerJurisdiction per jurisdiction)..." }

            val allSections = mutableListOf<LegislationSection>()

            // Fetch federal - limit to N Acts
            ctx.markAttempted()
            try {
                logger.info { "Fetching federal legislation with markdown..." }
                val federalItems = fetchFederalLegislation(ctx).take(limitPerJurisdiction)

                for (item in federalItems) {
                    ctx.markAttempted()
                    try {
                        delay(requestDelay)
                        val sections = fetchActSections(item)
                        allSections.addAll(sections)
                        ctx.markFetched()
                        ctx.markNew(sections.size)
                        logger.info { "Fetched ${sections.size} sections from ${item.title}" }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to fetch sections for ${item.title}" }
                        ctx.markFailed()
                        ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", item.title)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch federal legislation" }
                ctx.markFailed()
                ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", "Federal")
            }

            // Store markdown sections
            if (allSections.isNotEmpty()) {
                storeMarkdownSections(ctx, allSections)
            }

            // Store metadata
            pgStore.storeFetchMetadata(
                source = "legal_markdown",
                category = "australian_legislation_sections",
                itemCount = allSections.size,
                fetchedAt = Clock.System.now(),
                metadata = mapOf(
                    "acts_fetched" to allSections.map { it.actTitle }.distinct().size,
                    "fetched" to ctx.metrics.fetched,
                    "failed" to ctx.metrics.failed
                )
            )

            "Fetched ${allSections.size} sections from ${allSections.map { it.actTitle }.distinct().size} Acts (${ctx.metrics.failed} failed)"
        }
    }

    /**
     * Fetches all sections of an Act and converts to Markdown.
     */
    private suspend fun fetchActSections(item: LegislationItem): List<LegislationSection> {
        logger.info { "Fetching full content for: ${item.title}" }

        // Fetch the Act's page
        val doc = fetchPage(item.url)

        // Try to find the "view whole" or full text link
        val fullTextLink = doc.select(
            "a:contains(View), " +
            "a:contains(Full), " +
            "a:contains(Whole), " +
            "a[href*='whole'], " +
            "a[href*='current']"
        ).firstOrNull()?.attr("abs:href") ?: item.url

        // Fetch full text page if different
        val fullDoc = if (fullTextLink != item.url) {
            delay(requestDelay)
            fetchPage(fullTextLink)
        } else {
            doc
        }

        // Find the main content area (try various selectors)
        val contentElement = fullDoc.select(
            "#content, " +
            ".content, " +
            "main, " +
            ".legislation-content, " +
            ".act-content, " +
            "article"
        ).firstOrNull() ?: fullDoc.body()

        // Convert to markdown sections
        val markdownSections = HtmlToMarkdownConverter.convertWithSections(
            contentElement.html(),
            fullTextLink
        )

        // Map to LegislationSection objects
        return markdownSections.map { mdSection ->
            LegislationSection(
                actTitle = item.title,
                actUrl = fullTextLink,
                jurisdiction = item.jurisdiction,
                sectionNumber = mdSection.sectionNumber,
                sectionTitle = mdSection.title,
                markdownContent = mdSection.content,
                year = item.year,
                identifier = item.identifier
            )
        }
    }

    /**
     * Fetches legislation and pushes to BookStack.
     * Creates shelf per jurisdiction, book per Act, page per section.
     */
    suspend fun fetchToBookStack(limitPerJurisdiction: Int = 1): FetchResult {
        return FetchExecutionContext.execute("legal_docs_bookstack") { ctx ->
            logger.info { "Fetching legislation to BookStack (limit: $limitPerJurisdiction per jurisdiction)..." }

            val allSections = mutableListOf<LegislationSection>()
            var pagesCreated = 0

            // Fetch federal - limit to N Acts
            ctx.markAttempted()
            try {
                logger.info { "Fetching federal legislation..." }
                val federalItems = fetchFederalLegislation(ctx).take(limitPerJurisdiction)

                for (item in federalItems) {
                    ctx.markAttempted()
                    try {
                        delay(requestDelay)
                        val sections = fetchActSections(item)
                        allSections.addAll(sections)
                        ctx.markFetched()
                        logger.info { "Fetched ${sections.size} sections from ${item.title}" }

                        // Push to BookStack
                        val created = pushActToBookStack(sections)
                        pagesCreated += created
                        ctx.markNew(created)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to process ${item.title}" }
                        ctx.markFailed()
                        ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", item.title)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch federal legislation" }
                ctx.markFailed()
                ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", "Federal")
            }

            // Store backup to filesystem
            if (allSections.isNotEmpty()) {
                storeMarkdownSections(ctx, allSections)
            }

            // Store metadata
            pgStore.storeFetchMetadata(
                source = "legal_bookstack",
                category = "australian_legislation_bookstack",
                itemCount = pagesCreated,
                fetchedAt = Clock.System.now(),
                metadata = mapOf(
                    "acts_fetched" to allSections.map { it.actTitle }.distinct().size,
                    "sections_fetched" to allSections.size,
                    "pages_created" to pagesCreated,
                    "fetched" to ctx.metrics.fetched,
                    "failed" to ctx.metrics.failed
                )
            )

            "Created $pagesCreated BookStack pages from ${allSections.map { it.actTitle }.distinct().size} Acts (${ctx.metrics.failed} failed)"
        }
    }

    /**
     * Pushes Act sections to BookStack.
     * Returns number of pages created/updated.
     */
    private fun pushActToBookStack(sections: List<LegislationSection>): Int {
        if (sections.isEmpty()) return 0

        val firstSection = sections.first()
        val jurisdiction = firstSection.jurisdiction.uppercase()
        val actTitle = firstSection.actTitle

        logger.info { "Pushing $actTitle to BookStack..." }

        try {
            // Create/get shelf for jurisdiction
            val shelfId = bookstack.getOrCreateShelf(
                name = "Australian Legislation - $jurisdiction",
                description = "Legislation from $jurisdiction"
            )

            // Create/get book for Act
            val bookDescription = buildString {
                append("${firstSection.year ?: "Unknown year"}")
                firstSection.identifier?.let { append(" - $it") }
                append("\n\n")
                append("Source: ${firstSection.actUrl}")
            }

            val bookId = bookstack.getOrCreateBook(
                shelfId = shelfId,
                name = actTitle,
                description = bookDescription
            )

            // Create/update pages for each section
            var pagesCreated = 0
            sections.forEach { section ->
                try {
                    val pageName = if (section.sectionNumber.isNotEmpty()) {
                        "Section ${section.sectionNumber}: ${section.sectionTitle}"
                    } else {
                        section.sectionTitle
                    }

                    val tags = mutableMapOf(
                        "jurisdiction" to jurisdiction.lowercase(),
                        "act" to actTitle,
                        "source_url" to section.actUrl
                    )

                    section.year?.let { tags["year"] = it }
                    section.identifier?.let { tags["identifier"] = it }
                    if (section.sectionNumber.isNotEmpty()) {
                        tags["section_number"] = section.sectionNumber
                    }

                    bookstack.createOrUpdatePage(
                        bookId = bookId,
                        name = pageName,
                        markdownContent = section.markdownContent,
                        tags = tags
                    )

                    pagesCreated++
                } catch (e: Exception) {
                    logger.error(e) { "Failed to create page for section: ${section.sectionTitle}" }
                }
            }

            logger.info { "Created/updated $pagesCreated pages for $actTitle" }
            return pagesCreated

        } catch (e: Exception) {
            logger.error(e) { "Failed to push $actTitle to BookStack" }
            throw e
        }
    }

    /**
     * Stores markdown sections to filesystem.
     */
    private fun storeMarkdownSections(ctx: FetchExecutionContext, sections: List<LegislationSection>) {
        try {
            val timestamp = Clock.System.now().epochSeconds

            // Group by Act
            val byAct = sections.groupBy { it.actTitle }

            byAct.forEach { (actTitle, actSections) ->
                // Create a safe filename from act title
                val safeFilename = actTitle
                    .replace(Regex("[^a-zA-Z0-9\\s-]"), "")
                    .replace(Regex("\\s+"), "_")
                    .take(50)

                val itemId = "act_${safeFilename}_${timestamp}"

                val data = mapOf(
                    "actTitle" to actTitle,
                    "actUrl" to actSections.first().actUrl,
                    "jurisdiction" to actSections.first().jurisdiction,
                    "year" to actSections.first().year,
                    "identifier" to actSections.first().identifier,
                    "fetchedAt" to Clock.System.now().toString(),
                    "sectionCount" to actSections.size,
                    "sections" to actSections.map { section ->
                        mapOf(
                            "sectionNumber" to section.sectionNumber,
                            "sectionTitle" to section.sectionTitle,
                            "markdownContent" to section.markdownContent
                        )
                    }
                )

                val json = gson.toJson(data)
                ctx.storage.storeRawText(itemId, json, "json")
                logger.info { "Stored ${actSections.size} sections for $actTitle to $itemId" }
            }

        } catch (e: Exception) {
            logger.error(e) { "Failed to store markdown sections" }
        }
    }

    private suspend fun fetchFederalLegislation(ctx: FetchExecutionContext): List<LegislationItem> {
        val items = mutableListOf<LegislationItem>()

        // Fetch the search page with in-force Acts using standardized HTTP client
        val url = "${config.ausLegislationUrl}search/status(InForce)/collection(Act)"
        val response = ctx.http.get(url)
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code}")
        }
        val html = response.body?.string()
        response.close()
        if (html == null) {
            throw Exception("Empty response")
        }
        val doc = Jsoup.parse(html, url)

        // Try to find CSV download link for bulk data
        val csvLink = doc.select("a[href*='csv'], a[href*='download']").firstOrNull()?.attr("abs:href")
        if (csvLink != null && csvLink.contains("csv")) {
            logger.info { "Found CSV export link: $csvLink" }
            // Note: CSV parsing would go here, but for now we'll scrape HTML
        }

        // Parse legislation listings from the page
        // The federal site uses a structured listing format
        val listings = doc.select(".result-item, .search-result, article, .legislation-item")

        if (listings.isEmpty()) {
            // Fallback: try to find links to legislation
            logger.warn { "No structured listings found, trying fallback parsing" }
            val links = doc.select("a[href*='/Details/'], a[href*='/C20']")

            for (link in links.take(100)) { // Limit to first 100 to avoid overwhelming
                val title = link.text().trim()
                val href = link.attr("abs:href")

                if (title.isNotEmpty() && href.isNotEmpty()) {
                    val item = LegislationItem(
                        title = title,
                        jurisdiction = "federal",
                        year = extractYear(title),
                        type = "Act",
                        identifier = extractIdentifier(href),
                        url = href,
                        status = "In force",
                        registrationDate = null
                    )
                    items.add(item)
                    storeLegislationItem(ctx, item)
                }
            }
        } else {
            // Parse structured listings
            for (listing in listings.take(200)) { // Limit to first 200 items
                try {
                    val titleElem = listing.select("h2, h3, .title, .result-title").firstOrNull()
                    val title = titleElem?.text()?.trim() ?: continue

                    val linkElem = titleElem.select("a").firstOrNull() ?: listing.select("a").firstOrNull()
                    val url = linkElem?.attr("abs:href") ?: ""

                    val metaText = listing.select(".metadata, .result-metadata, dl, .details").text()
                    val status = extractStatus(metaText) ?: "In force"
                    val identifier = extractIdentifier(metaText) ?: extractIdentifier(url)
                    val year = extractYear(title) ?: extractYear(metaText)
                    val regDate = extractDate(metaText)

                    val item = LegislationItem(
                        title = title,
                        jurisdiction = "federal",
                        year = year,
                        type = "Act",
                        identifier = identifier,
                        url = url,
                        status = status,
                        registrationDate = regDate
                    )
                    items.add(item)
                    storeLegislationItem(ctx, item)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse listing item" }
                }
            }
        }

        logger.info { "Parsed ${items.size} federal legislation items" }
        return items
    }

    private suspend fun fetchStateLegislation(ctx: FetchExecutionContext, state: String, baseUrl: String): List<LegislationItem> {
        val items = mutableListOf<LegislationItem>()

        // Different states have different URL structures
        val searchUrls = when (state.lowercase()) {
            "nsw" -> listOf("${baseUrl}browse/inforce/", "${baseUrl}view/whole/html/inforce/current/")
            "vic" -> listOf("${baseUrl}in-force/acts", baseUrl)
            "qld" -> listOf("${baseUrl}browse/inforce", baseUrl)
            "wa" -> listOf("${baseUrl}legislation/statutes.nsf/main_mrtitle_", baseUrl)
            "sa" -> listOf("${baseUrl}browse/", baseUrl)
            "tas" -> listOf("${baseUrl}tocview/index.w3p;cond=;doc_id=;rec=", baseUrl)
            "act" -> listOf("${baseUrl}a", baseUrl)
            "nt" -> listOf("${baseUrl}legislation/", baseUrl)
            else -> listOf(baseUrl)
        }

        for (searchUrl in searchUrls.take(1)) { // Try first URL for each state
            try {
                val response = ctx.http.get(searchUrl)
                if (!response.isSuccessful) {
                    response.close()
                    continue
                }
                val html = response.body?.string()
                response.close()
                if (html == null) {
                    continue
                }
                val doc = Jsoup.parse(html, searchUrl)

                // Look for legislation links with various patterns
                val linkSelectors = listOf(
                    "a[href*='/act-'], a[href*='/Act-']",  // Common pattern
                    "a[href*='view/html'], a[href*='view/whole']",  // NSW
                    "a[href*='inforce/'], a[href*='in-force/']",  // General
                    "a.legislation-link, a.act-link",  // Class-based
                    "ul.legislation-list a, table.legislation a"  // List/table based
                )

                val links = doc.select(linkSelectors.joinToString(", "))

                for (link in links.take(100)) { // Limit per state
                    val title = link.text().trim()
                    val href = link.attr("abs:href")

                    // Filter out navigation and non-legislation links
                    if (title.isEmpty() ||
                        title.length < 10 ||
                        href.isEmpty() ||
                        title.lowercase().contains("home") ||
                        title.lowercase().contains("search") ||
                        title.lowercase().contains("browse")) {
                        continue
                    }

                    val item = LegislationItem(
                        title = title,
                        jurisdiction = state.uppercase(),
                        year = extractYear(title),
                        type = "Act",
                        identifier = null,
                        url = href,
                        status = "In force",
                        registrationDate = null
                    )
                    items.add(item)
                    storeLegislationItem(ctx, item)
                }

                // If we found items, break (don't try other URLs)
                if (items.isNotEmpty()) {
                    break
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch from $searchUrl" }
            }
        }

        logger.info { "Parsed ${items.size} ${state.uppercase()} legislation items" }
        return items
    }

    private fun fetchPage(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code} for $url")
            }

            val html = response.body?.string() ?: throw Exception("Empty response from $url")
            return Jsoup.parse(html, url)
        }
    }

    /**
     * Stores individual legislation item with content-based dedupe
     */
    private fun storeLegislationItem(ctx: FetchExecutionContext, item: LegislationItem) {
        try {
            val itemJson = gson.toJson(item)
            val contentHash = org.datamancy.datafetcher.storage.ContentHasher.hashJson(itemJson)

            // Use URL hash as deterministic ID
            val itemId = "leg_${item.url.hashCode()}"

            when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
                org.datamancy.datafetcher.storage.DedupeResult.NEW -> {
                    ctx.storage.storeRawText(itemId, itemJson, "json")
                    ctx.markNew()
                    ctx.markFetched()
                }
                org.datamancy.datafetcher.storage.DedupeResult.UPDATED -> {
                    ctx.storage.storeRawText(itemId, itemJson, "json")
                    ctx.markUpdated()
                    ctx.markFetched()
                }
                org.datamancy.datafetcher.storage.DedupeResult.UNCHANGED -> {
                    ctx.markSkipped()
                }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to store legislation item: ${item.title}" }
            ctx.recordError("STORAGE_ERROR", e.message ?: "Unknown error", item.url)
        }
    }

    private fun extractYear(text: String): String? {
        // Look for 4-digit year (1900-2099)
        val yearRegex = """\b(19|20)\d{2}\b""".toRegex()
        return yearRegex.find(text)?.value
    }

    private fun extractIdentifier(text: String): String? {
        // Look for patterns like C2004A00467, Act 2020-123, etc.
        val patterns = listOf(
            """C\d{4}[A-Z]\d+""".toRegex(),  // Federal pattern
            """(?:Act|No\.?)\s*\d{4}[-/]\d+""".toRegex(),  // State patterns
            """[A-Z]{2,4}-\d+-\d+""".toRegex()  // Generic pattern
        )

        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.value
            }
        }
        return null
    }

    private fun extractStatus(text: String): String? {
        val statusKeywords = listOf("In force", "Not in force", "Repealed", "Amended")
        for (keyword in statusKeywords) {
            if (text.contains(keyword, ignoreCase = true)) {
                return keyword
            }
        }
        return null
    }

    private fun extractDate(text: String): String? {
        // Look for dates in various formats
        val datePatterns = listOf(
            """\d{1,2}/\d{1,2}/\d{4}""".toRegex(),
            """\d{4}-\d{2}-\d{2}""".toRegex(),
            """\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{4}""".toRegex()
        )

        for (pattern in datePatterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.value
            }
        }
        return null
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying legal document sources..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check federal legislation portal
        checks.add(
            DryRunUtils.checkUrl(
                config.ausLegislationUrl,
                "Australian Federal Legislation"
            )
        )

        // Check each state legislation portal
        config.stateUrls.forEach { (state, url) ->
            checks.add(
                DryRunUtils.checkUrl(url, "AU ${state.uppercase()} Legislation")
            )
        }

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/legal", "Legal documents directory"))

        return DryRunResult(checks)
    }
}
