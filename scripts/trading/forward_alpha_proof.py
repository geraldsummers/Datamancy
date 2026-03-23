#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
from statistics import mean
from typing import Sequence

import numpy as np
import pandas as pd

from alpha_proof import (
    _env_flag,
    _json_default,
    build_tail_short_score,
    connect,
    depth_notional_usd,
    engineer_features,
    fit_strategy_state,
    load_market_frame,
    parse_strategy_label,
    resolve_exchange_aliases,
    split_contiguous_segments,
    simulate,
    spread_pct_to_bps,
    validate_strategy_name_family,
    CostScenario,
    StrategyParams,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run a recent forward-paper alpha proof using the same fixed strategy label as backtest proof"
    )
    parser.add_argument("--exchange", default=os.getenv("FORWARD_ALPHA_PROOF_EXCHANGE", "hyperliquid_mainnet"))
    parser.add_argument("--family", default=os.getenv("FORWARD_ALPHA_PROOF_FAMILY", "tail_short_v2"))
    parser.add_argument("--fixed-param-label", default=os.getenv("FORWARD_ALPHA_PROOF_FIXED_PARAM_LABEL"))
    parser.add_argument("--symbols", default=os.getenv("FORWARD_ALPHA_PROOF_SYMBOLS", "SOL"))
    parser.add_argument("--lookback", default=os.getenv("FORWARD_ALPHA_PROOF_LOOKBACK"))
    parser.add_argument(
        "--calibration-hours",
        type=int,
        default=int(os.getenv("FORWARD_ALPHA_PROOF_CALIBRATION_HOURS", "72")),
    )
    parser.add_argument(
        "--forward-hours",
        type=int,
        default=int(os.getenv("FORWARD_ALPHA_PROOF_FORWARD_HOURS", "24")),
    )
    parser.add_argument(
        "--lookback-buffer-hours",
        type=int,
        default=int(os.getenv("FORWARD_ALPHA_PROOF_LOOKBACK_BUFFER_HOURS", "4")),
    )
    parser.add_argument(
        "--max-gap-minutes",
        type=float,
        default=float(os.getenv("FORWARD_ALPHA_PROOF_MAX_GAP_MINUTES", "3")),
    )
    parser.add_argument(
        "--max-staleness-minutes",
        type=float,
        default=float(os.getenv("FORWARD_ALPHA_PROOF_MAX_STALENESS_MINUTES", "3")),
    )
    parser.add_argument(
        "--notional-usd",
        type=float,
        default=float(os.getenv("FORWARD_ALPHA_PROOF_NOTIONAL_USD", "7500")),
    )
    parser.add_argument(
        "--strategy-prefix",
        default=os.getenv("FORWARD_ALPHA_PROOF_STRATEGY_PREFIX", "alpha_proof_tail_short_v2_fixed"),
    )
    parser.add_argument("--strategy-name", default=os.getenv("FORWARD_ALPHA_PROOF_STRATEGY_NAME"))
    parser.add_argument(
        "--allow-strategy-name-mismatch",
        dest="allow_strategy_name_mismatch",
        action="store_true",
        default=_env_flag("FORWARD_ALPHA_PROOF_ALLOW_STRATEGY_NAME_MISMATCH", False),
        help="Allow persisted strategy names that do not encode the requested family",
    )
    parser.add_argument("--no-allow-strategy-name-mismatch", dest="allow_strategy_name_mismatch", action="store_false")
    parser.add_argument("--execution-exchange", default=os.getenv("FORWARD_ALPHA_PROOF_EXECUTION_EXCHANGE"))
    parser.add_argument(
        "--persist",
        dest="persist",
        action="store_true",
        default=_env_flag("FORWARD_ALPHA_PROOF_PERSIST", True),
    )
    parser.add_argument("--no-persist", dest="persist", action="store_false")
    parser.add_argument(
        "--use-trade-flow",
        dest="use_trade_flow",
        action="store_true",
        default=_env_flag("FORWARD_ALPHA_PROOF_USE_TRADE_FLOW", True),
    )
    parser.add_argument("--no-trade-flow", dest="use_trade_flow", action="store_false")
    parser.add_argument(
        "--latency-ms-base",
        type=float,
        default=float(os.getenv("FORWARD_ALPHA_PROOF_LATENCY_MS_BASE", "95")),
    )
    parser.add_argument(
        "--latency-ms-jitter",
        type=float,
        default=float(os.getenv("FORWARD_ALPHA_PROOF_LATENCY_MS_JITTER", "40")),
    )
    parser.add_argument(
        "--min-forward-trades",
        type=int,
        default=int(os.getenv("FORWARD_ALPHA_PROOF_MIN_FORWARD_TRADES", "3")),
    )
    parser.add_argument("--rng-seed", type=int, default=int(os.getenv("FORWARD_ALPHA_PROOF_RNG_SEED", "7")))
    parser.add_argument("--db-host", default=os.getenv("POSTGRES_HOST", "postgres"))
    parser.add_argument("--db-port", type=int, default=int(os.getenv("POSTGRES_PORT", "5432")))
    parser.add_argument("--db-name", default=os.getenv("POSTGRES_DB", "datamancy"))
    parser.add_argument("--db-user", default=os.getenv("POSTGRES_USER", "pipeline_user"))
    parser.add_argument("--db-password", default=os.getenv("POSTGRES_PASSWORD", ""))
    args = parser.parse_args()
    if not args.lookback:
        buffered_hours = max(0, args.lookback_buffer_hours)
        total_hours = max(1, args.calibration_hours + args.forward_hours + buffered_hours)
        args.lookback = f"{total_hours} hours"
    return args


