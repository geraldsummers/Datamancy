package org.datamancy.pipeline.processors

import org.datamancy.pipeline.core.Processor
import org.datamancy.pipeline.sinks.VectorDocument
import org.datamancy.pipeline.sources.RssArticle

/**
 * Combines RSS article metadata with its embedding vector to create a VectorDocument for Qdrant storage.
 *
 * ## Purpose
 * This processor is the final transformation step before vector storage. It bridges the gap between:
 * - **Embedder output**: Raw FloatArray vectors (1024 dimensions)
 * - **Qdrant requirements**: VectorDocument with vector + metadata + unique ID
 *
 * The result is a complete Qdrant point ready to be inserted into a collection.
 *
 * ## Why Separate Metadata from Vector?
 * Qdrant stores vectors and metadata separately for efficiency:
 * - **Vectors**: Indexed in HNSW (Hierarchical Navigable Small World) graph for fast similarity search
 * - **Metadata**: Stored as payload, retrievable with search results but not indexed for similarity
 *
 * This processor packages both parts into the VectorDocument structure that QdrantSink expects.
 *
 * ## Metadata Strategy
 * Stores essential RSS metadata as Qdrant payload fields:
 * - **id**: article.guid (unique identifier, prevents duplicates)
 * - **title**: Full article title (displayed in search results)
 * - **link**: Source URL (enables users to read full article)
 * - **description**: First 200 chars (preview in search results)
 * - **publishedDate**: Timestamp (enables temporal filtering in Search-Service)
 * - **author**: Attribution (helps filter by source)
 * - **feedTitle/feedUrl**: Feed context (groups results by feed)
 * - **categories**: Topic tags (enables faceted search)
 * - **source**: Always "rss" (distinguishes from cve, wikipedia, etc. in multi-source setups)
 *
 * ## Why Truncate Description to 200 Chars?
 * - Qdrant payload storage has no hard limit, but large payloads slow down retrieval
 * - 200 chars provides enough preview context without bloating storage
 * - Full text is already embedded in the vector; description is just for display
 *
 * ## Data Flow in Pipeline
 * ```
 * RssToText → Chunker → Embedder → TextToVector → QdrantSink
 *                           ↓           ↓
 *                      FloatArray   VectorDocument
 * ```
 *
 * TextToVector receives a Pair<RssArticle, FloatArray>:
 * - RssArticle: Original metadata from RSS feed
 * - FloatArray: Embedding from Embedding Service (via Embedder)
 *
 * ## Integration with Search-Service
 * When Search-Service queries Qdrant, it receives VectorDocuments with this metadata.
 * The metadata enables:
 * - **Result presentation**: Show title, link, description to users
 * - **Temporal filtering**: Search only recent articles (publishedDate)
 * - **Source filtering**: Search only specific feeds (feedTitle)
 * - **Category filtering**: Search only specific topics (categories)
 *
 * ## articleProvider Parameter
 * Currently unused (legacy). Originally intended for stateful pipelines where the processor
 * needed to fetch the original article independently. In practice, the article is always
 * provided in the input Pair, making this parameter redundant.
 *
 * Could be removed in future refactoring without breaking functionality.
 *
 * @param articleProvider Legacy parameter (unused, could be removed)
 */
class TextToVector(
    private val articleProvider: () -> RssArticle?
) : Processor<Pair<RssArticle, FloatArray>, VectorDocument> {
    override val name = "TextToVector"

    /**
     * Packages RSS article metadata and embedding vector into a VectorDocument.
     *
     * ## Processing Steps
     * 1. Destructure input pair into (article, embedding)
     * 2. Extract article.guid as unique ID (prevents duplicate insertions in Qdrant)
     * 3. Build metadata map with all searchable/displayable fields
     * 4. Truncate description to 200 chars to keep payload size reasonable
     * 5. Return VectorDocument ready for QdrantSink
     *
     * ## Why GUID as ID?
     * RSS GUIDs are designed to be globally unique and stable:
     * - Same article re-fetched from feed = same GUID = Qdrant update (not duplicate)
     * - Different articles = different GUIDs = separate Qdrant points
     *
     * This enables the pipeline to re-run without creating duplicate vectors.
     *
     * @param input Pair of (RssArticle, FloatArray embedding)
     * @return VectorDocument with id, vector, and metadata ready for Qdrant insertion
     */
    override suspend fun process(input: Pair<RssArticle, FloatArray>): VectorDocument {
        val (article, embedding) = input

        return VectorDocument(
            id = article.guid,
            vector = embedding,
            metadata = mapOf(
                "title" to article.title,
                "link" to article.link,
                "description" to article.description.take(200),  // Truncate for efficient storage
                "publishedDate" to article.publishedDate,
                "author" to article.author,
                "feedTitle" to article.feedTitle,
                "feedUrl" to article.feedUrl,
                "categories" to article.categories.joinToString(","),
                "source" to "rss"  // Distinguishes from other sources (cve, wikipedia, etc.)
            )
        )
    }
}
