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
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

/**
 * Fetches Australian legislation from various government APIs
 *
 * Commonwealth: https://www.legislation.gov.au/
 * States have APIs but formats vary - this is a simplified implementation
 */
class AustralianLawsSource(
    private val jurisdictions: List<String> = listOf("commonwealth"),  // commonwealth, nsw, vic, qld, wa, sa, tas, act, nt
    private val maxLaws: Int = Int.MAX_VALUE
) : Source<AustralianLaw> {
    override val name = "AustralianLawsSource"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val delayMs = 1000L  // Be polite to government APIs

    override suspend fun fetch(): Flow<AustralianLaw> = flow {
        logger.info { "Starting Australian Laws fetch for jurisdictions: ${jurisdictions.joinToString()}" }

        var totalFetched = 0

        jurisdictions.forEach { jurisdiction ->
            if (totalFetched >= maxLaws) return@forEach

            try {
                when (jurisdiction.lowercase()) {
                    "commonwealth" -> fetchCommonwealth(totalFetched)
                    else -> {
                        logger.warn { "Jurisdiction $jurisdiction not implemented yet" }
                    }
                }

                delay(delayMs)

            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch laws for $jurisdiction: ${e.message}" }
            }
        }

        logger.info { "Australian Laws fetch complete: $totalFetched laws fetched" }
    }

    /**
     * Fetch Commonwealth legislation
     * Note: This is a placeholder - the actual API requires more complex handling
     * Real implementation would use https://www.legislation.gov.au/api/v1/
     */
    private suspend fun FlowCollector<AustralianLaw>.fetchCommonwealth(startCount: Int) {
        logger.info { "Fetching Commonwealth legislation..." }

        // This is a simplified version - real API is more complex
        // For now, emit a sample law to demonstrate the structure
        val sampleLaw = AustralianLaw(
            id = "C2004A00123",
            title = "Sample Commonwealth Act 2004",
            jurisdiction = "Commonwealth",
            type = "Act",
            year = "2004",
            number = "123",
            url = "https://www.legislation.gov.au/C2004A00123/latest",
            text = "This is sample legislation text. In a real implementation, this would fetch from the API.",
            sections = listOf(
                LawSection("1", "Short title", "This Act may be cited as the Sample Commonwealth Act 2004."),
                LawSection("2", "Commencement", "This Act commences on the day on which it receives the Royal Assent.")
            )
        )

        emit(sampleLaw)
        logger.info { "Fetched 1 Commonwealth law (sample)" }
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
