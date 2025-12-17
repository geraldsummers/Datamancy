package org.datamancy.datafetcher.scheduler

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.datamancy.datafetcher.clients.StandardHttpClient
import org.datamancy.datafetcher.storage.CheckpointStore
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.DedupeStore
import org.datamancy.datafetcher.storage.FileSystemStore

private val logger = KotlinLogging.logger {}

/**
 * Execution context for a single fetch run.
 * Provides easy access to all Phase 0 infrastructure:
 * - Run metadata (runId, timestamps)
 * - Metrics tracking
 * - Error collection
 * - Checkpoint/dedupe/storage helpers
 * - Standardized HTTP client
 *
 * Usage pattern:
 * ```
 * override suspend fun fetch(): FetchResult {
 *     return FetchExecutionContext.execute("rss_feeds") { ctx ->
 *         // Get checkpoint
 *         val lastFetch = ctx.checkpoint.get("last_fetch_time")
 *
 *         // Fetch data
 *         items.forEach { item ->
 *             ctx.markAttempted()
 *
 *             // Check dedupe
 *             when (ctx.dedupe.shouldUpsert(item.id, item.contentHash)) {
 *                 DedupeResult.NEW -> {
 *                     // Insert new item
 *                     ctx.markNew()
 *                 }
 *                 DedupeResult.UPDATED -> {
 *                     // Update existing item
 *                     ctx.markUpdated()
 *                 }
 *                 DedupeResult.UNCHANGED -> {
 *                     // Skip
 *                     ctx.markSkipped()
 *                 }
 *             }
 *
 *             // Store raw data
 *             ctx.storage.storeRaw(item.id, item.rawData, "json")
 *         }
 *
 *         // Update checkpoint
 *         ctx.checkpoint.set("last_fetch_time", Clock.System.now().toString())
 *
 *         "Fetched ${ctx.metrics.new} new items"
 *     }
 * }
 * ```
 */
class FetchExecutionContext private constructor(
    val runId: String,
    val jobName: String,
    val startedAt: Instant,
    val version: String = "1.0.0"
) {
    // Metrics tracking
    private var _attempted = 0
    private var _fetched = 0
    private var _new = 0
    private var _updated = 0
    private var _skipped = 0
    private var _failed = 0
    private val _errorSamples = mutableListOf<ErrorSample>()
    private val maxErrorSamples = 10

    val metrics: FetchMetrics
        get() = FetchMetrics(_attempted, _fetched, _new, _updated, _skipped, _failed)

    val errorSamples: List<ErrorSample>
        get() = _errorSamples.toList()

    // Infrastructure helpers
    private val _checkpointStore = CheckpointStore()
    private val _dedupeStore = DedupeStore()
    private val _fileSystemStore = FileSystemStore()
    private val _httpClient = StandardHttpClient.builder()
        .maxRetries(3)
        .perHostConcurrency(5)
        .rateLimit(requestsPerSecond = 10)
        .build()

    /**
     * Checkpoint helper scoped to this job
     */
    val checkpoint: CheckpointHelper = CheckpointHelper(_checkpointStore, jobName)

    /**
     * Dedupe helper scoped to this job
     */
    val dedupe: DedupeHelper = DedupeHelper(_dedupeStore, jobName, runId)

    /**
     * Storage helper scoped to this job
     */
    val storage: StorageHelper = StorageHelper(_fileSystemStore, jobName, runId)

    /**
     * HTTP client with retry/backoff/rate limiting
     */
    val http: StandardHttpClient
        get() = _httpClient

    // Metrics tracking methods
    fun markAttempted(count: Int = 1) { _attempted += count }
    fun markFetched(count: Int = 1) { _fetched += count }
    fun markNew(count: Int = 1) { _new += count }
    fun markUpdated(count: Int = 1) { _updated += count }
    fun markSkipped(count: Int = 1) { _skipped += count }
    fun markFailed(count: Int = 1) { _failed += count }

    /**
     * Record an error sample (limited to maxErrorSamples)
     */
    fun recordError(errorType: String, message: String, itemId: String? = null) {
        if (_errorSamples.size < maxErrorSamples) {
            _errorSamples.add(ErrorSample(errorType, message, itemId, Clock.System.now()))
        }
    }

    companion object {
        /**
         * Execute a fetch with automatic context management and result building.
         *
         * @param jobName Name of the job being executed
         * @param version Version of the fetcher implementation
         * @param block Fetch logic that receives the context
         * @return FetchResult.Success or FetchResult.Error
         */
        suspend fun execute(
            jobName: String,
            version: String = "1.0.0",
            block: suspend (FetchExecutionContext) -> String
        ): FetchResult {
            val startedAt = Clock.System.now()
            val runId = generateRunId(jobName, startedAt)
            val ctx = FetchExecutionContext(runId, jobName, startedAt, version)

            return try {
                val message = block(ctx)
                val endedAt = Clock.System.now()

                FetchResult.Success(
                    runId = runId,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    jobName = jobName,
                    version = version,
                    message = message,
                    metrics = ctx.metrics
                )
            } catch (e: Exception) {
                val endedAt = Clock.System.now()
                logger.error(e) { "Fetch execution failed: $jobName" }

                // Record the exception as an error sample if not already recorded
                if (ctx._errorSamples.isEmpty()) {
                    ctx.recordError(
                        errorType = e::class.simpleName ?: "Exception",
                        message = e.message ?: "Unknown error"
                    )
                }

                FetchResult.Error(
                    runId = runId,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    jobName = jobName,
                    version = version,
                    message = "Fetch failed: ${e.message}",
                    metrics = ctx.metrics,
                    errorSamples = ctx.errorSamples
                )
            }
        }

        private fun generateRunId(jobName: String, timestamp: Instant): String {
            val epochSeconds = timestamp.epochSeconds
            return "${jobName}_${epochSeconds}_${(0..999).random()}"
        }
    }
}

/**
 * Checkpoint helper for scoped access
 */
class CheckpointHelper(private val store: CheckpointStore, private val jobName: String) {
    fun get(key: String): String? = store.get(jobName, key)
    fun set(key: String, value: String) = store.set(jobName, key, value)
    fun delete(key: String) = store.delete(jobName, key)
    fun getAll(): Map<String, String> = store.getAll(jobName)
}

/**
 * Dedupe helper for scoped access
 */
class DedupeHelper(private val store: DedupeStore, private val jobName: String, private val runId: String) {
    fun shouldUpsert(itemId: String, contentHash: String): DedupeResult =
        store.shouldUpsert(jobName, itemId, contentHash, runId)
    fun getStats() = store.getStats(jobName)
}

/**
 * Storage helper for scoped access
 */
class StorageHelper(private val store: FileSystemStore, private val jobName: String, private val runId: String) {
    fun storeRaw(itemId: String, content: ByteArray, extension: String = "bin"): String =
        store.storeRaw(jobName, runId, itemId, content, extension)
    fun storeRawText(itemId: String, content: String, extension: String = "txt"): String =
        store.storeRawText(jobName, runId, itemId, content, extension)
    fun readRaw(path: String): ByteArray = store.readRaw(path)
    fun exists(path: String): Boolean = store.exists(path)
}
