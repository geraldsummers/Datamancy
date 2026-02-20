package org.datamancy.trading.risk

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal

class RiskManagementDslTest {

    @Test
    fun `fixed position sizing works`() {
        val risk = riskManagement {
            sizing {
                fixed(5.0.toBigDecimal())
            }
        }

        val size = risk.calculatePositionSize(
            equity = 100_000.toBigDecimal(),
            entryPrice = 50_000.toBigDecimal()
        )

        assertEquals(BigDecimal.valueOf(5.0), size)
    }

    @Test
    fun `fixed percent position sizing works`() {
        val risk = riskManagement {
            sizing {
                fixedPercent(10.0) // 10% of equity
            }
        }

        val size = risk.calculatePositionSize(
            equity = 100_000.toBigDecimal(),
            entryPrice = 50_000.toBigDecimal()
        )

        // 10% of 100k = 10k
        // 10k / 50k = 0.2 BTC
        assertEquals(BigDecimal.valueOf(0.2).setScale(8), size)
    }

    @Test
    fun `ATR-based position sizing works`() {
        val risk = riskManagement {
            sizing {
                atrBased(atrMultiplier = 2.0, riskPercent = 1.0)
            }
        }

        val size = risk.calculatePositionSize(
            equity = 100_000.toBigDecimal(),
            entryPrice = 50_000.toBigDecimal(),
            atr = 1_000.toBigDecimal()
        )

        // Risk amount = 1% of 100k = 1k
        // Stop distance = 2 * 1000 = 2000
        // Size = 1000 / 2000 = 0.5
        assertEquals(BigDecimal.valueOf(0.5).setScale(8), size)
    }

    @Test
    fun `max position limit is enforced`() {
        val risk = riskManagement {
            sizing {
                fixedPercent(20.0) // 20% of equity
                maxPositionPercent(10.0) // But limit to 10%
            }
        }

        val size = risk.calculatePositionSize(
            equity = 100_000.toBigDecimal(),
            entryPrice = 50_000.toBigDecimal()
        )

        // Would be 20% = 20k / 50k = 0.4
        // But limited to 10% = 10k / 50k = 0.2
        assertEquals(BigDecimal.valueOf(0.2).setScale(8), size)
    }

    @Test
    fun `min position size is enforced`() {
        val risk = riskManagement {
            sizing {
                fixedPercent(0.0001) // Tiny percent
                minPositionSize(0.01)
            }
        }

        val size = risk.calculatePositionSize(
            equity = 100_000.toBigDecimal(),
            entryPrice = 50_000.toBigDecimal()
        )

        // Would be < 0.01, but min is 0.01
        assertEquals(BigDecimal.valueOf(0.01).setScale(8), size)
    }

    @Test
    fun `stop loss percent calculation works`() {
        val risk = riskManagement {
            exits {
                stopLoss {
                    percent(2.0) // 2% stop
                }
            }
        }

        val stopPrice = risk.calculateStopLoss(
            entryPrice = 50_000.toBigDecimal(),
            side = "long"
        )

        // 2% below entry = 50000 * 0.98 = 49000
        assertEquals(BigDecimal.valueOf(49_000.0), stopPrice)
    }

    @Test
    fun `stop loss ATR calculation works`() {
        val risk = riskManagement {
            exits {
                stopLoss {
                    atrBased(multiplier = 2.0)
                }
            }
        }

        val stopPrice = risk.calculateStopLoss(
            entryPrice = 50_000.toBigDecimal(),
            side = "long",
            atr = 1_000.toBigDecimal()
        )

        // 2 * 1000 = 2000 below entry = 48000
        assertEquals(BigDecimal.valueOf(48_000.0), stopPrice)
    }

    @Test
    fun `short stop loss is above entry`() {
        val risk = riskManagement {
            exits {
                stopLoss {
                    percent(2.0)
                }
            }
        }

        val stopPrice = risk.calculateStopLoss(
            entryPrice = 50_000.toBigDecimal(),
            side = "short"
        )

        // 2% above entry for short = 51000
        assertEquals(BigDecimal.valueOf(51_000.0), stopPrice)
    }

