#!/bin/bash

python3 <<'PY'
import json
import os
from pathlib import Path

home_dir = Path("/home/jovyan")
jupyter_config_dir = home_dir / ".jupyter"
jupyter_config_dir.mkdir(parents=True, exist_ok=True)

litellm_api_key = os.getenv("LITELLM_API_KEY") or os.getenv("LITELLM_MASTER_KEY") or "unused"

(jupyter_config_dir / "jupyter_jupyter_ai_config.json").write_text(
    json.dumps(
        {
            "AiExtension": {
                "model_parameters": {
                    "openai-chat:qwen2.5-0.5b": {
                        "api_base": "http://litellm:4000/v1",
                        "api_key": litellm_api_key,
                    }
                }
            }
        },
        indent=2,
    )
    + "\n",
    encoding="utf-8",
)

env_values = {
    "OPENAI_API_BASE": "http://litellm:4000/v1",
    "OPENAI_API_KEY": litellm_api_key,
    "VLLM_API_BASE": "http://vllm:8000/v1",
    "VLLM_API_KEY": "unused",
    "DEFAULT_LLM_MODEL": "qwen2.5-0.5b",
    "DEFAULT_EMBEDDING_MODEL": "embed-small",
    "LANGCHAIN_TRACING_V2": "false",
    "POSTGRES_HOST": os.getenv("POSTGRES_HOST", "postgres"),
    "POSTGRES_PORT": os.getenv("POSTGRES_PORT", "5432"),
    "POSTGRES_DB": os.getenv("POSTGRES_DB", "datamancy"),
    "POSTGRES_USER": os.getenv("POSTGRES_USER", "pipeline_user"),
    "POSTGRES_PASSWORD": os.getenv("POSTGRES_PASSWORD", ""),
}
(home_dir / ".env").write_text(
    "".join(f"{key}={value}\n" for key, value in env_values.items()),
    encoding="utf-8",
)

notebook_dir = "/home/jovyan/work/datamancy-notebooks"
os.makedirs(notebook_dir, exist_ok=True)

def markdown_cell(text: str):
    return {"cell_type": "markdown", "metadata": {}, "source": text.splitlines(keepends=True)}

def code_cell(text: str):
    return {"cell_type": "code", "execution_count": None, "metadata": {}, "outputs": [], "source": text.splitlines(keepends=True)}

