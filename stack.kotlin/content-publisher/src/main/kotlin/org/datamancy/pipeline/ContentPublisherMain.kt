package org.datamancy.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.config.PipelineConfig
import org.datamancy.pipeline.sinks.BookStackSink
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.workers.BookStackWriter

private val logger = KotlinLogging.logger {}
private const val DB_INIT_MAX_ATTEMPTS = 60
private const val DB_INIT_DELAY_MS = 2000L

fun main() {
    logger.info { "📚 Content publisher starting" }

    val config = PipelineConfig.fromEnv()

    if (!config.bookstack.enabled) {
        logger.warn { "BookStack publishing disabled (BOOKSTACK_ENABLED=false). Exiting." }
        return
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

    val bookStackSink = BookStackSink(
        bookstackUrl = config.bookstack.url,
        tokenId = config.bookstack.tokenId,
        tokenSecret = config.bookstack.tokenSecret
    )

    val allowedSources = System.getenv("BOOKSTACK_ALLOWED_SOURCES")
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()
    if (allowedSources.isEmpty()) {
        logger.info { "BookStack source filter disabled; publishing all embedded sources" }
    } else {
        logger.info { "BookStack source filter enabled: ${allowedSources.joinToString(",")}" }
    }

    runBlocking {
        val bookStackWriter = BookStackWriter(
            stagingStore = stagingStore,
            bookStackSink = bookStackSink,
            pollIntervalSeconds = 5,
            batchSize = 50,
            allowedSources = allowedSources
        )

        bookStackWriter.start()
    }
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