def clip(value: float, lower: float, upper: float) -> float:
    return float(np.clip(value, lower, upper))


def resolve_execution_exchange(exchange: str, explicit_exchange: str | None) -> str:
    if explicit_exchange and explicit_exchange.strip():
        return explicit_exchange.strip().lower()
    normalized = exchange.strip().lower()
    if normalized == "hyperliquid_mainnet":
        return "hyperliquid"
    return normalized


def timestamp_age_minutes(value) -> float | None:
    if value is None:
        return None
    ts = pd.Timestamp(value)
    if ts.tzinfo is None:
        ts = ts.tz_localize("UTC")
    else:
        ts = ts.tz_convert("UTC")
    now = pd.Timestamp.now(tz="UTC")
    return float((now - ts).total_seconds() / 60.0)


def load_baseline_backtest_edge(conn, strategy_name: str, symbol: str) -> float | None:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT CASE
                     WHEN trades > 0 THEN (net_return_pct * 100.0) / trades
                     ELSE net_return_pct * 100.0
                   END AS edge_bps
            FROM strategy_backtest_runs
            WHERE strategy_name = %s
              AND symbol = %s
            ORDER BY run_at DESC
            LIMIT 1
            """,
            (strategy_name, symbol),
        )
        row = cur.fetchone()
    return float(row[0]) if row and row[0] is not None else None


def build_tail_short_trade_ledger(
    frame: pd.DataFrame,
    params: StrategyParams,
    notional_usd: float,
    fit_state: dict,
    latency_ms_base: float,
    latency_ms_jitter: float,
    rng: np.random.Generator,
) -> list[dict]:
    df = frame.copy().reset_index(drop=True)
    df["tail_short_score"] = build_tail_short_score(df)

    spread_bps = spread_pct_to_bps(df["spread_pct"])
    depth = (df["bid_depth_10"] + df["ask_depth_10"]).replace(0, np.nan)
    depth_usd = depth_notional_usd(df, depth).fillna(0.0)
    vol_median = df["vol_30m"].rolling(180).median().fillna(df["vol_30m"].median()).replace(0, np.nan)
    vol_norm = (df["vol_30m"] / vol_median).replace([np.inf, -np.inf], np.nan).fillna(1.0)

    entry_threshold = float(fit_state["entry_threshold"])
    spread_cap_bps = float(fit_state["spread_cap_bps"])
    depth_floor_usd = float(fit_state["depth_floor_usd"])

    trades: list[dict] = []
    i = 0
    while i < len(df) - params.hold_bars - 1:
        if (
            df.iloc[i]["tail_short_score"] <= entry_threshold
            and spread_bps.iloc[i] <= spread_cap_bps
            and depth_usd.iloc[i] >= depth_floor_usd
        ):
            entry_i = i + 1
            exit_i = entry_i + params.hold_bars
            if exit_i >= len(df):
                break

            entry_depth_usd = max(float(depth_usd.iloc[entry_i]), 1.0)
            exit_depth_usd = max(float(depth_usd.iloc[exit_i]), 1.0)
            entry_fill = clip(
                0.95
                - float(spread_bps.iloc[entry_i]) / 60.0
                - float(vol_norm.iloc[entry_i]) * 0.05
                - (notional_usd / entry_depth_usd) * 4.0
                - latency_ms_base / 10000.0,
                0.25,
                1.0,
            )

            entry_spread_half = float(spread_bps.iloc[entry_i]) / 2.0
            exit_spread_half = float(spread_bps.iloc[exit_i]) / 2.0
            entry_impact = clip(0.2 + (notional_usd / entry_depth_usd) * 12.0, 0.2, 10.0)
            exit_impact = clip(0.2 + (notional_usd / exit_depth_usd) * 12.0, 0.2, 10.0)
            entry_slippage = clip(0.4 + entry_spread_half * 0.20 + float(vol_norm.iloc[entry_i]) * 0.45, 0.3, 8.0)
            exit_slippage = clip(0.6 + exit_spread_half * 0.25 + float(vol_norm.iloc[exit_i]) * 0.55, 0.3, 10.0)
            fee_bps = 5.0
            spread_cost_bps = entry_spread_half + exit_spread_half
            slippage_bps = entry_slippage + exit_slippage
            impact_bps = entry_impact + exit_impact
            total_cost_bps = fee_bps + spread_cost_bps + slippage_bps + impact_bps

            carry_bps = float((-df["carry_overlay"].iloc[entry_i:exit_i].fillna(0.0).sum()) * 10000.0)
            gross_return = float(
                (-entry_fill * (df["ret_fwd_1m"].iloc[entry_i:exit_i].fillna(0.0) + df["carry_overlay"].iloc[entry_i:exit_i].fillna(0.0))).sum()
            )
            net_return = gross_return - (entry_fill * total_cost_bps / 10000.0)

            trigger_score = float(df.iloc[i]["tail_short_score"])
            decision_latency_ms = clip(6.0 + abs(trigger_score) * 6.0 + rng.normal(0.0, 1.5), 4.0, 45.0)
            submit_to_ack_ms = clip(latency_ms_base + rng.normal(0.0, latency_ms_jitter), 20.0, 600.0)
            submit_to_fill_ms = clip(submit_to_ack_ms + 20.0 + (1.0 - entry_fill) * 220.0, 30.0, 1500.0)
            p50_roundtrip_ms = clip(submit_to_ack_ms + 12.0, 20.0, 800.0)
            p95_roundtrip_ms = clip(submit_to_ack_ms * 2.0, 30.0, 1500.0)
            p99_roundtrip_ms = clip(submit_to_ack_ms * 3.0, 40.0, 2500.0)
            jitter_ms = clip(abs(rng.normal(latency_ms_jitter / 5.0, max(latency_ms_jitter / 8.0, 1.0))), 1.0, 80.0)

            trades.append(
                {
                    "observed_at": pd.to_datetime(df.iloc[entry_i]["time"]).to_pydatetime(),
                    "exit_time": pd.to_datetime(df.iloc[exit_i]["time"]).to_pydatetime(),
                    "side": "SELL",
                    "signal_score": trigger_score,
                    "fill_ratio": entry_fill,
                    "fee_bps": fee_bps,
                    "fee_tier": "retail",
                    "fee_tier_adjustment_bps": 0.0,
                    "maker_fee_bps": 1.0,
                    "taker_fee_bps": 4.0,
                    "spread_cost_bps": spread_cost_bps,
                    "slippage_bps": slippage_bps,
                    "impact_bps": impact_bps,
                    "adverse_selection_bps": 0.0,
                    "funding_drift_bps": carry_bps,
                    "basis_drift_bps": 0.0,
                    "total_cost_bps": total_cost_bps,
                    "alpha_edge_bps": gross_return * 10000.0,
                    "edge_after_cost_bps": net_return * 10000.0,
                    "estimated_fee_usd": notional_usd * abs(fee_bps) / 10000.0,
                    "estimated_cost_usd": notional_usd * abs(total_cost_bps) / 10000.0,
                    "decision_latency_ms": decision_latency_ms,
                    "submit_to_ack_ms": submit_to_ack_ms,
                    "submit_to_fill_ms": submit_to_fill_ms,
                    "p50_roundtrip_ms": p50_roundtrip_ms,
                    "p95_roundtrip_ms": p95_roundtrip_ms,
                    "p99_roundtrip_ms": p99_roundtrip_ms,
                    "jitter_ms": jitter_ms,
                    "entry_price": float(df.iloc[entry_i]["close"]),
                    "exit_price": float(df.iloc[exit_i]["close"]),
                    "entry_depth_usd": entry_depth_usd,
                    "exit_depth_usd": exit_depth_usd,
                    "hold_bars": params.hold_bars,
                    "notional_usd": notional_usd,
                }
            )
            i = exit_i + 1
            continue
        i += 1
    return trades


def summarize_forward(
    metrics: dict,
    trades: Sequence[dict],
    strategy_name: str,
    symbol: str,
    family: str,
    fixed_param_label: str,
    calibration_frame: pd.DataFrame,
    forward_frame: pd.DataFrame,
    baseline_backtest_edge_bps: float | None,
    min_forward_trades: int,
) -> dict:
    avg_trade_edge = float(mean(trade["edge_after_cost_bps"] for trade in trades)) if trades else 0.0
    avg_trade_cost = float(mean(trade["total_cost_bps"] for trade in trades)) if trades else 0.0
    avg_fill_ratio = float(mean(trade["fill_ratio"] for trade in trades)) if trades else 0.0
    avg_submit_fill = float(mean(trade["submit_to_fill_ms"] for trade in trades)) if trades else 0.0
    trade_count = len(trades)
    positive_trades = sum(1 for trade in trades if trade["edge_after_cost_bps"] > 0)
    positive_return = metrics["net_return_pct"] > 0
    positive_avg_trade_edge = avg_trade_edge > 0
    sample_sufficient = trade_count >= min_forward_trades

    reasons = []
    if not sample_sufficient:
        reasons.append(f"fewer than {min_forward_trades} forward trades")
    if not positive_return:
        reasons.append("non-positive forward return")
    if not positive_avg_trade_edge:
        reasons.append("non-positive average trade edge after cost")

    if sample_sufficient and positive_return and positive_avg_trade_edge:
        status = "forward_pass"
        reasons = []
    elif not sample_sufficient:
        status = "forward_inconclusive"
    else:
        status = "forward_rejected"

    return {
        "strategy_name": strategy_name,
        "symbol": symbol,
        "family": family,
        "fixed_param_label": fixed_param_label,
        "status": status,
        "reasons": reasons,
        "bars": int(len(forward_frame)),
        "calibration_bars": int(len(calibration_frame)),
        "forward_start": forward_frame.iloc[0]["time"] if not forward_frame.empty else None,
        "forward_end": forward_frame.iloc[-1]["time"] if not forward_frame.empty else None,
        "min_forward_trades": min_forward_trades,
        "sample_sufficient": sample_sufficient,
        "positive_return": positive_return,
        "positive_avg_trade_edge": positive_avg_trade_edge,
        "trades": trade_count,
        "positive_trades": positive_trades,
        "net_return_pct": metrics["net_return_pct"],
        "max_drawdown_pct": metrics["max_drawdown_pct"],
        "sharpe": metrics["sharpe"],
        "avg_total_cost_bps": avg_trade_cost,
        "avg_edge_after_cost_bps": avg_trade_edge,
        "avg_fill_ratio": avg_fill_ratio,
        "avg_submit_to_fill_ms": avg_submit_fill,
        "baseline_backtest_edge_bps": baseline_backtest_edge_bps,
        "edge_delta_vs_backtest_bps": (
            avg_trade_edge - baseline_backtest_edge_bps if baseline_backtest_edge_bps is not None else None
        ),
    }


def normalize_timestamp(value) -> pd.Timestamp:
    ts = pd.Timestamp(value)
    if ts.tzinfo is None:
        return ts.tz_localize("UTC")
    return ts.tz_convert("UTC")


def persist_forward_summary(
    conn,
    strategy_name: str,
    symbol: str,
    execution_exchange: str,
    family: str,
    fixed_param_label: str,
    market_data_exchange: str,
    calibration_hours: int,
    forward_hours: int,
    baseline_backtest_edge_bps: float | None,
    summary: dict,
    trades_persisted: bool,
) -> None:
    observed_at_raw = summary.get("forward_end")
    if observed_at_raw is None:
        observed_at_raw = (summary.get("contiguity") or {}).get("latest_segment_end")
    if observed_at_raw is None:
        observed_at_raw = pd.Timestamp.now(tz="UTC")

    observed_at = normalize_timestamp(observed_at_raw)
    if trades_persisted:
        observed_at = observed_at + pd.Timedelta(microseconds=1)

    live_edge_bps = summary.get("avg_edge_after_cost_bps")
    if live_edge_bps is not None:
        live_edge_bps = float(live_edge_bps)

    drift_score = None
    if baseline_backtest_edge_bps is not None:
        comparison_edge = live_edge_bps if live_edge_bps is not None else 0.0
        drift_score = max(0.0, baseline_backtest_edge_bps - comparison_edge)

    metadata_json = json.dumps(
        {
            "source": "forward-alpha-proof",
            "recordType": "summary",
            "family": family,
            "fixed_param_label": fixed_param_label,
            "marketDataMode": "mainnet_live",
            "executionMode": "forward_paper",
            "marketDataExchange": market_data_exchange,
            "executionExchange": execution_exchange,
            "calibrationHours": calibration_hours,
            "forwardHours": forward_hours,
            "status": summary.get("status"),
            "reasons": summary.get("reasons", []),
            "bars": summary.get("bars"),
            "requiredBars": summary.get("required_bars"),
            "calibrationBars": summary.get("calibration_bars"),
            "minForwardTrades": summary.get("min_forward_trades"),
            "sampleSufficient": summary.get("sample_sufficient"),
            "positiveReturn": summary.get("positive_return"),
            "positiveAvgTradeEdge": summary.get("positive_avg_trade_edge"),
            "forwardStart": summary.get("forward_start"),
            "forwardEnd": summary.get("forward_end"),
            "trades": summary.get("trades"),
            "positiveTrades": summary.get("positive_trades"),
            "netReturnPct": summary.get("net_return_pct"),
            "maxDrawdownPct": summary.get("max_drawdown_pct"),
            "sharpe": summary.get("sharpe"),
            "avgTotalCostBps": summary.get("avg_total_cost_bps"),
            "avgEdgeAfterCostBps": summary.get("avg_edge_after_cost_bps"),
            "avgFillRatio": summary.get("avg_fill_ratio"),
            "avgSubmitToFillMs": summary.get("avg_submit_to_fill_ms"),
            "baselineBacktestEdgeBps": baseline_backtest_edge_bps,
            "edgeDeltaVsBacktestBps": summary.get("edge_delta_vs_backtest_bps"),
            "latestBarAgeMinutes": summary.get("latest_bar_age_minutes"),
            "contiguity": summary.get("contiguity"),
            "fitState": summary.get("fit_state"),
            "tradesPersisted": trades_persisted,
        },
        default=_json_default,
    )

    with conn.cursor() as cur:
        cur.execute(
            """
            DELETE FROM strategy_live_backtest_drift
            WHERE strategy_name = %s
              AND symbol = %s
              AND metadata ->> 'source' = 'forward-alpha-proof'
              AND COALESCE(metadata ->> 'recordType', '') = 'summary'
            """,
            (strategy_name, symbol),
        )
        cur.execute(
            """
            INSERT INTO strategy_live_backtest_drift (
                observed_at, strategy_name, symbol,
                live_edge_bps, backtest_edge_bps,
                fill_quality_delta_bps, slippage_drift_bps,
                latency_drift_ms, drift_score, metadata
            ) VALUES (
                %s, %s, %s,
                %s, %s,
                %s, %s,
                %s, %s, CAST(%s AS jsonb)
            )
            """,
            (
                observed_at.to_pydatetime(),
                strategy_name,
                symbol,
                live_edge_bps,
                baseline_backtest_edge_bps,
                None,
                None,
                None,
                drift_score,
                metadata_json,
            ),
        )
    conn.commit()


def persist_forward_records(
    conn,
    strategy_name: str,
    symbol: str,
    execution_exchange: str,
    family: str,
    fixed_param_label: str,
    market_data_exchange: str,
    calibration_hours: int,
    forward_hours: int,
    fit_state: dict,
    trades: Sequence[dict],
    baseline_backtest_edge_bps: float | None,
) -> None:
    if not trades:
        return

    first_observed_at = min(trade["observed_at"] for trade in trades)
    last_observed_at = max(trade["observed_at"] for trade in trades)
    avg_slippage = float(mean(trade["slippage_bps"] for trade in trades))
    avg_fill_ratio = float(mean(trade["fill_ratio"] for trade in trades))
    avg_submit_fill = float(mean(trade["submit_to_fill_ms"] for trade in trades))

    with conn.cursor() as cur:
        cur.execute(
            """
            DELETE FROM strategy_live_backtest_drift
            WHERE strategy_name = %s
              AND symbol = %s
              AND observed_at BETWEEN %s AND %s
            """,
            (strategy_name, symbol, first_observed_at, last_observed_at),
        )
        cur.execute(
            """
            DELETE FROM strategy_execution_costs
            WHERE strategy_name = %s
              AND exchange = %s
              AND symbol = %s
              AND observed_at BETWEEN %s AND %s
            """,
            (strategy_name, execution_exchange, symbol, first_observed_at, last_observed_at),
        )
        cur.execute(
            """
            DELETE FROM strategy_latency_metrics
            WHERE strategy_name = %s
              AND exchange = %s
              AND symbol = %s
              AND observed_at BETWEEN %s AND %s
            """,
            (strategy_name, execution_exchange, symbol, first_observed_at, last_observed_at),
        )

        for index, trade in enumerate(trades, start=1):
            metadata_json = json.dumps(
                {
                    "source": "forward-alpha-proof",
                    "family": family,
                    "fixed_param_label": fixed_param_label,
                    "marketDataMode": "mainnet_live",
                    "executionMode": "forward_paper",
                    "marketDataExchange": market_data_exchange,
                    "executionExchange": execution_exchange,
                    "calibrationHours": calibration_hours,
                    "forwardHours": forward_hours,
                    "recordType": "trade",
                    "tradeIndex": index,
                    "entryTime": trade["observed_at"],
                    "exitTime": trade["exit_time"],
                    "fitState": fit_state,
                    "holdBars": trade["hold_bars"],
                    "signalScore": trade["signal_score"],
                    "entryDepthUsd": trade["entry_depth_usd"],
                    "exitDepthUsd": trade["exit_depth_usd"],
                    "status": "SIMULATED",
                    "feeTier": trade["fee_tier"],
                },
                default=_json_default,
            )

            cur.execute(
                """
                INSERT INTO strategy_latency_metrics (
                    observed_at, strategy_name, exchange, symbol,
                    decision_latency_ms, submit_to_ack_ms, submit_to_fill_ms,
                    p50_roundtrip_ms, p95_roundtrip_ms, p99_roundtrip_ms,
                    jitter_ms, metadata
                ) VALUES (
                    %s, %s, %s, %s,
                    %s, %s, %s,
                    %s, %s, %s,
                    %s, CAST(%s AS jsonb)
                )
                """,
                (
                    trade["observed_at"],
                    strategy_name,
                    execution_exchange,
                    symbol,
                    trade["decision_latency_ms"],
                    trade["submit_to_ack_ms"],
                    trade["submit_to_fill_ms"],
                    trade["p50_roundtrip_ms"],
                    trade["p95_roundtrip_ms"],
                    trade["p99_roundtrip_ms"],
                    trade["jitter_ms"],
                    metadata_json,
                ),
            )
            cur.execute(
                """
                INSERT INTO strategy_execution_costs (
                    observed_at, strategy_name, exchange, symbol, side,
                    fee_bps, fee_tier, fee_tier_adjustment_bps,
                    maker_fee_bps, taker_fee_bps,
                    spread_cost_bps, slippage_bps, impact_bps,
                    adverse_selection_bps, funding_drift_bps, basis_drift_bps,
                    total_cost_bps, edge_after_cost_bps,
                    estimated_fee_usd, estimated_cost_usd, metadata
                ) VALUES (
                    %s, %s, %s, %s, %s,
                    %s, %s, %s,
                    %s, %s,
                    %s, %s, %s,
                    %s, %s, %s,
                    %s, %s,
                    %s, %s, CAST(%s AS jsonb)
                )
                """,
                (
                    trade["observed_at"],
                    strategy_name,
                    execution_exchange,
                    symbol,
                    trade["side"],
                    trade["fee_bps"],
                    trade["fee_tier"],
                    trade["fee_tier_adjustment_bps"],
                    trade["maker_fee_bps"],
                    trade["taker_fee_bps"],
                    trade["spread_cost_bps"],
                    trade["slippage_bps"],
                    trade["impact_bps"],
                    trade["adverse_selection_bps"],
                    trade["funding_drift_bps"],
                    trade["basis_drift_bps"],
                    trade["total_cost_bps"],
                    trade["edge_after_cost_bps"],
                    trade["estimated_fee_usd"],
                    trade["estimated_cost_usd"],
                    metadata_json,
                ),
            )

            slippage_drift_bps = trade["slippage_bps"] - avg_slippage
            fill_quality_delta_bps = (avg_fill_ratio - trade["fill_ratio"]) * 10000.0
            latency_drift_ms = trade["submit_to_fill_ms"] - avg_submit_fill
            edge_decay_bps = (
                max(0.0, baseline_backtest_edge_bps - trade["edge_after_cost_bps"])
                if baseline_backtest_edge_bps is not None
                else 0.0
            )
            drift_score = (
                max(0.0, slippage_drift_bps)
                + max(0.0, fill_quality_delta_bps)
                + max(0.0, latency_drift_ms) / 10.0
                + edge_decay_bps
            )

            cur.execute(
                """
                INSERT INTO strategy_live_backtest_drift (
                    observed_at, strategy_name, symbol,
                    live_edge_bps, backtest_edge_bps,
                    fill_quality_delta_bps, slippage_drift_bps,
                    latency_drift_ms, drift_score, metadata
                ) VALUES (
                    %s, %s, %s,
                    %s, %s,
                    %s, %s,
                    %s, %s, CAST(%s AS jsonb)
                )
                """,
                (
                    trade["observed_at"],
                    strategy_name,
                    symbol,
                    trade["edge_after_cost_bps"],
                    baseline_backtest_edge_bps,
                    fill_quality_delta_bps,
                    slippage_drift_bps,
                    latency_drift_ms,
                    drift_score,
                    metadata_json,
                ),
            )
    conn.commit()


def run_symbol(
    conn,
    args: argparse.Namespace,
    symbol: str,
    exchange_aliases: Sequence[str],
    params: StrategyParams,
    scenario: CostScenario,
    execution_exchange: str,
    rng: np.random.Generator,
) -> dict:
    raw = load_market_frame(conn, exchange_aliases=exchange_aliases, symbol=symbol, lookback=args.lookback, use_trade_flow=args.use_trade_flow)
    frame = engineer_features(raw)
    strategy_name = args.strategy_name or f"{args.strategy_prefix}_{symbol.lower()}"
    validate_strategy_name_family(
        strategy_name=strategy_name,
        family=args.family,
        allow_mismatch=args.allow_strategy_name_mismatch,
    )
    baseline_backtest_edge_bps = load_baseline_backtest_edge(conn, strategy_name=strategy_name, symbol=symbol)
    segments, contiguity = split_contiguous_segments(frame, max_gap_minutes=args.max_gap_minutes)
    latest_bar_age_minutes = timestamp_age_minutes(frame.iloc[-1]["time"]) if not frame.empty else None
    required_bars = (args.calibration_hours + args.forward_hours) * 60

    def finalize_early(status: str, reasons: list[str], **extra) -> dict:
        summary = {
            "strategy_name": strategy_name,
            "symbol": symbol,
            "family": args.family,
            "fixed_param_label": args.fixed_param_label,
            "status": status,
            "reasons": reasons,
            "baseline_backtest_edge_bps": baseline_backtest_edge_bps,
            "edge_delta_vs_backtest_bps": None,
            "persisted_trade_rows": 0,
            **extra,
        }
        if args.persist:
            persist_forward_summary(
                conn=conn,
                strategy_name=strategy_name,
                symbol=symbol,
                execution_exchange=execution_exchange,
                family=args.family,
                fixed_param_label=args.fixed_param_label,
                market_data_exchange=args.exchange,
                calibration_hours=args.calibration_hours,
                forward_hours=args.forward_hours,
                baseline_backtest_edge_bps=baseline_backtest_edge_bps,
                summary=summary,
                trades_persisted=False,
            )
            summary["persisted_summary_row"] = True
        else:
            summary["persisted_summary_row"] = False
        return summary

    if len(frame) < required_bars:
        return finalize_early(
            "insufficient_data",
            reasons=["fewer than required recent bars in lookback window"],
            bars=int(len(frame)),
            required_bars=required_bars,
            latest_bar_age_minutes=latest_bar_age_minutes,
            contiguity=contiguity,
        )

    if latest_bar_age_minutes is None or latest_bar_age_minutes > args.max_staleness_minutes:
        return finalize_early(
            "stale_recent_data",
            reasons=["latest bar age exceeds max staleness allowance"],
            bars=int(len(frame)),
            required_bars=required_bars,
            latest_bar_age_minutes=latest_bar_age_minutes,
            max_staleness_minutes=args.max_staleness_minutes,
            contiguity=contiguity,
        )

    latest_segment = segments[-1] if segments else frame
    if len(latest_segment) < required_bars:
        return finalize_early(
            "insufficient_contiguous_recent_data",
            reasons=["latest contiguous segment shorter than calibration + forward requirement"],
            bars=int(len(frame)),
            required_bars=required_bars,
            latest_segment_bars=int(len(latest_segment)),
            latest_bar_age_minutes=latest_bar_age_minutes,
            contiguity=contiguity,
        )

    scoped = latest_segment.tail(required_bars).reset_index(drop=True)
    calibration_bars = args.calibration_hours * 60
    forward_bars = args.forward_hours * 60
    calibration = scoped.iloc[:calibration_bars].reset_index(drop=True)
    forward = scoped.iloc[calibration_bars:calibration_bars + forward_bars].reset_index(drop=True)

    fit_state = fit_strategy_state(calibration, params)
    metrics, _ = simulate(forward, params=params, notional_usd=args.notional_usd, scenario=scenario, fit_state=fit_state)
    if params.family != "tail_short_v2":
        raise SystemExit("forward_alpha_proof currently supports forward telemetry persistence for tail_short_v2 only")

    trades = build_tail_short_trade_ledger(
        frame=forward,
        params=params,
        notional_usd=args.notional_usd,
        fit_state=fit_state,
        latency_ms_base=args.latency_ms_base,
        latency_ms_jitter=args.latency_ms_jitter,
        rng=rng,
    )

    summary = summarize_forward(
        metrics=metrics,
        trades=trades,
        strategy_name=strategy_name,
        symbol=symbol,
        family=args.family,
        fixed_param_label=args.fixed_param_label,
        calibration_frame=calibration,
        forward_frame=forward,
        baseline_backtest_edge_bps=baseline_backtest_edge_bps,
        min_forward_trades=args.min_forward_trades,
    )
    summary["fit_state"] = fit_state
    summary["latest_bar_age_minutes"] = latest_bar_age_minutes
    summary["contiguity"] = contiguity

    trades_persisted = False
    if args.persist and trades:
        persist_forward_records(
            conn=conn,
            strategy_name=strategy_name,
            symbol=symbol,
            execution_exchange=execution_exchange,
            family=args.family,
            fixed_param_label=args.fixed_param_label,
            market_data_exchange=args.exchange,
            calibration_hours=args.calibration_hours,
            forward_hours=args.forward_hours,
            fit_state=fit_state,
            trades=trades,
            baseline_backtest_edge_bps=baseline_backtest_edge_bps,
        )
        summary["persisted_trade_rows"] = len(trades)
        trades_persisted = True
    else:
        summary["persisted_trade_rows"] = 0
    if args.persist:
        persist_forward_summary(
            conn=conn,
            strategy_name=strategy_name,
            symbol=symbol,
            execution_exchange=execution_exchange,
            family=args.family,
            fixed_param_label=args.fixed_param_label,
            market_data_exchange=args.exchange,
            calibration_hours=args.calibration_hours,
            forward_hours=args.forward_hours,
            baseline_backtest_edge_bps=baseline_backtest_edge_bps,
            summary=summary,
            trades_persisted=trades_persisted,
        )
        summary["persisted_summary_row"] = True
    else:
        summary["persisted_summary_row"] = False
    return summary


def main() -> None:
    args = parse_args()
    if not args.fixed_param_label:
        raise SystemExit("--fixed-param-label (or FORWARD_ALPHA_PROOF_FIXED_PARAM_LABEL) is required")

    params = parse_strategy_label(args.fixed_param_label)
    if params.family != args.family:
        raise SystemExit(
            f"fixed strategy label family {params.family!r} does not match requested family {args.family!r}"
        )

    exchange_aliases = resolve_exchange_aliases(args.exchange)
    execution_exchange = resolve_execution_exchange(args.exchange, args.execution_exchange)
    symbols = [symbol.strip().upper() for symbol in args.symbols.split(",") if symbol.strip()]
    scenario = CostScenario(name="forward_mainnet_paper")
    rng = np.random.default_rng(args.rng_seed)

    print(
        f"[forward-alpha-proof] family={args.family} exchange={args.exchange} aliases={exchange_aliases} "
        f"execution_exchange={execution_exchange} symbols={symbols} lookback={args.lookback} "
        f"calibration_hours={args.calibration_hours} forward_hours={args.forward_hours} "
        f"persist={args.persist} fixed_param_label={args.fixed_param_label}"
    )

    conn = connect(args)
    try:
        results = [
            run_symbol(
                conn=conn,
                args=args,
                symbol=symbol,
                exchange_aliases=exchange_aliases,
                params=params,
                scenario=scenario,
                execution_exchange=execution_exchange,
                rng=rng,
            )
            for symbol in symbols
        ]
    finally:
        conn.close()

    print(json.dumps(results, indent=2, default=_json_default))


if __name__ == "__main__":
    main()
