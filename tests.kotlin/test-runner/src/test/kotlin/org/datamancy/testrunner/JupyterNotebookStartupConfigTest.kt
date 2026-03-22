package org.datamancy.testrunner

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `research notebooks canonicalize hyperliquid history and consume funding when present`() {
        val text = startupConfigText()
        assertTrue(
            text.contains("\"exchange_aliases = ['hyperliquid', 'hyperliquid_mainnet'] if exchange == 'hyperliquid_mainnet' else [exchange]\\n\""),
            "research notebooks should stitch hyperliquid alias history together when using the canonical mainnet exchange"
        )
        assertTrue(
            text.contains("\"  WHERE exchange IN ({exchange_sql})\\n\""),
            "research notebooks should query canonical exchange aliases rather than a single exchange identifier"
        )
        assertTrue(
            text.contains("data_type = 'funding'"),
            "research notebooks should query persisted funding rows when the pipeline stores them"
        )
        assertTrue(
            text.contains("\"  COALESCE(f.funding_rate, 0.0) AS funding_rate\\n\""),
            "research notebooks should fall back to zero funding safely when recent history is sparse"
        )
        assertTrue(
            text.contains("\"df['carry_overlay'] = (-(df['funding_rate'].fillna(0.0) / 60.0)).clip(-0.0005, 0.0005)\\n\""),
            "execution-aware research notebooks should convert hourly funding into bounded per-minute carry"
        )
        assertTrue(
            text.contains("\"spread_bps = (forward['spread_pct'].clip(lower=0) * 100.0)\\n\""),
            "research notebooks should convert stored spread_pct values from percent units into basis points"
        )
        assertTrue(
            text.contains("\"spread_bps = df['spread_pct'].clip(lower=0) * 100.0\\n\""),
            "research notebooks should reuse percent-to-bps conversion consistently in execution realism calculations"
        )
        assertFalse(
            text.contains("\"spread_bps = (forward['spread_pct'].clip(lower=0) * 10000.0)\\n\""),
            "research notebooks should not overstate spreads by treating percent units as fractions"
        )
        assertTrue(
            text.contains("def migrate_seeded_research_notebooks():"),
            "startup-config should patch existing seeded research notebooks in persisted Jupyter volumes when alias wiring changes"
        )
        assertTrue(
            text.contains("\"00_profit_workflow_index.ipynb\": {") &&
                text.contains("\"06_profitability_and_risk_attribution.ipynb\": {"),
            "startup-config should carry targeted migrations for the seeded backtest and attribution notebooks"
        )
        val firstPythonTerminator = text.indexOf("\nPY\n")
        val migrationCall = text.indexOf("migrate_seeded_research_notebooks()")
        assertTrue(
            migrationCall in 0 until firstPythonTerminator,
            "startup-config should invoke notebook migrations before the first Python heredoc terminates"
        )
    }

    @Test
    fun `research notebook migration rewrites persisted legacy hyperliquid aliases`() {
        val tempHome = Files.createTempDirectory("jupyter-startup-config-test")
        val notebookDir = Files.createDirectories(tempHome.resolve("work/datamancy-notebooks"))
        val notebookPath = notebookDir.resolve("01_quant_backtest_from_market_data.ipynb")
        Files.writeString(
            notebookPath,
            """
            {
             "cells": [
              {
               "cell_type": "code",
               "execution_count": null,
               "metadata": {},
               "outputs": [],
               "source": [
                "import os\n",
                "import pandas as pd\n",
                "from sqlalchemy import create_engine, text\n"
               ]
              },
              {
               "cell_type": "code",
               "execution_count": null,
               "metadata": {},
               "outputs": [],
               "source": [
                "symbol = 'BTC'\n",
                "interval = 'candle_1m'\n",
                "lookback = '30 days'\n",
                "\n",
                "sql = text('''\n",
                "SELECT time, open, high, low, close, volume\n",
                "FROM market_data\n",
                "WHERE symbol = :symbol\n",
                "  AND exchange = 'hyperliquid'\n",
                "  AND data_type = :interval\n",
                "  AND time >= NOW() - CAST(:lookback AS interval)\n",
                "ORDER BY time ASC\n",
                "''')\n",
                "df = pd.read_sql(sql, engine, params={'symbol': symbol, 'interval': interval, 'lookback': lookback}, parse_dates=['time'])\n"
               ]
              }
             ],
             "metadata": {
              "kernelspec": {
               "display_name": "Python 3",
               "language": "python",
               "name": "python3"
              },
              "language_info": {
               "name": "python"
              }
             },
             "nbformat": 4,
             "nbformat_minor": 5
            }
            """.trimIndent() + "\n"
        )

        val script = firstPythonHeredoc(startupConfigText())
            .replace(
                """home_dir = Path("/home/jovyan")""",
                """home_dir = Path(r"${pythonPathLiteral(tempHome)}")"""
            )
            .replace(
                """notebook_dir = "/home/jovyan/work/datamancy-notebooks"""",
                """notebook_dir = r"${pythonPathLiteral(notebookDir)}""""
            )
        val scriptPath = tempHome.resolve("startup-config-first-heredoc.py")
        Files.writeString(scriptPath, script)

        val process = ProcessBuilder("python3", scriptPath.toString())
            .directory(repoRoot().toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "startup-config python heredoc should finish promptly")
        assertEquals(0, process.exitValue(), output)

        val migratedNotebook = Files.readString(notebookPath)
        assertTrue(
            migratedNotebook.contains("exchange_aliases = ['hyperliquid', 'hyperliquid_mainnet']"),
            "runtime migration should inject canonical hyperliquid alias stitching into persisted seeded notebooks"
        )
        assertTrue(
            migratedNotebook.contains("AND exchange IN ({exchange_sql})"),
            "runtime migration should replace legacy single-exchange filters with canonical alias queries"
        )
        assertFalse(
            migratedNotebook.contains("AND exchange = 'hyperliquid'"),
            "runtime migration should remove the legacy hyperliquid-only filter from persisted seeded notebooks"
        )
    }

    @Test
    fun `jupyter notebook dockerfile pins compatible ai packages and checks dependencies`() {
        val text = dockerfileText()
        assertTrue(
            text.contains("'jupyter-ai==2.31.7'"),
            "jupyter notebook image should pin Jupyter AI so notebook environments do not drift unexpectedly"
        )
        assertTrue(
            text.contains("'langchain-openai==0.3.35'"),
            "jupyter notebook image should pin a langchain-openai release compatible with Jupyter AI 2.x"
        )
        assertFalse(
            text.contains("'langchain>=0.3.0'"),
            "jupyter notebook image should not install an unconstrained langchain package that can override Jupyter AI's compatibility line"
        )
        assertFalse(
            text.contains("'langchain-community>=0.3.0'"),
            "jupyter notebook image should not install an unconstrained langchain-community package that can override Jupyter AI's dependency set"
        )
        assertFalse(
            text.contains("'langgraph>=0.2.0'"),
            "jupyter notebook image should not install an unconstrained langgraph package into the shared notebook environment"
        )
        assertTrue(
            text.contains("'packaging<26.0.0'"),
            "jupyter notebook image should pin packaging below 26 so langchain-core remains compatible after vector tooling installs"
        )
        assertTrue(
            text.contains("pip check"),
            "jupyter notebook image should run pip check so dependency conflicts fail during the image build"
        )
    }

    private fun startupConfigText(): String {
        val startupConfig = repoRoot().resolve("stack.containers/jupyter-notebook/startup-config.sh")
        return Files.readString(startupConfig)
    }

    private fun dockerfileText(): String {
        val dockerfile = repoRoot().resolve("stack.containers/jupyter-notebook/Dockerfile")
        return Files.readString(dockerfile)
    }

    private fun firstPythonHeredoc(text: String): String {
        val startMarker = "python3 <<'PY'\n"
        val startIndex = text.indexOf(startMarker)
        require(startIndex >= 0) { "Could not locate first Python heredoc start in startup-config.sh" }
        val bodyStart = startIndex + startMarker.length
        val endIndex = text.indexOf("\nPY\n", bodyStart)
        require(endIndex > bodyStart) { "Could not locate first Python heredoc end in startup-config.sh" }
        return text.substring(bodyStart, endIndex)
    }

    private fun pythonPathLiteral(path: Path): String =
        path.toAbsolutePath().toString().replace("\\", "\\\\")

    private fun repoRoot(): Path {
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
