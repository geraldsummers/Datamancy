package org.datamancy.trading.alpha

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FallbackInterdayPanelSourceTest {
    @Test
    fun `uses primary panel when coverage is sufficient`() = runBlocking {
        val request = request()
        val source = FallbackInterdayPanelSource(
            primary = FixedPanelSource(panelWithBars(request, 40)),
            fallback = FixedPanelSource(panelWithBars(request, 45))
        )

        val panel = source.load(request)

        assertEquals(40, panel.timeline.size)
    }

    @Test
    fun `falls back when primary panel is too sparse`() = runBlocking {
        val request = request()
        val source = FallbackInterdayPanelSource(
            primary = FixedPanelSource(panelWithBars(request, 18)),
            fallback = FixedPanelSource(panelWithBars(request, 45))
        )

        val panel = source.load(request)

        assertEquals(45, panel.timeline.size)
    }

    @Test
    fun `falls back when primary source throws`() = runBlocking {
        val request = request()
        val source = FallbackInterdayPanelSource(
            primary = object : InterdayPanelSource {
                override suspend fun load(request: InterdayPanelRequest): InterdayPanel {
                    error("primary failed")
                }
            },
            fallback = FixedPanelSource(panelWithBars(request, 45))
        )

        val panel = source.load(request)

        assertEquals(45, panel.timeline.size)
    }

    @Test
    fun `coverage helper matches expected bar math`() {
        val request = request()
        assertEquals(45, expectedPanelBars(request))
        assertFalse(panelHasSufficientHistory(panelWithBars(request, 18), request))
        assertTrue(panelHasSufficientHistory(panelWithBars(request, 40), request))
    }

    private fun request(): InterdayPanelRequest =
        InterdayPanelRequest(
            exchange = "hyperliquid_mainnet",
            signalBarMinutes = 1_440,
            startTime = Instant.parse("2026-02-01T00:00:00Z"),
            endTime = Instant.parse("2026-03-18T00:00:00Z")
        )

    private fun panelWithBars(request: InterdayPanelRequest, bars: Int): InterdayPanel {
        val timeline = (0 until bars).map { index ->
            request.startTime.plusSeconds(index.toLong() * 86_400L)
        }
        val series = listOf(
            InterdaySymbolSeries(
                symbol = "BTC",
                bars = timeline.map { time ->
                    InterdayBar(
                        time = time,
                        open = 1.0,
                        high = 1.1,
                        low = 0.9,
                        close = 1.0,
                        volume = 100.0,
                        tradeVolume = 100.0,
                        buyVolume = 50.0,
                        sellVolume = 50.0,
                        spreadBps = null,
                        depthUsd = null,
                        fundingRate = 0.0,
                        openInterest = null,
                        tradeObservedRatio = 0.0,
                        orderbookObservedRatio = 0.0,
                        assetContextObservedRatio = 1.0
                    )
                }
            )
        )
        return InterdayPanel(
            exchange = request.exchange,
            signalBarMinutes = request.signalBarMinutes,
            timeline = timeline,
            series = series
        )
    }

    private class FixedPanelSource(
        private val panel: InterdayPanel
    ) : InterdayPanelSource {
        override suspend fun load(request: InterdayPanelRequest): InterdayPanel = panel
    }
}
