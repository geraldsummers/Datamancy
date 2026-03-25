package org.datamancy.alphaanalytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.RequirementLevel
import org.datamancy.trading.policy.TradingPolicy
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.Database
import org.postgresql.ds.PGSimpleDataSource
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

private const val CANONICAL_RESEARCH_FEATURE_BAR_SECONDS = 60L

private object DataHealthSymbol1mTable : Table("data_health_symbol_1m") {
    val exchange = text("exchange")
    val symbol = text("symbol")
    val activeRecent = bool("active_recent")
    val latestAnyRawTime = timestamp("latest_any_raw_time").nullable()
    val candle1mLatestRawTime = timestamp("candle_1m_latest_raw_time").nullable()
    val tradeLatestRawTime = timestamp("trade_latest_raw_time").nullable()
    val orderbookL2LatestRawTime = timestamp("orderbook_l2_latest_raw_time").nullable()
    val fundingLatestRawTime = timestamp("funding_latest_raw_time").nullable()
    val openInterestLatestRawTime = timestamp("open_interest_latest_raw_time").nullable()
    val tradeRawLagSeconds = long("trade_raw_lag_seconds").nullable()
    val orderbookL2RawLagSeconds = long("orderbook_l2_raw_lag_seconds").nullable()
    val fundingRawLagSeconds = long("funding_raw_lag_seconds").nullable()
    val openInterestRawLagSeconds = long("open_interest_raw_lag_seconds").nullable()
    val latestFeatureTime = timestamp("latest_feature_time").nullable()
    val finalizedThrough = timestamp("finalized_through").nullable()
    val materializerLagSeconds = long("materializer_lag_seconds").nullable()
    val coverageRatio = double("coverage_ratio")
    val finalizedRatio = double("finalized_ratio")
    val expectedBars = integer("expected_bars")
    val observedBars = integer("observed_bars")
    val finalizedBars = integer("finalized_bars")
    val featureRows = long("feature_rows")
    val recentFeatureRows24h = integer("recent_feature_rows_24h")
    val recentTradeObservedShare24h = double("recent_trade_observed_share_24h")
    val recentOrderbookObservedShare24h = double("recent_orderbook_observed_share_24h")
    val recentExecutionObservedShare24h = double("recent_execution_observed_share_24h")
    val recentFinalizedShare24h = double("recent_finalized_share_24h")
}

data class DataHealthThresholds(
    val exchange: String,
    val barMinutes: Int,
    val requiredRawChannels: List<String>,
    val rawStaleAfterSeconds: Long,
    val candleRawLagMaxSeconds: Long,
    val featureLagMaxSeconds: Long,
    val finalizedLagMaxMinutes: Long,
    val minCoverageRatio: Double,
    val minFinalizedRatio: Double,
    val minExecutionObservedRatio: Double,
    val minUniverseSymbols: Int,
    val activeRecentWindowHours: Int = 6,
    val recentObservationWindowHours: Int = 24
)

enum class DataHealthStatus {
    HEALTHY,
    DEGRADED,
    CRITICAL,
    INACTIVE
}

data class DataHealthSymbolIssue(
    val exchange: String,
    val symbol: String,
    val status: DataHealthStatus,
    val activeRecent: Boolean,
    val missingRequiredChannels: List<String>,
    val staleChannels: List<String>,
    val reasons: List<String>,
    val latestAnyRawTime: Instant?,
    val candleLatestRawTime: Instant?,
    val tradeLatestRawTime: Instant?,
    val orderbookLatestRawTime: Instant?,
    val fundingLatestRawTime: Instant?,
    val latestFeatureTime: Instant?,
    val finalizedThrough: Instant?,
    val candleRawLagSeconds: Long?,
    val tradeRawLagSeconds: Long?,
    val orderbookRawLagSeconds: Long?,
    val fundingRawLagSeconds: Long?,
    val featureLagSeconds: Long?,
    val finalizedLagMinutes: Double?,
    val materializerLagSeconds: Long?,
    val coverageRatio: Double,
    val finalizedRatio: Double,
    val recentExecutionObservedShare24h: Double,
    val recentFeatureRows24h: Int
)

