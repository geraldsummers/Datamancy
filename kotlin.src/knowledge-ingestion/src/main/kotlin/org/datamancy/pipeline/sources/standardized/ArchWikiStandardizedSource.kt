package org.datamancy.pipeline.sources.standardized

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.datamancy.pipeline.core.Chunkable
import org.datamancy.pipeline.core.StandardizedSource
import org.datamancy.pipeline.processors.Chunker
import org.datamancy.pipeline.scheduling.BackfillStrategy
import org.datamancy.pipeline.scheduling.ResyncStrategy
import org.datamancy.pipeline.scheduling.RunMetadata
import org.datamancy.pipeline.scheduling.RunType
import org.datamancy.pipeline.sinks.BookStackDocument
import org.datamancy.pipeline.sources.MediaWikiXmlDumpParser
import org.datamancy.pipeline.sources.WikiPage
import org.datamancy.pipeline.sources.WikiSource
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}


class ArchWikiStandardizedSource(
    private val maxPages: Int = 500,
    private val categories: List<String> = emptyList(),
    private val xmlDumpPath: String? = null  
) : StandardizedSource<WikiPageChunkable> {
    override val name = "arch_wiki"

    override fun resyncStrategy() = ResyncStrategy.DailyAt(hour = 4, minute = 30)

    override fun backfillStrategy() = BackfillStrategy.WikiDumpAndWatch(
        dumpUrl = "https://archive.org/download/wiki-wikiarchlinuxorg/wikiarchlinuxorg-20200209-history.xml.7z",
        recentChangesLimit = 500
    )

    override fun needsChunking() = true

    override fun chunker() = Chunker.forEmbeddingModel(tokenLimit = 8192, overlapPercent = 0.20)

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<WikiPageChunkable> {
        return when (metadata.runType) {
            RunType.INITIAL_PULL -> {
                
                fetchFromXmlDump()
            }
            RunType.RESYNC -> {
                
                
                fetchFromXmlDump()
            }
        }
    }

    private suspend fun fetchFromXmlDump(): Flow<WikiPageChunkable> {
        val dumpPath = xmlDumpPath ?: downloadXmlDumpIfNeeded()

        val xmlSource = if (dumpPath.endsWith(".7z")) {
            MediaWikiXmlDumpParser.XmlSource.SevenZipArchive(
                archivePath = dumpPath,
                xmlFilename = "wikiarchlinuxorg-20200209-history.xml"
            )
        } else {
            MediaWikiXmlDumpParser.XmlSource.LocalFile(dumpPath)
        }

        val parser = MediaWikiXmlDumpParser(
            xmlSource = xmlSource,
            wikiBaseUrl = "https://wiki.archlinux.org",
            maxPages = maxPages
        )

        return parser.fetch().map { WikiPageChunkable(it) }
    }

    private fun downloadXmlDumpIfNeeded(): String {
        val cacheDir = File(System.getProperty("java.io.tmpdir"), "datamancy-cache")
        cacheDir.mkdirs()

        val dumpFile = File(cacheDir, "archwiki-20200209-history.xml.7z")

        if (!dumpFile.exists()) {
            logger.info { "Downloading Arch Wiki XML dump (117 MB)..." }

            val url = "https://archive.org/download/wiki-wikiarchlinuxorg/wikiarchlinuxorg-20200209-history.xml.7z"
            val connection = java.net.URI(url).toURL().openConnection()
            connection.setRequestProperty("User-Agent", "Datamancy-Pipeline/1.0 (Educational/Research)")

            connection.getInputStream().use { input ->
                Files.copy(input, dumpFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }

            logger.info { "Downloaded XML dump to ${dumpFile.absolutePath}" }
        } else {
            logger.info { "Using cached XML dump at ${dumpFile.absolutePath}" }
        }

        return dumpFile.absolutePath
    }
}
