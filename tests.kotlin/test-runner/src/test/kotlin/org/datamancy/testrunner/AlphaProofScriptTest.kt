package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class AlphaProofScriptTest {

    @Test
    fun `alpha proof script canonicalizes exchange history and persists proof tables`() {
        val text = repoFileText("scripts/trading/alpha_proof.py")

        assertTrue(
            text.contains("return [\"hyperliquid\", \"hyperliquid_mainnet\"]"),
            "alpha proof should stitch legacy and canonical Hyperliquid mainnet identifiers into one research history"
        )
        assertTrue(
            text.contains("data_type = 'candle_1m'"),
            "alpha proof should source minute candles for the walk-forward base frame"
        )
        assertTrue(
            text.contains("data_type = 'trade'"),
            "alpha proof should augment the minute frame with signed trade flow instead of relying on candles alone"
        )
        assertTrue(
            text.contains("0.0 AS funding_rate"),
            "alpha proof should degrade carry overlay safely until funding data is ingested"
        )
        assertTrue(
            text.contains("spread_pct_to_bps"),
            "alpha proof should convert stored spread_pct values from percent units into basis points exactly once"
        )
        assertTrue(
            text.contains("INSERT INTO strategy_backtest_runs"),
            "alpha proof should persist aggregate walk-forward evidence into strategy_backtest_runs"
        )
        assertTrue(
            text.contains("INSERT INTO strategy_walkforward_runs"),
            "alpha proof should persist per-window OOS evidence into strategy_walkforward_runs"
        )
        assertTrue(
            text.contains("INSERT INTO strategy_sensitivity_sweeps"),
            "alpha proof should persist stress scenarios into strategy_sensitivity_sweeps"
        )
    }

    private fun repoFileText(relativePath: String): String {
        val path = findRepoRoot().resolve(relativePath)
        return Files.readString(path)
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("scripts"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
