package org.datamancy.pipeline.runtime

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicLong

private val minuteStagingLogger = KotlinLogging.logger {}
private const val DEFAULT_MINUTE_STAGING_BOOTSTRAP_HOURS = 72L
private const val DEFAULT_MINUTE_STAGING_BOOTSTRAP_CHUNK_HOURS = 2L
private const val DEFAULT_MINUTE_STAGING_TRADE_WORKERS = 4
private const val DEFAULT_MINUTE_STAGING_ORDERBOOK_WORKERS = 2
private const val DEFAULT_MINUTE_STAGING_ASSET_CONTEXT_WORKERS = 1
private const val DEFAULT_MINUTE_STAGING_STATS_INTERVAL_MS = 60_000L

internal data class MinuteStagingConfig(
    val bootstrapHours: Long,
    val bootstrapChunkHours: Long,
    val tradeWorkers: Int,
    val orderbookWorkers: Int,
    val assetContextWorkers: Int
)

internal data class MinuteTradeStageUpdate(
    val bucketTime: Instant,
    val symbol: String,
    val exchange: String,
    val tradeVolume: Double,
    val buyVolume: Double,
    val sellVolume: Double,
    val tradeCount: Int,
    val tradeNotional: Double,
    val lastEventTime: Instant
)

internal data class MinuteOrderbookStageUpdate(
    val bucketTime: Instant,
    val symbol: String,
    val exchange: String,
    val bestBid: Double?,
    val bestAsk: Double?,
    val spread: Double,
    val spreadPct: Double?,
    val midPrice: Double?,
    val bidDepth10: Double,
    val askDepth10: Double,
    val orderbookSamples: Int,
    val lastEventTime: Instant
)

internal data class MinuteAssetContextStageUpdate(
    val bucketTime: Instant,
    val symbol: String,
    val exchange: String,
    val fundingRate: Double,
    val openInterest: Double,
    val lastEventTime: Instant
)

internal fun loadMinuteStagingConfig(): MinuteStagingConfig = MinuteStagingConfig(
    bootstrapHours = System.getenv("MINUTE_STAGING_BOOTSTRAP_HOURS")?.toLongOrNull()
        ?: DEFAULT_MINUTE_STAGING_BOOTSTRAP_HOURS,
    bootstrapChunkHours = System.getenv("MINUTE_STAGING_BOOTSTRAP_CHUNK_HOURS")?.toLongOrNull()
        ?: DEFAULT_MINUTE_STAGING_BOOTSTRAP_CHUNK_HOURS,
    tradeWorkers = System.getenv("MINUTE_STAGING_TRADE_WORKERS")?.toIntOrNull()
        ?: DEFAULT_MINUTE_STAGING_TRADE_WORKERS,
    orderbookWorkers = System.getenv("MINUTE_STAGING_ORDERBOOK_WORKERS")?.toIntOrNull()
        ?: DEFAULT_MINUTE_STAGING_ORDERBOOK_WORKERS,
    assetContextWorkers = System.getenv("MINUTE_STAGING_ASSET_CONTEXT_WORKERS")?.toIntOrNull()
        ?: DEFAULT_MINUTE_STAGING_ASSET_CONTEXT_WORKERS
)

private data class MinuteStagingDependencies(
    val config: MarketDataServiceConfig,
    val stagingConfig: MinuteStagingConfig,
    val dataSource: PGSimpleDataSource,
    val transport: RawMarketDataTransport,
    val store: MinuteStagingStore
)

private fun buildMinuteStagingDependencies(): MinuteStagingDependencies {
    val config = loadMarketDataServiceConfig()
    val dataSource = createMarketDataDataSource(config)
    return MinuteStagingDependencies(
        config = config,
        stagingConfig = loadMinuteStagingConfig(),
        dataSource = dataSource,
        transport = NatsJetStreamRawMarketDataTransport(
            config = config.rawEventTransport,
            connectionName = "market-data-minute-staging"
        ),
        store = MinuteStagingStore(dataSource, config.exchangeId)
    )
}

