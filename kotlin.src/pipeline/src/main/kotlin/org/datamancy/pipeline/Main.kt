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

    
    val config = PipelineConfig.fromEnv()

    
    val dedupStore = DeduplicationStore()
    val metadataStore = SourceMetadataStore()

    
    val stagingStore = DocumentStagingStore(
        jdbcUrl = config.postgres.jdbcUrl,
        user = config.postgres.user,
        dbPassword = config.postgres.password
    )

    
    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            dedupStore.flush()
            stagingStore.close()
        } catch (e: Exception) {
            logger.error(e) { "Error during shutdown: ${e.message}" }
        }
    })

    
    val bookStackSink = if (config.bookstack.enabled) {
        BookStackSink(
            bookstackUrl = config.bookstack.url,
            tokenId = config.bookstack.tokenId,
            tokenSecret = config.bookstack.tokenSecret
        )
    } else {
        null
    }

    
    val embedder = Embedder(
        serviceUrl = config.embedding.serviceUrl,
        maxTokens = config.embedding.maxTokens
    )

    val qdrantSinks = mapOf(
        config.qdrant.rssCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.rssCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.cveCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.cveCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.torrentsCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.torrentsCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.wikipediaCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.wikipediaCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.australianLawsCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.australianLawsCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.linuxDocsCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.linuxDocsCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.debianWikiCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.debianWikiCollection, config.embedding.vectorSize, config.qdrant.apiKey),
        config.qdrant.archWikiCollection to QdrantSink(config.qdrant.host, config.qdrant.port, config.qdrant.archWikiCollection, config.embedding.vectorSize, config.qdrant.apiKey)
    )


    
    runBlocking {
        
        val monitoringServer = MonitoringServer(
            port = 8090,
            metadataStore = metadataStore,
            stagingStore = stagingStore  
        )
        monitoringServer.start()

        
        delay(1000)

        
        val progressReporter = org.datamancy.pipeline.monitoring.ProgressReporter(
            stagingStore = stagingStore,
            reportIntervalSeconds = 30
        )

        launch {
            progressReporter.start()
        }

        
        val embeddingScheduler = EmbeddingScheduler(
            stagingStore = stagingStore,
            embedder = embedder,
            qdrantSinks = qdrantSinks,
            batchSize = 50,
            pollInterval = 10,  
            maxConcurrentEmbeddings = 10
        )

        launch {
            embeddingScheduler.start()
        }

        delay(1000)

        
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
                    maxDocuments = config.australianLaws.maxLawsPerJurisdiction * 100  
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
            
            if (config.wiki.wikiTypes.any { it.equals("debian", ignoreCase = true) }) {
                launch { runStandardizedSource("Debian Wiki", config.qdrant.debianWikiCollection, config, stagingStore, dedupStore, metadataStore) {
                    DebianWikiStandardizedSource(
                        maxPages = config.wiki.maxPagesPerWiki,
                        categories = config.wiki.categories
                    )
                } }
            }

            
            if (config.wiki.wikiTypes.any { it.equals("arch", ignoreCase = true) }) {
                launch { runStandardizedSource("Arch Wiki", config.qdrant.archWikiCollection, config, stagingStore, dedupStore, metadataStore) {
                    ArchWikiStandardizedSource(
                        maxPages = config.wiki.maxPagesPerWiki,
                        categories = config.wiki.categories
                    )
                } }
            }
        }

        
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

        
        awaitCancellation()
    }
}


suspend fun <T : org.datamancy.pipeline.core.Chunkable> runStandardizedSource(
    displayName: String,
    collectionName: String,              
    config: PipelineConfig,
    stagingStore: DocumentStagingStore,  
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
