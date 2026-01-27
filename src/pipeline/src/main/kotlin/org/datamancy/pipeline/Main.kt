package org.datamancy.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.datamancy.pipeline.config.PipelineConfig
import org.datamancy.pipeline.core.StandardizedRunner
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.sinks.BookStackSink
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.sources.standardized.*
import org.datamancy.pipeline.storage.DeduplicationStore
import org.datamancy.pipeline.storage.SourceMetadataStore
import org.datamancy.pipeline.monitoring.MonitoringServer

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "🔥 Datamancy Pipeline Service Starting..." }

    // Load configuration
    val config = PipelineConfig.fromEnv()
    logger.info { "Configuration loaded: ${config.rss.feedUrls.size} RSS feeds configured" }

    // Initialize shared infrastructure
    val dedupStore = DeduplicationStore()
    val metadataStore = SourceMetadataStore()

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

    // Start monitoring HTTP server and pipelines
    runBlocking {
        // Start monitoring HTTP server in background coroutine
        val monitoringServer = MonitoringServer(port = 8090, metadataStore = metadataStore)
        monitoringServer.start()

        // Give monitoring server time to start
        delay(1000)

        // Launch all standardized sources
        if (config.rss.enabled) {
            launch { runStandardizedSource("RSS", config, dedupStore, metadataStore, bookStackSink) {
                RssStandardizedSource(
                    feedUrls = config.rss.feedUrls,
                    backfillDays = 7
                )
            } }
        }

        if (config.cve.enabled) {
            launch { runStandardizedSource("CVE", config, dedupStore, metadataStore, bookStackSink) {
                CveStandardizedSource(
                    apiKey = config.cve.apiKey,
                    maxResults = config.cve.maxResults
                )
            } }
        }

        if (config.torrents.enabled) {
            launch { runStandardizedSource("Torrents", config, dedupStore, metadataStore, bookStackSink) {
                TorrentsStandardizedSource(
                    dataPath = config.torrents.dataPath,
                    maxTorrents = config.torrents.maxResults
                )
            } }
        }

        if (config.wikipedia.enabled) {
            launch { runStandardizedSource("Wikipedia", config, dedupStore, metadataStore, bookStackSink) {
                WikipediaStandardizedSource(
                    dumpUrl = config.wikipedia.dumpPath,
                    maxArticles = config.wikipedia.maxArticles
                )
            } }
        }

        if (config.australianLaws.enabled) {
            launch { runStandardizedSource("Australian Laws", config, dedupStore, metadataStore, bookStackSink) {
                OpenAustralianLegalCorpusStandardizedSource(
                    cacheDir = "/data/australian-legal-corpus",
                    jurisdictions = if (config.australianLaws.jurisdictions.isNotEmpty())
                        config.australianLaws.jurisdictions else null,
                    maxDocuments = config.australianLaws.maxLawsPerJurisdiction * 100  // Scale up for full corpus
                )
            } }
        }

        if (config.linuxDocs.enabled) {
            launch { runStandardizedSource("Linux Docs", config, dedupStore, metadataStore, bookStackSink) {
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
                launch { runStandardizedSource("Debian Wiki", config, dedupStore, metadataStore, bookStackSink) {
                    DebianWikiStandardizedSource(
                        maxPages = config.wiki.maxPagesPerWiki,
                        categories = config.wiki.categories
                    )
                } }
            }

            // Launch Arch Wiki
            if (config.wiki.wikiTypes.any { it.equals("arch", ignoreCase = true) }) {
                launch { runStandardizedSource("Arch Wiki", config, dedupStore, metadataStore, bookStackSink) {
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
 */
suspend fun <T : org.datamancy.pipeline.core.Chunkable> runStandardizedSource(
    displayName: String,
    config: PipelineConfig,
    dedupStore: DeduplicationStore,
    metadataStore: SourceMetadataStore,
    bookStackSink: BookStackSink?,
    sourceFactory: () -> org.datamancy.pipeline.core.StandardizedSource<T>
) {
    logger.info { "Launching $displayName pipeline with standardized runner" }

    val source = sourceFactory()

    // Determine collection name based on source name
    val collectionName = when (source.name) {
        "rss" -> config.qdrant.rssCollection
        "cve" -> config.qdrant.cveCollection
        "torrents" -> config.qdrant.torrentsCollection
        "wikipedia" -> config.qdrant.wikipediaCollection
        "australian_laws" -> config.qdrant.australianLawsCollection
        "linux_docs" -> config.qdrant.linuxDocsCollection
        "debian_wiki" -> config.qdrant.debianWikiCollection
        "arch_wiki" -> config.qdrant.archWikiCollection
        else -> throw IllegalArgumentException("Unknown source: ${source.name}")
    }

    val embedder = Embedder(
        serviceUrl = config.embedding.serviceUrl,
        maxTokens = config.embedding.maxTokens
    )
    val qdrantSink = QdrantSink(
        qdrantUrl = config.qdrant.url,
        collectionName = collectionName,
        vectorSize = config.embedding.vectorSize
    )

    val runner = StandardizedRunner(
        source = source,
        qdrantSink = qdrantSink,
        embedder = embedder,
        dedupStore = dedupStore,
        metadataStore = metadataStore,
        bookStackSink = bookStackSink
    )

    runner.run()
}
