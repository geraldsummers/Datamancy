package org.datamancy.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.*
import org.datamancy.pipeline.config.PipelineConfig
import org.datamancy.pipeline.embedding.EmbeddingScheduler
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.storage.DocumentStagingStore

private val logger = KotlinLogging.logger {}
private const val DB_INIT_MAX_ATTEMPTS = 60
private const val DB_INIT_DELAY_MS = 2000L

fun main() {
    logger.info { "🧠 Embedding worker starting" }

    val config = PipelineConfig.fromEnv()
    val batchSize = envInt("EMBEDDING_WORKER_BATCH_SIZE", 200, min = 1, max = 5000)
    val pollIntervalSeconds = envInt("EMBEDDING_WORKER_POLL_INTERVAL_SECONDS", 1, min = 0, max = 3600)
    val maxConcurrentEmbeddings = envInt("EMBEDDING_WORKER_MAX_CONCURRENT", 24, min = 1, max = 256)
    val embeddingRequestBatchSize = envInt("EMBEDDING_WORKER_REQUEST_BATCH_SIZE", 32, min = 1, max = 256)
    val maxConcurrentBatchRequests = envInt("EMBEDDING_WORKER_MAX_CONCURRENT_BATCHES", 8, min = 1, max = 128)
    val schedulerMaxRetries = envInt("EMBEDDING_WORKER_MAX_RETRIES", 3, min = 0, max = 20)

    logger.info {
        "Embedding worker tuning: batchSize=$batchSize, pollIntervalSeconds=$pollIntervalSeconds, " +
            "maxConcurrentEmbeddings=$maxConcurrentEmbeddings, embeddingRequestBatchSize=$embeddingRequestBatchSize, " +
            "maxConcurrentBatchRequests=$maxConcurrentBatchRequests, schedulerMaxRetries=$schedulerMaxRetries"
    }

    val stagingStore = withRetry(
        label = "DocumentStagingStore",
        maxAttempts = DB_INIT_MAX_ATTEMPTS,
        delayMs = DB_INIT_DELAY_MS
    ) {
        DocumentStagingStore(
            jdbcUrl = config.postgres.jdbcUrl,
            user = config.postgres.user,
            dbPassword = config.postgres.password
        )
    }

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
            batchSize = batchSize,
            pollInterval = pollIntervalSeconds,
            maxConcurrentEmbeddings = maxConcurrentEmbeddings,
            maxRetries = schedulerMaxRetries,
            embeddingRequestBatchSize = embeddingRequestBatchSize,
            maxConcurrentBatchRequests = maxConcurrentBatchRequests
        )

        launch {
            embeddingScheduler.start()
        }

        awaitCancellation()
    }
}

private fun envInt(
    key: String,
    default: Int,
    min: Int,
    max: Int
): Int {
    val raw = System.getenv(key)?.trim()
    if (raw.isNullOrEmpty()) {
        return default
    }
    val parsed = raw.toIntOrNull()
    if (parsed == null) {
        logger.warn { "Invalid integer for $key=$raw, using default=$default" }
        return default
    }
    return parsed.coerceIn(min, max)
}

private fun <T> withRetry(
    label: String,
    maxAttempts: Int,
    delayMs: Long,
    block: () -> T
): T {
    var lastError: Exception? = null
    for (attempt in 1..maxAttempts) {
        try {
            if (attempt > 1) {
                logger.info { "$label init retry $attempt/$maxAttempts" }
            }
            return block()
        } catch (e: Exception) {
            lastError = e
            logger.warn { "$label init failed ($attempt/$maxAttempts): ${e.message}" }
            if (attempt < maxAttempts) {
                Thread.sleep(delayMs)
            }
        }
    }
    throw IllegalStateException("$label failed to initialize after $maxAttempts attempts", lastError)
}
