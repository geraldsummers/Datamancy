# Grafana Trading Queries

This repository ships provisioned trading dashboards under:

- `stack.config/grafana/provisioning/dashboards/trading-execution.json`
- `stack.config/grafana/provisioning/dashboards/trading-drift.json`
- `stack.config/grafana/provisioning/dashboards/trading-alpha.json`

The queries are centered on these tables and metric families:

- `strategy_latency_metrics`
- `strategy_execution_costs`
- `strategy_live_backtest_drift`
- `tx_gateway_trading_*`

Trading dashboards also assume two normalization rules:

- Hyperliquid mainnet charts stitch legacy `hyperliquid` rows with canonical `hyperliquid_mainnet` rows.
- Execution/drift dashboard strategy selectors query persisted strategy ids so notebook variants appear without editing dashboard JSON.

## Execution Panels

Typical SQL patterns used in Grafana execution panels:

```sql
SELECT time_bucket('5 minutes', observed_at) AS "time", AVG(decision_latency_ms) AS "Decision"
FROM strategy_latency_metrics
WHERE $__timeFilter(observed_at)
  AND strategy_name = '${execution_strategy}'
GROUP BY 1
ORDER BY 1
```

```sql
SELECT time_bucket('5 minutes', observed_at) AS "time", AVG(total_cost_bps) AS "Total Cost"
FROM strategy_execution_costs
WHERE $__timeFilter(observed_at)
  AND strategy_name = '${execution_strategy}'
GROUP BY 1
ORDER BY 1
```

## Drift Panels

Typical SQL patterns used in Grafana drift panels:

```sql
SELECT time_bucket('5 minutes', observed_at) AS "time", AVG(drift_score) AS "Drift Score"
FROM strategy_live_backtest_drift
WHERE $__timeFilter(observed_at)
  AND strategy_name = '${drift_strategy}'
GROUP BY 1
ORDER BY 1
```

## Prometheus Panels and Alerts

Prometheus alerts and panels rely on:

- `tx_gateway_trading_slippage_drift_bps`
- `tx_gateway_trading_fill_quality_decay_bps`
- `tx_gateway_trading_latency_drift_ms`
- `tx_gateway_trading_drift_score`
- `tx_gateway_trading_submit_to_fill_ms`
- `tx_gateway_trading_total_cost_bps`

Alert rules are provisioned in:

- `stack.config/prometheus/rules/health-alerts.yml`
