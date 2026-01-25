package org.datamancy.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.datamancy.pipeline.config.PipelineConfig
import org.datamancy.pipeline.core.Pipeline
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.processors.RssToText
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.sinks.VectorDocument
import org.datamancy.pipeline.sinks.BookStackSink
import org.datamancy.pipeline.sinks.BookStackDocument
import org.datamancy.pipeline.sources.RssArticle
import org.datamancy.pipeline.sources.RssSource
import org.datamancy.pipeline.sources.CveEntry
import org.datamancy.pipeline.sources.CveSource
import org.datamancy.pipeline.sources.TorrentEntry
import org.datamancy.pipeline.sources.TorrentsSource
import org.datamancy.pipeline.sources.WikipediaArticle
import org.datamancy.pipeline.sources.WikipediaSource
import org.datamancy.pipeline.sources.AustralianLaw
import org.datamancy.pipeline.sources.AustralianLawsSource
import org.datamancy.pipeline.sources.LinuxDoc
import org.datamancy.pipeline.sources.LinuxDocsSource
import org.datamancy.pipeline.storage.DeduplicationStore
import org.datamancy.pipeline.storage.SourceMetadataStore
import org.datamancy.pipeline.monitoring.MonitoringServer
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "🔥 Datamancy Pipeline Service Starting..." }

    // Load configuration
    val config = PipelineConfig.fromEnv()
    logger.info { "Configuration loaded: ${config.rss.feedUrls.size} RSS feeds configured" }

    // Initialize shared infrastructure
    val dedupStore = DeduplicationStore()
    val metadataStore = SourceMetadataStore()

    // Start monitoring HTTP server and pipelines
    runBlocking {
        // Start monitoring HTTP server in background coroutine
        val monitoringServer = MonitoringServer(port = 8090, metadataStore = metadataStore)
        monitoringServer.start()

        // Give monitoring server time to start
        delay(1000)

        launch { runRssPipeline(config, dedupStore, metadataStore) }
        launch { runCvePipeline(config, dedupStore, metadataStore) }
        launch { runTorrentsPipeline(config, dedupStore, metadataStore) }
        launch { runWikipediaPipeline(config, dedupStore, metadataStore) }
        launch { runAustralianLawsPipeline(config, dedupStore, metadataStore) }
        launch { runLinuxDocsPipeline(config, dedupStore, metadataStore) }

        // Keep running
        awaitCancellation()
    }
}

