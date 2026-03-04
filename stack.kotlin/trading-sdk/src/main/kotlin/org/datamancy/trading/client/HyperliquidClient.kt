package org.datamancy.trading.client

import org.datamancy.trading.models.*
import org.slf4j.LoggerFactory
import java.math.BigDecimal

class HyperliquidClient internal constructor(
    private val httpClient: TradingHttpClient
) {
    private val logger = LoggerFactory.getLogger(HyperliquidClient::class.java)

    /**
     * Submit a market order
     */
    suspend fun market(
        symbol: String,
        side: Side,
        size: BigDecimal,
        reduceOnly: Boolean = false
    ): ApiResult<Order> {
        logger.info("Market order: $side $size $symbol (reduceOnly=$reduceOnly)")

        val request = mapOf(
            "symbol" to symbol,
            "side" to side.name,
            "type" to "MARKET",
            "size" to size.toString(),
            "reduceOnly" to reduceOnly
        )

        return httpClient.post("/api/v1/hyperliquid/order", request)
    }

    /**
     * Submit a limit order
     */
    suspend fun limit(
        symbol: String,
        side: Side,
        size: BigDecimal,
        price: BigDecimal,
        reduceOnly: Boolean = false,
        postOnly: Boolean = false
    ): ApiResult<Order> {
        logger.info("Limit order: $side $size $symbol @ $price (reduceOnly=$reduceOnly, postOnly=$postOnly)")

        val request = mapOf(
            "symbol" to symbol,
            "side" to side.name,
            "type" to "LIMIT",
            "size" to size.toString(),
            "price" to price.toString(),
            "reduceOnly" to reduceOnly,
            "postOnly" to postOnly
        )

        return httpClient.post("/api/v1/hyperliquid/order", request)
    }

    /**
     * Cancel an order
     */
    suspend fun cancelOrder(orderId: String): ApiResult<Map<String, Any>> {
        logger.info("Cancel order: $orderId")
        return httpClient.post("/api/v1/hyperliquid/cancel/$orderId")
    }

    /**
     * Cancel all orders for a symbol
     */
    suspend fun cancelAll(symbol: String? = null): ApiResult<Map<String, Any>> {
        val path = if (symbol != null) {
            "/api/v1/hyperliquid/cancel-all?symbol=$symbol"
        } else {
            "/api/v1/hyperliquid/cancel-all"
        }
        logger.info("Cancel all orders" + (symbol?.let { " for $it" } ?: ""))
        return httpClient.post(path)
    }

    /**
     * Get current positions
     */
    suspend fun positions(): ApiResult<List<Position>> {
        return httpClient.get("/api/v1/hyperliquid/positions")
    }

    /**
     * Get open orders
     */
    suspend fun openOrders(symbol: String? = null): ApiResult<List<Order>> {
        val path = if (symbol != null) {
            "/api/v1/hyperliquid/orders?symbol=$symbol"
        } else {
            "/api/v1/hyperliquid/orders"
        }
        return httpClient.get(path)
    }

    /**
     * Get account balance
     */
    suspend fun balance(): ApiResult<Balance> {
        return httpClient.get("/api/v1/hyperliquid/balance")
    }

    /**
     * Close all positions
     */
    suspend fun closeAllPositions(): ApiResult<List<Order>> {
        logger.info("Closing all positions")
        return httpClient.post("/api/v1/hyperliquid/close-all")
    }

    /**
     * Close position for specific symbol
     */
    suspend fun closePosition(symbol: String): ApiResult<Order> {
        logger.info("Closing position: $symbol")
        val request = mapOf("symbol" to symbol)
        return httpClient.post("/api/v1/hyperliquid/close", request)
    }
}
