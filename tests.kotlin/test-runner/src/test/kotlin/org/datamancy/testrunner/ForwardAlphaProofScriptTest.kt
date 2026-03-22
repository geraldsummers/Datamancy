package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class ForwardAlphaProofScriptTest {

    @Test
    fun `forward alpha proof script reuses fixed backtest params and persists drift telemetry`() {
        val text = repoFileText("scripts/trading/forward_alpha_proof.py")

        assertTrue(
            text.contains("from alpha_proof import"),
            "forward alpha proof should reuse the core backtest proof functions instead of reimplementing a different strategy definition"
        )
        assertTrue(
            text.contains("--fixed-param-label"),
            "forward alpha proof should require a fixed strategy label so recent forward proof matches the backtest family exactly"
        )
        assertTrue(
            text.contains("--allow-strategy-name-mismatch") &&
                text.contains("validate_strategy_name_family("),
            "forward alpha proof should reject strategy names that mislabel the requested family unless the operator explicitly overrides the guard"
        )
        assertTrue(
            text.contains("strategy_latency_metrics"),
            "forward alpha proof should persist latency telemetry for Grafana execution panels"
        )
        assertTrue(
            text.contains("strategy_execution_costs"),
            "forward alpha proof should persist execution cost telemetry for Grafana drift and cost panels"
        )
        assertTrue(
            text.contains("strategy_live_backtest_drift"),
            "forward alpha proof should persist live-vs-backtest drift rows for Grafana drift panels"
        )
        assertTrue(
            text.contains("forward_pass"),
            "forward alpha proof should report an explicit pass/fail status for the recent forward slice"
        )
        assertTrue(
            text.contains("stale_recent_data"),
            "forward alpha proof should refuse to certify a stale recent slice after ingestion stalls"
        )
        assertTrue(
            text.contains("insufficient_contiguous_recent_data"),
            "forward alpha proof should refuse to bridge over missing bars when recent mainnet history is gappy"
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