suspend fun runRssPipeline(
    config: PipelineConfig,
    dedupStore: DeduplicationStore,
    metadataStore: SourceMetadataStore
) {
    if (!config.rss.enabled) {
        logger.info { "RSS pipeline disabled in config" }
        return
    }

    logger.info { "Starting RSS pipeline with ${config.rss.feedUrls.size} feeds" }

    // Initialize components
    val source = RssSource(config.rss.feedUrls)
    val embedder = Embedder(config.embedding.serviceUrl)
    val qdrantSink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = config.qdrant.rssCollection,
        vectorSize = config.embedding.vectorSize
    )

    // BookStack sink (optional - only if configured)
    val bookstackSink = if (config.bookstack.enabled) {
        BookStackSink(
            bookstackUrl = config.bookstack.url,
            tokenId = config.bookstack.tokenId,
            tokenSecret = config.bookstack.tokenSecret
        )
    } else null

    // Schedule periodic runs
    while (true) {
        try {
            logger.info { "=== Starting RSS fetch cycle ===" }
            val startTime = System.currentTimeMillis()

            // Run pipeline: RSS -> Text -> Embed -> Store
            var processed = 0
            var failed = 0
            var deduplicated = 0

            source.fetch()
                .buffer(1000)
                .map { article ->
                    try {
                        // Check deduplication
                        val hash = article.guid.hashCode().toString()
                        if (dedupStore.checkAndMark(hash, article.guid)) {
                            deduplicated++
                            logger.debug { "Skipping duplicate article: ${article.guid}" }
                            return@map null
                        }

                        // Convert to text
                        val text = article.toText()

                        // Generate embedding
                        val vector = embedder.process(text)

                        // Create vector document for Qdrant
                        val qdrantDoc = VectorDocument(
                            id = article.guid,
                            vector = vector,
                            metadata = mapOf(
                                "title" to article.title,
                                "link" to article.link,
                                "description" to article.description.take(200),
                                "publishedDate" to article.publishedDate,
                                "author" to article.author,
                                "feedTitle" to article.feedTitle,
                                "feedUrl" to article.feedUrl,
                                "categories" to article.categories.joinToString(","),
                                "source" to "rss"
                            )
                        )

                        // Create BookStack document (if sink enabled)
                        val bookstackDoc = if (bookstackSink != null) {
                            BookStackDocument(
                                bookName = "RSS Feeds",
                                bookDescription = "Articles from RSS feed aggregation",
                                chapterName = article.feedTitle,
                                chapterDescription = article.feedUrl,
                                pageTitle = article.title,
                                pageContent = buildRssArticleHtml(article),
                                tags = mapOf(
                                    "source" to "rss",
                                    "feed" to article.feedTitle,
                                    "published" to article.publishedDate,
                                    "author" to article.author
                                )
                            )
                        } else null

                        processed++
                        Pair(qdrantDoc, bookstackDoc)
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to process article: ${e.message}" }
                        null
                    }
                }
                .filterNotNull()
                .collect { (qdrantDoc, bookstackDoc) ->
                    try {
                        qdrantSink.write(qdrantDoc)

                        // Write to BookStack if enabled
                        if (bookstackDoc != null && bookstackSink != null) {
                            try {
                                bookstackSink.write(bookstackDoc)
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to write to BookStack: ${e.message}" }
                            }
                        }
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to write to Qdrant: ${e.message}" }
                    }
                }

            val duration = System.currentTimeMillis() - startTime
            val ioStats = source.getIOStats()
            val embedStats = embedder.getStats()
            logger.info {
                "=== RSS cycle complete: $processed processed, $failed failed, $deduplicated skipped (duplicates) in ${duration}ms | " +
                "Internet: ${ioStats.formatBytes()} from ${ioStats.feedsFetched} feeds | " +
                "Embeddings: ${embedStats.totalRequests} requests, avg ${embedStats.averageLatencyMs}ms ==="
            }

            // Update metadata
            metadataStore.recordSuccess("rss", processed.toLong(), failed.toLong())
            dedupStore.flush()
            source.resetStats()

            // Wait before next cycle
            val delayMinutes = config.rss.scheduleMinutes
            logger.info { "Next RSS fetch in $delayMinutes minutes" }
            delay(TimeUnit.MINUTES.toMillis(delayMinutes.toLong()))

        } catch (e: CancellationException) {
            logger.info { "RSS pipeline cancelled" }
            break
        } catch (e: Exception) {
            logger.error(e) { "RSS pipeline error: ${e.message}" }
            metadataStore.recordFailure("rss")
            logger.info { "Retrying in 1 minute..." }
            delay(TimeUnit.MINUTES.toMillis(1))
        }
    }
}

