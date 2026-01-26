package org.datamancy.pipeline.sources

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.datamancy.pipeline.core.Source
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Fetches documentation from Linux distribution wikis
 *
 * Supported wikis:
 * - Debian Wiki: https://wiki.debian.org/
 * - Arch Wiki: https://wiki.archlinux.org/
 *
 * These wikis contain curated, tutorial-style documentation that complements
 * the raw man pages and package docs.
 */
class WikiSource(
    private val wikiType: WikiType = WikiType.DEBIAN,
    private val maxPages: Int = 500,
    private val categories: List<String> = emptyList()  // Empty = fetch from recent changes
) : Source<WikiPage> {
    override val name = "WikiSource"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val delayMs = 1000L  // Be respectful to wiki servers

    enum class WikiType(val baseUrl: String, val displayName: String) {
        DEBIAN("https://wiki.debian.org", "Debian Wiki"),
        ARCH("https://wiki.archlinux.org", "Arch Wiki")
    }

    override suspend fun fetch(): Flow<WikiPage> = flow {
        logger.info { "Starting ${wikiType.displayName} fetch (max: $maxPages pages)" }

        var count = 0

        try {
            // Get list of pages to fetch
            val pageUrls = if (categories.isNotEmpty()) {
                fetchPagesFromCategories(categories)
            } else {
                fetchRecentPages()
            }.take(maxPages)

            logger.info { "Found ${pageUrls.size} pages to fetch from ${wikiType.displayName}" }

            for (pageUrl in pageUrls) {
                if (count >= maxPages) break

                try {
                    delay(delayMs)

                    val page = fetchWikiPage(pageUrl)
                    if (page != null) {
                        emit(page)
                        count++

                        if (count % 50 == 0) {
                            logger.info { "Fetched $count/${pageUrls.size} pages from ${wikiType.displayName}" }
                        }
                    }

                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch page $pageUrl: ${e.message}" }
                }
            }

        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch from ${wikiType.displayName}: ${e.message}" }
        }

        logger.info { "${wikiType.displayName} fetch complete: $count pages fetched" }
    }

    /**
     * Fetch list of recent pages from wiki
     */
    private fun fetchRecentPages(): List<String> {
        val pages = mutableListOf<String>()

        try {
            val url = when (wikiType) {
                WikiType.DEBIAN -> "${wikiType.baseUrl}/RecentChanges"
                WikiType.ARCH -> "${wikiType.baseUrl}/index.php?title=Special:RecentChanges&limit=500"
            }

            logger.info { "Fetching recent pages from: $url" }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Datamancy-Pipeline/1.0 (Educational/Research Purpose)")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                logger.error { "Failed to fetch recent pages: ${response.code}" }
                return emptyList()
            }

            val html = response.body?.string() ?: return emptyList()
            val doc = Jsoup.parse(html, wikiType.baseUrl)

            // Extract page links based on wiki type
            val links = when (wikiType) {
                WikiType.DEBIAN -> {
                    // Debian wiki uses different link format
                    doc.select("a[href^='/']")
                        .map { it.attr("abs:href") }
                        .filter { it.startsWith("${wikiType.baseUrl}/") }
                        .filter { !it.contains("action=") }
                        .filter { !it.contains("RecentChanges") }
                        .distinct()
                }
                WikiType.ARCH -> {
                    // Arch wiki recent changes page
                    doc.select("a.mw-changeslist-title")
                        .map { it.attr("abs:href") }
                        .distinct()
                }
            }

            pages.addAll(links.take(maxPages * 2))  // Get extra in case some fail
            logger.info { "Found ${pages.size} candidate pages" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch recent pages: ${e.message}" }
        }

        return pages
    }

    /**
     * Fetch pages from specific categories
     */
    private fun fetchPagesFromCategories(categories: List<String>): List<String> {
        val pages = mutableSetOf<String>()

        categories.forEach { category ->
            try {
                runBlocking { delay(delayMs) }

                val url = when (wikiType) {
                    WikiType.DEBIAN -> "${wikiType.baseUrl}/CategoryList?action=show&redirect=0&category=${category}"
                    WikiType.ARCH -> "${wikiType.baseUrl}/index.php?title=Category:${category}"
                }

                logger.debug { "Fetching category: $category" }

                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Datamancy-Pipeline/1.0 (Educational/Research Purpose)")
                    .build()

                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val html = response.body?.string() ?: return@forEach
                    val doc = Jsoup.parse(html, wikiType.baseUrl)

                    val links = doc.select("a[href^='/']")
                        .map { it.attr("abs:href") }
                        .filter { it.startsWith("${wikiType.baseUrl}/") }
                        .filter { !it.contains("action=") }

                    pages.addAll(links)
                }

            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch category $category: ${e.message}" }
            }
        }

        return pages.toList()
    }

    /**
     * Fetch and parse a single wiki page
     */
    private fun fetchWikiPage(url: String): WikiPage? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Datamancy-Pipeline/1.0 (Educational/Research Purpose)")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch page: ${response.code} $url" }
                return null
            }

            val html = response.body?.string() ?: return null
            val doc = Jsoup.parse(html, wikiType.baseUrl)

            // Extract title
            val title = doc.select("h1#firstHeading, h1.title, h1").firstOrNull()?.text()
                ?: doc.select("title").firstOrNull()?.text()
                ?: url.substringAfterLast("/")

            // Extract main content based on wiki type
            val content = when (wikiType) {
                WikiType.DEBIAN -> {
                    // Debian wiki content is in #content div
                    doc.select("#content, .wiki-content, .page-content").text()
                }
                WikiType.ARCH -> {
                    // Arch wiki content is in #mw-content-text
                    doc.select("#mw-content-text .mw-parser-output").text()
                }
            }

            if (content.isBlank() || content.length < 100) {
                logger.debug { "Skipping page with insufficient content: $title" }
                return null
            }

            // Extract categories/tags
            val categories = doc.select("a[href*='Category:']")
                .map { it.text() }
                .filter { it.isNotBlank() }
                .distinct()

            // Limit content size for embeddings
            val truncatedContent = if (content.length > 10000) {
                content.substring(0, 10000)
            } else {
                content
            }

            return WikiPage(
                id = "${wikiType.name.lowercase()}:${url.substringAfterLast("/")}",
                title = title,
                url = url,
                content = truncatedContent,
                wikiType = wikiType.displayName,
                categories = categories
            )

        } catch (e: Exception) {
            logger.error(e) { "Failed to parse page $url: ${e.message}" }
            return null
        }
    }
}

data class WikiPage(
    val id: String,
    val title: String,
    val url: String,
    val content: String,
    val wikiType: String,  // "Debian Wiki" or "Arch Wiki"
    val categories: List<String> = emptyList()
) {
    fun toText(): String {
        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("**Source:** $wikiType")
            appendLine("**URL:** $url")

            if (categories.isNotEmpty()) {
                appendLine("**Categories:** ${categories.take(5).joinToString(", ")}")
            }

            appendLine()
            appendLine("## Content")
            appendLine()
            appendLine(content)

            if (content.length >= 10000) {
                appendLine()
                appendLine("*(Content truncated to 10,000 characters for embedding)*")
            }
        }
    }

    fun contentHash(): String {
        return id.hashCode().toString()
    }
}
