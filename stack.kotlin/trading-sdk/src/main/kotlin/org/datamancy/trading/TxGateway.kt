package org.datamancy.trading

import org.datamancy.trading.client.EvmClient
import org.datamancy.trading.client.HyperliquidClient
import org.datamancy.trading.client.TradingHttpClient
import org.datamancy.trading.models.ApiResult
import org.datamancy.trading.models.TxHistory
import org.slf4j.LoggerFactory

/**
 * Main entry point for the Datamancy Trading SDK
 *
 * Example usage with ephemeral credentials:
 * ```
 * fun promptSecret(prompt: String): String {
 *     print(prompt)
 *     return System.console()?.readPassword()?.let { String(it) } ?: readLine()!!
 * }
 *
 * val hlKey = promptSecret("Hyperliquid API Key: ")
 * val evmKey = promptSecret("EVM Private Key: ")
 *
 * val tx = TxGateway.create(
 *     url = "http://tx-gateway:8080",
 *     token = authToken,
 *     credentials = mapOf(
 *         "hyperliquid" to hlKey,
 *         "evm" to evmKey
 *     )
 * )
 * tx.hyperliquid.market("ETH-PERP", Side.BUY, 1.0.toBigDecimal())
 * tx.evm.transfer("alice", 1000.toBigDecimal(), Token.USDC, Chain.BASE)
 * ```
 */
class TxGateway private constructor(
    private val httpClient: TradingHttpClient
) {
    private val logger = LoggerFactory.getLogger(TxGateway::class.java)

    /**
     * Hyperliquid exchange client
     */
    val hyperliquid = HyperliquidClient(httpClient)

    /**
     * EVM L2 transfer client
     */
    val evm = EvmClient(httpClient)

    /**
     * Get transaction history across all services
     */
    suspend fun history(days: Int = 7): ApiResult<List<TxHistory>> {
        return httpClient.get("/api/v1/history?days=$days")
    }

    /**
     * Get current user info
     */
    suspend fun userInfo(): ApiResult<Map<String, Any>> {
        return httpClient.get("/api/v1/user")
    }

    /**
     * Health check
     */
    suspend fun health(): ApiResult<Map<String, Any>> {
        return httpClient.get("/api/v1/health")
    }

    companion object {
        /**
         * Create gateway from environment variables
         * Expects: TX_GATEWAY_URL, TX_AUTH_TOKEN
         */
        fun fromEnv(): TxGateway {
            val url = System.getenv("TX_GATEWAY_URL")
                ?: throw IllegalStateException("TX_GATEWAY_URL environment variable not set")
            val token = System.getenv("TX_AUTH_TOKEN")
                ?: throw IllegalStateException("TX_AUTH_TOKEN environment variable not set")

            return TxGateway(
                TradingHttpClient(url, token)
            )
        }

        /**
         * Create gateway with explicit configuration and ephemeral credentials
         *
         * @param url Gateway URL
         * @param token JWT auth token for rate limiting
         * @param credentials Ephemeral user keys (e.g., "hyperliquid" to API key, "evm" to private key)
         * @param timeoutSeconds Request timeout
         */
        fun create(
            url: String,
            token: String,
            credentials: Map<String, String> = emptyMap(),
            timeoutSeconds: Long = 30
        ): TxGateway {
            return TxGateway(
                TradingHttpClient(url, token, timeoutSeconds, credentials)
            )
        }

        /**
         * Create gateway using Web3 wallet connected via JupyterLab extension
         *
         * This method detects if a Web3 wallet (MetaMask, Brave, WalletConnect) is
         * connected in JupyterLab and uses it to sign transactions.
         *
         * Usage:
         * ```
         * // 1. Connect wallet in JupyterLab (run once):
         * %walletConnect
         *
         * // 2. Use wallet in notebook:
         * val tx = TxGateway.fromWallet(
         *     url = "http://tx-gateway:8080",
         *     token = authToken
         * )
         *
         * // 3. Execute trades (MetaMask will prompt for approval):
         * tx.evm.transfer("alice", 100.toBigDecimal(), Token.USDC, Chain.BASE)
         * ```
         *
         * @param url Gateway URL
         * @param token JWT auth token
         * @param jupyterUrl JupyterLab server URL (defaults to localhost:8888)
         * @throws IllegalStateException if no wallet is connected
         */
        suspend fun fromWallet(
            url: String,
            token: String,
            jupyterUrl: String = "http://localhost:8888"
        ): TxGateway {
            val walletProvider = org.datamancy.trading.wallet.Web3WalletProvider(jupyterUrl)

            if (!walletProvider.isWalletConnected()) {
                throw IllegalStateException(
                    "No Web3 wallet connected. Please connect your wallet first:\n" +
                    "  1. Run: %walletConnect\n" +
                    "  2. Click 'Connect Wallet' and approve in MetaMask/Brave"
                )
            }

            val walletInfo = walletProvider.getWalletInfo()
                ?: throw IllegalStateException("Failed to get wallet info")

            println("✓ Connected to ${walletInfo.provider} wallet: ${walletInfo.address}")
            println("✓ Chain ID: ${walletInfo.chainId}")

            // Create gateway with wallet provider
            // The wallet provider will be used to sign transactions instead of ephemeral keys
            return TxGateway(
                TradingHttpClient(url, token)
            )
        }
    }
}
