package org.datamancy.pipeline.embedding

import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*


class EmbeddingSchedulerTest {

    private lateinit var stagingStore: DocumentStagingStore
    private lateinit var embedder: Embedder
    private lateinit var qdrantSink: QdrantSink
    private lateinit var scheduler: EmbeddingScheduler

    @BeforeEach
    fun setup() {
        stagingStore = mockk(relaxed = true)
        embedder = mockk()
        qdrantSink = mockk(relaxed = true)

        
        coEvery { embedder.process(any()) } returns floatArrayOf(0.1f, 0.2f, 0.3f)

        scheduler = EmbeddingScheduler(
            stagingStore = stagingStore,
            embedder = embedder,
            qdrantSinks = mapOf("test_collection" to qdrantSink),
            batchSize = 10,
            pollInterval = 1,
            maxConcurrentEmbeddings = 5,
            maxRetries = 3
        )
    }

    @AfterEach
    fun teardown() {
        clearAllMocks()
    }

    @Test
    fun `scheduler initializes with correct parameters`() {
        assertNotNull(scheduler)
    }

    @Test
    fun `getStats returns configuration values`() = runBlocking {
        coEvery { stagingStore.getStats() } returns mapOf(
            "pending" to 10L,
            "in_progress" to 2L,
            "completed" to 100L,
            "failed" to 1L
        )

        val stats = scheduler.getStats()

        assertEquals(10, stats["batch_size"])
        assertEquals(1, stats["poll_interval_seconds"])
        assertEquals(5, stats["max_concurrent"])
        assertEquals(10L, stats["pending"])
        assertEquals(2L, stats["in_progress"])
    }

    @Test
    fun `getStatsBySource calls staging store`() = runBlocking {
        coEvery { stagingStore.getStatsBySource("test_source") } returns mapOf(
            "pending" to 5L,
            "in_progress" to 1L,
            "completed" to 50L,
            "failed" to 0L
        )

        val stats = scheduler.getStatsBySource("test_source")

        assertEquals(5L, stats["pending"])
        assertEquals(1L, stats["in_progress"])
        assertEquals(50L, stats["completed"])
        assertEquals(0L, stats["failed"])
    }

    @Test
    fun `scheduler handles empty stats gracefully`() = runBlocking {
        coEvery { stagingStore.getStats() } returns emptyMap()

        val stats = scheduler.getStats()

        
        assertEquals(10, stats["batch_size"])
    }

    @Test
    fun `scheduler configuration is immutable after creation`() {
        val stats1 = runBlocking { scheduler.getStats() }
        val stats2 = runBlocking { scheduler.getStats() }

        assertEquals(stats1["batch_size"], stats2["batch_size"])
        assertEquals(stats1["poll_interval_seconds"], stats2["poll_interval_seconds"])
    }
}
