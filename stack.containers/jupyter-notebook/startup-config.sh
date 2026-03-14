#!/bin/bash
mkdir -p /home/jovyan/.jupyter
cat > /home/jovyan/.jupyter/jupyter_jupyter_ai_config.json <<'EOF'
{
  "AiExtension": {
    "model_parameters": {
      "openai-chat:qwen2.5-0.5b": {
        "api_base": "http://litellm:4000/v1",
        "api_key": "${LITELLM_API_KEY}"
      }
    }
  }
}
EOF
cat > /home/jovyan/.env <<'EOF'
OPENAI_API_BASE=http://litellm:4000/v1
OPENAI_API_KEY=${LITELLM_API_KEY}
VLLM_API_BASE=http://vllm:8000/v1
VLLM_API_KEY=unused
DEFAULT_LLM_MODEL=qwen2.5-0.5b
DEFAULT_EMBEDDING_MODEL=embed-small
LANGCHAIN_TRACING_V2=false
POSTGRES_HOST=${POSTGRES_HOST:-postgres}
POSTGRES_PORT=${POSTGRES_PORT:-5432}
POSTGRES_DB=${POSTGRES_DB:-datamancy}
POSTGRES_USER=${POSTGRES_USER:-pipeline_user}
POSTGRES_PASSWORD=${POSTGRES_PASSWORD}
EOF

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
            "symbol = 'BTC'\n"
            "interval = 'candle_1m'\n"
            "lookback = '30 days'\n"
            "\n"
            "sql = text('''\n"
            "SELECT time, open, high, low, close, volume\n"
            "FROM market_data\n"
            "WHERE symbol = :symbol\n"
            "  AND exchange = 'hyperliquid'\n"
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
            "plot_df = pd.read_sql(text('''\n"
            "WITH px AS (\n"
            "  SELECT time_bucket('15 minutes', time) AS ts, AVG(close) AS price\n"
            "  FROM market_data\n"
            "  WHERE symbol='BTC' AND exchange='hyperliquid' AND data_type='candle_1m'\n"
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
