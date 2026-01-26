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
import org.datamancy.pipeline.sources.LinuxDoc
import org.datamancy.pipeline.sources.LinuxDocsSource

/**
 * Chunkable wrapper for Linux documentation
 */
data class LinuxDocChunkable(val doc: LinuxDoc) : Chunkable {
    override fun toText(): String = doc.toText()
    override fun getId(): String = doc.id
    override fun getMetadata(): Map<String, String> = mapOf(
        "title" to doc.title,
        "type" to doc.type,
        "section" to doc.section,
        "path" to doc.path
    )
}

/**
 * Standardized Linux Docs source with chunking and scheduling
 */
class LinuxDocsStandardizedSource(
    private val sources: List<LinuxDocsSource.DocSource> = listOf(
        LinuxDocsSource.DocSource.MAN_PAGES,
        LinuxDocsSource.DocSource.DEBIAN_DOCS,
        LinuxDocsSource.DocSource.KERNEL_DOCS
    ),
    private val maxDocs: Int = Int.MAX_VALUE
) : StandardizedSource<LinuxDocChunkable> {
    override val name = "linux_docs"

    override fun resyncStrategy() = ResyncStrategy.Weekly(dayOfWeek = 0, hour = 3, minute = 0)

    override fun backfillStrategy() = BackfillStrategy.FilesystemScan(
        paths = listOf("/usr/share/man", "/usr/share/doc", "/usr/src/linux/Documentation")
    )

    override fun needsChunking() = true

    override fun chunker() = Chunker.forEmbeddingModel(tokenLimit = 512, overlapPercent = 0.20)

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<LinuxDocChunkable> {
        // For Linux docs, we scan the filesystem
        // Initial pull: scan all docs
        // Resync: scan all docs (filesystem changes are infrequent)

        val source = LinuxDocsSource(
            sources = sources,
            maxDocs = maxDocs
        )

        return source.fetch().map { LinuxDocChunkable(it) }
    }
}
