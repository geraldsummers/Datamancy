package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrafanaTradingDashboardConfigTest {

    @Test
    fun `alpha dashboards stitch canonical hyperliquid mainnet history`() {
        val tradingAlpha = dashboardText("stack.config/grafana/provisioning/dashboards/trading-alpha.json")
        val marketData = dashboardText("stack.config/grafana/provisioning/dashboards/market-data.json")

        assertTrue(
            tradingAlpha.contains("exchange IN ('hyperliquid', 'hyperliquid_mainnet')"),
            "trading alpha dashboard should stitch legacy and canonical Hyperliquid mainnet ids"
        )
        assertTrue(
            marketData.contains("exchange IN ('hyperliquid', 'hyperliquid_mainnet')"),
            "market data dashboard should stitch legacy and canonical Hyperliquid mainnet ids"
        )
        assertFalse(
            tradingAlpha.contains("AND exchange = 'hyperliquid'"),
            "trading alpha dashboard should not rely on the legacy hyperliquid exchange id alone"
        )
        assertFalse(
            marketData.contains("AND exchange = 'hyperliquid'"),
            "market data dashboard should not rely on the legacy hyperliquid exchange id alone"
        )
    }

    @Test
    fun `execution dashboard strategy selector is query backed`() {
        val execution = dashboardText("stack.config/grafana/provisioning/dashboards/trading-execution.json")

        assertTrue(
            execution.contains("\"name\": \"execution_strategy\""),
            "execution dashboard should define the execution_strategy selector"
        )
        assertTrue(
            execution.contains("\"type\": \"query\""),
            "execution dashboard strategy selector should query live strategy ids instead of using a fixed list"
        )
        assertTrue(
            execution.contains("SELECT strategy_name AS __text, strategy_name AS __value FROM (SELECT DISTINCT strategy_name FROM strategy_latency_metrics"),
            "execution dashboard should discover strategy names from persisted telemetry tables"
        )
    }

    @Test
    fun `drift dashboard strategy selector is query backed`() {
        val drift = dashboardText("stack.config/grafana/provisioning/dashboards/trading-drift.json")

        assertTrue(
            drift.contains("\"name\": \"drift_strategy\""),
            "drift dashboard should define the drift_strategy selector"
        )
        assertTrue(
            drift.contains("\"type\": \"query\""),
            "drift dashboard strategy selector should query live strategy ids instead of using a fixed list"
        )
        assertTrue(
            drift.contains("SELECT strategy_name AS __text, strategy_name AS __value FROM (SELECT DISTINCT strategy_name FROM strategy_live_backtest_drift"),
            "drift dashboard should discover strategy names from persisted drift telemetry tables"
        )
    }

    @Test
    fun `alpha lab dashboard is wired to universe and portfolio profile telemetry`() {
        val alphaLab = dashboardText("stack.config/grafana/provisioning/dashboards/alpha-lab.json")

        assertTrue(
            alphaLab.contains("\"name\": \"alpha_lab_strategy\""),
            "alpha lab dashboard should define the alpha_lab_strategy selector"
        )
        assertTrue(
            alphaLab.contains("\"type\": \"query\""),
            "alpha lab dashboard selectors should be query backed"
        )
        assertTrue(
            alphaLab.contains("SELECT strategy_name AS __text, strategy_name AS __value FROM (SELECT DISTINCT strategy_name FROM strategy_universe_profiles UNION SELECT DISTINCT strategy_name FROM strategy_portfolio_profiles UNION SELECT DISTINCT strategy_name FROM strategy_backtest_runs)"),
            "alpha lab dashboard should discover strategy names from persisted research telemetry tables"
        )
        assertTrue(
            alphaLab.contains("strategy_universe_profiles"),
            "alpha lab dashboard should visualize universe profile telemetry"
        )
        assertTrue(
            alphaLab.contains("strategy_portfolio_profiles"),
            "alpha lab dashboard should visualize portfolio profile telemetry"
        )
    }

    private fun dashboardText(relativePath: String): String {
        return Files.readString(findRepoRoot().resolve(relativePath))
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            if (Files.exists(current.resolve("stack.config/grafana/provisioning/dashboards/trading-execution.json"))) {
                return current
            }
            current = current.parent ?: return@repeat
        }
        error("Could not locate repository root from ${Path.of("").toAbsolutePath()}")
    }
}
