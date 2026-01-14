package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.PostgresStore
import org.jsoup.Jsoup
import java.net.URI

private val logger = KotlinLogging.logger {}
private val gson = Gson()

class DocsFetcher(private val config: DocsConfig) : Fetcher {
    private val pgStore = PostgresStore()
    private val crawlDepth = 2  // Limit depth to avoid over-fetching

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("docs", version = "2.0.0") { ctx ->
            logger.info { "Fetching documentation from ${config.sources.size} sources..." }

            if (config.sources.isEmpty()) {
                logger.warn { "No documentation sources configured" }
                return@execute "No sources configured"
            }

            // Load visited URLs from checkpoint to resume crawling (per-run state)
            val visitedUrls = mutableSetOf<String>()
            val checkpointData = ctx.checkpoint.get("visited_urls")
            if (checkpointData != null) {
                visitedUrls.addAll(checkpointData.split(",").filter { it.isNotBlank() })
                logger.info { "Loaded ${visitedUrls.size} previously visited URLs from checkpoint" }
            }

            config.sources.forEach { source ->
                ctx.markAttempted()
                try {
                    crawlDocsSite(ctx, source, visitedUrls)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch docs from: ${source.url}" }
                    ctx.markFailed()
                    ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", source.url)
                }
            }

            // Save visited URLs frontier
            ctx.checkpoint.set("visited_urls", visitedUrls.joinToString(","))

            pgStore.storeFetchMetadata(
                source = "docs",
                category = "technical_documentation",
                itemCount = ctx.metrics.new + ctx.metrics.updated,
                fetchedAt = Clock.System.now(),
                metadata = mapOf(
                    "sources" to config.sources.size,
                    "visited_urls" to visitedUrls.size
                )
            )

            "Processed ${ctx.metrics.attempted} pages: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${ctx.metrics.skipped} skipped"
        }
    }

    private suspend fun crawlDocsSite(ctx: FetchExecutionContext, source: DocsSource, visitedUrls: MutableSet<String>, depth: Int = 0) {
        if (depth > crawlDepth) {
            return
        }

        val normalizedUrl = normalizeUrl(source.url)
        if (visitedUrls.contains(normalizedUrl)) {
            ctx.markSkipped()
            return
        }

        logger.info { "Crawling: ${source.url} (depth: $depth)" }

        val response = ctx.http.get(source.url)
        if (!response.isSuccessful) {
            response.close()
            throw Exception("HTTP ${response.code}")
        }

        val html = response.body?.string()
        response.close()

        if (html == null) {
            throw Exception("Empty response")
        }

        visitedUrls.add(normalizedUrl)

        // Parse HTML
        val doc = Jsoup.parse(html, source.url)

        // Extract main content (try various selectors for different doc sites)
        val contentElement = doc.select(
            "main, " +
            "article, " +
            ".content, " +
            ".documentation, " +
            ".doc-content, " +
            "#content, " +
            ".main-content"
        ).firstOrNull() ?: doc.body()

        val title = doc.title()
        val textContent = contentElement.text()

        // Create document metadata
        val docData = mapOf(
            "url" to source.url,
            "title" to title,
            "category" to source.category,
            "sourceName" to source.name,
            "textContent" to textContent,
            "htmlContent" to contentElement.html(),
            "fetchedAt" to Clock.System.now().toString()
        )

        val docJson = gson.toJson(docData)
        val contentHash = ContentHasher.hashJson(docJson)

        val itemId = normalizedUrl.hashCode().toString()

        when (ctx.dedupe.shouldUpsert(itemId, contentHash)) {
            DedupeResult.NEW -> {
                ctx.storage.storeRawText(itemId, docJson, "json")
                ctx.markNew()
                ctx.markFetched()
            }
            DedupeResult.UPDATED -> {
                ctx.storage.storeRawText(itemId, docJson, "json")
                ctx.markUpdated()
                ctx.markFetched()
            }
            DedupeResult.UNCHANGED -> {
                ctx.markSkipped()
            }
        }

        // Extract links for frontier expansion (polite crawling within same domain)
        if (depth < crawlDepth) {
            val baseUri = URI(source.url)
            val links = contentElement.select("a[href]")
                .mapNotNull { it.attr("abs:href") }
                .filter { link ->
                    val linkUri = try { URI(link) } catch (e: Exception) { null }
                    linkUri != null && linkUri.host == baseUri.host && !visitedUrls.contains(normalizeUrl(link))
                }
                .take(10) // Limit frontier expansion per page

            links.forEach { link ->
                try {
                    crawlDocsSite(ctx, source.copy(url = link), visitedUrls, depth + 1)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to crawl link: $link" }
                }
            }
        }

        logger.info { "Docs: ${source.name} - $title" }
    }

    private fun normalizeUrl(url: String): String {
        return try {
            val uri = URI(url)
            val path = uri.path?.removeSuffix("/") ?: ""
            "${uri.scheme}://${uri.host}$path"
        } catch (e: Exception) {
            url
        }
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying documentation sources..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check each documentation source URL
        config.sources.forEach { source ->
            checks.add(DryRunUtils.checkUrl(source.url, "Docs: ${source.name}"))
        }

        // Check filesystem directory
        checks.add(DryRunUtils.checkDirectory("/app/data/docs", "Documentation directory"))

        // Check if at least one source is configured
        checks.add(
            DryRunUtils.checkConfig(
                if (config.sources.isEmpty()) null else "${config.sources.size} sources",
                "Documentation sources",
                required = true
            )
        )

        return DryRunResult(checks)
    }
}

data class DocsConfig(
    val sources: List<DocsSource> = listOf(
        DocsSource("linux_kernel", "https://www.kernel.org/doc/html/latest/", "linux"),
        DocsSource("debian_docs", "https://www.debian.org/doc/", "debian"),
        DocsSource("kotlin_docs", "https://kotlinlang.org/docs/", "programming")
    )
)

data class DocsSource(
    val name: String,
    val url: String,
    val category: String
)
