package org.datamancy.alphaanalytics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.datamancy.trading.policy.ActiveTradingPolicy
import org.datamancy.trading.policy.TradingPolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import kotlin.jvm.optionals.getOrNull

enum class VenueSanityClassification {
    LIVE_SPARSE,
    LOCAL_STALE,
    HEALTHY,
    VENUE_MISSING,
    UNKNOWN
}

data class DataHealthVenueSanity(
    val exchange: String,
    val symbol: String,
    val checkedAt: Instant,
    val localStatus: DataHealthStatus?,
    val localLivenessClass: DataHealthLivenessClass?,
    val localReadinessEligible: Boolean?,
    val localCandleLagSeconds: Long?,
    val localTradeLagSeconds: Long?,
    val localOrderbookLagSeconds: Long?,
    val venueMidPresent: Boolean,
    val venueBookPresent: Boolean,
    val venueBookTime: Instant?,
    val venueBookAgeSeconds: Long?,
    val classification: VenueSanityClassification,
    val reasons: List<String>,
    val probeError: String?
)

class HyperliquidVenueSanityService(
    private val policyProvider: () -> TradingPolicy = ActiveTradingPolicy::current,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val nowProvider: () -> Instant = Instant::now
) {
    @Volatile
    private var midsCache: CachedMids? = null

    private val bookCacheLock = Any()
    private val midsCacheLock = Any()
    private val bookCache = linkedMapOf<String, CachedBook>()

    suspend fun check(
        exchange: String,
        symbol: String,
        localIssue: DataHealthSymbolIssue?
    ): DataHealthVenueSanity = withContext(Dispatchers.IO) {
        require(exchange == "hyperliquid_mainnet") {
            "venue sanity is currently supported only for hyperliquid_mainnet"
        }
        val checkedAt = nowProvider()
        val canonicalSymbol = symbol.trim().uppercase()
        val infoUrl = resolveInfoUrl(exchange = exchange, policy = policyProvider())
        val midsResult = runCatching { loadMids(infoUrl) }
        val mids = midsResult.getOrElse { emptySet() }
        val bookResult = runCatching { loadBook(infoUrl = infoUrl, symbol = canonicalSymbol) }
        val book = bookResult.getOrNull()
        val probeError = listOfNotNull(
            midsResult.exceptionOrNull()?.message,
            bookResult.exceptionOrNull()?.message
        ).takeIf { it.isNotEmpty() }?.joinToString(" | ")
        val venueMidPresent = canonicalSymbol in mids
        val venueBookPresent = book?.present == true
        val venueBookTime = book?.bookTime
        val venueBookAgeSeconds = venueBookTime?.let { Duration.between(it, checkedAt).seconds.coerceAtLeast(0L) }
        val localLivenessClass = localIssue?.livenessClass

        val reasons = buildList {
            if (venueBookPresent) {
                add("venue l2Book returned live levels")
            } else if (venueMidPresent) {
                add("venue allMids lists symbol")
            } else {
                add("venue probes did not confirm live market data")
            }
            when (localLivenessClass) {
                DataHealthLivenessClass.LIVE_SPARSE ->
                    add("local pipeline has live execution context but quiet event-driven channels")
                DataHealthLivenessClass.LOCAL_STALE ->
                    add("local pipeline execution context is stale")
                DataHealthLivenessClass.HEALTHY ->
                    add("local pipeline execution context is current")
                DataHealthLivenessClass.INACTIVE ->
                    add("local symbol is inactive in current health view")
                null -> add("local data-health issue unavailable")
            }
            if (probeError != null) {
                add("venue probe error: $probeError")
            }
        }

        DataHealthVenueSanity(
            exchange = exchange,
            symbol = canonicalSymbol,
            checkedAt = checkedAt,
            localStatus = localIssue?.status,
            localLivenessClass = localIssue?.livenessClass,
            localReadinessEligible = localIssue?.readinessEligible,
            localCandleLagSeconds = localIssue?.candleRawLagSeconds,
            localTradeLagSeconds = localIssue?.tradeRawLagSeconds,
            localOrderbookLagSeconds = localIssue?.orderbookRawLagSeconds,
            venueMidPresent = venueMidPresent,
            venueBookPresent = venueBookPresent,
            venueBookTime = venueBookTime,
            venueBookAgeSeconds = venueBookAgeSeconds,
            classification = classify(
                localLivenessClass = localLivenessClass,
                venueMidPresent = venueMidPresent,
                venueBookPresent = venueBookPresent,
                probeError = probeError
            ),
            reasons = reasons,
            probeError = probeError
        )
    }

    private fun resolveInfoUrl(exchange: String, policy: TradingPolicy): String =
        policy.venues.values.firstOrNull { it.exchangeId == exchange }?.infoUrl
            ?: error("no venue infoUrl configured for exchange=$exchange")

    private fun classify(
        localLivenessClass: DataHealthLivenessClass?,
        venueMidPresent: Boolean,
        venueBookPresent: Boolean,
        probeError: String?
    ): VenueSanityClassification = when {
        venueBookPresent && localLivenessClass == DataHealthLivenessClass.LIVE_SPARSE -> VenueSanityClassification.LIVE_SPARSE
        venueBookPresent && localLivenessClass == DataHealthLivenessClass.LOCAL_STALE -> VenueSanityClassification.LOCAL_STALE
        venueBookPresent && localLivenessClass == DataHealthLivenessClass.HEALTHY -> VenueSanityClassification.HEALTHY
        probeError != null && !venueBookPresent && !venueMidPresent -> VenueSanityClassification.UNKNOWN
        !venueBookPresent && !venueMidPresent -> VenueSanityClassification.VENUE_MISSING
        venueMidPresent && localLivenessClass == DataHealthLivenessClass.LIVE_SPARSE -> VenueSanityClassification.LIVE_SPARSE
        venueMidPresent && localLivenessClass == DataHealthLivenessClass.LOCAL_STALE -> VenueSanityClassification.LOCAL_STALE
        venueMidPresent && localLivenessClass == DataHealthLivenessClass.HEALTHY -> VenueSanityClassification.HEALTHY
        else -> VenueSanityClassification.UNKNOWN
    }

    private fun loadMids(infoUrl: String): Set<String> {
        val now = nowProvider()
        midsCache?.takeIf { Duration.between(it.fetchedAt, now) <= Duration.ofSeconds(15) }?.let { return it.symbols }
        synchronized(midsCacheLock) {
            midsCache?.takeIf { Duration.between(it.fetchedAt, now) <= Duration.ofSeconds(15) }?.let { return it.symbols }
            val payload = """{"type":"allMids"}"""
            val body = post(infoUrl = infoUrl, payload = payload)
            val parsed = json.parseToJsonElement(body).jsonObject
            val snapshot = CachedMids(
                fetchedAt = now,
                symbols = parsed.keys.map { it.trim().uppercase() }.toSet()
            )
            midsCache = snapshot
            return snapshot.symbols
        }
    }

    private fun loadBook(infoUrl: String, symbol: String): BookProbe {
        val now = nowProvider()
        synchronized(bookCacheLock) {
            bookCache[symbol]
                ?.takeIf { Duration.between(it.fetchedAt, now) <= Duration.ofSeconds(30) }
                ?.let { return it.probe }
        }
        val payload = """{"type":"l2Book","coin":"$symbol"}"""
        val body = post(infoUrl = infoUrl, payload = payload)
        val parsed = json.parseToJsonElement(body).jsonObject
        val levels = parsed["levels"]?.jsonArray
        val bids = levels?.getOrNull(0)?.jsonArray.orEmpty()
        val asks = levels?.getOrNull(1)?.jsonArray.orEmpty()
        val probe = BookProbe(
            present = bids.isNotEmpty() || asks.isNotEmpty(),
            bookTime = parsed["time"]?.jsonPrimitive?.content?.toLongOrNull()?.let(Instant::ofEpochMilli)
        )
        synchronized(bookCacheLock) {
            bookCache[symbol] = CachedBook(fetchedAt = now, probe = probe)
            if (bookCache.size > 256) {
                val oldestKey = bookCache.entries.minByOrNull { it.value.fetchedAt }?.key
                if (oldestKey != null) {
                    bookCache.remove(oldestKey)
                }
            }
        }
        return probe
    }

    private fun post(infoUrl: String, payload: String): String {
        val request = HttpRequest.newBuilder(URI.create(infoUrl))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        require(response.statusCode() in 200..299) {
            "HTTP ${response.statusCode()} ${response.body().take(200)}"
        }
        return response.body()
    }

    private data class CachedMids(
        val fetchedAt: Instant,
        val symbols: Set<String>
    )

    private data class CachedBook(
        val fetchedAt: Instant,
        val probe: BookProbe
    )

    private data class BookProbe(
        val present: Boolean,
        val bookTime: Instant?
    )
}
