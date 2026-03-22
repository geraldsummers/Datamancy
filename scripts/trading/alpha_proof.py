#!/usr/bin/env python3

import argparse
import json
import os
import warnings
from collections import Counter
from dataclasses import dataclass
from typing import Sequence

import numpy as np
import pandas as pd
import psycopg2

warnings.filterwarnings("ignore", category=UserWarning, module="pandas.io.sql")


@dataclass(frozen=True)
class StrategyParams:
    family: str
    direction: float = 1.0
    book_weight: float = 0.0
    micro_weight: float = 0.0
    flow_weight: float = 0.0
    momentum_weight: float = 0.0
    entry_z: float = 0.0
    exit_z: float = 0.0
    entry_quantile: float = 0.0
    hold_bars: int = 0
    spread_cap_quantile: float = 0.0
    depth_floor_quantile: float = 0.0

    @property
    def label(self) -> str:
        if self.family == "tail_short_v2":
            return (
                f"family={self.family},entry_q={self.entry_quantile:.2f},hold={self.hold_bars},"
                f"spread_q={self.spread_cap_quantile:.2f},depth_q={self.depth_floor_quantile:.2f}"
            )
        return (
            f"family={self.family},dir={self.direction:.0f},book={self.book_weight:.2f},micro={self.micro_weight:.2f},"
            f"flow={self.flow_weight:.2f},mom={self.momentum_weight:.2f},entry={self.entry_z:.2f},exit={self.exit_z:.2f}"
        )


@dataclass(frozen=True)
class CostScenario:
    name: str
    fee_shift_bps: float = 0.0
    slippage_shift_bps: float = 0.0
    latency_ms_shift: float = 0.0
    impact_mult: float = 1.0
    note: str = ""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run walk-forward alpha proof against Datamancy market data")
    parser.add_argument("--exchange", default=os.getenv("ALPHA_PROOF_EXCHANGE", "hyperliquid_mainnet"))
    parser.add_argument("--family", default=os.getenv("ALPHA_PROOF_FAMILY", "microstructure_v1"))
    parser.add_argument("--fixed-param-label", default=os.getenv("ALPHA_PROOF_FIXED_PARAM_LABEL"))
    parser.add_argument(
        "--symbols",
        default=os.getenv("ALPHA_PROOF_SYMBOLS", "BTC,ETH,SOL,AVAX,LINK"),
        help="Comma-separated symbol list",
    )
    parser.add_argument("--lookback", default=os.getenv("ALPHA_PROOF_LOOKBACK", "5 days"))
    parser.add_argument("--train-hours", type=int, default=int(os.getenv("ALPHA_PROOF_TRAIN_HOURS", "12")))
    parser.add_argument("--test-hours", type=int, default=int(os.getenv("ALPHA_PROOF_TEST_HOURS", "4")))
    parser.add_argument("--step-hours", type=int, default=int(os.getenv("ALPHA_PROOF_STEP_HOURS", "4")))
    parser.add_argument("--min-bars", type=int, default=int(os.getenv("ALPHA_PROOF_MIN_BARS", "1200")))
    parser.add_argument("--notional-usd", type=float, default=float(os.getenv("ALPHA_PROOF_NOTIONAL_USD", "7500")))
    parser.add_argument(
        "--strategy-prefix",
        default=os.getenv("ALPHA_PROOF_STRATEGY_PREFIX", "alpha_proof_microstructure_v1"),
    )
    parser.add_argument(
        "--db-host",
        default=os.getenv("POSTGRES_HOST", "postgres"),
    )
    parser.add_argument(
        "--db-port",
        type=int,
        default=int(os.getenv("POSTGRES_PORT", "5432")),
    )
    parser.add_argument(
        "--db-name",
        default=os.getenv("POSTGRES_DB", "datamancy"),
    )
    parser.add_argument(
        "--db-user",
        default=os.getenv("POSTGRES_USER", "pipeline_user"),
    )
    parser.add_argument(
        "--db-password",
        default=os.getenv("POSTGRES_PASSWORD", ""),
    )
    parser.add_argument(
        "--persist",
        dest="persist",
        action="store_true",
        default=_env_flag("ALPHA_PROOF_PERSIST", True),
    )
    parser.add_argument("--no-persist", dest="persist", action="store_false")
    parser.add_argument(
        "--use-trade-flow",
        dest="use_trade_flow",
        action="store_true",
        default=_env_flag("ALPHA_PROOF_USE_TRADE_FLOW", False),
    )
    parser.add_argument("--no-trade-flow", dest="use_trade_flow", action="store_false")
    return parser.parse_args()


