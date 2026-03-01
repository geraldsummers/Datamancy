package org.datamancy.pipeline.sinks

/**
 * Represents a document to be written to BookStack knowledge base.
 *
 * **Hierarchy Model:**
 * - `bookName`: Top-level container (e.g., "RSS Articles", "CVE Database")
 * - `chapterName`: Optional organizational layer (e.g., "Hacker News", "High Severity CVEs")
 * - `pageTitle`: Individual document title
 * - `pageContent`: HTML-formatted content for display
 *
 * **HTML Formatting:**
 * The pageContent field should contain HTML for best rendering in BookStack. Raw text is supported
 * but may not display optimally. Sources that generate Markdown should convert to HTML before
 * creating BookStackDocument instances.
 *
 * **Tags:**
 * Tags are metadata key-value pairs (e.g., "source: rss", "url: https://...", "published: 2024-01-15").
 * These appear in BookStack's UI and are searchable within the knowledge base.
 *
 * **Usage:**
 * Created by BookStackWriter from StagedDocument after embedding is complete. The writer transforms
 * source-specific metadata into BookStack's hierarchy structure.
 *
 * @property bookName The book name (top-level container)
 * @property bookDescription Optional book description
 * @property chapterName Optional chapter name (organizational layer)
 * @property chapterDescription Optional chapter description
 * @property pageTitle The page title (individual document)
 * @property pageContent HTML-formatted content for display
 * @property tags Metadata key-value pairs for search and organization
 */
data class BookStackDocument(
    val bookName: String,
    val bookDescription: String? = null,
    val chapterName: String? = null,
    val chapterDescription: String? = null,
    val pageTitle: String,
    val pageContent: String,
    val tags: Map<String, String> = emptyMap()
)
