package org.datamancy.txgateway.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.datamancy.txgateway.models.OrderRequest
import org.datamancy.txgateway.services.AuthService
import org.datamancy.txgateway.services.DatabaseService
import org.datamancy.txgateway.services.LdapService
import org.datamancy.txgateway.services.WorkerClient
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("UnifiedExchangeRoutes")

private val supportedExchanges = listOf(
    "swyftx",
    "binance",
    "bybit",
    "coinbase",
    "dydx",
    "hyperliquid",
    "aster"
)

private val liveOrderExchanges = setOf("hyperliquid")

@Serializable
private data class UserResponse(
    val username: String,
    val email: String,
    val groups: List<String>,
    val allowedChains: List<String>,
    val allowedExchanges: List<String>,
    val maxTxPerHour: Int,
    val maxTxValueUSD: Int,
    val evmAddress: String?
)

@Serializable
private data class HistoryDetails(
    val request: String,
    val response: String? = null,
    val error: String? = null
)

@Serializable
private data class HistoryItem(
    val id: String,
    val timestamp: String,
    val type: String,
    val description: String,
    val status: String,
    val details: HistoryDetails
)

@Serializable
private data class ExchangeDescriptor(
    val name: String,
    val apiName: String,
    val liveOrder: Boolean
)

@Serializable
private data class ExchangeCatalogResponse(
    val exchanges: List<ExchangeDescriptor>
)

@Serializable
private data class QuoteResponse(
    val exchange: String,
    val symbol: String,
    val bid: Double,
    val ask: Double,
    val last: Double,
    val timestamp: String,
    val source: String
)

@Serializable
private data class BestQuoteResponse(
    val requestedSymbol: String,
    val normalizedSymbol: String,
    val side: String,
    val selectedExchange: String,
    val quote: QuoteResponse,
    val comparedExchanges: List<String>
)