    @Test
    fun `take profit with risk-reward ratio`() {
        val risk = riskManagement {
            exits {
                stopLoss {
                    fixed(1_000.toBigDecimal())
                }
                takeProfit {
                    riskReward(ratio = 3.0)
                }
            }
        }

        val stopPrice = risk.calculateStopLoss(
            entryPrice = 50_000.toBigDecimal(),
            side = "long"
        )!!

        val takeProfits = risk.calculateTakeProfit(
            entryPrice = 50_000.toBigDecimal(),
            stopPrice = stopPrice,
            side = "long"
        )

        assertEquals(1, takeProfits.size)
        val (tpPrice, tpPercent) = takeProfits.first()

        // Risk = 50000 - 49000 = 1000
        // Reward = 3 * 1000 = 3000
        // TP = 50000 + 3000 = 53000
        assertEquals(BigDecimal.valueOf(53_000.0), tpPrice)
        assertEquals(BigDecimal.valueOf(100), tpPercent)
    }

    @Test
    fun `take profit with multiple targets`() {
        val risk = riskManagement {
            exits {
                stopLoss {
                    fixed(1_000.toBigDecimal())
                }
                takeProfit {
                    targets(
                        2.0 to 50.0, // 2R at 50% position
                        4.0 to 50.0  // 4R at remaining 50%
                    )
                }
            }
        }

        val stopPrice = risk.calculateStopLoss(
            entryPrice = 50_000.toBigDecimal(),
            side = "long"
        )!!

        val takeProfits = risk.calculateTakeProfit(
            entryPrice = 50_000.toBigDecimal(),
            stopPrice = stopPrice,
            side = "long"
        )

        assertEquals(2, takeProfits.size)

        val (tp1Price, tp1Percent) = takeProfits[0]
        val (tp2Price, tp2Percent) = takeProfits[1]

        // Risk = 1000
        // TP1 = 50000 + 2000 = 52000 at 50%
        // TP2 = 50000 + 4000 = 54000 at 50%
        assertEquals(BigDecimal.valueOf(52_000.0), tp1Price)
        assertEquals(BigDecimal.valueOf(50.0), tp1Percent)
        assertEquals(BigDecimal.valueOf(54_000.0), tp2Price)
        assertEquals(BigDecimal.valueOf(50.0), tp2Percent)
    }

    @Test
    fun `portfolio validation rejects max positions`() {
        val risk = riskManagement {
            portfolio {
                maxOpenPositions(5)
            }
        }

        val result = risk.validatePosition(
            currentPositions = 5,
            newPositionValue = 10_000.toBigDecimal(),
            totalEquity = 100_000.toBigDecimal()
        )

        assertTrue(result is RiskCheckResult.Rejected)
        assertEquals("Max positions limit reached", (result as RiskCheckResult.Rejected).reason)
    }

    @Test
    fun `portfolio validation rejects oversized position`() {
        val risk = riskManagement {
            sizing {
                maxPositionPercent(10.0)
            }
        }

        val result = risk.validatePosition(
            currentPositions = 2,
            newPositionValue = 15_000.toBigDecimal(),
            totalEquity = 100_000.toBigDecimal()
        )

        // 15k / 100k = 15% > 10% limit
        assertTrue(result is RiskCheckResult.Rejected)
    }

    @Test
    fun `portfolio validation approves valid position`() {
        val risk = riskManagement {
            sizing {
                maxPositionPercent(10.0)
            }
            portfolio {
                maxOpenPositions(5)
            }
        }

        val result = risk.validatePosition(
            currentPositions = 3,
            newPositionValue = 8_000.toBigDecimal(),
            totalEquity = 100_000.toBigDecimal()
        )

        assertTrue(result is RiskCheckResult.Approved)
    }

    @Test
    fun `percent extension works`() {
        assertEquals(BigDecimal.valueOf(5.0), 5.percent)
        assertEquals(BigDecimal.valueOf(10.5), 10.5.percent)
    }
}
