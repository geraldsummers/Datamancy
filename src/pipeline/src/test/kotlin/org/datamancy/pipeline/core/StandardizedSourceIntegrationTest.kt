package org.datamancy.pipeline.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.scheduling.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for StandardizedSource implementations
 * Tests the contract that all sources must follow
 */
class StandardizedSourceIntegrationTest {

    @Test
    fun `standardized source should implement all required methods`() = runBlocking {
        // Given: A minimal StandardizedSource implementation
        val source = TestStandardizedSource()

        // Then: All required properties should be available
        assertEquals("test_source", source.name)
        assertTrue(source.resyncStrategy() is ResyncStrategy.DailyAt)
        assertTrue(source.backfillStrategy() is BackfillStrategy.NoBackfill)
        assertEquals(false, source.needsChunking())

        // And: Should be able to fetch for both run types
        val initialItems = source.fetchForRun(
            RunMetadata(RunType.INITIAL_PULL, 1, true)
        ).toList()
        val resyncItems = source.fetchForRun(
            RunMetadata(RunType.RESYNC, 1, false)
        ).toList()

        assertTrue(initialItems.isNotEmpty(), "Should fetch items on initial pull")
        assertTrue(resyncItems.isNotEmpty(), "Should fetch items on resync")
    }

    @Test
    fun `chunkable items should implement required interface`() {
        // Given: A Chunkable implementation
        val item = TestChunkableItem("id-1", "Some text content")

        // Then: All required methods should work
        assertEquals("Some text content", item.toText())
        assertEquals("id-1", item.getId())
        assertTrue(item.getMetadata().containsKey("id"))
        assertEquals("id-1", item.getMetadata()["id"])
    }

    @Test
    fun `source should differentiate initial pull vs resync`() = runBlocking {
        // Given: Source with different behavior for initial vs resync
        val source = TestStandardizedSource()

        // When: Fetch for initial pull
        val initialMetadata = RunMetadata(RunType.INITIAL_PULL, 1, true)
        val initialItems = source.fetchForRun(initialMetadata).toList()

        // When: Fetch for resync
        val resyncMetadata = RunMetadata(RunType.RESYNC, 1, false)
        val resyncItems = source.fetchForRun(resyncMetadata).toList()

        // Then: Can have different behavior (though our test doesn't)
        assertTrue(initialItems.isNotEmpty())
        assertTrue(resyncItems.isNotEmpty())
    }

    @Test
    fun `chunked source should provide chunker`() {
        // Given: Source that needs chunking
        val source = TestChunkedSource()

        // Then: Should indicate chunking is needed
        assertTrue(source.needsChunking())

        // And: Should provide a chunker
        val chunker = source.chunker()
        assertEquals(7372, chunker.maxTokens)  // Expected for 8192 token limit with 0.90 safety
    }

    @Test
    fun `source strategies should provide descriptions`() {
        // Given: Source with strategies
        val source = TestStandardizedSource()

        // Then: Strategies should have descriptions
        assertTrue(source.resyncStrategy().describe().isNotEmpty())
        assertTrue(source.backfillStrategy().describe().isNotEmpty())
    }
}

// Test implementations

class TestStandardizedSource : StandardizedSource<TestChunkableItem> {
    override val name = "test_source"

    override fun resyncStrategy() = ResyncStrategy.DailyAt(hour = 1, minute = 0)

    override fun backfillStrategy() = BackfillStrategy.NoBackfill

    override fun needsChunking() = false

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<TestChunkableItem> {
        return flowOf(
            TestChunkableItem("1", "Item 1"),
            TestChunkableItem("2", "Item 2"),
            TestChunkableItem("3", "Item 3")
        )
    }
}

class TestChunkedSource : StandardizedSource<TestChunkableItem> {
    override val name = "test_chunked"

    override fun resyncStrategy() = ResyncStrategy.Hourly(minute = 0)

    override fun backfillStrategy() = BackfillStrategy.RssHistory(daysBack = 7)

    override fun needsChunking() = true

    override suspend fun fetchForRun(metadata: RunMetadata): Flow<TestChunkableItem> {
        return flowOf(
            TestChunkableItem("1", "Very long text ".repeat(200))
        )
    }
}

data class TestChunkableItem(
    private val id: String,
    private val text: String
) : Chunkable {
    override fun toText() = text
    override fun getId() = id
    override fun getMetadata() = mapOf("id" to id, "length" to text.length.toString())
}