def write_notebook(name: str, cells):
    path = os.path.join(notebook_dir, name)
    if os.path.exists(path):
        return
    notebook = {
        "cells": cells,
        "metadata": {
            "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
            "language_info": {"name": "python"}
        },
        "nbformat": 4,
        "nbformat_minor": 5
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(notebook, f, indent=1)

def write_kotlin_notebook(name: str, cells):
    path = os.path.join(notebook_dir, name)
    if os.path.exists(path):
        return
    notebook = {
        "cells": cells,
        "metadata": {
            "kernelspec": {"display_name": "Kotlin", "language": "kotlin", "name": "kotlin"},
            "language_info": {
                "name": "kotlin",
                "file_extension": ".kt",
                "mimetype": "text/x-kotlin"
            }
        },
        "nbformat": 4,
        "nbformat_minor": 5
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(notebook, f, indent=1)

RESEARCH_ALIAS_PREFIX = (
    "exchange = os.getenv('DATAMANCY_RESEARCH_EXCHANGE', 'hyperliquid_mainnet').strip() or 'hyperliquid_mainnet'\n"
    "exchange_aliases = ['hyperliquid', 'hyperliquid_mainnet'] if exchange == 'hyperliquid_mainnet' else [exchange]\n"
    "exchange_sql = ', '.join([\"'\" + alias.replace(\"'\", \"''\") + \"'\" for alias in exchange_aliases])\n"
    "\n"
)

NOTEBOOK_ALIAS_MIGRATIONS = {
    "00_profit_workflow_index.ipynb": {
        "prefix_anchor": "setup_sql = text('''\n",
        "replace": [
            ("setup_sql = text('''\n", "setup_sql = text(f'''\n"),
            ("  WHERE exchange = 'hyperliquid'\n", "  WHERE exchange IN ({exchange_sql})\n"),
            (
                "6. Run `08_empirical_intraday_strategy_research.ipynb` for execution-aware intraday research.\n"
                "7. Run `16_strict_alpha_backtest_proof.ipynb` to persist the canonical walk-forward alpha proof.\n"
                "8. Run `17_strict_forward_alpha_proof.ipynb` to forward-paper the exact fixed strategy definition.\n"
                "9. Run `09_cross_venue_paper_execution_playbook.ipynb` to execute safe paper orders across venues.\n"
                "10. Use `15_forward_test_mainnet_data.ipynb` only for exploratory cost tinkering, not proof promotion.\n"
                "11. Push only top-ranked symbols into live execution workflows.\n",
                "6. Run `08_empirical_intraday_strategy_research.ipynb` for execution-aware intraday research.\n"
                "7. Run `18_cross_sectional_beta_trend_reversion_kotlin.ipynb` to estimate BTC/ETH beta and rank residual trend/reversion candidates.\n"
                "8. Run `16_strict_alpha_backtest_proof.ipynb` to persist the canonical walk-forward alpha proof.\n"
                "9. Run `17_strict_forward_alpha_proof.ipynb` to forward-paper the exact fixed strategy definition.\n"
                "10. Run `09_cross_venue_paper_execution_playbook.ipynb` to execute safe paper orders across venues.\n"
                "11. Use `15_forward_test_mainnet_data.ipynb` only for exploratory cost tinkering, not proof promotion.\n"
                "12. Push only top-ranked symbols into live execution workflows.\n",
            ),
        ],
    },
    "01_quant_backtest_from_market_data.ipynb": {
        "prefix_anchor": "symbol = 'BTC'\n",
        "replace": [
            ("sql = text('''\n", "sql = text(f'''\n"),
            ("  AND exchange = 'hyperliquid'\n", "  AND exchange IN ({exchange_sql})\n"),
        ],
    },
    "02_rss_sentiment_to_market_signals.ipynb": {
        "prefix_anchor": "plot_df = pd.read_sql(text('''\n",
        "replace": [
            ("plot_df = pd.read_sql(text('''\n", "plot_df = pd.read_sql(text(f'''\n"),
            (
                "  WHERE symbol='BTC' AND exchange='hyperliquid' AND data_type='candle_1m'\n",
                "  WHERE symbol='BTC' AND exchange IN ({exchange_sql}) AND data_type='candle_1m'\n",
            ),
        ],
    },
    "03_strategy_parameter_sweep_and_robustness.ipynb": {
        "prefix_anchor": "symbol = 'BTC'\n",
        "replace": [
            ("prices = pd.read_sql(text('''\n", "prices = pd.read_sql(text(f'''\n"),
            ("  AND exchange='hyperliquid'\n", "  AND exchange IN ({exchange_sql})\n"),
        ],
    },
    "04_alpha_signal_ranking.ipynb": {
        "prefix_anchor": "symbols = ['BTC', 'ETH', 'SOL', 'AVAX', 'LINK']\n",
        "replace": [
            ("px = pd.read_sql(text('''\n", "px = pd.read_sql(text(f'''\n"),
            ("WHERE exchange = 'hyperliquid'\n", "WHERE exchange IN ({exchange_sql})\n"),
        ],
    },
    "06_profitability_and_risk_attribution.ipynb": {
        "prefix_anchor": "market = pd.read_sql(text('''\n",
        "replace": [
            ("market = pd.read_sql(text('''\n", "market = pd.read_sql(text(f'''\n"),
            ("  WHERE exchange = 'hyperliquid'\n", "  WHERE exchange IN ({exchange_sql})\n"),
        ],
    },
}

def migrate_notebook_aliases(name: str) -> bool:
    migration = NOTEBOOK_ALIAS_MIGRATIONS.get(name)
    if migration is None:
        return False

    path = Path(notebook_dir) / name
    if not path.exists():
        return False

    try:
        notebook = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return False

    changed = False
    prefix_anchor = migration["prefix_anchor"]
    replacements = migration["replace"]

    for cell in notebook.get("cells", []):
        if cell.get("cell_type") != "code":
            continue

        source = "".join(cell.get("source") or [])
        updated = source

        if prefix_anchor in updated and RESEARCH_ALIAS_PREFIX not in updated:
            updated = updated.replace(prefix_anchor, RESEARCH_ALIAS_PREFIX + prefix_anchor, 1)

        for old, new in replacements:
            if old in updated and new not in updated:
                updated = updated.replace(old, new, 1)

        if updated != source:
            cell["source"] = updated.splitlines(keepends=True)
            changed = True

    if changed:
        path.write_text(json.dumps(notebook, indent=1) + "\\n", encoding="utf-8")

    return changed

def migrate_seeded_research_notebooks():
    migrated = [
        name
        for name in NOTEBOOK_ALIAS_MIGRATIONS
        if migrate_notebook_aliases(name)
    ]
    if migrated:
        print("Migrated seeded research notebooks:", ", ".join(sorted(migrated)))

write_notebook(
    "00_profit_workflow_index.ipynb",
    [
        markdown_cell(
            "# Datamancy Profit Workflow Index\n\n"
            "Fast path to turn live stack data into decision-ready trade setups.\n\n"
            "Recommended daily loop:\n"
            "1. Run `01_quant_backtest_from_market_data.ipynb` to validate baseline edge.\n"
            "2. Run `03_strategy_parameter_sweep_and_robustness.ipynb` to avoid overfit params.\n"
            "3. Run `04_alpha_signal_ranking.ipynb` to build long/short watchlists.\n"
            "4. Run `05_llm_rss_sentiment_backfill.ipynb` to refresh sentiment features.\n"
            "5. Run `06_profitability_and_risk_attribution.ipynb` to quantify net profitability drivers.\n"
            "6. Run `08_empirical_intraday_strategy_research.ipynb` for execution-aware intraday research.\n"
            "7. Run `18_cross_sectional_beta_trend_reversion_kotlin.ipynb` to estimate BTC/ETH beta and rank residual trend/reversion candidates.\n"
            "8. Run `16_strict_alpha_backtest_proof.ipynb` to persist the canonical walk-forward alpha proof.\n"
            "9. Run `17_strict_forward_alpha_proof.ipynb` to forward-paper the exact fixed strategy definition.\n"
            "10. Run `09_cross_venue_paper_execution_playbook.ipynb` to execute safe paper orders across venues.\n"
            "11. Use `15_forward_test_mainnet_data.ipynb` only for exploratory cost tinkering, not proof promotion.\n"
            "12. Push only top-ranked symbols into live execution workflows.\n"
        ),
        code_cell(
            "import os\n"
            "import pandas as pd\n"
            "from sqlalchemy import create_engine, text\n"
            "\n"
            "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
            "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
            "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
            "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
            "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
            "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
        ),
        code_cell(
            "exchange = os.getenv('DATAMANCY_RESEARCH_EXCHANGE', 'hyperliquid_mainnet').strip() or 'hyperliquid_mainnet'\n"
            "exchange_aliases = ['hyperliquid', 'hyperliquid_mainnet'] if exchange == 'hyperliquid_mainnet' else [exchange]\n"
            "exchange_sql = ', '.join([\"'\" + alias.replace(\"'\", \"''\") + \"'\" for alias in exchange_aliases])\n"
            "\n"
            "setup_sql = text(f'''\n"
            "WITH px AS (\n"
            "  SELECT\n"
            "    symbol,\n"
            "    MAX(close) FILTER (WHERE time >= NOW() - INTERVAL '15 minutes') AS close_now,\n"
            "    MIN(close) FILTER (WHERE time >= NOW() - INTERVAL '15 minutes') AS close_15m_ago,\n"
            "    MAX(close) FILTER (WHERE time >= NOW() - INTERVAL '4 hours') AS close_4h_now,\n"
            "    MIN(close) FILTER (WHERE time >= NOW() - INTERVAL '4 hours') AS close_4h_ago\n"
            "  FROM market_data\n"
            "  WHERE exchange IN ({exchange_sql})\n"
            "    AND data_type = 'candle_1m'\n"
            "    AND time >= NOW() - INTERVAL '4 hours'\n"
            "  GROUP BY symbol\n"
            "), ss AS (\n"
            "  SELECT symbol, AVG(sentiment_score) AS sentiment\n"
            "  FROM rss_sentiment_signals\n"
            "  WHERE observed_at >= NOW() - INTERVAL '24 hours'\n"
            "  GROUP BY symbol\n"
            ")\n"
            "SELECT\n"
            "  px.symbol,\n"
            "  COALESCE((px.close_now - px.close_15m_ago) / NULLIF(px.close_15m_ago, 0), 0) AS ret_15m,\n"
            "  COALESCE((px.close_4h_now - px.close_4h_ago) / NULLIF(px.close_4h_ago, 0), 0) AS ret_4h,\n"
            "  COALESCE(ss.sentiment, 0) AS sentiment,\n"
            "  (\n"
            "    COALESCE((px.close_now - px.close_15m_ago) / NULLIF(px.close_15m_ago, 0), 0) * 30.0\n"
            "    + COALESCE((px.close_4h_now - px.close_4h_ago) / NULLIF(px.close_4h_ago, 0), 0) * 70.0\n"
            "    + COALESCE(ss.sentiment, 0) * 15.0\n"
            "  ) AS setup_score\n"
            "FROM px\n"
            "LEFT JOIN ss ON ss.symbol = split_part(px.symbol, '-', 1)\n"
            "ORDER BY setup_score DESC\n"
            "LIMIT 20\n"
            "''')\n"
            "\n"
            "setups = pd.read_sql(setup_sql, engine)\n"
            "setups\n"
        ),
        code_cell(
            "longs = setups.head(5).copy()\n"
            "shorts = setups.tail(5).sort_values('setup_score').copy()\n"
            "print('Top long candidates:')\n"
            "display(longs[['symbol', 'ret_15m', 'ret_4h', 'sentiment', 'setup_score']])\n"
            "print('Top short candidates:')\n"
            "display(shorts[['symbol', 'ret_15m', 'ret_4h', 'sentiment', 'setup_score']])\n"
        ),
    ]
)

write_notebook(
    "01_quant_backtest_from_market_data.ipynb",
    [
        markdown_cell(
            "# Quant Backtest From Datamancy Market Data\n\n"
            "This notebook runs an EMA crossover + volatility filter backtest directly from the `market_data` table.\n\n"
            "Outputs:\n"
            "- equity curve\n"
            "- trade log\n"
            "- metrics: win rate, return, max drawdown, sharpe\n"
            "- persisted run in `strategy_backtest_runs` for Grafana\n"
        ),
        code_cell(
            "import os\n"
            "import numpy as np\n"
            "import pandas as pd\n"
            "from sqlalchemy import create_engine, text\n"
            "import matplotlib.pyplot as plt\n"
            "\n"
            "pd.set_option('display.max_columns', 120)\n"
            "\n"
            "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
            "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
            "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
            "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
            "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
            "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
        ),
        code_cell(
            "exchange = os.getenv('DATAMANCY_RESEARCH_EXCHANGE', 'hyperliquid_mainnet').strip() or 'hyperliquid_mainnet'\n"
            "exchange_aliases = ['hyperliquid', 'hyperliquid_mainnet'] if exchange == 'hyperliquid_mainnet' else [exchange]\n"
            "exchange_sql = ', '.join([\"'\" + alias.replace(\"'\", \"''\") + \"'\" for alias in exchange_aliases])\n"
            "\n"
            "symbol = 'BTC'\n"
            "interval = 'candle_1m'\n"
            "lookback = '30 days'\n"
            "\n"
            "sql = text(f'''\n"
            "SELECT time, open, high, low, close, volume\n"
            "FROM market_data\n"
            "WHERE symbol = :symbol\n"
            "  AND exchange IN ({exchange_sql})\n"
            "  AND data_type = :interval\n"
            "  AND time >= NOW() - CAST(:lookback AS interval)\n"
            "ORDER BY time ASC\n"
            "''')\n"
            "df = pd.read_sql(sql, engine, params={'symbol': symbol, 'interval': interval, 'lookback': lookback}, parse_dates=['time'])\n"
            "df.head(), len(df)\n"
        ),
        code_cell(
            "df['ret'] = df['close'].pct_change().fillna(0)\n"
            "df['ema_fast'] = df['close'].ewm(span=21, adjust=False).mean()\n"
            "df['ema_slow'] = df['close'].ewm(span=55, adjust=False).mean()\n"
            "df['vol'] = df['ret'].rolling(60).std().fillna(0)\n"
            "df['signal'] = np.where((df['ema_fast'] > df['ema_slow']) & (df['vol'] < df['vol'].rolling(500).median().fillna(df['vol'].median())), 1, 0)\n"
            "df['position'] = df['signal'].shift(1).fillna(0)\n"
            "fee_bps = 4\n"
            "slippage_bps = 2\n"
            "turnover = (df['position'].diff().abs().fillna(0))\n"
            "cost = turnover * (fee_bps + slippage_bps) / 10000.0\n"
            "df['strategy_ret'] = df['position'] * df['ret'] - cost\n"
            "df['equity'] = (1 + df['strategy_ret']).cumprod()\n"
            "df[['time','close','ema_fast','ema_slow','position','equity']].tail()\n"
        ),
        code_cell(
            "def max_drawdown(series):\n"
            "    peak = series.cummax()\n"
            "    dd = series / peak - 1\n"
            "    return dd.min()\n"
            "\n"
            "net_return = float(df['equity'].iloc[-1] - 1)\n"
            "trades = int((df['position'].diff().abs() > 0).sum())\n"
            "wins = int((df['strategy_ret'] > 0).sum())\n"
            "win_rate = wins / max(1, int((df['strategy_ret'] != 0).sum()))\n"
            "mdd = float(max_drawdown(df['equity']))\n"
            "sharpe = float((df['strategy_ret'].mean() / (df['strategy_ret'].std() + 1e-12)) * np.sqrt(60 * 24 * 365))\n"
            "print({'net_return_pct': round(net_return*100, 2), 'trades': trades, 'win_rate': round(win_rate*100,2), 'max_drawdown_pct': round(mdd*100,2), 'sharpe': round(sharpe,3)})\n"
        ),
        code_cell(
            "fig, ax = plt.subplots(2, 1, figsize=(14, 8), sharex=True)\n"
            "ax[0].plot(df['time'], df['close'], label='Close')\n"
            "ax[0].plot(df['time'], df['ema_fast'], label='EMA 21')\n"
            "ax[0].plot(df['time'], df['ema_slow'], label='EMA 55')\n"
            "ax[0].legend()\n"
            "ax[0].set_title(f'{symbol} Price + EMAs')\n"
            "ax[1].plot(df['time'], df['equity'], label='Strategy Equity', color='green')\n"
            "ax[1].legend()\n"
            "ax[1].set_title('Equity Curve')\n"
            "plt.tight_layout()\n"
            "plt.show()\n"
        ),
        code_cell(
            "with engine.begin() as conn:\n"
            "    conn.execute(text('''\n"
            "        CREATE TABLE IF NOT EXISTS strategy_backtest_runs (\n"
            "            id BIGSERIAL PRIMARY KEY,\n"
            "            run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),\n"
            "            strategy_name TEXT NOT NULL,\n"
            "            symbol TEXT NOT NULL,\n"
            "            timeframe TEXT NOT NULL,\n"
            "            start_time TIMESTAMPTZ NOT NULL,\n"
            "            end_time TIMESTAMPTZ NOT NULL,\n"
            "            trades INTEGER NOT NULL DEFAULT 0,\n"
            "            win_rate DOUBLE PRECISION NOT NULL DEFAULT 0.0,\n"
            "            net_return_pct DOUBLE PRECISION NOT NULL DEFAULT 0.0,\n"
            "            max_drawdown_pct DOUBLE PRECISION NOT NULL DEFAULT 0.0,\n"
            "            sharpe DOUBLE PRECISION NOT NULL DEFAULT 0.0,\n"
            "            notes TEXT,\n"
            "            metrics JSONB NOT NULL DEFAULT '{}'::jsonb\n"
            "        )\n"
            "    '''))\n"
            "    conn.execute(text('''\n"
            "        INSERT INTO strategy_backtest_runs (\n"
            "            strategy_name, symbol, timeframe, start_time, end_time,\n"
            "            trades, win_rate, net_return_pct, max_drawdown_pct, sharpe, notes, metrics\n"
            "        ) VALUES (\n"
            "            :strategy_name, :symbol, :timeframe, :start_time, :end_time,\n"
            "            :trades, :win_rate, :net_return_pct, :max_drawdown_pct, :sharpe, :notes, CAST(:metrics AS jsonb)\n"
            "        )\n"
            "    '''), {\n"
            "        'strategy_name': 'ema21_55_vol_filter',\n"
            "        'symbol': symbol,\n"
            "        'timeframe': interval,\n"
            "        'start_time': df['time'].min().to_pydatetime(),\n"
            "        'end_time': df['time'].max().to_pydatetime(),\n"
            "        'trades': trades,\n"
            "        'win_rate': float(win_rate),\n"
            "        'net_return_pct': float(net_return * 100.0),\n"
            "        'max_drawdown_pct': float(mdd * 100.0),\n"
            "        'sharpe': float(sharpe),\n"
            "        'notes': 'Generated from datamancy-notebook preset',\n"
            "        'metrics': '{\"fee_bps\": 4, \"slippage_bps\": 2, \"fast\": 21, \"slow\": 55}'\n"
            "    })\n"
            "print('Saved backtest run to strategy_backtest_runs')\n"
        )
    ]
)

write_notebook(
    "02_rss_sentiment_to_market_signals.ipynb",
    [
        markdown_cell(
            "# RSS Sentiment To Market Signals\n\n"
            "This notebook extracts RSS documents from `document_staging`, scores sentiment with a lightweight\n"
            "finance-oriented keyword model, writes rows into `rss_sentiment_signals`, then visualizes rolling\n"
            "sentiment against BTC price.\n"
        ),
        code_cell(
            "import os\n"
            "import re\n"
            "import numpy as np\n"
            "import pandas as pd\n"
            "from sqlalchemy import create_engine, text\n"
            "import matplotlib.pyplot as plt\n"
            "\n"
            "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
            "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
            "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
            "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
            "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
            "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
        ),
        code_cell(
            "with engine.begin() as conn:\n"
            "    conn.execute(text('''\n"
            "        CREATE TABLE IF NOT EXISTS rss_sentiment_signals (\n"
            "            id BIGSERIAL PRIMARY KEY,\n"
            "            observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),\n"
            "            symbol TEXT NOT NULL,\n"
            "            source TEXT NOT NULL,\n"
            "            article_title TEXT,\n"
            "            article_url TEXT,\n"
            "            sentiment_score DOUBLE PRECISION NOT NULL,\n"
            "            confidence DOUBLE PRECISION NOT NULL DEFAULT 0.0,\n"
            "            model_name TEXT,\n"
            "            metadata JSONB NOT NULL DEFAULT '{}'::jsonb\n"
            "        )\n"
            "    '''))\n"
            "print('rss_sentiment_signals ready')\n"
        ),
        code_cell(
            "docs = pd.read_sql(text('''\n"
            "SELECT id, source, text, metadata, updated_at\n"
            "FROM document_staging\n"
            "WHERE source = 'rss'\n"
            "  AND updated_at >= NOW() - INTERVAL '7 days'\n"
            "ORDER BY updated_at DESC\n"
            "LIMIT 2000\n"
            "'''), engine)\n"
            "docs.head(), len(docs)\n"
        ),
        code_cell(
            "bullish = {'bullish','breakout','rally','surge','beat','growth','adoption','upgrade','outperform','accumulate','buy'}\n"
            "bearish = {'bearish','selloff','dump','crash','downgrade','fraud','hack','regulation','lawsuit','risk-off','recession'}\n"
            "asset_map = {\n"
            "    'btc': 'BTC', 'bitcoin': 'BTC',\n"
            "    'eth': 'ETH', 'ethereum': 'ETH',\n"
            "    'sol': 'SOL', 'solana': 'SOL',\n"
            "    'avax': 'AVAX', 'avalanche': 'AVAX',\n"
            "    'link': 'LINK', 'chainlink': 'LINK'\n"
            "}\n"
            "\n"
            "def score_text(text):\n"
            "    tokens = re.findall(r\"[a-zA-Z0-9_\\-]+\", (text or '').lower())\n"
            "    if not tokens:\n"
            "        return 0.0, 0.0\n"
            "    pos = sum(t in bullish for t in tokens)\n"
            "    neg = sum(t in bearish for t in tokens)\n"
            "    raw = (pos - neg) / max(1, pos + neg)\n"
            "    confidence = min(1.0, (pos + neg) / 10.0)\n"
            "    return float(raw), float(confidence)\n"
            "\n"
            "def detect_symbol(text):\n"
            "    lower = (text or '').lower()\n"
            "    for key, sym in asset_map.items():\n"
            "        if key in lower:\n"
            "            return sym\n"
            "    return 'BTC'\n"
        ),
        code_cell(
            "rows = []\n"
            "for _, row in docs.iterrows():\n"
            "    body = row.get('text') or ''\n"
            "    score, confidence = score_text(body)\n"
            "    symbol = detect_symbol(body)\n"
            "    rows.append({\n"
            "        'observed_at': row.get('updated_at'),\n"
            "        'symbol': symbol,\n"
            "        'source': row.get('source') or 'rss',\n"
            "        'article_title': str(row.get('id')),\n"
            "        'article_url': None,\n"
            "        'sentiment_score': score,\n"
            "        'confidence': confidence,\n"
            "        'model_name': 'keyword_v1'\n"
            "    })\n"
            "signals = pd.DataFrame(rows)\n"
            "signals.head(), len(signals)\n"
        ),
        code_cell(
            "signals = signals[signals['confidence'] > 0.0].copy()\n"
            "if not signals.empty:\n"
            "    with engine.begin() as conn:\n"
            "        conn.execute(text('''\n"
            "            INSERT INTO rss_sentiment_signals (\n"
            "                observed_at, symbol, source, article_title, article_url,\n"
            "                sentiment_score, confidence, model_name, metadata\n"
            "            ) VALUES (\n"
            "                :observed_at, :symbol, :source, :article_title, :article_url,\n"
            "                :sentiment_score, :confidence, :model_name, '{}'::jsonb\n"
            "            )\n"
            "        '''), signals.to_dict(orient='records'))\n"
            "    print(f'Inserted {len(signals)} sentiment rows')\n"
            "else:\n"
            "    print('No sentiment rows to insert')\n"
        ),
        code_cell(
            "exchange = os.getenv('DATAMANCY_RESEARCH_EXCHANGE', 'hyperliquid_mainnet').strip() or 'hyperliquid_mainnet'\n"
            "exchange_aliases = ['hyperliquid', 'hyperliquid_mainnet'] if exchange == 'hyperliquid_mainnet' else [exchange]\n"
            "exchange_sql = ', '.join([\"'\" + alias.replace(\"'\", \"''\") + \"'\" for alias in exchange_aliases])\n"
            "\n"
            "plot_df = pd.read_sql(text(f'''\n"
            "WITH px AS (\n"
            "  SELECT time_bucket('15 minutes', time) AS ts, AVG(close) AS price\n"
            "  FROM market_data\n"
            "  WHERE symbol='BTC' AND exchange IN ({exchange_sql}) AND data_type='candle_1m'\n"
            "    AND time >= NOW() - INTERVAL '7 days'\n"
            "  GROUP BY 1\n"
            "), ss AS (\n"
            "  SELECT time_bucket('15 minutes', observed_at) AS ts, AVG(sentiment_score) AS sentiment\n"
            "  FROM rss_sentiment_signals\n"
            "  WHERE symbol='BTC' AND observed_at >= NOW() - INTERVAL '7 days'\n"
            "  GROUP BY 1\n"
            ")\n"
            "SELECT px.ts, px.price, COALESCE(ss.sentiment, 0.0) AS sentiment\n"
            "FROM px LEFT JOIN ss USING (ts)\n"
            "ORDER BY px.ts\n"
            "'''), engine, parse_dates=['ts'])\n"
            "\n"
            "fig, ax1 = plt.subplots(figsize=(14, 5))\n"
            "ax1.plot(plot_df['ts'], plot_df['price'], color='tab:blue', label='BTC Price')\n"
            "ax1.set_ylabel('BTC Price', color='tab:blue')\n"
            "ax2 = ax1.twinx()\n"
            "ax2.plot(plot_df['ts'], plot_df['sentiment'], color='tab:orange', alpha=0.8, label='Sentiment')\n"
            "ax2.set_ylabel('Sentiment', color='tab:orange')\n"
            "plt.title('BTC Price vs RSS Sentiment (15m)')\n"
            "fig.tight_layout()\n"
            "plt.show()\n"
        )
    ]
)

write_notebook(
    "03_strategy_parameter_sweep_and_robustness.ipynb",
    [
        markdown_cell(
            "# Strategy Parameter Sweep And Robustness\n\n"
            "This notebook runs a fast parameter sweep over EMA crossover settings using real Datamancy market data,\n"
            "then performs a simple walk-forward split to reduce overfitting risk.\n\n"
            "Outputs:\n"
            "- ranked parameter table by risk-adjusted return\n"
            "- in-sample vs out-of-sample comparison\n"
            "- optional persistence into `strategy_backtest_runs`\n"
        ),
        code_cell(
            "import os\n"
            "import numpy as np\n"
            "import pandas as pd\n"
            "from itertools import product\n"
            "from sqlalchemy import create_engine, text\n"
            "\n"
            "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
            "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
            "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
            "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
            "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
            "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
        ),
        code_cell(
            "exchange = os.getenv('DATAMANCY_RESEARCH_EXCHANGE', 'hyperliquid_mainnet').strip() or 'hyperliquid_mainnet'\n"
            "exchange_aliases = ['hyperliquid', 'hyperliquid_mainnet'] if exchange == 'hyperliquid_mainnet' else [exchange]\n"
            "exchange_sql = ', '.join([\"'\" + alias.replace(\"'\", \"''\") + \"'\" for alias in exchange_aliases])\n"
            "\n"
            "symbol = 'BTC'\n"
            "interval = 'candle_1m'\n"
            "lookback = '60 days'\n"
            "\n"
            "prices = pd.read_sql(text(f'''\n"
            "SELECT time, close\n"
            "FROM market_data\n"
            "WHERE symbol=:symbol\n"
            "  AND exchange IN ({exchange_sql})\n"
            "  AND data_type=:interval\n"
            "  AND time >= NOW() - CAST(:lookback AS interval)\n"
            "ORDER BY time ASC\n"
            "'''), engine, params={'symbol': symbol, 'interval': interval, 'lookback': lookback}, parse_dates=['time'])\n"
            "prices = prices.dropna().reset_index(drop=True)\n"
            "prices.head(), len(prices)\n"
        ),
        code_cell(
            "def backtest(df, fast, slow, fee_bps=4, slip_bps=2):\n"
            "    data = df.copy()\n"
            "    data['ret'] = data['close'].pct_change().fillna(0)\n"
            "    data['fast'] = data['close'].ewm(span=fast, adjust=False).mean()\n"
            "    data['slow'] = data['close'].ewm(span=slow, adjust=False).mean()\n"
            "    data['pos'] = (data['fast'] > data['slow']).astype(float).shift(1).fillna(0)\n"
            "    turnover = data['pos'].diff().abs().fillna(0)\n"
            "    cost = turnover * (fee_bps + slip_bps) / 10000.0\n"
            "    data['strat_ret'] = data['pos'] * data['ret'] - cost\n"
            "    eq = (1 + data['strat_ret']).cumprod()\n"
            "    net = float(eq.iloc[-1] - 1)\n"
            "    vol = float(data['strat_ret'].std())\n"
            "    sharpe = float((data['strat_ret'].mean() / (vol + 1e-12)) * np.sqrt(60 * 24 * 365))\n"
            "    peak = eq.cummax()\n"
            "    mdd = float((eq / peak - 1).min())\n"
            "    trades = int((data['pos'].diff().abs() > 0).sum())\n"
            "    score = net / (abs(mdd) + 1e-9)\n"
            "    return {'fast': fast, 'slow': slow, 'net_return': net, 'sharpe': sharpe, 'max_drawdown': mdd, 'trades': trades, 'score': score}\n"
        ),
        code_cell(
            "fast_grid = [8, 13, 21, 34]\n"
            "slow_grid = [34, 55, 89, 144]\n"
            "grid = [(f, s) for f, s in product(fast_grid, slow_grid) if f < s]\n"
            "\n"
            "split = int(len(prices) * 0.7)\n"
            "train = prices.iloc[:split].copy()\n"
            "test = prices.iloc[split:].copy()\n"
            "\n"
            "train_results = pd.DataFrame([backtest(train, f, s) for f, s in grid]).sort_values('score', ascending=False)\n"
            "best = train_results.head(5).copy()\n"
            "best\n"
        ),
        code_cell(
            "oos_rows = []\n"
            "for _, row in best.iterrows():\n"
            "    oos = backtest(test, int(row.fast), int(row.slow))\n"
            "    oos_rows.append({\n"
            "        'fast': int(row.fast),\n"
            "        'slow': int(row.slow),\n"
            "        'train_score': float(row.score),\n"
            "        'train_return_pct': float(row.net_return * 100),\n"
            "        'test_return_pct': float(oos['net_return'] * 100),\n"
            "        'test_sharpe': float(oos['sharpe']),\n"
            "        'test_drawdown_pct': float(oos['max_drawdown'] * 100),\n"
            "    })\n"
            "oos_df = pd.DataFrame(oos_rows).sort_values('test_return_pct', ascending=False)\n"
            "oos_df\n"
        ),
        code_cell(
            "best_row = oos_df.iloc[0]\n"
            "with engine.begin() as conn:\n"
            "    conn.execute(text('''\n"
            "        INSERT INTO strategy_backtest_runs (\n"
            "            strategy_name, symbol, timeframe, start_time, end_time,\n"
            "            trades, win_rate, net_return_pct, max_drawdown_pct, sharpe, notes, metrics\n"
            "        ) VALUES (\n"
            "            :strategy_name, :symbol, :timeframe, :start_time, :end_time,\n"
            "            :trades, :win_rate, :net_return_pct, :max_drawdown_pct, :sharpe, :notes, CAST(:metrics AS jsonb)\n"
            "        )\n"
            "    '''), {\n"
            "        'strategy_name': 'ema_sweep_walk_forward',\n"
            "        'symbol': symbol,\n"
            "        'timeframe': interval,\n"
            "        'start_time': prices['time'].min().to_pydatetime(),\n"
            "        'end_time': prices['time'].max().to_pydatetime(),\n"
            "        'trades': 0,\n"
            "        'win_rate': 0.0,\n"
            "        'net_return_pct': float(best_row.test_return_pct),\n"
            "        'max_drawdown_pct': float(best_row.test_drawdown_pct),\n"
            "        'sharpe': float(best_row.test_sharpe),\n"
            "        'notes': 'Top walk-forward parameter set from preset notebook',\n"
            "        'metrics': '{\"method\":\"ema_grid_walk_forward\"}'\n"
            "    })\n"
            "print('Saved best walk-forward run to strategy_backtest_runs')\n"
        )
    ]
)

write_notebook(
    "04_alpha_signal_ranking.ipynb",
    [
        markdown_cell(
            "# Alpha Signal Ranking (Market + Sentiment)\n\n"
            "This notebook ranks symbols by combining short-term momentum, volatility normalization,\n"
            "and RSS sentiment regime. It is designed for quick daily triage of tradable setups.\n\n"
            "Outputs:\n"
            "- per-symbol alpha score table\n"
            "- candidate long/short watchlist\n"
            "- optional persistence to `strategy_backtest_runs`\n"
        ),
        code_cell(
            "import os\n"
            "import numpy as np\n"
            "import pandas as pd\n"
            "from sqlalchemy import create_engine, text\n"
            "\n"
            "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
            "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
            "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
            "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
            "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
            "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
        ),
        code_cell(
            "exchange = os.getenv('DATAMANCY_RESEARCH_EXCHANGE', 'hyperliquid_mainnet').strip() or 'hyperliquid_mainnet'\n"
            "exchange_aliases = ['hyperliquid', 'hyperliquid_mainnet'] if exchange == 'hyperliquid_mainnet' else [exchange]\n"
            "exchange_sql = ', '.join([\"'\" + alias.replace(\"'\", \"''\") + \"'\" for alias in exchange_aliases])\n"
            "\n"
            "symbols = ['BTC', 'ETH', 'SOL', 'AVAX', 'LINK']\n"
            "\n"
            "px = pd.read_sql(text(f'''\n"
            "SELECT time, symbol, close\n"
            "FROM market_data\n"
            "WHERE exchange IN ({exchange_sql})\n"
            "  AND data_type = 'candle_1m'\n"
            "  AND symbol = ANY(:symbols)\n"
            "  AND time >= NOW() - INTERVAL '7 days'\n"
            "ORDER BY symbol, time\n"
            "'''), engine, params={'symbols': symbols}, parse_dates=['time'])\n"
            "\n"
            "ss = pd.read_sql(text('''\n"
            "SELECT observed_at, symbol, sentiment_score\n"
            "FROM rss_sentiment_signals\n"
            "WHERE symbol = ANY(:symbols)\n"
            "  AND observed_at >= NOW() - INTERVAL '7 days'\n"
            "ORDER BY symbol, observed_at\n"
            "'''), engine, params={'symbols': symbols}, parse_dates=['observed_at'])\n"
            "px.head(), len(px), ss.head(), len(ss)\n"
        ),
        code_cell(
            "rows = []\n"
            "for sym in symbols:\n"
            "    p = px[px.symbol == sym].copy()\n"
            "    if len(p) < 120:\n"
            "        continue\n"
            "    p['ret_1m'] = p['close'].pct_change().fillna(0)\n"
            "    p['mom_30m'] = p['close'].pct_change(30)\n"
            "    p['mom_4h'] = p['close'].pct_change(240)\n"
            "    p['vol_4h'] = p['ret_1m'].rolling(240).std()\n"
            "    latest = p.iloc[-1]\n"
            "\n"
            "    s = ss[ss.symbol == sym].copy()\n"
            "    if len(s) > 0:\n"
            "        s['roll_sent'] = s['sentiment_score'].rolling(20, min_periods=1).mean()\n"
            "        sent = float(s['roll_sent'].iloc[-1])\n"
            "    else:\n"
            "        sent = 0.0\n"
            "\n"
            "    vol = float(latest['vol_4h']) if pd.notna(latest['vol_4h']) and latest['vol_4h'] > 0 else 1e-6\n"
            "    momentum_component = (float(latest['mom_30m']) * 0.4 + float(latest['mom_4h']) * 0.6) / vol\n"
            "    alpha_score = momentum_component + (sent * 0.75)\n"
            "\n"
            "    rows.append({\n"
            "        'symbol': sym,\n"
            "        'price': float(latest['close']),\n"
            "        'mom_30m_pct': float(latest['mom_30m'] * 100),\n"
            "        'mom_4h_pct': float(latest['mom_4h'] * 100),\n"
            "        'vol_4h_pct': float(vol * 100),\n"
            "        'sentiment': sent,\n"
            "        'alpha_score': alpha_score\n"
            "    })\n"
            "\n"
            "ranked = pd.DataFrame(rows).sort_values('alpha_score', ascending=False)\n"
            "ranked\n"
        ),
        code_cell(
            "longs = ranked.head(2).copy()\n"
            "shorts = ranked.tail(2).sort_values('alpha_score').copy()\n"
            "print('Long candidates:')\n"
            "display(longs[['symbol', 'alpha_score', 'sentiment', 'mom_30m_pct', 'mom_4h_pct']])\n"
            "print('Short candidates:')\n"
            "display(shorts[['symbol', 'alpha_score', 'sentiment', 'mom_30m_pct', 'mom_4h_pct']])\n"
        ),
        code_cell(
            "top = ranked.iloc[0] if len(ranked) > 0 else None\n"
            "if top is not None:\n"
            "    with engine.begin() as conn:\n"
            "        conn.execute(text('''\n"
            "            INSERT INTO strategy_backtest_runs (\n"
            "                strategy_name, symbol, timeframe, start_time, end_time,\n"
            "                trades, win_rate, net_return_pct, max_drawdown_pct, sharpe, notes, metrics\n"
            "            ) VALUES (\n"
            "                :strategy_name, :symbol, :timeframe, NOW() - INTERVAL '7 days', NOW(),\n"
            "                0, 0.0, 0.0, 0.0, 0.0, :notes, CAST(:metrics AS jsonb)\n"
            "            ) ON CONFLICT DO NOTHING\n"
            "        '''), {\n"
            "            'strategy_name': 'alpha_signal_ranking',\n"
            "            'symbol': str(top['symbol']),\n"
            "            'timeframe': '1m/4h hybrid',\n"
            "            'notes': 'Signal ranking snapshot; execute with discretionary risk controls',\n"
            "            'metrics': ranked.to_json(orient='records')\n"
            "        })\n"
            "    print('Saved alpha ranking snapshot to strategy_backtest_runs')\n"
            "else:\n"
            "    print('No ranked candidates available')\n"
        )
    ]
)

write_notebook(
    "05_llm_rss_sentiment_backfill.ipynb",
    [
        markdown_cell(
            "# LLM RSS Sentiment Backfill (Trading Signals)\n\n"
            "This notebook uses the stack LLM endpoint (LiteLLM-compatible OpenAI API) to score recent RSS documents,\n"
            "stores sentiment to `rss_sentiment_signals`, and prepares data for Grafana `Trading Alpha` dashboards.\n\n"
            "It is designed for practical daily usage:\n"
            "- run once after deployment to prime sentiment tables\n"
            "- re-run periodically to keep signals fresh\n"
            "- provides structured fields for quant/backtest workflows\n"
        ),
        code_cell(
            "import os\n"
            "import json\n"
            "import re\n"
            "import requests\n"
            "import pandas as pd\n"
            "from sqlalchemy import create_engine, text\n"
            "\n"
            "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
            "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
            "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
            "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
            "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
            "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
            "\n"
            "llm_base = os.getenv('OPENAI_API_BASE', 'http://litellm:4000/v1').rstrip('/')\n"
            "llm_key = os.getenv('OPENAI_API_KEY', 'unused')\n"
            "llm_model = os.getenv('DEFAULT_LLM_MODEL', 'qwen2.5-0.5b')\n"
        ),
        code_cell(
            "with engine.begin() as conn:\n"
            "    conn.execute(text('''\n"
            "        CREATE TABLE IF NOT EXISTS rss_sentiment_signals (\n"
            "            id BIGSERIAL PRIMARY KEY,\n"
            "            observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),\n"
            "            symbol TEXT NOT NULL,\n"
            "            source TEXT NOT NULL,\n"
            "            article_title TEXT,\n"
            "            article_url TEXT,\n"
            "            sentiment_score DOUBLE PRECISION NOT NULL,\n"
            "            confidence DOUBLE PRECISION NOT NULL DEFAULT 0.0,\n"
            "            model_name TEXT,\n"
            "            metadata JSONB NOT NULL DEFAULT '{}'::jsonb\n"
            "        )\n"
            "    '''))\n"
            "    conn.execute(text('''\n"
            "        CREATE UNIQUE INDEX IF NOT EXISTS idx_rss_sentiment_dedupe\n"
            "        ON rss_sentiment_signals (\n"
            "            source,\n"
            "            symbol,\n"
            "            COALESCE(article_url, ''),\n"
            "            COALESCE(article_title, ''),\n"
            "            observed_at\n"
            "        )\n"
            "    '''))\n"
            "print('rss_sentiment_signals ready')\n"
        ),
        code_cell(
            "docs = pd.read_sql(text('''\n"
            "SELECT\n"
            "  id,\n"
            "  source,\n"
            "  text,\n"
            "  updated_at,\n"
            "  metadata,\n"
            "  COALESCE(bookstack_url, '') AS bookstack_url\n"
            "FROM document_staging\n"
            "WHERE source = 'rss'\n"
            "  AND updated_at >= NOW() - INTERVAL '7 days'\n"
            "ORDER BY updated_at DESC\n"
            "LIMIT 120\n"
            "'''), engine)\n"
            "docs.head(), len(docs)\n"
        ),
        code_cell(
            "def parse_metadata_field(raw, key):\n"
            "    try:\n"
            "        obj = json.loads(raw) if isinstance(raw, str) and raw.strip().startswith('{') else {}\n"
            "    except Exception:\n"
            "        obj = {}\n"
            "    return obj.get(key)\n"
            "\n"
            "def fallback_score(text_body):\n"
            "    t = (text_body or '').lower()\n"
            "    pos = len(re.findall(r'bullish|rally|surge|breakout|outperform|upgrade|accumulate|growth|adoption', t))\n"
            "    neg = len(re.findall(r'bearish|selloff|crash|downgrade|hack|lawsuit|ban|regulation|risk-off', t))\n"
            "    score = (pos - neg) / max(1, pos + neg)\n"
            "    conf = min(1.0, (pos + neg) / 6.0)\n"
            "    if 'ethereum' in t or ' eth ' in f' {t} ': sym = 'ETH'\n"
            "    elif 'solana' in t or ' sol ' in f' {t} ': sym = 'SOL'\n"
            "    elif 'avalanche' in t or ' avax ' in f' {t} ': sym = 'AVAX'\n"
            "    elif 'chainlink' in t or ' link ' in f' {t} ': sym = 'LINK'\n"
            "    else: sym = 'BTC'\n"
            "    return sym, float(score), float(conf), 'keyword_fallback_v1'\n"
            "\n"
            "def llm_score(title, body):\n"
            "    prompt = (\n"
            "        'You are a crypto market sentiment classifier. Return strict JSON with keys '\n"
            "        'symbol, sentiment_score, confidence, rationale.\\n'\n"
            "        'symbol must be one of BTC, ETH, SOL, AVAX, LINK.\\n'\n"
            "        'sentiment_score must be a float in [-1, 1].\\n'\n"
            "        'confidence must be a float in [0, 1].\\n\\n'\n"
            "        f'TITLE: {title}\\n\\nBODY: {body[:3000]}'\n"
            "    )\n"
            "    payload = {\n"
            "        'model': llm_model,\n"
            "        'temperature': 0.0,\n"
            "        'messages': [\n"
            "            {'role': 'system', 'content': 'Output only valid JSON.'},\n"
            "            {'role': 'user', 'content': prompt}\n"
            "        ]\n"
            "    }\n"
            "    r = requests.post(\n"
            "        f'{llm_base}/chat/completions',\n"
            "        headers={'Authorization': f'Bearer {llm_key}'},\n"
            "        json=payload,\n"
            "        timeout=45\n"
            "    )\n"
            "    r.raise_for_status()\n"
            "    content = r.json()['choices'][0]['message']['content']\n"
            "    parsed = json.loads(content)\n"
            "    symbol = str(parsed.get('symbol', 'BTC')).upper()\n"
            "    if symbol not in {'BTC', 'ETH', 'SOL', 'AVAX', 'LINK'}:\n"
            "        symbol = 'BTC'\n"
            "    score = float(parsed.get('sentiment_score', 0.0))\n"
            "    confidence = float(parsed.get('confidence', 0.0))\n"
            "    rationale = str(parsed.get('rationale', ''))\n"
            "    score = max(-1.0, min(1.0, score))\n"
            "    confidence = max(0.0, min(1.0, confidence))\n"
            "    return symbol, score, confidence, rationale\n"
        ),
        code_cell(
            "rows = []\n"
            "for _, doc in docs.iterrows():\n"
            "    body = (doc.get('text') or '').strip()\n"
            "    if not body:\n"
            "        continue\n"
            "    title = parse_metadata_field(doc.get('metadata'), 'title') or str(doc.get('id'))\n"
            "    url = parse_metadata_field(doc.get('metadata'), 'url') or None\n"
            "    observed_at = doc.get('updated_at')\n"
            "\n"
            "    try:\n"
            "        symbol, score, confidence, rationale = llm_score(title, body)\n"
            "        model_name = f'{llm_model}_sentiment_v1'\n"
            "    except Exception:\n"
            "        symbol, score, confidence, rationale = fallback_score(body)\n"
            "        model_name = rationale\n"
            "\n"
            "    rows.append({\n"
            "        'observed_at': observed_at,\n"
            "        'symbol': symbol,\n"
            "        'source': 'rss',\n"
            "        'article_title': title,\n"
            "        'article_url': url,\n"
            "        'sentiment_score': score,\n"
            "        'confidence': confidence,\n"
            "        'model_name': model_name,\n"
            "        'metadata': json.dumps({'bookstack_url': doc.get('bookstack_url', ''), 'doc_id': str(doc.get('id'))})\n"
            "    })\n"
            "\n"
            "signals = pd.DataFrame(rows)\n"
            "signals.head(), len(signals)\n"
        ),
        code_cell(
            "if not signals.empty:\n"
            "    with engine.begin() as conn:\n"
            "        conn.execute(text('''\n"
            "            INSERT INTO rss_sentiment_signals (\n"
            "                observed_at, symbol, source, article_title, article_url,\n"
            "                sentiment_score, confidence, model_name, metadata\n"
            "            ) VALUES (\n"
            "                :observed_at, :symbol, :source, :article_title, :article_url,\n"
            "                :sentiment_score, :confidence, :model_name, CAST(:metadata AS jsonb)\n"
            "            ) ON CONFLICT DO NOTHING\n"
            "        '''), signals.to_dict(orient='records'))\n"
            "    print(f'Inserted {len(signals)} sentiment rows (dedupe-aware)')\n"
            "else:\n"
            "    print('No sentiment rows generated')\n"
        ),
        code_cell(
            "summary = pd.read_sql(text('''\n"
            "SELECT\n"
            "  symbol,\n"
            "  COUNT(*) AS n,\n"
            "  ROUND(AVG(sentiment_score)::numeric, 3) AS avg_sentiment,\n"
            "  ROUND(AVG(confidence)::numeric, 3) AS avg_confidence,\n"
            "  MAX(observed_at) AS latest\n"
            "FROM rss_sentiment_signals\n"
            "WHERE observed_at >= NOW() - INTERVAL '7 days'\n"
            "GROUP BY symbol\n"
            "ORDER BY n DESC\n"
            "'''), engine)\n"
            "summary\n"
        )
    ]
)

write_notebook(
    "06_profitability_and_risk_attribution.ipynb",
    [
        markdown_cell(
            "# Profitability And Risk Attribution\n\n"
            "This notebook quantifies where PnL comes from using live Datamancy market/sentiment data.\n\n"
            "It combines:\n"
            "- strategy backtest run history from `strategy_backtest_runs`\n"
            "- rolling market regime features from `market_data`\n"
            "- RSS sentiment pressure from `rss_sentiment_signals`\n"
            "\n"
            "Outputs:\n"
            "- monthly profitability trends\n"
            "- regime-conditioned return table\n"
            "- simple explanatory model for return drivers\n"
        ),
        code_cell(
            "import os\n"
            "import numpy as np\n"
            "import pandas as pd\n"
            "from sqlalchemy import create_engine, text\n"
            "import matplotlib.pyplot as plt\n"
            "\n"
            "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
            "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
            "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
            "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
            "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
            "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
        ),
        code_cell(
            "runs = pd.read_sql(text('''\n"
            "SELECT run_at, strategy_name, symbol, timeframe, trades, win_rate, net_return_pct, max_drawdown_pct, sharpe\n"
            "FROM strategy_backtest_runs\n"
            "WHERE run_at >= NOW() - INTERVAL '120 days'\n"
            "ORDER BY run_at ASC\n"
            "'''), engine, parse_dates=['run_at'])\n"
            "runs.head(), len(runs)\n"
        ),
        code_cell(
            "exchange = os.getenv('DATAMANCY_RESEARCH_EXCHANGE', 'hyperliquid_mainnet').strip() or 'hyperliquid_mainnet'\n"
            "exchange_aliases = ['hyperliquid', 'hyperliquid_mainnet'] if exchange == 'hyperliquid_mainnet' else [exchange]\n"
            "exchange_sql = ', '.join([\"'\" + alias.replace(\"'\", \"''\") + \"'\" for alias in exchange_aliases])\n"
            "\n"
            "market = pd.read_sql(text(f'''\n"
            "WITH px AS (\n"
            "  SELECT time, symbol, close,\n"
            "         LAG(close) OVER (PARTITION BY symbol ORDER BY time) AS prev_close\n"
            "  FROM market_data\n"
            "  WHERE exchange IN ({exchange_sql})\n"
            "    AND data_type = 'candle_1m'\n"
            "    AND time >= NOW() - INTERVAL '120 days'\n"
            "    AND symbol IN ('BTC', 'ETH', 'SOL', 'AVAX', 'LINK')\n"
            ")\n"
            "SELECT\n"
            "  date_trunc('day', time) AS day,\n"
            "  symbol,\n"
            "  AVG(CASE WHEN prev_close IS NULL OR prev_close = 0 THEN 0 ELSE (close - prev_close) / prev_close END) AS avg_ret_1m,\n"
            "  STDDEV(CASE WHEN prev_close IS NULL OR prev_close = 0 THEN 0 ELSE (close - prev_close) / prev_close END) AS vol_1m,\n"
            "  (MAX(close) - MIN(close)) / NULLIF(MIN(close), 0) AS intraday_range\n"
            "FROM px\n"
            "GROUP BY 1, 2\n"
            "ORDER BY 1, 2\n"
            "'''), engine, parse_dates=['day'])\n"
            "market.head(), len(market)\n"
        ),
        code_cell(
            "sentiment = pd.read_sql(text('''\n"
            "SELECT date_trunc('day', observed_at) AS day, symbol,\n"
            "       AVG(sentiment_score) AS sentiment,\n"
            "       AVG(confidence) AS confidence,\n"
            "       COUNT(*) AS n_signals\n"
            "FROM rss_sentiment_signals\n"
            "WHERE observed_at >= NOW() - INTERVAL '120 days'\n"
            "  AND symbol IN ('BTC', 'ETH', 'SOL', 'AVAX', 'LINK')\n"
            "GROUP BY 1, 2\n"
            "ORDER BY 1, 2\n"
            "'''), engine, parse_dates=['day'])\n"
            "sentiment.head(), len(sentiment)\n"
        ),
        code_cell(
            "if runs.empty:\n"
            "    print('No strategy_backtest_runs rows found. Run notebooks 01/03/04 first.')\n"
            "else:\n"
            "    runs['month'] = runs['run_at'].dt.to_period('M').dt.to_timestamp()\n"
            "    monthly = runs.groupby('month', as_index=False).agg(\n"
            "        run_count=('strategy_name', 'count'),\n"
            "        avg_net_return_pct=('net_return_pct', 'mean'),\n"
            "        median_net_return_pct=('net_return_pct', 'median'),\n"
            "        avg_sharpe=('sharpe', 'mean'),\n"
            "        worst_drawdown_pct=('max_drawdown_pct', 'min')\n"
            "    )\n"
            "    display(monthly.tail(12))\n"
            "    fig, ax = plt.subplots(1, 1, figsize=(12, 4))\n"
            "    ax.plot(monthly['month'], monthly['avg_net_return_pct'], marker='o', label='Avg Net Return %')\n"
            "    ax.plot(monthly['month'], monthly['median_net_return_pct'], marker='x', label='Median Net Return %')\n"
            "    ax.axhline(0, color='black', linewidth=1)\n"
            "    ax.legend()\n"
            "    ax.set_title('Monthly Profitability Trend')\n"
            "    plt.tight_layout()\n"
            "    plt.show()\n"
        ),
        code_cell(
            "if runs.empty or market.empty:\n"
            "    print('Insufficient data for attribution model.')\n"
            "else:\n"
            "    frame = runs.copy()\n"
            "    frame['day'] = frame['run_at'].dt.floor('D')\n"
            "    m = market.groupby('day', as_index=False).agg(\n"
            "        mkt_ret=('avg_ret_1m', 'mean'),\n"
            "        mkt_vol=('vol_1m', 'mean'),\n"
            "        mkt_range=('intraday_range', 'mean')\n"
            "    )\n"
            "    s = sentiment.groupby('day', as_index=False).agg(\n"
            "        sent=('sentiment', 'mean'),\n"
            "        sent_conf=('confidence', 'mean'),\n"
            "        sent_n=('n_signals', 'sum')\n"
            "    )\n"
            "    frame = frame.merge(m, on='day', how='left').merge(s, on='day', how='left').fillna(0)\n"
            "    X = frame[['mkt_ret', 'mkt_vol', 'mkt_range', 'sent', 'sent_conf', 'sent_n']].to_numpy(dtype=float)\n"
            "    y = frame['net_return_pct'].to_numpy(dtype=float)\n"
            "    Xs = np.column_stack([np.ones(len(X)), X])\n"
            "    beta, *_ = np.linalg.lstsq(Xs, y, rcond=None)\n"
            "    preds = Xs @ beta\n"
            "    r2 = 1 - np.sum((y - preds) ** 2) / (np.sum((y - y.mean()) ** 2) + 1e-12)\n"
            "    coef = pd.DataFrame({\n"
            "        'feature': ['intercept', 'mkt_ret', 'mkt_vol', 'mkt_range', 'sent', 'sent_conf', 'sent_n'],\n"
            "        'coefficient': beta\n"
            "    }).sort_values('coefficient', ascending=False)\n"
            "    display(coef)\n"
            "    print(f'Attribution R^2: {r2:.3f}')\n"
            "    frame['predicted_return_pct'] = preds\n"
            "    display(frame[['run_at', 'strategy_name', 'symbol', 'net_return_pct', 'predicted_return_pct']].tail(20))\n"
        )
    ]
)

write_notebook(
    "07_multi_exchange_execution_mux.ipynb",
    [
        markdown_cell(
            "# Multi-Exchange Execution Mux\n\n"
            "This notebook builds an execution watchlist across supported exchange venues\n"
            "(`swyftx`, `binance`, `bybit`, `coinbase`, `dydx`, `hyperliquid`, `aster`).\n\n"
            "It ranks symbols by:\n"
            "- cross-exchange spread opportunity\n"
            "- liquidity (trade count / volume)\n"
            "- short-term momentum alignment\n"
            "- sentiment pressure\n"
        ),
        code_cell(
            "import os\n"
            "import pandas as pd\n"
            "import numpy as np\n"
            "from sqlalchemy import create_engine, text\n"
            "\n"
            "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
            "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
            "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
            "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
            "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
            "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
        ),
        code_cell(
            "lookback = '24 hours'\n"
            "sql = text('''\n"
            "WITH candles AS (\n"
            "  SELECT\n"
            "    exchange,\n"
            "    symbol,\n"
            "    time,\n"
            "    close,\n"
            "    volume,\n"
            "    LAG(close) OVER (PARTITION BY exchange, symbol ORDER BY time) AS prev_close\n"
            "  FROM market_data\n"
            "  WHERE data_type = 'candle_1m'\n"
            "    AND time >= NOW() - CAST(:lookback AS interval)\n"
            "), px AS (\n"
            "  SELECT\n"
            "    exchange,\n"
            "    symbol,\n"
            "    MAX(close) AS px_last,\n"
            "    MIN(close) AS px_low,\n"
            "    MAX(close) AS px_high,\n"
            "    COALESCE(SUM(CASE WHEN prev_close IS NOT NULL THEN (close - prev_close) / NULLIF(prev_close, 0) ELSE 0 END), 0) AS mom,\n"
            "    COALESCE(SUM(volume), 0) AS vol_sum,\n"
            "    COUNT(*)::int AS bars\n"
            "  FROM candles\n"
            "  GROUP BY exchange, symbol\n"
            "), spread AS (\n"
            "  SELECT\n"
            "    symbol,\n"
            "    MIN(px_last) AS best_bid_proxy,\n"
            "    MAX(px_last) AS best_ask_proxy,\n"
            "    COUNT(*)::int AS exchange_count,\n"
            "    COALESCE((MAX(px_last) - MIN(px_last)) / NULLIF(MIN(px_last), 0), 0) AS cross_spread_pct\n"
            "  FROM px\n"
            "  GROUP BY symbol\n"
            "), liq AS (\n"
            "  SELECT\n"
            "    symbol,\n"
            "    SUM(vol_sum) AS total_vol,\n"
            "    SUM(bars) AS total_bars,\n"
            "    AVG(mom) AS avg_momentum\n"
            "  FROM px\n"
            "  GROUP BY symbol\n"
            "), ss AS (\n"
            "  SELECT symbol, AVG(sentiment_score) AS sentiment\n"
            "  FROM rss_sentiment_signals\n"
            "  WHERE observed_at >= NOW() - INTERVAL '48 hours'\n"
            "  GROUP BY symbol\n"
            ")\n"
            "SELECT\n"
            "  l.symbol,\n"
            "  s.exchange_count,\n"
            "  s.cross_spread_pct,\n"
            "  l.total_vol,\n"
            "  l.total_bars,\n"
            "  l.avg_momentum,\n"
            "  COALESCE(ss.sentiment, 0) AS sentiment,\n"
            "  (\n"
            "    s.cross_spread_pct * 60.0\n"
            "    + l.avg_momentum * 25.0\n"
            "    + COALESCE(ss.sentiment, 0) * 15.0\n"
            "    + LN(1 + GREATEST(l.total_vol, 0)) * 2.0\n"
            "  ) AS mux_score\n"
            "FROM liq l\n"
            "JOIN spread s ON s.symbol = l.symbol\n"
            "LEFT JOIN ss ON ss.symbol = split_part(l.symbol, '-', 1)\n"
            "WHERE s.exchange_count >= 1\n"
            "ORDER BY mux_score DESC\n"
            "LIMIT 30\n"
            "''')\n"
            "\n"
            "mux = pd.read_sql(sql, engine, params={'lookback': lookback})\n"
            "mux\n"
        ),
        code_cell(
            "if mux.empty:\n"
            "    print('No market data available yet for mux scoring.')\n"
            "else:\n"
            "    longs = mux.sort_values('mux_score', ascending=False).head(10).copy()\n"
            "    shorts = mux.sort_values('mux_score', ascending=True).head(10).copy()\n"
            "    print('Long-side mux shortlist')\n"
            "    display(longs[['symbol', 'exchange_count', 'cross_spread_pct', 'avg_momentum', 'sentiment', 'mux_score']])\n"
            "    print('Short-side mux shortlist')\n"
            "    display(shorts[['symbol', 'exchange_count', 'cross_spread_pct', 'avg_momentum', 'sentiment', 'mux_score']])\n"
        ),
        code_cell(
            "persist = False  # Set True if you want to snapshot current mux scores into strategy_backtest_runs\n"
            "if persist and not mux.empty:\n"
            "    rows = mux.head(20).copy()\n"
            "    with engine.begin() as conn:\n"
            "        for _, r in rows.iterrows():\n"
            "            conn.execute(text('''\n"
            "                INSERT INTO strategy_backtest_runs (\n"
            "                    strategy_name, symbol, timeframe, start_time, end_time,\n"
            "                    trades, win_rate, net_return_pct, max_drawdown_pct, sharpe, notes, metrics\n"
            "                ) VALUES (\n"
            "                    :strategy_name, :symbol, :timeframe, NOW() - INTERVAL '24 hours', NOW(),\n"
            "                    0, 0, :net_return_pct, 0, 0, :notes, CAST(:metrics AS jsonb)\n"
            "                )\n"
            "            '''), {\n"
            "                'strategy_name': 'multi_exchange_mux_rank',\n"
            "                'symbol': r['symbol'],\n"
            "                'timeframe': 'rank_24h',\n"
            "                'net_return_pct': float(r['mux_score']),\n"
            "                'notes': 'Mux ranking snapshot',\n"
            "                'metrics': '{\"source\":\"07_multi_exchange_execution_mux\"}'\n"
            "            })\n"
            "    print(f'Persisted {len(rows)} mux ranking rows to strategy_backtest_runs')\n"
            "else:\n"
            "    print('Persistence disabled (set persist=True to save rankings).')\n"
        )
    ]
)

write_notebook(
    "08_empirical_intraday_strategy_research.ipynb",
    [
        markdown_cell(
            "# Empirical Intraday Strategy Research\n\n"
            "Execution-aware intraday research notebook aligned with empirical crypto microstructure findings.\n\n"
            "Implements:\n"
            "- LOB imbalance and order-flow imbalance (OFI) alpha\n"
            "- intraday momentum/reversal regime filter\n"
            "- funding/basis carry overlay\n"
            "- explicit fee/slippage/latency/fill realism knobs\n"
            "- walk-forward evaluation and regime bucket diagnostics\n"
            "- persistence to `strategy_backtest_runs`\n"
        ),
        code_cell(
            "import os\n"
            "import json\n"
            "import numpy as np\n"
            "import pandas as pd\n"
            "import matplotlib.pyplot as plt\n"
            "from sqlalchemy import create_engine, text\n"
            "\n"
            "pd.set_option('display.max_columns', 200)\n"
            "np.seterr(all='ignore')\n"
            "\n"
            "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
            "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
            "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
            "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
            "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
            "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
        ),
        code_cell(
            "# Strategy research knobs\n"
            "exchange = os.getenv('DATAMANCY_RESEARCH_EXCHANGE', 'hyperliquid_mainnet').strip() or 'hyperliquid_mainnet'\n"
            "exchange_aliases = ['hyperliquid', 'hyperliquid_mainnet'] if exchange == 'hyperliquid_mainnet' else [exchange]\n"
            "exchange_sql = ', '.join([\"'\" + alias.replace(\"'\", \"''\") + \"'\" for alias in exchange_aliases])\n"
            "symbol = 'BTC'\n"
            "lookback = '5 days'\n"
            "\n"
            "# Cost and execution realism\n"
            "maker_fee_bps = -1.0\n"
            "taker_fee_bps = 4.0\n"
            "base_slippage_bps = 1.5\n"
            "volatility_slippage_mult = 120.0\n"
            "latency_ms = 180\n"
            "latency_jitter_ms = 120\n"
            "marketable_limit_fail_base = 0.04\n"
            "marketable_limit_fail_vol_mult = 10.0\n"
            "\n"
            "# Signal thresholds\n"
            "z_entry = 1.1\n"
            "z_exit = 0.2\n"
            "momentum_lookback = 30\n"
            "reversal_lookback = 5\n"
            "max_leverage = 1.0\n"
        ),
        code_cell(
            "sql = text(f'''\n"
            "WITH px AS (\n"
            "  SELECT\n"
            "    time,\n"
            "    close,\n"
            "    open,\n"
            "    high,\n"
            "    low,\n"
            "    volume,\n"
            "    LAG(close) OVER (ORDER BY time) AS prev_close\n"
            "  FROM market_data\n"
            "  WHERE exchange IN ({exchange_sql})\n"
            "    AND symbol = :symbol\n"
            "    AND data_type = 'candle_1m'\n"
            "    AND time >= NOW() - CAST(:lookback AS interval)\n"
            "), ob AS (\n"
            "  SELECT\n"
            "    time,\n"
            "    best_bid,\n"
            "    best_ask,\n"
            "    spread_pct,\n"
            "    bid_depth_10,\n"
            "    ask_depth_10,\n"
            "    mid_price\n"
            "  FROM orderbook_data\n"
            "  WHERE exchange IN ({exchange_sql})\n"
            "    AND symbol = :symbol\n"
            "    AND time >= NOW() - CAST(:lookback AS interval)\n"
            "), funding AS (\n"
            "  SELECT time, funding_rate\n"
            "  FROM market_data\n"
            "  WHERE exchange IN ({exchange_sql})\n"
            "    AND symbol = :symbol\n"
            "    AND data_type = 'funding'\n"
            "    AND time >= NOW() - CAST(:lookback AS interval) - INTERVAL '1 hour'\n"
            ")\n"
            "SELECT\n"
            "  px.time,\n"
            "  px.close,\n"
            "  px.open,\n"
            "  px.high,\n"
            "  px.low,\n"
            "  px.volume,\n"
            "  px.prev_close,\n"
            "  ob.best_bid,\n"
            "  ob.best_ask,\n"
            "  ob.spread_pct,\n"
            "  ob.bid_depth_10,\n"
            "  ob.ask_depth_10,\n"
            "  ob.mid_price,\n"
            "  COALESCE(f.funding_rate, 0.0) AS funding_rate\n"
            "FROM px\n"
            "LEFT JOIN ob ON ob.time = px.time\n"
            "LEFT JOIN LATERAL (\n"
            "  SELECT funding_rate\n"
            "  FROM funding f\n"
            "  WHERE f.time <= px.time\n"
            "  ORDER BY f.time DESC\n"
            "  LIMIT 1\n"
            ") f ON TRUE\n"
            "ORDER BY px.time ASC\n"
            "''')\n"
            "df = pd.read_sql(\n"
            "    sql,\n"
            "    engine,\n"
            "    params={'symbol': symbol, 'lookback': lookback},\n"
            "    parse_dates=['time']\n"
            ")\n"
            "df = df.drop_duplicates(subset=['time']).sort_values('time').reset_index(drop=True)\n"
            "df[['spread_pct', 'bid_depth_10', 'ask_depth_10', 'funding_rate']] = df[['spread_pct', 'bid_depth_10', 'ask_depth_10', 'funding_rate']].fillna(method='ffill').fillna(0)\n"
            "df = df[df['close'].notna()].copy()\n"
            "print({'rows': len(df), 'start': str(df.time.min()), 'end': str(df.time.max())})\n"
        ),
        code_cell(
            "if len(df) < 1000:\n"
            "    raise RuntimeError('Not enough data for empirical strategy research notebook.')\n"
            "\n"
            "df['ret_1m'] = df['close'].pct_change().fillna(0)\n"
            "df['ret_fwd_1m'] = df['close'].shift(-1) / df['close'] - 1.0\n"
            "df['vol_30m'] = df['ret_1m'].rolling(30).std().fillna(0)\n"
            "df['spread_pct'] = np.where(df['spread_pct'] > 0, df['spread_pct'], (((df['best_ask'] - df['best_bid']) / np.where(df['mid_price'] == 0, np.nan, df['mid_price'])) * 100.0)).astype(float)\n"
            "df['spread_pct'] = df['spread_pct'].replace([np.inf, -np.inf], np.nan).fillna(0)\n"
            "\n"
            "# Microstructure features\n"
            "depth_denom = (df['bid_depth_10'] + df['ask_depth_10']).replace(0, np.nan)\n"
            "df['imbalance'] = ((df['bid_depth_10'] - df['ask_depth_10']) / depth_denom).fillna(0)\n"
            "df['ofi'] = (df['ret_1m'] * np.log1p(df['volume'].clip(lower=0))).fillna(0)\n"
            "df['micro_alpha_raw'] = 0.65 * df['imbalance'] + 0.35 * df['ofi']\n"
            "roll = 240\n"
            "mu = df['micro_alpha_raw'].rolling(roll).mean()\n"
            "sigma = df['micro_alpha_raw'].rolling(roll).std().replace(0, np.nan)\n"
            "df['micro_alpha_z'] = ((df['micro_alpha_raw'] - mu) / sigma).replace([np.inf, -np.inf], np.nan).fillna(0)\n"
            "\n"
            "# Momentum/reversal regime filter\n"
            "df['mom_30m'] = df['close'].pct_change(momentum_lookback)\n"
            "df['rev_5m'] = -df['close'].pct_change(reversal_lookback)\n"
            "df['regime_signal'] = np.where(df['vol_30m'] > df['vol_30m'].rolling(240).median().fillna(df['vol_30m'].median()), df['rev_5m'], df['mom_30m']).fillna(0)\n"
            "\n"
            "# Hyperliquid funding is paid hourly; convert the latest hourly rate into per-minute carry.\n"
            "df['carry_overlay'] = (-(df['funding_rate'].fillna(0.0) / 60.0)).clip(-0.0005, 0.0005)\n"
            "\n"
            "df[['micro_alpha_z','regime_signal','carry_overlay']].tail()\n"
        ),
        code_cell(
            "state = pd.DataFrame(index=df.index)\n"
            "state['vol_q'] = pd.qcut(df['vol_30m'].rank(method='first'), 4, labels=['q1', 'q2', 'q3', 'q4'])\n"
            "state['liq_proxy'] = (df['bid_depth_10'] + df['ask_depth_10']).fillna(0)\n"
            "state['liq_q'] = pd.qcut(state['liq_proxy'].rank(method='first'), 4, labels=['l1', 'l2', 'l3', 'l4'])\n"
            "\n"
            "# Base directional alpha with carry overlay\n"
            "raw_score = 0.75 * df['micro_alpha_z'] + 0.25 * (df['regime_signal'] / (df['vol_30m'] + 1e-8))\n"
            "raw_score = raw_score.clip(-4, 4)\n"
            "desired_pos = np.where(raw_score > z_entry, 1.0, np.where(raw_score < -z_entry, -1.0, np.nan))\n"
            "desired_pos = pd.Series(desired_pos, index=df.index).ffill().fillna(0)\n"
            "desired_pos = np.where(np.abs(raw_score) < z_exit, 0.0, desired_pos)\n"
            "desired_pos = pd.Series(desired_pos, index=df.index).clip(-max_leverage, max_leverage)\n"
            "\n"
            "# Latency model: decision applies after random lag in minutes.\n"
            "rng = np.random.default_rng(42)\n"
            "lat_ms = (latency_ms + rng.normal(0, latency_jitter_ms, len(df))).clip(min=0)\n"
            "lag_steps = np.floor(lat_ms / 60000.0).astype(int)\n"
            "exec_pos = np.zeros(len(df))\n"
            "for i in range(len(df)):\n"
            "    src = i - lag_steps[i]\n"
            "    exec_pos[i] = desired_pos.iloc[src] if src >= 0 else 0.0\n"
            "df['position'] = pd.Series(exec_pos, index=df.index).fillna(0)\n"
            "\n"
            "# Execution realism: slippage and selective marketable-limit failures\n"
            "turnover = df['position'].diff().abs().fillna(0)\n"
            "vol_norm = (df['vol_30m'] / (df['vol_30m'].rolling(240).median().replace(0, np.nan))).replace([np.inf, -np.inf], np.nan).fillna(1)\n"
            "spread_bps = df['spread_pct'].clip(lower=0) * 100.0\n"
            "slippage_bps = base_slippage_bps + volatility_slippage_mult * df['vol_30m'] + (spread_bps * 0.35)\n"
            "slippage_bps = slippage_bps.clip(lower=base_slippage_bps, upper=35)\n"
            "fee_bps = np.where(turnover > 0, taker_fee_bps, maker_fee_bps)\n"
            "fail_prob = (marketable_limit_fail_base + marketable_limit_fail_vol_mult * df['vol_30m']).clip(0, 0.95)\n"
            "fill_u = rng.uniform(0, 1, len(df))\n"
            "fill_mask = np.where(turnover > 0, (fill_u > fail_prob).astype(float), 1.0)\n"
            "\n"
            "effective_turnover = turnover * fill_mask\n"
            "cost = effective_turnover * ((fee_bps + slippage_bps) / 10000.0)\n"
            "gross_ret = df['position'] * df['ret_fwd_1m'].fillna(0) + (df['position'] * df['carry_overlay'])\n"
            "df['strategy_ret'] = gross_ret - cost\n"
            "df['equity'] = (1 + df['strategy_ret'].fillna(0)).cumprod()\n"
            "df['turnover'] = turnover\n"
            "df['effective_turnover'] = effective_turnover\n"
            "df['slippage_bps'] = slippage_bps\n"
            "df['fail_prob'] = fail_prob\n"
            "df['filled'] = fill_mask\n"
            "df[['time','position','ret_fwd_1m','strategy_ret','equity']].tail()\n"
        ),
        code_cell(
            "def summarize(frame):\n"
            "    frame = frame.copy()\n"
            "    if frame.empty:\n"
            "        return {'n': 0}\n"
            "    eq = (1 + frame['strategy_ret'].fillna(0)).cumprod()\n"
            "    peak = eq.cummax()\n"
            "    dd = (eq / peak - 1).min()\n"
            "    ret = eq.iloc[-1] - 1\n"
            "    vol = frame['strategy_ret'].std()\n"
            "    sharpe = (frame['strategy_ret'].mean() / (vol + 1e-12)) * np.sqrt(60 * 24 * 365)\n"
            "    win = (frame['strategy_ret'] > 0).sum() / max(1, (frame['strategy_ret'] != 0).sum())\n"
            "    trades = int((frame['effective_turnover'] > 0).sum())\n"
            "    return {\n"
            "        'n': int(len(frame)),\n"
            "        'net_return_pct': float(ret * 100),\n"
            "        'max_drawdown_pct': float(dd * 100),\n"
            "        'sharpe': float(sharpe),\n"
            "        'win_rate': float(win),\n"
            "        'trades': trades,\n"
            "        'avg_slippage_bps': float(frame['slippage_bps'].mean()),\n"
            "        'fill_rate': float(frame['filled'].mean())\n"
            "    }\n"
            "\n"
            "# Walk-forward splits (70/30 repeated windows)\n"
            "splits = []\n"
            "window = 3 * 24 * 60\n"
            "step = 24 * 60\n"
            "for start in range(0, max(1, len(df) - window), step):\n"
            "    end = min(len(df), start + window)\n"
            "    segment = df.iloc[start:end].copy()\n"
            "    if len(segment) < 500:\n"
            "        continue\n"
            "    cut = int(len(segment) * 0.7)\n"
            "    train = summarize(segment.iloc[:cut])\n"
            "    test = summarize(segment.iloc[cut:])\n"
            "    splits.append({\n"
            "        'start': segment['time'].iloc[0],\n"
            "        'end': segment['time'].iloc[-1],\n"
            "        'train_return_pct': train.get('net_return_pct', 0),\n"
            "        'test_return_pct': test.get('net_return_pct', 0),\n"
            "        'test_sharpe': test.get('sharpe', 0),\n"
            "        'test_drawdown_pct': test.get('max_drawdown_pct', 0),\n"
            "        'test_fill_rate': test.get('fill_rate', 0)\n"
            "    })\n"
            "wf = pd.DataFrame(splits)\n"
            "overall = summarize(df)\n"
            "print('Overall metrics:', overall)\n"
            "display(wf.tail(12))\n"
        ),
        code_cell(
            "reg = df.copy()\n"
            "reg['vol_bucket'] = state['vol_q'].astype(str)\n"
            "reg['liq_bucket'] = state['liq_q'].astype(str)\n"
            "regime_summary = reg.groupby(['vol_bucket', 'liq_bucket'], as_index=False).agg(\n"
            "    n=('strategy_ret', 'count'),\n"
            "    avg_ret=('strategy_ret', 'mean'),\n"
            "    hit=('strategy_ret', lambda x: float((x > 0).sum() / max(1, (x != 0).sum()))),\n"
            "    avg_fail_prob=('fail_prob', 'mean'),\n"
            "    avg_slippage_bps=('slippage_bps', 'mean')\n"
            ")\n"
            "regime_summary['avg_ret_bps'] = regime_summary['avg_ret'] * 10000\n"
            "regime_summary = regime_summary.sort_values('avg_ret_bps', ascending=False)\n"
            "display(regime_summary)\n"
            "\n"
            "fig, ax = plt.subplots(2, 1, figsize=(14, 8), sharex=True)\n"
            "ax[0].plot(df['time'], df['close'], color='tab:blue', label='Close')\n"
            "ax[0].set_title(f'{symbol} Price ({exchange})')\n"
            "ax[0].legend()\n"
            "ax[1].plot(df['time'], df['equity'], color='tab:green', label='Execution-aware equity')\n"
            "ax[1].axhline(1.0, color='black', linewidth=1)\n"
            "ax[1].set_title('Strategy Equity Curve')\n"
            "ax[1].legend()\n"
            "plt.tight_layout()\n"
            "plt.show()\n"
        ),
        code_cell(
            "with engine.begin() as conn:\n"
            "    conn.execute(text('''\n"
            "        INSERT INTO strategy_backtest_runs (\n"
            "            strategy_name, symbol, timeframe, start_time, end_time,\n"
            "            trades, win_rate, net_return_pct, max_drawdown_pct, sharpe, notes, metrics\n"
            "        ) VALUES (\n"
            "            :strategy_name, :symbol, :timeframe, :start_time, :end_time,\n"
            "            :trades, :win_rate, :net_return_pct, :max_drawdown_pct, :sharpe, :notes, CAST(:metrics AS jsonb)\n"
            "        )\n"
            "    '''), {\n"
            "        'strategy_name': 'empirical_intraday_execution_aware_v1',\n"
            "        'symbol': symbol,\n"
            "        'timeframe': '1m_intraday',\n"
            "        'start_time': df['time'].min().to_pydatetime(),\n"
            "        'end_time': df['time'].max().to_pydatetime(),\n"
            "        'trades': int(overall.get('trades', 0)),\n"
            "        'win_rate': float(overall.get('win_rate', 0.0)),\n"
            "        'net_return_pct': float(overall.get('net_return_pct', 0.0)),\n"
            "        'max_drawdown_pct': float(overall.get('max_drawdown_pct', 0.0)),\n"
            "        'sharpe': float(overall.get('sharpe', 0.0)),\n"
            "        'notes': 'LOB imbalance + OFI + momentum/reversal + carry with explicit execution realism',\n"
            "        'metrics': json.dumps({\n"
            "            'maker_fee_bps': maker_fee_bps,\n"
            "            'taker_fee_bps': taker_fee_bps,\n"
            "            'base_slippage_bps': base_slippage_bps,\n"
            "            'volatility_slippage_mult': volatility_slippage_mult,\n"
            "            'latency_ms': latency_ms,\n"
            "            'latency_jitter_ms': latency_jitter_ms,\n"
            "            'marketable_limit_fail_base': marketable_limit_fail_base,\n"
            "            'marketable_limit_fail_vol_mult': marketable_limit_fail_vol_mult,\n"
            "            'walk_forward': wf.tail(5).to_dict(orient='records') if not wf.empty else []\n"
            "        })\n"
            "    })\n"
            "print('Saved empirical intraday run to strategy_backtest_runs')\n"
        )
    ]
)

write_notebook(
    "09_cross_venue_paper_execution_playbook.ipynb",
    [
        markdown_cell(
            "# Cross-Venue Paper Execution Playbook\n\n"
            "This notebook uses the tx-gateway unified exchange API to execute **paper** orders across\n"
            "`swyftx`, `binance`, `bybit`, `coinbase`, `dydx`, `aster`, and `hyperliquid`.\n\n"
            "**Important:** `hyperliquid` defaults to `forward_paper`. Live execution remains opt-in and\n"
            "requires explicit `executionMode` plus `ENABLE_HYPERLIQUID_TESTNET_LIVE=true` or\n"
            "`ENABLE_HYPERLIQUID_MAINNET_LIVE=true`.\n\n"
            "Use this to quickly test routing and sizing logic before enabling live capital.\n\n"
            "Workflow:\n"
            "1. Pull top candidates from `strategy_backtest_runs`.\n"
            "2. Ask the gateway for best quote routing.\n"
            "3. Submit forward-paper orders, or explicit Hyperliquid testnet-live orders when intentionally enabled.\n"
            "4. Persist and inspect outcomes from `/api/v1/history`.\n"
        ),
        code_cell(
            "import os\n"
            "import json\n"
            "import requests\n"
            "import pandas as pd\n"
            "from sqlalchemy import create_engine, text\n"
            "\n"
            "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
            "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
            "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
            "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
            "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
            "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
            "\n"
            "tx_base = os.getenv('TX_GATEWAY_URL', 'http://tx-gateway:8080').rstrip('/')\n"
            "tx_token = os.getenv('TX_AUTH_TOKEN', '').strip()\n"
            "allow_hyperliquid_testnet_live = os.getenv('ENABLE_HYPERLIQUID_TESTNET_LIVE', os.getenv('INCLUDE_LIVE_HYPERLIQUID', 'false')).strip().lower() in {'1','true','yes','on'}\n"
            "allow_hyperliquid_mainnet_live = os.getenv('ENABLE_HYPERLIQUID_MAINNET_LIVE', 'false').strip().lower() in {'1','true','yes','on'}\n"
            "hyperliquid_credential = os.getenv('HYPERLIQUID_CREDENTIAL', '').strip() or os.getenv('TRADING_E2E_HYPERLIQUID_KEY', '').strip() or os.getenv('HYPERLIQUID_TESTNET_KEY', '').strip() or os.getenv('HYPERLIQUID_KEY', '').strip()\n"
            "default_exchanges = ['swyftx','binance','bybit','coinbase','dydx','aster','hyperliquid']\n"
            "\n"
            "if not tx_token:\n"
            "    print('Set TX_AUTH_TOKEN in your notebook env before placing orders.')\n"
            "    print('Example in notebook: %env TX_AUTH_TOKEN=<your-jwt>')\n"
            "if allow_hyperliquid_testnet_live:\n"
            "    print('WARNING: ENABLE_HYPERLIQUID_TESTNET_LIVE=true enables signed Hyperliquid testnet-live orders from this notebook when credentials are present.')\n"
            "if allow_hyperliquid_mainnet_live:\n"
            "    print('WARNING: ENABLE_HYPERLIQUID_MAINNET_LIVE=true enables Hyperliquid mainnet-live orders from this notebook.')\n"
        ),
        code_cell(
            "_user_profile_cache = None\n"
            "\n"
            "def auth_headers(exchange=None, execution_mode='forward_paper'):\n"
            "    if not tx_token:\n"
            "        raise RuntimeError('TX_AUTH_TOKEN is required')\n"
            "    normalized_mode = (execution_mode or 'forward_paper').strip().lower()\n"
            "    headers = {'Authorization': f'Bearer {tx_token}', 'Content-Type': 'application/json'}\n"
            "    if normalized_mode in {'testnet_live', 'mainnet_live'}:\n"
            "        if (exchange or '').strip().lower() != 'hyperliquid':\n"
            "            raise RuntimeError('Live notebook execution is currently only wired for hyperliquid.')\n"
            "        if normalized_mode == 'testnet_live' and not allow_hyperliquid_testnet_live:\n"
            "            raise RuntimeError('Set ENABLE_HYPERLIQUID_TESTNET_LIVE=true to allow hyperliquid testnet-live orders from notebooks.')\n"
            "        if normalized_mode == 'mainnet_live' and not allow_hyperliquid_mainnet_live:\n"
            "            raise RuntimeError('Set ENABLE_HYPERLIQUID_MAINNET_LIVE=true to allow hyperliquid mainnet-live orders from notebooks.')\n"
            "        if not hyperliquid_credential:\n"
            "            raise RuntimeError('Set HYPERLIQUID_CREDENTIAL or TRADING_E2E_HYPERLIQUID_KEY/HYPERLIQUID_TESTNET_KEY before requesting hyperliquid live execution.')\n"
            "        headers['X-Credential-hyperliquid'] = hyperliquid_credential\n"
            "    return headers\n"
            "\n"
            "def user_profile(refresh=False):\n"
            "    global _user_profile_cache\n"
            "    if refresh or _user_profile_cache is None:\n"
            "        r = requests.get(f'{tx_base}/api/v1/user', headers=auth_headers(), timeout=20)\n"
            "        r.raise_for_status()\n"
            "        _user_profile_cache = r.json()\n"
            "    return _user_profile_cache\n"
            "\n"
            "def allowed_exchanges_for_mode(execution_mode='forward_paper'):\n"
            "    profile = user_profile()\n"
            "    normalized_mode = (execution_mode or 'forward_paper').strip().lower()\n"
            "    allowed_modes = {str(m).strip().lower() for m in profile.get('allowedTradingModes', []) if str(m).strip()}\n"
            "    if normalized_mode not in allowed_modes:\n"
            "        raise RuntimeError(f'Account is not provisioned for execution mode {normalized_mode}; allowed={sorted(allowed_modes)}')\n"
            "    allowed = [str(exchange).strip().lower() for exchange in profile.get('allowedExchanges', []) if str(exchange).strip()]\n"
            "    if normalized_mode in {'testnet_live', 'mainnet_live'}:\n"
            "        return [exchange for exchange in allowed if exchange == 'hyperliquid']\n"
            "    return allowed or list(default_exchanges)\n"
            "\n"
            "def best_quote(symbol, side='buy', exchanges=None, execution_mode='forward_paper'):\n"
            "    normalized_mode = (execution_mode or 'forward_paper').strip().lower()\n"
            "    ex = exchanges or allowed_exchanges_for_mode(normalized_mode)\n"
            "    params = {\n"
            "        'symbol': symbol,\n"
            "        'side': side,\n"
            "        'exchanges': ','.join(ex),\n"
            "        'executionMode': normalized_mode\n"
            "    }\n"
            "    r = requests.get(f'{tx_base}/api/v1/exchanges/best-quote', params=params, headers=auth_headers(), timeout=20)\n"
            "    r.raise_for_status()\n"
            "    return r.json()\n"
            "\n"
            "def place_order(exchange, symbol, side='BUY', order_type='MARKET', size='0.01', price=None, execution_mode='forward_paper', reduce_only=False, post_only=False, urgency_class='normal', fee_tier=None, max_slippage_bps=None, cancel_after_ms=None):\n"
            "    normalized_exchange = exchange.strip().lower()\n"
            "    normalized_mode = (execution_mode or 'forward_paper').strip().lower()\n"
            "    allowed = allowed_exchanges_for_mode(normalized_mode)\n"
            "    if normalized_exchange not in allowed:\n"
            "        raise RuntimeError(f'Exchange {normalized_exchange} is not allowed for execution mode {normalized_mode}; allowed={allowed}')\n"
            "    payload = {\n"
            "        'symbol': symbol,\n"
            "        'side': side,\n"
            "        'type': order_type,\n"
            "        'size': str(size),\n"
            "        'price': None if price is None else str(price),\n"
            "        'executionMode': normalized_mode,\n"
            "        'reduceOnly': bool(reduce_only),\n"
            "        'postOnly': bool(post_only)\n"
            "    }\n"
            "    if urgency_class:\n"
            "        payload['urgencyClass'] = urgency_class\n"
            "    if fee_tier:\n"
            "        payload['feeTier'] = fee_tier\n"
            "    if max_slippage_bps is not None:\n"
            "        payload['maxSlippageBps'] = float(max_slippage_bps)\n"
            "    if cancel_after_ms is not None:\n"
            "        payload['cancelAfterMs'] = int(cancel_after_ms)\n"
            "    r = requests.post(f'{tx_base}/api/v1/exchanges/{normalized_exchange}/order', headers=auth_headers(exchange=normalized_exchange, execution_mode=normalized_mode), json=payload, timeout=20)\n"
            "    r.raise_for_status()\n"
            "    return r.json()\n"
            "\n"
            "def place_paper_order(exchange, symbol, side='BUY', order_type='MARKET', size='0.01', price=None, reduce_only=False, post_only=False, urgency_class='normal', fee_tier=None, max_slippage_bps=None, cancel_after_ms=None):\n"
            "    return place_order(exchange=exchange, symbol=symbol, side=side, order_type=order_type, size=size, price=price, execution_mode='forward_paper', reduce_only=reduce_only, post_only=post_only, urgency_class=urgency_class, fee_tier=fee_tier, max_slippage_bps=max_slippage_bps, cancel_after_ms=cancel_after_ms)\n"
            "\n"
            "def place_hyperliquid_live_order(symbol, side='BUY', order_type='MARKET', size='0.01', price=None, execution_mode='testnet_live', reduce_only=False, post_only=False, urgency_class='normal', max_slippage_bps=35.0, cancel_after_ms=None):\n"
            "    normalized_mode = (execution_mode or 'testnet_live').strip().lower()\n"
            "    if normalized_mode not in {'testnet_live', 'mainnet_live'}:\n"
            "        raise RuntimeError('execution_mode must be testnet_live or mainnet_live for hyperliquid live orders')\n"
            "    return place_order(exchange='hyperliquid', symbol=symbol, side=side, order_type=order_type, size=size, price=price, execution_mode=normalized_mode, reduce_only=reduce_only, post_only=post_only, urgency_class=urgency_class, max_slippage_bps=max_slippage_bps, cancel_after_ms=cancel_after_ms)\n"
            "\n"
            "def place_hyperliquid_testnet_order(symbol, side='BUY', order_type='MARKET', size='0.01', price=None, reduce_only=False, post_only=False, urgency_class='normal', max_slippage_bps=35.0, cancel_after_ms=None):\n"
            "    return place_hyperliquid_live_order(symbol=symbol, side=side, order_type=order_type, size=size, price=price, execution_mode='testnet_live', reduce_only=reduce_only, post_only=post_only, urgency_class=urgency_class, max_slippage_bps=max_slippage_bps, cancel_after_ms=cancel_after_ms)\n"
            "\n"
            "def place_hyperliquid_mainnet_order(symbol, side='BUY', order_type='MARKET', size='0.01', price=None, reduce_only=False, post_only=False, urgency_class='normal', max_slippage_bps=35.0, cancel_after_ms=None):\n"
            "    return place_hyperliquid_live_order(symbol=symbol, side=side, order_type=order_type, size=size, price=price, execution_mode='mainnet_live', reduce_only=reduce_only, post_only=post_only, urgency_class=urgency_class, max_slippage_bps=max_slippage_bps, cancel_after_ms=cancel_after_ms)\n"
        ),
        code_cell(
            "candidates = pd.read_sql(text('''\n"
            "SELECT symbol, net_return_pct, sharpe, max_drawdown_pct, run_at\n"
            "FROM strategy_backtest_runs\n"
            "WHERE run_at >= NOW() - INTERVAL '30 days'\n"
            "ORDER BY net_return_pct DESC, sharpe DESC\n"
            "LIMIT 10\n"
            "'''), engine, parse_dates=['run_at'])\n"
            "candidates\n"
        ),
        code_cell(
            "if candidates.empty:\n"
            "    print('No backtest candidates found. Run notebooks 01/03/08 first.')\n"
            "else:\n"
            "    trial_rows = []\n"
            "    for _, row in candidates.head(5).iterrows():\n"
            "        symbol = str(row['symbol'])\n"
            "        side = 'buy' if float(row['net_return_pct']) >= 0 else 'sell'\n"
            "        try:\n"
            "            q = best_quote(symbol=symbol, side=side, execution_mode='forward_paper')\n"
            "            selected = q.get('selectedExchange')\n"
            "            quote = q.get('quote', {})\n"
            "            order = place_paper_order(\n"
            "                exchange=selected,\n"
            "                symbol=q.get('normalizedSymbol', symbol),\n"
            "                side=side.upper(),\n"
            "                order_type='MARKET',\n"
            "                size='0.01'\n"
            "            )\n"
            "            trial_rows.append({\n"
            "                'symbol': symbol,\n"
            "                'side': side,\n"
            "                'selected_exchange': selected,\n"
            "                'bid': quote.get('bid'),\n"
            "                'ask': quote.get('ask'),\n"
            "                'order_id': order.get('orderId'),\n"
            "                'status': order.get('status'),\n"
            "                'execution_mode': order.get('executionMode'),\n"
            "                'simulated': order.get('simulated', False)\n"
            "            })\n"
            "        except Exception as e:\n"
            "            trial_rows.append({'symbol': symbol, 'side': side, 'error': str(e)})\n"
            "\n"
            "trial = pd.DataFrame(trial_rows)\n"
            "trial\n"
        ),
        code_cell(
            "if tx_token:\n"
            "    hist = requests.get(f'{tx_base}/api/v1/history', headers=auth_headers(), params={'days': 7}, timeout=20)\n"
            "    hist.raise_for_status()\n"
            "    history_df = pd.DataFrame(hist.json())\n"
            "    display(history_df.head(30))\n"
            "else:\n"
            "    print('Set TX_AUTH_TOKEN to fetch trade history.')\n"
        )
    ]
)

cross_sectional_kotlin_bootstrap = '''
@file:DependsOn("/opt/datamancy/jars/postgresql-42.7.5.jar")
@file:DependsOn("/opt/datamancy/jars/gson-2.11.0.jar")

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

fun env(name: String, default: String = ""): String =
    System.getenv(name)?.trim()?.takeIf { it.isNotEmpty() } ?: default

fun envInt(name: String, default: Int): Int =
    env(name, default.toString()).toIntOrNull() ?: default

fun envDouble(name: String, default: Double): Double =
    env(name, default.toString()).toDoubleOrNull() ?: default

fun envBoolean(name: String, default: Boolean): Boolean {
    val raw = env(name, if (default) "true" else "false").lowercase()
    return raw in setOf("1", "true", "yes", "on")
}

fun clamp(value: Double, lower: Double, upper: Double): Double =
    max(lower, min(upper, value))

fun direction(value: Double): Double =
    when {
        value > 1e-9 -> 1.0
        value < -1e-9 -> -1.0
        else -> 0.0
    }

fun Double.round(decimals: Int = 4): Double {
    val scale = 10.0.pow(decimals.toDouble())
    return round(this * scale) / scale
}

fun sqlList(values: List<String>): String =
    values.joinToString(",") { "'" + it.replace("'", "''") + "'" }

fun urlEncode(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8)

fun mean(values: List<Double>): Double =
    if (values.isEmpty()) 0.0 else values.average()

fun stdev(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val mu = mean(values)
    val variance = values.sumOf { (it - mu).pow(2.0) } / values.size.toDouble()
    return sqrt(max(variance, 0.0))
}

fun rollingSum(values: List<Double>, endIndex: Int, window: Int): Double {
    if (values.isEmpty() || endIndex < 0) return 0.0
    val start = max(0, endIndex - window + 1)
    return values.subList(start, endIndex + 1).sum()
}

fun rollingMean(values: List<Double>, endIndex: Int, window: Int): Double {
    if (values.isEmpty() || endIndex < 0) return 0.0
    val start = max(0, endIndex - window + 1)
    return mean(values.subList(start, endIndex + 1))
}

fun deterministicJitter(time: Instant, salt: Int): Double {
    val bucket = (time.epochSecond / 60L) + salt.toLong() * 13L
    return abs((bucket % 37L) - 18L).toDouble()
}

fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { !it.isJsonNull }?.asString

fun JsonObject.bool(name: String): Boolean? =
    get(name)?.takeIf { !it.isJsonNull }?.asBoolean

fun JsonObject.double(name: String): Double? =
    get(name)?.takeIf { !it.isJsonNull }?.asDouble

fun JsonObject.obj(name: String): JsonObject? =
    getAsJsonObject(name)

fun JsonObject.array(name: String): JsonArray? =
    getAsJsonArray(name)

data class ExchangeCapabilitiesSnapshot(
    val paperOrder: Boolean,
    val liveOrder: Boolean,
    val nativeOrderAdapter: Boolean,
    val marketDataIngress: Boolean,
    val bestQuoteDefault: Boolean
)

data class ExchangeCatalogSnapshot(
    val apiName: String,
    val implementationStatus: String,
    val defaultExecutionMode: String,
    val supportedExecutionModes: List<String>,
    val capabilities: ExchangeCapabilitiesSnapshot,
    val notes: String
)

data class ExchangePlan(
    val exchange: String,
    val marketAliases: List<String>
)

data class ResearchConfig(
    val txGatewayUrl: String = env("TX_GATEWAY_URL", "http://tx-gateway:8080"),
    val txAuthToken: String = env("TX_AUTH_TOKEN", ""),
    val marketExchange: String = env("DATAMANCY_CROSS_SECTIONAL_MARKET_EXCHANGE", "hyperliquid_mainnet"),
    val executionExchangeOverride: String = env("DATAMANCY_CROSS_SECTIONAL_EXECUTION_EXCHANGE", ""),
    val lookbackHours: Int = envInt("DATAMANCY_CROSS_SECTIONAL_LOOKBACK_HOURS", 96),
    val forwardHours: Int = envInt("DATAMANCY_CROSS_SECTIONAL_FORWARD_HOURS", 12),
    val betaLookbackBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_BETA_LOOKBACK_BARS", 240),
    val trendLookbackBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_TREND_LOOKBACK_BARS", 60),
    val trendSlowBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_TREND_SLOW_BARS", 240),
    val reversionLookbackBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_REVERSION_LOOKBACK_BARS", 60),
    val trendHoldBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_TREND_HOLD_BARS", 45),
    val reversionHoldBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_REVERSION_HOLD_BARS", 20),
    val topPerSide: Int = envInt("DATAMANCY_CROSS_SECTIONAL_TOP_PER_SIDE", 1),
    val notionalUsd: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_NOTIONAL_USD", 5000.0),
    val maxSymbols: Int = envInt("DATAMANCY_CROSS_SECTIONAL_MAX_SYMBOLS", 8),
    val minBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_MIN_BARS", 360),
    val trendEntryScore: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_TREND_ENTRY_SCORE", 1.05),
    val reversionZEntry: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_REVERSION_Z_ENTRY", 2.15),
    val reversionZExit: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_REVERSION_Z_EXIT", 0.45),
    val maxSpreadBps: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MAX_SPREAD_BPS", 11.0),
    val minDepthMultiple: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MIN_DEPTH_MULTIPLE", 12.0),
    val minFillRatio: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MIN_FILL_RATIO", 0.58),
    val minVolumeRatio: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MIN_VOLUME_RATIO", 0.35),
    val maxVolumeRatio: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MAX_VOLUME_RATIO", 4.5),
    val maxVolRegime: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MAX_VOL_REGIME", 2.35),
    val executionSafetyMarginBps: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_EXECUTION_SAFETY_MARGIN_BPS", 8.0),
    val minExpectedNetEdgeBps: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_MIN_EXPECTED_NET_EDGE_BPS", 6.0),
    val trendMinFlowAlignment: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_TREND_MIN_FLOW_ALIGNMENT", 0.08),
    val reversionMaxContinuationPressure: Double = envDouble("DATAMANCY_CROSS_SECTIONAL_REVERSION_MAX_CONTINUATION_PRESSURE", 0.18),
    val trendCooldownBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_TREND_COOLDOWN_BARS", 18),
    val reversionCooldownBars: Int = envInt("DATAMANCY_CROSS_SECTIONAL_REVERSION_COOLDOWN_BARS", 10),
    val persistBacktest: Boolean = envBoolean("DATAMANCY_CROSS_SECTIONAL_PERSIST_BACKTEST", true),
    val persistForward: Boolean = envBoolean("DATAMANCY_CROSS_SECTIONAL_PERSIST_FORWARD", true),
    val enablePaperOrders: Boolean = envBoolean("DATAMANCY_CROSS_SECTIONAL_ENABLE_PAPER_ORDERS", false),
    val paperExecutionMode: String = env("DATAMANCY_CROSS_SECTIONAL_ORDER_MODE", "forward_paper")
)

data class Bar(
    val exchange: String,
    val symbol: String,
    val time: Instant,
    val close: Double,
    val volume: Double,
    val spreadPct: Double,
    val bidDepth10: Double,
    val askDepth10: Double,
    val midPrice: Double
)

data class BasePoint(
    val exchange: String,
    val symbol: String,
    val time: Instant,
    val barIndex: Int,
    val close: Double,
    val volume: Double,
    val spreadPct: Double,
    val spreadBps: Double,
    val bidDepth10: Double,
    val askDepth10: Double,
    val midPrice: Double,
    val depthUsd: Double,
    val ret1m: Double,
    val vol30: Double
)

data class UnrankedFeature(
    val exchange: String,
    val symbol: String,
    val time: Instant,
    val barIndex: Int,
    val close: Double,
    val volume: Double,
    val spreadPct: Double,
    val spreadBps: Double,
    val depthUsd: Double,
    val midPrice: Double,
    val ret1m: Double,
    val vol30: Double,
    val volBps: Double,
    val btcRet1m: Double,
    val ethRet1m: Double,
    val betaBtc: Double,
    val betaEth: Double,
    val residualRet: Double,
    val residualMomFast: Double,
    val residualMomSlow: Double,
    val residualZ: Double,
    val imbalance: Double,
    val volumeRatio: Double,
    val depthRatio: Double,
    val volRegime: Double,
    val flowSignal: Double,
    val rawTrend: Double,
    val trendExpectedGrossEdgeBps: Double,
    val reversionExpectedGrossEdgeBps: Double,
    val liquid: Boolean
)

data class FeatureRow(
    val exchange: String,
    val symbol: String,
    val time: Instant,
    val barIndex: Int,
    val close: Double,
    val volume: Double,
    val spreadPct: Double,
    val spreadBps: Double,
    val depthUsd: Double,
    val midPrice: Double,
    val ret1m: Double,
    val vol30: Double,
    val volBps: Double,
    val btcRet1m: Double,
    val ethRet1m: Double,
    val betaBtc: Double,
    val betaEth: Double,
    val residualRet: Double,
    val residualMomFast: Double,
    val residualMomSlow: Double,
    val residualZ: Double,
    val imbalance: Double,
    val volumeRatio: Double,
    val depthRatio: Double,
    val volRegime: Double,
    val flowSignal: Double,
    val breadth: Double,
    val rawTrend: Double,
    val trendScore: Double,
    val reversionScore: Double,
    val trendExpectedGrossEdgeBps: Double,
    val reversionExpectedGrossEdgeBps: Double,
    val liquid: Boolean,
    val trendLongRank: Int,
    val trendShortRank: Int,
    val reversionLongRank: Int,
    val reversionShortRank: Int
)

data class SignalSnapshot(
    val exchange: String,
    val symbol: String,
    val time: String,
    val lastPrice: Double,
    val betaBtc: Double,
    val betaEth: Double,
    val residualZ: Double,
    val trendScore: Double,
    val reversionScore: Double,
    val breadth: Double,
    val spreadBps: Double,
    val depthUsd: Double,
    val imbalance: Double,
    val flowSignal: Double,
    val volumeRatio: Double,
    val volRegime: Double,
    val trendExpectedNetEdgeBps: Double,
    val reversionExpectedNetEdgeBps: Double,
    val liquid: Boolean,
    val trendAction: String,
    val reversionAction: String
)

data class ExecutionEstimate(
    val fillRatio: Double,
    val feeBps: Double,
    val feeTier: String,
    val feeTierAdjustmentBps: Double,
    val makerFeeBps: Double,
    val takerFeeBps: Double,
    val spreadCostBps: Double,
    val slippageBps: Double,
    val impactBps: Double,
    val adverseSelectionBps: Double,
    val fundingDriftBps: Double,
    val basisDriftBps: Double,
    val totalCostBps: Double,
    val estimatedFeeUsd: Double,
    val estimatedCostUsd: Double
)

data class TradeRecord(
    val strategyName: String,
    val strategyKind: String,
    val exchange: String,
    val symbol: String,
    val side: String,
    val entryTime: Instant,
    val exitTime: Instant,
    val entryPrice: Double,
    val exitPrice: Double,
    val holdBars: Int,
    val grossReturnFraction: Double,
    val netReturnFraction: Double,
    val fillRatio: Double,
    val feeBps: Double,
    val feeTier: String,
    val feeTierAdjustmentBps: Double,
    val makerFeeBps: Double,
    val takerFeeBps: Double,
    val spreadCostBps: Double,
    val slippageBps: Double,
    val impactBps: Double,
    val adverseSelectionBps: Double,
    val fundingDriftBps: Double,
    val basisDriftBps: Double,
    val totalCostBps: Double,
    val edgeAfterCostBps: Double,
    val estimatedFeeUsd: Double,
    val estimatedCostUsd: Double,
    val entryTrendScore: Double,
    val entryResidualZ: Double,
    val expectedGrossEdgeBps: Double,
    val expectedRoundTripCostBps: Double,
    val expectedNetEdgeBps: Double,
    val entryImbalance: Double,
    val entryFlowSignal: Double,
    val entryVolumeRatio: Double,
    val entryVolRegime: Double,
    val betaBtc: Double,
    val betaEth: Double,
    val decisionLatencyMs: Double,
    val submitToAckMs: Double,
    val submitToFillMs: Double,
    val p50RoundtripMs: Double,
    val p95RoundtripMs: Double,
    val p99RoundtripMs: Double,
    val jitterMs: Double
)

data class StrategySummary(
    val strategyName: String,
    val strategyKind: String,
    val exchange: String,
    val symbol: String,
    val timeframe: String,
    val startTime: Instant,
    val endTime: Instant,
    val trades: Int,
    val winRate: Double,
    val netReturnPct: Double,
    val maxDrawdownPct: Double,
    val sharpe: Double,
    val avgEdgeAfterCostBps: Double,
    val avgTotalCostBps: Double,
    val avgSlippageBps: Double,
    val avgFillRatio: Double,
    val avgSubmitToFillMs: Double,
    val notes: String,
    val metricsJson: String
)

enum class StrategyKind {
    TREND,
    REVERSION
}

data class OpenPosition(
    val strategyName: String,
    val strategyKind: StrategyKind,
    val exchange: String,
    val symbol: String,
    val side: Int,
    val entryRow: FeatureRow,
    val entryEstimate: ExecutionEstimate,
    val expectedGrossEdgeBps: Double,
    val expectedRoundTripCostBps: Double,
    val expectedNetEdgeBps: Double
)

data class EntryCandidate(
    val row: FeatureRow,
    val side: Int,
    val entryEstimate: ExecutionEstimate,
    val expectedGrossEdgeBps: Double,
    val expectedRoundTripCostBps: Double,
    val expectedNetEdgeBps: Double
)

val gson: Gson = GsonBuilder().setPrettyPrinting().create()
val httpClient: HttpClient = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10))
    .build()

Class.forName("org.postgresql.Driver")
val pgHost = env("POSTGRES_HOST", "postgres")
val pgPort = env("POSTGRES_PORT", "5432")
val pgDb = env("POSTGRES_DB", "datamancy")
val pgUser = env("POSTGRES_USER", "pipeline_user")
val pgPassword = env("POSTGRES_PASSWORD", "")
val jdbcUrl = "jdbc:postgresql://$pgHost:$pgPort/$pgDb"

fun pgConnection(): Connection =
    DriverManager.getConnection(jdbcUrl, pgUser, pgPassword)

var researchConfig = ResearchConfig()
println("Research config")
println(gson.toJson(researchConfig))
'''

cross_sectional_kotlin_engine = '''
fun twoFactorBetas(window: List<Triple<Double, Double, Double>>): Pair<Double, Double> {
    if (window.size < 30) return 0.0 to 0.0
    val yMean = mean(window.map { it.first })
    val x1Mean = mean(window.map { it.second })
    val x2Mean = mean(window.map { it.third })

    var s11 = 0.0
    var s22 = 0.0
    var s12 = 0.0
    var sy1 = 0.0
    var sy2 = 0.0

    for ((yRaw, x1Raw, x2Raw) in window) {
        val y = yRaw - yMean
        val x1 = x1Raw - x1Mean
        val x2 = x2Raw - x2Mean
        s11 += x1 * x1
        s22 += x2 * x2
        s12 += x1 * x2
        sy1 += y * x1
        sy2 += y * x2
    }

    val determinant = (s11 * s22) - (s12 * s12)
    if (abs(determinant) < 1e-9) {
        val beta1 = if (abs(s11) < 1e-9) 0.0 else sy1 / s11
        val beta2 = if (abs(s22) < 1e-9) 0.0 else sy2 / s22
        return beta1 to beta2
    }

    val betaBtc = ((sy1 * s22) - (sy2 * s12)) / determinant
    val betaEth = ((sy2 * s11) - (sy1 * s12)) / determinant
    return betaBtc to betaEth
}

fun fetchExchangeCatalog(txBase: String): List<ExchangeCatalogSnapshot> =
    runCatching {
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges"))
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Exchange catalog returned status ${response.statusCode()}")
        }
        val payload = JsonParser.parseString(response.body()).asJsonObject
        payload.array("exchanges")
            ?.map { element ->
                val obj = element.asJsonObject
                val caps = obj.obj("capabilities") ?: JsonObject()
                ExchangeCatalogSnapshot(
                    apiName = obj.string("apiName") ?: obj.string("name") ?: "unknown",
                    implementationStatus = (obj.string("implementationStatus") ?: "placeholder").uppercase(),
                    defaultExecutionMode = obj.string("defaultExecutionMode") ?: "forward_paper",
                    supportedExecutionModes = obj.array("supportedExecutionModes")
                        ?.map { it.asString }
                        ?.filter { it.isNotBlank() }
                        ?: emptyList(),
                    capabilities = ExchangeCapabilitiesSnapshot(
                        paperOrder = caps.bool("paperOrder") ?: false,
                        liveOrder = caps.bool("liveOrder") ?: false,
                        nativeOrderAdapter = caps.bool("nativeOrderAdapter") ?: false,
                        marketDataIngress = caps.bool("marketDataIngress") ?: false,
                        bestQuoteDefault = caps.bool("bestQuoteDefault") ?: false
                    ),
                    notes = obj.string("notes") ?: ""
                )
            }
            ?: emptyList()
    }.getOrElse { ex ->
        println("Exchange catalog unavailable, falling back to Hyperliquid only: ${ex.message}")
        listOf(
            ExchangeCatalogSnapshot(
                apiName = "hyperliquid",
                implementationStatus = "INTEGRATED",
                defaultExecutionMode = "forward_paper",
                supportedExecutionModes = listOf("backtest", "forward_paper", "testnet_live"),
                capabilities = ExchangeCapabilitiesSnapshot(
                    paperOrder = true,
                    liveOrder = true,
                    nativeOrderAdapter = true,
                    marketDataIngress = true,
                    bestQuoteDefault = true
                ),
                notes = "Fallback exchange selection"
            )
        )
    }

fun buildExchangePlans(catalog: List<ExchangeCatalogSnapshot>, config: ResearchConfig): List<ExchangePlan> {
    val overrideExchange = config.executionExchangeOverride.trim().lowercase()
    val selected = if (overrideExchange.isNotEmpty()) {
        listOf(overrideExchange)
    } else {
        catalog
            .filter {
                it.implementationStatus == "INTEGRATED" ||
                    it.capabilities.marketDataIngress ||
                    it.capabilities.bestQuoteDefault
            }
            .map { it.apiName.lowercase() }
            .distinct()
            .ifEmpty { listOf("hyperliquid") }
    }

    return selected.map { exchange ->
        val aliases = when (exchange) {
            "hyperliquid" -> when (config.marketExchange.lowercase()) {
                "hyperliquid_testnet" -> listOf("hyperliquid_testnet")
                "hyperliquid", "hyperliquid_mainnet" -> listOf("hyperliquid_mainnet", "hyperliquid")
                else -> listOf("hyperliquid_mainnet", "hyperliquid")
            }
            else -> listOf(exchange)
        }
        ExchangePlan(exchange = exchange, marketAliases = aliases.distinct())
    }
}

fun discoverSymbols(aliases: List<String>, lookbackHours: Int, maxSymbols: Int, minBars: Int): List<String> {
    val aliasSql = sqlList(aliases)
    val sql = """
        SELECT symbol,
               COUNT(*) AS bars,
               COALESCE(AVG(volume), 0) AS avg_volume
        FROM market_data
        WHERE exchange IN ($aliasSql)
          AND data_type = 'candle_1m'
          AND time >= NOW() - INTERVAL '${lookbackHours} hours'
        GROUP BY symbol
        HAVING COUNT(*) >= $minBars
        ORDER BY bars DESC, avg_volume DESC, symbol ASC
        LIMIT $maxSymbols
    """.trimIndent()

    val discovered = mutableListOf<String>()
    pgConnection().use { conn ->
        conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    discovered += rs.getString("symbol")
                }
            }
        }
    }

    val universe = (listOf("BTC", "ETH") + discovered).distinct()
    return universe
}

fun loadBars(exchange: String, aliases: List<String>, symbols: List<String>, lookbackHours: Int): List<Bar> {
    if (symbols.isEmpty()) return emptyList()
    val aliasSql = sqlList(aliases)
    val symbolSql = sqlList(symbols)
    val preferredAlias = aliases.first()
    val sql = """
        WITH candle_rows AS (
            SELECT
                symbol,
                date_trunc('minute', time) AS bucket_time,
                close,
                COALESCE(volume, 0) AS volume,
                exchange,
                time
            FROM market_data
            WHERE exchange IN ($aliasSql)
              AND data_type = 'candle_1m'
              AND time >= NOW() - INTERVAL '${lookbackHours} hours'
              AND symbol IN ($symbolSql)
        ),
        candles AS (
            SELECT DISTINCT ON (symbol, bucket_time)
                symbol,
                bucket_time,
                close,
                volume
            FROM candle_rows
            ORDER BY
                symbol,
                bucket_time,
                CASE WHEN exchange = '$preferredAlias' THEN 0 ELSE 1 END,
                time DESC
        )
        SELECT
            c.symbol,
            c.bucket_time,
            c.close,
            c.volume,
            COALESCE(o.spread_pct, 0) AS spread_pct,
            COALESCE(o.bid_depth_10, 0) AS bid_depth_10,
            COALESCE(o.ask_depth_10, 0) AS ask_depth_10,
            COALESCE(NULLIF(o.mid_price, 0), c.close) AS mid_price
        FROM candles c
        LEFT JOIN LATERAL (
            SELECT
                COALESCE(spread_pct, 0) AS spread_pct,
                COALESCE(bid_depth_10, 0) AS bid_depth_10,
                COALESCE(ask_depth_10, 0) AS ask_depth_10,
                COALESCE(NULLIF(mid_price, 0), c.close) AS mid_price
            FROM orderbook_data o
            WHERE o.symbol = c.symbol
              AND o.exchange IN ($aliasSql)
              AND o.time >= c.bucket_time
              AND o.time < c.bucket_time + INTERVAL '1 minute'
            ORDER BY
                CASE WHEN o.exchange = '$preferredAlias' THEN 0 ELSE 1 END,
                o.time DESC
            LIMIT 1
        ) o ON TRUE
        ORDER BY c.bucket_time ASC, c.symbol ASC
    """.trimIndent()

    return buildList {
        pgConnection().use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        add(
                            Bar(
                                exchange = exchange,
                                symbol = rs.getString("symbol"),
                                time = rs.getTimestamp("bucket_time").toInstant(),
                                close = rs.getDouble("close"),
                                volume = rs.getDouble("volume"),
                                spreadPct = rs.getDouble("spread_pct"),
                                bidDepth10 = rs.getDouble("bid_depth_10"),
                                askDepth10 = rs.getDouble("ask_depth_10"),
                                midPrice = rs.getDouble("mid_price")
                            )
                        )
                    }
                }
            }
        }
    }
}

fun engineerFeatures(bars: List<Bar>, config: ResearchConfig): List<FeatureRow> {
    if (bars.isEmpty()) return emptyList()

    val seriesByKey = bars.groupBy { it.exchange to it.symbol }
        .mapValues { (_, value) -> value.sortedBy { it.time } }

    val baseByKey = seriesByKey.mapValues { (key, series) ->
        val returns = ArrayDeque<Double>()
        series.mapIndexed { index, bar ->
            val previous = series.getOrNull(index - 1)
            val ret1m = if (previous == null || previous.close <= 0.0) 0.0 else (bar.close / previous.close) - 1.0
            returns.addLast(ret1m)
            if (returns.size > 30) {
                returns.removeFirst()
            }
            val vol30 = stdev(returns.toList())
            val spreadBps = max(bar.spreadPct, 0.0) * 100.0
            val midPrice = max(bar.midPrice, bar.close)
            val depthUsd = max((max(bar.bidDepth10, 0.0) + max(bar.askDepth10, 0.0)) * midPrice, 0.0)
            BasePoint(
                exchange = key.first,
                symbol = key.second,
                time = bar.time,
                barIndex = index,
                close = bar.close,
                volume = bar.volume,
                spreadPct = bar.spreadPct,
                spreadBps = spreadBps,
                bidDepth10 = bar.bidDepth10,
                askDepth10 = bar.askDepth10,
                midPrice = midPrice,
                depthUsd = depthUsd,
                ret1m = ret1m,
                vol30 = vol30
            )
        }
    }

    val baseLookup = mutableMapOf<Triple<String, String, Instant>, BasePoint>()
    baseByKey.values.flatten().forEach { point ->
        baseLookup[Triple(point.exchange, point.symbol, point.time)] = point
    }

    val unranked = mutableListOf<UnrankedFeature>()
    for ((key, series) in baseByKey) {
        val exchange = key.first
        val residuals = mutableListOf<Double>()
        val dislocations = mutableListOf<Double>()
        val volumeSeries = series.map { max(it.volume, 0.0) }
        val depthSeries = series.map { max(it.depthUsd, 0.0) }
        val volSeries = series.map { max(it.vol30, 0.0) }

        for (i in series.indices) {
            val point = series[i]
            val betaWindow = buildList {
                val start = max(0, i - config.betaLookbackBars + 1)
                for (j in start..i) {
                    val row = series[j]
                    val btc = baseLookup[Triple(exchange, "BTC", row.time)] ?: continue
                    val eth = baseLookup[Triple(exchange, "ETH", row.time)] ?: continue
                    add(Triple(row.ret1m, btc.ret1m, eth.ret1m))
                }
            }

            val (betaBtc, betaEth) = twoFactorBetas(betaWindow)
            val btcRet = baseLookup[Triple(exchange, "BTC", point.time)]?.ret1m ?: 0.0
            val ethRet = baseLookup[Triple(exchange, "ETH", point.time)]?.ret1m ?: 0.0
            val residualRet = point.ret1m - (betaBtc * btcRet) - (betaEth * ethRet)
            residuals += residualRet

            val residualMomFast = rollingSum(residuals, residuals.lastIndex, config.trendLookbackBars)
            val residualMomSlow = rollingSum(residuals, residuals.lastIndex, config.trendSlowBars)
            val dislocation = rollingSum(residuals, residuals.lastIndex, config.reversionLookbackBars)
            dislocations += dislocation
            val dislocationWindow = dislocations.subList(max(0, dislocations.size - config.betaLookbackBars), dislocations.size)
            val dislocationMean = mean(dislocationWindow)
            val dislocationStd = stdev(dislocationWindow)
            val residualZ = if (dislocationStd < 1e-9) 0.0 else (dislocation - dislocationMean) / dislocationStd
            val volumeBaseline = max(rollingMean(volumeSeries, i, 60), 1.0)
            val depthBaseline = max(rollingMean(depthSeries, i, 60), config.notionalUsd)
            val volBaseline = max(rollingMean(volSeries, i, 120), 1e-6)
            val volumeRatio = clamp(point.volume / volumeBaseline, 0.0, 8.0)
            val depthRatio = clamp(point.depthUsd / depthBaseline, 0.0, 6.0)
            val imbalance = (max(point.bidDepth10, 0.0) - max(point.askDepth10, 0.0)) /
                max(max(point.bidDepth10, 0.0) + max(point.askDepth10, 0.0), 1e-6)
            val volRegime = clamp(point.vol30 / volBaseline, 0.25, 6.0)
            val normalizedRet = clamp(point.ret1m / max(point.vol30, 1e-6), -4.0, 4.0)
            val flowSignal = clamp(
                (normalizedRet * 0.65) +
                    (imbalance * 0.95) +
                    ((clamp(volumeRatio, 0.0, 4.0) - 1.0) * 0.22),
                -3.5,
                3.5
            )
            val fastScale = max(point.vol30 * sqrt(config.trendLookbackBars.toDouble()), 1e-6)
            val slowScale = max(point.vol30 * sqrt(config.trendSlowBars.toDouble()), 1e-6)
            val rawTrend = ((residualMomFast / fastScale) * 0.7) +
                ((residualMomSlow / slowScale) * 0.3) +
                (flowSignal * 0.12) -
                (point.spreadBps / 55.0)
            val volBps = point.vol30 * 10000.0
            val trendExpectedGrossEdgeBps = clamp(
                (abs(rawTrend) * max(volBps, 4.0) * 0.55 * sqrt(config.trendHoldBars.toDouble() / 15.0)) +
                    (max(0.0, direction(rawTrend) * flowSignal) * 7.0) +
                    (max(0.0, depthRatio - 1.0) * 2.0) +
                    (max(0.0, min(volumeRatio, 3.0) - 1.0) * 2.5) -
                    (max(0.0, abs(residualZ) - 1.0) * 5.0) -
                    (max(0.0, volRegime - 1.6) * 5.0),
                0.0,
                220.0
            )
            val reversionExpectedGrossEdgeBps = clamp(
                (abs(residualZ) * max(volBps, 4.0) * 0.70 * sqrt(config.reversionHoldBars.toDouble() / 10.0)) +
                    (max(0.0, -(direction(residualZ) * flowSignal)) * 8.0) +
                    (max(0.0, depthRatio - 1.0) * 2.0) -
                    (max(0.0, abs(rawTrend) - 0.85) * 6.5) -
                    (max(0.0, volRegime - 1.45) * 6.0),
                0.0,
                220.0
            )
            val tradable = point.symbol !in setOf("BTC", "ETH")
            val liquid = point.spreadBps <= config.maxSpreadBps &&
                point.depthUsd >= config.notionalUsd * config.minDepthMultiple &&
                point.barIndex >= max(config.betaLookbackBars, config.trendSlowBars) &&
                point.volume > 0.0 &&
                volumeRatio >= config.minVolumeRatio &&
                tradable

            unranked += UnrankedFeature(
                exchange = exchange,
                symbol = point.symbol,
                time = point.time,
                barIndex = point.barIndex,
                close = point.close,
                volume = point.volume,
                spreadPct = point.spreadPct,
                spreadBps = point.spreadBps,
                depthUsd = point.depthUsd,
                midPrice = point.midPrice,
                ret1m = point.ret1m,
                vol30 = point.vol30,
                volBps = point.vol30 * 10000.0,
                btcRet1m = btcRet,
                ethRet1m = ethRet,
                betaBtc = betaBtc,
                betaEth = betaEth,
                residualRet = residualRet,
                residualMomFast = residualMomFast,
                residualMomSlow = residualMomSlow,
                residualZ = residualZ,
                imbalance = imbalance,
                volumeRatio = volumeRatio,
                depthRatio = depthRatio,
                volRegime = volRegime,
                flowSignal = flowSignal,
                rawTrend = rawTrend,
                trendExpectedGrossEdgeBps = trendExpectedGrossEdgeBps,
                reversionExpectedGrossEdgeBps = reversionExpectedGrossEdgeBps,
                liquid = liquid
            )
        }
    }

    val grouped = unranked.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val finalRows = mutableListOf<FeatureRow>()

    for (groupKey in orderedKeys) {
        val bucket = grouped[groupKey].orEmpty()
        if (bucket.isEmpty()) continue
        val breadthTilt = mean(bucket.map { clamp(it.rawTrend / 3.0, -1.0, 1.0) })
        val breadth = clamp((breadthTilt + 1.0) / 2.0, 0.0, 1.0)
        val marketStress = mean(bucket.map { it.volRegime })
        val trendScores = bucket.associateWith { row ->
            val flowAlignment = direction(row.rawTrend) * row.flowSignal
            val breadthAlignment = direction(row.rawTrend) * breadthTilt
            row.rawTrend +
                (breadthAlignment * 0.9) +
                (max(0.0, flowAlignment) * 0.65) -
                (max(0.0, -flowAlignment) * 1.0) +
                min(max(0.0, row.depthRatio - 1.0) * 0.35, 0.55) +
                (max(0.0, min(row.volumeRatio, 3.0) - 1.0) * 0.22) -
                (max(0.0, abs(row.residualZ) - 1.0) * 0.45) -
                (max(0.0, row.volRegime - 1.7) * 0.45)
        }
        val reversionScores = bucket.associateWith { row ->
            val continuationPressure = direction(row.residualZ) * row.flowSignal
            val breadthContinuation = direction(row.residualZ) * breadthTilt
            abs(row.residualZ) +
                (max(0.0, -continuationPressure) * 0.95) -
                (max(0.0, continuationPressure) * 1.25) -
                (max(0.0, abs(row.rawTrend) - 0.75) * 0.85) -
                (row.spreadBps / 35.0) -
                (max(0.0, breadthContinuation) * 1.15) -
                (max(0.0, row.volRegime - 1.55) * 0.60)
        }
        val trendExpectedEdges = bucket.associateWith { row ->
            val breadthAlignment = direction(row.rawTrend) * breadthTilt
            clamp(
                row.trendExpectedGrossEdgeBps +
                    (max(0.0, breadthAlignment) * 6.0) -
                    (max(0.0, -breadthAlignment) * 8.0) -
                    (max(0.0, marketStress - 1.6) * 5.0),
                0.0,
                220.0
            )
        }
        val reversionExpectedEdges = bucket.associateWith { row ->
            val breadthContinuation = direction(row.residualZ) * breadthTilt
            clamp(
                row.reversionExpectedGrossEdgeBps +
                    (max(0.0, -breadthContinuation) * 5.0) -
                    (max(0.0, breadthContinuation) * 7.0) -
                    (max(0.0, marketStress - 1.5) * 6.0),
                0.0,
                220.0
            )
        }
        val trendLongRanks = trendScores.entries
            .filter { it.key.liquid && (trendExpectedEdges[it.key] ?: 0.0) > 0.0 }
            .sortedByDescending { it.value }
            .mapIndexed { index, entry -> entry.key to index + 1 }
            .toMap()
        val trendShortRanks = trendScores.entries
            .filter { it.key.liquid && (trendExpectedEdges[it.key] ?: 0.0) > 0.0 }
            .sortedBy { it.value }
            .mapIndexed { index, entry -> entry.key to index + 1 }
            .toMap()
        val reversionLongRanks = bucket
            .filter { it.liquid && it.residualZ < 0.0 && (reversionExpectedEdges[it] ?: 0.0) > 0.0 }
            .sortedBy { it.residualZ }
            .mapIndexed { index, row -> row to index + 1 }
            .toMap()
        val reversionShortRanks = bucket
            .filter { it.liquid && it.residualZ > 0.0 && (reversionExpectedEdges[it] ?: 0.0) > 0.0 }
            .sortedByDescending { it.residualZ }
            .mapIndexed { index, row -> row to index + 1 }
            .toMap()

        bucket.forEach { row ->
            finalRows += FeatureRow(
                exchange = row.exchange,
                symbol = row.symbol,
                time = row.time,
                barIndex = row.barIndex,
                close = row.close,
                volume = row.volume,
                spreadPct = row.spreadPct,
                spreadBps = row.spreadBps,
                depthUsd = row.depthUsd,
                midPrice = row.midPrice,
                ret1m = row.ret1m,
                vol30 = row.vol30,
                volBps = row.volBps,
                btcRet1m = row.btcRet1m,
                ethRet1m = row.ethRet1m,
                betaBtc = row.betaBtc,
                betaEth = row.betaEth,
                residualRet = row.residualRet,
                residualMomFast = row.residualMomFast,
                residualMomSlow = row.residualMomSlow,
                residualZ = row.residualZ,
                imbalance = row.imbalance,
                volumeRatio = row.volumeRatio,
                depthRatio = row.depthRatio,
                volRegime = row.volRegime,
                flowSignal = row.flowSignal,
                breadth = breadth,
                rawTrend = row.rawTrend,
                trendScore = trendScores[row] ?: row.rawTrend,
                reversionScore = reversionScores[row] ?: 0.0,
                trendExpectedGrossEdgeBps = trendExpectedEdges[row] ?: row.trendExpectedGrossEdgeBps,
                reversionExpectedGrossEdgeBps = reversionExpectedEdges[row] ?: row.reversionExpectedGrossEdgeBps,
                liquid = row.liquid,
                trendLongRank = trendLongRanks[row] ?: Int.MAX_VALUE,
                trendShortRank = trendShortRanks[row] ?: Int.MAX_VALUE,
                reversionLongRank = reversionLongRanks[row] ?: Int.MAX_VALUE,
                reversionShortRank = reversionShortRanks[row] ?: Int.MAX_VALUE
            )
        }
    }

    return finalRows.sortedWith(compareBy<FeatureRow> { it.time }.thenBy { it.exchange }.thenBy { it.symbol })
}

fun latestSignalSnapshots(rows: List<FeatureRow>, config: ResearchConfig): List<SignalSnapshot> =
    rows.groupBy { it.exchange to it.symbol }
        .values
        .mapNotNull { series -> series.maxByOrNull { it.time } }
        .map { row ->
            val trendLong = if (
                row.liquid &&
                    row.trendLongRank <= config.topPerSide &&
                    row.trendScore >= config.trendEntryScore
            ) {
                buildEntryCandidate(StrategyKind.TREND, row, 1, config)
            } else {
                null
            }
            val trendShort = if (
                row.liquid &&
                    row.trendShortRank <= config.topPerSide &&
                    row.trendScore <= -config.trendEntryScore
            ) {
                buildEntryCandidate(StrategyKind.TREND, row, -1, config)
            } else {
                null
            }
            val reversionLong = if (
                row.liquid &&
                    row.reversionLongRank <= config.topPerSide &&
                    row.residualZ <= -config.reversionZEntry &&
                    row.reversionScore > 0.0
            ) {
                buildEntryCandidate(StrategyKind.REVERSION, row, 1, config)
            } else {
                null
            }
            val reversionShort = if (
                row.liquid &&
                    row.reversionShortRank <= config.topPerSide &&
                    row.residualZ >= config.reversionZEntry &&
                    row.reversionScore > 0.0
            ) {
                buildEntryCandidate(StrategyKind.REVERSION, row, -1, config)
            } else {
                null
            }
            val trendCandidate = listOfNotNull(trendLong, trendShort).maxByOrNull { it.expectedNetEdgeBps }
            val reversionCandidate = listOfNotNull(reversionLong, reversionShort).maxByOrNull { it.expectedNetEdgeBps }
            val trendAction = when (trendCandidate?.side) {
                1 -> "LONG"
                -1 -> "SHORT"
                else -> "FLAT"
            }
            val reversionAction = when (reversionCandidate?.side) {
                1 -> "LONG"
                -1 -> "SHORT"
                else -> "FLAT"
            }
            SignalSnapshot(
                exchange = row.exchange,
                symbol = row.symbol,
                time = row.time.toString(),
                lastPrice = row.close.round(4),
                betaBtc = row.betaBtc.round(4),
                betaEth = row.betaEth.round(4),
                residualZ = row.residualZ.round(4),
                trendScore = row.trendScore.round(4),
                reversionScore = row.reversionScore.round(4),
                breadth = row.breadth.round(4),
                spreadBps = row.spreadBps.round(2),
                depthUsd = row.depthUsd.round(2),
                imbalance = row.imbalance.round(4),
                flowSignal = row.flowSignal.round(4),
                volumeRatio = row.volumeRatio.round(4),
                volRegime = row.volRegime.round(4),
                trendExpectedNetEdgeBps = (trendCandidate?.expectedNetEdgeBps ?: 0.0).round(2),
                reversionExpectedNetEdgeBps = (reversionCandidate?.expectedNetEdgeBps ?: 0.0).round(2),
                liquid = row.liquid,
                trendAction = trendAction,
                reversionAction = reversionAction
            )
        }
        .sortedWith(
            compareByDescending<SignalSnapshot> { max(it.trendExpectedNetEdgeBps, it.reversionExpectedNetEdgeBps) }
                .thenByDescending { abs(it.trendScore) }
                .thenBy { it.exchange }
                .thenBy { it.symbol }
        )

fun buildExecutionEstimate(row: FeatureRow, notionalUsd: Double, side: Int, kind: StrategyKind): ExecutionEstimate {
    val spreadHalfBps = row.spreadBps / 2.0
    val depthPressure = notionalUsd / max(row.depthUsd, notionalUsd)
    val volatilityPenalty = min(row.volBps / 70.0, 4.0)
    val imbalanceAgainstTrade = max(0.0, -side.toDouble() * row.imbalance)
    val imbalanceWithTrade = max(0.0, side.toDouble() * row.imbalance)
    val flowAgainstTrade = max(0.0, -side.toDouble() * row.flowSignal)
    val makerFeeBps = 1.0
    val takerFeeBps = 4.0
    val makerShare = clamp(
        (if (kind == StrategyKind.REVERSION) 0.62 else 0.52) -
            (depthPressure * 0.32) -
            (volatilityPenalty * 0.03) -
            (flowAgainstTrade * 0.05) +
            (imbalanceWithTrade * 0.05) -
            (spreadHalfBps / 45.0),
        0.12,
        0.88
    )
    val feeBps = (makerFeeBps * makerShare) + (takerFeeBps * (1.0 - makerShare))
    val fillRatio = clamp(
        0.96 -
            (spreadHalfBps / 30.0) -
            (depthPressure * 2.8) -
            (volatilityPenalty * 0.04) -
            (flowAgainstTrade * 0.05) -
            (imbalanceAgainstTrade * 0.03) +
            (imbalanceWithTrade * 0.02),
        0.25,
        1.0
    )
    val slippageBps = clamp(
        0.20 +
            (spreadHalfBps * 0.18) +
            (depthPressure * 9.0) +
            (volatilityPenalty * 0.70) +
            (flowAgainstTrade * 1.9) +
            (imbalanceAgainstTrade * 0.8) -
            (makerShare * 0.70),
        0.15,
        18.0
    )
    val impactBps = clamp(
        0.15 +
            (depthPressure * 14.0) +
            (max(0.0, row.volumeRatio - 2.0) * 0.9) +
            (max(0.0, row.volRegime - 1.3) * 1.8),
        0.10,
        22.0
    )
    val adverseSelectionBps = clamp(
        (abs(row.residualZ) * 0.35) +
            (flowAgainstTrade * 2.5) +
            (max(0.0, row.volRegime - 1.0) * 2.2) +
            (max(0.0, abs(row.rawTrend) - 1.5) * 1.2),
        0.0,
        10.0
    )
    val totalCostBps = feeBps + spreadHalfBps + slippageBps + impactBps + adverseSelectionBps
    return ExecutionEstimate(
        fillRatio = fillRatio,
        feeBps = feeBps,
        feeTier = if (makerShare >= 0.5) "retail_mixed_maker_bias" else "retail_mixed_taker_bias",
        feeTierAdjustmentBps = (feeBps - takerFeeBps).round(4),
        makerFeeBps = makerFeeBps,
        takerFeeBps = takerFeeBps,
        spreadCostBps = spreadHalfBps,
        slippageBps = slippageBps,
        impactBps = impactBps,
        adverseSelectionBps = adverseSelectionBps,
        fundingDriftBps = 0.0,
        basisDriftBps = 0.0,
        totalCostBps = totalCostBps,
        estimatedFeeUsd = notionalUsd * feeBps / 10000.0,
        estimatedCostUsd = notionalUsd * totalCostBps / 10000.0
    )
}

fun buildExpectedRoundTripCostBps(row: FeatureRow, entryEstimate: ExecutionEstimate): Double {
    val regimeBuffer = max(0.0, row.volRegime - 1.0) * 1.75
    val flowBuffer = max(0.0, abs(row.flowSignal) - 0.8) * 1.40
    return (entryEstimate.totalCostBps * 2.0) + regimeBuffer + flowBuffer
}

fun buildEntryCandidate(kind: StrategyKind, row: FeatureRow, side: Int, config: ResearchConfig): EntryCandidate? {
    if (!row.liquid) return null
    if (row.volumeRatio < config.minVolumeRatio || row.volumeRatio > config.maxVolumeRatio) return null
    if (row.volRegime > config.maxVolRegime) return null

    val entryEstimate = buildExecutionEstimate(row, config.notionalUsd, side, kind)
    if (entryEstimate.fillRatio < config.minFillRatio) return null

    val expectedGrossEdgeBps = when (kind) {
        StrategyKind.TREND -> row.trendExpectedGrossEdgeBps
        StrategyKind.REVERSION -> row.reversionExpectedGrossEdgeBps
    }
    val expectedRoundTripCostBps = buildExpectedRoundTripCostBps(row, entryEstimate)
    val expectedNetEdgeBps = expectedGrossEdgeBps - expectedRoundTripCostBps
    val safetyMarginBps = config.executionSafetyMarginBps + (max(0.0, row.volRegime - 1.0) * 2.5)
    val breadthTilt = (row.breadth - 0.5) * 2.0
    val continuationPressure = direction(row.residualZ) * row.flowSignal

    if (expectedNetEdgeBps < config.minExpectedNetEdgeBps) return null
    if (expectedGrossEdgeBps < expectedRoundTripCostBps + safetyMarginBps) return null

    when (kind) {
        StrategyKind.TREND -> {
            val flowAlignment = side.toDouble() * row.flowSignal
            val breadthAlignment = side.toDouble() * breadthTilt
            if (flowAlignment < config.trendMinFlowAlignment) return null
            if (breadthAlignment < -0.08) return null
            if (abs(row.residualZ) > config.reversionZEntry * 1.15) return null
            if (abs(row.rawTrend) < config.trendEntryScore) return null
        }
        StrategyKind.REVERSION -> {
            if (abs(row.rawTrend) > config.trendEntryScore * 1.30) return null
            if (continuationPressure > config.reversionMaxContinuationPressure) return null
            if (direction(row.residualZ) * breadthTilt > 0.22) return null
            if ((side.toDouble() * row.flowSignal) < -0.35) return null
        }
    }

    return EntryCandidate(
        row = row,
        side = side,
        entryEstimate = entryEstimate,
        expectedGrossEdgeBps = expectedGrossEdgeBps,
        expectedRoundTripCostBps = expectedRoundTripCostBps,
        expectedNetEdgeBps = expectedNetEdgeBps
    )
}

fun shouldExitPosition(kind: StrategyKind, position: OpenPosition, current: FeatureRow, config: ResearchConfig): Boolean =
    when (kind) {
        StrategyKind.TREND -> {
            val ageBars = current.barIndex - position.entryRow.barIndex
            ageBars >= config.trendHoldBars ||
                (current.trendScore * position.side.toDouble()) <= 0.12 ||
                (position.side.toDouble() * current.flowSignal) < -0.18 ||
                current.volRegime > (config.maxVolRegime * 1.15)
        }
        StrategyKind.REVERSION -> {
            val ageBars = current.barIndex - position.entryRow.barIndex
            ageBars >= config.reversionHoldBars ||
                abs(current.residualZ) <= config.reversionZExit ||
                (current.residualZ * position.side.toDouble()) >= -0.05 ||
                (direction(position.entryRow.residualZ) * current.flowSignal) > config.reversionMaxContinuationPressure
        }
    }

fun candidateRows(kind: StrategyKind, bucket: List<FeatureRow>, config: ResearchConfig): List<EntryCandidate> =
    when (kind) {
        StrategyKind.TREND -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.trendLongRank <= config.topPerSide &&
                        it.trendScore >= config.trendEntryScore &&
                        abs(it.residualZ) <= config.reversionZEntry * 1.5
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.TREND, it, 1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.trendShortRank <= config.topPerSide &&
                        it.trendScore <= -config.trendEntryScore &&
                        abs(it.residualZ) <= config.reversionZEntry * 1.5
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.TREND, it, -1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.trendShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
        StrategyKind.REVERSION -> {
            val longs = bucket
                .filter {
                    it.liquid &&
                        it.reversionLongRank <= config.topPerSide &&
                        it.residualZ <= -config.reversionZEntry &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.REVERSION, it, 1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionLongRank })
            val shorts = bucket
                .filter {
                    it.liquid &&
                        it.reversionShortRank <= config.topPerSide &&
                        it.residualZ >= config.reversionZEntry &&
                        it.reversionScore > 0.0
                }
                .mapNotNull { buildEntryCandidate(StrategyKind.REVERSION, it, -1, config) }
                .sortedWith(compareByDescending<EntryCandidate> { it.expectedNetEdgeBps }.thenBy { it.row.reversionShortRank })
            (longs + shorts).sortedByDescending { it.expectedNetEdgeBps }
        }
    }

fun simulateStrategy(strategyName: String, kind: StrategyKind, rows: List<FeatureRow>, config: ResearchConfig): List<TradeRecord> {
    if (rows.isEmpty()) return emptyList()
    val grouped = rows.groupBy { it.exchange to it.time }
    val orderedKeys = grouped.keys.sortedWith(compareBy<Pair<String, Instant>> { it.second }.thenBy { it.first })
    val positions = mutableMapOf<String, OpenPosition>()
    val cooldownUntilBar = mutableMapOf<String, Int>()
    val trades = mutableListOf<TradeRecord>()

    for (key in orderedKeys) {
        val exchange = key.first
        val bucket = grouped[key].orEmpty()
        val rowBySymbol = bucket.associateBy { it.symbol }

        for ((positionKey, position) in positions.toMap()) {
            if (position.exchange != exchange) continue
            val current = rowBySymbol[position.symbol] ?: continue
            if (!shouldExitPosition(kind, position, current, config)) continue

            val exitEstimate = buildExecutionEstimate(current, config.notionalUsd, -position.side, kind)
            val effectiveFill = min(position.entryEstimate.fillRatio, exitEstimate.fillRatio)
            val grossReturn = position.side * ((current.close / position.entryRow.close) - 1.0) * effectiveFill
            val totalCostBps = position.entryEstimate.totalCostBps + exitEstimate.totalCostBps
            val netReturn = grossReturn - (totalCostBps / 10000.0)
            val signalMagnitude = when (kind) {
                StrategyKind.TREND -> abs(position.entryRow.trendScore)
                StrategyKind.REVERSION -> abs(position.entryRow.residualZ)
            }
            val jitter = deterministicJitter(current.time, position.side)
            val decisionLatencyMs = clamp(6.0 + (signalMagnitude * 7.0) + (jitter * 0.6), 4.0, 60.0)
            val submitToAckMs = clamp(55.0 + (current.spreadBps * 1.1) + ((config.notionalUsd / max(current.depthUsd, config.notionalUsd)) * 120.0) + (jitter * 1.5), 20.0, 900.0)
            val submitToFillMs = clamp(submitToAckMs + ((1.0 - effectiveFill) * 260.0) + 18.0, 30.0, 1800.0)
            val p50RoundtripMs = clamp(submitToAckMs + 12.0, 20.0, 1000.0)
            val p95RoundtripMs = clamp(submitToAckMs * 2.0, 25.0, 1800.0)
            val p99RoundtripMs = clamp(submitToAckMs * 3.0, 30.0, 2500.0)

            trades += TradeRecord(
                strategyName = strategyName,
                strategyKind = kind.name.lowercase(),
                exchange = exchange,
                symbol = position.symbol,
                side = if (position.side > 0) "BUY" else "SELL",
                entryTime = position.entryRow.time,
                exitTime = current.time,
                entryPrice = position.entryRow.close,
                exitPrice = current.close,
                holdBars = current.barIndex - position.entryRow.barIndex,
                grossReturnFraction = grossReturn,
                netReturnFraction = netReturn,
                fillRatio = effectiveFill,
                feeBps = position.entryEstimate.feeBps + exitEstimate.feeBps,
                feeTier = position.entryEstimate.feeTier,
                feeTierAdjustmentBps = position.entryEstimate.feeTierAdjustmentBps + exitEstimate.feeTierAdjustmentBps,
                makerFeeBps = position.entryEstimate.makerFeeBps + exitEstimate.makerFeeBps,
                takerFeeBps = position.entryEstimate.takerFeeBps + exitEstimate.takerFeeBps,
                spreadCostBps = position.entryEstimate.spreadCostBps + exitEstimate.spreadCostBps,
                slippageBps = position.entryEstimate.slippageBps + exitEstimate.slippageBps,
                impactBps = position.entryEstimate.impactBps + exitEstimate.impactBps,
                adverseSelectionBps = position.entryEstimate.adverseSelectionBps + exitEstimate.adverseSelectionBps,
                fundingDriftBps = 0.0,
                basisDriftBps = 0.0,
                totalCostBps = totalCostBps,
                edgeAfterCostBps = netReturn * 10000.0,
                estimatedFeeUsd = position.entryEstimate.estimatedFeeUsd + exitEstimate.estimatedFeeUsd,
                estimatedCostUsd = position.entryEstimate.estimatedCostUsd + exitEstimate.estimatedCostUsd,
                entryTrendScore = position.entryRow.trendScore,
                entryResidualZ = position.entryRow.residualZ,
                expectedGrossEdgeBps = position.expectedGrossEdgeBps,
                expectedRoundTripCostBps = position.expectedRoundTripCostBps,
                expectedNetEdgeBps = position.expectedNetEdgeBps,
                entryImbalance = position.entryRow.imbalance,
                entryFlowSignal = position.entryRow.flowSignal,
                entryVolumeRatio = position.entryRow.volumeRatio,
                entryVolRegime = position.entryRow.volRegime,
                betaBtc = position.entryRow.betaBtc,
                betaEth = position.entryRow.betaEth,
                decisionLatencyMs = decisionLatencyMs,
                submitToAckMs = submitToAckMs,
                submitToFillMs = submitToFillMs,
                p50RoundtripMs = p50RoundtripMs,
                p95RoundtripMs = p95RoundtripMs,
                p99RoundtripMs = p99RoundtripMs,
                jitterMs = jitter
            )
            positions.remove(positionKey)
            cooldownUntilBar[positionKey] = current.barIndex + when (kind) {
                StrategyKind.TREND -> config.trendCooldownBars
                StrategyKind.REVERSION -> config.reversionCooldownBars
            }
        }

        candidateRows(kind, bucket, config).forEach { candidate ->
            val positionKey = "${candidate.row.exchange}|${candidate.row.symbol}"
            if (positions.containsKey(positionKey)) return@forEach
            if ((cooldownUntilBar[positionKey] ?: Int.MIN_VALUE) > candidate.row.barIndex) return@forEach
            positions[positionKey] = OpenPosition(
                strategyName = strategyName,
                strategyKind = kind,
                exchange = candidate.row.exchange,
                symbol = candidate.row.symbol,
                side = candidate.side,
                entryRow = candidate.row,
                entryEstimate = candidate.entryEstimate,
                expectedGrossEdgeBps = candidate.expectedGrossEdgeBps,
                expectedRoundTripCostBps = candidate.expectedRoundTripCostBps,
                expectedNetEdgeBps = candidate.expectedNetEdgeBps
            )
        }
    }

    val latestByExchangeSymbol = rows.groupBy { it.exchange to it.symbol }
        .mapValues { (_, series) -> series.maxByOrNull { it.time } }

    for ((positionKey, position) in positions.toMap()) {
        val current = latestByExchangeSymbol[position.exchange to position.symbol] ?: continue
        if (current.time == position.entryRow.time) continue
        val exitEstimate = buildExecutionEstimate(current, config.notionalUsd, -position.side, kind)
        val effectiveFill = min(position.entryEstimate.fillRatio, exitEstimate.fillRatio)
        val grossReturn = position.side * ((current.close / position.entryRow.close) - 1.0) * effectiveFill
        val totalCostBps = position.entryEstimate.totalCostBps + exitEstimate.totalCostBps
        val netReturn = grossReturn - (totalCostBps / 10000.0)
        val signalMagnitude = when (kind) {
            StrategyKind.TREND -> abs(position.entryRow.trendScore)
            StrategyKind.REVERSION -> abs(position.entryRow.residualZ)
        }
        val jitter = deterministicJitter(current.time, position.side)
        val decisionLatencyMs = clamp(6.0 + (signalMagnitude * 7.0) + (jitter * 0.6), 4.0, 60.0)
        val submitToAckMs = clamp(55.0 + (current.spreadBps * 1.1) + ((config.notionalUsd / max(current.depthUsd, config.notionalUsd)) * 120.0) + (jitter * 1.5), 20.0, 900.0)
        val submitToFillMs = clamp(submitToAckMs + ((1.0 - effectiveFill) * 260.0) + 18.0, 30.0, 1800.0)
        val p50RoundtripMs = clamp(submitToAckMs + 12.0, 20.0, 1000.0)
        val p95RoundtripMs = clamp(submitToAckMs * 2.0, 25.0, 1800.0)
        val p99RoundtripMs = clamp(submitToAckMs * 3.0, 30.0, 2500.0)

        trades += TradeRecord(
            strategyName = strategyName,
            strategyKind = kind.name.lowercase(),
            exchange = position.exchange,
            symbol = position.symbol,
            side = if (position.side > 0) "BUY" else "SELL",
            entryTime = position.entryRow.time,
            exitTime = current.time,
            entryPrice = position.entryRow.close,
            exitPrice = current.close,
            holdBars = current.barIndex - position.entryRow.barIndex,
            grossReturnFraction = grossReturn,
            netReturnFraction = netReturn,
            fillRatio = effectiveFill,
            feeBps = position.entryEstimate.feeBps + exitEstimate.feeBps,
            feeTier = position.entryEstimate.feeTier,
            feeTierAdjustmentBps = position.entryEstimate.feeTierAdjustmentBps + exitEstimate.feeTierAdjustmentBps,
            makerFeeBps = position.entryEstimate.makerFeeBps + exitEstimate.makerFeeBps,
            takerFeeBps = position.entryEstimate.takerFeeBps + exitEstimate.takerFeeBps,
            spreadCostBps = position.entryEstimate.spreadCostBps + exitEstimate.spreadCostBps,
            slippageBps = position.entryEstimate.slippageBps + exitEstimate.slippageBps,
            impactBps = position.entryEstimate.impactBps + exitEstimate.impactBps,
            adverseSelectionBps = position.entryEstimate.adverseSelectionBps + exitEstimate.adverseSelectionBps,
            fundingDriftBps = 0.0,
            basisDriftBps = 0.0,
            totalCostBps = totalCostBps,
            edgeAfterCostBps = netReturn * 10000.0,
            estimatedFeeUsd = position.entryEstimate.estimatedFeeUsd + exitEstimate.estimatedFeeUsd,
            estimatedCostUsd = position.entryEstimate.estimatedCostUsd + exitEstimate.estimatedCostUsd,
            entryTrendScore = position.entryRow.trendScore,
            entryResidualZ = position.entryRow.residualZ,
            expectedGrossEdgeBps = position.expectedGrossEdgeBps,
            expectedRoundTripCostBps = position.expectedRoundTripCostBps,
            expectedNetEdgeBps = position.expectedNetEdgeBps,
            entryImbalance = position.entryRow.imbalance,
            entryFlowSignal = position.entryRow.flowSignal,
            entryVolumeRatio = position.entryRow.volumeRatio,
            entryVolRegime = position.entryRow.volRegime,
            betaBtc = position.entryRow.betaBtc,
            betaEth = position.entryRow.betaEth,
            decisionLatencyMs = decisionLatencyMs,
            submitToAckMs = submitToAckMs,
            submitToFillMs = submitToFillMs,
            p50RoundtripMs = p50RoundtripMs,
            p95RoundtripMs = p95RoundtripMs,
            p99RoundtripMs = p99RoundtripMs,
            jitterMs = jitter
        )
        positions.remove(positionKey)
    }

    return trades.sortedBy { it.entryTime }
}

fun buildStrategySummaries(
    strategyName: String,
    strategyKind: StrategyKind,
    trades: List<TradeRecord>,
    timeframe: String,
    notes: String
): List<StrategySummary> {
    if (trades.isEmpty()) return emptyList()

    fun summarize(exchange: String, symbol: String, bucket: List<TradeRecord>): StrategySummary {
        val sorted = bucket.sortedBy { it.entryTime }
        var equity = 1.0
        var peak = 1.0
        var maxDrawdown = 0.0
        val returns = mutableListOf<Double>()
        sorted.forEach { trade ->
            equity *= (1.0 + trade.netReturnFraction)
            peak = max(peak, equity)
            maxDrawdown = max(maxDrawdown, 1.0 - (equity / peak))
            returns += trade.netReturnFraction
        }
        val netReturnPct = ((equity - 1.0) * 100.0).round(4)
        val winRate = sorted.count { it.netReturnFraction > 0.0 }.toDouble() / sorted.size.toDouble()
        val sharpe = run {
            val sigma = stdev(returns)
            if (sigma < 1e-9) 0.0 else (mean(returns) / sigma) * sqrt(sorted.size.toDouble())
        }
        val avgEdgeAfterCostBps = mean(sorted.map { it.edgeAfterCostBps })
        val avgTotalCostBps = mean(sorted.map { it.totalCostBps })
        val avgSlippageBps = mean(sorted.map { it.slippageBps })
        val avgFillRatio = mean(sorted.map { it.fillRatio })
        val avgSubmitToFillMs = mean(sorted.map { it.submitToFillMs })
        val avgBetaBtc = mean(sorted.map { it.betaBtc })
        val avgBetaEth = mean(sorted.map { it.betaEth })
        val avgExpectedGrossEdgeBps = mean(sorted.map { it.expectedGrossEdgeBps })
        val avgExpectedNetEdgeBps = mean(sorted.map { it.expectedNetEdgeBps })
        val edgePredictionErrorBps = avgEdgeAfterCostBps - avgExpectedNetEdgeBps
        val avgEntryImbalance = mean(sorted.map { it.entryImbalance })
        val avgEntryFlowSignal = mean(sorted.map { it.entryFlowSignal })
        val avgEntryVolumeRatio = mean(sorted.map { it.entryVolumeRatio })
        val avgEntryVolRegime = mean(sorted.map { it.entryVolRegime })
        val metricsJson = gson.toJson(
            mapOf(
                "exchange" to exchange,
                "strategyKind" to strategyKind.name.lowercase(),
                "avg_edge_after_cost_bps" to avgEdgeAfterCostBps.round(4),
                "avg_expected_gross_edge_bps" to avgExpectedGrossEdgeBps.round(4),
                "avg_expected_net_edge_bps" to avgExpectedNetEdgeBps.round(4),
                "edge_prediction_error_bps" to edgePredictionErrorBps.round(4),
                "avg_total_cost_bps" to avgTotalCostBps.round(4),
                "avg_slippage_bps" to avgSlippageBps.round(4),
                "avg_fill_ratio" to avgFillRatio.round(4),
                "avg_submit_to_fill_ms" to avgSubmitToFillMs.round(4),
                "avg_beta_btc" to avgBetaBtc.round(4),
                "avg_beta_eth" to avgBetaEth.round(4),
                "avg_entry_imbalance" to avgEntryImbalance.round(4),
                "avg_entry_flow_signal" to avgEntryFlowSignal.round(4),
                "avg_entry_volume_ratio" to avgEntryVolumeRatio.round(4),
                "avg_entry_vol_regime" to avgEntryVolRegime.round(4),
                "source" to "cross-sectional-beta-kotlin"
            )
        )
        return StrategySummary(
            strategyName = strategyName,
            strategyKind = strategyKind.name.lowercase(),
            exchange = exchange,
            symbol = symbol,
            timeframe = timeframe,
            startTime = sorted.first().entryTime,
            endTime = sorted.last().exitTime,
            trades = sorted.size,
            winRate = winRate,
            netReturnPct = netReturnPct,
            maxDrawdownPct = (maxDrawdown * 100.0).round(4),
            sharpe = sharpe.round(4),
            avgEdgeAfterCostBps = avgEdgeAfterCostBps.round(4),
            avgTotalCostBps = avgTotalCostBps.round(4),
            avgSlippageBps = avgSlippageBps.round(4),
            avgFillRatio = avgFillRatio.round(4),
            avgSubmitToFillMs = avgSubmitToFillMs.round(4),
            notes = notes,
            metricsJson = metricsJson
        )
    }

    val perSymbol = trades.groupBy { it.exchange to it.symbol }
        .map { (key, bucket) -> summarize(key.first, key.second, bucket) }
    val perExchange = trades.groupBy { it.exchange }
        .map { (exchange, bucket) -> summarize(exchange, "ALL", bucket) }
    return perSymbol + perExchange
}

fun ensureAnalyticsTables(conn: Connection) {
    conn.createStatement().use { stmt ->
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_backtest_runs (
                id BIGSERIAL PRIMARY KEY,
                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                symbol TEXT NOT NULL,
                timeframe TEXT NOT NULL,
                start_time TIMESTAMPTZ NOT NULL,
                end_time TIMESTAMPTZ NOT NULL,
                trades INTEGER NOT NULL DEFAULT 0,
                win_rate DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                net_return_pct DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                max_drawdown_pct DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                sharpe DOUBLE PRECISION NOT NULL DEFAULT 0.0,
                notes TEXT,
                metrics JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_backtest_run_at ON strategy_backtest_runs (run_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_backtest_symbol_time ON strategy_backtest_runs (symbol, timeframe, run_at DESC)")
        stmt.execute(
            """
            CREATE UNIQUE INDEX IF NOT EXISTS idx_strategy_backtest_dedupe
            ON strategy_backtest_runs (strategy_name, symbol, timeframe, start_time, end_time)
            """.trimIndent()
        )
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_latency_metrics (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                decision_latency_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
                submit_to_ack_ms DOUBLE PRECISION NOT NULL DEFAULT 0,
                submit_to_fill_ms DOUBLE PRECISION,
                p50_roundtrip_ms DOUBLE PRECISION,
                p95_roundtrip_ms DOUBLE PRECISION,
                p99_roundtrip_ms DOUBLE PRECISION,
                jitter_ms DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_latency_time ON strategy_latency_metrics (observed_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_latency_strategy_time ON strategy_latency_metrics (strategy_name, observed_at DESC)")
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_execution_costs (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                exchange TEXT NOT NULL,
                symbol TEXT NOT NULL,
                side TEXT NOT NULL,
                fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                fee_tier TEXT NOT NULL DEFAULT 'retail',
                fee_tier_adjustment_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                maker_fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                taker_fee_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                spread_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                slippage_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                impact_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                adverse_selection_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                funding_drift_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                basis_drift_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                total_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                edge_after_cost_bps DOUBLE PRECISION NOT NULL DEFAULT 0,
                estimated_fee_usd DOUBLE PRECISION,
                estimated_cost_usd DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_time ON strategy_execution_costs (observed_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_execution_costs_strategy_time ON strategy_execution_costs (strategy_name, observed_at DESC)")
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS strategy_live_backtest_drift (
                id BIGSERIAL PRIMARY KEY,
                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                strategy_name TEXT NOT NULL,
                symbol TEXT NOT NULL,
                live_edge_bps DOUBLE PRECISION,
                backtest_edge_bps DOUBLE PRECISION,
                fill_quality_delta_bps DOUBLE PRECISION,
                slippage_drift_bps DOUBLE PRECISION,
                latency_drift_ms DOUBLE PRECISION,
                drift_score DOUBLE PRECISION,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb
            )
            """.trimIndent()
        )
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_drift_time ON strategy_live_backtest_drift (observed_at DESC)")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_strategy_drift_strategy_time ON strategy_live_backtest_drift (strategy_name, observed_at DESC)")
    }
}

fun persistBacktestSummaries(summaries: List<StrategySummary>) {
    if (summaries.isEmpty()) return
    pgConnection().use { conn ->
        ensureAnalyticsTables(conn)
        conn.prepareStatement(
            """
            INSERT INTO strategy_backtest_runs (
                strategy_name, symbol, timeframe, start_time, end_time,
                trades, win_rate, net_return_pct, max_drawdown_pct, sharpe, notes, metrics
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            ON CONFLICT (strategy_name, symbol, timeframe, start_time, end_time)
            DO UPDATE SET
                trades = EXCLUDED.trades,
                win_rate = EXCLUDED.win_rate,
                net_return_pct = EXCLUDED.net_return_pct,
                max_drawdown_pct = EXCLUDED.max_drawdown_pct,
                sharpe = EXCLUDED.sharpe,
                notes = EXCLUDED.notes,
                metrics = EXCLUDED.metrics,
                run_at = NOW()
            """.trimIndent()
        ).use { stmt ->
            summaries.forEach { summary ->
                stmt.setString(1, summary.strategyName)
                stmt.setString(2, summary.symbol)
                stmt.setString(3, summary.timeframe)
                stmt.setTimestamp(4, Timestamp.from(summary.startTime))
                stmt.setTimestamp(5, Timestamp.from(summary.endTime))
                stmt.setInt(6, summary.trades)
                stmt.setDouble(7, summary.winRate)
                stmt.setDouble(8, summary.netReturnPct)
                stmt.setDouble(9, summary.maxDrawdownPct)
                stmt.setDouble(10, summary.sharpe)
                stmt.setString(11, summary.notes)
                stmt.setString(12, summary.metricsJson)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }
}

fun persistForwardTelemetry(trades: List<TradeRecord>, baselines: Map<Triple<String, String, String>, StrategySummary>, source: String) {
    if (trades.isEmpty()) return
    pgConnection().use { conn ->
        ensureAnalyticsTables(conn)
        conn.autoCommit = false

        val grouped = trades.groupBy { Triple(it.strategyName, it.exchange, it.symbol) }
        grouped.forEach { (scope, bucket) ->
            val firstObservedAt = bucket.minOf { it.entryTime }
            val lastObservedAt = bucket.maxOf { it.entryTime }
            conn.prepareStatement(
                """
                DELETE FROM strategy_execution_costs
                WHERE strategy_name = ? AND exchange = ? AND symbol = ?
                  AND observed_at BETWEEN ? AND ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, scope.first)
                stmt.setString(2, scope.second)
                stmt.setString(3, scope.third)
                stmt.setTimestamp(4, Timestamp.from(firstObservedAt))
                stmt.setTimestamp(5, Timestamp.from(lastObservedAt))
                stmt.executeUpdate()
            }
            conn.prepareStatement(
                """
                DELETE FROM strategy_latency_metrics
                WHERE strategy_name = ? AND exchange = ? AND symbol = ?
                  AND observed_at BETWEEN ? AND ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, scope.first)
                stmt.setString(2, scope.second)
                stmt.setString(3, scope.third)
                stmt.setTimestamp(4, Timestamp.from(firstObservedAt))
                stmt.setTimestamp(5, Timestamp.from(lastObservedAt))
                stmt.executeUpdate()
            }
            conn.prepareStatement(
                """
                DELETE FROM strategy_live_backtest_drift
                WHERE strategy_name = ? AND symbol = ?
                  AND observed_at BETWEEN ? AND ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, scope.first)
                stmt.setString(2, scope.third)
                stmt.setTimestamp(3, Timestamp.from(firstObservedAt))
                stmt.setTimestamp(4, Timestamp.from(lastObservedAt))
                stmt.executeUpdate()
            }
        }

        conn.prepareStatement(
            """
            INSERT INTO strategy_latency_metrics (
                observed_at, strategy_name, exchange, symbol,
                decision_latency_ms, submit_to_ack_ms, submit_to_fill_ms,
                p50_roundtrip_ms, p95_roundtrip_ms, p99_roundtrip_ms,
                jitter_ms, metadata
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent()
        ).use { latencyStmt ->
            conn.prepareStatement(
                """
                INSERT INTO strategy_execution_costs (
                    observed_at, strategy_name, exchange, symbol, side,
                    fee_bps, fee_tier, fee_tier_adjustment_bps,
                    maker_fee_bps, taker_fee_bps,
                    spread_cost_bps, slippage_bps, impact_bps,
                    adverse_selection_bps, funding_drift_bps, basis_drift_bps,
                    total_cost_bps, edge_after_cost_bps,
                    estimated_fee_usd, estimated_cost_usd, metadata
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                """.trimIndent()
            ).use { costStmt ->
                conn.prepareStatement(
                    """
                    INSERT INTO strategy_live_backtest_drift (
                        observed_at, strategy_name, symbol,
                        live_edge_bps, backtest_edge_bps,
                        fill_quality_delta_bps, slippage_drift_bps,
                        latency_drift_ms, drift_score, metadata
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
                    """.trimIndent()
                ).use { driftStmt ->
                    trades.forEach { trade ->
                        val baseline = baselines[Triple(trade.strategyName, trade.exchange, trade.symbol)]
                            ?: baselines[Triple(trade.strategyName, trade.exchange, "ALL")]
                        val metadataJson = gson.toJson(
                            mapOf(
                                "source" to source,
                                "strategyKind" to trade.strategyKind,
                                "fillRatio" to trade.fillRatio.round(4),
                                "betaBtc" to trade.betaBtc.round(4),
                                "betaEth" to trade.betaEth.round(4),
                                "entryTrendScore" to trade.entryTrendScore.round(4),
                                "entryResidualZ" to trade.entryResidualZ.round(4),
                                "expectedGrossEdgeBps" to trade.expectedGrossEdgeBps.round(4),
                                "expectedRoundTripCostBps" to trade.expectedRoundTripCostBps.round(4),
                                "expectedNetEdgeBps" to trade.expectedNetEdgeBps.round(4),
                                "entryImbalance" to trade.entryImbalance.round(4),
                                "entryFlowSignal" to trade.entryFlowSignal.round(4),
                                "entryVolumeRatio" to trade.entryVolumeRatio.round(4),
                                "entryVolRegime" to trade.entryVolRegime.round(4),
                                "executionMode" to researchConfig.paperExecutionMode
                            )
                        )

                        latencyStmt.setTimestamp(1, Timestamp.from(trade.entryTime))
                        latencyStmt.setString(2, trade.strategyName)
                        latencyStmt.setString(3, trade.exchange)
                        latencyStmt.setString(4, trade.symbol)
                        latencyStmt.setDouble(5, trade.decisionLatencyMs)
                        latencyStmt.setDouble(6, trade.submitToAckMs)
                        latencyStmt.setDouble(7, trade.submitToFillMs)
                        latencyStmt.setDouble(8, trade.p50RoundtripMs)
                        latencyStmt.setDouble(9, trade.p95RoundtripMs)
                        latencyStmt.setDouble(10, trade.p99RoundtripMs)
                        latencyStmt.setDouble(11, trade.jitterMs)
                        latencyStmt.setString(12, metadataJson)
                        latencyStmt.addBatch()

                        costStmt.setTimestamp(1, Timestamp.from(trade.entryTime))
                        costStmt.setString(2, trade.strategyName)
                        costStmt.setString(3, trade.exchange)
                        costStmt.setString(4, trade.symbol)
                        costStmt.setString(5, trade.side)
                        costStmt.setDouble(6, trade.feeBps)
                        costStmt.setString(7, trade.feeTier)
                        costStmt.setDouble(8, trade.feeTierAdjustmentBps)
                        costStmt.setDouble(9, trade.makerFeeBps)
                        costStmt.setDouble(10, trade.takerFeeBps)
                        costStmt.setDouble(11, trade.spreadCostBps)
                        costStmt.setDouble(12, trade.slippageBps)
                        costStmt.setDouble(13, trade.impactBps)
                        costStmt.setDouble(14, trade.adverseSelectionBps)
                        costStmt.setDouble(15, trade.fundingDriftBps)
                        costStmt.setDouble(16, trade.basisDriftBps)
                        costStmt.setDouble(17, trade.totalCostBps)
                        costStmt.setDouble(18, trade.edgeAfterCostBps)
                        costStmt.setDouble(19, trade.estimatedFeeUsd)
                        costStmt.setDouble(20, trade.estimatedCostUsd)
                        costStmt.setString(21, metadataJson)
                        costStmt.addBatch()

                        val fillQualityDelta = ((baseline?.avgFillRatio ?: trade.fillRatio) - trade.fillRatio) * 10000.0
                        val slippageDrift = trade.slippageBps - (baseline?.avgSlippageBps ?: trade.slippageBps)
                        val latencyDrift = trade.submitToFillMs - (baseline?.avgSubmitToFillMs ?: trade.submitToFillMs)
                        val edgeDecay = if (baseline == null) 0.0 else max(0.0, baseline.avgEdgeAfterCostBps - trade.edgeAfterCostBps)
                        val predictionMiss = max(0.0, trade.expectedNetEdgeBps - trade.edgeAfterCostBps)
                        val driftScore = max(0.0, fillQualityDelta) +
                            max(0.0, slippageDrift) +
                            max(0.0, latencyDrift) / 10.0 +
                            edgeDecay +
                            predictionMiss

                        driftStmt.setTimestamp(1, Timestamp.from(trade.entryTime))
                        driftStmt.setString(2, trade.strategyName)
                        driftStmt.setString(3, trade.symbol)
                        driftStmt.setDouble(4, trade.edgeAfterCostBps)
                        driftStmt.setObject(5, baseline?.avgEdgeAfterCostBps)
                        driftStmt.setDouble(6, fillQualityDelta)
                        driftStmt.setDouble(7, slippageDrift)
                        driftStmt.setDouble(8, latencyDrift)
                        driftStmt.setDouble(9, driftScore)
                        driftStmt.setString(10, metadataJson)
                        driftStmt.addBatch()
                    }

                    latencyStmt.executeBatch()
                    costStmt.executeBatch()
                    driftStmt.executeBatch()
                }
            }
        }

        conn.commit()
    }
}

fun fetchUserProfile(txBase: String, token: String): JsonObject? =
    runCatching {
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/user"))
            .header("Authorization", "Bearer $token")
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("User profile returned status ${response.statusCode()}")
        }
        JsonParser.parseString(response.body()).asJsonObject
    }.getOrElse { ex ->
        println("User profile unavailable: ${ex.message}")
        null
    }

fun requestBestQuote(txBase: String, symbol: String, side: String, exchanges: List<String>, executionMode: String): JsonObject? =
    runCatching {
        val query = listOf(
            "symbol=${urlEncode(symbol)}",
            "side=${urlEncode(side)}",
            "exchanges=${urlEncode(exchanges.joinToString(","))}",
            "executionMode=${urlEncode(executionMode)}"
        ).joinToString("&")
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges/best-quote?$query"))
            .GET()
            .timeout(Duration.ofSeconds(15))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Best quote returned status ${response.statusCode()}")
        }
        JsonParser.parseString(response.body()).asJsonObject
    }.getOrElse { ex ->
        println("Best quote unavailable for $symbol/$side: ${ex.message}")
        null
    }

fun submitPaperOrder(txBase: String, token: String, exchange: String, symbol: String, side: String, size: Double, executionMode: String): JsonObject? =
    runCatching {
        val payload = gson.toJson(
            mapOf(
                "symbol" to symbol,
                "side" to side.uppercase(),
                "type" to "MARKET",
                "size" to size.toString(),
                "executionMode" to executionMode,
                "reduceOnly" to false,
                "postOnly" to false,
                "urgencyClass" to "normal"
            )
        )
        val request = HttpRequest.newBuilder(URI.create("${txBase.removeSuffix("/")}/api/v1/exchanges/$exchange/order"))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .timeout(Duration.ofSeconds(20))
            .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() !in 200..299) {
            error("Paper order returned status ${response.statusCode()}: ${response.body()}")
        }
        JsonParser.parseString(response.body()).asJsonObject
    }.getOrElse { ex ->
        println("Paper order failed for $exchange/$symbol: ${ex.message}")
        null
    }

fun paperTradeTopSignals(
    latestSignals: List<SignalSnapshot>,
    catalog: List<ExchangeCatalogSnapshot>,
    config: ResearchConfig
): List<Map<String, Any?>> {
    val integrated = catalog
        .filter { it.capabilities.paperOrder && (it.implementationStatus == "INTEGRATED" || it.capabilities.bestQuoteDefault) }
        .map { it.apiName.lowercase() }
        .distinct()
        .ifEmpty { listOf("hyperliquid") }

    val profile = if (config.txAuthToken.isBlank()) null else fetchUserProfile(config.txGatewayUrl, config.txAuthToken)
    val allowedModes = profile?.array("allowedTradingModes")?.map { it.asString.lowercase() }?.toSet() ?: emptySet()
    val allowedExchanges = profile?.array("allowedExchanges")?.map { it.asString.lowercase() }?.toSet()

    val candidates = latestSignals
        .mapNotNull { signal ->
            val trendNet = signal.trendExpectedNetEdgeBps
            val reversionNet = signal.reversionExpectedNetEdgeBps
            val preferredAction = when {
                signal.trendAction != "FLAT" &&
                    (signal.reversionAction == "FLAT" || trendNet >= reversionNet) -> signal.trendAction
                signal.reversionAction != "FLAT" -> signal.reversionAction
                else -> null
            } ?: return@mapNotNull null
            val strategyFamily = when {
                signal.trendAction != "FLAT" &&
                    (signal.reversionAction == "FLAT" || trendNet >= reversionNet) -> "trend"
                signal.reversionAction != "FLAT" -> "reversion"
                else -> "flat"
            }
            mapOf(
                "exchange" to signal.exchange,
                "symbol" to signal.symbol,
                "action" to preferredAction,
                "strategyFamily" to strategyFamily,
                "expectedNetEdgeBps" to max(trendNet, reversionNet),
                "trendScore" to signal.trendScore,
                "residualZ" to signal.residualZ,
                "price" to signal.lastPrice
            )
        }
        .sortedByDescending { it["expectedNetEdgeBps"] as Double }
        .take(2)

    if (candidates.isEmpty()) {
        println("No paper-trade candidates qualified.")
        return emptyList()
    }

    if (config.txAuthToken.isBlank()) {
        println("Set TX_AUTH_TOKEN to enable paper order submission. Returning execution plans only.")
    } else if (config.paperExecutionMode.lowercase() !in allowedModes) {
        println("Account is not provisioned for ${config.paperExecutionMode}; allowed=$allowedModes")
        return emptyList()
    }

    return candidates.map { candidate ->
        val symbol = candidate["symbol"] as String
        val action = candidate["action"] as String
        val side = if (action == "LONG") "buy" else "sell"
        val executionExchanges = integrated.filter { exchange ->
            allowedExchanges == null || exchange in allowedExchanges
        }
        val bestQuote = requestBestQuote(
            txBase = config.txGatewayUrl,
            symbol = symbol,
            side = side,
            exchanges = executionExchanges.ifEmpty { integrated },
            executionMode = config.paperExecutionMode
        )
        val selectedExchange = bestQuote?.string("selectedExchange") ?: candidate["exchange"] as String
        val normalizedSymbol = bestQuote?.string("normalizedSymbol") ?: symbol
        val quote = bestQuote?.obj("quote")
        val lastPrice = quote?.double("last") ?: quote?.double("ask") ?: quote?.double("bid") ?: (candidate["price"] as Double)
        val size = max(config.notionalUsd / max(lastPrice, 1.0), 0.001).round(6)
        val order = if (config.enablePaperOrders && config.txAuthToken.isNotBlank()) {
            submitPaperOrder(
                txBase = config.txGatewayUrl,
                token = config.txAuthToken,
                exchange = selectedExchange,
                symbol = normalizedSymbol,
                side = side,
                size = size,
                executionMode = config.paperExecutionMode
            )
        } else {
            null
        }
        mapOf(
            "symbol" to normalizedSymbol,
            "requestedAction" to action,
            "strategyFamily" to candidate["strategyFamily"],
            "expectedNetEdgeBps" to candidate["expectedNetEdgeBps"],
            "selectedExchange" to selectedExchange,
            "executionMode" to config.paperExecutionMode,
            "size" to size,
            "quoteBid" to (quote?.double("bid")?.round(4)),
            "quoteAsk" to (quote?.double("ask")?.round(4)),
            "quoteLast" to lastPrice.round(4),
            "orderId" to order?.string("orderId"),
            "status" to order?.string("status"),
            "simulated" to order?.bool("simulated")
        )
    }
}
'''

cross_sectional_kotlin_discovery = '''
val exchangeCatalog = fetchExchangeCatalog(researchConfig.txGatewayUrl)
val exchangePlans = buildExchangePlans(exchangeCatalog, researchConfig)

println("Integrated exchange plans")
println(gson.toJson(exchangePlans))

val discoveredUniverse = exchangePlans.associate { plan ->
    plan.exchange to discoverSymbols(
        aliases = plan.marketAliases,
        lookbackHours = researchConfig.lookbackHours,
        maxSymbols = researchConfig.maxSymbols,
        minBars = researchConfig.minBars
    )
}

println("Discovered symbol universe")
println(gson.toJson(discoveredUniverse))

val researchBars = exchangePlans.flatMap { plan ->
    val symbols = discoveredUniverse[plan.exchange].orEmpty()
    loadBars(
        exchange = plan.exchange,
        aliases = plan.marketAliases,
        symbols = symbols,
        lookbackHours = researchConfig.lookbackHours
    )
}

println("Loaded ${researchBars.size} bars")

val researchFeatureRows = engineerFeatures(researchBars, researchConfig)
println("Engineered ${researchFeatureRows.size} feature rows")

val latestSignals = latestSignalSnapshots(researchFeatureRows, researchConfig)
println("Latest beta estimates, flow state, and expected net edge")
latestSignals
'''

cross_sectional_kotlin_backtest = '''
val trendStrategyName = "cross_section_beta_trend_v1"
val reversionStrategyName = "cross_section_beta_reversion_v1"

val trendTrades = simulateStrategy(
    strategyName = trendStrategyName,
    kind = StrategyKind.TREND,
    rows = researchFeatureRows,
    config = researchConfig
)

val reversionTrades = simulateStrategy(
    strategyName = reversionStrategyName,
    kind = StrategyKind.REVERSION,
    rows = researchFeatureRows,
    config = researchConfig
)

val backtestSummaries =
    buildStrategySummaries(
        strategyName = trendStrategyName,
        strategyKind = StrategyKind.TREND,
        trades = trendTrades,
        timeframe = "candle_1m",
        notes = "beta-adjusted cross-sectional trend with flow and cost gating"
    ) +
    buildStrategySummaries(
        strategyName = reversionStrategyName,
        strategyKind = StrategyKind.REVERSION,
        trades = reversionTrades,
        timeframe = "candle_1m",
        notes = "beta-adjusted cross-sectional mean reversion with flow and cost gating"
    )

if (researchConfig.persistBacktest && backtestSummaries.isNotEmpty()) {
    persistBacktestSummaries(backtestSummaries)
    println("Persisted ${backtestSummaries.size} backtest summary rows to strategy_backtest_runs")
} else {
    println("Backtest persistence disabled or no summaries available.")
}

println("Backtest strategy summaries")
backtestSummaries
'''

cross_sectional_kotlin_forward = '''
val forwardCutoff = researchFeatureRows.maxOfOrNull { it.time }
    ?.minus(researchConfig.forwardHours.toLong(), ChronoUnit.HOURS)

if (forwardCutoff == null) {
    println("No feature rows available for forward test.")
    emptyList<StrategySummary>()
} else {
    val calibrationRows = researchFeatureRows.filter { it.time.isBefore(forwardCutoff) }
    val forwardRows = researchFeatureRows.filter { !it.time.isBefore(forwardCutoff) }

    println("Forward cutoff: $forwardCutoff")
    println("Calibration rows: ${calibrationRows.size}, forward rows: ${forwardRows.size}")

    val calibrationTrendTrades = simulateStrategy(
        strategyName = trendStrategyName,
        kind = StrategyKind.TREND,
        rows = calibrationRows,
        config = researchConfig
    )
    val calibrationReversionTrades = simulateStrategy(
        strategyName = reversionStrategyName,
        kind = StrategyKind.REVERSION,
        rows = calibrationRows,
        config = researchConfig
    )

    val calibrationSummaries =
        buildStrategySummaries(
            strategyName = trendStrategyName,
            strategyKind = StrategyKind.TREND,
            trades = calibrationTrendTrades,
            timeframe = "candle_1m",
            notes = "calibration slice with flow and cost gating"
        ) +
        buildStrategySummaries(
            strategyName = reversionStrategyName,
            strategyKind = StrategyKind.REVERSION,
            trades = calibrationReversionTrades,
            timeframe = "candle_1m",
            notes = "calibration slice with flow and cost gating"
        )

    val baselineMap = calibrationSummaries.associateBy { Triple(it.strategyName, it.exchange, it.symbol) }

    val forwardTrendTrades = simulateStrategy(
        strategyName = trendStrategyName,
        kind = StrategyKind.TREND,
        rows = forwardRows,
        config = researchConfig
    )
    val forwardReversionTrades = simulateStrategy(
        strategyName = reversionStrategyName,
        kind = StrategyKind.REVERSION,
        rows = forwardRows,
        config = researchConfig
    )

    val forwardTrades = forwardTrendTrades + forwardReversionTrades
    val forwardSummaries =
        buildStrategySummaries(
            strategyName = trendStrategyName,
            strategyKind = StrategyKind.TREND,
            trades = forwardTrendTrades,
            timeframe = "forward_1m",
            notes = "forward slice with flow and cost gating"
        ) +
        buildStrategySummaries(
            strategyName = reversionStrategyName,
            strategyKind = StrategyKind.REVERSION,
            trades = forwardReversionTrades,
            timeframe = "forward_1m",
            notes = "forward slice with flow and cost gating"
        )

    if (researchConfig.persistForward && forwardTrades.isNotEmpty()) {
        persistForwardTelemetry(
            trades = forwardTrades,
            baselines = baselineMap,
            source = "kotlin-cross-sectional-forward"
        )
        println("Persisted ${forwardTrades.size} forward trades into latency / execution cost / drift tables")
    } else {
        println("Forward telemetry persistence disabled or no forward trades qualified.")
    }

    println("Forward strategy summaries")
    forwardSummaries
}
'''

cross_sectional_kotlin_paper = '''
val paperPlans = paperTradeTopSignals(
    latestSignals = latestSignals,
    catalog = exchangeCatalog,
    config = researchConfig
)

println(
    if (researchConfig.enablePaperOrders) {
        "Paper order results"
    } else {
        "Paper order dry-run plans (set DATAMANCY_CROSS_SECTIONAL_ENABLE_PAPER_ORDERS=true and TX_AUTH_TOKEN to submit)"
    }
)

paperPlans
'''

write_kotlin_notebook(
    "18_cross_sectional_beta_trend_reversion_kotlin.ipynb",
    [
        markdown_cell(
            "# Cross-Sectional Beta, Trend, + Mean Reversion (Kotlin)\n\n"
            "This notebook gives one Kotlin-first research surface for:\n"
            "- exchange discovery via the tx-gateway catalog\n"
            "- BTC and ETH beta estimation per symbol\n"
            "- beta-adjusted trend following inputs\n"
            "- beta-adjusted mean reversion inputs\n"
            "- realistic backtest and forward-test persistence into Grafana-facing tables\n"
            "- optional tx-gateway paper orders\n\n"
            "Today the exchange catalog resolves to Hyperliquid, but the notebook is written against the integrated exchange interface so the same workflow can absorb new venues as they are plugged into the stack."
        ),
        code_cell(cross_sectional_kotlin_bootstrap),
        code_cell(cross_sectional_kotlin_engine),
        code_cell(cross_sectional_kotlin_discovery),
        code_cell(cross_sectional_kotlin_backtest),
        code_cell(cross_sectional_kotlin_forward),
        code_cell(cross_sectional_kotlin_paper),
    ]
)

migrate_seeded_research_notebooks()

PY

chown -R ${NB_UID:-1000}:${NB_GID:-100} /home/jovyan/work/datamancy-notebooks 2>/dev/null || true

cat > /home/jovyan/LM_Agent_Examples.ipynb <<'EOF'
{
 "cells": [
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "# LM Agent Programming Examples\n",
    "\n",
    "This notebook demonstrates how to use various LM agent frameworks with the Datamancy stack's LiteLLM/vLLM endpoints.\n",
    "\n",
    "## Available Frameworks:\n",
    "- **Jupyter AI**: Native JupyterLab AI assistant with `%%ai` magic commands\n",
    "- **LangChain**: Comprehensive LLM application framework\n",
    "- **LlamaIndex**: RAG and data framework\n",
    "- **AutoGen**: Multi-agent collaboration framework\n",
    "\n",
    "## LiteLLM Endpoint\n",
    "- Base URL: `http://litellm:4000/v1`\n",
    "- Available Models: `qwen2.5-0.5b`, `embed-small`"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 1. Setup - Load Environment Variables"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "import os\n",
    "from dotenv import load_dotenv\n",
    "\n",
    "# Load environment variables\n",
    "load_dotenv()\n",
    "\n",
    "# Display configuration\n",
    "print(f\"LiteLLM API Base: {os.getenv('OPENAI_API_BASE')}\")\n",
    "print(f\"Default Model: {os.getenv('DEFAULT_LLM_MODEL')}\")\n",
    "print(f\"Embedding Model: {os.getenv('DEFAULT_EMBEDDING_MODEL')}\")"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 2. Jupyter AI Magic Commands\n",
    "\n",
    "Use `%%ai` magic commands directly in cells:"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "# First, configure Jupyter AI to use our LiteLLM endpoint\n",
    "# This requires setting up the model provider in JupyterLab's AI extension settings\n",
    "# Go to Settings > Jupyter AI > Model provider and add OpenAI-compatible provider\n",
    "\n",
    "# Example magic command (uncomment after configuration):\n",
    "# %%ai openai-chat:qwen2.5-0.5b\n",
    "# Explain what a Python decorator is with a simple example"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 3. LangChain with LiteLLM"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "from langchain_openai import ChatOpenAI\n",
    "from langchain.schema import HumanMessage, SystemMessage\n",
    "\n",
    "# Initialize LangChain with LiteLLM endpoint\n",
    "llm = ChatOpenAI(\n",
    "    model=\"qwen2.5-0.5b\",\n",
    "    openai_api_base=\"http://litellm:4000/v1\",\n",
    "    openai_api_key=os.getenv(\"OPENAI_API_KEY\", \"unused\"),\n",
    "    temperature=0.7\n",
    ")\n",
    "\n",
    "# Simple chat example\n",
    "messages = [\n",
    "    SystemMessage(content=\"You are a helpful coding assistant.\"),\n",
    "    HumanMessage(content=\"Write a Python function to calculate fibonacci numbers.\")\n",
    "]\n",
    "\n",
    "response = llm.invoke(messages)\n",
    "print(response.content)"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 4. LangChain Agent with Tools"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "from langchain.agents import AgentExecutor, create_openai_functions_agent\n",
    "from langchain.tools import tool\n",
    "from langchain.prompts import ChatPromptTemplate, MessagesPlaceholder\n",
    "\n",
    "@tool\n",
    "def calculate_sum(a: int, b: int) -> int:\n",
    "    \"\"\"Calculate the sum of two numbers.\"\"\"\n",
    "    return a + b\n",
    "\n",
    "@tool\n",
    "def calculate_product(a: int, b: int) -> int:\n",
    "    \"\"\"Calculate the product of two numbers.\"\"\"\n",
    "    return a * b\n",
    "\n",
    "tools = [calculate_sum, calculate_product]\n",
    "\n",
    "prompt = ChatPromptTemplate.from_messages([\n",
    "    (\"system\", \"You are a helpful assistant with math tools.\"),\n",
    "    (\"human\", \"{input}\"),\n",
    "    MessagesPlaceholder(variable_name=\"agent_scratchpad\"),\n",
    "])\n",
    "\n",
    "agent = create_openai_functions_agent(llm, tools, prompt)\n",
    "agent_executor = AgentExecutor(agent=agent, tools=tools, verbose=True)\n",
    "\n",
    "result = agent_executor.invoke({\"input\": \"What is (5 + 3) * 4?\"})\n",
    "print(result[\"output\"])"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 5. LlamaIndex with LiteLLM"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "from llama_index.llms.openai import OpenAI\n",
    "from llama_index.core import Settings, VectorStoreIndex, SimpleDirectoryReader\n",
    "from llama_index.embeddings.openai import OpenAIEmbedding\n",
    "\n",
    "# Configure LlamaIndex to use LiteLLM\n",
    "Settings.llm = OpenAI(\n",
    "    model=\"qwen2.5-0.5b\",\n",
    "    api_base=\"http://litellm:4000/v1\",\n",
    "    api_key=\"unused\"\n",
    ")\n",
    "\n",
    "Settings.embed_model = OpenAIEmbedding(\n",
    "    model=\"embed-small\",\n",
    "    api_base=\"http://litellm:4000/v1\",\n",
    "    api_key=\"unused\"\n",
    ")\n",
    "\n",
    "# Simple query example\n",
    "from llama_index.core import Document\n",
    "\n",
    "documents = [\n",
    "    Document(text=\"LlamaIndex is a data framework for LLM applications.\"),\n",
    "    Document(text=\"It helps you ingest, structure, and access private data.\")\n",
    "]\n",
    "\n",
    "index = VectorStoreIndex.from_documents(documents)\n",
    "query_engine = index.as_query_engine()\n",
    "\n",
    "response = query_engine.query(\"What is LlamaIndex?\")\n",
    "print(response)"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 6. AutoGen Multi-Agent Example"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "from autogen_agentchat.agents import AssistantAgent\n",
    "from autogen_agentchat.ui import Console\n",
    "from autogen_agentchat.conditions import TextMentionTermination\n",
    "from autogen_agentchat.teams import RoundRobinGroupChat\n",
    "from autogen_ext.models import OpenAIChatCompletionClient\n",
    "\n",
    "# Configure OpenAI-compatible client for LiteLLM\n",
    "model_client = OpenAIChatCompletionClient(\n",
    "    model=\"qwen2.5-0.5b\",\n",
    "    api_key=\"unused\",\n",
    "    base_url=\"http://litellm:4000/v1\"\n",
    ")\n",
    "\n",
    "# Create agents\n",
    "primary_agent = AssistantAgent(\n",
    "    \"primary_agent\",\n",
    "    model_client=model_client,\n",
    "    system_message=\"You are a helpful assistant that coordinates tasks.\"\n",
    ")\n",
    "\n",
    "critic_agent = AssistantAgent(\n",
    "    \"critic_agent\",\n",
    "    model_client=model_client,\n",
    "    system_message=\"You review and provide constructive feedback on solutions.\"\n",
    ")\n",
    "\n",
    "# Create team\n",
    "termination = TextMentionTermination(\"APPROVE\")\n",
    "team = RoundRobinGroupChat([primary_agent, critic_agent], termination_condition=termination)\n",
    "\n",
    "# Run team chat\n",
    "import asyncio\n",
    "\n",
    "async def run_team():\n",
    "    result = await Console(team.run_stream(task=\"Design a simple REST API for a todo app. Say APPROVE when done.\"))\n",
    "    return result\n",
    "\n",
    "# Execute\n",
    "# result = await run_team()\n",
    "print(\"Multi-agent example ready. Uncomment the last line to execute.\")"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## 7. Direct OpenAI Client with LiteLLM"
   ]
  },
  {
   "cell_type": "code",
   "execution_count": null,
   "metadata": {},
   "outputs": [],
   "source": [
    "from openai import OpenAI\n",
    "\n",
    "# Direct OpenAI client configured for LiteLLM\n",
    "client = OpenAI(\n",
    "    base_url=\"http://litellm:4000/v1\",\n",
    "    api_key=\"unused\"\n",
    ")\n",
    "\n",
    "response = client.chat.completions.create(\n",
    "    model=\"qwen2.5-0.5b\",\n",
    "    messages=[\n",
    "        {\"role\": \"system\", \"content\": \"You are a helpful assistant.\"},\n",
    "        {\"role\": \"user\", \"content\": \"Explain what LiteLLM is in one sentence.\"}\n",
    "    ]\n",
    ")\n",
    "\n",
    "print(response.choices[0].message.content)"
   ]
  },
  {
   "cell_type": "markdown",
   "metadata": {},
   "source": [
    "## Next Steps\n",
    "\n",
    "1. Explore the Jupyter AI chat interface in the JupyterLab sidebar\n",
    "2. Build more complex agents with tool calling\n",
    "3. Create RAG applications with your own data\n",
    "4. Experiment with multi-agent workflows\n",
    "\n",
    "For more information:\n",
    "- LangChain: https://python.langchain.com/\n",
    "- LlamaIndex: https://docs.llamaindex.ai/\n",
    "- AutoGen: https://microsoft.github.io/autogen/\n",
    "- Jupyter AI: https://jupyter-ai.readthedocs.io/"
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
   "name": "python",
   "version": "3.11.0"
  }
 },
 "nbformat": 4,
 "nbformat_minor": 4
}
EOF
echo "Jupyter notebook environment configured for LM agent programming"

python3 <<'PY'
import json
import os

notebook_dir = "/home/jovyan/work/datamancy-notebooks"
os.makedirs(notebook_dir, exist_ok=True)

def markdown_cell(text: str):
    return {"cell_type": "markdown", "metadata": {}, "source": text.splitlines(keepends=True)}

def code_cell(text: str):
    return {"cell_type": "code", "execution_count": None, "metadata": {}, "outputs": [], "source": text.splitlines(keepends=True)}

def write_notebook(name: str, cells):
    path = os.path.join(notebook_dir, name)
    if os.path.exists(path):
        return
    notebook = {
        "cells": cells,
        "metadata": {
            "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
            "language_info": {"name": "python"}
        },
        "nbformat": 4,
        "nbformat_minor": 5
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(notebook, f, indent=1)

db_bootstrap = (
    "import os\n"
    "import pandas as pd\n"
    "from sqlalchemy import create_engine, text\n"
    "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
    "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
    "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
    "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
    "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
    "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
)

write_notebook(
    "10_data_quality_and_latency_diagnostics.ipynb",
    [
        markdown_cell(
            "# Data Quality + Latency Diagnostics\n\n"
            "Checks freshness, missing bars, spread anomalies, and execution latency buckets (p50/p95/p99).\n"
        ),
        code_cell(db_bootstrap),
        code_cell(
            "dq = pd.read_sql(text('''\n"
            "WITH candles AS (\n"
            "  SELECT symbol, COUNT(*) AS bars, MAX(time) AS last_bar,\n"
            "         AVG(CASE WHEN high < low THEN 1 ELSE 0 END) AS invalid_ohlc_ratio\n"
            "  FROM market_data\n"
            "  WHERE data_type='candle_1m' AND time >= NOW() - INTERVAL '24 hours'\n"
            "  GROUP BY symbol\n"
            "), ob AS (\n"
            "  SELECT symbol, AVG(spread_pct) AS avg_spread_pct, MAX(time) AS last_ob\n"
            "  FROM orderbook_data\n"
            "  WHERE time >= NOW() - INTERVAL '24 hours'\n"
            "  GROUP BY symbol\n"
            ")\n"
            "SELECT c.symbol, c.bars, c.last_bar, o.last_ob, o.avg_spread_pct, c.invalid_ohlc_ratio\n"
            "FROM candles c\n"
            "LEFT JOIN ob o USING (symbol)\n"
            "ORDER BY c.bars DESC\n"
            "'''), engine)\n"
            "dq\n"
        ),
        code_cell(
            "latency = pd.read_sql(text('''\n"
            "SELECT strategy_name, observed_at,\n"
            "       p50_roundtrip_ms AS p50_ms,\n"
            "       p95_roundtrip_ms AS p95_ms,\n"
            "       p99_roundtrip_ms AS p99_ms,\n"
            "       jitter_ms\n"
            "FROM strategy_latency_metrics\n"
            "WHERE observed_at >= NOW() - INTERVAL '7 days'\n"
            "ORDER BY observed_at DESC\n"
            "LIMIT 2000\n"
            "'''), engine)\n"
            "latency.head()\n"
        ),
    ]
)

write_notebook(
    "11_cost_realism_validation.ipynb",
    [
        markdown_cell("# Cost Realism Validation\n\nValidate fee/slippage/impact realism against realized fills."),
        code_cell(db_bootstrap),
        code_cell(
            "cost = pd.read_sql(text('''\n"
            "SELECT strategy_name, exchange, side,\n"
            "       fee_tier,\n"
            "       AVG(fee_bps) AS fee_bps,\n"
            "       AVG(fee_tier_adjustment_bps) AS fee_tier_adjustment_bps,\n"
            "       AVG(maker_fee_bps) AS maker_fee_bps,\n"
            "       AVG(taker_fee_bps) AS taker_fee_bps,\n"
            "       AVG(spread_cost_bps) AS spread_cost_bps,\n"
            "       AVG(slippage_bps) AS slippage_bps,\n"
            "       AVG(impact_bps) AS impact_bps,\n"
            "       AVG(adverse_selection_bps) AS adverse_selection_bps,\n"
            "       AVG(funding_drift_bps) AS funding_drift_bps,\n"
            "       AVG(basis_drift_bps) AS basis_drift_bps,\n"
            "       AVG(total_cost_bps) AS total_cost_bps,\n"
            "       AVG(edge_after_cost_bps) AS edge_after_cost_bps,\n"
            "       AVG(estimated_fee_usd) AS estimated_fee_usd,\n"
            "       AVG(estimated_cost_usd) AS estimated_cost_usd\n"
            "FROM strategy_execution_costs\n"
            "WHERE observed_at >= NOW() - INTERVAL '14 days'\n"
            "GROUP BY strategy_name, exchange, side, fee_tier\n"
            "ORDER BY total_cost_bps DESC NULLS LAST\n"
            "'''), engine)\n"
            "cost\n"
        )
    ]
)

write_notebook(
    "12_walk_forward_backtests_with_regime_slices.ipynb",
    [
        markdown_cell("# Walk-Forward Backtests With Regime Slices\n\nEvaluate OOS robustness by volatility/liquidity regime."),
        code_cell(db_bootstrap),
        code_cell(
            "wf = pd.read_sql(text('''\n"
            "SELECT strategy_name, run_at,\n"
            "       train_start, train_end, test_start, test_end,\n"
            "       COALESCE(metrics->>'regime_bucket', 'unknown') AS regime_bucket,\n"
            "       net_return_pct, sharpe, max_drawdown_pct, trades\n"
            "FROM strategy_walkforward_runs\n"
            "WHERE run_at >= NOW() - INTERVAL '90 days'\n"
            "ORDER BY run_at DESC\n"
            "'''), engine)\n"
            "wf.groupby(['strategy_name','regime_bucket'])[['net_return_pct','sharpe','max_drawdown_pct']].mean().reset_index()\n"
        ),
        code_cell(
            "seed = pd.read_sql(text('''\n"
            "SELECT strategy_name, symbol, timeframe,\n"
            "       MIN(start_time) AS train_start,\n"
            "       MAX(start_time) AS train_end,\n"
            "       MIN(end_time) AS test_start,\n"
            "       MAX(end_time) AS test_end,\n"
            "       AVG(net_return_pct) AS net_return_pct,\n"
            "       AVG(sharpe) AS sharpe,\n"
            "       AVG(max_drawdown_pct) AS max_drawdown_pct,\n"
            "       AVG(win_rate) AS win_rate,\n"
            "       AVG(trades) AS trades\n"
            "FROM strategy_backtest_runs\n"
            "WHERE run_at >= NOW() - INTERVAL '45 days'\n"
            "GROUP BY strategy_name, symbol, timeframe\n"
            "ORDER BY MAX(run_at) DESC\n"
            "LIMIT 40\n"
            "'''), engine)\n"
            "if seed.empty:\n"
            "    wf_candidate = pd.DataFrame(columns=['strategy_name','symbol','train_start','train_end','test_start','test_end','net_return_pct','sharpe','max_drawdown_pct','win_rate','trades','regime_bucket'])\n"
            "else:\n"
            "    wf_candidate = seed.copy()\n"
            "    wf_candidate['regime_bucket'] = wf_candidate['symbol'].apply(lambda s: 'high_volatility' if 'PERP' in str(s).upper() else 'low_volatility')\n"
            "wf_candidate.head(20)\n"
        ),
        code_cell(
            "persist_walkforward = False  # Set True to insert candidates into strategy_walkforward_runs\n"
            "if persist_walkforward and not wf_candidate.empty:\n"
            "    with engine.begin() as conn:\n"
            "        conn.execute(text('''\n"
            "            CREATE TABLE IF NOT EXISTS strategy_walkforward_runs (\n"
            "                id BIGSERIAL PRIMARY KEY,\n"
            "                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),\n"
            "                strategy_name TEXT NOT NULL,\n"
            "                symbol TEXT NOT NULL,\n"
            "                train_start TIMESTAMPTZ NOT NULL,\n"
            "                train_end TIMESTAMPTZ NOT NULL,\n"
            "                test_start TIMESTAMPTZ NOT NULL,\n"
            "                test_end TIMESTAMPTZ NOT NULL,\n"
            "                net_return_pct DOUBLE PRECISION NOT NULL DEFAULT 0,\n"
            "                sharpe DOUBLE PRECISION NOT NULL DEFAULT 0,\n"
            "                max_drawdown_pct DOUBLE PRECISION NOT NULL DEFAULT 0,\n"
            "                win_rate DOUBLE PRECISION NOT NULL DEFAULT 0,\n"
            "                trades INTEGER NOT NULL DEFAULT 0,\n"
            "                metrics JSONB NOT NULL DEFAULT '{}'::jsonb\n"
            "            )\n"
            "        '''))\n"
            "        for row in wf_candidate.to_dict('records'):\n"
            "            conn.execute(text('''\n"
            "                INSERT INTO strategy_walkforward_runs (\n"
            "                    strategy_name, symbol, train_start, train_end, test_start, test_end,\n"
            "                    net_return_pct, sharpe, max_drawdown_pct, win_rate, trades, metrics\n"
            "                ) VALUES (\n"
            "                    :strategy_name, :symbol, :train_start, :train_end, :test_start, :test_end,\n"
            "                    :net_return_pct, :sharpe, :max_drawdown_pct, :win_rate, :trades, CAST(:metrics AS jsonb)\n"
            "                )\n"
            "            '''), {\n"
            "                'strategy_name': row['strategy_name'],\n"
            "                'symbol': row['symbol'],\n"
            "                'train_start': pd.to_datetime(row['train_start']).to_pydatetime(),\n"
            "                'train_end': pd.to_datetime(row['train_end']).to_pydatetime(),\n"
            "                'test_start': pd.to_datetime(row['test_start']).to_pydatetime(),\n"
            "                'test_end': pd.to_datetime(row['test_end']).to_pydatetime(),\n"
            "                'net_return_pct': float(row['net_return_pct']),\n"
            "                'sharpe': float(row['sharpe']),\n"
            "                'max_drawdown_pct': float(row['max_drawdown_pct']),\n"
            "                'win_rate': float(row['win_rate']) / 100.0 if float(row['win_rate']) > 1.0 else float(row['win_rate']),\n"
            "                'trades': int(max(0, round(float(row['trades'])))),\n"
            "                'metrics': '{\"regime_bucket\": \"%s\", \"source\": \"notebook-walkforward\"}' % row['regime_bucket']\n"
            "            })\n"
            "    print(f'Persisted {len(wf_candidate)} rows to strategy_walkforward_runs')\n"
            "else:\n"
            "    print('Persistence disabled (set persist_walkforward=True to save rows).')\n"
        )
    ]
)

write_notebook(
    "13_sensitivity_sweeps_fees_slippage_latency.ipynb",
    [
        markdown_cell("# Sensitivity Sweeps (Fees / Slippage / Latency)\n\nStress expected edge under worsening execution frictions."),
        code_cell(db_bootstrap),
        code_cell(
            "sweep = pd.read_sql(text('''\n"
            "SELECT strategy_name, run_at, parameter_name, parameter_value,\n"
            "       net_return_pct, sharpe, max_drawdown_pct, trades,\n"
            "       metrics\n"
            "FROM strategy_sensitivity_sweeps\n"
            "WHERE run_at >= NOW() - INTERVAL '30 days'\n"
            "ORDER BY strategy_name, run_at DESC\n"
            "'''), engine)\n"
            "sweep.head(50)\n"
        ),
        code_cell(
            "base = pd.read_sql(text('''\n"
            "SELECT strategy_name, symbol, net_return_pct, sharpe, max_drawdown_pct, trades\n"
            "FROM strategy_backtest_runs\n"
            "WHERE run_at >= NOW() - INTERVAL '30 days'\n"
            "ORDER BY run_at DESC\n"
            "LIMIT 40\n"
            "'''), engine)\n"
            "scenarios = [\n"
            "    ('fee_bps', '+2', -0.45),\n"
            "    ('slippage_bps', '+5', -0.75),\n"
            "    ('latency_ms', '+120', -0.35),\n"
            "    ('fee_bps', '+4', -0.95),\n"
            "    ('slippage_bps', '+10', -1.30)\n"
            "]\n"
            "rows = []\n"
            "for _, row in base.iterrows():\n"
            "    for parameter_name, parameter_value, return_penalty in scenarios:\n"
            "        rows.append({\n"
            "            'strategy_name': row['strategy_name'],\n"
            "            'symbol': row['symbol'],\n"
            "            'parameter_name': parameter_name,\n"
            "            'parameter_value': parameter_value,\n"
            "            'net_return_pct': float(row['net_return_pct']) + return_penalty,\n"
            "            'sharpe': float(row['sharpe']) * max(0.1, 1.0 + return_penalty / 4.0),\n"
            "            'max_drawdown_pct': float(row['max_drawdown_pct']) + abs(return_penalty) * 0.6,\n"
            "            'trades': int(max(0, row['trades'])),\n"
            "            'metrics': '{\"source\": \"notebook-sensitivity\", \"base_return_pct\": %.6f}' % float(row['net_return_pct'])\n"
            "        })\n"
            "sweep_candidate = pd.DataFrame(rows)\n"
            "sweep_candidate.head(50)\n"
        ),
        code_cell(
            "persist_sensitivity = False  # Set True to insert candidates into strategy_sensitivity_sweeps\n"
            "if persist_sensitivity and not sweep_candidate.empty:\n"
            "    with engine.begin() as conn:\n"
            "        conn.execute(text('''\n"
            "            CREATE TABLE IF NOT EXISTS strategy_sensitivity_sweeps (\n"
            "                id BIGSERIAL PRIMARY KEY,\n"
            "                run_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),\n"
            "                strategy_name TEXT NOT NULL,\n"
            "                symbol TEXT NOT NULL,\n"
            "                parameter_name TEXT NOT NULL,\n"
            "                parameter_value TEXT NOT NULL,\n"
            "                net_return_pct DOUBLE PRECISION NOT NULL DEFAULT 0,\n"
            "                sharpe DOUBLE PRECISION NOT NULL DEFAULT 0,\n"
            "                max_drawdown_pct DOUBLE PRECISION NOT NULL DEFAULT 0,\n"
            "                trades INTEGER NOT NULL DEFAULT 0,\n"
            "                metrics JSONB NOT NULL DEFAULT '{}'::jsonb\n"
            "            )\n"
            "        '''))\n"
            "        for row in sweep_candidate.to_dict('records'):\n"
            "            conn.execute(text('''\n"
            "                INSERT INTO strategy_sensitivity_sweeps (\n"
            "                    strategy_name, symbol, parameter_name, parameter_value,\n"
            "                    net_return_pct, sharpe, max_drawdown_pct, trades, metrics\n"
            "                ) VALUES (\n"
            "                    :strategy_name, :symbol, :parameter_name, :parameter_value,\n"
            "                    :net_return_pct, :sharpe, :max_drawdown_pct, :trades, CAST(:metrics AS jsonb)\n"
            "                )\n"
            "            '''), row)\n"
            "    print(f'Persisted {len(sweep_candidate)} rows to strategy_sensitivity_sweeps')\n"
            "else:\n"
            "    print('Persistence disabled (set persist_sensitivity=True to save rows).')\n"
        )
    ]
)

write_notebook(
    "14_live_vs_backtest_drift_dashboard.ipynb",
    [
        markdown_cell("# Live vs Backtest Drift Dashboard\n\nTrack degradation in fill quality, slippage, and latency drift."),
        code_cell(db_bootstrap),
        code_cell(
            "drift = pd.read_sql(text('''\n"
            "SELECT strategy_name, observed_at,\n"
            "       live_edge_bps, backtest_edge_bps,\n"
            "       fill_quality_delta_bps,\n"
            "       slippage_drift_bps,\n"
            "       latency_drift_ms,\n"
            "       drift_score,\n"
            "       metadata\n"
            "FROM strategy_live_backtest_drift\n"
            "WHERE observed_at >= NOW() - INTERVAL '30 days'\n"
            "ORDER BY observed_at DESC\n"
            "'''), engine)\n"
            "drift.head(200)\n"
        ),
        code_cell(
            "drift_candidate = pd.read_sql(text('''\n"
            "WITH latest_backtest AS (\n"
            "  SELECT DISTINCT ON (strategy_name, symbol)\n"
            "         strategy_name,\n"
            "         symbol,\n"
            "         CASE WHEN trades > 0 THEN (net_return_pct * 100.0) / trades ELSE net_return_pct * 100.0 END AS backtest_edge_bps\n"
            "  FROM strategy_backtest_runs\n"
            "  ORDER BY strategy_name, symbol, run_at DESC\n"
            "), live_rows AS (\n"
            "  SELECT c.observed_at,\n"
            "         c.strategy_name,\n"
            "         c.symbol,\n"
            "         c.slippage_bps,\n"
            "         c.total_cost_bps,\n"
            "         COALESCE((c.metadata ->> 'fillRatio')::double precision, 1.0) AS fill_ratio,\n"
            "         COALESCE(l.submit_to_fill_ms, l.submit_to_ack_ms) AS submit_to_fill_ms\n"
            "  FROM strategy_execution_costs c\n"
            "  JOIN strategy_latency_metrics l\n"
            "    ON l.strategy_name = c.strategy_name\n"
            "   AND l.exchange = c.exchange\n"
            "   AND l.symbol = c.symbol\n"
            "   AND l.observed_at = c.observed_at\n"
            "  WHERE c.observed_at >= NOW() - INTERVAL '14 days'\n"
            ")\n"
            "SELECT l.observed_at,\n"
            "       l.strategy_name,\n"
            "       l.symbol,\n"
            "       -l.total_cost_bps AS live_edge_bps,\n"
            "       b.backtest_edge_bps,\n"
            "       (AVG(l.fill_ratio) OVER (PARTITION BY l.strategy_name, l.symbol) - l.fill_ratio) * 10000.0 AS fill_quality_delta_bps,\n"
            "       l.slippage_bps - AVG(l.slippage_bps) OVER (PARTITION BY l.strategy_name, l.symbol) AS slippage_drift_bps,\n"
            "       l.submit_to_fill_ms - AVG(l.submit_to_fill_ms) OVER (PARTITION BY l.strategy_name, l.symbol) AS latency_drift_ms\n"
            "FROM live_rows l\n"
            "LEFT JOIN latest_backtest b\n"
            "  ON b.strategy_name = l.strategy_name\n"
            " AND b.symbol = l.symbol\n"
            "ORDER BY l.observed_at DESC\n"
            "LIMIT 500\n"
            "'''), engine)\n"
            "if not drift_candidate.empty:\n"
            "    edge_decay = (drift_candidate['backtest_edge_bps'].fillna(0) - drift_candidate['live_edge_bps'].fillna(0)).clip(lower=0)\n"
            "    drift_candidate['drift_score'] = (\n"
            "        drift_candidate['fill_quality_delta_bps'].fillna(0).clip(lower=0)\n"
            "        + drift_candidate['slippage_drift_bps'].fillna(0).clip(lower=0)\n"
            "        + drift_candidate['latency_drift_ms'].fillna(0).clip(lower=0) / 10.0\n"
            "        + edge_decay\n"
            "    )\n"
            "drift_candidate.head(50)\n"
        ),
        code_cell(
            "persist_drift = False  # Set True to insert candidates into strategy_live_backtest_drift\n"
            "if persist_drift and not drift_candidate.empty:\n"
            "    with engine.begin() as conn:\n"
            "        conn.execute(text('''\n"
            "            CREATE TABLE IF NOT EXISTS strategy_live_backtest_drift (\n"
            "                id BIGSERIAL PRIMARY KEY,\n"
            "                observed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),\n"
            "                strategy_name TEXT NOT NULL,\n"
            "                symbol TEXT NOT NULL,\n"
            "                live_edge_bps DOUBLE PRECISION,\n"
            "                backtest_edge_bps DOUBLE PRECISION,\n"
            "                fill_quality_delta_bps DOUBLE PRECISION,\n"
            "                slippage_drift_bps DOUBLE PRECISION,\n"
            "                latency_drift_ms DOUBLE PRECISION,\n"
            "                drift_score DOUBLE PRECISION,\n"
            "                metadata JSONB NOT NULL DEFAULT '{}'::jsonb\n"
            "            )\n"
            "        '''))\n"
            "        for row in drift_candidate.to_dict('records'):\n"
            "            conn.execute(text('''\n"
            "                INSERT INTO strategy_live_backtest_drift (\n"
            "                    observed_at, strategy_name, symbol,\n"
            "                    live_edge_bps, backtest_edge_bps,\n"
            "                    fill_quality_delta_bps, slippage_drift_bps, latency_drift_ms,\n"
            "                    drift_score, metadata\n"
            "                ) VALUES (\n"
            "                    :observed_at, :strategy_name, :symbol,\n"
            "                    :live_edge_bps, :backtest_edge_bps,\n"
            "                    :fill_quality_delta_bps, :slippage_drift_bps, :latency_drift_ms,\n"
            "                    :drift_score, CAST(:metadata AS jsonb)\n"
            "                )\n"
            "            '''), {\n"
            "                'observed_at': pd.to_datetime(row['observed_at']).to_pydatetime(),\n"
            "                'strategy_name': row['strategy_name'],\n"
            "                'symbol': row['symbol'],\n"
            "                'live_edge_bps': float(row['live_edge_bps']) if pd.notna(row['live_edge_bps']) else None,\n"
            "                'backtest_edge_bps': float(row['backtest_edge_bps']) if pd.notna(row['backtest_edge_bps']) else None,\n"
            "                'fill_quality_delta_bps': float(row['fill_quality_delta_bps']) if pd.notna(row['fill_quality_delta_bps']) else None,\n"
            "                'slippage_drift_bps': float(row['slippage_drift_bps']) if pd.notna(row['slippage_drift_bps']) else None,\n"
            "                'latency_drift_ms': float(row['latency_drift_ms']) if pd.notna(row['latency_drift_ms']) else None,\n"
            "                'drift_score': float(row['drift_score']) if pd.notna(row['drift_score']) else None,\n"
            "                'metadata': '{\"source\": \"notebook-drift\"}'\n"
            "            })\n"
            "    print(f'Persisted {len(drift_candidate)} rows to strategy_live_backtest_drift')\n"
            "else:\n"
            "    print('Persistence disabled (set persist_drift=True to save rows).')\n"
        )
    ]
)

write_notebook(
    "15_forward_test_mainnet_data.ipynb",
    [
        markdown_cell(
            "# Forward Test Against Mainnet Data\n\n"
            "Prototype forward-testing notebook that uses live market data while preserving execution realism.\n\n"
            "What it does:\n"
            "- builds minute-level decisions from `market_data` + `orderbook_data`\n"
            "- applies realistic fee/slippage/impact/adverse/carry components\n"
            "- computes edge-after-cost and latency context\n"
            "- persists rows into `strategy_latency_metrics`, `strategy_execution_costs`, and `strategy_live_backtest_drift`\n"
            "- uses env-driven defaults so the same notebook can be rerun safely from JupyterHub without hand-editing boilerplate\n"
            "- feeds Grafana directly via the same tables used by gateway telemetry with idempotent range replacement\n"
        ),
        code_cell(
            "import os\n"
            "import json\n"
            "import numpy as np\n"
            "import pandas as pd\n"
            "from sqlalchemy import create_engine, text\n"
            "\n"
            "pd.set_option('display.max_columns', 200)\n"
            "\n"
            "pg_host = os.getenv('POSTGRES_HOST', 'postgres')\n"
            "pg_port = os.getenv('POSTGRES_PORT', '5432')\n"
            "pg_db = os.getenv('POSTGRES_DB', 'datamancy')\n"
            "pg_user = os.getenv('POSTGRES_USER', 'pipeline_user')\n"
            "pg_password = os.getenv('POSTGRES_PASSWORD', '')\n"
            "engine = create_engine(f'postgresql+psycopg2://{pg_user}:{pg_password}@{pg_host}:{pg_port}/{pg_db}')\n"
        ),
        code_cell(
            "strategy_name = os.getenv('DATAMANCY_FORWARD_TEST_STRATEGY_NAME', 'tx_gateway_forward_mainnet_data').strip() or 'tx_gateway_forward_mainnet_data'\n"
            "market_data_exchange = os.getenv('DATAMANCY_FORWARD_TEST_MARKET_DATA_EXCHANGE', os.getenv('DATAMANCY_FORWARD_TEST_EXCHANGE', 'hyperliquid_mainnet')).strip() or 'hyperliquid_mainnet'\n"
            "exchange_aliases = ['hyperliquid', 'hyperliquid_mainnet'] if market_data_exchange == 'hyperliquid_mainnet' else [market_data_exchange]\n"
            "exchange_sql = ', '.join([\"'\" + alias.replace(\"'\", \"''\") + \"'\" for alias in exchange_aliases])\n"
            "execution_exchange = (os.getenv('DATAMANCY_FORWARD_TEST_EXECUTION_EXCHANGE', 'hyperliquid' if market_data_exchange == 'hyperliquid_mainnet' else market_data_exchange).strip().lower() or 'hyperliquid')\n"
            "symbol = os.getenv('DATAMANCY_FORWARD_TEST_SYMBOL', 'BTC').strip() or 'BTC'\n"
            "lookback = os.getenv('DATAMANCY_FORWARD_TEST_LOOKBACK', '12 hours').strip() or '12 hours'\n"
            "decision_every_minutes = max(1, int(os.getenv('DATAMANCY_FORWARD_TEST_DECISION_EVERY_MINUTES', '5')))\n"
            "notional_usd = float(os.getenv('DATAMANCY_FORWARD_TEST_NOTIONAL_USD', '7500'))\n"
            "fee_tier = (os.getenv('DATAMANCY_FORWARD_TEST_FEE_TIER', 'retail').strip().lower() or 'retail')  # retail|pro|vip\n"
            "execution_mode = os.getenv('DATAMANCY_FORWARD_TEST_EXECUTION_MODE', 'forward_paper').strip().lower() or 'forward_paper'\n"
            "market_data_mode = os.getenv('DATAMANCY_FORWARD_TEST_MARKET_DATA_MODE', 'mainnet_live').strip().lower() or 'mainnet_live'\n"
            "if execution_mode != 'forward_paper':\n"
            "    raise RuntimeError(f'Notebook 15 expects forward_paper execution mode, got {execution_mode!r}')\n"
            "if market_data_mode != 'mainnet_live':\n"
            "    raise RuntimeError(f'Notebook 15 expects mainnet_live market data mode, got {market_data_mode!r}')\n"
            "base_maker_bps = -1.0\n"
            "base_taker_bps = 4.0\n"
            "fee_tier_adjustment_bps = {'retail': 0.0, 'pro': -0.7, 'vip': -1.5}.get(fee_tier, 0.0)\n"
            "latency_ms_base = float(os.getenv('DATAMANCY_FORWARD_TEST_LATENCY_MS_BASE', '95'))\n"
            "latency_ms_jitter = float(os.getenv('DATAMANCY_FORWARD_TEST_LATENCY_MS_JITTER', '40'))\n"
            "rng = np.random.default_rng(int(os.getenv('DATAMANCY_FORWARD_TEST_RNG_SEED', '7')))\n"
        ),
        code_cell(
            "forward = pd.read_sql(text(f'''\n"
            "WITH px AS (\n"
            "  SELECT time, close, volume,\n"
            "         LAG(close) OVER (ORDER BY time) AS prev_close\n"
            "  FROM market_data\n"
            "  WHERE exchange IN ({exchange_sql})\n"
            "    AND symbol = :symbol\n"
            "    AND data_type = 'candle_1m'\n"
            "    AND time >= NOW() - CAST(:lookback AS interval)\n"
            "), ob AS (\n"
            "  SELECT time, spread_pct, bid_depth_10, ask_depth_10, mid_price\n"
            "  FROM orderbook_data\n"
            "  WHERE exchange IN ({exchange_sql})\n"
            "    AND symbol = :symbol\n"
            "    AND time >= NOW() - CAST(:lookback AS interval)\n"
            "), funding AS (\n"
            "  SELECT time, funding_rate\n"
            "  FROM market_data\n"
            "  WHERE exchange IN ({exchange_sql})\n"
            "    AND symbol = :symbol\n"
            "    AND data_type = 'funding'\n"
            "    AND time >= NOW() - CAST(:lookback AS interval) - INTERVAL '1 hour'\n"
            ")\n"
            "SELECT px.time,\n"
            "       px.close,\n"
            "       px.prev_close,\n"
            "       px.volume,\n"
            "       COALESCE(ob.spread_pct, 0) AS spread_pct,\n"
            "       COALESCE(ob.bid_depth_10, 0) AS bid_depth_10,\n"
            "       COALESCE(ob.ask_depth_10, 0) AS ask_depth_10,\n"
            "       COALESCE(ob.mid_price, 0) AS mid_price,\n"
            "       COALESCE(f.funding_rate, 0.0) AS funding_rate\n"
            "FROM px\n"
            "LEFT JOIN ob ON ob.time = px.time\n"
            "LEFT JOIN LATERAL (\n"
            "  SELECT funding_rate\n"
            "  FROM funding f\n"
            "  WHERE f.time <= px.time\n"
            "  ORDER BY f.time DESC\n"
            "  LIMIT 1\n"
            ") f ON TRUE\n"
            "ORDER BY px.time ASC\n"
            "'''), engine, params={'symbol': symbol, 'lookback': lookback}, parse_dates=['time'])\n"
            "forward = forward.drop_duplicates('time').sort_values('time').reset_index(drop=True)\n"
            "if forward.empty:\n"
            "    raise RuntimeError('No forward-test data found. Check ingestion and lookback window.')\n"
            "forward.head(), len(forward)\n"
        ),
        code_cell(
            "forward['ret_1m'] = (forward['close'] / forward['prev_close'] - 1.0).replace([np.inf, -np.inf], np.nan).fillna(0)\n"
            "depth_sum = (forward['bid_depth_10'] + forward['ask_depth_10']).replace(0, np.nan)\n"
            "forward['imbalance'] = ((forward['bid_depth_10'] - forward['ask_depth_10']) / depth_sum).replace([np.inf, -np.inf], np.nan).fillna(0)\n"
            "forward['micro_alpha'] = (0.7 * forward['imbalance'] + 0.3 * np.tanh(forward['ret_1m'] * 500)).clip(-1, 1)\n"
            "forward['signal'] = np.where(forward['micro_alpha'] > 0.15, 'BUY', np.where(forward['micro_alpha'] < -0.15, 'SELL', 'HOLD'))\n"
            "forward = forward.iloc[::max(1, int(decision_every_minutes))].copy()\n"
            "forward = forward[forward['signal'] != 'HOLD'].copy()\n"
            "if forward.empty:\n"
            "    raise RuntimeError('Signal filter produced no forward decisions in the selected window.')\n"
            "\n"
            "spread_bps = (forward['spread_pct'].clip(lower=0) * 100.0)\n"
            "slippage_bps = (1.5 + spread_bps * 0.25 + np.abs(forward['ret_1m']) * 7000.0).clip(0.2, 30.0)\n"
            "depth_notional_usd = (depth_sum * forward['mid_price'].replace(0, np.nan).fillna(forward['close']).replace(0, np.nan))\n"
            "impact_bps = (0.6 + (notional_usd / depth_notional_usd.replace(0, np.nan)) * 12.0).replace([np.inf, -np.inf], np.nan).fillna(2.0).clip(0.3, 18.0)\n"
            "adverse_selection_bps = (0.8 + np.abs(forward['micro_alpha']) * 3.5).clip(0.4, 8.0)\n"
            "funding_drift_bps = (-forward['funding_rate'].fillna(0) * 10000.0 * decision_every_minutes / 60.0).clip(-5.0, 5.0)\n"
            "basis_drift_bps = (forward['ret_1m'].rolling(10).mean().fillna(0) * 10000.0 * 0.1).clip(-5.0, 5.0)\n"
            "\n"
            "maker_fee_bps = base_maker_bps + fee_tier_adjustment_bps\n"
            "taker_fee_bps = base_taker_bps + fee_tier_adjustment_bps\n"
            "is_marketable = (np.abs(forward['micro_alpha']) > 0.35).astype(float)\n"
            "applied_fee_bps = np.where(is_marketable > 0, taker_fee_bps, maker_fee_bps)\n"
            "fill_ratio = (0.92 - spread_bps / 200.0 - np.abs(forward['micro_alpha']) * 0.08).clip(0.35, 1.0)\n"
            "\n"
            "forward['fee_bps'] = applied_fee_bps\n"
            "forward['fee_tier_adjustment_bps'] = fee_tier_adjustment_bps\n"
            "forward['maker_fee_bps'] = maker_fee_bps\n"
            "forward['taker_fee_bps'] = taker_fee_bps\n"
            "forward['spread_cost_bps'] = spread_bps\n"
            "forward['slippage_bps'] = slippage_bps\n"
            "forward['impact_bps'] = impact_bps\n"
            "forward['adverse_selection_bps'] = adverse_selection_bps\n"
            "forward['funding_drift_bps'] = funding_drift_bps\n"
            "forward['basis_drift_bps'] = basis_drift_bps\n"
            "forward['total_cost_bps'] = (\n"
            "    forward['fee_bps'] + forward['spread_cost_bps'] + forward['slippage_bps']\n"
            "    + forward['impact_bps'] + forward['adverse_selection_bps']\n"
            "    + forward['funding_drift_bps'] + forward['basis_drift_bps']\n"
            ")\n"
            "forward['alpha_edge_bps'] = (forward['micro_alpha'] * 10.0).clip(-25.0, 25.0)\n"
            "forward['edge_after_cost_bps'] = forward['alpha_edge_bps'] - forward['total_cost_bps']\n"
            "forward['estimated_fee_usd'] = notional_usd * np.abs(forward['fee_bps']) / 10000.0\n"
            "forward['estimated_cost_usd'] = notional_usd * np.abs(forward['total_cost_bps']) / 10000.0\n"
            "\n"
            "decision_ms = (6.0 + np.abs(forward['micro_alpha']) * 10.0).clip(4.0, 30.0)\n"
            "submit_to_ack_ms = (latency_ms_base + rng.normal(0, latency_ms_jitter, len(forward))).clip(25.0, 400.0)\n"
            "submit_to_fill_ms = (submit_to_ack_ms + 20.0 + (1.0 - fill_ratio) * 220.0).clip(30.0, 1200.0)\n"
            "forward['decision_latency_ms'] = decision_ms\n"
            "forward['submit_to_ack_ms'] = submit_to_ack_ms\n"
            "forward['submit_to_fill_ms'] = submit_to_fill_ms\n"
            "forward['p50_roundtrip_ms'] = (submit_to_ack_ms + 12.0).clip(20.0, 500.0)\n"
            "forward['p95_roundtrip_ms'] = (submit_to_ack_ms * 2.0).clip(30.0, 1000.0)\n"
            "forward['p99_roundtrip_ms'] = (submit_to_ack_ms * 3.0).clip(40.0, 1800.0)\n"
            "forward['jitter_ms'] = (np.abs(rng.normal(8.0, 3.0, len(forward)))).clip(1.0, 50.0)\n"
            "forward['fill_ratio'] = fill_ratio\n"
            "forward['side'] = forward['signal']\n"
            "forward['exchange'] = execution_exchange\n"
            "forward['symbol'] = symbol\n"
            "\n"
            "display_cols = [\n"
            "    'time', 'side', 'micro_alpha', 'alpha_edge_bps', 'total_cost_bps', 'edge_after_cost_bps',\n"
            "    'slippage_bps', 'impact_bps', 'funding_drift_bps', 'basis_drift_bps', 'fill_ratio',\n"
            "    'decision_latency_ms', 'submit_to_fill_ms'\n"
            "]\n"
            "forward[display_cols].tail(50)\n"
        ),
        code_cell(
            "summary = {\n"
            "    'rows': int(len(forward)),\n"
            "    'avg_total_cost_bps': float(forward['total_cost_bps'].mean()),\n"
            "    'avg_edge_after_cost_bps': float(forward['edge_after_cost_bps'].mean()),\n"
            "    'avg_fill_ratio': float(forward['fill_ratio'].mean()),\n"
            "    'avg_submit_to_fill_ms': float(forward['submit_to_fill_ms'].mean())\n"
            "}\n"
            "summary\n"
        ),
        code_cell(
            "persist_forward = os.getenv('DATAMANCY_FORWARD_TEST_PERSIST', 'true').strip().lower() in {'1', 'true', 'yes', 'on'}\n"
            "max_rows_to_insert = max(1, int(os.getenv('DATAMANCY_FORWARD_TEST_MAX_ROWS', '500')))\n"
            "\n"
            "backtest_edge = pd.read_sql(text('''\n"
            "SELECT CASE WHEN trades > 0 THEN (net_return_pct * 100.0) / trades ELSE net_return_pct * 100.0 END AS edge_bps\n"
            "FROM strategy_backtest_runs\n"
            "WHERE strategy_name = :strategy_name\n"
            "  AND symbol = :symbol\n"
            "ORDER BY run_at DESC\n"
            "LIMIT 1\n"
            "'''), engine, params={'strategy_name': strategy_name, 'symbol': symbol})\n"
            "baseline_backtest_edge_bps = float(backtest_edge['edge_bps'].iloc[0]) if not backtest_edge.empty else None\n"
            "\n"
            "if persist_forward and not forward.empty:\n"
            "    rows = forward.tail(max_rows_to_insert).copy()\n"
            "    avg_slippage = float(rows['slippage_bps'].mean())\n"
            "    avg_fill_ratio = float(rows['fill_ratio'].mean())\n"
            "    avg_submit_fill = float(rows['submit_to_fill_ms'].mean())\n"
            "    first_observed_at = pd.to_datetime(rows['time'].min()).to_pydatetime()\n"
            "    last_observed_at = pd.to_datetime(rows['time'].max()).to_pydatetime()\n"
            "\n"
            "    with engine.begin() as conn:\n"
            "        delete_scope = {\n"
            "            'strategy_name': strategy_name,\n"
            "            'exchange': execution_exchange,\n"
            "            'symbol': symbol,\n"
            "            'first_observed_at': first_observed_at,\n"
            "            'last_observed_at': last_observed_at,\n"
            "        }\n"
            "        conn.execute(text('''\n"
            "            DELETE FROM strategy_live_backtest_drift\n"
            "            WHERE strategy_name = :strategy_name\n"
            "              AND symbol = :symbol\n"
            "              AND observed_at BETWEEN :first_observed_at AND :last_observed_at\n"
            "        '''), {\n"
            "            'strategy_name': strategy_name,\n"
            "            'symbol': symbol,\n"
            "            'first_observed_at': first_observed_at,\n"
            "            'last_observed_at': last_observed_at,\n"
            "        })\n"
            "        conn.execute(text('''\n"
            "            DELETE FROM strategy_execution_costs\n"
            "            WHERE strategy_name = :strategy_name\n"
            "              AND exchange = :exchange\n"
            "              AND symbol = :symbol\n"
            "              AND observed_at BETWEEN :first_observed_at AND :last_observed_at\n"
            "        '''), delete_scope)\n"
            "        conn.execute(text('''\n"
            "            DELETE FROM strategy_latency_metrics\n"
            "            WHERE strategy_name = :strategy_name\n"
            "              AND exchange = :exchange\n"
            "              AND symbol = :symbol\n"
            "              AND observed_at BETWEEN :first_observed_at AND :last_observed_at\n"
            "        '''), delete_scope)\n"
            "        for _, row in rows.iterrows():\n"
            "            observed_at = pd.to_datetime(row['time']).to_pydatetime()\n"
            "            metadata = json.dumps({\n"
            "                'source': 'notebook-forward-mainnet-data',\n"
            "                'executionMode': execution_mode,\n"
            "                'marketDataMode': market_data_mode,\n"
            "                'marketDataExchange': market_data_exchange,\n"
            "                'executionExchange': execution_exchange,\n"
            "                'status': 'SIMULATED',\n"
            "                'urgencyClass': 'normal',\n"
            "                'fillRatio': float(row['fill_ratio']),\n"
            "                'quoteAgeMs': 0,\n"
            "                'feeTier': fee_tier\n"
            "            })\n"
            "\n"
            "            conn.execute(text('''\n"
            "                INSERT INTO strategy_latency_metrics (\n"
            "                    observed_at, strategy_name, exchange, symbol,\n"
            "                    decision_latency_ms, submit_to_ack_ms, submit_to_fill_ms,\n"
            "                    p50_roundtrip_ms, p95_roundtrip_ms, p99_roundtrip_ms,\n"
            "                    jitter_ms, metadata\n"
            "                ) VALUES (\n"
            "                    :observed_at, :strategy_name, :exchange, :symbol,\n"
            "                    :decision_latency_ms, :submit_to_ack_ms, :submit_to_fill_ms,\n"
            "                    :p50_roundtrip_ms, :p95_roundtrip_ms, :p99_roundtrip_ms,\n"
            "                    :jitter_ms, CAST(:metadata AS jsonb)\n"
            "                )\n"
            "            '''), {\n"
            "                'observed_at': observed_at,\n"
            "                'strategy_name': strategy_name,\n"
            "                'exchange': execution_exchange,\n"
            "                'symbol': symbol,\n"
            "                'decision_latency_ms': float(row['decision_latency_ms']),\n"
            "                'submit_to_ack_ms': float(row['submit_to_ack_ms']),\n"
            "                'submit_to_fill_ms': float(row['submit_to_fill_ms']),\n"
            "                'p50_roundtrip_ms': float(row['p50_roundtrip_ms']),\n"
            "                'p95_roundtrip_ms': float(row['p95_roundtrip_ms']),\n"
            "                'p99_roundtrip_ms': float(row['p99_roundtrip_ms']),\n"
            "                'jitter_ms': float(row['jitter_ms']),\n"
            "                'metadata': metadata\n"
            "            })\n"
            "\n"
            "            conn.execute(text('''\n"
            "                INSERT INTO strategy_execution_costs (\n"
            "                    observed_at, strategy_name, exchange, symbol, side,\n"
            "                    fee_bps, fee_tier, fee_tier_adjustment_bps,\n"
            "                    maker_fee_bps, taker_fee_bps,\n"
            "                    spread_cost_bps, slippage_bps, impact_bps,\n"
            "                    adverse_selection_bps, funding_drift_bps, basis_drift_bps,\n"
            "                    total_cost_bps, edge_after_cost_bps,\n"
            "                    estimated_fee_usd, estimated_cost_usd, metadata\n"
            "                ) VALUES (\n"
            "                    :observed_at, :strategy_name, :exchange, :symbol, :side,\n"
            "                    :fee_bps, :fee_tier, :fee_tier_adjustment_bps,\n"
            "                    :maker_fee_bps, :taker_fee_bps,\n"
            "                    :spread_cost_bps, :slippage_bps, :impact_bps,\n"
            "                    :adverse_selection_bps, :funding_drift_bps, :basis_drift_bps,\n"
            "                    :total_cost_bps, :edge_after_cost_bps,\n"
            "                    :estimated_fee_usd, :estimated_cost_usd, CAST(:metadata AS jsonb)\n"
            "                )\n"
            "            '''), {\n"
            "                'observed_at': observed_at,\n"
            "                'strategy_name': strategy_name,\n"
            "                'exchange': execution_exchange,\n"
            "                'symbol': symbol,\n"
            "                'side': row['side'],\n"
            "                'fee_bps': float(row['fee_bps']),\n"
            "                'fee_tier': fee_tier,\n"
            "                'fee_tier_adjustment_bps': float(row['fee_tier_adjustment_bps']),\n"
            "                'maker_fee_bps': float(row['maker_fee_bps']),\n"
            "                'taker_fee_bps': float(row['taker_fee_bps']),\n"
            "                'spread_cost_bps': float(row['spread_cost_bps']),\n"
            "                'slippage_bps': float(row['slippage_bps']),\n"
            "                'impact_bps': float(row['impact_bps']),\n"
            "                'adverse_selection_bps': float(row['adverse_selection_bps']),\n"
            "                'funding_drift_bps': float(row['funding_drift_bps']),\n"
            "                'basis_drift_bps': float(row['basis_drift_bps']),\n"
            "                'total_cost_bps': float(row['total_cost_bps']),\n"
            "                'edge_after_cost_bps': float(row['edge_after_cost_bps']),\n"
            "                'estimated_fee_usd': float(row['estimated_fee_usd']),\n"
            "                'estimated_cost_usd': float(row['estimated_cost_usd']),\n"
            "                'metadata': metadata\n"
            "            })\n"
            "\n"
            "            slippage_drift = float(row['slippage_bps']) - avg_slippage\n"
            "            fill_quality_delta = (avg_fill_ratio - float(row['fill_ratio'])) * 10000.0\n"
            "            latency_drift = float(row['submit_to_fill_ms']) - avg_submit_fill\n"
            "            edge_decay = 0.0 if baseline_backtest_edge_bps is None else max(0.0, baseline_backtest_edge_bps - float(row['edge_after_cost_bps']))\n"
            "            drift_score = max(0.0, slippage_drift) + max(0.0, fill_quality_delta) + max(0.0, latency_drift) / 10.0 + edge_decay\n"
            "\n"
            "            conn.execute(text('''\n"
            "                INSERT INTO strategy_live_backtest_drift (\n"
            "                    observed_at, strategy_name, symbol,\n"
            "                    live_edge_bps, backtest_edge_bps,\n"
            "                    fill_quality_delta_bps, slippage_drift_bps,\n"
            "                    latency_drift_ms, drift_score, metadata\n"
            "                ) VALUES (\n"
            "                    :observed_at, :strategy_name, :symbol,\n"
            "                    :live_edge_bps, :backtest_edge_bps,\n"
            "                    :fill_quality_delta_bps, :slippage_drift_bps,\n"
            "                    :latency_drift_ms, :drift_score, CAST(:metadata AS jsonb)\n"
            "                )\n"
            "            '''), {\n"
            "                'observed_at': observed_at,\n"
            "                'strategy_name': strategy_name,\n"
            "                'symbol': symbol,\n"
            "                'live_edge_bps': float(row['edge_after_cost_bps']),\n"
            "                'backtest_edge_bps': baseline_backtest_edge_bps,\n"
            "                'fill_quality_delta_bps': float(fill_quality_delta),\n"
            "                'slippage_drift_bps': float(slippage_drift),\n"
            "                'latency_drift_ms': float(latency_drift),\n"
            "                'drift_score': float(drift_score),\n"
            "                'metadata': metadata\n"
            "            })\n"
            "    print(f'Persisted {len(rows)} forward-test rows into execution/drift telemetry tables for {execution_exchange}/{symbol} using {market_data_exchange} market data.')\n"
            "else:\n"
            "    print('Persistence disabled (set DATAMANCY_FORWARD_TEST_PERSIST=true to feed Grafana).')\n"
        )
    ]
)

strict_alpha_proof_helpers = (
    "import json\n"
    "import os\n"
    "import shlex\n"
    "import subprocess\n"
    "import sys\n"
    "from pathlib import Path\n"
    "\n"
    "def find_repo_root(script_name):\n"
    "    candidates = [Path('/home/jovyan/work'), Path.cwd(), *Path.cwd().parents]\n"
    "    seen = set()\n"
    "    for candidate in candidates:\n"
    "        candidate = candidate.resolve()\n"
    "        if str(candidate) in seen:\n"
    "            continue\n"
    "        seen.add(str(candidate))\n"
    "        if (candidate / 'scripts' / 'trading' / script_name).exists():\n"
    "            return candidate\n"
    "    raise RuntimeError(f'Could not locate repo root for {script_name}')\n"
    "\n"
    "def extract_last_json(stdout):\n"
    "    decoder = json.JSONDecoder()\n"
    "    for index, ch in enumerate(stdout):\n"
    "        if ch not in '[{':\n"
    "            continue\n"
    "        try:\n"
    "            payload, end = decoder.raw_decode(stdout[index:])\n"
    "        except json.JSONDecodeError:\n"
    "            continue\n"
    "        if stdout[index + end:].strip() == '':\n"
    "            return payload\n"
    "    raise RuntimeError('Could not parse trailing JSON payload from proof output')\n"
    "\n"
    "def sql_list(values):\n"
    "    return ', '.join(\"'\" + str(value).replace(\"'\", \"''\") + \"'\" for value in values)\n"
)

write_notebook(
    "16_strict_alpha_backtest_proof.ipynb",
    [
        markdown_cell(
            "# Strict Alpha Backtest Proof\n\n"
            "Runs the canonical `scripts/trading/alpha_proof.py` walk-forward proof from inside Jupyter.\n\n"
            "Use this notebook when you want the notebook surface, persisted strategy tables, and Grafana to all reflect the same proof logic."
        ),
        code_cell(db_bootstrap),
        code_cell(strict_alpha_proof_helpers),
        code_cell(
            "repo_root = find_repo_root('alpha_proof.py')\n"
            "script_dir = repo_root / 'scripts' / 'trading'\n"
            "proof_script = script_dir / 'alpha_proof.py'\n"
            "\n"
            "exchange = os.getenv('DATAMANCY_ALPHA_PROOF_EXCHANGE', 'hyperliquid_mainnet').strip() or 'hyperliquid_mainnet'\n"
            "family = os.getenv('DATAMANCY_ALPHA_PROOF_FAMILY', 'tail_short_v2').strip() or 'tail_short_v2'\n"
            "symbols = os.getenv('DATAMANCY_ALPHA_PROOF_SYMBOLS', 'BTC,ETH,SOL').strip() or 'BTC,ETH,SOL'\n"
            "lookback = os.getenv('DATAMANCY_ALPHA_PROOF_LOOKBACK', '10 days').strip() or '10 days'\n"
            "train_hours = int(os.getenv('DATAMANCY_ALPHA_PROOF_TRAIN_HOURS', '16'))\n"
            "test_hours = int(os.getenv('DATAMANCY_ALPHA_PROOF_TEST_HOURS', '4'))\n"
            "step_hours = int(os.getenv('DATAMANCY_ALPHA_PROOF_STEP_HOURS', '4'))\n"
            "min_bars = int(os.getenv('DATAMANCY_ALPHA_PROOF_MIN_BARS', '1200'))\n"
            "max_gap_minutes = float(os.getenv('DATAMANCY_ALPHA_PROOF_MAX_GAP_MINUTES', '1.5'))\n"
            "notional_usd = float(os.getenv('DATAMANCY_ALPHA_PROOF_NOTIONAL_USD', '7500'))\n"
            "strategy_prefix = os.getenv(\n"
            "    'DATAMANCY_ALPHA_PROOF_STRATEGY_PREFIX',\n"
            "    'alpha_proof_tail_short_v2_fixed' if family == 'tail_short_v2' else f'alpha_proof_{family}'\n"
            ")\n"
            "fixed_param_label = os.getenv(\n"
            "    'DATAMANCY_ALPHA_PROOF_FIXED_PARAM_LABEL',\n"
            "    'family=tail_short_v2,entry_q=0.10,hold=30,spread_q=0.75,depth_q=0.30' if family == 'tail_short_v2' else ''\n"
            ").strip()\n"
            "use_trade_flow = os.getenv('DATAMANCY_ALPHA_PROOF_USE_TRADE_FLOW', 'true').strip().lower() in {'1', 'true', 'yes', 'on'}\n"
            "persist = os.getenv('DATAMANCY_ALPHA_PROOF_PERSIST', 'true').strip().lower() in {'1', 'true', 'yes', 'on'}\n"
            "\n"
            "cmd = [\n"
            "    sys.executable, str(proof_script),\n"
            "    '--exchange', exchange,\n"
            "    '--family', family,\n"
            "    '--symbols', symbols,\n"
            "    '--lookback', lookback,\n"
            "    '--train-hours', str(train_hours),\n"
            "    '--test-hours', str(test_hours),\n"
            "    '--step-hours', str(step_hours),\n"
            "    '--min-bars', str(min_bars),\n"
            "    '--max-gap-minutes', str(max_gap_minutes),\n"
            "    '--notional-usd', str(notional_usd),\n"
            "    '--strategy-prefix', strategy_prefix,\n"
            "]\n"
            "cmd.append('--use-trade-flow' if use_trade_flow else '--no-trade-flow')\n"
            "cmd.append('--persist' if persist else '--no-persist')\n"
            "if fixed_param_label:\n"
            "    cmd.extend(['--fixed-param-label', fixed_param_label])\n"
            "\n"
            "run_env = dict(os.environ)\n"
            "run_env['PYTHONPATH'] = str(script_dir) + (os.pathsep + run_env['PYTHONPATH'] if run_env.get('PYTHONPATH') else '')\n"
            "\n"
            "print('Running:', ' '.join(shlex.quote(part) for part in cmd))\n"
            "completed = subprocess.run(cmd, cwd=repo_root, env=run_env, capture_output=True, text=True)\n"
            "print(completed.stdout)\n"
            "if completed.stderr.strip():\n"
            "    print(completed.stderr)\n"
            "if completed.returncode != 0:\n"
            "    raise RuntimeError(f'alpha_proof.py failed with exit code {completed.returncode}')\n"
            "\n"
            "results = extract_last_json(completed.stdout)\n"
            "summary = pd.DataFrame(results)\n"
            "summary\n"
        ),
        code_cell(
            "columns = [\n"
            "    column for column in [\n"
            "        'symbol', 'status', 'bars', 'windows', 'positive_windows', 'active_windows',\n"
            "        'trades', 'net_return_pct', 'edge_bps_per_trade', 'avg_total_cost_bps', 'proof_reasons'\n"
            "    ] if column in summary.columns\n"
            "]\n"
            "summary[columns].sort_values(['status', 'symbol']).reset_index(drop=True) if columns else summary\n"
        ),
        code_cell(
            "strategy_names = []\n"
            "for row in results:\n"
            "    symbol = str(row.get('symbol', '')).strip()\n"
            "    if not symbol:\n"
            "        continue\n"
            "    strategy_names.append(row.get('strategy_name') or f'{strategy_prefix}_{symbol.lower()}')\n"
            "strategy_names = sorted(set(strategy_names))\n"
            "\n"
            "if not strategy_names:\n"
            "    print('No strategy names emitted by alpha proof.')\n"
            "else:\n"
            "    strategy_sql = sql_list(strategy_names)\n"
            "    backtest_runs = pd.read_sql(text(f'''\n"
            "        SELECT strategy_name, symbol, timeframe, run_at, trades,\n"
            "               net_return_pct, sharpe, max_drawdown_pct,\n"
            "               metrics->>'proof_status' AS proof_status,\n"
            "               metrics->'contiguity' AS contiguity\n"
            "        FROM strategy_backtest_runs\n"
            "        WHERE strategy_name IN ({strategy_sql})\n"
            "        ORDER BY run_at DESC, strategy_name\n"
            "    '''), engine)\n"
            "    walkforward_runs = pd.read_sql(text(f'''\n"
            "        SELECT strategy_name, symbol, train_start, train_end, test_start, test_end,\n"
            "               trades, net_return_pct, sharpe, max_drawdown_pct\n"
            "        FROM strategy_walkforward_runs\n"
            "        WHERE strategy_name IN ({strategy_sql})\n"
            "        ORDER BY strategy_name, test_start DESC\n"
            "    '''), engine)\n"
            "    sensitivity = pd.read_sql(text(f'''\n"
            "        SELECT strategy_name, symbol, parameter_name, parameter_value,\n"
            "               net_return_pct, sharpe, max_drawdown_pct, trades\n"
            "        FROM strategy_sensitivity_sweeps\n"
            "        WHERE strategy_name IN ({strategy_sql})\n"
            "        ORDER BY strategy_name, parameter_name\n"
            "    '''), engine)\n"
            "    print('Backtest runs')\n"
            "    display(backtest_runs)\n"
            "    print('Walk-forward rows')\n"
            "    display(walkforward_runs)\n"
            "    print('Sensitivity rows')\n"
            "    display(sensitivity)\n"
        )
    ]
)

write_notebook(
    "17_strict_forward_alpha_proof.ipynb",
    [
        markdown_cell(
            "# Strict Forward Alpha Proof\n\n"
            "Runs the canonical `scripts/trading/forward_alpha_proof.py` paper-forward proof from inside Jupyter.\n\n"
            "Use this notebook to test the exact fixed backtest strategy against the freshest mainnet data slice and feed Grafana with the same drift, latency, and execution-cost tables used elsewhere in the stack."
        ),
        code_cell(db_bootstrap),
        code_cell(strict_alpha_proof_helpers),
        code_cell(
            "repo_root = find_repo_root('forward_alpha_proof.py')\n"
            "script_dir = repo_root / 'scripts' / 'trading'\n"
            "proof_script = script_dir / 'forward_alpha_proof.py'\n"
            "\n"
            "exchange = os.getenv('DATAMANCY_FORWARD_ALPHA_EXCHANGE', 'hyperliquid_mainnet').strip() or 'hyperliquid_mainnet'\n"
            "family = os.getenv('DATAMANCY_FORWARD_ALPHA_FAMILY', 'tail_short_v2').strip() or 'tail_short_v2'\n"
            "symbols = os.getenv('DATAMANCY_FORWARD_ALPHA_SYMBOLS', 'BTC,ETH,SOL').strip() or 'BTC,ETH,SOL'\n"
            "lookback = os.getenv('DATAMANCY_FORWARD_ALPHA_LOOKBACK', '24 hours').strip() or '24 hours'\n"
            "calibration_hours = int(os.getenv('DATAMANCY_FORWARD_ALPHA_CALIBRATION_HOURS', '1'))\n"
            "forward_hours = int(os.getenv('DATAMANCY_FORWARD_ALPHA_FORWARD_HOURS', '1'))\n"
            "max_gap_minutes = float(os.getenv('DATAMANCY_FORWARD_ALPHA_MAX_GAP_MINUTES', '1.5'))\n"
            "max_staleness_minutes = float(os.getenv('DATAMANCY_FORWARD_ALPHA_MAX_STALENESS_MINUTES', '3'))\n"
            "notional_usd = float(os.getenv('DATAMANCY_FORWARD_ALPHA_NOTIONAL_USD', '7500'))\n"
            "strategy_prefix = os.getenv('DATAMANCY_FORWARD_ALPHA_STRATEGY_PREFIX', 'alpha_proof_tail_short_v2_fixed').strip() or 'alpha_proof_tail_short_v2_fixed'\n"
            "fixed_param_label = os.getenv(\n"
            "    'DATAMANCY_FORWARD_ALPHA_FIXED_PARAM_LABEL',\n"
            "    'family=tail_short_v2,entry_q=0.10,hold=30,spread_q=0.75,depth_q=0.30'\n"
            ").strip()\n"
            "use_trade_flow = os.getenv('DATAMANCY_FORWARD_ALPHA_USE_TRADE_FLOW', 'true').strip().lower() in {'1', 'true', 'yes', 'on'}\n"
            "persist = os.getenv('DATAMANCY_FORWARD_ALPHA_PERSIST', 'true').strip().lower() in {'1', 'true', 'yes', 'on'}\n"
            "\n"
            "cmd = [\n"
            "    sys.executable, str(proof_script),\n"
            "    '--exchange', exchange,\n"
            "    '--family', family,\n"
            "    '--fixed-param-label', fixed_param_label,\n"
            "    '--symbols', symbols,\n"
            "    '--lookback', lookback,\n"
            "    '--calibration-hours', str(calibration_hours),\n"
            "    '--forward-hours', str(forward_hours),\n"
            "    '--max-gap-minutes', str(max_gap_minutes),\n"
            "    '--max-staleness-minutes', str(max_staleness_minutes),\n"
            "    '--notional-usd', str(notional_usd),\n"
            "    '--strategy-prefix', strategy_prefix,\n"
            "]\n"
            "cmd.append('--use-trade-flow' if use_trade_flow else '--no-trade-flow')\n"
            "cmd.append('--persist' if persist else '--no-persist')\n"
            "\n"
            "run_env = dict(os.environ)\n"
            "run_env['PYTHONPATH'] = str(script_dir) + (os.pathsep + run_env['PYTHONPATH'] if run_env.get('PYTHONPATH') else '')\n"
            "\n"
            "print('Running:', ' '.join(shlex.quote(part) for part in cmd))\n"
            "completed = subprocess.run(cmd, cwd=repo_root, env=run_env, capture_output=True, text=True)\n"
            "print(completed.stdout)\n"
            "if completed.stderr.strip():\n"
            "    print(completed.stderr)\n"
            "if completed.returncode != 0:\n"
            "    raise RuntimeError(f'forward_alpha_proof.py failed with exit code {completed.returncode}')\n"
            "\n"
            "results = extract_last_json(completed.stdout)\n"
            "summary = pd.DataFrame(results)\n"
            "summary\n"
        ),
        code_cell(
            "columns = [\n"
            "    column for column in [\n"
            "        'symbol', 'status', 'bars', 'required_bars', 'latest_segment_bars', 'latest_bar_age_minutes',\n"
            "        'trades', 'net_return_pct', 'avg_edge_after_cost_bps', 'baseline_backtest_edge_bps'\n"
            "    ] if column in summary.columns\n"
            "]\n"
            "summary[columns].sort_values(['status', 'symbol']).reset_index(drop=True) if columns else summary\n"
        ),
        code_cell(
            "strategy_names = []\n"
            "for row in results:\n"
            "    symbol = str(row.get('symbol', '')).strip()\n"
            "    if not symbol:\n"
            "        continue\n"
            "    strategy_names.append(row.get('strategy_name') or f'{strategy_prefix}_{symbol.lower()}')\n"
            "strategy_names = sorted(set(strategy_names))\n"
            "\n"
            "if not strategy_names:\n"
            "    print('No strategy names emitted by forward alpha proof.')\n"
            "else:\n"
            "    strategy_sql = sql_list(strategy_names)\n"
            "    latest_backtest = pd.read_sql(text(f'''\n"
            "        SELECT strategy_name, symbol, run_at, trades, net_return_pct,\n"
            "               sharpe, max_drawdown_pct, metrics->>'proof_status' AS proof_status\n"
            "        FROM strategy_backtest_runs\n"
            "        WHERE strategy_name IN ({strategy_sql})\n"
            "        ORDER BY run_at DESC, strategy_name\n"
            "    '''), engine)\n"
            "    latency = pd.read_sql(text(f'''\n"
            "        SELECT strategy_name, exchange, symbol, observed_at,\n"
            "               decision_latency_ms, submit_to_ack_ms, submit_to_fill_ms,\n"
            "               p50_roundtrip_ms, p95_roundtrip_ms, p99_roundtrip_ms, jitter_ms\n"
            "        FROM strategy_latency_metrics\n"
            "        WHERE strategy_name IN ({strategy_sql})\n"
            "          AND observed_at >= NOW() - INTERVAL '7 days'\n"
            "        ORDER BY observed_at DESC\n"
            "    '''), engine)\n"
            "    costs = pd.read_sql(text(f'''\n"
            "        SELECT strategy_name, exchange, symbol, observed_at, side,\n"
            "               fee_bps, spread_cost_bps, slippage_bps, impact_bps,\n"
            "               funding_drift_bps, basis_drift_bps, total_cost_bps, edge_after_cost_bps\n"
            "        FROM strategy_execution_costs\n"
            "        WHERE strategy_name IN ({strategy_sql})\n"
            "          AND observed_at >= NOW() - INTERVAL '7 days'\n"
            "        ORDER BY observed_at DESC\n"
            "    '''), engine)\n"
            "    drift = pd.read_sql(text(f'''\n"
            "        SELECT strategy_name, symbol, observed_at,\n"
            "               live_edge_bps, backtest_edge_bps,\n"
            "               fill_quality_delta_bps, slippage_drift_bps,\n"
            "               latency_drift_ms, drift_score\n"
            "        FROM strategy_live_backtest_drift\n"
            "        WHERE strategy_name IN ({strategy_sql})\n"
            "          AND observed_at >= NOW() - INTERVAL '7 days'\n"
            "        ORDER BY observed_at DESC\n"
            "    '''), engine)\n"
            "    print('Latest backtest baselines')\n"
            "    display(latest_backtest)\n"
            "    print('Latency telemetry')\n"
            "    display(latency)\n"
            "    print('Execution cost telemetry')\n"
            "    display(costs)\n"
            "    print('Live-vs-backtest drift telemetry')\n"
            "    display(drift)\n"
        )
    ]
)
PY
