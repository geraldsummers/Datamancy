package org.datamancy.trading.strategy

import org.datamancy.trading.data.Candle
import org.datamancy.trading.indicators.indicators
import org.datamancy.trading.models.Side
import org.datamancy.trading.risk.riskManagement
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.Instant

class StrategyDslTest {

    private fun assertBigDecimalEquals(expected: BigDecimal, actual: BigDecimal?, message: String? = null) {
        assertNotNull(actual)
        assertEquals(0, expected.compareTo(actual!!), message ?: "Expected $expected but got $actual")
    }

    @Test
    fun `strategy builder creates valid strategy`() {
        val strat = strategy("TestStrategy") {
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
            }

            onCandle {
                // Do nothing
            }
        }

        assertEquals("TestStrategy", strat.name)
        assertNotNull(strat.marketConfig)
        assertEquals(2, strat.marketConfig.symbols.size)
        assertTrue(strat.marketConfig.symbols.contains("BTC-PERP" to "hyperliquid"))
        assertTrue(strat.marketConfig.symbols.contains("ETH-PERP" to "hyperliquid"))
    }

    @Test
    fun `parameters work correctly`() {
        val strat = strategy("ParamTest") {
            markets {
                hyperliquid("BTC-PERP")
            }

            parameters {
                set("fastPeriod", 20)
                set("slowPeriod", 50)
                set("rsiPeriod", 14)
            }
        }

        assertEquals(20, strat.parameters.get("fastPeriod"))
        assertEquals(50, strat.parameters.get("slowPeriod"))
        assertEquals(14, strat.parameters.get("rsiPeriod"))
    }

    @Test
    fun `strategy with risk management`() {
        val strat = strategy("RiskTest") {
            markets {
                hyperliquid("BTC-PERP")
            }

            risk {
                sizing {
                    fixedPercent(2.0)
                    maxPositionPercent(10.0)
                }

                exits {
                    stopLoss {
                        atrBased(multiplier = 2.0)
                    }
                    takeProfit {
                        riskReward(ratio = 3.0)
                    }
                }
            }
        }

        assertNotNull(strat.riskManagement)
        val risk = strat.riskManagement!!

        val size = risk.calculatePositionSize(
            equity = BigDecimal.valueOf(100_000),
            entryPrice = BigDecimal.valueOf(50_000)
        )

        // 2% of 100k = 2k, divided by 50k = 0.04
        assertEquals(BigDecimal.valueOf(0.04).setScale(8), size)
    }

    @Test
    fun `entry config works`() {
        val entry = EntryConfig().apply {
            side = Side.BUY
            size = BigDecimal.valueOf(1.5)
            stopLoss = BigDecimal.valueOf(49_000)
            takeProfit = BigDecimal.valueOf(53_000)
            metadata["reason"] = "golden_cross"
        }

        assertEquals(Side.BUY, entry.side)
        assertBigDecimalEquals(BigDecimal.valueOf(1.5), entry.size)
        assertBigDecimalEquals(BigDecimal.valueOf(49_000), entry.stopLoss)
        assertEquals("golden_cross", entry.metadata["reason"])
    }

    @Test
    fun `exit config works`() {
        val exit = ExitConfig().apply {
            size = BigDecimal.valueOf(0.5) // Close half position
            reason = "take_profit"
        }

        assertBigDecimalEquals(BigDecimal.valueOf(0.5), exit.size)
        assertEquals("take_profit", exit.reason)
    }

    @Test
    fun `position model calculates PnL correctly for long`() {
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
        assertBigDecimalEquals(BigDecimal.valueOf(5_000), updated.unrealizedPnl)

        // PnL% = 5000 / 50000 = 10%
        assertBigDecimalEquals(BigDecimal.valueOf(10.0), updated.unrealizedPnlPercent.setScale(1))
    }

    @Test
    fun `position model calculates PnL correctly for short`() {
        val position = StrategyPosition(
            id = "test-2",
            symbol = "BTC-PERP",
            side = Side.SELL,
            entryTime = Instant.now(),
            entryPrice = BigDecimal.valueOf(50_000),
            size = BigDecimal.valueOf(1.0)
        )

        val updated = position.withPrice(BigDecimal.valueOf(48_000))

        // PnL = (50000 - 48000) * 1.0 = 2000
        assertBigDecimalEquals(BigDecimal.valueOf(2_000), updated.unrealizedPnl)

        // PnL% = 2000 / 50000 = 4%
        assertBigDecimalEquals(BigDecimal.valueOf(4.0), updated.unrealizedPnlPercent.setScale(1))
    }

    @Test
    fun `signal event creation`() {
        val signal = SignalEvent(
            time = Instant.now(),
            symbol = "BTC-PERP",
            signal = Signal.LONG,
            strength = BigDecimal.valueOf(0.8),
            reason = "Golden cross + RSI oversold",
            metadata = mapOf(
                "sma_fast" to 51000,
                "sma_slow" to 50000,
                "rsi" to 28
            )
        )

        assertEquals(Signal.LONG, signal.signal)
        assertBigDecimalEquals(BigDecimal.valueOf(0.8), signal.strength)
        assertEquals("Golden cross + RSI oversold", signal.reason)
        assertEquals(28, signal.metadata["rsi"])
    }

    @Test
    fun `strategy performance calculation`() {
        val perf = StrategyPerformance(
            strategyName = "TrendFollower",
            startTime = Instant.now().minusSeconds(86400),
            endTime = Instant.now(),
            totalTrades = 10,
            winningTrades = 6,
            losingTrades = 4,
            totalPnl = BigDecimal.valueOf(5000),
            totalPnlPercent = BigDecimal.valueOf(5.0),
            winRate = BigDecimal.valueOf(0.6),
            profitFactor = BigDecimal.valueOf(1.5),
            avgWin = BigDecimal.valueOf(1000),
            avgLoss = BigDecimal.valueOf(600),
            maxDrawdown = BigDecimal.valueOf(10.0),
            sharpeRatio = BigDecimal.valueOf(1.8),
            currentEquity = BigDecimal.valueOf(105_000),
            peakEquity = BigDecimal.valueOf(106_000)
        )

        // Expectancy = (winRate * avgWin) - ((1-winRate) * avgLoss)
        // = (0.6 * 1000) - (0.4 * 600) = 600 - 240 = 360
        assertBigDecimalEquals(BigDecimal.valueOf(360), perf.expectancy)
    }

    @Test
    fun `multiple markets configuration`() {
        val strat = strategy("MultiMarket") {
            markets {
                hyperliquid("BTC-PERP", "ETH-PERP", "SOL-PERP")
                binance("BTCUSDT")
                custom("dydx", "BTC-USD")
            }
        }

        assertEquals(5, strat.marketConfig.symbols.size)
        assertTrue(strat.marketConfig.symbols.contains("BTC-PERP" to "hyperliquid"))
        assertTrue(strat.marketConfig.symbols.contains("BTCUSDT" to "binance"))
        assertTrue(strat.marketConfig.symbols.contains("BTC-USD" to "dydx"))
    }
}
