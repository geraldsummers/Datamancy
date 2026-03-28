package org.datamancy.marketdataarchiveimporter

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant

data class ArchiveTrade(
    val time: Instant,
    val symbol: String,
    val price: Double,
    val size: Double,
    val side: String,
    val tradeId: String
)

data class ArchiveAssetContext(
    val time: Instant,
    val symbol: String,
    val fundingRate: Double,
    val openInterest: Double
)

data class ArchiveOrderbookLevel(
    val price: Double,
    val size: Double
)

data class ArchiveOrderbook(
    val time: Instant,
    val symbol: String,
    val bids: List<ArchiveOrderbookLevel>,
    val asks: List<ArchiveOrderbookLevel>
)

class ArchiveLineParsers {
    fun parseTradeLine(line: String): List<ArchiveTrade> {
        val element = parseJson(line) ?: return emptyList()
        val rawTrades = mutableListOf<RawArchiveTrade>()
        collectTrades(element = element, inheritedTime = null, inheritedSymbol = null, sink = rawTrades)
        return rawTrades.mapIndexed { index, trade ->
            ArchiveTrade(
                time = trade.time,
                symbol = trade.symbol,
                price = trade.price,
                size = trade.size,
                side = trade.side,
                tradeId = trade.tradeId ?: "archive:${trade.time.toEpochMilli()}:${trade.symbol}:${trade.side}:${trade.price}:${trade.size}:$index"
            )
        }.distinctBy { it.tradeId }
    }

    fun parseAssetContextLine(line: String): ArchiveAssetContext? {
        val element = parseJson(line) ?: return null
        return parseAssetContextElement(element, inheritedTime = null)
    }

    fun parseOrderbookLine(line: String): ArchiveOrderbook? {
        val element = parseJson(line) ?: return null
        return parseOrderbookElement(element, inheritedTime = null, inheritedSymbol = null)
    }

    private fun parseJson(line: String): JsonElement? =
        runCatching { JsonParser.parseString(line) }.getOrNull()

    private fun collectTrades(
        element: JsonElement,
        inheritedTime: Instant?,
        inheritedSymbol: String?,
        sink: MutableList<RawArchiveTrade>
    ) {
        when {
            element.isJsonArray -> element.asJsonArray.forEach { collectTrades(it, inheritedTime, inheritedSymbol, sink) }
            !element.isJsonObject -> return
        }

        val obj = element.asJsonObject
        val currentTime = firstInstant(obj, "time", "t", "timestamp", "block_time", "blockTime", "local_time", "localTime")
            ?: inheritedTime
        val currentSymbol = firstString(obj, "coin", "s", "symbol") ?: inheritedSymbol

        unwrapTradeField(obj, "raw", currentTime, currentSymbol, sink)
        unwrapTradeField(obj, "data", currentTime, currentSymbol, sink)
        unwrapTradeField(obj, "events", currentTime, currentSymbol, sink)
        unwrapTradeField(obj, "fills", currentTime, currentSymbol, sink)
        unwrapTradeField(obj, "trades", currentTime, currentSymbol, sink)

        val price = firstDouble(obj, "px", "price")
        val size = firstDouble(obj, "sz", "size")
        val side = normalizeSide(firstString(obj, "side", "dir", "direction", "aggressor"))
        if (currentTime != null && currentSymbol != null && price != null && size != null && side != null) {
            sink += RawArchiveTrade(
                time = currentTime,
                symbol = currentSymbol,
                price = price,
                size = size,
                side = side,
                tradeId = firstString(obj, "tid", "trade_id", "tradeId", "id", "hash")
                    ?: firstString(obj, "px", "price")?.let { px ->
                        val sz = firstString(obj, "sz", "size") ?: size.toString()
                        "${currentTime.toEpochMilli()}:$currentSymbol:$side:$px:$sz"
                    }
            )
        }
    }

    private fun unwrapTradeField(
        obj: JsonObject,
        field: String,
        currentTime: Instant?,
        currentSymbol: String?,
        sink: MutableList<RawArchiveTrade>
    ) {
        val nested = obj[field] ?: return
        when {
            nested.isJsonArray -> nested.asJsonArray.forEach { collectTrades(it, currentTime, currentSymbol, sink) }
            nested.isJsonObject -> collectTrades(nested, currentTime, currentSymbol, sink)
        }
    }

