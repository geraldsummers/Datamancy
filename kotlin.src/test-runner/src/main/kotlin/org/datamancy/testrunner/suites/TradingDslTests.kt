package org.datamancy.testrunner.suites

import org.datamancy.testrunner.framework.*
import org.datamancy.trading.data.*
import org.datamancy.trading.indicators.*
import org.datamancy.trading.risk.*
import org.datamancy.trading.strategy.*
import org.datamancy.trading.models.Side
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * E2E Integration Tests for Trading DSLs
 *
 * Tests the full stack:
 * - TimescaleDB storage
 * - Indicator calculations
 * - Risk management
 * - Strategy execution
 * - WebSocket data streaming (if available)
 */
suspend fun TestRunner.tradingDslTests() = suite("Trading DSL E2E Tests") {

    fun assertBigDecimalEquals(expected: BigDecimal, actual: BigDecimal?, message: String) {
        if (actual == null) throw AssertionError("$message: actual is null")
        if (expected.compareTo(actual) != 0) {
            throw AssertionError("$message: expected $expected, got $actual")
        }
    }

    val postgresHost = System.getenv("POSTGRES_HOST") ?: "postgres"
    val postgresPort = System.getenv("POSTGRES_PORT") ?: "5432"
    val postgresDb = System.getenv("POSTGRES_DB") ?: "datamancy"
    val postgresUser = System.getenv("POSTGRES_USER") ?: "test_runner"
    val postgresPassword = System.getenv("POSTGRES_PASSWORD") ?: ""

    val jdbcUrl = "jdbc:postgresql://$postgresHost:$postgresPort/$postgresDb"

    // ========================================================================
    // Indicator DSL Tests
    // ========================================================================

    test("Indicator DSL - SMA calculates correctly") {
        val indicators = indicators {
            sma(3)
        }

        val sma = indicators.sma(3)

        repeat(3) { i ->
            val candle = createTestCandle(100.0 + i * 10)
            indicators.update(candle)
        }

        if (!sma.isReady) {
            throw AssertionError("SMA not ready after 3 candles")
        }

        // (100 + 110 + 120) / 3 = 110
        val expected = BigDecimal.valueOf(110.0)
        if (sma.value != expected) {
            throw AssertionError("SMA value incorrect: expected $expected, got ${sma.value}")
        }

        println("      ✓ SMA calculated correctly: ${sma.value}")
    }

    test("Indicator DSL - RSI calculates correctly") {
        val indicators = indicators {
            rsi(14)
        }

        val rsi = indicators.rsi(14)

        // Generate uptrend
        repeat(20) { i ->
            val candle = createTestCandle(100.0 + i * 2)
            indicators.update(candle)
        }

        if (!rsi.isReady) {
            throw AssertionError("RSI not ready after 20 candles")
        }

        if (rsi.value!! < BigDecimal.ZERO || rsi.value!! > BigDecimal.valueOf(100)) {
            throw AssertionError("RSI out of bounds: ${rsi.value}")
        }

        // Uptrend should have high RSI
        if (rsi.value!! < BigDecimal.valueOf(50)) {
            throw AssertionError("RSI should be > 50 in uptrend: ${rsi.value}")
        }

        println("      ✓ RSI calculated correctly: ${rsi.value}")
    }

    test("Indicator DSL - Multiple indicators work together") {
        val indicators = indicators {
            sma(20)
            sma(50)
            rsi(14)
            atr(14)
        }

        repeat(100) { i ->
            indicators.update(createTestCandle(100.0 + Math.sin(i * 0.1) * 10))
        }

        println("      ✓ Multiple indicators updated successfully")
    }

    // ========================================================================
    // Risk Management DSL Tests
    // ========================================================================

    test("Risk Management - Position sizing works") {
        val risk = riskManagement {
            sizing {
                fixedPercent(2.0)
                maxPositionPercent(10.0)
            }
        }

        val size = risk.calculatePositionSize(
            equity = BigDecimal.valueOf(100_000),
            entryPrice = BigDecimal.valueOf(50_000)
        )

        // 2% of 100k = 2k, 2k / 50k = 0.04
        val expected = BigDecimal.valueOf(0.04).setScale(8)
        assertBigDecimalEquals(expected, size, "Position size incorrect")

        println("      ✓ Position size: $size BTC")
    }

    test("Risk Management - ATR-based sizing works") {
        val risk = riskManagement {
            sizing {
                atrBased(atrMultiplier = 2.0, riskPercent = 1.0)
            }
        }

        val size = risk.calculatePositionSize(
            equity = BigDecimal.valueOf(100_000),
            entryPrice = BigDecimal.valueOf(50_000),
            atr = BigDecimal.valueOf(1_000)
        )

        // Risk = 1% of 100k = 1k
        // Stop distance = 2 * 1000 = 2000
        // Size = 1000 / 2000 = 0.5
        val expected = BigDecimal.valueOf(0.5).setScale(8)
        assertBigDecimalEquals(expected, size, "ATR-based size incorrect")

        println("      ✓ ATR-based position size: $size BTC")
    }

    test("Risk Management - Stop loss calculation works") {
        val risk = riskManagement {
            exits {
                stopLoss {
                    atrBased(multiplier = 2.0)
                }
            }
        }

        val stopPrice = risk.calculateStopLoss(
            entryPrice = BigDecimal.valueOf(50_000),
            side = "long",
            atr = BigDecimal.valueOf(1_000)
        )

        // 50000 - (2 * 1000) = 48000
        val expected = BigDecimal.valueOf(48_000)
        assertBigDecimalEquals(expected, stopPrice, "Stop loss incorrect")

        println("      ✓ Stop loss: $stopPrice")
    }

    test("Risk Management - Take profit with R:R ratio") {
        val risk = riskManagement {
            exits {
                stopLoss {
                    fixed(BigDecimal.valueOf(1_000))
                }
                takeProfit {
                    riskReward(ratio = 3.0)
                }
            }
        }

        val stopPrice = risk.calculateStopLoss(
            entryPrice = BigDecimal.valueOf(50_000),
            side = "long"
        )!!

        val takeProfits = risk.calculateTakeProfit(
            entryPrice = BigDecimal.valueOf(50_000),
            stopPrice = stopPrice,
            side = "long"
        )

        if (takeProfits.isEmpty()) {
            throw AssertionError("No take profit targets generated")
        }

        val (tpPrice, _) = takeProfits.first()
        // Risk = 1000, Reward = 3000, TP = 53000
        val expected = BigDecimal.valueOf(53_000)
        assertBigDecimalEquals(expected, tpPrice, "Take profit incorrect")

        println("      ✓ Take profit (3:1 R:R): $tpPrice")
    }

    test("Risk Management - Portfolio validation") {
        val risk = riskManagement {
            sizing {
                maxPositionPercent(10.0)
            }
            portfolio {
                maxOpenPositions(5)
            }
        }

        // Valid position
        val result1 = risk.validatePosition(
            currentPositions = 3,
            newPositionValue = BigDecimal.valueOf(8_000),
            totalEquity = BigDecimal.valueOf(100_000)
        )

        if (result1 !is RiskCheckResult.Approved) {
            throw AssertionError("Valid position should be approved")
        }

        // Too many positions
        val result2 = risk.validatePosition(
            currentPositions = 5,
            newPositionValue = BigDecimal.valueOf(5_000),
            totalEquity = BigDecimal.valueOf(100_000)
        )

        if (result2 !is RiskCheckResult.Rejected) {
            throw AssertionError("Max positions limit should reject")
        }

        println("      ✓ Portfolio validation working correctly")
    }

    // ========================================================================
    // Strategy DSL Tests
    // ========================================================================

    test("Strategy DSL - Strategy builder works") {
        val strategy = strategy("TestStrategy") {
            markets {
                hyperliquid("BTC-PERP", "ETH-PERP")
            }

            parameters {
                set("fastPeriod", 20)
                set("slowPeriod", 50)
            }

            indicators {
                sma(20)
                sma(50)
                rsi(14)
            }

            risk {
                sizing {
                    fixedPercent(2.0)
                }
                exits {
                    stopLoss {
                        atrBased(2.0)
                    }
                }
            }

            onCandle {
                // Strategy logic would go here
            }
        }

        if (strategy.name != "TestStrategy") {
            throw AssertionError("Strategy name incorrect")
        }

        if (strategy.marketConfig.symbols.size != 2) {
            throw AssertionError("Expected 2 markets, got ${strategy.marketConfig.symbols.size}")
        }

        if (strategy.riskManagement == null) {
            throw AssertionError("Risk management not configured")
        }

        println("      ✓ Strategy built successfully: ${strategy.name}")
        println("      ✓ Markets: ${strategy.marketConfig.symbols.map { it.first }.joinToString()}")
    }

    test("Strategy DSL - Position PnL calculation") {
        val position = StrategyPosition(
            id = "test-1",
            symbol = "BTC-PERP",
            side = Side.BUY,
            entryTime = Instant.now(),
            entryPrice = BigDecimal.valueOf(50_000),
            size = BigDecimal.valueOf(1.0)
        )

        val updated = position.withPrice(BigDecimal.valueOf(55_000))

        // PnL = (55000 - 50000) * 1.0 = 5000
        assertBigDecimalEquals(BigDecimal.valueOf(5_000), updated.unrealizedPnl, "PnL incorrect")

        // PnL% = 10%
        assertBigDecimalEquals(BigDecimal.valueOf(10), updated.unrealizedPnlPercent.setScale(0), "PnL% incorrect")

        println("      ✓ Position PnL: \$${updated.unrealizedPnl} (${updated.unrealizedPnlPercent}%)")
    }

    // ========================================================================
    // TimescaleDB Integration Tests
    // ========================================================================

    test("TimescaleDB - Connect and verify schema") {
        val dataSource = createDataSource(jdbcUrl, postgresUser, postgresPassword)

        dataSource.connection.use { conn ->
            // Check if trades table exists
            val stmt = conn.createStatement()
            val result = stmt.executeQuery("""
                SELECT EXISTS (
                    SELECT FROM information_schema.tables
                    WHERE table_name = 'market_data'
                )
            """.trimIndent())

            result.next()
            val marketDataExists = result.getBoolean(1)

            val result2 = stmt.executeQuery("""
                SELECT EXISTS (
                    SELECT FROM information_schema.tables
                    WHERE table_name = 'orderbook_data'
                )
            """.trimIndent())

            result2.next()
            val orderbookExists = result2.getBoolean(1)

            if (!marketDataExists) {
                throw AssertionError("market_data table does not exist")
            }
            if (!orderbookExists) {
                throw AssertionError("orderbook_data table does not exist")
            }
        }

        println("      ✓ Unified market data tables verified (market_data + orderbook_data)")
    }

    test("TimescaleDB - Insert and query candles") {
        val dataSource = createDataSource(jdbcUrl, postgresUser, postgresPassword)
        val repo = MarketDataRepository(dataSource)

        val testCandle = createTestCandle(50_000.0, symbol = "TEST-${System.currentTimeMillis()}")

        // Insert
        repo.insertCandle(testCandle)

        // Query back
        val retrieved = repo.getLatestCandle(
            symbol = testCandle.symbol,
            interval = testCandle.interval,
            exchange = testCandle.exchange
        )

        if (retrieved == null) {
            throw AssertionError("Failed to retrieve inserted candle")
        }

        if (retrieved.close != testCandle.close) {
            throw AssertionError("Retrieved candle data mismatch")
        }

        println("      ✓ Candle inserted and queried successfully")
        println("      ✓ Symbol: ${retrieved.symbol}, Close: ${retrieved.close}")
    }

    test("TimescaleDB - Batch insert trades") {
        val dataSource = createDataSource(jdbcUrl, postgresUser, postgresPassword)
        val repo = MarketDataRepository(dataSource)

        val testSymbol = "TEST-${System.currentTimeMillis()}"
        val trades = (1..100).map { i ->
            Trade(
                time = Instant.now().minusSeconds(100L - i),
                symbol = testSymbol,
                exchange = "test",
                tradeId = "trade-$i",
                price = BigDecimal.valueOf(50_000.0 + i),
                size = BigDecimal.valueOf(0.1),
                side = if (i % 2 == 0) Side.BUY else Side.SELL,
                isLiquidation = false
            )
        }

        // Batch insert
        repo.insertTrades(trades)

        // Query stats
        val stats = repo.getVolumeStats(
            symbol = testSymbol,
            from = Instant.now().minusSeconds(200),
            exchange = "test"
        )

        if (stats == null) {
            throw AssertionError("Failed to get volume stats")
        }

        if (stats.numTrades != 100) {
            throw AssertionError("Expected 100 trades, got ${stats.numTrades}")
        }

        println("      ✓ Batch inserted 100 trades")
        println("      ✓ Total volume: ${stats.totalVolume}")
        println("      ✓ Buy/Sell split: ${stats.buyPercent.setScale(1)}% / ${stats.sellPercent.setScale(1)}%")
    }

    // ========================================================================
    // Live Market Data Test (if Hyperliquid is accessible)
    // ========================================================================

    test("WebSocket - Connect to Hyperliquid (optional)") {
        try {
            val ws = HyperliquidWebSocket()

            withTimeout(10.seconds) {
                ws.connect()
                println("      ✓ Connected to Hyperliquid WebSocket")
                ws.close()
            }
        } catch (e: Exception) {
            // This is OK - WebSocket might not be accessible in test environment
            println("      ℹ️  Hyperliquid WebSocket not accessible (expected in isolated env)")
            println("      ℹ️  Error: ${e.message}")
        }
    }
}

// ============================================================================
// Helper Functions
// ============================================================================

private fun createTestCandle(
    close: Double,
    symbol: String = "TEST-PERP",
    high: Double = close * 1.01,
    low: Double = close * 0.99,
    volume: Double = 1000.0
): Candle {
    return Candle(
        time = Instant.now(),
        symbol = symbol,
        exchange = "test",
        interval = "1m",
        open = BigDecimal.valueOf(close),
        high = BigDecimal.valueOf(high),
        low = BigDecimal.valueOf(low),
        close = BigDecimal.valueOf(close),
        volume = BigDecimal.valueOf(volume),
        numTrades = 100
    )
}

private fun createDataSource(url: String, user: String, password: String): javax.sql.DataSource {
    val ds = org.postgresql.ds.PGSimpleDataSource()
    ds.setURL(url)
    ds.user = user
    ds.password = password
    return ds
}
