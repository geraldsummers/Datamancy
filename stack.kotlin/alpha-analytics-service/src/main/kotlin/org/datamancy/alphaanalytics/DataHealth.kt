package org.datamancy.alphaanalytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.RequirementLevel
import org.datamancy.trading.policy.TradingPolicy
import org.postgresql.ds.PGSimpleDataSource
import java.sql.ResultSet
import java.time.Instant
import javax.sql.DataSource

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
        val sql = """
            SELECT
                exchange,
                symbol,
                active_recent,
                latest_any_raw_time,
                candle_1m_latest_raw_time,
                trade_latest_raw_time,
                orderbook_l2_latest_raw_time,
                funding_latest_raw_time,
                open_interest_latest_raw_time,
                candle_1m_raw_lag_seconds,
                trade_raw_lag_seconds,
                orderbook_l2_raw_lag_seconds,
                funding_raw_lag_seconds,
                open_interest_raw_lag_seconds,
                latest_feature_time,
                finalized_through,
                feature_lag_seconds,
                finalized_lag_minutes,
                feature_rows,
                materializer_lag_seconds,
                coverage_ratio,
                finalized_ratio,
                expected_bars,
                observed_bars,
                finalized_bars,
                recent_feature_rows_24h,
                recent_trade_observed_share_24h,
                recent_orderbook_observed_share_24h,
                recent_execution_observed_share_24h,
                recent_finalized_share_24h
            FROM data_health_symbol_1m
            WHERE exchange = ?
            ORDER BY symbol ASC
        """.trimIndent()

        return dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, exchange)
                statement.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.toDataHealthSymbolRow())
                        }
                    }
                }
            }
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

private fun ResultSet.toDataHealthSymbolRow(): DataHealthSymbolRow {
    return DataHealthSymbolRow(
        exchange = getString("exchange"),
        symbol = getString("symbol"),
        activeRecent = getBoolean("active_recent"),
        latestAnyRawTime = getTimestamp("latest_any_raw_time")?.toInstant(),
        candleLatestRawTime = getTimestamp("candle_1m_latest_raw_time")?.toInstant(),
        tradeLatestRawTime = getTimestamp("trade_latest_raw_time")?.toInstant(),
        orderbookLatestRawTime = getTimestamp("orderbook_l2_latest_raw_time")?.toInstant(),
        fundingLatestRawTime = getTimestamp("funding_latest_raw_time")?.toInstant(),
        openInterestLatestRawTime = getTimestamp("open_interest_latest_raw_time")?.toInstant(),
        candleRawLagSeconds = getLongOrNull("candle_1m_raw_lag_seconds"),
        tradeRawLagSeconds = getLongOrNull("trade_raw_lag_seconds"),
        orderbookRawLagSeconds = getLongOrNull("orderbook_l2_raw_lag_seconds"),
        fundingRawLagSeconds = getLongOrNull("funding_raw_lag_seconds"),
        openInterestRawLagSeconds = getLongOrNull("open_interest_raw_lag_seconds"),
        latestFeatureTime = getTimestamp("latest_feature_time")?.toInstant(),
        finalizedThrough = getTimestamp("finalized_through")?.toInstant(),
        featureLagSeconds = getLongOrNull("feature_lag_seconds"),
        finalizedLagMinutes = getDoubleOrNull("finalized_lag_minutes"),
        featureRows = getLong("feature_rows"),
        materializerLagSeconds = getLongOrNull("materializer_lag_seconds"),
        coverageRatio = getDouble("coverage_ratio"),
        finalizedRatio = getDouble("finalized_ratio"),
        expectedBars = getInt("expected_bars"),
        observedBars = getInt("observed_bars"),
        finalizedBars = getInt("finalized_bars"),
        recentFeatureRows24h = getInt("recent_feature_rows_24h"),
        recentTradeObservedShare24h = getDouble("recent_trade_observed_share_24h"),
        recentOrderbookObservedShare24h = getDouble("recent_orderbook_observed_share_24h"),
        recentExecutionObservedShare24h = getDouble("recent_execution_observed_share_24h"),
        recentFinalizedShare24h = getDouble("recent_finalized_share_24h")
    )
}

private fun ResultSet.getLongOrNull(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

private fun ResultSet.getDoubleOrNull(column: String): Double? {
    val value = getDouble(column)
    return if (wasNull()) null else value
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
