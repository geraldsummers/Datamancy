package org.datamancy.pipeline.storage

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.time.Instant

private val logger = KotlinLogging.logger {}

/**
 * Tracks execution state and checkpoint data for individual pipeline data sources.
 *
 * Each data source (RSS, CVE, Wikipedia, etc.) persists metadata to enable:
 * - Incremental updates: Resume from last checkpoint instead of re-scanning entire dataset
 * - Failure tracking: Detect sources with persistent errors (high consecutiveFailures)
 * - Throughput metrics: Monitor items processed/failed over time for capacity planning
 * - Version management: Track data source schema changes (e.g., RSS feed format updates)
 *
 * **Checkpoint Examples:**
 * - RSS: {"last_article_timestamp": "2025-01-15T10:30:00Z"} → Only fetch newer articles
 * - CVE: {"last_modified": "2025-01-14"} → Skip CVEs older than last sync
 * - Wikipedia: {"dump_version": "20250101"} → Detect when new dump is available
 *
 * Without checkpoints, every pipeline run would re-fetch millions of historical documents,
 * wasting bandwidth and CPU on content that's already been processed and deduplicated.
 *
 * @property sourceName Unique identifier (e.g., "rss", "cve", "wikipedia_en")
 * @property lastSuccessfulRun ISO-8601 timestamp of last completed run (null if never succeeded)
 * @property lastAttemptedRun ISO-8601 timestamp of last attempted run (may have failed)
 * @property totalItemsProcessed Cumulative count of documents staged (across all runs)
 * @property totalItemsFailed Cumulative count of errors (network failures, parsing errors, etc.)
 * @property consecutiveFailures Failure streak counter (reset to 0 on success)
 * @property sourceVersion Schema version for backward compatibility during upgrades
 * @property checkpointData Source-specific state for incremental updates (flexible key-value pairs)
 */
data class SourceMetadata(
    val sourceName: String,
    val lastSuccessfulRun: String? = null,
    val lastAttemptedRun: String? = null,
    val totalItemsProcessed: Long = 0,
    val totalItemsFailed: Long = 0,
    val consecutiveFailures: Int = 0,
    val sourceVersion: String = "1.0",
    val checkpointData: Map<String, String> = emptyMap()
)

/**
 * JSON-based persistence layer for source metadata with in-memory caching.
 *
 * Each data source has its own JSON file (e.g., rss.json, cve.json) containing
 * checkpoint state and execution history. Files are human-readable for debugging
 * and can be edited manually to force re-processing from a specific checkpoint.
 *
 * **Integration with Pipeline:**
 * - Data sources call load() at startup to retrieve checkpoint data
 * - After successful ingestion, recordSuccess() updates metrics and checkpoints
 * - On failure, recordFailure() increments consecutive failure counter
 * - Monitoring uses listAll() to display source health in dashboards
 *
 * **Memory Cache:**
 * Reduces disk I/O by caching loaded metadata. Cache is updated on save() to maintain
 * consistency. This enables sub-millisecond lookups during high-throughput ingestion.
 *
 * @property storePath Directory for JSON files (typically mounted volume for persistence)
 */
