package org.datamancy.datafetcher.fetchers

import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.datamancy.datafetcher.config.CVEConfig
import org.datamancy.datafetcher.scheduler.FetchExecutionContext
import org.datamancy.datafetcher.scheduler.FetchResult
import org.datamancy.datafetcher.storage.ClickHouseStore
import org.datamancy.datafetcher.storage.ContentHasher
import org.datamancy.datafetcher.storage.DedupeResult
import org.datamancy.datafetcher.storage.PostgresStore
import kotlin.time.Duration.Companion.milliseconds

private val logger = KotlinLogging.logger {}
private val gson = Gson()

/**
 * CVE Fetcher - Fetches vulnerability data from NVD (National Vulnerability Database)
 *
 * Features:
 * - Incremental sync using lastModStartDate checkpointing
 * - Full backfill support for historical CVE data
 * - Rate limit handling (50 req/30s with API key)
 * - Deduplication by CVE ID + modified timestamp
 * - Rich metadata extraction (CVSS scores, CWE, CPE matches)
 */
class CVEFetcher(private val config: CVEConfig) : Fetcher {
    private val clickHouseStore = ClickHouseStore()
    private val pgStore = PostgresStore()
    private val baseUrl = "https://services.nvd.nist.gov/rest/json/cves/2.0"

    // Rate limiting: 50 requests per 30 seconds with API key
    private val requestsPerWindow = if (config.apiKey.isNotEmpty()) 50 else 5
    private val windowSeconds = 30

    override suspend fun fetch(): FetchResult {
        return FetchExecutionContext.execute("cve_data", version = "1.0.0") { ctx ->
            logger.info { "Starting CVE data fetch from NVD (National Vulnerability Database)" }

            if (config.apiKey.isEmpty()) {
                logger.warn { "⚠ No NVD_API_KEY configured - using unauthenticated rate limits (5 req/30s)" }
            } else {
                logger.info { "Using authenticated rate limits (50 req/30s)" }
            }

            val lastModified = if (config.fullBackfillEnabled) {
                logger.info { "Full backfill enabled - fetching ALL CVEs from 2000-01-01 (this will take a while)" }
                Instant.parse("2000-01-01T00:00:00Z")
            } else {
                // Get last modified date from database, or fallback to syncDaysBack
                val lastMod = clickHouseStore.getLastCVEModifiedDate()
                    ?: Clock.System.now().minus((config.syncDaysBack * 24 * 3600).milliseconds)
                logger.info { "Incremental sync - fetching CVEs modified in last ${config.syncDaysBack} days" }
                lastMod
            }

            val now = Clock.System.now()
            logger.info { "Date range: $lastModified to $now" }

            // NVD API requires date ranges of max 120 days
            val chunks = splitDateRangeInto120DayChunks(lastModified, now)
            logger.info { "Split into ${chunks.size} date range chunks (max 120 days each)" }

            chunks.forEachIndexed { index, (start, end) ->
                logger.info { "Processing chunk ${index + 1}/${chunks.size}: $start to $end" }
                var startIndex = 0
                var hasMore = true

                while (hasMore) {
                    ctx.markAttempted()

                    try {
                        // Build request URL
                        val url = buildUrl(start, end, startIndex)

                        // Note: Rate limiting is handled by StandardHttpClient
                        val response = ctx.http.get(url)

                        if (!response.isSuccessful) {
                            response.close()
                            if (response.code == 429) {
                                logger.warn { "Rate limited - will retry on next run" }
                                ctx.markSkipped()
                                return@forEachIndexed
                            }
                            throw Exception("HTTP ${response.code}: ${response.message}")
                        }

                        val body = response.body?.string()
                        response.close()

                        if (body.isNullOrEmpty()) {
                            throw Exception("Empty response body")
                        }

                        val json = JsonParser.parseString(body).asJsonObject
                        val totalResults = json.get("totalResults")?.asInt ?: 0
                        val resultsPerPage = json.get("resultsPerPage")?.asInt ?: 0
                        val vulnerabilities = json.getAsJsonArray("vulnerabilities") ?: throw Exception("No vulnerabilities array")

                        logger.info { "Received ${vulnerabilities.size()} CVEs (total results: $totalResults, start: $startIndex)" }

                        // Process each CVE
                        vulnerabilities.forEach { vulnElement ->
                            try {
                                processCVE(ctx, vulnElement.asJsonObject)
                            } catch (e: Exception) {
                                logger.error(e) { "Failed to process CVE" }
                                ctx.markFailed()
                            }
                        }

                        // Check if there are more results
                        startIndex += resultsPerPage
                        hasMore = startIndex < totalResults

                        if (hasMore) {
                            logger.info { "More results available, fetching next batch (startIndex: $startIndex)" }
                        }

                    } catch (e: Exception) {
                        logger.error(e) { "Failed to fetch CVE batch" }
                        ctx.markFailed()
                        ctx.recordError("FETCH_ERROR", e.message ?: "Unknown error", "batch_$startIndex")
                        hasMore = false // Stop this chunk on error
                    }
                }
            }

            // Update checkpoint
            ctx.checkpoint.set("last_sync_date", now.toString())

            "Processed CVEs: ${ctx.metrics.new} new, ${ctx.metrics.updated} updated, ${ctx.metrics.skipped} skipped, ${ctx.metrics.failed} failed"
        }
    }

