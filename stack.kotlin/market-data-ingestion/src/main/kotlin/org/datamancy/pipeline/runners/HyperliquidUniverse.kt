package org.datamancy.pipeline.runners

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val universeLogger = KotlinLogging.logger {}

internal const val DEFAULT_HYPERLIQUID_UNIVERSE_REFRESH_INTERVAL_MS = 300_000L
internal const val MIN_HYPERLIQUID_UNIVERSE_REFRESH_INTERVAL_MS = 60_000L

internal enum class HyperliquidUniverseMode {
    CATALOG,
    STATIC
}

internal data class HyperliquidUniverseEntry(
    val symbol: String,
    val delisted: Boolean
)

internal data class HyperliquidUniverseSnapshot(
    val symbols: List<String>,
    val source: String
)

internal data class HyperliquidUniverseSettings(
    val mode: HyperliquidUniverseMode,
    val staticSymbols: List<String>,
    val includeSymbols: Set<String>,
    val excludeSymbols: Set<String>,
    val includeDelisted: Boolean,
    val refreshIntervalMs: Long
)

internal fun parseSymbolList(raw: String?): List<String> =
    raw.orEmpty()
        .split(',')
        .map { it.trim().uppercase() }
        .filter { it.isNotEmpty() }
        .distinct()

internal fun parseSymbolSet(raw: String?): Set<String> =
    parseSymbolList(raw).toSet()

private fun symbolKey(symbol: String): String = symbol.trim().uppercase()

internal fun resolveHyperliquidUniverseMode(explicitMode: String?, staticSymbols: List<String>): HyperliquidUniverseMode {
    val normalized = explicitMode?.trim()?.lowercase()
    return when (normalized) {
        "catalog", "dynamic", "auto", "exchange", "full" -> HyperliquidUniverseMode.CATALOG
        "static", "fixed" -> HyperliquidUniverseMode.STATIC
        null, "" -> if (staticSymbols.isNotEmpty()) HyperliquidUniverseMode.STATIC else HyperliquidUniverseMode.CATALOG
        else -> {
            universeLogger.warn {
                "Unknown HYPERLIQUID_UNIVERSE_MODE=$explicitMode; " +
                    "defaulting to ${if (staticSymbols.isNotEmpty()) "STATIC" else "CATALOG"}"
            }
            if (staticSymbols.isNotEmpty()) HyperliquidUniverseMode.STATIC else HyperliquidUniverseMode.CATALOG
        }
    }
}

internal fun resolveHyperliquidUniverseRefreshIntervalMs(explicitIntervalMs: Long?): Long {
    val intervalMs = explicitIntervalMs ?: DEFAULT_HYPERLIQUID_UNIVERSE_REFRESH_INTERVAL_MS
    return intervalMs.coerceAtLeast(MIN_HYPERLIQUID_UNIVERSE_REFRESH_INTERVAL_MS)
}

internal fun filterCatalogUniverse(
    entries: List<HyperliquidUniverseEntry>,
    includeSymbols: Set<String>,
    excludeSymbols: Set<String>,
    includeDelisted: Boolean
): List<String> {
    return entries
        .asSequence()
        .filter { includeDelisted || !it.delisted }
        .map { it.symbol.trim() }
        .filter { it.isNotEmpty() }
        .filter { includeSymbols.isEmpty() || symbolKey(it) in includeSymbols }
        .filterNot { symbolKey(it) in excludeSymbols }
        .distinct()
        .sortedWith(compareBy<String> { symbolKey(it) }.thenBy { it })
        .toList()
}

