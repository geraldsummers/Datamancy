package org.datamancy.txgateway.models

import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class OrderRequest(
    val symbol: String,
    val side: String,
    val type: String,
    val size: String,
    val price: String? = null,
    val reduceOnly: Boolean = false,
    val postOnly: Boolean = false
)

@Serializable
data class TransferRequest(
    val toUser: String? = null,
    val toAddress: String? = null,
    val amount: String,
    val token: String,
    val chain: String
)

@Serializable
data class CancelTxRequest(
    val txHash: String,
    val chain: String
)

@Serializable
data class UserInfo(
    val username: String,
    val email: String,
    val groups: List<String>,
    val evmAddress: String?,
    val allowedChains: List<String>,
    val allowedExchanges: List<String>,
    val maxTxPerHour: Int,
    val maxTxValueUSD: Int
)
