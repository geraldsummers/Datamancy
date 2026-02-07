package org.datamancy.trading.client

import org.datamancy.trading.models.*
import org.slf4j.LoggerFactory
import java.math.BigDecimal

class EvmClient internal constructor(
    private val httpClient: TradingHttpClient
) {
    private val logger = LoggerFactory.getLogger(EvmClient::class.java)

    /**
     * Transfer tokens to another LDAP user
     */
    suspend fun transfer(
        toUser: String,
        amount: BigDecimal,
        token: Token,
        chain: Chain
    ): ApiResult<EvmTransfer> {
        logger.info("Transfer: $amount $token on $chain to user '$toUser'")

        val request = mapOf(
            "toUser" to toUser,
            "amount" to amount.toString(),
            "token" to token.name,
            "chain" to chain.name
        )

        return httpClient.post("/api/v1/evm/transfer", request)
    }

    /**
     * Transfer tokens to an explicit address
     */
    suspend fun transferToAddress(
        toAddress: String,
        amount: BigDecimal,
        token: Token,
        chain: Chain
    ): ApiResult<EvmTransfer> {
        logger.info("Transfer: $amount $token on $chain to address $toAddress")

        val request = mapOf(
            "toAddress" to toAddress,
            "amount" to amount.toString(),
            "token" to token.name,
            "chain" to chain.name
        )

        return httpClient.post("/api/v1/evm/transfer", request)
    }

    /**
     * Get EVM balance for a token
     */
    suspend fun balance(token: Token, chain: Chain): ApiResult<BigDecimal> {
        return httpClient.get("/api/v1/evm/balance?token=${token.name}&chain=${chain.name}")
    }

    /**
     * Get EVM address for an LDAP user
     */
    suspend fun addressBook(user: String): ApiResult<String> {
        return httpClient.get("/api/v1/evm/addressbook/$user")
    }

    /**
     * Get transaction status
     */
    suspend fun txStatus(txHash: String, chain: Chain): ApiResult<EvmTransfer> {
        return httpClient.get("/api/v1/evm/tx/$txHash?chain=${chain.name}")
    }

    /**
     * Get recent transfer history
     */
    suspend fun history(limit: Int = 100): ApiResult<List<EvmTransfer>> {
        return httpClient.get("/api/v1/evm/history?limit=$limit")
    }

    /**
     * Cancel a pending transaction (submits replacement with 0 ETH to self)
     */
    suspend fun cancelTx(txHash: String, chain: Chain): ApiResult<EvmTransfer> {
        logger.info("Cancelling tx: $txHash on $chain")
        val request = mapOf(
            "txHash" to txHash,
            "chain" to chain.name
        )
        return httpClient.post("/api/v1/evm/cancel", request)
    }
}
