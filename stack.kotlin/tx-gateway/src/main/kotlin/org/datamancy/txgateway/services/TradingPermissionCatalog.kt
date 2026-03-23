package org.datamancy.txgateway.services

import kotlinx.serialization.Serializable

@Serializable
data class TradingAccountAudit(
    val username: String,
    val email: String,
    val evmAddress: String?,
    val groups: List<String>,
    val hasTradingProfile: Boolean,
    val hasTradingObjectClass: Boolean,
    val rawAllowedChains: List<String>,
    val allowedChains: List<String>,
    val rawAllowedExchanges: List<String>,
    val allowedExchanges: List<String>,
    val rawAllowedTradingModes: List<String>,
    val allowedTradingModes: List<String>,
    val maxTxPerHour: Int,
    val maxTxValueUSD: Int,
    val findings: List<String>
)

data class PermissionNormalization(
    val rawValues: List<String>,
    val normalized: List<String>,
    val unsupported: List<String>,
    val defaulted: Boolean,
    val duplicatesDropped: Boolean
)

object TradingPermissionCatalog {
    private val defaultChainFallback = listOf("base", "arbitrum", "optimism")
    val knownExchanges = listOf(
        "swyftx",
        "binance",
        "bybit",
        "coinbase",
        "dydx",
        "hyperliquid",
        "aster"
    )
    val supportedExchanges = knownExchanges
    val paperOrderExchanges = knownExchanges.toSet()
    val marketDataIngressExchanges = setOf("hyperliquid")
    val bestQuoteDefaultExchanges = marketDataIngressExchanges
    val nativeOrderExchanges = setOf("hyperliquid")
    val liveOrderExchanges = setOf("hyperliquid")
    val supportedTradingModes = listOf("backtest", "forward_paper", "testnet_live", "mainnet_live")
    val defaultAllowedChains = parseCsv(System.getenv("LDAP_DEFAULT_ALLOWED_CHAINS")).ifEmpty { defaultChainFallback }
    val defaultAllowedExchanges =
        parseCsv(System.getenv("LDAP_DEFAULT_ALLOWED_EXCHANGES")).filter { it in knownExchanges }.ifEmpty {
            knownExchanges
        }
    val defaultAllowedTradingModes =
        parseCsv(System.getenv("LDAP_DEFAULT_ALLOWED_TRADING_MODES")).filter { it in supportedTradingModes }.ifEmpty {
            listOf("backtest", "forward_paper", "testnet_live")
        }
    val mainnetReservedGroups = parseCsv(System.getenv("LDAP_MAINNET_ALLOWED_GROUPS")).toSet().ifEmpty { setOf("admins") }
    val tradingAdminGroups = parseCsv(System.getenv("LDAP_TRADING_ADMIN_GROUPS")).toSet().ifEmpty { setOf("admins") }

    fun implementationStatus(exchange: String): String {
        val normalized = normalizeValue(exchange)
        return when {
            normalized in nativeOrderExchanges && normalized in marketDataIngressExchanges -> "integrated"
            normalized in paperOrderExchanges -> "paper_only"
            else -> "placeholder"
        }
    }

    fun implementationNotes(exchange: String): String {
        return when (implementationStatus(exchange)) {
            "integrated" ->
                "Worker-backed execution and continuous market-data ingress are wired for this venue."
            "paper_only" ->
                "Venue id is reserved in the unified API, but native adapters are not wired; only gateway paper simulation is available."
            else ->
                "Venue id is reserved for future adapters and should not be treated as implemented."
        }
    }

    fun supportedExecutionModes(exchange: String): List<String> {
        return if (normalizeValue(exchange) == "hyperliquid") {
            listOf("forward_paper", "testnet_live", "mainnet_live")
        } else {
            listOf("forward_paper")
        }
    }

    fun defaultExecutionModeForExchange(exchange: String): String = "forward_paper"

    fun normalizeChains(rawValues: List<String>, defaultIfEmpty: Boolean): PermissionNormalization {
        return normalize(
            rawValues = rawValues,
            allowedValues = defaultAllowedChains.toSet(),
            defaultValues = if (defaultIfEmpty) defaultAllowedChains else emptyList()
        )
    }

    fun normalizeExchanges(rawValues: List<String>, defaultIfEmpty: Boolean): PermissionNormalization {
        return normalize(
            rawValues = rawValues,
            allowedValues = knownExchanges.toSet(),
            defaultValues = if (defaultIfEmpty) defaultAllowedExchanges else emptyList()
        )
    }

    fun normalizeTradingModes(rawValues: List<String>, defaultIfEmpty: Boolean): PermissionNormalization {
        return normalize(
            rawValues = rawValues,
            allowedValues = supportedTradingModes.toSet(),
            defaultValues = if (defaultIfEmpty) defaultAllowedTradingModes else emptyList()
        )
    }

    private fun normalize(
        rawValues: List<String>,
        allowedValues: Set<String>,
        defaultValues: List<String>
    ): PermissionNormalization {
        val cleaned = rawValues
            .map(::normalizeValue)
            .filter { it.isNotEmpty() }
        val duplicatesDropped = cleaned.size != cleaned.distinct().size
        val normalizedExplicit = cleaned.filter { it in allowedValues }.distinct()
        val unsupported = cleaned.filter { it !in allowedValues }.distinct()
        val defaulted = cleaned.isEmpty() && defaultValues.isNotEmpty()
        val normalized = if (defaulted) defaultValues else normalizedExplicit
        return PermissionNormalization(
            rawValues = rawValues,
            normalized = normalized,
            unsupported = unsupported,
            defaulted = defaulted,
            duplicatesDropped = duplicatesDropped
        )
    }

    private fun normalizeValue(raw: String): String = raw.trim().lowercase()

    private fun parseCsv(raw: String?): List<String> {
        return raw.orEmpty()
            .split(',', ';', '|', ' ')
            .map(::normalizeValue)
            .filter { it.isNotEmpty() }
            .distinct()
    }
}