fun Route.unifiedExchangeRoutes(
    authService: AuthService,
    ldapService: LdapService,
    workerClient: WorkerClient,
    dbService: DatabaseService
) {
    route("/api/v1") {
        get("/health") {
            call.respond(
                HttpStatusCode.OK,
                mapOf("status" to "healthy", "service" to "tx-gateway")
            )
        }

        get("/user") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val userInfo = ldapService.getUserInfo(username)
                ?: run {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                    return@get
                }

            call.respond(
                HttpStatusCode.OK,
                UserResponse(
                    username = userInfo.username,
                    email = userInfo.email,
                    groups = userInfo.groups,
                    allowedChains = userInfo.allowedChains,
                    allowedExchanges = userInfo.allowedExchanges,
                    maxTxPerHour = userInfo.maxTxPerHour,
                    maxTxValueUSD = userInfo.maxTxValueUSD,
                    evmAddress = userInfo.evmAddress
                )
            )
        }

        get("/history") {
            val username = extractAuthenticatedUsername(call, authService) ?: return@get
            val days = call.request.queryParameters["days"]?.toIntOrNull() ?: 7
            val records = dbService.getTransactionHistory(username = username, days = days)

            val mapped = records.map { record ->
                HistoryItem(
                    id = record.id.toString(),
                    timestamp = record.timestamp.toString(),
                    type = record.txType,
                    description = "${record.txType}:${record.status}",
                    status = record.status,
                    details = HistoryDetails(
                        request = record.request,
                        response = record.response,
                        error = record.errorMessage
                    )
                )
            }
            call.respond(HttpStatusCode.OK, mapped)
        }

        route("/exchanges") {
            get {
                val payload = supportedExchanges.map { exchange ->
                    ExchangeDescriptor(
                        name = exchange,
                        apiName = exchange,
                        liveOrder = liveOrderExchanges.contains(exchange)
                    )
                }
                call.respond(HttpStatusCode.OK, ExchangeCatalogResponse(exchanges = payload))
            }

            get("/best-quote") {
                val symbol = call.request.queryParameters["symbol"]?.trim()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing symbol"))
                        return@get
                    }
                if (symbol.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing symbol"))
                    return@get
                }

                val sideRaw = call.request.queryParameters["side"]?.trim()?.lowercase().orEmpty()
                val side = when (sideRaw) {
                    "", "buy" -> "buy"
                    "sell" -> "sell"
                    else -> {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid side: $sideRaw"))
                        return@get
                    }
                }

                val requestedExchanges = call.request.queryParameters["exchanges"]
                    ?.split(",")
                    ?.map { it.trim().lowercase() }
                    ?.filter { it.isNotBlank() }
                    ?.distinct()
                    ?.ifEmpty { null }

                val exchangesToScan = requestedExchanges ?: supportedExchanges
                val unsupported = exchangesToScan.filterNot { it in supportedExchanges }
                if (unsupported.isNotEmpty()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Unsupported exchange(s): ${unsupported.joinToString(", ")}")
                    )
                    return@get
                }

                val quotes = exchangesToScan.mapNotNull { exchange ->
                    dbService.fetchLatestQuote(exchange = exchange, symbol = symbol)
                }

                if (quotes.isEmpty()) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf(
                            "error" to "Quote unavailable",
                            "symbol" to symbol,
                            "side" to side,
                            "exchangesScanned" to exchangesToScan
                        )
                    )
                    return@get
                }

                val best = if (side == "buy") {
                    quotes.minByOrNull { it.ask }
                } else {
                    quotes.maxByOrNull { it.bid }
                } ?: quotes.first()

                val quotePayload = QuoteResponse(
                    exchange = best.exchange,
                    symbol = best.symbol,
                    bid = best.bid,
                    ask = best.ask,
                    last = best.last,
                    timestamp = best.timestamp.toString(),
                    source = best.source
                )
                call.respond(
                    HttpStatusCode.OK,
                    BestQuoteResponse(
                        requestedSymbol = symbol,
                        normalizedSymbol = best.symbol,
                        side = side,
                        selectedExchange = best.exchange,
                        quote = quotePayload,
                        comparedExchanges = quotes.map { it.exchange }.distinct()
                    )
                )
            }

            get("/{exchange}/quote") {
                val exchange = call.parameters["exchange"]?.lowercase()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing exchange"))
                        return@get
                    }
                if (exchange !in supportedExchanges) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Unsupported exchange: $exchange")
                    )
                    return@get
                }

                val symbol = call.request.queryParameters["symbol"]?.trim()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing symbol"))
                        return@get
                    }
                if (symbol.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing symbol"))
                    return@get
                }

                val quote = dbService.fetchLatestQuote(exchange = exchange, symbol = symbol)
                if (quote == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf(
                            "error" to "Quote unavailable",
                            "exchange" to exchange,
                            "symbol" to symbol
                        )
                    )
                    return@get
                }

                call.respond(
                    HttpStatusCode.OK,
                    QuoteResponse(
                        exchange = exchange,
                        symbol = quote.symbol,
                        bid = quote.bid,
                        ask = quote.ask,
                        last = quote.last,
                        timestamp = quote.timestamp.toString(),
                        source = quote.source
                    )
                )
            }

            post("/{exchange}/order") {
                val exchange = call.parameters["exchange"]?.lowercase()
                    ?: run {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing exchange"))
                        return@post
                    }
                if (exchange !in supportedExchanges) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf("error" to "Unsupported exchange: $exchange")
                    )
                    return@post
                }

                val username = extractAuthenticatedUsername(call, authService) ?: return@post
                val userInfo = ldapService.getUserInfo(username)
                    ?: run {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "User not found"))
                        return@post
                    }

                val allowedExchanges = userInfo.allowedExchanges.map { it.lowercase() }.toSet()
                if (exchange !in allowedExchanges) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf("error" to "Exchange not allowed: $exchange")
                    )
                    return@post
                }

                if (!dbService.checkRateLimit(username, userInfo.maxTxPerHour)) {
                    call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Rate limit exceeded"))
                    return@post
                }

                val orderRequest = call.receive<OrderRequest>()

                if (exchange != "hyperliquid") {
                    call.respond(
                        HttpStatusCode.NotImplemented,
                        mapOf(
                            "error" to "Live order adapter not configured for $exchange",
                            "supportedLiveOrderExchanges" to liveOrderExchanges.toList()
                        )
                    )
                    return@post
                }

                val hyperliquidKey = call.request.headers["X-Credential-hyperliquid"]
                if (hyperliquidKey == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing Hyperliquid credentials"))
                    return@post
                }

                try {
                    val payload: Map<String, Any> = mapOf(
                        "username" to username,
                        "symbol" to orderRequest.symbol,
                        "side" to orderRequest.side,
                        "type" to orderRequest.type,
                        "size" to orderRequest.size,
                        "price" to (orderRequest.price ?: ""),
                        "reduceOnly" to orderRequest.reduceOnly,
                        "postOnly" to orderRequest.postOnly,
                        "hyperliquidKey" to hyperliquidKey
                    )
                    val result = workerClient.submitHyperliquidOrder(payload)

                    dbService.logTransaction(
                        username = username,
                        txType = "exchange_order_$exchange",
                        request = mapOf(
                            "exchange" to exchange,
                            "symbol" to orderRequest.symbol,
                            "side" to orderRequest.side,
                            "type" to orderRequest.type,
                            "size" to orderRequest.size,
                            "price" to orderRequest.price
                        ).toString(),
                        response = result.toString(),
                        status = "success"
                    )

                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    logger.error("Unified order failed for exchange=$exchange user=$username", e)
                    dbService.logTransaction(
                        username = username,
                        txType = "exchange_order_$exchange",
                        request = mapOf(
                            "exchange" to exchange,
                            "symbol" to orderRequest.symbol,
                            "side" to orderRequest.side,
                            "type" to orderRequest.type,
                            "size" to orderRequest.size,
                            "price" to orderRequest.price
                        ).toString(),
                        response = null,
                        status = "error",
                        errorMessage = e.message ?: "unified order failed"
                    )
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to (e.message ?: "unified order failed"))
                    )
                }
            }
        }
    }
}

private suspend fun extractAuthenticatedUsername(
    call: RoutingCall,
    authService: AuthService
): String? {
    val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")
    if (token == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Missing token"))
        return null
    }

    val jwt = authService.validateToken(token)
    if (jwt == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token"))
        return null
    }

    val username = authService.extractUsername(jwt)
    if (username == null) {
        call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token claims"))
        return null
    }

    return username
}
