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
import org.datamancy.txgateway.services.LatestQuote
import org.datamancy.txgateway.services.RiskDecision
import org.datamancy.txgateway.services.RiskEngineService
import org.datamancy.txgateway.services.RiskAccountStatePatch
import org.datamancy.txgateway.services.WorkerClient
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.util.UUID

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
private val maxQuoteAgeMs: Long = System.getenv("TX_GATEWAY_MAX_QUOTE_AGE_MS")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.toLongOrNull()
    ?.coerceAtLeast(1L)
    ?: 300_000L
private val symbolPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,63}$")
private val maxOrderSize = BigDecimal("1000000000")
private val maxOrderPrice = BigDecimal("1000000000")

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

@Serializable
private data class PaperQuoteSnapshot(
    val bid: Double,
    val ask: Double,
    val last: Double,
    val timestamp: String,
    val source: String
)

@Serializable
private data class PaperOrderResponse(
    val orderId: String,
    val status: String,
    val requestedSize: String,
    val filledSize: String,
    val remainingSize: String,
    val fillRatio: Double,
    val fillPrice: String? = null,
    val exchange: String,
    val symbol: String,
    val side: String,
    val type: String,
    val simulated: Boolean,
    val executionMode: String,
    val rejectionReason: String? = null,
    val quoteAgeMs: Long,
    val quote: PaperQuoteSnapshot,
    val policy: PaperExecutionPolicy,
    val costs: PaperCostBreakdown,
    val telemetry: PaperExecutionTelemetry,
    val simulation: PaperExecutionSimulation,
    val receivedAt: String
)

@Serializable
private data class PaperExecutionPolicy(
    val venue: String,
    val orderType: String,
    val urgencyClass: String,
    val cancelCadenceMs: Long,
    val placementSchedule: List<PaperPlacementSlice>
)

@Serializable
private data class PaperPlacementSlice(
    val delayMs: Long,
    val price: String,
    val sizePercent: Int
)

@Serializable
private data class PaperCostBreakdown(
    val feeTier: String,
    val feeTierAdjustmentBps: Double,
    val makerFeeBps: Double,
    val takerFeeBps: Double,
    val appliedFeeBps: Double,
    val spreadCostBps: Double,
    val slippageBps: Double,
    val impactBps: Double,
    val adverseSelectionBps: Double,
    val fundingDriftBps: Double,
    val basisDriftBps: Double,
    val totalCostBps: Double,
    val estimatedFeeUsd: Double,
    val estimatedCostUsd: Double
)

@Serializable
private data class PaperExecutionTelemetry(
    val decisionLatencyMs: Long,
    val submitToAckMs: Long,
    val submitToFirstFillMs: Long? = null,
    val submitToFinalFillMs: Long? = null,
    val cancelReplaceLatencyMs: Long,
    val p50RoundTripMs: Long,
    val p95RoundTripMs: Long,
    val p99RoundTripMs: Long,
    val jitterMs: Long
)

@Serializable
private data class PaperExecutionSimulation(
    val queuePositionEstimate: Int,
    val expectedFillProbability: Double,
    val projectedPartialFills: List<PaperFillSlice>,
    val failToFillReason: String? = null,
    val regimeBucket: String,
    val liquidityBucket: String
)

@Serializable
private data class PaperFillSlice(
    val delayMs: Long,
    val size: String,
    val price: String
)

@Serializable
private data class RiskRejectionResponse(
    val error: String,
    val risk: RiskRejectionDetails
)

@Serializable
private data class RiskRejectionDetails(
    val allowed: Boolean,
    val tier: String,
    val action: String,
    val reason: String,
    val policyId: String? = null,
    val policyVersion: Int? = null,
    val suggestedMaxOrderNotionalUsd: String? = null,
    val unwindSliceSeconds: Int? = null,
    val unwindMaxSlippageBps: Double? = null,
    val metrics: RiskRejectionMetrics,
    val sentiment: RiskRejectionSentiment? = null
)

@Serializable
private data class RiskRejectionMetrics(
    val currentExposureUsd: String,
    val projectedExposureUsd: String,
    val accountEquityUsd: String,
    val highWaterMarkUsd: String,
    val dailyLossUsd: String,
    val leverage: Double,
    val exposureUtilization: Double,
    val drawdownPct: Double,
    val drawdownUtilization: Double,
    val dailyLossUtilization: Double,
    val leverageUtilization: Double
)

@Serializable
private data class RiskRejectionSentiment(
    val symbol: String,
    val sentimentScore: Double,
    val confidence: Double,
    val observedAt: String,
    val modelName: String? = null,
    val source: String? = null,
    val label: String? = null
)

private enum class UrgencyClass {
    LOW,
    NORMAL,
    HIGH
}

private enum class FeeTier {
    RETAIL,
    PRO,
    VIP
}