suspend fun runCvePipeline(
    config: PipelineConfig,
    dedupStore: DeduplicationStore,
    metadataStore: SourceMetadataStore
) {
    if (!config.cve.enabled) {
        logger.info { "CVE pipeline disabled in config" }
        return
    }

    logger.info { "Starting CVE pipeline" }

    // Initialize components
    val embedder = Embedder(config.embedding.serviceUrl)
    val qdrantSink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = config.qdrant.cveCollection,
        vectorSize = config.embedding.vectorSize
    )

    // BookStack sink (optional - only if configured)
    val bookstackSink = if (config.bookstack.enabled) {
        BookStackSink(
            bookstackUrl = config.bookstack.url,
            tokenId = config.bookstack.tokenId,
            tokenSecret = config.bookstack.tokenSecret
        )
    } else null

    // Schedule periodic runs
    while (true) {
        try {
            logger.info { "=== Starting CVE fetch cycle ===" }
            val startTime = System.currentTimeMillis()

            var processed = 0
            var failed = 0
            var deduplicated = 0

            // Load checkpoint to resume from last position
            val metadata = metadataStore.load("cve")
            val startIndex = metadata.checkpointData["nextIndex"]?.toIntOrNull() ?: 0

            val source = CveSource(
                apiKey = config.cve.apiKey,
                startIndex = startIndex,
                maxResults = config.cve.maxResults
            )

            source.fetch()
                .buffer(1000)
                .map { cve ->
                    try {
                        // Check deduplication using content hash
                        val hash = cve.contentHash()
                        if (dedupStore.checkAndMark(hash, cve.cveId)) {
                            deduplicated++
                            logger.debug { "Skipping duplicate CVE: ${cve.cveId}" }
                            return@map null
                        }

                        // Convert to text
                        val text = cve.toText()

                        // Generate embedding
                        val vector = embedder.process(text)

                        // Create vector document for Qdrant
                        val qdrantDoc = VectorDocument(
                            id = cve.cveId,
                            vector = vector,
                            metadata = mapOf(
                                "cveId" to cve.cveId,
                                "severity" to cve.severity,
                                "baseScore" to (cve.baseScore?.toString() ?: "N/A"),
                                "publishedDate" to cve.publishedDate,
                                "lastModifiedDate" to cve.lastModifiedDate,
                                "description" to cve.description.take(500),
                                "affectedProducts" to cve.affectedProducts.take(10).joinToString(", "),
                                "source" to "cve"
                            )
                        )

                        // Create BookStack document (if sink enabled)
                        val bookstackDoc = if (bookstackSink != null) {
                            BookStackDocument(
                                bookName = "Security Vulnerabilities",
                                bookDescription = "CVE security vulnerability database",
                                chapterName = cve.severity,
                                chapterDescription = "CVEs with ${cve.severity} severity",
                                pageTitle = cve.cveId,
                                pageContent = buildCveHtml(cve),
                                tags = mapOf(
                                    "source" to "cve",
                                    "severity" to cve.severity,
                                    "published" to cve.publishedDate,
                                    "baseScore" to (cve.baseScore?.toString() ?: "N/A")
                                )
                            )
                        } else null

                        processed++
                        Pair(qdrantDoc, bookstackDoc)
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to process CVE: ${e.message}" }
                        null
                    }
                }
                .filterNotNull()
                .collect { (qdrantDoc, bookstackDoc) ->
                    try {
                        qdrantSink.write(qdrantDoc)

                        // Write to BookStack if enabled
                        if (bookstackDoc != null && bookstackSink != null) {
                            try {
                                bookstackSink.write(bookstackDoc)
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to write to BookStack: ${e.message}" }
                            }
                        }
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to write to Qdrant: ${e.message}" }
                    }
                }

            val duration = System.currentTimeMillis() - startTime
            logger.info {
                "=== CVE cycle complete: $processed processed, $failed failed, $deduplicated skipped (duplicates) in ${duration}ms ==="
            }

            // Update metadata with checkpoint
            val nextIndex = startIndex + processed + deduplicated
            metadataStore.recordSuccess(
                "cve",
                processed.toLong(),
                failed.toLong(),
                mapOf("nextIndex" to nextIndex.toString())
            )
            dedupStore.flush()

            // Wait before next cycle
            val delayMinutes = config.cve.scheduleMinutes
            logger.info { "Next CVE fetch in $delayMinutes minutes" }
            delay(TimeUnit.MINUTES.toMillis(delayMinutes.toLong()))

        } catch (e: CancellationException) {
            logger.info { "CVE pipeline cancelled" }
            break
        } catch (e: Exception) {
            logger.error(e) { "CVE pipeline error: ${e.message}" }
            metadataStore.recordFailure("cve")
            logger.info { "Retrying in 5 minutes..." }
            delay(TimeUnit.MINUTES.toMillis(5))
        }
    }
}

