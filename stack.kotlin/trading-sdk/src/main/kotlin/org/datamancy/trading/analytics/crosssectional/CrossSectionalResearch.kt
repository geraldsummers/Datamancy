package org.datamancy.trading.analytics.crosssectional

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.CoverageContractPolicy
import org.datamancy.trading.policy.TradingPolicy
import org.datamancy.trading.policy.UniversePolicy
import org.datamancy.trading.policy.UniverseSelectionMode
import org.datamancy.trading.policy.VenuePolicy
import org.datamancy.trading.storage.verifyCanonicalMarketDataDatabase
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

fun env(name: String, default: String = ""): String =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: default

fun envInt(name: String, default: Int): Int =
    env(name, default.toString()).toIntOrNull() ?: default

fun envDouble(name: String, default: Double): Double =
    env(name, default.toString()).toDoubleOrNull() ?: default

fun envBoolean(name: String, default: Boolean): Boolean {
    val raw = env(name, if (default) "true" else "false").lowercase()
    return raw in setOf("1", "true", "yes", "on")
}

private const val CANONICAL_RESEARCH_FEATURE_BAR_SECONDS = 60L
private const val DEFAULT_RESEARCH_QUERY_PARALLELISM = 4


fun clamp(value: Double, lower: Double, upper: Double): Double =
    max(lower, min(upper, value))

fun direction(value: Double): Double =
    when {
        value > 1e-9 -> 1.0
        value < -1e-9 -> -1.0
        else -> 0.0
    }

fun Double.round(decimals: Int = 4): Double {
    val scale = 10.0.pow(decimals.toDouble())
    return round(this * scale) / scale
}

fun sqlList(values: List<String>): String =
    values.joinToString(",") { "'" + it.replace("'", "''") + "'" }

fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

internal fun resolveResearchQueryParallelism(
    workItems: Int,
    configuredMax: Int = envInt("ALPHA_RESEARCH_QUERY_PARALLELISM", DEFAULT_RESEARCH_QUERY_PARALLELISM)
): Int =
    workItems.coerceAtLeast(1).coerceAtMost(configuredMax.coerceAtLeast(1))

internal fun <T, R> parallelMapBlocking(
    items: List<T>,
    maxParallelism: Int,
    block: (T) -> R
): List<R> {
    if (items.isEmpty()) return emptyList()
    val parallelism = maxParallelism.coerceIn(1, items.size)
    if (parallelism == 1) {
        return items.map(block)
    }

    return runBlocking {
        val semaphore = Semaphore(parallelism)
        coroutineScope {
            items.map { item ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        block(item)
                    }
                }
            }.awaitAll()
        }
    }
}

fun mean(values: List<Double>): Double =
    if (values.isEmpty()) 0.0 else values.average()

fun percentile(values: List<Double>, quantile: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val position = clamp(quantile, 0.0, 1.0) * (sorted.lastIndex.toDouble())
    val lower = position.toInt()
    val upper = ceil(position).toInt()
    if (lower == upper) return sorted[lower]
    val weight = position - lower.toDouble()
    return (sorted[lower] * (1.0 - weight)) + (sorted[upper] * weight)
}

fun median(values: List<Double>): Double = percentile(values, 0.5)

fun medianAbsoluteDeviation(values: List<Double>, center: Double = median(values)): Double {
    if (values.isEmpty()) return 0.0
    return median(values.map { abs(it - center) })
}

fun robustZScore(value: Double, values: List<Double>, fallbackScale: Double = 1.0): Double {
    if (values.isEmpty()) return 0.0
    val center = median(values)
    val mad = medianAbsoluteDeviation(values, center)
    val scale = max(mad * 1.4826, fallbackScale)
    return clamp((value - center) / scale, -6.0, 6.0)
}

fun stdev(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mu = mean(values)
    val variance = values.sumOf { (it - mu).pow(2.0) } / values.size.toDouble()
    return sqrt(max(variance, 0.0))
}

fun rollingSum(values: List<Double>, endIndex: Int, window: Int): Double {
    if (values.isEmpty() || endIndex < 0) return 0.0
    val start = max(0, endIndex - window + 1)
    return values.subList(start, endIndex + 1).sum()
}

fun rollingMean(values: List<Double>, endIndex: Int, window: Int): Double {
    if (values.isEmpty() || endIndex < 0) return 0.0
    val start = max(0, endIndex - window + 1)
    return mean(values.subList(start, endIndex + 1))
}

fun deterministicJitter(time: Instant, salt: Int): Double {
    val bucket = (time.epochSecond / 60L) + salt.toLong() * 13L
    return abs((bucket % 37L) - 18L).toDouble()
}

fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString

fun JsonObject.bool(name: String): Boolean? =
    get(name)?.takeIf { !it.isJsonNull }?.asBoolean

fun JsonObject.double(name: String): Double? =
    get(name)?.takeIf { !it.isJsonNull }?.asDouble

fun JsonObject.obj(name: String): JsonObject? =
    getAsJsonObject(name)

fun JsonObject.array(name: String): JsonArray? =
    getAsJsonArray(name)

internal fun ResultSet.doubleOrNull(name: String): Double? {
    val value = getDouble(name)
    return if (wasNull()) null else value
}


val gson: Gson = GsonBuilder().setPrettyPrinting().create()
val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

private val postgresDriverLoaded: Boolean = run {
    Class.forName("org.postgresql.Driver")
    true
}
val pgHost = env("POSTGRES_HOST", "market-postgres")
val pgPort = env("POSTGRES_PORT", "5432")
val pgDb = env("POSTGRES_DB", "datamancy")
val pgUser = env("POSTGRES_USER", "pipeline_user")
val pgPassword = env("POSTGRES_PASSWORD", "")
val jdbcUrl = "jdbc:postgresql://$pgHost:$pgPort/$pgDb"

fun pgConnection(): Connection =
    DriverManager.getConnection(jdbcUrl, pgUser, pgPassword)
        .also { connection ->
            verifyCanonicalMarketDataDatabase(
                connection = connection,
                verificationKey = "trading-sdk:$jdbcUrl:$pgUser",
                descriptor = "trading-sdk market-data connection $jdbcUrl as $pgUser"
            )
        }

inline fun <T> timedMillis(block: () -> T): Pair<T, Long> {
    val started = System.nanoTime()
    val result = block()
    val elapsedMs = (System.nanoTime() - started) / 1_000_000
    return result to elapsedMs
}

fun twoFactorBetas(window: List<Triple<Double, Double, Double>>): Pair<Double, Double> {
    if (window.size < 30) return 0.0 to 0.0
    val yMean = mean(window.map { it.first })
    val x1Mean = mean(window.map { it.second })
    val x2Mean = mean(window.map { it.third })

    var s11 = 0.0
    var s22 = 0.0
    var s12 = 0.0
    var sy1 = 0.0
    var sy2 = 0.0

    for ((yRaw, x1Raw, x2Raw) in window) {
        val y = yRaw - yMean
        val x1 = x1Raw - x1Mean
        val x2 = x2Raw - x2Mean
        s11 += x1 * x1
        s22 += x2 * x2
        s12 += x1 * x2
        sy1 += y * x1
        sy2 += y * x2
    }

    val determinant = (s11 * s22) - (s12 * s12)
    if (abs(determinant) < 1e-9) {
        val beta1 = if (abs(s11) < 1e-9) 0.0 else sy1 / s11
        val beta2 = if (abs(s22) < 1e-9) 0.0 else sy2 / s22
        return beta1 to beta2
    }

    val betaBtc = ((sy1 * s22) - (sy2 * s12)) / determinant
    val betaEth = ((sy2 * s11) - (sy1 * s12)) / determinant
    return betaBtc to betaEth
}

fun fetchExchangeCatalog(txBase: String): List<ExchangeCatalogSnapshot> =
    runCatching {
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges"))
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Exchange catalog returned status ${response.statusCode()}")
        }
        val payload = JsonParser.parseString(response.body()).asJsonObject
        payload.array("exchanges")
            ?.map { element ->
                val obj = element.asJsonObject
                val caps = obj.obj("capabilities") ?: JsonObject()
                ExchangeCatalogSnapshot(
                    apiName = obj.string("apiName") ?: obj.string("name") ?: "unknown",
                    implementationStatus = (obj.string("implementationStatus") ?: "placeholder").uppercase(),
                    defaultExecutionMode = obj.string("defaultExecutionMode") ?: "forward_paper",
                    supportedExecutionModes = obj.array("supportedExecutionModes")
                        ?.map { it.asString }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList(),
                    capabilities = ExchangeCapabilitiesSnapshot(
                        paperOrder = caps.bool("paperOrder") ?: false,
                        liveOrder = caps.bool("liveOrder") ?: false,
                        nativeOrderAdapter = caps.bool("nativeOrderAdapter") ?: false,
                        marketDataIngress = caps.bool("marketDataIngress") ?: false,
                        bestQuoteDefault = caps.bool("bestQuoteDefault") ?: false
                    ),
                    notes = obj.string("notes") ?: ""
                )
            }
            ?: emptyList()
    }.getOrElse { ex ->
        println("Exchange catalog unavailable, falling back to Hyperliquid only: ${ex.message}")
        listOf(
            ExchangeCatalogSnapshot(
                apiName = "hyperliquid",
                implementationStatus = "INTEGRATED",
                defaultExecutionMode = "forward_paper",
                supportedExecutionModes = listOf("backtest", "forward_paper", "testnet_live"),
                capabilities = ExchangeCapabilitiesSnapshot(
                    paperOrder = true,
                    liveOrder = true,
                    nativeOrderAdapter = true,
                    marketDataIngress = true,
                    bestQuoteDefault = true
                ),
                notes = "Fallback exchange selection"
            )
        )
    }

fun fetchExchangeMarkets(txBase: String, exchange: String): List<ExchangeMarketSnapshot> =
    runCatching {
        val request = HttpRequest.newBuilder(
            URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges/${urlEncode(exchange)}/markets")
        )
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Exchange markets returned status ${response.statusCode()}")
        }
        val payload = JsonParser.parseString(response.body()).asJsonObject
        payload.array("markets")
            ?.mapNotNull { element ->
                val obj = element.asJsonObject
                val symbol = obj.string("symbol")?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val attributes = obj.obj("attributes")
                    ?.entrySet()
                    ?.asSequence()
                    ?.filter { (_, value) -> !value.isJsonNull }
                    ?.associate { (key, value) -> key to value.toMarketAttributeValue() }
                    ?.filterValues { it.isNotEmpty() }
                    ?: emptyMap()
                ExchangeMarketSnapshot(symbol = symbol, attributes = attributes)
            }
            ?.distinctBy { it.symbol }
            ?: emptyList()
    }.getOrElse { ex ->
        println("Exchange market catalog unavailable for $exchange: ${ex.message}")
        emptyList()
    }

private fun com.google.gson.JsonElement.toMarketAttributeValue(): String =
    when {
        isJsonNull -> ""
        isJsonPrimitive && asJsonPrimitive.isString -> asString.trim()
        else -> toString()
    }

private fun symbolKey(symbol: String): String = symbol.trim().uppercase()

fun ExchangeMarketSnapshot.isDelisted(): Boolean {
    val raw = attributes["isDelisted"] ?: attributes["delisted"] ?: return false
    return raw.equals("true", ignoreCase = true)
}

fun TradingPolicy.findVenueForExchange(exchange: String, aliases: Collection<String> = emptyList()): VenuePolicy? {
    val keys = buildSet {
        add(exchange.trim().lowercase())
        aliases.mapTo(this) { it.trim().lowercase() }
    }.filter { it.isNotEmpty() }.toSet()
    return venues.values.firstOrNull { venue ->
        venue.venueId.trim().lowercase() in keys || venue.exchangeId.trim().lowercase() in keys
    }
}

fun filterSymbolsByUniversePolicy(
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

fun filterExchangeMarketsByUniversePolicy(
    markets: Collection<ExchangeMarketSnapshot>,
    universe: UniversePolicy
): List<ExchangeMarketSnapshot> {
    val includeSymbols = universe.includeSymbols.map(::symbolKey).toSet()
    val excludeSymbols = universe.excludeSymbols.map(::symbolKey).toSet()
    return markets
        .asSequence()
        .filter { universe.includeDelisted || !it.isDelisted() }
        .filter { includeSymbols.isEmpty() || symbolKey(it.symbol) in includeSymbols }
        .filterNot { symbolKey(it.symbol) in excludeSymbols }
        .distinctBy { symbolKey(it.symbol) }
        .sortedWith(compareBy<ExchangeMarketSnapshot> { symbolKey(it.symbol) }.thenBy { it.symbol })
        .toList()
}

fun resolveAuthoritativeMarketSymbols(
    txBase: String,
    exchange: String,
    aliases: Collection<String> = emptyList(),
    policy: TradingPolicy = ActiveTradingPolicy.current(),
    fetchMarkets: (String, String) -> List<ExchangeMarketSnapshot> = ::fetchExchangeMarkets
): List<String> {
    val venue = policy.findVenueForExchange(exchange, aliases)
        ?: error("No trading policy venue configured for exchange=$exchange aliases=${aliases.joinToString(",")}")
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
                markets = fetchMarkets(txBase, venue.venueId),
                universe = venue.universe
            )
            require(markets.isNotEmpty()) {
                "Exchange catalog resolved no markets for venue=${venue.venueId} exchange=${venue.exchangeId}"
            }
            markets.map { it.symbol }
        }
    }
}

fun buildExchangePlans(catalog: List<ExchangeCatalogSnapshot>, config: ResearchConfig): List<ExchangePlan> {
    val overrideExchange = config.executionExchangeOverride.trim().lowercase()
    val selected = if (overrideExchange.isNotEmpty()) {
        listOf(overrideExchange)
    } else {
        catalog
            .filter {
                it.implementationStatus == "INTEGRATED" ||
                    it.capabilities.marketDataIngress ||
                    it.capabilities.bestQuoteDefault
            }
            .map { it.apiName.lowercase() }
            .distinct()
            .ifEmpty { listOf("hyperliquid") }
    }

    return selected.map { exchange ->
        val aliases = when (exchange) {
            "hyperliquid" -> when (config.marketExchange.lowercase()) {
                "hyperliquid_testnet" -> listOf("hyperliquid_testnet")
                "hyperliquid" -> listOf("hyperliquid")
                "hyperliquid_merged", "hyperliquid_mainnet_merged" -> listOf("hyperliquid_mainnet", "hyperliquid")
                "hyperliquid_mainnet" -> listOf("hyperliquid_mainnet")
                else -> listOf("hyperliquid_mainnet")
            }
            else -> listOf(exchange)
        }
        ExchangePlan(exchange = exchange, marketAliases = aliases.distinct())
    }
}

fun selectResearchCandleSource(barMinutes: Int): CandleSource =
    max(barMinutes, 1).let { minutes ->
        val intervalLabel = if (minutes % 60 == 0) {
            "${minutes / 60}h"
        } else {
            "${minutes}m"
        }
        CandleSource(intervalLabel = intervalLabel, minutes = minutes)
    }

fun scaleRequiredSourceBars(minBars: Int, sourceMinutes: Int, targetBarMinutes: Int): Int {
    val normalizedSourceMinutes = max(sourceMinutes, 1)
    val normalizedTargetMinutes = max(targetBarMinutes, normalizedSourceMinutes)
    val coverageMinutes = minBars.toDouble() * normalizedTargetMinutes.toDouble()
    return max(1, ceil(coverageMinutes / normalizedSourceMinutes.toDouble()).toInt())
}

internal fun requiredResearchWindowBars(lookbackHours: Int, barMinutes: Int, minBars: Int): Int {
    val lookbackBars = ceil((lookbackHours.toDouble() * 60.0) / max(barMinutes, 1).toDouble()).toInt()
    return max(minBars, lookbackBars)
}

internal fun researchWarmupBars(config: ResearchConfig): Int =
    max(config.betaLookbackBars, config.trendSlowBars)

internal fun minimumResearchLookbackHours(config: ResearchConfig): Int {
    val warmupHours = ceil(
        researchWarmupBars(config).toDouble() * max(config.barMinutes, 1).toDouble() / 60.0
    ).toInt()
    return warmupHours + max(config.forwardHours, 1) + 1
}

private data class TimedCacheEntry<T>(
    val loadedAt: Instant,
    val value: T
)

private class TimedLruCache<K, V>(private val maxEntries: Int) {
    private val entries = object : LinkedHashMap<K, TimedCacheEntry<V>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, TimedCacheEntry<V>>?): Boolean =
            size > maxEntries
    }

    fun get(key: K, ttl: Duration, now: Instant = Instant.now()): V? = synchronized(this) {
        val entry = entries[key] ?: return null
        if (Duration.between(entry.loadedAt, now) > ttl) {
            entries.remove(key)
            return null
        }
        entry.value
    }

    fun put(key: K, value: V, now: Instant = Instant.now()) = synchronized(this) {
        entries[key] = TimedCacheEntry(loadedAt = now, value = value)
    }
}

private val researchFeatureQueryCacheTtl: Duration =
    Duration.ofSeconds(crossSectionalPolicy().featureCache.ttlSeconds.toLong())
private val researchFeatureLiquidityCache = TimedLruCache<String, List<SymbolLiquiditySnapshot>>(
    crossSectionalPolicy().featureCache.maxEntries
)
private val researchFeatureBarCache = TimedLruCache<String, List<Bar>>(
    crossSectionalPolicy().featureCache.maxEntries
)

private fun featureCacheKey(
    prefix: String,
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    barMinutes: Int
): String = buildString {
    append(prefix)
    append('|')
    append(aliases.sorted().joinToString(","))
    append('|')
    append(symbols.sorted().joinToString(","))
    append('|')
    append(lookbackHours)
    append('|')
    append(barMinutes)
}

private fun queryDiscoveredSymbolLiquidityFromFeatures(
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    source: CandleSource,
    scaledMinBars: Int
): List<SymbolLiquiditySnapshot> {
    if (symbols.isEmpty()) return emptyList()
    loadUniverseSnapshotLiquidity(
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        barMinutes = source.minutes,
        minBars = scaledMinBars
    ).takeIf { it.isNotEmpty() }?.let { return it }

    val cacheKey = featureCacheKey(
        prefix = "feature-liquidity",
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        barMinutes = source.minutes
    )
    researchFeatureLiquidityCache.get(cacheKey, researchFeatureQueryCacheTtl)?.let { return it }

    val aliasSql = sqlList(aliases)
    val symbolSql = sqlList(symbols)
    val preferredAlias = aliases.firstOrNull().orEmpty()
    val window = alignedResearchWindowBounds(lookbackHours = lookbackHours, barMinutes = source.minutes)
    val bucketSeconds = window.bucketSeconds
    val sql = """
        WITH minute_rows AS (
            SELECT DISTINCT ON (symbol, time)
                symbol,
                time,
                COALESCE(volume, 0) AS volume
            FROM research_features_1m
            WHERE exchange IN ($aliasSql)
              AND time >= ?
              AND time < ?
              AND symbol IN ($symbolSql)
            ORDER BY
                symbol,
                time,
                CASE WHEN exchange = '$preferredAlias' THEN 0 ELSE 1 END
        ),
        bucketed AS (
            SELECT
                symbol,
                to_timestamp(floor(extract(epoch from time) / $bucketSeconds) * $bucketSeconds) AS bucket_time,
                volume
            FROM minute_rows
        ),
        aggregated AS (
            SELECT
                symbol,
                bucket_time,
                SUM(volume) AS bucket_volume
            FROM bucketed
            GROUP BY symbol, bucket_time
        )
        SELECT
            symbol,
            COUNT(*) AS bars,
            COALESCE(AVG(bucket_volume), 0) AS avg_volume
        FROM aggregated
        GROUP BY symbol
        HAVING COUNT(*) >= $scaledMinBars
        ORDER BY bars DESC, avg_volume DESC, symbol ASC
    """.trimIndent()

    val result = runCatching {
        buildList {
            pgConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(window.startInclusive))
                    stmt.setTimestamp(2, Timestamp.from(window.endExclusive))
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            add(
                                SymbolLiquiditySnapshot(
                                    symbol = rs.getString("symbol"),
                                    bars = rs.getInt("bars"),
                                    avgVolume = rs.getDouble("avg_volume")
                                )
                            )
                        }
                    }
                }
            }
        }
    }.getOrElse { emptyList() }

    if (result.isNotEmpty()) {
        researchFeatureLiquidityCache.put(cacheKey, result)
    }
    return result
}

