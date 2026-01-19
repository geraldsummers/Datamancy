package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.config.RssConfig
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.PostgresStore
import java.net.URI

private val logger = KotlinLogging.logger {}
private val gson = Gson()

class RssFetcher(private val config: RssConfig) : Fetcher {
    private val pgStore = PostgresStore()

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("rss_feeds", version = "2.0.0") { ctx ->
            logger.info { "Fetching ${config.feeds.size} RSS feeds..." }

            config.feeds.forEach { feed ->
                try {
                    logger.debug { "Fetching RSS feed: ${feed.url}" }

                    // Use standardized HTTP client with retry/backoff
                    val response = ctx.http.get(feed.url)

                    if (!response.isSuccessful) {
                        logger.warn { "HTTP ${response.code} for ${feed.url}" }
                        ctx.recordError("HTTP_ERROR", "HTTP ${response.code}", feed.url)
                        response.close()
                        return@forEach
                    }

                    val responseBody = response.body
                    if (responseBody == null) {
                        ctx.recordError("EMPTY_RESPONSE", "Empty response", feed.url)
                        response.close()
                        return@forEach
                    }

                    // Parse RSS feed using XmlReader which handles encoding/BOM/gzip automatically
                    val syndFeed = try {
                        val xmlReader = XmlReader(responseBody.byteStream())
                        SyndFeedInput().build(xmlReader)
                    } finally {
                        response.close()
                    }

                    // Process each entry individually with dedupe
                    syndFeed.entries?.forEach { entry ->
                        ctx.markAttempted()

                        try {
                            // Create deterministic item ID from GUID or link
                            val itemId = entry.uri ?: entry.link
                            if (itemId == null) {
                                ctx.markSkipped()
                                return@forEach
                            }
                            // Use absolute value to avoid negative hashcodes which cause filesystem errors
                            val safeItemId = kotlin.math.abs(itemId.hashCode()).toString()

                            // Build normalized entry data
                            val entryData = mapOf(
                                "guid" to (entry.uri ?: ""),
                                "title" to (entry.title ?: ""),
                                "link" to (entry.link ?: ""),
                                "description" to (entry.description?.value ?: ""),
                                "publishedDate" to (entry.publishedDate?.toInstant()?.toString() ?: ""),
                                "author" to (entry.author ?: ""),
                                "categories" to (entry.categories?.map { it.name } ?: emptyList<String>()),
                                "feedUrl" to feed.url,
                                "feedCategory" to feed.category
                            )

                            // Compute content hash for dedupe
                            val contentJson = gson.toJson(entryData)
                            val contentHash = ContentHasher.hashJson(contentJson)

                            // Dedupe check
                            when (ctx.dedupe.shouldUpsert(safeItemId, contentHash)) {
                                DedupeResult.NEW -> {
                                    // Store raw entry data
                                    ctx.storage.storeRawText(
                                        itemId = safeItemId,
                                        content = contentJson,
                                        extension = "json"
                                    )

                                    // Store metadata
                                    pgStore.storeFetchMetadata(
                                        source = "rss",
                                        category = feed.category,
                                        itemCount = 1,
                                        fetchedAt = Clock.System.now(),
                                        metadata = mapOf(
                                            "feedUrl" to feed.url,
                                            "itemId" to safeItemId,
                                            "title" to entryData["title"].toString()
                                        )
                                    )

                                    ctx.markNew()
                                    ctx.markFetched()
                                }
                                DedupeResult.UPDATED -> {
                                    // Content changed, update
                                    ctx.storage.storeRawText(
                                        itemId = safeItemId,
                                        content = contentJson,
                                        extension = "json"
                                    )
                                    ctx.markUpdated()
                                    ctx.markFetched()
                                }
                                DedupeResult.UNCHANGED -> {
                                    ctx.markSkipped()
                                }
                            }

                        } catch (e: Exception) {
                            logger.error(e) { "Failed to process RSS entry: ${entry.link}" }
                            ctx.markFailed()
                            ctx.recordError("ENTRY_PROCESSING_ERROR", e.message ?: "Unknown error", entry.link)
                        }
                    }

                    logger.info { "Processed feed ${feed.category}: ${syndFeed.entries?.size ?: 0} entries" }

                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch RSS feed: ${feed.url}" }
                    ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", feed.url)
                }
            }

            // Update checkpoint
            ctx.checkpoint.set("last_fetch_time", Clock.System.now().toString())

            "Processed ${ctx.metrics.attempted} entries: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${ctx.metrics.skipped} skipped (${ctx.metrics.failed} failed)"
        }
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying ${config.feeds.size} RSS feeds..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/rss", "RSS data directory"))

        // Check each RSS feed URL
        config.feeds.forEach { feed ->
            checks.add(DryRunUtils.checkUrl(feed.url, "RSS feed: ${feed.category}"))
        }

        // Check database connection
        val pgHost = System.getenv("POSTGRES_HOST") ?: "postgres"
        val pgPort = System.getenv("POSTGRES_PORT")?.toIntOrNull() ?: 5432
        val pgDb = System.getenv("POSTGRES_DB") ?: "datamancy"
        val pgUser = System.getenv("POSTGRES_USER") ?: "datamancer"
        val pgPass = System.getenv("POSTGRES_PASSWORD") ?: ""
        checks.add(
            DryRunUtils.checkDatabase(
                "jdbc:postgresql://$pgHost:$pgPort/$pgDb",
                pgUser,
                pgPass,
                "PostgreSQL (metadata)"
            )
        )

        return DryRunResult(checks)
    }
}
