package org.datamancy.pipeline.core

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicLong

private val logger = KotlinLogging.logger {}

/**
 * Core pipeline orchestrator that composes Source, Processors, and Sink into a data flow.
 *
 * The Pipeline class connects data ingestion sources (RSS, CVE, Wikipedia) with transformation
 * processors (chunking, deduplication, embedding) and output sinks (PostgreSQL, Qdrant, BookStack).
 *
 * Integration points:
 * - Prometheus: Exposes metrics via MonitoringServer (port 8090) for Grafana dashboards
 * - Kotlin Coroutines: Uses Flow API for backpressure-aware streaming (bufferSize controls memory usage)
 * - PostgreSQL: Typical flow ends at DocumentStagingSink with status=PENDING
 * - EmbeddingScheduler: Polls staged documents asynchronously to decouple ingestion from embedding
 *
 * Data flow example (RSS pipeline):
 * ```
 * RssSource → DeduplicationProcessor → ChunkingProcessor → DocumentStagingSink (PostgreSQL)
 *                                                                ↓
 *                                          EmbeddingScheduler polls for PENDING
 *                                                                ↓
 *                                          Embedder → QdrantSink (status=COMPLETED)
 * ```
 *
 * Design rationale:
 * - bufferSize=1000 balances memory usage vs throughput (prevents OOM during large ingestion runs)
 * - Fault tolerance: Individual item failures don't stop the pipeline (tracked in itemsFailed)
 * - Async processing: Staging pattern allows ingestion to continue even if Qdrant is slow/unavailable
 */
class Pipeline<T, R>(
    private val source: Source<T>,
    private val processors: List<Processor<*, *>>,
    private val sink: Sink<R>,
    private val bufferSize: Int = 1000,
    private val concurrency: Int = 4
) {
    private val itemsProcessed = AtomicLong(0)
    private val itemsFailed = AtomicLong(0)

    /**
     * Executes the pipeline from source to sink, processing all items through the processor chain.
     *
     * Processing stages:
     * 1. Source.fetch() emits flow of raw items
     * 2. Items buffered (bufferSize) to handle bursts from fast sources
     * 3. Each item passed through processor chain sequentially
     * 4. Successful results written to sink (batch writes used where available)
     * 5. Failures logged but don't stop pipeline execution
     *
     * Error handling:
     * - Individual item failures increment itemsFailed but don't stop the flow
     * - Sink write failures are retried by the sink implementation (e.g., PostgreSQL retry logic)
     * - Pipeline-level exceptions (source fetch failures) propagate to caller for restart
     */
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
