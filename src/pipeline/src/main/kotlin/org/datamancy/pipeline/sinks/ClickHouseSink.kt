package org.datamancy.pipeline.sinks

import com.clickhouse.client.ClickHouseClient
import com.clickhouse.client.ClickHouseCredentials
import com.clickhouse.client.ClickHouseNode
import com.clickhouse.client.ClickHouseProtocol
import com.clickhouse.client.ClickHouseRequest
import io.github.oshai.kotlinlogging.KotlinLogging
import org.datamancy.pipeline.core.Sink
import org.datamancy.pipeline.sources.BinanceKline

private val logger = KotlinLogging.logger {}

/**
 * Writes Binance klines to ClickHouse time-series database
 */
class ClickHouseSink(
    clickhouseUrl: String,
    private val user: String = "default",
    private val password: String = ""
) : Sink<BinanceKline> {
    override val name = "ClickHouseSink"

    private val host: String
    private val port: Int

    init {
        val urlParts = clickhouseUrl
            .removePrefix("http://")
            .removePrefix("https://")
            .split(":")

        host = urlParts[0]
        port = urlParts.getOrNull(1)?.toIntOrNull() ?: 8123
    }

    private val node = ClickHouseNode.builder()
        .host(host)
        .port(ClickHouseProtocol.HTTP, port)
        .credentials(ClickHouseCredentials.fromUserAndPassword(user, password))
        .build()

    private val client = ClickHouseClient.newInstance(node.protocol)

    init {
        ensureTableExists()
    }

    private fun ensureTableExists() {
        try {
            logger.info { "Ensuring ClickHouse table exists: market_klines" }

            val createTableSQL = """
                CREATE TABLE IF NOT EXISTS market_klines (
                    symbol String,
                    interval String,
                    open_time DateTime64(3),
                    open Float64,
                    high Float64,
                    low Float64,
                    close Float64,
                    volume Float64,
                    close_time DateTime64(3),
                    quote_asset_volume Float64,
                    number_of_trades UInt32,
                    taker_buy_base_volume Float64,
                    taker_buy_quote_volume Float64
                ) ENGINE = MergeTree()
                PARTITION BY toYYYYMM(open_time)
                ORDER BY (symbol, interval, open_time)
            """.trimIndent()

            client.read(node).query(createTableSQL).executeAndWait()
            logger.info { "ClickHouse table market_klines ready" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to create ClickHouse table: ${e.message}" }
        }
    }

    override suspend fun write(item: BinanceKline) {
        try {
            val insertSQL = """
                INSERT INTO market_klines VALUES (
                    '${item.symbol}',
                    '${item.interval}',
                    toDateTime64(${item.openTime}, 3),
                    ${item.open},
                    ${item.high},
                    ${item.low},
                    ${item.close},
                    ${item.volume},
                    toDateTime64(${item.closeTime}, 3),
                    ${item.quoteAssetVolume},
                    ${item.numberOfTrades},
                    ${item.takerBuyBaseAssetVolume},
                    ${item.takerBuyQuoteAssetVolume}
                )
            """.trimIndent()

            client.read(node).query(insertSQL).executeAndWait()
            logger.debug { "Wrote kline for ${item.symbol} at ${item.openTime}" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to write kline to ClickHouse: ${e.message}" }
            throw e
        }
    }

    override suspend fun writeBatch(items: List<BinanceKline>) {
        if (items.isEmpty()) return

        try {
            val values = items.joinToString(",\n") { item ->
                """
                (
                    '${item.symbol}',
                    '${item.interval}',
                    toDateTime64(${item.openTime}, 3),
                    ${item.open},
                    ${item.high},
                    ${item.low},
                    ${item.close},
                    ${item.volume},
                    toDateTime64(${item.closeTime}, 3),
                    ${item.quoteAssetVolume},
                    ${item.numberOfTrades},
                    ${item.takerBuyBaseAssetVolume},
                    ${item.takerBuyQuoteAssetVolume}
                )
                """.trimIndent()
            }

            val insertSQL = "INSERT INTO market_klines VALUES $values"

            client.read(node).query(insertSQL).executeAndWait()
            logger.info { "Wrote ${items.size} klines to ClickHouse" }

        } catch (e: Exception) {
            logger.error(e) { "Failed to write batch to ClickHouse: ${e.message}" }
            throw e
        }
    }

    override suspend fun healthCheck(): Boolean {
        return try {
            client.read(node).query("SELECT 1").executeAndWait()
            true
        } catch (e: Exception) {
            logger.error(e) { "ClickHouse health check failed: ${e.message}" }
            false
        }
    }
}
