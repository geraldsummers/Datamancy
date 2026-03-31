package org.datamancy.alphaanalytics

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.RequirementLevel
import org.datamancy.trading.policy.TradingPolicy
import org.datamancy.trading.policy.UniversePolicy
import org.datamancy.trading.policy.UniverseSelectionMode
import org.datamancy.trading.policy.VenuePolicy
import org.datamancy.trading.storage.verifyCanonicalMarketDataDatabase
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.postgresql.ds.PGSimpleDataSource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import javax.sql.DataSource

private const val CANONICAL_RESEARCH_FEATURE_BAR_SECONDS = 60L
private val marketCatalogHttpClient: HttpClient = HttpClient.newBuilder().build()

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
    val minTradeObservedRatioForEligibility: Double,
    val activeRecentWindowHours: Int = 6,
    val recentObservationWindowHours: Int = 24
)

enum class DataHealthStatus {
    HEALTHY,
    DEGRADED,
    CRITICAL,
    IDLE_LIVE,
    INACTIVE
}

enum class DataHealthLivenessClass {
    HEALTHY,
    LIVE_SPARSE,
    LOCAL_STALE,
    INACTIVE
}

data class DataHealthSymbolIssue(
    val exchange: String,
    val symbol: String,
    val status: DataHealthStatus,
    val livenessClass: DataHealthLivenessClass,
    val activeRecent: Boolean,
    val readinessEligible: Boolean,
    val missingRequiredChannels: List<String>,
    val idleButLiveChannels: List<String>,
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
    val eligibleSymbols: Int,
    val idleLiveSymbols: Int,
    val liveSparseSymbols: Int,
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

internal data class DataHealthSymbolRow(
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
    private val policyProvider: () -> TradingPolicy = ActiveTradingPolicy::current,
    private val txGatewayUrlProvider: () -> String = {
        System.getenv("TX_GATEWAY_URL")?.trim()?.takeIf { it.isNotEmpty() } ?: "http://tx-gateway:8080"
    }
) {
    private val database by lazy { Database.connect(dataSource) }

    suspend fun loadSummary(exchange: String? = null, barMinutes: Int = 1): DataHealthSummary = withContext(Dispatchers.IO) {
        require(barMinutes == 1) { "data health currently supports only barMinutes=1" }
        val policy = policyProvider()
        val resolvedExchange = resolveExchange(exchange, policy)
        val thresholds = thresholdsFor(policy, resolvedExchange, barMinutes)
        val rows = loadRows(
            exchange = resolvedExchange,
            authoritativeSymbols = resolveAuthoritativeSymbols(resolvedExchange, policy)
        )
        val activeRows = rows.filter { it.activeRecent }
        val evaluated = activeRows.map { evaluate(it, thresholds) }
        val eligibleRows = evaluated.filter { it.readinessEligible }

        DataHealthSummary(
            exchange = resolvedExchange,
            barMinutes = barMinutes,
            asOf = Instant.now(),
            thresholds = thresholds,
            trackedSymbols = rows.size,
            activeSymbols = activeRows.size,
            eligibleSymbols = eligibleRows.size,
            idleLiveSymbols = evaluated.count { it.status == DataHealthStatus.IDLE_LIVE },
            liveSparseSymbols = evaluated.count { it.livenessClass == DataHealthLivenessClass.LIVE_SPARSE },
            inactiveSymbols = rows.size - activeRows.size,
            healthySymbols = evaluated.count { it.status == DataHealthStatus.HEALTHY },
            degradedSymbols = evaluated.count { it.status == DataHealthStatus.DEGRADED },
            criticalSymbols = evaluated.count { it.status == DataHealthStatus.CRITICAL },
            symbolsMissingRequiredChannels = evaluated.count { it.missingRequiredChannels.isNotEmpty() },
            staleCandleSymbols = evaluated.count { "candle_1m" in it.staleChannels },
            staleFeatureSymbols = evaluated.count {
                (
                    it.featureLagSeconds == null || it.featureLagSeconds > thresholds.featureLagMaxSeconds
                )
            },
            coverageFailSymbols = evaluated.count {
                it.coverageRatio < thresholds.minCoverageRatio
            },
            finalizedFailSymbols = evaluated.count {
                it.finalizedRatio < thresholds.minFinalizedRatio
            },
            executionFailSymbols = evaluated.count { it.hasExecutionHealthFailure(thresholds) },
            avgCoverageRatioActive = evaluated.averageOfOrZero { it.coverageRatio },
            avgFinalizedRatioActive = evaluated.averageOfOrZero { it.finalizedRatio },
            avgRecentExecutionObservedShare24hActive = evaluated.averageOfOrZero { it.recentExecutionObservedShare24h },
            maxCandleLagSecondsActive = evaluated.maxOfOrNull { it.candleRawLagSeconds ?: 0L } ?: 0L,
            maxFeatureLagSecondsActive = evaluated.maxOfOrNull { it.featureLagSeconds ?: 0L } ?: 0L,
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
        val policy = policyProvider()
        val resolvedExchange = resolveExchange(exchange, policy)
        val thresholds = thresholdsFor(policy, resolvedExchange, barMinutes)
        val issues = loadRows(
            exchange = resolvedExchange,
            authoritativeSymbols = resolveAuthoritativeSymbols(resolvedExchange, policy)
        )
            .map { evaluate(it, thresholds) }
            .filter { includeInactive || (it.status != DataHealthStatus.INACTIVE && it.status != DataHealthStatus.IDLE_LIVE) }
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

    suspend fun loadIssue(
        symbol: String,
        exchange: String? = null,
        barMinutes: Int = 1
    ): DataHealthSymbolIssue = withContext(Dispatchers.IO) {
        require(barMinutes == 1) { "data health currently supports only barMinutes=1" }
        val policy = policyProvider()
        val resolvedExchange = resolveExchange(exchange, policy)
        val thresholds = thresholdsFor(policy, resolvedExchange, barMinutes)
        val authoritative = resolveAuthoritativeSymbols(resolvedExchange, policy)
        val canonicalSymbol = symbol.trim().uppercase()
        val row = loadRows(exchange = resolvedExchange, authoritativeSymbols = authoritative)
            .firstOrNull { it.symbol.trim().uppercase() == canonicalSymbol }
            ?: emptyDataHealthSymbolRow(exchange = resolvedExchange, symbol = canonicalSymbol)
        evaluate(row, thresholds)
    }

    private fun resolveExchange(exchange: String?, policy: TradingPolicy): String {
        val configured = exchange?.trim().orEmpty()
        if (configured.isNotEmpty()) return configured
        return policy.research.datasets.marketExchange.ifBlank {
            policy.venues.values.firstOrNull()?.exchangeId ?: error("no trading policy exchanges configured")
        }
    }

    private fun thresholdsFor(policy: TradingPolicy, exchange: String, barMinutes: Int): DataHealthThresholds {
        val venue = policy.venues.values.firstOrNull { it.exchangeId == exchange } ?: policy.venues.values.firstOrNull()
        val rawSync = venue?.rawSync
        val featurePolicy = venue?.features?.freshness
        val signal = policy.research.readiness.signal
        val execution = policy.research.readiness.execution

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
            featureLagMaxSeconds = signal.maxFeatureLagSeconds.takeIf { it > 0 } ?: (featurePolicy?.maxFeatureLagSeconds ?: 180L),
            finalizedLagMaxMinutes = signal.maxFinalizedLagMinutes.takeIf { it > 0 } ?: (featurePolicy?.maxFinalizedLagMinutes ?: 5L),
            minCoverageRatio = signal.minCoverageRatio,
            minFinalizedRatio = signal.minFinalizedRatio,
            minExecutionObservedRatio = execution.minExecutionObservedRatio,
            minUniverseSymbols = signal.minUniverseSymbols,
            minTradeObservedRatioForEligibility = execution.minTradeObservedRatioForEligibility
        )
    }

    private fun resolveAuthoritativeSymbols(exchange: String, policy: TradingPolicy): List<String> =
        resolveAuthoritativeMarketSymbols(
            txBase = txGatewayUrlProvider(),
            exchange = exchange,
            policy = policy
        )

    private fun loadRows(exchange: String, authoritativeSymbols: List<String>): List<DataHealthSymbolRow> {
        val asOf = Instant.now()
        val rowsBySymbol = transaction(database) {
            DataHealthSymbol1mTable
                .selectAll()
                .andWhere { DataHealthSymbol1mTable.exchange eq exchange }
                .orderBy(DataHealthSymbol1mTable.symbol to SortOrder.ASC)
                .map { it.toDataHealthSymbolRow(asOf) }
                .associateBy { it.symbol }
        }
        return authoritativeSymbols.map { symbol ->
            rowsBySymbol[symbol] ?: emptyDataHealthSymbolRow(exchange = exchange, symbol = symbol)
        }
    }

    private fun evaluate(row: DataHealthSymbolRow, thresholds: DataHealthThresholds): DataHealthSymbolIssue =
        evaluateDataHealthRow(row, thresholds)

    private fun statusRank(status: DataHealthStatus): Int = when (status) {
        DataHealthStatus.CRITICAL -> 4
        DataHealthStatus.DEGRADED -> 3
        DataHealthStatus.IDLE_LIVE -> 2
        DataHealthStatus.INACTIVE -> 1
        DataHealthStatus.HEALTHY -> 0
    }

    companion object {
        fun fromEnvironment(policyProvider: () -> TradingPolicy = ActiveTradingPolicy::current): DataHealthService {
            val host = System.getenv("POSTGRES_HOST") ?: "market-postgres"
            val port = (System.getenv("POSTGRES_PORT") ?: "5432").toInt()
            val database = System.getenv("POSTGRES_DB") ?: "datamancy"
            val user = System.getenv("POSTGRES_USER") ?: "pipeline_user"
            val password = System.getenv("POSTGRES_PASSWORD") ?: ""
            val dataSource = PGSimpleDataSource().apply {
                serverNames = arrayOf(host)
                portNumbers = intArrayOf(port)
                databaseName = database
                this.user = user
                this.password = password
            }
            dataSource.connection.use { connection ->
                verifyCanonicalMarketDataDatabase(
                    connection = connection,
                    verificationKey = "alpha-analytics-service:$host:$port/$database:$user",
                    descriptor = "alpha-analytics-service market-data connection $host:$port/$database as $user"
                )
            }
            return DataHealthService(dataSource = dataSource, policyProvider = policyProvider)
        }
    }
}

private data class AuthoritativeExchangeMarket(
    val symbol: String,
    val attributes: Map<String, String> = emptyMap()
)

private fun resolveAuthoritativeMarketSymbols(
    txBase: String,
    exchange: String,
    policy: TradingPolicy
): List<String> {
    val venue = policy.findVenueForExchange(exchange)
        ?: error("No trading policy venue configured for exchange=$exchange")
    return when (venue.universe.selectionMode) {
        UniverseSelectionMode.STATIC -> {
            val symbols = filterSymbolsByUniversePolicy(venue.universe.staticSymbols, venue.universe)
            require(symbols.isNotEmpty()) {
                "Static universe policy resolved no symbols for venue=${venue.venueId} exchange=${venue.exchangeId}"
            }
            symbols
        }

        UniverseSelectionMode.EXCHANGE_CATALOG -> {
            val markets = filterExchangeMarketsByUniversePolicy(
                markets = fetchExchangeMarkets(txBase, venue.venueId),
                universe = venue.universe
            )
            require(markets.isNotEmpty()) {
                "Exchange catalog resolved no markets for venue=${venue.venueId} exchange=${venue.exchangeId}"
            }
            markets.map { it.symbol }
        }
    }
}

private fun TradingPolicy.findVenueForExchange(exchange: String): VenuePolicy? {
    val exchangeKey = exchange.trim().lowercase()
    if (exchangeKey.isEmpty()) return null
    return venues.values.firstOrNull { venue ->
        venue.venueId.trim().lowercase() == exchangeKey || venue.exchangeId.trim().lowercase() == exchangeKey
    }
}

private fun fetchExchangeMarkets(txBase: String, exchange: String): List<AuthoritativeExchangeMarket> =
    runCatching {
        val request = HttpRequest.newBuilder(
            URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges/${urlEncode(exchange)}/markets")
        )
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = marketCatalogHttpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Exchange markets returned status ${response.statusCode()}")
        }
        val payload = JsonParser.parseString(response.body()).asJsonObject
        payload.getAsJsonArray("markets")
            ?.mapNotNull { element ->
                val obj = element.asJsonObject
                val symbol = obj.get("symbol")
                    ?.takeIf { !it.isJsonNull }
                    ?.asString
                    ?.trim()
                    ?.uppercase()
                    ?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val attributes = obj.getAsJsonObject("attributes")
                    ?.entrySet()
                    ?.asSequence()
                    ?.filter { (_, value) -> !value.isJsonNull }
                    ?.associate { (key, value) ->
                        key to if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                            value.asString.trim()
                        } else {
                            value.toString()
                        }
                    }
                    ?.filterValues { it.isNotEmpty() }
                    ?: emptyMap()
                AuthoritativeExchangeMarket(symbol = symbol, attributes = attributes)
            }
            ?.distinctBy { it.symbol }
            ?: emptyList()
    }.getOrElse { ex ->
        error("Failed to load exchange market catalog for $exchange: ${ex.message}")
    }

