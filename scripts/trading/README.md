# Trading Research Scripts

## `alpha_proof.py`

Runs a walk-forward alpha proof against Datamancy market data and persists results into:

- `strategy_backtest_runs`
- `strategy_walkforward_runs`
- `strategy_sensitivity_sweeps`

Current behavior:

- canonicalizes Hyperliquid history across `hyperliquid` and `hyperliquid_mainnet`
- uses `candle_1m` bars plus signed trade flow and order-book depth
- consumes persisted funding rows when present and safely falls back to zero carry when they are absent
- converts top-of-book depth from base units into quote notional before impact modelling
- splits walk-forward windows on data gaps instead of spanning outages with fake continuity
- applies explicit fee/slippage/impact/latency stress scenarios
- supports `--family microstructure_v1` and the lower-turnover `--family tail_short_v2`
- supports `--fixed-param-label` so a dominant walk-forward configuration can be replayed as a pure OOS proof

Remote run example on `latium.local`:

```bash
ssh gerald@latium.local 'cd ~/datamancy && export POSTGRES_PASSWORD=$(docker compose exec -T postgres env </dev/null | sed -n "s/^POSTGRES_PIPELINE_PASSWORD=//p") && docker run --rm --network datamancy_litellm -v "$PWD":/workspace -w /workspace -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB=datamancy -e POSTGRES_USER=pipeline_user -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" datamancy-jupyter-notebook:5.4.3 python scripts/trading/alpha_proof.py --exchange hyperliquid_mainnet --symbols BTC,ETH,SOL --lookback "5 days"'
```

Tail-short proof example on `latium.local`:

```bash
ssh gerald@latium.local 'cd ~/datamancy && export POSTGRES_PASSWORD=$(docker compose exec -T postgres env </dev/null | sed -n "s/^POSTGRES_PIPELINE_PASSWORD=//p") && docker run --rm --network datamancy_litellm -v "$PWD":/workspace -w /workspace -e PYTHONPATH=/workspace -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB=datamancy -e POSTGRES_USER=pipeline_user -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" datamancy-jupyter-notebook:5.4.3 python scripts/trading/alpha_proof.py --family tail_short_v2 --strategy-prefix alpha_proof_tail_short_v2 --exchange hyperliquid_mainnet --symbols SOL --lookback "10 days" --train-hours 16 --test-hours 4 --step-hours 4 --use-trade-flow'
```

Fixed-parameter replay example on `latium.local`:

```bash
ssh gerald@latium.local 'cd ~/datamancy && export POSTGRES_PASSWORD=$(docker compose exec -T postgres env </dev/null | sed -n "s/^POSTGRES_PIPELINE_PASSWORD=//p") && docker run --rm --network datamancy_litellm -v "$PWD":/workspace -w /workspace -e PYTHONPATH=/workspace -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB=datamancy -e POSTGRES_USER=pipeline_user -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" datamancy-jupyter-notebook:5.4.3 python scripts/trading/alpha_proof.py --family tail_short_v2 --fixed-param-label "family=tail_short_v2,entry_q=0.10,hold=30,spread_q=0.75,depth_q=0.30" --strategy-prefix alpha_proof_tail_short_v2_fixed --exchange hyperliquid_mainnet --symbols SOL --lookback "10 days" --train-hours 16 --test-hours 4 --step-hours 4 --use-trade-flow'
```

## `forward_alpha_proof.py`

Runs a recent forward-paper proof using the same fixed strategy label as the historical alpha proof and persists telemetry into:

- `strategy_latency_metrics`
- `strategy_execution_costs`
- `strategy_live_backtest_drift`

Current behavior:

- reuses the fixed strategy label from `alpha_proof.py` so forward and back proofs stay on the same strategy definition
- calibrates thresholds on a recent historical slice, then simulates only the latest forward slice
- defaults the lookback window to `calibration + forward + 4h` so tiny ingestion gaps do not create false insufficient-data failures
- treats low-sample forward slices as `forward_inconclusive` instead of mislabeling them as `forward_rejected`
- refuses stale or non-contiguous recent market-data tails, so a forward pass cannot be claimed on broken ingestion
- persists trade-level latency/cost/drift telemetry for Grafana using the same strategy id as the backtest proof
- currently supports forward telemetry persistence for `tail_short_v2`

Forward-paper proof example on `latium.local`:

```bash
ssh gerald@latium.local 'cd ~/datamancy && export POSTGRES_PASSWORD=$(docker compose exec -T postgres env </dev/null | sed -n "s/^POSTGRES_PIPELINE_PASSWORD=//p") && docker run --rm --network datamancy_litellm -v "$PWD":/workspace -w /workspace -e PYTHONPATH=/workspace -e POSTGRES_HOST=postgres -e POSTGRES_PORT=5432 -e POSTGRES_DB=datamancy -e POSTGRES_USER=pipeline_user -e POSTGRES_PASSWORD="$POSTGRES_PASSWORD" datamancy-jupyter-notebook:5.4.3 python scripts/trading/forward_alpha_proof.py --family tail_short_v2 --fixed-param-label "family=tail_short_v2,entry_q=0.10,hold=30,spread_q=0.75,depth_q=0.30" --strategy-prefix alpha_proof_tail_short_v2_fixed --exchange hyperliquid_mainnet --symbols SOL --lookback "100 hours" --calibration-hours 72 --forward-hours 24 --use-trade-flow'
```