private fun discoverSymbolsByAggregateFromFeatures(
    aliases: List<String>,
    lookbackHours: Int,
    maxSymbols: Int,
    minBars: Int,
    barMinutes: Int
): List<String> {
    val source = selectResearchCandleSource(barMinutes)
    val scaledMinBars = scaleRequiredSourceBars(minBars, source.minutes, barMinutes)
    loadUniverseSnapshotLiquidity(
        aliases = aliases,
        symbols = emptyList(),
        lookbackHours = lookbackHours,
        barMinutes = source.minutes,
        minBars = scaledMinBars,
        maxSymbols = maxSymbols
    ).takeIf { it.isNotEmpty() }?.let { ranked ->
        return ranked.map { it.symbol }
    }

    val aliasSql = sqlList(aliases)
    val preferredAlias = aliases.firstOrNull().orEmpty()
    val window = alignedResearchWindowBounds(lookbackHours = lookbackHours, barMinutes = source.minutes)
    val bucketSeconds = window.bucketSeconds
    val sql = """
        WITH minute_rows AS (
            SELECT DISTINCT ON (symbol, time)
                symbol,
                time,
                COALESCE(volume, 0) AS volume
            FROM research_features_1m
            WHERE exchange IN ($aliasSql)
              AND time >= ?
              AND time < ?
            ORDER BY
                symbol,
                time,
                CASE WHEN exchange = '$preferredAlias' THEN 0 ELSE 1 END
        ),
        bucketed AS (
            SELECT
                symbol,
                to_timestamp(floor(extract(epoch from time) / $bucketSeconds) * $bucketSeconds) AS bucket_time,
                volume
            FROM minute_rows
        ),
        aggregated AS (
            SELECT
                symbol,
                bucket_time,
                SUM(volume) AS bucket_volume
            FROM bucketed
            GROUP BY symbol, bucket_time
        )
        SELECT
            symbol,
            COUNT(*) AS bars,
            COALESCE(AVG(bucket_volume), 0) AS avg_volume
        FROM aggregated
        GROUP BY symbol
        HAVING COUNT(*) >= $scaledMinBars
        ORDER BY bars DESC, avg_volume DESC, symbol ASC
        ${if (maxSymbols > 0) "LIMIT $maxSymbols" else ""}
    """.trimIndent()

    return runCatching {
        buildList {
            pgConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(window.startInclusive))
                    stmt.setTimestamp(2, Timestamp.from(window.endExclusive))
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            add(rs.getString("symbol"))
                        }
                    }
                }
            }
        }
    }.getOrElse { emptyList() }
}

fun queryDiscoveredSymbolLiquidity(
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    source: CandleSource,
    scaledMinBars: Int
): List<SymbolLiquiditySnapshot> =
    queryDiscoveredSymbolLiquidityFromFeatures(
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        source = source,
        scaledMinBars = scaledMinBars
    )
        .sortedWith(
            compareByDescending<SymbolLiquiditySnapshot> { it.bars }
                .thenByDescending { it.avgVolume }
                .thenBy { it.symbol }
        )

internal fun rankDiscoveredSymbolLiquidityBatches(
    batches: List<List<String>>,
    maxParallelism: Int,
    query: (List<String>) -> List<SymbolLiquiditySnapshot>
): List<SymbolLiquiditySnapshot> =
    parallelMapBlocking(
        items = batches,
        maxParallelism = maxParallelism,
        block = query
    )
        .flatten()
        .distinctBy { it.symbol }
        .sortedWith(
            compareByDescending<SymbolLiquiditySnapshot> { it.bars }
                .thenByDescending { it.avgVolume }
                .thenBy { it.symbol }
        )

fun discoverSymbolsFromMarketCatalog(
    aliases: List<String>,
    candidateSymbols: List<String>,
    lookbackHours: Int,
    maxSymbols: Int,
    minBars: Int,
    barMinutes: Int
): List<String> {
    val normalizedSymbols = candidateSymbols
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }
        .distinct()
    if (normalizedSymbols.isEmpty()) return emptyList()

    val source = selectResearchCandleSource(barMinutes)
    val scaledMinBars = scaleRequiredSourceBars(minBars, source.minutes, barMinutes)
    loadUniverseSnapshot(
        aliases = aliases,
        lookbackHours = lookbackHours,
        barMinutes = source.minutes
    )?.let { snapshot ->
        val ranked = rankUniverseSnapshotLiquidity(
            snapshot = snapshot,
            symbols = normalizedSymbols,
            lookbackHours = lookbackHours,
            barMinutes = source.minutes,
            minBars = scaledMinBars
        ).map { it.symbol }
        if (ranked.isNotEmpty()) {
            return if (maxSymbols > 0) ranked.take(maxSymbols) else ranked
        }
    }

    val batchSize = if (maxSymbols > 0) {
        min(64, max(16, maxSymbols * 4))
    } else {
        64
    }
    val batches = normalizedSymbols.chunked(batchSize)
    val ranked = rankDiscoveredSymbolLiquidityBatches(
        batches = batches,
        maxParallelism = resolveResearchQueryParallelism(batches.size)
    ) { batch ->
        queryDiscoveredSymbolLiquidity(
            aliases = aliases,
            symbols = batch,
            lookbackHours = lookbackHours,
            source = source,
            scaledMinBars = scaledMinBars
        )
    }
        .map { it.symbol }

    return if (ranked.isNotEmpty()) {
        if (maxSymbols > 0) ranked.take(maxSymbols) else ranked
    } else {
        if (maxSymbols > 0) normalizedSymbols.take(maxSymbols) else normalizedSymbols
    }
}

fun discoverSymbolsByAggregate(
    aliases: List<String>,
    lookbackHours: Int,
    maxSymbols: Int,
    minBars: Int,
    barMinutes: Int
): List<String> =
    discoverSymbolsByAggregateFromFeatures(
        aliases = aliases,
        lookbackHours = lookbackHours,
        maxSymbols = maxSymbols,
        minBars = minBars,
        barMinutes = barMinutes
    )

fun discoverSymbols(
    txBase: String,
    exchange: String,
    aliases: List<String>,
    lookbackHours: Int,
    maxSymbols: Int,
    minBars: Int,
    barMinutes: Int
): List<String> {
    val requiredBars = requiredResearchWindowBars(
        lookbackHours = lookbackHours,
        barMinutes = barMinutes,
        minBars = minBars
    )
    val markets = resolveAuthoritativeMarketSymbols(
        txBase = txBase,
        exchange = exchange,
        aliases = aliases
    )
    val discovered = if (markets.isNotEmpty()) {
        val ranked = discoverSymbolsFromMarketCatalog(
            aliases = aliases,
            candidateSymbols = markets,
            lookbackHours = lookbackHours,
            maxSymbols = maxSymbols,
            minBars = requiredBars,
            barMinutes = barMinutes
        )
        println(
            "Cross-sectional universe discovery exchange=$exchange source=market_catalog " +
                "markets=${markets.size} discovered=${ranked.size}"
        )
        ranked
    } else {
        val ranked = discoverSymbolsByAggregate(
            aliases = aliases,
            lookbackHours = lookbackHours,
            maxSymbols = maxSymbols,
            minBars = requiredBars,
            barMinutes = barMinutes
        )
        println(
            "Cross-sectional universe discovery exchange=$exchange source=market_data_aggregate " +
                "discovered=${ranked.size}"
        )
        ranked
    }

    return discovered
}

internal fun discoveryCandidateLimit(maxSymbols: Int, discoveryMaxSymbols: Int): Int =
    when {
        discoveryMaxSymbols > 0 -> max(discoveryMaxSymbols, maxSymbols)
        else -> 0
    }

private fun universeSelectionWindowHours(config: ResearchConfig): Int =
    max(max(config.forwardHours * 2, 72), max((config.barMinutes * 12) / 60, 24))

internal fun rankResearchUniverseCandidates(
    bars: List<Bar>,
    config: ResearchConfig
): List<ResearchUniverseCandidate> {
    if (bars.isEmpty()) return emptyList()

    val latestTime = bars.maxOfOrNull { it.time } ?: return emptyList()
    val recentCutoff = latestTime.minus(universeSelectionWindowHours(config).toLong(), ChronoUnit.HOURS)
    val benchmarkSymbols = setOf("BTC", "ETH")
    val grouped = bars.groupBy { it.exchange to it.symbol }

    return grouped.mapNotNull { (key, series) ->
        val exchange = key.first
        val symbol = key.second
        val ordered = series.sortedBy { it.time }
        val recent = ordered.filter { !it.time.isBefore(recentCutoff) }
        val observedRecent = recent.filter { it.executionObserved && observedSpreadBps(it) > 0.0 && observedDepthUsd(it) > 0.0 }
        val tradableRecent = observedRecent.filter {
            observedSpreadBps(it) <= config.maxSpreadBps &&
                observedDepthUsd(it) >= config.notionalUsd * config.minDepthMultiple
        }
        val recentBars = recent.size
        val recentObservedBars = observedRecent.size
        val recentTradableBars = tradableRecent.size
        if (symbol !in benchmarkSymbols && ordered.isEmpty()) return@mapNotNull null

        ResearchUniverseCandidate(
            exchange = exchange,
            symbol = symbol,
            totalBars = ordered.size,
            recentBars = recentBars,
            recentObservedBars = recentObservedBars,
            recentTradableBars = recentTradableBars,
            recentTradableRatio = recentTradableBars.toDouble() / max(recentBars, 1).toDouble(),
            recentObservedRatio = recentObservedBars.toDouble() / max(recentBars, 1).toDouble(),
            avgRecentDepthUsd = if (observedRecent.isEmpty()) 0.0 else mean(observedRecent.map(::observedDepthUsd)),
            avgRecentVolumeUsd = if (recent.isEmpty()) 0.0 else mean(recent.map(::observedVolumeUsd)),
            avgRecentSpreadBps = if (observedRecent.isEmpty()) Double.POSITIVE_INFINITY else mean(observedRecent.map(::observedSpreadBps))
        )
    }
}

internal fun selectResearchUniverseFromCandidates(
    candidates: List<ResearchUniverseCandidate>,
    config: ResearchConfig
): Map<String, List<String>> {
    if (candidates.isEmpty()) return emptyMap()
    val benchmarkSymbols = setOf("BTC", "ETH")
    val requiredHistoryBars = max(
        config.minBars,
        maxOf(config.betaLookbackBars, config.trendSlowBars, config.reversionLookbackBars) + 1
    )

    return candidates.groupBy { it.exchange }
        .mapValues { (_, candidates) ->
            val benchmarks = candidates
                .filter { it.symbol in benchmarkSymbols }
                .sortedBy { it.symbol }
                .map { it.symbol }
            val selected = candidates
                .filter { it.symbol !in benchmarkSymbols && it.totalBars >= requiredHistoryBars }
                .sortedWith(
                    compareByDescending<ResearchUniverseCandidate> { it.recentTradableBars }
                        .thenByDescending { it.recentTradableRatio }
                        .thenByDescending { it.recentObservedBars }
                        .thenByDescending { it.recentObservedRatio }
                        .thenByDescending { it.avgRecentDepthUsd }
                        .thenByDescending { it.avgRecentVolumeUsd }
                        .thenBy { it.avgRecentSpreadBps }
                        .thenByDescending { it.totalBars }
                        .thenBy { it.symbol }
                )
                .let { ranked ->
                    if (config.maxSymbols > 0) ranked.take(config.maxSymbols) else ranked
                }
                .map { it.symbol }

            (benchmarks + selected).distinct()
        }
}

fun selectResearchUniverseFromBars(
    bars: List<Bar>,
    config: ResearchConfig
): Map<String, List<String>> =
    selectResearchUniverseFromCandidates(rankResearchUniverseCandidates(bars, config), config)

private fun universeLiquidityBucket(candidate: ResearchUniverseCandidate, config: ResearchConfig): String =
    when {
        candidate.recentTradableRatio >= 0.75 &&
            candidate.avgRecentDepthUsd >= (config.notionalUsd * config.minDepthMultiple * 6.0) &&
            candidate.avgRecentSpreadBps <= (config.maxSpreadBps * 0.55) -> "deep"
        candidate.recentTradableRatio >= 0.60 &&
            candidate.avgRecentDepthUsd >= (config.notionalUsd * config.minDepthMultiple * 3.0) &&
            candidate.avgRecentSpreadBps <= (config.maxSpreadBps * 0.8) -> "core"
        candidate.recentTradableRatio >= 0.35 &&
            candidate.avgRecentDepthUsd >= (config.notionalUsd * config.minDepthMultiple) -> "tradable"
        else -> "fragile"
    }

internal fun buildUniverseProfiles(
    candidates: List<ResearchUniverseCandidate>,
    selectedUniverse: Map<String, List<String>>,
    config: ResearchConfig
): List<UniverseProfileSnapshot> {
    if (candidates.isEmpty()) return emptyList()

    fun sanitized(value: Double): Double =
        if (value.isFinite()) value else max(config.maxSpreadBps * 4.0, 0.0)

    fun avg(candidates: List<ResearchUniverseCandidate>, selector: (ResearchUniverseCandidate) -> Double): Double =
        mean(candidates.map { sanitized(selector(it)) })

    fun median(candidates: List<ResearchUniverseCandidate>, selector: (ResearchUniverseCandidate) -> Double): Double =
        percentile(candidates.map { sanitized(selector(it)) }, 0.5)

    fun share(sumNumerator: Int, sumDenominator: Int): Double =
        if (sumDenominator <= 0) 0.0 else sumNumerator.toDouble() / sumDenominator.toDouble()

    return candidates.groupBy { it.exchange }
        .map { (exchange, exchangeCandidates) ->
            val selectedSymbols = selectedUniverse[exchange].orEmpty().toSet()
            val selectedCandidates = exchangeCandidates.filter { it.symbol in selectedSymbols }
            val orderedCandidates = exchangeCandidates.sortedWith(
                compareByDescending<ResearchUniverseCandidate> { it.recentTradableBars }
                    .thenByDescending { it.recentTradableRatio }
                    .thenByDescending { it.avgRecentDepthUsd }
                    .thenByDescending { it.avgRecentVolumeUsd }
                    .thenBy { it.avgRecentSpreadBps }
                    .thenBy { it.symbol }
            )
            val liquidityBuckets = orderedCandidates.groupBy { universeLiquidityBucket(it, config) }
                .map { (label, bucket) ->
                    UniverseLiquidityBucketSnapshot(
                        label = label,
                        symbols = bucket.size,
                        avgSpreadBps = avg(bucket) { it.avgRecentSpreadBps }.round(4),
                        avgDepthUsd = avg(bucket) { it.avgRecentDepthUsd }.round(4),
                        avgVolumeUsd = avg(bucket) { it.avgRecentVolumeUsd }.round(4),
                        avgTradableRatio = avg(bucket) { it.recentTradableRatio }.round(4)
                    )
                }
                .sortedBy { listOf("deep", "core", "tradable", "fragile").indexOf(it.label).let { idx -> if (idx >= 0) idx else Int.MAX_VALUE } }

            UniverseProfileSnapshot(
                exchange = exchange,
                candidateSymbols = orderedCandidates.size,
                selectedSymbols = selectedCandidates.size,
                benchmarkSymbols = orderedCandidates.count { it.symbol in setOf("BTC", "ETH") },
                candidateAvgRecentTradableRatio = avg(orderedCandidates) { it.recentTradableRatio }.round(4),
                selectedAvgRecentTradableRatio = avg(selectedCandidates) { it.recentTradableRatio }.round(4),
                candidateAvgRecentObservedRatio = avg(orderedCandidates) { it.recentObservedRatio }.round(4),
                selectedAvgRecentObservedRatio = avg(selectedCandidates) { it.recentObservedRatio }.round(4),
                candidateAvgRecentSpreadBps = avg(orderedCandidates) { it.avgRecentSpreadBps }.round(4),
                selectedAvgRecentSpreadBps = avg(selectedCandidates) { it.avgRecentSpreadBps }.round(4),
                candidateMedianRecentSpreadBps = median(orderedCandidates) { it.avgRecentSpreadBps }.round(4),
                selectedMedianRecentSpreadBps = median(selectedCandidates) { it.avgRecentSpreadBps }.round(4),
                candidateAvgRecentDepthUsd = avg(orderedCandidates) { it.avgRecentDepthUsd }.round(4),
                selectedAvgRecentDepthUsd = avg(selectedCandidates) { it.avgRecentDepthUsd }.round(4),
                candidateAvgRecentVolumeUsd = avg(orderedCandidates) { it.avgRecentVolumeUsd }.round(4),
                selectedAvgRecentVolumeUsd = avg(selectedCandidates) { it.avgRecentVolumeUsd }.round(4),
                candidateObservedExecutionShare = share(
                    orderedCandidates.sumOf { it.recentObservedBars },
                    orderedCandidates.sumOf { it.recentBars }
                ).round(4),
                selectedObservedExecutionShare = share(
                    selectedCandidates.sumOf { it.recentObservedBars },
                    selectedCandidates.sumOf { it.recentBars }
                ).round(4),
                candidateTradableExecutionShare = share(
                    orderedCandidates.sumOf { it.recentTradableBars },
                    orderedCandidates.sumOf { it.recentBars }
                ).round(4),
                selectedTradableExecutionShare = share(
                    selectedCandidates.sumOf { it.recentTradableBars },
                    selectedCandidates.sumOf { it.recentBars }
                ).round(4),
                liquidityBuckets = liquidityBuckets,
                selectedUniverse = selectedCandidates.map { it.symbol }.sorted(),
                topCandidates = orderedCandidates.take(12).map { it.symbol }
            )
        }
        .sortedBy { it.exchange }
}

fun loadBars(exchange: String, aliases: List<String>, symbols: List<String>, lookbackHours: Int, barMinutes: Int): List<Bar> =
    queryBarsFromFeatures(
        exchange = exchange,
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        barMinutes = barMinutes
    )
        .distinctBy { Triple(it.symbol, it.time, it.exchange) }
        .sortedWith(compareBy<Bar> { it.time }.thenBy { it.symbol })

