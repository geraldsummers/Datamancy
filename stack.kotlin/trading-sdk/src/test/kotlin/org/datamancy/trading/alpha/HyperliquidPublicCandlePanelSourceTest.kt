package org.datamancy.trading.alpha

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HyperliquidPublicCandlePanelSourceTest {
    @Test
    fun `loads 4h public candles into aligned panel`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {"universe":[{"name":"ALPHA","isDelisted":false},{"name":"BRAVO","isDelisted":false},{"name":"DEAD","isDelisted":true}]}
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setBody(candleArray("ALPHA", listOf("2026-03-01T00:00:00Z" to 100.0, "2026-03-01T04:00:00Z" to 104.0))))
        server.enqueue(MockResponse().setBody(candleArray("BRAVO", listOf("2026-03-01T00:00:00Z" to 200.0, "2026-03-01T04:00:00Z" to 198.0))))

        server.use {
            val source = HyperliquidPublicCandlePanelSource(
                dataSource = null,
                infoUrl = server.url("/info").toString(),
                concurrency = 1
            )
            val panel = source.load(
                InterdayPanelRequest(
                    exchange = "hyperliquid_mainnet",
                    signalBarMinutes = 240,
                    startTime = Instant.parse("2026-03-01T00:00:00Z"),
                    endTime = Instant.parse("2026-03-02T00:00:00Z")
                )
            )

            assertEquals(240, panel.signalBarMinutes)
            assertEquals(2, panel.series.size)
            assertEquals(listOf("2026-03-01T00:00:00Z", "2026-03-01T04:00:00Z"), panel.timeline.map(Instant::toString))
            assertTrue(panel.series.all { series -> series.bars.all { it != null } })
            assertEquals(listOf("BRAVO", "ALPHA"), panel.series.map { it.symbol })
        }
    }

    @Test
    fun `retries candle fetch after rate limit response`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """
                {"universe":[{"name":"ALPHA","isDelisted":false}]}
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody(candleArray("ALPHA", listOf("2026-03-01T00:00:00Z" to 100.0))))

        server.use {
            val source = HyperliquidPublicCandlePanelSource(
                dataSource = null,
                infoUrl = server.url("/info").toString(),
                concurrency = 1,
                requestSpacingMs = 0,
                maxRetries = 2,
                baseRetryDelayMs = 1
            )
            val panel = source.load(
                InterdayPanelRequest(
                    exchange = "hyperliquid_mainnet",
                    signalBarMinutes = 240,
                    startTime = Instant.parse("2026-03-01T00:00:00Z"),
                    endTime = Instant.parse("2026-03-02T00:00:00Z")
                )
            )

            assertEquals(1, panel.series.size)
            assertEquals("ALPHA", panel.series.single().symbol)
            assertEquals(1, panel.timeline.size)
        }
    }
}

private fun candleArray(symbol: String, points: List<Pair<String, Double>>): String {
    val array = JsonArray()
    points.forEachIndexed { index, (time, close) ->
        val obj = JsonObject()
        obj.addProperty("s", symbol)
        obj.addProperty("i", "4h")
        obj.addProperty("t", Instant.parse(time).toEpochMilli())
        obj.addProperty("o", close * 0.99)
        obj.addProperty("h", close * 1.01)
        obj.addProperty("l", close * 0.98)
        obj.addProperty("c", close)
        obj.addProperty("v", 1000 + index)
        array.add(obj)
    }
    return array.toString()
}
