package org.datamancy.trading.models

data class ExchangeCapabilities(
    val paperOrder: Boolean,
    val liveOrder: Boolean,
    val nativeOrderAdapter: Boolean,
    val marketDataIngress: Boolean,
    val bestQuoteDefault: Boolean
)

enum class ExchangeImplementationStatus {
    INTEGRATED,
    PAPER_ONLY,
    PLACEHOLDER;

    companion object {
        fun fromApi(raw: String?): ExchangeImplementationStatus {
            return when (raw?.trim()?.lowercase()) {
                "integrated" -> INTEGRATED
                "paper_only" -> PAPER_ONLY
                else -> PLACEHOLDER
            }
        }
    }
}

data class ExchangeCatalogEntry(
    val exchange: ExchangeId,
    val implementationStatus: ExchangeImplementationStatus,
    val defaultExecutionMode: TradingMode,
    val supportedExecutionModes: List<TradingMode>,
    val capabilities: ExchangeCapabilities,
    val notes: String
)

data class ExchangeMarketDescriptor(
    val symbol: String,
    val attributes: Map<String, String> = emptyMap()
)

data class ExchangeMarketCatalog(
    val exchange: ExchangeId,
    val markets: List<ExchangeMarketDescriptor>
)