    private fun parseAssetContextElement(element: JsonElement, inheritedTime: Instant?): ArchiveAssetContext? {
        if (element.isJsonArray) {
            return element.asJsonArray.firstNotNullOfOrNull { parseAssetContextElement(it, inheritedTime) }
        }
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val currentTime = firstInstant(obj, "time", "t", "timestamp") ?: inheritedTime
        val raw = obj["raw"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val data = raw?.get("data")
        if (data != null) {
            return parseAssetContextElement(data, currentTime)
        }
        val symbol = firstString(obj, "coin", "symbol") ?: return null
        val ctx = obj["ctx"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: obj
        val fundingRate = firstDouble(ctx, "funding", "funding_rate") ?: return null
        val openInterest = firstDouble(ctx, "openInterest", "open_interest") ?: return null
        return ArchiveAssetContext(
            time = currentTime ?: return null,
            symbol = symbol,
            fundingRate = fundingRate,
            openInterest = openInterest
        )
    }

    private fun parseOrderbookElement(
        element: JsonElement,
        inheritedTime: Instant?,
        inheritedSymbol: String?
    ): ArchiveOrderbook? {
        if (element.isJsonArray) {
            return element.asJsonArray.firstNotNullOfOrNull { parseOrderbookElement(it, inheritedTime, inheritedSymbol) }
        }
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        val currentTime = firstInstant(obj, "time", "t", "timestamp") ?: inheritedTime
        val currentSymbol = firstString(obj, "coin", "symbol", "s") ?: inheritedSymbol
        val raw = obj["raw"]?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val data = raw?.get("data")
        if (data != null) {
            return parseOrderbookElement(data, currentTime, currentSymbol)
        }
        val levels = obj["levels"]?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return null
        if (levels.size() < 2) return null
        val bids = parseOrderbookSide(levels[0].takeIf(JsonElement::isJsonArray)?.asJsonArray ?: JsonArray())
        val asks = parseOrderbookSide(levels[1].takeIf(JsonElement::isJsonArray)?.asJsonArray ?: JsonArray())
        if (currentTime == null || currentSymbol == null) return null
        return ArchiveOrderbook(
            time = currentTime,
            symbol = currentSymbol,
            bids = bids,
            asks = asks
        )
    }

    private fun parseOrderbookSide(levels: JsonArray): List<ArchiveOrderbookLevel> =
        levels.mapNotNull { element ->
            when {
                element.isJsonObject -> parseOrderbookLevelObject(element.asJsonObject)
                element.isJsonArray && element.asJsonArray.size() > 0 && element.asJsonArray[0].isJsonObject ->
                    parseOrderbookLevelObject(element.asJsonArray[0].asJsonObject)
                else -> null
            }
        }

    private fun parseOrderbookLevelObject(obj: JsonObject): ArchiveOrderbookLevel? {
        val price = firstDouble(obj, "px", "price") ?: return null
        val size = firstDouble(obj, "sz", "size") ?: return null
        return ArchiveOrderbookLevel(price = price, size = size)
    }

    private fun firstString(obj: JsonObject, vararg names: String): String? =
        names.firstNotNullOfOrNull { name ->
            obj[name]?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString?.trim()?.ifEmpty { null }
        }

    private fun firstDouble(obj: JsonObject, vararg names: String): Double? =
        names.firstNotNullOfOrNull { name ->
            obj[name]?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive?.asString?.toDoubleOrNull()
        }

    private fun firstInstant(obj: JsonObject, vararg names: String): Instant? =
        names.firstNotNullOfOrNull { name ->
            val primitive = obj[name]?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return@firstNotNullOfOrNull null
            primitive.asLongOrNull()?.let(Instant::ofEpochMilli)
                ?: primitive.asStringOrNull()?.let(::parseInstant)
        }

    private fun normalizeSide(raw: String?): String? {
        val normalized = raw?.trim()?.lowercase() ?: return null
        return when (normalized) {
            "b", "buy", "bid", "long" -> "buy"
            "a", "ask", "sell", "short" -> "sell"
            else -> null
        }
    }

    private fun parseInstant(value: String): Instant? {
        value.toLongOrNull()?.let { return Instant.ofEpochMilli(it) }
        return runCatching { Instant.parse(value) }.getOrNull()
    }

    private fun com.google.gson.JsonPrimitive.asLongOrNull(): Long? = runCatching { asLong }.getOrNull()
    private fun com.google.gson.JsonPrimitive.asStringOrNull(): String? = runCatching { asString }.getOrNull()
}

private data class RawArchiveTrade(
    val time: Instant,
    val symbol: String,
    val price: Double,
    val size: Double,
    val side: String,
    val tradeId: String?
)
