package org.datamancy.trading.client

import org.datamancy.trading.models.ApiResult
import org.datamancy.trading.models.ExchangeId
import org.datamancy.trading.models.OrderStatus
import org.datamancy.trading.models.TradingMode
import org.datamancy.trading.models.UnifiedOrderRequest
import org.datamancy.trading.strategy.ModeRoutedOrderExecutor
import org.datamancy.trading.strategy.ModeRoutedOrderRequest
import org.datamancy.trading.strategy.ModeRoutedOrderResult

/**
 * Routes one strategy order interface across backtest, forward-paper, and gateway-backed live paths.
 */
class GatewayModeRoutedOrderExecutor(
    private val exchangeClient: UnifiedExchangeClient
) : ModeRoutedOrderExecutor {

    override suspend fun submit(request: ModeRoutedOrderRequest): ApiResult<ModeRoutedOrderResult> {
        if (request.mode == TradingMode.BACKTEST) {
            return ApiResult.Success(
                ModeRoutedOrderResult(
                    mode = request.mode,
                    orderId = "backtest-${request.strategyName}-${System.currentTimeMillis()}",
                    exchange = request.exchange.lowercase(),
                    symbol = request.symbol,
                    status = OrderStatus.FILLED,
                    filledSize = request.size,
                    fillPrice = request.price,
                    raw = mapOf("mode" to "backtest", "simulated" to true)
                )
            )
        }

        val targetExchange = resolveExchange(request)
            ?: return ApiResult.Error("Unsupported exchange: ${request.exchange}")
        val gatewayRequest = UnifiedOrderRequest(
            exchange = targetExchange,
            symbol = request.symbol,
            side = request.side,
            type = request.type,
            size = request.size,
            price = request.price,
            executionMode = request.mode,
            reduceOnly = request.reduceOnly,
            urgencyClass = request.urgencyClass,
            feeTier = request.feeTier,
            maxSlippageBps = request.maxSlippageBps,
            cancelAfterMs = request.cancelAfterMs
        )

        return when (val result = exchangeClient.placeOrder(gatewayRequest)) {
            is ApiResult.Success -> ApiResult.Success(
                ModeRoutedOrderResult(
                    mode = request.mode,
                    orderId = result.data.orderId,
                    exchange = targetExchange.apiName,
                    symbol = result.data.symbol,
                    status = result.data.status,
                    filledSize = result.data.filledSize,
                    fillPrice = result.data.fillPrice,
                    raw = result.data.raw + mapOf(
                        "mode" to request.mode.name.lowercase(),
                        "requestedExchange" to request.exchange.lowercase()
                    )
                )
            )
            is ApiResult.Error -> ApiResult.Error(result.message, result.code)
        }
    }

    private fun resolveExchange(request: ModeRoutedOrderRequest): ExchangeId? {
        val rawExchange = request.exchange.trim().lowercase()
        val requested = rawExchange.takeIf { it.isNotBlank() }
            ?.let { candidate -> ExchangeId.entries.firstOrNull { it.apiName == candidate } }
        if (rawExchange.isNotBlank() && requested == null) {
            return null
        }
        return when (request.mode) {
            TradingMode.FORWARD_PAPER -> requested ?: ExchangeId.HYPERLIQUID
            TradingMode.TESTNET_LIVE,
            TradingMode.MAINNET_LIVE -> requested ?: ExchangeId.HYPERLIQUID
            TradingMode.BACKTEST -> requested ?: ExchangeId.HYPERLIQUID
        }
    }
}
