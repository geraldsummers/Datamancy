package org.datamancy.pipeline.sinks

import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * Unit tests for MarketDataSink
 *
 * Note: Tests requiring a real TimescaleDB connection have been moved to
 * MarketDataSinkIntegrationTest in the test-runner module.
 */
class MarketDataSinkTest {


    @Test
    fun `ingestion stats calculate totals correctly`() {
        val stats = IngestionStats(
            tradesIngested = 100,
            candlesIngested = 50,
            orderbooksIngested = 25,
            pendingTrades = 5,
            pendingCandles = 3,
            pendingOrderbooks = 2
        )

        assertEquals(175, stats.totalIngested)
        assertEquals(10, stats.totalPending)
    }
}