internal class MinuteStagingStore(
    private val dataSource: PGSimpleDataSource,
    private val exchangeId: String
) {
    private val tradeUpserts = AtomicLong(0)
    private val orderbookUpserts = AtomicLong(0)
    private val assetContextUpserts = AtomicLong(0)
    private val bootstrapChunks = AtomicLong(0)

    suspend fun bootstrapRecent(bootstrapHours: Long, chunkHours: Long) = withContext(Dispatchers.IO) {
        val endExclusive = Instant.now().truncatedTo(ChronoUnit.MINUTES)
        val startInclusive = endExclusive.minus(bootstrapHours.coerceAtLeast(1L), ChronoUnit.HOURS)
        var chunkEndExclusive = endExclusive
        while (chunkEndExclusive.isAfter(startInclusive)) {
            val chunkStartInclusive = laterInstant(
                startInclusive,
                chunkEndExclusive.minus(chunkHours.coerceAtLeast(1L), ChronoUnit.HOURS)
            )
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    backfillTradeChunk(conn, chunkStartInclusive, chunkEndExclusive)
                    backfillOrderbookChunk(conn, chunkStartInclusive, chunkEndExclusive)
                    backfillAssetContextChunk(conn, chunkStartInclusive, chunkEndExclusive)
                    conn.commit()
                    bootstrapChunks.incrementAndGet()
                    minuteStagingLogger.info {
                        "minute-staging bootstrap exchange=$exchangeId window=$chunkStartInclusive..$chunkEndExclusive"
                    }
                } catch (ex: Exception) {
                    conn.rollback()
                    throw ex
                }
            }
            chunkEndExclusive = chunkStartInclusive
        }
    }

    suspend fun apply(envelope: RawMarketDataEnvelope) = withContext(Dispatchers.IO) {
        dataSource.connection.use { conn ->
            conn.autoCommit = false
            try {
                when (envelope.channel) {
                    "trade" -> upsertTrades(conn, aggregateTradeStageUpdates(envelope))
                    "orderbook_l2" -> upsertOrderbook(conn, summarizeOrderbookStageUpdate(envelope))
                    "asset_context" -> upsertAssetContext(conn, summarizeAssetContextStageUpdate(envelope))
                }
                conn.commit()
            } catch (ex: Exception) {
                conn.rollback()
                throw ex
            }
        }
    }

    fun statsSnapshot(): MinuteStagingStats = MinuteStagingStats(
        tradeUpserts = tradeUpserts.get(),
        orderbookUpserts = orderbookUpserts.get(),
        assetContextUpserts = assetContextUpserts.get(),
        bootstrapChunks = bootstrapChunks.get()
    )

    private fun backfillTradeChunk(conn: Connection, startInclusive: Instant, endExclusive: Instant) {
        val sql = """
            WITH aggregated AS (
                SELECT
                    time_bucket(INTERVAL '1 minute', time) AS bucket_time,
                    symbol,
                    exchange,
                    COALESCE(SUM(size), 0) AS trade_volume,
                    COALESCE(SUM(CASE WHEN side = 'buy' THEN size ELSE 0 END), 0) AS buy_volume,
                    COALESCE(SUM(CASE WHEN side = 'sell' THEN size ELSE 0 END), 0) AS sell_volume,
                    COUNT(*)::INTEGER AS trade_count,
                    COALESCE(SUM(price * size), 0) AS trade_notional,
                    MAX(time) AS last_event_time
                FROM market_data
                WHERE exchange = ?
                  AND data_type = 'trade'
                  AND time >= ?
                  AND time < ?
                GROUP BY 1, 2, 3
            )
            INSERT INTO minute_trade_stats (
                time,
                symbol,
                exchange,
                trade_volume,
                buy_volume,
                sell_volume,
                trade_count,
                trade_notional,
                last_event_time,
                source_updated_at
            )
            SELECT
                bucket_time,
                symbol,
                exchange,
                trade_volume,
                buy_volume,
                sell_volume,
                trade_count,
                trade_notional,
                last_event_time,
                NOW()
            FROM aggregated
            ON CONFLICT (time, symbol, exchange) DO UPDATE SET
                trade_volume = EXCLUDED.trade_volume,
                buy_volume = EXCLUDED.buy_volume,
                sell_volume = EXCLUDED.sell_volume,
                trade_count = EXCLUDED.trade_count,
                trade_notional = EXCLUDED.trade_notional,
                last_event_time = EXCLUDED.last_event_time,
                source_updated_at = EXCLUDED.source_updated_at
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, exchangeId)
            stmt.setTimestamp(2, Timestamp.from(startInclusive))
            stmt.setTimestamp(3, Timestamp.from(endExclusive))
            stmt.executeUpdate()
        }
    }

    private fun backfillOrderbookChunk(conn: Connection, startInclusive: Instant, endExclusive: Instant) {
        val sql = """
            WITH minute_orderbook_counts AS (
                SELECT
                    time_bucket(INTERVAL '1 minute', time) AS bucket_time,
                    symbol,
                    exchange,
                    COUNT(*)::INTEGER AS orderbook_samples,
                    MAX(time) AS last_event_time
                FROM orderbook_data
                WHERE exchange = ?
                  AND time >= ?
                  AND time < ?
                GROUP BY 1, 2, 3
            ),
            minute_orderbook_latest AS (
                SELECT DISTINCT ON (bucket_time, symbol, exchange)
                    time_bucket(INTERVAL '1 minute', time) AS bucket_time,
                    symbol,
                    exchange,
                    best_bid,
                    best_ask,
                    spread,
                    spread_pct,
                    mid_price,
                    bid_depth_10,
                    ask_depth_10,
                    time AS last_event_time
                FROM orderbook_data
                WHERE exchange = ?
                  AND time >= ?
                  AND time < ?
                ORDER BY bucket_time, symbol, exchange, time DESC
            )
            INSERT INTO minute_orderbook_state (
                time,
                symbol,
                exchange,
                best_bid,
                best_ask,
                spread,
                spread_pct,
                mid_price,
                bid_depth_10,
                ask_depth_10,
                orderbook_samples,
                last_event_time,
                source_updated_at
            )
            SELECT
                latest.bucket_time,
                latest.symbol,
                latest.exchange,
                latest.best_bid,
                latest.best_ask,
                latest.spread,
                latest.spread_pct,
                latest.mid_price,
                COALESCE(latest.bid_depth_10, 0),
                COALESCE(latest.ask_depth_10, 0),
                counts.orderbook_samples,
                latest.last_event_time,
                NOW()
            FROM minute_orderbook_latest latest
            JOIN minute_orderbook_counts counts
              ON counts.bucket_time = latest.bucket_time
             AND counts.symbol = latest.symbol
             AND counts.exchange = latest.exchange
            ON CONFLICT (time, symbol, exchange) DO UPDATE SET
                best_bid = EXCLUDED.best_bid,
                best_ask = EXCLUDED.best_ask,
                spread = EXCLUDED.spread,
                spread_pct = EXCLUDED.spread_pct,
                mid_price = EXCLUDED.mid_price,
                bid_depth_10 = EXCLUDED.bid_depth_10,
                ask_depth_10 = EXCLUDED.ask_depth_10,
                orderbook_samples = EXCLUDED.orderbook_samples,
                last_event_time = EXCLUDED.last_event_time,
                source_updated_at = EXCLUDED.source_updated_at
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, exchangeId)
            stmt.setTimestamp(2, Timestamp.from(startInclusive))
            stmt.setTimestamp(3, Timestamp.from(endExclusive))
            stmt.setString(4, exchangeId)
            stmt.setTimestamp(5, Timestamp.from(startInclusive))
            stmt.setTimestamp(6, Timestamp.from(endExclusive))
            stmt.executeUpdate()
        }
    }

    private fun backfillAssetContextChunk(conn: Connection, startInclusive: Instant, endExclusive: Instant) {
        val sql = """
            WITH minute_funding AS (
                SELECT DISTINCT ON (bucket_time, symbol, exchange)
                    time_bucket(INTERVAL '1 minute', time) AS bucket_time,
                    symbol,
                    exchange,
                    funding_rate,
                    time AS funding_time
                FROM market_data
                WHERE exchange = ?
                  AND data_type = 'funding'
                  AND time >= ?
                  AND time < ?
                ORDER BY bucket_time, symbol, exchange, time DESC
            ),
            minute_open_interest AS (
                SELECT DISTINCT ON (bucket_time, symbol, exchange)
                    time_bucket(INTERVAL '1 minute', time) AS bucket_time,
                    symbol,
                    exchange,
                    open_interest,
                    time AS open_interest_time
                FROM market_data
                WHERE exchange = ?
                  AND data_type = 'open_interest'
                  AND time >= ?
                  AND time < ?
                ORDER BY bucket_time, symbol, exchange, time DESC
            )
            INSERT INTO minute_asset_context (
                time,
                symbol,
                exchange,
                funding_rate,
                open_interest,
                last_event_time,
                source_updated_at
            )
            SELECT
                COALESCE(f.bucket_time, oi.bucket_time),
                COALESCE(f.symbol, oi.symbol),
                COALESCE(f.exchange, oi.exchange),
                f.funding_rate,
                oi.open_interest,
                COALESCE(
                    GREATEST(f.funding_time, oi.open_interest_time),
                    f.funding_time,
                    oi.open_interest_time
                ),
                NOW()
            FROM minute_funding f
            FULL OUTER JOIN minute_open_interest oi
              ON oi.bucket_time = f.bucket_time
             AND oi.symbol = f.symbol
             AND oi.exchange = f.exchange
            ON CONFLICT (time, symbol, exchange) DO UPDATE SET
                funding_rate = EXCLUDED.funding_rate,
                open_interest = EXCLUDED.open_interest,
                last_event_time = EXCLUDED.last_event_time,
                source_updated_at = EXCLUDED.source_updated_at
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setString(1, exchangeId)
            stmt.setTimestamp(2, Timestamp.from(startInclusive))
            stmt.setTimestamp(3, Timestamp.from(endExclusive))
            stmt.setString(4, exchangeId)
            stmt.setTimestamp(5, Timestamp.from(startInclusive))
            stmt.setTimestamp(6, Timestamp.from(endExclusive))
            stmt.executeUpdate()
        }
    }

    private fun upsertTrades(conn: Connection, updates: List<MinuteTradeStageUpdate>) {
        if (updates.isEmpty()) return
        val sql = """
            INSERT INTO minute_trade_stats (
                time,
                symbol,
                exchange,
                trade_volume,
                buy_volume,
                sell_volume,
                trade_count,
                trade_notional,
                last_event_time,
                source_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (time, symbol, exchange) DO UPDATE SET
                trade_volume = minute_trade_stats.trade_volume + EXCLUDED.trade_volume,
                buy_volume = minute_trade_stats.buy_volume + EXCLUDED.buy_volume,
                sell_volume = minute_trade_stats.sell_volume + EXCLUDED.sell_volume,
                trade_count = minute_trade_stats.trade_count + EXCLUDED.trade_count,
                trade_notional = minute_trade_stats.trade_notional + EXCLUDED.trade_notional,
                last_event_time = CASE
                    WHEN minute_trade_stats.last_event_time IS NULL THEN EXCLUDED.last_event_time
                    ELSE GREATEST(minute_trade_stats.last_event_time, EXCLUDED.last_event_time)
                END,
                source_updated_at = GREATEST(minute_trade_stats.source_updated_at, EXCLUDED.source_updated_at)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            val sourceUpdatedAt = Timestamp.from(Instant.now())
            updates.forEach { update ->
                stmt.setTimestamp(1, Timestamp.from(update.bucketTime))
                stmt.setString(2, update.symbol)
                stmt.setString(3, update.exchange)
                stmt.setDouble(4, update.tradeVolume)
                stmt.setDouble(5, update.buyVolume)
                stmt.setDouble(6, update.sellVolume)
                stmt.setInt(7, update.tradeCount)
                stmt.setDouble(8, update.tradeNotional)
                stmt.setTimestamp(9, Timestamp.from(update.lastEventTime))
                stmt.setTimestamp(10, sourceUpdatedAt)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
        tradeUpserts.addAndGet(updates.size.toLong())
    }

    private fun upsertOrderbook(conn: Connection, update: MinuteOrderbookStageUpdate?) {
        if (update == null) return
        val sql = """
            INSERT INTO minute_orderbook_state (
                time,
                symbol,
                exchange,
                best_bid,
                best_ask,
                spread,
                spread_pct,
                mid_price,
                bid_depth_10,
                ask_depth_10,
                orderbook_samples,
                last_event_time,
                source_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (time, symbol, exchange) DO UPDATE SET
                best_bid = CASE
                    WHEN minute_orderbook_state.last_event_time IS NULL OR EXCLUDED.last_event_time >= minute_orderbook_state.last_event_time THEN EXCLUDED.best_bid
                    ELSE minute_orderbook_state.best_bid
                END,
                best_ask = CASE
                    WHEN minute_orderbook_state.last_event_time IS NULL OR EXCLUDED.last_event_time >= minute_orderbook_state.last_event_time THEN EXCLUDED.best_ask
                    ELSE minute_orderbook_state.best_ask
                END,
                spread = CASE
                    WHEN minute_orderbook_state.last_event_time IS NULL OR EXCLUDED.last_event_time >= minute_orderbook_state.last_event_time THEN EXCLUDED.spread
                    ELSE minute_orderbook_state.spread
                END,
                spread_pct = CASE
                    WHEN minute_orderbook_state.last_event_time IS NULL OR EXCLUDED.last_event_time >= minute_orderbook_state.last_event_time THEN EXCLUDED.spread_pct
                    ELSE minute_orderbook_state.spread_pct
                END,
                mid_price = CASE
                    WHEN minute_orderbook_state.last_event_time IS NULL OR EXCLUDED.last_event_time >= minute_orderbook_state.last_event_time THEN EXCLUDED.mid_price
                    ELSE minute_orderbook_state.mid_price
                END,
                bid_depth_10 = CASE
                    WHEN minute_orderbook_state.last_event_time IS NULL OR EXCLUDED.last_event_time >= minute_orderbook_state.last_event_time THEN EXCLUDED.bid_depth_10
                    ELSE minute_orderbook_state.bid_depth_10
                END,
                ask_depth_10 = CASE
                    WHEN minute_orderbook_state.last_event_time IS NULL OR EXCLUDED.last_event_time >= minute_orderbook_state.last_event_time THEN EXCLUDED.ask_depth_10
                    ELSE minute_orderbook_state.ask_depth_10
                END,
                orderbook_samples = minute_orderbook_state.orderbook_samples + EXCLUDED.orderbook_samples,
                last_event_time = CASE
                    WHEN minute_orderbook_state.last_event_time IS NULL THEN EXCLUDED.last_event_time
                    ELSE GREATEST(minute_orderbook_state.last_event_time, EXCLUDED.last_event_time)
                END,
                source_updated_at = GREATEST(minute_orderbook_state.source_updated_at, EXCLUDED.source_updated_at)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            val sourceUpdatedAt = Timestamp.from(Instant.now())
            stmt.setTimestamp(1, Timestamp.from(update.bucketTime))
            stmt.setString(2, update.symbol)
            stmt.setString(3, update.exchange)
            setNullableDouble(stmt, 4, update.bestBid)
            setNullableDouble(stmt, 5, update.bestAsk)
            stmt.setDouble(6, update.spread)
            setNullableDouble(stmt, 7, update.spreadPct)
            setNullableDouble(stmt, 8, update.midPrice)
            stmt.setDouble(9, update.bidDepth10)
            stmt.setDouble(10, update.askDepth10)
            stmt.setInt(11, update.orderbookSamples)
            stmt.setTimestamp(12, Timestamp.from(update.lastEventTime))
            stmt.setTimestamp(13, sourceUpdatedAt)
            stmt.executeUpdate()
        }
        orderbookUpserts.incrementAndGet()
    }

    private fun upsertAssetContext(conn: Connection, update: MinuteAssetContextStageUpdate?) {
        if (update == null) return
        val sql = """
            INSERT INTO minute_asset_context (
                time,
                symbol,
                exchange,
                funding_rate,
                open_interest,
                last_event_time,
                source_updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (time, symbol, exchange) DO UPDATE SET
                funding_rate = CASE
                    WHEN minute_asset_context.last_event_time IS NULL OR EXCLUDED.last_event_time >= minute_asset_context.last_event_time THEN EXCLUDED.funding_rate
                    ELSE minute_asset_context.funding_rate
                END,
                open_interest = CASE
                    WHEN minute_asset_context.last_event_time IS NULL OR EXCLUDED.last_event_time >= minute_asset_context.last_event_time THEN EXCLUDED.open_interest
                    ELSE minute_asset_context.open_interest
                END,
                last_event_time = CASE
                    WHEN minute_asset_context.last_event_time IS NULL THEN EXCLUDED.last_event_time
                    ELSE GREATEST(minute_asset_context.last_event_time, EXCLUDED.last_event_time)
                END,
                source_updated_at = GREATEST(minute_asset_context.source_updated_at, EXCLUDED.source_updated_at)
        """.trimIndent()
        conn.prepareStatement(sql).use { stmt ->
            stmt.setTimestamp(1, Timestamp.from(update.bucketTime))
            stmt.setString(2, update.symbol)
            stmt.setString(3, update.exchange)
            stmt.setDouble(4, update.fundingRate)
            stmt.setDouble(5, update.openInterest)
            stmt.setTimestamp(6, Timestamp.from(update.lastEventTime))
            stmt.setTimestamp(7, Timestamp.from(Instant.now()))
            stmt.executeUpdate()
        }
        assetContextUpserts.incrementAndGet()
    }
}

internal data class MinuteStagingStats(
    val tradeUpserts: Long,
    val orderbookUpserts: Long,
    val assetContextUpserts: Long,
    val bootstrapChunks: Long
)

internal fun aggregateTradeStageUpdates(envelope: RawMarketDataEnvelope): List<MinuteTradeStageUpdate> {
    val exchange = envelope.exchange
    val grouped = linkedMapOf<Pair<Instant, String>, MinuteTradeStageUpdate>()
    envelope.trades.orEmpty().forEach { trade ->
        val tradeTime = Instant.parse(trade.time)
        val bucketTime = tradeTime.truncatedTo(ChronoUnit.MINUTES)
        val key = bucketTime to trade.symbol
        val existing = grouped[key]
        val tradeVolume = trade.size
        val buyVolume = if (trade.side.equals("buy", ignoreCase = true)) trade.size else 0.0
        val sellVolume = if (trade.side.equals("sell", ignoreCase = true)) trade.size else 0.0
        val tradeNotional = trade.price * trade.size
        grouped[key] = if (existing == null) {
            MinuteTradeStageUpdate(
                bucketTime = bucketTime,
                symbol = trade.symbol,
                exchange = exchange,
                tradeVolume = tradeVolume,
                buyVolume = buyVolume,
                sellVolume = sellVolume,
                tradeCount = 1,
                tradeNotional = tradeNotional,
                lastEventTime = tradeTime
            )
        } else {
            existing.copy(
                tradeVolume = existing.tradeVolume + tradeVolume,
                buyVolume = existing.buyVolume + buyVolume,
                sellVolume = existing.sellVolume + sellVolume,
                tradeCount = existing.tradeCount + 1,
                tradeNotional = existing.tradeNotional + tradeNotional,
                lastEventTime = laterInstant(existing.lastEventTime, tradeTime)
            )
        }
    }
    return grouped.values.toList()
}

internal fun summarizeOrderbookStageUpdate(envelope: RawMarketDataEnvelope): MinuteOrderbookStageUpdate? {
    val orderbook = envelope.orderbook ?: return null
    val eventTime = Instant.parse(orderbook.time)
    val sortedBids = orderbook.bids.sortedByDescending { it.price }
    val sortedAsks = orderbook.asks.sortedBy { it.price }
    val bestBid = sortedBids.firstOrNull()?.price
    val bestAsk = sortedAsks.firstOrNull()?.price
    val spread = if (bestBid != null && bestAsk != null) {
        (bestAsk - bestBid).coerceAtLeast(0.0)
    } else {
        0.0
    }
    val midPrice = if (bestBid != null && bestAsk != null) {
        (bestAsk + bestBid) / 2.0
    } else {
        null
    }
    val spreadPct = if (midPrice != null && midPrice > 0.0) {
        (spread / midPrice) * 100.0
    } else {
        null
    }
    return MinuteOrderbookStageUpdate(
        bucketTime = eventTime.truncatedTo(ChronoUnit.MINUTES),
        symbol = orderbook.symbol,
        exchange = envelope.exchange,
        bestBid = bestBid,
        bestAsk = bestAsk,
        spread = spread,
        spreadPct = spreadPct,
        midPrice = midPrice,
        bidDepth10 = depth(sortedBids),
        askDepth10 = depth(sortedAsks),
        orderbookSamples = 1,
        lastEventTime = eventTime
    )
}

internal fun summarizeAssetContextStageUpdate(envelope: RawMarketDataEnvelope): MinuteAssetContextStageUpdate? {
    val assetContext = envelope.assetContext ?: return null
    val eventTime = Instant.parse(assetContext.time)
    return MinuteAssetContextStageUpdate(
        bucketTime = eventTime.truncatedTo(ChronoUnit.MINUTES),
        symbol = assetContext.symbol,
        exchange = envelope.exchange,
        fundingRate = assetContext.fundingRate,
        openInterest = assetContext.openInterest,
        lastEventTime = eventTime
    )
}

private fun depth(levels: List<OrderbookLevelPayload>): Double = levels.take(10).sumOf { it.size }

private fun setNullableDouble(stmt: java.sql.PreparedStatement, index: Int, value: Double?) {
    if (value == null) {
        stmt.setNull(index, java.sql.Types.DOUBLE)
    } else {
        stmt.setDouble(index, value)
    }
}

private fun laterInstant(a: Instant, b: Instant): Instant = if (a.isAfter(b)) a else b

class MinuteStagingRunner internal constructor(
    private val config: MarketDataServiceConfig,
    private val stagingConfig: MinuteStagingConfig,
    private val dataSource: PGSimpleDataSource,
    private val transport: RawMarketDataTransport,
    private val store: MinuteStagingStore
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var bootstrapJob: Job? = null
    private var consumeJobs: List<Job> = emptyList()
    private var statsJob: Job? = null

    constructor() : this(buildMinuteStagingDependencies())

    private constructor(deps: MinuteStagingDependencies) : this(
        config = deps.config,
        stagingConfig = deps.stagingConfig,
        dataSource = deps.dataSource,
        transport = deps.transport,
        store = deps.store
    )

    fun start() {
        runBlocking {
            if (!waitForDataSource(dataSource, "TimescaleDB")) {
                error("TimescaleDB did not become ready for market-data-minute-staging")
            }
        }
        minuteStagingLogger.info {
            "Starting market-data-minute-staging exchange=${config.exchangeId} bootstrapHours=${stagingConfig.bootstrapHours} chunkHours=${stagingConfig.bootstrapChunkHours}"
        }
        bootstrapJob = scope.launch {
            try {
                store.bootstrapRecent(
                    bootstrapHours = stagingConfig.bootstrapHours,
                    chunkHours = stagingConfig.bootstrapChunkHours
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ex: Exception) {
                minuteStagingLogger.error(ex) { "minute-staging bootstrap failed: ${ex.message}" }
            }
        }
        consumeJobs = buildList {
            addAll(launchConsumers("trade", stagingConfig.tradeWorkers))
            addAll(launchConsumers("orderbook_l2", stagingConfig.orderbookWorkers))
            addAll(launchConsumers("asset_context", stagingConfig.assetContextWorkers))
        }
        statsJob = scope.launch {
            while (isActive) {
                delay(DEFAULT_MINUTE_STAGING_STATS_INTERVAL_MS)
                val stats = store.statsSnapshot()
                minuteStagingLogger.info {
                    "market-data-minute-staging tradeUpserts=${stats.tradeUpserts} orderbookUpserts=${stats.orderbookUpserts} assetContextUpserts=${stats.assetContextUpserts} bootstrapChunks=${stats.bootstrapChunks}"
                }
            }
        }
    }

    fun stop() {
        runBlocking {
            statsJob?.cancelAndJoin()
            bootstrapJob?.cancelAndJoin()
            consumeJobs.forEach { it.cancelAndJoin() }
            runCatching { transport.close() }
            scope.cancel()
        }
    }

    private fun launchConsumers(channel: String, workerCount: Int): List<Job> = List(workerCount.coerceAtLeast(1)) {
        scope.launch {
            transport.consume(
                subjectFilter = config.rawEventTransport.persistSubject(
                    exchangeId = config.exchangeId,
                    channel = channel,
                    lane = RawEventLane.LIVE
                ),
                durableName = "market-data-minute-staging-v1-${sanitizeSubjectToken(config.exchangeId)}-${sanitizeSubjectToken(channel)}",
                deliverPolicy = persistDeliverPolicy(RawEventLane.LIVE)
            ) { envelope ->
                store.apply(envelope)
            }
        }
    }
}
