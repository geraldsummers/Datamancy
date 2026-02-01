package org.datamancy.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.datamancy.pipeline.config.PipelineConfig
import org.datamancy.pipeline.core.StandardizedRunner
import org.datamancy.pipeline.embedding.EmbeddingScheduler
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.sinks.BookStackSink
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.sources.standardized.*
import org.datamancy.pipeline.storage.DeduplicationStore
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.SourceMetadataStore
import org.datamancy.pipeline.monitoring.MonitoringServer

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "🔥 Datamancy Pipeline Service Starting (DECOUPLED ARCHITECTURE)..." }

    // Load configuration
    val config = PipelineConfig.fromEnv()
    logger.info { "Configuration loaded: ${config.rss.feedUrls.size} RSS feeds configured" }

    // Initialize shared infrastructure
    val dedupStore = DeduplicationStore()
    val metadataStore = SourceMetadataStore()

    // NEW: Initialize ClickHouse document staging store
    val stagingStore = DocumentStagingStore(
        clickhouseUrl = config.clickhouse.url,
        user = config.clickhouse.user,
        password = config.clickhouse.password
    )
    logger.info { "📦 ClickHouse document staging initialized: ${config.clickhouse.url}" }

    // Add shutdown hook for graceful cleanup
    Runtime.getRuntime().addShutdownHook(Thread {
        logger.info { "Shutdown signal received, cleaning up resources..." }
        try {
            dedupStore.flush()
            stagingStore.close()
            logger.info { "Resources cleaned up successfully" }
        } catch (e: Exception) {
            logger.error(e) { "Error during shutdown: ${e.message}" }
        }
    })

    // Initialize BookStack sink if enabled
    val bookStackSink = if (config.bookstack.enabled) {
        logger.info { "BookStack integration enabled: ${config.bookstack.url}" }
        BookStackSink(
            bookstackUrl = config.bookstack.url,
            tokenId = config.bookstack.tokenId,
            tokenSecret = config.bookstack.tokenSecret
        )
    } else {
        logger.info { "BookStack integration disabled" }
        null
    }

    // NEW: Initialize embedder and Qdrant sinks for embedding scheduler
    val embedder = Embedder(
        serviceUrl = config.embedding.serviceUrl,
        maxTokens = config.embedding.maxTokens
    )

    val qdrantSinks = mapOf(
        config.qdrant.rssCollection to QdrantSink(config.qdrant.url, config.qdrant.rssCollection, config.embedding.vectorSize),
        config.qdrant.cveCollection to QdrantSink(config.qdrant.url, config.qdrant.cveCollection, config.embedding.vectorSize),
        config.qdrant.torrentsCollection to QdrantSink(config.qdrant.url, config.qdrant.torrentsCollection, config.embedding.vectorSize),
        config.qdrant.wikipediaCollection to QdrantSink(config.qdrant.url, config.qdrant.wikipediaCollection, config.embedding.vectorSize),
        config.qdrant.australianLawsCollection to QdrantSink(config.qdrant.url, config.qdrant.australianLawsCollection, config.embedding.vectorSize),
        config.qdrant.linuxDocsCollection to QdrantSink(config.qdrant.url, config.qdrant.linuxDocsCollection, config.embedding.vectorSize),
        config.qdrant.debianWikiCollection to QdrantSink(config.qdrant.url, config.qdrant.debianWikiCollection, config.embedding.vectorSize),
        config.qdrant.archWikiCollection to QdrantSink(config.qdrant.url, config.qdrant.archWikiCollection, config.embedding.vectorSize)
    )

    logger.info { "🧠 Embedding service configured: ${config.embedding.serviceUrl}" }
    logger.info { "📊 Qdrant sinks initialized for ${qdrantSinks.size} collections" }

    // Start monitoring HTTP server and pipelines
    runBlocking {
        // Start monitoring HTTP server in background coroutine
        val monitoringServer = MonitoringServer(
            port = 8090,
            metadataStore = metadataStore,
            stagingStore = stagingStore  // NEW: Pass staging store for queue monitoring
        )
        monitoringServer.start()

        // Give monitoring server time to start
        delay(1000)

        // NEW: Start embedding scheduler in background
        val embeddingScheduler = EmbeddingScheduler(
            stagingStore = stagingStore,
            embedder = embedder,
            qdrantSinks = qdrantSinks,
            batchSize = 50,
            pollInterval = 10,  // Check every 10 seconds
            maxConcurrentEmbeddings = 10
        )

        launch {
            logger.info { "🚀 Starting embedding scheduler..." }
            embeddingScheduler.start()
        }

        delay(1000)

        // Launch all standardized sources (now they stage to ClickHouse instead of direct embedding)
        if (config.rss.enabled) {
            launch { runStandardizedSource("RSS", config.qdrant.rssCollection, config, stagingStore, dedupStore, metadataStore, bookStackSink) {
                RssStandardizedSource(
                    feedUrls = config.rss.feedUrls,
                    backfillDays = 7
                )
            } }
        }

        if (config.cve.enabled) {
            launch { runStandardizedSource("CVE", config.qdrant.cveCollection, config, stagingStore, dedupStore, metadataStore, bookStackSink) {
                CveStandardizedSource(
                    apiKey = config.cve.apiKey,
                    maxResults = config.cve.maxResults
                )
            } }
        }

        if (config.torrents.enabled) {
            launch { runStandardizedSource("Torrents", config.qdrant.torrentsCollection, config, stagingStore, dedupStore, metadataStore, bookStackSink) {
                TorrentsStandardizedSource(
                    dataPath = config.torrents.dataPath,
                    maxTorrents = config.torrents.maxResults
                )
            } }
        }

        if (config.wikipedia.enabled) {
            launch { runStandardizedSource("Wikipedia", config.qdrant.wikipediaCollection, config, stagingStore, dedupStore, metadataStore, bookStackSink) {
                WikipediaStandardizedSource(
                    dumpUrl = config.wikipedia.dumpPath,
                    maxArticles = config.wikipedia.maxArticles
                )
            } }
        }

        if (config.australianLaws.enabled) {
            launch { runStandardizedSource("Australian Laws", config.qdrant.australianLawsCollection, config, stagingStore, dedupStore, metadataStore, bookStackSink) {
                OpenAustralianLegalCorpusStandardizedSource(
                    cacheDir = "/data/australian-legal-corpus",
                    jurisdictions = if (config.australianLaws.jurisdictions.isNotEmpty())
                        config.australianLaws.jurisdictions else null,
                    maxDocuments = config.australianLaws.maxLawsPerJurisdiction * 100  // Scale up for full corpus
                )
            } }
        }

        if (config.linuxDocs.enabled) {
            launch { runStandardizedSource("Linux Docs", config.qdrant.linuxDocsCollection, config, stagingStore, dedupStore, metadataStore, bookStackSink) {
                LinuxDocsStandardizedSource(
                    sources = config.linuxDocs.sources.mapNotNull {
                        try {
                            org.datamancy.pipeline.sources.LinuxDocsSource.DocSource.valueOf(it.uppercase())
                        } catch (e: Exception) { null }
                    },
                    maxDocs = config.linuxDocs.maxDocs
                )
            } }
        }

        if (config.wiki.enabled) {
            // Launch Debian Wiki
            if (config.wiki.wikiTypes.any { it.equals("debian", ignoreCase = true) }) {
                launch { runStandardizedSource("Debian Wiki", config.qdrant.debianWikiCollection, config, stagingStore, dedupStore, metadataStore, bookStackSink) {
                    DebianWikiStandardizedSource(
                        maxPages = config.wiki.maxPagesPerWiki,
                        categories = config.wiki.categories
                    )
                } }
            }

            // Launch Arch Wiki
            if (config.wiki.wikiTypes.any { it.equals("arch", ignoreCase = true) }) {
                launch { runStandardizedSource("Arch Wiki", config.qdrant.archWikiCollection, config, stagingStore, dedupStore, metadataStore, bookStackSink) {
                    ArchWikiStandardizedSource(
                        maxPages = config.wiki.maxPagesPerWiki,
                        categories = config.wiki.categories
                    )
                } }
            }
        }

        // Keep running
        awaitCancellation()
    }
}

/**
 * Generic function to run any standardized source
 * All sources go through StandardizedRunner - no more ad-hoc loops!
 *
 * NEW: Uses decoupled architecture - stages to ClickHouse instead of direct embedding
 */
suspend fun <T : org.datamancy.pipeline.core.Chunkable> runStandardizedSource(
    displayName: String,
    collectionName: String,              // Explicit collection name
    config: PipelineConfig,
    stagingStore: DocumentStagingStore,  // NEW: ClickHouse staging
    dedupStore: DeduplicationStore,
    metadataStore: SourceMetadataStore,
    bookStackSink: BookStackSink?,
    sourceFactory: () -> org.datamancy.pipeline.core.StandardizedSource<T>
) {
    logger.info { "Launching $displayName pipeline with standardized runner (DECOUPLED)" }

    val source = sourceFactory()

    val runner = StandardizedRunner(
        source = source,
        collectionName = collectionName,
        stagingStore = stagingStore,
        dedupStore = dedupStore,
        metadataStore = metadataStore,
        bookStackSink = bookStackSink
    )

    runner.run()
}
