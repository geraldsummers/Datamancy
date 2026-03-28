package org.datamancy.trading.analytics.crosssectional

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.TradingPolicy
import org.datamancy.trading.policy.UniversePolicy
import org.datamancy.trading.policy.UniverseSelectionMode
import org.datamancy.trading.policy.VenuePolicy
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.LinkedHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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