suspend fun runTorrentsPipeline(
    config: PipelineConfig,
    dedupStore: DeduplicationStore,
    metadataStore: SourceMetadataStore
) {
    if (!config.torrents.enabled) {
        logger.info { "Torrents pipeline disabled in config" }
        return
    }

    logger.info { "Starting Torrents pipeline" }

    // Initialize components
    val embedder = Embedder(config.embedding.serviceUrl)
    val sink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = config.qdrant.torrentsCollection,
        vectorSize = config.embedding.vectorSize
    )

    // Schedule periodic runs
    while (true) {
        try {
            logger.info { "=== Starting Torrents fetch cycle ===" }
            val startTime = System.currentTimeMillis()

            var processed = 0
            var failed = 0
            var deduplicated = 0

            // Load checkpoint to resume from last line
            val metadata = metadataStore.load("torrents")
            val startLine = metadata.checkpointData["nextLine"]?.toLongOrNull() ?: 0L

            val source = TorrentsSource(
                dataPath = config.torrents.dataPath,
                startLine = startLine,
                maxTorrents = config.torrents.maxResults
            )

            source.fetch()
                .buffer(1000)
                .map { torrent ->
                    try {
                        // Check deduplication using infohash
                        val hash = torrent.contentHash()
                        if (dedupStore.checkAndMark(hash, torrent.infohash)) {
                            deduplicated++
                            if (deduplicated % 1000 == 0) {
                                logger.debug { "Skipped $deduplicated duplicate torrents so far" }
                            }
                            return@map null
                        }

                        // Convert to text
                        val text = torrent.toText()

                        // Generate embedding
                        val vector = embedder.process(text)

                        // Create vector document
                        val doc = VectorDocument(
                            id = torrent.infohash,
                            vector = vector,
                            metadata = mapOf(
                                "infohash" to torrent.infohash,
                                "name" to torrent.name.take(500),
                                "sizeBytes" to torrent.sizeBytes.toString(),
                                "seeders" to torrent.seeders.toString(),
                                "leechers" to torrent.leechers.toString(),
                                "completed" to torrent.completed.toString(),
                                "createdUnix" to (torrent.createdUnix?.toString() ?: "N/A"),
                                "source" to "torrents"
                            )
                        )

                        processed++
                        doc
                    } catch (e: Exception) {
                        failed++
                        if (failed % 100 == 0) {
                            logger.error(e) { "Failed to process torrent (total failures: $failed): ${e.message}" }
                        }
                        null
                    }
                }
                .filterNotNull()
                .collect { doc ->
                    try {
                        sink.write(doc)
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to write to Qdrant: ${e.message}" }
                    }
                }

            val duration = System.currentTimeMillis() - startTime
            logger.info {
                "=== Torrents cycle complete: $processed processed, $failed failed, $deduplicated skipped (duplicates) in ${duration}ms ==="
            }

            // Update metadata with checkpoint
            // Since we processed torrents in order, the next line is the last torrent's lineNumber + 1
            metadataStore.recordSuccess(
                "torrents",
                processed.toLong(),
                failed.toLong(),
                mapOf("nextLine" to (startLine + processed + deduplicated).toString())
            )
            dedupStore.flush()

            // Wait before next cycle
            val delayMinutes = config.torrents.scheduleMinutes
            logger.info { "Next Torrents fetch in $delayMinutes minutes" }
            delay(TimeUnit.MINUTES.toMillis(delayMinutes.toLong()))

        } catch (e: CancellationException) {
            logger.info { "Torrents pipeline cancelled" }
            break
        } catch (e: Exception) {
            logger.error(e) { "Torrents pipeline error: ${e.message}" }
            metadataStore.recordFailure("torrents")
            logger.info { "Retrying in 10 minutes..." }
            delay(TimeUnit.MINUTES.toMillis(10))
        }
    }
}

