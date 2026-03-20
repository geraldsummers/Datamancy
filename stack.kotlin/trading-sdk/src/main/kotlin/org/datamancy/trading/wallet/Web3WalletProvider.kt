package org.datamancy.trading.wallet

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Information about a connected Web3 wallet
 */
data class WalletInfo(
    val address: String,
    val chainId: Int,
    val provider: String, // "metamask", "brave", "walletconnect"
    val connected: Boolean
)

/**
 * Provides access to Web3 wallet connected via JupyterLab extension
 */
class Web3WalletProvider(
    private val jupyterBaseUrl: String = "http://localhost:8888",
    private val signPollIntervalMs: Long = 1_000L,
    private val signPollTimeoutMs: Long = 60_000L
) {
    private val logger = LoggerFactory.getLogger(Web3WalletProvider::class.java)
    private val gson = Gson()
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Check if a Web3 wallet is connected in JupyterLab
     */
    suspend fun isWalletConnected(): Boolean = withContext(Dispatchers.IO) {
        try {
            val walletInfo = getWalletInfo()
            walletInfo?.connected == true
        } catch (e: Exception) {
            logger.debug("Wallet not connected: ${e.message}")
            false
        }
    }

    /**
     * Get information about the connected wallet
     */
    suspend fun getWalletInfo(): WalletInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$jupyterBaseUrl/datamancy/web3-wallet/magic")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use null
                    val result = gson.fromJson(body, Map::class.java)
                    val wallet = result["wallet"] as? Map<*, *> ?: return@use null

                    val connected = wallet["connected"] as? Boolean ?: false
                    if (!connected) {
                        return@use WalletInfo(
                            address = "",
                            chainId = 0,
                            provider = wallet["provider"] as? String ?: "unknown",
                            connected = false
                        )
                    }

                    WalletInfo(
                        address = wallet["address"] as? String ?: return@use null,
                        chainId = parseChainId(wallet["chainId"]) ?: return@use null,
                        provider = wallet["provider"] as? String ?: "unknown",
                        connected = true
                    )
                } else {
                    null
                }
            }
        } catch (e: IOException) {
            logger.warn("Failed to get wallet info: ${e.message}")
            null
        }
    }

    /**
     * Request wallet to sign a transaction
     */
    suspend fun signTransaction(transaction: Map<String, Any>): String? = withContext(Dispatchers.IO) {
        try {
            val requestBody = gson.toJson(mapOf(
                "operation" to "sign_transaction",
                "transaction" to transaction
            )).toRequestBody(jsonMediaType)

            val request = Request.Builder()
                .url("$jupyterBaseUrl/datamancy/web3-wallet")
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@use null
                    val result = gson.fromJson(body, Map::class.java)

                    val txId = result["tx_id"] as? String ?: return@use null
                    logger.info("Transaction queued for signing: $txId")
                    pollSignedTransaction(txId)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to sign transaction: ${e.message}")
            null
        }
    }

    /**
     * Get the wallet's address (convenience method)
     */
    suspend fun getAddress(): String? {
        return getWalletInfo()?.address
    }

    /**
     * Get the current chain ID (convenience method)
     */
    suspend fun getChainId(): Int? {
        return getWalletInfo()?.chainId
    }

    private suspend fun pollSignedTransaction(txId: String): String? {
        val attempts = (signPollTimeoutMs / signPollIntervalMs).toInt().coerceAtLeast(1)
        repeat(attempts) {
            val status = fetchTransactionStatus(txId)
            when (status?.status?.lowercase()) {
                "signed" -> return status.signedTransaction
                "rejected", "failed" -> return null
            }
            delay(signPollIntervalMs)
        }
        logger.warn("Wallet signing timed out for txId={}", txId)
        return null
    }

    private fun fetchTransactionStatus(txId: String): WalletTxStatus? {
        val request = Request.Builder()
            .url("$jupyterBaseUrl/datamancy/web3-wallet/tx/$txId")
            .get()
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@use null
            }
            val body = response.body?.string() ?: return@use null
            val result = gson.fromJson(body, Map::class.java)
            val tx = result["tx"] as? Map<*, *> ?: return@use null
            WalletTxStatus(
                status = tx["status"] as? String ?: return@use null,
                signedTransaction = tx["signedTransaction"] as? String
            )
        }
    }

    private fun parseChainId(raw: Any?): Int? {
        return when (raw) {
            is Number -> raw.toInt()
            is String -> raw.trim().takeIf { it.isNotBlank() }?.let {
                if (it.startsWith("0x")) it.removePrefix("0x").toIntOrNull(16) else it.toIntOrNull()
            }
            else -> null
        }
    }

    private data class WalletTxStatus(
        val status: String,
        val signedTransaction: String?
    )
}
