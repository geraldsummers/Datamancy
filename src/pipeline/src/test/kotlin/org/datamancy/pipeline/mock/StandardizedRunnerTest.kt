package org.datamancy.pipeline.core

import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.scheduling.*
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.sinks.VectorDocument
import org.datamancy.pipeline.storage.DeduplicationStore
import org.datamancy.pipeline.storage.SourceMetadataStore
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.BeforeTest

/**
 * Tests for StandardizedRunner - the core orchestration component
 */
class StandardizedRunnerTest {

    private lateinit var mockSource: StandardizedSource<TestChunkable>
    private lateinit var mockQdrantSink: QdrantSink
    private lateinit var mockEmbedder: Embedder
    private lateinit var dedupStore: DeduplicationStore
    private lateinit var metadataStore: SourceMetadataStore
    private lateinit var runner: StandardizedRunner<TestChunkable>
    private lateinit var tempDir: java.io.File

    @BeforeTest
    fun setup() {
        // Create isolated temp directory for each test
        tempDir = java.nio.file.Files.createTempDirectory("test-").toFile()

        mockSource = mockk()
        mockQdrantSink = mockk()
        mockEmbedder = mockk()
        dedupStore = DeduplicationStore(storePath = tempDir.absolutePath + "/dedup")
        metadataStore = SourceMetadataStore(storePath = tempDir.absolutePath + "/metadata")

        every { mockSource.name } returns "test"
        every { mockSource.resyncStrategy() } returns ResyncStrategy.DailyAt(hour = 1, minute = 0)
        every { mockSource.needsChunking() } returns false

        runner = StandardizedRunner(
            source = mockSource,
            qdrantSink = mockQdrantSink,
            embedder = mockEmbedder,
            dedupStore = dedupStore,
            metadataStore = metadataStore
        )
    }

    @Test
    fun `should process items without chunking`() = runBlocking {
        // Given: Source that doesn't need chunking
        val testItem = TestChunkable("test-1", "Hello world")
        val testVector = floatArrayOf(0.1f, 0.2f, 0.3f)

        coEvery { mockSource.fetchForRun(any()) } returns flowOf(testItem)
        coEvery { mockEmbedder.process(any()) } returns testVector
        coEvery { mockQdrantSink.write(any()) } just Runs

        // Use a test scheduler with runOnce=true
        val testScheduler = SourceScheduler(
            sourceName = "test",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )
        val testRunner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, testScheduler)

        // When: Runner processes source
        testRunner.run()

        // Then: Item should be embedded and stored
        coVerify(exactly = 1) { mockEmbedder.process("Hello world") }
        coVerify(exactly = 1) {
            mockQdrantSink.write(match {
                it.id == "test-1" && it.vector.contentEquals(testVector)
            })
        }
    }

    @Test
    fun `should skip duplicate items`() = runBlocking {
        // Given: Two identical items
        val item1 = TestChunkable("test-1", "Hello")
        val item2 = TestChunkable("test-1", "Hello")

        coEvery { mockSource.fetchForRun(any()) } returns flowOf(item1, item2)
        coEvery { mockEmbedder.process(any()) } returns floatArrayOf(0.1f)
        coEvery { mockQdrantSink.write(any()) } just Runs

        // Use a test scheduler with runOnce=true
        val testScheduler = SourceScheduler(
            sourceName = "test",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )
        val testRunner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, testScheduler)

        // When: Runner processes source
        testRunner.run()

        // Then: Only one item should be processed
        coVerify(exactly = 1) { mockEmbedder.process(any()) }
        coVerify(exactly = 1) { mockQdrantSink.write(any()) }
    }

    @Test
    fun `should process items with chunking`() = runBlocking {
        // Given: Source that needs chunking
        every { mockSource.needsChunking() } returns true
        every { mockSource.chunker() } returns mockk {
            coEvery { process(any()) } returns listOf(
                org.datamancy.pipeline.processors.TextChunk("chunk1", 0, 0, 100, 2),
                org.datamancy.pipeline.processors.TextChunk("chunk2", 1, 90, 200, 2)
            )
        }

        val testItem = TestChunkable("test-1", "Long text that needs chunking")
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(testItem)
        coEvery { mockEmbedder.process(any()) } returns floatArrayOf(0.1f, 0.2f)
        coEvery { mockQdrantSink.write(any()) } just Runs

        // Use a test scheduler with runOnce=true
        val testScheduler = SourceScheduler(
            sourceName = "test",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )
        val testRunner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, testScheduler)

        // When: Runner processes source
        testRunner.run()

        // Then: Both chunks should be processed
        coVerify(exactly = 2) { mockEmbedder.process(any()) }
        coVerify(exactly = 2) { mockQdrantSink.write(any()) }
    }

    @Test
    fun `should record success metrics`() = runBlocking {
        // Given: Successful processing
        val testItem = TestChunkable("test-1", "Hello")
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(testItem)
        coEvery { mockEmbedder.process(any()) } returns floatArrayOf(0.1f)
        coEvery { mockQdrantSink.write(any()) } just Runs

        // Mock scheduler
        val mockScheduler = mockk<SourceScheduler>()
        coEvery { mockScheduler.schedule(any()) } coAnswers {
            val onRun = firstArg<suspend (RunMetadata) -> Unit>()
            onRun(RunMetadata(RunType.INITIAL_PULL, 1, true))
        }

        // When: Runner processes source with mocked scheduler
        val testRunner = StandardizedRunner(mockSource, mockQdrantSink, mockEmbedder, dedupStore, metadataStore, null, mockScheduler)
        testRunner.run()

        // Then: Metadata should be recorded
        val metadata = metadataStore.load("test")
        assertEquals(1, metadata.totalItemsProcessed)
        assertEquals(0, metadata.totalItemsFailed)
    }
}

// Test implementation of Chunkable
data class TestChunkable(
    private val id: String,
    private val text: String
) : Chunkable {
    override fun toText(): String = text
    override fun getId(): String = id
    override fun getMetadata(): Map<String, String> = mapOf("id" to id)
}
