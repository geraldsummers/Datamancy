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
import org.datamancy.pipeline.sources.CveEntry
import org.datamancy.pipeline.sources.CveSource

/**
 * Chunkable wrapper for CVE entries
 */
data class CveChunkable(val entry: CveEntry) : Chunkable {
    override fun toText(): String = entry.toText()
    override fun getId(): String = entry.cveId
    override fun getMetadata(): Map<String, String> = mapOf(
        "severity" to entry.severity,
        "cvssScore" to (entry.baseScore?.toString() ?: "unknown"),
        "publishedDate" to entry.publishedDate,
        "lastModifiedDate" to entry.lastModifiedDate,
        "affectedProductsCount" to entry.affectedProducts.size.toString()
    )
}

/**
 * Standardized CVE source with chunking and scheduling
 */
class CveStandardizedSource(
    private val apiKey: String? = null,
    private val maxResults: Int = Int.MAX_VALUE
) : StandardizedSource<CveChunkable> {
    override val name = "cve"

    override fun resyncStrategy() = ResyncStrategy.DailyAt(hour = 1, minute = 0)

    override fun backfillStrategy() = BackfillStrategy.CveDatabase(modifiedSinceLastRun = true)

    override fun needsChunking() = true

    override fun chunker() = Chunker.forEmbeddingModel(tokenLimit = 8192, overlapPercent = 0.20)

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<CveChunkable> {
        // For CVE, we fetch all recently modified CVEs
        // The API supports filtering by lastModifiedStartDate/lastModifiedEndDate
        // but CveSource doesn't expose that yet - it would be added as a parameter

        val source = when (metadata.runType) {
            RunType.INITIAL_PULL -> {
                // Initial pull: get first 10,000 CVEs to establish baseline
                CveSource(apiKey = apiKey, maxResults = 10000)
            }
            RunType.RESYNC -> {
                // Resync: get recently modified CVEs (last 24 hours)
                // Would need to add modifiedSince parameter to CveSource
                CveSource(apiKey = apiKey, maxResults = 1000)
            }
        }

        return source.fetch().map { CveChunkable(it) }
    }
}
