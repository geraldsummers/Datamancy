package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class AlphaProofScriptTest {

    @Test
    fun `alpha proof script canonicalizes exchange history, consumes funding, and persists proof tables`() {
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
            text.contains("data_type = 'funding'"),
            "alpha proof should query persisted funding rows when they exist"
        )
        assertTrue(
            text.contains("COALESCE(f.funding_rate, 0.0) AS funding_rate"),
            "alpha proof should fall back to zero funding safely when funding history is sparse"
        )
        assertTrue(
            text.contains("df[\"carry_overlay\"] = (-(df[\"funding_rate\"].fillna(0.0) / 60.0)).clip(-0.0005, 0.0005)"),
            "alpha proof should translate hourly funding into bounded per-minute carry"
        )
        assertTrue(
            text.contains("spread_pct_to_bps"),
            "alpha proof should convert stored spread_pct values from percent units into basis points exactly once"
        )
        assertTrue(
            text.contains("orderbook_data bid/ask depth columns are stored in base-asset size, not quote notional"),
            "alpha proof should document that orderbook depth is stored in base units so impact models do not treat it as USD notional"
        )
        assertTrue(
            text.contains("return simulate_tail_short("),
            "alpha proof should expose the slower downside-tail strategy family for lower-latency-sensitive alpha discovery"
        )
        assertTrue(
            text.contains("def default_strategy_prefix(family: str) -> str:") &&
                text.contains("args.strategy_prefix = default_strategy_prefix(args.family)"),
            "alpha proof should derive the persisted strategy prefix from the selected family when the operator does not override it"
        )
        assertTrue(
            text.contains("--allow-strategy-name-mismatch") &&
                text.contains("def validate_strategy_name_family(") &&
                text.contains("refusing to persist mislabeled alpha proof rows"),
            "alpha proof should guard against persisting one strategy family under another family's name"
        )
        assertTrue(
            text.contains("--fixed-param-label"),
            "alpha proof should support fixed-parameter replay so the operator can verify a dominant walk-forward config without per-window reselection"
        )
        assertTrue(
            text.contains("def parse_strategy_label(label: str) -> StrategyParams:"),
            "alpha proof should parse persisted parameter labels back into executable strategy parameters"
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
        assertTrue(
            text.contains("def split_contiguous_segments("),
            "alpha proof should explicitly segment minute history around ingestion gaps instead of building walk-forward windows across outages"
        )
        assertTrue(
            text.contains("\"largest_gap_minutes\""),
            "alpha proof should surface contiguity diagnostics so Grafana and operators can see when history contains material gaps"
        )
        assertTrue(
            text.contains("trade_fill_mask = effective_turnover > 0"),
            "alpha proof should only average microstructure execution costs over actual trade rows"
        )
        assertTrue(
            text.contains("\"avg_total_cost_bps\": avg_total_cost_bps") &&
                text.contains("\"avg_fill_ratio\": avg_fill_ratio"),
            "alpha proof should not report synthetic average cost/fill metrics when the selected OOS slice produced no trades"
        )
        assertTrue(
            text.contains("avg_total_cost_bps = float(oos.loc[trade_fill_mask, \"total_cost_bps\"].mean()) if trade_rows > 0 else 0.0") &&
                text.contains("avg_fill_ratio = float(oos.loc[trade_fill_mask, \"fill_ratio\"].mean()) if trade_rows > 0 else 1.0"),
            "alpha proof should aggregate OOS cost and fill metrics over executed trades only"
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