    private fun processCVE(ctx: FetchExecutionContext, vulnObj: com.google.gson.JsonObject) {
        val cve = vulnObj.getAsJsonObject("cve")
        val cveId = cve.get("id")?.asString ?: throw Exception("Missing CVE ID")

        // Parse dates
        val publishedDate = Instant.parse(cve.get("published")?.asString ?: throw Exception("Missing published date"))
        val lastModified = Instant.parse(cve.get("lastModified")?.asString ?: throw Exception("Missing lastModified date"))

        // Extract description
        val descriptions = cve.getAsJsonArray("descriptions")
        val description = descriptions
            ?.firstOrNull { it.asJsonObject.get("lang")?.asString == "en" }
            ?.asJsonObject?.get("value")?.asString
            ?: ""

        // Extract CVSS scores
        val metrics = cve.getAsJsonObject("metrics")
        var cvssV3Score: Double? = null
        var cvssV3Severity: String? = null
        var cvssV2Score: Double? = null
        var cvssV2Severity: String? = null

        metrics?.getAsJsonArray("cvssMetricV31")?.firstOrNull()?.asJsonObject?.let { cvssV31 ->
            val cvssData = cvssV31.getAsJsonObject("cvssData")
            cvssV3Score = cvssData?.get("baseScore")?.asDouble
            cvssV3Severity = cvssData?.get("baseSeverity")?.asString
        }

        metrics?.getAsJsonArray("cvssMetricV2")?.firstOrNull()?.asJsonObject?.let { cvssV2 ->
            val cvssData = cvssV2.getAsJsonObject("cvssData")
            cvssV2Score = cvssData?.get("baseScore")?.asDouble
            cvssV2Severity = cvssV2.get("baseSeverity")?.asString
        }

        // Extract CWE IDs
        val cweIds = mutableListOf<String>()
        cve.getAsJsonArray("weaknesses")?.forEach { weakness ->
            weakness.asJsonObject.getAsJsonArray("description")?.forEach { desc ->
                val value = desc.asJsonObject.get("value")?.asString
                if (value != null && value.startsWith("CWE-")) {
                    cweIds.add(value)
                }
            }
        }

        // Extract CPE matches (affected products)
        val cpeMatches = mutableListOf<String>()
        cve.getAsJsonArray("configurations")?.forEach { config ->
            config.asJsonObject.getAsJsonArray("nodes")?.forEach { node ->
                node.asJsonObject.getAsJsonArray("cpeMatch")?.forEach { match ->
                    val criteria = match.asJsonObject.get("criteria")?.asString
                    if (criteria != null) {
                        cpeMatches.add(criteria)
                    }
                }
            }
        }

        // Extract references
        val references = mutableListOf<String>()
        cve.getAsJsonArray("references")?.forEach { ref ->
            val url = ref.asJsonObject.get("url")?.asString
            if (url != null) {
                references.add(url)
            }
        }

        // Compute content hash
        val rawJson = gson.toJson(cve)
        val contentHash = ContentHasher.hashJson(rawJson)

        // Dedupe check
        val existingHash = clickHouseStore.getCVEContentHash(cveId)
        val dedupeResult = when {
            existingHash == null -> DedupeResult.NEW
            existingHash == contentHash -> DedupeResult.UNCHANGED
            else -> DedupeResult.UPDATED
        }

        when (dedupeResult) {
            DedupeResult.NEW -> {
                clickHouseStore.storeCVEData(
                    cveId = cveId,
                    publishedDate = publishedDate,
                    lastModified = lastModified,
                    cvssV3Score = cvssV3Score,
                    cvssV3Severity = cvssV3Severity,
                    cvssV2Score = cvssV2Score,
                    cvssV2Severity = cvssV2Severity,
                    description = description,
                    cweIds = cweIds,
                    cpeMatches = cpeMatches,
                    references = references,
                    fetchedAt = Clock.System.now(),
                    contentHash = contentHash,
                    rawJson = rawJson,
                    metadata = mapOf(
                        "cwe_count" to cweIds.size,
                        "cpe_count" to cpeMatches.size,
                        "ref_count" to references.size
                    )
                )

                // Store raw JSON
                ctx.storage.storeRawText(cveId, rawJson, "json")

                pgStore.storeFetchMetadata(
                    source = "cve_data",
                    category = cvssV3Severity ?: cvssV2Severity ?: "UNKNOWN",
                    itemCount = 1,
                    fetchedAt = Clock.System.now(),
                    metadata = mapOf(
                        "cve_id" to cveId,
                        "cvss_v3_score" to (cvssV3Score ?: 0.0),
                        "primary_cwe" to (cweIds.firstOrNull() ?: "")
                    )
                )

                ctx.markNew()
                ctx.markFetched()
                logger.info { "CVE: $cveId | Severity: ${cvssV3Severity ?: cvssV2Severity ?: "N/A"} | Score: ${cvssV3Score ?: cvssV2Score ?: "N/A"}" }
            }
            DedupeResult.UPDATED -> {
                clickHouseStore.storeCVEData(
                    cveId = cveId,
                    publishedDate = publishedDate,
                    lastModified = lastModified,
                    cvssV3Score = cvssV3Score,
                    cvssV3Severity = cvssV3Severity,
                    cvssV2Score = cvssV2Score,
                    cvssV2Severity = cvssV2Severity,
                    description = description,
                    cweIds = cweIds,
                    cpeMatches = cpeMatches,
                    references = references,
                    fetchedAt = Clock.System.now(),
                    contentHash = contentHash,
                    rawJson = rawJson,
                    metadata = mapOf(
                        "cwe_count" to cweIds.size,
                        "cpe_count" to cpeMatches.size,
                        "ref_count" to references.size
                    )
                )
                ctx.storage.storeRawText(cveId, rawJson, "json")
                ctx.markUpdated()
                ctx.markFetched()
                logger.info { "CVE Updated: $cveId" }
            }
            DedupeResult.UNCHANGED -> {
                ctx.markSkipped()
            }
        }
    }

