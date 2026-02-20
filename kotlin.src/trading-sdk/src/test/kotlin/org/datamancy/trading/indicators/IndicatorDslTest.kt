package org.datamancy.trading.indicators

import org.datamancy.trading.data.Candle
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.math.BigDecimal
import java.time.Instant

class IndicatorDslTest {

    private fun createCandle(close: Double, high: Double = close, low: Double = close, volume: Double = 1000.0): Candle {
        return Candle(
            time = Instant.now(),
            symbol = "BTC-PERP",
            exchange = "test",
            interval = "1m",
            open = BigDecimal.valueOf(close),
            high = BigDecimal.valueOf(high),
            low = BigDecimal.valueOf(low),
            close = BigDecimal.valueOf(close),
            volume = BigDecimal.valueOf(volume)
        )
    }

    @Test
    fun `SMA calculates correctly`() {
        val sma = SMA(3)

        assertFalse(sma.isReady)
        assertNull(sma.value)

        sma.update(createCandle(100.0))
        sma.update(createCandle(110.0))
        assertFalse(sma.isReady)

        sma.update(createCandle(120.0))
        assertTrue(sma.isReady)

        // (100 + 110 + 120) / 3 = 110
        assertEquals(BigDecimal.valueOf(110.0), sma.value)

        sma.update(createCandle(130.0))
        // (110 + 120 + 130) / 3 = 120
        assertEquals(BigDecimal.valueOf(120.0), sma.value)
    }

    @Test
    fun `EMA calculates correctly`() {
        val ema = EMA(3)

        assertFalse(ema.isReady)

        ema.update(createCandle(100.0))
        assertTrue(ema.isReady)
        assertEquals(BigDecimal.valueOf(100.0), ema.value)

        ema.update(createCandle(110.0))
        // EMA multiplier = 2/(3+1) = 0.5
        // EMA = 110 * 0.5 + 100 * 0.5 = 105
        assertEquals(BigDecimal.valueOf(105.0), ema.value)
    }

    @Test
    fun `RSI calculates correctly`() {
        val rsi = RSI(3)

        val prices = listOf(100.0, 102.0, 101.0, 103.0)

        prices.forEach { price ->
            rsi.update(createCandle(price))
        }

        assertTrue(rsi.isReady)
        assertNotNull(rsi.value)
        assertTrue(rsi.value!! > BigDecimal.ZERO)
        assertTrue(rsi.value!! < BigDecimal.valueOf(100))
    }

    @Test
    fun `ATR calculates correctly`() {
        val atr = ATR(3)

        atr.update(createCandle(close = 100.0, high = 105.0, low = 95.0))
        atr.update(createCandle(close = 102.0, high = 108.0, low = 98.0))
        assertFalse(atr.isReady)

        atr.update(createCandle(close = 101.0, high = 106.0, low = 96.0))
        assertTrue(atr.isReady)

        // First TR = 105 - 95 = 10
        // Second TR = max(108-98, |108-100|, |98-100|) = 10
        // Third TR = max(106-96, |106-102|, |96-102|) = 10
        // ATR = (10 + 10 + 10) / 3 = 10
        assertEquals(BigDecimal.valueOf(10.0), atr.value)
    }

    @Test
    fun `Bollinger Bands calculate correctly`() {
        val bb = BollingerBandsIndicator(period = 3, stdDevMultiplier = 2.0)

        bb.update(createCandle(100.0))
        bb.update(createCandle(100.0))
        assertFalse(bb.isReady)

        bb.update(createCandle(100.0))
        assertTrue(bb.isReady)

        val bands = bb.value!!
        assertEquals(BigDecimal.valueOf(100.0), bands.middle)
        // With no volatility, bands should be equal to middle
        assertEquals(bands.middle, bands.upper)
        assertEquals(bands.middle, bands.lower)
    }

    @Test
    fun `VWAP calculates correctly`() {
        val vwap = VWAP()

        vwap.update(createCandle(close = 100.0, high = 101.0, low = 99.0, volume = 1000.0))
        // Typical price = (101 + 99 + 100) / 3 = 100
        assertEquals(BigDecimal.valueOf(100.0), vwap.value)

        vwap.update(createCandle(close = 110.0, high = 111.0, low = 109.0, volume = 2000.0))
        // First: 100 * 1000 = 100,000
        // Second: 110 * 2000 = 220,000
        // VWAP = 320,000 / 3000 = 106.666...
        assertTrue(vwap.value!! > BigDecimal.valueOf(106))
        assertTrue(vwap.value!! < BigDecimal.valueOf(107))
    }

    @Test
    fun `IndicatorSet updates all indicators`() {
        val indicators = indicators {
            sma(3)
            ema(3)
            rsi(3)
        }

        repeat(5) { i ->
            indicators.update(createCandle(100.0 + i))
        }

        // All indicators should have been updated
        // (We can't easily test values without accessing them, but we verify no exceptions)
    }

    @Test
    fun `reset clears indicator state`() {
        val sma = SMA(3)

        sma.update(createCandle(100.0))
        sma.update(createCandle(110.0))
        sma.update(createCandle(120.0))

        assertTrue(sma.isReady)
        assertNotNull(sma.value)

        sma.reset()

        assertFalse(sma.isReady)
        assertNull(sma.value)
    }
}
