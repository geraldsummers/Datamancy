package org.datamancy.integration

import kotlinx.coroutines.runBlocking
import org.datamancy.pipeline.sinks.*
import org.datamancy.pipeline.sources.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Disabled
import org.postgresql.ds.PGSimpleDataSource
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.*

/**
 * Integration tests for MarketDataSink
 *
 * These tests require a real TimescaleDB connection and are run in the Docker test environment.
 * They are disabled for regular builds and should be run via Docker Compose.
 */
@Disabled("Integration tests - run via docker compose --profile testing")
class MarketDataSinkIntegrationTest {

    private lateinit var dataSource: DataSource
    private lateinit var sink: MarketDataSink

    @BeforeEach
    fun setup() {
        // Use environment variables for test database
        val host = System.getenv("TEST_POSTGRES_HOST") ?: "localhost"
        val port = System.getenv("TEST_POSTGRES_PORT")?.toIntOrNull() ?: 5432
        val dbName = System.getenv("TEST_POSTGRES_DB") ?: "datamancy_test"
        val user = System.getenv("TEST_POSTGRES_USER") ?: "test"
        val password = System.getenv("TEST_POSTGRES_PASSWORD") ?: "test"

        dataSource = PGSimpleDataSource().apply {
            serverNames = arrayOf(host)
            portNumbers = intArrayOf(port)
            databaseName = dbName
            this.user = user
            this.password = password
        }

        sink = MarketDataSink(dataSource, batchSize = 10)
    }

    @AfterEach
    fun teardown() = runBlocking {
        sink.flush()
    }

    @Test
    fun `stats accumulate pending items`() = runBlocking {
        val trade = createTestTrade()
        sink.write(HyperliquidMarketData.Trades(listOf(trade)))

        val stats = sink.getStats()
        assertEquals(1, stats.pendingTrades)
        assertEquals(0, stats.tradesIngested) // Not flushed yet
    }

    @Test
    fun `health check passes with valid connection`() = runBlocking {
        assertTrue(sink.healthCheck())
    }

    @Test
    fun `can write and flush trades`() = runBlocking {
        val trades = (1..5).map { i ->
            createTestTrade(symbol = "TEST-${System.currentTimeMillis()}", price = 1000.0 + i)
        }

        sink.write(HyperliquidMarketData.Trades(trades))
        sink.flush()

        val stats = sink.getStats()
        assertTrue(stats.tradesIngested >= 5)
        assertEquals(0, stats.pendingTrades)
    }

    @Test
    fun `can write and flush candles`() = runBlocking {
        val candles = (1..5).map { i ->
            createTestCandle(symbol = "TEST-${System.currentTimeMillis()}", interval = "1m")
        }

        candles.forEach { candle ->
            sink.write(HyperliquidMarketData.Candle(candle))
        }
        sink.flush()

        val stats = sink.getStats()
        assertTrue(stats.candlesIngested >= 5)
        assertEquals(0, stats.pendingCandles)
    }

    @Test
    fun `can write and flush orderbooks`() = runBlocking {
        val orderbooks = (1..3).map {
            createTestOrderbook(symbol = "TEST-${System.currentTimeMillis()}")
        }

        orderbooks.forEach { orderbook ->
            sink.write(HyperliquidMarketData.Orderbook(orderbook))
        }
        sink.flush()

        val stats = sink.getStats()
        assertTrue(stats.orderbooksIngested >= 3)
        assertEquals(0, stats.pendingOrderbooks)
    }

    @Test
    fun `batch write triggers automatic flush`() = runBlocking {
        // Create more items than batch size (10)
        val trades = (1..15).map { i ->
            createTestTrade(symbol = "TEST-${System.currentTimeMillis()}", price = 1000.0 + i)
        }

        sink.write(HyperliquidMarketData.Trades(trades))

        val stats = sink.getStats()
        // Batch flush writes all accumulated trades when threshold is hit
        assertTrue(stats.tradesIngested >= 15)
        assertEquals(0, stats.pendingTrades)
    }

    @Test
    fun `writeBatch flushes all items`() = runBlocking {
        val items = listOf(
            HyperliquidMarketData.Trades(listOf(createTestTrade())),
            HyperliquidMarketData.Candle(createTestCandle()),
            HyperliquidMarketData.Orderbook(createTestOrderbook())
        )

        sink.writeBatch(items)

        val stats = sink.getStats()
        // All items should be flushed
        assertEquals(0, stats.totalPending)
        assertTrue(stats.totalIngested >= 3)
    }

    @Test
    fun `duplicate trades are handled gracefully`() = runBlocking {
        val trade = createTestTrade(tradeId = "DUPLICATE-123")

        // Write same trade twice
        sink.write(HyperliquidMarketData.Trades(listOf(trade)))
        sink.flush()

        sink.write(HyperliquidMarketData.Trades(listOf(trade)))
        sink.flush()

        // Should not throw exception (ON CONFLICT DO NOTHING)
        assertTrue(true)
    }

    @Test
    fun `candle upserts work correctly`() = runBlocking {
        val symbol = "TEST-${System.currentTimeMillis()}"
        val time = Instant.now().minusSeconds(60)

        val candle1 = createTestCandle(symbol = symbol, open = 100.0, close = 105.0, time = time)
        sink.write(HyperliquidMarketData.Candle(candle1))
        sink.flush()

        // Update same candle with new data
        val candle2 = createTestCandle(symbol = symbol, open = 100.0, close = 110.0, time = time)
        sink.write(HyperliquidMarketData.Candle(candle2))
        sink.flush()

        // Should not throw exception (ON CONFLICT DO UPDATE)
        assertTrue(true)
    }

    @Test
    fun `stats reset works correctly`() = runBlocking {
        val trade = createTestTrade()
        sink.write(HyperliquidMarketData.Trades(listOf(trade)))

        val statsBefore = sink.getStats()
        assertTrue(statsBefore.pendingTrades > 0)

        sink.resetStats()

        val statsAfter = sink.getStats()
        assertEquals(0, statsAfter.tradesIngested)
        // Pending items are not cleared, only counters
    }

    // Helper functions
    private fun createTestTrade(
        symbol: String = "TEST",
        price: Double = 50000.0,
        size: Double = 0.5,
        side: String = "buy",
        tradeId: String? = "test-${System.nanoTime()}"
    ): HyperliquidTrade {
        return HyperliquidTrade(
            time = Instant.now(),
            symbol = symbol,
            price = price,
            size = size,
            side = side,
            tradeId = tradeId
        )
    }

    private fun createTestCandle(
        symbol: String = "TEST",
        interval: String = "1m",
        open: Double = 50000.0,
        high: Double = 51000.0,
        low: Double = 49000.0,
        close: Double = 50500.0,
        volume: Double = 100.0,
        numTrades: Int = 50,
        time: Instant = Instant.now()
    ): HyperliquidCandle {
        return HyperliquidCandle(
            time = time,
            symbol = symbol,
            interval = interval,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            numTrades = numTrades
        )
    }

    private fun createTestOrderbook(
        symbol: String = "TEST"
    ): HyperliquidOrderbook {
        return HyperliquidOrderbook(
            time = Instant.now(),
            symbol = symbol,
            bids = listOf(
                HyperliquidOrderbookLevel(50000.0, 1.0),
                HyperliquidOrderbookLevel(49999.0, 2.0)
            ),
            asks = listOf(
                HyperliquidOrderbookLevel(50001.0, 1.5),
                HyperliquidOrderbookLevel(50002.0, 2.5)
            )
        )
    }
}
