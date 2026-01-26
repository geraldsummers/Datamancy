package org.datamancy.pipeline.sources.standardized

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.datamancy.pipeline.core.Chunkable
import org.datamancy.pipeline.core.StandardizedSource
import org.datamancy.pipeline.scheduling.BackfillStrategy
import org.datamancy.pipeline.scheduling.ResyncStrategy
import org.datamancy.pipeline.scheduling.RunMetadata
import org.datamancy.pipeline.sources.WikipediaArticle
import org.datamancy.pipeline.sources.WikipediaSource

/**
 * STANDARDIZED Wikipedia Source
 *
 * - Daily resync at 1am
 * - Full dump on initial pull
 * - Re-stream dump on resync (Wikipedia doesn't have good incremental API)
 * - Chunking REQUIRED (articles are long)
 */
class WikipediaStandardizedSource(
    private val dumpUrl: String,
    private val maxArticles: Int = Int.MAX_VALUE
) : StandardizedSource<WikipediaChunkableArticle> {

    override val name = "wikipedia"

    override fun resyncStrategy(): ResyncStrategy {
        // Large source - check daily at 1am
        return ResyncStrategy.DailyAt(hour = 1, minute = 0)
    }

    override fun backfillStrategy(): BackfillStrategy {
        return BackfillStrategy.WikipediaDump(
            dumpUrl = dumpUrl,
            maxArticles = maxArticles
        )
    }

    override fun needsChunking(): Boolean {
        // Wikipedia articles are LONG - chunking is REQUIRED
        return true
    }

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<WikipediaChunkableArticle> {
        // Wikipedia doesn't have a good incremental API
        // Always stream the full dump (but Qdrant dedup will skip unchanged articles)
        return WikipediaSource(
            dumpPath = dumpUrl,
            maxArticles = maxArticles,
            maxChunkSize = 10000,  // Large size, we'll chunk properly with Chunker
            chunkOverlap = 0  // No overlap here, Chunker handles it
        ).fetch()
            .map { article -> WikipediaChunkableArticle(article) }
    }
}

/**
 * Wrapper to make WikipediaArticle implement Chunkable
 */
data class WikipediaChunkableArticle(val article: WikipediaArticle) : Chunkable {
    override fun toText(): String = article.text

    override fun getId(): String = article.originalArticleId

    override fun getMetadata(): Map<String, String> = mapOf(
        "title" to article.title,
        "wikipedia_id" to article.id,
        "source" to "wikipedia"
    )
}
