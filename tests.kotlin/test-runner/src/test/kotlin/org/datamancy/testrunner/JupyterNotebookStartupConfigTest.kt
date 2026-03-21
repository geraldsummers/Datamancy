package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class JupyterNotebookStartupConfigTest {

    @Test
    fun `cross venue notebook best quote forwards execution mode`() {
        val text = startupConfigText()
        val startMarker = "\"def best_quote(symbol, side='buy', exchanges=None, execution_mode='forward_paper'):\\n\""
        val endMarker = "\"def place_order(exchange, symbol, side='BUY', order_type='MARKET', size='0.01', price=None, execution_mode='forward_paper', reduce_only=False, post_only=False, urgency_class='normal', fee_tier=None, max_slippage_bps=None, cancel_after_ms=None):\\n\""

        val startIndex = text.indexOf(startMarker)
        val endIndex = text.indexOf(endMarker)
        require(startIndex >= 0 && endIndex > startIndex) {
            "Unable to locate cross-venue notebook best_quote block in startup-config.sh"
        }
        val bestQuoteBlock = text.substring(startIndex, endIndex)

        assertTrue(
            bestQuoteBlock.contains("\"    normalized_mode = (execution_mode or 'forward_paper').strip().lower()\\n\""),
            "best_quote helper should normalize execution_mode before choosing quotes"
        )
        assertTrue(
            bestQuoteBlock.contains("\"        'executionMode': normalized_mode\\n\""),
            "best_quote helper should forward executionMode to tx-gateway"
        )
    }

    @Test
    fun `cross venue notebook exposes explicit hyperliquid mainnet helper`() {
        val text = startupConfigText()
        assertTrue(
            text.contains("\"def place_hyperliquid_mainnet_order(symbol, side='BUY', order_type='MARKET', size='0.01', price=None, reduce_only=False, post_only=False, urgency_class='normal', max_slippage_bps=35.0, cancel_after_ms=None):\\n\""),
            "startup-config should seed an explicit Hyperliquid mainnet order helper for notebook parity"
        )
    }

    private fun startupConfigText(): String {
        val startupConfig = findRepoRoot().resolve("stack.containers/jupyter-notebook/startup-config.sh")
        return Files.readString(startupConfig)
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("stack.containers/jupyter-notebook/startup-config.sh"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