data class DataHealthSummary(
    val exchange: String,
    val barMinutes: Int,
    val asOf: Instant,
    val thresholds: DataHealthThresholds,
    val trackedSymbols: Int,
    val activeSymbols: Int,
    val inactiveSymbols: Int,
    val healthySymbols: Int,
    val degradedSymbols: Int,
    val criticalSymbols: Int,
    val symbolsMissingRequiredChannels: Int,
    val staleCandleSymbols: Int,
    val staleFeatureSymbols: Int,
    val coverageFailSymbols: Int,
    val finalizedFailSymbols: Int,
    val executionFailSymbols: Int,
    val avgCoverageRatioActive: Double,
    val avgFinalizedRatioActive: Double,
    val avgRecentExecutionObservedShare24hActive: Double,
    val maxCandleLagSecondsActive: Long,
    val maxFeatureLagSecondsActive: Long,
    val criticalSample: List<String>
)

data class DataHealthIssuesResponse(
    val exchange: String,
    val barMinutes: Int,
    val asOf: Instant,
    val thresholds: DataHealthThresholds,
    val totalIssues: Int,
    val issues: List<DataHealthSymbolIssue>
)

private data class DataHealthSymbolRow(
    val exchange: String,
    val symbol: String,
    val activeRecent: Boolean,
    val latestAnyRawTime: Instant?,
    val candleLatestRawTime: Instant?,
    val tradeLatestRawTime: Instant?,
    val orderbookLatestRawTime: Instant?,
    val fundingLatestRawTime: Instant?,
    val openInterestLatestRawTime: Instant?,
    val candleRawLagSeconds: Long?,
    val tradeRawLagSeconds: Long?,
    val orderbookRawLagSeconds: Long?,
    val fundingRawLagSeconds: Long?,
    val openInterestRawLagSeconds: Long?,
    val latestFeatureTime: Instant?,
    val finalizedThrough: Instant?,
    val featureLagSeconds: Long?,
    val finalizedLagMinutes: Double?,
    val featureRows: Long,
    val materializerLagSeconds: Long?,
    val coverageRatio: Double,
    val finalizedRatio: Double,
    val expectedBars: Int,
    val observedBars: Int,
    val finalizedBars: Int,
    val recentFeatureRows24h: Int,
    val recentTradeObservedShare24h: Double,
    val recentOrderbookObservedShare24h: Double,
    val recentExecutionObservedShare24h: Double,
    val recentFinalizedShare24h: Double
)