private fun filterSymbolsByUniversePolicy(
    symbols: Collection<String>,
    universe: UniversePolicy
): List<String> {
    val includeSymbols = universe.includeSymbols.map(::symbolKey).toSet()
    val excludeSymbols = universe.excludeSymbols.map(::symbolKey).toSet()
    return symbols
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .filter { includeSymbols.isEmpty() || symbolKey(it) in includeSymbols }
        .filterNot { symbolKey(it) in excludeSymbols }
        .distinct()
        .sortedWith(compareBy<String> { symbolKey(it) }.thenBy { it })
        .toList()
}

private fun filterExchangeMarketsByUniversePolicy(
    markets: Collection<AuthoritativeExchangeMarket>,
    universe: UniversePolicy
): List<AuthoritativeExchangeMarket> {
    val includeSymbols = universe.includeSymbols.map(::symbolKey).toSet()
    val excludeSymbols = universe.excludeSymbols.map(::symbolKey).toSet()
    return markets
        .asSequence()
        .filter { universe.includeDelisted || !it.isDelisted() }
        .filter { includeSymbols.isEmpty() || symbolKey(it.symbol) in includeSymbols }
        .filterNot { symbolKey(it.symbol) in excludeSymbols }
        .distinctBy { symbolKey(it.symbol) }
        .sortedWith(compareBy<AuthoritativeExchangeMarket> { symbolKey(it.symbol) }.thenBy { it.symbol })
        .toList()
}

