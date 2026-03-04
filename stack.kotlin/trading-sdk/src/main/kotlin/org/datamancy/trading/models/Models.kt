package org.datamancy.trading.models

import java.math.BigDecimal
import java.time.Instant

enum class Side {
    BUY, SELL
}

enum class OrderType {
    MARKET, LIMIT
}

enum class OrderStatus {
    PENDING, FILLED, PARTIALLY_FILLED, CANCELLED, REJECTED
}

enum class Chain {
    BASE, ARBITRUM, OPTIMISM, ETHEREUM;

    val chainId: Long
        get() = when (this) {
            BASE -> 8453
            ARBITRUM -> 42161
            OPTIMISM -> 10
            ETHEREUM -> 1
        }
}

enum class Token {
    ETH, USDC, USDT;

    fun contractAddress(chain: Chain): String? = when (this) {
        ETH -> null // Native token
        USDC -> when (chain) {
            Chain.BASE -> "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913"
            Chain.ARBITRUM -> "0xaf88d065e77c8cC2239327C5EDb3A432268e5831"
            Chain.OPTIMISM -> "0x0b2C639c533813f4Aa9D7837CAf62653d097Ff85"
            Chain.ETHEREUM -> "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48"
        }
        USDT -> when (chain) {
            Chain.BASE -> "0xfde4C96c8593536E31F229EA8f37b2ADa2699bb2"
            Chain.ARBITRUM -> "0xFd086bC7CD5C481DCC9C85ebE478A1C0b69FCbb9"
            Chain.OPTIMISM -> "0x94b008aA00579c1307B0EF2c499aD98a8ce58e58"
            Chain.ETHEREUM -> "0xdAC17F958D2ee523a2206206994597C13D831ec7"
        }
    }
}

data class Order(
    val orderId: String,
    val symbol: String,
    val side: Side,
    val type: OrderType,
    val size: BigDecimal,
    val price: BigDecimal?,
    val status: OrderStatus,
    val filledSize: BigDecimal = BigDecimal.ZERO,
    val fillPrice: BigDecimal? = null,
    val timestamp: Instant = Instant.now()
)

data class Position(
    val symbol: String,
    val size: BigDecimal,
    val entryPrice: BigDecimal,
    val markPrice: BigDecimal,
    val leverage: BigDecimal,
    val liquidationPrice: BigDecimal? = null
) {
    val notionalValue: BigDecimal
        get() = size.abs() * markPrice

    val unrealizedPnl: BigDecimal
        get() = (markPrice - entryPrice) * size

    val pnlPercent: BigDecimal
        get() = if (entryPrice > BigDecimal.ZERO) {
            (unrealizedPnl / (entryPrice * size.abs())) * BigDecimal(100)
        } else {
            BigDecimal.ZERO
        }
}

data class Balance(
    val currency: String,
    val available: BigDecimal,
    val total: BigDecimal,
    val locked: BigDecimal = total - available
)

data class EvmTransfer(
    val txHash: String,
    val from: String,
    val to: String,
    val toUser: String?,
    val amount: BigDecimal,
    val token: Token,
    val chain: Chain,
    val status: TxStatus,
    val timestamp: Instant = Instant.now(),
    val confirmations: Int = 0,
    val gasUsed: BigDecimal? = null
)

enum class TxStatus {
    PENDING, SUBMITTED, CONFIRMED, FAILED, REPLACED
}

data class TxHistory(
    val id: String,
    val timestamp: Instant,
    val type: String, // "order", "transfer"
    val description: String,
    val status: String,
    val details: Map<String, Any>
)

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int? = null) : ApiResult<Nothing>()
}
