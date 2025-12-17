package org.datamancy.searchservice

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class SearchGatewayTest {

    private lateinit var searchGateway: SearchGateway

    @BeforeEach
    fun setup() {
        // Note: We're only testing pure functions that don't require external dependencies
        searchGateway = SearchGateway(
            qdrantUrl = "http://localhost:6334",
            clickhouseUrl = "http://localhost:8123",
            embeddingServiceUrl = "http://localhost:8080"
        )
    }

    @Test
    fun `rerank should combine vector and BM25 results using RRF`() {
        // Given
        val vectorResults = listOf(
            SearchResult("url1", "Title 1", "snippet1", 0.9, "collection1", mapOf("type" to "vector")),
            SearchResult("url2", "Title 2", "snippet2", 0.8, "collection1", mapOf("type" to "vector")),
            SearchResult("url3", "Title 3", "snippet3", 0.7, "collection1", mapOf("type" to "vector"))
        )
        val bm25Results = listOf(
            SearchResult("url2", "Title 2", "snippet2", 12.0, "collection1", mapOf("type" to "bm25")),
            SearchResult("url4", "Title 4", "snippet4", 10.0, "collection1", mapOf("type" to "bm25")),
            SearchResult("url1", "Title 1", "snippet1", 8.0, "collection1", mapOf("type" to "bm25"))
        )
        val limit = 5

        // Use reflection to call private rerank method
        val rerankMethod = SearchGateway::class.java.getDeclaredMethod(
            "rerank",
            List::class.java,
            List::class.java,
            Int::class.java
        )
        rerankMethod.isAccessible = true

        // When
        @Suppress("UNCHECKED_CAST")
        val result = rerankMethod.invoke(searchGateway, vectorResults, bm25Results, limit) as List<SearchResult>

        // Then
        assertNotNull(result)
        assertTrue(result.size <= limit, "Result size should not exceed limit")
        assertTrue(result.isNotEmpty(), "Should return combined results")

        // url2 appears in both lists (rank 1 in vector, rank 0 in bm25), should rank highly
        // url1 appears in both lists (rank 0 in vector, rank 2 in bm25), should rank highly
        val urls = result.map { it.url }
        assertTrue(urls.contains("url1"), "url1 should be in results (appears in both)")
        assertTrue(urls.contains("url2"), "url2 should be in results (appears in both)")

        // Verify reranked_score is added to metadata
        result.forEach { searchResult ->
            assertTrue(searchResult.metadata.containsKey("reranked_score"), "Should contain reranked_score")
        }
    }

    @Test
    fun `rerank should handle empty vector results`() {
        // Given
        val vectorResults = emptyList<SearchResult>()
        val bm25Results = listOf(
            SearchResult("url1", "Title 1", "snippet1", 10.0, "collection1", mapOf("type" to "bm25")),
            SearchResult("url2", "Title 2", "snippet2", 8.0, "collection1", mapOf("type" to "bm25"))
        )
        val limit = 5

        // Use reflection to call private rerank method
        val rerankMethod = SearchGateway::class.java.getDeclaredMethod(
            "rerank",
            List::class.java,
            List::class.java,
            Int::class.java
        )
        rerankMethod.isAccessible = true

        // When
        @Suppress("UNCHECKED_CAST")
        val result = rerankMethod.invoke(searchGateway, vectorResults, bm25Results, limit) as List<SearchResult>

        // Then
        assertEquals(2, result.size, "Should return all BM25 results")
        assertEquals("url1", result[0].url, "Should maintain ranking from BM25")
    }

    @Test
    fun `rerank should handle empty BM25 results`() {
        // Given
        val vectorResults = listOf(
            SearchResult("url1", "Title 1", "snippet1", 0.9, "collection1", mapOf("type" to "vector")),
            SearchResult("url2", "Title 2", "snippet2", 0.8, "collection1", mapOf("type" to "vector"))
        )
        val bm25Results = emptyList<SearchResult>()
        val limit = 5

        // Use reflection to call private rerank method
        val rerankMethod = SearchGateway::class.java.getDeclaredMethod(
            "rerank",
            List::class.java,
            List::class.java,
            Int::class.java
        )
        rerankMethod.isAccessible = true

        // When
        @Suppress("UNCHECKED_CAST")
        val result = rerankMethod.invoke(searchGateway, vectorResults, bm25Results, limit) as List<SearchResult>

        // Then
        assertEquals(2, result.size, "Should return all vector results")
        assertEquals("url1", result[0].url, "Should maintain ranking from vector")
    }

    @Test
    fun `rerank should respect limit parameter`() {
        // Given
        val vectorResults = listOf(
            SearchResult("url1", "Title 1", "snippet1", 0.9, "collection1"),
            SearchResult("url2", "Title 2", "snippet2", 0.8, "collection1"),
            SearchResult("url3", "Title 3", "snippet3", 0.7, "collection1")
        )
        val bm25Results = listOf(
            SearchResult("url4", "Title 4", "snippet4", 10.0, "collection1"),
            SearchResult("url5", "Title 5", "snippet5", 9.0, "collection1"),
            SearchResult("url6", "Title 6", "snippet6", 8.0, "collection1")
        )
        val limit = 3

        // Use reflection to call private rerank method
        val rerankMethod = SearchGateway::class.java.getDeclaredMethod(
            "rerank",
            List::class.java,
            List::class.java,
            Int::class.java
        )
        rerankMethod.isAccessible = true

        // When
        @Suppress("UNCHECKED_CAST")
        val result = rerankMethod.invoke(searchGateway, vectorResults, bm25Results, limit) as List<SearchResult>

        // Then
        assertEquals(limit, result.size, "Should respect limit parameter")
    }

    @Test
    fun `rerank should handle duplicate URLs across both result sets`() {
        // Given
        val vectorResults = listOf(
            SearchResult("url1", "Title 1", "snippet1", 0.9, "collection1", mapOf("type" to "vector"))
        )
        val bm25Results = listOf(
            SearchResult("url1", "Title 1 BM25", "snippet1 bm25", 10.0, "collection1", mapOf("type" to "bm25"))
        )
        val limit = 5

        // Use reflection to call private rerank method
        val rerankMethod = SearchGateway::class.java.getDeclaredMethod(
            "rerank",
            List::class.java,
            List::class.java,
            Int::class.java
        )
        rerankMethod.isAccessible = true

        // When
        @Suppress("UNCHECKED_CAST")
        val result = rerankMethod.invoke(searchGateway, vectorResults, bm25Results, limit) as List<SearchResult>

        // Then
        assertEquals(1, result.size, "Should deduplicate URLs")
        assertEquals("url1", result[0].url)
        // The score should be higher due to appearing in both result sets
        val rerankedScore = result[0].metadata["reranked_score"]?.toDoubleOrNull()
        assertNotNull(rerankedScore)
        assertTrue(rerankedScore!! > 0.01, "Reranked score should be boosted for appearing in both")
    }

    @Test
    fun `rerank should sort results by combined RRF score descending`() {
        // Given
        val vectorResults = listOf(
            SearchResult("url1", "Title 1", "snippet1", 0.9, "collection1"),
            SearchResult("url2", "Title 2", "snippet2", 0.5, "collection1")
        )
        val bm25Results = listOf(
            SearchResult("url2", "Title 2", "snippet2", 15.0, "collection1"),
            SearchResult("url3", "Title 3", "snippet3", 5.0, "collection1")
        )
        val limit = 10

        // Use reflection to call private rerank method
        val rerankMethod = SearchGateway::class.java.getDeclaredMethod(
            "rerank",
            List::class.java,
            List::class.java,
            Int::class.java
        )
        rerankMethod.isAccessible = true

        // When
        @Suppress("UNCHECKED_CAST")
        val result = rerankMethod.invoke(searchGateway, vectorResults, bm25Results, limit) as List<SearchResult>

        // Then
        assertTrue(result.size >= 2)
        // Verify scores are descending
        for (i in 0 until result.size - 1) {
            val score1 = result[i].score
            val score2 = result[i + 1].score
            assertTrue(score1 >= score2, "Results should be sorted by score descending")
        }
    }
}
