package org.datamancy.trading.analytics.crosssectional

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.max

fun fetchUserProfile(txBase: String, token: String): JsonObject? =
    runCatching {
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/user"))
            .header("Authorization", "Bearer $token")
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("User profile returned status ${response.statusCode()}")
        }
        JsonParser.parseString(response.body()).asJsonObject
    }.getOrElse { ex ->
        println("User profile unavailable: ${ex.message}")
        null
    }

fun requestBestQuote(txBase: String, symbol: String, side: String, exchanges: List<String>, executionMode: String): JsonObject? =
    runCatching {
        val query = listOf(
            "symbol=${urlEncode(symbol)}",
            "side=${urlEncode(side)}",
            "exchanges=${urlEncode(exchanges.joinToString(","))}",
            "executionMode=${urlEncode(executionMode)}"
        ).joinToString("&")
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges/best-quote?$query"))
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Best quote returned status ${response.statusCode()}")
        }
        JsonParser.parseString(response.body()).asJsonObject
    }.getOrElse { ex ->
        println("Best quote unavailable for $symbol/$side: ${ex.message}")
        null
    }

fun submitPaperOrder(txBase: String, token: String, exchange: String, symbol: String, side: String, size: Double, executionMode: String): JsonObject? =
    runCatching {
        val payload = gson.toJson(
            mapOf(
                "symbol" to symbol,
                "side" to side.uppercase(),
                "type" to "MARKET",
                "size" to size.toString(),
                "executionMode" to executionMode,
                "reduceOnly" to false,
                "postOnly" to false,
                "urgencyClass" to "normal"
            )
        )
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges/$exchange/order"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .timeout(Duration.ofSeconds(20))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Paper order returned status ${response.statusCode()}: ${response.body()}")
        }
        JsonParser.parseString(response.body()).asJsonObject
    }.getOrElse { ex ->
        println("Paper order failed for $exchange/$symbol: ${ex.message}")
        null
    }

fun paperTradeTopSignals(
    latestSignals: List<SignalSnapshot>,
    catalog: List<ExchangeCatalogSnapshot>,
    config: ResearchConfig
): List<Map<String, Any?>> {
    val integrated = catalog
        .filter { it.capabilities.paperOrder && (it.implementationStatus == "INTEGRATED" || it.capabilities.bestQuoteDefault) }
        .map { it.apiName.lowercase() }
        .distinct()
        .ifEmpty { listOf("hyperliquid") }

    val profile = if (config.txAuthToken.isBlank()) null else fetchUserProfile(config.txGatewayUrl, config.txAuthToken)
    val allowedModes = profile?.array("allowedTradingModes")?.map { it.asString.lowercase() }?.toSet() ?: emptySet()
    val allowedExchanges = profile?.array("allowedExchanges")?.map { it.asString.lowercase() }?.toSet()

    val candidates = latestSignals
        .mapNotNull { signal ->
            val preferredAction = signal.action.takeUnless { it == "FLAT" } ?: return@mapNotNull null
            mapOf(
                "exchange" to signal.exchange,
                "symbol" to signal.symbol,
                "action" to preferredAction,
                "strategyFamily" to "trend",
                "expectedNetEdgeBps" to signal.expectedNetEdgeBps,
                "targetExposureFraction" to signal.targetExposureFraction,
                "trendScore" to signal.trendScore,
                "residualZ" to signal.residualZ,
                "price" to signal.lastPrice
            )
        }
        .sortedByDescending { it["expectedNetEdgeBps"] as Double }
        .take(2)

    if (candidates.isEmpty()) {
        println("No paper-trade candidates qualified.")
        return emptyList()
    }

    if (config.txAuthToken.isBlank()) {
        println("Set TX_AUTH_TOKEN to enable paper order submission. Returning execution plans only.")
    } else if (config.paperExecutionMode.lowercase() !in allowedModes) {
        println("Account is not provisioned for ${config.paperExecutionMode}; allowed=$allowedModes")
        return emptyList()
    }

    return candidates.map { candidate ->
        val symbol = candidate["symbol"] as String
        val action = candidate["action"] as String
        val side = if (action == "LONG") "buy" else "sell"
        val executionExchanges = integrated.filter { exchange ->
            allowedExchanges == null || exchange in allowedExchanges
        }
        val bestQuote = requestBestQuote(
            txBase = config.txGatewayUrl,
            symbol = symbol,
            side = side,
            exchanges = executionExchanges.ifEmpty { integrated },
            executionMode = config.paperExecutionMode
        )
        val selectedExchange = bestQuote?.string("selectedExchange") ?: candidate["exchange"] as String
        val normalizedSymbol = bestQuote?.string("normalizedSymbol") ?: symbol
        val quote = bestQuote?.obj("quote")
        val lastPrice = quote?.double("last") ?: quote?.double("ask") ?: quote?.double("bid") ?: (candidate["price"] as Double)
        val targetExposureFraction = candidate["targetExposureFraction"] as Double
        val size = max((config.notionalUsd * targetExposureFraction) / max(lastPrice, 1.0), 0.001).round(6)
        val order = if (config.enablePaperOrders && config.txAuthToken.isNotBlank()) {
            submitPaperOrder(
                txBase = config.txGatewayUrl,
                token = config.txAuthToken,
                exchange = selectedExchange,
                symbol = normalizedSymbol,
                side = side,
                size = size,
                executionMode = config.paperExecutionMode
            )
        } else {
            null
        }
        mapOf(
            "symbol" to normalizedSymbol,
            "requestedAction" to action,
            "strategyFamily" to candidate["strategyFamily"],
            "expectedNetEdgeBps" to candidate["expectedNetEdgeBps"],
            "targetExposureFraction" to targetExposureFraction,
            "selectedExchange" to selectedExchange,
            "executionMode" to config.paperExecutionMode,
            "size" to size,
            "quoteBid" to (quote?.double("bid")?.round(4)),
            "quoteAsk" to (quote?.double("ask")?.round(4)),
            "quoteLast" to lastPrice.round(4),
            "orderId" to order?.string("orderId"),
            "status" to order?.string("status"),
            "simulated" to order?.bool("simulated")
        )
    }
}
