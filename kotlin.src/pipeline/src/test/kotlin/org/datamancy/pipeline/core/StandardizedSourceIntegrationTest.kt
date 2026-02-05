package org.datamancy.pipeline.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.scheduling.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class StandardizedSourceIntegrationTest {

    @Test
    fun `standardized source should implement all required methods`() = runBlocking {
        
        val source = TestStandardizedSource()

        
        assertEquals("test_source", source.name)
        assertTrue(source.resyncStrategy() is ResyncStrategy.DailyAt)
        assertTrue(source.backfillStrategy() is BackfillStrategy.NoBackfill)
        assertEquals(false, source.needsChunking())

        
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
        
        val item = TestChunkableItem("id-1", "Some text content")

        
        assertEquals("Some text content", item.toText())
        assertEquals("id-1", item.getId())
        assertTrue(item.getMetadata().containsKey("id"))
        assertEquals("id-1", item.getMetadata()["id"])
    }

    @Test
    fun `source should differentiate initial pull vs resync`() = runBlocking {
        
        val source = TestStandardizedSource()

        
        val initialMetadata = RunMetadata(RunType.INITIAL_PULL, 1, true)
        val initialItems = source.fetchForRun(initialMetadata).toList()

        
        val resyncMetadata = RunMetadata(RunType.RESYNC, 1, false)
        val resyncItems = source.fetchForRun(resyncMetadata).toList()

        
        assertTrue(initialItems.isNotEmpty())
        assertTrue(resyncItems.isNotEmpty())
    }

    @Test
    fun `chunked source should provide chunker`() {
        
        val source = TestChunkedSource()

        
        assertTrue(source.needsChunking())

        
        val chunker = source.chunker()
        assertEquals(7372, chunker.maxTokens)  
    }

    @Test
    fun `source strategies should provide descriptions`() {
        
        val source = TestStandardizedSource()

        
        assertTrue(source.resyncStrategy().describe().isNotEmpty())
        assertTrue(source.backfillStrategy().describe().isNotEmpty())
    }
}



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
