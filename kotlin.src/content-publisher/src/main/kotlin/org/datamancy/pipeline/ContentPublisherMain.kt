package org.datamancy.pipeline

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.config.PipelineConfig
import org.datamancy.pipeline.sinks.BookStackSink
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.workers.BookStackWriter

private val logger = KotlinLogging.logger {}

fun main() {
    logger.info { "📚 Content publisher starting" }

    val config = PipelineConfig.fromEnv()

    if (!config.bookstack.enabled) {
        logger.warn { "BookStack publishing disabled (BOOKSTACK_ENABLED=false). Exiting." }
        return
    }

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

    val bookStackSink = BookStackSink(
        bookstackUrl = config.bookstack.url,
        tokenId = config.bookstack.tokenId,
        tokenSecret = config.bookstack.tokenSecret
    )

    runBlocking {
        val bookStackWriter = BookStackWriter(
            stagingStore = stagingStore,
            bookStackSink = bookStackSink,
            pollIntervalSeconds = 5,
            batchSize = 50
        )

        bookStackWriter.start()
    }
}
