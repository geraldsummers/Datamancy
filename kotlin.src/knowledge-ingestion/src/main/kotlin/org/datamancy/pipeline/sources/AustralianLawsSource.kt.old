package org.datamancy.pipeline.sources

import com.google.gson.JsonParser
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.datamancy.pipeline.core.Source
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Fetches Australian legislation from AustLII (Australasian Legal Information Institute)
 *
 * AustLII provides free access to legislation from all Australian jurisdictions:
 * - Commonwealth (Federal)
 * - New South Wales (NSW)
 * - Victoria (VIC)
 * - Queensland (QLD)
 * - Western Australia (WA)
 * - South Australia (SA)
 * - Tasmania (TAS)
 * - Australian Capital Territory (ACT)
 * - Northern Territory (NT)
 *
 * Sources:
 * - AustLII: https://www.austlii.edu.au/
 * - Queensland API: https://api.legislation.qld.gov.au/ (requires registration)
 */
class AustralianLawsSource(
    private val jurisdictions: List<String> = listOf("commonwealth", "nsw", "vic", "qld", "wa", "sa", "tas", "act", "nt"),
    private val maxLawsPerJurisdiction: Int = 100,
    private val startYear: Int = 2020  // Focus on recent legislation
) : Source<AustralianLaw> {
    override val name = "AustralianLawsSource"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val delayMs = 2000L  // Be respectful to AustLII servers

    // AustLII jurisdiction mappings
    private val jurisdictionPaths = mapOf(
        "commonwealth" to "au/legis/cth/consol_act",
        "nsw" to "au/legis/nsw/consol_act",
        "vic" to "au/legis/vic/consol_act",
        "qld" to "au/legis/qld/consol_act",
        "wa" to "au/legis/wa/consol_act",
        "sa" to "au/legis/sa/consol_act",
        "tas" to "au/legis/tas/consol_act",
        "act" to "au/legis/act/consol_act",
        "nt" to "au/legis/nt/consol_act"
    )

    private val jurisdictionNames = mapOf(
        "commonwealth" to "Commonwealth of Australia",
        "nsw" to "New South Wales",
        "vic" to "Victoria",
        "qld" to "Queensland",
        "wa" to "Western Australia",
        "sa" to "South Australia",
        "tas" to "Tasmania",
        "act" to "Australian Capital Territory",
        "nt" to "Northern Territory"
    )

    override suspend fun fetch(): Flow<AustralianLaw> = flow {
        logger.info { "Starting Australian Laws fetch for ${jurisdictions.size} jurisdictions from year $startYear onwards" }

        var totalFetched = 0

        for (jurisdiction in jurisdictions) {
            if (totalFetched >= maxLawsPerJurisdiction * jurisdictions.size) break

            try {
                val path = jurisdictionPaths[jurisdiction.lowercase()]
                if (path == null) {
                    logger.warn { "Unknown jurisdiction: $jurisdiction" }
                    continue
                }

                logger.info { "Fetching ${jurisdictionNames[jurisdiction.lowercase()]} legislation..." }

                val fetched = fetchFromAustLII(jurisdiction, path)
                totalFetched += fetched

                logger.info { "Fetched $fetched laws from ${jurisdictionNames[jurisdiction.lowercase()]}" }

                delay(delayMs)

            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch laws for $jurisdiction: ${e.message}" }
            }
        }

        logger.info { "Australian Laws fetch complete: $totalFetched laws fetched from ${jurisdictions.size} jurisdictions" }
    }

    /**
     * Fetch legislation from AustLII for a specific jurisdiction
     */
    private suspend fun FlowCollector<AustralianLaw>.fetchFromAustLII(
        jurisdiction: String,
        path: String
    ): Int {
        var count = 0

        try {
            // Get the index page for this jurisdiction
            val indexUrl = "https://www.austlii.edu.au/$path/"
            logger.debug { "Fetching index: $indexUrl" }

            val request = Request.Builder()
                .url(indexUrl)
                .header("User-Agent", "Datamancy-Pipeline/1.0 (Educational/Research Purpose)")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                logger.error { "Failed to fetch index for $jurisdiction: ${response.code}" }
                return 0
            }

            val html = response.body?.string() ?: return 0
            val doc = Jsoup.parse(html)

            // Extract act links from the index page
            val actLinks = doc.select("a[href*='/consol_act/']")
                .map { it.attr("abs:href") }
                .filter { it.isNotBlank() }
                .distinct()
                .take(maxLawsPerJurisdiction)

            logger.info { "Found ${actLinks.size} acts in ${jurisdictionNames[jurisdiction]} index" }

            for (actUrl in actLinks) {
                if (count >= maxLawsPerJurisdiction) break

                try {
                    delay(500)  // Rate limiting between individual acts

                    val act = fetchActFromAustLII(jurisdiction, actUrl)
                    if (act != null) {
                        emit(act)
                        count++

                        if (count % 10 == 0) {
                            logger.info { "Processed $count/${actLinks.size} acts from ${jurisdictionNames[jurisdiction]}" }
                        }
                    }

                } catch (e: Exception) {
                    logger.error(e) { "Failed to fetch act from $actUrl: ${e.message}" }
                }
            }

        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch from AustLII for $jurisdiction: ${e.message}" }
        }

        return count
    }

    /**
     * Fetch a single act from AustLII
     */
    private fun fetchActFromAustLII(jurisdiction: String, url: String): AustralianLaw? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Datamancy-Pipeline/1.0 (Educational/Research Purpose)")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                logger.warn { "Failed to fetch act: ${response.code} $url" }
                return null
            }

            val html = response.body?.string() ?: return null
            val doc = Jsoup.parse(html)

            // Extract title
            val title = doc.select("h1").firstOrNull()?.text()
                ?: doc.select("title").firstOrNull()?.text()
                ?: "Unknown Act"

            // Extract act ID from URL
            val actId = url.substringAfterLast("/").substringBefore(".")

            // Extract year from title or URL
            val yearMatch = Regex("\\b(19|20)\\d{2}\\b").find(title)
            val year = yearMatch?.value ?: "Unknown"

            // Skip old legislation if startYear is set
            if (year != "Unknown" && year.toIntOrNull() != null && year.toInt() < startYear) {
                return null
            }

            // Extract main content (remove navigation, headers, footers)
            val content = doc.select("body")
                .select("*")
                .filter { element ->
                    val text = element.ownText()
                    text.isNotBlank() && text.length > 20
                }
                .joinToString("\n") { it.text() }
                .take(10000)  // Limit content size for embeddings

            // Extract type (Act, Regulation, etc.)
            val actType = when {
                title.contains("Act", ignoreCase = true) -> "Act"
                title.contains("Regulation", ignoreCase = true) -> "Regulation"
                title.contains("Rule", ignoreCase = true) -> "Rule"
                else -> "Legislation"
            }

            return AustralianLaw(
                id = "$jurisdiction:$actId",
                title = title,
                jurisdiction = jurisdictionNames[jurisdiction.lowercase()] ?: jurisdiction,
                type = actType,
                year = year,
                number = actId,
                url = url,
                text = content,
                sections = emptyList()  // Section parsing would require more complex HTML parsing
            )

        } catch (e: Exception) {
            logger.error(e) { "Failed to parse act from $url: ${e.message}" }
            return null
        }
    }
}

data class LawSection(
    val number: String,
    val title: String,
    val text: String
)

data class AustralianLaw(
    val id: String,
    val title: String,
    val jurisdiction: String,  // commonwealth, nsw, vic, etc.
    val type: String,  // Act, Regulation, etc.
    val year: String,
    val number: String,
    val url: String,
    val text: String,
    val sections: List<LawSection> = emptyList()
) {
    fun toText(): String {
        return buildString {
            appendLine("# $title")
            appendLine()
            appendLine("**Jurisdiction:** $jurisdiction")
            appendLine("**Type:** $type")
            appendLine("**Year:** $year")
            appendLine("**Number:** $number")
            appendLine("**URL:** $url")
            appendLine()

            if (sections.isNotEmpty()) {
                appendLine("## Sections")
                sections.forEach { section ->
                    appendLine()
                    appendLine("### Section ${section.number}: ${section.title}")
                    appendLine(section.text)
                }
            } else {
                appendLine("## Full Text")
                appendLine(text.take(2000))  // Truncate for display
                if (text.length > 2000) {
                    appendLine("...")
                    appendLine("*(Full text: ${text.length} characters)*")
                }
            }
        }
    }

    fun contentHash(): String {
        return id.hashCode().toString()
    }
}
