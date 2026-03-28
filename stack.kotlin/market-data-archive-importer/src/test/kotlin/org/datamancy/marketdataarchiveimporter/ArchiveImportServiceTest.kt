package org.datamancy.marketdataarchiveimporter

import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.datamancy.trading.alpha.ArchiveImportRunRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArchiveImportServiceTest {
    @Test
    fun `service imports trades and synthesizes candles`() {
        val objectStore = FakeArchiveObjectStore(
            mapOf(
                "hl-mainnet-node-data:node_fills_by_block/hourly/20260310/0.lz4" to lz4(
                    """
                    {"block_time":1773100800000,"events":[{"coin":"BTC","px":"50000","sz":"0.10","side":"buy","tid":"t1"},{"coin":"ETH","px":"2500","sz":"1.50","side":"sell","tid":"t2"}]}
                    {"block_time":1773100860000,"events":[{"coin":"BTC","px":"50010","sz":"0.05","side":"buy","tid":"t3"}]}
                    """.trimIndent()
                )
            )
        )
        val persistence = FakeArchivePersistence()
        val service = ArchiveImportService(
            config = ArchiveImporterConfig(),
            objectStore = objectStore,
            persistence = persistence
        )

        val response = service.run(
            ArchiveImportRunRequest(
                startDate = "2026-03-10",
                endDate = "2026-03-10",
                channels = listOf("trade", "candle_1m")
            )
        )

        assertEquals(2, response.results.size)
        assertEquals(3, response.results.first { it.channel == "trade" }.rowsImported)
        assertEquals(2, response.results.first { it.channel == "candle_1m" }.symbolsImported)
        assertEquals(3, persistence.persistedTrades.size)
        assertEquals(1, persistence.candleSynthCalls.size)
        assertTrue(response.notes.any { "archived node fill/trade data" in it })
    }

    private class FakeArchiveObjectStore(
        private val payloads: Map<String, ByteArray>
    ) : ArchiveObjectStore {
        override fun listObjects(bucket: String, prefix: String): List<ArchiveObjectRef> =
            payloads.keys
                .filter { it.startsWith("$bucket:$prefix") }
                .map { key ->
                    val objectKey = key.substringAfter(':')
                    ArchiveObjectRef(bucket = bucket, key = objectKey, sizeBytes = payloads.getValue(key).size.toLong())
                }

        override fun open(objectRef: ArchiveObjectRef) =
            ByteArrayInputStream(payloads.getValue("${objectRef.bucket}:${objectRef.key}"))
    }

    private class FakeArchivePersistence : ArchivePersistence {
        val persistedTrades = mutableListOf<ArchiveTrade>()
        val candleSynthCalls = mutableListOf<Pair<Instant, Instant>>()

        override fun persistTrades(exchange: String, trades: List<ArchiveTrade>) {
            persistedTrades += trades
        }

        override fun persistAssetContexts(exchange: String, assetContexts: List<ArchiveAssetContext>) = Unit

        override fun persistOrderbooks(exchange: String, orderbooks: List<ArchiveOrderbook>) = Unit

        override fun synthesizeMinuteCandlesFromTrades(
            exchange: String,
            startInclusive: Instant,
            endExclusive: Instant,
            symbols: Set<String>
        ): ArchivePersistSummary {
            candleSynthCalls += startInclusive to endExclusive
            return ArchivePersistSummary(
                rowCount = symbols.size.toLong(),
                earliestTime = startInclusive,
                latestTime = endExclusive.minusMillis(1),
                symbolsImported = symbols.size
            )
        }
    }
}

private fun lz4(text: String): ByteArray {
    val output = ByteArrayOutputStream()
    FramedLZ4CompressorOutputStream(output).use { it.write(text.toByteArray()) }
    return output.toByteArray()
}
