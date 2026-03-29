package org.datamancy.trading.alpha

import org.datamancy.trading.models.ExchangeMarketDescriptor
import java.math.BigDecimal
import java.math.RoundingMode

internal data class ExchangeOrderSizingDecision(
    val size: BigDecimal,
    val notes: List<String>
)

internal object ExchangeOrderSizing {
    fun quantizeSize(
        symbol: String,
        requestedSize: BigDecimal,
        market: ExchangeMarketDescriptor?
    ): ExchangeOrderSizingDecision {
        if (market == null) {
            return ExchangeOrderSizingDecision(
                size = requestedSize,
                notes = listOf("No venue lot-size metadata found for $symbol; submitted unquantized size.")
            )
        }

        val sizeDecimals = market.attributes["szDecimals"]?.toIntOrNull()
            ?: return ExchangeOrderSizingDecision(
                size = requestedSize,
                notes = listOf("Venue metadata for $symbol has no szDecimals attribute; submitted unquantized size.")
            )
        require(sizeDecimals >= 0) { "Invalid szDecimals=$sizeDecimals for $symbol" }

        val quantized = requestedSize.setScale(sizeDecimals, RoundingMode.DOWN).stripTrailingZeros()
        require(quantized > BigDecimal.ZERO) {
            "Order size for $symbol rounds to zero at venue precision szDecimals=$sizeDecimals"
        }

        val note = if (quantized.compareTo(requestedSize) == 0) {
            "Applied venue size precision for $symbol with szDecimals=$sizeDecimals."
        } else {
            "Quantized $symbol size from ${requestedSize.stripTrailingZeros().toPlainString()} to ${quantized.toPlainString()} using szDecimals=$sizeDecimals."
        }
        return ExchangeOrderSizingDecision(size = quantized, notes = listOf(note))
    }
}
