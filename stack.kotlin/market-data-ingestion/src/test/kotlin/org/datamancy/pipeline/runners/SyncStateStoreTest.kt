package org.datamancy.pipeline.runners

import java.sql.BatchUpdateException
import java.sql.SQLException
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncStateStoreTest {
    @Test
    fun `raw sync normalization aggregates duplicates and sorts keys deterministically`() {
        val normalized = normalizeRawSyncObservations(
            listOf(
                observation(symbol = "SOL", channel = "orderbook_l2", time = "2026-03-25T03:04:00Z"),
                observation(symbol = "BTC", channel = "trade", time = "2026-03-25T03:02:00Z"),
                observation(symbol = "BTC", channel = "trade", time = "2026-03-25T03:03:00Z"),
                observation(symbol = "BTC", channel = "candle_5m", time = "2026-03-25T03:01:00Z")
            )
        )

        assertEquals(3, normalized.size)
        assertEquals("BTC", normalized[0].symbol)
        assertEquals("candle_5m", normalized[0].channel)
        assertEquals("BTC", normalized[1].symbol)
        assertEquals("trade", normalized[1].channel)
        assertEquals(Instant.parse("2026-03-25T03:02:00Z"), normalized[1].earliestTime)
        assertEquals(Instant.parse("2026-03-25T03:03:00Z"), normalized[1].latestTime)
        assertEquals(2L, normalized[1].rowCount)
        assertEquals("SOL", normalized[2].symbol)
    }

    @Test
    fun `deadlock detection unwraps batch update next exception`() {
        val batch = BatchUpdateException("batch failed", "08000", 0, IntArray(0))
        batch.setNextException(SQLException("deadlock detected", POSTGRES_DEADLOCK_SQL_STATE))

        assertTrue(isRetryableRawSyncFailure(batch))
    }

    private fun observation(symbol: String, channel: String, time: String) = RawSyncObservation(
        symbol = symbol,
        channel = channel,
        earliestTime = Instant.parse(time),
        latestTime = Instant.parse(time),
        rowCount = 1L
    )
}
