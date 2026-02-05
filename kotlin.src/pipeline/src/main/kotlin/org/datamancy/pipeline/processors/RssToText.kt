package org.datamancy.pipeline.processors

import org.datamancy.pipeline.core.Processor
import org.datamancy.pipeline.sources.RssArticle

/**
 * Converts RSS article metadata into plain text suitable for embedding.
 *
 * ## Purpose
 * RSS feeds provide structured data (title, description, link, author, categories, etc.).
 * Embedding models need plain text. This processor flattens RssArticle objects into a
 * text representation that preserves key metadata while being embeddable.
 *
 * ## Text Format
 * The exact format is defined by RssArticle.toText(). Typically concatenates:
 * - Title (most important for semantic search)
 * - Description (article summary)
 * - Author (attribution context)
 * - Categories/tags (topic keywords)
 * - Published date (temporal context)
 *
 * Example output:
 * ```
 * Title: New Kubernetes 1.28 Release Announced
 * Description: Kubernetes 1.28 brings sidecar containers and improved security...
 * Author: Kubernetes Blog
 * Categories: cloud, containers, kubernetes
 * Published: 2024-01-15
 * ```
 *
 * ## Why Not Just Embed the Title?
 * Embedding only the title loses important context:
 * - Description provides semantic depth (what the article is really about)
 * - Categories provide topical context (helps cluster similar content)
 * - Author/source helps distinguish technical docs from blog posts
 *
 * Embedding the full metadata improves Search-Service retrieval accuracy when users
 * query for specific topics, authors, or time periods.
 *
 * ## Data Flow in Pipeline
 * ```
 * RssSource → RssToText → Chunker → Embedder → TextToVector → QdrantSink
 *     ↓            ↓
 * RssArticle    String
 * ```
 *
 * This processor is typically the first transformation step after data ingestion,
 * converting structured feed data into the text domain where token-based processing begins.
 *
 * ## Integration with Other Processors
 * - **Input**: RssArticle (from RssSource or similar feed readers)
 * - **Output**: String (passed to Chunker or directly to Embedder for small articles)
 *
 * Simple, stateless processor with no external dependencies or retry logic needed.
 */
class RssToText : Processor<RssArticle, String> {
    override val name = "RssToText"

    /**
     * Converts an RSS article to plain text using the article's toText() method.
     *
     * Delegates to RssArticle.toText() to maintain consistent text formatting across
     * the pipeline. If the text format needs to change (e.g., include more/less metadata),
     * modify RssArticle.toText() rather than this processor.
     *
     * @param input RssArticle with title, description, link, author, categories, etc.
     * @return Plain text representation suitable for embedding
     */
    override suspend fun process(input: RssArticle): String {
        return input.toText()
    }
}
