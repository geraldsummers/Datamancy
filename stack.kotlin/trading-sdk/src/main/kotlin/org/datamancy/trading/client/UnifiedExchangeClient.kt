package org.datamancy.trading.client

import org.datamancy.trading.models.*
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * Unified exchange surface and simple muxing helpers.
 *
 * Endpoints are intentionally gateway-routed so exchange adapters can be added
 * incrementally without changing notebook/client code.
 */
class UnifiedExchangeClient internal constructor(
    private val httpClient: TradingHttpClient
) {
    private val logger = LoggerFactory.getLogger(UnifiedExchangeClient::class.java)

    private val supported = listOf(
        ExchangeId.SWYFTX,
        ExchangeId.BINANCE,
        ExchangeId.BYBIT,
        ExchangeId.COINBASE,
        ExchangeId.DYDX,
        ExchangeId.HYPERLIQUID,
        ExchangeId.ASTER
    )
    private val maxGatewayQuoteAge = Duration.ofMinutes(10)
    private val futureQuoteSkewTolerance = Duration.ofSeconds(5)

    fun supportedExchanges(): List<ExchangeId> = supported

    suspend fun quote(
        exchange: ExchangeId,
        symbol: String,
        executionMode: TradingMode = TradingMode.FORWARD_PAPER
    ): ApiResult<UnifiedQuote> {
        val path = buildString {
            append("/api/v1/exchanges/${exchange.apiName}/quote")
            append("?symbol=${symbol.encodeForQuery()}")
            append("&executionMode=${executionMode.name.lowercase().encodeForQuery()}")
        }
        return when (val result = httpClient.get<Map<String, Any>>(path)) {
            is ApiResult.Success -> parseQuote(exchange, symbol, result.data)
            is ApiResult.Error -> ApiResult.Error(
                "Quote unavailable for ${exchange.apiName}: ${result.message}",
                result.code
            )
        }
    }

    suspend fun placeOrder(request: UnifiedOrderRequest): ApiResult<UnifiedOrderResult> {
        val path = "/api/v1/exchanges/${request.exchange.apiName}/order"
        val executionMode = request.executionMode ?: TradingMode.FORWARD_PAPER
        val payload = mapOf(
            "symbol" to request.symbol,
            "side" to request.side.name,
            "type" to request.type.name,
            "size" to request.size.toString(),
            "price" to request.price?.toString(),
            "executionMode" to executionMode.name.lowercase(),
            "reduceOnly" to request.reduceOnly,
            "urgencyClass" to request.urgencyClass,
            "feeTier" to request.feeTier,
            "maxSlippageBps" to request.maxSlippageBps?.toDouble(),
            "cancelAfterMs" to request.cancelAfterMs
        )

        return when (val result = httpClient.post<Map<String, Any?>, Map<String, Any?>>(path, payload)) {
            is ApiResult.Success -> parseOrderResult(request, result.data)
            is ApiResult.Error -> ApiResult.Error(
                "Order failed on ${request.exchange.apiName}: ${result.message}",
                result.code
            )
        }
    }

    /**
     * Query multiple exchanges and select the best executable quote for a side.
     */
    suspend fun bestQuote(
        symbol: String,
        side: Side,
        exchanges: List<ExchangeId> = supported,
        executionMode: TradingMode = TradingMode.FORWARD_PAPER
    ): ApiResult<UnifiedQuote> {
        val candidates = mutableListOf<UnifiedQuote>()
        val errors = mutableListOf<String>()

        for (exchange in exchanges.distinct()) {
            when (val q = quote(exchange, symbol, executionMode)) {
                is ApiResult.Success -> candidates += q.data
                is ApiResult.Error -> errors += "${exchange.apiName}: ${q.message}"
            }
        }

        val best = selectBestQuote(candidates, side)
        if (best != null) {
            return ApiResult.Success(best)
        }

        val reason = if (errors.isNotEmpty()) errors.joinToString(" | ") else "no quote adapters returned data"
        return ApiResult.Error("No executable quote for $symbol ($side): $reason")
    }

    /**
     * Ask tx-gateway to compute best venue in one request.
     * Falls back to the local fan-out implementation if unavailable.
     */
    suspend fun bestQuoteViaGateway(
        symbol: String,
        side: Side,
        exchanges: List<ExchangeId> = supported,
        executionMode: TradingMode = TradingMode.FORWARD_PAPER
    ): ApiResult<UnifiedQuote> {
        val exchangeCsv = exchanges.distinct().joinToString(",") { it.apiName }
        val path =
            "/api/v1/exchanges/best-quote?symbol=${symbol.encodeForQuery()}&side=${side.name.lowercase()}&exchanges=${exchangeCsv.encodeForQuery()}&executionMode=${executionMode.name.lowercase().encodeForQuery()}"

        return when (val result = httpClient.get<Map<String, Any?>>(path)) {
            is ApiResult.Success -> {
                parseBestQuotePayload(
                    payload = result.data,
                    allowedExchangeNames = exchanges.map { it.apiName }.toSet()
                )
                    ?: bestQuote(symbol = symbol, side = side, exchanges = exchanges, executionMode = executionMode)
            }
            is ApiResult.Error -> bestQuote(symbol = symbol, side = side, exchanges = exchanges, executionMode = executionMode)
        }
    }

    private fun parseQuote(exchange: ExchangeId, symbol: String, payload: Map<String, Any>): ApiResult<UnifiedQuote> {
        val bid = payload["bid"].toBigDecimalOrNull()
        val ask = payload["ask"].toBigDecimalOrNull()
        val last = payload["last"].toBigDecimalOrNull()

        if (bid == null || ask == null || bid <= BigDecimal.ZERO || ask <= BigDecimal.ZERO || ask < bid) {
            logger.warn("Invalid quote payload from {}: {}", exchange.apiName, payload)
            return ApiResult.Error("Invalid quote payload from ${exchange.apiName}")
        }

        return ApiResult.Success(
            UnifiedQuote(
                exchange = exchange,
                symbol = symbol,
                bid = bid,
                ask = ask,
                last = last,
                timestamp = Instant.now()
            )
        )
    }

    private fun parseOrderResult(
        request: UnifiedOrderRequest,
        payload: Map<String, Any?>
    ): ApiResult<UnifiedOrderResult> {
        val orderId = payload["orderId"]?.toString()
            ?: payload["id"]?.toString()
            ?: return ApiResult.Error("Missing orderId in response from ${request.exchange.apiName}")

        val status = payload["status"]?.toString()
            ?.uppercase()
            ?.let { runCatching { OrderStatus.valueOf(it) }.getOrNull() }
            ?: OrderStatus.PENDING
        val executionMode = payload["executionMode"]?.toString()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.uppercase()
            ?.let { runCatching { TradingMode.valueOf(it) }.getOrNull() }

        val requestedSize = request.size.abs()
        val filledSize = payload["filledSize"].toBigDecimalOrNull() ?: BigDecimal.ZERO
        val fillPrice = payload["fillPrice"].toBigDecimalOrNull()
        if (filledSize < BigDecimal.ZERO) {
            return ApiResult.Error("Invalid filledSize in response from ${request.exchange.apiName}")
        }
        if (filledSize > requestedSize) {
            return ApiResult.Error("filledSize exceeds requested size for ${request.exchange.apiName}")
        }
        if (fillPrice != null && fillPrice <= BigDecimal.ZERO) {
            return ApiResult.Error("Invalid fillPrice in response from ${request.exchange.apiName}")
        }
        if (fillPrice != null && filledSize <= BigDecimal.ZERO) {
            return ApiResult.Error("fillPrice provided without fills on ${request.exchange.apiName}")
        }
        if (filledSize > BigDecimal.ZERO && fillPrice == null) {
            return ApiResult.Error("Missing fillPrice for non-zero fill on ${request.exchange.apiName}")
        }
        when (status) {
            OrderStatus.FILLED -> if (filledSize.compareTo(requestedSize) != 0) {
                return ApiResult.Error("Invalid filledSize for FILLED status from ${request.exchange.apiName}")
            }
            OrderStatus.PARTIALLY_FILLED -> if (filledSize <= BigDecimal.ZERO || filledSize.compareTo(requestedSize) >= 0) {
                return ApiResult.Error("Invalid filledSize for PARTIALLY_FILLED status from ${request.exchange.apiName}")
            }
            OrderStatus.PENDING,
            OrderStatus.REJECTED -> if (filledSize > BigDecimal.ZERO) {
                return ApiResult.Error("Invalid status/filledSize combination from ${request.exchange.apiName}")
            }
            OrderStatus.CANCELLED -> if (filledSize.compareTo(requestedSize) == 0 && requestedSize > BigDecimal.ZERO) {
                return ApiResult.Error("Invalid filledSize for CANCELLED status from ${request.exchange.apiName}")
            }
        }

        return ApiResult.Success(
            UnifiedOrderResult(
                exchange = request.exchange,
                orderId = orderId,
                symbol = request.symbol,
                side = request.side,
                type = request.type,
                status = status,
                filledSize = filledSize,
                fillPrice = fillPrice,
                executionMode = executionMode,
                timestamp = Instant.now(),
                raw = payload
            )
        )
    }

    companion object {
        fun selectBestQuote(candidates: List<UnifiedQuote>, side: Side): UnifiedQuote? {
            if (candidates.isEmpty()) return null
            val validCandidates = candidates.filter { it.bid > BigDecimal.ZERO && it.ask > BigDecimal.ZERO && it.ask >= it.bid }
            if (validCandidates.isEmpty()) return null
            return when (side) {
                Side.BUY -> validCandidates.minByOrNull { it.ask }
                Side.SELL -> validCandidates.maxByOrNull { it.bid }
            }
        }
    }

    private fun parseBestQuotePayload(
        payload: Map<String, Any?>,
        allowedExchangeNames: Set<String>
    ): ApiResult<UnifiedQuote>? {
        val quoteNode = payload["quote"] as? Map<*, *> ?: return null
        val exchangeName = quoteNode["exchange"]?.toString()?.trim()?.lowercase() ?: return null
        val exchange = ExchangeId.entries.firstOrNull { it.apiName == exchangeName } ?: return null
        if (exchange.apiName !in allowedExchangeNames) return null
        val symbol = quoteNode["symbol"]?.toString() ?: payload["normalizedSymbol"]?.toString() ?: return null
        val bid = quoteNode["bid"].toBigDecimalOrNull() ?: return null
        val ask = quoteNode["ask"].toBigDecimalOrNull() ?: return null
        if (bid <= BigDecimal.ZERO || ask <= BigDecimal.ZERO || ask < bid) return null
        val last = quoteNode["last"].toBigDecimalOrNull()
        val parsedTimestamp = quoteNode["timestamp"]?.toString()?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrNull()
        } ?: Instant.now()
        val quoteAge = Duration.between(parsedTimestamp, Instant.now())
        if (quoteAge > maxGatewayQuoteAge || quoteAge < futureQuoteSkewTolerance.negated()) return null
        return ApiResult.Success(
            UnifiedQuote(
                exchange = exchange,
                symbol = symbol,
                bid = bid,
                ask = ask,
                last = last,
                timestamp = parsedTimestamp
            )
        )
    }
}

private fun Any?.toBigDecimalOrNull(): BigDecimal? = when (this) {
    null -> null
    is BigDecimal -> this
    is Number -> this.toString().parseBigDecimalOrNull()
    is String -> this.parseBigDecimalOrNull()
    else -> null
}

private fun String.parseBigDecimalOrNull(): BigDecimal? = runCatching {
    trim().takeIf { it.isNotEmpty() }?.let { BigDecimal(it) }
}.getOrNull()

private fun String.encodeForQuery(): String = URLEncoder.encode(this, StandardCharsets.UTF_8)
