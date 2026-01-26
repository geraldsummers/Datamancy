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
import org.datamancy.pipeline.sources.AustralianLegalDocument
import org.datamancy.pipeline.sources.OpenAustralianLegalCorpusSource

/**
 * Chunkable wrapper for Australian legal documents from the Open Corpus
 */
data class AustralianLegalDocumentChunkable(val doc: AustralianLegalDocument) : Chunkable {
    override fun toText(): String = doc.toText()
    override fun getId(): String = doc.id
    override fun getMetadata(): Map<String, String> = mapOf(
        "type" to doc.type,
        "jurisdiction" to doc.jurisdiction,
        "date" to doc.date,
        "citation" to doc.citation,
        "source" to doc.source,
        "url" to doc.url
    )
}

/**
 * Standardized source for Open Australian Legal Corpus
 *
 * Downloads the complete corpus (~5GB, 229K documents) from HuggingFace and caches locally.
 * Subsequent runs use the cached file unless a resync is triggered.
 */
class OpenAustralianLegalCorpusStandardizedSource(
    private val cacheDir: String = "/data/australian-legal-corpus",
    private val jurisdictions: List<String>? = null,  // null = all jurisdictions
    private val documentTypes: List<String>? = null,  // null = all types
    private val maxDocuments: Int = Int.MAX_VALUE
) : StandardizedSource<AustralianLegalDocumentChunkable> {
    override val name = "australian_laws"

    // Resync monthly to get corpus updates (corpus is updated frequently)
    override fun resyncStrategy() = ResyncStrategy.Monthly(dayOfMonth = 1, hour = 2, minute = 0)

    override fun backfillStrategy() = BackfillStrategy.LegalDatabase(
        jurisdictions = jurisdictions ?: listOf("all"),
        startYear = 1900  // Corpus contains historical legislation
    )

    override fun needsChunking() = true

    override fun chunker() = Chunker.forEmbeddingModel(tokenLimit = 512, overlapPercent = 0.20)

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<AustralianLegalDocumentChunkable> {
        // For Open Corpus, initial pull and resyncs are the same:
        // Download the full corpus (or use cache) and process all documents

        val source = OpenAustralianLegalCorpusSource(
            cacheDir = cacheDir,
            filterJurisdictions = jurisdictions,
            filterTypes = documentTypes,
            maxDocuments = maxDocuments
        )

        return source.fetch().map { AustralianLegalDocumentChunkable(it) }
    }
}