class DataHealthService(
    private val dataSource: DataSource,
    private val policyProvider: () -> TradingPolicy = ActiveTradingPolicy::current
) {
    private val database by lazy { Database.connect(dataSource) }

    suspend fun loadSummary(exchange: String? = null, barMinutes: Int = 1): DataHealthSummary = withContext(Dispatchers.IO) {
        require(barMinutes == 1) { "data health currently supports only barMinutes=1" }
        val resolvedExchange = resolveExchange(exchange)
        val thresholds = thresholdsFor(resolvedExchange, barMinutes)
        val rows = loadRows(resolvedExchange)
        val activeRows = rows.filter { it.activeRecent }
        val evaluated = activeRows.map { evaluate(it, thresholds) }

        DataHealthSummary(
            exchange = resolvedExchange,
            barMinutes = barMinutes,
            asOf = Instant.now(),
            thresholds = thresholds,
            trackedSymbols = rows.size,
            activeSymbols = activeRows.size,
            inactiveSymbols = rows.size - activeRows.size,
            healthySymbols = evaluated.count { it.status == DataHealthStatus.HEALTHY },
            degradedSymbols = evaluated.count { it.status == DataHealthStatus.DEGRADED },
            criticalSymbols = evaluated.count { it.status == DataHealthStatus.CRITICAL },
            symbolsMissingRequiredChannels = evaluated.count { it.missingRequiredChannels.isNotEmpty() },
            staleCandleSymbols = evaluated.count { "candle_1m" in it.staleChannels },
            staleFeatureSymbols = evaluated.count {
                it.status != DataHealthStatus.INACTIVE && (
                    it.featureLagSeconds == null || it.featureLagSeconds > thresholds.featureLagMaxSeconds
                )
            },
            coverageFailSymbols = evaluated.count {
                it.status != DataHealthStatus.INACTIVE && it.coverageRatio < thresholds.minCoverageRatio
            },
            finalizedFailSymbols = evaluated.count {
                it.status != DataHealthStatus.INACTIVE && it.finalizedRatio < thresholds.minFinalizedRatio
            },
            executionFailSymbols = evaluated.count {
                it.status != DataHealthStatus.INACTIVE &&
                    it.recentFeatureRows24h > 0 &&
                    it.recentExecutionObservedShare24h < thresholds.minExecutionObservedRatio
            },
            avgCoverageRatioActive = activeRows.averageOfOrZero { it.coverageRatio },
            avgFinalizedRatioActive = activeRows.averageOfOrZero { it.finalizedRatio },
            avgRecentExecutionObservedShare24hActive = activeRows.averageOfOrZero { it.recentExecutionObservedShare24h },
            maxCandleLagSecondsActive = activeRows.maxOfOrNull { it.candleRawLagSeconds ?: 0L } ?: 0L,
            maxFeatureLagSecondsActive = activeRows.maxOfOrNull { it.featureLagSeconds ?: 0L } ?: 0L,
            criticalSample = evaluated
                .filter { it.status == DataHealthStatus.CRITICAL }
                .sortedByDescending { it.candleRawLagSeconds ?: Long.MAX_VALUE }
                .take(12)
                .map { it.symbol }
        )
    }

    suspend fun loadIssues(
        exchange: String? = null,
        barMinutes: Int = 1,
        limit: Int = 50,
        includeInactive: Boolean = false,
        includeHealthy: Boolean = false
    ): DataHealthIssuesResponse = withContext(Dispatchers.IO) {
        require(barMinutes == 1) { "data health currently supports only barMinutes=1" }
        val resolvedExchange = resolveExchange(exchange)
        val thresholds = thresholdsFor(resolvedExchange, barMinutes)
        val issues = loadRows(resolvedExchange)
            .map { evaluate(it, thresholds) }
            .filter { includeInactive || it.status != DataHealthStatus.INACTIVE }
            .filter { includeHealthy || it.status != DataHealthStatus.HEALTHY }
            .sortedWith(
                compareByDescending<DataHealthSymbolIssue> { statusRank(it.status) }
                    .thenByDescending { it.candleRawLagSeconds ?: -1L }
                    .thenByDescending { it.featureLagSeconds ?: -1L }
                    .thenBy { it.symbol }
            )

        DataHealthIssuesResponse(
            exchange = resolvedExchange,
            barMinutes = barMinutes,
            asOf = Instant.now(),
            thresholds = thresholds,
            totalIssues = issues.size,
            issues = issues.take(limit.coerceIn(1, 500))
        )
    }

    private fun resolveExchange(exchange: String?): String {
        val policy = policyProvider()
        val configured = exchange?.trim().orEmpty()
        if (configured.isNotEmpty()) return configured
        return policy.research.crossSectional.marketExchange.ifBlank {
            policy.venues.values.firstOrNull()?.exchangeId ?: error("no trading policy exchanges configured")
        }
    }

    private fun thresholdsFor(exchange: String, barMinutes: Int): DataHealthThresholds {
        val policy = policyProvider()
        val venue = policy.venues.values.firstOrNull { it.exchangeId == exchange } ?: policy.venues.values.firstOrNull()
        val rawSync = venue?.rawSync
        val featurePolicy = venue?.features?.freshness
        val coverage = policy.research.crossSectional.coverage

        return DataHealthThresholds(
            exchange = exchange,
            barMinutes = barMinutes,
            requiredRawChannels = rawSync?.channels
                ?.filterValues { it == RequirementLevel.REQUIRED }
                ?.keys
                ?.sorted()
                .orEmpty(),
            rawStaleAfterSeconds = ((rawSync?.staleAfterMs ?: 120_000L) / 1_000L).coerceAtLeast(1L),
            candleRawLagMaxSeconds = featurePolicy?.maxRawLagSeconds ?: 90L,
            featureLagMaxSeconds = featurePolicy?.maxFeatureLagSeconds ?: 180L,
            finalizedLagMaxMinutes = featurePolicy?.maxFinalizedLagMinutes ?: 5L,
            minCoverageRatio = coverage.minCoverageRatio,
            minFinalizedRatio = coverage.minFinalizedRatio,
            minExecutionObservedRatio = coverage.minExecutionObservedRatio,
            minUniverseSymbols = coverage.minUniverseSymbols
        )
    }

    private fun loadRows(exchange: String): List<DataHealthSymbolRow> {
        val asOf = Instant.now()
        return transaction(database) {
            DataHealthSymbol1mTable
                .selectAll()
                .andWhere { DataHealthSymbol1mTable.exchange eq exchange }
                .orderBy(DataHealthSymbol1mTable.symbol to SortOrder.ASC)
                .map { it.toDataHealthSymbolRow(asOf) }
        }
    }

    private fun evaluate(row: DataHealthSymbolRow, thresholds: DataHealthThresholds): DataHealthSymbolIssue {
        if (!row.activeRecent) {
            return DataHealthSymbolIssue(
                exchange = row.exchange,
                symbol = row.symbol,
                status = DataHealthStatus.INACTIVE,
                activeRecent = false,
                missingRequiredChannels = emptyList(),
                staleChannels = emptyList(),
                reasons = listOf("no recent raw activity inside ${thresholds.activeRecentWindowHours}h window"),
                latestAnyRawTime = row.latestAnyRawTime,
                candleLatestRawTime = row.candleLatestRawTime,
                tradeLatestRawTime = row.tradeLatestRawTime,
                orderbookLatestRawTime = row.orderbookLatestRawTime,
                fundingLatestRawTime = row.fundingLatestRawTime,
                latestFeatureTime = row.latestFeatureTime,
                finalizedThrough = row.finalizedThrough,
                candleRawLagSeconds = row.candleRawLagSeconds,
                tradeRawLagSeconds = row.tradeRawLagSeconds,
                orderbookRawLagSeconds = row.orderbookRawLagSeconds,
                fundingRawLagSeconds = row.fundingRawLagSeconds,
                featureLagSeconds = row.featureLagSeconds,
                finalizedLagMinutes = row.finalizedLagMinutes,
                materializerLagSeconds = row.materializerLagSeconds,
                coverageRatio = row.coverageRatio,
                finalizedRatio = row.finalizedRatio,
                recentExecutionObservedShare24h = row.recentExecutionObservedShare24h,
                recentFeatureRows24h = row.recentFeatureRows24h
            )
        }

        val missingRequiredChannels = thresholds.requiredRawChannels.filter { channel ->
            row.latestRawTime(channel) == null
        }
        val staleChannels = thresholds.requiredRawChannels.filter { channel ->
            row.rawLagSeconds(channel)?.let { it > thresholdForChannel(channel, thresholds) } == true
        }

        val criticalReasons = mutableListOf<String>()
        val degradedReasons = mutableListOf<String>()

        if ("candle_1m" in missingRequiredChannels) {
            criticalReasons += "missing required raw channel candle_1m"
        }
        if ("candle_1m" in staleChannels) {
            criticalReasons += "candle_1m lag ${row.candleRawLagSeconds}s exceeds ${thresholds.candleRawLagMaxSeconds}s"
        }
        if (row.latestFeatureTime == null) {
            criticalReasons += "missing research_features_1m rows"
        }
        if (row.featureLagSeconds == null || row.featureLagSeconds > thresholds.featureLagMaxSeconds) {
            criticalReasons += "feature lag ${row.featureLagSeconds ?: -1}s exceeds ${thresholds.featureLagMaxSeconds}s"
        }
        if (row.recentFeatureRows24h == 0) {
            criticalReasons += "no recent feature rows in ${thresholds.recentObservationWindowHours}h"
        }

        missingRequiredChannels
            .filterNot { it == "candle_1m" }
            .forEach { degradedReasons += "missing required raw channel $it" }
        staleChannels
            .filterNot { it == "candle_1m" }
            .forEach { degradedReasons += "$it lag ${row.rawLagSeconds(it)}s exceeds ${thresholdForChannel(it, thresholds)}s" }

        if (row.coverageRatio < thresholds.minCoverageRatio) {
            degradedReasons += "coverage ${row.coverageRatio.formatRatio()} below ${thresholds.minCoverageRatio.formatRatio()}"
        }
        if (row.finalizedRatio < thresholds.minFinalizedRatio) {
            degradedReasons += "finalized coverage ${row.finalizedRatio.formatRatio()} below ${thresholds.minFinalizedRatio.formatRatio()}"
        }
        if (row.finalizedLagMinutes == null || row.finalizedLagMinutes > thresholds.finalizedLagMaxMinutes) {
            degradedReasons += "finalized lag ${row.finalizedLagMinutes?.formatMinutes() ?: "n/a"} exceeds ${thresholds.finalizedLagMaxMinutes}m"
        }
        if (row.recentFeatureRows24h > 0 && row.recentExecutionObservedShare24h < thresholds.minExecutionObservedRatio) {
            degradedReasons +=
                "recent execution observed ${row.recentExecutionObservedShare24h.formatRatio()} below ${thresholds.minExecutionObservedRatio.formatRatio()}"
        }
        if (row.materializerLagSeconds != null && row.materializerLagSeconds > thresholds.featureLagMaxSeconds) {
            degradedReasons += "materializer lag ${row.materializerLagSeconds}s exceeds ${thresholds.featureLagMaxSeconds}s"
        }

        val status = when {
            criticalReasons.isNotEmpty() -> DataHealthStatus.CRITICAL
            degradedReasons.isNotEmpty() -> DataHealthStatus.DEGRADED
            else -> DataHealthStatus.HEALTHY
        }

        return DataHealthSymbolIssue(
            exchange = row.exchange,
            symbol = row.symbol,
            status = status,
            activeRecent = true,
            missingRequiredChannels = missingRequiredChannels,
            staleChannels = staleChannels,
            reasons = criticalReasons + degradedReasons,
            latestAnyRawTime = row.latestAnyRawTime,
            candleLatestRawTime = row.candleLatestRawTime,
            tradeLatestRawTime = row.tradeLatestRawTime,
            orderbookLatestRawTime = row.orderbookLatestRawTime,
            fundingLatestRawTime = row.fundingLatestRawTime,
            latestFeatureTime = row.latestFeatureTime,
            finalizedThrough = row.finalizedThrough,
            candleRawLagSeconds = row.candleRawLagSeconds,
            tradeRawLagSeconds = row.tradeRawLagSeconds,
            orderbookRawLagSeconds = row.orderbookRawLagSeconds,
            fundingRawLagSeconds = row.fundingRawLagSeconds,
            featureLagSeconds = row.featureLagSeconds,
            finalizedLagMinutes = row.finalizedLagMinutes,
            materializerLagSeconds = row.materializerLagSeconds,
            coverageRatio = row.coverageRatio,
            finalizedRatio = row.finalizedRatio,
            recentExecutionObservedShare24h = row.recentExecutionObservedShare24h,
            recentFeatureRows24h = row.recentFeatureRows24h
        )
    }

    private fun thresholdForChannel(channel: String, thresholds: DataHealthThresholds): Long {
        return if (channel == "candle_1m") thresholds.candleRawLagMaxSeconds else thresholds.rawStaleAfterSeconds
    }

    private fun statusRank(status: DataHealthStatus): Int = when (status) {
        DataHealthStatus.CRITICAL -> 3
        DataHealthStatus.DEGRADED -> 2
        DataHealthStatus.HEALTHY -> 1
        DataHealthStatus.INACTIVE -> 0
    }

    companion object {
        fun fromEnvironment(policyProvider: () -> TradingPolicy = ActiveTradingPolicy::current): DataHealthService {
            val dataSource = PGSimpleDataSource().apply {
                serverNames = arrayOf(System.getenv("POSTGRES_HOST") ?: "postgres")
                portNumbers = intArrayOf((System.getenv("POSTGRES_PORT") ?: "5432").toInt())
                databaseName = System.getenv("POSTGRES_DB") ?: "datamancy"
                user = System.getenv("POSTGRES_USER") ?: "pipeline_user"
                password = System.getenv("POSTGRES_PASSWORD") ?: ""
            }
            return DataHealthService(dataSource = dataSource, policyProvider = policyProvider)
        }
    }
}

