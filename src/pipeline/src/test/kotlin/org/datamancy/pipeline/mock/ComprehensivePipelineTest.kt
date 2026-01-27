package org.datamancy.pipeline.mock

import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.core.*
import org.datamancy.pipeline.processors.Chunker
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.processors.TextChunk
import org.datamancy.pipeline.scheduling.*
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.sinks.VectorDocument
import org.datamancy.pipeline.storage.DeduplicationStore
import org.datamancy.pipeline.storage.SourceMetadataStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Comprehensive Mock Test Suite
 *
 * Fast unit tests using mocks to verify:
 * - Pipeline orchestration logic
 * - Error handling and recovery
 * - Deduplication
 * - Chunking integration
 * - Metrics recording
 * - Edge cases
 */
class ComprehensivePipelineTest {

    private lateinit var mockSource: StandardizedSource<MockChunkable>
    private lateinit var mockQdrantSink: QdrantSink
    private lateinit var mockEmbedder: Embedder
    private lateinit var dedupStore: DeduplicationStore
    private lateinit var metadataStore: SourceMetadataStore
    private lateinit var tempDir: java.io.File

    @BeforeEach
    fun setup() {
        // Create isolated temp directory for each test
        tempDir = java.nio.file.Files.createTempDirectory("test-").toFile()

        mockSource = mockk()
        mockQdrantSink = mockk()
        mockEmbedder = mockk()
        dedupStore = DeduplicationStore(storePath = tempDir.absolutePath + "/dedup")
        metadataStore = SourceMetadataStore(storePath = tempDir.absolutePath + "/metadata")

        every { mockSource.name } returns "mock_source"
        every { mockSource.resyncStrategy() } returns ResyncStrategy.DailyAt(1, 0)
        every { mockSource.backfillStrategy() } returns BackfillStrategy.NoBackfill
        every { mockSource.needsChunking() } returns false
    }

    @Test
    fun `pipeline should process 1000 items efficiently`() = runBlocking {
        // Given: Large batch of items
        val items = (1..1000).map { MockChunkable("id-$it", "text-$it") }
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(*items.toTypedArray())
        coEvery { mockEmbedder.process(any()) } returns floatArrayOf(0.1f)
        coEvery { mockQdrantSink.write(any()) } just Runs

        // Use a test scheduler with runOnce=true
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        // When: Process through runner
        val runner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, testScheduler)
        runner.run()

