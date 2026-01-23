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
    val sink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = config.qdrant.rssCollection,
        vectorSize = config.embedding.vectorSize
    )

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

                        // Create vector document
                        val doc = VectorDocument(
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

                        processed++
                        doc
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to process article: ${e.message}" }
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
                "=== RSS cycle complete: $processed processed, $failed failed, $deduplicated skipped (duplicates) in ${duration}ms ==="
            }

            // Update metadata
            metadataStore.recordSuccess("rss", processed.toLong(), failed.toLong())
            dedupStore.flush()

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
    val sink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = config.qdrant.cveCollection,
        vectorSize = config.embedding.vectorSize
    )

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

                        // Create vector document
                        val doc = VectorDocument(
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

                        processed++
                        doc
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to process CVE: ${e.message}" }
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
    val sink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = config.qdrant.wikipediaCollection,
        vectorSize = config.embedding.vectorSize
    )

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

                        // Create vector document
                        val doc = VectorDocument(
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

                        processed++
                        if (processed % 100 == 0) {
                            logger.info { "Processed $processed Wikipedia articles (chunks)" }
                        }
                        doc
                    } catch (e: Exception) {
                        failed++
                        if (failed % 100 == 0) {
                            logger.error(e) { "Failed to process Wikipedia article (total failures: $failed): ${e.message}" }
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
                "=== Wikipedia cycle complete: $processed processed, $failed failed, $deduplicated skipped (duplicates) in ${duration}ms ==="
            }

            // Update metadata
            metadataStore.recordSuccess(
                "wikipedia",
                processed.toLong(),
                failed.toLong()
            )
            dedupStore.flush()

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
    val sink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = config.qdrant.australianLawsCollection,
        vectorSize = config.embedding.vectorSize
    )

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

                        // Create vector document
                        val doc = VectorDocument(
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

                        processed++
                        doc
                    } catch (e: Exception) {
                        failed++
                        logger.error(e) { "Failed to process law: ${e.message}" }
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
    val sink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = config.qdrant.linuxDocsCollection,
        vectorSize = config.embedding.vectorSize
    )

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

                        // Create vector document
                        val vecDoc = VectorDocument(
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

                        processed++
                        if (processed % 100 == 0) {
                            logger.info { "Processed $processed Linux docs" }
                        }
                        vecDoc
                    } catch (e: Exception) {
                        failed++
                        if (failed % 100 == 0) {
                            logger.error(e) { "Failed to process Linux doc (total failures: $failed): ${e.message}" }
                        }
                        null
                    }
                }
                .filterNotNull()
                .collect { vecDoc ->
                    try {
                        sink.write(vecDoc)
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