suspend fun runWikipediaPipeline(
    config: PipelineConfig,
    dedupStore: DeduplicationStore,
    metadataStore: SourceMetadataStore
) {
    if (!config.wikipedia.enabled) {
        logger.info { "Wikipedia pipeline disabled in config" }
        return
    }

    logger.info { "Starting Wikipedia pipeline" }

    // Initialize components
    val embedder = Embedder(config.embedding.serviceUrl)
    val qdrantSink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = config.qdrant.wikipediaCollection,
        vectorSize = config.embedding.vectorSize
    )

    // BookStack sink (optional - only if configured)
    val bookstackSink = if (config.bookstack.enabled) {
        BookStackSink(
            bookstackUrl = config.bookstack.url,
            tokenId = config.bookstack.tokenId,
            tokenSecret = config.bookstack.tokenSecret
        )
    } else null

    // Schedule periodic runs
    while (true) {
        try {
            logger.info { "=== Starting Wikipedia fetch cycle ===" }
            val startTime = System.currentTimeMillis()

            var processed = 0
            var failed = 0
            var deduplicated = 0

            // Load checkpoint to resume from last article
            val metadata = metadataStore.load("wikipedia")
            val articlesProcessed = metadata.totalItemsProcessed

            val source = WikipediaSource(
                dumpPath = config.wikipedia.dumpPath,
                maxArticles = config.wikipedia.maxArticles
            )

            source.fetch()
                .buffer(1000)
                .map { article ->
                    try {
                        // Check deduplication using content hash
                        val hash = article.contentHash()
                        if (dedupStore.checkAndMark(hash, "${article.title}:${article.chunkIndex}")) {
                            deduplicated++
                            if (deduplicated % 1000 == 0) {
                                logger.debug { "Skipped $deduplicated duplicate articles so far" }
                            }
                            return@map null
                        }

                        // Convert to text
                        val text = article.toText()

                        // Generate embedding
                        val vector = embedder.process(text)

                        // Create vector document for Qdrant
                        val qdrantDoc = VectorDocument(
                            id = "${article.title.hashCode()}_${article.chunkIndex}",
                            vector = vector,
                            metadata = mapOf(
                                "title" to article.title,
                                "chunkIndex" to article.chunkIndex.toString(),
                                "isChunk" to article.isChunk.toString(),
                                "originalArticleId" to article.originalArticleId,
                                "text" to article.text.take(1000),
                                "source" to "wikipedia"
                            )
                        )

                        // Create BookStack document (if sink enabled)
                        val bookstackDoc = if (bookstackSink != null) {
                            val firstLetter = article.title.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
                            BookStackDocument(
                                bookName = "Wikipedia",
                                bookDescription = "Wikipedia articles and knowledge",
                                chapterName = firstLetter,
                                chapterDescription = "Articles starting with $firstLetter",
                                pageTitle = article.title + if (article.isChunk) " (Part ${article.chunkIndex + 1})" else "",
                                pageContent = buildWikipediaHtml(article),
                                tags = mapOf(
                                    "source" to "wikipedia",
                                    "title" to article.title,
                                    "isChunk" to article.isChunk.toString(),
                                    "chunkIndex" to article.chunkIndex.toString()
                                )
                            )
                        } else null

                        processed++
                        if (processed % 100 == 0) {
                            logger.info { "Processed $processed Wikipedia articles (chunks)" }
                        }
                        Pair(qdrantDoc, bookstackDoc)
                    } catch (e: Exception) {
                        failed++
                        if (failed % 100 == 0) {
                            logger.error(e) { "Failed to process Wikipedia article (total failures: $failed): ${e.message}" }
                        }
                        null
                    }
                }
                .filterNotNull()
                .collect { (qdrantDoc, bookstackDoc) ->
                    try {
                        qdrantSink.write(qdrantDoc)

                        // Write to BookStack if enabled
                        if (bookstackDoc != null && bookstackSink != null) {
                            try {
                                bookstackSink.write(bookstackDoc)
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to write to BookStack: ${e.message}" }
                            }
                        }
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to write to Qdrant: ${e.message}" }
                    }
                }

            val duration = System.currentTimeMillis() - startTime
            val ioStats = source.getIOStats()
            val embedStats = embedder.getStats()
            logger.info {
                "=== Wikipedia cycle complete: $processed processed, $failed failed, $deduplicated skipped (duplicates) in ${duration}ms | " +
                "Disk IO: ${ioStats.formatBytes()} read from ${ioStats.articlesProcessed} articles | " +
                "Embeddings: ${embedStats.totalRequests} requests, avg ${embedStats.averageLatencyMs}ms ==="
            }

            // Update metadata
            metadataStore.recordSuccess(
                "wikipedia",
                processed.toLong(),
                failed.toLong()
            )
            dedupStore.flush()
            source.resetStats()

            // Wait before next cycle
            val delayMinutes = config.wikipedia.scheduleMinutes
            logger.info { "Next Wikipedia fetch in $delayMinutes minutes" }
            delay(TimeUnit.MINUTES.toMillis(delayMinutes.toLong()))

        } catch (e: CancellationException) {
            logger.info { "Wikipedia pipeline cancelled" }
            break
        } catch (e: Exception) {
            logger.error(e) { "Wikipedia pipeline error: ${e.message}" }
            metadataStore.recordFailure("wikipedia")
            logger.info { "Retrying in 30 minutes..." }
            delay(TimeUnit.MINUTES.toMillis(30))
        }
    }
}