        // Then: All items should be processed
        coVerify(exactly = 1000) { mockQdrantSink.write(any()) }
        assertEquals(1000, metadataStore.load("mock_source").totalItemsProcessed)
    }

    @Test
    fun `pipeline should handle embedding failures gracefully`() = runBlocking {
        // Given: Source with items where some embeddings fail
        val items = listOf(
            MockChunkable("id-1", "good"),
            MockChunkable("id-2", "bad"),
            MockChunkable("id-3", "good")
        )
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(*items.toTypedArray())
        coEvery { mockEmbedder.process("good") } returns floatArrayOf(0.1f)
        coEvery { mockEmbedder.process("bad") } throws RuntimeException("Embedding failed")
        coEvery { mockQdrantSink.write(any()) } just Runs

        // Use a test scheduler with runOnce=true
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        // When: Process through runner
        val runner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, testScheduler)
        runner.run()

        // Then: Good items should succeed, bad ones should be counted as failures
        coVerify(exactly = 2) { mockQdrantSink.write(any()) }
        val metadata = metadataStore.load("mock_source")
        assertEquals(2, metadata.totalItemsProcessed)
        assertEquals(1, metadata.totalItemsFailed)
    }

    @Test
    fun `pipeline should deduplicate across runs`() = runBlocking {
        // Given: Two runs with overlapping items
        val run1Items = listOf(
            MockChunkable("id-1", "text1"),
            MockChunkable("id-2", "text2")
        )
        val run2Items = listOf(
            MockChunkable("id-2", "text2"),  // Duplicate
            MockChunkable("id-3", "text3")   // New
        )

        coEvery { mockEmbedder.process(any()) } returns floatArrayOf(0.1f)
        coEvery { mockQdrantSink.write(any()) } just Runs

        // Use a test scheduler with runOnce=true
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        // First run
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(*run1Items.toTypedArray())
        var runner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, testScheduler)
        runner.run()

        // Second run - create new scheduler for second run
        val testScheduler2 = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(*run2Items.toTypedArray())
        runner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, testScheduler2)
        runner.run()

        // Then: Should process 3 unique items total (not 4)
        coVerify(exactly = 3) { mockQdrantSink.write(any()) }
    }

    @Test
    fun `chunking should create multiple vectors per item`() = runBlocking {
        // Given: Source with chunking enabled
        every { mockSource.needsChunking() } returns true
        every { mockSource.chunker() } returns mockk {
            coEvery { process("long text") } returns listOf(
                TextChunk("chunk1", 0, 0, 6, 2),
                TextChunk("chunk2", 1, 5, 10, 2)
            )
        }

        val item = MockChunkable("id-1", "long text")
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(item)
        coEvery { mockEmbedder.process(any()) } returns floatArrayOf(0.1f)
        coEvery { mockQdrantSink.write(any()) } just Runs

        // Use a test scheduler with runOnce=true
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        // When: Process
        val runner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, testScheduler)
        runner.run()

        // Then: Should create 2 vectors (one per chunk)
        coVerify(exactly = 2) { mockEmbedder.process(any()) }
        coVerify(exactly = 2) { mockQdrantSink.write(any()) }

        // And: Chunk IDs should be unique
        val capturedDocs = mutableListOf<VectorDocument>()
        coVerify { mockQdrantSink.write(capture(capturedDocs)) }
        assertEquals(2, capturedDocs.size)
        assertTrue(capturedDocs[0].id != capturedDocs[1].id, "Chunk IDs should be different")
    }

    @Test
    fun `empty source should not crash pipeline`() = runBlocking {
        // Given: Source that returns no items
        coEvery { mockSource.fetchForRun(any()) } returns flowOf()

        // Use a test scheduler with runOnce=true
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        // When: Process
        val runner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, testScheduler)
        runner.run()

        // Then: Should complete without error
        coVerify(exactly = 0) { mockQdrantSink.write(any()) }
        assertEquals(0, metadataStore.load("mock_source").totalItemsProcessed)
    }

    @Test
    fun `metadata should include chunk information`() = runBlocking {
        // Given: Chunked item
        every { mockSource.needsChunking() } returns true
        every { mockSource.chunker() } returns mockk {
            coEvery { process(any()) } returns listOf(
                TextChunk("chunk1", 0, 0, 100, 3),
                TextChunk("chunk2", 1, 90, 190, 3),
                TextChunk("chunk3", 2, 180, 280, 3)
            )
        }

        val item = MockChunkable("id-1", "very long text")
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(item)
        coEvery { mockEmbedder.process(any()) } returns floatArrayOf(0.1f)
        coEvery { mockQdrantSink.write(any()) } just Runs

        // Use a test scheduler with runOnce=true
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        // When: Process
        val runner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, testScheduler)
        runner.run()

        // Then: Metadata should include chunk info
        val capturedDocs = mutableListOf<VectorDocument>()
        coVerify { mockQdrantSink.write(capture(capturedDocs)) }

        capturedDocs.forEach { doc ->
            assertTrue(doc.metadata.containsKey("chunk_index"), "Should have chunk_index")
            assertTrue(doc.metadata.containsKey("total_chunks"), "Should have total_chunks")
            assertTrue(doc.metadata.containsKey("is_chunked"), "Should have is_chunked")
        }
    }

    @Test
    fun `items with identical IDs should be deduplicated`() = runBlocking {
        // Given: Items with same ID but different content
        val items = listOf(
            MockChunkable("same-id", "text1"),
            MockChunkable("same-id", "text2"),  // Duplicate ID
            MockChunkable("same-id", "text3")   // Duplicate ID
        )

        coEvery { mockSource.fetchForRun(any()) } returns flowOf(*items.toTypedArray())
        coEvery { mockEmbedder.process(any()) } returns floatArrayOf(0.1f)
        coEvery { mockQdrantSink.write(any()) } just Runs

        // Use a test scheduler with runOnce=true
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        // When: Process
        val runner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, testScheduler)
        runner.run()

        // Then: Should only process first occurrence
        coVerify(exactly = 1) { mockQdrantSink.write(any()) }
    }
}

// Mock data class
data class MockChunkable(
    private val id: String,
    private val text: String
) : Chunkable {
    override fun toText() = text
    override fun getId() = id
    override fun getMetadata() = mapOf("id" to id, "test" to "true")
}