    private fun buildUrl(startDate: Instant, endDate: Instant, startIndex: Int): String {
        val params = mutableListOf(
            "lastModStartDate=${startDate}",
            "lastModEndDate=${endDate}",
            "startIndex=$startIndex",
            "resultsPerPage=${config.batchSize}"
        )

        if (config.apiKey.isNotEmpty()) {
            params.add("apiKey=${config.apiKey}")
        }

        return "$baseUrl?${params.joinToString("&")}"
    }

    private fun splitDateRangeInto120DayChunks(start: Instant, end: Instant): List<Pair<Instant, Instant>> {
        val chunks = mutableListOf<Pair<Instant, Instant>>()
        var current = start
        val maxDuration = (120 * 24 * 3600).milliseconds

        while (current < end) {
            val chunkEnd = minOf(current + maxDuration, end)
            chunks.add(current to chunkEnd)
            current = chunkEnd
        }

        return chunks
    }

    override suspend fun dryRun(): DryRunResult {
        logger.info { "Dry-run: Verifying CVE data sources..." }
        val checks = mutableListOf<DryRunCheck>()

        // Check filesystem directories
        checks.add(DryRunUtils.checkDirectory("/app/data/cve_data", "CVE data directory"))

        // Check NVD API endpoint
        val apiUrl = if (config.apiKey.isNotEmpty()) {
            "$baseUrl?apiKey=${config.apiKey}"
        } else {
            baseUrl
        }
        checks.add(
            DryRunUtils.checkUrl(
                apiUrl,
                "NVD API (${if (config.apiKey.isNotEmpty()) "authenticated" else "unauthenticated"})"
            )
        )

        // Verify API key is configured
        checks.add(
            DryRunUtils.checkConfig(
                if (config.apiKey.isNotEmpty()) "configured" else null,
                "NVD API Key",
                required = false
            )
        )

        // Check ClickHouse connection
        val chHost = System.getenv("CLICKHOUSE_HOST") ?: "clickhouse"
        val chPort = System.getenv("CLICKHOUSE_PORT")?.toIntOrNull() ?: 8123
        checks.add(
            DryRunUtils.checkUrl(
                "http://$chHost:$chPort/ping",
                "ClickHouse (CVE storage)"
            )
        )

        return DryRunResult(checks)
    }
}
