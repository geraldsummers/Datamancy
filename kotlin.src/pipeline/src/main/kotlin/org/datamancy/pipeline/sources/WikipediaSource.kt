package org.datamancy.pipeline.sources

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.datamancy.pipeline.core.Source
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.concurrent.atomic.AtomicLong
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamReader

private val logger = KotlinLogging.logger {}

/**
 * Fetches articles from Wikipedia XML dump
 * Dump format: https://dumps.wikimedia.org/enwiki/latest/
 * File: enwiki-latest-pages-articles.xml.bz2 (~20GB compressed)
 *
 * For chunking: Articles are split into chunks of maxChunkSize characters
 * with overlap to maintain context across chunks
 */
class WikipediaSource(
    private val dumpPath: String,  // Path to XML dump file
    private val maxArticles: Int = Int.MAX_VALUE,
    private val maxChunkSize: Int = 2000,  // ~512 tokens
    private val chunkOverlap: Int = 200
) : Source<WikipediaArticle> {
    override val name = "WikipediaSource"

    // Track IO stats (bytes read from disk/network)
    private val bytesRead = AtomicLong(0)
    private val articlesProcessed = AtomicLong(0)

    override suspend fun fetch(): Flow<WikipediaArticle> = flow {
        logger.info { "Starting Wikipedia fetch from $dumpPath (max: $maxArticles articles)" }

        var articleCount = 0

        try {
            val reader: BufferedReader = if (dumpPath.startsWith("http://") || dumpPath.startsWith("https://")) {
                // Retry logic with exponential backoff for network failures
                var retries = 3
                var lastException: Exception? = null
                var result: BufferedReader? = null

                while (retries > 0 && result == null) {
                    try {
                        val connection = java.net.URI(dumpPath).toURL().openConnection()
                        connection.setRequestProperty("User-Agent", "Datamancy/1.0 (Educational Research)")
                        connection.connectTimeout = 60000  // 60 seconds
                        connection.readTimeout = 1800000   // 30 minutes for large dumps
                        val inputStream = connection.getInputStream()

                        val stream: InputStream = when {
                            dumpPath.endsWith(".bz2") -> BZip2CompressorInputStream(inputStream, true)
                            dumpPath.endsWith(".gz") -> GZIPInputStream(inputStream)
                            else -> inputStream
                        }

                        result = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
                    } catch (e: Exception) {
                        lastException = e
                        retries--
                        if (retries > 0) {
                            val backoffSeconds = (4 - retries) * 60  // 60s, 120s, 180s
                            logger.warn { "Wikipedia download failed, retrying in ${backoffSeconds}s (${retries} attempts left): ${e.message}" }
                            Thread.sleep(backoffSeconds * 1000L)
                        }
                    }
                }

                result ?: throw (lastException ?: Exception("Failed to download Wikipedia dump after 3 retries"))
            } else {
                logger.info { "Reading Wikipedia dump from file: $dumpPath" }
                val file = File(dumpPath)
                if (!file.exists()) {
                    logger.error { "Wikipedia dump file not found: $dumpPath" }
                    return@flow
                }

                val inputStream = file.inputStream()
                val stream: InputStream = when {
                    dumpPath.endsWith(".bz2") -> {
                        logger.info { "Decompressing BZip2 file..." }
                        BZip2CompressorInputStream(inputStream)
                    }
                    dumpPath.endsWith(".gz") -> GZIPInputStream(inputStream)
                    else -> inputStream
                }

                BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
            }

            reader.use { br ->
                val xmlFactory = XMLInputFactory.newInstance()
                val xmlReader = xmlFactory.createXMLStreamReader(br)

                var currentTitle = ""
                var currentId = ""
                var currentText = ""
                var inPage = false
                var inTitle = false
                var inId = false
                var inText = false

                while (xmlReader.hasNext() && articleCount < maxArticles) {
                    when (xmlReader.next()) {
                        XMLStreamReader.START_ELEMENT -> {
                            when (xmlReader.localName) {
                                "page" -> inPage = true
                                "title" -> inTitle = true
                                "id" -> if (inPage && currentId.isEmpty()) inId = true  // Only first ID (page ID, not revision ID)
                                "text" -> inText = true
                            }
                        }
                        XMLStreamReader.CHARACTERS -> {
                            when {
                                inTitle -> currentTitle = xmlReader.text
                                inId -> currentId = xmlReader.text
                                inText -> {
                                    val text = xmlReader.text
                                    currentText += text
                                    bytesRead.addAndGet(text.length.toLong())
                                }
                            }
                        }
                        XMLStreamReader.END_ELEMENT -> {
                            when (xmlReader.localName) {
                                "page" -> {
                                    if (currentTitle.isNotEmpty() && currentText.isNotEmpty()) {
                                        // Skip redirects and special pages
                                        if (!currentText.startsWith("#REDIRECT") &&
                                            !currentTitle.startsWith("Wikipedia:") &&
                                            !currentTitle.startsWith("Template:") &&
                                            !currentTitle.startsWith("Category:")) {

                                            // Clean and chunk the text
                                            val cleanedText = cleanWikitext(currentText)

                                            if (cleanedText.length > maxChunkSize) {
                                                // Split into chunks
                                                var chunkIndex = 0
                                                var startPos = 0

                                                while (startPos < cleanedText.length) {
                                                    val endPos = minOf(startPos + maxChunkSize, cleanedText.length)
                                                    val chunkText = cleanedText.substring(startPos, endPos)

                                                    emit(WikipediaArticle(
                                                        id = "$currentId-chunk-$chunkIndex",
                                                        title = if (chunkIndex == 0) currentTitle else "$currentTitle (part ${chunkIndex + 1})",
                                                        text = chunkText,
                                                        isChunk = true,
                                                        chunkIndex = chunkIndex,
                                                        originalArticleId = currentId
                                                    ))

                                                    chunkIndex++
                                                    startPos = endPos - chunkOverlap
                                                    if (startPos < 0) startPos = endPos
                                                }
                                            } else {
                                                // Emit as single article
                                                emit(WikipediaArticle(
                                                    id = currentId,
                                                    title = currentTitle,
                                                    text = cleanedText,
                                                    isChunk = false,
                                                    chunkIndex = 0,
                                                    originalArticleId = currentId
                                                ))
                                            }

                                            articleCount++
                                            articlesProcessed.incrementAndGet()

                                            if (articleCount % 1000 == 0) {
                                                logger.info { "Processed $articleCount articles" }
                                            }
                                        }
                                    }

                                    // Reset for next page
                                    currentTitle = ""
                                    currentId = ""
                                    currentText = ""
                                    inPage = false
                                }
                                "title" -> inTitle = false
                                "id" -> inId = false
                                "text" -> inText = false
                            }
                        }
                    }
                }

                xmlReader.close()
            }

            logger.info { "Wikipedia fetch complete: $articleCount articles processed" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch Wikipedia articles: ${e.message}" }
        }
    }

    /**
     * Clean wikitext markup to plain text
     * This is a simplified version - full MediaWiki parsing is complex
     */
    private fun cleanWikitext(text: String): String {
        var cleaned = text

        // Remove common wiki markup
        cleaned = cleaned.replace(Regex("\\{\\{[^}]+\\}\\}"), "")  // Remove templates
        cleaned = cleaned.replace(Regex("\\[\\[File:[^]]+\\]\\]"), "")  // Remove file links
        cleaned = cleaned.replace(Regex("\\[\\[Image:[^]]+\\]\\]"), "")  // Remove image links
        cleaned = cleaned.replace(Regex("\\[\\[Category:[^]]+\\]\\]"), "")  // Remove categories
        cleaned = cleaned.replace(Regex("\\[\\[([^]|]+)\\|([^]]+)\\]\\]"), "$2")  // [[link|text]] -> text
        cleaned = cleaned.replace(Regex("\\[\\[([^]]+)\\]\\]"), "$1")  // [[link]] -> link
        cleaned = cleaned.replace(Regex("\\[https?://[^\\s]+\\s+([^]]+)\\]"), "$1")  // [url text] -> text
        cleaned = cleaned.replace(Regex("'{2,5}"), "")  // Remove bold/italic markup
        cleaned = cleaned.replace(Regex("^[=]+(.+?)[=]+$", RegexOption.MULTILINE), "$1")  // Remove heading markup
        cleaned = cleaned.replace(Regex("<[^>]+>"), "")  // Remove HTML tags
        cleaned = cleaned.replace(Regex("&[a-z]+;"), " ")  // Remove HTML entities

        // Clean up whitespace
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        cleaned = cleaned.trim()

        return cleaned
    }

    fun getIOStats(): WikipediaIOStats {
        return WikipediaIOStats(
            bytesRead = bytesRead.get(),
            articlesProcessed = articlesProcessed.get()
        )
    }

    fun resetStats() {
        bytesRead.set(0)
        articlesProcessed.set(0)
    }
}

data class WikipediaIOStats(
    val bytesRead: Long,
    val articlesProcessed: Long
) {
    fun formatBytes(): String {
        val kb = bytesRead / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.2f GB".format(gb)
            mb >= 1.0 -> "%.2f MB".format(mb)
            kb >= 1.0 -> "%.2f KB".format(kb)
            else -> "$bytesRead bytes"
        }
    }
}

data class WikipediaArticle(
    val id: String,
    val title: String,
    val text: String,
    val isChunk: Boolean = false,
    val chunkIndex: Int = 0,
    val originalArticleId: String
) {
    fun toText(): String {
        return buildString {
            appendLine("# $title")
            appendLine()
            if (isChunk) {
                appendLine("*[Part ${chunkIndex + 1} of article]*")
                appendLine()
            }
            appendLine(text)
            appendLine()
            appendLine("**Source:** Wikipedia")
            appendLine("**Article ID:** $originalArticleId")
        }
    }

    fun contentHash(): String {
        return "$originalArticleId:$chunkIndex".hashCode().toString()
    }
}
