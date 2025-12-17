package org.datamancy.datafetcher.fetchers

import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.config.RssConfig
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.FileSystemStore
import org.datamancy.datafetcher.storage.PostgresStore
import java.net.URI

private val logger = KotlinLogging.logger {}

class RssFetcher(private val config: RssConfig) : Fetcher {
    private val fsStore = FileSystemStore()
    private val pgStore = PostgresStore()

    override suspend fun fetch(): FetchResult {
        logger.info { "Fetching ${config.feeds.size} RSS feeds..." }
        var totalItems = 0
        val errors = mutableListOf<String>()

        config.feeds.forEach { feed ->
            try {
                logger.debug { "Fetching RSS feed: ${feed.url}" }
                val syndFeed = SyndFeedInput().build(XmlReader(URI(feed.url).toURL()))

                val items = syndFeed.entries?.map { entry ->
                    mapOf<String, Any>(
                        "title" to (entry.title ?: ""),
                        "link" to (entry.link ?: ""),
                        "description" to (entry.description?.value ?: ""),
                        "publishedDate" to (entry.publishedDate?.toInstant()?.toString() ?: ""),
                        "author" to (entry.author ?: ""),
                        "categories" to (entry.categories?.map { it.name } ?: emptyList<String>())
                    )
                } ?: emptyList()

                totalItems += items.size

                // Store raw feed data
                val filename = "${feed.category}_${Clock.System.now().epochSeconds}.json"
                val json = com.google.gson.Gson().toJson(mapOf(
                    "feedUrl" to feed.url,
                    "feedTitle" to syndFeed.title,
                    "fetchedAt" to Clock.System.now().toString(),
                    "items" to items
                ))
                fsStore.storeRawText("rss", filename, json)

                // Store metadata
                pgStore.storeFetchMetadata(
                    source = "rss",
                    category = feed.category,
                    itemCount = items.size,
                    fetchedAt = Clock.System.now(),
                    metadata = mapOf(
                        "feedUrl" to feed.url,
                        "feedTitle" to (syndFeed.title ?: "")
                    )
                )

                logger.info { "Fetched ${items.size} items from ${feed.category}" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch RSS feed: ${feed.url}" }
                errors.add("${feed.url}: ${e.message}")
            }
        }

        return if (errors.isEmpty()) {
            FetchResult.Success("Fetched $totalItems items from ${config.feeds.size} feeds", totalItems)
        } else {
            FetchResult.Error("Fetched $totalItems items with ${errors.size} errors: ${errors.joinToString("; ")}")
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