private fun AuthoritativeExchangeMarket.isDelisted(): Boolean {
    val raw = attributes["isDelisted"] ?: attributes["delisted"] ?: return false
    return raw.equals("true", ignoreCase = true)
}

private fun symbolKey(symbol: String): String = symbol.trim().uppercase()

private fun urlEncode(value: String): String =
    java.net.URLEncoder.encode(value, Charsets.UTF_8)

internal fun evaluateDataHealthRow(row: DataHealthSymbolRow, thresholds: DataHealthThresholds): DataHealthSymbolIssue {
    if (!row.activeRecent) {
        return DataHealthSymbolIssue(
            exchange = row.exchange,
            symbol = row.symbol,
            status = DataHealthStatus.INACTIVE,
            livenessClass = DataHealthLivenessClass.INACTIVE,
            activeRecent = false,
            readinessEligible = false,
            missingRequiredChannels = emptyList(),
            idleButLiveChannels = emptyList(),
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

    val readinessEligible = row.isReadinessEligible(thresholds)
    val livenessClass = row.livenessClass(thresholds)
    val missingRequiredChannels = thresholds.requiredRawChannels.filter { channel ->
        row.latestRawTime(channel) == null
    }
    val idleButLiveChannels = thresholds.requiredRawChannels.filter { channel ->
        row.isIdleButLiveChannel(channel, thresholds, readinessEligible)
    }
    val staleChannels = thresholds.requiredRawChannels.filter { channel ->
        channel !in idleButLiveChannels &&
            row.rawLagSeconds(channel)?.let { it > thresholdForChannel(channel, thresholds) } == true
    }

    if (!readinessEligible && row.isOrderbookLive(thresholds)) {
        val reasons = buildList {
            add(
                "idle but live: orderbook current, but recent trade observed share " +
                    "${row.recentTradeObservedShare24h.formatRatio()} below " +
                    "${thresholds.minTradeObservedRatioForEligibility.formatRatio()} eligibility threshold"
            )
            idleButLiveChannels.forEach { channel ->
                add("$channel idle but live under current orderbook liveness")
            }
        }
        return DataHealthSymbolIssue(
            exchange = row.exchange,
            symbol = row.symbol,
            status = if (row.isOrderbookLive(thresholds)) DataHealthStatus.IDLE_LIVE else DataHealthStatus.INACTIVE,
            livenessClass = livenessClass,
            activeRecent = true,
            readinessEligible = false,
            missingRequiredChannels = missingRequiredChannels,
            idleButLiveChannels = idleButLiveChannels,
            staleChannels = staleChannels,
            reasons = reasons,
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

    val criticalReasons = mutableListOf<String>()
    val degradedReasons = mutableListOf<String>()

    if (!row.isOrderbookLive(thresholds)) {
        criticalReasons += "execution context is not currently live"
    }
    if ("candle_1m" in missingRequiredChannels) {
        criticalReasons += "missing required raw channel candle_1m"
    }
    if ("candle_1m" in staleChannels && livenessClass != DataHealthLivenessClass.LIVE_SPARSE) {
        criticalReasons += "candle_1m lag ${row.candleRawLagSeconds}s exceeds ${thresholds.candleRawLagMaxSeconds}s"
    }
    if (row.latestFeatureTime == null) {
        criticalReasons += "missing execution_context_1m rows"
    }
    if (row.featureLagSeconds == null || row.featureLagSeconds > thresholds.featureLagMaxSeconds) {
        criticalReasons += "feature lag ${row.featureLagSeconds ?: -1}s exceeds ${thresholds.featureLagMaxSeconds}s"
    }
    if (row.recentFeatureRows24h == 0) {
        criticalReasons += "no recent feature rows in ${thresholds.recentObservationWindowHours}h"
    }

    val degradeEligibleChannels = if (livenessClass == DataHealthLivenessClass.LIVE_SPARSE) {
        setOf("funding", "orderbook_l2", "open_interest")
    } else {
        thresholds.requiredRawChannels.toSet() - "candle_1m"
    }

    missingRequiredChannels
        .filter { it in degradeEligibleChannels }
        .forEach { degradedReasons += "missing required raw channel $it" }
    staleChannels
        .filter { it in degradeEligibleChannels }
        .forEach { degradedReasons += "$it lag ${row.rawLagSeconds(it)}s exceeds ${thresholdForChannel(it, thresholds)}s" }

    if (livenessClass == DataHealthLivenessClass.LIVE_SPARSE) {
        degradedReasons += "live sparse market: orderbook current while trade/candle channels are quiet"
    }

    if (row.coverageRatio < thresholds.minCoverageRatio) {
        degradedReasons +=
            "recent feature coverage ${row.coverageRatio.formatRatio()} below ${thresholds.minCoverageRatio.formatRatio()}"
    }
    if (row.finalizedRatio < thresholds.minFinalizedRatio) {
        degradedReasons +=
            "recent finalized coverage ${row.finalizedRatio.formatRatio()} below ${thresholds.minFinalizedRatio.formatRatio()}"
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
        livenessClass = livenessClass,
        activeRecent = true,
        readinessEligible = readinessEligible,
        missingRequiredChannels = missingRequiredChannels,
        idleButLiveChannels = idleButLiveChannels,
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

private fun thresholdForChannel(channel: String, thresholds: DataHealthThresholds): Long =
    if (channel == "candle_1m") thresholds.candleRawLagMaxSeconds else thresholds.rawStaleAfterSeconds

private fun emptyDataHealthSymbolRow(exchange: String, symbol: String): DataHealthSymbolRow =
    DataHealthSymbolRow(
        exchange = exchange,
        symbol = symbol,
        activeRecent = false,
        latestAnyRawTime = null,
        candleLatestRawTime = null,
        tradeLatestRawTime = null,
        orderbookLatestRawTime = null,
        fundingLatestRawTime = null,
        openInterestLatestRawTime = null,
        candleRawLagSeconds = null,
        tradeRawLagSeconds = null,
        orderbookRawLagSeconds = null,
        fundingRawLagSeconds = null,
        openInterestRawLagSeconds = null,
        latestFeatureTime = null,
        finalizedThrough = null,
        featureLagSeconds = null,
        finalizedLagMinutes = null,
        featureRows = 0L,
        materializerLagSeconds = null,
        coverageRatio = 0.0,
        finalizedRatio = 0.0,
        expectedBars = 0,
        observedBars = 0,
        finalizedBars = 0,
        recentFeatureRows24h = 0,
        recentTradeObservedShare24h = 0.0,
        recentOrderbookObservedShare24h = 0.0,
        recentExecutionObservedShare24h = 0.0,
        recentFinalizedShare24h = 0.0
    )

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

private fun DataHealthSymbolRow.isOrderbookLive(thresholds: DataHealthThresholds): Boolean =
    orderbookRawLagSeconds?.let { it <= thresholds.rawStaleAfterSeconds } == true

private fun DataHealthSymbolRow.isReadinessEligible(thresholds: DataHealthThresholds): Boolean =
    activeRecent &&
        isOrderbookLive(thresholds) &&
        recentTradeObservedShare24h >= thresholds.minTradeObservedRatioForEligibility

private fun DataHealthSymbolRow.isIdleButLiveChannel(
    channel: String,
    thresholds: DataHealthThresholds,
    readinessEligible: Boolean
): Boolean {
    if (readinessEligible) return false
    if (!isOrderbookLive(thresholds)) return false
    if (channel != "trade" && channel != "candle_1m") return false
    return latestRawTime(channel) != null
}

private fun DataHealthSymbolRow.livenessClass(thresholds: DataHealthThresholds): DataHealthLivenessClass {
    if (!activeRecent) return DataHealthLivenessClass.INACTIVE
    if (!isOrderbookLive(thresholds)) return DataHealthLivenessClass.LOCAL_STALE
    val eventDrivenChannels = setOf("trade", "candle_1m")
    val nonEventChannels = setOf("orderbook_l2", "funding", "open_interest")
    val staleNonEventChannel = nonEventChannels.any { channel ->
        rawLagSeconds(channel)?.let { it > thresholdForChannel(channel, thresholds) } == true
    }
    if (staleNonEventChannel) {
        return DataHealthLivenessClass.LOCAL_STALE
    }
    val staleEventDrivenChannels = eventDrivenChannels.filter { channel ->
        rawLagSeconds(channel)?.let { it > thresholdForChannel(channel, thresholds) } == true
    }
    return if (staleEventDrivenChannels.isNotEmpty() || !isReadinessEligible(thresholds)) {
        DataHealthLivenessClass.LIVE_SPARSE
    } else {
        DataHealthLivenessClass.HEALTHY
    }
}

private fun DataHealthSymbolIssue.hasExecutionHealthFailure(thresholds: DataHealthThresholds): Boolean {
    if (livenessClass == DataHealthLivenessClass.LIVE_SPARSE) {
        return false
    }
    if (recentFeatureRows24h > 0 && recentExecutionObservedShare24h < thresholds.minExecutionObservedRatio) {
        return true
    }
    return missingRequiredChannels.any { it != "candle_1m" } ||
        staleChannels.any { it != "candle_1m" } ||
        (!readinessEligible && status != DataHealthStatus.IDLE_LIVE)
}

private fun Iterable<DataHealthSymbolIssue>.averageOfOrZero(selector: (DataHealthSymbolIssue) -> Double): Double {
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
