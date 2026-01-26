package org.datamancy.pipeline.sources.standardized

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.datamancy.pipeline.core.Chunkable
import org.datamancy.pipeline.core.StandardizedSource
import org.datamancy.pipeline.processors.Chunker
import org.datamancy.pipeline.scheduling.BackfillStrategy
import org.datamancy.pipeline.scheduling.ResyncStrategy
import org.datamancy.pipeline.scheduling.RunMetadata
import org.datamancy.pipeline.scheduling.RunType
import org.datamancy.pipeline.sources.WikiPage
import org.datamancy.pipeline.sources.WikiSource

/**
 * Chunkable wrapper for Wiki pages
 */
data class WikiPageChunkable(val page: WikiPage) : Chunkable {
    override fun toText(): String = page.toText()
    override fun getId(): String = page.id
    override fun getMetadata(): Map<String, String> = mapOf(
        "title" to page.title,
        "wikiType" to page.wikiType,
        "url" to page.url,
        "categories" to page.categories.joinToString(", ")
    )
}

/**
 * Standardized Debian Wiki source with chunking and scheduling
 */
class DebianWikiStandardizedSource(
    private val maxPages: Int = 500,
    private val categories: List<String> = emptyList()
) : StandardizedSource<WikiPageChunkable> {
    override val name = "debian_wiki"

    override fun resyncStrategy() = ResyncStrategy.DailyAt(hour = 4, minute = 0)

    override fun backfillStrategy() = BackfillStrategy.WikiDumpAndWatch(
        dumpUrl = "https://wiki.debian.org/RecentChanges",
        recentChangesLimit = 500
    )

    override fun needsChunking() = true

    override fun chunker() = Chunker.forEmbeddingModel(tokenLimit = 8192, overlapPercent = 0.20)

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<WikiPageChunkable> {
        // For Debian Wiki, we fetch from recent changes or categories
        // Initial pull: fetch first 500 pages
        // Resync: fetch recent changes only

        val pageLimit = when (metadata.runType) {
            RunType.INITIAL_PULL -> maxPages
            RunType.RESYNC -> 100  // Only recent changes
        }

        val source = WikiSource(
            wikiType = WikiSource.WikiType.DEBIAN,
            maxPages = pageLimit,
            categories = categories
        )

        return source.fetch().map { WikiPageChunkable(it) }
    }
}
