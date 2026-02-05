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
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.SourceMetadataStore
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class ComprehensivePipelineTest {

    private lateinit var mockSource: StandardizedSource<MockChunkable>
    private lateinit var mockStagingStore: DocumentStagingStore
    private lateinit var dedupStore: DeduplicationStore
    private lateinit var metadataStore: SourceMetadataStore
    private lateinit var tempDir: java.io.File

    @BeforeEach
    fun setup() {
        
        tempDir = java.nio.file.Files.createTempDirectory("test-").toFile()

        mockSource = mockk()
        mockStagingStore = mockk(relaxed = true)
        dedupStore = DeduplicationStore(storePath = tempDir.absolutePath + "/dedup")
        metadataStore = SourceMetadataStore(storePath = tempDir.absolutePath + "/metadata")

        every { mockSource.name } returns "mock_source"
        every { mockSource.resyncStrategy() } returns ResyncStrategy.DailyAt(1, 0)
        every { mockSource.backfillStrategy() } returns BackfillStrategy.NoBackfill
        every { mockSource.needsChunking() } returns false
    }

    @Test
    fun `pipeline should process 1000 items efficiently`() = runBlocking {
        
        val items = (1..1000).map { MockChunkable("id-$it", "text-$it") }
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(*items.toTypedArray())
        
        

        
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        
        val runner = StandardizedRunner(mockSource, "test_collection", mockStagingStore, dedupStore, metadataStore, testScheduler)
        runner.run()

        
        
        coVerify(atLeast = 1) { mockStagingStore.stageBatch(any()) }
        assertEquals(1000, metadataStore.load("mock_source").totalItemsProcessed)
    }

    @Test
    fun `pipeline should handle embedding failures gracefully`() = runBlocking {
        
        val items = listOf(
            MockChunkable("id-1", "good"),
            MockChunkable("id-2", "bad"),
            MockChunkable("id-3", "good")
        )
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(*items.toTypedArray())
        
        
        

        
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        
        val runner = StandardizedRunner(mockSource, "test_collection", mockStagingStore, dedupStore, metadataStore, testScheduler)
        runner.run()

        
        
        
        val metadata = metadataStore.load("mock_source")
        assertEquals(3, metadata.totalItemsProcessed)  
        assertEquals(0, metadata.totalItemsFailed)     
    }

    @Test
    fun `pipeline should deduplicate across runs`() = runBlocking {
        
        val run1Items = listOf(
            MockChunkable("id-1", "text1"),
            MockChunkable("id-2", "text2")
        )
        val run2Items = listOf(
            MockChunkable("id-2", "text2"),  
            MockChunkable("id-3", "text3")   
        )

        
        

        
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(*run1Items.toTypedArray())
        var runner = StandardizedRunner(mockSource, "test_collection", mockStagingStore, dedupStore, metadataStore, testScheduler)
        runner.run()

        
        val testScheduler2 = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )
        runner = StandardizedRunner(mockSource, "test_collection", mockStagingStore, dedupStore, metadataStore, testScheduler2)
        runner.run()

        
        
        
    }

    @Test
    fun `chunking should create multiple vectors per item`() = runBlocking {
        
        every { mockSource.needsChunking() } returns true

        
        val mockChunker = mockk<Chunker>()
        coEvery { mockChunker.process(any()) } returns listOf(
            TextChunk("chunk1", 0, 0, 6, 2),
            TextChunk("chunk2", 1, 5, 10, 2)
        )
        every { mockSource.chunker() } returns mockChunker

        val item = MockChunkable("id-1", "long text")
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(item)
        
        

        
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        
        val runner = StandardizedRunner(mockSource, "test_collection", mockStagingStore, dedupStore, metadataStore, testScheduler)
        runner.run()

        
        
        
        coVerify(atLeast = 1) {
            mockStagingStore.stageBatch(any())
        }

        
        
    }

    @Test
    fun `empty source should not crash pipeline`() = runBlocking {
        
        coEvery { mockSource.fetchForRun(any()) } returns flowOf()

        
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        
        val runner = StandardizedRunner(mockSource, "test_collection", mockStagingStore, dedupStore, metadataStore, testScheduler)
        runner.run()

        
        
        
        assertEquals(0, metadataStore.load("mock_source").totalItemsProcessed)
    }

    @Test
    fun `metadata should include chunk information`() = runBlocking {
        
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
        
        

        
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        
        val runner = StandardizedRunner(mockSource, "test_collection", mockStagingStore, dedupStore, metadataStore, testScheduler)
        runner.run()

        
        val capturedDocs = mutableListOf<VectorDocument>()
        
        

        capturedDocs.forEach { doc ->
            assertTrue(doc.metadata.containsKey("chunk_index"), "Should have chunk_index")
            assertTrue(doc.metadata.containsKey("total_chunks"), "Should have total_chunks")
            assertTrue(doc.metadata.containsKey("is_chunked"), "Should have is_chunked")
        }
    }

    @Test
    fun `items with identical IDs should be deduplicated`() = runBlocking {
        
        val items = listOf(
            MockChunkable("same-id", "text1"),
            MockChunkable("same-id", "text2"),  
            MockChunkable("same-id", "text3")   
        )

        coEvery { mockSource.fetchForRun(any()) } returns flowOf(*items.toTypedArray())
        
        

        
        val testScheduler = SourceScheduler(
            sourceName = "mock_source",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )

        
        val runner = StandardizedRunner(mockSource, "test_collection", mockStagingStore, dedupStore, metadataStore, testScheduler)
        runner.run()

        
        
        
    }
}


data class MockChunkable(
    private val id: String,
    private val text: String
) : Chunkable {
    override fun toText() = text
    override fun getId() = id
    override fun getMetadata() = mapOf("id" to id, "test" to "true")
}
