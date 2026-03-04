package org.datamancy.trading.wallet

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
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
    private val jupyterBaseUrl: String = "http://localhost:8888"
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

                    WalletInfo(
                        address = wallet["address"] as? String ?: return@use null,
                        chainId = (wallet["chainId"] as? Double)?.toInt() ?: return@use null,
                        provider = wallet["provider"] as? String ?: "unknown",
                        connected = wallet["connected"] as? Boolean ?: false
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

                    // Transaction is queued for signing by frontend
                    val txId = result["tx_id"] as? String

                    // Poll for signed transaction
                    // In a real implementation, this would use WebSockets or polling
                    logger.info("Transaction queued for signing: $txId")

                    // TODO: Implement polling mechanism
                    null
                } else {
                    null
                }
            }
        } catch (e: IOException) {
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
}