def _env_flag(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def sql_quote(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def spread_pct_to_bps(series: pd.Series) -> pd.Series:
    # orderbook_data.spread_pct is stored in percent units, not fraction units.
    return (series.clip(lower=0) * 100.0).fillna(0.0)


def depth_notional_usd(df: pd.DataFrame, depth_units: pd.Series) -> pd.Series:
    # orderbook_data bid/ask depth columns are stored in base-asset size, not quote notional.
    reference_price = df["mid_price"].replace(0, np.nan).fillna(df["close"]).replace(0, np.nan)
    return (depth_units * reference_price).replace([np.inf, -np.inf], np.nan)


def parse_strategy_label(label: str) -> StrategyParams:
    raw = (label or "").strip()
    if not raw:
        raise SystemExit("fixed strategy label must not be empty")

    parts = {}
    for item in raw.split(","):
        if "=" not in item:
            raise SystemExit(f"invalid strategy label segment: {item}")
        key, value = item.split("=", 1)
        parts[key.strip()] = value.strip()

    family = parts.get("family")
    if family == "tail_short_v2":
        return StrategyParams(
            family=family,
            entry_quantile=float(parts["entry_q"]),
            hold_bars=int(parts["hold"]),
            spread_cap_quantile=float(parts["spread_q"]),
            depth_floor_quantile=float(parts["depth_q"]),
        )
    if family == "microstructure_v1":
        return StrategyParams(
            family=family,
            direction=float(parts["dir"]),
            book_weight=float(parts["book"]),
            micro_weight=float(parts["micro"]),
            flow_weight=float(parts["flow"]),
            momentum_weight=float(parts["mom"]),
            entry_z=float(parts["entry"]),
            exit_z=float(parts["exit"]),
        )
    raise SystemExit(f"unsupported strategy family in label: {family}")


def resolve_exchange_aliases(exchange: str) -> list[str]:
    normalized = exchange.strip().lower()
    if normalized == "hyperliquid_mainnet":
        return ["hyperliquid", "hyperliquid_mainnet"]
    if normalized == "hyperliquid":
        return ["hyperliquid"]
    if normalized == "hyperliquid_testnet":
        return ["hyperliquid_testnet"]
    return [normalized]


def build_query(exchange_aliases: Sequence[str], use_trade_flow: bool) -> str:
    aliases = ", ".join(sql_quote(alias) for alias in exchange_aliases)
    trade_flow_cte = f"""
,trade_flow AS (
    SELECT
        date_trunc('minute', time) AS minute,
        SUM(CASE WHEN lower(side) = 'buy' THEN COALESCE(size, 0) ELSE 0 END) AS buy_volume,
        SUM(CASE WHEN lower(side) = 'sell' THEN COALESCE(size, 0) ELSE 0 END) AS sell_volume,
        COUNT(*) AS trade_count
    FROM market_data
    WHERE exchange IN ({aliases})
      AND symbol = %(symbol)s
      AND data_type = 'trade'
      AND time >= NOW() - CAST(%(lookback)s AS interval)
    GROUP BY 1
)
""" if use_trade_flow else ""
    funding_cte = f"""
,funding AS (
    SELECT time, funding_rate
    FROM market_data
    WHERE exchange IN ({aliases})
      AND symbol = %(symbol)s
      AND data_type = 'funding'
      AND time >= NOW() - CAST(%(lookback)s AS interval) - INTERVAL '1 hour'
)
"""
    trade_flow_select = (
        "COALESCE(t.buy_volume, 0) AS buy_volume,\n"
        "    COALESCE(t.sell_volume, 0) AS sell_volume,\n"
        "    COALESCE(t.trade_count, 0) AS trade_count,"
        if use_trade_flow else
        "0.0 AS buy_volume,\n"
        "    0.0 AS sell_volume,\n"
        "    0 AS trade_count,"
    )
    trade_flow_join = "LEFT JOIN trade_flow t\n  ON t.minute = c.time\n" if use_trade_flow else ""
    return f"""
WITH candles AS (
    SELECT time, open, high, low, close, volume
    FROM market_data
    WHERE exchange IN ({aliases})
      AND symbol = %(symbol)s
      AND data_type = 'candle_1m'
      AND time >= NOW() - CAST(%(lookback)s AS interval)
)
{trade_flow_cte}
{funding_cte}
SELECT
    c.time,
    c.open,
    c.high,
    c.low,
    c.close,
    c.volume,
    {trade_flow_select}
    o.spread_pct,
    o.bid_depth_10,
    o.ask_depth_10,
    o.mid_price,
    o.best_bid,
    o.best_ask,
    COALESCE(f.funding_rate, 0.0) AS funding_rate
FROM candles c
{trade_flow_join}LEFT JOIN LATERAL (
    SELECT spread_pct, bid_depth_10, ask_depth_10, mid_price, best_bid, best_ask
    FROM orderbook_data o
    WHERE o.exchange IN ({aliases})
      AND o.symbol = %(symbol)s
      AND o.time >= c.time
      AND o.time < c.time + INTERVAL '1 minute'
    ORDER BY o.time DESC
    LIMIT 1
) o ON TRUE
LEFT JOIN LATERAL (
    SELECT funding_rate
    FROM funding f
    WHERE f.time <= c.time
    ORDER BY f.time DESC
    LIMIT 1
) f ON TRUE
ORDER BY c.time ASC
"""


def connect(args: argparse.Namespace):
    if not args.db_password:
        raise SystemExit("POSTGRES_PASSWORD/--db-password is required")
    return psycopg2.connect(
        host=args.db_host,
        port=args.db_port,
        dbname=args.db_name,
        user=args.db_user,
        password=args.db_password,
    )


def load_market_frame(conn, exchange_aliases: Sequence[str], symbol: str, lookback: str, use_trade_flow: bool) -> pd.DataFrame:
    sql = build_query(exchange_aliases, use_trade_flow=use_trade_flow)
    frame = pd.read_sql(sql, conn, params={"symbol": symbol, "lookback": lookback}, parse_dates=["time"])
    frame = frame.drop_duplicates(subset=["time"]).sort_values("time").reset_index(drop=True)
    return frame


def engineer_features(frame: pd.DataFrame) -> pd.DataFrame:
    df = frame.copy()
    numeric_fill = [
        "spread_pct",
        "bid_depth_10",
        "ask_depth_10",
        "mid_price",
        "best_bid",
        "best_ask",
        "buy_volume",
        "sell_volume",
        "trade_count",
        "funding_rate",
    ]
    df[numeric_fill] = df[numeric_fill].ffill().fillna(0)
    df = df[df["close"].notna()].copy()
    if df.empty:
        return df

    depth = (df["bid_depth_10"] + df["ask_depth_10"]).replace(0, np.nan)
    df["ret_1m"] = df["close"].pct_change().fillna(0)
    df["ret_fwd_1m"] = df["close"].shift(-1) / df["close"] - 1.0
    df["vol_30m"] = df["ret_1m"].rolling(30).std().fillna(0)
    df["mom_30m"] = df["close"].pct_change(30).fillna(0)
    df["rev_5m"] = -df["close"].pct_change(5).fillna(0)
    if float(df["trade_count"].sum()) > 0:
        df["signed_flow"] = (
            (df["buy_volume"] - df["sell_volume"]) /
            (df["buy_volume"] + df["sell_volume"]).replace(0, np.nan)
        ).replace([np.inf, -np.inf], np.nan).fillna(0)
    else:
        df["signed_flow"] = (df["ret_1m"] * np.log1p(df["volume"].clip(lower=0))).fillna(0)
    df["book_imbalance"] = ((df["bid_depth_10"] - df["ask_depth_10"]) / depth).replace([np.inf, -np.inf], np.nan).fillna(0)
    df["microprice"] = ((df["best_ask"] * df["bid_depth_10"]) + (df["best_bid"] * df["ask_depth_10"])) / depth
    df["microprice_alpha"] = (
        df["microprice"] / df["mid_price"].replace(0, np.nan) - 1.0
    ).replace([np.inf, -np.inf], np.nan).fillna(0)
    # Hyperliquid funding is paid hourly; convert the latest hourly rate into per-minute carry.
    df["carry_overlay"] = (-(df["funding_rate"].fillna(0.0) / 60.0)).clip(-0.0005, 0.0005)

    for column in ["signed_flow", "book_imbalance", "microprice_alpha", "mom_30m", "rev_5m"]:
        mu = df[column].rolling(180).mean()
        sigma = df[column].rolling(180).std().replace(0, np.nan)
        df[f"{column}_z"] = ((df[column] - mu) / sigma).replace([np.inf, -np.inf], np.nan).fillna(0)

    df = df.dropna(subset=["ret_fwd_1m"]).reset_index(drop=True)
    return df


def build_microstructure_param_grid() -> list[StrategyParams]:
    params: list[StrategyParams] = []
    weight_bases = [
        (0.45, 0.25, 0.25, 0.05),
        (0.40, 0.30, 0.25, 0.05),
        (0.35, 0.35, 0.20, 0.10),
        (0.50, 0.20, 0.25, 0.05),
        (0.42, 0.28, 0.20, 0.10),
    ]
    for direction in (1.0, -1.0):
        for book_weight, micro_weight, flow_weight, momentum_weight in weight_bases:
            for entry_z in (0.8, 1.0, 1.2, 1.4, 1.6):
                for exit_z in (0.1, 0.2, 0.3):
                    params.append(
                        StrategyParams(
                            family="microstructure_v1",
                            direction=direction,
                            book_weight=book_weight,
                            micro_weight=micro_weight,
                            flow_weight=flow_weight,
                            momentum_weight=momentum_weight,
                            entry_z=entry_z,
                            exit_z=exit_z,
                        )
                    )
    return params


def build_tail_short_param_grid() -> list[StrategyParams]:
    params: list[StrategyParams] = []
    for entry_quantile in (0.10, 0.15):
        for hold_bars in (30, 45):
            for spread_cap_quantile in (0.75, 0.80):
                for depth_floor_quantile in (0.30, 0.40):
                    params.append(
                        StrategyParams(
                            family="tail_short_v2",
                            entry_quantile=entry_quantile,
                            hold_bars=hold_bars,
                            spread_cap_quantile=spread_cap_quantile,
                            depth_floor_quantile=depth_floor_quantile,
                        )
                    )
    return params


def build_param_grid(family: str, fixed_param_label: str | None = None) -> list[StrategyParams]:
    if fixed_param_label:
        fixed = parse_strategy_label(fixed_param_label)
        if fixed.family != family:
            raise SystemExit(
                f"fixed strategy label family {fixed.family!r} does not match requested family {family!r}"
            )
        return [fixed]
    if family == "microstructure_v1":
        return build_microstructure_param_grid()
    if family == "tail_short_v2":
        return build_tail_short_param_grid()
    raise SystemExit(f"Unsupported alpha proof family: {family}")


def simulate_microstructure(frame: pd.DataFrame, params: StrategyParams, notional_usd: float, scenario: CostScenario) -> tuple[dict, pd.DataFrame]:
    df = frame.copy()
    vol_median = df["vol_30m"].rolling(180).median().fillna(df["vol_30m"].median())
    regime = np.where(df["vol_30m"] > vol_median, df["rev_5m_z"], df["mom_30m_z"])
    score = (
        params.book_weight * df["book_imbalance_z"] +
        params.micro_weight * df["microprice_alpha_z"] +
        params.flow_weight * df["signed_flow_z"] +
        params.momentum_weight * regime
    ).clip(-5.0, 5.0) * params.direction

    desired = np.where(score > params.entry_z, 1.0, np.where(score < -params.entry_z, -1.0, np.nan))
    desired = pd.Series(desired, index=df.index).ffill().fillna(0.0)
    desired = np.where(np.abs(score) < params.exit_z, 0.0, desired)
    desired = pd.Series(desired, index=df.index).clip(-1.0, 1.0)

    turnover = desired.diff().abs().fillna(np.abs(desired.iloc[0]))
    spread_bps = spread_pct_to_bps(df["spread_pct"])
    depth = (df["bid_depth_10"] + df["ask_depth_10"]).replace(0, np.nan)
    depth_usd = depth_notional_usd(df, depth).fillna(0.0)
    vol_norm = (df["vol_30m"] / vol_median.replace(0, np.nan)).replace([np.inf, -np.inf], np.nan).fillna(1.0)
    latency_ms = 110.0 + scenario.latency_ms_shift

    slippage_bps = (
        1.2 + scenario.slippage_shift_bps +
        spread_bps * 0.30 +
        df["vol_30m"] * 9000.0 +
        np.abs(score) * 0.8
    ).clip(0.5, 40.0)
    impact_bps = (
        0.4 + (notional_usd / depth_usd.replace(0, np.nan)) * 10.0
    ).replace([np.inf, -np.inf], np.nan).fillna(4.0).clip(0.3, 20.0) * scenario.impact_mult
    latency_bps = ((latency_ms / 100.0) * (0.08 + spread_bps / 200.0 + vol_norm * 0.15)).clip(0.0, 8.0)
    adverse_bps = (0.5 + np.abs(score) * 1.2 + latency_bps).clip(0.2, 12.0)

    maker_fee_bps = -1.0 + scenario.fee_shift_bps
    taker_fee_bps = 4.0 + scenario.fee_shift_bps
    fee_bps = np.where(turnover > 0, taker_fee_bps, maker_fee_bps)
    fill_ratio = (
        0.98 -
        spread_bps / 250.0 -
        np.abs(score) * 0.02 -
        latency_ms / 5000.0
    ).clip(0.35, 1.0)
    effective_turnover = turnover * fill_ratio

    total_cost_bps = fee_bps + spread_bps + slippage_bps + impact_bps + adverse_bps
    gross_ret = desired * (df["ret_fwd_1m"].fillna(0.0) + df["carry_overlay"].fillna(0.0))
    strategy_ret = gross_ret - effective_turnover * (total_cost_bps / 10000.0)

    eq = (1.0 + strategy_ret).cumprod()
    peak = eq.cummax()
    drawdown = (eq / peak - 1.0).min()
    trades = int((effective_turnover > 0).sum())
    active_rows = int((strategy_ret != 0).sum())
    total_return = float(eq.iloc[-1] - 1.0) if not eq.empty else 0.0
    sharpe = float((strategy_ret.mean() / (strategy_ret.std() + 1e-12)) * np.sqrt(60 * 24 * 365)) if not strategy_ret.empty else 0.0
    edge_bps_per_trade = float(total_return * 10000.0 / max(trades, 1))

    result_frame = pd.DataFrame(
        {
            "time": df["time"],
            "strategy_ret": strategy_ret,
            "fill_ratio": fill_ratio,
            "total_cost_bps": total_cost_bps,
            "position": desired,
            "vol_30m": df["vol_30m"],
            "depth_usd": depth_usd,
            "trade_flag": (effective_turnover > 0).astype(int),
        }
    )

    metrics = {
        "net_return_pct": total_return * 100.0,
        "max_drawdown_pct": float(drawdown * 100.0),
        "sharpe": sharpe,
        "trades": trades,
        "win_rate": float((strategy_ret > 0).sum() / max(1, active_rows)),
        "edge_bps_per_trade": edge_bps_per_trade,
        "avg_total_cost_bps": float(pd.Series(total_cost_bps).mean()),
        "avg_fill_ratio": float(pd.Series(fill_ratio).mean()),
    }
    return metrics, result_frame


def build_tail_short_score(df: pd.DataFrame) -> pd.Series:
    return (
        0.55 * df["book_imbalance_z"] +
        0.25 * df["microprice_alpha_z"] +
        0.20 * df["signed_flow_z"]
    ).clip(-5.0, 5.0)


def fit_strategy_state(frame: pd.DataFrame, params: StrategyParams) -> dict:
    if params.family != "tail_short_v2":
        return {}
    score = build_tail_short_score(frame)
    spread_bps = spread_pct_to_bps(frame["spread_pct"])
    depth = (frame["bid_depth_10"] + frame["ask_depth_10"]).replace(0, np.nan)
    depth_usd = depth_notional_usd(frame, depth)
    return {
        "entry_threshold": float(score.quantile(params.entry_quantile)),
        "spread_cap_bps": float(spread_bps.quantile(params.spread_cap_quantile)),
        "depth_floor_usd": float(depth_usd.quantile(params.depth_floor_quantile)),
    }


def simulate_tail_short(
    frame: pd.DataFrame,
    params: StrategyParams,
    notional_usd: float,
    scenario: CostScenario,
    fit_state: dict | None = None,
) -> tuple[dict, pd.DataFrame]:
    df = frame.copy()
    df["tail_short_score"] = build_tail_short_score(df)
    spread_bps = spread_pct_to_bps(df["spread_pct"])
    depth = (df["bid_depth_10"] + df["ask_depth_10"]).replace(0, np.nan)
    depth_usd = depth_notional_usd(df, depth).fillna(0.0)
    vol_median = df["vol_30m"].rolling(180).median().fillna(df["vol_30m"].median()).replace(0, np.nan)
    vol_norm = (df["vol_30m"] / vol_median).replace([np.inf, -np.inf], np.nan).fillna(1.0)
    state = fit_state or fit_strategy_state(df, params)

    desired = pd.Series(0.0, index=df.index, dtype=float)
    fill_ratio = pd.Series(1.0, index=df.index, dtype=float)
    entry_turnover = pd.Series(0.0, index=df.index, dtype=float)
    exit_turnover = pd.Series(0.0, index=df.index, dtype=float)
    entry_cost_bps = pd.Series(0.0, index=df.index, dtype=float)
    exit_cost_bps = pd.Series(0.0, index=df.index, dtype=float)
    trade_costs: list[float] = []
    trade_fills: list[float] = []
    latency_ms = 75.0 + scenario.latency_ms_shift

    i = 0
    while i < len(df) - params.hold_bars - 1:
        if (
            df.iloc[i]["tail_short_score"] <= state["entry_threshold"]
            and spread_bps.iloc[i] <= state["spread_cap_bps"]
            and depth_usd.iloc[i] >= state["depth_floor_usd"]
        ):
            entry_i = i + 1
            exit_i = entry_i + params.hold_bars
            if exit_i >= len(df):
                break

            entry_depth_usd = max(depth_usd.iloc[entry_i], 1.0)
            exit_depth_usd = max(depth_usd.iloc[exit_i], 1.0)
            entry_fill = float(
                np.clip(
                    0.95
                    - spread_bps.iloc[entry_i] / 60.0
                    - vol_norm.iloc[entry_i] * 0.05
                    - (notional_usd / entry_depth_usd) * 4.0
                    - latency_ms / 10000.0,
                    0.25,
                    1.0,
                )
            )

            desired.iloc[entry_i:exit_i] = -entry_fill
            fill_ratio.iloc[entry_i:exit_i] = entry_fill
            entry_turnover.iloc[entry_i] = entry_fill
            exit_turnover.iloc[exit_i] = entry_fill

            entry_spread_half = spread_bps.iloc[entry_i] / 2.0
            exit_spread_half = spread_bps.iloc[exit_i] / 2.0
            entry_impact = float(np.clip(0.2 + (notional_usd / entry_depth_usd) * 12.0, 0.2, 10.0)) * scenario.impact_mult
            exit_impact = float(np.clip(0.2 + (notional_usd / exit_depth_usd) * 12.0, 0.2, 10.0)) * scenario.impact_mult
            entry_slippage = float(
                np.clip(
                    0.4 + scenario.slippage_shift_bps * 0.35 + entry_spread_half * 0.20 + vol_norm.iloc[entry_i] * 0.45,
                    0.3,
                    8.0,
                )
            )
            exit_slippage = float(
                np.clip(
                    0.6 + scenario.slippage_shift_bps + exit_spread_half * 0.25 + vol_norm.iloc[exit_i] * 0.55,
                    0.3,
                    10.0,
                )
            )
            entry_cost = 1.0 + scenario.fee_shift_bps + entry_spread_half + entry_slippage + entry_impact
            exit_cost = 4.0 + scenario.fee_shift_bps + exit_spread_half + exit_slippage + exit_impact
            entry_cost_bps.iloc[entry_i] = entry_cost
            exit_cost_bps.iloc[exit_i] = exit_cost
            trade_costs.append(entry_cost + exit_cost)
            trade_fills.append(entry_fill)
            i = exit_i + 1
            continue
        i += 1

    cost = (entry_turnover * entry_cost_bps + exit_turnover * exit_cost_bps) / 10000.0
    strategy_ret = desired * (df["ret_fwd_1m"].fillna(0.0) + df["carry_overlay"].fillna(0.0)) - cost

    eq = (1.0 + strategy_ret).cumprod()
    peak = eq.cummax()
    drawdown = (eq / peak - 1.0).min()
    trades = int((entry_turnover > 0).sum())
    active_rows = int((strategy_ret != 0).sum())
    total_return = float(eq.iloc[-1] - 1.0) if not eq.empty else 0.0
    sharpe = float((strategy_ret.mean() / (strategy_ret.std() + 1e-12)) * np.sqrt(60 * 24 * 365)) if not strategy_ret.empty else 0.0
    edge_bps_per_trade = float(total_return * 10000.0 / max(trades, 1))

    result_frame = pd.DataFrame(
        {
            "time": df["time"],
            "strategy_ret": strategy_ret,
            "fill_ratio": fill_ratio,
            "total_cost_bps": entry_cost_bps + exit_cost_bps,
            "position": desired,
            "vol_30m": df["vol_30m"],
            "depth_usd": depth_usd,
            "trade_flag": (entry_turnover > 0).astype(int),
        }
    )

    metrics = {
        "net_return_pct": total_return * 100.0,
        "max_drawdown_pct": float(drawdown * 100.0),
        "sharpe": sharpe,
        "trades": trades,
        "win_rate": float((strategy_ret > 0).sum() / max(1, active_rows)),
        "edge_bps_per_trade": edge_bps_per_trade,
        "avg_total_cost_bps": float(np.mean(trade_costs)) if trade_costs else 0.0,
        "avg_fill_ratio": float(np.mean(trade_fills)) if trade_fills else 1.0,
    }
    return metrics, result_frame


def simulate(
    frame: pd.DataFrame,
    params: StrategyParams,
    notional_usd: float,
    scenario: CostScenario,
    fit_state: dict | None = None,
) -> tuple[dict, pd.DataFrame]:
    if params.family == "tail_short_v2":
        return simulate_tail_short(frame, params=params, notional_usd=notional_usd, scenario=scenario, fit_state=fit_state)
    return simulate_microstructure(frame, params=params, notional_usd=notional_usd, scenario=scenario)


def train_score(metrics: dict, params: StrategyParams) -> float:
    min_trades = 3 if params.family == "tail_short_v2" else 6
    if metrics["trades"] < min_trades:
        return -1_000_000.0 + metrics["trades"]
    return (
        metrics["edge_bps_per_trade"] +
        (0.25 * metrics["net_return_pct"]) +
        (0.15 * metrics["sharpe"]) +
        (0.02 * metrics["trades"]) +
        (0.10 * metrics["win_rate"] * 100.0) -
        (0.35 * abs(min(metrics["max_drawdown_pct"], 0.0)))
    )


def build_windows(frame: pd.DataFrame, train_bars: int, test_bars: int, step_bars: int) -> list[tuple[int, int, int, int]]:
    windows = []
    limit = len(frame) - train_bars - test_bars
    for start in range(0, max(0, limit) + 1, step_bars):
        train_start = start
        train_end = start + train_bars
        test_start = train_end
        test_end = test_start + test_bars
        if test_end <= len(frame):
            windows.append((train_start, train_end, test_start, test_end))
    return windows


def select_walkforward_params(
    frame: pd.DataFrame,
    param_grid: Sequence[StrategyParams],
    windows: Sequence[tuple[int, int, int, int]],
    notional_usd: float,
) -> list[dict]:
    selected = []
    baseline = CostScenario(name="baseline")
    for train_start, train_end, test_start, test_end in windows:
        train = frame.iloc[train_start:train_end].reset_index(drop=True)
        best_params = None
        best_metrics = None
        best_score = None
        best_fit_state = None
        for params in param_grid:
            fit_state = fit_strategy_state(train, params)
            metrics, _ = simulate(train, params=params, notional_usd=notional_usd, scenario=baseline, fit_state=fit_state)
            score = train_score(metrics, params=params)
            if best_score is None or score > best_score:
                best_score = score
                best_params = params
                best_metrics = metrics
                best_fit_state = fit_state
        assert best_params is not None and best_metrics is not None
        selected.append(
            {
                "params": best_params,
                "fit_state": best_fit_state or {},
                "train_metrics": best_metrics,
                "train_start": frame.iloc[train_start]["time"],
                "train_end": frame.iloc[train_end - 1]["time"],
                "test_start": frame.iloc[test_start]["time"],
                "test_end": frame.iloc[test_end - 1]["time"],
                "test_slice": frame.iloc[test_start:test_end].reset_index(drop=True),
            }
        )
    return selected


def regime_summary(result_frame: pd.DataFrame) -> list[dict]:
    if result_frame.empty:
        return []
    summary = result_frame.copy()
    summary["vol_bucket"] = pd.qcut(summary["vol_30m"].rank(method="first"), 4, labels=["q1", "q2", "q3", "q4"])
    summary["liq_bucket"] = pd.qcut(summary["depth_usd"].rank(method="first"), 4, labels=["l1", "l2", "l3", "l4"])
    grouped = summary.groupby(["vol_bucket", "liq_bucket"], observed=False).agg(
        net_return_pct=("strategy_ret", lambda values: float((((1.0 + values).prod()) - 1.0) * 100.0)),
        avg_cost_bps=("total_cost_bps", "mean"),
        avg_fill_ratio=("fill_ratio", "mean"),
        rows=("strategy_ret", "size"),
    ).reset_index()
    records = []
    for row in grouped.to_dict(orient="records"):
        row["vol_bucket"] = str(row["vol_bucket"])
        row["liq_bucket"] = str(row["liq_bucket"])
        records.append(row)
    return records


def evaluate_selected_windows(
    selected_windows: Sequence[dict],
    notional_usd: float,
    scenario: CostScenario,
) -> tuple[dict, list[dict], pd.DataFrame]:
    window_rows = []
    oos_segments = []
    for window in selected_windows:
        metrics, series = simulate(
            window["test_slice"],
            params=window["params"],
            notional_usd=notional_usd,
            scenario=scenario,
            fit_state=window.get("fit_state") or {},
        )
        window_rows.append(
            {
                "train_start": window["train_start"],
                "train_end": window["train_end"],
                "test_start": window["test_start"],
                "test_end": window["test_end"],
                "params": window["params"],
                "train_metrics": window["train_metrics"],
                "test_metrics": metrics,
            }
        )
        oos_segments.append(series.assign(window_start=window["test_start"], window_end=window["test_end"]))

    oos = pd.concat(oos_segments, ignore_index=True) if oos_segments else pd.DataFrame()

    if oos.empty:
        overall_metrics = {
            "net_return_pct": 0.0,
            "max_drawdown_pct": 0.0,
            "sharpe": 0.0,
            "trades": 0,
            "win_rate": 0.0,
            "edge_bps_per_trade": 0.0,
            "avg_total_cost_bps": 0.0,
            "avg_fill_ratio": 0.0,
        }
    else:
        strategy_ret = oos["strategy_ret"].fillna(0.0)
        eq = (1.0 + strategy_ret).cumprod()
        drawdown = (eq / eq.cummax() - 1.0).min()
        trades = int(sum(row["test_metrics"]["trades"] for row in window_rows))
        active_rows = int((strategy_ret != 0).sum())
        total_return = float(eq.iloc[-1] - 1.0)
        trade_rows = int(oos["trade_flag"].sum()) if "trade_flag" in oos else 0
        trade_cost_denominator = max(1, trade_rows if trade_rows > 0 else trades)
        trade_fill_mask = oos["trade_flag"] > 0 if "trade_flag" in oos else pd.Series(False, index=oos.index)
        overall_metrics = {
            "net_return_pct": total_return * 100.0,
            "max_drawdown_pct": float(drawdown * 100.0),
            "sharpe": float((strategy_ret.mean() / (strategy_ret.std() + 1e-12)) * np.sqrt(60 * 24 * 365)),
            "trades": trades,
            "win_rate": float((strategy_ret > 0).sum() / max(1, active_rows)),
            "edge_bps_per_trade": float(total_return * 10000.0 / max(trades, 1)),
            "avg_total_cost_bps": float(oos["total_cost_bps"].sum() / trade_cost_denominator),
            "avg_fill_ratio": float(
                oos.loc[trade_fill_mask, "fill_ratio"].mean() if trade_rows > 0 else oos["fill_ratio"].mean()
            ),
        }

    overall_metrics["window_count"] = len(window_rows)
    overall_metrics["positive_windows"] = sum(1 for row in window_rows if row["test_metrics"]["net_return_pct"] > 0)
    overall_metrics["positive_window_ratio"] = (
        overall_metrics["positive_windows"] / max(1, overall_metrics["window_count"])
    )
    overall_metrics["active_windows"] = sum(1 for row in window_rows if row["test_metrics"]["trades"] > 0)
    overall_metrics["positive_active_windows"] = sum(
        1 for row in window_rows if row["test_metrics"]["trades"] > 0 and row["test_metrics"]["net_return_pct"] > 0
    )
    overall_metrics["positive_active_window_ratio"] = (
        overall_metrics["positive_active_windows"] / max(1, overall_metrics["active_windows"])
    )
    return overall_metrics, window_rows, oos


def acceptance(overall_metrics: dict, sensitivity_rows: Sequence[dict], family: str) -> tuple[str, list[str]]:
    reasons = []
    if family == "tail_short_v2":
        if overall_metrics["window_count"] < 4:
            reasons.append("fewer than 4 walk-forward windows")
        if overall_metrics["active_windows"] < 4:
            reasons.append("fewer than 4 active walk-forward windows")
        if overall_metrics["trades"] < 8:
            reasons.append("fewer than 8 aggregate OOS trades")
        if overall_metrics["net_return_pct"] <= 0:
            reasons.append("non-positive aggregate OOS return")
        if overall_metrics["edge_bps_per_trade"] <= 0:
            reasons.append("non-positive aggregate edge per trade")
        if overall_metrics["positive_active_window_ratio"] < 0.60:
            reasons.append("less than 60% positive active OOS windows")
        if overall_metrics["max_drawdown_pct"] < -8.0:
            reasons.append("aggregate OOS drawdown worse than -8%")
    else:
        if overall_metrics["window_count"] < 3:
            reasons.append("fewer than 3 walk-forward windows")
        if overall_metrics["net_return_pct"] <= 0:
            reasons.append("non-positive aggregate OOS return")
        if overall_metrics["edge_bps_per_trade"] <= 0:
            reasons.append("non-positive aggregate edge per trade")
        if overall_metrics["positive_window_ratio"] < 0.55:
            reasons.append("less than 55% positive OOS windows")
        if overall_metrics["max_drawdown_pct"] < -8.0:
            reasons.append("aggregate OOS drawdown worse than -8%")

    worst_sensitivity = min((row["metrics"]["edge_bps_per_trade"] for row in sensitivity_rows), default=0.0)
    if worst_sensitivity < -0.5:
        reasons.append("sensitivity sweeps break edge too aggressively")

    return ("provisional_pass" if not reasons else "rejected"), reasons


def scenario_rows() -> list[CostScenario]:
    return [
        CostScenario(name="base", note="Baseline execution realism"),
        CostScenario(name="fee_plus_2bps", fee_shift_bps=2.0, note="Add 2 bps to maker/taker fees"),
        CostScenario(name="slippage_plus_5bps", slippage_shift_bps=5.0, note="Add 5 bps slippage stress"),
        CostScenario(name="latency_plus_120ms", latency_ms_shift=120.0, note="Increase decision-to-submit latency by 120ms"),
        CostScenario(name="impact_x135", impact_mult=1.35, note="Increase impact by 35%"),
    ]


def persist_symbol_result(
    conn,
    strategy_name: str,
    symbol: str,
    family: str,
    fixed_param_label: str | None,
    canonical_exchange: str,
    exchange_aliases: Sequence[str],
    lookback: str,
    overall_metrics: dict,
    window_rows: Sequence[dict],
    oos_frame: pd.DataFrame,
    sensitivity_rows: Sequence[dict],
    proof_status: str,
    proof_reasons: Sequence[str],
    carry_overlay_enabled: bool,
) -> None:
    start_time = window_rows[0]["test_start"]
    end_time = window_rows[-1]["test_end"]
    metrics_json = json.dumps(
        {
            "family": family,
            "fixed_param_label": fixed_param_label,
            "canonical_exchange": canonical_exchange,
            "exchange_aliases": list(exchange_aliases),
            "lookback": lookback,
            "proof_status": proof_status,
            "proof_reasons": list(proof_reasons),
            "positive_windows": overall_metrics["positive_windows"],
            "window_count": overall_metrics["window_count"],
            "positive_window_ratio": overall_metrics["positive_window_ratio"],
            "active_windows": overall_metrics.get("active_windows", 0),
            "positive_active_windows": overall_metrics.get("positive_active_windows", 0),
            "positive_active_window_ratio": overall_metrics.get("positive_active_window_ratio", 0.0),
            "selected_parameters": dict(Counter(row["params"].label for row in window_rows)),
            "regime_summary": regime_summary(oos_frame),
            "sensitivity": [
                {
                    "scenario": row["scenario"].name,
                    "metrics": row["metrics"],
                    "note": row["scenario"].note,
                }
                for row in sensitivity_rows
            ],
            "carry_overlay_enabled": carry_overlay_enabled,
        },
        default=_json_default,
    )

    with conn.cursor() as cur:
        cur.execute(
            "DELETE FROM strategy_backtest_runs WHERE strategy_name = %s AND symbol = %s AND timeframe = %s",
            (strategy_name, symbol, "1m_walkforward_oos"),
        )
        cur.execute(
            """
            INSERT INTO strategy_backtest_runs (
                strategy_name, symbol, timeframe, start_time, end_time,
                trades, win_rate, net_return_pct, max_drawdown_pct, sharpe, notes, metrics
            ) VALUES (
                %s, %s, %s, %s, %s,
                %s, %s, %s, %s, %s, %s, CAST(%s AS jsonb)
            )
            """,
            (
                strategy_name,
                symbol,
                "1m_walkforward_oos",
                start_time,
                end_time,
                overall_metrics["trades"],
                overall_metrics["win_rate"],
                overall_metrics["net_return_pct"],
                overall_metrics["max_drawdown_pct"],
                overall_metrics["sharpe"],
                "Walk-forward OOS aggregate using canonicalized Hyperliquid history with funding-aware carry overlay when available",
                metrics_json,
            ),
        )

        cur.execute("DELETE FROM strategy_walkforward_runs WHERE strategy_name = %s AND symbol = %s", (strategy_name, symbol))
        for row in window_rows:
            cur.execute(
                """
                INSERT INTO strategy_walkforward_runs (
                    strategy_name, symbol,
                    train_start, train_end, test_start, test_end,
                    net_return_pct, sharpe, max_drawdown_pct, win_rate, trades, metrics
                ) VALUES (
                    %s, %s,
                    %s, %s, %s, %s,
                    %s, %s, %s, %s, %s, CAST(%s AS jsonb)
                )
                """,
                (
                    strategy_name,
                    symbol,
                    row["train_start"],
                    row["train_end"],
                    row["test_start"],
                    row["test_end"],
                    row["test_metrics"]["net_return_pct"],
                    row["test_metrics"]["sharpe"],
                    row["test_metrics"]["max_drawdown_pct"],
                    row["test_metrics"]["win_rate"],
                    row["test_metrics"]["trades"],
                    json.dumps(
                        {
                            "selected_params": row["params"].label,
                            "train_metrics": row["train_metrics"],
                            "test_metrics": row["test_metrics"],
                        },
                        default=_json_default,
                    ),
                ),
            )

        cur.execute("DELETE FROM strategy_sensitivity_sweeps WHERE strategy_name = %s AND symbol = %s", (strategy_name, symbol))
        for row in sensitivity_rows:
            cur.execute(
                """
                INSERT INTO strategy_sensitivity_sweeps (
                    strategy_name, symbol, parameter_name, parameter_value,
                    net_return_pct, sharpe, max_drawdown_pct, trades, metrics
                ) VALUES (
                    %s, %s, %s, %s,
                    %s, %s, %s, %s, CAST(%s AS jsonb)
                )
                """,
                (
                    strategy_name,
                    symbol,
                    row["scenario"].name,
                    row["scenario"].note or row["scenario"].name,
                    row["metrics"]["net_return_pct"],
                    row["metrics"]["sharpe"],
                    row["metrics"]["max_drawdown_pct"],
                    row["metrics"]["trades"],
                    json.dumps(
                        {
                            "scenario": row["scenario"].name,
                            "note": row["scenario"].note,
                            "metrics": row["metrics"],
                        },
                        default=_json_default,
                    ),
                ),
            )
    conn.commit()


def _json_default(value):
    if isinstance(value, pd.Timestamp):
        return value.isoformat()
    if isinstance(value, np.generic):
        return value.item()
    return str(value)


def run_symbol(
    conn,
    args: argparse.Namespace,
    symbol: str,
    exchange_aliases: Sequence[str],
    param_grid: Sequence[StrategyParams],
) -> dict:
    raw = load_market_frame(
        conn,
        exchange_aliases=exchange_aliases,
        symbol=symbol,
        lookback=args.lookback,
        use_trade_flow=args.use_trade_flow,
    )
    frame = engineer_features(raw)
    print(f"[alpha-proof] {symbol}: loaded {len(frame)} bars from aliases={exchange_aliases}")
    if len(frame) < args.min_bars:
        return {
            "symbol": symbol,
            "status": "insufficient_data",
            "bars": int(len(frame)),
            "exchange_aliases": list(exchange_aliases),
        }

    train_bars = args.train_hours * 60
    test_bars = args.test_hours * 60
    step_bars = args.step_hours * 60
    windows = build_windows(frame, train_bars=train_bars, test_bars=test_bars, step_bars=step_bars)
    if len(windows) < 3:
        return {
            "symbol": symbol,
            "status": "insufficient_windows",
            "bars": int(len(frame)),
            "windows": len(windows),
            "exchange_aliases": list(exchange_aliases),
        }

    selected_windows = select_walkforward_params(frame, param_grid=param_grid, windows=windows, notional_usd=args.notional_usd)
    baseline = scenario_rows()[0]
    overall_metrics, window_rows, oos_frame = evaluate_selected_windows(
        selected_windows,
        notional_usd=args.notional_usd,
        scenario=baseline,
    )

    sensitivity = []
    for scenario in scenario_rows():
        metrics, _, _ = evaluate_selected_windows(selected_windows, notional_usd=args.notional_usd, scenario=scenario)
        sensitivity.append({"scenario": scenario, "metrics": metrics})

    proof_status, proof_reasons = acceptance(overall_metrics, sensitivity, family=args.family)
    carry_overlay_enabled = bool(frame["funding_rate"].abs().sum() > 0)
    strategy_name = f"{args.strategy_prefix}_{symbol.lower()}"
    summary = {
        "symbol": symbol,
        "strategy_name": strategy_name,
        "family": args.family,
        "fixed_param_label": args.fixed_param_label,
        "status": proof_status,
        "bars": int(len(frame)),
        "windows": overall_metrics["window_count"],
        "positive_windows": overall_metrics["positive_windows"],
        "active_windows": overall_metrics.get("active_windows", 0),
        "positive_active_windows": overall_metrics.get("positive_active_windows", 0),
        "trades": overall_metrics["trades"],
        "net_return_pct": overall_metrics["net_return_pct"],
        "edge_bps_per_trade": overall_metrics["edge_bps_per_trade"],
        "max_drawdown_pct": overall_metrics["max_drawdown_pct"],
        "sharpe": overall_metrics["sharpe"],
        "avg_total_cost_bps": overall_metrics["avg_total_cost_bps"],
        "avg_fill_ratio": overall_metrics["avg_fill_ratio"],
        "proof_reasons": proof_reasons,
        "exchange_aliases": list(exchange_aliases),
        "carry_overlay_enabled": carry_overlay_enabled,
        "best_selected_params": Counter(row["params"].label for row in window_rows).most_common(3),
        "sensitivity": {
            row["scenario"].name: {
                "net_return_pct": row["metrics"]["net_return_pct"],
                "edge_bps_per_trade": row["metrics"]["edge_bps_per_trade"],
                "max_drawdown_pct": row["metrics"]["max_drawdown_pct"],
            }
            for row in sensitivity
        },
    }

    if args.persist:
        persist_symbol_result(
            conn,
            strategy_name=strategy_name,
            symbol=symbol,
            family=args.family,
            fixed_param_label=args.fixed_param_label,
            canonical_exchange=args.exchange,
            exchange_aliases=exchange_aliases,
            lookback=args.lookback,
            overall_metrics=overall_metrics,
            window_rows=window_rows,
            oos_frame=oos_frame,
            sensitivity_rows=sensitivity,
            proof_status=proof_status,
            proof_reasons=proof_reasons,
            carry_overlay_enabled=carry_overlay_enabled,
        )
        print(f"[alpha-proof] {symbol}: persisted strategy tables for {strategy_name}")

    return summary


def main() -> None:
    args = parse_args()
    symbols = [symbol.strip().upper() for symbol in args.symbols.split(",") if symbol.strip()]
    exchange_aliases = resolve_exchange_aliases(args.exchange)
    param_grid = build_param_grid(args.family, fixed_param_label=args.fixed_param_label)
    print(
        f"[alpha-proof] family={args.family} exchange={args.exchange} aliases={exchange_aliases} symbols={symbols} "
        f"lookback={args.lookback} persist={args.persist} use_trade_flow={args.use_trade_flow} "
        f"fixed_param_label={args.fixed_param_label or 'none'}"
    )
    conn = connect(args)
    try:
        summaries = [run_symbol(conn, args=args, symbol=symbol, exchange_aliases=exchange_aliases, param_grid=param_grid) for symbol in symbols]
    finally:
        conn.close()

    ranked = sorted(
        summaries,
        key=lambda row: (row.get("status") == "provisional_pass", row.get("edge_bps_per_trade", float("-inf"))),
        reverse=True,
    )
    print(json.dumps(ranked, indent=2, default=_json_default))


if __name__ == "__main__":
    main()
