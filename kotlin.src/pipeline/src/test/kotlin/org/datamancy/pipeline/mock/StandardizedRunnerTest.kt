package org.datamancy.pipeline.core

import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.processors.Embedder
import org.datamancy.pipeline.scheduling.*
import org.datamancy.pipeline.sinks.QdrantSink
import org.datamancy.pipeline.sinks.VectorDocument
import org.datamancy.pipeline.storage.DeduplicationStore
import org.datamancy.pipeline.storage.DocumentStagingStore
import org.datamancy.pipeline.storage.SourceMetadataStore
import org.datamancy.pipeline.storage.StagedDocument
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.BeforeTest


class StandardizedRunnerTest {

    private lateinit var mockSource: StandardizedSource<TestChunkable>
    private lateinit var mockStagingStore: DocumentStagingStore
    private lateinit var dedupStore: DeduplicationStore
    private lateinit var metadataStore: SourceMetadataStore
    private lateinit var runner: StandardizedRunner<TestChunkable>
    private lateinit var tempDir: java.io.File

    @BeforeTest
    fun setup() {
        
        tempDir = java.nio.file.Files.createTempDirectory("test-").toFile()

        mockSource = mockk()
        mockStagingStore = mockk(relaxed = true)
        dedupStore = DeduplicationStore(storePath = tempDir.absolutePath + "/dedup")
        metadataStore = SourceMetadataStore(storePath = tempDir.absolutePath + "/metadata")

        every { mockSource.name } returns "test"
        every { mockSource.resyncStrategy() } returns ResyncStrategy.DailyAt(hour = 1, minute = 0)
        every { mockSource.needsChunking() } returns false

        runner = StandardizedRunner(
            source = mockSource,
            collectionName = "test_collection",
            stagingStore = mockStagingStore,
            dedupStore = dedupStore,
            metadataStore = metadataStore
        )
    }

    @Test
    fun `should process items without chunking`() = runBlocking {
        
        val testItem = TestChunkable("test-1", "Hello world")

        coEvery { mockSource.fetchForRun(any()) } returns flowOf(testItem)
        coEvery { mockStagingStore.stageBatch(any()) } just Runs

        
        val testScheduler = SourceScheduler(
            sourceName = "test",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )
        val testRunner = StandardizedRunner(
            source = mockSource,
            collectionName = "test_collection",
            stagingStore = mockStagingStore,
            dedupStore = dedupStore,
            metadataStore = metadataStore,
            scheduler = testScheduler
        )

        
        testRunner.run()

        
        coVerify(exactly = 1) {
            mockStagingStore.stageBatch(match { docs ->
                docs.size == 1 &&
                docs[0].id == "test-1" &&
                docs[0].text == "Hello world" &&
                docs[0].collection == "test_collection"
            })
        }
    }

    @Test
    fun `should skip duplicate items`() = runBlocking {
        
        val item1 = TestChunkable("test-1", "Hello")
        val item2 = TestChunkable("test-1", "Hello")

        coEvery { mockSource.fetchForRun(any()) } returns flowOf(item1, item2)
        coEvery { mockStagingStore.stageBatch(any()) } just Runs

        
        val testScheduler = SourceScheduler(
            sourceName = "test",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )
        val testRunner = StandardizedRunner(
            source = mockSource,
            collectionName = "test_collection",
            stagingStore = mockStagingStore,
            dedupStore = dedupStore,
            metadataStore = metadataStore,
            scheduler = testScheduler
        )

        
        testRunner.run()

        
        coVerify(exactly = 1) {
            mockStagingStore.stageBatch(match { docs -> docs.size == 1 })
        }
    }

    @Test
    fun `should process items with chunking`() = runBlocking {
        
        every { mockSource.needsChunking() } returns true
        every { mockSource.chunker() } returns mockk {
            coEvery { process(any()) } returns listOf(
                org.datamancy.pipeline.processors.TextChunk("chunk1", 0, 0, 100, 2),
                org.datamancy.pipeline.processors.TextChunk("chunk2", 1, 90, 200, 2)
            )
        }

        val testItem = TestChunkable("test-1", "Long text that needs chunking")
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(testItem)
        coEvery { mockStagingStore.stageBatch(any()) } just Runs

        
        val testScheduler = SourceScheduler(
            sourceName = "test",
            resyncStrategy = ResyncStrategy.DailyAt(1, 0),
            initialPullEnabled = true,
            runOnce = true
        )
        val testRunner = StandardizedRunner(
            source = mockSource,
            collectionName = "test_collection",
            stagingStore = mockStagingStore,
            dedupStore = dedupStore,
            metadataStore = metadataStore,
            scheduler = testScheduler
        )

        
        testRunner.run()

        
        coVerify(exactly = 1) {
            mockStagingStore.stageBatch(match { docs ->
                docs.size == 2 &&
                docs[0].chunkIndex == 0 &&
                docs[1].chunkIndex == 1
            })
        }
    }

    @Test
    fun `should record success metrics`() = runBlocking {
        
        val testItem = TestChunkable("test-1", "Hello")
        coEvery { mockSource.fetchForRun(any()) } returns flowOf(testItem)
        coEvery { mockStagingStore.stageBatch(any()) } just Runs

        
        val mockScheduler = mockk<SourceScheduler>()
        coEvery { mockScheduler.schedule(any()) } coAnswers {
            val onRun = firstArg<suspend (RunMetadata) -> Unit>()
            onRun(RunMetadata(RunType.INITIAL_PULL, 1, true))
        }

        
        val testRunner = StandardizedRunner(
            source = mockSource,
            collectionName = "test_collection",
            stagingStore = mockStagingStore,
            dedupStore = dedupStore,
            metadataStore = metadataStore,
            scheduler = mockScheduler
        )
        testRunner.run()

        
        val metadata = metadataStore.load("test")
        assertEquals(1, metadata.totalItemsProcessed)
        assertEquals(0, metadata.totalItemsFailed)
    }
}


data class TestChunkable(
    private val id: String,
    private val text: String
) : Chunkable {
    override fun toText(): String = text
    override fun getId(): String = id
    override fun getMetadata(): Map<String, String> = mapOf("id" to id)
}