suspend fun runAustralianLawsPipeline(
    config: PipelineConfig,
    dedupStore: DeduplicationStore,
    metadataStore: SourceMetadataStore
) {
    if (!config.australianLaws.enabled) {
        logger.info { "Australian Laws pipeline disabled in config" }
        return
    }

    logger.info { "Starting Australian Laws pipeline" }

    // Initialize components
    val embedder = Embedder(config.embedding.serviceUrl)
    val qdrantSink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = config.qdrant.australianLawsCollection,
        vectorSize = config.embedding.vectorSize
    )

    // BookStack sink (optional - only if configured)
    val bookstackSink = if (config.bookstack.enabled) {
        BookStackSink(
            bookstackUrl = config.bookstack.url,
            tokenId = config.bookstack.tokenId,
            tokenSecret = config.bookstack.tokenSecret
        )
    } else null

    // Schedule periodic runs
    while (true) {
        try {
            logger.info { "=== Starting Australian Laws fetch cycle ===" }
            val startTime = System.currentTimeMillis()

            var processed = 0
            var failed = 0
            var deduplicated = 0

            val source = AustralianLawsSource(
                jurisdictions = listOf(config.australianLaws.jurisdiction),
                maxLaws = config.australianLaws.maxLaws
            )

            source.fetch()
                .buffer(1000)
                .map { law ->
                    try {
                        // Check deduplication using content hash
                        val hash = law.contentHash()
                        if (dedupStore.checkAndMark(hash, law.id)) {
                            deduplicated++
                            logger.debug { "Skipping duplicate law: ${law.id}" }
                            return@map null
                        }

                        // Convert to text
                        val text = law.toText()

                        // Generate embedding
                        val vector = embedder.process(text)

                        // Create vector document for Qdrant
                        val qdrantDoc = VectorDocument(
                            id = law.id,
                            vector = vector,
                            metadata = mapOf(
                                "id" to law.id,
                                "title" to law.title,
                                "jurisdiction" to law.jurisdiction,
                                "year" to law.year,
                                "number" to law.number,
                                "type" to law.type,
                                "url" to law.url,
                                "sectionCount" to law.sections.size.toString(),
                                "source" to "australian_laws"
                            )
                        )

                        // Create BookStack document (if sink enabled)
                        val bookstackDoc = if (bookstackSink != null) {
                            BookStackDocument(
                                bookName = "Australian Legislation",
                                bookDescription = "Australian laws and legislation",
                                chapterName = law.jurisdiction,
                                chapterDescription = "Laws from ${law.jurisdiction}",
                                pageTitle = law.title,
                                pageContent = buildAustralianLawHtml(law),
                                tags = mapOf(
                                    "source" to "australian_laws",
                                    "jurisdiction" to law.jurisdiction,
                                    "year" to law.year,
                                    "type" to law.type
                                )
                            )
                        } else null

                        processed++
                        Pair(qdrantDoc, bookstackDoc)
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to process law: ${e.message}" }
                        null
                    }
                }
                .filterNotNull()
                .collect { (qdrantDoc, bookstackDoc) ->
                    try {
                        qdrantSink.write(qdrantDoc)

                        // Write to BookStack if enabled
                        if (bookstackDoc != null && bookstackSink != null) {
                            try {
                                bookstackSink.write(bookstackDoc)
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to write to BookStack: ${e.message}" }
                            }
                        }
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to write to Qdrant: ${e.message}" }
                    }
                }

            val duration = System.currentTimeMillis() - startTime
            logger.info {
                "=== Australian Laws cycle complete: $processed processed, $failed failed, $deduplicated skipped (duplicates) in ${duration}ms ==="
            }

            // Update metadata
            metadataStore.recordSuccess(
                "australian_laws",
                processed.toLong(),
                failed.toLong()
            )
            dedupStore.flush()

            // Wait before next cycle
            val delayMinutes = config.australianLaws.scheduleMinutes
            logger.info { "Next Australian Laws fetch in $delayMinutes minutes" }
            delay(TimeUnit.MINUTES.toMillis(delayMinutes.toLong()))

        } catch (e: CancellationException) {
            logger.info { "Australian Laws pipeline cancelled" }
            break
        } catch (e: Exception) {
            logger.error(e) { "Australian Laws pipeline error: ${e.message}" }
            metadataStore.recordFailure("australian_laws")
            logger.info { "Retrying in 10 minutes..." }
            delay(TimeUnit.MINUTES.toMillis(10))
        }
    }
}

