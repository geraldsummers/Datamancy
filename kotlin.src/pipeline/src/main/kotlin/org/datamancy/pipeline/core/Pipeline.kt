package org.datamancy.pipeline.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}


class Pipeline<T, R>(
    private val source: Source<T>,
    private val processors: List<Processor<*, *>>,
    private val sink: Sink<R>,
    private val bufferSize: Int = 1000,
    private val concurrency: Int = 4
) {
    private val itemsProcessed = AtomicLong(0)
    private val itemsFailed = AtomicLong(0)

    
    @Suppress("UNCHECKED_CAST")
    suspend fun run() {
        logger.info { "Starting pipeline: ${source.name} -> ${processors.map { (it as Processor<*, *>).name }} -> ${sink.name}" }

        val startTime = System.currentTimeMillis()

        try {
            source.fetch()
                .buffer(bufferSize)  
                .map { item ->
                    try {
                        
                        var result: Any? = item
                        for (processor in processors) {
                            result = (processor as Processor<Any?, Any?>).process(result)
                        }
                        itemsProcessed.incrementAndGet()
                        result as R
                    } catch (e: Exception) {
                        itemsFailed.incrementAndGet()
                        logger.error(e) { "Failed to process item: ${e.message}" }
                        null
                    }
                }
                .filterNotNull()
                .collect { result ->
                    try {
                        sink.write(result)
                    } catch (e: Exception) {
                        itemsFailed.incrementAndGet()
                        logger.error(e) { "Failed to write to sink: ${e.message}" }
                    }
                }

            val duration = System.currentTimeMillis() - startTime
            logger.info {
                "Pipeline completed: ${itemsProcessed.get()} processed, ${itemsFailed.get()} failed in ${duration}ms"
            }
        } catch (e: Exception) {
            logger.error(e) { "Pipeline failed: ${e.message}" }
            throw e
        }
    }

    fun getMetrics(): PipelineMetrics {
        return PipelineMetrics(
            name = "${source.name}->${sink.name}",
            itemsProcessed = itemsProcessed.get(),
            itemsFailed = itemsFailed.get()
        )
    }
}

data class PipelineMetrics(
    val name: String,
    val itemsProcessed: Long,
    val itemsFailed: Long
)
