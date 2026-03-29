package org.datamancy.trading.alpha

import org.datamancy.trading.TxGateway
import org.datamancy.trading.models.ApiResult
import org.datamancy.trading.models.ExchangeId
import org.datamancy.trading.models.OrderType
import org.datamancy.trading.models.Side
import org.datamancy.trading.models.TradingMode
import org.datamancy.trading.models.UnifiedOrderRequest
import java.math.BigDecimal

class AlphaExecutionSubmitter(
    private val txGatewayUrlProvider: () -> String
) {
    suspend fun submit(
        request: AlphaExecutionSubmitRequest,
        bearerToken: String,
        credentials: Map<String, String>
    ): AlphaExecutionSubmission {
        require(bearerToken.isNotBlank()) { "tx-gateway bearer token is required" }
        require(request.symbol.isNotBlank()) { "symbol is required" }
        require(request.size > 0.0) { "size must be positive" }

        val gateway = TxGateway.create(
            url = txGatewayUrlProvider(),
            token = bearerToken,
            credentials = credentials
        )
        val exchange = ExchangeId.entries.firstOrNull { it.apiName == request.exchange.trim().lowercase() }
            ?: error("Unsupported exchange: ${request.exchange}")
        val side = when (request.direction) {
            AlphaDirection.LONG -> Side.BUY
            AlphaDirection.SHORT -> Side.SELL
        }
        val orderType = OrderType.valueOf(request.orderType.trim().uppercase())
        val executionMode = when (request.mode) {
            AlphaRunMode.OFFLINE_BACKTEST,
            AlphaRunMode.WALK_FORWARD,
            AlphaRunMode.FORWARD_PAPER -> TradingMode.FORWARD_PAPER
            AlphaRunMode.TESTNET_LIVE -> TradingMode.TESTNET_LIVE
            AlphaRunMode.LIVE -> TradingMode.MAINNET_LIVE
        }
        val requestedSize = BigDecimal.valueOf(request.size)
        val marketCatalog = when (val result = gateway.exchanges.markets(exchange)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> error("Unable to fetch market catalog for ${exchange.apiName}: ${result.message}")
        }
        val market = marketCatalog.firstOrNull { it.symbol.equals(request.symbol.trim(), ignoreCase = true) }
            ?: error("No market metadata found for ${request.symbol} on ${exchange.apiName}")
        val sizingDecision = ExchangeOrderSizing.quantizeSize(
            symbol = request.symbol.trim(),
            requestedSize = requestedSize,
            market = market
        )

        val gatewayRequest = UnifiedOrderRequest(
            exchange = exchange,
            symbol = request.symbol,
            side = side,
            type = orderType,
            size = sizingDecision.size,
            price = request.price?.let(BigDecimal::valueOf),
            executionMode = executionMode,
            reduceOnly = request.reduceOnly,
            urgencyClass = request.urgencyClass,
            feeTier = request.feeTier,
            maxSlippageBps = request.maxSlippageBps?.let(BigDecimal::valueOf),
            cancelAfterMs = request.cancelAfterMs
        )

        return when (val result = gateway.exchanges.placeOrder(gatewayRequest)) {
            is ApiResult.Success -> AlphaExecutionSubmission(
                accepted = true,
                exchange = result.data.exchange.apiName,
                symbol = result.data.symbol,
                mode = request.mode,
                orderId = result.data.orderId,
                status = result.data.status.name,
                filledSize = result.data.filledSize.toDouble(),
                fillPrice = result.data.fillPrice?.toDouble(),
                notes = sizingDecision.notes + listOf(
                    "Order reached tx-gateway and returned a concrete execution result.",
                    "Execution realism remains enforced by tx-gateway and venue-specific worker paths."
                )
            )
            is ApiResult.Error -> AlphaExecutionSubmission(
                accepted = false,
                exchange = request.exchange.trim().lowercase(),
                symbol = request.symbol,
                mode = request.mode,
                upstreamCode = result.code,
                error = result.message,
                notes = sizingDecision.notes + listOf(
                    "Route reached tx-gateway but upstream execution was rejected or blocked.",
                    "Treat provisioning, risk, and credential errors separately from transport failures."
                )
            )
        }
    }
}
