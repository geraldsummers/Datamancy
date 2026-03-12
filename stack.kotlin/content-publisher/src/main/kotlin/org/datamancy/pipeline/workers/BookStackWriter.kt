package org.datamancy.pipeline.workers

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.datamancy.pipeline.core.BookStackHtmlHelper
import org.datamancy.pipeline.sinks.BookStackDocument
import org.datamancy.pipeline.sinks.BookStackSink
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.StagedDocument
import kotlin.math.pow
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger {}


class BookStackWriter(
    private val stagingStore: DocumentStagingStore,
    private val bookStackSink: BookStackSink,
    private val pollIntervalSeconds: Long = 5,
    private val batchSize: Int = 200,
    private val maxRetries: Int = 3
) {
    
    suspend fun start() {
        logger.info { "BookStack writer starting (poll interval: ${pollIntervalSeconds}s, batch size: $batchSize)" }

        while (true) {
            try {
                
                val pendingDocs = stagingStore.getPendingForBookStack(limit = batchSize)

                if (pendingDocs.isEmpty()) {
                    logger.debug { "No pending BookStack writes" }
                    delay(pollIntervalSeconds.seconds)
                    continue
                }


                coroutineScope {
                    pendingDocs.map { doc ->
                        async {
                            try {
                                // Check if document has exceeded retry limit
                                if (doc.retryCount >= maxRetries) {
                                    logger.warn { "Document ${doc.id} exceeded max BookStack retries (${doc.retryCount}/$maxRetries), skipping" }
                                    return@async
                                }

                                // Apply exponential backoff if this is a retry
                                if (doc.retryCount > 0) {
                                    val backoffSeconds = 2.0.pow(doc.retryCount.toDouble()).toLong()
                                    logger.debug { "Applying backoff for ${doc.id}: ${backoffSeconds}s (retry ${doc.retryCount}/$maxRetries)" }
                                    delay(backoffSeconds * 1000)
                                }


                                val bookStackDoc = toBookStackDocument(doc)


                                bookStackSink.write(bookStackDoc)


                                val pageUrl = bookStackSink.getLastPageUrl(bookStackDoc.pageTitle)


                                if (pageUrl != null) {
                                    stagingStore.updateBookStackUrl(doc.id, pageUrl)
                                }


                                stagingStore.markBookStackComplete(doc.id)

                                logger.debug { "Wrote document ${doc.id} to BookStack: $pageUrl" }

                            } catch (e: Exception) {
                                logger.error(e) { "Failed to write document ${doc.id} to BookStack (attempt ${doc.retryCount + 1}/$maxRetries): ${e.message}" }


                                stagingStore.markBookStackFailed(doc.id, e.message ?: "Unknown error")
                            }
                        }
                    }.awaitAll()
                }

            } catch (e: Exception) {
                logger.error(e) { "Error in BookStack writer loop: ${e.message}" }
            }

            
            delay(pollIntervalSeconds.seconds)
        }
    }

    
    private fun toBookStackDocument(doc: StagedDocument): BookStackDocument {
        return when (doc.source) {
            "rss" -> buildRssDocument(doc)
            "torrents" -> buildTorrentDocument(doc)
            "wikipedia" -> buildWikipediaDocument(doc)
            "australian_laws" -> buildAustralianLegalDocument(doc)
            "linux_docs" -> buildLinuxDocDocument(doc)
            "debian_wiki" -> buildWikiDocument(doc, wikiName = "Debian Wiki", accentColor = "#D70A53")
            "arch_wiki" -> buildWikiDocument(doc, wikiName = "Arch Wiki", accentColor = "#1793D1")
            "cve" -> buildGenericDocument(doc, bookName = "CVE Database")
            else -> buildGenericDocument(doc, bookName = "Knowledge Base")
        }
    }

    
    private fun buildTags(doc: StagedDocument): Map<String, String> {
        val tags = mutableMapOf<String, String>()

        
        tags["source"] = doc.source

        
        tags["collection"] = doc.collection

        
        val chunkIndex = doc.chunkIndex
        val totalChunks = doc.totalChunks
        if (chunkIndex != null && totalChunks != null) {
            tags["chunk"] = "${chunkIndex + 1}/${totalChunks}"
        }

        
        (doc.metadata["url"] ?: doc.metadata["link"])?.let { tags["url"] = it }

        
        (doc.metadata["published_at"] ?: doc.metadata["published_date"])?.let { tags["published"] = it }

        
        doc.metadata["author"]?.let { tags["author"] = it }

        
        doc.metadata["feed_title"]?.let { tags["feed"] = it }

        
        doc.metadata["infohash"]?.let { tags["infohash"] = it }

        
        doc.metadata["jurisdiction"]?.let { tags["jurisdiction"] = it }
        doc.metadata["type"]?.let { tags["type"] = it }
        doc.metadata["section"]?.let { tags["section"] = it }
        doc.metadata["citation"]?.let { tags["citation"] = it }

        
        doc.metadata["wikiType"]?.let { tags["wiki_type"] = it }
        doc.metadata["categories"]?.let { tags["categories"] = it }

        return tags
    }

    private fun buildRssDocument(doc: StagedDocument): BookStackDocument {
        val metadata = doc.metadata
        val title = preferredTitle(metadata["title"], doc.id, doc)
        val feedTitle = metadata["feed_title"]?.ifBlank { null } ?: "RSS Feed"
        val feedUrl = metadata["feed_url"]?.ifBlank { null }
        val link = metadata["link"]?.ifBlank { null }
        val published = metadata["published_date"]?.ifBlank { null } ?: metadata["published_at"]?.ifBlank { null }
        val author = metadata["author"]?.ifBlank { null }
        val categories = metadata["categories"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val description = metadata["description"]?.ifBlank { null }

        val metadataItems = buildList {
            add("Feed" to formatLink(feedUrl ?: "", feedTitle, fallback = feedTitle))
            if (link != null) add("Article" to formatLink(link, "View original"))
            if (published != null) add("Published" to BookStackHtmlHelper.sanitizeForHtml(published))
            if (author != null) add("Author" to BookStackHtmlHelper.sanitizeForHtml(author))
            if (categories.isNotEmpty()) add("Categories" to BookStackHtmlHelper.sanitizeForHtml(categories.joinToString(", ")))
        }

        val contentBody = extractRssBody(doc.text)
        val contentSection = if (contentBody.isNotBlank()) {
            formatSection("Content", formatParagraphs(contentBody))
        } else ""

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                description?.let { formatSection("Summary", "<p>${BookStackHtmlHelper.sanitizeForHtml(it)}</p>") },
                contentSection.ifBlank { null }
            ).joinToString("\n"),
            footerSource = "RSS feed ${feedTitle}"
        )

        return BookStackDocument(
            bookName = "RSS Articles",
            bookDescription = "Aggregated RSS articles and feeds",
            chapterName = feedTitle,
            chapterDescription = feedUrl?.let { "Articles from $it" },
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc) + mapOf(
                "feed" to feedTitle
            )
        )
    }

    private fun buildTorrentDocument(doc: StagedDocument): BookStackDocument {
        val metadata = doc.metadata
        val infohash = metadata["infohash"]?.ifBlank { null }
        val name = metadata["name"]?.ifBlank { null } ?: infohash ?: doc.id
        val seeders = metadata["seeders"]?.ifBlank { null }
        val leechers = metadata["leechers"]?.ifBlank { null }
        val sizeBytes = metadata["sizeBytes"]?.toLongOrNull()

        val metadataItems = buildList {
            if (infohash != null) add("Infohash" to formatInlineCode(infohash))
            if (sizeBytes != null) add("Size" to BookStackHtmlHelper.sanitizeForHtml(formatBytes(sizeBytes)))
            if (seeders != null) add("Seeders" to BookStackHtmlHelper.sanitizeForHtml(seeders))
            if (leechers != null) add("Leechers" to BookStackHtmlHelper.sanitizeForHtml(leechers))
            if (infohash != null) {
                val magnet = "magnet:?xt=urn:btih:$infohash"
                add("Magnet" to formatLink(magnet, "Open magnet"))
            }
        }

        val pageContent = buildPage(
            title = name,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(formatChunkNotice(doc)).joinToString("\n"),
            footerSource = "torrent index dataset"
        )

        return BookStackDocument(
            bookName = "Torrent Index",
            bookDescription = "Torrent metadata index for research and analysis",
            chapterName = null,
            pageTitle = name,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun buildWikipediaDocument(doc: StagedDocument): BookStackDocument {
        val metadata = doc.metadata
        val title = preferredTitle(metadata["title"], doc.id, doc)
        val wikipediaId = metadata["wikipedia_id"]?.ifBlank { null }
        val pageUrl = "https://en.wikipedia.org/wiki/${title.replace(" ", "_")}"
        val cleanText = doc.text.trim()

        val summary = cleanText.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        val contentSection = if (cleanText.isNotBlank()) {
            formatSection("Article", formatParagraphs(cleanText))
        } else {
            "<p><em>No content available.</em></p>"
        }

        val metadataItems = buildList {
            if (wikipediaId != null) add("Wikipedia ID" to formatInlineCode(wikipediaId))
            add("Source" to formatLink(pageUrl, "View on Wikipedia"))
        }

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                summary?.let { formatSection("Summary", "<p>${BookStackHtmlHelper.sanitizeForHtml(it)}</p>") },
                contentSection
            ).joinToString("\n"),
            footerSource = "Wikipedia"
        )

        return BookStackDocument(
            bookName = "Wikipedia Articles",
            bookDescription = "Articles from Wikipedia, the free encyclopedia",
            chapterName = null,
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun buildAustralianLegalDocument(doc: StagedDocument): BookStackDocument {
        val metadata = doc.metadata
        val jurisdiction = metadata["jurisdiction"]?.ifBlank { null } ?: "Unknown jurisdiction"
        val type = metadata["type"]?.ifBlank { null }
        val citation = metadata["citation"]?.ifBlank { null }
        val date = metadata["date"]?.ifBlank { null }
        val source = metadata["source"]?.ifBlank { null }
        val url = metadata["url"]?.ifBlank { null }

        val title = preferredTitle(citation ?: type, doc.id, doc)

        val metadataItems = buildList {
            if (type != null) add("Type" to BookStackHtmlHelper.sanitizeForHtml(type))
            add("Jurisdiction" to BookStackHtmlHelper.sanitizeForHtml(jurisdiction))
            if (date != null) add("Date" to BookStackHtmlHelper.sanitizeForHtml(date))
            if (citation != null) add("Citation" to formatInlineCode(citation))
            if (source != null && url != null) {
                add("Source" to formatLink(url, source))
            } else if (url != null) {
                add("Source" to formatLink(url, url))
            }
        }

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                formatSection("Document", formatPre(doc.text))
            ).joinToString("\n"),
            footerSource = "Open Australian Legal Corpus"
        )

        return BookStackDocument(
            bookName = "Australian Legal Corpus",
            bookDescription = "Legal documents from Australian Commonwealth and State sources",
            chapterName = jurisdiction,
            chapterDescription = "Legal documents from $jurisdiction",
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun buildLinuxDocDocument(doc: StagedDocument): BookStackDocument {
        val metadata = doc.metadata
        val title = preferredTitle(metadata["title"], doc.id, doc)
        val type = metadata["type"]?.ifBlank { null }
        val section = metadata["section"]?.ifBlank { null }
        val path = metadata["path"]?.ifBlank { null }

        val metadataItems = buildList {
            if (type != null) add("Type" to BookStackHtmlHelper.sanitizeForHtml(type))
            if (section != null) add("Section" to BookStackHtmlHelper.sanitizeForHtml(section))
            if (path != null) add("Path" to formatInlineCode(path))
        }

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                formatSection("Content", formatPre(doc.text))
            ).joinToString("\n"),
            footerSource = "Linux documentation"
        )

        return BookStackDocument(
            bookName = "Linux Documentation",
            bookDescription = "Manual pages and system documentation for Linux",
            chapterName = type,
            chapterDescription = type?.let { "Section $section" },
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun buildWikiDocument(doc: StagedDocument, wikiName: String, accentColor: String): BookStackDocument {
        val metadata = doc.metadata
        val title = preferredTitle(metadata["title"], doc.id, doc)
        val url = metadata["url"]?.ifBlank { null }
        val categories = metadata["categories"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val chapterName = categories.firstOrNull()

        val metadataItems = buildList {
            add("Wiki" to "<span style=\"color: $accentColor; font-weight: bold;\">${BookStackHtmlHelper.sanitizeForHtml(wikiName)}</span>")
            if (url != null) add("URL" to formatLink(url, url))
            if (categories.isNotEmpty()) add("Categories" to BookStackHtmlHelper.sanitizeForHtml(categories.joinToString(", ")))
        }

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                formatSection("Content", formatParagraphs(doc.text))
            ).joinToString("\n"),
            footerSource = wikiName
        )

        return BookStackDocument(
            bookName = wikiName,
            bookDescription = "Community-maintained documentation for $wikiName",
            chapterName = chapterName,
            chapterDescription = chapterName?.let { "Pages in $it category" },
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun buildGenericDocument(doc: StagedDocument, bookName: String): BookStackDocument {
        val title = preferredTitle(doc.metadata["title"], doc.id, doc)
        val metadataItems = buildList {
            doc.metadata["url"]?.let { add("Source" to formatLink(it, it)) }
        }

        val pageContent = buildPage(
            title = title,
            metadataItems = metadataItems,
            bodyHtml = listOfNotNull(
                formatChunkNotice(doc),
                formatSection("Content", formatParagraphs(doc.text))
            ).joinToString("\n"),
            footerSource = doc.source
        )

        return BookStackDocument(
            bookName = bookName,
            chapterName = doc.source.replaceFirstChar { it.titlecase() },
            pageTitle = title,
            pageContent = pageContent,
            tags = buildTags(doc)
        )
    }

    private fun preferredTitle(primary: String?, fallback: String, doc: StagedDocument): String {
        val base = primary?.ifBlank { null } ?: fallback
        val chunkIndex = doc.chunkIndex
        val totalChunks = doc.totalChunks
        val needsChunkSuffix = chunkIndex != null && totalChunks != null && totalChunks > 1 &&
            !Regex("\\(part\\s+\\d+", RegexOption.IGNORE_CASE).containsMatchIn(base)
        return if (needsChunkSuffix) "$base (Part ${chunkIndex + 1} of ${totalChunks})" else base
    }

    private fun buildPage(
        title: String,
        metadataItems: List<Pair<String, String>>,
        bodyHtml: String,
        footerSource: String
    ): String {
        val safeTitle = BookStackHtmlHelper.sanitizeForHtml(title)
        val metadataBox = if (metadataItems.isNotEmpty()) BookStackHtmlHelper.formatMetadataBox(metadataItems) else ""
        val footer = BookStackHtmlHelper.formatFooter(footerSource)

        return """
            <h1>$safeTitle</h1>
            $metadataBox
            $bodyHtml
            $footer
        """.trimIndent()
    }

    private fun formatChunkNotice(doc: StagedDocument): String {
        val chunkIndex = doc.chunkIndex
        val totalChunks = doc.totalChunks
        if (chunkIndex == null || totalChunks == null || totalChunks <= 1) return ""

        val label = "Part ${chunkIndex + 1} of $totalChunks"
        return """
            <div style="margin-bottom: 16px; padding: 10px; background-color: #eef6ff; border-left: 4px solid #1e88e5;">
                <strong>Chunked document:</strong> ${BookStackHtmlHelper.sanitizeForHtml(label)}
            </div>
        """.trimIndent()
    }

    private fun formatSection(title: String, bodyHtml: String): String {
        if (bodyHtml.isBlank()) return ""
        return """
            <div style="margin-top: 20px;">
                <h2>${BookStackHtmlHelper.sanitizeForHtml(title)}</h2>
                $bodyHtml
            </div>
        """.trimIndent()
    }

    private fun formatParagraphs(text: String): String {
        if (text.isBlank()) return ""
        val safe = BookStackHtmlHelper.sanitizeForHtml(text)
        return safe.split(Regex("\\n\\s*\\n"))
            .map { it.trim().replace(Regex("\\s*\\n\\s*"), " ") }
            .filter { it.isNotBlank() }
            .joinToString("\n") { "<p>$it</p>" }
    }

    private fun formatPre(text: String): String {
        if (text.isBlank()) return "<p><em>No content available.</em></p>"
        val safe = BookStackHtmlHelper.sanitizeForHtml(text)
        return "<pre style=\"white-space: pre-wrap; word-wrap: break-word; font-family: 'Courier New', monospace; background-color: #f8f9fa; padding: 15px; border-radius: 4px; line-height: 1.5;\">$safe</pre>"
    }

    private fun formatInlineCode(value: String): String {
        return "<code>${BookStackHtmlHelper.sanitizeForHtml(value)}</code>"
    }

    private fun formatLink(url: String, label: String, fallback: String? = null): String {
        val safeUrl = BookStackHtmlHelper.sanitizeForHtml(url)
        val safeLabel = BookStackHtmlHelper.sanitizeForHtml(label.ifBlank { fallback ?: url })
        return if (safeUrl.isNotBlank()) {
            "<a href=\"$safeUrl\" target=\"_blank\">$safeLabel</a>"
        } else {
            safeLabel
        }
    }

    private fun extractRssBody(text: String): String {
        if (text.isBlank()) return ""
        val lines = text.lines()
        var index = 0
        if (lines.isNotEmpty() && lines[0].trim().startsWith("#")) {
            index++
        }
        while (index < lines.size && lines[index].isBlank()) index++
        while (index < lines.size && lines[index].trim().startsWith("**")) index++
        while (index < lines.size && lines[index].isBlank()) index++
        val remainder = lines.drop(index).toMutableList()
        val readMoreIndex = remainder.indexOfFirst { it.trim().startsWith("**Read more:**") }
        if (readMoreIndex >= 0) {
            remainder.subList(readMoreIndex, remainder.size).clear()
        }
        return remainder.joinToString("\n").trim()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "%.2f KB".format(bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }
}
