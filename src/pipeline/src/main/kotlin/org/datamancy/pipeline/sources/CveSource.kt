package org.datamancy.pipeline.sources

import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.datamancy.pipeline.core.Source
import java.time.Instant
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Fetches CVE (Common Vulnerabilities and Exposures) data from NVD API
 * API Docs: https://nvd.nist.gov/developers/vulnerabilities
 * Rate limit: 5 requests per 30 seconds (without API key), 50 req/30sec with key
 *
 * NOTE: Requires API key from https://nvd.nist.gov/developers/request-an-api-key
 * Set CVE_ENABLED=true and CVE_API_KEY environment variable to enable
 */
class CveSource(
    private val apiKey: String? = null,
    private val startIndex: Int = 0,
    private val maxResults: Int = Int.MAX_VALUE,
    private val testMode: Boolean = false  // Skip rate limiting in tests
) : Source<CveEntry> {
    override val name = "CveSource"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "https://services.nvd.nist.gov/rest/json/cves/2.0"
    private val resultsPerPage = 2000  // Max allowed by API

    // Rate limiting: 5 req/30sec without key, 50 req/30sec with key
    // In test mode, skip delays to avoid slow CI builds
    private val delayMs = when {
        testMode -> 0L
        apiKey != null -> 600L
        else -> 6000L
    }

    override suspend fun fetch(): Flow<CveEntry> = flow {
        var currentIndex = startIndex
        var totalFetched = 0
        var shouldContinue = true

        logger.info { "Starting CVE fetch from index $startIndex (max: $maxResults)" }

        while (totalFetched < maxResults && shouldContinue) {
            try {
                val url = buildString {
                    append(baseUrl)
                    append("?startIndex=$currentIndex")
                    append("&resultsPerPage=$resultsPerPage")
                }

                logger.debug { "Fetching CVEs: $url" }

                val request = Request.Builder()
                    .url(url)
                    .apply {
                        if (apiKey != null) {
                            addHeader("apiKey", apiKey)
                        }
                    }
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    logger.error { "CVE API request failed: ${response.code} ${response.message}" }
                    shouldContinue = false
                    continue
                }

                val body = response.body?.string()
                if (body == null) {
                    logger.error { "Empty response body from CVE API" }
                    shouldContinue = false
                    continue
                }

                val json = JsonParser.parseString(body).asJsonObject
                val totalResults = json.get("totalResults")?.asInt ?: 0
                val vulnerabilities = json.getAsJsonArray("vulnerabilities")

                logger.info { "Fetched ${vulnerabilities.size()} CVEs (total: $totalResults, fetched so far: $totalFetched)" }

                if (vulnerabilities.isEmpty) {
                    logger.info { "No more CVEs to fetch" }
                    shouldContinue = false
                    continue
                }

                // Parse CVE entries outside of try-catch to avoid Flow emission violations
                val parsedCves = vulnerabilities.mapNotNull { vulnElement ->
                    try {
                        parseCveEntry(vulnElement)
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to parse CVE entry: ${e.message}" }
                        null
                    }
                }

                // Emit parsed CVEs (no exceptions can occur here)
                for (cveEntry in parsedCves) {
                    emit(cveEntry)
                    totalFetched++

                    if (totalFetched >= maxResults) {
                        logger.info { "Reached max results limit: $maxResults" }
                        shouldContinue = false
                        break
                    }
                }

                currentIndex += vulnerabilities.size()

                // Rate limiting
                if (currentIndex < totalResults && totalFetched < maxResults) {
                    logger.debug { "Waiting ${delayMs}ms before next request (rate limiting)" }
                    delay(delayMs)
                }

                if (vulnerabilities.size() < resultsPerPage) {
                    logger.info { "Received partial page, no more CVEs available" }
                    shouldContinue = false
                }

            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch CVEs: ${e.message}" }
                shouldContinue = false
            }
        }

        logger.info { "CVE fetch complete: $totalFetched total CVEs fetched" }
    }

    private fun parseCveEntry(vulnElement: com.google.gson.JsonElement): CveEntry {
        val cve = vulnElement.asJsonObject.getAsJsonObject("cve")
        val cveId = cve.get("id")?.asString ?: "UNKNOWN"

        // Extract description
        val descriptions = cve.get("descriptions")
        val description = if (descriptions != null && descriptions.isJsonArray) {
            val descriptionsArray = descriptions.asJsonArray
            descriptionsArray
                .firstOrNull { it.isJsonObject && it.asJsonObject.get("lang")?.asString == "en" }
                ?.asJsonObject?.get("value")?.asString
                ?: descriptionsArray.firstOrNull()?.asJsonObject?.get("value")?.asString
                ?: ""
        } else {
            ""
        }

        // Extract severity/CVSS
        val metricsElement = cve.get("metrics")
        val metricsObj = if (metricsElement != null && metricsElement.isJsonObject) {
            metricsElement.asJsonObject
        } else null

        val severity: String
        val baseScore: Double?
        val vectorString: String?

        when {
            metricsObj?.has("cvssMetricV31") == true -> {
                val cvssV31 = metricsObj.getAsJsonArray("cvssMetricV31").firstOrNull()?.asJsonObject
                val cvssData = cvssV31?.getAsJsonObject("cvssData")
                severity = cvssData?.get("baseSeverity")?.asString ?: "UNKNOWN"
                baseScore = cvssData?.get("baseScore")?.asDouble
                vectorString = cvssData?.get("vectorString")?.asString
            }
            metricsObj?.has("cvssMetricV30") == true -> {
                val cvssV30 = metricsObj.getAsJsonArray("cvssMetricV30").firstOrNull()?.asJsonObject
                val cvssData = cvssV30?.getAsJsonObject("cvssData")
                severity = cvssData?.get("baseSeverity")?.asString ?: "UNKNOWN"
                baseScore = cvssData?.get("baseScore")?.asDouble
                vectorString = cvssData?.get("vectorString")?.asString
            }
            metricsObj?.has("cvssMetricV2") == true -> {
                val cvssV2 = metricsObj.getAsJsonArray("cvssMetricV2").firstOrNull()?.asJsonObject
                val cvssData = cvssV2?.getAsJsonObject("cvssData")
                severity = when {
                    cvssData?.get("baseScore")?.asDouble?.let { it >= 7.0 } == true -> "HIGH"
                    cvssData?.get("baseScore")?.asDouble?.let { it >= 4.0 } == true -> "MEDIUM"
                    else -> "LOW"
                }
                baseScore = cvssData?.get("baseScore")?.asDouble
                vectorString = cvssData?.get("vectorString")?.asString
            }
            else -> {
                severity = "UNKNOWN"
                baseScore = null
                vectorString = null
            }
        }

        // Extract references
        val referencesElement = cve.get("references")
        val references = if (referencesElement != null && referencesElement.isJsonArray) {
            referencesElement.asJsonArray.mapNotNull { 
                if (it.isJsonObject) it.asJsonObject.get("url")?.asString else null 
            }
        } else {
            emptyList()
        }

        // Extract affected products/CPE configurations
        val configurationsElement = cve.get("configurations")
        val configurations = if (configurationsElement != null && configurationsElement.isJsonObject) {
            configurationsElement.asJsonObject
        } else null

        val affectedProducts = mutableListOf<String>()

        configurations?.get("nodes")?.let { nodesElement ->
            if (nodesElement.isJsonArray) {
                nodesElement.asJsonArray.forEach { node ->
                    if (node.isJsonObject) {
                        node.asJsonObject.get("cpeMatch")?.let { cpeMatchElement ->
                            if (cpeMatchElement.isJsonArray) {
                                cpeMatchElement.asJsonArray.forEach { cpeMatch ->
                                    if (cpeMatch.isJsonObject) {
                                        val cpe = cpeMatch.asJsonObject.get("criteria")?.asString
                                        if (cpe != null) {
                                            // Parse CPE to extract readable product name
                                            val parts = cpe.split(":")
                                            if (parts.size >= 5) {
                                                val vendor = parts[3]
                                                val product = parts[4]
                                                affectedProducts.add("$vendor:$product")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Extract dates
        val publishedDate = cve.get("published")?.asString ?: ""
        val lastModifiedDate = cve.get("lastModified")?.asString ?: ""

        return CveEntry(
            cveId = cveId,
            description = description,
            severity = severity,
            baseScore = baseScore,
            vectorString = vectorString,
            publishedDate = publishedDate,
            lastModifiedDate = lastModifiedDate,
            references = references,
            affectedProducts = affectedProducts.distinct()
        )
    }
}

data class CveEntry(
    val cveId: String,
    val description: String,
    val severity: String,
    val baseScore: Double?,
    val vectorString: String?,
    val publishedDate: String,
    val lastModifiedDate: String,
    val references: List<String>,
    val affectedProducts: List<String>
) {
    fun toText(): String {
        return buildString {
            appendLine("# $cveId")
            appendLine()
            appendLine("**Severity:** $severity${baseScore?.let { " (CVSS: $it)" } ?: ""}")
            appendLine("**Published:** $publishedDate")
            appendLine("**Last Modified:** $lastModifiedDate")
            appendLine()
            appendLine("## Description")
            appendLine(description)

            if (affectedProducts.isNotEmpty()) {
                appendLine()
                appendLine("## Affected Products")
                affectedProducts.take(10).forEach { product ->
                    appendLine("- $product")
                }
                if (affectedProducts.size > 10) {
                    appendLine("- ... and ${affectedProducts.size - 10} more")
                }
            }

            if (vectorString != null) {
                appendLine()
                appendLine("**CVSS Vector:** $vectorString")
            }

            if (references.isNotEmpty()) {
                appendLine()
                appendLine("## References")
                references.take(5).forEach { ref ->
                    appendLine("- $ref")
                }
                if (references.size > 5) {
                    appendLine("- ... and ${references.size - 5} more")
                }
            }
        }
    }

    /**
     * Generate a content hash for deduplication
     */
    fun contentHash(): String {
        return "$cveId:$lastModifiedDate".hashCode().toString()
    }
}
