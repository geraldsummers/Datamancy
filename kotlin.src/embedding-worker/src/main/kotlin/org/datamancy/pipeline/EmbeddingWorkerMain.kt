package org.datamancy.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.datamancy.pipeline.config.PipelineConfig
import org.datamancy.pipeline.embedding.EmbeddingScheduler
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.storage.DocumentStagingStore

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "🧠 Embedding worker starting" }

    val config = PipelineConfig.fromEnv()

    val stagingStore = DocumentStagingStore(
        jdbcUrl = config.postgres.jdbcUrl,
        user = config.postgres.user,
        dbPassword = config.postgres.password
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        try {
            stagingStore.close()
        } catch (e: Exception) {
            logger.error(e) { "Error during shutdown: ${e.message}" }
        }
    })

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

        awaitCancellation()
    }
}
