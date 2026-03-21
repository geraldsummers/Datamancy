package org.datamancy.trading.strategy

import org.datamancy.trading.models.ApiResult
import org.datamancy.trading.models.OrderStatus
import org.datamancy.trading.models.OrderType
import org.datamancy.trading.models.Side
import org.datamancy.trading.models.TradingMode
import java.math.BigDecimal
import java.util.UUID

data class ModeRoutedOrderRequest(
    val mode: TradingMode,
    val strategyName: String,
    val exchange: String,
    val symbol: String,
    val side: Side,
    val type: OrderType,
    val size: BigDecimal,
    val price: BigDecimal?,
    val reduceOnly: Boolean = false,
    val urgencyClass: String? = null,
    val feeTier: String? = null,
    val maxSlippageBps: BigDecimal? = null,
    val cancelAfterMs: Long? = null,
    val metadata: Map<String, Any?> = emptyMap()
)

data class ModeRoutedOrderResult(
    val mode: TradingMode,
    val orderId: String,
    val exchange: String,
    val symbol: String,
    val status: OrderStatus,
    val filledSize: BigDecimal = BigDecimal.ZERO,
    val fillPrice: BigDecimal? = null,
    val raw: Map<String, Any?> = emptyMap()
)

interface ModeRoutedOrderExecutor {
    suspend fun submit(request: ModeRoutedOrderRequest): ApiResult<ModeRoutedOrderResult>
}

object InMemoryModeRoutedOrderExecutor : ModeRoutedOrderExecutor {
    override suspend fun submit(request: ModeRoutedOrderRequest): ApiResult<ModeRoutedOrderResult> {
        val orderId = "sim-${request.mode.name.lowercase()}-${UUID.randomUUID().toString().take(12)}"
        val response = ModeRoutedOrderResult(
            mode = request.mode,
            orderId = orderId,
            exchange = request.exchange.lowercase(),
            symbol = request.symbol,
            status = OrderStatus.FILLED,
            filledSize = request.size,
            fillPrice = request.price,
            raw = mapOf(
                "simulated" to true,
                "mode" to request.mode.name.lowercase()
            )
        )
        return ApiResult.Success(response)
    }
}
