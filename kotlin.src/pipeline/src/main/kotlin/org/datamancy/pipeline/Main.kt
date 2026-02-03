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
    logger.info { "🔥 Pipeline starting" }

    // Load configuration
    val config = PipelineConfig.fromEnv()

    // Initialize shared infrastructure
    val dedupStore = DeduplicationStore()
    val metadataStore = SourceMetadataStore()

    // NEW: Initialize PostgreSQL document staging store
    val stagingStore = DocumentStagingStore(
        jdbcUrl = config.postgres.jdbcUrl,
        user = config.postgres.user,
        dbPassword = config.postgres.password
    )

    // Add shutdown hook for graceful cleanup
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            dedupStore.flush()
            stagingStore.close()
        } catch (e: Exception) {
            logger.error(e) { "Error during shutdown: ${e.message}" }
        }
    })

    // Initialize BookStack sink if enabled
    val bookStackSink = if (config.bookstack.enabled) {
        BookStackSink(
            bookstackUrl = config.bookstack.url,
            tokenId = config.bookstack.tokenId,
            tokenSecret = config.bookstack.tokenSecret
        )
    } else {
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

        // Start unified progress reporter
        val progressReporter = org.datamancy.pipeline.monitoring.ProgressReporter(
            stagingStore = stagingStore,
            reportIntervalSeconds = 30
        )

        launch {
            progressReporter.start()
        }

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
            embeddingScheduler.start()
        }

        delay(1000)

        // Launch all standardized sources (now they stage to PostgreSQL instead of direct embedding)
        if (config.rss.enabled) {
            launch { runStandardizedSource("RSS", config.qdrant.rssCollection, config, stagingStore, dedupStore, metadataStore) {
                RssStandardizedSource(
                    feedUrls = config.rss.feedUrls,
                    backfillDays = 7
                )
            } }
        }

        if (config.cve.enabled) {
            launch { runStandardizedSource("CVE", config.qdrant.cveCollection, config, stagingStore, dedupStore, metadataStore) {
                CveStandardizedSource(
                    apiKey = config.cve.apiKey,
                    maxResults = config.cve.maxResults
                )
            } }
        }

        if (config.torrents.enabled) {
            launch { runStandardizedSource("Torrents", config.qdrant.torrentsCollection, config, stagingStore, dedupStore, metadataStore) {
                TorrentsStandardizedSource(
                    dataPath = config.torrents.dataPath,
                    maxTorrents = config.torrents.maxResults
                )
            } }
        }

        if (config.wikipedia.enabled) {
            launch { runStandardizedSource("Wikipedia", config.qdrant.wikipediaCollection, config, stagingStore, dedupStore, metadataStore) {
                WikipediaStandardizedSource(
                    dumpUrl = config.wikipedia.dumpPath,
                    maxArticles = config.wikipedia.maxArticles
                )
            } }
        }

        if (config.australianLaws.enabled) {
            launch { runStandardizedSource("Australian Laws", config.qdrant.australianLawsCollection, config, stagingStore, dedupStore, metadataStore) {
                OpenAustralianLegalCorpusStandardizedSource(
                    cacheDir = "/data/australian-legal-corpus",
                    jurisdictions = if (config.australianLaws.jurisdictions.isNotEmpty())
                        config.australianLaws.jurisdictions else null,
                    maxDocuments = config.australianLaws.maxLawsPerJurisdiction * 100  // Scale up for full corpus
                )
            } }
        }

        if (config.linuxDocs.enabled) {
            launch { runStandardizedSource("Linux Docs", config.qdrant.linuxDocsCollection, config, stagingStore, dedupStore, metadataStore) {
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
                launch { runStandardizedSource("Debian Wiki", config.qdrant.debianWikiCollection, config, stagingStore, dedupStore, metadataStore) {
                    DebianWikiStandardizedSource(
                        maxPages = config.wiki.maxPagesPerWiki,
                        categories = config.wiki.categories
                    )
                } }
            }

            // Launch Arch Wiki
            if (config.wiki.wikiTypes.any { it.equals("arch", ignoreCase = true) }) {
                launch { runStandardizedSource("Arch Wiki", config.qdrant.archWikiCollection, config, stagingStore, dedupStore, metadataStore) {
                    ArchWikiStandardizedSource(
                        maxPages = config.wiki.maxPagesPerWiki,
                        categories = config.wiki.categories
                    )
                } }
            }
        }

        // Launch BookStack writer if enabled
        if (bookStackSink != null) {
            launch {
                val bookStackWriter = org.datamancy.pipeline.workers.BookStackWriter(
                    stagingStore = stagingStore,
                    bookStackSink = bookStackSink,
                    pollIntervalSeconds = 5,
                    batchSize = 50
                )
                bookStackWriter.start()
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
 * NEW: Uses decoupled architecture - stages to PostgreSQL instead of direct embedding
 */
suspend fun <T : org.datamancy.pipeline.core.Chunkable> runStandardizedSource(
    displayName: String,
    collectionName: String,              // Explicit collection name
    config: PipelineConfig,
    stagingStore: DocumentStagingStore,  // NEW: PostgreSQL staging
    dedupStore: DeduplicationStore,
    metadataStore: SourceMetadataStore,
    sourceFactory: () -> org.datamancy.pipeline.core.StandardizedSource<T>
) {
    val source = sourceFactory()

    val runner = StandardizedRunner(
        source = source,
        collectionName = collectionName,
        stagingStore = stagingStore,
        dedupStore = dedupStore,
        metadataStore = metadataStore
    )

    runner.run()
}
