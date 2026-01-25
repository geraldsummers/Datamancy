package org.datamancy.pipeline.sources

import com.rometools.rome.io.SyndFeedInput
import com.rometools.rome.io.XmlReader
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.datamancy.pipeline.core.Source
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Fetches articles from RSS/Atom feeds
 */
class RssSource(
    private val feedUrls: List<String>
) : Source<RssArticle> {
    override val name = "RssSource"

    // Track internet IO
    private val bytesDownloaded = AtomicLong(0)
    private val feedsFetched = AtomicLong(0)

    override suspend fun fetch(): Flow<RssArticle> = flow {
        feedUrls.forEach { feedUrl ->
            try {
                logger.info { "Fetching RSS feed: $feedUrl" }
                val feedInput = SyndFeedInput().apply { isAllowDoctypes = false }

                // Sanitize XML to handle malformed DOCTYPE declarations (e.g., arXiv feeds)
                val content = URL(feedUrl).readText()
                bytesDownloaded.addAndGet(content.length.toLong())
                feedsFetched.incrementAndGet()

                val sanitized = content.replace(Regex("<!DOCTYPE[^>]*>"), "").trim()
                val feed = feedInput.build(java.io.StringReader(sanitized))

                feed.entries.forEach { entry ->
                    val article = RssArticle(
                        guid = entry.uri ?: entry.link ?: "${feed.title}-${entry.title}".hashCode().toString(),
                        title = entry.title ?: "Untitled",
                        link = entry.link ?: "",
                        description = entry.description?.value ?: "",
                        content = entry.contents?.firstOrNull()?.value ?: entry.description?.value ?: "",
                        publishedDate = entry.publishedDate?.toInstant()?.toString() ?: "",
                        author = entry.author ?: "",
                        feedTitle = feed.title ?: "Unknown",
                        feedUrl = feedUrl,
                        categories = entry.categories?.map { it.name } ?: emptyList()
                    )
                    emit(article)
                }

                logger.info { "Fetched ${feed.entries.size} articles from $feedUrl" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch RSS feed $feedUrl: ${e.message}" }
            }
        }
    }

    fun getIOStats(): IOStats {
        return IOStats(
            bytesDownloaded = bytesDownloaded.get(),
            feedsFetched = feedsFetched.get()
        )
    }

    fun resetStats() {
        bytesDownloaded.set(0)
        feedsFetched.set(0)
    }
}

data class IOStats(
    val bytesDownloaded: Long,
    val feedsFetched: Long
) {
    fun formatBytes(): String {
        val kb = bytesDownloaded / 1024.0
        val mb = kb / 1024.0
        return when {
            mb >= 1.0 -> "%.2f MB".format(mb)
            kb >= 1.0 -> "%.2f KB".format(kb)
            else -> "$bytesDownloaded bytes"
        }
    }
}

data class RssArticle(
    val guid: String,
    val title: String,
    val link: String,
    val description: String,
    val content: String,
    val publishedDate: String,
    val author: String,
    val feedTitle: String,
    val feedUrl: String,
    val categories: List<String>
) {
    fun toText(): String {
        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("**Source:** $feedTitle")
            appendLine("**Published:** $publishedDate")
            if (author.isNotBlank()) {
                appendLine("**Author:** $author")
            }
            if (categories.isNotEmpty()) {
                appendLine("**Categories:** ${categories.joinToString(", ")}")
            }
            appendLine()
            appendLine(content.ifBlank { description })
            appendLine()
            appendLine("**Read more:** $link")
        }
    }
}