internal fun parseHyperliquidMarketCatalogEntries(
    body: String,
    json: Json = Json { ignoreUnknownKeys = true }
): List<HyperliquidUniverseEntry> {
    val parsed = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: run {
        universeLogger.warn { "Hyperliquid market catalog payload was not a JSON object: ${body.take(200)}" }
        return emptyList()
    }
    val markets = parsed["markets"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return markets.mapNotNull { element ->
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val symbol = obj["symbol"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val canonical = symbol.trim()
        if (canonical.isEmpty()) {
            return@mapNotNull null
        }
        val attributes = obj["attributes"]?.jsonObject
        val delisted = obj["isDelisted"]?.jsonPrimitive?.booleanOrNull == true ||
            obj["delisted"]?.jsonPrimitive?.booleanOrNull == true ||
            attributes?.get("isDelisted")?.jsonPrimitive?.booleanOrNull == true ||
            attributes?.get("delisted")?.jsonPrimitive?.booleanOrNull == true
        HyperliquidUniverseEntry(symbol = canonical, delisted = delisted)
    }
}

internal fun parseHyperliquidMetaEntries(
    body: String,
    json: Json = Json { ignoreUnknownKeys = true }
): List<HyperliquidUniverseEntry> {
    val parsed = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject ?: run {
        universeLogger.warn { "Hyperliquid meta payload was not a JSON object: ${body.take(200)}" }
        return emptyList()
    }
    val universe = parsed["universe"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return universe.mapNotNull { element ->
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val symbol = obj["name"]?.jsonPrimitive?.contentOrNull
            ?: obj["coin"]?.jsonPrimitive?.contentOrNull
            ?: return@mapNotNull null
        val canonical = symbol.trim()
        if (canonical.isEmpty()) {
            return@mapNotNull null
        }
        HyperliquidUniverseEntry(
            symbol = canonical,
            delisted = obj["isDelisted"]?.jsonPrimitive?.booleanOrNull == true ||
                obj["delisted"]?.jsonPrimitive?.booleanOrNull == true
        )
    }
}

internal class HyperliquidUniverseResolver(
    private val infoUrl: String,
    private val marketCatalogUrl: String? = null,
    private val client: HttpClient = HttpClient(CIO),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun resolve(settings: HyperliquidUniverseSettings): HyperliquidUniverseSnapshot {
        return when (settings.mode) {
            HyperliquidUniverseMode.STATIC -> {
                val symbols = settings.staticSymbols
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .filter { settings.includeSymbols.isEmpty() || symbolKey(it) in settings.includeSymbols }
                    .filterNot { symbolKey(it) in settings.excludeSymbols }
                    .distinct()
                require(symbols.isNotEmpty()) {
                    "Static universe mode resolved no symbols. Provide HYPERLIQUID_SYMBOLS or switch to catalog mode."
                }
                HyperliquidUniverseSnapshot(symbols = symbols, source = "static")
            }

            HyperliquidUniverseMode.CATALOG -> {
                val catalogSymbols = fetchMarketCatalogEntries()
                    .ifEmpty { fetchCatalogEntries() }
                val symbols = filterCatalogUniverse(
                    entries = catalogSymbols,
                    includeSymbols = settings.includeSymbols,
                    excludeSymbols = settings.excludeSymbols,
                    includeDelisted = settings.includeDelisted
                )
                if (symbols.isNotEmpty()) {
                    HyperliquidUniverseSnapshot(symbols = symbols, source = "catalog")
                } else if (settings.staticSymbols.isNotEmpty()) {
                    universeLogger.warn {
                        "Hyperliquid catalog resolved no symbols after filters; falling back to static override"
                    }
                    HyperliquidUniverseSnapshot(symbols = settings.staticSymbols, source = "static_fallback")
                } else {
                    error("Hyperliquid catalog resolved no symbols and no static fallback is configured")
                }
            }
        }
    }

    fun close() {
        client.close()
    }

    private suspend fun fetchMarketCatalogEntries(): List<HyperliquidUniverseEntry> {
        val url = marketCatalogUrl?.trim().orEmpty()
        if (url.isEmpty()) {
            return emptyList()
        }
        val body = try {
            client.get(url).bodyAsText()
        } catch (ex: Exception) {
            universeLogger.warn(ex) { "Failed to fetch Hyperliquid market catalog from $url" }
            return emptyList()
        }
        return parseHyperliquidMarketCatalogEntries(body, json)
    }

    private suspend fun fetchCatalogEntries(): List<HyperliquidUniverseEntry> {
        val payload = buildJsonObject {
            put("type", JsonPrimitive("meta"))
        }
        val response = client.post(infoUrl) {
            contentType(ContentType.Application.Json)
            setBody(payload.toString())
        }
        val body = response.bodyAsText()
        return parseHyperliquidMetaEntries(body, json)
    }
}