class SourceMetadataStore(
    private val storePath: String = System.getProperty("java.io.tmpdir") + "/datamancy/metadata"
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val storeDir = File(storePath)
    private val memoryCache = mutableMapOf<String, SourceMetadata>()

    init {
        storeDir.mkdirs()
    }

    /**
     * Retrieves metadata for a data source, creating default entry if not found.
     *
     * Checks memory cache first (fast path), falls back to disk if cache miss.
     * If no persisted metadata exists (first run), returns fresh SourceMetadata with
     * zero metrics and empty checkpoint data.
     *
     * @param sourceName Source identifier (e.g., "rss", "cve")
     * @return Metadata for this source (never null, creates default if needed)
     */
    fun load(sourceName: String): SourceMetadata {
        // Fast path: return cached metadata if available
        memoryCache[sourceName]?.let { return it }

        // Slow path: load from disk and cache
        val file = File(storeDir, "${sourceName}.json")
        return try {
            if (file.exists()) {
                val json = file.readText()
                val metadata = gson.fromJson(json, SourceMetadata::class.java)
                memoryCache[sourceName] = metadata
                metadata
            } else {
                logger.info { "No metadata found for $sourceName, creating new" }
                val metadata = SourceMetadata(sourceName = sourceName)
                memoryCache[sourceName] = metadata
                metadata
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to load metadata for $sourceName: ${e.message}" }
            val metadata = SourceMetadata(sourceName = sourceName)
            memoryCache[sourceName] = metadata
            metadata
        }
    }

    /**
     * Persists metadata to disk and updates memory cache.
     *
     * JSON is pretty-printed for human readability (operators can manually inspect
     * checkpoint data or edit files to force specific pipeline behavior).
     *
     * Write failures are logged but not propagated (metadata is not critical enough
     * to halt pipeline execution). Worst case: source re-processes from beginning on
     * next run if checkpoint data is lost.
     *
     * @param metadata Source metadata to persist
     */
    fun save(metadata: SourceMetadata) {
        // Update cache immediately for subsequent load() calls
        memoryCache[metadata.sourceName] = metadata

        // Persist to disk (may fail, but metadata loss is non-fatal)
        if (!storeDir.exists()) {
            storeDir.mkdirs()
        }

        val file = File(storeDir, "${metadata.sourceName}.json")
        try {
            val json = gson.toJson(metadata)
            file.writeText(json)
            logger.debug { "Saved metadata for ${metadata.sourceName}" }
        } catch (e: Exception) {
            logger.error(e) { "Failed to save metadata for ${metadata.sourceName}: ${e.message}" }
            // Non-fatal: source will re-process from last successful checkpoint on restart
        }
    }

    /**
     * Records successful pipeline run with updated metrics and checkpoint data.
     *
     * Called by data sources after completing ingestion cycle. Updates:
     * - Timestamps to track run frequency and detect stalled sources
     * - Cumulative counters for long-term throughput analysis
     * - Consecutive failure counter reset (source is healthy again)
     * - Checkpoint data for next incremental update
     *
     * Example: After ingesting 100 RSS articles, 5 of which failed to parse:
     * ```
     * metadataStore.recordSuccess(
     *     sourceName = "rss",
     *     itemsProcessed = 95,
     *     itemsFailed = 5,
     *     checkpointData = mapOf("last_timestamp" to "2025-01-15T10:30:00Z")
     * )
     * ```
     *
     * @param sourceName Source identifier
     * @param itemsProcessed Documents successfully staged in this run
     * @param itemsFailed Documents that failed processing in this run
     * @param checkpointData State to resume from on next run (source-specific format)
     */
    fun recordSuccess(
        sourceName: String,
        itemsProcessed: Long,
        itemsFailed: Long = 0,
        checkpointData: Map<String, String> = emptyMap()
    ) {
        val existing = load(sourceName)
        val updated = existing.copy(
            lastSuccessfulRun = Instant.now().toString(),
            lastAttemptedRun = Instant.now().toString(),
            totalItemsProcessed = existing.totalItemsProcessed + itemsProcessed,
            totalItemsFailed = existing.totalItemsFailed + itemsFailed,
            consecutiveFailures = 0,
            checkpointData = checkpointData
        )
        save(updated)
    }

    /**
     * Records failed pipeline run, incrementing consecutive failure counter.
     *
     * Called by data sources when ingestion cycle fails (e.g., network timeout,
     * authentication error, malformed data). The consecutiveFailures counter enables
     * monitoring to alert on sources with persistent issues.
     *
     * Failure does NOT reset checkpoint data, so the next successful run can resume
     * from the last good state rather than starting over.
     *
     * Example: RSS feed returns HTTP 500 error:
     * ```
     * try {
     *     // Fetch and process RSS feed
     * } catch (e: Exception) {
     *     logger.error(e) { "RSS ingestion failed" }
     *     metadataStore.recordFailure("rss")
     * }
     * ```
     *
     * @param sourceName Source identifier
     */
    fun recordFailure(sourceName: String) {
        val existing = load(sourceName)
        val updated = existing.copy(
            lastAttemptedRun = Instant.now().toString(),
            consecutiveFailures = existing.consecutiveFailures + 1
        )
        save(updated)
    }

    /**
     * Returns metadata for all known data sources.
     *
     * Used by monitoring dashboards to display source health matrix:
     * - Last run timestamps (detect stalled sources)
     * - Throughput metrics (identify high-volume sources)
     * - Failure rates (alert on degraded sources)
     *
     * @return List of all source metadata (empty if no sources have run yet)
     */
    fun listAll(): List<SourceMetadata> {
        return try {
            storeDir.listFiles()
                ?.filter { it.extension == "json" }
                ?.mapNotNull { file ->
                    try {
                        val json = file.readText()
                        gson.fromJson(json, SourceMetadata::class.java)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to parse ${file.name}: ${e.message}" }
                        null
                    }
                } ?: emptyList()
        } catch (e: Exception) {
            logger.error(e) { "Failed to list metadata: ${e.message}" }
            emptyList()
        }
    }
}