suspend fun runLinuxDocsPipeline(
    config: PipelineConfig,
    dedupStore: DeduplicationStore,
    metadataStore: SourceMetadataStore
) {
    if (!config.linuxDocs.enabled) {
        logger.info { "Linux Docs pipeline disabled in config" }
        return
    }

    logger.info { "Starting Linux Docs pipeline" }

    // Initialize components
    val embedder = Embedder(config.embedding.serviceUrl)
    val qdrantSink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = config.qdrant.linuxDocsCollection,
        vectorSize = config.embedding.vectorSize
    )

    // BookStack sink (optional - only if configured)
    val bookstackSink = if (config.bookstack.enabled) {
        BookStackSink(
            bookstackUrl = config.bookstack.url,
            tokenId = config.bookstack.tokenId,
            tokenSecret = config.bookstack.tokenSecret
        )
    } else null

    // Schedule periodic runs
    while (true) {
        try {
            logger.info { "=== Starting Linux Docs fetch cycle ===" }
            val startTime = System.currentTimeMillis()

            var processed = 0
            var failed = 0
            var deduplicated = 0

            // Parse doc sources from config
            val docSources = config.linuxDocs.sources.mapNotNull { sourceName ->
                try {
                    LinuxDocsSource.DocSource.valueOf(sourceName.uppercase())
                } catch (e: Exception) {
                    logger.warn { "Unknown doc source: $sourceName" }
                    null
                }
            }

            val source = LinuxDocsSource(
                sources = docSources,
                maxDocs = config.linuxDocs.maxDocs
            )

            source.fetch()
                .buffer(1000)
                .map { doc ->
                    try {
                        // Check deduplication using content hash
                        val hash = doc.contentHash()
                        if (dedupStore.checkAndMark(hash, doc.id)) {
                            deduplicated++
                            if (deduplicated % 1000 == 0) {
                                logger.debug { "Skipped $deduplicated duplicate docs so far" }
                            }
                            return@map null
                        }

                        // Convert to text
                        val text = doc.toText()

                        // Generate embedding
                        val vector = embedder.process(text)

                        // Create vector document for Qdrant
                        val qdrantDoc = VectorDocument(
                            id = doc.id,
                            vector = vector,
                            metadata = mapOf(
                                "id" to doc.id,
                                "title" to doc.title,
                                "section" to doc.section,
                                "type" to doc.type,
                                "path" to doc.path,
                                "source" to "linux_docs"
                            )
                        )

                        // Create BookStack document (if sink enabled)
                        val bookstackDoc = if (bookstackSink != null) {
                            BookStackDocument(
                                bookName = "Linux Documentation",
                                bookDescription = "Linux kernel and system documentation",
                                chapterName = doc.section,
                                chapterDescription = "Documentation for ${doc.section}",
                                pageTitle = doc.title,
                                pageContent = buildLinuxDocHtml(doc),
                                tags = mapOf(
                                    "source" to "linux_docs",
                                    "section" to doc.section,
                                    "type" to doc.type,
                                    "path" to doc.path
                                )
                            )
                        } else null

                        processed++
                        if (processed % 100 == 0) {
                            logger.info { "Processed $processed Linux docs" }
                        }
                        Pair(qdrantDoc, bookstackDoc)
                    } catch (e: Exception) {
                        failed++
                        if (failed % 100 == 0) {
                            logger.error(e) { "Failed to process Linux doc (total failures: $failed): ${e.message}" }
                        }
                        null
                    }
                }
                .filterNotNull()
                .collect { (qdrantDoc, bookstackDoc) ->
                    try {
                        qdrantSink.write(qdrantDoc)

                        // Write to BookStack if enabled
                        if (bookstackDoc != null && bookstackSink != null) {
                            try {
                                bookstackSink.write(bookstackDoc)
                            } catch (e: Exception) {
                                logger.warn(e) { "Failed to write to BookStack: ${e.message}" }
                            }
                        }
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to write to Qdrant: ${e.message}" }
                    }
                }

            val duration = System.currentTimeMillis() - startTime
            logger.info {
                "=== Linux Docs cycle complete: $processed processed, $failed failed, $deduplicated skipped (duplicates) in ${duration}ms ==="
            }

            // Update metadata
            metadataStore.recordSuccess(
                "linux_docs",
                processed.toLong(),
                failed.toLong()
            )
            dedupStore.flush()

            // Wait before next cycle
            val delayMinutes = config.linuxDocs.scheduleMinutes
            logger.info { "Next Linux Docs fetch in $delayMinutes minutes" }
            delay(TimeUnit.MINUTES.toMillis(delayMinutes.toLong()))

        } catch (e: CancellationException) {
            logger.info { "Linux Docs pipeline cancelled" }
            break
        } catch (e: Exception) {
            logger.error(e) { "Linux Docs pipeline error: ${e.message}" }
            metadataStore.recordFailure("linux_docs")
            logger.info { "Retrying in 30 minutes..." }
            delay(TimeUnit.MINUTES.toMillis(30))
        }
    }
}

// Helper functions to build HTML content for BookStack pages
internal fun buildRssArticleHtml(article: RssArticle): String {
    return """
        <h1>${escapeHtml(article.title)}</h1>

        <div style="background-color: #f5f5f5; padding: 10px; border-left: 4px solid #4CAF50; margin-bottom: 20px;">
            <p><strong>Source:</strong> ${escapeHtml(article.feedTitle)}</p>
            <p><strong>Published:</strong> ${escapeHtml(article.publishedDate)}</p>
            ${if (article.author.isNotBlank()) "<p><strong>Author:</strong> ${escapeHtml(article.author)}</p>" else ""}
            ${if (article.categories.isNotEmpty()) "<p><strong>Categories:</strong> ${article.categories.joinToString(", ") { escapeHtml(it) }}</p>" else ""}
            <p><strong>Original Link:</strong> <a href="${escapeHtml(article.link)}" target="_blank">${escapeHtml(article.link)}</a></p>
        </div>

        <div>
            ${article.description}
        </div>
    """.trimIndent()
}