private fun effectiveBarCloseLagSeconds(
    bucketStartTime: Instant?,
    referenceTime: Instant,
    bucketSeconds: Long = CANONICAL_RESEARCH_FEATURE_BAR_SECONDS
): Long? =
    bucketStartTime
        ?.plusSeconds(bucketSeconds.coerceAtLeast(1L))
        ?.let { bucketClose ->
            Duration.between(bucketClose, referenceTime).seconds.coerceAtLeast(0L)
        }

private fun effectiveBarCloseLagMinutes(
    bucketStartTime: Instant?,
    referenceTime: Instant,
    bucketSeconds: Long = CANONICAL_RESEARCH_FEATURE_BAR_SECONDS
): Double? =
    bucketStartTime
        ?.plusSeconds(bucketSeconds.coerceAtLeast(1L))
        ?.let { bucketClose ->
            Duration.between(bucketClose, referenceTime).toMillis().coerceAtLeast(0L) / 60_000.0
        }

private fun ResultRow.toDataHealthSymbolRow(referenceTime: Instant): DataHealthSymbolRow {
    val candleLatestRawTime = this[DataHealthSymbol1mTable.candle1mLatestRawTime]
    val latestFeatureTime = this[DataHealthSymbol1mTable.latestFeatureTime]
    val finalizedThrough = this[DataHealthSymbol1mTable.finalizedThrough]
    return DataHealthSymbolRow(
        exchange = this[DataHealthSymbol1mTable.exchange],
        symbol = this[DataHealthSymbol1mTable.symbol],
        activeRecent = this[DataHealthSymbol1mTable.activeRecent],
        latestAnyRawTime = this[DataHealthSymbol1mTable.latestAnyRawTime],
        candleLatestRawTime = candleLatestRawTime,
        tradeLatestRawTime = this[DataHealthSymbol1mTable.tradeLatestRawTime],
        orderbookLatestRawTime = this[DataHealthSymbol1mTable.orderbookL2LatestRawTime],
        fundingLatestRawTime = this[DataHealthSymbol1mTable.fundingLatestRawTime],
        openInterestLatestRawTime = this[DataHealthSymbol1mTable.openInterestLatestRawTime],
        candleRawLagSeconds = effectiveBarCloseLagSeconds(candleLatestRawTime, referenceTime),
        tradeRawLagSeconds = this[DataHealthSymbol1mTable.tradeRawLagSeconds],
        orderbookRawLagSeconds = this[DataHealthSymbol1mTable.orderbookL2RawLagSeconds],
        fundingRawLagSeconds = this[DataHealthSymbol1mTable.fundingRawLagSeconds],
        openInterestRawLagSeconds = this[DataHealthSymbol1mTable.openInterestRawLagSeconds],
        latestFeatureTime = latestFeatureTime,
        finalizedThrough = finalizedThrough,
        featureLagSeconds = effectiveBarCloseLagSeconds(latestFeatureTime, referenceTime),
        finalizedLagMinutes = effectiveBarCloseLagMinutes(finalizedThrough, referenceTime),
        featureRows = this[DataHealthSymbol1mTable.featureRows],
        materializerLagSeconds = this[DataHealthSymbol1mTable.materializerLagSeconds],
        coverageRatio = this[DataHealthSymbol1mTable.coverageRatio],
        finalizedRatio = this[DataHealthSymbol1mTable.finalizedRatio],
        expectedBars = this[DataHealthSymbol1mTable.expectedBars],
        observedBars = this[DataHealthSymbol1mTable.observedBars],
        finalizedBars = this[DataHealthSymbol1mTable.finalizedBars],
        recentFeatureRows24h = this[DataHealthSymbol1mTable.recentFeatureRows24h],
        recentTradeObservedShare24h = this[DataHealthSymbol1mTable.recentTradeObservedShare24h],
        recentOrderbookObservedShare24h = this[DataHealthSymbol1mTable.recentOrderbookObservedShare24h],
        recentExecutionObservedShare24h = this[DataHealthSymbol1mTable.recentExecutionObservedShare24h],
        recentFinalizedShare24h = this[DataHealthSymbol1mTable.recentFinalizedShare24h]
    )
}

private fun DataHealthSymbolRow.latestRawTime(channel: String): Instant? = when (channel) {
    "candle_1m" -> candleLatestRawTime
    "trade" -> tradeLatestRawTime
    "orderbook_l2" -> orderbookLatestRawTime
    "funding" -> fundingLatestRawTime
    "open_interest" -> openInterestLatestRawTime
    else -> null
}

private fun DataHealthSymbolRow.rawLagSeconds(channel: String): Long? = when (channel) {
    "candle_1m" -> candleRawLagSeconds
    "trade" -> tradeRawLagSeconds
    "orderbook_l2" -> orderbookRawLagSeconds
    "funding" -> fundingRawLagSeconds
    "open_interest" -> openInterestRawLagSeconds
    else -> null
}

private fun Iterable<DataHealthSymbolRow>.averageOfOrZero(selector: (DataHealthSymbolRow) -> Double): Double {
    var count = 0
    var total = 0.0
    for (row in this) {
        total += selector(row)
        count += 1
    }
    return if (count == 0) 0.0 else total / count.toDouble()
}

private fun Double.formatRatio(): String = String.format("%.3f", this)

private fun Double.formatMinutes(): String = String.format("%.1f", this)
