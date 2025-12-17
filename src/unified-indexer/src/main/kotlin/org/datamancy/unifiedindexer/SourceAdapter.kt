package org.datamancy.unifiedindexer

/**
 * Interface for data source adapters.
 * Allows indexing from different sources (BookStack, PDFs, databases, etc.)
 */
interface SourceAdapter {
    /**
     * Get all pages/documents from this source for the specified collection.
     */
    suspend fun getPages(collection: String): List<PageInfo>

    /**
     * Export a single page/document as plain text.
     */
    suspend fun exportPage(pageId: Int): String
}

/**
 * Generic page/document information.
 */
data class PageInfo(
    val id: Int,
    val name: String,
    val url: String
)