internal fun buildCveHtml(cve: CveEntry): String {
    val severityColor = when (cve.severity.uppercase()) {
        "CRITICAL" -> "#d32f2f"
        "HIGH" -> "#f57c00"
        "MEDIUM" -> "#fbc02d"
        "LOW" -> "#388e3c"
        else -> "#757575"
    }

    return """
        <h1>${escapeHtml(cve.cveId)}</h1>

        <div style="background-color: #f5f5f5; padding: 10px; border-left: 4px solid $severityColor; margin-bottom: 20px;">
            <p><strong>Severity:</strong> <span style="color: $severityColor; font-weight: bold;">${escapeHtml(cve.severity)}</span></p>
            ${if (cve.baseScore != null) "<p><strong>CVSS Score:</strong> ${cve.baseScore}</p>" else ""}
            <p><strong>Published:</strong> ${escapeHtml(cve.publishedDate)}</p>
            <p><strong>Last Modified:</strong> ${escapeHtml(cve.lastModifiedDate)}</p>
        </div>

        <h2>Description</h2>
        <p>${escapeHtml(cve.description)}</p>

        ${if (cve.affectedProducts.isNotEmpty()) """
            <h2>Affected Products</h2>
            <ul>
                ${cve.affectedProducts.take(20).joinToString("\n") { "<li>${escapeHtml(it)}</li>" }}
                ${if (cve.affectedProducts.size > 20) "<li><em>... and ${cve.affectedProducts.size - 20} more</em></li>" else ""}
            </ul>
        """ else ""}
    """.trimIndent()
}

internal fun buildWikipediaHtml(article: WikipediaArticle): String {
    return """
        <h1>${escapeHtml(article.title)}</h1>

        ${if (article.isChunk) """
            <div style="background-color: #fff3cd; padding: 10px; border-left: 4px solid #ff9800; margin-bottom: 20px;">
                <p><strong>Note:</strong> This is chunk ${article.chunkIndex + 1} of a larger article.</p>
                <p><strong>Original Article ID:</strong> ${escapeHtml(article.originalArticleId)}</p>
            </div>
        """ else ""}

        <div>
            ${escapeHtml(article.text).replace("\n", "<br>")}
        </div>
    """.trimIndent()
}

internal fun buildLinuxDocHtml(doc: LinuxDoc): String {
    return """
        <h1>${escapeHtml(doc.title)}</h1>

        <div style="background-color: #f5f5f5; padding: 10px; border-left: 4px solid #2196F3; margin-bottom: 20px;">
            <p><strong>Section:</strong> ${escapeHtml(doc.section)}</p>
            <p><strong>Type:</strong> ${escapeHtml(doc.type)}</p>
            <p><strong>Path:</strong> <code>${escapeHtml(doc.path)}</code></p>
        </div>

        <div>
            <pre style="background-color: #f5f5f5; padding: 15px; border-radius: 5px; overflow-x: auto;">${escapeHtml(doc.content)}</pre>
        </div>
    """.trimIndent()
}

internal fun buildAustralianLawHtml(law: AustralianLaw): String {
    return """
        <h1>${escapeHtml(law.title)}</h1>

        <div style="background-color: #f5f5f5; padding: 10px; border-left: 4px solid #673ab7; margin-bottom: 20px;">
            <p><strong>Jurisdiction:</strong> ${escapeHtml(law.jurisdiction)}</p>
            <p><strong>Year:</strong> ${escapeHtml(law.year)}</p>
            <p><strong>Number:</strong> ${escapeHtml(law.number)}</p>
            <p><strong>Type:</strong> ${escapeHtml(law.type)}</p>
            <p><strong>URL:</strong> <a href="${escapeHtml(law.url)}" target="_blank">${escapeHtml(law.url)}</a></p>
        </div>

        ${if (law.sections.isNotEmpty()) """
            <h2>Sections</h2>
            ${law.sections.joinToString("\n\n") { section ->
                """
                <div style="margin-bottom: 20px; padding: 10px; background-color: #fafafa; border-left: 2px solid #ddd;">
                    <h3>${escapeHtml(section.title)}</h3>
                    <p>${escapeHtml(section.text)}</p>
                </div>
                """.trimIndent()
            }}
        """ else ""}
    """.trimIndent()
}

internal fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
}
