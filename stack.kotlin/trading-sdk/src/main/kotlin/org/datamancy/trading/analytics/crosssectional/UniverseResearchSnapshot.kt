package org.datamancy.trading.analytics.crosssectional

import org.datamancy.trading.policy.ActiveTradingPolicy
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.LinkedHashMap
import kotlin.math.max

internal data class UniverseSnapshotBar(
    val symbol: String,
    val time: Instant,
    val close: Double,
    val volume: Double,
    val spreadPct: Double,
    val bidDepth10: Double,
    val askDepth10: Double,
    val midPrice: Double,
    val executionObserved: Boolean
)

internal data class UniverseSnapshot(
    val aliases: List<String>,
    val barMinutes: Int,
    val lookbackHours: Int,
    val loadedAt: Instant,
    val barsBySymbol: Map<String, List<UniverseSnapshotBar>>,
    val totalBars: Int,
    val firstBarTime: Instant?,
    val lastBarTime: Instant?
)

data class UniverseSnapshotCacheEntryStatus(
    val aliases: List<String>,
    val barMinutes: Int,
    val lookbackHours: Int,
    val loadedAt: Instant,
    val ageSeconds: Long,
    val symbols: Int,
    val bars: Int,
    val firstBarTime: Instant?,
    val lastBarTime: Instant?
)

data class UniverseSnapshotCacheStatus(
    val enabled: Boolean,
    val ttlSeconds: Long,
    val maxEntries: Int,
    val hits: Long,
    val misses: Long,
    val reloads: Long,
    val lastLoadMs: Long?,
    val lastError: String?,
    val entries: List<UniverseSnapshotCacheEntryStatus>
)

private data class UniverseSnapshotKey(
    val aliasesKey: String,
    val barMinutes: Int
)

private data class UniverseSnapshotRecord(
    val snapshot: UniverseSnapshot,
    val lastLoadMs: Long
)

private class UniverseSnapshotCache(
    private val enabled: Boolean,
    private val ttl: Duration,
    private val maxEntries: Int
) {
    private val entries = object : LinkedHashMap<UniverseSnapshotKey, UniverseSnapshotRecord>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UniverseSnapshotKey, UniverseSnapshotRecord>?): Boolean =
            size > maxEntries
    }
    private var hits: Long = 0
    private var misses: Long = 0
    private var reloads: Long = 0
    private var lastLoadMs: Long? = null
    private var lastError: String? = null

    fun getOrLoad(aliases: List<String>, lookbackHours: Int, barMinutes: Int): UniverseSnapshot? {
        if (!enabled) return null
        val normalizedAliases = aliases.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalizedAliases.isEmpty()) return null
        val key = UniverseSnapshotKey(
            aliasesKey = normalizedAliases.sorted().joinToString(","),
            barMinutes = max(barMinutes, 1)
        )
        val now = Instant.now()

        synchronized(this) {
            val existing = entries[key]
            if (existing != null) {
                val fresh = Duration.between(existing.snapshot.loadedAt, now) <= ttl
                if (fresh && existing.snapshot.lookbackHours >= lookbackHours) {
                    hits += 1
                    return existing.snapshot
                }
            }
            misses += 1
        }

        return runCatching {
            val startedAt = System.nanoTime()
            val snapshot = loadUniverseSnapshotFromFeatures(
                aliases = normalizedAliases,
                lookbackHours = lookbackHours,
                barMinutes = barMinutes,
                loadedAt = now
            )
            val loadMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(0L)
            synchronized(this) {
                entries[key] = UniverseSnapshotRecord(snapshot = snapshot, lastLoadMs = loadMs)
                reloads += 1
                lastLoadMs = loadMs
                lastError = null
            }
            snapshot
        }.onFailure { ex ->
            synchronized(this) {
                lastError = ex.message ?: ex::class.simpleName ?: "unknown"
            }
        }.getOrNull()
    }

    fun status(now: Instant = Instant.now()): UniverseSnapshotCacheStatus = synchronized(this) {
        UniverseSnapshotCacheStatus(
            enabled = enabled,
            ttlSeconds = ttl.seconds,
            maxEntries = maxEntries,
            hits = hits,
            misses = misses,
            reloads = reloads,
            lastLoadMs = lastLoadMs,
            lastError = lastError,
            entries = entries.values.map { record ->
                UniverseSnapshotCacheEntryStatus(
                    aliases = record.snapshot.aliases,
                    barMinutes = record.snapshot.barMinutes,
                    lookbackHours = record.snapshot.lookbackHours,
                    loadedAt = record.snapshot.loadedAt,
                    ageSeconds = Duration.between(record.snapshot.loadedAt, now).seconds.coerceAtLeast(0L),
                    symbols = record.snapshot.barsBySymbol.size,
                    bars = record.snapshot.totalBars,
                    firstBarTime = record.snapshot.firstBarTime,
                    lastBarTime = record.snapshot.lastBarTime
                )
            }.sortedWith(
                compareBy<UniverseSnapshotCacheEntryStatus> { it.barMinutes }
                    .thenByDescending { it.lookbackHours }
                    .thenBy { it.aliases.joinToString(",") }
            )
        )
    }
}

