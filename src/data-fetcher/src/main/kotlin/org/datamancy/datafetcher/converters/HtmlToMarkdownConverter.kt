package org.datamancy.datafetcher.converters

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

private val logger = KotlinLogging.logger {}

/**
 * Converts HTML content to Markdown format suitable for storage and indexing.
 */
object HtmlToMarkdownConverter {
    private val converter = FlexmarkHtmlConverter.builder().build()

    /**
     * Converts HTML string to clean Markdown.
     * Strips navigation, ads, and non-content elements before conversion.
     */
    fun convert(html: String, baseUrl: String = ""): String {
        return try {
            val doc = Jsoup.parse(html, baseUrl)

            // Clean up the document - remove non-content elements
            cleanDocument(doc)

            // Convert cleaned HTML to markdown
            val cleanedHtml = doc.body().html()
            val markdown = converter.convert(cleanedHtml)

            // Post-process markdown
            postProcessMarkdown(markdown)
        } catch (e: Exception) {
            logger.error(e) { "Failed to convert HTML to Markdown" }
            // Fallback: return plain text
            Jsoup.parse(html).text()
        }
    }

    /**
     * Removes navigation, ads, scripts, styles, and other non-content elements.
     */
    private fun cleanDocument(doc: Document) {
        // Remove common non-content elements
        val selectorsToRemove = listOf(
            "script",
            "style",
            "nav",
            "header",
            "footer",
            ".navigation",
            ".nav",
            ".menu",
            ".sidebar",
            ".advertisement",
            ".ad",
            ".social-share",
            ".breadcrumb",
            "#navigation",
            "#header",
            "#footer",
            "[role=navigation]",
            "[role=banner]",
            "[role=contentinfo]"
        )

        selectorsToRemove.forEach { selector ->
            doc.select(selector).remove()
        }

        // Remove empty paragraphs and divs
        doc.select("p:empty, div:empty").remove()

        // Remove comments
        doc.select("*").forEach { element ->
            element.childNodes()
                .filter { it.nodeName() == "#comment" }
                .forEach { it.remove() }
        }
    }

    /**
     * Cleans up common markdown formatting issues.
     */
    private fun postProcessMarkdown(markdown: String): String {
        return markdown
            // Remove excessive blank lines (more than 2)
            .replace(Regex("\n{3,}"), "\n\n")
            // Clean up list formatting
            .replace(Regex("^\\s*-\\s*$", RegexOption.MULTILINE), "")
            // Remove leading/trailing whitespace
            .trim()
            // Ensure consistent line endings
            .replace("\r\n", "\n")
    }

    /**
     * Converts HTML to markdown while preserving specific sections.
     * Useful for legislation where section structure matters.
     */
    fun convertWithSections(html: String, baseUrl: String = ""): List<MarkdownSection> {
        val doc = Jsoup.parse(html, baseUrl)
        cleanDocument(doc)

        val sections = mutableListOf<MarkdownSection>()

        // Try to find section elements (common patterns in legislation)
        val sectionElements = doc.select(
            "section, " +
            "div.section, " +
            "div.act-section, " +
            "div[id^=section], " +
            "div[id^=s], " +
            "article"
        )

        if (sectionElements.isNotEmpty()) {
            // Parse structured sections
            sectionElements.forEach { element ->
                val sectionId = element.attr("id").ifEmpty { null }
                val heading = element.select("h1, h2, h3, h4").firstOrNull()?.text() ?: ""
                val sectionNumber = extractSectionNumber(heading, sectionId)

                val sectionHtml = element.html()
                val markdown = converter.convert(sectionHtml)

                sections.add(MarkdownSection(
                    sectionNumber = sectionNumber,
                    title = heading.ifEmpty { "Section $sectionNumber" },
                    content = postProcessMarkdown(markdown)
                ))
            }
        } else {
            // No structured sections found, split by headings
            val headings = doc.select("h1, h2, h3")

            if (headings.isNotEmpty()) {
                headings.forEachIndexed { index, heading ->
                    val nextHeading = headings.getOrNull(index + 1)
                    val content = extractContentBetween(heading, nextHeading)
                    val sectionNumber = extractSectionNumber(heading.text(), null)

                    sections.add(MarkdownSection(
                        sectionNumber = sectionNumber,
                        title = heading.text(),
                        content = postProcessMarkdown(converter.convert(content))
                    ))
                }
            } else {
                // No sections or headings, return entire document as one section
                sections.add(MarkdownSection(
                    sectionNumber = "1",
                    title = "Full Document",
                    content = postProcessMarkdown(converter.convert(doc.body().html()))
                ))
            }
        }

        return sections
    }

    /**
     * Extracts section number from heading text or element ID.
     * Handles patterns like "Section 26", "s.26", "26.", etc.
     */
    private fun extractSectionNumber(text: String, elementId: String?): String {
        // Try to extract from text first
        val patterns = listOf(
            """(?:Section|Sec\.?|§)\s*(\d+[A-Z]*\d*)""".toRegex(RegexOption.IGNORE_CASE),
            """^(\d+[A-Z]*\d*)\.""".toRegex(),
            """^s\.?\s*(\d+[A-Z]*\d*)""".toRegex(RegexOption.IGNORE_CASE)
        )

        patterns.forEach { pattern ->
            pattern.find(text)?.groupValues?.getOrNull(1)?.let { return it }
        }

        // Try element ID
        elementId?.let {
            val idPattern = """(?:section|s)[-_]?(\d+[A-Z]*\d*)""".toRegex(RegexOption.IGNORE_CASE)
            idPattern.find(it)?.groupValues?.getOrNull(1)?.let { num -> return num }
        }

        // Fallback: return empty string
        return ""
    }

    /**
     * Extracts HTML content between two heading elements.
     */
    private fun extractContentBetween(start: org.jsoup.nodes.Element, end: org.jsoup.nodes.Element?): String {
        val content = StringBuilder()
        var current = start.nextElementSibling()

        while (current != null && current != end) {
            content.append(current.outerHtml())
            current = current.nextElementSibling()
        }

        return content.toString()
    }
}

/**
 * Represents a markdown section extracted from HTML.
 */
data class MarkdownSection(
    val sectionNumber: String,
    val title: String,
    val content: String
)
