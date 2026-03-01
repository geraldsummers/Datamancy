package org.datamancy.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.datamancy.pipeline.config.PipelineConfig
import org.datamancy.pipeline.core.StandardizedRunner
import org.datamancy.pipeline.monitoring.MonitoringServer
import org.datamancy.pipeline.monitoring.ProgressReporter
import org.datamancy.pipeline.sources.standardized.*
import org.datamancy.pipeline.storage.DeduplicationStore
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.SourceMetadataStore

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "🔥 Knowledge ingestion starting" }

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

    runBlocking {
        val monitoringServer = MonitoringServer(
            port = 8090,
            metadataStore = metadataStore,
            stagingStore = stagingStore
        )
        monitoringServer.start()

        delay(1000)

        val progressReporter = ProgressReporter(
            stagingStore = stagingStore,
            reportIntervalSeconds = 30
        )

        launch {
            progressReporter.start()
        }

        if (config.rss.enabled) {
            launch {
                runStandardizedSource(config.qdrant.rssCollection, stagingStore, dedupStore, metadataStore) {
                    RssStandardizedSource(
                        feedUrls = config.rss.feedUrls,
                        backfillDays = 7
                    )
                }
            }
        }

        if (config.cve.enabled) {
            launch {
                runStandardizedSource(config.qdrant.cveCollection, stagingStore, dedupStore, metadataStore) {
                    CveStandardizedSource(
                        apiKey = config.cve.apiKey,
                        maxResults = config.cve.maxResults
                    )
                }
            }
        }

        if (config.torrents.enabled) {
            launch {
                runStandardizedSource(config.qdrant.torrentsCollection, stagingStore, dedupStore, metadataStore) {
                    TorrentsStandardizedSource(
                        dataPath = config.torrents.dataPath,
                        maxTorrents = config.torrents.maxResults
                    )
                }
            }
        }

        if (config.wikipedia.enabled) {
            launch {
                runStandardizedSource(config.qdrant.wikipediaCollection, stagingStore, dedupStore, metadataStore) {
                    WikipediaStandardizedSource(
                        dumpUrl = config.wikipedia.dumpPath,
                        maxArticles = config.wikipedia.maxArticles
                    )
                }
            }
        }

        if (config.australianLaws.enabled) {
            launch {
                runStandardizedSource(config.qdrant.australianLawsCollection, stagingStore, dedupStore, metadataStore) {
                    OpenAustralianLegalCorpusStandardizedSource(
                        cacheDir = "/data/australian-legal-corpus",
                        jurisdictions = if (config.australianLaws.jurisdictions.isNotEmpty())
                            config.australianLaws.jurisdictions else null,
                        maxDocuments = config.australianLaws.maxLawsPerJurisdiction * 100
                    )
                }
            }
        }

        if (config.linuxDocs.enabled) {
            launch {
                runStandardizedSource(config.qdrant.linuxDocsCollection, stagingStore, dedupStore, metadataStore) {
                    LinuxDocsStandardizedSource(
                        sources = config.linuxDocs.sources.mapNotNull {
                            try {
                                org.datamancy.pipeline.sources.LinuxDocsSource.DocSource.valueOf(it.uppercase())
                            } catch (e: Exception) { null }
                        },
                        maxDocs = config.linuxDocs.maxDocs
                    )
                }
            }
        }

        if (config.wiki.enabled) {
            if (config.wiki.wikiTypes.any { it.equals("debian", ignoreCase = true) }) {
                launch {
                    runStandardizedSource(config.qdrant.debianWikiCollection, stagingStore, dedupStore, metadataStore) {
                        DebianWikiStandardizedSource(
                            maxPages = config.wiki.maxPagesPerWiki,
                            categories = config.wiki.categories
                        )
                    }
                }
            }

            if (config.wiki.wikiTypes.any { it.equals("arch", ignoreCase = true) }) {
                launch {
                    runStandardizedSource(config.qdrant.archWikiCollection, stagingStore, dedupStore, metadataStore) {
                        ArchWikiStandardizedSource(
                            maxPages = config.wiki.maxPagesPerWiki,
                            categories = config.wiki.categories
                        )
                    }
                }
            }
        }

        awaitCancellation()
    }
}

suspend fun <T : org.datamancy.pipeline.core.Chunkable> runStandardizedSource(
    collectionName: String,
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