private fun queryBarsFromFeatures(
    exchange: String,
    aliases: List<String>,
    symbols: List<String>,
    lookbackHours: Int,
    barMinutes: Int
): List<Bar> {
    if (symbols.isEmpty()) return emptyList()
    loadUniverseSnapshotBars(
        exchange = exchange,
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        barMinutes = barMinutes
    ).takeIf { it.isNotEmpty() }?.let { return it }

    val cacheKey = featureCacheKey(
        prefix = "feature-bars:$exchange",
        aliases = aliases,
        symbols = symbols,
        lookbackHours = lookbackHours,
        barMinutes = barMinutes
    )
    researchFeatureBarCache.get(cacheKey, researchFeatureQueryCacheTtl)?.let { return it }

    val aliasSql = sqlList(aliases)
    val symbolSql = sqlList(symbols)
    val preferredAlias = aliases.first()
    val window = alignedResearchWindowBounds(lookbackHours = lookbackHours, barMinutes = barMinutes)
    val bucketSeconds = window.bucketSeconds
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
                funding_rate,
                open_interest,
                asset_context_observed,
                orderbook_observed,
                exchange
            FROM research_features_1m
            WHERE exchange IN ($aliasSql)
              AND time >= ?
              AND time < ?
              AND symbol IN ($symbolSql)
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
                funding_rate,
                open_interest,
                asset_context_observed,
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
        ),
        bucket_asset_context AS (
            SELECT DISTINCT ON (symbol, bucket_time)
                symbol,
                bucket_time,
                funding_rate,
                open_interest,
                asset_context_observed,
                time AS latest_crowding_observed_time
            FROM bucketed
            WHERE asset_context_observed
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
            CASE WHEN o.symbol IS NULL THEN FALSE ELSE TRUE END AS execution_observed,
            a.funding_rate,
            a.open_interest,
            CASE WHEN a.symbol IS NULL THEN FALSE ELSE TRUE END AS asset_context_observed,
            a.latest_crowding_observed_time
        FROM bucket_close c
        JOIN bucket_volume v
          ON v.symbol = c.symbol
         AND v.bucket_time = c.bucket_time
        LEFT JOIN bucket_orderbook o
          ON o.symbol = c.symbol
         AND o.bucket_time = c.bucket_time
        LEFT JOIN bucket_asset_context a
          ON a.symbol = c.symbol
         AND a.bucket_time = c.bucket_time
        ORDER BY c.bucket_time ASC, c.symbol ASC
    """.trimIndent()

    val result = runCatching {
        buildList {
            pgConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setTimestamp(1, Timestamp.from(window.startInclusive))
                    stmt.setTimestamp(2, Timestamp.from(window.endExclusive))
                    stmt.executeQuery().use { rs ->
                        while (rs.next()) {
                            add(
                                Bar(
                                    exchange = exchange,
                                    symbol = rs.getString("symbol"),
                                    time = rs.getTimestamp("bucket_time").toInstant(),
                                    close = rs.getDouble("close"),
                                    volume = rs.getDouble("volume"),
                                    spreadPct = rs.getDouble("spread_pct"),
                                    bidDepth10 = rs.getDouble("bid_depth_10"),
                                    askDepth10 = rs.getDouble("ask_depth_10"),
                                    midPrice = rs.getDouble("mid_price"),
                                    executionObserved = rs.getBoolean("execution_observed"),
                                    fundingRate = rs.doubleOrNull("funding_rate"),
                                    openInterest = rs.doubleOrNull("open_interest"),
                                    assetContextObserved = rs.getBoolean("asset_context_observed"),
                                    latestCrowdingObservedTime = rs.getTimestamp("latest_crowding_observed_time")?.toInstant()
                                )
                            )
                        }
                    }
                }
            }
        }
    }.getOrElse { emptyList() }

    if (result.isNotEmpty()) {
        researchFeatureBarCache.put(cacheKey, result)
    }
    return result
}

private fun observedSpreadBps(bar: Bar): Double =
    max(bar.spreadPct, 0.0) * 100.0

private fun observedMidPrice(bar: Bar): Double =
    max(bar.midPrice, bar.close)

private fun observedDepthUsd(bar: Bar): Double =
    max((max(bar.bidDepth10, 0.0) + max(bar.askDepth10, 0.0)) * observedMidPrice(bar), 0.0)

private fun observedVolumeUsd(bar: Bar): Double =
    max(bar.volume * observedMidPrice(bar), 0.0)

// Use live-observed execution shapes as conservative priors when historical bars lack orderbook snapshots.
private fun buildExecutionProxyProfiles(
    bars: List<Bar>,
    config: ResearchConfig
): Pair<Map<Pair<String, String>, ExecutionProxyProfile>, ExecutionProxyProfile> {
    val observedBars = bars.filter { it.executionObserved && observedSpreadBps(it) > 0.0 && observedDepthUsd(it) > 0.0 }

    fun buildProfile(samples: List<Bar>): ExecutionProxyProfile {
        if (samples.isEmpty()) {
            return ExecutionProxyProfile(
                spreadBps = max(config.maxSpreadBps * 0.9, 0.5),
                depthToVolumeRatio = 0.03,
                depthFloorUsd = config.notionalUsd * 2.0
            )
        }

        val spreads = samples.map(::observedSpreadBps)
        val depths = samples.map(::observedDepthUsd)
        val depthRatios = samples.map { sample ->
            observedDepthUsd(sample) / max(observedVolumeUsd(sample), config.notionalUsd)
        }

        return ExecutionProxyProfile(
            spreadBps = max(percentile(spreads, 0.75) * 1.15, 0.25),
            depthToVolumeRatio = max(percentile(depthRatios, 0.25) * 0.85, 0.02),
            depthFloorUsd = max(percentile(depths, 0.25) * 0.75, config.notionalUsd * 2.0)
        )
    }

    val marketProfile = buildProfile(observedBars)
    val symbolProfiles = observedBars.groupBy { it.exchange to it.symbol }
        .mapValues { (_, samples) ->
            buildProfile(samples).let { profile ->
                ExecutionProxyProfile(
                    spreadBps = max(profile.spreadBps, marketProfile.spreadBps * 0.85),
                    depthToVolumeRatio = max(profile.depthToVolumeRatio, marketProfile.depthToVolumeRatio * 0.65),
                    depthFloorUsd = max(profile.depthFloorUsd, marketProfile.depthFloorUsd * 0.65)
                )
            }
        }

    return symbolProfiles to marketProfile
}

fun engineerFeatures(bars: List<Bar>, config: ResearchConfig): List<FeatureRow> {
    if (bars.isEmpty()) return emptyList()

    val seriesByKey = bars.groupBy { it.exchange to it.symbol }
        .mapValues { (_, value) -> value.sortedBy { it.time } }
    val (executionProxyByKey, marketExecutionProxy) = buildExecutionProxyProfiles(bars, config)

    val baseByKey = seriesByKey.mapValues { (key, series) ->
        val returns = ArrayDeque<Double>()
        val executionProxy = executionProxyByKey[key] ?: marketExecutionProxy
        var latestFundingRate: Double? = null
        var latestOpenInterest: Double? = null
        var previousFundingRate = 0.0
        var previousOpenInterest = 0.0
        var previousOiChange = 0.0
        series.mapIndexed { index, bar ->
            val previous = series.getOrNull(index - 1)
            val ret1m = if (previous == null || previous.close <= 0.0) 0.0 else (bar.close / previous.close) - 1.0
            returns.addLast(ret1m)
            if (returns.size > 30) {
                returns.removeFirst()
            }
            val vol30 = stdev(returns.toList())
            val midPrice = observedMidPrice(bar)
            val rawSpreadBps = observedSpreadBps(bar)
            val rawDepthUsd = observedDepthUsd(bar)
            val executionObserved = bar.executionObserved && rawSpreadBps > 0.0 && rawDepthUsd > 0.0
            val volumeUsd = max(observedVolumeUsd(bar), config.notionalUsd)
            val proxyDepthUsd = max(
                executionProxy.depthFloorUsd,
                volumeUsd * executionProxy.depthToVolumeRatio
            )
            val proxyDepthUnits = proxyDepthUsd / max(midPrice, 1e-6)
            if (bar.assetContextObserved) {
                if (bar.fundingRate != null) latestFundingRate = bar.fundingRate
                if (bar.openInterest != null) latestOpenInterest = max(bar.openInterest, 0.0)
            }
            val fundingRate = latestFundingRate ?: 0.0
            val openInterest = max(latestOpenInterest ?: 0.0, 0.0)
            val openInterestNotionalUsd = openInterest * max(midPrice, 0.0)
            val oiChange = if (previousOpenInterest > 0.0 && openInterest > 0.0) {
                clamp((openInterest / previousOpenInterest) - 1.0, -0.95, 8.0)
            } else {
                0.0
            }
            val oiAcceleration = clamp(oiChange - previousOiChange, -1.5, 1.5)
            val spreadBps = if (executionObserved) rawSpreadBps else executionProxy.spreadBps
            val spreadPct = if (executionObserved) max(bar.spreadPct, 0.0) else executionProxy.spreadBps / 10000.0
            val bidDepth10 = if (executionObserved) max(bar.bidDepth10, 0.0) else proxyDepthUnits / 2.0
            val askDepth10 = if (executionObserved) max(bar.askDepth10, 0.0) else proxyDepthUnits / 2.0
            val depthUsd = if (executionObserved) rawDepthUsd else proxyDepthUsd
            val fundingChange = clamp(fundingRate - previousFundingRate, -0.02, 0.02)
            previousFundingRate = fundingRate
            previousOpenInterest = openInterest
            previousOiChange = oiChange
            BasePoint(
                exchange = key.first,
                symbol = key.second,
                time = bar.time,
                barIndex = index,
                close = bar.close,
                volume = bar.volume,
                spreadPct = spreadPct,
                spreadBps = spreadBps,
                bidDepth10 = bidDepth10,
                askDepth10 = askDepth10,
                midPrice = midPrice,
                depthUsd = depthUsd,
                ret1m = ret1m,
                vol30 = vol30,
                executionObserved = executionObserved,
                fundingRate = fundingRate,
                fundingChange = fundingChange,
                openInterest = openInterest,
                openInterestNotionalUsd = openInterestNotionalUsd,
                oiChange = oiChange,
                oiAcceleration = oiAcceleration,
                assetContextObserved = bar.assetContextObserved
            )
        }
    }

    val baseLookup = mutableMapOf<Triple<String, String, Instant>, BasePoint>()
    baseByKey.values.flatten().forEach { point ->
        baseLookup[Triple(point.exchange, point.symbol, point.time)] = point
    }

    val mediumTrendBars = max(config.trendMediumBars, config.trendSlowBars + 1)
    val longTrendBars = max(config.trendLongBars, mediumTrendBars + 1)
    val unranked = mutableListOf<UnrankedFeature>()
    for ((key, series) in baseByKey) {
        val exchange = key.first
        val residuals = mutableListOf<Double>()
        val dislocations = mutableListOf<Double>()
        val volumeSeries = series.map { max(it.volume, 0.0) }
        val depthSeries = series.map { max(it.depthUsd, 0.0) }
        val volSeries = series.map { max(it.vol30, 0.0) }

        for (i in series.indices) {
            val point = series[i]
            val betaWindow = buildList {
                val start = max(0, i - config.betaLookbackBars + 1)
                for (j in start..i) {
                    val row = series[j]
                    val btc = baseLookup[Triple(exchange, "BTC", row.time)] ?: continue
                    val eth = baseLookup[Triple(exchange, "ETH", row.time)] ?: continue
                    add(Triple(row.ret1m, btc.ret1m, eth.ret1m))
                }
            }

            val (betaBtc, betaEth) = twoFactorBetas(betaWindow)
            val btcRet = baseLookup[Triple(exchange, "BTC", point.time)]?.ret1m ?: 0.0
            val ethRet = baseLookup[Triple(exchange, "ETH", point.time)]?.ret1m ?: 0.0
            val residualRet = point.ret1m - (betaBtc * btcRet) - (betaEth * ethRet)
            residuals += residualRet

            val residualMomFast = rollingSum(residuals, residuals.lastIndex, config.trendLookbackBars)
            val residualMomSlow = rollingSum(residuals, residuals.lastIndex, config.trendSlowBars)
            val residualMomMedium = rollingSum(residuals, residuals.lastIndex, mediumTrendBars)
            val residualMomLong = rollingSum(residuals, residuals.lastIndex, longTrendBars)
            val dislocation = rollingSum(residuals, residuals.lastIndex, config.reversionLookbackBars)
            dislocations += dislocation
            val dislocationWindow = dislocations.subList(max(0, dislocations.size - config.betaLookbackBars), dislocations.size)
            val dislocationMean = mean(dislocationWindow)
            val dislocationStd = stdev(dislocationWindow)
            val residualZ = if (dislocationStd < 1e-9) 0.0 else (dislocation - dislocationMean) / dislocationStd
            val volumeBaseline = max(rollingMean(volumeSeries, i, 60), 1.0)
            val depthBaseline = max(rollingMean(depthSeries, i, 60), config.notionalUsd)
            val volBaseline = max(rollingMean(volSeries, i, 120), 1e-6)
            val volumeRatio = clamp(point.volume / volumeBaseline, 0.0, 8.0)
            val depthRatio = clamp(point.depthUsd / depthBaseline, 0.0, 6.0)
            val imbalance = (max(point.bidDepth10, 0.0) - max(point.askDepth10, 0.0)) /
                max(max(point.bidDepth10, 0.0) + max(point.askDepth10, 0.0), 1e-6)
            val volRegime = clamp(point.vol30 / volBaseline, 0.25, 6.0)
            val normalizedRet = clamp(point.ret1m / max(point.vol30, 1e-6), -4.0, 4.0)
            val flowSignal = clamp(
                (normalizedRet * 0.65) +
                    (imbalance * 0.95) +
                    ((clamp(volumeRatio, 0.0, 4.0) - 1.0) * 0.22),
                -3.5,
                3.5
            )
            val fastScale = max(point.vol30 * sqrt(config.trendLookbackBars.toDouble()), 1e-6)
            val slowScale = max(point.vol30 * sqrt(config.trendSlowBars.toDouble()), 1e-6)
            val mediumScale = max(point.vol30 * sqrt(mediumTrendBars.toDouble()), 1e-6)
            val longScale = max(point.vol30 * sqrt(longTrendBars.toDouble()), 1e-6)
            val normalizedFast = residualMomFast / fastScale
            val normalizedSlow = residualMomSlow / slowScale
            val normalizedMedium = residualMomMedium / mediumScale
            val normalizedLong = residualMomLong / longScale
            val mediumTrendScore = (normalizedSlow * 0.20) +
                (normalizedMedium * 0.45) +
                (normalizedLong * 0.35)
            val mediumTrendDirection = direction(mediumTrendScore)
            val trendPersistence = if (mediumTrendDirection == 0.0) {
                0.0
            } else {
                listOf(normalizedFast, normalizedSlow, normalizedMedium, normalizedLong)
                    .count { direction(it) == mediumTrendDirection }
                    .toDouble() / 4.0
            }
            val trendPullback = if (mediumTrendDirection == 0.0) 0.0 else max(0.0, -(mediumTrendDirection * residualZ))
            val trendExhaustion = if (mediumTrendDirection == 0.0) 0.0 else max(0.0, mediumTrendDirection * residualZ)
            val rawTrend = (normalizedFast * 0.25) +
                (normalizedSlow * 0.15) +
                (mediumTrendScore * 0.60) +
                (flowSignal * 0.12) +
                (mediumTrendDirection * max(0.0, trendPersistence - 0.5) * 0.55) +
                (mediumTrendDirection * min(trendPullback, 1.5) * 0.12) -
                (point.spreadBps / 55.0)
            val volBps = point.vol30 * 10000.0
            val mediumAlignment = mediumTrendDirection * direction(residualZ)
            val trendConfirmationScore = clamp(
                (abs(mediumTrendScore) * 0.55) +
                    (trendPersistence * 0.95) +
                    (max(0.0, direction(rawTrend) * flowSignal) * 0.70) +
                    (max(0.0, mediumAlignment) * 0.45) +
                    (max(0.0, min(depthRatio, 2.0) - 1.0) * 0.30) +
                    (max(0.0, min(volumeRatio, 3.0) - 1.0) * 0.18) -
                    (max(0.0, trendExhaustion - 0.9) * 0.55) -
                    (max(0.0, volRegime - 1.6) * 0.35),
                0.0,
                6.0
            )
            val trendReentryBias = max(0.0, abs(mediumTrendScore) - abs(normalizedFast)) * max(0.0, -mediumAlignment)
            val trendExhaustionBias = max(0.0, abs(mediumTrendScore) - 0.6) * max(0.0, mediumAlignment)
            val trendExpectedGrossEdgeBps = clamp(
                (abs(mediumTrendScore) * max(volBps, 4.0) * 0.58 * sqrt(config.trendHoldBars.toDouble() / 12.0)) +
                    (max(0.0, direction(rawTrend) * flowSignal) * 7.0) +
                    (max(0.0, trendPersistence - 0.5) * 14.0) +
                    (min(trendPullback, 1.25) * 4.5) +
                    (max(0.0, depthRatio - 1.0) * 2.0) +
                    (max(0.0, min(volumeRatio, 3.0) - 1.0) * 2.5) -
                    (max(0.0, trendExhaustion - 1.25) * 6.0) -
                    (max(0.0, volRegime - 1.6) * 5.0),
                0.0,
                220.0
            )
            val reversionExpectedGrossEdgeBps = clamp(
                (abs(residualZ) * max(volBps, 4.0) * 0.62 * sqrt(config.reversionHoldBars.toDouble() / 10.0)) +
                    (max(0.0, -(direction(residualZ) * flowSignal)) * 8.0) +
                    (trendReentryBias * 8.5) +
                    (trendExhaustionBias * 6.0) +
                    (max(0.0, depthRatio - 1.0) * 2.0) -
                    (max(0.0, abs(rawTrend) - 1.25) * 4.5) -
                    (max(0.0, volRegime - 1.45) * 6.0),
                0.0,
                220.0
            )
            val tradable = point.symbol !in setOf("BTC", "ETH")
            val liquid = point.spreadBps <= config.maxSpreadBps &&
                point.depthUsd >= config.notionalUsd * config.minDepthMultiple &&
                point.barIndex >= max(config.betaLookbackBars, config.trendSlowBars) &&
                point.volume > 0.0 &&
                volumeRatio >= config.minVolumeRatio &&
                tradable

            unranked += UnrankedFeature(
                exchange = exchange,
                symbol = point.symbol,
                time = point.time,
                barIndex = point.barIndex,
                close = point.close,
                volume = point.volume,
                spreadPct = point.spreadPct,
                spreadBps = point.spreadBps,
                depthUsd = point.depthUsd,
                midPrice = point.midPrice,
                ret1m = point.ret1m,
                vol30 = point.vol30,
                volBps = point.vol30 * 10000.0,
                btcRet1m = btcRet,
                ethRet1m = ethRet,
                betaBtc = betaBtc,
                betaEth = betaEth,
                residualRet = residualRet,
                residualMomFast = residualMomFast,
                residualMomSlow = residualMomSlow,
                residualMomMedium = residualMomMedium,
                residualMomLong = residualMomLong,
                residualZ = residualZ,
                residualCrossSectionalZ = 0.0,
                reversionState = residualZ,
                reversionEntryLowerBound = -config.reversionZEntry,
                reversionEntryUpperBound = config.reversionZEntry,
                reversionExitLowerBound = -config.reversionZExit,
                reversionExitUpperBound = config.reversionZExit,
                imbalance = imbalance,
                volumeRatio = volumeRatio,
                depthRatio = depthRatio,
                volRegime = volRegime,
                flowSignal = flowSignal,
                mediumTrendScore = mediumTrendScore,
                trendConfirmationScore = trendConfirmationScore,
                trendPersistence = trendPersistence,
                trendPullback = trendPullback,
                trendExhaustion = trendExhaustion,
                rawTrend = rawTrend,
                trendExpectedGrossEdgeBps = trendExpectedGrossEdgeBps,
                reversionExpectedGrossEdgeBps = reversionExpectedGrossEdgeBps,
                liquid = liquid,
                executionObserved = point.executionObserved,
                fundingRate = point.fundingRate,
                fundingChange = point.fundingChange,
                openInterest = point.openInterest,
                openInterestNotionalUsd = point.openInterestNotionalUsd,
                oiChange = point.oiChange,
                oiAcceleration = point.oiAcceleration,
                assetContextObserved = point.assetContextObserved
            )
        }
    }

    val grouped = unranked.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val finalRows = mutableListOf<FeatureRow>()

    for (groupKey in orderedKeys) {
        val bucket = grouped[groupKey].orEmpty()
        if (bucket.isEmpty()) continue
        val breadthTilt = mean(bucket.map { clamp(it.rawTrend / 3.0, -1.0, 1.0) })
        val breadth = clamp((breadthTilt + 1.0) / 2.0, 0.0, 1.0)
        val marketStress = mean(bucket.map { it.volRegime })
        val comparableCohorts = bucket.groupBy(::reversionUniverseBucket)
        val minimumCohortSize = min(max(bucket.size / 8, 6), max(bucket.size, 1))
        val residualCrossSectionalZByRow = mutableMapOf<UnrankedFeature, Double>()

        bucket.forEach { row ->
            val cohort = comparableCohorts[reversionUniverseBucket(row)].orEmpty()
                .takeIf { it.size >= minimumCohortSize }
                ?: bucket
            val residualRetUniverse = cohort.map { it.residualRet }
            val fallbackScale = max(stdev(residualRetUniverse), max(row.vol30, 1e-6))
            residualCrossSectionalZByRow[row] = robustZScore(
                value = row.residualRet,
                values = residualRetUniverse,
                fallbackScale = fallbackScale
            )
        }
        val crowdingMetricsByRow = computeCrowdingContextMetrics(
            bucket = bucket,
            comparableCohorts = comparableCohorts,
            minimumCohortSize = minimumCohortSize
        )

        fun trendReference(row: UnrankedFeature): Double =
            if (abs(row.mediumTrendScore) > abs(row.rawTrend)) row.mediumTrendScore else row.rawTrend

        val reversionStateByRow = bucket.associateWith { row ->
            val crossSectionalComponent = residualCrossSectionalZByRow[row] ?: 0.0
            val blendedState = ((1.0 - config.reversionCrossSectionalWeight) * row.residualZ) +
                (config.reversionCrossSectionalWeight * crossSectionalComponent)
            clamp(blendedState, -6.0, 6.0)
        }
        val reversionEntryBoundsByRow = mutableMapOf<UnrankedFeature, Pair<Double, Double>>()
        val reversionExitBoundsByRow = mutableMapOf<UnrankedFeature, Pair<Double, Double>>()

        bucket.forEach { row ->
            val cohort = comparableCohorts[reversionUniverseBucket(row)].orEmpty()
                .takeIf { it.size >= minimumCohortSize }
                ?: bucket
            val stateUniverse = cohort.map { reversionStateByRow[it] ?: 0.0 }
            val entryLowerQuantile = percentile(stateUniverse, config.reversionEntryQuantile)
            val entryUpperQuantile = percentile(stateUniverse, 1.0 - config.reversionEntryQuantile)
            val exitLowerQuantile = percentile(stateUniverse, config.reversionExitQuantile)
            val exitUpperQuantile = percentile(stateUniverse, 1.0 - config.reversionExitQuantile)
            val entryLowerBound = min(entryLowerQuantile, -config.reversionZEntry)
            val entryUpperBound = max(entryUpperQuantile, config.reversionZEntry)
            val exitLowerBound = min(max(exitLowerQuantile, -config.reversionZExit), 0.0)
            val exitUpperBound = max(min(exitUpperQuantile, config.reversionZExit), 0.0)
            reversionEntryBoundsByRow[row] = entryLowerBound to entryUpperBound
            reversionExitBoundsByRow[row] = exitLowerBound to exitUpperBound
        }

        val trendScores = bucket.associateWith { row ->
            val mediumDirection = direction(row.mediumTrendScore)
            val reference = trendReference(row)
            val flowAlignment = direction(reference) * row.flowSignal
            val breadthAlignment = direction(reference) * breadthTilt
            val crowdingMetrics = crowdingMetricsByRow[row] ?: CrowdingContextMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val participation = crowdingMetrics.participationScore
            val crowding = crowdingMetrics.crowdingScore
            row.rawTrend +
                (row.mediumTrendScore * 0.85) +
                (mediumDirection * max(0.0, row.trendPersistence - 0.5) * 0.70) +
                (mediumDirection * min(row.trendPullback, 1.25) * 0.18) +
                (breadthAlignment * 0.9) +
                (max(0.0, flowAlignment) * 0.65) -
                (max(0.0, -flowAlignment) * 1.0) +
                (max(0.0, participation) * 0.58) -
                (max(0.0, -participation) * 0.82) +
                (max(0.0, crowding) * 0.28) -
                (max(0.0, -crowding) * 0.45) +
                min(max(0.0, row.depthRatio - 1.0) * 0.35, 0.55) +
                (max(0.0, min(row.volumeRatio, 3.0) - 1.0) * 0.22) -
                (max(0.0, row.trendExhaustion - 1.1) * 0.55) -
                (max(0.0, row.volRegime - 1.7) * 0.45)
        }
        val reversionScores = bucket.associateWith { row ->
            val reversionState = reversionStateByRow[row] ?: row.residualZ
            val continuationPressure = direction(reversionState) * row.flowSignal
            val breadthContinuation = direction(reversionState) * breadthTilt
            val mediumAlignment = direction(row.mediumTrendScore) * direction(reversionState)
            val reentryBonus = max(0.0, abs(row.mediumTrendScore) - abs(row.rawTrend)) * max(0.0, -mediumAlignment)
            val exhaustionBonus = max(0.0, abs(row.mediumTrendScore) - 0.6) * max(0.0, mediumAlignment)
            abs(reversionState) +
                (reentryBonus * 0.95) +
                (exhaustionBonus * 0.80) +
                (max(0.0, -continuationPressure) * 0.95) -
                (max(0.0, continuationPressure) * 1.25) -
                (max(0.0, abs(row.rawTrend) - 1.15) * 0.65) -
                (row.spreadBps / 35.0) -
                (max(0.0, breadthContinuation) * 1.15) -
                (max(0.0, row.volRegime - 1.55) * 0.60)
        }
        val trendExpectedEdges = bucket.associateWith { row ->
            val breadthAlignment = direction(trendReference(row)) * breadthTilt
            val crowdingMetrics = crowdingMetricsByRow[row] ?: CrowdingContextMetrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
            val participation = crowdingMetrics.participationScore
            val crowding = crowdingMetrics.crowdingScore
            clamp(
                row.trendExpectedGrossEdgeBps +
                    (max(0.0, participation) * 6.5) -
                    (max(0.0, -participation) * 8.0) +
                    (max(0.0, crowding) * 3.5) -
                    (max(0.0, -crowding) * 5.0) +
                    (max(0.0, breadthAlignment) * 6.0) -
                    (max(0.0, -breadthAlignment) * 8.0) -
                    (max(0.0, marketStress - 1.6) * 5.0),
                0.0,
                220.0
            )
        }
        val reversionExpectedEdges = bucket.associateWith { row ->
            val breadthContinuation = direction(reversionStateByRow[row] ?: row.residualZ) * breadthTilt
            clamp(
                row.reversionExpectedGrossEdgeBps +
                    (max(0.0, -breadthContinuation) * 5.0) -
                    (max(0.0, breadthContinuation) * 7.0) -
                    (max(0.0, marketStress - 1.5) * 6.0),
                0.0,
                220.0
            )
        }
        val trendLongRanks = trendScores.entries
            .filter { it.key.liquid && (trendExpectedEdges[it.key] ?: 0.0) > 0.0 }
            .sortedByDescending { it.value }
            .mapIndexed { index, entry -> entry.key to index + 1 }
            .toMap()
        val trendShortRanks = trendScores.entries
            .filter { it.key.liquid && (trendExpectedEdges[it.key] ?: 0.0) > 0.0 }
            .sortedBy { it.value }
            .mapIndexed { index, entry -> entry.key to index + 1 }
            .toMap()
        val reversionLongRanks = bucket
            .filter {
                val reversionState = reversionStateByRow[it] ?: it.residualZ
                it.liquid && reversionState < 0.0 && (reversionExpectedEdges[it] ?: 0.0) > 0.0
            }
            .sortedBy { reversionStateByRow[it] ?: it.residualZ }
            .mapIndexed { index, row -> row to index + 1 }
            .toMap()
        val reversionShortRanks = bucket
            .filter {
                val reversionState = reversionStateByRow[it] ?: it.residualZ
                it.liquid && reversionState > 0.0 && (reversionExpectedEdges[it] ?: 0.0) > 0.0
            }
            .sortedByDescending { reversionStateByRow[it] ?: it.residualZ }
            .mapIndexed { index, row -> row to index + 1 }
            .toMap()

        bucket.forEach { row ->
            val provisionalRow = FeatureRow(
                exchange = row.exchange,
                symbol = row.symbol,
                time = row.time,
                barIndex = row.barIndex,
                close = row.close,
                volume = row.volume,
                spreadPct = row.spreadPct,
                spreadBps = row.spreadBps,
                depthUsd = row.depthUsd,
                midPrice = row.midPrice,
                ret1m = row.ret1m,
                vol30 = row.vol30,
                volBps = row.volBps,
                btcRet1m = row.btcRet1m,
                ethRet1m = row.ethRet1m,
                betaBtc = row.betaBtc,
                betaEth = row.betaEth,
                residualRet = row.residualRet,
                residualMomFast = row.residualMomFast,
                residualMomSlow = row.residualMomSlow,
                residualMomMedium = row.residualMomMedium,
                residualMomLong = row.residualMomLong,
                residualZ = row.residualZ,
                residualCrossSectionalZ = (residualCrossSectionalZByRow[row] ?: 0.0).round(6),
                reversionState = (reversionStateByRow[row] ?: row.residualZ).round(6),
                reversionEntryLowerBound = (reversionEntryBoundsByRow[row]?.first ?: -config.reversionZEntry).round(6),
                reversionEntryUpperBound = (reversionEntryBoundsByRow[row]?.second ?: config.reversionZEntry).round(6),
                reversionExitLowerBound = (reversionExitBoundsByRow[row]?.first ?: -config.reversionZExit).round(6),
                reversionExitUpperBound = (reversionExitBoundsByRow[row]?.second ?: config.reversionZExit).round(6),
                imbalance = row.imbalance,
                volumeRatio = row.volumeRatio,
                depthRatio = row.depthRatio,
                volRegime = row.volRegime,
                flowSignal = row.flowSignal,
                breadth = breadth,
                mediumTrendScore = row.mediumTrendScore,
                trendConfirmationScore = row.trendConfirmationScore,
                trendPersistence = row.trendPersistence,
                trendPullback = row.trendPullback,
                trendExhaustion = row.trendExhaustion,
                rawTrend = row.rawTrend,
                trendScore = trendScores[row] ?: row.rawTrend,
                reversionScore = reversionScores[row] ?: 0.0,
                trendExpectedGrossEdgeBps = trendExpectedEdges[row] ?: row.trendExpectedGrossEdgeBps,
                reversionExpectedGrossEdgeBps = reversionExpectedEdges[row] ?: row.reversionExpectedGrossEdgeBps,
                trendTargetExposureFraction = 0.0,
                reversionTargetExposureFraction = 0.0,
                liquid = row.liquid,
                trendLongRank = trendLongRanks[row] ?: Int.MAX_VALUE,
                trendShortRank = trendShortRanks[row] ?: Int.MAX_VALUE,
                reversionLongRank = reversionLongRanks[row] ?: Int.MAX_VALUE,
                reversionShortRank = reversionShortRanks[row] ?: Int.MAX_VALUE,
                executionObserved = row.executionObserved,
                fundingRate = row.fundingRate,
                fundingZ = (crowdingMetricsByRow[row]?.fundingZ ?: 0.0).round(6),
                fundingChangeZ = (crowdingMetricsByRow[row]?.fundingChangeZ ?: 0.0).round(6),
                openInterest = row.openInterest,
                openInterestNotionalUsd = row.openInterestNotionalUsd.round(4),
                oiChange = row.oiChange.round(6),
                oiChangeZ = (crowdingMetricsByRow[row]?.oiChangeZ ?: 0.0).round(6),
                oiAccelerationZ = (crowdingMetricsByRow[row]?.oiAccelerationZ ?: 0.0).round(6),
                oiNotionalZ = (crowdingMetricsByRow[row]?.oiNotionalZ ?: 0.0).round(6),
                crowdingScore = (crowdingMetricsByRow[row]?.crowdingScore ?: 0.0).round(6),
                participationScore = (crowdingMetricsByRow[row]?.participationScore ?: 0.0).round(6),
                assetContextObserved = row.assetContextObserved
            )
            val trendSide = direction(provisionalRow.trendScore).toInt()
            val reversionSide = (-direction(provisionalRow.reversionState)).toInt()
            val trendNetEdge = provisionalRow.trendExpectedGrossEdgeBps -
                buildExpectedRoundTripCostBps(
                    provisionalRow,
                    buildExecutionEstimate(
                        provisionalRow,
                        config.notionalUsd,
                        if (trendSide == 0) 1 else trendSide,
                        StrategyKind.TREND
                    )
                )
            val reversionNetEdge = provisionalRow.reversionExpectedGrossEdgeBps -
                buildExpectedRoundTripCostBps(
                    provisionalRow,
                    buildExecutionEstimate(
                        provisionalRow,
                        config.notionalUsd,
                        if (reversionSide == 0) 1 else reversionSide,
                        StrategyKind.REVERSION
                    )
                )
            val trendSizing = targetExposureFraction(
                kind = StrategyKind.TREND,
                row = provisionalRow,
                side = if (trendSide == 0) 1 else trendSide,
                expectedNetEdgeBps = trendNetEdge,
                config = config
            )
            val reversionSizing = targetExposureFraction(
                kind = StrategyKind.REVERSION,
                row = provisionalRow,
                side = if (reversionSide == 0) 1 else reversionSide,
                expectedNetEdgeBps = reversionNetEdge,
                config = config
            )
            finalRows += provisionalRow.copy(
                trendTargetExposureFraction = trendSizing.first,
                reversionTargetExposureFraction = reversionSizing.first
            )
        }
    }

    return finalRows.sortedWith(compareBy<FeatureRow> { it.time }.thenBy { it.exchange }.thenBy { it.symbol })
}

fun buildExecutionEstimate(row: FeatureRow, notionalUsd: Double, side: Int, kind: StrategyKind): ExecutionEstimate {
    val spreadHalfBps = row.spreadBps / 2.0
    val depthPressure = notionalUsd / max(row.depthUsd, notionalUsd)
    val volatilityPenalty = min(row.volBps / 70.0, 4.0)
    val imbalanceAgainstTrade = max(0.0, -side.toDouble() * row.imbalance)
    val imbalanceWithTrade = max(0.0, side.toDouble() * row.imbalance)
    val flowAgainstTrade = max(0.0, -side.toDouble() * row.flowSignal)
    val makerFeeBps = 1.0
    val takerFeeBps = 4.0
    var makerShare = clamp(
        (if (kind == StrategyKind.REVERSION) 0.62 else 0.52) -
            (depthPressure * 0.32) -
            (volatilityPenalty * 0.03) -
            (flowAgainstTrade * 0.05) +
            (imbalanceWithTrade * 0.05) -
            (spreadHalfBps / 45.0),
        0.12,
        0.88
    )
    var fillRatio = clamp(
        0.96 -
            (spreadHalfBps / 30.0) -
            (depthPressure * 2.8) -
            (volatilityPenalty * 0.04) -
            (flowAgainstTrade * 0.05) -
            (imbalanceAgainstTrade * 0.03) +
            (imbalanceWithTrade * 0.02),
        0.25,
        1.0
    )
    var slippageBps = clamp(
        0.20 +
            (spreadHalfBps * 0.18) +
            (depthPressure * 9.0) +
            (volatilityPenalty * 0.70) +
            (flowAgainstTrade * 1.9) +
            (imbalanceAgainstTrade * 0.8) -
            (makerShare * 0.70),
        0.15,
        18.0
    )
    var impactBps = clamp(
        0.15 +
            (depthPressure * 14.0) +
            (max(0.0, row.volumeRatio - 2.0) * 0.9) +
            (max(0.0, row.volRegime - 1.3) * 1.8),
        0.10,
        22.0
    )
    var adverseSelectionBps = clamp(
        (abs(row.residualZ) * 0.35) +
            (flowAgainstTrade * 2.5) +
            (max(0.0, row.volRegime - 1.0) * 2.2) +
            (max(0.0, abs(row.rawTrend) - 1.5) * 1.2),
        0.0,
        10.0
    )
    if (!row.executionObserved) {
        makerShare = clamp(makerShare - 0.10, 0.05, 0.78)
        fillRatio = clamp(fillRatio - 0.08, 0.18, 0.96)
        slippageBps = clamp((slippageBps * 1.18) + 0.9, 0.25, 24.0)
        impactBps = clamp((impactBps * 1.20) + 1.1, 0.15, 28.0)
        adverseSelectionBps = clamp((adverseSelectionBps * 1.15) + 0.6, 0.0, 12.0)
    }
    val feeBps = (makerFeeBps * makerShare) + (takerFeeBps * (1.0 - makerShare))
    val totalCostBps = feeBps + spreadHalfBps + slippageBps + impactBps + adverseSelectionBps
    return ExecutionEstimate(
        fillRatio = fillRatio,
        feeBps = feeBps,
        feeTier = if (makerShare >= 0.5) "retail_mixed_maker_bias" else "retail_mixed_taker_bias",
        feeTierAdjustmentBps = (feeBps - takerFeeBps).round(4),
        makerFeeBps = makerFeeBps,
        takerFeeBps = takerFeeBps,
        spreadCostBps = spreadHalfBps,
        slippageBps = slippageBps,
        impactBps = impactBps,
        adverseSelectionBps = adverseSelectionBps,
        fundingDriftBps = 0.0,
        basisDriftBps = 0.0,
        totalCostBps = totalCostBps,
        estimatedFeeUsd = notionalUsd * feeBps / 10000.0,
        estimatedCostUsd = notionalUsd * totalCostBps / 10000.0
    )
}

fun buildExpectedRoundTripCostBps(row: FeatureRow, entryEstimate: ExecutionEstimate): Double {
    val regimeBuffer = max(0.0, row.volRegime - 1.0) * 1.75
    val flowBuffer = max(0.0, abs(row.flowSignal) - 0.8) * 1.40
    return (entryEstimate.totalCostBps * 2.0) + regimeBuffer + flowBuffer
}

fun reversionUniverseBucket(row: UnrankedFeature): String {
    val liquidityScore = min(((min(row.depthRatio, 3.0) + min(row.volumeRatio, 3.0)) / 2.0), 3.0)
    val liquidityBucket = when {
        liquidityScore < 0.9 -> "thin"
        liquidityScore < 1.45 -> "normal"
        else -> "deep"
    }
    return "$liquidityBucket|${volatilityRegimeBucket(row.volRegime)}"
}

fun breadthTilt(row: FeatureRow): Double =
    (row.breadth - 0.5) * 2.0

private fun confidenceToExposureFraction(confidence: Double, config: ResearchConfig): Double =
    clamp(
        config.minTargetExposureFraction +
            ((config.maxTargetExposureFraction - config.minTargetExposureFraction) * clamp(confidence, 0.0, 1.0)),
        config.minTargetExposureFraction,
        config.maxTargetExposureFraction
    ).round(4)

fun targetExposureFraction(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    expectedNetEdgeBps: Double,
    config: ResearchConfig
): Pair<Double, Double> {
    if (side == 0) return config.minTargetExposureFraction.round(4) to 0.0
    val expectedEdgeConfidence = clamp(
        expectedNetEdgeBps / max(config.minExpectedNetEdgeBps + 12.0, 20.0),
        0.0,
        1.0
    )
    val confidence = when (kind) {
        StrategyKind.TREND -> {
            val signalStrength = clamp((side.toDouble() * row.trendScore) / max(config.trendEntryScore * 2.0, 1.0), 0.0, 1.25)
            val confirmation = clamp(row.trendConfirmationScore / 3.0, 0.0, 1.25)
            val flowAlignment = clamp((side.toDouble() * row.flowSignal) / 1.25, 0.0, 1.0)
            val breadthAlignment = clamp((side.toDouble() * breadthTilt(row) + 0.35) / 1.35, 0.0, 1.0)
            val persistence = clamp(row.trendPersistence, 0.0, 1.0)
            val pullbackSupport = clamp(row.trendPullback / 1.15, 0.0, 1.0)
            val exhaustionPenalty = clamp(max(0.0, row.trendExhaustion - 0.9) / 1.2, 0.0, 1.0)
            clamp(
                (signalStrength * 0.28) +
                    (confirmation * 0.24) +
                    (persistence * 0.16) +
                    (flowAlignment * 0.10) +
                    (breadthAlignment * 0.08) +
                    (pullbackSupport * 0.06) +
                    (expectedEdgeConfidence * 0.18) -
                    (exhaustionPenalty * 0.18),
                0.0,
                1.0
            )
        }
        StrategyKind.REVERSION -> {
            val entryBound = if (side > 0) abs(row.reversionEntryLowerBound) else abs(row.reversionEntryUpperBound)
            val exitBound = if (side > 0) abs(row.reversionExitLowerBound) else abs(row.reversionExitUpperBound)
            val stateAbs = abs(row.reversionState)
            val penetration = clamp((stateAbs - entryBound) / max(entryBound, 0.35), 0.0, 1.4)
            val traversal = clamp(
                (stateAbs - exitBound) / max(entryBound - exitBound, 0.2),
                0.0,
                1.25
            )
            val antiContinuation = clamp(
                ((-direction(row.reversionState) * row.flowSignal) + 0.35) / 1.4,
                0.0,
                1.0
            )
            val pullbackSupport = clamp(max(row.trendPullback, row.trendExhaustion) / 1.1, 0.0, 1.0)
            val rawTrendPenalty = clamp(abs(row.rawTrend) / max(config.trendEntryScore * 1.6, 1.0), 0.0, 1.0)
            val scoreStrength = clamp(row.reversionScore / 3.0, 0.0, 1.2)
            clamp(
                (penetration * 0.28) +
                    (traversal * 0.18) +
                    (antiContinuation * 0.14) +
                    (pullbackSupport * 0.10) +
                    (scoreStrength * 0.14) +
                    (expectedEdgeConfidence * 0.18) -
                    (rawTrendPenalty * 0.16),
                0.0,
                1.0
            )
        }
    }
    return confidenceToExposureFraction(confidence, config) to confidence.round(4)
}

private fun buildSizedCandidate(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    expectedGrossEdgeBps: Double,
    cappedNetEdgeBps: Double,
    config: ResearchConfig,
    calibrationSamples: Int = 0,
    calibrationWinRate: Double = 0.0,
    calibrationLowerBoundBps: Double = 0.0,
    calibrationScope: String = "heuristic"
): EntryCandidate {
    val sizing = targetExposureFraction(kind, row, side, cappedNetEdgeBps, config)
    val targetExposureFraction = sizing.first
    val scaledNotionalUsd = config.notionalUsd * targetExposureFraction
    val entryEstimate = buildExecutionEstimate(row, scaledNotionalUsd, side, kind)
    val expectedRoundTripCostBps = buildExpectedRoundTripCostBps(row, entryEstimate)
    val feasibleNetEdgeBps = max(0.0, expectedGrossEdgeBps - expectedRoundTripCostBps)
    val expectedNetEdgeBps = min(max(cappedNetEdgeBps, 0.0), feasibleNetEdgeBps).round(4)
    return EntryCandidate(
        row = row,
        side = side,
        entryEstimate = entryEstimate,
        expectedGrossEdgeBps = expectedGrossEdgeBps.round(4),
        expectedRoundTripCostBps = expectedRoundTripCostBps.round(4),
        expectedNetEdgeBps = expectedNetEdgeBps,
        targetExposureFraction = targetExposureFraction,
        signalConfidence = sizing.second,
        calibrationSamples = calibrationSamples,
        calibrationWinRate = calibrationWinRate,
        calibrationLowerBoundBps = calibrationLowerBoundBps,
        calibrationScope = calibrationScope
    )
}

fun volatilityRegimeBucket(volRegime: Double): String =
    when {
        volRegime < 0.95 -> "calm"
        volRegime < 1.45 -> "normal"
        else -> "stress"
    }

fun calibrationRegimeBucket(row: FeatureRow): String =
    volatilityRegimeBucket(row.volRegime)

fun tradeRegimeBucket(trade: TradeRecord): String =
    volatilityRegimeBucket(trade.entryVolRegime)

fun calibrationSignalBucket(kind: StrategyKind, row: FeatureRow): String =
    when (kind) {
        StrategyKind.TREND -> when {
            max(abs(row.trendScore), abs(row.mediumTrendScore)) < 1.35 -> "entry"
            max(abs(row.trendScore), abs(row.mediumTrendScore)) < 1.90 -> "strong"
            else -> "extreme"
        }
        StrategyKind.REVERSION -> when {
            abs(row.reversionState) < max(abs(row.reversionEntryLowerBound), abs(row.reversionEntryUpperBound)) * 1.2 -> "entry"
            abs(row.reversionState) < max(abs(row.reversionEntryLowerBound), abs(row.reversionEntryUpperBound)) * 1.8 -> "deep"
            else -> "extreme"
        }
    }

fun calibrationConfirmationBucket(kind: StrategyKind, row: FeatureRow, side: Int, config: ResearchConfig): String {
    val flowAlignment = side.toDouble() * row.flowSignal
    val fastAlignment = side.toDouble() * direction(row.residualMomFast)
    val slowAlignment = side.toDouble() * direction(row.residualMomSlow)
    val mediumAlignment = side.toDouble() * direction(row.mediumTrendScore)
    val continuationPressure = direction(row.reversionState) * row.flowSignal
    return when (kind) {
        StrategyKind.TREND -> when {
            flowAlignment >= config.trendMinFlowAlignment &&
                slowAlignment > 0.0 &&
                mediumAlignment > 0.0 -> "confirmed"
            flowAlignment >= -0.04 && (fastAlignment > 0.0 || slowAlignment > 0.0 || mediumAlignment > 0.0) -> "mixed"
            else -> "fragile"
        }
        StrategyKind.REVERSION -> when {
            row.trendPullback >= 0.35 || row.trendExhaustion >= 0.55 -> "confirmed"
            flowAlignment >= 0.08 && fastAlignment > 0.0 -> "confirmed"
            continuationPressure <= (config.reversionMaxContinuationPressure * 0.55) &&
                flowAlignment >= -0.04 &&
                abs(row.rawTrend) < (config.trendEntryScore * 0.95) -> "stall"
            else -> "fragile"
        }
    }
}

fun calibrationBaseKey(kind: StrategyKind, row: FeatureRow, side: Int, config: ResearchConfig): CalibrationKey =
    CalibrationKey(
        strategyKind = kind,
        exchange = row.exchange,
        symbol = row.symbol,
        side = side,
        regimeBucket = calibrationRegimeBucket(row),
        signalBucket = calibrationSignalBucket(kind, row),
        confirmationBucket = calibrationConfirmationBucket(kind, row, side, config)
    )

fun calibrationScopesForKey(key: CalibrationKey): List<Pair<String, CalibrationKey>> =
    listOf(
        "symbol_regime_signal_confirmation" to key,
        "market_regime_signal_confirmation" to key.copy(symbol = "ALL"),
        "symbol_regime_confirmation" to key.copy(signalBucket = "ALL"),
        "market_regime_confirmation" to key.copy(symbol = "ALL", signalBucket = "ALL"),
        "symbol_confirmation" to key.copy(regimeBucket = "ALL", signalBucket = "ALL"),
        "market_confirmation" to key.copy(symbol = "ALL", regimeBucket = "ALL", signalBucket = "ALL"),
        "symbol_all" to key.copy(regimeBucket = "ALL", signalBucket = "ALL", confirmationBucket = "ALL"),
        "market_all" to key.copy(symbol = "ALL", regimeBucket = "ALL", signalBucket = "ALL", confirmationBucket = "ALL")
    )

fun calibrationScopesForRow(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    config: ResearchConfig
): List<Pair<String, CalibrationKey>> =
    calibrationScopesForKey(calibrationBaseKey(kind, row, side, config))

fun CalibrationAccumulator.applyExample(example: CalibrationExample, multiplier: Int) {
    val factor = multiplier.toDouble()
    samples += multiplier
    wins += if (example.netEdgeBps > 0.0) multiplier else 0
    sumGrossEdgeBps += example.grossEdgeBps * factor
    sumNetEdgeBps += example.netEdgeBps * factor
    sumNetEdgeSqBps += example.netEdgeBps.pow(2.0) * factor
    sumTotalCostBps += example.totalCostBps * factor
    sumFillRatio += example.fillRatio * factor
}

fun CalibrationAccumulator.toStats(scope: String): CalibrationStats? {
    if (samples <= 0) return null
    val sampleCount = samples.toDouble()
    val avgNetEdgeBps = sumNetEdgeBps / sampleCount
    val variance = max((sumNetEdgeSqBps / sampleCount) - avgNetEdgeBps.pow(2.0), 0.0)
    val stderr = sqrt(variance / sampleCount)
    return CalibrationStats(
        samples = samples,
        winRate = wins.toDouble() / sampleCount,
        avgGrossEdgeBps = sumGrossEdgeBps / sampleCount,
        avgNetEdgeBps = avgNetEdgeBps,
        avgTotalCostBps = sumTotalCostBps / sampleCount,
        avgFillRatio = sumFillRatio / sampleCount,
        lowerBoundNetEdgeBps = avgNetEdgeBps - (1.28 * stderr),
        scope = scope
    )
}

fun addCalibrationExample(state: CalibrationState, example: CalibrationExample) {
    calibrationScopesForKey(example.key).forEach { (_, scopedKey) ->
        state.scoped.getOrPut(scopedKey) { CalibrationAccumulator() }.applyExample(example, 1)
    }
}

fun removeCalibrationExample(state: CalibrationState, example: CalibrationExample) {
    calibrationScopesForKey(example.key).forEach { (_, scopedKey) ->
        val accumulator = state.scoped[scopedKey] ?: return@forEach
        accumulator.applyExample(example, -1)
        if (accumulator.samples <= 0) {
            state.scoped.remove(scopedKey)
        }
    }
}

fun buildCalibrationState(examples: List<CalibrationExample>): CalibrationState {
    val state = CalibrationState()
    examples.forEach { addCalibrationExample(state, it) }
    return state
}

fun blendCalibrationStats(primary: CalibrationStats, fallback: CalibrationStats, config: ResearchConfig): CalibrationStats {
    if (primary.scope == fallback.scope) return primary
    val weight = clamp(primary.samples.toDouble() / max(config.strongCalibrationSamples, 1).toDouble(), 0.0, 1.0)
    return CalibrationStats(
        samples = primary.samples,
        winRate = (primary.winRate * weight) + (fallback.winRate * (1.0 - weight)),
        avgGrossEdgeBps = (primary.avgGrossEdgeBps * weight) + (fallback.avgGrossEdgeBps * (1.0 - weight)),
        avgNetEdgeBps = (primary.avgNetEdgeBps * weight) + (fallback.avgNetEdgeBps * (1.0 - weight)),
        avgTotalCostBps = (primary.avgTotalCostBps * weight) + (fallback.avgTotalCostBps * (1.0 - weight)),
        avgFillRatio = (primary.avgFillRatio * weight) + (fallback.avgFillRatio * (1.0 - weight)),
        lowerBoundNetEdgeBps = (primary.lowerBoundNetEdgeBps * weight) + (fallback.lowerBoundNetEdgeBps * (1.0 - weight)),
        scope = primary.scope
    )
}

fun conservativeCalibrationNetEdgeBps(
    calibration: CalibrationStats,
    config: ResearchConfig
): Double {
    val confidence = clamp(
        (calibration.samples - config.minCalibrationSamples).toDouble() /
            max(config.strongCalibrationSamples - config.minCalibrationSamples, 1).toDouble(),
        0.0,
        1.0
    )
    val scopeBonus = when {
        calibration.scope.startsWith("symbol_regime_signal_confirmation") -> 0.10
        calibration.scope.startsWith("symbol_regime") || calibration.scope.startsWith("symbol_confirmation") -> 0.05
        calibration.scope.startsWith("market_regime") -> 0.02
        else -> 0.0
    }
    val avgWeight = clamp(0.15 + (confidence * 0.35) + scopeBonus, 0.15, 0.60)
    return max(
        (calibration.lowerBoundNetEdgeBps * (1.0 - avgWeight)) +
            (calibration.avgNetEdgeBps * avgWeight),
        0.0
    )
}

fun resolveCalibration(
    state: CalibrationState?,
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    config: ResearchConfig
): CalibrationStats? {
    if (state == null) return null
    val scopedStats = calibrationScopesForRow(kind, row, side, config)
        .mapNotNull { (scope, key) -> state.scoped[key]?.toStats(scope) }
    if (scopedStats.isEmpty()) return null
    val fallback = scopedStats.last()
    val primary = scopedStats.firstOrNull { it.samples >= config.minCalibrationSamples }
        ?: fallback.takeIf { it.samples >= config.minCalibrationSamples }
        ?: return null
    return blendCalibrationStats(primary, fallback, config)
}

fun buildStructuralCandidate(kind: StrategyKind, row: FeatureRow, side: Int, config: ResearchConfig): EntryCandidate? {
    if (!row.liquid) return null
    if (row.volumeRatio < config.minVolumeRatio || row.volumeRatio > config.maxVolumeRatio) return null
    if (row.volRegime > config.maxVolRegime) return null

    val expectedGrossEdgeBps = when (kind) {
        StrategyKind.TREND -> row.trendExpectedGrossEdgeBps
        StrategyKind.REVERSION -> row.reversionExpectedGrossEdgeBps
    }
    val rowBreadthTilt = breadthTilt(row)
    val continuationPressure = direction(row.reversionState) * row.flowSignal

    when (kind) {
        StrategyKind.TREND -> {
            val flowAlignment = side.toDouble() * row.flowSignal
            val breadthAlignment = side.toDouble() * rowBreadthTilt
            val mediumAlignment = side.toDouble() * row.mediumTrendScore
            val pullbackAllowance = if (mediumAlignment > 0.55 && row.trendPersistence >= 0.5) 1.45 else 1.05
            if ((side.toDouble() * row.trendScore) < config.trendEntryScore) return null
            if ((side.toDouble() * row.rawTrend) <= 0.0 && mediumAlignment < 0.35) return null
            if (mediumAlignment <= 0.0) return null
            if (flowAlignment < config.trendMinFlowAlignment && mediumAlignment < 0.65) return null
            if (breadthAlignment < -0.05 && mediumAlignment < 0.65) return null
            if ((side.toDouble() * direction(row.residualMomFast)) <= 0.0 && mediumAlignment < 0.75) return null
            if ((side.toDouble() * direction(row.residualMomSlow)) <= 0.0) return null
            if ((side.toDouble() * direction(row.residualMomMedium)) <= 0.0) return null
            if (abs(row.reversionState) > max(abs(row.reversionEntryLowerBound), abs(row.reversionEntryUpperBound)) * pullbackAllowance) {
                return null
            }
        }
        StrategyKind.REVERSION -> {
            val trendAwareRawTrendCap = if (row.trendPullback > 0.35 || row.trendExhaustion > 0.75) {
                config.trendEntryScore * 1.55
            } else {
                config.trendEntryScore * 1.10
            }
            val continuationCap = if (row.trendPullback > 0.35 || row.trendExhaustion > 0.75) {
                config.reversionMaxContinuationPressure * 1.10
            } else {
                config.reversionMaxContinuationPressure * 0.75
            }
            val breadthCap = if (row.trendPullback > 0.35 || row.trendExhaustion > 0.75) 0.30 else 0.15
            if (side > 0 && row.reversionState > row.reversionEntryLowerBound) return null
            if (side < 0 && row.reversionState < row.reversionEntryUpperBound) return null
            if (row.reversionScore <= 0.0) return null
            if (abs(row.rawTrend) > trendAwareRawTrendCap) return null
            if (continuationPressure > continuationCap) return null
            if (direction(row.reversionState) * rowBreadthTilt > breadthCap) return null
            if ((side.toDouble() * row.flowSignal) < -0.08 && row.trendPullback < 0.35 && row.trendExhaustion < 0.55) return null
            if ((side.toDouble() * direction(row.residualMomFast)) < 0.0 &&
                abs(row.rawTrend) > (config.trendEntryScore * 0.85) &&
                row.trendPullback < 0.35
            ) return null
        }
    }

    val baseSizing = targetExposureFraction(kind, row, side, expectedGrossEdgeBps, config)
    val scaledEntryEstimate = buildExecutionEstimate(row, config.notionalUsd * baseSizing.first, side, kind)
    if (scaledEntryEstimate.fillRatio < config.minFillRatio) return null
    val expectedRoundTripCostBps = buildExpectedRoundTripCostBps(row, scaledEntryEstimate)
    val expectedNetEdgeBps = expectedGrossEdgeBps - expectedRoundTripCostBps
    return buildSizedCandidate(
        kind = kind,
        row = row,
        side = side,
        expectedGrossEdgeBps = expectedGrossEdgeBps,
        cappedNetEdgeBps = expectedNetEdgeBps,
        config = config
    )
}

fun buildEntryCandidate(
    kind: StrategyKind,
    row: FeatureRow,
    side: Int,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): EntryCandidate? {
    val seed = buildStructuralCandidate(kind, row, side, config) ?: return null
    val safetyMarginBps = config.executionSafetyMarginBps + (max(0.0, row.volRegime - 1.0) * 2.5)

    if (calibrationState == null) {
        if (seed.expectedNetEdgeBps < config.minExpectedNetEdgeBps) return null
        if (seed.expectedGrossEdgeBps < seed.expectedRoundTripCostBps + safetyMarginBps) return null
        return seed
    }

    val calibration = resolveCalibration(calibrationState, kind, row, side, config) ?: return null
    if (calibration.avgFillRatio < config.minFillRatio) return null
    if (calibration.winRate < config.minCalibrationWinRate) return null
    if (calibration.lowerBoundNetEdgeBps < config.minCalibrationLowerBoundBps) return null
    val calibratedNetEdgeBps = min(
        seed.expectedNetEdgeBps,
        conservativeCalibrationNetEdgeBps(calibration, config)
    )
    if (calibratedNetEdgeBps < config.minExpectedNetEdgeBps) return null

    val calibratedGrossEdgeBps = seed.expectedRoundTripCostBps + calibratedNetEdgeBps
    if (calibratedNetEdgeBps < safetyMarginBps) return null

    return buildSizedCandidate(
        kind = kind,
        row = row,
        side = side,
        expectedGrossEdgeBps = calibratedGrossEdgeBps.round(4),
        cappedNetEdgeBps = calibratedNetEdgeBps.round(4),
        config = config,
        calibrationSamples = calibration.samples,
        calibrationWinRate = calibration.winRate.round(4),
        calibrationLowerBoundBps = calibration.lowerBoundNetEdgeBps.round(4),
        calibrationScope = calibration.scope
    )
}

fun shouldExitPosition(kind: StrategyKind, position: OpenPosition, current: FeatureRow, config: ResearchConfig): Boolean =
    baseExitTriggered(kind, position, current, config) ||
        trailingStopTriggered(kind, position, current, config) ||
        takeProfitTriggered(kind, position, current, config)

private fun baseExitTriggered(
    kind: StrategyKind,
    position: OpenPosition,
    current: FeatureRow,
    config: ResearchConfig
): Boolean =
    when (kind) {
        StrategyKind.TREND -> {
            val ageBars = current.barIndex - position.entryRow.barIndex
            ageBars >= config.trendHoldBars ||
                ((current.trendScore * position.side.toDouble()) <= 0.12 &&
                    (current.mediumTrendScore * position.side.toDouble()) <= 0.10) ||
                ((position.side.toDouble() * current.flowSignal) < -0.18 && current.trendPullback < 0.35) ||
                current.volRegime > (config.maxVolRegime * 1.15)
        }
        StrategyKind.REVERSION -> {
            val ageBars = current.barIndex - position.entryRow.barIndex
            ageBars >= config.reversionHoldBars ||
                current.reversionState in current.reversionExitLowerBound..current.reversionExitUpperBound ||
                (current.reversionState * position.side.toDouble()) >= -0.05 ||
                (direction(position.entryRow.reversionState) * current.flowSignal) > config.reversionMaxContinuationPressure
        }
    }

private fun positionSignedReturnFraction(position: OpenPosition, current: FeatureRow): Double {
    if (position.entryRow.close <= 0.0 || current.close <= 0.0) return 0.0
    return position.side.toDouble() * ((current.close / position.entryRow.close) - 1.0)
}

private fun exitDistanceFraction(position: OpenPosition, current: FeatureRow, multiple: Double): Double {
    if (!multiple.isFinite() || multiple <= 0.0) return Double.POSITIVE_INFINITY
    val referenceVol = max(max(position.entryRow.vol30, current.vol30), 1e-6)
    return multiple * referenceVol
}

private fun trailingStopMultiple(kind: StrategyKind, config: ResearchConfig): Double =
    when (kind) {
        StrategyKind.TREND -> config.trendTrailingStopVolMultiple
        StrategyKind.REVERSION -> config.reversionTrailingStopVolMultiple
    }

private fun takeProfitMultiple(kind: StrategyKind, config: ResearchConfig): Double =
    when (kind) {
        StrategyKind.TREND -> config.trendTakeProfitVolMultiple
        StrategyKind.REVERSION -> config.reversionTakeProfitVolMultiple
    }

private fun trailingStopTriggered(
    kind: StrategyKind,
    position: OpenPosition,
    current: FeatureRow,
    config: ResearchConfig
): Boolean {
    val distance = exitDistanceFraction(position, current, trailingStopMultiple(kind, config))
    if (!distance.isFinite()) return false
    val favorableReturn = positionSignedReturnFraction(position, current)
    return position.maxFavorableReturnFraction >= distance &&
        (position.maxFavorableReturnFraction - favorableReturn) >= distance
}

private fun takeProfitTriggered(
    kind: StrategyKind,
    position: OpenPosition,
    current: FeatureRow,
    config: ResearchConfig
): Boolean {
    val distance = exitDistanceFraction(position, current, takeProfitMultiple(kind, config))
    if (!distance.isFinite()) return false
    return positionSignedReturnFraction(position, current) >= distance
}

private fun updateOpenPosition(position: OpenPosition, current: FeatureRow): OpenPosition =
    position.copy(
        maxFavorableReturnFraction = max(
            position.maxFavorableReturnFraction,
            positionSignedReturnFraction(position, current)
        )
    )

fun seedCandidateRows(kind: StrategyKind, bucket: List<FeatureRow>, config: ResearchConfig): List<EntryCandidate> =
    when (kind) {
        StrategyKind.TREND -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.trendLongRank <= config.topPerSide &&
                        it.trendScore >= config.trendEntryScore &&
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.TREND, it, 1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.trendShortRank <= config.topPerSide &&
                        it.trendScore <= -config.trendEntryScore &&
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.TREND, it, -1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
        StrategyKind.REVERSION -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.reversionLongRank <= config.topPerSide &&
                        it.reversionState <= it.reversionEntryLowerBound &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.REVERSION, it, 1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.reversionShortRank <= config.topPerSide &&
                        it.reversionState >= it.reversionEntryUpperBound &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildStructuralCandidate(StrategyKind.REVERSION, it, -1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
    }

fun candidateRows(
    kind: StrategyKind,
    bucket: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): List<EntryCandidate> =
    when (kind) {
        StrategyKind.TREND -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.trendLongRank <= config.topPerSide &&
                        it.trendScore >= config.trendEntryScore &&
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.TREND, it, 1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.trendShortRank <= config.topPerSide &&
                        it.trendScore <= -config.trendEntryScore &&
                        abs(it.reversionState) <= max(abs(it.reversionEntryLowerBound), abs(it.reversionEntryUpperBound)) * 1.25
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.TREND, it, -1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
        StrategyKind.REVERSION -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.reversionLongRank <= config.topPerSide &&
                        it.reversionState <= it.reversionEntryLowerBound &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.REVERSION, it, 1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.reversionShortRank <= config.topPerSide &&
                        it.reversionState >= it.reversionEntryUpperBound &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.REVERSION, it, -1, config, calibrationState) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
    }

fun latestSignalSnapshots(
    rows: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): List<SignalSnapshot> =
    rows.groupBy { it.exchange to it.symbol }
        .values
        .mapNotNull { series -> series.maxByOrNull { it.time } }
        .map { row ->
            val trendLong = if (
                row.liquid &&
                    row.trendLongRank <= config.topPerSide &&
                    row.trendScore >= config.trendEntryScore
            ) {
                buildEntryCandidate(StrategyKind.TREND, row, 1, config, calibrationState)
            } else {
                null
            }
            val trendShort = if (
                row.liquid &&
                    row.trendShortRank <= config.topPerSide &&
                    row.trendScore <= -config.trendEntryScore
            ) {
                buildEntryCandidate(StrategyKind.TREND, row, -1, config, calibrationState)
            } else {
                null
            }
            val trendCandidate = listOfNotNull(trendLong, trendShort).maxByOrNull { it.expectedNetEdgeBps }
            SignalSnapshot(
                exchange = row.exchange,
                symbol = row.symbol,
                time = row.time.toString(),
                lastPrice = row.close.round(4),
                betaBtc = row.betaBtc.round(4),
                betaEth = row.betaEth.round(4),
                residualZ = row.residualZ.round(4),
                residualCrossSectionalZ = row.residualCrossSectionalZ.round(4),
                mediumTrendScore = row.mediumTrendScore.round(4),
                trendConfirmationScore = row.trendConfirmationScore.round(4),
                trendPersistence = row.trendPersistence.round(4),
                trendPullback = row.trendPullback.round(4),
                trendExhaustion = row.trendExhaustion.round(4),
                trendScore = row.trendScore.round(4),
                breadth = row.breadth.round(4),
                spreadBps = row.spreadBps.round(2),
                depthUsd = row.depthUsd.round(2),
                imbalance = row.imbalance.round(4),
                flowSignal = row.flowSignal.round(4),
                volumeRatio = row.volumeRatio.round(4),
                volRegime = row.volRegime.round(4),
                expectedNetEdgeBps = (trendCandidate?.expectedNetEdgeBps ?: 0.0).round(2),
                targetExposureFraction = (trendCandidate?.targetExposureFraction ?: row.trendTargetExposureFraction).round(4),
                calibrationSamples = trendCandidate?.calibrationSamples ?: 0,
                calibrationLowerBoundBps = (trendCandidate?.calibrationLowerBoundBps ?: 0.0).round(2),
                liquid = row.liquid,
                action = when (trendCandidate?.side) {
                    1 -> "LONG"
                    -1 -> "SHORT"
                    else -> "FLAT"
                },
                fundingZ = row.fundingZ.round(4),
                oiChangeZ = row.oiChangeZ.round(4),
                crowdingScore = row.crowdingScore.round(4),
                participationScore = row.participationScore.round(4)
            )
        }
        .sortedWith(
            compareByDescending<SignalSnapshot> { it.expectedNetEdgeBps }
                .thenByDescending { it.calibrationLowerBoundBps }
                .thenByDescending { abs(it.trendScore) }
                .thenBy { it.exchange }
                .thenBy { it.symbol }
        )

fun buildTradeRecord(position: OpenPosition, current: FeatureRow, config: ResearchConfig): TradeRecord {
    val kind = position.strategyKind
    val entryNotionalUsd = config.notionalUsd * position.targetExposureFraction
    val exitEstimate = buildExecutionEstimate(current, entryNotionalUsd, -position.side, kind)
    val effectiveFill = min(position.entryEstimate.fillRatio, exitEstimate.fillRatio)
    val deployedGrossReturn = position.side * ((current.close / position.entryRow.close) - 1.0) * effectiveFill
    val totalCostBps = position.entryEstimate.totalCostBps + exitEstimate.totalCostBps
    val grossReturn = deployedGrossReturn * position.targetExposureFraction
    val netReturn = (deployedGrossReturn - (totalCostBps / 10000.0)) * position.targetExposureFraction
    val signalMagnitude = when (kind) {
        StrategyKind.TREND -> abs(position.entryRow.trendScore)
        StrategyKind.REVERSION -> abs(position.entryRow.reversionState)
    }
    val jitter = deterministicJitter(current.time, position.side)
    val decisionLatencyMs = clamp(6.0 + (signalMagnitude * 7.0) + (jitter * 0.6), 4.0, 60.0)
    val submitToAckMs = clamp(
        55.0 +
            (current.spreadBps * 1.1) +
            ((entryNotionalUsd / max(current.depthUsd, entryNotionalUsd)) * 120.0) +
            (jitter * 1.5),
        20.0,
        900.0
    )
    val submitToFillMs = clamp(submitToAckMs + ((1.0 - effectiveFill) * 260.0) + 18.0, 30.0, 1800.0)
    val p50RoundtripMs = clamp(submitToAckMs + 12.0, 20.0, 1000.0)
    val p95RoundtripMs = clamp(submitToAckMs * 2.0, 25.0, 1800.0)
    val p99RoundtripMs = clamp(submitToAckMs * 3.0, 30.0, 2500.0)

    return TradeRecord(
        strategyName = position.strategyName,
        strategyKind = kind.name.lowercase(),
        exchange = position.exchange,
        symbol = position.symbol,
        side = if (position.side > 0) "BUY" else "SELL",
        entryTime = position.entryRow.time,
        exitTime = current.time,
        entryPrice = position.entryRow.close,
        exitPrice = current.close,
        holdBars = current.barIndex - position.entryRow.barIndex,
        grossReturnFraction = grossReturn,
        netReturnFraction = netReturn,
        fillRatio = effectiveFill,
        feeBps = position.entryEstimate.feeBps + exitEstimate.feeBps,
        feeTier = position.entryEstimate.feeTier,
        feeTierAdjustmentBps = position.entryEstimate.feeTierAdjustmentBps + exitEstimate.feeTierAdjustmentBps,
        makerFeeBps = position.entryEstimate.makerFeeBps + exitEstimate.makerFeeBps,
        takerFeeBps = position.entryEstimate.takerFeeBps + exitEstimate.takerFeeBps,
        spreadCostBps = position.entryEstimate.spreadCostBps + exitEstimate.spreadCostBps,
        slippageBps = position.entryEstimate.slippageBps + exitEstimate.slippageBps,
        impactBps = position.entryEstimate.impactBps + exitEstimate.impactBps,
        adverseSelectionBps = position.entryEstimate.adverseSelectionBps + exitEstimate.adverseSelectionBps,
        fundingDriftBps = 0.0,
        basisDriftBps = 0.0,
        totalCostBps = totalCostBps,
        edgeAfterCostBps = (deployedGrossReturn - (totalCostBps / 10000.0)) * 10000.0,
        targetExposureFraction = position.targetExposureFraction,
        entryNotionalUsd = entryNotionalUsd.round(4),
        estimatedFeeUsd = position.entryEstimate.estimatedFeeUsd + exitEstimate.estimatedFeeUsd,
        estimatedCostUsd = position.entryEstimate.estimatedCostUsd + exitEstimate.estimatedCostUsd,
        entryTrendScore = position.entryRow.trendScore,
        entryResidualZ = position.entryRow.residualZ,
        expectedGrossEdgeBps = position.expectedGrossEdgeBps,
        expectedRoundTripCostBps = position.expectedRoundTripCostBps,
        expectedNetEdgeBps = position.expectedNetEdgeBps,
        calibrationSamples = position.calibrationSamples,
        calibrationWinRate = position.calibrationWinRate,
        calibrationLowerBoundBps = position.calibrationLowerBoundBps,
        calibrationScope = position.calibrationScope,
        entryImbalance = position.entryRow.imbalance,
        entryFlowSignal = position.entryRow.flowSignal,
        entryVolumeRatio = position.entryRow.volumeRatio,
        entryVolRegime = position.entryRow.volRegime,
        betaBtc = position.entryRow.betaBtc,
        betaEth = position.entryRow.betaEth,
        decisionLatencyMs = decisionLatencyMs,
        submitToAckMs = submitToAckMs,
        submitToFillMs = submitToFillMs,
        p50RoundtripMs = p50RoundtripMs,
        p95RoundtripMs = p95RoundtripMs,
        p99RoundtripMs = p99RoundtripMs,
        jitterMs = jitter
    )
}

fun simulateIndependentTrade(
    strategyName: String,
    kind: StrategyKind,
    candidate: EntryCandidate,
    series: List<FeatureRow>,
    startIndex: Int,
    config: ResearchConfig
): TradeRecord? {
    if (series.isEmpty() || startIndex !in series.indices) return null
    var position = OpenPosition(
        strategyName = strategyName,
        strategyKind = kind,
        exchange = candidate.row.exchange,
        symbol = candidate.row.symbol,
        side = candidate.side,
        entryRow = candidate.row,
        entryEstimate = candidate.entryEstimate,
        expectedGrossEdgeBps = candidate.expectedGrossEdgeBps,
        expectedRoundTripCostBps = candidate.expectedRoundTripCostBps,
        expectedNetEdgeBps = candidate.expectedNetEdgeBps,
        targetExposureFraction = candidate.targetExposureFraction,
        calibrationSamples = candidate.calibrationSamples,
        calibrationWinRate = candidate.calibrationWinRate,
        calibrationLowerBoundBps = candidate.calibrationLowerBoundBps,
        calibrationScope = candidate.calibrationScope,
        maxFavorableReturnFraction = 0.0
    )
    for (index in (startIndex + 1) until series.size) {
        val current = series[index]
        position = updateOpenPosition(position, current)
        if (shouldExitPosition(kind, position, current, config)) {
            return buildTradeRecord(position, current, config)
        }
    }
    val last = series.lastOrNull() ?: return null
    return if (last.time == candidate.row.time) {
        null
    } else {
        buildTradeRecord(updateOpenPosition(position, last), last, config)
    }
}

fun buildCalibrationExamples(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig
): List<CalibrationExample> {
    if (rows.isEmpty()) return emptyList()
    val grouped = rows.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val seriesByExchangeSymbol = rows.groupBy { it.exchange to it.symbol }
        .mapValues { (_, series) -> series.sortedBy { it.time } }
    val indexLookup = mutableMapOf<Triple<String, String, Instant>, Int>()
    seriesByExchangeSymbol.forEach { (key, series) ->
        series.forEachIndexed { index, row ->
            indexLookup[Triple(key.first, key.second, row.time)] = index
        }
    }

    val examples = mutableListOf<CalibrationExample>()
    for (key in orderedKeys) {
        val bucket = grouped[key].orEmpty()
        seedCandidateRows(kind, bucket, config).forEach { candidate ->
            val series = seriesByExchangeSymbol[candidate.row.exchange to candidate.row.symbol] ?: return@forEach
            val startIndex = indexLookup[Triple(candidate.row.exchange, candidate.row.symbol, candidate.row.time)] ?: return@forEach
            val trade = simulateIndependentTrade(strategyName, kind, candidate, series, startIndex, config) ?: return@forEach
            val realizedGrossEdgeBps = if (trade.targetExposureFraction > 1e-9) {
                (trade.grossReturnFraction * 10000.0) / trade.targetExposureFraction
            } else {
                0.0
            }
            examples += CalibrationExample(
                key = calibrationBaseKey(kind, candidate.row, candidate.side, config),
                entryTime = candidate.row.time,
                availableAt = trade.exitTime,
                grossEdgeBps = realizedGrossEdgeBps,
                netEdgeBps = trade.edgeAfterCostBps,
                totalCostBps = trade.totalCostBps,
                fillRatio = trade.fillRatio
            )
        }
    }
    return examples.sortedBy { it.availableAt }
}

private fun effectiveLongCapacity(config: ResearchConfig): Int =
    min(max(config.maxConcurrentLongs, 1), max(config.maxConcurrentPositions, 1))

private fun effectiveShortCapacity(config: ResearchConfig): Int =
    min(max(config.maxConcurrentShorts, 1), max(config.maxConcurrentPositions, 1))

private fun currentGrossExposureUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.targetExposureFraction }

private fun currentLongExposureUnits(positions: Collection<OpenPosition>): Double =
    positions.filter { it.side > 0 }.sumOf { it.targetExposureFraction }

private fun currentShortExposureUnits(positions: Collection<OpenPosition>): Double =
    positions.filter { it.side < 0 }.sumOf { it.targetExposureFraction }

private fun currentBetaBtcUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.side.toDouble() * it.targetExposureFraction * it.entryRow.betaBtc }

private fun currentBetaEthUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.side.toDouble() * it.targetExposureFraction * it.entryRow.betaEth }

private fun currentNetUnits(positions: Collection<OpenPosition>): Double =
    positions.sumOf { it.side.toDouble() * it.targetExposureFraction }

private fun portfolioTelemetryPoint(
    positions: Collection<OpenPosition>
): PortfolioTelemetryPoint {
    val grossPositions = positions.size
    val longPositions = positions.count { it.side > 0 }
    val shortPositions = positions.count { it.side < 0 }
    return PortfolioTelemetryPoint(
        grossPositions = grossPositions,
        longPositions = longPositions,
        shortPositions = shortPositions,
        grossExposureUnits = currentGrossExposureUnits(positions).round(4),
        longExposureUnits = currentLongExposureUnits(positions).round(4),
        shortExposureUnits = currentShortExposureUnits(positions).round(4),
        netExposureUnits = currentNetUnits(positions).round(4),
        betaBtcUnits = currentBetaBtcUnits(positions).round(4),
        betaEthUnits = currentBetaEthUnits(positions).round(4)
    )
}

private fun portfolioAcceptanceScore(
    positions: Collection<OpenPosition>,
    candidate: EntryCandidate,
    config: ResearchConfig
): Double {
    val capacity = max(config.maxConcurrentPositions, 1).toDouble()
    val candidateNetContribution = candidate.side.toDouble() * candidate.targetExposureFraction
    val currentNetFraction = abs(currentNetUnits(positions)) / capacity
    val candidateNetFraction = abs(currentNetUnits(positions) + candidateNetContribution) / capacity
    val currentBetaPenalty =
        (abs(currentBetaBtcUnits(positions)) / capacity) +
            (abs(currentBetaEthUnits(positions)) / capacity)
    val candidateBetaPenalty =
        (abs(currentBetaBtcUnits(positions) + (candidateNetContribution * candidate.row.betaBtc)) / capacity) +
            (abs(currentBetaEthUnits(positions) + (candidateNetContribution * candidate.row.betaEth)) / capacity)
    val balanceBonus = (currentNetFraction - candidateNetFraction) * 6.0
    val betaBonus = (currentBetaPenalty - candidateBetaPenalty) * 10.0
    val capacityPenalty = (currentGrossExposureUnits(positions) / capacity) * 0.75
    val similarityPenalty = positions
        .filter { it.side == candidate.side }
        .map {
            val betaDistance = (abs(it.entryRow.betaBtc - candidate.row.betaBtc) + abs(it.entryRow.betaEth - candidate.row.betaEth)) / 2.0
            clamp(1.0 - betaDistance, 0.0, 1.0)
        }
        .takeIf { it.isNotEmpty() }
        ?.let(::mean)
        ?.times(2.0)
        ?: 0.0
    return candidate.expectedNetEdgeBps + balanceBonus + betaBonus - capacityPenalty - similarityPenalty
}

private fun canAddCandidateToPortfolio(
    positions: Map<String, OpenPosition>,
    candidate: EntryCandidate,
    config: ResearchConfig,
    counters: PortfolioConstraintCounters
): Boolean {
    counters.candidateEntries += 1
    val positionKey = "${candidate.row.exchange}|${candidate.row.symbol}"
    if (positions.containsKey(positionKey)) {
        counters.rejectedOpenSymbol += 1
        return false
    }

    val grossAfter = positions.size + 1
    if (grossAfter > config.maxConcurrentPositions) {
        counters.rejectedGrossLimit += 1
        return false
    }

    val longAfter = positions.values.count { it.side > 0 } + if (candidate.side > 0) 1 else 0
    val shortAfter = positions.values.count { it.side < 0 } + if (candidate.side < 0) 1 else 0
    if (candidate.side > 0 && longAfter > effectiveLongCapacity(config)) {
        counters.rejectedLongLimit += 1
        return false
    }
    if (candidate.side < 0 && shortAfter > effectiveShortCapacity(config)) {
        counters.rejectedShortLimit += 1
        return false
    }

    val capacity = max(config.maxConcurrentPositions, 1).toDouble()
    val candidateNetContribution = candidate.side.toDouble() * candidate.targetExposureFraction
    val nextNetFraction = abs(currentNetUnits(positions.values) + candidateNetContribution) / capacity
    if (nextNetFraction > config.maxNetExposureFraction + 1e-9) {
        counters.rejectedNetLimit += 1
        return false
    }

    val nextBetaBtc = abs(currentBetaBtcUnits(positions.values) + (candidateNetContribution * candidate.row.betaBtc)) / capacity
    val nextBetaEth = abs(currentBetaEthUnits(positions.values) + (candidateNetContribution * candidate.row.betaEth)) / capacity
    if (nextBetaBtc > config.maxPortfolioBetaBtcAbs + 1e-9 || nextBetaEth > config.maxPortfolioBetaEthAbs + 1e-9) {
        counters.rejectedBetaLimit += 1
        return false
    }

    counters.acceptedEntries += 1
    return true
}

private fun buildPortfolioProfile(
    kind: StrategyKind,
    stage: String,
    exchanges: List<String>,
    trades: List<TradeRecord>,
    telemetry: List<PortfolioTelemetryPoint>,
    counters: PortfolioConstraintCounters,
    config: ResearchConfig
): PortfolioProfileSnapshot {
    val samples = if (telemetry.isEmpty()) {
        listOf(PortfolioTelemetryPoint(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0))
    } else {
        telemetry
    }
    val capacity = max(config.maxConcurrentPositions, 1).toDouble()
    val grossSeries = samples.map { it.grossPositions.toDouble() }
    val longSeries = samples.map { it.longPositions.toDouble() }
    val shortSeries = samples.map { it.shortPositions.toDouble() }
    val grossExposureSeries = samples.map { it.grossExposureUnits }
    val netSeries = samples.map { abs(it.netExposureUnits) }
    val betaBtcSeries = samples.map { abs(it.betaBtcUnits) / capacity }
    val betaEthSeries = samples.map { abs(it.betaEthUnits) / capacity }
    val utilizationSeries = samples.map { it.grossExposureUnits / capacity }

    return PortfolioProfileSnapshot(
        strategyKind = kind.name.lowercase(),
        stage = stage,
        exchanges = exchanges,
        trades = trades.size,
        policyMaxConcurrentPositions = config.maxConcurrentPositions,
        policyMaxConcurrentLongs = effectiveLongCapacity(config),
        policyMaxConcurrentShorts = effectiveShortCapacity(config),
        policyMaxNetExposureFraction = config.maxNetExposureFraction.round(4),
        policyMaxAbsBetaBtc = config.maxPortfolioBetaBtcAbs.round(4),
        policyMaxAbsBetaEth = config.maxPortfolioBetaEthAbs.round(4),
        maxConcurrentPositions = samples.maxOfOrNull { it.grossPositions } ?: 0,
        maxConcurrentLongs = samples.maxOfOrNull { it.longPositions } ?: 0,
        maxConcurrentShorts = samples.maxOfOrNull { it.shortPositions } ?: 0,
        avgConcurrentPositions = mean(grossSeries).round(4),
        avgConcurrentLongs = mean(longSeries).round(4),
        avgConcurrentShorts = mean(shortSeries).round(4),
        maxGrossExposureUsd = ((samples.maxOfOrNull { it.grossExposureUnits } ?: 0.0) * config.notionalUsd).round(4),
        avgGrossExposureUsd = (mean(grossExposureSeries) * config.notionalUsd).round(4),
        maxNetExposureUsd = ((samples.maxOfOrNull { abs(it.netExposureUnits) } ?: 0.0) * config.notionalUsd).round(4),
        avgNetExposureUsd = (mean(netSeries) * config.notionalUsd).round(4),
        maxAbsNetExposureFraction = (netSeries.maxOrNull()?.div(capacity) ?: 0.0).round(4),
        avgAbsNetExposureFraction = (mean(netSeries) / capacity).round(4),
        maxAbsBetaBtc = (betaBtcSeries.maxOrNull() ?: 0.0).round(4),
        avgAbsBetaBtc = mean(betaBtcSeries).round(4),
        maxAbsBetaEth = (betaEthSeries.maxOrNull() ?: 0.0).round(4),
        avgAbsBetaEth = mean(betaEthSeries).round(4),
        avgCapacityUtilization = mean(utilizationSeries).round(4),
        maxCapacityUtilization = (utilizationSeries.maxOrNull() ?: 0.0).round(4),
        entryConstraints = PortfolioConstraintSnapshot(
            candidateEntries = counters.candidateEntries,
            acceptedEntries = counters.acceptedEntries,
            rejectedOpenSymbol = counters.rejectedOpenSymbol,
            rejectedGrossLimit = counters.rejectedGrossLimit,
            rejectedLongLimit = counters.rejectedLongLimit,
            rejectedShortLimit = counters.rejectedShortLimit,
            rejectedNetLimit = counters.rejectedNetLimit,
            rejectedBetaLimit = counters.rejectedBetaLimit
        )
    )
}

private fun buildOpenPosition(
    strategyName: String,
    kind: StrategyKind,
    candidate: EntryCandidate
): OpenPosition =
    OpenPosition(
        strategyName = strategyName,
        strategyKind = kind,
        exchange = candidate.row.exchange,
        symbol = candidate.row.symbol,
        side = candidate.side,
        entryRow = candidate.row,
        entryEstimate = candidate.entryEstimate,
        expectedGrossEdgeBps = candidate.expectedGrossEdgeBps,
        expectedRoundTripCostBps = candidate.expectedRoundTripCostBps,
        expectedNetEdgeBps = candidate.expectedNetEdgeBps,
        targetExposureFraction = candidate.targetExposureFraction,
        calibrationSamples = candidate.calibrationSamples,
        calibrationWinRate = candidate.calibrationWinRate,
        calibrationLowerBoundBps = candidate.calibrationLowerBoundBps,
        calibrationScope = candidate.calibrationScope,
        maxFavorableReturnFraction = 0.0
    )

private fun shouldRebalancePosition(
    existing: OpenPosition,
    candidate: EntryCandidate,
    config: ResearchConfig
): Boolean =
    existing.side == candidate.side &&
        candidate.row.barIndex > existing.entryRow.barIndex &&
        abs(candidate.targetExposureFraction - existing.targetExposureFraction) >= config.rebalanceTargetExposureStep

private fun simulateStrategyWithPortfolio(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    stage: String,
    bucketCandidates: (List<FeatureRow>, Instant) -> List<EntryCandidate>
): StrategySimulationResult {
    if (rows.isEmpty()) {
        return StrategySimulationResult(
            trades = emptyList(),
            portfolioProfile = buildPortfolioProfile(
                kind = kind,
                stage = stage,
                exchanges = emptyList(),
                trades = emptyList(),
                telemetry = emptyList(),
                counters = PortfolioConstraintCounters(),
                config = config
            )
        )
    }

    val grouped = rows.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val positions = mutableMapOf<String, OpenPosition>()
    val cooldownUntilBar = mutableMapOf<String, Int>()
    val trades = mutableListOf<TradeRecord>()
    val telemetry = mutableListOf<PortfolioTelemetryPoint>()
    val counters = PortfolioConstraintCounters()

    for (key in orderedKeys) {
        val exchange = key.first
        val currentTime = key.second
        val bucket = grouped[key].orEmpty()
        val rowBySymbol = bucket.associateBy { it.symbol }

        for ((positionKey, position) in positions.toMap()) {
            if (position.exchange != exchange) continue
            val current = rowBySymbol[position.symbol] ?: continue
            val updatedPosition = updateOpenPosition(position, current)
            positions[positionKey] = updatedPosition
            if (!shouldExitPosition(kind, updatedPosition, current, config)) continue
            trades += buildTradeRecord(updatedPosition, current, config)
            positions.remove(positionKey)
            cooldownUntilBar[positionKey] = current.barIndex + when (kind) {
                StrategyKind.TREND -> config.trendCooldownBars
                StrategyKind.REVERSION -> config.reversionCooldownBars
            }
        }

        val pendingCandidates = bucketCandidates(bucket, currentTime).toMutableList()
        while (pendingCandidates.isNotEmpty()) {
            val candidate = pendingCandidates.maxWithOrNull(
                compareBy<EntryCandidate>(
                    { portfolioAcceptanceScore(positions.values, it, config) },
                    { it.expectedNetEdgeBps },
                    { -it.row.barIndex },
                    { it.row.symbol }
                )
            ) ?: break
            pendingCandidates.remove(candidate)

            val positionKey = "${candidate.row.exchange}|${candidate.row.symbol}"
            val existingPosition = positions[positionKey]
            if (existingPosition != null && shouldRebalancePosition(existingPosition, candidate, config)) {
                trades += buildTradeRecord(updateOpenPosition(existingPosition, candidate.row), candidate.row, config)
                positions.remove(positionKey)
                positions[positionKey] = buildOpenPosition(strategyName, kind, candidate)
                continue
            }
            if ((cooldownUntilBar[positionKey] ?: Int.MIN_VALUE) > candidate.row.barIndex) {
                continue
            }
            if (!canAddCandidateToPortfolio(positions, candidate, config, counters)) {
                continue
            }
            positions[positionKey] = buildOpenPosition(strategyName, kind, candidate)
        }

        telemetry += portfolioTelemetryPoint(positions.values)
    }

    val latestByExchangeSymbol = rows.groupBy { it.exchange to it.symbol }
        .mapValues { (_, series) -> series.maxByOrNull { it.time } }

    for ((positionKey, position) in positions.toMap()) {
        val current = latestByExchangeSymbol[position.exchange to position.symbol] ?: continue
        if (current.time == position.entryRow.time) continue
        trades += buildTradeRecord(updateOpenPosition(position, current), current, config)
        positions.remove(positionKey)
    }

    return StrategySimulationResult(
        trades = trades.sortedBy { it.entryTime },
        portfolioProfile = buildPortfolioProfile(
            kind = kind,
            stage = stage,
            exchanges = rows.map { it.exchange }.distinct().sorted(),
            trades = trades,
            telemetry = telemetry,
            counters = counters,
            config = config
        )
    )
}

private fun simulateStrategyResult(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null,
    stage: String = "backtest"
): StrategySimulationResult =
    simulateStrategyWithPortfolio(
        strategyName = strategyName,
        kind = kind,
        rows = rows,
        config = config,
        stage = stage
    ) { bucket, _ ->
        candidateRows(kind, bucket, config, calibrationState)
    }

fun simulateStrategy(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    calibrationState: CalibrationState? = null
): List<TradeRecord> =
    simulateStrategyResult(strategyName, kind, rows, config, calibrationState).trades

private fun simulateStrategyWalkForwardResult(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig,
    seedExamples: List<CalibrationExample> = emptyList(),
    stage: String = "forward"
): StrategySimulationResult {
    if (rows.isEmpty()) {
        return StrategySimulationResult(
            trades = emptyList(),
            portfolioProfile = buildPortfolioProfile(
                kind = kind,
                stage = stage,
                exchanges = emptyList(),
                trades = emptyList(),
                telemetry = emptyList(),
                counters = PortfolioConstraintCounters(),
                config = config
            )
        )
    }

    val calibrationExamples = buildCalibrationExamples(strategyName, kind, rows, config)
    val calibrationState = buildCalibrationState(seedExamples)
    val activeExamples = ArrayDeque<CalibrationExample>()
    seedExamples
        .sortedBy { it.availableAt }
        .forEach(activeExamples::addLast)
    var exampleIndex = 0

    return simulateStrategyWithPortfolio(
        strategyName = strategyName,
        kind = kind,
        rows = rows,
        config = config,
        stage = stage
    ) { bucket, currentTime ->
        while (exampleIndex < calibrationExamples.size && calibrationExamples[exampleIndex].availableAt.isBefore(currentTime)) {
            val example = calibrationExamples[exampleIndex]
            activeExamples.addLast(example)
            addCalibrationExample(calibrationState, example)
            exampleIndex += 1
        }
        val cutoff = currentTime.minus(config.calibrationLookbackHours.toLong(), ChronoUnit.HOURS)
        while (activeExamples.isNotEmpty() && activeExamples.first().availableAt.isBefore(cutoff)) {
            removeCalibrationExample(calibrationState, activeExamples.removeFirst())
        }
        candidateRows(kind, bucket, config, calibrationState)
    }
}

fun simulateStrategyWalkForward(
    strategyName: String,
    kind: StrategyKind,
    rows: List<FeatureRow>,
    config: ResearchConfig
): List<TradeRecord> =
    simulateStrategyWalkForwardResult(strategyName, kind, rows, config).trades

fun buildStrategySummaries(
    config: ResearchConfig,
    strategyName: String,
    strategyKind: StrategyKind,
    trades: List<TradeRecord>,
    timeframe: String,
    notes: String
): List<StrategySummary> {
    if (trades.isEmpty()) return emptyList()

    fun summarize(exchange: String, symbol: String, bucket: List<TradeRecord>): StrategySummary {
        val sorted = bucket.sortedBy { it.entryTime }
        var equity = 1.0
        var peak = 1.0
        var maxDrawdown = 0.0
        val returns = mutableListOf<Double>()
        sorted.forEach { trade ->
            equity *= (1.0 + trade.netReturnFraction)
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, 1.0 - (equity / peak))
            returns += trade.netReturnFraction
        }
        val netReturnPct = ((equity - 1.0) * 100.0).round(4)
        val winRate = sorted.count { it.netReturnFraction > 0.0 }.toDouble() / sorted.size.toDouble()
        val sharpe = run {
            val sigma = stdev(returns)
            if (sigma < 1e-9) 0.0 else (mean(returns) / sigma) * sqrt(sorted.size.toDouble())
        }
        val avgEdgeAfterCostBps = mean(sorted.map { it.edgeAfterCostBps })
        val avgTotalCostBps = mean(sorted.map { it.totalCostBps })
        val avgSlippageBps = mean(sorted.map { it.slippageBps })
        val avgFillRatio = mean(sorted.map { it.fillRatio })
        val avgSubmitToFillMs = mean(sorted.map { it.submitToFillMs })
        val avgBetaBtc = mean(sorted.map { it.betaBtc })
        val avgBetaEth = mean(sorted.map { it.betaEth })
        val avgExpectedGrossEdgeBps = mean(sorted.map { it.expectedGrossEdgeBps })
        val avgExpectedNetEdgeBps = mean(sorted.map { it.expectedNetEdgeBps })
        val edgePredictionErrorBps = avgEdgeAfterCostBps - avgExpectedNetEdgeBps
        val avgCalibrationSamples = mean(sorted.map { it.calibrationSamples.toDouble() })
        val avgCalibrationWinRate = mean(sorted.map { it.calibrationWinRate })
        val avgCalibrationLowerBoundBps = mean(sorted.map { it.calibrationLowerBoundBps })
        val dominantCalibrationScope = sorted.groupingBy { it.calibrationScope }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: "heuristic"
        val avgEntryImbalance = mean(sorted.map { it.entryImbalance })
        val avgEntryFlowSignal = mean(sorted.map { it.entryFlowSignal })
        val avgEntryVolumeRatio = mean(sorted.map { it.entryVolumeRatio })
        val avgEntryVolRegime = mean(sorted.map { it.entryVolRegime })
        val metricsJson = gson.toJson(
            mapOf(
                "exchange" to exchange,
                "strategyKind" to strategyKind.name.lowercase(),
                "bar_minutes" to config.barMinutes,
                "avg_edge_after_cost_bps" to avgEdgeAfterCostBps.round(4),
                "avg_expected_gross_edge_bps" to avgExpectedGrossEdgeBps.round(4),
                "avg_expected_net_edge_bps" to avgExpectedNetEdgeBps.round(4),
                "edge_prediction_error_bps" to edgePredictionErrorBps.round(4),
                "avg_total_cost_bps" to avgTotalCostBps.round(4),
                "avg_slippage_bps" to avgSlippageBps.round(4),
                "avg_fill_ratio" to avgFillRatio.round(4),
                "avg_submit_to_fill_ms" to avgSubmitToFillMs.round(4),
                "avg_beta_btc" to avgBetaBtc.round(4),
                "avg_beta_eth" to avgBetaEth.round(4),
                "avg_calibration_samples" to avgCalibrationSamples.round(4),
                "avg_calibration_win_rate" to avgCalibrationWinRate.round(4),
                "avg_calibration_lower_bound_bps" to avgCalibrationLowerBoundBps.round(4),
                "dominant_calibration_scope" to dominantCalibrationScope,
                "avg_entry_imbalance" to avgEntryImbalance.round(4),
                "avg_entry_flow_signal" to avgEntryFlowSignal.round(4),
                "avg_entry_volume_ratio" to avgEntryVolumeRatio.round(4),
                "avg_entry_vol_regime" to avgEntryVolRegime.round(4),
                "trend_trailing_stop_vol_multiple" to config.trendTrailingStopVolMultiple.round(4),
                "reversion_trailing_stop_vol_multiple" to config.reversionTrailingStopVolMultiple.round(4),
                "trend_take_profit_vol_multiple" to config.trendTakeProfitVolMultiple.round(4),
                "reversion_take_profit_vol_multiple" to config.reversionTakeProfitVolMultiple.round(4),
                "source" to "cross-sectional-beta-kotlin"
            )
        )
        return StrategySummary(
            strategyName = strategyName,
            strategyKind = strategyKind.name.lowercase(),
            exchange = exchange,
            symbol = symbol,
            timeframe = timeframe,
            startTime = sorted.first().entryTime,
            endTime = sorted.last().exitTime,
            trades = sorted.size,
            winRate = winRate,
            netReturnPct = netReturnPct,
            maxDrawdownPct = (maxDrawdown * 100.0).round(4),
            sharpe = sharpe.round(4),
            avgEdgeAfterCostBps = avgEdgeAfterCostBps.round(4),
            avgTotalCostBps = avgTotalCostBps.round(4),
            avgSlippageBps = avgSlippageBps.round(4),
            avgFillRatio = avgFillRatio.round(4),
            avgSubmitToFillMs = avgSubmitToFillMs.round(4),
            notes = notes,
            metricsJson = metricsJson
        )
    }

    val perSymbol = trades.groupBy { it.exchange to it.symbol }
        .map { (key, bucket) -> summarize(key.first, key.second, bucket) }
    val perExchange = trades.groupBy { it.exchange }
        .map { (exchange, bucket) -> summarize(exchange, "ALL", bucket) }
    return perSymbol + perExchange
}

private fun effectiveSliceCount(counts: List<Int>): Double {
    val total = counts.sum().toDouble()
    if (total <= 0.0) return 0.0
    val hhi = counts.sumOf { count ->
        val share = count.toDouble() / total
        share * share
    }
    if (hhi <= 0.0) return 0.0
    return (1.0 / hhi).round(4)
}

private fun summarizeTradeSlice(label: String, trades: List<TradeRecord>): StrategySliceSnapshot {
    val sorted = trades.sortedBy { it.entryTime }
    var equity = 1.0
    var peak = 1.0
    var maxDrawdown = 0.0
    sorted.forEach { trade ->
        equity *= (1.0 + trade.netReturnFraction)
        peak = max(peak, equity)
        maxDrawdown = max(maxDrawdown, 1.0 - (equity / peak))
    }
    return StrategySliceSnapshot(
        label = label,
        trades = sorted.size,
        winRate = (sorted.count { it.netReturnFraction > 0.0 }.toDouble() / max(sorted.size, 1).toDouble()).round(4),
        netReturnPct = ((equity - 1.0) * 100.0).round(4),
        maxDrawdownPct = (maxDrawdown * 100.0).round(4),
        avgEdgeAfterCostBps = mean(sorted.map { it.edgeAfterCostBps }).round(4),
        avgFillRatio = mean(sorted.map { it.fillRatio }).round(4)
    )
}

fun computeStrategyRobustness(
    kind: StrategyKind,
    trades: List<TradeRecord>
): StrategyRobustnessSnapshot? {
    if (trades.isEmpty()) return null

    val totalTrades = trades.size
    val multipleExchanges = trades.map { it.exchange }.distinct().size > 1
    val symbolSlices = trades.groupBy { it.exchange to it.symbol }
        .map { (key, bucket) ->
            val label = if (multipleExchanges) "${key.first}:${key.second}" else key.second
            summarizeTradeSlice(label, bucket)
        }
        .sortedWith(
            compareByDescending<StrategySliceSnapshot> { it.trades }
                .thenByDescending { it.avgEdgeAfterCostBps }
                .thenBy { it.label }
        )
    val regimeOrder = mapOf("calm" to 0, "normal" to 1, "stress" to 2)
    val regimeSlices = trades.groupBy(::tradeRegimeBucket)
        .map { (bucket, bucketTrades) -> summarizeTradeSlice(bucket, bucketTrades) }
        .sortedWith(
            compareBy<StrategySliceSnapshot> { regimeOrder[it.label] ?: Int.MAX_VALUE }
                .thenByDescending { it.trades }
        )

    val symbolCount = symbolSlices.size
    val regimeCount = regimeSlices.size
    val effectiveSymbolCount = effectiveSliceCount(symbolSlices.map { it.trades })
    val effectiveRegimeCount = effectiveSliceCount(regimeSlices.map { it.trades })
    val largestSymbolTradeShare =
        ((symbolSlices.maxOfOrNull { it.trades } ?: 0).toDouble() / max(totalTrades, 1).toDouble()).round(4)
    val largestRegimeTradeShare =
        ((regimeSlices.maxOfOrNull { it.trades } ?: 0).toDouble() / max(totalTrades, 1).toDouble()).round(4)
    val profitableSymbolShare = if (symbolCount == 0) {
        0.0
    } else {
        symbolSlices.count { it.netReturnPct > 0.0 && it.avgEdgeAfterCostBps > 0.0 }.toDouble() / symbolCount.toDouble()
    }.round(4)
    val profitableRegimeShare = if (regimeCount == 0) {
        0.0
    } else {
        regimeSlices.count { it.netReturnPct > 0.0 && it.avgEdgeAfterCostBps > 0.0 }.toDouble() / regimeCount.toDouble()
    }.round(4)
    val worstSymbolNetReturnPct = (symbolSlices.minOfOrNull { it.netReturnPct } ?: 0.0).round(4)
    val worstSymbolEdgeAfterCostBps = (symbolSlices.minOfOrNull { it.avgEdgeAfterCostBps } ?: 0.0).round(4)
    val worstRegimeNetReturnPct = (regimeSlices.minOfOrNull { it.netReturnPct } ?: 0.0).round(4)
    val worstRegimeEdgeAfterCostBps = (regimeSlices.minOfOrNull { it.avgEdgeAfterCostBps } ?: 0.0).round(4)

    val normalizedSymbolBreadth = if (symbolCount <= 1) {
        0.0
    } else {
        clamp((effectiveSymbolCount - 1.0) / (symbolCount.toDouble() - 1.0), 0.0, 1.0)
    }
    val normalizedRegimeBreadth = if (regimeCount <= 1) {
        0.0
    } else {
        clamp((effectiveRegimeCount - 1.0) / (regimeCount.toDouble() - 1.0), 0.0, 1.0)
    }
    val worstSlicePenalty = clamp(
        (max(0.0, -worstSymbolEdgeAfterCostBps) + max(0.0, -worstRegimeEdgeAfterCostBps)) / 16.0,
        0.0,
        1.0
    )
    val stabilityScore = (
        (normalizedSymbolBreadth * 30.0) +
            (normalizedRegimeBreadth * 25.0) +
            (profitableSymbolShare * 20.0) +
            (profitableRegimeShare * 15.0) +
            ((1.0 - worstSlicePenalty) * 10.0)
        ).round(4)

    return StrategyRobustnessSnapshot(
        strategyKind = kind.name.lowercase(),
        totalTrades = totalTrades,
        symbolCount = symbolCount,
        regimeCount = regimeCount,
        effectiveSymbolCount = effectiveSymbolCount,
        effectiveRegimeCount = effectiveRegimeCount,
        largestSymbolTradeShare = largestSymbolTradeShare,
        largestRegimeTradeShare = largestRegimeTradeShare,
        profitableSymbolShare = profitableSymbolShare,
        profitableRegimeShare = profitableRegimeShare,
        worstSymbolNetReturnPct = worstSymbolNetReturnPct,
        worstSymbolEdgeAfterCostBps = worstSymbolEdgeAfterCostBps,
        worstRegimeNetReturnPct = worstRegimeNetReturnPct,
        worstRegimeEdgeAfterCostBps = worstRegimeEdgeAfterCostBps,
        stabilityScore = stabilityScore,
        symbolSlices = symbolSlices,
        regimeSlices = regimeSlices
    )
}



fun evaluateCrossSectionalResearch(
    context: ResearchDataContext,
    config: ResearchConfig
): CrossSectionalResearchResult =
    evaluateCrossSectionalResearchRows(
        context = context,
        researchFeatureRows = engineerFeatures(context.bars, config),
        config = config
    )

internal fun splitResearchWindow(
    researchFeatureRows: List<FeatureRow>,
    config: ResearchConfig
): ResearchWindowSplit {
    val forwardCutoff = researchFeatureRows.maxOfOrNull { it.time }
        ?.minus(config.forwardHours.toLong(), ChronoUnit.HOURS)
        ?: return ResearchWindowSplit(
            forwardCutoff = null,
            calibrationRows = researchFeatureRows,
            backtestCalibrationRows = emptyList(),
            backtestRows = researchFeatureRows,
            forwardRows = emptyList()
        )
    val calibrationRows = researchFeatureRows.filter { it.time.isBefore(forwardCutoff) }
    if (calibrationRows.isEmpty()) {
        return ResearchWindowSplit(
            forwardCutoff = forwardCutoff,
            calibrationRows = emptyList(),
            backtestCalibrationRows = emptyList(),
            backtestRows = emptyList(),
            forwardRows = researchFeatureRows.filter { !it.time.isBefore(forwardCutoff) }
        )
    }
    val calibrationStart = calibrationRows.minOf { it.time }
    val calibrationHours = max(Duration.between(calibrationStart, forwardCutoff).toHours(), 1L)
    val backtestCalibrationHours = min(
        max(calibrationHours / 2L, 1L),
        max(config.calibrationLookbackHours.toLong(), 1L)
    )
    val backtestCalibrationCutoff = calibrationStart.plus(backtestCalibrationHours, ChronoUnit.HOURS)
    return ResearchWindowSplit(
        forwardCutoff = forwardCutoff,
        calibrationRows = calibrationRows,
        backtestCalibrationRows = calibrationRows.filter { it.time.isBefore(backtestCalibrationCutoff) },
        backtestRows = calibrationRows.filter { !it.time.isBefore(backtestCalibrationCutoff) },
        forwardRows = researchFeatureRows.filter { !it.time.isBefore(forwardCutoff) }
    )
}

internal fun evaluateCrossSectionalResearchRows(
    context: ResearchDataContext,
    researchFeatureRows: List<FeatureRow>,
    config: ResearchConfig
): CrossSectionalResearchResult {
    val researchBars = context.bars
    val diagnostics = computeResearchDiagnostics(researchFeatureRows, config)
    val heuristicSignals = latestSignalSnapshots(researchFeatureRows, config)
    val windowSplit = splitResearchWindow(researchFeatureRows, config)
    val backtestSeedRows = if (windowSplit.backtestRows.isNotEmpty()) {
        windowSplit.backtestCalibrationRows
    } else {
        emptyList()
    }
    val backtestRows = if (windowSplit.backtestRows.isNotEmpty()) {
        windowSplit.backtestRows
    } else {
        windowSplit.calibrationRows
    }

    val trendStrategyName = "cross_section_beta_trend_v1"
    val strategyNamesByKind = mapOf(
        StrategyKind.TREND.name.lowercase() to trendStrategyName
    )
    val backtestCalibrationSeedExamples = buildCalibrationExamples(
        strategyName = trendStrategyName,
        kind = StrategyKind.TREND,
        rows = backtestSeedRows,
        config = config
    )

    val trendBacktest = simulateStrategyWalkForwardResult(
        strategyName = trendStrategyName,
        kind = StrategyKind.TREND,
        rows = backtestRows,
        config = config,
        seedExamples = backtestCalibrationSeedExamples,
        stage = "backtest"
    )
    val backtestSummaries =
        buildStrategySummaries(
            config = config,
            strategyName = trendStrategyName,
            strategyKind = StrategyKind.TREND,
            trades = trendBacktest.trades,
            timeframe = "candle_${config.barMinutes}m",
            notes = "${config.barMinutes}m beta-adjusted cross-sectional trend with causal calibration gating"
        )
    val backtestPortfolioProfiles = mapOf(
        StrategyKind.TREND.name.lowercase() to trendBacktest.portfolioProfile
    )
    val backtestRobustness = mutableMapOf<String, StrategyRobustnessSnapshot>().apply {
        computeStrategyRobustness(StrategyKind.TREND, trendBacktest.trades)?.let {
            put(StrategyKind.TREND.name.lowercase(), it)
        }
    }

    if (config.persistBacktest && backtestSummaries.isNotEmpty()) {
        persistBacktestSummaries(backtestSummaries)
        persistPortfolioProfiles(
            config = config,
            timeframe = "candle_${config.barMinutes}m",
            strategyNames = strategyNamesByKind,
            profiles = backtestPortfolioProfiles
        )
    }
    if ((config.persistBacktest || config.persistForward) && context.universeProfiles.isNotEmpty()) {
        persistUniverseProfiles(
            config = config,
            timeframe = "candle_${config.barMinutes}m",
            strategyNames = strategyNamesByKind.values,
            profiles = context.universeProfiles
        )
    }

    var latestSignals = heuristicSignals
    var forwardSummaries = emptyList<StrategySummary>()
    var calibrationRowsCount = 0
    var forwardRowsCount = 0
    var calibrationCounts = emptyMap<String, Int>()
    var forwardPortfolioProfiles = emptyMap<String, PortfolioProfileSnapshot>()
    var forwardRobustness = emptyMap<String, StrategyRobustnessSnapshot>()

    if (windowSplit.forwardCutoff != null) {
        val calibrationRows = windowSplit.calibrationRows
        val forwardRows = windowSplit.forwardRows
        calibrationRowsCount = calibrationRows.size
        forwardRowsCount = forwardRows.size

        val calibrationTrendExamples = buildCalibrationExamples(
            strategyName = trendStrategyName,
            kind = StrategyKind.TREND,
            rows = calibrationRows,
            config = config
        )
        calibrationCounts = mapOf(
            "trend" to calibrationTrendExamples.size
        )
        val forwardCalibrationState = buildCalibrationState(calibrationTrendExamples)
        val baselineMap = backtestSummaries.associateBy { Triple(it.strategyName, it.exchange, it.symbol) }

        val forwardTrend = simulateStrategyResult(
            strategyName = trendStrategyName,
            kind = StrategyKind.TREND,
            rows = forwardRows,
            config = config,
            calibrationState = forwardCalibrationState,
            stage = "forward"
        )
        val forwardTrendTrades = forwardTrend.trades
        val forwardTrades = forwardTrendTrades
        forwardPortfolioProfiles = mapOf(
            StrategyKind.TREND.name.lowercase() to forwardTrend.portfolioProfile
        )
        forwardSummaries =
            buildStrategySummaries(
                config = config,
                strategyName = trendStrategyName,
                strategyKind = StrategyKind.TREND,
                trades = forwardTrendTrades,
                timeframe = "forward_${config.barMinutes}m",
                notes = "forward ${config.barMinutes}m slice with calibrated promotion gating"
            )
        forwardRobustness = mutableMapOf<String, StrategyRobustnessSnapshot>().apply {
            computeStrategyRobustness(StrategyKind.TREND, forwardTrendTrades)?.let {
                put(StrategyKind.TREND.name.lowercase(), it)
            }
        }

        latestSignals = latestSignalSnapshots(researchFeatureRows, config, forwardCalibrationState)

        if (config.persistForward && forwardTrades.isNotEmpty()) {
            persistForwardTelemetry(
                config = config,
                trades = forwardTrades,
                baselines = baselineMap,
                source = "alpha-analytics-service"
            )
            persistPortfolioProfiles(
                config = config,
                timeframe = "forward_${config.barMinutes}m",
                strategyNames = strategyNamesByKind,
                profiles = forwardPortfolioProfiles
            )
        }
    }

    return CrossSectionalResearchResult(
        config = config,
        exchangeCatalog = context.exchangeCatalog,
        exchangePlans = context.exchangePlans,
        candidateUniverse = context.candidateUniverse,
        discoveredUniverse = context.discoveredUniverse,
        universeProfiles = context.universeProfiles,
        barsLoaded = researchBars.size,
        featureRows = researchFeatureRows.size,
        diagnostics = diagnostics,
        heuristicSignals = heuristicSignals,
        latestSignals = latestSignals,
        backtestSummaries = backtestSummaries,
        forwardSummaries = forwardSummaries,
        forwardCutoff = windowSplit.forwardCutoff,
        calibrationRows = calibrationRowsCount,
        forwardRows = forwardRowsCount,
        calibrationExampleCounts = calibrationCounts,
        backtestPortfolioProfiles = backtestPortfolioProfiles,
        forwardPortfolioProfiles = forwardPortfolioProfiles,
        backtestRobustness = backtestRobustness,
        forwardRobustness = forwardRobustness
    )
}

fun runCrossSectionalResearch(config: ResearchConfig = ResearchConfig()): CrossSectionalResearchResult =
    evaluateCrossSectionalResearch(loadResearchDataContext(config), config)