private val universeSnapshotCache = UniverseSnapshotCache(
    enabled = ActiveTradingPolicy.current().research.crossSectional.universeCache.enabled,
    ttl = Duration.ofSeconds(ActiveTradingPolicy.current().research.crossSectional.universeCache.ttlSeconds.toLong()),
    maxEntries = ActiveTradingPolicy.current().research.crossSectional.universeCache.maxEntries
)

private fun loadUniverseSnapshotFromFeatures(
    aliases: List<String>,
    lookbackHours: Int,
    barMinutes: Int,
    loadedAt: Instant
): UniverseSnapshot {
    val aliasSql = sqlList(aliases)
    val preferredAlias = aliases.first()
    val bucketSeconds = max(barMinutes, 1) * 60
    val sql = """
        WITH minute_rows AS (
            SELECT DISTINCT ON (symbol, time)
                symbol,
                time,
                close,
                COALESCE(volume, 0) AS volume,
                COALESCE(spread_pct, 0) AS spread_pct,
                COALESCE(bid_depth_10, 0) AS bid_depth_10,
                COALESCE(ask_depth_10, 0) AS ask_depth_10,
                COALESCE(NULLIF(mid_price, 0), close) AS mid_price,
                orderbook_observed,
                exchange
            FROM research_features_1m
            WHERE exchange IN ($aliasSql)
              AND time >= NOW() - INTERVAL '${lookbackHours} hours'
              AND candle_observed
            ORDER BY
                symbol,
                time,
                CASE WHEN exchange = '$preferredAlias' THEN 0 ELSE 1 END
        ),
        bucketed AS (
            SELECT
                symbol,
                to_timestamp(floor(extract(epoch from time) / $bucketSeconds) * $bucketSeconds) AS bucket_time,
                time,
                close,
                volume,
                spread_pct,
                bid_depth_10,
                ask_depth_10,
                mid_price,
                orderbook_observed
            FROM minute_rows
        ),
        bucket_volume AS (
            SELECT
                symbol,
                bucket_time,
                SUM(volume) AS volume
            FROM bucketed
            GROUP BY symbol, bucket_time
        ),
        bucket_close AS (
            SELECT DISTINCT ON (symbol, bucket_time)
                symbol,
                bucket_time,
                close
            FROM bucketed
            ORDER BY symbol, bucket_time, time DESC
        ),
        bucket_orderbook AS (
            SELECT DISTINCT ON (symbol, bucket_time)
                symbol,
                bucket_time,
                spread_pct,
                bid_depth_10,
                ask_depth_10,
                mid_price,
                orderbook_observed
            FROM bucketed
            WHERE orderbook_observed
            ORDER BY symbol, bucket_time, time DESC
        )
        SELECT
            c.symbol,
            c.bucket_time,
            c.close,
            v.volume,
            COALESCE(o.spread_pct, 0) AS spread_pct,
            COALESCE(o.bid_depth_10, 0) AS bid_depth_10,
            COALESCE(o.ask_depth_10, 0) AS ask_depth_10,
            COALESCE(o.mid_price, c.close) AS mid_price,
            CASE WHEN o.symbol IS NULL THEN FALSE ELSE TRUE END AS execution_observed
        FROM bucket_close c
        JOIN bucket_volume v
          ON v.symbol = c.symbol
         AND v.bucket_time = c.bucket_time
        LEFT JOIN bucket_orderbook o
          ON o.symbol = c.symbol
         AND o.bucket_time = c.bucket_time
        ORDER BY c.bucket_time ASC, c.symbol ASC
    """.trimIndent()

    val rows = buildList {
        pgConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        add(
                            UniverseSnapshotBar(
                                symbol = rs.getString("symbol"),
                                time = rs.getTimestamp("bucket_time").toInstant(),
                                close = rs.getDouble("close"),
                                volume = rs.getDouble("volume"),
                                spreadPct = rs.getDouble("spread_pct"),
                                bidDepth10 = rs.getDouble("bid_depth_10"),
                                askDepth10 = rs.getDouble("ask_depth_10"),
                                midPrice = rs.getDouble("mid_price"),
                                executionObserved = rs.getBoolean("execution_observed")
                            )
                        )
                    }
                }
            }
        }
    }
    val barsBySymbol = rows.groupBy { it.symbol }
        .mapValues { (_, bucket) -> bucket.sortedBy { it.time } }

    return UniverseSnapshot(
        aliases = aliases,
        barMinutes = max(barMinutes, 1),
        lookbackHours = lookbackHours,
        loadedAt = loadedAt,
        barsBySymbol = barsBySymbol,
        totalBars = rows.size,
        firstBarTime = rows.firstOrNull()?.time,
        lastBarTime = rows.lastOrNull()?.time
    )
}

