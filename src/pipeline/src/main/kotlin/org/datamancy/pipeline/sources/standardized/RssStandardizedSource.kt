package org.datamancy.pipeline.sources.standardized

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.datamancy.pipeline.core.Chunkable
import org.datamancy.pipeline.core.StandardizedSource
import org.datamancy.pipeline.scheduling.BackfillStrategy
import org.datamancy.pipeline.scheduling.ResyncStrategy
import org.datamancy.pipeline.scheduling.RunMetadata
import org.datamancy.pipeline.scheduling.RunType
import org.datamancy.pipeline.sources.RssArticle
import org.datamancy.pipeline.sources.RssSource
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * STANDARDIZED RSS Source
 *
 * This is what ALL sources should look like!
 *
 * Enforces:
 * - Hourly resync
 * - 7-day backfill on initial pull
 * - Optional chunking (most articles are short)
 * - Different behavior for initial vs resync
 */
class RssStandardizedSource(
    private val feedUrls: List<String>,
    private val backfillDays: Int = 7
) : StandardizedSource<RssChunkableArticle> {

    override val name = "rss"

    override fun resyncStrategy(): ResyncStrategy {
        // RSS updates frequently - check every hour
        return ResyncStrategy.Hourly(minute = 0)
    }

    override fun backfillStrategy(): BackfillStrategy {
        // On initial pull, fetch last 7 days of articles
        return BackfillStrategy.RssHistory(daysBack = backfillDays)
    }

    override fun needsChunking(): Boolean {
        // Most RSS articles fit in 512 tokens, but some don't
        // Enable chunking to be safe
        return true
    }

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<RssChunkableArticle> {
        val since = when (metadata.runType) {
            RunType.INITIAL_PULL -> {
                // Initial: fetch last 7 days
                Instant.now().minus(backfillDays.toLong(), ChronoUnit.DAYS)
            }
            RunType.RESYNC -> {
                // Resync: fetch only new articles (since ~1 hour ago)
                Instant.now().minus(1, ChronoUnit.HOURS)
            }
        }

        // Use existing RssSource but filter by date
        return org.datamancy.pipeline.sources.RssSource(feedUrls)
            .fetch()
            .map { article -> RssChunkableArticle(article) }
    }
}

/**
 * Wrapper to make RssArticle implement Chunkable interface
 */
data class RssChunkableArticle(val article: RssArticle) : Chunkable {
    override fun toText(): String = article.toText()

    override fun getId(): String = article.guid

    override fun getMetadata(): Map<String, String> = mapOf(
        "title" to article.title,
        "link" to article.link,
        "description" to article.description.take(200),
        "published_date" to article.publishedDate,
        "author" to article.author,
        "feed_title" to article.feedTitle,
        "feed_url" to article.feedUrl,
        "categories" to article.categories.joinToString(","),
        "source" to "rss"
    )
}