fun Route.unifiedExchangeRoutes(
    authService: AuthService,
    ldapService: LdapService,
    workerClient: WorkerClient,
    dbService: DatabaseService
) {
    val riskEngine = RiskEngineService(dbService)
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
                    val quote = dbService.fetchLatestQuote(exchange = exchange, symbol = symbol)
                    when {
                        quote == null -> null
                        !quote.isValidSnapshot() -> {
                            logger.warn(
                                "Ignoring invalid quote snapshot for exchange={} symbol={} bid={} ask={} last={}",
                                quote.exchange,
                                quote.symbol,
                                quote.bid,
                                quote.ask,
                                quote.last
                            )
                            null
                        }
                        !quote.isFreshSnapshot(maxQuoteAgeMs = maxQuoteAgeMs) -> {
                            logger.warn(
                                "Ignoring stale quote snapshot for exchange={} symbol={} ageMs={} maxQuoteAgeMs={}",
                                quote.exchange,
                                quote.symbol,
                                quote.quoteAgeMs(),
                                maxQuoteAgeMs
                            )
                            null
                        }

                        else -> quote
                    }
                }

                if (quotes.isEmpty()) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        mapOf(
                            "error" to "Quote unavailable",
                            "symbol" to symbol,
                            "side" to side,
                            "exchangesScanned" to exchangesToScan.joinToString(",")
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

                val quote = resolveQuoteWithPaperFallback(
                    dbService = dbService,
                    exchange = exchange,
                    symbol = symbol
                )
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
                if (!quote.isValidSnapshot()) {
                    logger.warn(
                        "Rejecting invalid quote snapshot for exchange={} symbol={} bid={} ask={} last={}",
                        quote.exchange,
                        quote.symbol,
                        quote.bid,
                        quote.ask,
                        quote.last
                    )
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf(
                            "error" to "Invalid quote snapshot",
                            "exchange" to exchange,
                            "symbol" to symbol
                        )
                    )
                    return@get
                }
                if (!quote.isFreshSnapshot(maxQuoteAgeMs = maxQuoteAgeMs)) {
                    logger.warn(
                        "Rejecting stale quote snapshot for exchange={} symbol={} ageMs={} maxQuoteAgeMs={}",
                        quote.exchange,
                        quote.symbol,
                        quote.quoteAgeMs(),
                        maxQuoteAgeMs
                    )
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf(
                            "error" to "Stale quote snapshot",
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
                val orderValidationError = validateOrderRequest(orderRequest)
                if (orderValidationError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to orderValidationError))
                    return@post
                }
                val executionControlError = validateExecutionControls(orderRequest)
                if (executionControlError != null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to executionControlError))
                    return@post
                }
                val requestLog = mapOf(
                    "exchange" to exchange,
                    "symbol" to orderRequest.symbol,
                    "side" to orderRequest.side,
                    "type" to orderRequest.type,
                    "size" to orderRequest.size,
                    "price" to orderRequest.price,
                    "urgencyClass" to orderRequest.urgencyClass,
                    "feeTier" to orderRequest.feeTier,
                    "maxSlippageBps" to orderRequest.maxSlippageBps,
                    "cancelAfterMs" to orderRequest.cancelAfterMs
                )
                val maxTxValueUsd = BigDecimal.valueOf(userInfo.maxTxValueUSD.toLong())

                if (exchange != "hyperliquid") {
                    val quote = resolveQuoteWithPaperFallback(
                        dbService = dbService,
                        exchange = exchange,
                        symbol = orderRequest.symbol
                    )
                    if (quote == null) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            mapOf(
                                "error" to "Quote unavailable",
                                "exchange" to exchange,
                                "symbol" to orderRequest.symbol
                            )
                        )
                        return@post
                    }
                    if (!quote.isValidSnapshot()) {
                        logger.warn(
                            "Rejecting paper order due to invalid quote snapshot for exchange={} symbol={} bid={} ask={} last={}",
                            quote.exchange,
                            quote.symbol,
                            quote.bid,
                            quote.ask,
                            quote.last
                        )
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf(
                                "error" to "Invalid quote snapshot",
                                "exchange" to exchange,
                                "symbol" to orderRequest.symbol
                            )
                        )
                        return@post
                    }
                    if (!quote.isFreshSnapshot(maxQuoteAgeMs = maxQuoteAgeMs)) {
                        logger.warn(
                            "Rejecting paper order due to stale quote snapshot for exchange={} symbol={} ageMs={} maxQuoteAgeMs={}",
                            quote.exchange,
                            quote.symbol,
                            quote.quoteAgeMs(),
                            maxQuoteAgeMs
                        )
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            mapOf(
                                "error" to "Stale quote snapshot",
                                "exchange" to exchange,
                                "symbol" to orderRequest.symbol
                            )
                        )
                        return@post
                    }

                    val estimatedNotionalUsd = estimateOrderNotionalUsd(orderRequest, quote)
                        ?: run {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                mapOf("error" to "Unable to estimate order value for risk checks")
                            )
                            return@post
                        }
                    if (estimatedNotionalUsd > maxTxValueUsd) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            mapOf(
                                "error" to "Order value exceeds maxTxValueUSD",
                                "estimatedNotionalUsd" to estimatedNotionalUsd.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                                "maxTxValueUSD" to userInfo.maxTxValueUSD.toString()
                            )
                        )
                        return@post
                    }

                    val riskDecision = riskEngine.evaluateOrder(
                        username = username,
                        symbol = orderRequest.symbol,
                        orderNotionalUsd = estimatedNotionalUsd,
                        reduceOnly = orderRequest.reduceOnly
                    )
                    if (!riskDecision.allowed) {
                        call.respond(HttpStatusCode.Conflict, riskDecision.toRejectionPayload())
                        return@post
                    }

                    val paperResult = runCatching {
                        buildPaperOrderResponse(exchange = exchange, orderRequest = orderRequest, quote = quote)
                    }.getOrElse { error ->
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to (error.message ?: "invalid order payload")))
                        return@post
                    }

                    dbService.logTransaction(
                        username = username,
                        txType = "exchange_order_$exchange",
                        request = requestLog.toString(),
                        response = paperResult.toString(),
                        status = "success"
                    )

                    if (paperResult.status == "FILLED" || paperResult.status == "PARTIALLY_FILLED") {
                        riskEngine.recordAcceptedOrder(
                            username = username,
                            notionalUsd = paperResult.estimatedExecutedNotionalUsd()
                                ?: estimatedNotionalUsd,
                            reduceOnly = orderRequest.reduceOnly
                        )
                    }

                    call.respond(HttpStatusCode.OK, paperResult)
                    return@post
                }

                val rawRiskQuote = dbService.fetchLatestQuote(exchange = exchange, symbol = orderRequest.symbol)
                val quoteForRisk = rawRiskQuote?.takeIf {
                    it.isValidSnapshot() && it.isFreshSnapshot(maxQuoteAgeMs = maxQuoteAgeMs)
                }
                if (rawRiskQuote != null && quoteForRisk == null) {
                    if (!rawRiskQuote.isValidSnapshot()) {
                        logger.warn(
                            "Ignoring invalid live quote snapshot for exchange={} symbol={} bid={} ask={} last={}",
                            rawRiskQuote.exchange,
                            rawRiskQuote.symbol,
                            rawRiskQuote.bid,
                            rawRiskQuote.ask,
                            rawRiskQuote.last
                        )
                    } else {
                        logger.warn(
                            "Ignoring stale live quote snapshot for exchange={} symbol={} ageMs={} maxQuoteAgeMs={}",
                            rawRiskQuote.exchange,
                            rawRiskQuote.symbol,
                            rawRiskQuote.quoteAgeMs(),
                            maxQuoteAgeMs
                        )
                    }
                }
                if (quoteForRisk == null) {
                    logger.warn(
                        "Rejecting live order due to missing fresh quote snapshot for exchange={} symbol={} hasRawQuote={}",
                        exchange,
                        orderRequest.symbol,
                        rawRiskQuote != null
                    )
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf(
                            "error" to "Fresh quote snapshot required for live order risk checks",
                            "exchange" to exchange,
                            "symbol" to orderRequest.symbol
                        )
                    )
                    return@post
                }
                if (!quoteForRisk.isOrderbookBacked()) {
                    logger.warn(
                        "Rejecting live order due to non-orderbook quote source for exchange={} symbol={} source={}",
                        exchange,
                        orderRequest.symbol,
                        quoteForRisk.source
                    )
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf(
                            "error" to "Orderbook-backed quote required for live order risk checks",
                            "exchange" to exchange,
                            "symbol" to orderRequest.symbol,
                            "quoteSource" to quoteForRisk.source
                        )
                    )
                    return@post
                }
                val estimatedNotionalUsd = estimateOrderNotionalUsd(orderRequest, quoteForRisk)
                    ?: run {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Unable to estimate order value for risk checks")
                        )
                        return@post
                    }
                if (estimatedNotionalUsd > maxTxValueUsd) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        mapOf(
                            "error" to "Order value exceeds maxTxValueUSD",
                            "estimatedNotionalUsd" to estimatedNotionalUsd.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                            "maxTxValueUSD" to userInfo.maxTxValueUSD.toString()
                        )
                    )
                    return@post
                }

                val riskDecision = riskEngine.evaluateOrder(
                    username = username,
                    symbol = orderRequest.symbol,
                    orderNotionalUsd = estimatedNotionalUsd,
                    reduceOnly = orderRequest.reduceOnly
                )
                if (!riskDecision.allowed) {
                    call.respond(HttpStatusCode.Conflict, riskDecision.toRejectionPayload())
                    return@post
                }

                val liveExecutionPreview = quoteForRisk?.let { quote ->
                    runCatching {
                        buildPaperOrderResponse(exchange = exchange, orderRequest = orderRequest, quote = quote)
                    }.getOrNull()
                }
                if (liveExecutionPreview?.status == "REJECTED") {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf(
                            "error" to (liveExecutionPreview.rejectionReason ?: "Order rejected by execution safeguards"),
                            "exchange" to exchange,
                            "symbol" to orderRequest.symbol
                        )
                    )
                    return@post
                }

                val hyperliquidKey = call.request.headers["X-Credential-hyperliquid"]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                if (hyperliquidKey == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing Hyperliquid credentials"))
                    return@post
                }

                try {
                    val payload = mutableMapOf<String, Any>(
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
                    orderRequest.urgencyClass?.let { payload["urgencyClass"] = it }
                    orderRequest.feeTier?.let { payload["feeTier"] = it }
                    orderRequest.maxSlippageBps?.let { payload["maxSlippageBps"] = it }
                    orderRequest.cancelAfterMs?.let { payload["cancelAfterMs"] = it }
                    val result = workerClient.submitHyperliquidOrder(payload)

                    dbService.logTransaction(
                        username = username,
                        txType = "exchange_order_$exchange",
                        request = requestLog.toString(),
                        response = result.toString(),
                        status = "success"
                    )

                    val executedNotionalUsd = result.executedNotionalUsd(
                        estimatedNotionalUsd = estimatedNotionalUsd,
                        requestedSizeRaw = orderRequest.size
                    )
                    if (executedNotionalUsd != null && executedNotionalUsd > BigDecimal.ZERO) {
                        riskEngine.recordAcceptedOrder(
                            username = username,
                            notionalUsd = executedNotionalUsd,
                            reduceOnly = orderRequest.reduceOnly
                        )
                    }
                    runCatching {
                        reconcileHyperliquidRiskState(
                            dbService = dbService,
                            workerClient = workerClient,
                            username = username,
                            hyperliquidKey = hyperliquidKey
                        )
                    }.onFailure { syncError ->
                        logger.warn(
                            "Live risk reconciliation failed for user={} exchange={} symbol={}: {}",
                            username,
                            exchange,
                            orderRequest.symbol,
                            syncError.message
                        )
                    }

                    call.respond(HttpStatusCode.OK, result)
                } catch (e: Exception) {
                    logger.error("Unified order failed for exchange=$exchange user=$username", e)
                    dbService.logTransaction(
                        username = username,
                        txType = "exchange_order_$exchange",
                        request = requestLog.toString(),
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

private fun buildPaperOrderResponse(
    exchange: String,
    orderRequest: OrderRequest,
    quote: LatestQuote
): PaperOrderResponse {
    val now = Instant.now()
    val side = orderRequest.side.trim().uppercase()
    require(side == "BUY" || side == "SELL") { "Unsupported side: ${orderRequest.side}" }

    val type = orderRequest.type.trim().uppercase()
    require(type == "MARKET" || type == "LIMIT") { "Unsupported order type: ${orderRequest.type}" }

    val size = orderRequest.size.trim().toBigDecimalOrNull()
        ?: throw IllegalArgumentException("Invalid size: ${orderRequest.size}")
    require(size > BigDecimal.ZERO) { "Order size must be > 0" }

    val limitPrice = orderRequest.price?.trim()?.takeIf { it.isNotBlank() }?.toBigDecimalOrNull()
    if (type == "LIMIT" && (limitPrice == null || limitPrice <= BigDecimal.ZERO)) {
        throw IllegalArgumentException("Limit orders require a positive price")
    }

    val quoteBid = quote.bid.toBigDecimal()
    val quoteAsk = quote.ask.toBigDecimal()
    val quoteMid = quoteBid.add(quoteAsk).divide(BigDecimal.valueOf(2), 18, RoundingMode.HALF_UP)
    val spread = quoteAsk.subtract(quoteBid).max(BigDecimal.ZERO)
    val spreadBps = toBps(spread, quoteMid)

    val urgency = parseUrgencyClass(orderRequest.urgencyClass)
    val feeTier = parseFeeTier(orderRequest.feeTier)
    val marketFillPrice = if (side == "BUY") quoteAsk else quoteBid
    val targetPrice = limitPrice ?: marketFillPrice
    val canFillLimit = when (side) {
        "BUY" -> targetPrice >= quoteAsk
        else -> targetPrice <= quoteBid
    }
    val postOnlyWouldTakeLiquidity = type == "LIMIT" && orderRequest.postOnly && canFillLimit

    val notional = size.multiply(marketFillPrice)
    val sizeImpactBps = when {
        notional <= BigDecimal("25000") -> BigDecimal("0.5")
        notional <= BigDecimal("100000") -> BigDecimal("3.0")
        else -> BigDecimal("8.0")
    }

    val baseSlippageBps = when {
        notional <= BigDecimal("25000") -> BigDecimal("1.5")
        notional <= BigDecimal("100000") -> BigDecimal("6.0")
        else -> BigDecimal("15.0")
    }

    val urgencySlippageAdj = when (urgency) {
        UrgencyClass.LOW -> BigDecimal("-0.5")
        UrgencyClass.NORMAL -> BigDecimal.ZERO
        UrgencyClass.HIGH -> BigDecimal("1.5")
    }

    val spreadAdj = spreadBps.multiply(
        if (type == "MARKET") BigDecimal("0.25") else BigDecimal("0.10")
    )
    val estimatedSlippageBps = if (type == "LIMIT" && !canFillLimit) {
        BigDecimal.ZERO
    } else {
        (baseSlippageBps + sizeImpactBps + urgencySlippageAdj + spreadAdj).max(BigDecimal.ZERO)
    }
    val regimeBucket = classifyRegimeBucket(spreadBps, estimatedSlippageBps)
    val liquidityBucket = classifyLiquidityBucket(spreadBps, notional)

    val maxSlippageBps = orderRequest.maxSlippageBps?.let { BigDecimal.valueOf(it) }
    val rejectionReason = when {
        postOnlyWouldTakeLiquidity ->
            "Post-only limit order would cross the spread and remove liquidity"
        maxSlippageBps != null && estimatedSlippageBps > maxSlippageBps ->
            "Estimated slippage ${estimatedSlippageBps.setScale(2, RoundingMode.HALF_UP)}bps exceeds limit ${maxSlippageBps.setScale(2, RoundingMode.HALF_UP)}bps"
        else -> null
    }

    var fillRatio = when {
        type == "LIMIT" && !canFillLimit -> BigDecimal.ZERO
        notional <= BigDecimal("25000") -> BigDecimal.ONE
        notional <= BigDecimal("100000") -> BigDecimal("0.70")
        else -> BigDecimal("0.35")
    }
    fillRatio += when (urgency) {
        UrgencyClass.LOW -> BigDecimal("-0.15")
        UrgencyClass.NORMAL -> BigDecimal.ZERO
        UrgencyClass.HIGH -> BigDecimal("0.15")
    }
    if (type == "LIMIT" && canFillLimit) {
        fillRatio += BigDecimal("0.10")
    }
    fillRatio = fillRatio.coerceIn(BigDecimal.ZERO, BigDecimal.ONE)

    if (rejectionReason != null) {
        fillRatio = BigDecimal.ZERO
    }

    val filledSize = size.multiply(fillRatio).setScale(8, RoundingMode.DOWN)
    val remainingSize = size.subtract(filledSize).max(BigDecimal.ZERO).setScale(8, RoundingMode.DOWN)

    val isFilled = fillRatio >= BigDecimal("0.9999")
    val fillPrice = when {
        fillRatio <= BigDecimal.ZERO -> null
        type == "MARKET" -> applySlippage(side, marketFillPrice, estimatedSlippageBps)
        side == "BUY" -> applySlippage(side, quoteAsk, estimatedSlippageBps).min(targetPrice)
        else -> applySlippage(side, quoteBid, estimatedSlippageBps).max(targetPrice)
    }

    val feeProfile = paperFeeProfile(exchange, feeTier)
    val appliedFeeBps = when {
        fillRatio <= BigDecimal.ZERO -> BigDecimal.ZERO
        type == "LIMIT" && !canFillLimit -> feeProfile.makerFeeBps
        type == "LIMIT" && orderRequest.postOnly && !canFillLimit -> feeProfile.makerFeeBps
        else -> feeProfile.takerFeeBps
    }

    val spreadCostBps = when {
        fillRatio <= BigDecimal.ZERO -> BigDecimal.ZERO
        type == "MARKET" -> spreadBps.divide(BigDecimal("2"), 8, RoundingMode.HALF_UP)
        type == "LIMIT" && canFillLimit -> spreadBps.divide(BigDecimal("3"), 8, RoundingMode.HALF_UP)
        else -> spreadBps.divide(BigDecimal("10"), 8, RoundingMode.HALF_UP)
    }
    val adverseSelectionBps = when {
        fillRatio <= BigDecimal.ZERO -> BigDecimal.ZERO
        urgency == UrgencyClass.HIGH -> BigDecimal("1.5")
        type == "MARKET" -> BigDecimal("0.8")
        else -> BigDecimal("0.3")
    }
    val fundingDriftBps = if (orderRequest.symbol.contains("PERP", ignoreCase = true)) {
        when (regimeBucket) {
            "high_volatility", "event_jump" -> BigDecimal("1.2")
            "liquidity_stress" -> BigDecimal("0.8")
            else -> BigDecimal("0.35")
        }
    } else {
        BigDecimal.ZERO
    }
    val basisDriftBps = if (orderRequest.symbol.contains("PERP", ignoreCase = true)) {
        when (urgency) {
            UrgencyClass.LOW -> BigDecimal("0.20")
            UrgencyClass.NORMAL -> BigDecimal("0.45")
            UrgencyClass.HIGH -> BigDecimal("0.80")
        }
    } else {
        BigDecimal.ZERO
    }

    val totalCostBps =
        appliedFeeBps + spreadCostBps + estimatedSlippageBps + sizeImpactBps + adverseSelectionBps + fundingDriftBps + basisDriftBps
    val executionNotional = filledSize.multiply(fillPrice ?: marketFillPrice)
    val estimatedFeeUsd = executionNotional.multiply(appliedFeeBps).divide(BigDecimal("10000"), 8, RoundingMode.HALF_UP)
    val estimatedCostUsd = executionNotional.multiply(totalCostBps).divide(BigDecimal("10000"), 8, RoundingMode.HALF_UP)

    val cancelCadenceMs = orderRequest.cancelAfterMs?.coerceAtLeast(100L) ?: when (urgency) {
        UrgencyClass.LOW -> 2_000L
        UrgencyClass.NORMAL -> 1_000L
        UrgencyClass.HIGH -> 400L
    }
    val tierLatencyAdj = when {
        notional <= BigDecimal("25000") -> 0L
        notional <= BigDecimal("100000") -> 20L
        else -> 45L
    }
    val decisionLatencyMs = 8L + tierLatencyAdj / 10L + when (urgency) {
        UrgencyClass.LOW -> 3L
        UrgencyClass.NORMAL -> 2L
        UrgencyClass.HIGH -> 1L
    }
    val submitToAckMs = when (urgency) {
        UrgencyClass.LOW -> 140L
        UrgencyClass.NORMAL -> 90L
        UrgencyClass.HIGH -> 55L
    } + tierLatencyAdj
    val submitToFirstFillMs = if (fillRatio > BigDecimal.ZERO) submitToAckMs + 15L else null
    val submitToFinalFillMs = when {
        fillRatio <= BigDecimal.ZERO -> null
        isFilled -> submitToFirstFillMs?.plus(30L + tierLatencyAdj)
        else -> submitToFirstFillMs?.plus(450L + tierLatencyAdj * 2L)
    }
    val jitterMs = 6L + tierLatencyAdj / 5L
    val p50RoundTripMs = submitToAckMs + jitterMs / 2L
    val p95RoundTripMs = submitToAckMs * 2L + jitterMs
    val p99RoundTripMs = submitToAckMs * 3L + jitterMs * 2L
    val queuePositionEstimate = estimateQueuePosition(type, urgency, canFillLimit, liquidityBucket)
    val expectedFillProbability = fillRatio.setScale(6, RoundingMode.HALF_UP).toDouble()
    val projectedPartialFills = buildProjectedFills(
        filledSize = filledSize,
        fillPrice = fillPrice ?: marketFillPrice,
        submitToFirstFillMs = submitToFirstFillMs,
        submitToFinalFillMs = submitToFinalFillMs
    )
    val failToFillReason = when {
        rejectionReason != null -> rejectionReason
        fillRatio <= BigDecimal.ZERO && type == "LIMIT" -> "Queue priority too deep for immediate fill at requested limit price"
        fillRatio <= BigDecimal.ZERO -> "No executable liquidity available at current controls"
        else -> null
    }

    val placementSchedule = buildPlacementSchedule(
        side = side,
        type = type,
        urgency = urgency,
        targetPrice = targetPrice,
        spread = spread,
        marketFillPrice = marketFillPrice
    )

    val status = when {
        rejectionReason != null -> "REJECTED"
        fillRatio <= BigDecimal.ZERO -> "PENDING"
        isFilled -> "FILLED"
        else -> "PARTIALLY_FILLED"
    }

    return PaperOrderResponse(
        orderId = "paper-$exchange-${UUID.randomUUID().toString().take(12)}",
        status = status,
        requestedSize = size.setScale(8, RoundingMode.DOWN).stripTrailingZeros().toPlainString(),
        filledSize = filledSize.stripTrailingZeros().toPlainString(),
        remainingSize = remainingSize.stripTrailingZeros().toPlainString(),
        fillRatio = fillRatio.setScale(6, RoundingMode.HALF_UP).toDouble(),
        fillPrice = fillPrice?.setScale(8, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString(),
        exchange = exchange,
        symbol = orderRequest.symbol,
        side = side,
        type = type,
        simulated = true,
        executionMode = "paper",
        rejectionReason = rejectionReason,
        quoteAgeMs = Duration.between(quote.timestamp, now).toMillis().coerceAtLeast(0L),
        quote = PaperQuoteSnapshot(
            bid = quote.bid,
            ask = quote.ask,
            last = quote.last,
            timestamp = quote.timestamp.toString(),
            source = quote.source
        ),
        policy = PaperExecutionPolicy(
            venue = exchange,
            orderType = type,
            urgencyClass = urgency.name.lowercase(),
            cancelCadenceMs = cancelCadenceMs,
            placementSchedule = placementSchedule
        ),
        costs = PaperCostBreakdown(
            feeTier = feeProfile.tier.name.lowercase(),
            feeTierAdjustmentBps = feeProfile.tierAdjustmentBps.toDouble(),
            makerFeeBps = feeProfile.makerFeeBps.toDouble(),
            takerFeeBps = feeProfile.takerFeeBps.toDouble(),
            appliedFeeBps = appliedFeeBps.toDouble(),
            spreadCostBps = spreadCostBps.setScale(6, RoundingMode.HALF_UP).toDouble(),
            slippageBps = estimatedSlippageBps.setScale(6, RoundingMode.HALF_UP).toDouble(),
            impactBps = sizeImpactBps.toDouble(),
            adverseSelectionBps = adverseSelectionBps.toDouble(),
            fundingDriftBps = fundingDriftBps.toDouble(),
            basisDriftBps = basisDriftBps.toDouble(),
            totalCostBps = totalCostBps.setScale(6, RoundingMode.HALF_UP).toDouble(),
            estimatedFeeUsd = estimatedFeeUsd.setScale(6, RoundingMode.HALF_UP).toDouble(),
            estimatedCostUsd = estimatedCostUsd.setScale(6, RoundingMode.HALF_UP).toDouble()
        ),
        telemetry = PaperExecutionTelemetry(
            decisionLatencyMs = decisionLatencyMs,
            submitToAckMs = submitToAckMs,
            submitToFirstFillMs = submitToFirstFillMs,
            submitToFinalFillMs = submitToFinalFillMs,
            cancelReplaceLatencyMs = cancelCadenceMs,
            p50RoundTripMs = p50RoundTripMs,
            p95RoundTripMs = p95RoundTripMs,
            p99RoundTripMs = p99RoundTripMs,
            jitterMs = jitterMs
        ),
        simulation = PaperExecutionSimulation(
            queuePositionEstimate = queuePositionEstimate,
            expectedFillProbability = expectedFillProbability,
            projectedPartialFills = projectedPartialFills,
            failToFillReason = failToFillReason,
            regimeBucket = regimeBucket,
            liquidityBucket = liquidityBucket
        ),
        receivedAt = now.toString()
    )
}

private data class PaperFeeProfile(
    val makerFeeBps: BigDecimal,
    val takerFeeBps: BigDecimal,
    val tier: FeeTier,
    val tierAdjustmentBps: BigDecimal
)

private fun estimateOrderNotionalUsd(
    orderRequest: OrderRequest,
    quote: LatestQuote?
): BigDecimal? {
    val side = orderRequest.side.trim().uppercase()
    if (side != "BUY" && side != "SELL") return null

    val type = orderRequest.type.trim().uppercase()
    if (type != "MARKET" && type != "LIMIT") return null

    val size = orderRequest.size.trim().toBigDecimalOrNull() ?: return null
    if (size <= BigDecimal.ZERO) return null

    val explicitPrice = orderRequest.price
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.toBigDecimalOrNull()

    val bookPrice = quote?.let {
        if (side == "BUY") it.ask.toBigDecimal() else it.bid.toBigDecimal()
    }

    val executionPrice = when (type) {
        "MARKET" -> bookPrice
        else -> explicitPrice
    } ?: return null

    if (executionPrice <= BigDecimal.ZERO) return null
    return size.abs().multiply(executionPrice)
}

private fun paperFeeProfile(exchange: String, tier: FeeTier): PaperFeeProfile {
    val base = when (exchange.lowercase()) {
        "binance" -> BigDecimal("1.0") to BigDecimal("5.0")
        "bybit" -> BigDecimal("1.5") to BigDecimal("5.5")
        "coinbase" -> BigDecimal("3.5") to BigDecimal("6.0")
        "dydx" -> BigDecimal("2.0") to BigDecimal("5.0")
        "swyftx" -> BigDecimal("4.0") to BigDecimal("7.0")
        "aster" -> BigDecimal("2.0") to BigDecimal("5.0")
        else -> BigDecimal("2.0") to BigDecimal("5.0")
    }
    val tierAdjustment = when (tier) {
        FeeTier.RETAIL -> BigDecimal.ZERO
        FeeTier.PRO -> BigDecimal("-0.5")
        FeeTier.VIP -> BigDecimal("-1.5")
    }
    return PaperFeeProfile(
        makerFeeBps = (base.first + tierAdjustment).max(BigDecimal("-2.0")),
        takerFeeBps = (base.second + tierAdjustment).max(BigDecimal("0.4")),
        tier = tier,
        tierAdjustmentBps = tierAdjustment
    )
}

private fun parseUrgencyClass(raw: String?): UrgencyClass = when (raw?.trim()?.lowercase()) {
    "low" -> UrgencyClass.LOW
    "high" -> UrgencyClass.HIGH
    else -> UrgencyClass.NORMAL
}

private fun parseFeeTier(raw: String?): FeeTier = when (raw?.trim()?.lowercase()) {
    "pro" -> FeeTier.PRO
    "vip" -> FeeTier.VIP
    else -> FeeTier.RETAIL
}

private fun validateOrderRequest(orderRequest: OrderRequest): String? {
    if (orderRequest.symbol != orderRequest.symbol.trim()) {
        return "symbol must not contain leading or trailing whitespace"
    }
    val symbol = orderRequest.symbol.trim()
    if (!symbolPattern.matches(symbol)) {
        return "Invalid symbol: ${orderRequest.symbol}"
    }

    val side = orderRequest.side.trim().uppercase()
    if (side != "BUY" && side != "SELL") {
        return "Invalid side: ${orderRequest.side}"
    }

    val type = orderRequest.type.trim().uppercase()
    if (type != "MARKET" && type != "LIMIT") {
        return "Invalid type: ${orderRequest.type}"
    }

    val size = orderRequest.size.trim().toBigDecimalOrNull()
        ?: return "Invalid size: ${orderRequest.size}"
    if (size <= BigDecimal.ZERO) {
        return "size must be > 0"
    }
    if (size > maxOrderSize) {
        return "size exceeds safety limit"
    }

    val parsedPrice = orderRequest.price
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.toBigDecimalOrNull()
    if (orderRequest.price != null && parsedPrice == null) {
        return "Invalid price: ${orderRequest.price}"
    }
    if (parsedPrice != null && parsedPrice <= BigDecimal.ZERO) {
        return "price must be > 0"
    }
    if (parsedPrice != null && parsedPrice > maxOrderPrice) {
        return "price exceeds safety limit"
    }
    if (type == "LIMIT" && parsedPrice == null) {
        return "Limit orders require a positive price"
    }
    if (type == "MARKET" && parsedPrice != null) {
        return "Market orders must not include price"
    }

    return null
}

private fun validateExecutionControls(orderRequest: OrderRequest): String? {
    val type = orderRequest.type.trim().uppercase()
    if (orderRequest.postOnly && type != "LIMIT") {
        return "postOnly is only valid for LIMIT orders"
    }

    val urgencyClass = orderRequest.urgencyClass?.trim()?.lowercase()
    if (!urgencyClass.isNullOrBlank() && urgencyClass !in setOf("low", "normal", "high")) {
        return "Invalid urgencyClass: ${orderRequest.urgencyClass}"
    }

    val feeTier = orderRequest.feeTier?.trim()?.lowercase()
    if (!feeTier.isNullOrBlank() && feeTier !in setOf("retail", "pro", "vip")) {
        return "Invalid feeTier: ${orderRequest.feeTier}"
    }

    val maxSlippageBps = orderRequest.maxSlippageBps
    if (maxSlippageBps != null) {
        if (!maxSlippageBps.isFinite()) {
            return "maxSlippageBps must be finite"
        }
        if (maxSlippageBps < 0.0) {
            return "maxSlippageBps must be >= 0"
        }
        if (maxSlippageBps > 500.0) {
            return "maxSlippageBps is unreasonably high"
        }
    }

    val cancelAfterMs = orderRequest.cancelAfterMs
    if (cancelAfterMs != null) {
        if (cancelAfterMs < 100L) {
            return "cancelAfterMs must be >= 100"
        }
        if (cancelAfterMs > 600_000L) {
            return "cancelAfterMs must be <= 600000"
        }
    }

    return null
}

private fun applySlippage(side: String, referencePrice: BigDecimal, slippageBps: BigDecimal): BigDecimal {
    val slipFactor = slippageBps.divide(BigDecimal("10000"), 18, RoundingMode.HALF_UP)
    return if (side == "BUY") {
        referencePrice.multiply(BigDecimal.ONE + slipFactor)
    } else {
        referencePrice.multiply(BigDecimal.ONE - slipFactor)
    }
}

private fun buildPlacementSchedule(
    side: String,
    type: String,
    urgency: UrgencyClass,
    targetPrice: BigDecimal,
    spread: BigDecimal,
    marketFillPrice: BigDecimal
): List<PaperPlacementSlice> {
    val schedule = when (type) {
        "MARKET" -> listOf(
            PaperPlacementSlice(
                delayMs = 0,
                price = marketFillPrice.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                sizePercent = 100
            )
        )

        else -> when (urgency) {
            UrgencyClass.LOW -> listOf(
                PaperPlacementSlice(
                    delayMs = 0,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.10"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 40
                ),
                PaperPlacementSlice(
                    delayMs = 1_000,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.25"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 30
                ),
                PaperPlacementSlice(
                    delayMs = 2_000,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.50"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 30
                )
            )

            UrgencyClass.NORMAL -> listOf(
                PaperPlacementSlice(
                    delayMs = 0,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.20"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 60
                ),
                PaperPlacementSlice(
                    delayMs = 600,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.40"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 40
                )
            )

            UrgencyClass.HIGH -> listOf(
                PaperPlacementSlice(
                    delayMs = 0,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.30"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 70
                ),
                PaperPlacementSlice(
                    delayMs = 250,
                    price = (targetPrice + signedOffset(side, spread.multiply(BigDecimal("0.60"))))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString(),
                    sizePercent = 30
                )
            )
        }
    }
    return schedule
}

private fun estimateQueuePosition(
    orderType: String,
    urgency: UrgencyClass,
    canFillLimit: Boolean,
    liquidityBucket: String
): Int {
    if (orderType == "MARKET" || canFillLimit) return 1
    val base = when (liquidityBucket) {
        "liquidity_stress" -> 28
        "thin" -> 18
        else -> 10
    }
    val urgencyAdj = when (urgency) {
        UrgencyClass.HIGH -> -4
        UrgencyClass.NORMAL -> 0
        UrgencyClass.LOW -> 5
    }
    return (base + urgencyAdj).coerceAtLeast(1)
}

private fun buildProjectedFills(
    filledSize: BigDecimal,
    fillPrice: BigDecimal,
    submitToFirstFillMs: Long?,
    submitToFinalFillMs: Long?
): List<PaperFillSlice> {
    if (filledSize <= BigDecimal.ZERO) return emptyList()
    val firstDelay = submitToFirstFillMs ?: 0L
    val finalDelay = submitToFinalFillMs ?: firstDelay
    val duration = (finalDelay - firstDelay).coerceAtLeast(0L)
    val ratios = when {
        duration <= 80L -> listOf(BigDecimal.ONE)
        duration <= 400L -> listOf(BigDecimal("0.6"), BigDecimal("0.4"))
        else -> listOf(BigDecimal("0.5"), BigDecimal("0.3"), BigDecimal("0.2"))
    }

    val slices = mutableListOf<PaperFillSlice>()
    var remaining = filledSize
    ratios.forEachIndexed { index, ratio ->
        val sliceSize = if (index == ratios.lastIndex) {
            remaining
        } else {
            filledSize.multiply(ratio).setScale(8, RoundingMode.DOWN)
        }
        remaining = (remaining - sliceSize).max(BigDecimal.ZERO)
        val offset = if (ratios.size == 1) {
            0L
        } else {
            (duration * index.toLong()) / (ratios.lastIndex.toLong().coerceAtLeast(1L))
        }
        slices += PaperFillSlice(
            delayMs = firstDelay + offset,
            size = sliceSize.stripTrailingZeros().toPlainString(),
            price = fillPrice.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        )
    }
    return slices
}

private fun classifyRegimeBucket(spreadBps: BigDecimal, slippageBps: BigDecimal): String = when {
    spreadBps >= BigDecimal("20") || slippageBps >= BigDecimal("25") -> "liquidity_stress"
    spreadBps >= BigDecimal("8") || slippageBps >= BigDecimal("12") -> "high_volatility"
    spreadBps >= BigDecimal("4") || slippageBps >= BigDecimal("6") -> "event_jump"
    else -> "low_volatility"
}

private fun classifyLiquidityBucket(spreadBps: BigDecimal, notional: BigDecimal): String = when {
    spreadBps >= BigDecimal("20") -> "liquidity_stress"
    spreadBps >= BigDecimal("10") || notional > BigDecimal("250000") -> "thin"
    spreadBps >= BigDecimal("4") || notional > BigDecimal("100000") -> "medium"
    else -> "deep"
}

private fun signedOffset(side: String, value: BigDecimal): BigDecimal {
    return if (side == "BUY") value.negate() else value
}

private fun BigDecimal.coerceIn(min: BigDecimal, max: BigDecimal): BigDecimal {
    return when {
        this < min -> min
        this > max -> max
        else -> this
    }
}

private fun resolveQuoteWithPaperFallback(
    dbService: DatabaseService,
    exchange: String,
    symbol: String
): LatestQuote? {
    val directQuote = dbService.fetchLatestQuote(exchange = exchange, symbol = symbol)
    if (directQuote != null) return directQuote
    if (exchange in liveOrderExchanges) return null

    val proxyQuote = dbService.fetchLatestQuote(exchange = "hyperliquid", symbol = symbol) ?: return null
    return proxyQuote.copy(
        exchange = exchange,
        source = "proxy:hyperliquid:${proxyQuote.source}"
    )
}

private fun toBps(numerator: BigDecimal, denominator: BigDecimal): BigDecimal {
    if (denominator <= BigDecimal.ZERO) return BigDecimal.ZERO
    return numerator
        .multiply(BigDecimal("10000"))
        .divide(denominator, 8, RoundingMode.HALF_UP)
}

private fun Any?.toBigDecimalOrNull(): BigDecimal? = when (this) {
    null -> null
    is BigDecimal -> this
    is Number -> runCatching { BigDecimal(this.toString()) }.getOrNull()
    is String -> runCatching {
        this.trim().takeIf { it.isNotEmpty() }?.let { BigDecimal(it) }
    }.getOrNull()
    else -> null
}

private fun LatestQuote.isValidSnapshot(): Boolean {
    if (!bid.isFinite() || !ask.isFinite() || !last.isFinite()) return false
    if (bid <= 0.0 || ask <= 0.0 || last <= 0.0) return false
    return ask >= bid
}

private fun LatestQuote.quoteAgeMs(referenceTime: Instant = Instant.now()): Long {
    return Duration.between(timestamp, referenceTime).toMillis()
}

private fun LatestQuote.isFreshSnapshot(
    referenceTime: Instant = Instant.now(),
    maxQuoteAgeMs: Long
): Boolean {
    val ageMs = quoteAgeMs(referenceTime)
    if (ageMs < -5_000L) return false
    return ageMs <= maxQuoteAgeMs
}

private fun LatestQuote.isOrderbookBacked(): Boolean {
    return source.lowercase().startsWith("orderbook_data")
}

private fun Map<String, Any>.executedNotionalUsd(
    estimatedNotionalUsd: BigDecimal,
    requestedSizeRaw: String
): BigDecimal? {
    val explicitNotional = this["executedNotionalUsd"].toBigDecimalOrNull()
    if (explicitNotional != null && explicitNotional > BigDecimal.ZERO) {
        return explicitNotional
    }

    val filledSize = this["filledSize"].toBigDecimalOrNull()
    val fillPrice = this["fillPrice"].toBigDecimalOrNull()
    if (filledSize != null && fillPrice != null && filledSize > BigDecimal.ZERO && fillPrice > BigDecimal.ZERO) {
        return filledSize.multiply(fillPrice).setScale(8, RoundingMode.HALF_UP)
    }

    val status = this["status"]?.toString()?.trim()?.uppercase().orEmpty()
    val requestedSize = requestedSizeRaw.toBigDecimalOrNull()
    if (filledSize != null && requestedSize != null && requestedSize > BigDecimal.ZERO && filledSize > BigDecimal.ZERO) {
        val fillRatio = filledSize
            .divide(requestedSize, 18, RoundingMode.HALF_UP)
            .coerceIn(BigDecimal.ZERO, BigDecimal.ONE)
        return estimatedNotionalUsd.multiply(fillRatio).setScale(8, RoundingMode.HALF_UP)
    }
    return if (status == "FILLED") estimatedNotionalUsd else null
}

private fun reconcileHyperliquidRiskState(
    dbService: DatabaseService,
    workerClient: WorkerClient,
    username: String,
    hyperliquidKey: String
) {
    val balance = workerClient.getHyperliquidBalance(username, hyperliquidKey)
    val positions = workerClient.getHyperliquidPositions(username, hyperliquidKey)

    val accountEquity = balance["accountValue"].toBigDecimalOrNull()
        ?: balance["totalRawUsd"].toBigDecimalOrNull()
    var openExposureUsd = BigDecimal.ZERO
    var unrealizedPnlUsd = BigDecimal.ZERO

    positions.forEach { position ->
        val size = position["size"].toBigDecimalOrNull()?.abs() ?: BigDecimal.ZERO
        val entryPrice = position["entryPrice"].toBigDecimalOrNull()
        val marginUsed = position["marginUsed"].toBigDecimalOrNull()
        val notional = when {
            size <= BigDecimal.ZERO -> BigDecimal.ZERO
            entryPrice != null && entryPrice > BigDecimal.ZERO -> size.multiply(entryPrice)
            marginUsed != null && marginUsed > BigDecimal.ZERO -> marginUsed
            else -> BigDecimal.ZERO
        }
        openExposureUsd = openExposureUsd.add(notional)
        unrealizedPnlUsd = unrealizedPnlUsd.add(position["unrealizedPnl"].toBigDecimalOrNull() ?: BigDecimal.ZERO)
    }

    val patch = RiskAccountStatePatch(
        accountEquityUsd = accountEquity,
        unrealizedPnlUsd = unrealizedPnlUsd,
        openExposureUsd = openExposureUsd
    )
    dbService.upsertRiskAccountState(username = username, patch = patch)
}

private fun PaperOrderResponse.estimatedExecutedNotionalUsd(): BigDecimal? {
    val fillPx = fillPrice?.toBigDecimalOrNull() ?: return null
    val filled = filledSize.toBigDecimalOrNull() ?: return null
    if (fillPx <= BigDecimal.ZERO || filled <= BigDecimal.ZERO) return null
    return fillPx.multiply(filled)
}

private fun RiskDecision.toRejectionPayload(): RiskRejectionResponse {
    return RiskRejectionResponse(
        error = "Order blocked by risk engine",
        risk = RiskRejectionDetails(
            allowed = allowed,
            tier = tier.name.lowercase(),
            action = action.name.lowercase(),
            reason = reason,
            policyId = policyId,
            policyVersion = policyVersion,
            suggestedMaxOrderNotionalUsd = suggestedMaxOrderNotionalUsd?.toPlainString(),
            unwindSliceSeconds = unwindSliceSeconds,
            unwindMaxSlippageBps = unwindMaxSlippageBps,
            metrics = RiskRejectionMetrics(
                currentExposureUsd = metrics.currentExposureUsd.toPlainString(),
                projectedExposureUsd = metrics.projectedExposureUsd.toPlainString(),
                accountEquityUsd = metrics.accountEquityUsd.toPlainString(),
                highWaterMarkUsd = metrics.highWaterMarkUsd.toPlainString(),
                dailyLossUsd = metrics.dailyLossUsd.toPlainString(),
                leverage = metrics.leverage,
                exposureUtilization = metrics.exposureUtilization,
                drawdownPct = metrics.drawdownPct,
                drawdownUtilization = metrics.drawdownUtilization,
                dailyLossUtilization = metrics.dailyLossUtilization,
                leverageUtilization = metrics.leverageUtilization
            ),
            sentiment = sentiment?.let {
                RiskRejectionSentiment(
                    symbol = it.symbol,
                    sentimentScore = it.sentimentScore,
                    confidence = it.confidence,
                    observedAt = it.observedAt.toString(),
                    modelName = it.modelName,
                    source = it.source,
                    label = it.sentimentLabel
                )
            }
        )
    )
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