internal fun loadUniverseSnapshotBars(
    exchange: String,
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    barMinutes: Int,
    now: Instant = Instant.now()
): List<Bar> {
    if (symbols.isEmpty()) return emptyList()
    val snapshot = universeSnapshotCache.getOrLoad(aliases, lookbackHours, barMinutes) ?: return emptyList()
    val cutoff = now.minus(lookbackHours.toLong(), ChronoUnit.HOURS)
    return symbols.asSequence()
        .distinct()
        .flatMap { symbol ->
            snapshot.barsBySymbol[symbol].orEmpty().asSequence()
                .filter { !it.time.isBefore(cutoff) }
                .map { bar ->
                    Bar(
                        exchange = exchange,
                        symbol = bar.symbol,
                        time = bar.time,
                        close = bar.close,
                        volume = bar.volume,
                        spreadPct = bar.spreadPct,
                        bidDepth10 = bar.bidDepth10,
                        askDepth10 = bar.askDepth10,
                        midPrice = bar.midPrice,
                        executionObserved = bar.executionObserved
                    )
                }
        }
        .sortedWith(compareBy<Bar> { it.time }.thenBy { it.symbol })
        .toList()
}

internal fun loadUniverseSnapshotLiquidity(
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    barMinutes: Int,
    minBars: Int,
    maxSymbols: Int = 0,
    now: Instant = Instant.now()
): List<SymbolLiquiditySnapshot> {
    val snapshot = universeSnapshotCache.getOrLoad(aliases, lookbackHours, barMinutes) ?: return emptyList()
    val cutoff = now.minus(lookbackHours.toLong(), ChronoUnit.HOURS)
    val targetSymbols = if (symbols.isEmpty()) {
        snapshot.barsBySymbol.keys.sorted()
    } else {
        symbols.distinct()
    }
    val ranked = targetSymbols.mapNotNull { symbol ->
        val bars = snapshot.barsBySymbol[symbol].orEmpty().filter { !it.time.isBefore(cutoff) }
        if (bars.size < minBars) {
            null
        } else {
            SymbolLiquiditySnapshot(
                symbol = symbol,
                bars = bars.size,
                avgVolume = mean(bars.map { it.volume })
            )
        }
    }.sortedWith(
        compareByDescending<SymbolLiquiditySnapshot> { it.bars }
            .thenByDescending { it.avgVolume }
            .thenBy { it.symbol }
    )
    return if (maxSymbols > 0) ranked.take(maxSymbols) else ranked
}

fun crossSectionalUniverseSnapshotCacheStatus(): UniverseSnapshotCacheStatus =
    universeSnapshotCache.status()

fun warmCrossSectionalUniverseSnapshots(config: ResearchConfig = ResearchConfig()): UniverseSnapshotCacheStatus {
    val exchangePlans = buildExchangePlans(fetchExchangeCatalog(config.txGatewayUrl), config)
    val sourceBarMinutes = selectResearchCandleSource(config.barMinutes).minutes
    exchangePlans.forEach { plan ->
        universeSnapshotCache.getOrLoad(
            aliases = plan.marketAliases,
            lookbackHours = config.lookbackHours,
            barMinutes = config.barMinutes
        )
        if (sourceBarMinutes != config.barMinutes) {
            universeSnapshotCache.getOrLoad(
                aliases = plan.marketAliases,
                lookbackHours = config.lookbackHours,
                barMinutes = sourceBarMinutes
            )
        }
    }
    return universeSnapshotCache.status()
}
