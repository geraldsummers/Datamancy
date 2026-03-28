package org.datamancy.marketdataarchiveimporter

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ArchiveLineParsersTest {
    private val parsers = ArchiveLineParsers()

    @Test
    fun `parse trade line unwraps batched block events`() {
        val trades = parsers.parseTradeLine(
            """
            {"block_time":1773187200000,"events":[
              {"coin":"BTC","px":"50000","sz":"0.10","side":"buy","tid":"t1"},
              {"coin":"ETH","px":"2500","sz":"1.50","side":"sell","tid":"t2"}
            ]}
            """.trimIndent()
        )

        assertEquals(2, trades.size)
        assertEquals("BTC", trades[0].symbol)
        assertEquals("buy", trades[0].side)
        assertEquals("ETH", trades[1].symbol)
        assertEquals("sell", trades[1].side)
    }

    @Test
    fun `parse archived wrapper lines for asset context and orderbook`() {
        val assetContext = parsers.parseAssetContextLine(
            """
            {"time":"2026-03-10T00:00:01Z","raw":{"data":{"coin":"BTC","ctx":{"funding":"0.0001","openInterest":"12345"}}}}
            """.trimIndent()
        )
        val orderbook = parsers.parseOrderbookLine(
            """
            {"time":"2026-03-10T00:01:00Z","raw":{"data":{"coin":"BTC","time":1773100860000,"levels":[[{"px":"49990","sz":"1.0","n":1}],[{"px":"50010","sz":"1.2","n":1}]]}}}
            """.trimIndent()
        )

        assertNotNull(assetContext)
        assertEquals("BTC", assetContext.symbol)
        assertEquals(0.0001, assetContext.fundingRate)
        assertEquals(12345.0, assetContext.openInterest)

        assertNotNull(orderbook)
        assertEquals("BTC", orderbook.symbol)
        assertEquals(1, orderbook.bids.size)
        assertEquals(49990.0, orderbook.bids.first().price)
        assertEquals(50010.0, orderbook.asks.first().price)
    }
}
