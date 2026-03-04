package org.datamancy.pipeline.sources

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.datamancy.pipeline.core.Source
import java.io.File
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamReader

private val logger = KotlinLogging.logger {}


class MediaWikiXmlDumpParser(
    private val xmlSource: XmlSource,
    private val wikiBaseUrl: String,
    private val maxPages: Int = Int.MAX_VALUE
) : Source<WikiPage> {
    override val name = "MediaWikiXmlDumpParser"

    sealed class XmlSource {
        data class LocalFile(val path: String) : XmlSource()
        data class SevenZipArchive(val archivePath: String, val xmlFilename: String) : XmlSource()
    }

    override suspend fun fetch(): Flow<WikiPage> = flow {
        logger.info { "Starting MediaWiki XML dump parse (max: $maxPages pages)" }

        val inputStream = when (xmlSource) {
            is XmlSource.LocalFile -> File(xmlSource.path).inputStream()
            is XmlSource.SevenZipArchive -> {
                
                val process = ProcessBuilder(
                    "7z", "x", "-so", xmlSource.archivePath, xmlSource.xmlFilename
                ).start()
                process.inputStream
            }
        }

        var count = 0
        inputStream.use { stream ->
            val pages = parseXmlDump(stream)
            for (page in pages) {
                if (count >= maxPages) break
                emit(page)
                count++

                if (count % 100 == 0) {
                    logger.info { "Parsed $count pages from XML dump" }
                }
            }
        }

        logger.info { "XML dump parse complete: $count pages" }
    }

    private fun parseXmlDump(inputStream: InputStream): Sequence<WikiPage> = sequence {
        val factory = XMLInputFactory.newInstance()
        val reader = factory.createXMLStreamReader(inputStream)

        var currentPage: PageBuilder? = null
        var currentRevision: RevisionBuilder? = null
        var currentElement = ""

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamReader.START_ELEMENT -> {
                        currentElement = reader.localName
                        when (currentElement) {
                            "page" -> currentPage = PageBuilder()
                            "revision" -> currentRevision = RevisionBuilder()
                        }
                    }

                    XMLStreamReader.CHARACTERS -> {
                        val text = reader.text
                        when (currentElement) {
                            "title" -> currentPage?.title = text
                            "ns" -> currentPage?.namespace = text.toIntOrNull() ?: 0
                            "id" -> {
                                if (currentRevision != null) {
                                    currentRevision.id = text
                                } else {
                                    currentPage?.id = text
                                }
                            }
                            "timestamp" -> currentRevision?.timestamp = text
                            "text" -> currentRevision?.text = text
                        }
                    }

                    XMLStreamReader.END_ELEMENT -> {
                        when (reader.localName) {
                            "revision" -> {
                                
                                currentRevision?.let { currentPage?.latestRevision = it }
                                currentRevision = null
                            }
                            "page" -> {
                                
                                currentPage?.build(wikiBaseUrl)?.let { page ->
                                    if (shouldIncludePage(page)) {
                                        yield(page)
                                    }
                                }
                                currentPage = null
                            }
                        }
                        currentElement = ""
                    }
                }
            }
        } finally {
            reader.close()
        }
    }

    private fun shouldIncludePage(page: WikiPage): Boolean {
        
        if (page.id.startsWith("arch:")) {
            val title = page.title
            return when {
                title.startsWith("Talk:") -> false
                title.startsWith("User:") -> false
                title.startsWith("User talk:") -> false
                title.startsWith("ArchWiki:") -> false
                title.startsWith("ArchWiki talk:") -> false
                title.startsWith("File:") -> false
                title.startsWith("File talk:") -> false
                title.startsWith("MediaWiki:") -> false
                title.startsWith("MediaWiki talk:") -> false
                title.startsWith("Template:") -> false
                title.startsWith("Template talk:") -> false
                title.startsWith("Help:") -> false
                title.startsWith("Help talk:") -> false
                title.startsWith("Category:") -> false
                title.startsWith("Category talk:") -> false
                page.content.startsWith("#REDIRECT") -> false
                page.content.startsWith("#redirect") -> false
                page.content.isEmpty() -> false
                else -> true
            }
        }
        return true
    }

    private data class PageBuilder(
        var title: String = "",
        var namespace: Int = 0,
        var id: String = "",
        var latestRevision: RevisionBuilder? = null
    ) {
        fun build(baseUrl: String): WikiPage? {
            val revision = latestRevision ?: return null
            val text = revision.text ?: return null

            return WikiPage(
                id = "arch:${title.replace(" ", "_")}",
                title = title,
                url = "$baseUrl/index.php/${title.replace(" ", "_")}",
                content = text,
                wikiType = "Arch Wiki",
                categories = emptyList()
            )
        }
    }

    private data class RevisionBuilder(
        var id: String = "",
        var timestamp: String = "",
        var text: String? = null
    )
}
